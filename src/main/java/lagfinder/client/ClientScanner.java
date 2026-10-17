package lagfinder.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import lagfinder.Config;
import lagfinder.LagFinder;
import lagfinder.client.gui.GuiLagFinder;
import lagfinder.core.LagFilter;
import lagfinder.core.ProfileSession;
import lagfinder.core.Report;
import lagfinder.core.ReportFiles;
import lagfinder.core.ReportMerge;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;
import lagfinder.core.ScanSide;
import lagfinder.network.Net;

/**
 * The client's side of scanning: runs frame scans itself, asks the server for tick scans and follows them, or both at
 * once, puts the server's report together from its parts, adds the two reports of a scan of both sides up into one,
 * and keeps the last report for the window.
 */
public final class ClientScanner {

    public static final ClientScanner INSTANCE = new ClientScanner();

    public enum Phase {
        /** Nothing running. */
        IDLE,
        /** Asked the server, no answer yet. */
        REQUESTED,
        /** Measuring. */
        RUNNING,
        /** Measured; the report is being made or received. */
        RECEIVING
    }

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    // What the server said last.
    private volatile boolean serverHasMod;
    private boolean serverAllowed, teleportAllowed;
    private int serverState = Net.Status.IDLE;

    /** How far the server's scan and this client's scan are: either or both run, as the side asked. */
    private Phase serverPhase = Phase.IDLE, clientPhase = Phase.IDLE;
    private ScanSide side = ScanSide.SERVER;
    private ScanFocus focus = ScanFocus.LAG;
    /** What the report of a tick scan leaves out, null for nothing. */
    private LagFilter filter;
    private float progress;
    private int secondsLeft;
    private long requestedAt;
    /** Seconds the tick scan was asked for, and when the server last told how far it is. */
    private int requestedSeconds;
    private long statusAt;
    /** Asks the server whether it has the mod once the player is in the world. */
    private volatile boolean queryPending;

    private ProfileSession frameSession;
    private long frameStart;
    /** When the last frames before a scan started (nanoseconds, a ring), for the rate the game ran at without it. */
    private final long[] recentFrames = new long[1024];
    private int recentCount, recentNext;
    /** Frames a second just before the frame scan running started, 0 when not known. */
    private float frameRateBefore;

    /** Parts of a report taken at most: about 64 MB, far more than any report; a claim of more is refused. */
    private static final int MAX_PARTS = (ReportFiles.MAX_BYTES + Net.PART_SIZE - 1) / Net.PART_SIZE;
    private int partsId = -1;
    private byte[][] parts;
    private int partsReceived;
    private int partsBytes;
    /** The server's report has all its parts and is being read (on another thread). */
    private boolean reading;
    /** The server said it got the start of the scan: it is waited for however long its next tick takes. */
    private boolean queued;
    /** The longest scan the server allows (s), 0 when not known. */
    private int serverMaxSeconds;
    /** The server's version of the messages ({@link Net#PROTOCOL}), 0 when not known. */
    private int serverProtocol;
    /** The server's scan stands still (the single player game is paused): its progress isn't moved on. */
    private boolean serverPaused;
    /**
     * The scan was cancelled here and the server hasn't answered yet: what it said before it knew (that the scan runs,
     * that its report comes, the report's parts) is not taken.
     */
    private boolean cancelling;
    /** Counts the servers joined: a report read for one left meanwhile is not shown as the next one's. */
    private int serverGeneration;

    private Report report;
    /** Both sides scanned: the report of the side that ended first, until the other's comes to add up with it. */
    private Report held;
    private byte[] heldData;
    /** The two reports of a scan of both sides are being added up (on another thread). */
    private boolean merging;
    /** The server of the scan runs in this same Java (single player): what is of the whole Java is counted once. */
    private boolean sameJvm;
    /** A report arrived the player hasn't opened yet. */
    private boolean unseen;
    private String message;
    private Object[] messageArgs = new Object[0];
    private long messageAt;

    private ClientScanner() {}

    public void schedule(Runnable task) {
        tasks.add(task);
    }

