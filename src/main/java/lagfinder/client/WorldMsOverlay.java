package lagfinder.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import lagfinder.Config;
import lagfinder.Dims;
import lagfinder.core.Report;
import lagfinder.core.ScanMode;

/**
 * In the world, over the machines around the player: what each took a tick in the last tick scan (as a block entity,
 * and as part of an AE2 network), the one under the crosshair in more detail. Turned on and off with its key.
 */
public final class WorldMsOverlay {

    public static final WorldMsOverlay INSTANCE = new WorldMsOverlay();

    private final WorldMsIndex index = new WorldMsIndex();
    private Report indexedReport;
    private List<WorldMsIndex.BlockTime> nearby = Collections.emptyList();
    private long refreshAt;
    /** The report of the server left: it is of other machines, and isn't shown on the next one. */
    private Report staleReport;
    private final Map<WorldMsIndex.BlockTime, List<String>> shortCache = new IdentityHashMap<>();
    private final Map<WorldMsIndex.BlockTime, List<String>> detailCache = new IdentityHashMap<>();
    private int dimension = Integer.MIN_VALUE;
    private boolean enabled;

    private WorldMsOverlay() {}

    public void toggle() {
        enabled = !enabled;
        refreshAt = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        String message = I18n.get(
            enabled ? "lagfinder.world_ms.enabled" : "lagfinder.world_ms.disabled",
            KeyHandler.WORLD_MS.getTranslatedKeyMessage()
                .getString());
        if (enabled && !hasTickReport(ClientScanner.INSTANCE.report())) {
            message += " " + I18n.get("lagfinder.world_ms.no_report");
        }
        mc.player.displayClientMessage(Component.literal("[LagFinder] " + message), false);
    }

    /** Joined or left a server: what was shown is of the one before. */
    void serverChanged() {
        staleReport = ClientScanner.INSTANCE.report();
        enabled = false;
        indexedReport = null;
        index.build(null);
        nearby = Collections.emptyList();
        shortCache.clear();
        detailCache.clear();
        dimension = Integer.MIN_VALUE;
        refreshAt = 0;
    }

    private boolean hasTickReport(Report report) {
        return report != null && report != staleReport && report.mode == ScanMode.TPS && report.periods > 0;
    }

