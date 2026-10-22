package lagfinder.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import lagfinder.Config;

/** A finished scan: the rate over time and what took the time, by category, mod, kind and instance. */
public final class Report {

    private static final int MAGIC = 0x4C46524E; // "LFRN"
    /** 11: the server's own work ({@link Category#SERVER}), which an older reader has no category for. */
    // 13: medians and GregTech activity per line (this fork); 14: entity ids, to follow an entity (upstream).
    public static final int VERSION = 19;

    public ScanMode mode;
    /** What the scan was for; older reports were all lag scans. */
    public ScanFocus focus = ScanFocus.LAG;
    public float steadyMedianTickMs, steadyP90TickMs;
    /** When it started (epoch milliseconds) and how long it ran (seconds). */
    public long startedAt;
    public float durationSec;
    /** Where it was made: the server's or the world's name. */
    public String source = "";
    /** Ticks or frames measured. */
    public int periods;
    /** Length of one bucket of the graphs (seconds); the last one may be shorter. */
    public float bucketSec;
    /** Per bucket: ticks or frames a second, their average time and their worst time (ms). */
    public float[] rate, avgMs, maxMs;
    /** Per bucket: ticks or frames in it. */
    public int[] counts;
    /** A period of an element at least this long (ms) counted as a spike. */
    public float spikeMs;
    public final Map<Integer, String> dimensions = new HashMap<>();
    public final List<ReportEntry> categories = new ArrayList<>();
    /** What the scanned thread was doing, method by method; null when it wasn't sampled (or an older report). */
    public Profile profile;
    /** The Java heap during the scan: its use, the allocation and the objects by class; null when not watched. */
    public HeapProfile memory;
    /** The objects created during the scan and the classes that created them; null when not watched. */
    public AllocationProfile allocations;
    /**
     * Whether the server and the client were scanned together and their methods, objects or allocations added up
     * into this one report; its rate and graph are then the server's.
     */
    public boolean bothSides;
    /**
     * What the scan itself took of its length (seconds): timing each thing and adding it up. The ticks and frames of
     * the graphs are already without it; the rate over the whole scan leaves it out for frames.
     */
    public float overheadSec;
    /** Frames a second the game ran at just before the scan, 0 when not known. */
    public float rateBefore;

    /** Where the scan ran. */
    public ScanSide side() {
        return bothSides ? ScanSide.BOTH : ScanSide.of(mode);
    }

    public int buckets() {
        return rate.length;
    }

    /**
     * What the scan could not measure as it should, as translation keys: a part whose hook another mod changed, ticks
     * marked only by the tick events. Shown with the report.
     */
    public final List<String> warnings = new ArrayList<>();
    public float[] gcMs;
    public int[] gcCount;

    /**
     * Per bucket: of a frame's time, what it spent waiting (the frame limiter's sleep, the wait for vsync) instead of
     * working (ms); null where not known (tick scans, older reports).
     */
    public float[] waitMs;
    /** The frame rate the frames were held to (the limit, or vsync's), 0 for none or not known. */
    public float frameCap;

    /** Ticks (or frames) of the worst second of the scan, -1 when not known (older reports, or under a second). */
    public float lowestSecond = -1;

    public int computerThreads;

    /** How long the scan ran without what it took itself (seconds). */
    public float measuredSec() {
        return Math.max(durationSec * 0.02f, durationSec - overheadSec);
    }

    /**
     * The average rate over the whole scan (ticks or frames a second). Ticks as they came: the server runs them on its
     * schedule and sleeps away what the scan took while it keeps up, so leaving that out would only make up ticks.
     * Frames follow each other: theirs is without what the scan itself took.
     */
    public float averageRate() {
        if (durationSec <= 0) {
            return 0;
        }
        return mode == ScanMode.TPS ? Math.min(20f, periods / durationSec) : periods / measuredSec();
    }

    /**
     * The lowest rate of the scan: of its worst second, a fixed window whatever the scan's length. The moments of the
     * graph are shorter than a second on short scans (83 ms on one of 10 s): a hitch of 300 ms the server makes up
     * for within the second would read as 3 TPS there, so the graph's lowest moment is not the lowest rate. For reports
     * made before the worst second was kept, the graph's lowest moment.
     */
    public float minimumRate() {
        if (lowestSecond >= 0) {
            return lowestSecond;
        }
        float min = Float.MAX_VALUE;
        for (int i = 0; i < rate.length; i++) {
            min = Math.min(min, rate[i]);
        }
        return min == Float.MAX_VALUE ? 0 : min;
    }

