package lagfinder.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class ComputerWork {

    private static final int MAX_QUEUED = 1 << 16;
    private static final ConcurrentLinkedQueue<Sample> QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<Long> THREADS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, String> CALLS = new ConcurrentHashMap<>();
    private static final AtomicInteger QUEUED = new AtomicInteger();

    private ComputerWork() {}

    public static ProfileSession session() {
        return ProfileSession.serverFromAnyThread();
    }

    public static void ran(ProfileSession session, Object host, long runNanos, long waitNanos) {
        THREADS.add(
            Thread.currentThread()
                .getId());
        add(new Sample(session, host, runNanos, waitNanos, 0));
    }

    public static void synced(ProfileSession session, Object host, long nanos) {
        add(new Sample(session, host, 0, 0, nanos));
    }

    public static void called(ProfileSession session, String component, String method, long nanos) {
        String key = component + "." + method;
        String known = CALLS.putIfAbsent(key, key);
        session.record(Category.OC_SYNC, known != null ? known : key, nanos);
    }

    public static int threads() {
        return THREADS.size();
    }

    private static void add(Sample sample) {
        if (sample.session == null || sample.host == null || QUEUED.get() >= MAX_QUEUED) {
            return;
        }
        QUEUED.incrementAndGet();
        QUEUE.add(sample);
    }

    static void drain(ProfileSession session) {
        Sample sample;
        while ((sample = QUEUE.poll()) != null) {
            QUEUED.decrementAndGet();
            if (sample.session == session) {
                session.recordComputer(sample.host, sample.runNanos, sample.waitNanos, sample.syncNanos);
            }
        }
    }

    private static final class Sample {

        final ProfileSession session;
        final Object host;
        final long runNanos, waitNanos, syncNanos;

        Sample(ProfileSession session, Object host, long runNanos, long waitNanos, long syncNanos) {
            this.session = session;
            this.host = host;
            this.runNanos = runNanos;
            this.waitNanos = waitNanos;
            this.syncNanos = syncNanos;
        }
    }
}
