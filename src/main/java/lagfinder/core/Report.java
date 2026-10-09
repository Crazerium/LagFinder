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

/** A finished scan: the rate over time and what took the time, by category, mod, kind and instance. */
public final class Report {

    private static final int MAGIC = 0x4C46524E; // "LFRN"
    public static final int VERSION = 3;

    public ScanMode mode;
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

    public int buckets() {
        return rate.length;
    }

    /** The average rate over the whole scan (ticks or frames a second). */
    public float averageRate() {
        return durationSec <= 0 ? 0 : Math.min(mode == ScanMode.TPS ? 20f : Float.MAX_VALUE, periods / durationSec);
    }

    public float minimumRate() {
        float min = Float.MAX_VALUE;
        for (int i = 0; i < rate.length; i++) {
            min = Math.min(min, rate[i]);
        }
        return min == Float.MAX_VALUE ? 0 : min;
    }

    /** Average time of a tick or frame (ms). */
    public float averagePeriodMs() {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < avgMs.length; i++) {
            if (avgMs[i] > 0) {
                sum += avgMs[i];
                count++;
            }
        }
        return count == 0 ? 0 : (float) (sum / count);
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
            return durationSec * 1000.0 / periods;
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
        return report;
    }
}
