package LagFinder.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of a scan's tree while it runs: a category (depth 0), a mod (1), a kind of thing (2) or one thing in the
 * world (3). Time measured in a tick (or frame) is added to a leaf, and at the end of the period it is passed up to
 * its parents and added to everyone's totals, so every level knows its own worst period, not only the sum of its
 * children's.
 */
public final class Node {

    public static final int CATEGORY = 0, MOD = 1, TYPE = 2, INSTANCE = 3;

    public final int depth;
    public final Category category;
    /** What it is measured by: the tile entity, entity or listener itself for leaves, a name or class above. */
    public final Object key;
    public Node parent;
    /** Children by their key, for categories, mods and types. */
    public Map<Object, Node> children;

    // Description, set when the leaf is first resolved (see Describer).
    public String mod;
    public Object typeKey;
    public String name;
    /** Class or handler behind it, shown in the details. */
    public String detail;
    /** A translation key the client may know a better name by, or null. */
    public String translationKey;
    /** Item standing for it (its icon and name on the client), -1 for none. */
    public int itemId = -1, itemDamage;

    // Where an instance is.
    public int dim, x, y, z;
    public LocationKind kind = LocationKind.NONE;
    /** Something whose position is read again when the report is made (an entity that moves), or null. */
    public Object live;
    /** What an instance is on its own: an item's name, a named mob, a chunk; null when its type says it all. */
    public String label;
    /** Set when the thing behind the leaf was resolved. */
    public boolean resolved;

    // The period being measured.
    long periodNanos;
    int periodCalls;
    boolean touched;

    // Totals over the scan.
    public long totalNanos;
    public long calls;
    public long maxNanos;
    /** Seconds since the start of the scan when its worst period happened. */
    public float maxAt;
    public int activePeriods;
    public int spikes;
    /** Instances seen, for types. */
    public int instances;
    /** Per bucket of time: the time spent in it, and the worst single period (nanoseconds, as floats). */
    public float[] bucketSum, bucketMax;

    public Node(int depth, Category category, Object key) {
        this.depth = depth;
        this.category = category;
        this.key = key;
    }

    /** The child with the key, made with {@code depth + 1} if there is none yet. */
    public Node child(Object childKey) {
        if (children == null) {
            children = new LinkedHashMap<>();
        }
        Node child = children.get(childKey);
        if (child == null) {
            child = new Node(depth + 1, category, childKey);
            child.parent = this;
            children.put(childKey, child);
        }
        return child;
    }

    void add(long nanos, int count, ProfileSession session) {
        periodNanos += nanos;
        periodCalls += count;
        if (!touched) {
            touched = true;
            session.touched(this);
        }
    }

    /** Adds the period that just ended to the totals. */
    void finishPeriod(int bucket, int buckets, float seconds, long spikeNanos) {
        long nanos = periodNanos;
        totalNanos += nanos;
        calls += periodCalls;
        activePeriods++;
        if (nanos > maxNanos) {
            maxNanos = nanos;
            maxAt = seconds;
        }
        if (nanos >= spikeNanos) {
            spikes++;
        }
        if (bucketSum == null) {
            bucketSum = new float[buckets];
            bucketMax = new float[buckets];
        }
        bucketSum[bucket] += nanos;
        if (nanos > bucketMax[bucket]) {
            bucketMax[bucket] = nanos;
        }
        periodNanos = 0;
        periodCalls = 0;
        touched = false;
    }
}
