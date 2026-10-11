package lagfinder.client.gui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import lagfinder.client.ReportStore;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.ScaledScreen;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.WindowHeader;
import lagfinder.client.heatmap.Heatmap;

/**
 * The saved tick scans to make the chunk lag heatmap of Wayfarer's Map from. Only those are listed: reports of other
 * kinds of scans have no chunks in them. A click shows the log on the map, which this screen then gives back.
 */
public class GuiHeatmapLogs extends ScaledScreen {

    private static final int WIDTH = 400, ROW = 22, SHOW_WIDTH = 64;
    private static final int ID_CLOSE = 0;

    private final GuiScreen parent;
    private final List<ReportStore.Saved> logs = new ArrayList<>();
    private final Map<ReportStore.Saved, Smooth> lights = new HashMap<>();
    /** Something to tell (already formatted), and when. */
    private String message;
    private long messageAt;

    private int left, top, panelHeight, listTop, listHeight;
    private int scroll;
    private final Smooth shownScroll = new Smooth(0);

    /** @param parent the screen to go back to (the map), where the picked log is shown */
    public GuiHeatmapLogs(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        logs.clear();
        lights.clear();
        for (ReportStore.Saved saved : ReportStore.list()) {
            if (Heatmap.accepts(saved)) {
                logs.add(saved);
            }
        }
        int fixed = WindowHeader.HEIGHT + 10 + 16 + 10;
        int room = height - 8 - fixed;
        listHeight = Math.max(ROW * 4, Math.min(room, ROW * 12));
        panelHeight = fixed + listHeight;
        left = (width - WIDTH) / 2;
        top = Math.max(4, (height - panelHeight) / 2);
        listTop = top + WindowHeader.HEIGHT + 10 + 16;
        buttonList.clear();
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, left + WIDTH, top));
        clampScroll();
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, logs.size() * ROW - listHeight));
    }

    // ---------------------------------------------------------------- Input

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            mc.displayGuiScreen(parent);
        }
    }

    private int rowAt(int mouseX, int mouseY) {
        if (!Theme.inside(mouseX, mouseY, left + 8, listTop, left + WIDTH - 8, listTop + listHeight)) {
            return -1;
        }
        int index = (mouseY - listTop + scroll) / ROW;
        return index >= 0 && index < logs.size() ? index : -1;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (button != 0) {
            return;
        }
        int index = rowAt(mouseX, mouseY);
        if (index < 0) {
            return;
        }
        if (Heatmap.show(logs.get(index))) {
            mc.displayGuiScreen(parent);
        } else {
            message = I18n.format("lagfinder.logs.unreadable");
            messageAt = System.currentTimeMillis();
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll -= Integer.signum(wheel) * 2 * ROW;
            clampScroll();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
        }
    }

    // ---------------------------------------------------------------- Drawing

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, left + WIDTH, top + panelHeight);
        WindowHeader.draw(
            fontRendererObj,
            left,
            top,
            left + WIDTH,
            left + WIDTH - WindowHeader.CLOSE_ROOM,
            Icons.HEATMAP,
            I18n.format("lagfinder.heatmap.picker_title"),
            I18n.format("lagfinder.heatmap.picker_subtitle"),
            Theme.TEXT_MUTED,
            String.valueOf(logs.size()));

        int hintY = top + WindowHeader.HEIGHT + 10 + 4;
        String note = message != null && System.currentTimeMillis() - messageAt < 4000 ? message : null;
        fontRendererObj.drawString(
            note != null ? note : I18n.format("lagfinder.heatmap.picker_hint"),
            left + 12,
            hintY,
            note != null ? Theme.DANGER : Theme.TEXT_MUTED);

        int x0 = left + 8, x1 = left + WIDTH - 8, y0 = listTop, y1 = listTop + listHeight;
        Theme.card(x0, y0, x1, y1);
        if (logs.isEmpty()) {
            Theme.centered(
                fontRendererObj,
                I18n.format("lagfinder.heatmap.picker_empty"),
                (x0 + x1) / 2,
                (y0 + y1) / 2 - 4,
                Theme.TEXT_DISABLED);
        } else {
            drawRows(mouseX, mouseY, x0, y0, x1, y1);
        }
        super.drawScaled(mouseX, mouseY, partialTicks);
    }

    private void drawRows(int mouseX, int mouseY, int x0, int y0, int x1, int y1) {
        int smooth = (int) Math.round(shownScroll.update(scroll, 18));
        SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy  HH:mm:ss");
        int hoveredIndex = rowAt(mouseX, mouseY);
        Theme.clip(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
        for (int i = 0; i < logs.size(); i++) {
            int y = y0 + i * ROW - smooth;
            if (y + ROW < y0 || y > y1) {
                continue;
            }
            ReportStore.Saved saved = logs.get(i);
            boolean hovered = i == hoveredIndex;
            boolean current = saved.file.equals(Heatmap.source()) && Heatmap.isActive();
            double lit = lights.computeIfAbsent(saved, k -> new Smooth(0))
                .update(hovered ? 1 : 0, 22);
            if (lit > 0.01) {
                Theme.fill(x0 + 1, y, x1 - 1, y + ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
            }
            if (i > 0) {
                Theme.fill(x0 + 6, y, x1 - 6, y + 1, 0xFF1C2129);
            }
            int textY = y + (ROW - 8) / 2;
            int color = current ? Theme.SUCCESS : Theme.ACCENT;
            Theme.gradient(
                x0 + 1,
                y + 2,
                x0 + 3,
                y + ROW - 2,
                Theme.blend(color, 0xFF000000, 0.3 - 0.3 * lit),
                Theme.blend(color, 0xFF000000, 0.6 - 0.3 * lit));
            Icons.draw(Icons.SMALL_GRID, x0 + 8, y + (ROW - 7) / 2, color);
            fontRendererObj.drawString(
                format.format(new Date(saved.time)),
                x0 + 22,
                textY,
                Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.6 + 0.4 * lit));
            if (current) {
                fontRendererObj.drawString(I18n.format("lagfinder.heatmap.shown"), x0 + 150, textY, Theme.SUCCESS);
            }
            String size = size(saved.size);
            int showX = x1 - 14 - SHOW_WIDTH;
            fontRendererObj
                .drawString(size, showX - 10 - fontRendererObj.getStringWidth(size), textY, Theme.TEXT_DISABLED);
            int by0 = y + 3, by1 = y + ROW - 3;
            boolean over = Theme.inside(mouseX, mouseY, showX, by0, showX + SHOW_WIDTH, by1);
            Theme.fill(showX, by0, showX + SHOW_WIDTH, by1, over ? Theme.ACCENT_DIM : Theme.CONTROL);
            Theme.outline(showX, by0, showX + SHOW_WIDTH, by1, over || hovered ? Theme.ACCENT : Theme.BORDER);
            Theme.centered(
                fontRendererObj,
                I18n.format("lagfinder.heatmap.show"),
                showX + SHOW_WIDTH / 2,
                by0 + (by1 - by0 - 8) / 2,
                hovered ? Theme.TEXT : Theme.TEXT_MUTED);
        }
        Theme.unclip();
        if (logs.size() * ROW > listHeight) {
            Theme.scrollbar(
                x1 - 4,
                y0 + 2,
                y1 - 2,
                listHeight,
                logs.size() * ROW,
                smooth / (double) (logs.size() * ROW - listHeight),
                Theme.inside(mouseX, mouseY, x0, y0, x1, y1));
        }
    }

    private static String size(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
