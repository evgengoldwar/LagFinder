package lagfinder.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import lagfinder.Config;
import lagfinder.LagFinder;
import lagfinder.core.Describer;
import lagfinder.core.ProfileSession;
import lagfinder.core.Report;
import lagfinder.core.ReportFiles;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;
import lagfinder.network.Net;

/**
 * Runs tick scans on the server for the players allowed to: one at a time, started and cancelled by the player who
 * asked, who is told how far along it is every second and gets the report when it ends.
 */
public final class ServerScanner {

    public static final ServerScanner INSTANCE = new ServerScanner();

    /** Report parts sent a tick, so a big report doesn't flood the connection. */
    private static final int PARTS_PER_TICK = 6;

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private ProfileSession session;
    private UUID owner;
    private long tickStart;
    /** Whether MinecraftServer.tickServer() marks the ticks (its mixin applied); the tick events do otherwise. */
    private volatile boolean tickHooked;
    /** Whether the worlds' ticks are marked around NeoForge's world tick events (their mixin applied). */
    private volatile boolean worldHooked;
    /** Whether the server's tick ran in this round of the integrated server's (it doesn't while the game is paused). */
    private boolean ticked;
    /** Whether the integrated server's rounds are marked (their mixin applied): the pauses are left out then. */
    private volatile boolean roundHooked;
    /** When the round started. */
    private long roundStart;
    private long lastStatus;
    private int reportId;
    /**
     * When each world's tick started, what the scan itself had taken of the tick by then, and what was measured
     * outside any thing.
     */
    private final Map<Level, long[]> worldStarts = new IdentityHashMap<>();

    /**
     * Counts the servers this one ran: a report made for a server that stopped meanwhile (the player left the world) is
     * never sent on the next one.
     */
    private volatile int generation;
    /**
     * Players with the mod (they asked for the state): told when a scan starts or ends, so none waits on a stale one.
     */
    private final Set<UUID> listeners = new HashSet<>();

    /** A report being made (written on another thread) or sent. */
    private volatile boolean building;
    private final Queue<Net.ReportPart> outgoing = new ArrayDeque<>();
    private UUID outgoingTo;

    private ServerScanner() {}