    public boolean serverHasMod() {
        return serverHasMod;
    }

    public boolean serverAllowed() {
        return serverHasMod && serverAllowed;
    }

    public boolean serverArchiveAvailable() {
        return serverHasMod && serverAllowed && serverProtocol >= 3;
    }

    public boolean teleportAllowed() {
        return serverHasMod && teleportAllowed;
    }

    /** Someone else's scan runs on the server. */
    public boolean serverBusy() {
        return serverState == Net.Status.BUSY;
    }

    /** The state of the scan as a whole: running while either side measures. */
    public Phase phase() {
        if (serverPhase == Phase.RUNNING || clientPhase == Phase.RUNNING) {
            return Phase.RUNNING;
        }
        if (serverPhase == Phase.REQUESTED) {
            return Phase.REQUESTED;
        }
        if (serverPhase == Phase.RECEIVING || clientPhase == Phase.RECEIVING || merging) {
            return Phase.RECEIVING;
        }
        return Phase.IDLE;
    }

    public ScanSide side() {
        return side;
    }

    public ScanFocus focus() {
        return focus;
    }

    public boolean isActive() {
        return serverPhase != Phase.IDLE || clientPhase != Phase.IDLE || merging;
    }

    /** How far along the scan is, 0 to 1; with both sides, the one further behind. */
    public float progress() {
        if (serverPhase == Phase.IDLE) {
            return clientProgress();
        }
        if (clientPhase == Phase.IDLE) {
            return serverProgress();
        }
        return Math.min(serverProgress(), clientProgress());
    }

    private float serverProgress() {
        if (serverPhase == Phase.RECEIVING) {
            return parts == null ? 1f : partsReceived / (float) parts.length;
        }
        if (serverPhase == Phase.RUNNING && requestedSeconds > 0 && !serverPaused) {
            // Moves on smoothly between the server's reports, never far ahead of them.
            float since = (System.currentTimeMillis() - statusAt) / 1000f;
            return Math.min(1f, progress + Math.min(1.5f, since) / requestedSeconds);
        }
        return progress;
    }

    private float clientProgress() {
        return frameSession != null ? frameSession.progress() : clientPhase == Phase.RECEIVING ? 1f : 0f;
    }

    /** Seconds until the scan ends; with both sides, until the later one does. */
    public int secondsLeft() {
        int left = serverPhase != Phase.IDLE ? secondsLeft : 0;
        if (frameSession != null) {
            left = Math.max(left, (int) Math.ceil(frameSession.nanosLeft() / 1e9));
        }
        return left;
    }

    public Report report() {
        return report;
    }

    public boolean hasUnseenReport() {
        return unseen && report != null;
    }

    public void markSeen() {
        unseen = false;
    }

    /** Opens an older report from disk as the current one. */
    public void setReport(Report report) {
        this.report = report;
    }

    /** Something to tell the player (a translation key), shown for a few seconds; null when there is none. */
    public String message() {
        return message != null && System.currentTimeMillis() - messageAt < 6000 ? message : null;
    }

    /** What {@link #message} says, in the player's language with its values; null when there is nothing. */
    public String messageText() {
        String key = message();
        return key == null ? null : net.minecraft.client.resources.I18n.format(key, messageArgs);
    }

    /** The longest tick scan the server allows (s), 0 when not known yet. */
    public int serverMaxSeconds() {
        return serverMaxSeconds;
    }

    private void tell(String key, Object... args) {
        message = key == null || key.isEmpty() ? null : key;
        messageArgs = args;
        messageAt = System.currentTimeMillis();
    }

    /** Asks the server to ask for its state again (the window opened). */
    public void query() {
        if (serverHasMod) {
            Net.sendToServer(new Net.Query());
        }
    }

    /** Starts a scan on the side asked: the server's ticks, this client's frames, or both at once. */
    public void start(ScanSide side, ScanFocus focus, int seconds) {
        start(side, focus, seconds, null, false);
    }

