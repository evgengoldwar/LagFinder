package lagfinder.server;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;

import lagfinder.LagFinder;
import lagfinder.core.Report;
import lagfinder.core.ReportFiles;
import lagfinder.core.ScanMode;
import lagfinder.network.Net;

public final class ServerReports {

    public static final ServerReports INSTANCE = new ServerReports();

    private final ThreadPoolExecutor reader = new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(2),
        task -> {
            Thread thread = new Thread(task, "LagFinder archive reader");
            thread.setDaemon(true);
            return thread;
        });
    private final Map<UUID, Long> requested = new LinkedHashMap<>();
    private final Map<UUID, Transfer> transfers = new HashMap<>();
    private final Map<UUID, Integer> pending = new HashMap<>();
    private final ArrayBlockingQueue<Runnable> requests = new ArrayBlockingQueue<>(32);
    private int generation;

    private static final class Transfer {

        final EntityPlayerMP player;
        final int request;
        final byte[] data;
        final long startedAt = System.currentTimeMillis();
        int index;

        Transfer(EntityPlayerMP player, int request, byte[] data) {
            this.player = player;
            this.request = request;
            this.data = data;
        }
    }

    private ServerReports() {}

    public static File folder() {
        return new File("lagfinder", "reports");
    }

    public boolean enqueue(EntityPlayerMP player, Net.ArchiveRequest request) {
        return requests.offer(() -> request(player, request));
    }

    private void request(EntityPlayerMP player, Net.ArchiveRequest request) {
        if (!ServerScanner.isOnline(player)) {
            return;
        }
        if (!ServerScanner.canScan(player)) {
            reply(player, request.id, Net.ArchiveCatalog.DENIED);
            return;
        }
        if (request.action != Net.ArchiveRequest.LIST && request.action != Net.ArchiveRequest.OPEN
            || request.action == Net.ArchiveRequest.OPEN && ReportFiles.describe(request.name, 1) == null) {
            reply(player, request.id, Net.ArchiveCatalog.FAILED);
            return;
        }
        UUID uuid = player.getUniqueID();
        long now = System.currentTimeMillis();
        Long last = requested.get(uuid);
        if (last != null && now - last < 250 || pending.containsKey(uuid)
            || transfers.containsKey(uuid)
            || pending.size() + transfers.size() >= 2) {
            reply(player, request.id, Net.ArchiveCatalog.BUSY);
            return;
        }
        requested.put(uuid, now);
        while (requested.size() > 128) {
            requested.remove(
                requested.keySet()
                    .iterator()
                    .next());
        }
        int made = generation;
        pending.put(uuid, request.id);
        reader.execute(() -> {
            List<ReportFiles.Saved> saved = new ArrayList<>();
            byte[] data = null;
            int result = Net.ArchiveCatalog.OK;
            try {
                if (request.action == Net.ArchiveRequest.LIST) {
                    saved = ReportFiles.list(folder());
                    saved.removeIf(entry -> entry.side != lagfinder.core.ScanSide.SERVER);
                    if (saved.size() > ReportFiles.KEEP) {
                        saved = new ArrayList<>(saved.subList(0, ReportFiles.KEEP));
                    }
                } else {
                    data = ReportFiles.bytes(folder(), request.name);
                    Report report = Report.read(new ByteArrayInputStream(data));
                    if (report.mode != ScanMode.TPS) {
                        throw new IOException("Archive file is not a server report");
                    }
                }
            } catch (IOException | RuntimeException ex) {
                result = Net.ArchiveCatalog.FAILED;
                LagFinder.LOG.warn("Could not read the shared report archive", ex);
            }
            byte[] bytes = data;
            List<ReportFiles.Saved> entries = saved;
            int status = result;
            ServerScanner.INSTANCE.schedule(() -> {
                if (made != generation) {
                    return;
                }
                pending.remove(uuid);
                if (!ServerScanner.isOnline(player)) {
                    return;
                }
                if (!ServerScanner.canScan(player)) {
                    reply(player, request.id, Net.ArchiveCatalog.DENIED);
                } else if (status != Net.ArchiveCatalog.OK) {
                    reply(player, request.id, status);
                } else if (bytes != null) {
                    transfers.put(uuid, new Transfer(player, request.id, bytes));
                } else {
                    Net.sendTo(new Net.ArchiveCatalog(request.id, status, entries), player);
                }
            });
        });
    }

    private static void reply(EntityPlayerMP player, int id, int status) {
        Net.sendTo(new Net.ArchiveCatalog(id, status, new ArrayList<>()), player);
    }

    public void tick() {
        for (int i = 0; i < 4; i++) {
            Runnable request = requests.poll();
            if (request == null) {
                break;
            }
            request.run();
        }
        for (UUID uuid : new ArrayList<>(transfers.keySet())) {
            Transfer transfer = transfers.get(uuid);
            if (System.currentTimeMillis() - transfer.startedAt > 120_000) {
                transfers.remove(uuid);
                if (ServerScanner.isOnline(transfer.player)) {
                    reply(transfer.player, transfer.request, Net.ArchiveCatalog.FAILED);
                }
                continue;
            }
            if (!ServerScanner.isOnline(transfer.player) || !ServerScanner.canScan(transfer.player)) {
                transfers.remove(uuid);
                if (ServerScanner.isOnline(transfer.player)) {
                    reply(transfer.player, transfer.request, Net.ArchiveCatalog.DENIED);
                }
                continue;
            }
            int total = (transfer.data.length + Net.PART_SIZE - 1) / Net.PART_SIZE;
            for (int sent = 0; sent < 2 && transfer.index < total; sent++) {
                int start = transfer.index * Net.PART_SIZE;
                byte[] part = java.util.Arrays
                    .copyOfRange(transfer.data, start, Math.min(transfer.data.length, start + Net.PART_SIZE));
                Net.sendTo(new Net.ArchivePart(transfer.request, transfer.index++, total, part), transfer.player);
            }
            if (transfer.index == total) {
                transfers.remove(uuid);
            }
        }
    }

    public void reset() {
        generation++;
        requests.clear();
        requested.clear();
        transfers.clear();
        pending.clear();
        reader.getQueue()
            .clear();
    }
}
