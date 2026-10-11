package lagfinder.core;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import lagfinder.LagFinder;

/**
 * Watches what objects the game creates during a scan, and where: the JVM's Flight Recorder takes samples of the
 * allocations ({@code jdk.ObjectAllocationSample}, Java 16 and later), each with the object's type, the thread and
 * the stack it was created on. The Flight Recorder is reached by reflection, so the mod still runs where there is
 * none: there the scanned thread's stack is sampled instead, with the bytes it allocated in between (who allocates,
 * roughly, but not what). What it gathered is read once it is stopped.
 */
public final class AllocationWatcher {

    private static final String EVENT = "jdk.ObjectAllocationSample";
    /** Types and creating classes kept in the report at most, and how many of them keep their timeline. */
    private static final int MAX_GROUPS = 3000, MAX_TIMELINES = 250;
    /** Places of creation kept in the report at most: the smallest are left out. */
    private static final int MAX_SITES = 12_000;
    /** Milliseconds between samples of the stack where there is no Flight Recorder. */
    private static final int STACK_INTERVAL_MS = 4;

    private final Thread target;
    private final long targetId;
    private final long startMillis;
    /** Length of a moment of the graph (ms), as the report's: not rounded, or the last moment takes the rest. */
    private final double bucketMillis;
    /** When the scan ended (epoch ms): allocations after it are not the scan's. Long.MAX_VALUE while it runs. */
    private volatile long endMillis = Long.MAX_VALUE;
    /** The time of the latest event the Flight Recorder handed over (epoch ms). */
    private volatile long lastEventMillis;
    /** The end of the scan as planned (epoch ms): nothing after it goes into its moments. */
    private final long plannedEndMillis;
    /** When the first allocation was seen (epoch ms): before it nothing is known. 0 until then. */
    private long firstMillis;
    /**
     * Threads seen by the Flight Recorder. A thread's first sample stands for all it allocated since it started,
     * hours of the game's allocation for its main thread: for a thread that ran before the scan it only marks where
     * that thread's samples start. A thread started during the scan allocated all of it during the scan: its first
     * sample counts.
     */
    private final Set<Long> sampledThreads = new HashSet<>();
    /** The threads running when the scan started; null when they couldn't be told (every first sample is left out). */
    private final Set<Long> threadsBefore;
    /** Starts the Flight Recorder (or the stack sampler) off the game's thread: starting it can take a second. */
    private Thread starter;
    private final int buckets, rate;

    /** The Flight Recorder's stream, while it runs; null when the stacks are sampled. */
    private Object stream;
    private Jfr jfr;
    /** The thread sampling the stack where there is no Flight Recorder. */
    private Thread sampler;
    private volatile boolean running = true;
    /** The thread delivering the Flight Recorder's events: what it allocates is ours. */
    private volatile long ownThreadId = -1;
    private String note;
    private int source = AllocationProfile.SOURCE_JFR;

    // Touched under the lock.
    private final Map<String, Live> types = new HashMap<>(), creators = new HashMap<>();
    private final Map<String, LiveSite> sites = new HashMap<>();
    private final float[] allMb, targetMb;
    private long bytes, targetBytes;
    private int samples, targetSamples;

    /**
     * @param rate how many allocations a second the Flight Recorder takes samples of at most
     */
    public AllocationWatcher(Thread target, long startMillis, long durationNanos, int buckets, int rate) {
        this.target = target;
        this.targetId = target.getId();
        this.startMillis = startMillis;
        this.buckets = buckets;
        this.bucketMillis = Math.max(0.001, durationNanos / 1e6 / buckets);
        this.plannedEndMillis = startMillis + durationNanos / 1_000_000L;
        this.rate = Math.max(10, rate);
        this.allMb = new float[buckets];
        this.targetMb = new float[buckets];
        this.threadsBefore = runningThreads();
    }

