package lagfinder;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    /** Only operators may scan ticks and teleport (the owner of a single player world always may). */
    public static boolean requireOp = true;
    public static boolean allowTeleport = true;
    /** Longest scan a player may ask for (seconds). */
    public static int maxScanSeconds = 600;
    /** A tick (or frame) in which one thing took at least this long counts as a spike for it (ms). */
    public static float spikeTpsMs = 2.5f;
    public static float spikeFpsMs = 1.0f;
    /** How often a Methods scan samples the scanned thread's stack (ms). */
    public static int sampleIntervalMs = 5;
    /** How many times an Objects scan counts the objects in the heap by class, only those in use. */
    public static int heapSnapshots = 4;
    public static boolean heapLiveOnly = true;
    /** How many allocations a second the Flight Recorder takes samples of at most, in an allocations scan. */
    public static int allocSampleRate = 500;
    /** How long a highlight stays in the world (seconds). */
    public static int highlightSeconds = 120;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        requireOp = configuration.getBoolean(
            "requireOp",
            "server",
            requireOp,
            "Only operators may run tick scans and teleport to what they find. The owner of a single player world always may.");
        allowTeleport = configuration
            .getBoolean("allowTeleport", "server", allowTeleport, "Players allowed to scan may teleport from reports.");
        maxScanSeconds = configuration
            .getInt("maxScanSeconds", "server", maxScanSeconds, 5, 3600, "Longest tick scan a player may run.");
        spikeTpsMs = configuration.getFloat(
            "spikeTpsMs",
            "scan",
            spikeTpsMs,
            0.01f,
            1000f,
            "A tick in which one thing took at least this long (ms) counts as a spike for it.");
        spikeFpsMs = configuration.getFloat(
            "spikeFpsMs",
            "scan",
            spikeFpsMs,
            0.01f,
            1000f,
            "A frame in which one thing took at least this long (ms) counts as a spike for it.");
        sampleIntervalMs = configuration.getInt(
            "sampleIntervalMs",
            "scan",
            sampleIntervalMs,
            1,
            100,
            "Milliseconds between samples of the stack. Lower is more precise but costs more.");
        heapSnapshots = configuration.getInt(
            "heapSnapshots",
            "memory",
            heapSnapshots,
            0,
            20,
            "How many times the objects in the heap are counted by class along an Objects scan (the first at its start, the last near its end; at least 2). Each count stops the game for a moment (seconds on a big heap).");
        heapLiveOnly = configuration.getBoolean(
            "heapLiveOnly",
            "memory",
            heapLiveOnly,
            "Count only the objects still in use: a full garbage collection is made before each count, which takes longer. Off counts garbage too.");
        allocSampleRate = configuration.getInt(
            "allocSampleRate",
            "memory",
            allocSampleRate,
            20,
            10000,
            "How many allocations a second Java's Flight Recorder takes samples of at most along an Allocations scan (Java 16 and later). More is more precise but costs more.");
        highlightSeconds = configuration
            .getInt("highlightSeconds", "client", highlightSeconds, 5, 3600, "How long a highlight stays in the world.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
