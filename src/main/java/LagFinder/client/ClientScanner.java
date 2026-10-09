package LagFinder.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import LagFinder.Config;
import LagFinder.LagFinder;
import LagFinder.client.gui.GuiLagFinder;
import LagFinder.core.ProfileSession;
import LagFinder.core.Report;
import LagFinder.core.ScanMode;
import LagFinder.network.Net;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * The client's side of scanning: runs frame scans itself, asks the server for tick scans and follows them, puts the
 * report together from its parts, and keeps the last report for the window.
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

    private Phase phase = Phase.IDLE;
    private ScanMode mode = ScanMode.TPS;
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

    public Phase phase() {
        return phase;
    }

    public ScanMode mode() {
        return mode;
    }

    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    public float progress() {
        if (phase == Phase.RECEIVING) {
            return parts == null ? 1f : partsReceived / (float) parts.length;
        }
        if (frameSession != null) {
            return frameSession.progress();
        }
        if (phase == Phase.RUNNING && requestedSeconds > 0) {
            // Moves on smoothly between the server's reports, never far ahead of them.
            float since = (System.currentTimeMillis() - statusAt) / 1000f;
            return Math.min(1f, progress + Math.min(1.5f, since) / requestedSeconds);
        }
        return progress;
    }

    public int secondsLeft() {
        if (frameSession != null) {
            return (int) Math.ceil(
                Math.max(0, frameSession.durationNanos - (System.nanoTime() - frameSession.startNanos)) / 1e9);
        }
        return secondsLeft;
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

    public void start(ScanMode mode, int seconds) {
        if (isActive()) {
            return;
        }
        this.mode = mode;
        progress = 0;
        tell(null);
        if (mode == ScanMode.TPS) {
            if (!serverAllowed()) {
                tell(serverHasMod ? "lagfinder.message.not_allowed" : "lagfinder.message.no_server_mod");
                return;
            }
            phase = Phase.REQUESTED;
            requestedAt = System.currentTimeMillis();
            requestedSeconds = seconds;
            statusAt = requestedAt;
            secondsLeft = seconds;
            Net.sendToServer(new Net.StartScan(seconds));
        } else {
            frameSession = new ProfileSession(
                ScanMode.FPS,
                Thread.currentThread(),
                seconds * 1_000_000_000L,
                Config.spikeFpsMs,
                new ClientDescriber());
            frameStart = 0;
            ProfileSession.setClient(frameSession);
            phase = Phase.RUNNING;
        }
    }

    public void cancel() {
        if (frameSession != null) {
            stopFrames();
            phase = Phase.IDLE;
            tell("lagfinder.message.cancelled");
        } else if (phase != Phase.IDLE) {
            Net.sendToServer(new Net.CancelScan());
            phase = Phase.IDLE;
        }
    }

    private void stopFrames() {
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
            if (mode != ScanMode.TPS || frameSession != null) {
                return;
            }
            switch (status.state) {
                case Net.Status.RUNNING:
                    phase = Phase.RUNNING;
                    progress = status.progress;
                    secondsLeft = status.secondsLeft;
                    statusAt = System.currentTimeMillis();
                    break;
                case Net.Status.SENDING:
                    phase = Phase.RECEIVING;
                    break;
                default:
                    if (phase == Phase.REQUESTED || phase == Phase.RUNNING) {
                        // Refused, cancelled, or ended without a report.
                        phase = Phase.IDLE;
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
            phase = Phase.RECEIVING;
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
                        schedule(() -> finished(read, data));
                    } catch (Throwable t) {
                        LagFinder.LOG.error("Could not read the scan's report", t);
                        schedule(() -> {
                            phase = Phase.IDLE;
                            tell("lagfinder.message.failed");
                        });
                    }
                }, "LagFinder report reader");
                reader.setDaemon(true);
                reader.start();
            }
        });
    }

    private void finished(Report finished, byte[] data) {
        report = finished;
        unseen = true;
        phase = Phase.IDLE;
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
        if (queryPending && Minecraft.getMinecraft().thePlayer != null) {
            // Servers with the mod answer; others never do.
            queryPending = false;
            Net.sendToServer(new Net.Query());
        }
        if (phase == Phase.REQUESTED && System.currentTimeMillis() - requestedAt > 10_000) {
            // The server never answered.
            phase = Phase.IDLE;
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
            stopFrames();
            phase = Phase.RECEIVING;
            try {
                Minecraft mc = Minecraft.getMinecraft();
                String source = mc.isSingleplayer() && mc.getIntegratedServer() != null
                    ? mc.getIntegratedServer()
                        .getWorldName()
                    : mc.func_147104_D() != null ? mc.func_147104_D().serverName : "";
                Report built = session.buildReport(source);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                built.write(bytes);
                finished(built, bytes.toByteArray());
            } catch (Throwable t) {
                LagFinder.LOG.error("Could not build the frame scan's report", t);
                phase = Phase.IDLE;
                tell("lagfinder.message.failed");
            }
            return;
        }
        // The time taken to add up the last frame is left out of the next one.
        frameStart = System.nanoTime();
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
                phase = Phase.IDLE;
            }
        });
    }

    private void resetServer() {
        serverAllowed = false;
        teleportAllowed = false;
        serverState = Net.Status.IDLE;
        parts = null;
        partsId = -1;
        if (mode == ScanMode.TPS && frameSession == null) {
            phase = Phase.IDLE;
        }
    }
}