    private static Set<Long> runningThreads() {
        try {
            Set<Long> ids = new HashSet<>();
            for (long id : ManagementFactory.getThreadMXBean()
                .getAllThreadIds()) {
                ids.add(id);
            }
            return ids;
        } catch (Throwable t) {
            return null;
        }
    }

    public void start() {
        starter = new Thread(() -> {
            if (startRecorder()) {
                return;
            }
            source = AllocationProfile.SOURCE_STACKS;
            if (!running) {
                return;
            }
            sampler = new Thread(this::sampleStacks, "LagFinder allocation sampler");
            sampler.setDaemon(true);
            sampler.setPriority(Thread.MAX_PRIORITY);
            sampler.start();
        }, "LagFinder allocation starter");
        starter.setDaemon(true);
        starter.start();
    }

    /**
     * The scan ended now: what is allocated from here on is left out. Cheap, so it is called on the game's thread
     * right at the end; {@link #stop} (which waits) is not.
     */
    public void markEnd() {
        if (endMillis == Long.MAX_VALUE) {
            endMillis = System.currentTimeMillis();
        }
    }

    /**
     * Stops watching, once the Flight Recorder handed over the events of the scan it still holds (it hands them over
     * about once a second): that takes up to a couple of seconds, so never call it on the game's thread.
     */
    public void stop() {
        markEnd();
        running = false;
        if (starter != null) {
            try {
                starter.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
        }
        if (stream != null) {
            if (jfr.stop == null) {
                // Before Java 20 closing drops what isn't handed over yet: wait for the events up to the end.
                long deadline = System.currentTimeMillis() + 3000;
                while (lastEventMillis < endMillis && System.currentTimeMillis() < deadline) {
                    LockSupport.parkNanos(50_000_000L);
                }
            }
            jfr.stop(stream);
            stream = null;
        }
        if (sampler != null) {
            LockSupport.unpark(sampler);
            try {
                sampler.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
        }
    }

    // ---------------------------------------------------------------- The Flight Recorder

    private static int javaVersion() {
        String version = System.getProperty("java.specification.version", "1.8");
        try {
            return version.startsWith("1.") ? Integer.parseInt(version.substring(2)) : Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    private boolean startRecorder() {
        int java = javaVersion();
        if (java < 16) {
            note = "Java " + java;
            return false;
        }
        Object created = null;
        try {
            jfr = new Jfr();
            created = jfr.streamType.getConstructor()
                .newInstance();
            Object settings = jfr.enable.invoke(created, EVENT);
            jfr.with.invoke(settings, "throttle", rate + "/s");
            jfr.withStackTrace.invoke(settings);
            Consumer<Object> handler = this::onEvent;
            jfr.onEvent.invoke(created, EVENT, handler);
            jfr.startAsync.invoke(created);
            stream = created;
            return true;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
                ? t.getCause()
                : t;
            note = cause.getClass()
                .getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            LagFinder.LOG.warn("LagFinder could not start the Flight Recorder; sampling stacks instead", cause);
            if (created != null) {
                jfr.stop(created);
            }
            return false;
        }
    }

    /** One sample of an allocation, on the thread the Flight Recorder delivers its events on. */
    private void onEvent(Object event) {
        if (ownThreadId < 0) {
            ownThreadId = Thread.currentThread()
                .getId();
        }
        try {
            Object thread = jfr.getThread.invoke(event, "eventThread");
            long threadId = thread == null ? -1 : (Long) jfr.javaThreadId.invoke(thread);
            if (threadId == ownThreadId) {
                return;
            }
            long weight = (Long) jfr.getLong.invoke(event, "weight");
            if (weight <= 0) {
                return;
            }
            Object type = jfr.objectClass.invoke(event, "objectClass");
            String typeName = type == null ? AllocationProfile.UNKNOWN_TYPE
                : HeapWatcher.javaName((String) jfr.className.invoke(type));
            Instant time = (Instant) jfr.startTime.invoke(event);
            long epochMillis = time == null ? System.currentTimeMillis() : time.toEpochMilli();
            if (epochMillis > lastEventMillis) {
                lastEventMillis = epochMillis;
            }
            if (epochMillis < startMillis || epochMillis >= endMillis || epochMillis >= plannedEndMillis) {
                // Before the scan, or after it while the Flight Recorder was being stopped.
                return;
            }
            synchronized (this) {
                if (firstMillis == 0) {
                    firstMillis = epochMillis;
                }
                if (sampledThreads.add(threadId)
                    && (threadId < 0 || threadsBefore == null || threadsBefore.contains(threadId))) {
                    // Its weight is all the thread allocated before, much of it before the scan: only a start.
                    return;
                }
            }

            // The creating class: the nearest frame that isn't Java's or a library's.
            String creator = AllocationProfile.UNKNOWN_TYPE, method = "", via = "", top = null;
            int line = -1;
            Object trace = jfr.stackTrace.invoke(event);
            List<?> frames = trace == null ? null : (List<?>) jfr.frames.invoke(trace);
            if (frames != null) {
                String previous = null;
                for (Object frame : frames) {
                    if (!(Boolean) jfr.javaFrame.invoke(frame)) {
                        continue;
                    }
                    Object recorded = jfr.frameMethod.invoke(frame);
                    String className = (String) jfr.className.invoke(jfr.methodType.invoke(recorded));
                    String methodName = (String) jfr.methodName.invoke(recorded);
                    int frameLine = (Integer) jfr.lineNumber.invoke(frame);
                    if (top == null) {
                        top = className;
                        creator = className;
                        method = methodName;
                        line = frameLine;
                    }
                    if (!isPlumbing(className)) {
                        creator = className;
                        method = methodName;
                        line = frameLine;
                        via = previous == null ? "" : previous;
                        break;
                    }
                    previous = className + "." + methodName;
                }
            }
            record(typeName, creator, method, line, via, weight, threadId == targetId, epochMillis);
        } catch (Throwable t) {
            // An event that can't be read is left out.
        }
    }

    /**
     * Whether the thread is waiting (between ticks, for the next frame), not working: a sleep, a wait or a park among
     * the frames of Java on the top of its stack (going to sleep takes a few of them first).
     */
    private static boolean isIdle(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String type = frame.getClassName(), method = frame.getMethodName();
            if ("java.lang.Thread".equals(type) && (method.startsWith("sleep") || method.startsWith("yield"))
                || "java.lang.Object".equals(type) && method.startsWith("wait")
                || type.endsWith(".Unsafe") && method.equals("park")
                || "java.util.concurrent.locks.LockSupport".equals(type)) {
                return true;
            }
            if (!isPlumbing(type)) {
                return false;
            }
        }
        return false;
    }

    /** Whether the class is Java's or a library's: what it allocates goes to whoever called it. */
    private static boolean isPlumbing(String className) {
        String mod;
        try {
            mod = ModResolver.modOfClassName(className);
        } catch (Throwable t) {
            return false;
        }
        return mod.equals(ModResolver.JAVA) || mod.equals(ModResolver.LIBRARIES);
    }

    /** The parts of the Flight Recorder's API used, found by reflection. */
    private static final class Jfr {

        final Class<?> streamType;
        final Method enable, onEvent, startAsync, close, stop;
        final Method with, withStackTrace;
        final Method getThread, getLong, objectClass, startTime, stackTrace;
        final Method javaThreadId, className, frames, javaFrame, frameMethod, lineNumber, methodType, methodName;

        Jfr() throws ReflectiveOperationException {
            streamType = type("jdk.jfr.consumer.RecordingStream");
            enable = streamType.getMethod("enable", String.class);
            onEvent = streamType.getMethod("onEvent", String.class, Consumer.class);
            startAsync = streamType.getMethod("startAsync");
            close = streamType.getMethod("close");
            Method stopping;
            try {
                // Java 20 and later: hands over the events still held before it returns.
                stopping = streamType.getMethod("stop");
            } catch (NoSuchMethodException e) {
                stopping = null;
            }
            stop = stopping;
            Class<?> settings = type("jdk.jfr.EventSettings");
            with = settings.getMethod("with", String.class, String.class);
            withStackTrace = settings.getMethod("withStackTrace");
            Class<?> event = type("jdk.jfr.consumer.RecordedEvent");
            getThread = event.getMethod("getThread", String.class);
            getLong = event.getMethod("getLong", String.class);
            objectClass = event.getMethod("getClass", String.class);
            startTime = event.getMethod("getStartTime");
            stackTrace = event.getMethod("getStackTrace");
            javaThreadId = type("jdk.jfr.consumer.RecordedThread").getMethod("getJavaThreadId");
            className = type("jdk.jfr.consumer.RecordedClass").getMethod("getName");
            frames = type("jdk.jfr.consumer.RecordedStackTrace").getMethod("getFrames");
            Class<?> frame = type("jdk.jfr.consumer.RecordedFrame");
            javaFrame = frame.getMethod("isJavaFrame");
            frameMethod = frame.getMethod("getMethod");
            lineNumber = frame.getMethod("getLineNumber");
            Class<?> recordedMethod = type("jdk.jfr.consumer.RecordedMethod");
            methodType = recordedMethod.getMethod("getType");
            methodName = recordedMethod.getMethod("getName");
        }

        /** The JDK's own class: through the system class loader, past the game's. */
        private static Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, false, ClassLoader.getSystemClassLoader());
        }

        void stop(Object stream) {
            try {
                if (stop != null) {
                    stop.invoke(stream);
                }
            } catch (Throwable t) {
                LagFinder.LOG.warn("LagFinder could not stop the Flight Recorder cleanly", t);
            }
            try {
                close.invoke(stream);
            } catch (Throwable t) {
                LagFinder.LOG.warn("LagFinder could not close the Flight Recorder", t);
            }
        }
    }

    // ---------------------------------------------------------------- Without a Flight Recorder

    /** Samples the scanned thread's stack, putting the bytes it allocated since the last sample down to it. */
    private void sampleStacks() {
        com.sun.management.ThreadMXBean threads;
        try {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!(bean instanceof com.sun.management.ThreadMXBean)
                || !((com.sun.management.ThreadMXBean) bean).isThreadAllocatedMemorySupported()) {
                note = (note == null ? "" : note + ", ") + "no allocation counter";
                return;
            }
            threads = (com.sun.management.ThreadMXBean) bean;
            if (!threads.isThreadAllocatedMemoryEnabled()) {
                threads.setThreadAllocatedMemoryEnabled(true);
            }
        } catch (Throwable t) {
            note = (note == null ? "" : note + ", ") + t.getClass()
                .getSimpleName();
            return;
        }
        long last = threads.getThreadAllocatedBytes(targetId);
        synchronized (this) {
            if (firstMillis == 0) {
                firstMillis = System.currentTimeMillis();
            }
        }
        // Where the thread was last seen working: creator, method, line, via.
        String[] lastSite = null;
        while (running) {
            LockSupport.parkNanos(STACK_INTERVAL_MS * 1_000_000L);
            if (!running) {
                break;
            }
            try {
                long now = threads.getThreadAllocatedBytes(targetId);
                long weight = now - last;
                last = now;
                if (weight <= 0) {
                    continue;
                }
                if (System.currentTimeMillis() >= Math.min(endMillis, plannedEndMillis)) {
                    break;
                }
                StackTraceElement[] stack = target.getStackTrace();
                if (stack.length == 0) {
                    continue;
                }
                if (isIdle(stack)) {
                    // Waiting between ticks (or for the next frame): the bytes were allocated before it started to
                    // wait, where it was seen last, not by the loop that waits.
                    if (lastSite != null) {
                        record(
                            AllocationProfile.UNKNOWN_TYPE,
                            lastSite[0],
                            lastSite[1],
                            Integer.parseInt(lastSite[2]),
                            lastSite[3],
                            weight,
                            true,
                            System.currentTimeMillis());
                    }
                    continue;
                }
                int at = 0;
                while (at < stack.length - 1 && isPlumbing(stack[at].getClassName())) {
                    at++;
                }
                if (isPlumbing(stack[at].getClassName())) {
                    at = 0;
                }
                String via = at == 0 ? "" : stack[at - 1].getClassName() + "." + stack[at - 1].getMethodName();
                lastSite = new String[] { stack[at].getClassName(), stack[at].getMethodName(),
                    String.valueOf(stack[at].getLineNumber()), via };
                record(
                    AllocationProfile.UNKNOWN_TYPE,
                    lastSite[0],
                    lastSite[1],
                    stack[at].getLineNumber(),
                    via,
                    weight,
                    true,
                    System.currentTimeMillis());
            } catch (Throwable t) {
                LagFinder.LOG.warn("LagFinder allocation sampler stopped", t);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- Adding up

    private static final class Live {

        final String name;
        long bytes, targetBytes;
        int samples, targetSamples;
        final float[] timeline, targetTimeline;

        Live(String name, int buckets) {
            this.name = name;
            this.timeline = new float[buckets];
            this.targetTimeline = new float[buckets];
        }

        void add(long weight, boolean target, int bucket) {
            float mb = weight / 1048576f;
            bytes += weight;
            samples++;
            timeline[bucket] += mb;
            if (target) {
                targetBytes += weight;
                targetSamples++;
                targetTimeline[bucket] += mb;
            }
        }
    }

    private static final class LiveSite {

        final String type, creator, method, via;
        final int line;
        long bytes, targetBytes;
        int samples, targetSamples;

        LiveSite(String type, String creator, String method, int line, String via) {
            this.type = type;
            this.creator = creator;
            this.method = method;
            this.line = line;
            this.via = via;
        }
    }

    private synchronized void record(String type, String creator, String method, int line, String via, long weight,
        boolean isTarget, long epochMillis) {
        int bucket = (int) Math.max(0, Math.min(buckets - 1, (epochMillis - startMillis) / bucketMillis));
        if (epochMillis >= endMillis || epochMillis >= plannedEndMillis) {
            return;
        }
        float mb = weight / 1048576f;
        bytes += weight;
        samples++;
        allMb[bucket] += mb;
        if (isTarget) {
            targetBytes += weight;
            targetSamples++;
            targetMb[bucket] += mb;
        }
        live(types, type).add(weight, isTarget, bucket);
        live(creators, creator).add(weight, isTarget, bucket);
        String key = type + '\u0000' + creator + '\u0000' + method + '\u0000' + line + '\u0000' + via;
        LiveSite site = sites.get(key);
        if (site == null) {
            site = new LiveSite(type, creator, method, line, via);
            sites.put(key, site);
        }
        site.bytes += weight;
        site.samples++;
        if (isTarget) {
            site.targetBytes += weight;
            site.targetSamples++;
        }
    }

    private Live live(Map<String, Live> map, String name) {
        Live live = map.get(name);
        if (live == null) {
            live = new Live(name, buckets);
            map.put(name, live);
        }
        return live;
    }

    // ---------------------------------------------------------------- The result

    /** What was gathered, for the report. Call after {@link #stop}. */
    public synchronized AllocationProfile build() {
        AllocationProfile profile = new AllocationProfile();
        profile.source = source;
        profile.thread = target.getName();
        profile.note = note;
        profile.recordedFromSec = firstMillis == 0 ? 0 : Math.max(0, (firstMillis - startMillis) / 1000f);
        profile.samples = samples;
        profile.targetSamples = targetSamples;
        profile.bytes = bytes;
        profile.targetBytes = targetBytes;
        profile.allMb = allMb.clone();
        profile.targetMb = targetMb.clone();

        Map<String, Integer> modIds = new HashMap<>();
        Map<String, Integer> typeIds = keep(types, profile.types, profile, modIds, true);
        Map<String, Integer> creatorIds = keep(creators, profile.creators, profile, modIds, false);

        // The biggest places of creation of all threads and of the scanned one: busy threads of their own don't
        // crowd the scanned thread's out of the report.
        for (LiveSite live : biggest(sites.values(), site -> site.bytes, site -> site.targetBytes, MAX_SITES)) {
            Integer type = typeIds.get(live.type), creator = creatorIds.get(live.creator);
            if (type == null || creator == null) {
                continue;
            }
            AllocationProfile.Site site = new AllocationProfile.Site();
            site.type = type;
            site.creator = creator;
            site.method = live.method;
            site.line = live.line;
            site.via = live.via;
            site.bytes = live.bytes;
            site.targetBytes = live.targetBytes;
            site.samples = live.samples;
            site.targetSamples = live.targetSamples;
            profile.sites.add(site);
        }
        return profile;
    }

    /** Puts the biggest types (or creating classes) in the report, the biggest with their timeline; their indexes. */
    private static Map<String, Integer> keep(Map<String, Live> lives, List<AllocationProfile.Group> into,
        AllocationProfile profile, Map<String, Integer> modIds, boolean isType) {
        // The scanned thread's biggest are kept, with their timeline, though they may be small among all threads.
        Set<Live> timelines = new HashSet<>(
            biggest(lives.values(), live -> live.bytes, live -> live.targetBytes, MAX_TIMELINES * 2));
        Map<String, Integer> ids = new HashMap<>();
        for (Live live : biggest(lives.values(), l -> l.bytes, l -> l.targetBytes, MAX_GROUPS)) {
            AllocationProfile.Group group = new AllocationProfile.Group();
            group.name = live.name;
            group.mod = modId(modOf(live.name, isType), profile, modIds);
            group.bytes = live.bytes;
            group.targetBytes = live.targetBytes;
            group.samples = live.samples;
            group.targetSamples = live.targetSamples;
            if (timelines.contains(live)) {
                group.timeline = live.timeline.clone();
                group.targetTimeline = live.targetTimeline.clone();
            }
            ids.put(live.name, into.size());
            into.add(group);
        }
        return ids;
    }

    /**
     * At most {@code max} of the items: the biggest of all threads and the biggest of the scanned one, in turn, so
     * each gets about half when there are too many; biggest of all threads first.
     */
    private static <T> List<T> biggest(java.util.Collection<T> items, ToLongFunction<T> all, ToLongFunction<T> target,
        int max) {
        List<T> byAll = new ArrayList<>(items);
        byAll.sort(
            Comparator.comparingLong(all)
                .reversed());
        List<T> chosen;
        if (byAll.size() <= max) {
            chosen = byAll;
        } else {
            List<T> byTarget = new ArrayList<>(items);
            byTarget.sort(
                Comparator.comparingLong(target)
                    .reversed());
            Set<T> kept = new HashSet<>();
            for (int i = 0; i < byAll.size() && kept.size() < max; i++) {
                kept.add(byAll.get(i));
                if (kept.size() < max && target.applyAsLong(byTarget.get(i)) > 0) {
                    kept.add(byTarget.get(i));
                }
            }
            chosen = new ArrayList<>(kept);
            chosen.sort(
                Comparator.comparingLong(all)
                    .reversed());
        }
        return chosen;
    }

    private static String modOf(String name, boolean isType) {
        if (AllocationProfile.UNKNOWN_TYPE.equals(name)) {
            return Describer.UNKNOWN_MOD;
        }
        if (isType) {
            return HeapWatcher.modOf(name);
        }
        try {
            return ModResolver.modOfClassName(name);
        } catch (Throwable t) {
            return Describer.UNKNOWN_MOD;
        }
    }

    private static int modId(String mod, AllocationProfile profile, Map<String, Integer> modIds) {
        Integer id = modIds.get(mod);
        if (id == null) {
            id = profile.mods.size();
            modIds.put(mod, id);
            profile.mods.add(mod);
        }
        return id;
    }
}
