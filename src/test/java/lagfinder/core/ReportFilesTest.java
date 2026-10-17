package lagfinder.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReportFilesTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static Report report() {
        Report report = new Report();
        report.mode = ScanMode.TPS;
        report.focus = ScanFocus.STEADY;
        report.startedAt = 1791540000000L;
        report.durationSec = 10;
        report.periods = 200;
        report.bucketSec = 10;
        report.spikeMs = 10;
        report.rate = new float[] { 20 };
        report.avgMs = new float[] { 5 };
        report.maxMs = new float[] { 12 };
        report.counts = new int[] { 200 };
        report.source = "GTNH 2.9";
        report.steadyMedianTickMs = 4;
        report.steadyP90TickMs = 9;
        return report;
    }

    private static byte[] encode(Report report) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        report.write(bytes);
        return bytes.toByteArray();
    }

    @Test
    public void savesAndReadsSteadyAndWorldDataWithoutChangingTheFormat() throws Exception {
        Report report = report();
        ReportEntry world = new ReportEntry();
        world.category = Category.WORLDS;
        world.name = "Overworld";
        world.kind = LocationKind.NONE;
        world.medianMs = 2;
        world.p90Ms = 3;
        world.gtActiveMs = 40;
        world.gtIdleMs = 20;
        report.categories.add(world);
        report.dimensions.put(0, "Overworld");
        report.profile = new Profile();
        report.memory = new HeapProfile();
        report.allocations = new AllocationProfile();
        Path folder = temporary.newFolder()
            .toPath();
        ReportFiles.save(folder.toFile(), report, encode(report));
        List<ReportFiles.Saved> saved = ReportFiles.list(folder.toFile());
        assertEquals(1, saved.size());
        Report loaded = ReportFiles.load(folder.toFile(), saved.get(0).name);
        assertEquals(ScanFocus.STEADY, loaded.focus);
        assertEquals(report.source, loaded.source);
        assertEquals(4, loaded.steadyMedianTickMs, 0);
        assertEquals(9, loaded.steadyP90TickMs, 0);
        assertEquals("Overworld", loaded.dimensionName(0));
        assertEquals(2, loaded.category(Category.WORLDS).medianMs, 0);
        assertEquals(40, loaded.category(Category.WORLDS).gtActiveMs, 0);
        assertNotNull(loaded.profile);
        assertNotNull(loaded.memory);
        assertNotNull(loaded.allocations);
    }

    @Test
    public void keepsLegacyNamesAndVersionOneReportsReadable() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(0x4C46524E);
            out.writeShort(1);
            out.writeByte(ScanMode.TPS.ordinal());
            out.writeLong(1);
            out.writeFloat(10);
            out.writeUTF("legacy");
            out.writeInt(200);
            out.writeFloat(10);
            out.writeFloat(10);
            out.writeShort(1);
            out.writeFloat(20);
            out.writeFloat(5);
            out.writeFloat(10);
            out.writeInt(200);
            out.writeShort(0);
            out.writeShort(0);
        }
        Path folder = temporary.newFolder()
            .toPath();
        String name = "2026-10-09_18-30-00_TPS.lfr";
        Files.write(folder.resolve(name), bytes.toByteArray());
        assertEquals(
            ScanSide.SERVER,
            ReportFiles.list(folder.toFile())
                .get(0).side);
        Report loaded = ReportFiles.load(folder.toFile(), name);
        assertEquals(ScanFocus.LAG, loaded.focus);
        assertEquals("legacy", loaded.source);
    }

    @Test
    public void separatesClientAndServerFoldersAndAvoidsFilenameCollisions() throws Exception {
        Path server = temporary.newFolder("server")
            .toPath();
        Path client = temporary.newFolder("client")
            .toPath();
        Report report = report();
        ReportFiles.save(server.toFile(), report, encode(report));
        ReportFiles.save(server.toFile(), report, encode(report));
        report.mode = ScanMode.FPS;
        ReportFiles.save(client.toFile(), report, encode(report));
        assertEquals(
            2,
            ReportFiles.list(server.toFile())
                .size());
        assertEquals(
            1,
            ReportFiles.list(client.toFile())
                .size());
        assertEquals(
            ScanSide.CLIENT,
            ReportFiles.list(client.toFile())
                .get(0).side);
    }

    @Test
    public void retainsTheNewestFiftyReports() throws Exception {
        Path folder = temporary.newFolder()
            .toPath();
        Report report = report();
        for (int i = 0; i < 53; i++) {
            report.startedAt += 1000;
            ReportFiles.save(folder.toFile(), report, encode(report));
        }
        List<ReportFiles.Saved> saved = ReportFiles.list(folder.toFile());
        assertEquals(50, saved.size());
        assertEquals(report.startedAt, saved.get(0).time);
        assertEquals(report.startedAt - 49000, saved.get(49).time);
    }

    @Test
    public void rejectsTraversalAndMalformedNames() {
        assertNull(ReportFiles.describe("../2026-10-09_18-30-00_TPS.lfr", 1));
        assertNull(ReportFiles.describe("2026-99-09_18-30-00_TPS.lfr", 1));
        assertNull(ReportFiles.describe("2026-10-09_18-30-00_UNKNOWN.lfr", 1));
        assertNull(ReportFiles.describe("2026-10-09_18-30-00_TPS.lfr", ReportFiles.MAX_BYTES + 1L));
        assertNotNull(ReportFiles.describe("2026-10-09_18-30-00_BOTH-METHODS.lfr", 1));
    }

    @Test
    public void rejectsMissingReports() throws Exception {
        Path folder = temporary.newFolder()
            .toPath();
        String name = "2026-10-09_18-30-00_TPS.lfr";
        assertThrows(IOException.class, () -> ReportFiles.bytes(folder.toFile(), name));
    }

    @Test
    public void rejectsSymlinks() throws Exception {
        Path folder = temporary.newFolder()
            .toPath();
        Path outside = temporary.newFile()
            .toPath();
        String name = "2026-10-09_18-30-00_TPS.lfr";
        Path link = temporary.getRoot()
            .toPath()
            .resolve("linked-reports");
        try {
            Files.createSymbolicLink(folder.resolve(name), outside);
            Files.createSymbolicLink(link, folder);
        } catch (FileSystemException | UnsupportedOperationException | SecurityException e) {
            assumeNoException("Symbolic link creation is unsupported or not permitted", e);
        }
        assertTrue(
            ReportFiles.list(folder.toFile())
                .isEmpty());
        assertThrows(IOException.class, () -> ReportFiles.bytes(folder.toFile(), name));
        assertThrows(IOException.class, () -> ReportFiles.list(link.toFile()));
    }

    @Test
    public void rejectsCorruptionAndTruncatedGzipChecksums() throws Exception {
        byte[] encoded = encode(report());
        assertThrows(IOException.class, () -> Report.read(new ByteArrayInputStream(new byte[] { 1, 2, 3 })));
        assertThrows(
            IOException.class,
            () -> Report.read(new ByteArrayInputStream(java.util.Arrays.copyOf(encoded, encoded.length - 4))));
        encoded[encoded.length - 8] ^= 1;
        assertThrows(IOException.class, () -> Report.read(new ByteArrayInputStream(encoded)));
    }

    @Test
    public void rejectsHugeAndNegativeSerializedCollectionsBeforeAllocation() throws Exception {
        assertThrows(
            IOException.class,
            () -> ReportInput.count(new ReportInput(new ByteArrayInputStream(new byte[0])), Integer.MAX_VALUE, 200000));
        assertThrows(
            IOException.class,
            () -> ReportInput.count(new ReportInput(new ByteArrayInputStream(new byte[0])), -1, 32767));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("server");
        out.writeFloat(5);
        out.writeInt(10);
        out.writeShort(0);
        out.writeInt(Integer.MAX_VALUE);
        assertThrows(
            IOException.class,
            () -> Profile.read(new ReportInput(new ByteArrayInputStream(bytes.toByteArray()))));
    }

    @Test
    public void rejectsExcessiveCallTreeDepth() throws Exception {
        Report report = report();
        report.profile = new Profile();
        report.profile.root = new CallNode();
        CallNode node = report.profile.root;
        for (int i = 0; i < 258; i++) {
            CallNode child = new CallNode();
            node.children.add(child);
            node = child;
        }
        byte[] bytes = encode(report);
        assertThrows(IOException.class, () -> Report.read(new ByteArrayInputStream(bytes)));
    }
}
