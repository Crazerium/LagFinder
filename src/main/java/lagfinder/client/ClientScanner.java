package lagfinder.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

import lagfinder.Config;
import lagfinder.LagFinder;
import lagfinder.client.gui.GuiLagFinder;
import lagfinder.core.Describer;
import lagfinder.core.LagFilter;
import lagfinder.core.ProfileSession;
import lagfinder.core.Report;
import lagfinder.core.ReportFiles;
import lagfinder.core.ReportMerge;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;
import lagfinder.core.ScanSide;
import lagfinder.network.Net;

/**
 * The client's side of scanning: asks the server for tick scans and follows them, scans this client's own thread
 * (its methods, objects or allocations) frame by frame, or both at once, puts the server's report together from its
 * parts, adds the two reports of a scan of both sides up into one, and keeps the last report for the window.
 */
public final class ClientScanner {

    public static final ClientScanner INSTANCE = new ClientScanner();

    public enum Phase {
        /** Nothing running. */
        IDLE,
        /** Asked the server, no answer yet. */
        REQUESTED,
        /** Measuring. */
        RUNNING,
        /** Measured; the report is being made or received. */
        RECEIVING
    }

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    private boolean serverAllowed, teleportAllowed;
    private int serverState = Net.Status.IDLE;
    /** How far the server's scan and this client's scan are: either or both run, as the side asked. */
    private Phase serverPhase = Phase.IDLE, clientPhase = Phase.IDLE;
    private ScanSide side = ScanSide.SERVER;
    /** What the report of a tick scan leaves out, null for nothing. */
    private LagFilter filter;
    /** What the scan running (or the last one) is for: the lag of the things, or the methods, objects... */
    private ScanFocus focus = ScanFocus.LAG;
    private float progress;
    private int secondsLeft;
    private long requestedAt;
    private int requestedSeconds;
    private long statusAt;
    /** Asks the server for its state once the player is in the world. */
    private volatile boolean queryPending;

    /** This client's scan running, and when its frame being measured started (0 before the first). */
    private ProfileSession frameSession;
    private long frameStart;

    /** Parts of a report taken at most: about 64 MB, far more than any report; a claim of more is refused. */
    private static final int MAX_PARTS = (ReportFiles.MAX_BYTES + Net.PART_SIZE - 1) / Net.PART_SIZE;
    private int partsId = -1;
    private byte[][] parts;
    private int partsReceived;
    private int partsBytes;
    private boolean reading;
    private boolean queued;
    private int serverMaxSeconds;
    private boolean serverPaused;
    private boolean cancelling;
    private int serverGeneration;

    private Report report;
    /** Both sides scanned: the report of the side that ended first, until the other's comes to add up with it. */
    private Report held;
    private byte[] heldData;
    /** The two reports of a scan of both sides are being added up (on another thread). */
    private boolean merging;
    /** The server of the scan runs in this same Java (single player): what is of the whole Java is counted once. */
    private boolean sameJvm;
    private boolean unseen;
    private String message;
    private Object[] messageArgs = new Object[0];
    private long messageAt;

    private ClientScanner() {}

    public void schedule(Runnable task) {
        tasks.add(task);
    }

    /** Whether the server has LagFinder: it took the mod's messages when the player joined. */
    public boolean serverHasMod() {
        ClientPacketListener connection = Minecraft.getInstance()
            .getConnection();
        return connection != null && connection.hasChannel(Net.Query.TYPE);
    }

    public boolean serverAllowed() {
        return serverHasMod() && serverAllowed;
    }

    /** Whether the server can list its saved reports for this player. */
    public boolean serverArchiveAvailable() {
        ClientPacketListener connection = Minecraft.getInstance()
            .getConnection();
        return serverAllowed() && connection != null && connection.hasChannel(Net.ArchiveRequest.TYPE);
    }

    public boolean teleportAllowed() {
        return serverHasMod() && teleportAllowed;
    }

    /** Someone else's scan runs on the server. */
    public boolean serverBusy() {
        return serverState == Net.Status.BUSY;
    }

    /** The state of the scan as a whole: running while either side measures. */
    public Phase phase() {
        if (serverPhase == Phase.RUNNING || clientPhase == Phase.RUNNING) {
            return Phase.RUNNING;
        }
        if (serverPhase == Phase.REQUESTED) {
            return Phase.REQUESTED;
        }
        if (serverPhase == Phase.RECEIVING || clientPhase == Phase.RECEIVING || merging) {
            return Phase.RECEIVING;
        }
        return Phase.IDLE;
    }

    public ScanSide side() {
        return side;
    }

    public ScanFocus focus() {
        return focus;
    }

    public boolean isActive() {
        return serverPhase != Phase.IDLE || clientPhase != Phase.IDLE || merging;
    }

