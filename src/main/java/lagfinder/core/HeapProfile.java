package lagfinder.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Java heap during a scan: how much of it was used and how fast the game allocated, moment by moment (the same
 * moments as the report's graph), and a few counts of the objects in it by class (like {@code jmap -histo}) taken
 * along the scan, so what grew and what shrank shows. Counting the objects stops the whole game for a moment; with
 * {@link #liveOnly} a full collection is made first, so only objects still in use are counted.
 */
public final class HeapProfile {

    /** Classes kept in the report: the biggest ones, and the ones that grew or shrank the most. */
    static final int KEEP_BY_SIZE = 300, KEEP_BY_GROWTH = 150, KEEP_BY_COUNT_GROWTH = 100;

    /** The most the heap may grow to (bytes), or 0 when it isn't known. */
    public long maxHeap;
    /** Whether the counts are of objects in use only (a full collection was made before each). */
    public boolean liveOnly;
    /** Whether the allocation of the game was measured (it needs a HotSpot JVM). */
    public boolean allocKnown;

    // Per moment of the graph.
    /** The lowest and highest heap used in the moment, and what was used at its end (MB). */
    public float[] heapLowMb = new float[0], heapHighMb = new float[0], heapMb = new float[0];
    /** Megabytes allocated in the moment, by all threads and by the scanned thread. */
    public float[] allocMb = new float[0], threadAllocMb = new float[0];
    /** Collections in the moment, and the time they took (ms). */
    public int[] gcCount = new int[0];
    public float[] gcMs = new float[0];

    // Per count of the objects.
    /** When each count was taken (seconds from the start), and how long the game stood still for it (ms). */
    public float[] snapshotSec = new float[0], snapshotPauseMs = new float[0];
    /** Objects and bytes of all classes, and the number of classes with any objects, in each count. */
    public long[] totalInstances = new long[0], totalBytes = new long[0];
    public int[] classCount = new int[0];
    /** Why the objects could not be counted, or null. */
    public String histogramError;

    /** Mods (and Minecraft, Forge, Java...) by index, with the objects and bytes of all their classes in each count. */
    public final List<String> mods = new ArrayList<>();
    public final List<long[]> modInstances = new ArrayList<>(), modBytes = new ArrayList<>();
    /** The classes kept. */
    public final List<HeapClass> classes = new ArrayList<>();

    /** One class: its objects and their bytes in each count. */
    public static final class HeapClass {

        /** As in Java code: "java.lang.String", "byte[]". */
        public String name = "";
        /** Index into {@link HeapProfile#mods}. */
        public int mod;
        public long[] instances, bytes;

        public long instancesAt(int snapshot) {
            return snapshot >= 0 && snapshot < instances.length ? instances[snapshot] : 0;
        }

        public long bytesAt(int snapshot) {
            return snapshot >= 0 && snapshot < bytes.length ? bytes[snapshot] : 0;
        }

        public long peakBytes() {
            long peak = 0;
            for (long value : bytes) {
                peak = Math.max(peak, value);
            }
            return peak;
        }

        public String simpleName() {
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(dot + 1);
        }

        public String packageName() {
            int dot = name.lastIndexOf('.');
            return dot < 0 ? "" : name.substring(0, dot);
        }
    }

    public int snapshots() {
        return snapshotSec.length;
    }

    public int buckets() {
        return heapMb.length;
    }

    public String modName(int index) {
        return index >= 0 && index < mods.size() ? mods.get(index) : Describer.UNKNOWN_MOD;
    }

    public int modIndex(String name) {
        return mods.indexOf(name);
    }

    /** Megabytes allocated by all threads over the whole scan. */
    public double allocatedMb() {
        double sum = 0;
        for (float value : allocMb) {
            sum += value;
        }
        return sum;
    }

    public int collections() {
        int sum = 0;
        for (int value : gcCount) {
            sum += value;
        }
        return sum;
    }

    public double collectionMs() {
        double sum = 0;
        for (float value : gcMs) {
            sum += value;
        }
        return sum;
    }

    /** Keeps the first {@code buckets} moments only: the scan ended before its time. */
    void trim(int buckets) {
        if (buckets >= heapMb.length) {
            return;
        }
        heapLowMb = Arrays.copyOf(heapLowMb, buckets);
        heapHighMb = Arrays.copyOf(heapHighMb, buckets);
        heapMb = Arrays.copyOf(heapMb, buckets);
        allocMb = Arrays.copyOf(allocMb, buckets);
        threadAllocMb = Arrays.copyOf(threadAllocMb, buckets);
        gcCount = Arrays.copyOf(gcCount, buckets);
        gcMs = Arrays.copyOf(gcMs, buckets);
    }

    // ---------------------------------------------------------------- Saving

    void write(DataOutputStream out) throws IOException {
        out.writeLong(maxHeap);
        out.writeBoolean(liveOnly);
        out.writeBoolean(allocKnown);
        int buckets = heapMb.length;
        out.writeShort(buckets);
        for (int i = 0; i < buckets; i++) {
            out.writeFloat(heapLowMb[i]);
            out.writeFloat(heapHighMb[i]);
            out.writeFloat(heapMb[i]);
            out.writeFloat(allocMb[i]);
            out.writeFloat(threadAllocMb[i]);
            out.writeShort(Math.min(Short.MAX_VALUE, gcCount[i]));
            out.writeFloat(gcMs[i]);
        }
        int snapshots = snapshotSec.length;
        out.writeShort(snapshots);
        for (int i = 0; i < snapshots; i++) {
            out.writeFloat(snapshotSec[i]);
            out.writeFloat(snapshotPauseMs[i]);
            out.writeLong(totalInstances[i]);
            out.writeLong(totalBytes[i]);
            out.writeInt(classCount[i]);
        }
        out.writeBoolean(histogramError != null);
        if (histogramError != null) {
            out.writeUTF(histogramError.length() > 300 ? histogramError.substring(0, 300) : histogramError);
        }
        out.writeShort(mods.size());
        for (int i = 0; i < mods.size(); i++) {
            out.writeUTF(mods.get(i));
            writeLongs(out, modInstances.get(i), snapshots);
            writeLongs(out, modBytes.get(i), snapshots);
        }
        out.writeInt(classes.size());
        for (HeapClass type : classes) {
            out.writeUTF(type.name.length() > 1000 ? type.name.substring(0, 1000) : type.name);
            out.writeShort(type.mod);
            writeLongs(out, type.instances, snapshots);
            writeLongs(out, type.bytes, snapshots);
        }
    }

    private static void writeLongs(DataOutputStream out, long[] values, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            out.writeLong(i < values.length ? values[i] : 0);
        }
    }

    private static long[] readLongs(DataInputStream in, int count) throws IOException {
        ReportInput.count(in, count, 32767);
        long[] values = new long[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readLong();
        }
        return values;
    }

    static HeapProfile read(DataInputStream in) throws IOException {
        HeapProfile heap = new HeapProfile();
        heap.maxHeap = in.readLong();
        heap.liveOnly = in.readBoolean();
        heap.allocKnown = in.readBoolean();
        int buckets = ReportInput.count(in, in.readShort(), 32767);
        heap.heapLowMb = new float[buckets];
        heap.heapHighMb = new float[buckets];
        heap.heapMb = new float[buckets];
        heap.allocMb = new float[buckets];
        heap.threadAllocMb = new float[buckets];
        heap.gcCount = new int[buckets];
        heap.gcMs = new float[buckets];
        for (int i = 0; i < buckets; i++) {
            heap.heapLowMb[i] = in.readFloat();
            heap.heapHighMb[i] = in.readFloat();
            heap.heapMb[i] = in.readFloat();
            heap.allocMb[i] = in.readFloat();
            heap.threadAllocMb[i] = in.readFloat();
            heap.gcCount[i] = in.readShort();
            heap.gcMs[i] = in.readFloat();
        }
        int snapshots = ReportInput.count(in, in.readShort(), 32767);
        heap.snapshotSec = new float[snapshots];
        heap.snapshotPauseMs = new float[snapshots];
        heap.totalInstances = new long[snapshots];
        heap.totalBytes = new long[snapshots];
        heap.classCount = new int[snapshots];
        for (int i = 0; i < snapshots; i++) {
            heap.snapshotSec[i] = in.readFloat();
            heap.snapshotPauseMs[i] = in.readFloat();
            heap.totalInstances[i] = in.readLong();
            heap.totalBytes[i] = in.readLong();
            heap.classCount[i] = in.readInt();
        }
        if (in.readBoolean()) {
            heap.histogramError = in.readUTF();
        }
        int mods = ReportInput.count(in, in.readShort(), 32767);
        for (int i = 0; i < mods; i++) {
            heap.mods.add(in.readUTF());
            heap.modInstances.add(readLongs(in, snapshots));
            heap.modBytes.add(readLongs(in, snapshots));
        }
        int classes = ReportInput.count(in, in.readInt(), 200_000);
        for (int i = 0; i < classes; i++) {
            HeapClass type = new HeapClass();
            type.name = in.readUTF();
            type.mod = in.readShort();
            type.instances = readLongs(in, snapshots);
            type.bytes = readLongs(in, snapshots);
            heap.classes.add(type);
        }
        return heap;
    }
}
