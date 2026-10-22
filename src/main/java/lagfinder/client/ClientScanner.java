package lagfinder.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

import lagfinder.LagFinder;
import lagfinder.client.gui.GuiLagFinder;
import lagfinder.core.LagFilter;
import lagfinder.core.Report;
import lagfinder.core.ReportFiles;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;
import lagfinder.core.ScanSide;
import lagfinder.network.Net;

/**
 * The client's side of scanning: asks the server for tick scans and follows them, puts the server's report together
 * from its parts, and keeps the last report for the window.
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

    private boolean serverAllowed, teleportAllowed;
    private int serverState = Net.Status.IDLE;
    private Phase serverPhase = Phase.IDLE;
    /** What the report of a tick scan leaves out, null for nothing. */
    private LagFilter filter;
    private float progress;
    private int secondsLeft;
    private long requestedAt;
    private int requestedSeconds;
    private long statusAt;
    /** Asks the server for its state once the player is in the world. */
    private volatile boolean queryPending;

    /** Parts of a report taken at most: about 64 MB, far more than any report; a claim of more is refused. */
    private static final int MAX_PARTS = (ReportFiles.MAX_BYTES + Net.PART_SIZE - 1) / Net.PART_SIZE;
    private int partsId = -1;
    private byte[][] parts;
    private int partsReceived;
    private int partsBytes;
    private boolean reading;
    private boolean queued;
    private int serverMaxSeconds;
    private boolean serverPaused;
    private boolean cancelling;
    private int serverGeneration;

    private Report report;
    private boolean unseen;
    private String message;
    private Object[] messageArgs = new Object[0];
    private long messageAt;

    private ClientScanner() {}

    public void schedule(Runnable task) {
        tasks.add(task);
    }

    /** Whether the server has LagFinder: it took the mod's messages when the player joined. */
    public boolean serverHasMod() {
        ClientPacketListener connection = Minecraft.getInstance()
            .getConnection();
        return connection != null && connection.hasChannel(Net.Query.TYPE);
    }

    public boolean serverAllowed() {
        return serverHasMod() && serverAllowed;
    }

    public boolean teleportAllowed() {
        return serverHasMod() && teleportAllowed;
    }

    /** Someone else's scan runs on the server. */
    public boolean serverBusy() {
        return serverState == Net.Status.BUSY;
    }

    public Phase phase() {
        return serverPhase;
    }

    public ScanSide side() {
        return ScanSide.SERVER;
    }

    public ScanFocus focus() {
        return ScanFocus.LAG;
    }

    public boolean isActive() {
        return serverPhase != Phase.IDLE;
    }

    /** How far along the scan is, 0 to 1. */
    public float progress() {
        if (serverPhase == Phase.RECEIVING) {
            return parts == null ? 1f : partsReceived / (float) parts.length;
        }
        if (serverPhase == Phase.RUNNING && requestedSeconds > 0 && !serverPaused) {
            float since = (System.currentTimeMillis() - statusAt) / 1000f;
            return Math.min(1f, progress + Math.min(1.5f, since) / requestedSeconds);
        }
        return progress;
    }

    public int secondsLeft() {
        return serverPhase != Phase.IDLE ? secondsLeft : 0;
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

    public String message() {
        return message != null && System.currentTimeMillis() - messageAt < 6000 ? message : null;
    }

    public String messageText() {
        String key = message();
        return key == null ? null : I18n.get(key, messageArgs);
    }

    public int serverMaxSeconds() {
        return serverMaxSeconds;
    }

    private void tell(String key, Object... args) {
        message = key == null || key.isEmpty() ? null : key;
        messageArgs = args;
        messageAt = System.currentTimeMillis();
    }

    public void sendToServer(CustomPacketPayload payload) {
        if (serverHasMod()) {
            PacketDistributor.sendToServer(payload);
        }
    }

    /** Asks the server for its state again (the window opened). */
    public void query() {
        sendToServer(new Net.Query(Net.PROTOCOL));
    }

    /**
     * Starts a scan of the server's ticks; the report leaves out what the filter says (null: nothing is left out), and
     * a {@code light} one measures one tick in a few.
     */
    public void start(int seconds, LagFilter filter, boolean light) {
        if (isActive()) {
            return;
        }
        if (!serverAllowed()) {
            tell(serverHasMod() ? "lagfinder.message.not_allowed" : "lagfinder.message.no_server_mod");
            return;
        }
        if (cancelling) {
            return;
        }
        this.filter = filter;
        progress = 0;
        tell(null);
        serverPhase = Phase.REQUESTED;
        queued = false;
        serverPaused = false;
        requestedAt = System.currentTimeMillis();
        requestedSeconds = seconds;
        statusAt = requestedAt;
        secondsLeft = seconds;
        sendToServer(new Net.StartScan(seconds, ScanFocus.LAG, light));
    }

    /** Cancels the scan. */
    public void cancel() {
        if (serverPhase == Phase.REQUESTED || serverPhase == Phase.RUNNING) {
            sendToServer(new Net.CancelScan());
            serverPhase = Phase.IDLE;
            cancelling = true;
            parts = null;
            partsId = -1;
        }
    }

    /** The server's state arrived (any thread). */
    public void onStatus(Net.Status status) {
        schedule(() -> {
            if (status.state == Net.Status.QUEUED) {
                if (serverPhase == Phase.REQUESTED) {
                    queued = true;
                }
                return;
            }
            serverAllowed = status.allowed;
            teleportAllowed = status.teleportAllowed;
            serverState = status.state;
            serverPaused = status.paused;
            if (status.maxSeconds > 0) {
                serverMaxSeconds = status.maxSeconds;
            }
            if (!status.message.isEmpty()) {
                tell(status.message);
            }
            if (cancelling) {
                if (status.state == Net.Status.RUNNING || status.state == Net.Status.SENDING) {
                    return;
                }
                cancelling = false;
            }
            switch (status.state) {
                case Net.Status.RUNNING:
                    if (serverPhase == Phase.REQUESTED && status.totalSeconds > 0
                        && status.totalSeconds < requestedSeconds) {
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
                    if (serverPhase != Phase.IDLE) {
                        serverPhase = Phase.RECEIVING;
                    }
                    break;
                default:
                    if (serverPhase == Phase.REQUESTED || serverPhase == Phase.RUNNING) {
                        serverPhase = Phase.IDLE;
                    } else if (serverPhase == Phase.RECEIVING && !reading) {
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
            if (serverPhase == Phase.IDLE || cancelling || reading || part.total > MAX_PARTS || !part.valid()) {
                return;
            }
            if (part.id != partsId || parts == null || parts.length != part.total) {
                partsId = part.id;
                parts = new byte[part.total][];
                partsReceived = 0;
                partsBytes = 0;
            }
            if (parts[part.index] != null) {
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
                            leftOut.apply(read);
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            read.write(bytes);
                            data = bytes.toByteArray();
                        }
                        byte[] kept = data;
                        schedule(() -> {
                            if (readFor != serverGeneration) {
                                publish(read, kept);
                                return;
                            }
                            reading = false;
                            serverPhase = Phase.IDLE;
                            publish(read, kept);
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

    /** The report of the scan is ready: kept for the window, saved, and the player told. */
    private void publish(Report finished, byte[] data) {
        report = finished;
        unseen = true;
        GuiLagFinder.forgetReopen();
        ReportStore.save(finished, data);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !(mc.screen instanceof GuiLagFinder)) {
            mc.player.displayClientMessage(
                Component.literal("[LagFinder] ")
                    .append(
                        Component.translatable("lagfinder.chat.ready", KeyHandler.keyName())
                            .withStyle(ChatFormatting.AQUA)),
                false);
        }
    }

    void onClientTick() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LagFinder.LOG.error("LagFinder task failed", t);
            }
        }
        if (queryPending && Minecraft.getInstance().player != null) {
            queryPending = false;
            query();
        }
        if (serverPhase == Phase.REQUESTED && !queued && System.currentTimeMillis() - requestedAt > 15_000) {
            serverPhase = Phase.IDLE;
            tell("lagfinder.message.no_answer");
        }
    }

    void onConnect() {
        queryPending = true;
        schedule(this::resetServer);
    }

    void onDisconnect() {
        queryPending = false;
        schedule(this::resetServer);
        Highlighter.INSTANCE.clear();
    }

    private void resetServer() {
        serverGeneration++;
        reading = false;
        cancelling = false;
        serverPaused = false;
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
