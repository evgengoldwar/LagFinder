package lagfinder.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import lagfinder.LagFinder;
import lagfinder.server.LiveLagTracker;

public final class LiveLagPackets {

    public static final int PROTOCOL = 4;
    public static final int MAX_HITS = 64;
    public static final int BLOCK = -1;

    private LiveLagPackets() {}

    public static final class Subscription implements IMessage {

        public int id;
        public boolean enabled;
        private boolean valid;

        public Subscription() {}

        public Subscription(int id, boolean enabled) {
            this.id = id;
            this.enabled = enabled;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            valid = buf.readableBytes() == 5;
            if (valid) {
                id = buf.readInt();
                enabled = buf.readBoolean();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(id);
            buf.writeBoolean(enabled);
        }

        public static final class Handler implements IMessageHandler<Subscription, IMessage> {

            @Override
            public IMessage onMessage(Subscription message, MessageContext ctx) {
                if (message.valid) {
                    LiveLagTracker.INSTANCE.request(ctx.getServerHandler().playerEntity, message);
                }
                return null;
            }
        }
    }

    public static final class Hit {

        public final int x, y, z, entityId;

        public Hit(int x, int y, int z, int entityId) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.entityId = entityId;
        }
    }

    public static final class Batch implements IMessage {

        public int id, dimension;
        public List<Hit> hits = Collections.emptyList();

        public Batch() {}

        public Batch(int id, int dimension, List<Hit> hits) {
            this.id = id;
            this.dimension = dimension;
            this.hits = hits;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            hits = Collections.emptyList();
            if (buf.readableBytes() < 9) {
                return;
            }
            id = buf.readInt();
            dimension = buf.readInt();
            int count = buf.readUnsignedByte();
            if (count > MAX_HITS || buf.readableBytes() != count * 16) {
                return;
            }
            hits = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                hits.add(new Hit(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()));
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(id);
            buf.writeInt(dimension);
            buf.writeByte(hits.size());
            for (Hit hit : hits) {
                buf.writeInt(hit.x);
                buf.writeInt(hit.y);
                buf.writeInt(hit.z);
                buf.writeInt(hit.entityId);
            }
        }

        public static final class Handler implements IMessageHandler<Batch, IMessage> {

            @Override
            public IMessage onMessage(Batch message, MessageContext ctx) {
                if (!message.hits.isEmpty()) {
                    LagFinder.proxy.onLiveLag(message);
                }
                return null;
            }
        }
    }
}
