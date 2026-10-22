package lagfinder.core;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * One line of a scan's tree while it runs: a category (depth 0), a mod (1), a kind of thing (2) or one thing in the
 * world (3). Time measured in a tick (or frame) is added to a leaf, and at the end of the period it is passed up to
 * its parents and added to everyone's totals, so every level knows its own worst period, not only the sum of its
 * children's.
 */
public final class Node {

    public static final int CATEGORY = 0, MOD = 1, TYPE = 2, INSTANCE = 3;

    public final int depth;
    public final Category category;
    /**
     * What it is measured by: the tile entity, entity or listener itself for leaves, a name or class above. Let go
     * (null) when the thing is gone from the world, so a long scan doesn't keep every dead entity and unloaded world.
     */
    public Object key;
    /** The class of the key, kept when the key is let go. */
    public final Class<?> keyClass;
    public Node parent;
    /** Children by their key, for categories, mods and types. */
    public Map<Object, Node> children;

    // Description, set when the leaf is first resolved (see Describer).
    public String mod;
    public Object typeKey;
    public String name;
    /** Class or handler behind it, shown in the details. */
    public String detail;
    /** A translation key the client may know a better name by, or null. */
    public String translationKey;
    /** Item standing for it (its icon and name on the client), -1 for none. */
    public String item;

    // Where an instance is.
    public int dim, x, y, z;
    public LocationKind kind = LocationKind.NONE;
    /** Something whose position is read again when the report is made (an entity that moves), or null. */
    public Object live;
    /** The entity's id in its world, so the client can find it where it has gone since; -1 for other things. */
    public int entityId = -1;
    /** What an instance is on its own: an item's name, a named mob, a chunk; null when its type says it all. */
    public String label;
    /** Set when the thing behind the leaf was resolved. */
    public boolean resolved;
    /** The side (0-5) of the block a GregTech cover is on, -1 for anything else. */
    public int coverSide = -1;
    public int pipePart = -1;
    public int[] parts;

    // The period being measured.
    long periodNanos;
    int periodCalls;
    boolean touched;

    // Totals over the scan.
    public long totalNanos;
    public long calls;
    public long maxNanos;
    /** Seconds since the start of the scan when its worst period happened. */
    public float maxAt;
    public int activePeriods;
    public int spikes;
    /** Instances seen, for types. */
    public int instances;
    public long gtActiveNanos, gtIdleNanos, gtActiveCalls, gtIdleCalls;
    long periodGtActive, periodGtIdle, periodGtActiveCalls, periodGtIdleCalls;
    private int[] steadyHistogram;
    private boolean gtChecked, gtTile, gtActive, gtHasSample;
    private int gtLastCheck = Integer.MIN_VALUE;
    private static final Class<?> GT_TILE = gtClass();
    private static final Method GT_IS_ACTIVE = gtMethod();
    /**
     * Per bucket of time: the time spent in it, and the worst single period (nanoseconds, as floats); null until a
     * thing in the world was active in many of them. Most things are active only in a few (an item on the ground, an
     * arrow, a mob that despawns): those keep only the buckets they were active in, in order.
     */
    private float[] bucketSum, bucketMax;
    private short[] activeBucket;
    private float[] activeSum, activeMax;
    private int activeCount;

    public Node(int depth, Category category, Object key) {
        this.depth = depth;
        this.category = category;
        this.key = key;
        this.keyClass = key == null ? Object.class : key.getClass();
    }

    /** Whether it has its time per bucket. */
    public boolean hasBuckets() {
        return bucketSum != null || activeCount > 0;
    }

    /** Its time and worst period per bucket, into the arrays (nanoseconds; the arrays are zeroed first). */
    public void copyBuckets(float[] sum, float[] max) {
        Arrays.fill(sum, 0);
        Arrays.fill(max, 0);
        if (bucketSum != null) {
            System.arraycopy(bucketSum, 0, sum, 0, Math.min(sum.length, bucketSum.length));
            System.arraycopy(bucketMax, 0, max, 0, Math.min(max.length, bucketMax.length));
            return;
        }
        for (int i = 0; i < activeCount; i++) {
            int bucket = activeBucket[i];
            if (bucket < sum.length) {
                sum[bucket] = activeSum[i];
                max[bucket] = activeMax[i];
            }
        }
    }

    /** The child with the key, made with {@code depth + 1} if there is none yet. */
    public Node child(Object childKey) {
        if (children == null) {
            children = new LinkedHashMap<>();
        }
        Node child = children.get(childKey);
        if (child == null) {
            child = new Node(depth + 1, category, childKey);
            child.parent = this;
            children.put(childKey, child);
        }
        return child;
    }

    void add(long nanos, int count, ProfileSession session) {
        periodNanos += nanos;
        periodCalls += count;
        if (!touched) {
            touched = true;
            session.touched(this);
        }
    }

    private static Class<?> gtClass() {
        try {
            return Class
                .forName("gregtech.api.interfaces.tileentity.IGregTechTileEntity", false, Node.class.getClassLoader());
        } catch (Throwable ex) {
            return null;
        }
    }

