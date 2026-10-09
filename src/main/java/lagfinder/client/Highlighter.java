package lagfinder.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import lagfinder.Config;
import lagfinder.core.LocationKind;
import lagfinder.core.ReportEntry;

/**
 * Things picked in a report, outlined in the world through walls with a beam up to the sky, so they are found from
 * afar: a block, an entity's place, a chunk with the block that ticked in it, or a chunk section.
 */
public final class Highlighter {

    public static final Highlighter INSTANCE = new Highlighter();

    /** Colors given to highlights in turn. */
    private static final int[] COLORS = { 0xFFFF5C5C, 0xFFFFB648, 0xFF4C9AFF, 0xFF3FB950, 0xFFB37FEB, 0xFF39C5CF };

    public static final class Highlight {

        public final int dim, x, y, z;
        public final LocationKind kind;
        public final String name;
        public final int color;
        final long until;

        Highlight(ReportEntry entry, String name, int color) {
            this.dim = entry.dim;
            this.x = entry.x;
            this.y = entry.y;
            this.z = entry.z;
            this.kind = entry.kind;
            this.name = name;
            this.color = color;
            this.until = System.currentTimeMillis() + Config.highlightSeconds * 1000L;
        }

        boolean same(ReportEntry entry) {
            return entry.dim == dim && entry.x == x && entry.y == y && entry.z == z && entry.kind == kind;
        }
    }

    private final List<Highlight> highlights = new ArrayList<>();
    private int nextColor;

    private Highlighter() {}

    public List<Highlight> all() {
        expire();
        return highlights;
    }

    public boolean isHighlighted(ReportEntry entry) {
        for (Highlight highlight : all()) {
            if (highlight.same(entry)) {
                return true;
            }
        }
        return false;
    }

    /** Highlights the instance, or takes its highlight away if it has one. */
    public void toggle(ReportEntry entry) {
        if (!entry.hasLocation()) {
            return;
        }
        for (Iterator<Highlight> it = highlights.iterator(); it.hasNext();) {
            if (it.next()
                .same(entry)) {
                it.remove();
                return;
            }
        }
        highlights.add(new Highlight(entry, Names.of(entry), COLORS[nextColor++ % COLORS.length]));
    }

    public void clear() {
        highlights.clear();
    }

    private void expire() {
        long now = System.currentTimeMillis();
        highlights.removeIf(highlight -> highlight.until < now);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (highlights.isEmpty() || mc.theWorld == null) {
            return;
        }
        expire();
        int dim = mc.theWorld.provider.dimensionId;
        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 250.0);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LINE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPushMatrix();
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        for (Highlight highlight : highlights) {
            if (highlight.dim != dim) {
                continue;
            }
            double[] box = box(highlight);
            int rgb = highlight.color & 0xFFFFFF;
            fillBox(box, rgb, (int) (30 + 40 * pulse));
            GL11.glLineWidth(2.5f);
            outlineBox(box, rgb, 255);
            double cx = (box[0] + box[3]) / 2, cz = (box[2] + box[5]) / 2;
            // A beam up from it, to find it from far away.
            GL11.glLineWidth(4f);
            Tessellator tess = Tessellator.instance;
            tess.startDrawing(GL11.GL_LINES);
            tess.setColorRGBA_I(rgb, (int) (120 + 100 * pulse));
            tess.addVertex(cx, box[4], cz);
            tess.setColorRGBA_I(rgb, 0);
            tess.addVertex(cx, Math.max(box[4] + 64, 256), cz);
            tess.draw();
            if (highlight.kind == LocationKind.CHUNK) {
                // The chunk's border at the height of the block that ticked.
                int chunkX = highlight.x >> 4 << 4, chunkZ = highlight.z >> 4 << 4;
                GL11.glLineWidth(1.5f);
                outlineBox(
                    new double[] { chunkX, highlight.y, chunkZ, chunkX + 16, highlight.y + 1, chunkZ + 16 },
                    rgb,
                    140);
            }
        }
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static double[] box(Highlight highlight) {
        double e = 0.02;
        switch (highlight.kind) {
            case ENTITY:
                return new double[] { highlight.x - 0.1, highlight.y, highlight.z - 0.1, highlight.x + 1.1,
                    highlight.y + 2, highlight.z + 1.1 };
            case SECTION:
                return new double[] { highlight.x, highlight.y, highlight.z, highlight.x + 16, highlight.y + 16,
                    highlight.z + 16 };
            default:
                return new double[] { highlight.x - e, highlight.y - e, highlight.z - e, highlight.x + 1 + e,
                    highlight.y + 1 + e, highlight.z + 1 + e };
        }
    }

    private static void fillBox(double[] b, int rgb, int alpha) {
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.setColorRGBA_I(rgb, alpha);
        // Bottom, top, and the four sides.
        quad(tess, b[0], b[1], b[2], b[3], b[1], b[2], b[3], b[1], b[5], b[0], b[1], b[5]);
        quad(tess, b[0], b[4], b[2], b[0], b[4], b[5], b[3], b[4], b[5], b[3], b[4], b[2]);
        quad(tess, b[0], b[1], b[2], b[0], b[4], b[2], b[3], b[4], b[2], b[3], b[1], b[2]);
        quad(tess, b[0], b[1], b[5], b[3], b[1], b[5], b[3], b[4], b[5], b[0], b[4], b[5]);
        quad(tess, b[0], b[1], b[2], b[0], b[1], b[5], b[0], b[4], b[5], b[0], b[4], b[2]);
        quad(tess, b[3], b[1], b[2], b[3], b[4], b[2], b[3], b[4], b[5], b[3], b[1], b[5]);
        tess.draw();
    }

    private static void quad(Tessellator tess, double... v) {
        for (int i = 0; i < 12; i += 3) {
            tess.addVertex(v[i], v[i + 1], v[i + 2]);
        }
    }

    private static void outlineBox(double[] b, int rgb, int alpha) {
        Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_LINES);
        tess.setColorRGBA_I(rgb, alpha);
        double[][] corners = { { b[0], b[2] }, { b[3], b[2] }, { b[3], b[5] }, { b[0], b[5] } };
        for (int i = 0; i < 4; i++) {
            double[] a = corners[i], c = corners[(i + 1) % 4];
            tess.addVertex(a[0], b[1], a[1]);
            tess.addVertex(c[0], b[1], c[1]);
            tess.addVertex(a[0], b[4], a[1]);
            tess.addVertex(c[0], b[4], c[1]);
            tess.addVertex(a[0], b[1], a[1]);
            tess.addVertex(a[0], b[4], a[1]);
        }
        tess.draw();
    }
}
