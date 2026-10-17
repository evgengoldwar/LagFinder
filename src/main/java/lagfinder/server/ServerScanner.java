package lagfinder.server;

import java.io.ByteArrayOutputStream;
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

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
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
    /** Whether MinecraftServer.tick() marks the ticks (its mixin applied); the tick events do otherwise. */
    private volatile boolean tickHooked;
    /** Whether the worlds' ticks are marked around FML's world tick events (their mixin applied). */
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
    private final Map<World, long[]> worldStarts = new IdentityHashMap<>();

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

    /** Runs the task on the server thread at the start of the next tick (before it is measured). */
    public void schedule(Runnable task) {
        tasks.add(task);
    }

    /** Whether the player may scan: the owner of a single player world always, others as operators. */
    public static boolean canScan(EntityPlayerMP player) {
        return player != null && (isOperator(player) || ServerUtilitiesAccess.allowed(player));
    }

    /** The owner of a single player world, or an operator. */
    private static boolean isOperator(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return false;
        }
        if (server.isSinglePlayer() && player.getCommandSenderName()
            .equals(server.getServerOwner())) {
            return true;
        }
        return server.getConfigurationManager()
            .func_152596_g(player.getGameProfile());
    }

    /**
     * Whether the player may teleport from a report: to any place, as {@code /tp} does, so only those who may use it,
     * even where everyone may scan.
     */
    public static boolean canTeleport(EntityPlayerMP player) {
        return Config.allowTeleport && isOperator(player);
    }

    /** Whether the player is still on the server (a task runs a tick after it was asked for). */
    static boolean isOnline(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || player == null || player.isDead) {
            return false;
        }
        for (Object object : server.getConfigurationManager().playerEntityList) {
            if (object == player) {
                return true;
            }
        }
        return false;
    }

    public void start(EntityPlayerMP player, int seconds, ScanFocus focus, boolean light) {
        if (!canScan(player)) {
            sendStatus(player, "lagfinder.message.not_allowed");
            return;
        }
        if (ownerPlayer(player.getUniqueID()) == null) {
            // Left before the scan could start: nobody would get its report.
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
        owner = player.getUniqueID();
        tickStart = 0;
        lastStatus = 0;
        worldStarts.clear();
        ProfileSession.setServer(session);
        LagFinder.LOG.info(
            "{} started a {} s {} tick scan{}",
            player.getCommandSenderName(),
            seconds,
            focus,
            session.sampleEvery() > 1 ? " (light: 1 tick in " + session.sampleEvery() + ")" : "");
        sendStatus(player, null);
        broadcast(player.getUniqueID());
    }

    /**
     * Cancels the player's own scan: the one running, or its report still being made or sent (the player pressed
     * cancel as the scan ended). A player whose start was refused (another's scan runs) cancels nothing of the other's.
     */
    public void cancel(EntityPlayerMP player) {
        boolean mine = player.getUniqueID()
            .equals(owner);
        if (mine && session != null) {
            stop();
            LagFinder.LOG.info("{} cancelled the tick scan", player.getCommandSenderName());
        } else if (mine && (building || !outgoing.isEmpty())) {
            // A report made before is never sent: the writer's result is dropped, the parts left unsent.
            generation++;
            building = false;
            outgoing.clear();
            LagFinder.LOG.info("{} cancelled the tick scan's report", player.getCommandSenderName());
        } else {
            sendStatus(player, null);
            return;
        }
        sendStatus(player, "lagfinder.message.cancelled");
        broadcast(player.getUniqueID());
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
        session = null;
        worldStarts.clear();
    }

    public void teleport(EntityPlayerMP player, Net.Teleport message) {
        if (!isOnline(player)) {
            // Left before its turn came: moving it would put it back into a world.
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

    public void sendStatus(EntityPlayerMP player, String message) {
        Net.Status status = new Net.Status();
        status.allowed = canScan(player);
        status.teleportAllowed = canTeleport(player);
        boolean mine = player.getUniqueID()
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
            listeners.add(player.getUniqueID());
        } else {
            listeners.remove(player.getUniqueID());
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
            EntityPlayerMP player = ownerPlayer(id);
            if (player == null) {
                listeners.remove(id);
            } else {
                sendStatus(player, null);
            }
        }
    }

    private EntityPlayerMP ownerPlayer(UUID id) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || id == null) {
            return null;
        }
        for (Object object : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) object;
            if (player.getUniqueID()
                .equals(id)) {
                return player;
            }
        }
        return null;
    }

    /**
     * The server's tick starts: called first thing in {@code MinecraftServer.tick()} (MixinMinecraftServer), so every
     * mod's tick handlers, whatever their priority and order, are inside the tick measured.
     */
    public void tickStarts() {
        tickHooked = true;
        beginTick();
    }

    /** The server's tick ends: called last thing in {@code MinecraftServer.tick()}. */
    public void tickEnds() {
        endTick();
    }

    /**
     * A round of the single player world's server starts ({@code IntegratedServer.tick()}, MixinIntegratedServer): it
     * runs the server's tick only while the game isn't paused.
     */
    public void roundStarts() {
        roundHooked = true;
        ticked = false;
        roundStart = System.nanoTime();
    }

    /**
     * The round ends: when the game was paused and no tick ran, the scan leaves the pause out from the start of the
     * round (else it would show a world standing still, and the worlds saved as the game pauses as a lag spike), and
     * what was asked of the server is done all the same, a report on its way still sent.
     */
    public void roundEnds() {
        if (ticked) {
            return;
        }
        runTasks();
        sendParts();
        if (session != null) {
            // The worlds saved as the game paused (their save events), and the tasks: no part of a tick.
            session.dropPeriod();
            session.paused(roundStart);
            // The player is still told how things stand (paused), and a scan whose player left still ends.
            checkIn(System.nanoTime());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTickStart(TickEvent.ServerTickEvent event) {
        // Only where the tick isn't hooked (the mixin didn't apply): the tick events mark it then.
        if (event.phase == TickEvent.Phase.START && !tickHooked) {
            beginTick();
            if (session != null) {
                // Mods' tick handlers ahead of this one are left out: the report says so.
                session.tickedByEvents();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !tickHooked) {
            endTick();
        }
    }

    private void beginTick() {
        ticked = true;
        runTasks();
        sendParts();
        if (session != null) {
            // What LagFinder's own tasks (a teleport, a report sent) set off is no part of the tick.
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
            // What no hook measured: the server's own work outside the worlds and the events. Only known when the
            // tick and the worlds are marked around all of it, not from inside LagFinder's own tick handlers.
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
        // The time taken to add up the tick (and tell how far along it is) is left out of the scan's length.
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
        EntityPlayerMP player = ownerPlayer(owner);
        if (player != null && canScan(player)) {
            sendStatus(player, null);
            return true;
        }
        LagFinder.LOG.info("The player who started the tick scan left or lost access: it is cancelled");
        stop();
        broadcast();
        return false;
    }

    /**
     * A world's tick starts: called just before FML's world tick start event (MixinMinecraftServer), so the world's
     * time holds every mod's handlers of it, whatever their priority and order.
     */
    public void worldStarts(World world) {
        worldHooked = true;
        beginWorld(world);
    }

    /** A world's tick ends: called just after FML's world tick end event. */
    public void worldEnds(World world) {
        endWorld(world);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onWorldTickStart(TickEvent.WorldTickEvent event) {
        // Only where the worlds' ticks aren't hooked (the mixin didn't apply).
        if (event.phase == TickEvent.Phase.START && !worldHooked) {
            beginWorld(event.world);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onWorldTickEnd(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !worldHooked) {
            endWorld(event.world);
        }
    }

    private void beginWorld(World world) {
        if (session != null) {
            worldStarts.put(world, new long[] { System.nanoTime(), session.periodOverhead(), session.nested() });
        }
    }

    private void endWorld(World world) {
        if (session == null) {
            return;
        }
        long[] start = worldStarts.remove(world);
        if (start != null) {
            // Without what timing the things in it took.
            long nanos = System.nanoTime() - start[0] - (session.periodOverhead() - start[1]);
            // Marked around FML's world tick (not inside LagFinder's own handlers of it), the world counts as one thing
            // measured: what is left of the tick is the server's own work.
            session.recordWorld(world, Math.max(0, nanos), worldHooked ? start[2] : -1);
        }
    }

    private void finish() {
        ProfileSession finished = session;
        stop(false);
        UUID to = owner;
        String source = serverName();
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null && server.isSinglePlayer() && !roundHooked) {
            // The pauses of the game could not be told apart from a server standing still.
            finished.warn("lagfinder.warning.pause_events");
        }
        try {
            // What needs the game, quickly; the report itself is built on the writer's thread.
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
        EntityPlayerMP ownerPlayer = ownerPlayer(to);
        if (ownerPlayer != null) {
            // Told at once that the scan ended and its report is on the way: not left on a scan with nothing left.
            sendStatus(ownerPlayer, null);
        }
        // Building, finishing and compressing a big report takes a moment: not on the server's thread.
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
                ReportFiles.save(ServerReports.folder(), report, data);
                int total = Math.max(1, (data.length + Net.PART_SIZE - 1) / Net.PART_SIZE);
                schedule(() -> {
                    if (made != generation) {
                        // Made for a server that stopped since.
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
        EntityPlayerMP player = ownerPlayer(to);
        if (player != null) {
            sendStatus(player, "lagfinder.message.failed");
        }
        broadcast(to);
    }

    private void sendParts() {
        ServerReports.INSTANCE.tick();
        if (outgoing.isEmpty()) {
            return;
        }
        EntityPlayerMP player = ownerPlayer(outgoingTo);
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
            // The server is free for the others again.
            broadcast(player.getUniqueID());
        }
    }

    private static String serverName() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return "";
        }
        if (server.isSinglePlayer()) {
            WorldServer world = server.worldServerForDimension(0);
            return world != null ? world.getWorldInfo()
                .getWorldName() : "";
        }
        return server.getMOTD();
    }

    /** The server stopped: nothing carries over to the next one. */
    public void reset() {
        generation++;
        ServerReports.INSTANCE.reset();
        stop();
        tasks.clear();
        outgoing.clear();
        building = false;
        owner = null;
        listeners.clear();
    }
}