    /** How far along the scan is, 0 to 1; with both sides, the one further behind. */
    public float progress() {
        if (serverPhase == Phase.IDLE) {
            return clientProgress();
        }
        if (clientPhase == Phase.IDLE) {
            return serverProgress();
        }
        return Math.min(serverProgress(), clientProgress());
    }

    private float serverProgress() {
        if (serverPhase == Phase.RECEIVING) {
            return parts == null ? 1f : partsReceived / (float) parts.length;
        }
        if (serverPhase == Phase.RUNNING && requestedSeconds > 0 && !serverPaused) {
            float since = (System.currentTimeMillis() - statusAt) / 1000f;
            return Math.min(1f, progress + Math.min(1.5f, since) / requestedSeconds);
        }
        return progress;
    }

    private float clientProgress() {
        return frameSession != null ? frameSession.progress() : clientPhase == Phase.RECEIVING ? 1f : 0f;
    }

    /** Seconds until the scan ends; with both sides, until the later one does. */
    public int secondsLeft() {
        int left = serverPhase != Phase.IDLE ? secondsLeft : 0;
        if (frameSession != null) {
            left = Math.max(left, (int) Math.ceil(frameSession.nanosLeft() / 1e9));
        }
        return left;
    }

    public Report report() {
        return report;
    }

    public boolean hasUnseenReport() {
        return unseen && report != null;
    }

    public void markSeen() {
        unseen = false;
    }

    /** Opens an older report from disk as the current one. */
    public void setReport(Report report) {
        this.report = report;
    }

    public String message() {
        return message != null && System.currentTimeMillis() - messageAt < 6000 ? message : null;
    }

    public String messageText() {
        String key = message();
        return key == null ? null : I18n.get(key, messageArgs);
    }

    public int serverMaxSeconds() {
        return serverMaxSeconds;
    }

    private void tell(String key, Object... args) {
        message = key == null || key.isEmpty() ? null : key;
        messageArgs = args;
        messageAt = System.currentTimeMillis();
    }

    public void sendToServer(CustomPacketPayload payload) {
        if (serverHasMod()) {
            PacketDistributor.sendToServer(payload);
        }
    }

    /** Asks the server for its state again (the window opened). */
    public void query() {
        sendToServer(new Net.Query(Net.PROTOCOL));
    }

    /**
     * Starts a scan on the side asked: the server's ticks, this client's frames, or both at once. Of a scan of the
     * server's ticks, the report leaves out what the filter says (null: nothing is left out), and a {@code light} one
     * measures one tick in a few. This client is only scanned for its methods, objects or allocations.
     */
    public void start(ScanSide side, int seconds, ScanFocus focus, LagFilter filter, boolean light) {
        if (isActive()) {
            return;
        }
        if (focus == ScanFocus.LAG || focus == ScanFocus.STEADY) {
            side = ScanSide.SERVER;
        }
        if (side.server() && !serverAllowed()) {
            tell(serverHasMod() ? "lagfinder.message.not_allowed" : "lagfinder.message.no_server_mod");
            return;
        }
        if (side.server() && cancelling) {
            return;
        }
        this.side = side;
        this.filter = filter;
        this.focus = focus;
        progress = 0;
        held = null;
        heldData = null;
        sameJvm = Minecraft.getInstance()
            .isSingleplayer();
        tell(null);
        if (side.server()) {
            serverPhase = Phase.REQUESTED;
            queued = false;
            serverPaused = false;
            requestedAt = System.currentTimeMillis();
            requestedSeconds = seconds;
            statusAt = requestedAt;
            secondsLeft = seconds;
            sendToServer(new Net.StartScan(seconds, focus, light));
        }
        if (side.client()) {
            frameSession = new ProfileSession(
                ScanMode.FPS,
                Thread.currentThread(),
                seconds * 1_000_000_000L,
                Config.spikeFpsMs,
                new Describer());
            frameStart = 0;
            frameSession.startFor(focus);
            ProfileSession.setClient(frameSession);
            clientPhase = Phase.RUNNING;
        }
    }

    /** Cancels the scan, on both sides when both run. */
    public void cancel() {
        if (frameSession != null) {
            stopFrames(true);
            clientPhase = Phase.IDLE;
            tell("lagfinder.message.cancelled");
        }
        if (serverPhase == Phase.REQUESTED || serverPhase == Phase.RUNNING) {
            sendToServer(new Net.CancelScan());
            serverPhase = Phase.IDLE;
            cancelling = true;
            parts = null;
            partsId = -1;
        }
    }

    /** Stops this client's scan; when it ended (not {@code discard}), its report is still to be made from it. */
    private void stopFrames(boolean discard) {
        if (frameSession != null) {
            if (discard) {
                frameSession.discard();
            } else {
                frameSession.close();
            }
        }
        ProfileSession.setClient(null);
        frameSession = null;
        frameStart = 0;
    }