    public void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.getDebugOverlay()
            .showDebugScreen()) {
            return;
        }
        Report report = ClientScanner.INSTANCE.report();
        if (!hasTickReport(report)) {
            return;
        }
        if (indexedReport != report) {
            index.build(report);
            shortCache.clear();
            detailCache.clear();
            indexedReport = report;
            refreshAt = 0;
        }
        int dim = Dims.id(mc.level);
        long now = System.currentTimeMillis();
        if (refreshAt <= now || dimension != dim) {
            dimension = dim;
            refreshAt = now + 300;
            nearby = index.nearby(
                dim,
                mc.player.getX(),
                mc.player.getY(),
                mc.player.getZ(),
                Config.worldMsRadius,
                Math.min(256, Config.worldMsMaxLabels * 4),
                Config.worldMsMinMs);
        }
        WorldMsIndex.BlockTime focused = focusedBlock(mc, dim);
        BlockPos missing = null;
        if (focused == null && mc.hitResult instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            if (pos.distToCenterSqr(mc.player.position()) <= Config.worldMsRadius * Config.worldMsRadius
                && mc.level.getBlockEntity(pos) != null) {
                missing = pos;
            }
        }
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        Vec3 look = new Vec3(camera.getLookVector());
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers()
            .bufferSource();
        Font font = mc.font;
        int shown = 0;
        for (WorldMsIndex.BlockTime block : nearby) {
            if (block == focused || !present(mc, block) || !inView(block, eye, look)) {
                continue;
            }
            draw(
                pose,
                buffers,
                font,
                camera,
                block.x,
                block.y,
                block.z,
                shortLines(block),
                false,
                severity(block.totalAverage()));
            if (++shown >= Config.worldMsMaxLabels) {
                break;
            }
        }
        if (focused != null && present(mc, focused)) {
            draw(
                pose,
                buffers,
                font,
                camera,
                focused.x,
                focused.y,
                focused.z,
                detailLines(focused),
                true,
                severity(focused.totalAverage()));
        } else if (missing != null) {
            draw(
                pose,
                buffers,
                font,
                camera,
                missing.getX(),
                missing.getY(),
                missing.getZ(),
                Collections.singletonList(I18n.get("lagfinder.world_ms.not_measured")),
                true,
                0xFF8E9EB2);
        }
        buffers.endBatch();
    }

    private WorldMsIndex.BlockTime focusedBlock(Minecraft mc, int dim) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        WorldMsIndex.BlockTime block = index.at(dim, pos.getX(), pos.getY(), pos.getZ());
        return block != null && block.distanceSquared(mc.player.getX(), mc.player.getY(), mc.player.getZ())
            <= Config.worldMsRadius * Config.worldMsRadius ? block : null;
    }

    private static boolean present(Minecraft mc, WorldMsIndex.BlockTime block) {
        return mc.level.hasChunk(block.x >> 4, block.z >> 4) && !mc.level.getBlockState(
            new BlockPos(block.x, block.y, block.z))
            .isAir();
    }

    private static boolean inView(WorldMsIndex.BlockTime block, Vec3 eye, Vec3 look) {
        double dx = block.x + 0.5 - eye.x;
        double dy = block.y + 1.0 - eye.y;
        double dz = block.z + 0.5 - eye.z;
        return dx * look.x + dy * look.y + dz * look.z > 0;
    }

    private List<String> shortLines(WorldMsIndex.BlockTime block) {
        List<String> cached = shortCache.get(block);
        if (cached != null) {
            return cached;
        }
        List<String> lines = new ArrayList<>(2);
        if (block.tileEntry != null) {
            lines.add(I18n.get("lagfinder.world_ms.short_tile", value(block.tileAverage)));
        }
        if (block.aeEntry != null) {
            lines.add(I18n.get("lagfinder.world_ms.short_ae", value(block.aeAverage)));
        }
        shortCache.put(block, lines);
        return lines;
    }

    private List<String> detailLines(WorldMsIndex.BlockTime block) {
        List<String> cached = detailCache.get(block);
        if (cached != null) {
            return cached;
        }
        List<String> lines = new ArrayList<>(4);
        lines.add(block.name());
        if (block.tileEntry != null) {
            lines.add(I18n.get("lagfinder.world_ms.detail_tile", value(block.tileAverage), value(block.tilePeak)));
        }
        if (block.aeEntry != null) {
            lines.add(I18n.get("lagfinder.world_ms.detail_ae", value(block.aeAverage), value(block.aePeak)));
        }
        lines.add(I18n.get("lagfinder.world_ms.snapshot"));
        detailCache.put(block, lines);
        return lines;
    }

    private static String value(double ms) {
        if (ms < 0.01) {
            return String.format(Locale.ROOT, "%.2f µs", ms * 1000.0);
        }
        if (ms < 1) {
            return String.format(Locale.ROOT, "%.3f ms", ms);
        }
        return String.format(Locale.ROOT, "%.2f ms", ms);
    }

    private static int severity(double averageMs) {
        return averageMs >= 1 ? 0xFFF16D6D : averageMs >= 0.2 ? 0xFFFFC068 : 0xFF7BBD8B;
    }

    /** A label over the block, facing the camera; the one looked at is seen through walls and bigger. */
    private static void draw(PoseStack pose, MultiBufferSource buffers, Font font, Camera camera, int x, int y, int z,
        List<String> lines, boolean focused, int accent) {
        if (lines.isEmpty()) {
            return;
        }
        Vec3 eye = camera.getPosition();
        int lineHeight = 11;
        int height = lines.size() * lineHeight;
        pose.pushPose();
        pose.translate(x + 0.5 - eye.x, y + 1.25 - eye.y, z + 0.5 - eye.z);
        pose.mulPose(camera.rotation());
        float scale = focused ? 0.018f : 0.014f;
        pose.scale(scale, -scale, scale);
        Matrix4f matrix = pose.last()
            .pose();
        Font.DisplayMode mode = focused ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
        int background = focused ? 0xD609121C : 0xB609121C;
        for (int i = 0; i < lines.size(); i++) {
            String line = font.plainSubstrByWidth(lines.get(i), 310);
            int color = i == 0 ? accent | 0xFF000000
                : focused && i == lines.size() - 1 ? 0xFF8E9EB2 : 0xFFFFFFFF;
            float textX = -font.width(line) / 2f, textY = -height + i * lineHeight;
            font.drawInBatch(
                line,
                textX,
                textY,
                focused ? color & 0xFFFFFF | 0x40000000 : color,
                false,
                matrix,
                buffers,
                mode,
                background,
                LightTexture.FULL_BRIGHT);
            font.drawInBatch(
                line,
                textX,
                textY,
                color,
                false,
                matrix,
                buffers,
                Font.DisplayMode.NORMAL,
                0,
                LightTexture.FULL_BRIGHT);
        }
        pose.popPose();
    }
}
