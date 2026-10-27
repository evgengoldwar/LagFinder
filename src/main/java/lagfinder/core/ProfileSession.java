package lagfinder.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import lagfinder.Config;
import lagfinder.LagFinder;
import lagfinder.compat.EnderIoConduits;
import lagfinder.compat.GregTechPipes;

/**
 * A running scan: the hooks in the game hand it the time each measured thing took, and the side running it marks
 * where each tick (or frame) starts and ends. Only measures on the thread it was started for, so the server's and
 * the client's scans in single player never mix.
 */
public final class ProfileSession {

    /** Points of time a scan is split into, for the graphs. */
    public static final int BUCKETS = 120;

    private static volatile ProfileSession server, client;

    /**
     * The lag scan timing things on this thread, or null: the hooks call it for every tile entity, entity, block tick,
     * event and renderer, so it is cheap. Scans for something else (methods, objects, allocations) time only their
     * ticks or frames: timing every thing would cost them and show up in what they measure.
     */
    public static ProfileSession current() {
        ProfileSession session = server;
        if (session != null && session.timesThings && session.measuring && session.thread == Thread.currentThread()) {
            return session;
        }
        session = client;
        if (session != null && session.timesThings && session.measuring && session.thread == Thread.currentThread()) {
            return session;
        }
        return null;
    }

    public static ProfileSession currentServer() {
        ProfileSession session = server;
        if (session != null && session.timesThings
            && session.measuring
            && session.mode == ScanMode.TPS
            && session.thread == Thread.currentThread()) {
            return session;
        }
        return null;
    }

    public static ProfileSession serverFromAnyThread() {
        ProfileSession session = server;
        if (session != null && session.timesThings && session.mode == ScanMode.TPS && !session.closed) {
            return session;
        }
        return null;
    }

    public static void setServer(ProfileSession session) {
        server = session;
    }

    public static void setClient(ProfileSession session) {
        client = session;
    }

    public final ScanMode mode;
    /** What the scan is for: what it watches besides the time things take. */
    private ScanFocus focus = ScanFocus.LAG;
    /** Whether the time of each thing is measured: in lag scans only. */
    private volatile boolean timesThings = true;
    /**
     * A light scan measures one period in this many and lets the others run untouched (1: every period). What a
     * measured period finds stands for all the periods it represents, so averages per tick stay right.
     */
    private int sampleEvery = 1;
    /** Whether the period running now is measured (always, unless the scan is light). */
    private volatile boolean measuring = true;
    public final Thread thread;
    public final long durationNanos;
    public final long startNanos;
    public final long startMillis;
    public final long bucketNanos;
    /** A period of a thing at least this long (ms) is a spike. */
    private final float spikeMs;
    private final long spikeNanos;
    private final Describer describer;
    /** Samples of the thread's stack, for the call tree; null when not sampled. */
    private Sampler sampler;
    /** Watches the Java heap, for the report's memory; null when not watched. */
    private HeapWatcher heap;
    /** The heap watcher of a scan that ended, until {@link #completeReport} finishes the report's memory with it. */
    private volatile HeapWatcher finishingHeap;
    /** Watches what objects are created and where, for the report's allocations; null when not watched. */
    private AllocationWatcher allocations;
    /** The watcher of a scan that ended, until {@link #completeReport} finishes the report's allocations with it. */
    private volatile AllocationWatcher finishingAllocations;

    private final Node[] roots = new Node[Category.values().length];
    @SuppressWarnings("unchecked")
    private final IdentityHashMap<Object, Node>[] leaves = new IdentityHashMap[Category.values().length];
    private final IdentityHashMap<Node, long[]> computerStats = new IdentityHashMap<>();
    /** Block ticks by world, block and chunk. */
    private final Map<World, IdentityHashMap<Object, HashMap<Long, Node>>> blockTicks = new IdentityHashMap<>();
    private final Map<World, IdentityHashMap<Object, HashMap<Long, Node>>> lightAt = new IdentityHashMap<>();
    private final Map<World, IdentityHashMap<Object, HashMap<Long, Node>>> neighborsAt = new IdentityHashMap<>();
    private final Map<Long, WorldTask.Key> worldTaskKeys = new HashMap<>();
    public static final int SPAN_LIGHT = 0, SPAN_PATH = 1;
    private final int[] spanDepth = new int[2];
    private final long[] spanStart = new long[2], spanOuter = new long[2];
    private final GcWatcher gc = new GcWatcher();
    private final double[] gcMillis = new double[BUCKETS];
    private final int[] gcCounts = new int[BUCKETS];
    /**
     * Tile entities by world and block: one reloaded with its chunk is a new object at the same place, and is still
     * counted as the same thing.
     */
    private final Map<World, HashMap<Long, Node>> tilesAt = new IdentityHashMap<>();
    /** What AE2 networks ticked, by network: their machines and their own work. */
    private final Map<Object, IdentityHashMap<Object, Node>> networks = new IdentityHashMap<>();
    /** GregTech covers that worked, by the tile they are on and its side. */
    private final Map<TileEntity, Node[]> covers = new IdentityHashMap<>();
    public static final int PIPE_GT_TICK = 0, PIPE_GT_ROUTE = 1, PIPE_GT_ENERGY = 2, PIPE_EIO_NETWORK = 3,
        PIPE_EIO_CONDUIT = 4, PIPE_AE2 = 5;
    private final IdentityHashMap<Object, Node> pipeNetworks = new IdentityHashMap<>();
    private final Map<TileEntity, GregTechPipes.Network> gtNetworks = new IdentityHashMap<>();
    /**
     * Time measured inside the thing being measured now (an event a tile entity posts, the AE2 networks an event
     * handler ticks), left out of its own time: everything is counted once, where its own code runs.
     */
    private long nestedNanos;
    private final List<Node> pending = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private final List<Node>[] touched = new List[4];

