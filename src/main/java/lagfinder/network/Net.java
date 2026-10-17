package lagfinder.network;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import lagfinder.LagFinder;
import lagfinder.core.LocationKind;
import lagfinder.core.ReportEntry;
import lagfinder.core.ReportFiles;
import lagfinder.core.ScanFocus;
import lagfinder.server.ServerReports;
import lagfinder.server.ServerScanner;

/**
 * The mod's channel. The client asks the server to start, cancel or report on a tick scan and to teleport it; the
 * server answers with its state and, at the end of a scan, the report in parts. Servers without the mod never answer,
 * so the client knows it can only scan frames there.
 */
public final class Net {

    /**
     * The version of the messages: 2 and later servers tell at once that the start of a scan arrived
     * ({@link Status#QUEUED}) and whether a scan stands paused.
     */
    public static final int PROTOCOL = 3;
    /** Bytes of a report per message: well under the limits of a packet. */
    public static final int PART_SIZE = 30000;

    private static SimpleNetworkWrapper channel;

    private Net() {}

    public static void register() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(LagFinder.MODID);
        channel.registerMessage(StartScan.Handler.class, StartScan.class, 0, Side.SERVER);
        channel.registerMessage(CancelScan.Handler.class, CancelScan.class, 1, Side.SERVER);
        channel.registerMessage(Query.Handler.class, Query.class, 2, Side.SERVER);
        channel.registerMessage(Teleport.Handler.class, Teleport.class, 3, Side.SERVER);
        channel.registerMessage(Status.Handler.class, Status.class, 4, Side.CLIENT);
        channel.registerMessage(ReportPart.Handler.class, ReportPart.class, 5, Side.CLIENT);
        channel.registerMessage(ArchiveRequest.Handler.class, ArchiveRequest.class, 6, Side.SERVER);
        channel.registerMessage(ArchiveCatalog.Handler.class, ArchiveCatalog.class, 7, Side.CLIENT);
        channel.registerMessage(ArchivePart.Handler.class, ArchivePart.class, 8, Side.CLIENT);
    }

    public static void sendToServer(IMessage message) {
        channel.sendToServer(message);
    }

    public static void sendTo(IMessage message, EntityPlayerMP player) {
        channel.sendTo(message, player);
    }

    /** Asks for a tick scan of the given length, for what it is for. */
    public static final class StartScan implements IMessage {

        public int seconds;
        public ScanFocus focus = ScanFocus.LAG;
        /** The client understands being told at once that the start arrived ({@link Status#QUEUED}). */
        public boolean takesQueued;
        /** A light scan: one tick in a few is measured (older clients don't send it: a full scan). */
        public boolean light;

        public StartScan() {}

        public StartScan(int seconds, ScanFocus focus, boolean light) {
            this.seconds = seconds;
            this.focus = focus;
            this.light = light;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            seconds = buf.readInt();
            // Clients from before the scans had a focus send only the length.
            focus = buf.isReadable() ? ScanFocus.of(buf.readByte()) : ScanFocus.LAG;
            takesQueued = buf.isReadable() && buf.readBoolean();
            light = buf.isReadable() && buf.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(seconds);
            buf.writeByte(focus.ordinal());
            buf.writeBoolean(true);
            buf.writeBoolean(light);
        }

        public static final class Handler implements IMessageHandler<StartScan, IMessage> {

            @Override
            public IMessage onMessage(StartScan message, MessageContext ctx) {
                EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                ServerScanner.INSTANCE.schedule(
                    () -> ServerScanner.INSTANCE.start(player, message.seconds, message.focus, message.light));
                if (!message.takesQueued) {
                    // An older client would read it as a refusal.
                    return null;
                }
                // Told at once that it arrived: the server's tick that takes it up may be long in coming.
                Status queued = new Status();
                queued.state = Status.QUEUED;
                return queued;
            }
        }
    }

    public static final class CancelScan implements IMessage {

        @Override
        public void fromBytes(ByteBuf buf) {}

        @Override
        public void toBytes(ByteBuf buf) {}

        public static final class Handler implements IMessageHandler<CancelScan, IMessage> {

            @Override
            public IMessage onMessage(CancelScan message, MessageContext ctx) {
                EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                ServerScanner.INSTANCE.schedule(() -> ServerScanner.INSTANCE.cancel(player));
                return null;
            }
        }
    }

    /** Asks for the server's state, sent when the window opens. */
    public static final class Query implements IMessage {

        public int protocol = PROTOCOL;

        @Override
        public void fromBytes(ByteBuf buf) {
            protocol = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(protocol);
        }

        public static final class Handler implements IMessageHandler<Query, IMessage> {

            @Override
            public IMessage onMessage(Query message, MessageContext ctx) {
                EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                ServerScanner.INSTANCE.schedule(() -> ServerScanner.INSTANCE.sendStatus(player, null));
                return null;
            }
        }
    }

    /**
     * Teleports the player to a place in a dimension (an instance from a report). For an entity, to where the entity
     * is now, when it is still there: it has often walked off since the report was made.
     */
    public static final class Teleport implements IMessage {

        public int dim, x, y, z;
        /** The entity to go to, -1 for a place; its class, so that another one given its id isn't taken for it. */
        public int entityId = -1;
        public String entityClass = "";

        public Teleport() {}

        public Teleport(int dim, int x, int y, int z) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Teleport(int dim, int x, int y, int z, int entityId, String entityClass) {
            this(dim, x, y, z);
            this.entityId = entityId;
            this.entityClass = entityClass == null ? "" : entityClass;
        }

        /** To the instance of a report: to the entity itself when it is one. */
        public static Teleport to(ReportEntry entry) {
            return entry.kind == LocationKind.ENTITY
                ? new Teleport(entry.dim, entry.x, entry.y, entry.z, entry.entityId, entry.detail)
                : new Teleport(entry.dim, entry.x, entry.y, entry.z);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            dim = buf.readInt();
            x = buf.readInt();
            y = buf.readInt();
            z = buf.readInt();
            // Older clients send only the place.
            if (buf.isReadable()) {
                entityId = buf.readInt();
                entityClass = ByteBufUtils.readUTF8String(buf);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(dim);
            buf.writeInt(x);
            buf.writeInt(y);
            buf.writeInt(z);
            buf.writeInt(entityId);
            ByteBufUtils.writeUTF8String(buf, entityClass);
        }

        public static final class Handler implements IMessageHandler<Teleport, IMessage> {

            @Override
            public IMessage onMessage(Teleport message, MessageContext ctx) {
                EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                ServerScanner.INSTANCE.schedule(() -> ServerScanner.INSTANCE.teleport(player, message));
                return null;
            }
        }
    }

    /** The server's state for the player: whether it may scan, and the scan running. */
    public static final class Status implements IMessage {

        /**
         * QUEUED: the start of a scan reached the server, which takes it up on its next tick (sent at once from the
         * network thread: the tick may be long on a server that lags, which is what is being looked for).
         */
        public static final int IDLE = 0, RUNNING = 1, BUSY = 2, SENDING = 3, QUEUED = 4;

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
        public void fromBytes(ByteBuf buf) {
            protocol = buf.readInt();
            allowed = buf.readBoolean();
            teleportAllowed = buf.readBoolean();
            state = buf.readByte();
            progress = buf.readFloat();
            secondsLeft = buf.readInt();
            message = ByteBufUtils.readUTF8String(buf);
            // Servers from before these were sent leave them out.
            if (buf.readableBytes() >= 8) {
                totalSeconds = buf.readInt();
                maxSeconds = buf.readInt();
            }
            paused = buf.isReadable() && buf.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(protocol);
            buf.writeBoolean(allowed);
            buf.writeBoolean(teleportAllowed);
            buf.writeByte(state);
            buf.writeFloat(progress);
            buf.writeInt(secondsLeft);
            ByteBufUtils.writeUTF8String(buf, message == null ? "" : message);
            buf.writeInt(totalSeconds);
            buf.writeInt(maxSeconds);
            buf.writeBoolean(paused);
        }

        public static final class Handler implements IMessageHandler<Status, IMessage> {

            @Override
            public IMessage onMessage(Status message, MessageContext ctx) {
                LagFinder.proxy.onStatus(message);
                return null;
            }
        }
    }

    /** A part of a finished report (gzipped), sent over several messages. */
    public static final class ReportPart implements IMessage {

        public int id, index, total;
        public byte[] data;

        public ReportPart() {}

        public ReportPart(int id, int index, int total, byte[] data) {
            this.id = id;
            this.index = index;
            this.total = total;
            this.data = data;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            if (buf.readableBytes() < 16 || buf.readableBytes() > 16 + PART_SIZE) {
                return;
            }
            id = buf.readInt();
            index = buf.readInt();
            total = buf.readInt();
            int length = buf.readInt();
            if (total < 1 || total > (ReportFiles.MAX_BYTES + PART_SIZE - 1) / PART_SIZE
                || index < 0
                || index >= total
                || length < 1
                || length > PART_SIZE
                || length != buf.readableBytes()) {
                return;
            }
            data = new byte[length];
            buf.readBytes(data);
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
        public void toBytes(ByteBuf buf) {
            buf.writeInt(id);
            buf.writeInt(index);
            buf.writeInt(total);
            buf.writeInt(data.length);
            buf.writeBytes(data);
        }

        public static final class Handler implements IMessageHandler<ReportPart, IMessage> {

            @Override
            public IMessage onMessage(ReportPart message, MessageContext ctx) {
                LagFinder.proxy.onReportPart(message);
                return null;
            }
        }
    }

    public static final class ArchiveRequest implements IMessage {

        public static final int LIST = 0, OPEN = 1;
        public int id, action;
        public String name = "";
        public boolean valid;

        public ArchiveRequest() {}

        public ArchiveRequest(int id, int action, String name) {
            this.id = id;
            this.action = action;
            this.name = name;
            this.valid = true;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            if (buf.readableBytes() < 6 || buf.readableBytes() > 6 + ReportFiles.MAX_NAME) {
                return;
            }
            id = buf.readInt();
            action = buf.readUnsignedByte();
            name = readName(buf);
            valid = id > 0 && !buf.isReadable()
                && (action == LIST && name.isEmpty() || action == OPEN && ReportFiles.describe(name, 1) != null);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(id);
            buf.writeByte(action);
            writeName(buf, name);
        }

        public static final class Handler implements IMessageHandler<ArchiveRequest, IMessage> {

            @Override
            public IMessage onMessage(ArchiveRequest message, MessageContext ctx) {
                if (!message.valid) {
                    return null;
                }
                EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                if (!ServerReports.INSTANCE.enqueue(player, message)) {
                    return new ArchiveCatalog(message.id, ArchiveCatalog.BUSY, new ArrayList<>());
                }
                return null;
            }
        }
    }

    private static String readName(ByteBuf buf) {
        int length = buf.readUnsignedByte();
        if (length > ReportFiles.MAX_NAME || length > buf.readableBytes()) {
            throw new IllegalArgumentException("Invalid archive filename length");
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        for (byte value : bytes) {
            if (value < 32 || value > 126) {
                throw new IllegalArgumentException("Invalid archive filename");
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void writeName(ByteBuf buf, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > ReportFiles.MAX_NAME) {
            throw new IllegalArgumentException("Archive filename is too long");
        }
        buf.writeByte(bytes.length);
        buf.writeBytes(bytes);
    }

    public static final class ArchiveCatalog implements IMessage {

        public static final int OK = 0, DENIED = 1, FAILED = 2, BUSY = 3;
        public int id, status;
        public final List<ReportFiles.Saved> saved = new ArrayList<>();
        public boolean valid;

        public ArchiveCatalog() {}

        public ArchiveCatalog(int id, int status, List<ReportFiles.Saved> saved) {
            this.id = id;
            this.status = status;
            this.saved.addAll(saved);
            valid = true;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            if (buf.readableBytes() < 6 || buf.readableBytes() > 6 + ReportFiles.KEEP * (17 + ReportFiles.MAX_NAME)) {
                return;
            }
            id = buf.readInt();
            status = buf.readUnsignedByte();
            int count = buf.readUnsignedByte();
            if (id < 1 || status > BUSY || count > ReportFiles.KEEP || status != OK && count != 0) {
                return;
            }
            for (int i = 0; i < count; i++) {
                String name = readName(buf);
                if (buf.readableBytes() < 16) {
                    saved.clear();
                    return;
                }
                long time = buf.readLong();
                ReportFiles.Saved entry = ReportFiles.describe(name, buf.readLong(), time);
                if (entry == null || entry.side != lagfinder.core.ScanSide.SERVER) {
                    saved.clear();
                    return;
                }
                saved.add(entry);
            }
            valid = !buf.isReadable();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            if (saved.size() > ReportFiles.KEEP) {
                throw new IllegalArgumentException("Archive catalog is too large");
            }
            buf.writeInt(id);
            buf.writeByte(status);
            buf.writeByte(saved.size());
            for (ReportFiles.Saved entry : saved) {
                writeName(buf, entry.name);
                buf.writeLong(entry.time);
                buf.writeLong(entry.size);
            }
        }

        public static final class Handler implements IMessageHandler<ArchiveCatalog, IMessage> {

            @Override
            public IMessage onMessage(ArchiveCatalog message, MessageContext ctx) {
                if (message.valid) {
                    LagFinder.proxy.onArchiveCatalog(message);
                }
                return null;
            }
        }
    }

    public static final class ArchivePart implements IMessage {

        public ReportPart part = new ReportPart();

        public ArchivePart() {}

        public ArchivePart(int id, int index, int total, byte[] data) {
            part = new ReportPart(id, index, total, data);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            part.fromBytes(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            part.toBytes(buf);
        }

        public static final class Handler implements IMessageHandler<ArchivePart, IMessage> {

            @Override
            public IMessage onMessage(ArchivePart message, MessageContext ctx) {
                if (message.part.valid()) {
                    LagFinder.proxy.onArchivePart(message);
                }
                return null;
            }
        }
    }
}
