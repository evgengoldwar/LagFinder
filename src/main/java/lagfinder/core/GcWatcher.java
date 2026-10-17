package lagfinder.core;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

public final class GcWatcher {

    private final List<GarbageCollectorMXBean> beans = new ArrayList<>();
    private final long[] counts;
    private final long[] millis;
    public long lastCount, lastMillis;

    public GcWatcher() {
        try {
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                String name = bean.getName();
                if (name != null && (name.contains("Cycles") || name.contains("Concurrent"))) {
                    continue;
                }
                beans.add(bean);
            }
        } catch (Throwable ignored) {
            beans.clear();
        }
        counts = new long[beans.size()];
        millis = new long[beans.size()];
        for (int i = 0; i < beans.size(); i++) {
            counts[i] = Math.max(
                0,
                beans.get(i)
                    .getCollectionCount());
            millis[i] = Math.max(
                0,
                beans.get(i)
                    .getCollectionTime());
        }
    }

    public boolean poll() {
        lastCount = 0;
        lastMillis = 0;
        try {
            read();
        } catch (Throwable failed) {
            beans.clear();
            return false;
        }
        return lastCount > 0;
    }

    private void read() {
        for (int i = 0; i < beans.size(); i++) {
            GarbageCollectorMXBean bean = beans.get(i);
            long count = bean.getCollectionCount(), time = bean.getCollectionTime();
            if (count > counts[i]) {
                lastCount += count - counts[i];
                lastMillis += Math.max(0, time - millis[i]);
            }
            counts[i] = Math.max(counts[i], count);
            millis[i] = Math.max(millis[i], time);
        }
    }
}