    /** What one call of {@code System.nanoTime()} costs here (ns): measured when the scan starts. */
    private final long timerNanos;
    /** What recording one thing usually takes after its clock is read (ns), and how many were recorded. */
    private long bookkeepingNanos;
    private int leaveCount;
    /**
     * The scan's own cost in the period being measured (ns): reading the clock around each thing and adding its time
     * up. Left out of the period's time, so the report shows the game as it runs without the scan.
     */
    private long periodOverhead;
    /** The scan's own cost over the whole scan, between the periods too (ns). */
    private long totalOverhead;
    /** Ends of the ticks (or frames) of the last second, in the game's time (a ring), and the worst second's rate. */
    private static final long SECOND_NANOS = 1_000_000_000L;
    private long[] ends = new long[64];
    private int endFirst, endCount;
    private float lowestSecond = -1;
    /** The bucket the worst second ended in, -1 for none: the graph shows it there. */
    private int lowestSecondBucket = -1;
    /** The worlds' ticks by dimension: one unloaded and loaded again during the scan is still one line. */
    private final Map<Integer, Node> worlds = new HashMap<>();

    /** How often each category's hook measured something: a hook that never did may not have been put in. */
    private final int[] hits = new int[Category.values().length];
    /** The ticks were marked by the tick events, not around MinecraftServer.tick() (its mixin didn't apply). */
    private boolean tickedByEvents;
    /** Names of the dimensions seen loaded along the scan: one unloaded before its end still has its name. */
    private final Map<Integer, String> knownDims = new HashMap<>();
    /** When things gone from the world are next let go (ns). */
    private long nextSweep;
    private static final long SWEEP_NANOS = 5_000_000_000L;
    /**
     * {@link #close} ran, and {@link #prepareReport} did. Once closed, nothing more is recorded: a hook still running
     * when the scan ended (the tick event that ended it) leaves alone what the report is being built from.
     */
    private volatile boolean closed;
    private boolean prepared;
    private final int[] steadyTickHistogram = new int[SteadyHistogram.BINS];
    private final List<String> warnings = new ArrayList<>();

    /**
     * Time a frame spent waiting rather than working (the frame limiter's sleep, the wait for vsync), in the period
     * being measured and per bucket: what a frame costs and what it would gain without something depends on it.
     */
    private long periodWait;
    private final double[] waitNanos = new double[BUCKETS];
    /** The frame rate the frames were held to (the limit, or vsync's), 0 for none. */
    private float frameCap;

    private final int[] periods = new int[BUCKETS];
    private final double[] periodNanos = new double[BUCKETS];
    private final float[] periodMax = new float[BUCKETS];
    private int totalPeriods;
    /** End of the last period, in the scan's time (the pauses left out). */
    private long lastEnd;
    /**
     * Time the game stood paused during the scan (a single player world: the pause menu, or the window left), and
     * when the pause going on started (0 for none). Left out of the scan as if it hadn't happened: no tick runs then,
     * and counting it would show a server that is fine as standing still.
     */
    private long pausedNanos, pausedSince;

    public ProfileSession(ScanMode mode, Thread thread, long durationNanos, float spikeMs, Describer describer) {
        this.mode = mode;
        this.thread = thread;
        this.durationNanos = durationNanos;
        this.describer = describer;
        this.spikeMs = spikeMs;
        this.spikeNanos = (long) (spikeMs * 1_000_000L);
        this.startNanos = System.nanoTime();
        this.startMillis = System.currentTimeMillis();
        this.bucketNanos = Math.max(1, durationNanos / BUCKETS);
        this.lastEnd = startNanos;
        this.timerNanos = timerCost();
        for (Category category : Category.values()) {
            roots[category.ordinal()] = new Node(Node.CATEGORY, category, category);
            leaves[category.ordinal()] = new IdentityHashMap<>();
        }
        for (int i = 0; i < touched.length; i++) {
            touched[i] = new ArrayList<>();
        }
    }