    /**
     * Starts a scan; of a scan of the server's ticks, the report leaves out what the filter says (null: nothing is
     * left out), and a {@code light} one measures one tick in a few.
     */
    public void start(ScanSide side, ScanFocus focus, int seconds, LagFilter filter, boolean light) {
        if (isActive()) {
            return;
        }
        if (serverHasMod && !serverAllowed()) {
            tell("lagfinder.message.not_allowed");
            return;
        }
        this.filter = filter;
        if (side.server() && !serverAllowed()) {
            tell(serverHasMod ? "lagfinder.message.not_allowed" : "lagfinder.message.no_server_mod");
            return;
        }
        if (side.server() && cancelling) {
            // The server hasn't answered the cancel of the last one yet (a tick): its answer would be taken as this
            // one's.
            return;
        }
        this.side = side;
        this.focus = focus;
        progress = 0;
        held = null;
        heldData = null;
        sameJvm = Minecraft.getMinecraft()
            .isSingleplayer();
        tell(null);
        if (side.server()) {
            serverPhase = Phase.REQUESTED;
            queued = false;
            cancelling = false;
            serverPaused = false;
            requestedAt = System.currentTimeMillis();
            requestedSeconds = seconds;
            statusAt = requestedAt;
            secondsLeft = seconds;
            Net.sendToServer(new Net.StartScan(seconds, focus, light));
        }
        if (side.client()) {
            frameRateBefore = recentFrameRate();
            frameSession = new ProfileSession(
                ScanMode.FPS,
                Thread.currentThread(),
                seconds * 1_000_000_000L,
                Config.spikeFpsMs,
                new ClientDescriber());
            frameStart = 0;
            frameSession.startFor(focus);
            frameSession.setFrameCap(frameCap());
            ProfileSession.setClient(frameSession);
            clientPhase = Phase.RUNNING;
        }
    }

    /** Cancels the scan, on both sides when both run. */
    public void cancel() {
        // A side whose report is already on the way is left to finish.
        if (frameSession != null) {
            stopFrames();
            clientPhase = Phase.IDLE;
            tell("lagfinder.message.cancelled");
        }
        if (serverPhase == Phase.REQUESTED || serverPhase == Phase.RUNNING) {
            // The server says it was cancelled; until then, what it says of the scan is of before it knew.
            Net.sendToServer(new Net.CancelScan());
            serverPhase = Phase.IDLE;
            cancelling = true;
            parts = null;
            partsId = -1;
        }
    }

    /** Stops the frame scan running, all of it: cancelled, or the game left. */
    private void stopFrames() {
        stopFrames(true);
    }

    /** Stops the frame scan running; when it ended (not {@code discard}), its report is still to be made from it. */
    private void stopFrames(boolean discard) {
        if (frameSession != null) {
            if (discard) {
                frameSession.discard();
            } else {
                frameSession.close();
            }
        }
        ProfileSession.setClient(null);
        frameSession = null;
        frameStart = 0;
    }

