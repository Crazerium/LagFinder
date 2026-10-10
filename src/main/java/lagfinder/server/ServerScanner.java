package lagfinder.server;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import lagfinder.Config;
import lagfinder.LagFinder;
import lagfinder.core.Category;
import lagfinder.core.Describer;
import lagfinder.core.ProfileSession;
import lagfinder.core.Report;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;
import lagfinder.network.Net;

/**
 * Runs tick scans on the server for the players allowed to: one at a time, started and cancelled by the player who
 * asked, who is told how far along it is every second and gets the report when it ends.
 */
public final class ServerScanner {

    public static final ServerScanner INSTANCE = new ServerScanner();

    /** Report parts sent a tick, so a big report doesn't flood the connection. */
    private static final int PARTS_PER_TICK = 6;

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private ProfileSession session;
    private UUID owner;
    private long tickStart;
    private long lastStatus;
    private int reportId;
    /** When each world's tick started, and what the scan itself had taken of the tick by then. */
    private final Map<World, long[]> worldStarts = new IdentityHashMap<>();

    /** A report being made (written on another thread) or sent. */
    private volatile boolean building;
    private final Queue<Net.ReportPart> outgoing = new ArrayDeque<>();
    private UUID outgoingTo;

    private ServerScanner() {}

    /** Runs the task on the server thread at the start of the next tick (before it is measured). */
    public void schedule(Runnable task) {
        tasks.add(task);
    }