    /** What one call of {@code System.nanoTime()} costs (ns): the cheapest average of a few rounds of calls. */
    private static long timerCost() {
        long best = Long.MAX_VALUE, sink = 0;
        for (int round = 0; round < 5; round++) {
            long from = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                sink += System.nanoTime();
            }
            best = Math.min(best, (System.nanoTime() - from) / 1001);
        }
        // Keeps the calls from being optimized away.
        return sink == 42 ? best + 1 : Math.max(0, best);
    }

    /**
     * Starts what the scan is for, and only that: a lag scan times each thing; a methods scan samples the thread's
     * stack; an objects scan watches the heap and counts its objects a few times along; an allocations scan watches
     * what
     * objects are created and by which classes. Every scan times its ticks or frames, for its graphs.
     */
    public void startFor(ScanFocus focus) {
        this.focus = focus;
        timesThings = focus == ScanFocus.LAG || focus == ScanFocus.STEADY;
        switch (focus) {
            case METHODS:
                startSampler(Config.sampleIntervalMs);
                break;
            case OBJECTS:
                startHeapWatcher(Math.max(2, Config.heapSnapshots), Config.heapLiveOnly);
                break;
            case ALLOCATIONS:
                allocations = new AllocationWatcher(
                    thread,
                    startMillis,
                    durationNanos,
                    BUCKETS,
                    Config.allocSampleRate);
                allocations.start();
                break;
            default:
                break;
        }
    }

    /** Starts sampling the thread's stack every few milliseconds while it works, for the report's call tree. */
    public void startSampler(int intervalMs) {
        sampler = new Sampler(thread, intervalMs);
        sampler.start();
    }

    /**
     * Starts watching the Java heap: its use and the allocation all along, and the objects in it by class counted
     * {@code snapshots} times (0 for never), only those in use when {@code liveOnly}.
     */
    public void startHeapWatcher(int snapshots, boolean liveOnly) {
        heap = new HeapWatcher(thread, startNanos, durationNanos, BUCKETS, snapshots, liveOnly);
        heap.start();
    }

    /**
     * The scan ended or was cancelled: stops sampling, and marks the end of the heap's watch and of the allocations
     * without waiting for them (a count of the objects under way can take seconds; the report waits for it in
     * {@link #completeReport}, off the game's thread).
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (sampler != null) {
            sampler.stop();
        }
        if (heap != null) {
            heap.markEnd();
        }
        if (allocations != null) {
            // What is allocated from now on is left out; the report (or discard) stops the watcher.
            allocations.markEnd();
        }
    }

    /**
     * The scan was cancelled (or the game left): stops everything, the Flight Recorder too, in the background since
     * stopping it waits for its last events.
     */
    public void discard() {
        close();
        AllocationWatcher watcher = allocations != null ? allocations : finishingAllocations;
        allocations = null;
        finishingAllocations = null;
        if (watcher != null) {
            Thread stopper = new Thread(watcher::stop, "LagFinder allocation stopper");
            stopper.setDaemon(true);
            stopper.start();
        }
    }

    /**
     * Makes the scan light: one period in {@code every} is measured, the others run without the scan's hooks. Only for
     * lag scans, whose report is about averages; a steady scan needs every tick.
     */
    public void setSampleEvery(int every) {
        if (focus == ScanFocus.LAG && every > 1) {
            sampleEvery = every;
            warn("lagfinder.warning.light_scan");
        }
    }

    public int sampleEvery() {
        return sampleEvery;
    }

    /** A tick (or frame) starts at {@code now}: a pause going on ends, and the thread is sampled until it ends. */
    public void beginPeriod(long now) {
        if (pausedSince != 0) {
            pausedNanos += Math.max(0, now - pausedSince);
            pausedSince = 0;
        }
        measuring = sampleEvery <= 1 || totalPeriods % sampleEvery == 0;
        if (sampler != null) {
            sampler.setActive(true);
        }
    }

    /**
     * The game stands paused (no tick runs) since {@code since}: the time until the next period starts is left out of
     * the scan (never what was before the scan started).
     */
    public void paused(long since) {
        if (pausedSince == 0) {
            pausedSince = Math.max(since, startNanos);
        }
    }

    /** Whether the game stands paused now (no tick runs; the scan doesn't move on). */
    public boolean isPaused() {
        return pausedSince != 0;
    }

    /** How long the scan has run at {@code now}, the pauses left out (ns). */
    private long elapsed(long now) {
        return (pausedSince != 0 ? pausedSince : now) - startNanos - pausedNanos;
    }

    /** How far along the scan is, 0 to 1. */
    public float progress() {
        return Math.min(1f, elapsed(System.nanoTime()) / (float) durationNanos);
    }

    /** How long the scan still runs (ns). */
    public long nanosLeft() {
        return Math.max(0, durationNanos - elapsed(System.nanoTime()));
    }

    public boolean isDone(long now) {
        return elapsed(now) >= durationNanos;
    }

    /**
     * Something measured starts: what is measured inside it from now on is counted for itself, not for it. Hand what it
     * returns to the {@code record} that ends it.
     */
    public long enter() {
        long outer = nestedNanos;
        nestedNanos = 0;
        return outer;
    }

    /**
     * The own time of the thing entered at {@code start} that ended at {@code end}: without what was measured inside
     * it, nor the reading of the clock.
     */
    private long ownTime(long start, long end) {
        return Math.max(0, end - start - nestedNanos - timerNanos);
    }

    /**
     * Its time is all the outer thing's own time but what was measured inside it; this bookkeeping included. What the
     * scan itself took here, from reading the clock at {@code start} to now, is counted as its cost.
     */
    private void leave(long start, long end, long outer) {
        // Reading the clock once more for every thing costs as much as the bookkeeping it would time: it is read one
        // time in 64, and the bookkeeping's usual cost stands for it the other times.
        long now;
        if ((++leaveCount & 63) == 0) {
            now = System.nanoTime();
            bookkeepingNanos = (bookkeepingNanos * 7 + Math.max(0, now - end)) / 8;
        } else {
            now = end + bookkeepingNanos;
        }
        nestedNanos = outer + (now - start);
        // Half a reading before the start, one inside, the bookkeeping since the end, half a reading after now.
        periodOverhead += now - end + 2 * timerNanos;
    }

    /**
     * The thing (an entity, a listener, a renderer) {@link #enter entered} at {@code start} ended: its own time is
     * recorded, what was measured inside it left out.
     */
    public void record(Category category, Object key, long start, long outer) {
        if (closed) {
            return;
        }
        long end = System.nanoTime();
        record(category, key, ownTime(start, end));
        leave(start, end, outer);
    }

    /** As {@link #record(Category, Object, long, long)}, for a tile entity's update. */
    public void recordTile(TileEntity tile, long start, long outer) {
        if (closed) {
            return;
        }
        long end = System.nanoTime();
        long nanos = ownTime(start, end);
        if (timesThings) {
            // The line is kept on the tile itself (a mixin): no map look-up for the tens of thousands a tick.
            TileNodeCache cache = tile instanceof TileNodeCache ? (TileNodeCache) tile : null;
            Node node = cache != null ? cache.lagfinder$cachedNode(this) : null;
            if (node == null) {
                IdentityHashMap<Object, Node> map = leaves[Category.TILE_ENTITIES.ordinal()];
                node = map.get(tile);
                if (node == null) {
                    node = tileAt(tile);
                    map.put(tile, node);
                }
                if (cache != null) {
                    cache.lagfinder$cacheNode(this, node);
                }
            }
            node.add(nanos, 1, this);
            if (focus == ScanFocus.STEADY) {
                node.recordGtActivity(tile, nanos, totalPeriods);
            }
            hits[Category.TILE_ENTITIES.ordinal()]++;
        }
        leave(start, end, outer);
    }

    /** The line of the tile entity's block: the one already there when it is the same kind of tile, or a new one. */
    private Node tileAt(TileEntity tile) {
        World world = tile.getWorldObj();
        HashMap<Long, Node> byPlace = null;
        Long place = null;
        if (world != null) {
            byPlace = tilesAt.get(world);
            if (byPlace == null) {
                byPlace = new HashMap<>();
                tilesAt.put(world, byPlace);
            }
            place = ((long) tile.xCoord & 0x3FFFFFFL) << 38 | ((long) tile.zCoord & 0x3FFFFFFL) << 12
                | (tile.yCoord & 0xFFFL);
            Node known = byPlace.get(place);
            if (known != null && known.keyClass == tile.getClass()) {
                return known;
            }
        }
        Node node = new Node(Node.INSTANCE, Category.TILE_ENTITIES, tile);
        pending.add(node);
        if (byPlace != null) {
            byPlace.put(place, node);
        }
        return node;
    }

    /**
     * An AE2 network's machine ({@code machine}, its grid node) or its own work ({@code machine} false, the cache) that
     * was {@link #enter entered} at {@code start} ended.
     */
    public void recordNetwork(Object network, Object thing, boolean machine, long start, long outer) {
        if (closed) {
            return;
        }
        long end = System.nanoTime();
        long nanos = ownTime(start, end);
        if (timesThings && network != null) {
            IdentityHashMap<Object, Node> things = networks.get(network);
            if (things == null) {
                things = new IdentityHashMap<>();
                networks.put(network, things);
            }
            Node node = things.get(thing);
            if (node == null) {
                node = new Node(machine ? Node.INSTANCE : Node.TYPE, Category.AE2_NETWORKS, thing);
                node.live = network;
                things.put(thing, node);
                pending.add(node);
            }
            node.add(nanos, 1, this);
            addToNetwork(network, PIPE_AE2, nanos);
        }
        leave(start, end, outer);
    }

    /**
     * A GregTech cover on {@code side} (0-5) of {@code tile} that was {@link #enter entered} at {@code start} finished
     * its work: counted per block and side, out of the time of the tile it is on.
     */
    public void recordCover(TileEntity tile, int side, Object cover, long start, long outer) {
        if (closed) {
            return;
        }
        long end = System.nanoTime();
        long nanos = ownTime(start, end);
        if (timesThings && tile != null && cover != null && side >= 0 && side < 6) {
            Node[] sides = covers.get(tile);
            if (sides == null) {
                sides = new Node[6];
                covers.put(tile, sides);
            }
            Node node = sides[side];
            if (node == null || node.key != cover) {
                // A new cover, or the one on this side was swapped for another.
                node = new Node(Node.INSTANCE, Category.COVERS, cover);
                node.live = tile;
                node.coverSide = side;
                sides[side] = node;
                pending.add(node);
            }
            node.add(nanos, 1, this);
            hits[Category.COVERS.ordinal()]++;
        }
        leave(start, end, outer);
    }

    public void recordPipe(int part, Object key, Object live, long start, long outer) {
        if (closed) {
            return;
        }
        long end = System.nanoTime();
        long nanos = ownTime(start, end);
        if (timesThings && key != null) {
            Object network = pipeNetwork(part, key);
            if (network != null) {
                addToNetwork(network, part >= PIPE_EIO_NETWORK ? PIPE_EIO_NETWORK : PIPE_GT_TICK, nanos);
            }
        }
        pass(end, outer);
    }

    private void addToNetwork(Object network, int family, long nanos) {
        Node node = pipeNetworks.get(network);
        if (node == null) {
            node = new Node(Node.INSTANCE, Category.PIPE_NETWORKS, network);
            node.live = network;
            node.pipePart = family;
            pipeNetworks.put(network, node);
            pending.add(node);
        }
        node.add(nanos, 1, this);
        hits[Category.PIPE_NETWORKS.ordinal()]++;
    }

    public void recordPipeSide(TileEntity pipe, int side, long start, long outer) {
        recordPipe(PIPE_GT_ENERGY, pipe, null, start, outer);
    }

    private Object pipeNetwork(int part, Object key) {
        try {
            switch (part) {
                case PIPE_EIO_NETWORK:
                    return key;
                case PIPE_EIO_CONDUIT:
                    return EnderIoConduits.networkOf(key);
                default:
                    return key instanceof TileEntity ? GregTechPipes.networkOf((TileEntity) key, gtNetworks) : null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private void pass(long end, long outer) {
        long now = System.nanoTime();
        nestedNanos = outer + nestedNanos + (now - end);
        periodOverhead += now - end + 2 * timerNanos;
    }

    /** Time the thing (a tile entity, an entity, a listener, a world) took just now. */
    public void record(Category category, Object key, long nanos) {
        if (!timesThings || closed) {
            return;
        }
        IdentityHashMap<Object, Node> map = leaves[category.ordinal()];
        Node node = map.get(key);
        if (node == null) {
            node = new Node(category.hasInstances ? Node.INSTANCE : Node.TYPE, category, key);
            map.put(key, node);
            pending.add(node);
        }
        node.add(nanos, 1, this);
        hits[category.ordinal()]++;
    }

    void recordComputer(Object host, long runNanos, long waitNanos, long syncNanos) {
        if (!timesThings || !measuring || closed) {
            return;
        }
        if (runNanos > 0) {
            record(Category.OC_THREAD, host, runNanos);
        }
        Node node = leaves[Category.OC_THREAD.ordinal()].get(host);
        if (node == null) {
            return;
        }
        long[] stats = computerStats.get(node);
        if (stats == null) {
            stats = new long[5];
            computerStats.put(node, stats);
        }
        if (runNanos > 0) {
            stats[0]++;
            stats[1] += waitNanos;
            stats[2] = Math.max(stats[2], waitNanos);
        }
        if (syncNanos > 0) {
            stats[3]++;
            stats[4] += syncNanos;
        }
    }

    private void labelComputers() {
        for (Map.Entry<Node, long[]> entry : computerStats.entrySet()) {
            long[] stats = entry.getValue();
            StringBuilder label = new StringBuilder();
            if (stats[0] > 0) {
                label.append(
                    String.format(
                        Locale.ROOT,
                        "queue %.2f ms avg, %.1f ms max",
                        stats[1] / (double) stats[0] / 1e6,
                        stats[2] / 1e6));
            }
            if (stats[3] > 0) {
                if (label.length() > 0) {
                    label.append(" · ");
                }
                label.append(
                    String
                        .format(Locale.ROOT, "%d sync calls, %.1f ms on the server thread", stats[3], stats[4] / 1e6));
            }
            entry.getKey().label = label.length() > 0 ? label.toString() : null;
        }
    }

    /** A block's tick {@link #enter entered} at {@code start} ended: its own time is counted per block and chunk. */
    public void recordBlockTick(World world, Block block, int x, int y, int z, long start, long outer) {
        if (closed) {
            return;
        }
        long end = System.nanoTime();
        recordBlockTick(world, block, x, y, z, ownTime(start, end));
        leave(start, end, outer);
    }

    /**
     * Time a block's tick took, counted per block and chunk; the first block that ticked in the chunk is where it is
     * shown and teleported to, so its place agrees with the chunk named.
     */
    public void recordBlockTick(World world, Block block, int x, int y, int z, long nanos) {
        recordAt(blockTicks, Category.BLOCK_TICKS, world, block, x, y, z, nanos);
    }

    public void recordNeighborUpdate(World world, Block block, int x, int y, int z, long start, long outer) {
        if (closed) {
            return;
        }
        long end = System.nanoTime();
        recordAt(neighborsAt, Category.NEIGHBOR_UPDATES, world, block, x, y, z, ownTime(start, end));
        leave(start, end, outer);
    }

    public void recordTask(World world, WorldTask task, long start, long outer) {
        if (closed) {
            return;
        }
        record(task.category, worldTask(world, task), start, outer);
    }

    private WorldTask.Key worldTask(World world, WorldTask task) {
        int dim = world.provider.dimensionId;
        long id = (long) dim << 8 | task.ordinal();
        WorldTask.Key key = worldTaskKeys.get(id);
        if (key == null) {
            key = new WorldTask.Key(dim, task, world);
            worldTaskKeys.put(id, key);
        }
        return key;
    }

    public void spanBegin(int span) {
        if (closed) {
            return;
        }
        if (spanDepth[span]++ == 0) {
            spanOuter[span] = enter();
            spanStart[span] = System.nanoTime();
        }
    }

    private boolean spanEnds(int span) {
        if (spanDepth[span] <= 0) {
            return false;
        }
        return --spanDepth[span] == 0 && !closed;
    }

    public void spanEndLight(World world, Object type, int x, int y, int z) {
        if (!spanEnds(SPAN_LIGHT)) {
            return;
        }
        long start = spanStart[SPAN_LIGHT];
        long end = System.nanoTime();
        recordAt(lightAt, Category.LIGHTING, world, type, x, y, z, ownTime(start, end));
        leave(start, end, spanOuter[SPAN_LIGHT]);
    }

    public void spanEndPath(Object entity) {
        if (!spanEnds(SPAN_PATH)) {
            return;
        }
        if (entity == null) {
            long start = spanStart[SPAN_PATH];
            leave(start, System.nanoTime(), spanOuter[SPAN_PATH]);
            return;
        }
        record(Category.PATHFINDING, entity, spanStart[SPAN_PATH], spanOuter[SPAN_PATH]);
    }

    private void resetSpans() {
        spanDepth[SPAN_LIGHT] = 0;
        spanDepth[SPAN_PATH] = 0;
    }

    private void recordAt(Map<World, IdentityHashMap<Object, HashMap<Long, Node>>> places, Category category,
        World world, Object key, int x, int y, int z, long nanos) {
        if (!timesThings || world == null || key == null) {
            return;
        }
        IdentityHashMap<Object, HashMap<Long, Node>> byKey = places.get(world);
        if (byKey == null) {
            byKey = new IdentityHashMap<>();
            places.put(world, byKey);
        }
        HashMap<Long, Node> byChunk = byKey.get(key);
        if (byChunk == null) {
            byChunk = new HashMap<>();
            byKey.put(key, byChunk);
        }
        long chunk = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
        Node node = byChunk.get(chunk);
        if (node == null) {
            node = new Node(Node.INSTANCE, category, key);
            node.live = world;
            node.kind = LocationKind.CHUNK;
            node.x = x;
            node.y = y;
            node.z = z;
            byChunk.put(chunk, node);
            pending.add(node);
        }
        node.add(nanos, 1, this);
        hits[category.ordinal()]++;
    }

    /**
     * Time a world's tick took, all it did (its things included), by dimension. With {@code nestedBefore} (what was
     * measured outside any thing when the world started, -1 for unknown), the world counts as one thing measured,
     * the things in it no more: what is left of the tick is the server's own work.
     */
    public void recordWorld(World world, long nanos, long nestedBefore) {
        if (closed || !measuring) {
            return;
        }
        if (timesThings) {
            int dim = world.provider.dimensionId;
            Node node = worlds.get(dim);
            if (node == null) {
                node = new Node(Node.TYPE, Category.WORLDS, world);
                worlds.put(dim, node);
                pending.add(node);
            } else if (node.key == null) {
                // Loaded again after it was let go.
                node.key = world;
            }
            node.add(nanos, 1, this);
            hits[Category.WORLDS.ordinal()]++;
        }
        if (nestedBefore >= 0) {
            nestedNanos = nestedBefore + nanos;
        }
    }

    /** What was measured in the period so far outside any thing being measured (ns). */
    public long nested() {
        return nestedNanos;
    }

    /**
     * The tick that took {@code nanos} is ending: the part of it nothing measured (the server's own work no hook
     * timed, outside the worlds and the events) is counted as the rest of the tick.
     */
    public void recordRest(long nanos) {
        if (!measuring) {
            // Nothing was measured in this tick: all of it would look like the server's own work.
            return;
        }
        long rest = nanos - periodOverhead - nestedNanos;
        if (rest > 0) {
            record(Category.SERVER, ServerPart.REST, rest);
        }
    }

    /** Something the report should say was not measured as it should be (a translation key). */
    public void warn(String key) {
        if (!warnings.contains(key)) {
            warnings.add(key);
        }
    }

    void touched(Node node) {
        touched[node.depth].add(node);
    }

    /**
     * A tick (or frame) that started at {@code start} and took {@code nanos} ended: everything measured since the last
     * one is added up.
     */
    public void endPeriod(long start, long nanos) {
        if (sampler != null) {
            sampler.setActive(false);
        }
        if (mode == ScanMode.TPS) {
            ComputerWork.drain(this);
        }
        resolvePending();
        // In the scan's time: the pauses before it left out.
        start -= pausedNanos;
        lastEnd = start + nanos;
        // Ticks start on the server's schedule whatever each one takes, so the second counted by their starts doesn't
        // swing with their lengths; they are counted as they came, the scan's cost in them included (the server sleeps
        // it away while it keeps up). Frames follow each other: theirs is counted without the scan's own cost.
        trackSecond(mode == ScanMode.TPS ? start : start - totalOverhead);
        // The period as it would have been without the scan.
        long overhead = Math.max(0, Math.min(periodOverhead, nanos - nanos / 50));
        periodOverhead = 0;
        totalOverhead += overhead;
        nanos -= overhead;
        int bucket = bucketOf(start);
        resetSpans();
        if (gc.poll()) {
            gcMillis[bucket] += gc.lastMillis;
            gcCounts[bucket] += (int) Math.min(Integer.MAX_VALUE, gc.lastCount);
        }
        waitNanos[bucket] += Math.min(periodWait, nanos);
        periodWait = 0;
        periods[bucket]++;
        if (focus == ScanFocus.STEADY) {
            steadyTickHistogram[SteadyHistogram.bin(nanos)]++;
        }
        periodNanos[bucket] += nanos;
        periodMax[bucket] = Math.max(periodMax[bucket], nanos);
        totalPeriods++;
        // In a light scan a measured period stands for the ones left unmeasured until the next.
        int weight = measuring ? sampleEvery : 1;
        float seconds = (start - startNanos) / 1e9f;
        for (int depth = Node.INSTANCE; depth >= Node.CATEGORY; depth--) {
            List<Node> list = touched[depth];
            // Parents are touched one level up, which is handled after this one.
            for (int i = 0; i < list.size(); i++) {
                Node node = list.get(i);
                // This mod's own handlers are left out of everything above them, as they are of the report.
                if (node.parent != null && !(depth >= Node.TYPE && LagFinder.NAME.equals(node.mod))) {
                    node.parent.add(node.periodNanos, node.periodCalls, this);
                    node.parent.addGtActivity(
                        node.periodGtActive,
                        node.periodGtIdle,
                        node.periodGtActiveCalls,
                        node.periodGtIdleCalls);
                }
                node.finishPeriod(
                    bucket,
                    BUCKETS,
                    seconds,
                    depth == Node.CATEGORY ? Long.MAX_VALUE : spikeNanos,
                    focus == ScanFocus.STEADY,
                    weight);
            }
            list.clear();
        }
        sweep();
    }

    /**
     * What was measured since the last period is let go: it was no part of a tick (LagFinder's own tasks before the
     * tick, the worlds saved as a single player game pauses).
     */
    public void dropPeriod() {
        for (List<Node> list : touched) {
            for (Node node : list) {
                node.periodNanos = 0;
                node.periodCalls = 0;
                node.periodGtActive = 0;
                node.periodGtIdle = 0;
                node.periodGtActiveCalls = 0;
                node.periodGtIdleCalls = 0;
                node.touched = false;
            }
            list.clear();
        }
        periodOverhead = 0;
        nestedNanos = 0;
        resetSpans();
    }

    /** The frame waited this long instead of working: the frame limiter's sleep, or the wait for vsync. */
    public void addWait(long nanos) {
        periodWait += Math.max(0, nanos);
    }

    /** The frame rate the frames are held to (the limit or vsync's), 0 for none. */
    public void setFrameCap(float fps) {
        frameCap = Math.max(0, fps);
    }

    /** The ticks were marked by the tick events (the mixin around MinecraftServer.tick() didn't apply). */
    public void tickedByEvents() {
        tickedByEvents = true;
    }

    /**
     * Every few seconds: lets go of the things gone from the world (dead entities, unloaded tile entities, unloaded
     * worlds), keeping what was measured of them and where they were last, and notes the names of the dimensions
     * loaded, for those unloaded before the end.
     */
    private void sweep() {
        long now = System.nanoTime();
        if (now < nextSweep) {
            return;
        }
        nextSweep = now + SWEEP_NANOS;
        try {
            knownDims.putAll(describer.dimensionNames());
            for (IdentityHashMap<Object, Node> map : leaves) {
                for (Iterator<Map.Entry<Object, Node>> it = map.entrySet()
                    .iterator(); it.hasNext();) {
                    Map.Entry<Object, Node> entry = it.next();
                    Node node = entry.getValue();
                    if (node.resolved && describer.isGone(entry.getKey())) {
                        letGo(node);
                        it.remove();
                    }
                }
            }
            // Unloaded worlds: their line keeps what was measured, without the world.
            for (Node node : worlds.values()) {
                if (node.key != null && node.resolved && describer.isGone(node.key)) {
                    letGo(node);
                }
            }
            // AE2 networks gone (their last node removed: torn down, or their chunks unloaded).
            for (Iterator<Map.Entry<Object, IdentityHashMap<Object, Node>>> it = networks.entrySet()
                .iterator(); it.hasNext();) {
                Map.Entry<Object, IdentityHashMap<Object, Node>> entry = it.next();
                if (isEmptyNetwork(entry.getKey())) {
                    for (Node node : entry.getValue()
                        .values()) {
                        if (node.resolved) {
                            letGo(node);
                        }
                    }
                    it.remove();
                }
            }
            // Covers of unloaded or broken blocks: their lines keep what was measured and where.
            for (Iterator<Map.Entry<TileEntity, Node[]>> it = covers.entrySet()
                .iterator(); it.hasNext();) {
                Map.Entry<TileEntity, Node[]> entry = it.next();
                if (describer.isGone(entry.getKey())) {
                    for (Node node : entry.getValue()) {
                        if (node != null && node.resolved) {
                            node.key = null;
                            node.live = null;
                        }
                    }
                    it.remove();
                }
            }
            for (Iterator<Map.Entry<TileEntity, GregTechPipes.Network>> it = gtNetworks.entrySet()
                .iterator(); it.hasNext();) {
                if (describer.isGone(
                    it.next()
                        .getKey())) {
                    it.remove();
                }
            }
            // Unloaded worlds: their block ticks and tile entities let go of them.
            for (Map<World, IdentityHashMap<Object, HashMap<Long, Node>>> places : Arrays
                .asList(blockTicks, lightAt, neighborsAt)) {
                for (Iterator<Map.Entry<World, IdentityHashMap<Object, HashMap<Long, Node>>>> it = places.entrySet()
                    .iterator(); it.hasNext();) {
                    Map.Entry<World, IdentityHashMap<Object, HashMap<Long, Node>>> entry = it.next();
                    if (describer.isGone(entry.getKey())) {
                        for (HashMap<Long, Node> byChunk : entry.getValue()
                            .values()) {
                            for (Node node : byChunk.values()) {
                                node.live = null;
                            }
                        }
                        it.remove();
                    }
                }
            }
            for (Iterator<Map.Entry<World, HashMap<Long, Node>>> it = tilesAt.entrySet()
                .iterator(); it.hasNext();) {
                Map.Entry<World, HashMap<Long, Node>> entry = it.next();
                if (describer.isGone(entry.getKey())) {
                    for (Node node : entry.getValue()
                        .values()) {
                        if (node.key != null) {
                            letGo(node);
                        }
                    }
                    it.remove();
                }
            }
        } catch (Throwable t) {
            LagFinder.LOG.warn("LagFinder could not let go of what is gone", t);
        }
    }

    /** Whether the scan should have measured some of the category; a check that fails warns of nothing. */
    private boolean expects(Category category) {
        try {
            return describer.expects(category);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether an AE2 network has no node left ({@code IGrid.isEmpty()}, read by reflection: AE2 may not be there). */
    private static boolean isEmptyNetwork(Object network) {
        try {
            return (Boolean) network.getClass()
                .getMethod("isEmpty")
                .invoke(network);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Keeps what is known of the thing, and lets go of the thing itself. */
    private void letGo(Node node) {
        describer.updateLocation(node);
        if (node.typeKey == node.key) {
            node.typeKey = node.keyClass;
        }
        node.key = null;
        node.live = null;
    }

    /**
     * Counts the ticks (or frames) of every second of the scan, at the start of each one: how many started in the last
     * second of the game's time (the scan's own time left out), the worst kept. A hitch of a few hundred milliseconds
     * costs the second it is in, as on the server: the moments of the graph are shorter than a second on short scans
     * and would show such a hitch as a far lower rate.
     */
    private void trackSecond(long end) {
        if (endCount == ends.length) {
            long[] grown = new long[ends.length * 2];
            for (int i = 0; i < endCount; i++) {
                grown[i] = ends[(endFirst + i) % ends.length];
            }
            ends = grown;
            endFirst = 0;
        }
        ends[(endFirst + endCount) % ends.length] = end;
        endCount++;
        // The window starts at the latest end that still leaves a whole second behind this one.
        while (endCount >= 3 && end - ends[(endFirst + 1) % ends.length] >= SECOND_NANOS) {
            endFirst = (endFirst + 1) % ends.length;
            endCount--;
        }
        long span = end - ends[endFirst];
        if (endCount >= 2 && span >= SECOND_NANOS) {
            // Exactly one second back: the ticks wholly in it, and the part of the oldest one that is.
            long second = ends[(endFirst + 1) % ends.length];
            long oldest = second - ends[endFirst];
            float inSecond = (endCount - 2) + (oldest <= 0 ? 0 : (float) (SECOND_NANOS - (end - second)) / oldest);
            float rate = inSecond;
            if (mode == ScanMode.TPS) {
                rate = Math.min(20f, rate);
            }
            if (lowestSecond < 0 || rate < lowestSecond) {
                lowestSecond = rate;
                lowestSecondBucket = bucketOf(mode == ScanMode.TPS ? end : end + totalOverhead);
            }
        }
    }

    /** What the scan itself took so far in the period being measured (ns). */
    public long periodOverhead() {
        return periodOverhead;
    }

    /** Time the scan itself took between two periods (adding the last one up), left out of the scan's length. */
    public void addOverhead(long nanos) {
        totalOverhead += Math.max(0, nanos);
    }

    private int bucketOf(long time) {
        return (int) Math.max(0, Math.min(BUCKETS - 1, (time - startNanos) / bucketNanos));
    }

    /** Finds out what new leaves are and puts them in the tree. */
    private void resolvePending() {
        // Describing a thing can run mod code that is measured too and adds new leaves meanwhile: they wait for the
        // next round (or the next period), never into the list being walked.
        for (int round = 0; round < 4 && !pending.isEmpty(); round++) {
            List<Node> batch = new ArrayList<>(pending);
            pending.clear();
            resolve(batch);
        }
    }

    private void resolve(List<Node> batch) {
        for (Node leaf : batch) {
            try {
                describer.describe(leaf);
            } catch (Throwable t) {
                // A mod's object that can't say what it is: still counted, by its class.
                Describer.fallback(leaf);
            }
            if (leaf.mod == null) {
                leaf.mod = Describer.UNKNOWN_MOD;
            }
            if (leaf.typeKey == null) {
                leaf.typeKey = leaf.keyClass;
            }
            if (leaf.name == null) {
                leaf.name = leaf.keyClass.getSimpleName();
            }
            leaf.resolved = true;
            Node modNode = roots[leaf.category.ordinal()].child(leaf.mod);
            modNode.name = leaf.mod;
            if (leaf.depth == Node.TYPE) {
                if (modNode.children == null) {
                    modNode.children = new LinkedHashMap<>();
                }
                modNode.children.put(leaf, leaf);
                leaf.parent = modNode;
                continue;
            }
            Node type = modNode.child(leaf.typeKey);
            if (type.name == null) {
                type.name = leaf.name;
                type.detail = leaf.detail;
                type.translationKey = leaf.translationKey;
                type.itemId = leaf.itemId;
                type.itemDamage = leaf.itemDamage;
                type.mod = leaf.mod;
                type.kind = leaf.kind;
            }
            type.instances++;
            if (type.children == null) {
                type.children = new LinkedHashMap<>();
            }
            type.children.put(leaf, leaf);
            leaf.parent = type;
        }
    }

    /**
     * Adds what only comes in after the scan to its report: the memory, once a count of the objects still under way
     * ended, and the allocations the Flight Recorder still holds, which it hands over within a couple of seconds. Waits
     * for them, so call it off the game's thread, before the report is
     * written; nothing to do for other scans.
     */
    public void completeReport(Report report) {
        HeapWatcher heapWatcher = finishingHeap;
        finishingHeap = null;
        if (heapWatcher != null) {
            // Waits for a count of the objects still under way.
            heapWatcher.stop();
            try {
                report.memory = heapWatcher.build();
                report.memory.trim(report.buckets());
            } catch (Throwable t) {
                LagFinder.LOG.error("Could not build the memory of the scan", t);
            }
        }
        AllocationWatcher watcher = finishingAllocations;
        finishingAllocations = null;
        if (watcher == null) {
            return;
        }
        watcher.stop();
        try {
            report.allocations = watcher.build();
            report.allocations.trim(report.buckets());
        } catch (Throwable t) {
            LagFinder.LOG.error("Could not build the allocations of the scan", t);
        }
    }

    /**
     * The part of making the report that needs the game, on the scan's thread right after its last period: stops the
     * watching, finds out what the last things measured are, reads where the things that move are now, and notes the
     * dimensions' names and what was not measured. Quick; {@link #buildReport} does the rest on any thread.
     */
    public void prepareReport() {
        if (prepared) {
            return;
        }
        prepared = true;
        // The allocations are finished by completeReport, off the game's thread: only their end is marked here.
        finishingAllocations = allocations;
        allocations = null;
        if (finishingAllocations != null) {
            finishingAllocations.markEnd();
        }
        close();
        if (endCount > 0) {
            // The end of the last tick (or frame) counts as the start of the next one would: a freeze the scan ended
            // on is in its worst second too.
            trackSecond(mode == ScanMode.TPS ? lastEnd : lastEnd - totalOverhead);
        }
        resolvePending();
        for (IdentityHashMap<Object, Node> map : leaves) {
            for (Node node : map.values()) {
                describer.updateLocation(node);
            }
        }
        labelComputers();
        try {
            knownDims.putAll(describer.dimensionNames());
        } catch (Throwable t) {
            LagFinder.LOG.warn("LagFinder could not name the dimensions", t);
        }
        if (timesThings) {
            for (Category category : Category.of(mode)) {
                if (hits[category.ordinal()] == 0 && expects(category)) {
                    // There were such things, and none was measured: its hook wasn't put in (another mod changed it).
                    warnings.add("lagfinder.warning.unhooked." + category.id);
                    LagFinder.LOG.warn(
                        "LagFinder measured no {} although there were some: its hook may not have been put in",
                        category.id);
                }
            }
            if (tickedByEvents) {
                warnings.add("lagfinder.warning.tick_events");
            }
        }
        // The heap's watcher may still be counting the objects: completeReport waits for it, off the game's thread.
        finishingHeap = heap;
        heap = null;
    }

    /**
     * Builds the report from what was measured: on any thread once {@link #prepareReport} ran on the scan's (it runs it
     * itself if not), as sorting and adding up a big scan takes a moment the game shouldn't wait for.
     */
    public Report buildReport(String source) {
        prepareReport();
        long end = Math.max(lastEnd, startNanos + 1);
        Report report = ReportBuilder
            .build(this, roots, periods, periodNanos, periodMax, totalPeriods, end - startNanos, source, knownDims);
        report.focus = focus;
        if (focus == ScanFocus.STEADY) {
            report.steadyMedianTickMs = SteadyHistogram.percentile(steadyTickHistogram, totalPeriods, 0.5f);
            report.steadyP90TickMs = SteadyHistogram.percentile(steadyTickHistogram, totalPeriods, 0.9f);
        }
        report.spikeMs = spikeMs;
        report.computerThreads = ComputerWork.threads();
        report.overheadSec = (float) Math.min(totalOverhead / 1e9, report.durationSec * 0.98);
        report.lowestSecond = lowestSecond;
        report.frameCap = frameCap;
        report.waitMs = new float[report.buckets()];
        for (int i = 0; i < report.waitMs.length; i++) {
            report.waitMs[i] = report.counts[i] == 0 ? 0 : (float) (waitNanos[i] / report.counts[i] / 1e6);
        }
        report.gcMs = new float[report.buckets()];
        report.gcCount = new int[report.buckets()];
        for (int i = 0; i < report.gcMs.length; i++) {
            report.gcMs[i] = (float) gcMillis[i];
            report.gcCount[i] = gcCounts[i];
        }
        if (lowestSecond >= 0 && lowestSecondBucket >= 0 && report.rate.length > 0) {
            // The graph dips where the worst second was, as low: its lowest point and the lowest rate agree.
            int bucket = Math.min(lowestSecondBucket, report.rate.length - 1);
            report.rate[bucket] = Math.min(report.rate[bucket], lowestSecond);
        }
        report.warnings.addAll(warnings);
        if (!timesThings) {
            // Nothing was timed one by one: no lag tabs, only the ticks or frames for the graphs.
            report.categories.clear();
        }
        if (sampler != null) {
            try {
                report.profile = sampler.build();
            } catch (Throwable t) {
                // The report is still worth having without its call tree.
                LagFinder.LOG.error("Could not build the call tree", t);
            }
        }
        return report;
    }
}
