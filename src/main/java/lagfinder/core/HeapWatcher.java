package lagfinder.core;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import lagfinder.LagFinder;

/**
 * Watches the Java heap while a scan runs, on a thread of its own: every few dozen milliseconds how much of it is used,
 * how much the game allocated and how often it collected, and now and then a count of all the objects in it by class,
 * as {@code jmap -histo} does (through the JVM's own diagnostic command). What it gathered is read once it is stopped.
 */
public final class HeapWatcher {

    private static final String HISTOGRAM_BEAN = "com.sun.management:type=DiagnosticCommand";

    private final long startNanos, durationNanos, bucketNanos, intervalNanos;
    private final int buckets, snapshots;
    private final boolean liveOnly;
    private final Thread thread;
    private volatile boolean running = true;

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final Allocation allocation;

    // Written by the watching thread only; read under the lock once it stopped.
    private final long[] heapLow, heapHigh, heapLast, alloc, threadAlloc, gcTime;
    private final int[] gcCount, polls;
    private long lastGcCount, lastGcTime;

    private final List<Float> snapshotSec = new ArrayList<>(), snapshotPause = new ArrayList<>();
    private final List<long[]> snapshotTotals = new ArrayList<>();
    /** Each class seen in a count, by its name as the JVM gives it: its objects and bytes in each count. */
    private final Map<String, Counted> counted = new HashMap<>();
    private String histogramError;

    private static final class Counted {

        final String name;
        final String mod;
        final long[] instances, bytes;

        Counted(String name, String mod, int snapshots) {
            this.name = name;
            this.mod = mod;
            this.instances = new long[snapshots];
            this.bytes = new long[snapshots];
        }
    }

    /**
     * @param snapshots how many times the objects are counted along the scan (the first at its start, the last near
     *                  its end), 0 for none
     * @param liveOnly  count only the objects in use: a full collection is made before each count
     */
    public HeapWatcher(Thread target, long startNanos, long durationNanos, int buckets, int snapshots,
        boolean liveOnly) {
        this.startNanos = startNanos;
        this.durationNanos = durationNanos;
        this.buckets = buckets;
        this.bucketNanos = Math.max(1, durationNanos / buckets);
        this.intervalNanos = Math.max(25_000_000L, Math.min(250_000_000L, bucketNanos / 4));
        this.snapshots = snapshots <= 0 ? 0 : Math.max(2, snapshots);
        this.liveOnly = liveOnly;
        heapLow = new long[buckets];
        heapHigh = new long[buckets];
        heapLast = new long[buckets];
        alloc = new long[buckets];
        threadAlloc = new long[buckets];
        gcTime = new long[buckets];
        gcCount = new int[buckets];
        polls = new int[buckets];
        this.thread = new Thread(this::run, "LagFinder heap watcher");
        thread.setDaemon(true);
        allocation = Allocation.create(target, thread);
    }

    public void start() {
        thread.start();
    }

    /**
     * The scan ended: no more looks at the heap and no new count of the objects. Doesn't wait for a count under way,
     * so it can be called on the game's thread; {@link #stop} (which waits) can't.
     */
    public void markEnd() {
        running = false;
        LockSupport.unpark(thread);
    }

    /** Stops watching and waits for a count of the objects under way to end, so what it gathered can be read. */
    public void stop() {
        markEnd();
        try {
            thread.join(20_000);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
    }

    /** When the count of the objects {@code index} is due (nanoseconds from the start). */
    private long snapshotAt(int index) {
        if (index == 0) {
            return 0;
        }
        long step = durationNanos / (snapshots - 1);
        if (index < snapshots - 1) {
            return step * index;
        }
        // The last one a bit before the end, so the scan doesn't wait for it.
        return durationNanos - Math.min(1_000_000_000L, step / 2);
    }

    private void run() {
        try {
            resetCollections();
            if (allocation != null) {
                allocation.poll();
            }
            int next = 0;
            while (running) {
                long now = System.nanoTime();
                if (next < snapshots && now - startNanos >= snapshotAt(next)) {
                    poll(now);
                    snapshot(next++);
                    // The collection made for the count is ours, not the game's.
                    resetCollections();
                    continue;
                }
                poll(now);
                long wait = intervalNanos;
                if (next < snapshots) {
                    wait = Math.min(wait, Math.max(0, startNanos + snapshotAt(next) - System.nanoTime()));
                }
                LockSupport.parkNanos(Math.max(1_000_000L, wait));
            }
        } catch (Throwable t) {
            LagFinder.LOG.warn("LagFinder heap watcher stopped", t);
        }
    }

    private int bucketOf(long time) {
        return (int) Math.max(0, Math.min(buckets - 1, (time - startNanos) / bucketNanos));
    }

    private void resetCollections() {
        long count = 0, time = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            count += Math.max(0, collector.getCollectionCount());
            time += Math.max(0, collector.getCollectionTime());
        }
        lastGcCount = count;
        lastGcTime = time;
    }

