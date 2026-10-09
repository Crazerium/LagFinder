package lagfinder.core;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

import lagfinder.LagFinder;

/**
 * Watches what objects the game creates during a scan, and where: the JVM's Flight Recorder takes samples of the
 * allocations ({@code jdk.ObjectAllocationSample}, Java 16 and later), each with the object's type, the thread and
 * the stack it was created on. The Flight Recorder is reached by reflection, so the mod still runs where there is
 * none: there the scanned thread's stack is sampled instead, with the bytes it allocated in between (who allocates,
 * roughly, but not what). What it gathered is read once it is stopped.
 */
public final class AllocationWatcher {

    private static final String EVENT = "jdk.ObjectAllocationSample";
    /** Types and creating classes kept in the report at most, and how many of them keep their timeline. */
    private static final int MAX_GROUPS = 3000, MAX_TIMELINES = 250;
    /** Places of creation kept in the report at most: the smallest are left out. */
    private static final int MAX_SITES = 12_000;
    /** Milliseconds between samples of the stack where there is no Flight Recorder. */
    private static final int STACK_INTERVAL_MS = 4;

    private final Thread target;
    private final long targetId;
    private final long startMillis, bucketMillis;
    private final int buckets, rate;

    /** The Flight Recorder's stream, while it runs; null when the stacks are sampled. */
    private Object stream;
    private Jfr jfr;
    /** The thread sampling the stack where there is no Flight Recorder. */
    private Thread sampler;
    private volatile boolean running = true;
    /** The thread delivering the Flight Recorder's events: what it allocates is ours. */
    private volatile long ownThreadId = -1;
    private String note;
    private int source = AllocationProfile.SOURCE_JFR;

    // Touched under the lock.
    private final Map<String, Live> types = new HashMap<>(), creators = new HashMap<>();
    private final Map<String, LiveSite> sites = new HashMap<>();
    private final float[] allMb, targetMb;
    private long bytes, targetBytes;
    private int samples, targetSamples;

    /**
     * @param rate how many allocations a second the Flight Recorder takes samples of at most
     */
    public AllocationWatcher(Thread target, long startMillis, long durationNanos, int buckets, int rate) {
        this.target = target;
        this.targetId = target.getId();
        this.startMillis = startMillis;
        this.buckets = buckets;
        this.bucketMillis = Math.max(1, durationNanos / 1_000_000L / buckets);
        this.rate = Math.max(10, rate);
        this.allMb = new float[buckets];
        this.targetMb = new float[buckets];
    }

    public void start() {
        if (startRecorder()) {
            return;
        }
        source = AllocationProfile.SOURCE_STACKS;
        sampler = new Thread(this::sampleStacks, "LagFinder allocation sampler");
        sampler.setDaemon(true);
        sampler.setPriority(Thread.MAX_PRIORITY);
        sampler.start();
    }

