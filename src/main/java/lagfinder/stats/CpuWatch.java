package lagfinder.stats;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

import lagfinder.core.Timeline;

public final class CpuWatch {

    private static final long INTERVAL_NANOS = 1_000_000_000L;

    public final Timeline process = new Timeline();
    public final Timeline system = new Timeline();
    private long lastSample;

    public void sample(long now) {
        if (lastSample != 0 && now - lastSample < INTERVAL_NANOS) {
            return;
        }
        lastSample = now;
        add(process, now, processLoad());
        add(system, now, systemLoad());
    }

    public void clear() {
        process.clear();
        system.clear();
        lastSample = 0;
    }

    private static void add(Timeline timeline, long now, double load) {
        if (load >= 0) {
            timeline.add(now, load * 100);
        }
    }

    @SuppressWarnings("deprecation")
    private static double systemLoad() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) os).getSystemCpuLoad();
        }
        return -1;
    }

    private static double processLoad() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuLoad();
        }
        return -1;
    }
}
