package lagfinder;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {

    public static boolean allowTeleport = true;
    /** Longest scan a player may ask for (seconds). */
    public static int maxScanSeconds = 600;
    /** A tick (or frame) in which one thing took at least this long counts as a spike for it (ms). */
    public static float spikeTpsMs = 2.5f;
    public static float spikeFpsMs = 1.0f;
    /** A light tick scan measures one tick in this many; the others run without measuring. */
    public static int lightScanEvery = 4;
    /** How often a Methods scan samples the scanned thread's stack (ms). */
    public static int sampleIntervalMs = 5;
    /** How many times an Objects scan counts the objects in the heap by class, only those in use. */
    public static int heapSnapshots = 4;
    public static boolean heapLiveOnly = true;
    /** How many allocations a second the Flight Recorder takes samples of at most, in an allocations scan. */
    public static int allocSampleRate = 500;
    /** How long a highlight stays in the world (seconds). */
    public static int highlightSeconds = 120;
    /** How far labels of the time machines took are drawn in the world (blocks), how many, and the least time shown. */
    public static int worldMsRadius = 16;
    public static int worldMsMaxLabels = 36;
    public static float worldMsMinMs = 0.01f;

    private static final ModConfigSpec SERVER_SPEC;
    private static final ModConfigSpec.BooleanValue ALLOW_TELEPORT;
    private static final ModConfigSpec.IntValue MAX_SCAN_SECONDS;
    private static final ModConfigSpec.DoubleValue SPIKE_TPS_MS;
    private static final ModConfigSpec.IntValue LIGHT_SCAN_EVERY;
    private static final ModConfigSpec.IntValue SAMPLE_INTERVAL_MS;
    private static final ModConfigSpec.IntValue HEAP_SNAPSHOTS;
    private static final ModConfigSpec.BooleanValue HEAP_LIVE_ONLY;
    private static final ModConfigSpec.IntValue ALLOC_SAMPLE_RATE;

    private static final ModConfigSpec CLIENT_SPEC;
    private static final ModConfigSpec.IntValue HIGHLIGHT_SECONDS;
    private static final ModConfigSpec.IntValue WORLD_MS_RADIUS;
    private static final ModConfigSpec.IntValue WORLD_MS_MAX_LABELS;
    private static final ModConfigSpec.DoubleValue WORLD_MS_MIN_MS;

    static {
        ModConfigSpec.Builder server = new ModConfigSpec.Builder();
        ALLOW_TELEPORT = server
            .comment("Players with the lagfinder.teleport permission (operators by default) may teleport from reports.")
            .define("allowTeleport", allowTeleport);
        MAX_SCAN_SECONDS = server.comment("Longest tick scan a player may run (seconds).")
            .defineInRange("maxScanSeconds", maxScanSeconds, 5, 3600);
        SPIKE_TPS_MS = server.comment("A tick in which one thing took at least this long (ms) counts as a spike for it.")
            .defineInRange("spikeTpsMs", spikeTpsMs, 0.01, 1000);
        LIGHT_SCAN_EVERY = server.comment(
            "A light tick scan measures one tick in this many and lets the others run untouched: the server barely slows down and averages stay right, but a single short spike can fall between measured ticks.")
            .defineInRange("lightScanEvery", lightScanEvery, 2, 20);
        SAMPLE_INTERVAL_MS = server
            .comment("Milliseconds between samples of the stack in a Methods scan. Lower is more precise but costs more.")
            .defineInRange("sampleIntervalMs", sampleIntervalMs, 1, 100);
        HEAP_SNAPSHOTS = server.comment(
            "How many times the objects in the heap are counted by class along an Objects scan (the first at its start, the last near its end; at least 2). Each count stops the server for a moment (seconds on a big heap).")
            .defineInRange("heapSnapshots", heapSnapshots, 0, 20);
        HEAP_LIVE_ONLY = server.comment(
            "Count only the objects still in use: a full garbage collection is made before each count, which takes longer. Off counts garbage too.")
            .define("heapLiveOnly", heapLiveOnly);
        ALLOC_SAMPLE_RATE = server.comment(
            "How many allocations a second Java's Flight Recorder takes samples of at most along an Allocations scan. More is more precise but costs more.")
            .defineInRange("allocSampleRate", allocSampleRate, 20, 10000);
        SERVER_SPEC = server.build();

        ModConfigSpec.Builder client = new ModConfigSpec.Builder();
        HIGHLIGHT_SECONDS = client.comment("How long a highlight stays in the world (seconds).")
            .defineInRange("highlightSeconds", highlightSeconds, 5, 3600);
        WORLD_MS_RADIUS = client.comment("How far world millisecond labels are drawn, in blocks.")
            .defineInRange("worldMsRadius", worldMsRadius, 4, 48);
        WORLD_MS_MAX_LABELS = client.comment("Maximum nearby machine time labels shown in the world.")
            .defineInRange("worldMsMaxLabels", worldMsMaxLabels, 1, 96);
        WORLD_MS_MIN_MS = client.comment(
            "Minimum average milliseconds per tick for a nearby label. The block under the crosshair is always shown if measured.")
            .defineInRange("worldMsMinMs", worldMsMinMs, 0, 10);
        CLIENT_SPEC = client.build();
    }

    private Config() {}

    static void register(ModContainer container, IEventBus modBus) {
        container.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
        modBus.addListener(ModConfigEvent.Loading.class, event -> read(event.getConfig()));
        modBus.addListener(ModConfigEvent.Reloading.class, event -> read(event.getConfig()));
    }

    /**
     * Writes the settings as they are now to their files (changed in the Settings tab): the client's, and the
     * server's where it runs in this game (single player).
     */
    public static void save() {
        if (SERVER_SPEC.isLoaded()) {
            ALLOW_TELEPORT.set(allowTeleport);
            MAX_SCAN_SECONDS.set(maxScanSeconds);
            SPIKE_TPS_MS.set(Double.parseDouble(Float.toString(spikeTpsMs)));
            SAMPLE_INTERVAL_MS.set(sampleIntervalMs);
            HEAP_SNAPSHOTS.set(heapSnapshots);
            HEAP_LIVE_ONLY.set(heapLiveOnly);
            ALLOC_SAMPLE_RATE.set(allocSampleRate);
            SERVER_SPEC.save();
        }
        if (CLIENT_SPEC.isLoaded()) {
            HIGHLIGHT_SECONDS.set(highlightSeconds);
            WORLD_MS_RADIUS.set(worldMsRadius);
            WORLD_MS_MAX_LABELS.set(worldMsMaxLabels);
            WORLD_MS_MIN_MS.set(Double.parseDouble(Float.toString(worldMsMinMs)));
            CLIENT_SPEC.save();
        }
    }

    private static void read(ModConfig config) {
        if (config.getSpec() == SERVER_SPEC) {
            allowTeleport = ALLOW_TELEPORT.get();
            maxScanSeconds = MAX_SCAN_SECONDS.get();
            spikeTpsMs = SPIKE_TPS_MS.get()
                .floatValue();
            lightScanEvery = LIGHT_SCAN_EVERY.get();
            sampleIntervalMs = SAMPLE_INTERVAL_MS.get();
            heapSnapshots = HEAP_SNAPSHOTS.get();
            heapLiveOnly = HEAP_LIVE_ONLY.get();
            allocSampleRate = ALLOC_SAMPLE_RATE.get();
        } else if (config.getSpec() == CLIENT_SPEC) {
            highlightSeconds = HIGHLIGHT_SECONDS.get();
            worldMsRadius = WORLD_MS_RADIUS.get();
            worldMsMaxLabels = WORLD_MS_MAX_LABELS.get();
            worldMsMinMs = WORLD_MS_MIN_MS.get()
                .floatValue();
        }
    }
}