    private static Method gtMethod() {
        if (GT_TILE == null) {
            return null;
        }
        try {
            return GT_TILE.getMethod("isActive");
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    void recordGtActivity(Object tile, long nanos, int period) {
        if (!gtChecked) {
            gtTile = GT_TILE != null && GT_IS_ACTIVE != null && GT_TILE.isInstance(tile);
            gtChecked = true;
            gtLastCheck = period - (20 - (System.identityHashCode(tile) & 0x7FFFFFFF) % 20);
        }
        if (!gtTile) {
            return;
        }
        if (period - gtLastCheck >= 20) {
            gtLastCheck = period;
            try {
                gtActive = Boolean.TRUE.equals(GT_IS_ACTIVE.invoke(tile));
                gtHasSample = true;
            } catch (ReflectiveOperationException ex) {
                gtTile = false;
                return;
            }
        }
        if (!gtHasSample) {
            return;
        }
        if (gtActive) {
            periodGtActive += nanos;
            periodGtActiveCalls++;
        } else {
            periodGtIdle += nanos;
            periodGtIdleCalls++;
        }
    }

    void addGtActivity(long active, long idle, long activeCalls, long idleCalls) {
        periodGtActive += active;
        periodGtIdle += idle;
        periodGtActiveCalls += activeCalls;
        periodGtIdleCalls += idleCalls;
    }

    public float steadyP50(int allPeriods) {
        return percentile(allPeriods, 0.5f);
    }

    public float steadyP90(int allPeriods) {
        return percentile(allPeriods, 0.9f);
    }

    private float percentile(int allPeriods, float fraction) {
        if (steadyHistogram == null || allPeriods <= 0) {
            return 0;
        }
        int missed = allPeriods - activePeriods;
        int threshold = Math.max(1, (int) Math.ceil(allPeriods * fraction));
        if (threshold <= missed) {
            return 0;
        }
        return SteadyHistogram.percentile(steadyHistogram, activePeriods, (threshold - missed) / (float) activePeriods);
    }

    /**
     * Adds the period that just ended to the totals. {@code weight} is how many periods it stands for (more than one in
     * a light scan, which measures one period in a few): totals and averages count it that many times, its worst
     * period is what it really took.
     */
    void finishPeriod(int bucket, int buckets, float seconds, long spikeNanos, boolean steady, int weight) {
        long nanos = periodNanos;
        long weighted = nanos * weight;
        totalNanos += weighted;
        calls += (long) periodCalls * weight;
        gtActiveNanos += periodGtActive * weight;
        gtIdleNanos += periodGtIdle * weight;
        gtActiveCalls += periodGtActiveCalls * weight;
        gtIdleCalls += periodGtIdleCalls * weight;
        if (steady && depth != INSTANCE) {
            if (steadyHistogram == null) {
                steadyHistogram = new int[SteadyHistogram.BINS];
            }
            steadyHistogram[SteadyHistogram.bin(nanos)]++;
        }
        activePeriods += weight;
        if (nanos > maxNanos) {
            maxNanos = nanos;
            maxAt = seconds;
        }
        if (nanos >= spikeNanos) {
            spikes += weight;
        }
        if (bucketSum == null && depth == INSTANCE && activeCount < buckets / 4) {
            addSparse(bucket, weighted, nanos);
        } else {
            if (bucketSum == null) {
                toDense(buckets);
            }
            bucketSum[bucket] += weighted;
            if (nanos > bucketMax[bucket]) {
                bucketMax[bucket] = nanos;
            }
        }
        periodNanos = 0;
        periodCalls = 0;
        periodGtActive = 0;
        periodGtIdle = 0;
        periodGtActiveCalls = 0;
        periodGtIdleCalls = 0;
        touched = false;
    }

    /** Adds a period to the buckets it was active in (buckets only grow along the scan). */
    private void addSparse(int bucket, long sum, long nanos) {
        if (activeCount > 0 && activeBucket[activeCount - 1] == bucket) {
            activeSum[activeCount - 1] += sum;
            activeMax[activeCount - 1] = Math.max(activeMax[activeCount - 1], nanos);
            return;
        }
        if (activeBucket == null || activeCount == activeBucket.length) {
            int size = activeBucket == null ? 2 : activeBucket.length * 2;
            activeBucket = activeBucket == null ? new short[size] : Arrays.copyOf(activeBucket, size);
            activeSum = activeSum == null ? new float[size] : Arrays.copyOf(activeSum, size);
            activeMax = activeMax == null ? new float[size] : Arrays.copyOf(activeMax, size);
        }
        activeBucket[activeCount] = (short) bucket;
        activeSum[activeCount] = sum;
        activeMax[activeCount] = nanos;
        activeCount++;
    }

    /** Active in many buckets: all of them kept, as for the lines above it. */
    private void toDense(int buckets) {
        bucketSum = new float[buckets];
        bucketMax = new float[buckets];
        for (int i = 0; i < activeCount; i++) {
            bucketSum[activeBucket[i]] = activeSum[i];
            bucketMax[activeBucket[i]] = activeMax[i];
        }
        activeBucket = null;
        activeSum = activeMax = null;
        activeCount = 0;
    }
}
