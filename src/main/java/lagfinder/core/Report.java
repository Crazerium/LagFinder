package lagfinder.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import lagfinder.Config;

/** A finished scan: the rate over time and what took the time, by category, mod, kind and instance. */
public final class Report {

    private static final int MAGIC = 0x4C46524E; // "LFRN"
    public static final int VERSION = 9;

    public ScanMode mode;
    /** What the scan was for; older reports were all lag scans. */
    public ScanFocus focus = ScanFocus.LAG;
    /** When it started (epoch milliseconds) and how long it ran (seconds). */
    public long startedAt;
    public float durationSec;
    /** Where it was made: the server's or the world's name. */
    public String source = "";
    /** Ticks or frames measured. */
    public int periods;
    /** Length of one bucket of the graphs (seconds); the last one may be shorter. */
    public float bucketSec;
    /** Per bucket: ticks or frames a second, their average time and their worst time (ms). */
    public float[] rate, avgMs, maxMs;
    /** Per bucket: ticks or frames in it. */
    public int[] counts;
    /** A period of an element at least this long (ms) counted as a spike. */
    public float spikeMs;
    public final Map<Integer, String> dimensions = new HashMap<>();
    public final List<ReportEntry> categories = new ArrayList<>();
    /** What the scanned thread was doing, method by method; null when it wasn't sampled (or an older report). */
    public Profile profile;
    /** The Java heap during the scan: its use, the allocation and the objects by class; null when not watched. */
    public HeapProfile memory;
    /** The objects created during the scan and the classes that created them; null when not watched. */
    public AllocationProfile allocations;
    /**
     * Whether the server and the client were scanned together and their methods, objects or allocations added up
     * into this one report; its rate and graph are then the server's.
     */
    public boolean bothSides;
    /**
     * What the scan itself took of its length (seconds): timing each thing and adding it up. The ticks and frames of
     * the graphs are already without it; the rate over the whole scan leaves it out too.
     */
    public float overheadSec;
    /** Frames a second the game ran at just before the scan, 0 when not known. */
    public float rateBefore;

    /** Where the scan ran. */
    public ScanSide side() {
        return bothSides ? ScanSide.BOTH : ScanSide.of(mode);
    }

    public int buckets() {
        return rate.length;
    }

    /** Ticks (or frames) of the worst second of the scan, -1 when not known (older reports, or under a second). */
    public float lowestSecond = -1;

    /** How long the scan ran without what it took itself (seconds). */
    public float measuredSec() {
        return Math.max(durationSec * 0.02f, durationSec - overheadSec);
    }

    /** The average rate over the whole scan (ticks or frames a second), without what the scan itself took. */
    public float averageRate() {
        return durationSec <= 0 ? 0 : Math.min(mode == ScanMode.TPS ? 20f : Float.MAX_VALUE, periods / measuredSec());
    }

    /**
     * The lowest rate of the scan: of its worst second (a fixed window, whatever the scan's length); for reports made
     * before that was kept, of its worst moment of the graph.
     */
    public float minimumRate() {
        if (lowestSecond >= 0) {
            return lowestSecond;
        }
        float min = Float.MAX_VALUE;
        for (int i = 0; i < rate.length; i++) {
            min = Math.min(min, rate[i]);
        }
        return min == Float.MAX_VALUE ? 0 : min;
    }

    /** Average time of a tick or frame over the whole scan (ms): every tick counts the same. */
    public float averagePeriodMs() {
        double sum = 0;
        long count = 0;
        for (int i = 0; i < avgMs.length; i++) {
            sum += (double) avgMs[i] * counts[i];
            count += counts[i];
        }
        return count == 0 ? 0 : (float) (sum / count);
    }

    /**
     * The rate in each moment of the graph, from the time its ticks (or frames) took, as Forge's /forge tps counts it:
     * 1000 / average tick, at most 20 for ticks. Counting the ticks that started in a moment instead gives 12 or 24
     * for a moment of a twelfth of a second that holds one or two of them, on a server that is perfectly fine. A
     * moment no tick started in lies inside a tick longer than it: it has that tick's rate (the one before it).
     */
    public static float[] ratesOf(ScanMode mode, int[] counts, float[] averageMs) {
        int n = counts.length;
        float[] rates = new float[n];
        float last = -1;
        for (int i = 0; i < n; i++) {
            if (counts[i] > 0 && averageMs[i] > 0) {
                float rate = 1000f / averageMs[i];
                last = mode == ScanMode.TPS ? Math.min(20f, rate) : rate;
            }
            rates[i] = last;
        }
        // Moments before the first tick ended: as the first one.
        float first = 0;
        for (int i = 0; i < n; i++) {
            if (rates[i] >= 0) {
                first = rates[i];
                break;
            }
        }
        for (int i = 0; i < n && rates[i] < 0; i++) {
            rates[i] = first;
        }
        return rates;
    }

    public float worstPeriodMs() {
        float worst = 0;
        for (float value : maxMs) {
            worst = Math.max(worst, value);
        }
        return worst;
    }

    /** The entry's average time per tick or frame in the bucket (ms). */
    public float perPeriod(ReportEntry entry, int bucket) {
        return entry.sum == null || counts[bucket] == 0 ? 0 : entry.sum[bucket] / counts[bucket];
    }

    /** The entry's average time per tick or frame over the whole scan (ms). */
    public double perPeriod(ReportEntry entry) {
        return periods == 0 ? 0 : entry.totalMs / periods;
    }