    /** Average time of a tick or frame over the whole scan (ms): every tick counts the same. */
    public float averagePeriodMs() {
        double sum = 0;
        long count = 0;
        for (int i = 0; i < avgMs.length; i++) {
            sum += (double) avgMs[i] * counts[i];
            count += counts[i];
        }
        return count == 0 ? 0 : (float) (sum / count);
    }

    /**
     * The rate in each moment of the graph, from the time its ticks (or frames) took, as Forge's /forge tps counts it:
     * 1000 / average tick, at most 20 for ticks. Counting the ticks that started in a moment instead gives 12 or 24
     * for a moment of a twelfth of a second that holds one or two of them, on a server that is perfectly fine. A
     * moment no tick started in lies inside a tick longer than it: it has that tick's rate (the one before it).
     */
    public static float[] ratesOf(ScanMode mode, int[] counts, float[] averageMs) {
        int n = counts.length;
        float[] rates = new float[n];
        float last = -1;
        for (int i = 0; i < n; i++) {
            if (counts[i] > 0 && averageMs[i] > 0) {
                float rate = 1000f / averageMs[i];
                last = mode == ScanMode.TPS ? Math.min(20f, rate) : rate;
            }
            rates[i] = last;
        }
        // Moments before the first tick ended: as the first one.
        float first = 0;
        for (int i = 0; i < n; i++) {
            if (rates[i] >= 0) {
                first = rates[i];
                break;
            }
        }
        for (int i = 0; i < n && rates[i] < 0; i++) {
            rates[i] = first;
        }
        return rates;
    }

    public float gcTotalMs() {
        float total = 0;
        if (gcMs != null) {
            for (float value : gcMs) {
                total += value;
            }
        }
        return total;
    }

    public int gcTotalCount() {
        int total = 0;
        if (gcCount != null) {
            for (int value : gcCount) {
                total += value;
            }
        }
        return total;
    }

    public float worstPeriodMs() {
        float worst = 0;
        for (float value : maxMs) {
            worst = Math.max(worst, value);
        }
        return worst;
    }

    /** The entry's average time per tick or frame in the bucket (ms). */
    public float perPeriod(ReportEntry entry, int bucket) {
        return entry.sum == null || counts[bucket] == 0 ? 0 : entry.sum[bucket] / counts[bucket];
    }

    /** The entry's average time per tick or frame over the whole scan (ms). */
    public double perPeriod(ReportEntry entry) {
        return periods == 0 ? 0 : entry.totalMs / periods;
    }

    /** A whole frame (or tick) as it went by on the clock, idle time included (ms). */
    public double wallPeriodMs() {
        if (periods > 0 && durationSec > 0) {
            return measuredSec() * 1000.0 / periods;
        }
        return averagePeriodMs();
    }

    /** A frame's average wait over the whole scan (ms): what of {@link #wallPeriodMs} was no work. */
    public double averageWaitMs() {
        if (waitMs == null) {
            return 0;
        }
        double sum = 0;
        long count = 0;
        for (int i = 0; i < waitMs.length && i < counts.length; i++) {
            sum += (double) waitMs[i] * counts[i];
            count += counts[i];
        }
        return count == 0 ? 0 : sum / count;
    }

    private double waitAt(int bucket) {
        return waitMs == null || bucket < 0 || bucket >= waitMs.length ? 0 : waitMs[bucket];
    }

    /**
     * How many frames a second this much time in every frame costs: how many more there would be without it. Only
     * the frame's work counts: a frame held to a limit (or vsync) that waits anyway gains nothing from less work
     * until its work is longer than the limit's frame.
     */
    public double fpsCost(double perFrameMs) {
        return fpsCost(perFrameMs, wallPeriodMs(), averageWaitMs());
    }

    /** The same, in one moment of the graph. */
    public double fpsCost(double perFrameMs, int bucket) {
        return fpsCost(perFrameMs, avgMs[bucket], waitAt(bucket));
    }

    private double fpsCost(double perFrameMs, double frameMs, double waitMs) {
        if (frameMs <= 0 || perFrameMs <= 0) {
            return 0;
        }
        double work = Math.max(0, frameMs - waitMs);
        if (heldToLimit()) {
            double free = Math.max(work, frameMs * 0.02);
            return Math.max(0, 1000.0 / Math.max(free - perFrameMs, free * 0.02) - 1000.0 / free);
        }
        double without = Math.max(Math.max(work - perFrameMs, capFrameMs(frameMs)), frameMs * 0.02);
        return Math.max(0, 1000.0 / without - 1000.0 / frameMs);
    }

    /**
     * How far the frames a second fell in the frame where this took the longest (it usually takes the average): its
     * extra time first fills what the frame would have waited.
     */
    public double fpsDrop(double averageMs, double peakMs) {
        return fpsDrop(averageMs, peakMs, wallPeriodMs(), averageWaitMs());
    }

