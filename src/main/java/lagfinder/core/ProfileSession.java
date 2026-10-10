package lagfinder.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import lagfinder.Config;
import lagfinder.LagFinder;

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
        if (session != null && session.timesThings && session.thread == Thread.currentThread()) {
            return session;
        }
        session = client;
        if (session != null && session.timesThings && session.thread == Thread.currentThread()) {
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
    public final Thread thread;
    public final long durationNanos;
    public final long startNanos;
    public final long startMillis;
    public final long bucketNanos;
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
    /** Block ticks by world, block and chunk. */
    private final Map<World, IdentityHashMap<Block, HashMap<Long, Node>>> blockTicks = new IdentityHashMap<>();
    private final List<Node> pending = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private final List<Node>[] touched = new List[4];

    private final int[] periods = new int[BUCKETS];
    private final double[] periodNanos = new double[BUCKETS];
    private final float[] periodMax = new float[BUCKETS];
    private int totalPeriods;
    private long lastEnd;

    public ProfileSession(ScanMode mode, Thread thread, long durationNanos, float spikeMs, Describer describer) {
        this.mode = mode;
        this.thread = thread;
        this.durationNanos = durationNanos;
        this.describer = describer;
        this.spikeNanos = (long) (spikeMs * 1_000_000L);
        this.startNanos = System.nanoTime();
        this.startMillis = System.currentTimeMillis();
        this.bucketNanos = Math.max(1, durationNanos / BUCKETS);
        this.lastEnd = startNanos;
        for (Category category : Category.values()) {
            roots[category.ordinal()] = new Node(Node.CATEGORY, category, category);
            leaves[category.ordinal()] = new IdentityHashMap<>();
        }
        for (int i = 0; i < touched.length; i++) {
            touched[i] = new ArrayList<>();
        }
    }

    /**
     * Starts what the scan is for, and only that: a lag scan times each thing; a methods scan samples the thread's
     * stack; an objects scan watches the heap and counts its objects a few times along; an allocations scan watches what
     * objects are created and by which classes. Every scan times its ticks or frames, for its graphs.
     */
    public void startFor(ScanFocus focus) {
        this.focus = focus;
        timesThings = focus == ScanFocus.LAG;
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

    /** A tick (or frame) starts: the thread is sampled until it ends. */
    public void beginPeriod() {
        if (sampler != null) {
            sampler.setActive(true);
        }
    }

    /** How far along the scan is, 0 to 1. */
    public float progress() {
        return Math.min(1f, (System.nanoTime() - startNanos) / (float) durationNanos);
    }

    public boolean isDone(long now) {
        return now - startNanos >= durationNanos;
    }

    /** Time the thing (a tile entity, an entity, a listener, a world) took just now. */
    public void record(Category category, Object key, long nanos) {
        if (!timesThings) {
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
    }

    /** Time a block's tick took, counted per block and chunk; the block that ticked last is remembered. */
    public void recordBlockTick(World world, Block block, int x, int y, int z, long nanos) {
        if (!timesThings) {
            return;
        }
        IdentityHashMap<Block, HashMap<Long, Node>> byBlock = blockTicks.get(world);
        if (byBlock == null) {
            byBlock = new IdentityHashMap<>();
            blockTicks.put(world, byBlock);
        }
        HashMap<Long, Node> byChunk = byBlock.get(block);
        if (byChunk == null) {
            byChunk = new HashMap<>();
            byBlock.put(block, byChunk);
        }
        long chunk = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
        Node node = byChunk.get(chunk);
        if (node == null) {
            node = new Node(Node.INSTANCE, Category.BLOCK_TICKS, block);
            node.live = world;
            node.kind = LocationKind.CHUNK;
            byChunk.put(chunk, node);
            pending.add(node);
        }
        node.x = x;
        node.y = y;
        node.z = z;
        node.add(nanos, 1, this);
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
        resolvePending();
        int bucket = bucketOf(start);
        periods[bucket]++;
        periodNanos[bucket] += nanos;
        periodMax[bucket] = Math.max(periodMax[bucket], nanos);
        totalPeriods++;
        lastEnd = start + nanos;
        float seconds = (start - startNanos) / 1e9f;
        for (int depth = Node.INSTANCE; depth >= Node.CATEGORY; depth--) {
            List<Node> list = touched[depth];
            // Parents are touched one level up, which is handled after this one.
            for (int i = 0; i < list.size(); i++) {
                Node node = list.get(i);
                // This mod's own handlers are left out of everything above them, as they are of the report.
                if (node.parent != null && !(depth >= Node.TYPE && LagFinder.NAME.equals(node.mod))) {
                    node.parent.add(node.periodNanos, node.periodCalls, this);
                }
                node.finishPeriod(bucket, BUCKETS, seconds, depth == Node.CATEGORY ? Long.MAX_VALUE : spikeNanos);
            }
            list.clear();
        }
    }

    private int bucketOf(long time) {
        return (int) Math.max(0, Math.min(BUCKETS - 1, (time - startNanos) / bucketNanos));
    }

    /** Finds out what new leaves are and puts them in the tree. */
    private void resolvePending() {
        if (pending.isEmpty()) {
            return;
        }
        for (Node leaf : pending) {
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
                leaf.typeKey = leaf.key.getClass();
            }
            if (leaf.name == null) {
                leaf.name = leaf.key.getClass()
                    .getSimpleName();
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
        pending.clear();
    }

    /**
     * Adds what only comes in after the scan to its report: the memory, once a count of the objects still under way
     * ended, and the allocations the Flight Recorder still holds, which it hands over within a couple of seconds. Waits for them, so call it off the game's thread, before the report is
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

    /** Builds the report from what was measured. Call on the scan's thread, after its last period. */
    public Report buildReport(String source) {
        // The allocations are finished by completeReport, off the game's thread: only their end is marked here.
        finishingAllocations = allocations;
        allocations = null;
        if (finishingAllocations != null) {
            finishingAllocations.markEnd();
        }
        close();
        resolvePending();
        for (IdentityHashMap<Object, Node> map : leaves) {
            for (Node node : map.values()) {
                describer.updateLocation(node);
            }
        }
        long end = Math.max(lastEnd, startNanos + 1);
        Report report = ReportBuilder.build(
            this,
            roots,
            periods,
            periodNanos,
            periodMax,
            totalPeriods,
            end - startNanos,
            source,
            describer.dimensionNames());
        report.focus = focus;
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
        // The heap's watcher may still be counting the objects: completeReport waits for it, off the game's thread.
        finishingHeap = heap;
        heap = null;
        return report;
    }
}