    /** A whole frame (or tick) as it went by on the clock, idle time included (ms). */
    public double wallPeriodMs() {
        if (periods > 0 && durationSec > 0) {
            return measuredSec() * 1000.0 / periods;
        }
        return averagePeriodMs();
    }

    /** How many frames a second this much time in every frame costs: how many more there would be without it. */
    public double fpsCost(double perFrameMs) {
        return fpsCost(perFrameMs, wallPeriodMs());
    }

    /** The same, against frames of the given length (ms), e.g. those of one bucket. */
    public static double fpsCost(double perFrameMs, double frameMs) {
        if (frameMs <= 0 || perFrameMs <= 0) {
            return 0;
        }
        double without = Math.max(frameMs - perFrameMs, frameMs * 0.02);
        return 1000.0 / without - 1000.0 / frameMs;
    }

    /** How far the frames a second fell in the frame where this took the longest (it usually takes the average). */
    public double fpsDrop(double averageMs, double peakMs) {
        return fpsDrop(averageMs, peakMs, wallPeriodMs());
    }

    public static double fpsDrop(double averageMs, double peakMs, double frameMs) {
        if (frameMs <= 0 || peakMs <= averageMs) {
            return 0;
        }
        return 1000.0 / frameMs - 1000.0 / (frameMs + peakMs - averageMs);
    }

    public String dimensionName(int dim) {
        String name = dimensions.get(dim);
        return name != null ? name : "DIM " + dim;
    }

    public ReportEntry category(Category category) {
        for (ReportEntry entry : categories) {
            if (entry.category == category) {
                return entry;
            }
        }
        return null;
    }

    public void write(OutputStream stream) throws IOException {
        GZIPOutputStream zip = new GZIPOutputStream(stream);
        DataOutputStream out = new DataOutputStream(zip);
        out.writeInt(MAGIC);
        out.writeShort(VERSION);
        out.writeByte(mode.ordinal());
        out.writeLong(startedAt);
        out.writeFloat(durationSec);
        out.writeUTF(source == null ? "" : source);
        out.writeInt(periods);
        out.writeFloat(bucketSec);
        out.writeFloat(spikeMs);
        out.writeShort(rate.length);
        for (int i = 0; i < rate.length; i++) {
            out.writeFloat(rate[i]);
            out.writeFloat(avgMs[i]);
            out.writeFloat(maxMs[i]);
            out.writeInt(counts[i]);
        }
        out.writeShort(dimensions.size());
        for (Map.Entry<Integer, String> dim : dimensions.entrySet()) {
            out.writeInt(dim.getKey());
            out.writeUTF(dim.getValue());
        }
        out.writeShort(categories.size());
        for (ReportEntry entry : categories) {
            entry.write(out);
        }
        out.writeBoolean(profile != null);
        if (profile != null) {
            profile.write(out);
        }
        out.writeBoolean(memory != null);
        if (memory != null) {
            memory.write(out);
        }
        out.writeByte(focus.ordinal());
        out.writeBoolean(allocations != null);
        if (allocations != null) {
            allocations.write(out);
        }
        out.writeBoolean(bothSides);
        out.writeFloat(overheadSec);
        out.writeFloat(rateBefore);
        out.writeFloat(lowestSecond);
        out.flush();
        zip.finish();
    }

    public static Report read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(new GZIPInputStream(stream));
        if (in.readInt() != MAGIC) {
            throw new IOException("Not a LagFinder report");
        }
        int version = in.readShort();
        if (version < 1 || version > VERSION) {
            throw new IOException("Report version " + version + " is not supported");
        }
        Report report = new Report();
        report.mode = ScanMode.values()[in.readByte()];
        report.startedAt = in.readLong();
        report.durationSec = in.readFloat();
        report.source = in.readUTF();
        report.periods = in.readInt();
        report.bucketSec = in.readFloat();
        report.spikeMs = in.readFloat();
        if (report.spikeMs <= 0) {
            // Reports made before it was kept: the spike of the scan's side as it is set now.
            report.spikeMs = report.mode == ScanMode.TPS ? Config.spikeTpsMs : Config.spikeFpsMs;
        }
        int buckets = in.readShort();
        report.rate = new float[buckets];
        report.avgMs = new float[buckets];
        report.maxMs = new float[buckets];
        report.counts = new int[buckets];
        for (int i = 0; i < buckets; i++) {
            report.rate[i] = in.readFloat();
            report.avgMs[i] = in.readFloat();
            report.maxMs[i] = in.readFloat();
            report.counts[i] = in.readInt();
        }
        // Older reports counted ticks per moment; from their times the rate comes out right for them too.
        report.rate = ratesOf(report.mode, report.counts, report.avgMs);
        int dims = in.readShort();
        for (int i = 0; i < dims; i++) {
            report.dimensions.put(in.readInt(), in.readUTF());
        }
        int count = in.readShort();
        for (int i = 0; i < count; i++) {
            report.categories.add(ReportEntry.read(in, null));
        }
        if (version >= 2 && in.readBoolean()) {
            report.profile = Profile.read(in);
        }
        if (version >= 3 && in.readBoolean()) {
            report.memory = HeapProfile.read(in);
        }
        if (version >= 4) {
            report.focus = ScanFocus.of(in.readByte());
        }
        if (version >= 5 && in.readBoolean()) {
            report.allocations = AllocationProfile.read(in, version);
        }
        if (version >= 7) {
            report.bothSides = in.readBoolean();
        }
        if (version >= 8) {
            report.overheadSec = in.readFloat();
            report.rateBefore = in.readFloat();
        }
        if (version >= 9) {
            report.lowestSecond = in.readFloat();
        }
        return report;
    }
}
