package lagfinder.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.TimeZone;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lagfinder.core.ReportFiles;

public class ArchivePacketTest {

    private static final String NAME = "2026-10-09_18-30-00_TPS-STEADY.lfr";

    @Test
    public void roundTripsListAndOpenRequests() {
        for (int action = 0; action < 2; action++) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                new Net.ArchiveRequest(7, action, action == 0 ? "" : NAME).toBytes(buffer);
                Net.ArchiveRequest decoded = new Net.ArchiveRequest();
                decoded.fromBytes(buffer);
                assertTrue(decoded.valid);
                assertEquals(7, decoded.id);
                assertEquals(action, decoded.action);
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    public void rejectsTraversalInvalidActionsAndOversizedRequests() {
        for (Net.ArchiveRequest request : new Net.ArchiveRequest[] {
            new Net.ArchiveRequest(1, Net.ArchiveRequest.OPEN, "../" + NAME), new Net.ArchiveRequest(1, 3, ""),
            new Net.ArchiveRequest(-1, 0, "") }) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                request.toBytes(buffer);
                Net.ArchiveRequest decoded = new Net.ArchiveRequest();
                decoded.fromBytes(buffer);
                assertFalse(decoded.valid);
            } finally {
                buffer.release();
            }
        }
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeZero(ReportFiles.MAX_NAME + 7);
            Net.ArchiveRequest decoded = new Net.ArchiveRequest();
            decoded.fromBytes(buffer);
            assertFalse(decoded.valid);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void roundTripsCatalogMetadata() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new Net.ArchiveCatalog(2, Net.ArchiveCatalog.OK, Collections.singletonList(ReportFiles.describe(NAME, 500)))
                .toBytes(buffer);
            Net.ArchiveCatalog decoded = new Net.ArchiveCatalog();
            decoded.fromBytes(buffer);
            assertTrue(decoded.valid);
            assertEquals(NAME, decoded.saved.get(0).name);
            assertEquals(500, decoded.saved.get(0).size);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void refusesOversizedCatalogsAndDataOnDeniedResponses() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeInt(1)
                .writeByte(Net.ArchiveCatalog.DENIED)
                .writeByte(1);
            Net.ArchiveCatalog decoded = new Net.ArchiveCatalog();
            decoded.fromBytes(buffer);
            assertFalse(decoded.valid);
            assertTrue(decoded.saved.isEmpty());
            buffer.clear()
                .writeInt(1)
                .writeByte(0)
                .writeByte(ReportFiles.KEEP + 1);
            decoded = new Net.ArchiveCatalog();
            decoded.fromBytes(buffer);
            assertFalse(decoded.valid);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void boundsPartSizeTotalCountAndDeclaredLengthBeforeAllocation() {
        for (int[] fields : new int[][] { { 0, Integer.MAX_VALUE, 1, 1 }, { 0, 1, Integer.MAX_VALUE, 1 },
            { -1, 1, 1, 1 }, { 0, 1, 10, 1 }, { 0, 1, 0, 0 } }) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                buffer.writeInt(1)
                    .writeInt(fields[0])
                    .writeInt(fields[1])
                    .writeInt(fields[2])
                    .writeZero(fields[3]);
                Net.ReportPart part = new Net.ReportPart();
                part.fromBytes(buffer);
                assertFalse(part.valid());
                assertNull(part.data);
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    public void roundTripsArchivePartsAtTheMaximumPacketSize() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new Net.ArchivePart(3, 0, 1, new byte[Net.PART_SIZE]).toBytes(buffer);
            Net.ArchivePart decoded = new Net.ArchivePart();
            decoded.fromBytes(buffer);
            assertTrue(decoded.part.valid());
            assertEquals(3, decoded.part.id);
            assertEquals(Net.PART_SIZE, decoded.part.data.length);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void preservesServerTimestampsAcrossClientTimezones() {
        TimeZone original = TimeZone.getDefault();
        ByteBuf buffer = Unpooled.buffer();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            ReportFiles.Saved saved = ReportFiles.describe(NAME, 500);
            new Net.ArchiveCatalog(2, Net.ArchiveCatalog.OK, Collections.singletonList(saved)).toBytes(buffer);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Vladivostok"));
            Net.ArchiveCatalog decoded = new Net.ArchiveCatalog();
            decoded.fromBytes(buffer);
            assertTrue(decoded.valid);
            assertEquals(saved.time, decoded.saved.get(0).time);
        } finally {
            TimeZone.setDefault(original);
            buffer.release();
        }
    }

}
