package lagfinder.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import lagfinder.network.LiveLagPackets;
import lagfinder.network.Net;

public final class LiveLagTracker {

    public static final LiveLagTracker INSTANCE = new LiveLagTracker();

    private static final long THRESHOLD_NANOS = 100_000;
    private static final int SUBSCRIPTION_TICKS = 120;

    private final Map<EntityPlayerMP, LiveLagPackets.Subscription> pending = new ConcurrentHashMap<>();
    private final Map<EntityPlayerMP, Watch> watches = new HashMap<>();
    private volatile Thread recordingThread;
    private int tick;

    private LiveLagTracker() {}

    private static final class Watch {

        final int id;
        final int expires;
        World world;
        final Set<LiveLagPackets.Hit> hits = new LinkedHashSet<>();

        Watch(int id, int expires) {
            this.id = id;
            this.expires = expires;
        }
    }

    public static boolean isRecording() {
        return INSTANCE.recordingThread == Thread.currentThread();
    }

    public void request(EntityPlayerMP player, LiveLagPackets.Subscription message) {
        pending.put(player, message);
    }

    public void reset() {
        recordingThread = null;
        pending.clear();
        watches.clear();
        tick = 0;
    }

    void beginTick() {
        tick++;
        pending.forEach((player, request) -> {
            if (!pending.remove(player, request)) {
                return;
            }
            if (request.enabled && ServerScanner.isOnline(player) && ServerScanner.canScan(player)) {
                watches.put(player, new Watch(request.id, tick + SUBSCRIPTION_TICKS));
            } else {
                watches.remove(player);
            }
        });
        watches.entrySet().removeIf(entry -> tick - entry.getValue().expires >= 0
            || !ServerScanner.isOnline(entry.getKey()) || !ServerScanner.canScan(entry.getKey()));
        recordingThread = watches.isEmpty() ? null : Thread.currentThread();
    }

    void endTick() {
        recordingThread = null;
        Iterator<Map.Entry<EntityPlayerMP, Watch>> entries = watches.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<EntityPlayerMP, Watch> entry = entries.next();
            EntityPlayerMP player = entry.getKey();
            Watch watch = entry.getValue();
            if (watch.hits.isEmpty()) {
                continue;
            }
            if (!ServerScanner.isOnline(player) || !ServerScanner.canScan(player)) {
                entries.remove();
                continue;
            }
            if (watch.world == player.worldObj) {
                sendHits(player, watch);
            }
            watch.hits.clear();
        }
    }

    private static void sendHits(EntityPlayerMP player, Watch watch) {
        Iterator<LiveLagPackets.Hit> pendingHits = watch.hits.iterator();
        while (pendingHits.hasNext()) {
            List<LiveLagPackets.Hit> batch = new ArrayList<>(LiveLagPackets.MAX_HITS);
            do {
                batch.add(pendingHits.next());
            } while (batch.size() < LiveLagPackets.MAX_HITS && pendingHits.hasNext());
            Net.sendTo(new LiveLagPackets.Batch(watch.id, player.dimension, batch), player);
        }
    }

    public void entity(World world, Entity entity, long elapsed) {
        if (elapsed > THRESHOLD_NANOS && entity.worldObj == world) {
            record(
                world,
                MathHelper.floor_double(entity.posX),
                MathHelper.floor_double(entity.posY),
                MathHelper.floor_double(entity.posZ),
                entity.getEntityId());
        }
    }

    public void block(World world, int x, int y, int z, long elapsed) {
        if (elapsed > THRESHOLD_NANOS) {
            record(world, x, y, z, LiveLagPackets.BLOCK);
        }
    }

    private void record(World world, int x, int y, int z, int entityId) {
        if (!(world instanceof WorldServer)) {
            return;
        }
        for (Map.Entry<EntityPlayerMP, Watch> entry : watches.entrySet()) {
            EntityPlayerMP player = entry.getKey();
            if (player.worldObj != world || !((WorldServer) world).getPlayerManager()
                .isPlayerWatchingChunk(player, x >> 4, z >> 4)) {
                continue;
            }
            Watch watch = entry.getValue();
            if (watch.world != world) {
                watch.hits.clear();
                watch.world = world;
            }
            LiveLagPackets.Hit hit = new LiveLagPackets.Hit(x, y, z, entityId);
            watch.hits.remove(hit);
            watch.hits.add(hit);
        }
    }
}