    private synchronized void poll(long now) {
        int bucket = bucketOf(now);
        long used = memory.getHeapMemoryUsage()
            .getUsed();
        if (polls[bucket] == 0) {
            heapLow[bucket] = used;
            heapHigh[bucket] = used;
        } else {
            heapLow[bucket] = Math.min(heapLow[bucket], used);
            heapHigh[bucket] = Math.max(heapHigh[bucket], used);
        }
        heapLast[bucket] = used;
        polls[bucket]++;

        long count = 0, time = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            count += Math.max(0, collector.getCollectionCount());
            time += Math.max(0, collector.getCollectionTime());
        }
        gcCount[bucket] += (int) Math.max(0, count - lastGcCount);
        gcTime[bucket] += Math.max(0, time - lastGcTime);
        lastGcCount = count;
        lastGcTime = time;

        if (allocation != null) {
            long[] bytes = allocation.poll();
            alloc[bucket] += bytes[0];
            threadAlloc[bucket] += bytes[1];
        }
    }

    // ---------------------------------------------------------------- Counting the objects

    private void snapshot(int index) {
        if (histogramError != null) {
            return;
        }
        String text;
        long before = System.nanoTime();
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            Object[] arguments = { liveOnly ? new String[0] : new String[] { "-all" } };
            text = (String) server.invoke(
                new ObjectName(HISTOGRAM_BEAN),
                "gcClassHistogram",
                arguments,
                new String[] { String[].class.getName() });
        } catch (Throwable t) {
            histogramError = t.getClass()
                .getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            LagFinder.LOG.warn("LagFinder could not count the objects in the heap", t);
            return;
        }
        float pauseMs = (System.nanoTime() - before) / 1e6f;
        long totalInstances = 0, totalBytes = 0;
        int classes = 0;
        Map<String, long[]> found = new HashMap<>();
        for (String line : text.split("\n")) {
            String[] parts = line.trim()
                .split("\\s+");
            if (parts.length < 4 || !parts[0].endsWith(":")) {
                continue;
            }
            try {
                long instances = Long.parseLong(parts[1]);
                long bytes = Long.parseLong(parts[2]);
                // A class loaded by more than one class loader has a line for each, under the same name.
                long[] same = found.get(parts[3]);
                if (same == null) {
                    found.put(parts[3], new long[] { instances, bytes });
                } else {
                    same[0] += instances;
                    same[1] += bytes;
                }
                totalInstances += instances;
                totalBytes += bytes;
                classes++;
            } catch (NumberFormatException ignored) {
                // The heading.
            }
        }
        // Which mod a class is of may take a look into the mods' jars: not while holding the lock.
        Map<String, Counted> added = new HashMap<>();
        for (String key : found.keySet()) {
            if (!counted.containsKey(key)) {
                String name = javaName(key);
                added.put(key, new Counted(name, modOf(name), snapshots));
            }
        }
        synchronized (this) {
            counted.putAll(added);
            for (Map.Entry<String, long[]> entry : found.entrySet()) {
                Counted type = counted.get(entry.getKey());
                type.instances[index] = entry.getValue()[0];
                type.bytes[index] = entry.getValue()[1];
            }
            snapshotSec.add((before - startNanos) / 1e9f);
            snapshotPause.add(pauseMs);
            snapshotTotals.add(new long[] { totalInstances, totalBytes, classes });
        }
    }

    /** The name of a class as the JVM gives it ("[Ljava.lang.String;", "[I") as in Java code ("String[]", "int[]"). */
    static String javaName(String name) {
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') {
            dims++;
        }
        if (dims == 0) {
            return name;
        }
        String element = name.substring(dims);
        if (element.startsWith("L") && element.endsWith(";")) {
            element = element.substring(1, element.length() - 1);
        } else if (element.length() == 1) {
            switch (element.charAt(0)) {
                case 'B':
                    element = "byte";
                    break;
                case 'C':
                    element = "char";
                    break;
                case 'D':
                    element = "double";
                    break;
                case 'F':
                    element = "float";
                    break;
                case 'I':
                    element = "int";
                    break;
                case 'J':
                    element = "long";
                    break;
                case 'S':
                    element = "short";
                    break;
                case 'Z':
                    element = "boolean";
                    break;
                default:
                    break;
            }
        }
        StringBuilder text = new StringBuilder(element);
        for (int i = 0; i < dims; i++) {
            text.append("[]");
        }
        return text.toString();
    }

    /** The mod of a class by its name; arrays go by what they hold, arrays of numbers are Java's. */
    static String modOf(String javaName) {
        String element = javaName;
        int bracket = element.indexOf('[');
        if (bracket > 0) {
            element = element.substring(0, bracket);
        }
        if (element.indexOf('.') < 0) {
            // A number, or a class of the JVM itself.
            return ModResolver.JAVA;
        }
        try {
            return ModResolver.modOfClassName(element);
        } catch (Throwable t) {
            return Describer.UNKNOWN_MOD;
        }
    }

    // ---------------------------------------------------------------- The result

    /** What was gathered, for the report. Call after {@link #stop}. */
    public synchronized HeapProfile build() {
        HeapProfile heap = new HeapProfile();
        MemoryUsage usage = memory.getHeapMemoryUsage();
        heap.maxHeap = Math.max(0, usage.getMax());
        heap.liveOnly = liveOnly;
        heap.allocKnown = allocation != null;
        heap.histogramError = histogramError;

        heap.heapLowMb = new float[buckets];
        heap.heapHighMb = new float[buckets];
        heap.heapMb = new float[buckets];
        heap.allocMb = new float[buckets];
        heap.threadAllocMb = new float[buckets];
        heap.gcCount = new int[buckets];
        heap.gcMs = new float[buckets];
        long last = -1;
        for (int i = 0; i < buckets; i++) {
            if (polls[i] == 0 && last < 0) {
                continue;
            }
            // A moment without a look at the heap keeps what was used before it.
            long low = polls[i] > 0 ? heapLow[i] : last, high = polls[i] > 0 ? heapHigh[i] : last;
            last = polls[i] > 0 ? heapLast[i] : last;
            heap.heapLowMb[i] = low / 1048576f;
            heap.heapHighMb[i] = high / 1048576f;
            heap.heapMb[i] = last / 1048576f;
            heap.allocMb[i] = alloc[i] / 1048576f;
            heap.threadAllocMb[i] = threadAlloc[i] / 1048576f;
            heap.gcCount[i] = gcCount[i];
            heap.gcMs[i] = gcTime[i];
        }
        // Moments before the first look: as at the first.
        for (int i = 0; i < buckets && polls[i] == 0; i++) {
            int first = i;
            while (first < buckets && polls[first] == 0) {
                first++;
            }
            if (first < buckets) {
                heap.heapLowMb[i] = heap.heapHighMb[i] = heap.heapMb[i] = heapLow[first] / 1048576f;
            }
        }

        int taken = snapshotSec.size();
        heap.snapshotSec = new float[taken];
        heap.snapshotPauseMs = new float[taken];
        heap.totalInstances = new long[taken];
        heap.totalBytes = new long[taken];
        heap.classCount = new int[taken];
        for (int i = 0; i < taken; i++) {
            heap.snapshotSec[i] = snapshotSec.get(i);
            heap.snapshotPauseMs[i] = snapshotPause.get(i);
            heap.totalInstances[i] = snapshotTotals.get(i)[0];
            heap.totalBytes[i] = snapshotTotals.get(i)[1];
            heap.classCount[i] = (int) snapshotTotals.get(i)[2];
        }
        if (taken == 0) {
            return heap;
        }

        // Every mod's objects, of all its classes.
        Map<String, Integer> modIds = new HashMap<>();
        List<Counted> all = new ArrayList<>(counted.values());
        for (Counted type : all) {
            Integer id = modIds.get(type.mod);
            if (id == null) {
                id = heap.mods.size();
                modIds.put(type.mod, id);
                heap.mods.add(type.mod);
                heap.modInstances.add(new long[taken]);
                heap.modBytes.add(new long[taken]);
            }
            long[] instances = heap.modInstances.get(id), bytes = heap.modBytes.get(id);
            for (int i = 0; i < taken; i++) {
                instances[i] += type.instances[i];
                bytes[i] += type.bytes[i];
            }
        }

        // The classes kept: the biggest, and the ones that changed the most.
        Set<Counted> kept = new LinkedHashSet<>();
        keep(all, kept, HeapProfile.KEEP_BY_SIZE, type -> {
            long peak = 0;
            for (int i = 0; i < taken; i++) {
                peak = Math.max(peak, type.bytes[i]);
            }
            return peak;
        });
        keep(all, kept, HeapProfile.KEEP_BY_GROWTH, type -> Math.abs(type.bytes[taken - 1] - type.bytes[0]));
        keep(
            all,
            kept,
            HeapProfile.KEEP_BY_COUNT_GROWTH,
            type -> Math.abs(type.instances[taken - 1] - type.instances[0]));
        for (Counted type : kept) {
            HeapProfile.HeapClass entry = new HeapProfile.HeapClass();
            entry.name = type.name;
            entry.mod = modIds.get(type.mod);
            entry.instances = Arrays.copyOf(type.instances, taken);
            entry.bytes = Arrays.copyOf(type.bytes, taken);
            heap.classes.add(entry);
        }
        return heap;
    }

    private interface Measure {

        long of(Counted type);
    }

    private static void keep(List<Counted> all, Set<Counted> kept, int count, Measure measure) {
        all.sort(
            Comparator.comparingLong(measure::of)
                .reversed());
        for (int i = 0; i < all.size() && i < count; i++) {
            if (measure.of(all.get(i)) <= 0) {
                break;
            }
            kept.add(all.get(i));
        }
    }

    // ---------------------------------------------------------------- Allocation

    /** The bytes threads allocated, as HotSpot counts them for each thread; null where the JVM can't tell. */
    private static final class Allocation {

        private final com.sun.management.ThreadMXBean threads;
        private final long targetId, ownId;
        private final Map<Long, Long> last = new HashMap<>();
        private boolean first = true;

        private Allocation(com.sun.management.ThreadMXBean threads, Thread target, Thread own) {
            this.threads = threads;
            this.targetId = target.getId();
            this.ownId = own.getId();
        }

        static Allocation create(Thread target, Thread own) {
            try {
                java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
                if (!(bean instanceof com.sun.management.ThreadMXBean)) {
                    return null;
                }
                com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
                if (!threads.isThreadAllocatedMemorySupported() || !threads.isThreadAllocatedMemoryEnabled()) {
                    return null;
                }
                return new Allocation(threads, target, own);
            } catch (Throwable t) {
                return null;
            }
        }

        /** Bytes allocated since the last poll: by all threads (but this watcher), and by the scanned one. */
        long[] poll() {
            long[] ids = threads.getAllThreadIds();
            long[] bytes = threads.getThreadAllocatedBytes(ids);
            long all = 0, target = 0;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == ownId || bytes[i] < 0) {
                    continue;
                }
                Long before = last.put(ids[i], bytes[i]);
                if (first) {
                    continue;
                }
                // A thread started since the last poll allocated all it has during the scan.
                long delta = Math.max(0, bytes[i] - (before == null ? 0 : before));
                all += delta;
                if (ids[i] == targetId) {
                    target = delta;
                }
            }
            if (last.size() > ids.length * 2 + 64) {
                // Threads that ended are forgotten.
                Set<Long> alive = new HashSet<>();
                for (long id : ids) {
                    alive.add(id);
                }
                last.keySet()
                    .retainAll(alive);
            }
            first = false;
            return new long[] { all, target };
        }
    }
}