    /** The server's state arrived (any thread). */
    public void onStatus(Net.Status status) {
        schedule(() -> {
            serverHasMod = true;
            if (status.state == Net.Status.QUEUED) {
                // Only that the start got there: the server takes it up on its next tick, however long that takes.
                if (serverPhase == Phase.REQUESTED) {
                    queued = true;
                }
                return;
            }
            serverAllowed = status.allowed;
            teleportAllowed = status.teleportAllowed;
            serverState = status.state;
            serverProtocol = status.protocol;
            serverPaused = status.paused;
            if (status.maxSeconds > 0) {
                serverMaxSeconds = status.maxSeconds;
            }
            if (!status.message.isEmpty()) {
                tell(status.message);
            }
            if (!side.server()) {
                return;
            }
            if (cancelling) {
                if (status.state == Net.Status.RUNNING || status.state == Net.Status.SENDING) {
                    // Said before the cancel got there.
                    return;
                }
                // The server's answer to the cancel: nothing of the scan runs or comes any more.
                cancelling = false;
            }
            switch (status.state) {
                case Net.Status.RUNNING:
                    if (serverPhase == Phase.REQUESTED && status.totalSeconds > 0
                        && status.totalSeconds < requestedSeconds) {
                        // The server allows less than was asked: the scan is that long, and the player is told.
                        tell("lagfinder.message.clamped", GuiLagFinder.formatTime(status.totalSeconds));
                    }
                    if (status.totalSeconds > 0) {
                        requestedSeconds = status.totalSeconds;
                    }
                    serverPhase = Phase.RUNNING;
                    progress = status.progress;
                    secondsLeft = status.secondsLeft;
                    statusAt = System.currentTimeMillis();
                    break;
                case Net.Status.SENDING:
                    serverPhase = Phase.RECEIVING;
                    break;
                default:
                    if (serverPhase == Phase.REQUESTED || serverPhase == Phase.RUNNING) {
                        // Refused, cancelled, or ended without a report.
                        serverPhase = Phase.IDLE;
                    } else if (serverPhase == Phase.RECEIVING && !reading) {
                        // The server is done and no whole report came (the last part comes before this): it failed,
                        // went to nobody, or was cancelled. A part of one is let go.
                        serverPhase = Phase.IDLE;
                        parts = null;
                        partsId = -1;
                    }
            }
        });
    }

    /** A part of a report arrived (any thread). */
    public void onReportPart(Net.ReportPart part) {
        schedule(() -> {
            if (!side.server() || serverPhase == Phase.IDLE
                || cancelling
                || reading
                || part.total < 1
                || part.total > MAX_PARTS
                || !part.valid()) {
                // Not asked for (no tick scan of ours runs), or more than any report: not taken.
                return;
            }
            if (part.id != partsId || parts == null || parts.length != part.total) {
                partsId = part.id;
                parts = new byte[part.total][];
                partsReceived = 0;
                partsBytes = 0;
            }
            if (part.index < 0 || part.index >= parts.length || parts[part.index] != null) {
                return;
            }
            if (partsBytes > ReportFiles.MAX_BYTES - part.data.length) {
                parts = null;
                serverPhase = Phase.IDLE;
                tell("lagfinder.message.failed");
                return;
            }
            partsBytes += part.data.length;
            parts[part.index] = part.data;
            partsReceived++;
            serverPhase = Phase.RECEIVING;
            if (partsReceived == parts.length) {
                byte[][] all = parts;
                parts = null;
                partsId = -1;
                ByteArrayOutputStream joined = new ByteArrayOutputStream();
                for (byte[] bytes : all) {
                    joined.write(bytes, 0, bytes.length);
                }
                byte[] received = joined.toByteArray();
                LagFilter leftOut = filter;
                int readFor = serverGeneration;
                reading = true;
                Thread reader = new Thread(() -> {
                    try {
                        Report read = Report.read(new ByteArrayInputStream(received));
                        byte[] data = received;
                        if (leftOut != null && read.mode == ScanMode.TPS && read.focus == ScanFocus.LAG) {
                            // Saved as it is shown: without what took less than the least time asked.
                            leftOut.apply(read);
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            read.write(bytes);
                            data = bytes.toByteArray();
                        }
                        byte[] kept = data;
                        schedule(() -> {
                            if (readFor != serverGeneration) {
                                // Of a server left meanwhile: kept (it came whole, and says where it was made), but
                                // the scan of the server joined since is left as it is.
                                publish(read, kept);
                                return;
                            }
                            reading = false;
                            serverPhase = Phase.IDLE;
                            finished(read, kept);
                        });
                    } catch (Throwable t) {
                        LagFinder.LOG.error("Could not read the scan's report", t);
                        schedule(() -> {
                            if (readFor != serverGeneration) {
                                return;
                            }
                            reading = false;
                            serverPhase = Phase.IDLE;
                            tell("lagfinder.message.failed");
                        });
                    }
                }, "LagFinder report reader");
                reader.setDaemon(true);
                reader.start();
            }
        });
    }

