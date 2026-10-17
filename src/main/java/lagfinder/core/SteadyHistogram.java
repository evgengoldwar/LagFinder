package lagfinder.core;

public final class SteadyHistogram {

    public static final int BINS = 128;

    private SteadyHistogram() {}

    public static int bin(long nanos) {
        if (nanos <= 0) {
            return 0;
        }
        int power = 63 - Long.numberOfLeadingZeros(nanos);
        long base = 1L << power;
        long half = base >>> 1;
        int halfIndex = half != 0 && nanos >= base + half ? 1 : 0;
        return Math.min(BINS - 1, 1 + power * 2 + halfIndex);
    }

    public static float percentile(int[] hist, int periods, float fraction) {
        if (hist == null || periods <= 0) {
            return 0;
        }
        int target = Math.max(1, (int) Math.ceil(periods * fraction));
        int sum = 0;
        for (int i = 0; i < hist.length; i++) {
            sum += hist[i];
            if (sum >= target) {
                if (i == 0) {
                    return 0;
                }
                int power = (i - 1) / 2;
                double base = Math.scalb(1.0, power);
                double low = (i & 1) == 0 ? base * 1.5 : base;
                double high = (i & 1) == 0 ? base * 2.0 : base * 1.5;
                return (float) ((low + high) / 2.0e6);
            }
        }
        return 0;
    }
}
