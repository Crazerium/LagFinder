package lagfinder.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import lagfinder.Config;
import lagfinder.LagFinder;
import lagfinder.client.gui.GuiLagFinder;
import lagfinder.core.ProfileSession;
import lagfinder.core.Report;
import lagfinder.core.ReportMerge;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;
import lagfinder.core.ScanSide;
import lagfinder.network.Net;

/**
 * The client's side of scanning: runs frame scans itself, asks the server for tick scans and follows them, or both at
 * once, puts the server's report together from its parts, adds the two reports of a scan of both sides up into one,
 * and keeps the last report for the window.
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

    // What the server said last.
    private volatile boolean serverHasMod;
    private boolean serverAllowed, teleportAllowed;
    private int serverState = Net.Status.IDLE;

    /** How far the server's scan and this client's scan are: either or both run, as the side asked. */
    private Phase serverPhase = Phase.IDLE, clientPhase = Phase.IDLE;
    private ScanSide side = ScanSide.SERVER;
    private ScanFocus focus = ScanFocus.LAG;
    private float progress;
    private int secondsLeft;
    private long requestedAt;
    /** Seconds the tick scan was asked for, and when the server last told how far it is. */
    private int requestedSeconds;
    private long statusAt;
    /** Asks the server whether it has the mod once the player is in the world. */
    private volatile boolean queryPending;

    private ProfileSession frameSession;
    private long frameStart;

    private int partsId = -1;
    private byte[][] parts;
    private int partsReceived;

    private Report report;
    /** Both sides scanned: the report of the side that ended first, until the other's comes to add up with it. */
    private Report held;
    private byte[] heldData;
    /** The two reports of a scan of both sides are being added up (on another thread). */
    private boolean merging;
    /** The server of the scan runs in this same Java (single player): what is of the whole Java is counted once. */
    private boolean sameJvm;
    /** A report arrived the player hasn't opened yet. */
    private boolean unseen;
    private String message;
    private long messageAt;

    private ClientScanner() {}

    public void schedule(Runnable task) {
        tasks.add(task);
    }

    public boolean serverHasMod() {
        return serverHasMod;
    }

    public boolean serverAllowed() {
        return serverHasMod && serverAllowed;
    }

    public boolean teleportAllowed() {
        return serverHasMod && teleportAllowed;
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
        if (serverPhase == Phase.RUNNING && requestedSeconds > 0) {
            // Moves on smoothly between the server's reports, never far ahead of them.
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
            left = Math.max(
                left,
                (int) Math.ceil(
                    Math.max(0, frameSession.durationNanos - (System.nanoTime() - frameSession.startNanos)) / 1e9));
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

    /** Something to tell the player (a translation key), shown for a few seconds; null when there is none. */
    public String message() {
        return message != null && System.currentTimeMillis() - messageAt < 6000 ? message : null;
    }

    private void tell(String key) {
        message = key == null || key.isEmpty() ? null : key;
        messageAt = System.currentTimeMillis();
    }

    /** Asks the server to ask for its state again (the window opened). */
    public void query() {
        if (serverHasMod) {
            Net.sendToServer(new Net.Query());
        }
    }

    /** Starts a scan on the side asked: the server's ticks, this client's frames, or both at once. */
    public void start(ScanSide side, ScanFocus focus, int seconds) {
        if (isActive()) {
            return;
        }
        if (side.server() && !serverAllowed()) {
            tell(serverHasMod ? "lagfinder.message.not_allowed" : "lagfinder.message.no_server_mod");
            return;
        }
        this.side = side;
        this.focus = focus;
        progress = 0;
        held = null;
        heldData = null;
        sameJvm = Minecraft.getMinecraft()
            .isSingleplayer();
        tell(null);
        if (side.server()) {
            serverPhase = Phase.REQUESTED;
            requestedAt = System.currentTimeMillis();
            requestedSeconds = seconds;
            statusAt = requestedAt;
            secondsLeft = seconds;
            Net.sendToServer(new Net.StartScan(seconds, focus));
        }
        if (side.client()) {
            frameSession = new ProfileSession(
                ScanMode.FPS,
                Thread.currentThread(),
                seconds * 1_000_000_000L,
                Config.spikeFpsMs,
                new ClientDescriber());
            frameStart = 0;
            frameSession.startFor(focus);
            ProfileSession.setClient(frameSession);
            clientPhase = Phase.RUNNING;
        }
    }

    /** Cancels the scan, on both sides when both run. */
    public void cancel() {
        // A side whose report is already on the way is left to finish.
        if (frameSession != null) {
            stopFrames();
            clientPhase = Phase.IDLE;
            tell("lagfinder.message.cancelled");
        }
        if (serverPhase == Phase.REQUESTED || serverPhase == Phase.RUNNING) {
            // The server says it was cancelled.
            Net.sendToServer(new Net.CancelScan());
            serverPhase = Phase.IDLE;
        }
    }

    /** Stops the frame scan running, all of it: cancelled, or the game left. */
    private void stopFrames() {
        stopFrames(true);
    }

    /** Stops the frame scan running; when it ended (not {@code discard}), its report is still to be made from it. */
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
            serverHasMod = true;
            serverAllowed = status.allowed;
            teleportAllowed = status.teleportAllowed;
            serverState = status.state;
            if (!status.message.isEmpty()) {
                tell(status.message);
            }
            if (!side.server()) {
                return;
            }
            switch (status.state) {
                case Net.Status.RUNNING:
                    serverPhase = Phase.RUNNING;
                    progress = status.progress;
                    secondsLeft = status.secondsLeft;
                    statusAt = System.currentTimeMillis();
                    break;
                case Net.Status.SENDING:
                    serverPhase = Phase.RECEIVING;
                    break;
                default:
                    if (serverPhase == Phase.REQUESTED || serverPhase == Phase.RUNNING) {
                        // Refused, cancelled, or ended without a report.
                        serverPhase = Phase.IDLE;
                    }
            }
        });
    }

    /** A part of a report arrived (any thread). */
    public void onReportPart(Net.ReportPart part) {
        schedule(() -> {
            if (part.id != partsId || parts == null || parts.length != part.total) {
                partsId = part.id;
                parts = new byte[Math.max(1, part.total)][];
                partsReceived = 0;
            }
            if (part.index < 0 || part.index >= parts.length || parts[part.index] != null) {
                return;
            }
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
                byte[] data = joined.toByteArray();
                Thread reader = new Thread(() -> {
                    try {
                        Report read = Report.read(new ByteArrayInputStream(data));
                        schedule(() -> {
                            serverPhase = Phase.IDLE;
                            finished(read, data);
                        });
                    } catch (Throwable t) {
                        LagFinder.LOG.error("Could not read the scan's report", t);
                        schedule(() -> {
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
                // Waits for the other side's, to add them up.
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
        ReportStore.save(finished, data);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && !(mc.currentScreen instanceof GuiLagFinder)) {
            ChatComponentTranslation text = new ChatComponentTranslation("lagfinder.chat.ready", KeyHandler.keyName());
            text.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.AQUA));
            mc.thePlayer.addChatMessage(new ChatComponentText("[LagFinder] ").appendSibling(text));
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LagFinder.LOG.error("LagFinder task failed", t);
            }
        }
        if (held != null && !isActive()) {
            // The other side ended without a report (cancelled, failed): this one is all there is.
            Report alone = held;
            byte[] data = heldData;
            held = null;
            heldData = null;
            publish(alone, data);
        }
        if (queryPending && Minecraft.getMinecraft().thePlayer != null) {
            // Servers with the mod answer; others never do.
            queryPending = false;
            Net.sendToServer(new Net.Query());
        }
        if (serverPhase == Phase.REQUESTED && System.currentTimeMillis() - requestedAt > 10_000) {
            // The server never answered.
            serverPhase = Phase.IDLE;
            tell("lagfinder.message.no_answer");
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || frameSession == null) {
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
            try {
                Minecraft mc = Minecraft.getMinecraft();
                String source = mc.isSingleplayer() && mc.getIntegratedServer() != null ? mc.getIntegratedServer()
                    .getWorldName() : mc.func_147104_D() != null ? mc.func_147104_D().serverName : "";
                Report built = session.buildReport(source);
                // Finishing the allocations waits for the Flight Recorder: not on the game's thread.
                Thread writer = new Thread(() -> {
                    try {
                        session.completeReport(built);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        built.write(bytes);
                        byte[] data = bytes.toByteArray();
                        schedule(() -> {
                            clientPhase = Phase.IDLE;
                            finished(built, data);
                        });
                    } catch (Throwable t) {
                        LagFinder.LOG.error("Could not write the frame scan's report", t);
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
                LagFinder.LOG.error("Could not build the frame scan's report", t);
                session.discard();
                clientPhase = Phase.IDLE;
                tell("lagfinder.message.failed");
            }
            return;
        }
        // The time taken to add up the last frame is left out of the next one.
        frameStart = System.nanoTime();
        frameSession.beginPeriod();
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        serverHasMod = false;
        queryPending = true;
        schedule(this::resetServer);
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        serverHasMod = false;
        queryPending = false;
        schedule(() -> {
            resetServer();
            if (frameSession != null) {
                stopFrames();
                clientPhase = Phase.IDLE;
            }
        });
    }

    private void resetServer() {
        serverAllowed = false;
        teleportAllowed = false;
        serverState = Net.Status.IDLE;
        parts = null;
        partsId = -1;
        serverPhase = Phase.IDLE;
    }
}