    /** Stops watching; the Flight Recorder first hands over the events it still holds. */
    public void stop() {
        running = false;
        if (stream != null) {
            jfr.stop(stream);
            stream = null;
        }
        if (sampler != null) {
            LockSupport.unpark(sampler);
            try {
                sampler.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
        }
    }

    // ---------------------------------------------------------------- The Flight Recorder

    private static int javaVersion() {
        String version = System.getProperty("java.specification.version", "1.8");
        try {
            return version.startsWith("1.") ? Integer.parseInt(version.substring(2)) : Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    private boolean startRecorder() {
        int java = javaVersion();
        if (java < 16) {
            note = "Java " + java;
            return false;
        }
        Object created = null;
        try {
            jfr = new Jfr();
            created = jfr.streamType.getConstructor()
                .newInstance();
            Object settings = jfr.enable.invoke(created, EVENT);
            jfr.with.invoke(settings, "throttle", rate + "/s");
            jfr.withStackTrace.invoke(settings);
            Consumer<Object> handler = this::onEvent;
            jfr.onEvent.invoke(created, EVENT, handler);
            jfr.startAsync.invoke(created);
            stream = created;
            return true;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
                ? t.getCause()
                : t;
            note = cause.getClass()
                .getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            LagFinder.LOG.warn("LagFinder could not start the Flight Recorder; sampling stacks instead", cause);
            if (created != null) {
                jfr.stop(created);
            }
            return false;
        }
    }

    /** One sample of an allocation, on the thread the Flight Recorder delivers its events on. */
    private void onEvent(Object event) {
        if (ownThreadId < 0) {
            ownThreadId = Thread.currentThread()
                .getId();
        }
        try {
            Object thread = jfr.getThread.invoke(event, "eventThread");
            long threadId = thread == null ? -1 : (Long) jfr.javaThreadId.invoke(thread);
            if (threadId == ownThreadId) {
                return;
            }
            long weight = (Long) jfr.getLong.invoke(event, "weight");
            if (weight <= 0) {
                return;
            }
            Object type = jfr.objectClass.invoke(event, "objectClass");
            String typeName = type == null ? AllocationProfile.UNKNOWN_TYPE
                : HeapWatcher.javaName((String) jfr.className.invoke(type));
            Instant time = (Instant) jfr.startTime.invoke(event);

            // The creating class: the nearest frame that isn't Java's or a library's.
            String creator = AllocationProfile.UNKNOWN_TYPE, method = "", via = "", top = null;
            int line = -1;
            Object trace = jfr.stackTrace.invoke(event);
            List<?> frames = trace == null ? null : (List<?>) jfr.frames.invoke(trace);
            if (frames != null) {
                String previous = null;
                for (Object frame : frames) {
                    if (!(Boolean) jfr.javaFrame.invoke(frame)) {
                        continue;
                    }
                    Object recorded = jfr.frameMethod.invoke(frame);
                    String className = (String) jfr.className.invoke(jfr.methodType.invoke(recorded));
                    String methodName = (String) jfr.methodName.invoke(recorded);
                    int frameLine = (Integer) jfr.lineNumber.invoke(frame);
                    if (top == null) {
                        top = className;
                        creator = className;
                        method = methodName;
                        line = frameLine;
                    }
                    if (!isPlumbing(className)) {
                        creator = className;
                        method = methodName;
                        line = frameLine;
                        via = previous == null ? "" : previous;
                        break;
                    }
                    previous = className + "." + methodName;
                }
            }
            record(typeName, creator, method, line, via, weight, threadId == targetId,
                time == null ? System.currentTimeMillis() : time.toEpochMilli());
        } catch (Throwable t) {
            // An event that can't be read is left out.
        }
    }

    /** Whether the class is Java's or a library's: what it allocates goes to whoever called it. */
    private static boolean isPlumbing(String className) {
        String mod;
        try {
            mod = ModResolver.modOfClassName(className);
        } catch (Throwable t) {
            return false;
        }
        return mod.equals(ModResolver.JAVA) || mod.equals(ModResolver.LIBRARIES);
    }

    /** The parts of the Flight Recorder's API used, found by reflection. */
    private static final class Jfr {

        final Class<?> streamType;
        final Method enable, onEvent, startAsync, close, stop;
        final Method with, withStackTrace;
        final Method getThread, getLong, objectClass, startTime, stackTrace;
        final Method javaThreadId, className, frames, javaFrame, frameMethod, lineNumber, methodType, methodName;

        Jfr() throws ReflectiveOperationException {
            streamType = type("jdk.jfr.consumer.RecordingStream");
            enable = streamType.getMethod("enable", String.class);
            onEvent = streamType.getMethod("onEvent", String.class, Consumer.class);
            startAsync = streamType.getMethod("startAsync");
            close = streamType.getMethod("close");
            Method stopping;
            try {
                // Java 20 and later: hands over the events still held before it returns.
                stopping = streamType.getMethod("stop");
            } catch (NoSuchMethodException e) {
                stopping = null;
            }
            stop = stopping;
            Class<?> settings = type("jdk.jfr.EventSettings");
            with = settings.getMethod("with", String.class, String.class);
            withStackTrace = settings.getMethod("withStackTrace");
            Class<?> event = type("jdk.jfr.consumer.RecordedEvent");
            getThread = event.getMethod("getThread", String.class);
            getLong = event.getMethod("getLong", String.class);
            objectClass = event.getMethod("getClass", String.class);
            startTime = event.getMethod("getStartTime");
            stackTrace = event.getMethod("getStackTrace");
            javaThreadId = type("jdk.jfr.consumer.RecordedThread").getMethod("getJavaThreadId");
            className = type("jdk.jfr.consumer.RecordedClass").getMethod("getName");
            frames = type("jdk.jfr.consumer.RecordedStackTrace").getMethod("getFrames");
            Class<?> frame = type("jdk.jfr.consumer.RecordedFrame");
            javaFrame = frame.getMethod("isJavaFrame");
            frameMethod = frame.getMethod("getMethod");
            lineNumber = frame.getMethod("getLineNumber");
            Class<?> recordedMethod = type("jdk.jfr.consumer.RecordedMethod");
            methodType = recordedMethod.getMethod("getType");
            methodName = recordedMethod.getMethod("getName");
        }

        /** The JDK's own class: through the system class loader, past the game's. */
        private static Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, false, ClassLoader.getSystemClassLoader());
        }

        void stop(Object stream) {
            try {
                if (stop != null) {
                    stop.invoke(stream);
                }
            } catch (Throwable t) {
                LagFinder.LOG.warn("LagFinder could not stop the Flight Recorder cleanly", t);
            }
            try {
                close.invoke(stream);
            } catch (Throwable t) {
                LagFinder.LOG.warn("LagFinder could not close the Flight Recorder", t);
            }
        }
    }

    // ---------------------------------------------------------------- Without a Flight Recorder

    /** Samples the scanned thread's stack, putting the bytes it allocated since the last sample down to it. */
    private void sampleStacks() {
        com.sun.management.ThreadMXBean threads;
        try {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!(bean instanceof com.sun.management.ThreadMXBean)
                || !((com.sun.management.ThreadMXBean) bean).isThreadAllocatedMemorySupported()) {
                note = (note == null ? "" : note + ", ") + "no allocation counter";
                return;
            }
            threads = (com.sun.management.ThreadMXBean) bean;
            if (!threads.isThreadAllocatedMemoryEnabled()) {
                threads.setThreadAllocatedMemoryEnabled(true);
            }
        } catch (Throwable t) {
            note = (note == null ? "" : note + ", ") + t.getClass()
                .getSimpleName();
            return;
        }
        long last = threads.getThreadAllocatedBytes(targetId);
        while (running) {
            LockSupport.parkNanos(STACK_INTERVAL_MS * 1_000_000L);
            if (!running) {
                break;
            }
            try {
                long now = threads.getThreadAllocatedBytes(targetId);
                long weight = now - last;
                last = now;
                if (weight <= 0) {
                    continue;
                }
                StackTraceElement[] stack = target.getStackTrace();
                if (stack.length == 0) {
                    continue;
                }
                int at = 0;
                while (at < stack.length - 1 && isPlumbing(stack[at].getClassName())) {
                    at++;
                }
                if (isPlumbing(stack[at].getClassName())) {
                    at = 0;
                }
                String via = at == 0 ? "" : stack[at - 1].getClassName() + "." + stack[at - 1].getMethodName();
                record(AllocationProfile.UNKNOWN_TYPE, stack[at].getClassName(), stack[at].getMethodName(),
                    stack[at].getLineNumber(), via, weight, true, System.currentTimeMillis());
            } catch (Throwable t) {
                LagFinder.LOG.warn("LagFinder allocation sampler stopped", t);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- Adding up

    private static final class Live {

        final String name;
        long bytes, targetBytes;
        int samples, targetSamples;
        final float[] timeline, targetTimeline;

        Live(String name, int buckets) {
            this.name = name;
            this.timeline = new float[buckets];
            this.targetTimeline = new float[buckets];
        }

        void add(long weight, boolean target, int bucket) {
            float mb = weight / 1048576f;
            bytes += weight;
            samples++;
            timeline[bucket] += mb;
            if (target) {
                targetBytes += weight;
                targetSamples++;
                targetTimeline[bucket] += mb;
            }
        }
    }

    private static final class LiveSite {

        final String type, creator, method, via;
        final int line;
        long bytes, targetBytes;
        int samples, targetSamples;

        LiveSite(String type, String creator, String method, int line, String via) {
            this.type = type;
            this.creator = creator;
            this.method = method;
            this.line = line;
            this.via = via;
        }
    }

    private synchronized void record(String type, String creator, String method, int line, String via, long weight,
        boolean isTarget, long epochMillis) {
        int bucket = (int) Math.max(0, Math.min(buckets - 1, (epochMillis - startMillis) / bucketMillis));
        float mb = weight / 1048576f;
        bytes += weight;
        samples++;
        allMb[bucket] += mb;
        if (isTarget) {
            targetBytes += weight;
            targetSamples++;
            targetMb[bucket] += mb;
        }
        live(types, type).add(weight, isTarget, bucket);
        live(creators, creator).add(weight, isTarget, bucket);
        String key = type + '\u0000' + creator + '\u0000' + method + '\u0000' + line + '\u0000' + via;
        LiveSite site = sites.get(key);
        if (site == null) {
            site = new LiveSite(type, creator, method, line, via);
            sites.put(key, site);
        }
        site.bytes += weight;
        site.samples++;
        if (isTarget) {
            site.targetBytes += weight;
            site.targetSamples++;
        }
    }

    private Live live(Map<String, Live> map, String name) {
        Live live = map.get(name);
        if (live == null) {
            live = new Live(name, buckets);
            map.put(name, live);
        }
        return live;
    }

    // ---------------------------------------------------------------- The result

    /** What was gathered, for the report. Call after {@link #stop}. */
    public synchronized AllocationProfile build() {
        AllocationProfile profile = new AllocationProfile();
        profile.source = source;
        profile.thread = target.getName();
        profile.note = note;
        profile.samples = samples;
        profile.targetSamples = targetSamples;
        profile.bytes = bytes;
        profile.targetBytes = targetBytes;
        profile.allMb = allMb.clone();
        profile.targetMb = targetMb.clone();

        Map<String, Integer> modIds = new HashMap<>();
        Map<String, Integer> typeIds = keep(types, profile.types, profile, modIds, true);
        Map<String, Integer> creatorIds = keep(creators, profile.creators, profile, modIds, false);

        List<LiveSite> all = new ArrayList<>(sites.values());
        all.sort(Comparator.comparingLong((LiveSite site) -> site.bytes)
            .reversed());
        for (LiveSite live : all) {
            if (profile.sites.size() >= MAX_SITES) {
                break;
            }
            Integer type = typeIds.get(live.type), creator = creatorIds.get(live.creator);
            if (type == null || creator == null) {
                continue;
            }
            AllocationProfile.Site site = new AllocationProfile.Site();
            site.type = type;
            site.creator = creator;
            site.method = live.method;
            site.line = live.line;
            site.via = live.via;
            site.bytes = live.bytes;
            site.targetBytes = live.targetBytes;
            site.samples = live.samples;
            site.targetSamples = live.targetSamples;
            profile.sites.add(site);
        }
        return profile;
    }

    /** Puts the biggest types (or creating classes) in the report, the biggest with their timeline; their indexes. */
    private static Map<String, Integer> keep(Map<String, Live> lives, List<AllocationProfile.Group> into,
        AllocationProfile profile, Map<String, Integer> modIds, boolean isType) {
        List<Live> all = new ArrayList<>(lives.values());
        all.sort(Comparator.comparingLong((Live live) -> live.bytes)
            .reversed());
        // The scanned thread's biggest keep their timeline too, though they may be small among all threads.
        List<Live> byTarget = new ArrayList<>(all);
        byTarget.sort(Comparator.comparingLong((Live live) -> live.targetBytes)
            .reversed());
        java.util.Set<Live> timelines = new java.util.HashSet<>();
        for (int i = 0; i < all.size() && i < MAX_TIMELINES; i++) {
            timelines.add(all.get(i));
            timelines.add(byTarget.get(i));
        }
        Map<String, Integer> ids = new HashMap<>();
        for (int i = 0; i < all.size() && i < MAX_GROUPS; i++) {
            Live live = all.get(i);
            AllocationProfile.Group group = new AllocationProfile.Group();
            group.name = live.name;
            group.mod = modId(modOf(live.name, isType), profile, modIds);
            group.bytes = live.bytes;
            group.targetBytes = live.targetBytes;
            group.samples = live.samples;
            group.targetSamples = live.targetSamples;
            if (timelines.contains(live)) {
                group.timeline = live.timeline.clone();
                group.targetTimeline = live.targetTimeline.clone();
            }
            ids.put(live.name, into.size());
            into.add(group);
        }
        return ids;
    }

    private static String modOf(String name, boolean isType) {
        if (AllocationProfile.UNKNOWN_TYPE.equals(name)) {
            return Describer.UNKNOWN_MOD;
        }
        if (isType) {
            return HeapWatcher.modOf(name);
        }
        try {
            return ModResolver.modOfClassName(name);
        } catch (Throwable t) {
            return Describer.UNKNOWN_MOD;
        }
    }

    private static int modId(String mod, AllocationProfile profile, Map<String, Integer> modIds) {
        Integer id = modIds.get(mod);
        if (id == null) {
            id = profile.mods.size();
            modIds.put(mod, id);
            profile.mods.add(mod);
        }
        return id;
    }
}