    /** The server's state arrived (any thread). */
    public void onStatus(Net.Status status) {
        schedule(() -> {
            if (status.state == Net.Status.QUEUED) {
                if (serverPhase == Phase.REQUESTED) {
                    queued = true;
                }
                return;
            }
            serverAllowed = status.allowed;
            teleportAllowed = status.teleportAllowed;
            serverState = status.state;
            serverPaused = status.paused;
            if (status.maxSeconds > 0) {
                serverMaxSeconds = status.maxSeconds;
            }
            if (!status.message.isEmpty()) {
                tell(status.message);
            }
            if (!side.server()) {
                return;
            }
            if (cancelling) {
                if (status.state == Net.Status.RUNNING || status.state == Net.Status.SENDING) {
                    return;
                }
                cancelling = false;
            }
            switch (status.state) {
                case Net.Status.RUNNING:
                    if (serverPhase == Phase.REQUESTED && status.totalSeconds > 0
                        && status.totalSeconds < requestedSeconds) {
                        tell("lagfinder.message.clamped", GuiLagFinder.formatTime(status.totalSeconds));
                    }
                    if (status.totalSeconds > 0) {
                        requestedSeconds = status.totalSeconds;
                    }
                    serverPhase = Phase.RUNNING;
                    progress = status.progress;
                    secondsLeft = status.secondsLeft;
                    statusAt = System.currentTimeMillis();
                    break;
                case Net.Status.SENDING:
                    if (serverPhase != Phase.IDLE) {
                        serverPhase = Phase.RECEIVING;
                    }
                    break;
                default:
                    if (serverPhase == Phase.REQUESTED || serverPhase == Phase.RUNNING) {
                        serverPhase = Phase.IDLE;
                    } else if (serverPhase == Phase.RECEIVING && !reading) {
                        serverPhase = Phase.IDLE;
                        parts = null;
                        partsId = -1;
                    }
            }
        });
    }

    /** A part of a report arrived (any thread). */
    public void onReportPart(Net.ReportPart part) {
        schedule(() -> {
            if (!side.server() || serverPhase == Phase.IDLE
                || cancelling
                || reading
                || part.total > MAX_PARTS
                || !part.valid()) {
                return;
            }
            if (part.id != partsId || parts == null || parts.length != part.total) {
                partsId = part.id;
                parts = new byte[part.total][];
                partsReceived = 0;
                partsBytes = 0;
            }
            if (parts[part.index] != null) {
                return;
            }
            if (partsBytes > ReportFiles.MAX_BYTES - part.data.length) {
                parts = null;
                serverPhase = Phase.IDLE;
                tell("lagfinder.message.failed");
                return;
            }
            partsBytes += part.data.length;
            parts[part.index] = part.data;
            partsReceived++;
            serverPhase = Phase.RECEIVING;
            if (partsReceived == parts.length) {
                byte[][] all = parts;
                parts = null;
                partsId = -1;
                ByteArrayOutputStream joined = new ByteArrayOutputStream();
                for (byte[] bytes : all) {
                    joined.write(bytes, 0, bytes.length);
                }
                byte[] received = joined.toByteArray();
                LagFilter leftOut = filter;
                int readFor = serverGeneration;
                reading = true;
                Thread reader = new Thread(() -> {
                    try {
                        Report read = Report.read(new ByteArrayInputStream(received));
                        byte[] data = received;
                        if (leftOut != null && read.mode == ScanMode.TPS && read.focus == ScanFocus.LAG) {
                            leftOut.apply(read);
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            read.write(bytes);
                            data = bytes.toByteArray();
                        }
                        byte[] kept = data;
                        schedule(() -> {
                            if (readFor != serverGeneration) {
                                publish(read, kept);
                                return;
                            }
                            reading = false;
                            serverPhase = Phase.IDLE;
                            finished(read, kept);
                        });
                    } catch (Throwable t) {
                        LagFinder.LOG.error("Could not read the scan's report", t);
                        schedule(() -> {
                            if (readFor != serverGeneration) {
                                return;
                            }
                            reading = false;
                            serverPhase = Phase.IDLE;
                            tell("lagfinder.message.failed");
                        });
                    }
                }, "LagFinder report reader");
                reader.setDaemon(true);
                reader.start();
            }
        });
    }

    /** A report of the scan is ready; its side's phase is already back to idle. */
    private void finished(Report finished, byte[] data) {
        if (side == ScanSide.BOTH) {
            if (held == null && isActive()) {
                held = finished;
                heldData = data;
                return;
            }
            if (held != null) {
                Report server = finished.mode == ScanMode.TPS ? finished : held;
                Report client = server == finished ? held : finished;
                held = null;
                heldData = null;
                merge(server, client);
                return;
            }
        }
        publish(finished, data);
    }

