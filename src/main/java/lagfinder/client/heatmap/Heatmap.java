package lagfinder.client.heatmap;

import java.awt.Color;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;

import org.lwjgl.opengl.GL11;

import lagfinder.client.ReportStore;
import lagfinder.client.gui.Draw;
import lagfinder.client.gui.ui.Theme;
import lagfinder.core.Report;
import lagfinder.core.ReportEntry;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;

/**
 * The chunk lag heatmap mode of Wayfarer's Map: the cost of each chunk in a tick scan, painted over the map from green
 * (cheap) to red (the most expensive chunk of the dimension). The map's mixin ({@code MixinGuiWorldMap}) calls in
 * here; everything that is drawn and remembered lives here, so the mixin stays thin.
 */
public final class Heatmap {

    /** The scale tops out at this much at least (ms per tick): a scan with nothing heavy doesn't paint red. */
    private static final double MIN_TOP_MS = 0.5;
    /** Most things named in a chunk's tooltip. */
    private static final int TOOLTIP_THINGS = 4;
    private static final int LEGEND_WIDTH = 150, LEGEND_MARGIN = 6, FOOTER = 14;

    /** Whether the map shows the heatmap. */
    private static boolean active;
    /** The log it is made from; kept when the mode is left, so the next pick can mark it. */
    private static ChunkHeat heat;
    private static File source;

    private Heatmap() {}

    public static boolean isActive() {
        return active && heat != null;
    }

    /** Leaves the mode (the map switched to another one). */
    public static void deactivate() {
        active = false;
    }

    public static File source() {
        return source;
    }

    /** Whether the saved report is a tick scan of the server's lag: the only kind the heatmap is made from. */
    public static boolean accepts(ReportStore.Saved saved) {
        return saved.mode == ScanMode.TPS && saved.focus == ScanFocus.LAG
            && saved.side == lagfinder.core.ScanSide.SERVER;
    }

    /** Reads the log and shows it on the map; false when it is not readable or not a tick scan. */
    public static boolean show(ReportStore.Saved saved) {
        if (!accepts(saved)) {
            return false;
        }
        Report report = ReportStore.load(saved.file);
        if (report == null || report.mode != ScanMode.TPS || report.focus != ScanFocus.LAG || report.bothSides) {
            return false;
        }
        heat = new ChunkHeat(report);
        source = saved.file;
        active = true;
        return true;
    }

    // ---------------------------------------------------------------- Colors

    /** The color of a cost: 0 is green, 1 is red, yellow between. Opaque. */
    public static int color(double t) {
        t = Math.max(0, Math.min(1, t));
        // Hue from 120 degrees (green) down to 0 (red).
        return Color.HSBtoRGB((float) ((1 - t) / 3.0), 0.9f, 0.95f) | 0xFF000000;
    }

    /** How strongly a cost is painted: the cheap ones let the map show through, the expensive ones cover it. */
    private static int alpha(double t) {
        return (int) Math.round(0x50 + 0x70 * Math.max(0, Math.min(1, t)));
    }

    private static double top(int dimension) {
        return Math.max(MIN_TOP_MS, heat.max(dimension));
    }

    // ---------------------------------------------------------------- Drawing

