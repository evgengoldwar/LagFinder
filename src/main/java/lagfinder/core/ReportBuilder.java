package lagfinder.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lagfinder.LagFinder;

/**
 * Turns a session's tree into a report. Every category, mod and type is kept; of the instances, the ones that took
 * the most time in all and the ones with the worst single ticks are kept, and fewer of them with their graphs, so a
 * huge base still gives a report that is quick to send.
 */
final class ReportBuilder {

    /**
     * Instances kept: the most expensive ones in all, and the ones with the worst single periods. Enough for a base
     * with tens of thousands of machines to be looked through one by one; without their graphs they take little.
     */
    private static final int KEEP_BY_TOTAL = 30000, KEEP_BY_PEAK = 3000;
    /** Of those, the ones that keep their graphs. */
    private static final int GRAPH_BY_TOTAL = 1200, GRAPH_BY_PEAK = 400;

    private ReportBuilder() {}

    static Report build(ProfileSession session, Node[] roots, int[] periods, double[] periodNanos, float[] periodMax,
        int totalPeriods, long elapsedNanos, String source, Map<Integer, String> dimensions) {
        Report report = new Report();
        report.mode = session.mode;
        report.startedAt = session.startMillis;
        report.durationSec = elapsedNanos / 1e9f;
        report.source = source;
        report.periods = totalPeriods;
        report.bucketSec = session.bucketNanos / 1e9f;
        report.dimensions.putAll(dimensions);
        int used = (int) Math
            .max(1, Math.min(ProfileSession.BUCKETS, (elapsedNanos + session.bucketNanos - 1) / session.bucketNanos));
        report.rate = new float[used];
        report.avgMs = new float[used];
        report.maxMs = new float[used];
        report.counts = new int[used];
        for (int i = 0; i < used; i++) {
            report.avgMs[i] = periods[i] == 0 ? 0 : (float) (periodNanos[i] / periods[i] / 1e6);
            report.maxMs[i] = periodMax[i] / 1e6f;
            report.counts[i] = periods[i];
        }

        report.rate = Report.ratesOf(session.mode, report.counts, report.avgMs);

        // Which instances are kept, and which with their graphs.
        List<Node> instances = new ArrayList<>();
        for (Node root : roots) {
            collectInstances(root, instances);
        }
        Set<Node> kept = new HashSet<>(), graphed = new HashSet<>();
        instances.sort(
            Comparator.comparingLong((Node node) -> node.totalNanos)
                .reversed());
        for (int i = 0; i < instances.size() && i < KEEP_BY_TOTAL; i++) {
            kept.add(instances.get(i));
            if (i < GRAPH_BY_TOTAL) {
                graphed.add(instances.get(i));
            }
        }
        instances.sort(
            Comparator.comparingLong((Node node) -> node.maxNanos)
                .reversed());
        for (int i = 0; i < instances.size() && i < KEEP_BY_PEAK; i++) {
            kept.add(instances.get(i));
            if (i < GRAPH_BY_PEAK) {
                graphed.add(instances.get(i));
            }
        }

        for (Category category : Category.of(session.mode)) {
            Node root = roots[category.ordinal()];
            if ((category == Category.AE2_NETWORKS || category == Category.SERVER
                || category == Category.COVERS
                || category == Category.PIPE_NETWORKS
                || category == Category.OC_THREAD
                || category == Category.OC_SYNC) && root.children == null) {
                // No AE2, or no network ticked; no server's work measured (an older server's hooks); no GregTech
                // cover worked: no tab for it.
                continue;
            }
            ReportEntry entry = convert(root, used, kept, graphed, totalPeriods);
            entry.name = category.id;
            report.categories.add(entry);
        }
        return report;
    }

    private static void collectInstances(Node node, List<Node> out) {
        if (node.depth == Node.INSTANCE) {
            out.add(node);
        } else if (node.children != null) {
            for (Node child : node.children.values()) {
                collectInstances(child, out);
            }
        }
    }

    private static ReportEntry convert(Node node, int buckets, Set<Node> kept, Set<Node> graphed, int sessionPeriods) {
        ReportEntry entry = new ReportEntry();
        entry.level = node.depth;
        entry.category = node.category;
        entry.name = node.name != null ? node.name : node.keyClass.getSimpleName();
        entry.detail = node.detail;
        entry.label = node.label;
        entry.translationKey = node.translationKey;
        entry.item = node.item;
        entry.kind = node.depth == Node.INSTANCE ? node.kind : LocationKind.NONE;
        entry.dim = node.dim;
        entry.x = node.x;
        entry.y = node.y;
        entry.z = node.z;
        entry.entityId = node.entityId;
        entry.parts = node.parts;
        entry.totalMs = node.totalNanos / 1e6;
        entry.calls = node.calls;
        entry.maxMs = node.maxNanos / 1e6f;
        entry.maxAt = node.maxAt;
        entry.activePeriods = node.activePeriods;
        entry.spikes = node.spikes;
        entry.instances = node.instances;
        entry.medianMs = node.steadyP50(sessionPeriods);
        entry.p90Ms = node.steadyP90(sessionPeriods);
        entry.gtActiveMs = node.gtActiveNanos / 1e6;
        entry.gtIdleMs = node.gtIdleNanos / 1e6;
        entry.gtActiveCalls = node.gtActiveCalls;
        entry.gtIdleCalls = node.gtIdleCalls;
        if (node.hasBuckets() && (node.depth != Node.INSTANCE || graphed.contains(node))) {
            entry.sum = new float[buckets];
            entry.max = new float[buckets];
            node.copyBuckets(entry.sum, entry.max);
            for (int i = 0; i < buckets; i++) {
                entry.sum[i] /= 1e6f;
                entry.max[i] /= 1e6f;
            }
        }
        if (node.children != null) {
            for (Node child : node.children.values()) {
                if (child.totalNanos <= 0 || child.depth == Node.MOD && LagFinder.NAME.equals(child.name)) {
                    // Nothing measured, or this mod's own handlers.
                    continue;
                }
                if (child.depth == Node.INSTANCE && !kept.contains(child)) {
                    continue;
                }
                ReportEntry converted = convert(child, buckets, kept, graphed, sessionPeriods);
                converted.parent = entry;
                entry.children.add(converted);
            }
            entry.children.sort((a, b) -> Double.compare(b.totalMs, a.totalMs));
        }
        return entry;
    }
}