    /** Whether the player may scan: the owner of a single player world always, others as operators. */
    public static boolean canScan(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return false;
        }
        if (server.isSinglePlayer() && player.getCommandSenderName()
            .equals(server.getServerOwner())) {
            return true;
        }
        return !Config.requireOp || player.canCommandSenderUseCommand(2, "lagfinder");
    }

    public static boolean canTeleport(EntityPlayerMP player) {
        return Config.allowTeleport && canScan(player);
    }

    public void start(EntityPlayerMP player, int seconds, ScanFocus focus) {
        if (!canScan(player)) {
            sendStatus(player, "lagfinder.message.not_allowed");
            return;
        }
        if (session != null || building || !outgoing.isEmpty()) {
            sendStatus(player, "lagfinder.message.busy");
            return;
        }
        seconds = Math.max(5, Math.min(Config.maxScanSeconds, seconds));
        session = new ProfileSession(
            ScanMode.TPS,
            Thread.currentThread(),
            seconds * 1_000_000_000L,
            Config.spikeTpsMs,
            new Describer());
        session.startFor(focus);
        owner = player.getUniqueID();
        tickStart = 0;
        lastStatus = 0;
        worldStarts.clear();
        ProfileSession.setServer(session);
        LagFinder.LOG.info("{} started a {} s {} tick scan", player.getCommandSenderName(), seconds, focus);
        sendStatus(player, null);
    }

    public void cancel(EntityPlayerMP player) {
        if (session != null && (player.getUniqueID()
            .equals(owner) || canScan(player))) {
            stop();
            LagFinder.LOG.info("{} cancelled the tick scan", player.getCommandSenderName());
            sendStatus(player, "lagfinder.message.cancelled");
        } else {
            sendStatus(player, null);
        }
    }

    /** Stops the scan running, all of it: cancelled, or the server stopping. */
    private void stop() {
        stop(true);
    }

    /** Stops the scan running; when it ended (not {@code discard}), its report is still to be made from it. */
    private void stop(boolean discard) {
        if (session != null) {
            if (discard) {
                session.discard();
            } else {
                session.close();
            }
        }
        ProfileSession.setServer(null);
        session = null;
        worldStarts.clear();
    }

    public void teleport(EntityPlayerMP player, Net.Teleport message) {
        if (!canTeleport(player)) {
            sendStatus(player, "lagfinder.message.no_teleport");
            return;
        }
        if (!Teleports.teleport(player, message.dim, message.x, message.y, message.z)) {
            sendStatus(player, "lagfinder.message.no_dimension");
        }
    }

    public void sendStatus(EntityPlayerMP player, String message) {
        Net.Status status = new Net.Status();
        status.allowed = canScan(player);
        status.teleportAllowed = canTeleport(player);
        boolean mine = player.getUniqueID()
            .equals(owner);
        if (session != null) {
            status.state = mine ? Net.Status.RUNNING : Net.Status.BUSY;
            status.progress = session.progress();
            status.secondsLeft = (int) Math
                .ceil(Math.max(0, session.durationNanos - (System.nanoTime() - session.startNanos)) / 1e9);
        } else if (building || !outgoing.isEmpty()) {
            status.state = mine ? Net.Status.SENDING : Net.Status.BUSY;
            status.progress = 1f;
        } else {
            status.state = Net.Status.IDLE;
        }
        status.message = message == null ? "" : message;
        Net.sendTo(status, player);
    }

    private EntityPlayerMP ownerPlayer(UUID id) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || id == null) {
            return null;
        }
        for (Object object : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) object;
            if (player.getUniqueID()
                .equals(id)) {
                return player;
            }
        }
        return null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTickStart(TickEvent.ServerTickEvent event) {
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
        sendParts();
        if (session != null) {
            tickStart = System.nanoTime();
            session.beginPeriod();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || session == null || tickStart == 0) {
            return;
        }
        long now = System.nanoTime();
        session.endPeriod(tickStart, now - tickStart);
        if (session.isDone(now)) {
            finish();
            return;
        }
        if (now - lastStatus > 1_000_000_000L) {
            lastStatus = now;
            EntityPlayerMP player = ownerPlayer(owner);
            if (player != null) {
                sendStatus(player, null);
            }
        }
        // The time taken to add up the tick (and tell how far along it is) is left out of the scan's length.
        session.addOverhead(System.nanoTime() - now);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onWorldTickStart(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.START && session != null) {
            worldStarts.put(event.world, new long[] { System.nanoTime(), session.periodOverhead() });
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onWorldTickEnd(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || session == null) {
            return;
        }
        long[] start = worldStarts.remove(event.world);
        if (start != null) {
            // Without what timing the things in it took.
            long nanos = System.nanoTime() - start[0] - (session.periodOverhead() - start[1]);
            session.record(Category.WORLDS, event.world, Math.max(0, nanos));
        }
    }

    private void finish() {
        ProfileSession finished = session;
        stop(false);
        UUID to = owner;
        String source = serverName();
        Report report;
        try {
            report = finished.buildReport(source);
        } catch (Throwable t) {
            LagFinder.LOG.error("Could not build the scan's report", t);
            finished.discard();
            EntityPlayerMP player = ownerPlayer(to);
            if (player != null) {
                sendStatus(player, "lagfinder.message.failed");
            }
            return;
        }
        building = true;
        int id = ++reportId;
        // Compressing a big report takes a moment: not on the server's thread.
        Thread writer = new Thread(() -> {
            try {
                finished.completeReport(report);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                report.write(bytes);
                byte[] data = bytes.toByteArray();
                int total = Math.max(1, (data.length + Net.PART_SIZE - 1) / Net.PART_SIZE);
                schedule(() -> {
                    outgoing.clear();
                    outgoingTo = to;
                    for (int i = 0; i < total; i++) {
                        int from = i * Net.PART_SIZE;
                        outgoing.add(
                            new Net.ReportPart(
                                id,
                                i,
                                total,
                                Arrays.copyOfRange(data, from, Math.min(data.length, from + Net.PART_SIZE))));
                    }
                    building = false;
                    LagFinder.LOG.info("Tick scan finished: report of {} KB in {} parts", data.length / 1024, total);
                });
            } catch (Throwable t) {
                LagFinder.LOG.error("Could not write the scan's report", t);
                finished.discard();
                building = false;
            }
        }, "LagFinder report writer");
        writer.setDaemon(true);
        writer.start();
    }

    private void sendParts() {
        if (outgoing.isEmpty()) {
            return;
        }
        EntityPlayerMP player = ownerPlayer(outgoingTo);
        if (player == null) {
            // Left before getting it.
            outgoing.clear();
            return;
        }
        for (int i = 0; i < PARTS_PER_TICK && !outgoing.isEmpty(); i++) {
            Net.sendTo(outgoing.poll(), player);
        }
        if (outgoing.isEmpty()) {
            sendStatus(player, null);
        }
    }

    private static String serverName() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return "";
        }
        if (server.isSinglePlayer()) {
            WorldServer world = server.worldServerForDimension(0);
            return world != null ? world.getWorldInfo()
                .getWorldName() : "";
        }
        return server.getMOTD();
    }

    /** The server stopped: nothing carries over to the next one. */
    public void reset() {
        stop();
        tasks.clear();
        outgoing.clear();
        building = false;
        owner = null;
    }
}
