package lagfinder.network;

import java.util.function.Consumer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import lagfinder.LagFinder;
import lagfinder.core.LocationKind;
import lagfinder.core.ReportEntry;
import lagfinder.core.ReportFiles;
import lagfinder.core.ScanFocus;
import lagfinder.server.ServerScanner;

/**
 * The mod's messages. The client asks the server to start, cancel or report on a tick scan and to teleport it; the
 * server answers with its state and, at the end of a scan, the report in parts. The channel is optional: a client
 * joins a server without the mod (and the other way round), it only can't scan it.
 */
public final class Net {

    /** The version of the messages. */
    public static final int PROTOCOL = 1;
    /** Bytes of a report per message: well under the limits of a packet. */
    public static final int PART_SIZE = 30000;
    private static final int MAX_CLASS_NAME = 256;
    private static final int MAX_MESSAGE = 256;

    /** What the client does with the server's messages; set on the client only. */
    public static Consumer<Status> onStatus = status -> {};
    public static Consumer<ReportPart> onReportPart = part -> {};

    private Net() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(LagFinder.MODID, path);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(String.valueOf(PROTOCOL))
            .optional()
            .executesOn(HandlerThread.NETWORK);
        registrar.playToServer(StartScan.TYPE, StartScan.CODEC, StartScan::handle);
        registrar.playToServer(CancelScan.TYPE, CancelScan.CODEC, CancelScan::handle);
        registrar.playToServer(Query.TYPE, Query.CODEC, Query::handle);
        registrar.playToServer(Teleport.TYPE, Teleport.CODEC, Teleport::handle);
        registrar.playToClient(Status.TYPE, Status.CODEC, (status, context) -> onStatus.accept(status));
        registrar.playToClient(ReportPart.TYPE, ReportPart.CODEC, (part, context) -> {
            if (part.valid()) {
                onReportPart.accept(part);
            }
        });
    }

    public static void sendTo(CustomPacketPayload message, ServerPlayer player) {
        if (player.connection != null && player.connection.hasChannel(message.type())) {
            PacketDistributor.sendToPlayer(player, message);
        }
    }

    private static ServerPlayer player(IPayloadContext context) {
        return context.player() instanceof ServerPlayer player ? player : null;
    }

    /** Asks for a tick scan of the given length, for what it is for. */
    public record StartScan(int seconds, ScanFocus focus, boolean light) implements CustomPacketPayload {

        public static final Type<StartScan> TYPE = new Type<>(id("start_scan"));
        public static final StreamCodec<FriendlyByteBuf, StartScan> CODEC = StreamCodec.of((buf, message) -> {
            buf.writeVarInt(message.seconds);
            buf.writeByte(message.focus.ordinal());
            buf.writeBoolean(message.light);
        }, buf -> new StartScan(buf.readVarInt(), ScanFocus.of(buf.readByte()), buf.readBoolean()));

        @Override
        public Type<StartScan> type() {
            return TYPE;
        }

        private static void handle(StartScan message, IPayloadContext context) {
            ServerPlayer player = player(context);
            if (player == null) {
                return;
            }
            ServerScanner.INSTANCE.schedule(
                () -> ServerScanner.INSTANCE.start(player, message.seconds, ScanFocus.LAG, message.light));
            // Told at once that it arrived: the server's tick that takes it up may be long in coming.
            Status queued = new Status();
            queued.state = Status.QUEUED;
            context.reply(queued);
        }
    }

    public record CancelScan() implements CustomPacketPayload {

        public static final Type<CancelScan> TYPE = new Type<>(id("cancel_scan"));
        public static final StreamCodec<FriendlyByteBuf, CancelScan> CODEC = StreamCodec.unit(new CancelScan());

        @Override
        public Type<CancelScan> type() {
            return TYPE;
        }

        private static void handle(CancelScan message, IPayloadContext context) {
            ServerPlayer player = player(context);
            if (player != null) {
                ServerScanner.INSTANCE.schedule(() -> ServerScanner.INSTANCE.cancel(player));
            }
        }
    }

    /** Asks for the server's state, sent when the window opens. */
    public record Query(int protocol) implements CustomPacketPayload {

        public static final Type<Query> TYPE = new Type<>(id("query"));
        public static final StreamCodec<FriendlyByteBuf, Query> CODEC = StreamCodec
            .of((buf, message) -> buf.writeVarInt(message.protocol), buf -> new Query(buf.readVarInt()));

        @Override
        public Type<Query> type() {
            return TYPE;
        }

        private static void handle(Query message, IPayloadContext context) {
            ServerPlayer player = player(context);
            if (player != null) {
                ServerScanner.INSTANCE.schedule(() -> ServerScanner.INSTANCE.sendStatus(player, null));
            }
        }
    }

    /**
     * Teleports the player to a place in a dimension (an instance from a report). For an entity, to where the entity
     * is now, when it is still there: it has often walked off since the report was made.
     */
    public record Teleport(int dim, int x, int y, int z, int entityId, String entityClass)
        implements CustomPacketPayload {

        public static final Type<Teleport> TYPE = new Type<>(id("teleport"));
        public static final StreamCodec<FriendlyByteBuf, Teleport> CODEC = StreamCodec.of((buf, message) -> {
            buf.writeInt(message.dim);
            buf.writeInt(message.x);
            buf.writeInt(message.y);
            buf.writeInt(message.z);
            buf.writeInt(message.entityId);
            buf.writeUtf(message.entityClass, MAX_CLASS_NAME);
        },
            buf -> new Teleport(
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readUtf(MAX_CLASS_NAME)));

        public Teleport(int dim, int x, int y, int z) {
            this(dim, x, y, z, -1, "");
        }

        /** To the instance of a report: to the entity itself when it is one. */
        public static Teleport to(ReportEntry entry) {
            if (entry.kind == LocationKind.ENTITY) {
                String type = entry.detail == null ? "" : entry.detail;
                return new Teleport(
                    entry.dim,
                    entry.x,
                    entry.y,
                    entry.z,
                    entry.entityId,
                    type.length() > MAX_CLASS_NAME ? "" : type);
            }
            return new Teleport(entry.dim, entry.x, entry.y, entry.z);
        }

        @Override
        public Type<Teleport> type() {
            return TYPE;
        }

        private static void handle(Teleport message, IPayloadContext context) {
            ServerPlayer player = player(context);
            if (player != null) {
                ServerScanner.INSTANCE.schedule(() -> ServerScanner.INSTANCE.teleport(player, message));
            }
        }
    }

    /** The server's state for the player: whether it may scan, and the scan running. */
    public static final class Status implements CustomPacketPayload {

        /**
         * QUEUED: the start of a scan reached the server, which takes it up on its next tick (sent at once from the
         * network thread: the tick may be long on a server that lags, which is what is being looked for).
         */
        public static final int IDLE = 0, RUNNING = 1, BUSY = 2, SENDING = 3, QUEUED = 4;

        public static final Type<Status> TYPE = new Type<>(id("status"));
        public static final StreamCodec<FriendlyByteBuf, Status> CODEC = StreamCodec.of((buf, status) -> {
            buf.writeVarInt(status.protocol);
            buf.writeBoolean(status.allowed);
            buf.writeBoolean(status.teleportAllowed);
            buf.writeByte(status.state);
            buf.writeFloat(status.progress);
            buf.writeVarInt(status.secondsLeft);
            buf.writeUtf(status.message == null ? "" : status.message, MAX_MESSAGE);
            buf.writeVarInt(status.totalSeconds);
            buf.writeVarInt(status.maxSeconds);
            buf.writeBoolean(status.paused);
        }, buf -> {
            Status status = new Status();
            status.protocol = buf.readVarInt();
            status.allowed = buf.readBoolean();
            status.teleportAllowed = buf.readBoolean();
            status.state = buf.readByte();
            status.progress = buf.readFloat();
            status.secondsLeft = buf.readVarInt();
            status.message = buf.readUtf(MAX_MESSAGE);
            status.totalSeconds = buf.readVarInt();
            status.maxSeconds = buf.readVarInt();
            status.paused = buf.readBoolean();
            return status;
        });

        public int protocol = PROTOCOL;
        public boolean allowed;
        public boolean teleportAllowed;
        public int state;
        public float progress;
        public int secondsLeft;
        /** A translation key of something to tell the player, or empty. */
        public String message = "";
        /** How long the scan running lasts in all (s), as the server allows it; 0 when none runs. */
        public int totalSeconds;
        /** The longest scan the server allows (s). */
        public int maxSeconds;
        /** The scan running stands still: the single player game is paused. */
        public boolean paused;

        @Override
        public Type<Status> type() {
            return TYPE;
        }
    }

    /** A part of a finished report (gzipped), sent over several messages. */
    public static final class ReportPart implements CustomPacketPayload {

        public static final Type<ReportPart> TYPE = new Type<>(id("report_part"));
        public static final StreamCodec<FriendlyByteBuf, ReportPart> CODEC = StreamCodec.of((buf, part) -> {
            buf.writeInt(part.id);
            buf.writeInt(part.index);
            buf.writeInt(part.total);
            buf.writeByteArray(part.data);
        }, buf -> {
            int id = buf.readInt();
            int index = buf.readInt();
            int total = buf.readInt();
            byte[] data = buf.readByteArray(PART_SIZE);
            return new ReportPart(id, index, total, data);
        });

        public final int id, index, total;
        public final byte[] data;

        public ReportPart(int id, int index, int total, byte[] data) {
            this.id = id;
            this.index = index;
            this.total = total;
            this.data = data;
        }

        public boolean valid() {
            return total > 0 && total <= (ReportFiles.MAX_BYTES + PART_SIZE - 1) / PART_SIZE
                && index >= 0
                && index < total
                && data != null
                && data.length > 0
                && data.length <= PART_SIZE;
        }

        @Override
        public Type<ReportPart> type() {
            return TYPE;
        }
    }
}