    /** The same, in one moment of the graph. */
    public double fpsDrop(double averageMs, double peakMs, int bucket) {
        return fpsDrop(averageMs, peakMs, avgMs[bucket], waitAt(bucket));
    }

    private double fpsDrop(double averageMs, double peakMs, double frameMs, double waitMs) {
        if (frameMs <= 0 || peakMs <= averageMs) {
            return 0;
        }
        double work = Math.max(0, frameMs - waitMs);
        if (heldToLimit()) {
            double free = Math.max(work, frameMs * 0.02);
            return Math.max(0, 1000.0 / free - 1000.0 / (free + peakMs - averageMs));
        }
        double longer = Math.max(work + peakMs - averageMs, capFrameMs(frameMs));
        return Math.max(0, 1000.0 / frameMs - 1000.0 / Math.max(longer, frameMs));
    }

    /**
     * Whether what things cost is told in frames a second: in a frame scan whose frames weren't held to a limit. Held
     * to one (they waited for a good part of each frame), nothing costs a frame a second until the frames get longer
     * than the limit's; what things take is told in milliseconds of a frame then.
     */
    public boolean costsInFps() {
        return mode == ScanMode.FPS;
    }

    public boolean heldToLimit() {
        return mode == ScanMode.FPS && frameCap > 0 && averageWaitMs() > wallPeriodMs() * 0.2;
    }

    public double unlimitedRate() {
        double work = wallPeriodMs() - averageWaitMs();
        return work > 0 ? 1000.0 / work : 0;
    }

    /**
     * The length of a frame at the rate the frames were held to (ms), 0 when they weren't, or when the frames went
     * clearly faster than it: the rate guessed was not what held them.
     */
    private double capFrameMs(double frameMs) {
        if (frameCap <= 0) {
            return 0;
        }
        double capMs = 1000.0 / frameCap;
        return capMs > frameMs * 1.05 ? 0 : capMs;
    }

    public String dimensionName(int dim) {
        String name = dimensions.get(dim);
        return name != null ? name : "DIM " + dim;
    }

    public ReportEntry category(Category category) {
        for (ReportEntry entry : categories) {
            if (entry.category == category) {
                return entry;
            }
        }
        return null;
    }

    public void write(OutputStream stream) throws IOException {
        GZIPOutputStream zip = new GZIPOutputStream(stream);
        DataOutputStream out = new DataOutputStream(zip);
        out.writeInt(MAGIC);
        out.writeShort(VERSION);
        out.writeByte(mode.ordinal());
        out.writeLong(startedAt);
        out.writeFloat(durationSec);
        out.writeUTF(source == null ? "" : source);
        out.writeInt(periods);
        out.writeFloat(bucketSec);
        out.writeFloat(spikeMs);
        out.writeShort(rate.length);
        for (int i = 0; i < rate.length; i++) {
            out.writeFloat(rate[i]);
            out.writeFloat(avgMs[i]);
            out.writeFloat(maxMs[i]);
            out.writeInt(counts[i]);
        }
        out.writeShort(dimensions.size());
        for (Map.Entry<Integer, String> dim : dimensions.entrySet()) {
            out.writeInt(dim.getKey());
            out.writeUTF(dim.getValue());
        }
        out.writeShort(categories.size());
        for (ReportEntry entry : categories) {
            entry.write(out);
        }
        out.writeBoolean(profile != null);
        if (profile != null) {
            profile.write(out);
        }
        out.writeBoolean(memory != null);
        if (memory != null) {
            memory.write(out);
        }
        out.writeByte(focus.ordinal());
        out.writeBoolean(allocations != null);
        if (allocations != null) {
            allocations.write(out);
        }
        out.writeBoolean(bothSides);
        out.writeFloat(overheadSec);
        out.writeFloat(rateBefore);
        out.writeFloat(lowestSecond);
        out.writeShort(warnings.size());
        for (String warning : warnings) {
            out.writeUTF(warning);
        }
        out.writeFloat(frameCap);
        out.writeBoolean(waitMs != null);
        if (waitMs != null) {
            out.writeShort(waitMs.length);
            for (float wait : waitMs) {
                out.writeFloat(wait);
            }
        }
        out.writeFloat(steadyMedianTickMs);
        out.writeFloat(steadyP90TickMs);
        boolean gc = gcMs != null && gcCount != null && gcMs.length == rate.length && gcCount.length == rate.length;
        out.writeBoolean(gc);
        if (gc) {
            for (int i = 0; i < gcMs.length; i++) {
                out.writeFloat(gcMs[i]);
                out.writeInt(gcCount[i]);
            }
        }
        out.writeShort(computerThreads);
        out.flush();
        zip.finish();
    }

