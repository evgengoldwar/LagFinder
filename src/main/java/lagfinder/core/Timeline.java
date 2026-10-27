package lagfinder.core;

import java.util.Arrays;

public final class Timeline {

    public static final int KEEP = 320;

    private final int capacity;
    private final long[] slotSecond;
    private final int[] count;
    private final double[] sum, low, high;
    private final boolean[] present;

    public Timeline() {
        this(KEEP);
    }

    public Timeline(int capacity) {
        this.capacity = capacity;
        slotSecond = new long[capacity];
        count = new int[capacity];
        sum = new double[capacity];
        low = new double[capacity];
        high = new double[capacity];
        present = new boolean[capacity];
        clear();
    }

    public static long second(long nanos) {
        return Math.floorDiv(nanos, 1_000_000_000L);
    }

    public synchronized void clear() {
        Arrays.fill(slotSecond, Long.MIN_VALUE);
    }

    private int slot(long second) {
        int i = (int) Math.floorMod(second, (long) capacity);
        if (slotSecond[i] != second) {
            slotSecond[i] = second;
            count[i] = 0;
            sum[i] = 0;
            low[i] = Double.POSITIVE_INFINITY;
            high[i] = Double.NEGATIVE_INFINITY;
            present[i] = false;
        }
        return i;
    }

    public synchronized void add(long nanos, double value) {
        int i = slot(second(nanos));
        count[i]++;
        sum[i] += value;
        low[i] = Math.min(low[i], value);
        high[i] = Math.max(high[i], value);
        present[i] = true;
    }

    public synchronized void cover(long fromNanos, long toNanos) {
        long last = second(toNanos);
        for (long s = Math.max(second(fromNanos), last - capacity + 1); s <= last; s++) {
            present[slot(s)] = true;
        }
    }

    public synchronized Span span(long first, long last) {
        Span span = new Span();
        for (long s = Math.max(first, last - capacity + 1); s <= last; s++) {
            int i = (int) Math.floorMod(s, (long) capacity);
            if (slotSecond[i] != s || !present[i]) {
                continue;
            }
            span.seconds++;
            span.count += count[i];
            span.sum += sum[i];
            span.low = Math.min(span.low, low[i]);
            span.high = Math.max(span.high, high[i]);
            span.fewest = Math.min(span.fewest, count[i]);
            span.most = Math.max(span.most, count[i]);
        }
        return span;
    }

    public static final class Span {

        public int seconds;
        public long count;
        public double sum;
        public double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        public int fewest = Integer.MAX_VALUE, most;

        public boolean isEmpty() {
            return seconds == 0;
        }

        public boolean hasValues() {
            return count > 0;
        }

        public double mean() {
            return count == 0 ? 0 : sum / count;
        }

        public double perSecond() {
            return seconds == 0 ? 0 : (double) count / seconds;
        }
    }
}