    /**
     * Paints the chunks over the map. {@code scale} is GUI pixels per block and the point {@code centerX}, {@code
     * centerZ} (blocks) is in the middle of the {@code width} x {@code height} area.
     */
    public static void draw(int dimension, double centerX, double centerZ, double scale, int width, int height) {
        if (!isActive()) {
            return;
        }
        Map<Long, ChunkHeat.Cell> chunks = heat.chunks(dimension);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        double top = top(dimension);
        double left = centerX - width / 2.0 / scale, topEdge = centerZ - height / 2.0 / scale;
        int firstX = (int) Math.floor(left / 16), lastX = (int) Math.floor((left + width / scale) / 16);
        int firstZ = (int) Math.floor(topEdge / 16), lastZ = (int) Math.floor((topEdge + height / scale) / 16);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        long inView = (long) (lastX - firstX + 1) * (lastZ - firstZ + 1);
        if (inView < chunks.size()) {
            // Fewer places in view than chunks measured (zoomed in): look the places up.
            for (int cx = firstX; cx <= lastX; cx++) {
                for (int cz = firstZ; cz <= lastZ; cz++) {
                    ChunkHeat.Cell cell = chunks.get(ChunkHeat.key(cx, cz));
                    if (cell != null) {
                        quad(tessellator, cx, cz, cell, top, left, topEdge, scale);
                    }
                }
            }
        } else {
            for (Map.Entry<Long, ChunkHeat.Cell> entry : chunks.entrySet()) {
                int cx = (int) (entry.getKey() >> 32), cz = (int) (long) entry.getKey();
                if (cx >= firstX && cx <= lastX && cz >= firstZ && cz <= lastZ) {
                    quad(tessellator, cx, cz, entry.getValue(), top, left, topEdge, scale);
                }
            }
        }
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void quad(Tessellator tessellator, int cx, int cz, ChunkHeat.Cell cell, double top, double left,
        double topEdge, double scale) {
        double t = cell.msPerTick / top;
        int rgb = color(t);
        tessellator.setColorRGBA(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, alpha(t));
        double x0 = (cx * 16 - left) * scale, y0 = (cz * 16 - topEdge) * scale;
        double x1 = x0 + 16 * scale, y1 = y0 + 16 * scale;
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
    }

    /** Outlines the chunk under the mouse, over the heat. */
    public static void drawHovered(int dimension, double centerX, double centerZ, double scale, int width, int height,
        int mouseX, int mouseY) {
        if (!isActive()) {
            return;
        }
        int cx = chunkAt(centerX, width, scale, mouseX), cz = chunkAt(centerZ, height, scale, mouseY);
        if (heat.cell(dimension, cx, cz) == null) {
            return;
        }
        int x0 = (int) Math.round(width / 2.0 + (cx * 16 - centerX) * scale);
        int y0 = (int) Math.round(height / 2.0 + (cz * 16 - centerZ) * scale);
        int x1 = (int) Math.round(width / 2.0 + (cx * 16 + 16 - centerX) * scale);
        int y1 = (int) Math.round(height / 2.0 + (cz * 16 + 16 - centerZ) * scale);
        Theme.outline(x0, y0, Math.max(x1, x0 + 2), Math.max(y1, y0 + 2), 0xFFFFFFFF);
    }

    private static int chunkAt(double center, int size, double scale, int mouse) {
        return (int) Math.floor((center + (mouse - size / 2.0) / scale) / 16);
    }

    // ---------------------------------------------------------------- Legend and tooltip

    /** The scale in the lower left corner: what the colors stand for, and which log they come from. */
    public static void drawLegend(FontRenderer font, int dimension, int height) {
        if (!isActive()) {
            return;
        }
        double top = top(dimension);
        boolean any = heat.max(dimension) > 0;
        String title = I18n.format("lagfinder.heatmap.title");
        String log = new SimpleDateFormat("dd.MM.yyyy  HH:mm:ss").format(new Date(heat.startedAt));
        String unit = I18n.format("lagfinder.heatmap.unit");
        String peak = String.format("%.2f %s", top, unit);
        String hint = I18n.format("lagfinder.heatmap.change");
        String empty = I18n.format("lagfinder.heatmap.empty");

        int text = Math.max(font.getStringWidth(title), font.getStringWidth(any ? hint : empty));
        int w = Math.max(LEGEND_WIDTH, text + 12);
        int h = 12 + 10 + 10 + 4 + 8 + 4 + 10 + 6;
        int x0 = LEGEND_MARGIN, y1 = height - FOOTER - LEGEND_MARGIN, y0 = y1 - h, x1 = x0 + w;
        Theme.panel(x0, y0, x1, y1);
        int y = y0 + 6;
        font.drawString(title, x0 + 6, y, Theme.TEXT);
        y += 10;
        font.drawString(log, x0 + 6, y, Theme.TEXT_MUTED);
        y += 14;
        if (any) {
            int bx0 = x0 + 6, bx1 = x1 - 6;
            int segments = 4;
            for (int i = 0; i < segments; i++) {
                int a = bx0 + (bx1 - bx0) * i / segments, b = bx0 + (bx1 - bx0) * (i + 1) / segments;
                int from = color(i / (double) segments), to = color((i + 1) / (double) segments);
                Draw.horizontalGradient(a, y, b, y + 8, from, to);
            }
            Theme.outline(bx0 - 1, y - 1, bx1 + 1, y + 9, Theme.BORDER);
            y += 12;
            font.drawString("0", bx0, y, Theme.TEXT_MUTED);
            font.drawString(peak, bx1 - font.getStringWidth(peak), y, Theme.TEXT_MUTED);
            y += 10;
            font.drawString(hint, x0 + 6, y, Theme.TEXT_DISABLED);
        } else {
            font.drawString(empty, x0 + 6, y, Theme.TEXT_MUTED);
        }
    }

    /** The lines of the tooltip for the chunk under the mouse, or null when nothing was measured in it. */
    public static List<String> tooltip(int dimension, double centerX, double centerZ, double scale, int width,
        int height, int mouseX, int mouseY) {
        if (!isActive()) {
            return null;
        }
        int cx = chunkAt(centerX, width, scale, mouseX), cz = chunkAt(centerZ, height, scale, mouseY);
        ChunkHeat.Cell cell = heat.cell(dimension, cx, cz);
        if (cell == null) {
            return null;
        }
        Report report = heat.report;
        List<String> lines = new ArrayList<>();
        lines.add(I18n.format("lagfinder.heatmap.chunk", cx, cz, cx * 16, cz * 16));
        double budget = report.mode.budgetMs();
        lines.add(
            I18n.format(
                "lagfinder.heatmap.load",
                String.format("%.2f", cell.msPerTick),
                String.format("%.1f", cell.msPerTick / budget * 100)));
        lines.add(I18n.format("lagfinder.heatmap.things", cell.things.size()));
        List<ReportEntry> heaviest = new ArrayList<>(cell.things);
        heaviest.sort((a, b) -> Double.compare(b.totalMs, a.totalMs));
        for (int i = 0; i < heaviest.size() && i < TOOLTIP_THINGS; i++) {
            ReportEntry thing = heaviest.get(i);
            String name = thing.label != null && !thing.label.isEmpty() ? thing.name + " (" + thing.label + ")"
                : thing.name;
            String ms = String.format("%.2f", report.perPeriod(thing));
            lines.add("  " + name + "  " + ms + " " + I18n.format("lagfinder.unit.ms"));
        }
        if (heaviest.size() > TOOLTIP_THINGS) {
            lines.add("  " + I18n.format("lagfinder.heatmap.more", heaviest.size() - TOOLTIP_THINGS));
        }
        return lines;
    }
}
