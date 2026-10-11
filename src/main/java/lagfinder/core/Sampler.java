package lagfinder.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import lagfinder.LagFinder;

/**
 * Takes a sample of a thread's stack every few milliseconds while it works (during a tick, or a frame) and adds them
 * up into a call tree, like Spark's sampler: a method found on the stack in many samples is where the time went. Runs
 * on a thread of its own; what it gathered is read once it is stopped.
 */
public final class Sampler {

    /** Frames of a stack kept, from the thread's start: deeper ones are counted in the deepest kept. */
    private static final int MAX_DEPTH = 256;
    /** Lines of the call tree kept in the report at most: the least ones are left out until it fits. */
    private static final int MAX_NODES = 30_000;
    /** Lines taking less than this share of all the time are left out of the report's call tree. */
    private static final double MIN_SHARE = 0.0001;

    private final Thread target;
    private final long intervalNanos;
    private final Thread thread;
    private volatile boolean running = true;
    /** Whether the target is working now (in a tick or frame): samples are only taken then. */
    private volatile boolean active;

    // Touched by the sampling thread only, until it has stopped.
    private final Live root;
    private final List<String> mods = new ArrayList<>();
    /** Whether each mod is one samples can be put down to: not Minecraft, Forge, Java or a library. */
    private final List<Boolean> blamable = new ArrayList<>();
    private final Map<String, Integer> modIds = new HashMap<>();
    private final Map<String, Integer> classMods = new HashMap<>();
    private long[] modNanos = new long[32];
    private int samples;

    public Sampler(Thread target, int intervalMs) {
        this.target = target;
        this.intervalNanos = Math.max(1, intervalMs) * 1_000_000L;
        this.root = new Live("", target.getName(), modId(ModResolver.JAVA));
        this.thread = new Thread(this::run, "LagFinder sampler");
        thread.setDaemon(true);
        // Samples on time even while the game keeps the processor busy.
        thread.setPriority(Thread.MAX_PRIORITY);
    }

    public void start() {
        thread.start();
    }

    /** The target started (true) or ended (false) a tick or frame. */
    public void setActive(boolean active) {
        this.active = active;
    }

    /** Stops sampling and waits for it to end, so what it gathered can be read. */
    public void stop() {
        running = false;
        active = false;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
    }

    private void run() {
        long last = System.nanoTime();
        while (running) {
            long wait = intervalNanos - (System.nanoTime() - last);
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
            if (!running) {
                break;
            }
            long now = System.nanoTime();
            // The time since the last sample, so a sleep that overslept counts for what it was; but not a pause of the
            // whole machine.
            long weight = Math.min(now - last, intervalNanos * 3);
            last = now;
            if (!active) {
                continue;
            }
            StackTraceElement[] stack;
            try {
                stack = target.getStackTrace();
            } catch (Throwable t) {
                continue;
            }
            if (!active || stack.length == 0) {
                // The tick ended meanwhile: the stack may be of the time between ticks.
                continue;
            }
            try {
                synchronized (this) {
                    add(stack, weight);
                }
            } catch (Throwable t) {
                LagFinder.LOG.warn("LagFinder sampler stopped", t);
                return;
            }
        }
    }

    private static boolean isIdle(StackTraceElement top) {
        // Waiting for the next frame (the frame limiter) is no work.
        if (!"java.lang.Thread".equals(top.getClassName())) {
            return false;
        }
        String method = top.getMethodName();
        return method.startsWith("sleep") || method.startsWith("yield");
    }

    private void add(StackTraceElement[] stack, long weight) {
        if (isIdle(stack[0])) {
            return;
        }
        int blamed = blame(stack);
        samples++;
        if (blamed >= modNanos.length) {
            long[] grown = new long[Math.max(blamed + 1, modNanos.length * 2)];
            System.arraycopy(modNanos, 0, grown, 0, modNanos.length);
            modNanos = grown;
        }
        modNanos[blamed] += weight;
        Live node = root;
        node.add(weight, blamed, false);
        int last = Math.max(0, stack.length - MAX_DEPTH);
        // The stack starts with the method running now; the tree, with the thread's start.
        for (int i = stack.length - 1; i >= last; i--) {
            node = node.child(stack[i], this);
            node.add(weight, blamed, i == last);
        }
    }

    /**
     * The mod a sample is put down to: the one whose code is nearest the top of the stack. Without any, the game
     * (Minecraft or Forge) nearest the top, or what runs at the top.
     */
    private int blame(StackTraceElement[] stack) {
        int game = -1;
        for (StackTraceElement frame : stack) {
            int mod = classMod(frame.getClassName());
            if (blamable.get(mod)) {
                return mod;
            }
            if (game < 0 && (mods.get(mod)
                .equals(ModResolver.MINECRAFT)
                || mods.get(mod)
                    .equals(ModResolver.FORGE))) {
                game = mod;
            }
        }
        return game >= 0 ? game : classMod(stack[0].getClassName());
    }

    int classMod(String className) {
        Integer id = classMods.get(className);
        if (id == null) {
            String mod;
            try {
                mod = ModResolver.modOfClassName(className);
            } catch (Throwable t) {
                mod = Describer.UNKNOWN_MOD;
            }
            id = modId(mod);
            classMods.put(className, id);
        }
        return id;
    }

    private int modId(String mod) {
        Integer id = modIds.get(mod);
        if (id == null) {
            id = mods.size();
            mods.add(mod);
            blamable.add(
                !mod.equals(ModResolver.MINECRAFT) && !mod.equals(ModResolver.FORGE)
                    && !mod.equals(ModResolver.JAVA)
                    && !mod.equals(ModResolver.LIBRARIES)
                    && !mod.equals(Describer.UNKNOWN_MOD)
                    && !mod.equals(LagFinder.NAME));
            modIds.put(mod, id);
        }
        return id;
    }

