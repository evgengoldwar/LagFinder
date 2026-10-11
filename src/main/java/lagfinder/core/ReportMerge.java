package lagfinder.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the reports of a scan of both sides up into one: the server's thread and the client's in one call tree, their
 * hot methods and mods together, the objects of both heaps, and what both threads created. In single player both run
 * in the same Java, so what is of the whole Java there (the heap, all threads' allocations) is counted once; what is
 * of the scanned threads is added up.
 */
public final class ReportMerge {

    private ReportMerge() {}

    /** The server's report with the client's added to it; {@code sameJvm} when both ran in the same Java. */
    public static Report merge(Report server, Report client, boolean sameJvm) {
        Report merged = server;
        merged.bothSides = true;
        float serverSec = server.durationSec;
        merged.durationSec = Math.max(serverSec, client.durationSec);
        // The rate is the server's: its ticks over its own length, not over a longer client's.
        merged.overheadSec += merged.durationSec - serverSec;
        merged.profile = merge(server.profile, client.profile);
        merged.memory = merge(server.memory, client.memory, sameJvm);
        merged.allocations = merge(server.allocations, client.allocations, sameJvm);
        for (String warning : client.warnings) {
            if (!merged.warnings.contains(warning)) {
                merged.warnings.add(warning);
            }
        }
        return merged;
    }

    /** Where each of the names is in {@code into}, added to it when it isn't there yet. */
    private static int[] mods(List<String> names, List<String> into) {
        int[] map = new int[names.size()];
        for (int i = 0; i < map.length; i++) {
            int at = into.indexOf(names.get(i));
            if (at < 0) {
                at = into.size();
                into.add(names.get(i));
            }
            map[i] = at;
        }
        return map;
    }

    private static int map(int[] map, int index) {
        return index >= 0 && index < map.length ? map[index] : index;
    }

    // ---------------------------------------------------------------- Methods

    static Profile merge(Profile a, Profile b) {
        if (a == null || b == null) {
            return a != null ? a : b;
        }
        Profile out = new Profile();
        out.thread = a.thread + " + " + b.thread;
        out.intervalMs = Math.max(a.intervalMs, b.intervalMs);
        out.samples = a.samples + b.samples;
        int[] mapA = mods(a.mods, out.mods), mapB = mods(b.mods, out.mods);
        out.modMs = new double[out.mods.size()];
        for (int i = 0; i < mapA.length && i < a.modMs.length; i++) {
            out.modMs[mapA[i]] += a.modMs[i];
        }
        for (int i = 0; i < mapB.length && i < b.modMs.length; i++) {
            out.modMs[mapB[i]] += b.modMs[i];
        }
        if (a.root != null || b.root != null) {
            CallNode root = new CallNode();
            root.method = out.thread;
            root.mod = a.root != null ? map(mapA, a.root.mod) : map(mapB, b.root.mod);
            if (a.root != null) {
                addTree(root, a.root, mapA);
            }
            if (b.root != null) {
                addTree(root, b.root, mapB);
            }
            sortTree(root);
            out.root = root;
        }
        Map<String, CallNode> methods = new HashMap<>();
        addMethods(methods, a.methods, mapA);
        addMethods(methods, b.methods, mapB);
        List<CallNode> hot = new ArrayList<>(methods.values());
        hot.sort((x, y) -> Double.compare(y.selfMs, x.selfMs));
        out.methods.addAll(hot.subList(0, Math.min(Profile.HOT_METHODS, hot.size())));
        return out;
    }

    /** Adds the source's times to the target, and its calls to the target's calls of the same methods. */
    private static void addTree(CallNode target, CallNode source, int[] map) {
        addTimes(target, source, map);
        if (source.children.isEmpty()) {
            return;
        }
        Map<String, CallNode> calls = new HashMap<>();
        for (CallNode child : target.children) {
            calls.put(child.fullName(), child);
        }
        for (CallNode child : source.children) {
            CallNode into = calls.get(child.fullName());
            if (into == null) {
                into = copy(child, map);
                into.parent = target;
                into.depth = target.depth + 1;
                target.children.add(into);
                calls.put(into.fullName(), into);
            }
            addTree(into, child, map);
        }
    }

    private static void sortTree(CallNode node) {
        node.children.sort((x, y) -> Double.compare(y.totalMs, x.totalMs));
        for (CallNode child : node.children) {
            sortTree(child);
        }
    }

    private static void addMethods(Map<String, CallNode> into, List<CallNode> methods, int[] map) {
        for (CallNode method : methods) {
            CallNode sum = into.get(method.fullName());
            if (sum == null) {
                sum = copy(method, map);
                into.put(sum.fullName(), sum);
            }
            addTimes(sum, method, map);
        }
    }

    /** A new node for the same method, with no time yet. */
    private static CallNode copy(CallNode node, int[] map) {
        CallNode copy = new CallNode();
        copy.className = node.className;
        copy.method = node.method;
        copy.mod = map(map, node.mod);
        return copy;
    }

    private static void addTimes(CallNode target, CallNode source, int[] map) {
        target.totalMs += source.totalMs;
        target.selfMs += source.selfMs;
        target.samples += source.samples;
        int[] ids = target.mods;
        float[] total = target.modTotalMs, self = target.modSelfMs;
        for (int i = 0; i < source.mods.length; i++) {
            int id = map(map, source.mods[i]);
            int at = 0;
            while (at < ids.length && ids[at] != id) {
                at++;
            }
            if (at == ids.length) {
                ids = Arrays.copyOf(ids, at + 1);
                total = Arrays.copyOf(total, at + 1);
                self = Arrays.copyOf(self, at + 1);
                ids[at] = id;
            }
            total[at] += source.modTotalMs[i];
            self[at] += source.modSelfMs[i];
        }
        target.setMods(ids, total, self);
    }

    // ---------------------------------------------------------------- Objects

    static HeapProfile merge(HeapProfile a, HeapProfile b, boolean sameJvm) {
        if (a == null || b == null) {
            return a != null ? a : b;
        }
        if (sameJvm) {
            // One heap, counted by both: only the allocation of the scanned threads adds up.
            a.threadAllocMb = sum(a.threadAllocMb, b.threadAllocMb);
            return a;
        }
        HeapProfile out = new HeapProfile();
        out.maxHeap = a.maxHeap > 0 && b.maxHeap > 0 ? a.maxHeap + b.maxHeap : 0;
        out.liveOnly = a.liveOnly && b.liveOnly;
        out.allocKnown = a.allocKnown && b.allocKnown;
        out.heapLowMb = sum(a.heapLowMb, b.heapLowMb);
        out.heapHighMb = sum(a.heapHighMb, b.heapHighMb);
        out.heapMb = sum(a.heapMb, b.heapMb);
        out.allocMb = sum(a.allocMb, b.allocMb);
        out.threadAllocMb = sum(a.threadAllocMb, b.threadAllocMb);
        out.gcMs = sum(a.gcMs, b.gcMs);
        out.gcCount = new int[Math.max(a.gcCount.length, b.gcCount.length)];
        for (int i = 0; i < out.gcCount.length; i++) {
            out.gcCount[i] = (i < a.gcCount.length ? a.gcCount[i] : 0) + (i < b.gcCount.length ? b.gcCount[i] : 0);
        }
        out.histogramError = a.histogramError != null ? a.histogramError : b.histogramError;
        // The counts of the objects are added count by count; a side whose objects couldn't be counted adds none.
        int n = a.snapshots() == 0 ? b.snapshots()
            : b.snapshots() == 0 ? a.snapshots() : Math.min(a.snapshots(), b.snapshots());
        HeapProfile times = a.snapshots() > 0 ? a : b;
        out.snapshotSec = Arrays.copyOf(times.snapshotSec, n);
        out.snapshotPauseMs = new float[n];
        out.totalInstances = new long[n];
        out.totalBytes = new long[n];
        out.classCount = new int[n];
        for (HeapProfile side : Arrays.asList(a, b)) {
            for (int i = 0; i < n && i < side.snapshots(); i++) {
                out.snapshotPauseMs[i] = Math.max(out.snapshotPauseMs[i], side.snapshotPauseMs[i]);
                out.totalInstances[i] += side.totalInstances[i];
                out.totalBytes[i] += side.totalBytes[i];
                out.classCount[i] = Math.max(out.classCount[i], side.classCount[i]);
            }
        }
        Map<String, HeapProfile.HeapClass> classes = new HashMap<>();
        for (HeapProfile side : Arrays.asList(a, b)) {
            int[] map = mods(side.mods, out.mods);
            while (out.modInstances.size() < out.mods.size()) {
                out.modInstances.add(new long[n]);
                out.modBytes.add(new long[n]);
            }
            for (int m = 0; m < map.length; m++) {
                add(out.modInstances.get(map[m]), side.modInstances.get(m));
                add(out.modBytes.get(map[m]), side.modBytes.get(m));
            }
            for (HeapProfile.HeapClass type : side.classes) {
                HeapProfile.HeapClass sum = classes.get(type.name);
                if (sum == null) {
                    sum = new HeapProfile.HeapClass();
                    sum.name = type.name;
                    sum.mod = map(map, type.mod);
                    sum.instances = new long[n];
                    sum.bytes = new long[n];
                    classes.put(sum.name, sum);
                    out.classes.add(sum);
                }
                add(sum.instances, type.instances);
                add(sum.bytes, type.bytes);
            }
        }
        out.classes.sort(
            Comparator.comparingLong(HeapProfile.HeapClass::peakBytes)
                .reversed());
        return out;
    }

    private static void add(long[] into, long[] values) {
        for (int i = 0; i < into.length && i < values.length; i++) {
            into[i] += values[i];
        }
    }

    private static float[] sum(float[] a, float[] b) {
        float[] out = new float[Math.max(a.length, b.length)];
        for (int i = 0; i < out.length; i++) {
            out[i] = (i < a.length ? a[i] : 0) + (i < b.length ? b[i] : 0);
        }
        return out;
    }

    // ---------------------------------------------------------------- Allocations

    /** Of the whole Java: counted once in single player, where both sides saw the same. */
    private static long whole(long a, long b, boolean sameJvm) {
        return sameJvm ? Math.max(a, b) : a + b;
    }

    private static float[] whole(float[] a, float[] b, boolean sameJvm) {
        if (a == null || b == null) {
            return a != null ? a.clone() : b != null ? b.clone() : null;
        }
        float[] out = new float[Math.max(a.length, b.length)];
        for (int i = 0; i < out.length; i++) {
            float x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            out[i] = sameJvm ? Math.max(x, y) : x + y;
        }
        return out;
    }

    static AllocationProfile merge(AllocationProfile a, AllocationProfile b, boolean sameJvm) {
        if (a == null || b == null) {
            return a != null ? a : b;
        }
        AllocationProfile out = new AllocationProfile();
        out.source = a.source == AllocationProfile.SOURCE_JFR || b.source == AllocationProfile.SOURCE_JFR
            ? AllocationProfile.SOURCE_JFR
            : AllocationProfile.SOURCE_STACKS;
        out.thread = a.thread + " + " + b.thread;
        out.recordedFromSec = Math.max(a.recordedFromSec, b.recordedFromSec);
        out.note = a.note != null ? a.note : b.note;
        out.samples = (int) whole(a.samples, b.samples, sameJvm);
        out.targetSamples = a.targetSamples + b.targetSamples;
        out.bytes = whole(a.bytes, b.bytes, sameJvm);
        out.targetBytes = a.targetBytes + b.targetBytes;
        out.allMb = whole(a.allMb, b.allMb, sameJvm);
        out.targetMb = sum(a.targetMb, b.targetMb);
        int[] mapA = mods(a.mods, out.mods), mapB = mods(b.mods, out.mods);
        mergeGroups(out.types, a.types, mapA, b.types, mapB, sameJvm);
        mergeGroups(out.creators, a.creators, mapA, b.creators, mapB, sameJvm);
        Map<String, Integer> types = indexes(out.types), creators = indexes(out.creators);
        Map<String, AllocationProfile.Site> sites = new HashMap<>();
        for (AllocationProfile side : Arrays.asList(a, b)) {
            for (AllocationProfile.Site site : side.sites) {
                String type = side.types.get(site.type).name, creator = side.creators.get(site.creator).name;
                String key = type + '\n' + creator + '\n' + site.method + '\n' + site.line + '\n' + site.via;
                AllocationProfile.Site sum = sites.get(key);
                if (sum == null) {
                    sum = new AllocationProfile.Site();
                    sum.type = types.get(type);
                    sum.creator = creators.get(creator);
                    sum.method = site.method;
                    sum.line = site.line;
                    sum.via = site.via;
                    sites.put(key, sum);
                    out.sites.add(sum);
                }
                sum.bytes = whole(sum.bytes, site.bytes, sameJvm);
                sum.samples = (int) whole(sum.samples, site.samples, sameJvm);
                sum.targetBytes += site.targetBytes;
                sum.targetSamples += site.targetSamples;
            }
        }
        out.sites.sort((x, y) -> Long.compare(y.bytes, x.bytes));
        return out;
    }

    private static void mergeGroups(List<AllocationProfile.Group> into, List<AllocationProfile.Group> a, int[] mapA,
        List<AllocationProfile.Group> b, int[] mapB, boolean sameJvm) {
        Map<String, AllocationProfile.Group> byName = new HashMap<>();
        for (int side = 0; side < 2; side++) {
            int[] map = side == 0 ? mapA : mapB;
            for (AllocationProfile.Group group : side == 0 ? a : b) {
                AllocationProfile.Group sum = byName.get(group.name);
                if (sum == null) {
                    sum = new AllocationProfile.Group();
                    sum.name = group.name;
                    sum.mod = map(map, group.mod);
                    byName.put(sum.name, sum);
                    into.add(sum);
                }
                sum.bytes = whole(sum.bytes, group.bytes, sameJvm);
                sum.samples = (int) whole(sum.samples, group.samples, sameJvm);
                sum.targetBytes += group.targetBytes;
                sum.targetSamples += group.targetSamples;
                sum.timeline = whole(sum.timeline, group.timeline, sameJvm);
                sum.targetTimeline = sum.targetTimeline == null || group.targetTimeline == null
                    ? whole(sum.targetTimeline, group.targetTimeline, false)
                    : sum(sum.targetTimeline, group.targetTimeline);
            }
        }
        into.sort((x, y) -> Long.compare(y.bytes, x.bytes));
    }

    private static Map<String, Integer> indexes(List<AllocationProfile.Group> groups) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            indexes.put(groups.get(i).name, i);
        }
        return indexes;
    }
}