    /** Where the server keeps its reports (the last {@link ReportFiles#KEEP}). */
    public static File folder() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        File root = (server != null ? server.getServerDirectory() : java.nio.file.Path.of(""))
            .toAbsolutePath()
            .toFile();
        return new File(new File(root, "lagfinder"), "reports");
    }

    /** Runs the task on the server thread at the start of the next tick (before it is measured). */
    public void schedule(Runnable task) {
        tasks.add(task);
    }

    /** Whether the player may scan: {@code lagfinder.scan}, operators by default. */
    public static boolean canScan(ServerPlayer player) {
        return Permissions.has(player, Permissions.SCAN);
    }

    /**
     * Whether the player may teleport from a report: to any place, as {@code /tp} does, so only those who may, even
     * where others may scan.
     */
    public static boolean canTeleport(ServerPlayer player) {
        return Config.allowTeleport && Permissions.has(player, Permissions.TELEPORT);
    }

    /** Whether the player is still on the server (a task runs a tick after it was asked for). */
    static boolean isOnline(ServerPlayer player) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || player == null || player.hasDisconnected()) {
            return false;
        }
        return server.getPlayerList()
            .getPlayer(player.getUUID()) == player;
    }

    public void start(ServerPlayer player, int seconds, ScanFocus focus, boolean light) {
        if (!isOnline(player)) {
            return;
        }
        if (!canScan(player)) {
            sendStatus(player, "lagfinder.message.not_allowed");
            return;
        }
        if (session != null || building || !outgoing.isEmpty()) {
            sendStatus(player, "lagfinder.message.busy");
            return;
        }
        seconds = Math.max(5, Math.min(Config.maxScanSeconds, seconds));
        session = new ProfileSession(
            ScanMode.TPS,
            Thread.currentThread(),
            seconds * 1_000_000_000L,
            Config.spikeTpsMs,
            new Describer());
        session.startFor(focus);
        if (light) {
            session.setSampleEvery(Config.lightScanEvery);
        }
        owner = player.getUUID();
        tickStart = 0;
        lastStatus = 0;
        worldStarts.clear();
        EventTiming.install();
        ProfileSession.setServer(session);
        LagFinder.LOG.info(
            "{} started a {} s tick scan{}",
            player.getGameProfile()
                .getName(),
            seconds,
            session.sampleEvery() > 1 ? " (light: 1 tick in " + session.sampleEvery() + ")" : "");
        sendStatus(player, null);
        broadcast(player.getUUID());
    }

    /**
     * Cancels the player's own scan: the one running, or its report still being made or sent (the player pressed
     * cancel as the scan ended). A player whose start was refused (another's scan runs) cancels nothing of the other's.
     */
    public void cancel(ServerPlayer player) {
        if (!isOnline(player)) {
            return;
        }
        boolean mine = player.getUUID()
            .equals(owner);
        if (mine && session != null) {
            stop();
            LagFinder.LOG.info(
                "{} cancelled the tick scan",
                player.getGameProfile()
                    .getName());
        } else if (mine && (building || !outgoing.isEmpty())) {
            generation++;
            building = false;
            outgoing.clear();
            LagFinder.LOG.info(
                "{} cancelled the tick scan's report",
                player.getGameProfile()
                    .getName());
        } else {
            sendStatus(player, null);
            return;
        }
        sendStatus(player, "lagfinder.message.cancelled");
        broadcast(player.getUUID());
    }

    /** Stops the scan running, all of it: cancelled, or the server stopping. */
    private void stop() {
        stop(true);
    }

    /** Stops the scan running; when it ended (not {@code discard}), its report is still to be made from it. */
    private void stop(boolean discard) {
        if (session != null) {
            if (discard) {
                session.discard();
            } else {
                session.close();
            }
        }
        ProfileSession.setServer(null);
        EventTiming.uninstall();
        session = null;
        worldStarts.clear();
    }

    public void teleport(ServerPlayer player, Net.Teleport message) {
        if (!isOnline(player)) {
            return;
        }
        if (!canTeleport(player)) {
            sendStatus(player, "lagfinder.message.no_teleport");
            return;
        }
        if (!Teleports.teleport(player, message)) {
            sendStatus(player, "lagfinder.message.no_dimension");
        }
    }

    public void sendStatus(ServerPlayer player, String message) {
        if (!isOnline(player)) {
            return;
        }
        Net.Status status = new Net.Status();
        status.allowed = canScan(player);
        status.teleportAllowed = canTeleport(player);
        boolean mine = player.getUUID()
            .equals(owner);
        if (session != null) {
            status.state = mine ? Net.Status.RUNNING : Net.Status.BUSY;
            status.progress = session.progress();
            status.secondsLeft = (int) Math.ceil(session.nanosLeft() / 1e9);
            status.paused = session.isPaused();
        } else if (building || !outgoing.isEmpty()) {
            status.state = mine ? Net.Status.SENDING : Net.Status.BUSY;
            status.progress = 1f;
        } else {
            status.state = Net.Status.IDLE;
        }
        status.message = message == null ? "" : message;
        status.totalSeconds = session != null ? (int) Math.round(session.durationNanos / 1e9) : 0;
        status.maxSeconds = Config.maxScanSeconds;
        if (status.allowed) {
            listeners.add(player.getUUID());
        } else {
            listeners.remove(player.getUUID());
            status.state = Net.Status.IDLE;
            status.progress = 0;
            status.secondsLeft = 0;
        }
        Net.sendTo(status, player);
    }

    /** Tells every player with the mod but those given how things stand now (a scan started or ended). */
    private void broadcast(UUID... except) {
        Set<UUID> skip = new HashSet<>(Arrays.asList(except));
        for (UUID id : new ArrayList<>(listeners)) {
            if (skip.contains(id)) {
                continue;
            }
            ServerPlayer player = ownerPlayer(id);
            if (player == null) {
                listeners.remove(id);
            } else {
                sendStatus(player, null);
            }
        }
    }

    private ServerPlayer ownerPlayer(UUID id) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || id == null) {
            return null;
        }
        return server.getPlayerList()
            .getPlayer(id);
    }

    /**
     * The server's tick starts: called first thing in {@code MinecraftServer.tickServer()} (MixinMinecraftServer), so
     * every mod's tick handlers, whatever their priority and order, are inside the tick measured.
     */
    public void tickStarts() {
        tickHooked = true;
        beginTick();
    }

    /** The server's tick ends: called last thing in {@code MinecraftServer.tickServer()}. */
    public void tickEnds() {
        endTick();
    }

    /**
     * A round of the single player world's server starts ({@code IntegratedServer.tickServer()}): it runs the server's
     * tick only while the game isn't paused.
     */
    public void roundStarts() {
        roundHooked = true;
        ticked = false;
        roundStart = System.nanoTime();
    }

    /**
     * The round ends: when the game was paused and no tick ran, the scan leaves the pause out from the start of the
     * round, and what was asked of the server is done all the same, a report on its way still sent.
     */
    public void roundEnds() {
        if (ticked) {
            return;
        }
        runTasks();
        sendParts();
        if (session != null) {
            session.dropPeriod();
            session.paused(roundStart);
            checkIn(System.nanoTime());
        }
    }

    /** The server tick event started: marks the tick only where the mixin didn't apply. */
    public void tickEventStarts() {
        if (!tickHooked) {
            beginTick();
            if (session != null) {
                session.tickedByEvents();
            }
        }
    }

    public void tickEventEnds() {
        if (!tickHooked) {
            endTick();
        }
    }

    private void beginTick() {
        ticked = true;
        runTasks();
        sendParts();
        if (session != null) {
            session.dropPeriod();
            tickStart = System.nanoTime();
            session.beginPeriod(tickStart);
        }
    }

    private void runTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LagFinder.LOG.error("LagFinder task failed", t);
            }
        }
    }

    private void endTick() {
        if (session == null || tickStart == 0) {
            return;
        }
        long now = System.nanoTime();
        if (tickHooked && worldHooked) {
            session.recordRest(now - tickStart);
        }
        session.endPeriod(tickStart, now - tickStart);
        if (session.isDone(now)) {
            finish();
            return;
        }
        if (!checkIn(now)) {
            return;
        }
        session.addOverhead(System.nanoTime() - now);
    }

    /**
     * Once a second: tells the player who asked how far along the scan is, or cancels it when that player left
     * (nobody would get the report, and the server would stay busy for it). False when it was cancelled.
     */
    private boolean checkIn(long now) {
        if (now - lastStatus <= 1_000_000_000L) {
            return true;
        }
        lastStatus = now;
        ServerPlayer player = ownerPlayer(owner);
        if (player != null && canScan(player)) {
            EventTiming.install();
            sendStatus(player, null);
            return true;
        }
        LagFinder.LOG.info("The player who started the tick scan left or lost access: it is cancelled");
        stop();
        broadcast();
        return false;
    }

    /**
     * A world's tick starts: called just before NeoForge's world tick start event (MixinMinecraftServer), so the
     * world's time holds every mod's handlers of it, whatever their priority and order.
     */
    public void worldStarts(Level world) {
        worldHooked = true;
        beginWorld(world);
    }

    /** A world's tick ends: called just after NeoForge's world tick end event. */
    public void worldEnds(Level world) {
        endWorld(world);
    }

    public void worldEventStarts(Level world) {
        if (!worldHooked) {
            beginWorld(world);
        }
    }

    public void worldEventEnds(Level world) {
        if (!worldHooked) {
            endWorld(world);
        }
    }

    private void beginWorld(Level world) {
        if (session != null) {
            worldStarts.put(world, new long[] { System.nanoTime(), session.periodOverhead(), session.nested() });
        }
    }

    private void endWorld(Level world) {
        if (session == null) {
            return;
        }
        long[] start = worldStarts.remove(world);
        if (start != null) {
            long nanos = System.nanoTime() - start[0] - (session.periodOverhead() - start[1]);
            session.recordWorld(world, Math.max(0, nanos), worldHooked ? start[2] : -1);
        }
    }

    private void finish() {
        ProfileSession finished = session;
        stop(false);
        UUID to = owner;
        String source = serverName();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isSingleplayer() && !roundHooked) {
            finished.warn("lagfinder.warning.pause_events");
        }
        try {
            finished.prepareReport();
        } catch (Throwable t) {
            LagFinder.LOG.error("Could not build the scan's report", t);
            finished.discard();
            failed(to);
            return;
        }
        building = true;
        int id = ++reportId;
        int made = generation;
        File folder = folder();
        ServerPlayer ownerPlayer = ownerPlayer(to);
        if (ownerPlayer != null) {
            sendStatus(ownerPlayer, null);
        }
        Thread writer = new Thread(() -> {
            try {
                Report report = finished.buildReport(source);
                for (String warning : report.warnings) {
                    LagFinder.LOG.warn("Tick scan: {}", warning);
                }
                finished.completeReport(report);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                report.write(bytes);
                byte[] data = bytes.toByteArray();
                if (made != generation) {
                    return;
                }
                ReportFiles.save(folder, report, data);
                int total = Math.max(1, (data.length + Net.PART_SIZE - 1) / Net.PART_SIZE);
                schedule(() -> {
                    if (made != generation) {
                        return;
                    }
                    outgoing.clear();
                    outgoingTo = to;
                    for (int i = 0; i < total; i++) {
                        int from = i * Net.PART_SIZE;
                        outgoing.add(
                            new Net.ReportPart(
                                id,
                                i,
                                total,
                                Arrays.copyOfRange(data, from, Math.min(data.length, from + Net.PART_SIZE))));
                    }
                    building = false;
                    LagFinder.LOG.info("Tick scan finished: report of {} KB in {} parts", data.length / 1024, total);
                });
            } catch (Throwable t) {
                LagFinder.LOG.error("Could not write the scan's report", t);
                finished.discard();
                schedule(() -> {
                    if (made == generation) {
                        building = false;
                        failed(to);
                    }
                });
            }
        }, "LagFinder report writer");
        writer.setDaemon(true);
        writer.start();
    }

    /**
     * The report couldn't be made: its owner is told (not left waiting on it), and the others that the server is free.
     */
    private void failed(UUID to) {
        ServerPlayer player = ownerPlayer(to);
        if (player != null) {
            sendStatus(player, "lagfinder.message.failed");
        }
        broadcast(to);
    }

    private void sendParts() {
        if (outgoing.isEmpty()) {
            return;
        }
        ServerPlayer player = ownerPlayer(outgoingTo);
        if (player == null || !canScan(player)) {
            outgoing.clear();
            broadcast();
            return;
        }
        for (int i = 0; i < PARTS_PER_TICK && !outgoing.isEmpty(); i++) {
            Net.sendTo(outgoing.poll(), player);
        }
        if (outgoing.isEmpty()) {
            sendStatus(player, null);
            broadcast(player.getUUID());
        }
    }

    private static String serverName() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return "";
        }
        if (server.isSingleplayer()) {
            return server.getWorldData()
                .getLevelName();
        }
        String motd = server.getMotd();
        return motd == null ? "" : motd;
    }

    /** The server stopped: nothing carries over to the next one. */
    public void reset() {
        generation++;
        stop();
        tasks.clear();
        outgoing.clear();
        building = false;
        owner = null;
        listeners.clear();
    }
}
