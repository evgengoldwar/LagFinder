package lagfinder.core;

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
    public int itemId = -1, itemDamage;

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

    /** Adds the period that just ended to the totals. */
    void finishPeriod(int bucket, int buckets, float seconds, long spikeNanos) {
        long nanos = periodNanos;
        totalNanos += nanos;
        calls += periodCalls;
        activePeriods++;
        if (nanos > maxNanos) {
            maxNanos = nanos;
            maxAt = seconds;
        }
        if (nanos >= spikeNanos) {
            spikes++;
        }
        if (bucketSum == null && depth == INSTANCE && activeCount < buckets / 4) {
            addSparse(bucket, nanos);
        } else {
            if (bucketSum == null) {
                toDense(buckets);
            }
            bucketSum[bucket] += nanos;
            if (nanos > bucketMax[bucket]) {
                bucketMax[bucket] = nanos;
            }
        }
        periodNanos = 0;
        periodCalls = 0;
        touched = false;
    }

    /** Adds a period to the buckets it was active in (buckets only grow along the scan). */
    private void addSparse(int bucket, long nanos) {
        if (activeCount > 0 && activeBucket[activeCount - 1] == bucket) {
            activeSum[activeCount - 1] += nanos;
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
        activeSum[activeCount] = nanos;
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