    /** Adds the server's and the client's reports up into one, off the game's thread, and keeps it. */
    private void merge(Report server, Report client) {
        merging = true;
        boolean oneJvm = sameJvm;
        Thread merger = new Thread(() -> {
            try {
                Report merged = ReportMerge.merge(server, client, oneJvm);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                merged.write(bytes);
                byte[] data = bytes.toByteArray();
                schedule(() -> {
                    merging = false;
                    publish(merged, data);
                });
            } catch (Throwable t) {
                LagFinder.LOG.error("Could not add up the server's and the client's reports", t);
                schedule(() -> {
                    merging = false;
                    tell("lagfinder.message.failed");
                });
            }
        }, "LagFinder report merger");
        merger.setDaemon(true);
        merger.start();
    }

    /** The report of the scan is ready: kept for the window, saved, and the player told. */
    private void publish(Report finished, byte[] data) {
        report = finished;
        unseen = true;
        GuiLagFinder.forgetReopen();
        ReportStore.save(finished, data);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !(mc.screen instanceof GuiLagFinder)) {
            mc.player.displayClientMessage(
                Component.literal("[LagFinder] ")
                    .append(
                        Component.translatable("lagfinder.chat.ready", KeyHandler.keyName())
                            .withStyle(ChatFormatting.AQUA)),
                false);
        }
    }

    void onClientTick() {
        ClientReports.INSTANCE.tick();
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LagFinder.LOG.error("LagFinder task failed", t);
            }
        }
        if (held != null && !isActive()) {
            Report alone = held;
            byte[] data = heldData;
            held = null;
            heldData = null;
            publish(alone, data);
        }
        if (queryPending && Minecraft.getInstance().player != null) {
            queryPending = false;
            query();
        }
        if (serverPhase == Phase.REQUESTED && !queued && System.currentTimeMillis() - requestedAt > 15_000) {
            serverPhase = Phase.IDLE;
            tell("lagfinder.message.no_answer");
        }
    }

    /** A frame starts: the one before it ends, and this client's scan ends when its time is up. */
    void onFrameStart() {
        if (frameSession == null) {
            return;
        }
        long now = System.nanoTime();
        if (frameStart != 0) {
            frameSession.endPeriod(frameStart, now - frameStart);
        }
        if (frameSession.isDone(now)) {
            ProfileSession session = frameSession;
            stopFrames(false);
            clientPhase = Phase.RECEIVING;
            String source = sourceName();
            try {
                session.prepareReport();
                Thread writer = new Thread(() -> {
                    try {
                        Report built = session.buildReport(source);
                        session.completeReport(built);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        built.write(bytes);
                        byte[] data = bytes.toByteArray();
                        schedule(() -> {
                            clientPhase = Phase.IDLE;
                            finished(built, data);
                        });
                    } catch (Throwable t) {
                        LagFinder.LOG.error("Could not write the client scan's report", t);
                        session.discard();
                        schedule(() -> {
                            clientPhase = Phase.IDLE;
                            tell("lagfinder.message.failed");
                        });
                    }
                }, "LagFinder report writer");
                writer.setDaemon(true);
                writer.start();
            } catch (Throwable t) {
                LagFinder.LOG.error("Could not build the client scan's report", t);
                session.discard();
                clientPhase = Phase.IDLE;
                tell("lagfinder.message.failed");
            }
            return;
        }
        frameStart = System.nanoTime();
        frameSession.addOverhead(frameStart - now);
        frameSession.beginPeriod(frameStart);
    }

    /** Where this client's scan was made: the single player world's name, or the server's. */
    private static String sourceName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            return mc.getSingleplayerServer()
                .getWorldData()
                .getLevelName();
        }
        ServerData server = mc.getCurrentServer();
        return server != null ? server.name : "";
    }

    void onConnect() {
        queryPending = true;
        schedule(this::resetServer);
    }

    void onDisconnect() {
        queryPending = false;
        schedule(() -> {
            resetServer();
            if (frameSession != null) {
                stopFrames(true);
                clientPhase = Phase.IDLE;
            }
        });
        Highlighter.INSTANCE.clear();
    }

    private void resetServer() {
        serverGeneration++;
        ClientReports.INSTANCE.reset();
        reading = false;
        cancelling = false;
        serverPaused = false;
        serverAllowed = false;
        teleportAllowed = false;
        serverState = Net.Status.IDLE;
        serverMaxSeconds = 0;
        parts = null;
        partsId = -1;
        queued = false;
        serverPhase = Phase.IDLE;
    }
}