    /** A report of the scan is ready; its side's phase is already back to idle. */
    private void finished(Report finished, byte[] data) {
        if (side == ScanSide.BOTH) {
            if (held == null && isActive()) {
                // Waits for the other side's, to add them up.
                held = finished;
                heldData = data;
                return;
            }
            if (held != null) {
                Report server = finished.mode == ScanMode.TPS ? finished : held;
                Report client = server == finished ? held : finished;
                held = null;
                heldData = null;
                merge(server, client);
                return;
            }
        }
        publish(finished, data);
    }

    /** Adds the server's and the client's reports up into one, off the game's thread, and keeps it. */
    private void merge(Report server, Report client) {
        merging = true;
        boolean oneJvm = sameJvm;
        Thread merger = new Thread(() -> {
            try {
                Report merged = ReportMerge.merge(server, client, oneJvm);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                merged.write(bytes);
                byte[] data = bytes.toByteArray();
                schedule(() -> {
                    merging = false;
                    publish(merged, data);
                });
            } catch (Throwable t) {
                LagFinder.LOG.error("Could not add up the server's and the client's reports", t);
                schedule(() -> {
                    merging = false;
                    tell("lagfinder.message.failed");
                });
            }
        }, "LagFinder report merger");
        merger.setDaemon(true);
        merger.start();
    }

