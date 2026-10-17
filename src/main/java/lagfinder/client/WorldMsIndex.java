package lagfinder.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lagfinder.core.Category;
import lagfinder.core.LocationKind;
import lagfinder.core.Report;
import lagfinder.core.ReportEntry;
import lagfinder.core.ScanMode;

public final class WorldMsIndex {

    public static final class BlockTime {

        public final int dim;
        public final int x;
        public final int y;
        public final int z;
        public double tileAverage;
        public float tilePeak;
        public double aeAverage;
        public float aePeak;
        public int aeNodes;
        public ReportEntry tileEntry;
        public ReportEntry aeEntry;

        private BlockTime(int dim, int x, int y, int z) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public double totalAverage() {
            return tileAverage + aeAverage;
        }

        public String name() {
            ReportEntry entry = tileEntry != null ? tileEntry : aeEntry;
            return entry == null ? "" : Names.of(entry);
        }

        public double distanceSquared(double x, double y, double z) {
            double dx = this.x + 0.5 - x;
            double dy = this.y + 0.5 - y;
            double dz = this.z + 0.5 - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final Map<Integer, Map<Long, Map<Long, BlockTime>>> blocks = new HashMap<>();

    public void build(Report report) {
        blocks.clear();
        if (report == null || report.mode != ScanMode.TPS || report.periods <= 0) {
            return;
        }
        for (ReportEntry category : report.categories) {
            if (category.category == Category.TILE_ENTITIES || category.category == Category.AE2_NETWORKS
                || category.category == Category.COVERS) {
                collect(category, report.periods);
            }
        }
    }

    private void collect(ReportEntry entry, int periods) {
        if (entry.level == 3 && entry.kind == LocationKind.BLOCK && entry.totalMs > 0) {
            if (entry.y >= 0 && entry.y < 256) {
                Map<Long, Map<Long, BlockTime>> dimension = blocks.computeIfAbsent(entry.dim, k -> new HashMap<>());
                Map<Long, BlockTime> chunk = dimension
                    .computeIfAbsent(chunkKey(entry.x >> 4, entry.z >> 4), k -> new HashMap<>());
                BlockTime time = chunk.computeIfAbsent(
                    blockKey(entry.x, entry.y, entry.z),
                    k -> new BlockTime(entry.dim, entry.x, entry.y, entry.z));
                double average = entry.totalMs / periods;
                if (entry.category == Category.TILE_ENTITIES) {
                    time.tileAverage += average;
                    time.tilePeak = Math.max(time.tilePeak, entry.maxMs);
                    if (time.tileEntry == null || entry.totalMs > time.tileEntry.totalMs) {
                        time.tileEntry = entry;
                    }
                } else {
                    time.aeAverage += average;
                    time.aePeak = Math.max(time.aePeak, entry.maxMs);
                    time.aeNodes++;
                    if (time.aeEntry == null || entry.totalMs > time.aeEntry.totalMs) {
                        time.aeEntry = entry;
                    }
                }
            }
            return;
        }
        for (ReportEntry child : entry.children) {
            collect(child, periods);
        }
    }

    public BlockTime at(int dim, int x, int y, int z) {
        Map<Long, Map<Long, BlockTime>> dimension = blocks.get(dim);
        if (dimension == null) {
            return null;
        }
        Map<Long, BlockTime> chunk = dimension.get(chunkKey(x >> 4, z >> 4));
        return chunk == null ? null : chunk.get(blockKey(x, y, z));
    }

    public List<BlockTime> nearby(int dim, double x, double y, double z, int radius, int limit, double minMs) {
        Map<Long, Map<Long, BlockTime>> dimension = blocks.get(dim);
        if (dimension == null || limit <= 0) {
            return Collections.emptyList();
        }
        double maxDistance = radius * (double) radius;
        List<BlockTime> found = new ArrayList<>();
        int minChunkX = (int) Math.floor((x - radius) / 16.0);
        int maxChunkX = (int) Math.floor((x + radius) / 16.0);
        int minChunkZ = (int) Math.floor((z - radius) / 16.0);
        int maxChunkZ = (int) Math.floor((z + radius) / 16.0);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                Map<Long, BlockTime> chunk = dimension.get(chunkKey(chunkX, chunkZ));
                if (chunk == null) {
                    continue;
                }
                for (BlockTime block : chunk.values()) {
                    if (block.totalAverage() >= minMs && block.distanceSquared(x, y, z) <= maxDistance) {
                        found.add(block);
                    }
                }
            }
        }
        found.sort(
            Comparator.comparingDouble(BlockTime::totalAverage)
                .reversed()
                .thenComparingDouble(block -> block.distanceSquared(x, y, z)));
        if (found.size() > limit) {
            return new ArrayList<>(found.subList(0, limit));
        }
        return found;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | y & 0xFFFL;
    }
}
