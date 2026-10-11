package lagfinder.client.heatmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lagfinder.core.Report;
import lagfinder.core.ReportEntry;

/**
 * How much each chunk cost in a tick scan: the time of everything the report places in a chunk (tile entities,
 * entities, block ticks, the machines of AE2 networks), added up per chunk. What was left out of the report as too
 * light to keep is not in it, so the numbers are a little under the truth for chunks with a great many small things.
 */
public final class ChunkHeat {

    /** The chunk's key in a dimension's map: x in the high half, z in the low one. */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** What one chunk cost. */
    public static final class Cell {

        /** Average time of its things per tick over the scan (ms). */
        public double msPerTick;
        /** The things in it, to name the heaviest ones. */
        public final List<ReportEntry> things = new ArrayList<>();
    }

    /** The report it was made from, and when its scan started. */
    public final Report report;
    public final long startedAt;
    /** Per dimension, per chunk. */
    private final Map<Integer, Map<Long, Cell>> dimensions = new HashMap<>();
    private final Map<Integer, Double> maxima = new HashMap<>();
    private double maximum;
    private int chunks;

    public ChunkHeat(Report report) {
        this.report = report;
        this.startedAt = report.startedAt;
        for (ReportEntry category : report.categories) {
            add(category, report);
        }
        for (Map.Entry<Integer, Map<Long, Cell>> dimension : dimensions.entrySet()) {
            double max = 0;
            for (Cell cell : dimension.getValue()
                .values()) {
                max = Math.max(max, cell.msPerTick);
            }
            maxima.put(dimension.getKey(), max);
            maximum = Math.max(maximum, max);
            chunks += dimension.getValue()
                .size();
        }
    }

    private void add(ReportEntry entry, Report report) {
        if (entry.hasLocation()) {
            double ms = report.perPeriod(entry);
            if (ms > 0) {
                int chunkX = entry.x >> 4, chunkZ = entry.z >> 4;
                Cell cell = dimensions.computeIfAbsent(entry.dim, d -> new HashMap<>())
                    .computeIfAbsent(key(chunkX, chunkZ), k -> new Cell());
                cell.msPerTick += ms;
                cell.things.add(entry);
            }
            // A thing in the world is one line: whatever hangs under it is part of its time.
            return;
        }
        for (ReportEntry child : entry.children) {
            add(child, report);
        }
    }

    /** The chunk's cost, or null when nothing in it was measured. */
    public Cell cell(int dimension, int chunkX, int chunkZ) {
        Map<Long, Cell> chunks = dimensions.get(dimension);
        return chunks == null ? null : chunks.get(key(chunkX, chunkZ));
    }

    /** The chunks of a dimension with their cost, or null when the scan saw nothing of it. */
    public Map<Long, Cell> chunks(int dimension) {
        return dimensions.get(dimension);
    }

    /** The cost of the most expensive chunk of the dimension (ms per tick), 0 when there are none. */
    public double max(int dimension) {
        Double max = maxima.get(dimension);
        return max == null ? 0 : max;
    }

    /** The cost of the most expensive chunk of all (ms per tick). */
    public double max() {
        return maximum;
    }

    /** Chunks with something measured in them, in all dimensions. */
    public int chunkCount() {
        return chunks;
    }

    public boolean isEmpty() {
        return chunks == 0;
    }
}
