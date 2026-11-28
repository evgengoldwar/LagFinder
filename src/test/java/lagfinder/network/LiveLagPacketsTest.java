package lagfinder.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class LiveLagPacketsTest {

    @Test
    public void preservesBlockPositionsAndEntityIds() {
        LiveLagPackets.Batch sent = new LiveLagPackets.Batch(
            7,
            -1,
            Arrays.asList(new LiveLagPackets.Hit(-32, 65, -12, LiveLagPackets.BLOCK),
                new LiveLagPackets.Hit(20, 63, -9, 42)));
        ByteBuf data = Unpooled.buffer();
        try {
            sent.toBytes(data);
            LiveLagPackets.Batch received = new LiveLagPackets.Batch();
            received.fromBytes(data);
            assertEquals(7, received.id);
            assertEquals(-1, received.dimension);
            assertEquals(2, received.hits.size());
            LiveLagPackets.Hit block = received.hits.get(0);
            assertEquals(-32, block.x);
            assertEquals(65, block.y);
            assertEquals(-12, block.z);
            assertEquals(LiveLagPackets.BLOCK, block.entityId);
            assertEquals(42, received.hits.get(1).entityId);
        } finally {
            data.release();
        }
    }

    @Test
    public void rejectsTruncatedAndOversizedBatches() {
        ByteBuf data = Unpooled.buffer();
        try {
            LiveLagPackets.Batch received = new LiveLagPackets.Batch();
            data.writeInt(7).writeInt(0).writeByte(1).writeInt(20);
            received.fromBytes(data);
            assertTrue(received.hits.isEmpty());

            data.clear();
            int count = LiveLagPackets.MAX_HITS + 1;
            data.writeInt(7).writeInt(0).writeByte(count).writeZero(count * 16);
            received.fromBytes(data);
            assertTrue(received.hits.isEmpty());
        } finally {
            data.release();
        }
    }
}