    // ---------------------------------------------------------------- The result

    /** What was gathered, for the report. Call after {@link #stop}. */
    public synchronized Profile build() {
        Profile profile = new Profile();
        profile.thread = target.getName();
        profile.intervalMs = intervalNanos / 1e6f;
        profile.samples = samples;
        profile.mods.addAll(mods);
        profile.modMs = new double[mods.size()];
        for (int i = 0; i < mods.size() && i < modNanos.length; i++) {
            profile.modMs[i] = modNanos[i] / 1e6;
        }
        long min = (long) (root.total * MIN_SHARE);
        while (count(root, min) > MAX_NODES) {
            min = Math.max(1, min * 2);
        }
        profile.root = root.toNode(min, null);

        // Hot methods: each method's time over all the places it was called from.
        Map<String, Live> flat = new HashMap<>();
        for (Live child : root.children()) {
            flatten(child, flat, new HashSet<>());
        }
        List<Live> hot = new ArrayList<>(flat.values());
        hot.sort((a, b) -> Long.compare(b.self, a.self));
        for (int i = 0; i < hot.size() && i < Profile.HOT_METHODS; i++) {
            if (hot.get(i).self <= 0) {
                break;
            }
            profile.methods.add(
                hot.get(i)
                    .toNode(Long.MAX_VALUE, null));
        }
        return profile;
    }

    private static int count(Live node, long min) {
        int count = 1;
        for (Live child : node.children()) {
            if (child.total >= min) {
                count += count(child, min);
            }
        }
        return count;
    }

    /** Adds the method's time to its line in the list, its time in all only where it isn't called by itself. */
    private static void flatten(Live node, Map<String, Live> flat, Set<String> path) {
        String key = node.key();
        Live sum = flat.get(key);
        if (sum == null) {
            sum = new Live(node.className, node.method, node.mod);
            flat.put(key, sum);
        }
        boolean outermost = path.add(key);
        sum.merge(node, outermost);
        for (Live child : node.children()) {
            flatten(child, flat, path);
        }
        if (outermost) {
            path.remove(key);
        }
    }

    /** A line of the tree while sampling: its time in nanoseconds, by the mod it is put down to. */
    private static final class Live {

        final String className, method;
        final int mod;
        long total, self;
        int samples;
        private Map<String, Live> children;
        private int[] mods = new int[2];
        private long[] modTotal = new long[2], modSelf = new long[2];
        private int modCount;

        Live(String className, String method, int mod) {
            this.className = className;
            this.method = method;
            this.mod = mod;
        }

        String key() {
            return className + '.' + method;
        }

        Iterable<Live> children() {
            return children == null ? new ArrayList<>() : children.values();
        }

        Live child(StackTraceElement frame, Sampler sampler) {
            if (children == null) {
                children = new HashMap<>();
            }
            String key = frame.getClassName() + '.' + frame.getMethodName();
            Live child = children.get(key);
            if (child == null) {
                child = new Live(frame.getClassName(), frame.getMethodName(), sampler.classMod(frame.getClassName()));
                children.put(key, child);
            }
            return child;
        }

        private int slot(int mod) {
            for (int i = 0; i < modCount; i++) {
                if (mods[i] == mod) {
                    return i;
                }
            }
            if (modCount == mods.length) {
                int size = modCount * 2;
                mods = Arrays.copyOf(mods, size);
                modTotal = Arrays.copyOf(modTotal, size);
                modSelf = Arrays.copyOf(modSelf, size);
            }
            mods[modCount] = mod;
            return modCount++;
        }

        void add(long nanos, int blamed, boolean own) {
            total += nanos;
            samples++;
            int slot = slot(blamed);
            modTotal[slot] += nanos;
            if (own) {
                self += nanos;
                modSelf[slot] += nanos;
            }
        }

        void merge(Live other, boolean withTotal) {
            self += other.self;
            if (withTotal) {
                total += other.total;
                samples += other.samples;
            }
            for (int i = 0; i < other.modCount; i++) {
                int slot = slot(other.mods[i]);
                modSelf[slot] += other.modSelf[i];
                if (withTotal) {
                    modTotal[slot] += other.modTotal[i];
                }
            }
        }

        /** The line as kept in the report, with the lines under it taking at least {@code min}. */
        CallNode toNode(long min, CallNode parent) {
            CallNode node = new CallNode();
            node.className = className;
            node.method = method;
            node.mod = mod;
            node.totalMs = total / 1e6;
            node.selfMs = self / 1e6;
            node.samples = samples;
            node.parent = parent;
            node.depth = parent == null ? 0 : parent.depth + 1;
            int[] ids = new int[modCount];
            float[] totals = new float[modCount], selfs = new float[modCount];
            for (int i = 0; i < modCount; i++) {
                ids[i] = mods[i];
                totals[i] = modTotal[i] / 1e6f;
                selfs[i] = modSelf[i] / 1e6f;
            }
            node.setMods(ids, totals, selfs);
            if (children != null) {
                List<Live> kept = new ArrayList<>();
                for (Live child : children.values()) {
                    if (child.total >= min) {
                        kept.add(child);
                    }
                }
                kept.sort((a, b) -> Long.compare(b.total, a.total));
                for (Live child : kept) {
                    node.children.add(child.toNode(min, node));
                }
            }
            return node;
        }
    }
}