    public static Report read(InputStream stream) throws IOException {
        DataInputStream in = new ReportInput(new GZIPInputStream(stream));
        if (in.readInt() != MAGIC) {
            throw new IOException("Not a LagFinder report");
        }
        int version = in.readShort();
        if (version < 1 || version > VERSION) {
            throw new IOException("Report version " + version + " is not supported");
        }
        Report report = new Report();
        report.mode = ScanMode.values()[ReportInput.index(in.readByte(), ScanMode.values().length)];
        report.startedAt = in.readLong();
        report.durationSec = in.readFloat();
        report.source = in.readUTF();
        report.periods = in.readInt();
        report.bucketSec = in.readFloat();
        report.spikeMs = in.readFloat();
        if (report.spikeMs <= 0) {
            // Reports made before it was kept: the spike of the scan's side as it is set now.
            report.spikeMs = report.mode == ScanMode.TPS ? Config.spikeTpsMs : Config.spikeFpsMs;
        }
        int buckets = ReportInput.count(in, in.readShort(), 32767);
        if (buckets == 0) {
            throw new IOException("Report graph is empty");
        }
        report.rate = new float[buckets];
        report.avgMs = new float[buckets];
        report.maxMs = new float[buckets];
        report.counts = new int[buckets];
        for (int i = 0; i < buckets; i++) {
            report.rate[i] = in.readFloat();
            report.avgMs[i] = in.readFloat();
            report.maxMs[i] = in.readFloat();
            report.counts[i] = in.readInt();
            if (!Float.isFinite(report.avgMs[i]) || !Float.isFinite(report.maxMs[i])
                || report.avgMs[i] < 0
                || report.maxMs[i] < 0
                || report.counts[i] < 0) {
                throw new IOException("Invalid report graph");
            }
        }
        // Older reports counted ticks per moment; from their times the rate comes out right for them too.
        report.rate = ratesOf(report.mode, report.counts, report.avgMs);
        int dims = ReportInput.count(in, in.readShort(), 32767);
        for (int i = 0; i < dims; i++) {
            report.dimensions.put(in.readInt(), in.readUTF());
        }
        int count = ReportInput.count(in, in.readShort(), Category.values().length);
        for (int i = 0; i < count; i++) {
            report.categories.add(ReportEntry.read(in, null, version, buckets));
        }
        if (version >= 2 && in.readBoolean()) {
            report.profile = Profile.read(in);
        }
        if (version >= 3 && in.readBoolean()) {
            report.memory = HeapProfile.read(in);
        }
        if (version >= 4) {
            report.focus = ScanFocus.values()[ReportInput.index(in.readByte(), ScanFocus.values().length)];
        }
        if (version >= 5 && in.readBoolean()) {
            report.allocations = AllocationProfile.read(in, version);
        }
        if (version >= 7) {
            report.bothSides = in.readBoolean();
        }
        if (version >= 8) {
            report.overheadSec = in.readFloat();
            report.rateBefore = in.readFloat();
        }
        if (version >= 9) {
            report.lowestSecond = in.readFloat();
        }
        if (version >= 10) {
            int warningCount = ReportInput.count(in, in.readShort(), 32767);
            for (int i = 0; i < warningCount; i++) {
                report.warnings.add(in.readUTF());
            }
        }
        if (version >= 12) {
            report.frameCap = in.readFloat();
            if (in.readBoolean()) {
                int waits = ReportInput.count(in, in.readShort(), buckets);
                if (waits != buckets) {
                    throw new IOException("Invalid report wait graph");
                }
                report.waitMs = new float[waits];
                for (int i = 0; i < report.waitMs.length; i++) {
                    report.waitMs[i] = in.readFloat();
                }
            }
        }
        if (version >= 13) {
            report.steadyMedianTickMs = in.readFloat();
            report.steadyP90TickMs = in.readFloat();
        }
        if (version >= 15 && in.readBoolean()) {
            report.gcMs = new float[buckets];
            report.gcCount = new int[buckets];
            for (int i = 0; i < buckets; i++) {
                report.gcMs[i] = in.readFloat();
                report.gcCount[i] = in.readInt();
                if (!Float.isFinite(report.gcMs[i]) || report.gcMs[i] < 0 || report.gcCount[i] < 0) {
                    throw new IOException("Invalid report GC data");
                }
            }
        }
        if (version >= 18) {
            report.computerThreads = in.readShort();
            if (report.computerThreads < 0) {
                throw new IOException("Invalid report computer threads");
            }
        }
        if (!Float.isFinite(report.durationSec) || report.durationSec < 0
            || !Float.isFinite(report.bucketSec)
            || report.bucketSec < 0
            || report.periods < 0
            || in.read() != -1) {
            throw new IOException("Invalid report data");
        }
        return report;
    }
}