    /** The report of the scan is ready: kept for the window, saved, and the player told. */
    private void publish(Report finished, byte[] data) {
        report = finished;
        unseen = true;
        // The key opens the scanner, where the new report is, not a log closed before.
        GuiLagFinder.forgetReopen();
        if (finished.mode == ScanMode.FPS || finished.bothSides) {
            ReportStore.save(finished, data);
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && !(mc.currentScreen instanceof GuiLagFinder)) {
            ChatComponentTranslation text = new ChatComponentTranslation("lagfinder.chat.ready", KeyHandler.keyName());
            text.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.AQUA));
            mc.thePlayer.addChatMessage(new ChatComponentText("[LagFinder] ").appendSibling(text));
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        ClientReports.INSTANCE.tick();
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LagFinder.LOG.error("LagFinder task failed", t);
            }
        }
        if (held != null && !isActive()) {
            // The other side ended without a report (cancelled, failed): this one is all there is.
            Report alone = held;
            byte[] data = heldData;
            held = null;
            heldData = null;
            publish(alone, data);
        }
        if (queryPending && Minecraft.getMinecraft().thePlayer != null) {
            // Servers with the mod answer; others never do.
            queryPending = false;
            Net.sendToServer(new Net.Query());
        }
        if (serverPhase == Phase.REQUESTED && !queued
            && serverProtocol >= 2
            && System.currentTimeMillis() - requestedAt > 15_000) {
            // The server never said it got the start (one of protocol 2 says so at once, whatever its ticks take). An
            // older one doesn't: it is waited for (the scan can be cancelled), its tick may be long in coming.
            serverPhase = Phase.IDLE;
            tell("lagfinder.message.no_answer");
        }
    }

    /** Frames a second over the last few seconds of play, 0 when too few frames were seen. */
    private float recentFrameRate() {
        if (recentCount < 10) {
            return 0;
        }
        int length = recentFrames.length;
        long last = recentFrames[(recentNext - 1 + length) % length];
        long first = last;
        int frames = 0;
        for (int i = 2; i <= recentCount; i++) {
            long time = recentFrames[(recentNext - i + length) % length];
            if (last - time > 5_000_000_000L) {
                break;
            }
            first = time;
            frames++;
        }
        return frames < 10 || last <= first ? 0 : (float) (frames * 1e9 / (last - first));
    }

    /** Remembers when a frame started while no frame scan runs, for the rate before the next one. */
    private void noteFrame(long now) {
        if (Minecraft.getMinecraft().theWorld == null) {
            recentCount = 0;
            return;
        }
        if (recentCount > 0
            && now - recentFrames[(recentNext - 1 + recentFrames.length) % recentFrames.length] > 1_000_000_000L) {
            // A pause (a loading screen, the game minimized): what came before is another time.
            recentCount = 0;
        }
        recentFrames[recentNext] = now;
        recentNext = (recentNext + 1) % recentFrames.length;
        recentCount = Math.min(recentCount + 1, recentFrames.length);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        long now = System.nanoTime();
        if (frameSession == null) {
            noteFrame(now);
            return;
        }
        if (frameStart != 0) {
            frameSession.endPeriod(frameStart, now - frameStart);
        }
        if (frameSession.isDone(now)) {
            ProfileSession session = frameSession;
            stopFrames(false);
            clientPhase = Phase.RECEIVING;
            try {
                Minecraft mc = Minecraft.getMinecraft();
                String source = mc.isSingleplayer() && mc.getIntegratedServer() != null ? mc.getIntegratedServer()
                    .getWorldName() : mc.func_147104_D() != null ? mc.func_147104_D().serverName : "";
                // What needs the game here; building the report, and finishing the allocations (which waits for the
                // Flight Recorder), on another thread.
                session.prepareReport();
                float rateBefore = frameRateBefore;
                Thread writer = new Thread(() -> {
                    try {
                        Report built = session.buildReport(source);
                        built.rateBefore = rateBefore;
                        session.completeReport(built);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        built.write(bytes);
                        byte[] data = bytes.toByteArray();
                        schedule(() -> {
                            clientPhase = Phase.IDLE;
                            finished(built, data);
                        });
                    } catch (Throwable t) {
                        LagFinder.LOG.error("Could not write the frame scan's report", t);
                        session.discard();
                        schedule(() -> {
                            clientPhase = Phase.IDLE;
                            tell("lagfinder.message.failed");
                        });
                    }
                }, "LagFinder report writer");
                writer.setDaemon(true);
                writer.start();
            } catch (Throwable t) {
                LagFinder.LOG.error("Could not build the frame scan's report", t);
                session.discard();
                clientPhase = Phase.IDLE;
                tell("lagfinder.message.failed");
            }
            return;
        }
        // The time taken to add up the last frame is left out of the next one, and of the scan's length.
        frameStart = System.nanoTime();
        frameSession.addOverhead(frameStart - now);
        frameSession.beginPeriod(frameStart);
    }

    /** The frame waited this long instead of working (the frame limiter, vsync): told by MixinMinecraft. */
    public void frameWaited(long nanos) {
        if (frameSession != null) {
            frameSession.addWait(nanos);
        }
    }

    /**
     * The frame rate frames are held to now: the limit set, or the screen's refresh with vsync, the lower of them; 0
     * when nothing holds them.
     */
    private static float frameCap() {
        Minecraft mc = Minecraft.getMinecraft();
        float cap = 0;
        if (mc.isFramerateLimitBelowMax()) {
            cap = mc.getLimitFramerate();
        }
        if (mc.gameSettings.enableVsync) {
            int refresh = 60;
            try {
                int frequency = org.lwjgl.opengl.Display.isFullscreen() ? org.lwjgl.opengl.Display.getDisplayMode()
                    .getFrequency() : 0;
                if (frequency <= 0) {
                    frequency = org.lwjgl.opengl.Display.getDesktopDisplayMode()
                        .getFrequency();
                }
                if (frequency > 0) {
                    refresh = frequency;
                }
            } catch (Throwable ignored) {
                // The usual refresh then.
            }
            cap = cap > 0 ? Math.min(cap, refresh) : refresh;
        }
        return cap;
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        serverHasMod = false;
        queryPending = true;
        schedule(this::resetServer);
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        serverHasMod = false;
        queryPending = false;
        schedule(() -> {
            resetServer();
            if (frameSession != null) {
                stopFrames();
                clientPhase = Phase.IDLE;
            }
        });
    }

    private void resetServer() {
        serverGeneration++;
        ClientReports.INSTANCE.reset();
        reading = false;
        cancelling = false;
        serverPaused = false;
        serverProtocol = 0;
        serverAllowed = false;
        teleportAllowed = false;
        serverState = Net.Status.IDLE;
        serverMaxSeconds = 0;
        parts = null;
        partsId = -1;
        queued = false;
        serverPhase = Phase.IDLE;
    }
}
