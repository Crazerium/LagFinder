package lagfinder.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import lagfinder.client.Names;
import lagfinder.client.gui.ui.FlatButton;
import lagfinder.client.gui.ui.FlatTextField;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.WindowHeader;
import lagfinder.core.HeapProfile;
import lagfinder.core.HeapProfile.HeapClass;
import lagfinder.core.Report;
import lagfinder.core.ScanMode;

/**
 * The Java heap of a scan: on top, how much of it was used, how fast the game allocated and when it collected, over the
 * whole scan; under it, the objects in the heap by class (how many and how big) as counted along the scan, with how
 * each grew or shrank from the first count to the last. On the left, the mods with the bytes of all their classes;
 * picking one shows only its classes.
 */
public class GuiMemory extends LagFinderScreen {

    private static final int ID_CLOSE = 0, ID_SORT = 1;
    private static final int ROW = 13, MOD_ROW = 18;
    private static final int SIDE_WIDTH = 176, DETAILS_HEIGHT = 40;
    private static final int COUNT_COLUMN = 54, COUNT_DELTA_COLUMN = 56, SIZE_COLUMN = 56, SIZE_DELTA_COLUMN = 60,
        TREND_COLUMN = 46;
    private static final int CARD = 0xFF12161B, SELECTED = 0xFF1E2C40;
    private static final int WARN = 0xFFF2C14E;
    private static final int HEAP_COLOR = 0xFF4C9AFF, ALLOC_COLOR = 0xFFA371F7, GC_COLOR = 0xFFF0883E,
        SNAPSHOT_COLOR = 0xFF8A93A0;

    private enum Sort {

        SIZE("lagfinder.memory.sort.size"),
        GROWTH("lagfinder.memory.sort.growth"),
        COUNT("lagfinder.memory.sort.count"),
        COUNT_GROWTH("lagfinder.memory.sort.count_growth");

        final String key;

        Sort(String key) {
            this.key = key;
        }
    }

    /** The sorting stays while the game runs. */
    private static Sort sort = Sort.SIZE;

    private final Report report;
    private final HeapProfile heap;
    private final GuiScreen parent;
    /** The mod whose classes are shown (index into the heap's mods), -1 for all. */
    private int modFilter = -1;
    private HeapClass selected;

    /** Clickable area registered while drawing, cut to what shows of it. */
    private static final class Hit {

        final int x0, y0, x1, y1;
        final Runnable action;
        final String tooltip;

        Hit(int x0, int y0, int x1, int y1, Runnable action, String tooltip) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.action = action;
            this.tooltip = tooltip;
        }

        boolean contains(int x, int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final List<HeapClass> rows = new ArrayList<>();
    /** Mods with any objects, the biggest (or the most grown) first. */
    private final List<Integer> modOrder = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int clipX0 = Integer.MIN_VALUE, clipY0 = Integer.MIN_VALUE, clipX1 = Integer.MAX_VALUE,
        clipY1 = Integer.MAX_VALUE;
    private final Map<Object, Smooth> lights = new HashMap<>();
    private int scroll, sideScroll;
    private final Smooth shownScroll = new Smooth(0), shownSideScroll = new Smooth(0);
    private String query = "";

    private int panelLeft, panelTop, panelRight, panelBottom;
    private int toolbarY, bodyBottom;
    private int sideLeft, sideRight, sideTop;
    private int graphTop, graphBottom;
    private int listLeft, listRight, listTop, listBottom;
    private FlatTextField search;
    private FlatButton sortButton;
    private String lastSearch = "";

    public GuiMemory(Report report) {
        this.report = report;
        this.heap = report.memory;
        this.parent = net.minecraft.client.Minecraft.getMinecraft().currentScreen;
    }

    /** Opens the memory with only the mod's classes shown, when the scan counted any of them. */
    public GuiMemory filtered(String mod) {
        int index = mod == null ? -1 : heap.modIndex(mod);
        if (index >= 0 && last(heap.modBytes.get(index)) > 0) {
            modFilter = index;
        }
        return this;
    }

    @Override
    protected Page page() {
        return Page.MEMORY;
    }

    @Override
    protected Report shownReport() {
        return report;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        layoutShell();
        panelLeft = pageLeft;
        panelTop = shellTop;
        panelRight = shellRight;
        panelBottom = shellBottom;
        toolbarY = panelTop + WindowHeader.HEIGHT + 8;
        bodyBottom = panelBottom - 8;
        sideLeft = panelLeft + 8;
        sideRight = sideLeft + SIDE_WIDTH;
        sideTop = toolbarY + 22;
        listLeft = sideRight + 8;
        listRight = panelRight - 8;
        graphTop = toolbarY + 22;
        int body = bodyBottom - DETAILS_HEIGHT - 6 - graphTop;
        graphBottom = graphTop + Math.max(70, Math.min(150, body * 2 / 5));
        listTop = graphBottom + 18;
        listBottom = bodyBottom - DETAILS_HEIGHT - 6;

        buttonList.clear();
        sortButton = new FlatButton(ID_SORT, listRight - 128, toolbarY, 128, 16, "");
        sortButton.icon = Icons.SMALL_SORT;
        buttonList.add(sortButton);
        String text = search != null ? search.getText() : "";
        int searchWidth = Math.max(60, sortButton.xPosition - 6 - listLeft);
        search = new FlatTextField(fontRendererObj, listLeft, toolbarY, searchWidth, 16);
        search.setHint(I18n.format("lagfinder.memory.search"));
        search.setMaxStringLength(80);
        search.setText(text);
        search.setOnCleared(this::rebuild);
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, panelRight, panelTop));
        updateButtons();
        rebuild();
    }

    private void updateButtons() {
        sortButton.displayString = I18n.format(sort.key);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    // ---------------------------------------------------------------- What is shown

    private int snapshots() {
        return heap.snapshots();
    }

    private static long first(long[] values) {
        return values.length == 0 ? 0 : values[0];
    }

    private static long last(long[] values) {
        return values.length == 0 ? 0 : values[values.length - 1];
    }

    private static long delta(long[] values) {
        return last(values) - first(values);
    }

    /** What the classes are sorted by. */
    private static long value(HeapClass type) {
        switch (sort) {
            case GROWTH:
                return delta(type.bytes);
            case COUNT:
                return last(type.instances);
            case COUNT_GROWTH:
                return delta(type.instances);
            default:
                return last(type.bytes);
        }
    }

    private long modValue(int mod) {
        long[] bytes = heap.modBytes.get(mod), instances = heap.modInstances.get(mod);
        switch (sort) {
            case GROWTH:
                return delta(bytes);
            case COUNT:
                return last(instances);
            case COUNT_GROWTH:
                return delta(instances);
            default:
                return last(bytes);
        }
    }

    private boolean matches(HeapClass type) {
        if (query.isEmpty()) {
            return true;
        }
        return type.name.toLowerCase(Locale.ROOT)
            .contains(query)
            || heap.modName(type.mod)
                .toLowerCase(Locale.ROOT)
                .contains(query);
    }

    /** Lays the lines out again: after a change of sorting, mod or search. */
    private void rebuild() {
        lastSearch = search.getText();
        query = lastSearch.trim()
            .toLowerCase(Locale.ROOT);
        rows.clear();
        for (HeapClass type : heap.classes) {
            if ((modFilter < 0 || type.mod == modFilter) && matches(type)) {
                rows.add(type);
            }
        }
        rows.sort(Comparator.comparingLong(GuiMemory::value)
            .reversed()
            .thenComparing(type -> type.name));
        modOrder.clear();
        for (int i = 0; i < heap.mods.size(); i++) {
            if (heap.modBytes.get(i).length > 0 && heap.modBytes.get(i)[0] + last(heap.modBytes.get(i)) > 0) {
                modOrder.add(i);
            }
        }
        modOrder.sort((a, b) -> Long.compare(modValue(b), modValue(a)));
        clampScroll();
        clampSideScroll();
    }

    private int listHeight() {
        return listBottom - listTop;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, rows.size() * ROW - listHeight() + 2));
    }

    private int sideHeight() {
        return bodyBottom - sideTop - 18;
    }

    private void clampSideScroll() {
        sideScroll = Math.max(0, Math.min(sideScroll, (modOrder.size() + 1) * MOD_ROW - sideHeight()));
    }

    private void setFilter(int mod) {
        modFilter = modFilter == mod ? -1 : mod;
        scroll = 0;
        if (selected != null && modFilter >= 0 && selected.mod != modFilter) {
            selected = null;
        }
        rebuild();
    }

    private void scrollTo(HeapClass type) {
        int index = rows.indexOf(type);
        if (index < 0) {
            return;
        }
        int y = index * ROW;
        if (y < scroll) {
            scroll = y;
        } else if (y + ROW > scroll + listHeight()) {
            scroll = y + ROW - listHeight();
        }
        clampScroll();
    }

    // ---------------------------------------------------------------- Input

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            close();
        } else if (button.id == ID_SORT) {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            scroll = 0;
            updateButtons();
            rebuild();
        }
    }

    private void close() {
        mc.displayGuiScreen(parent);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (mc.currentScreen != this) {
            // A tab of the sidebar opened another page.
            return;
        }
        search.mouseClicked(mouseX, mouseY, button);
        if (!search.getText()
            .equals(lastSearch)) {
            rebuild();
        }
        if (button != 0) {
            return;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mouseX, mouseY) && hit.action != null) {
                hit.action.run();
                return;
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int x = Mouse.getEventX() * width / mc.displayWidth;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (Theme.inside(x, y, listLeft, listTop, listRight, listBottom)) {
            scroll -= Integer.signum(wheel) * 4 * ROW;
            clampScroll();
        } else if (Theme.inside(x, y, sideLeft, sideTop, sideRight, bodyBottom)) {
            sideScroll -= Integer.signum(wheel) * 2 * MOD_ROW;
            clampSideScroll();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (search.isFocused() && !search.getText()
                .isEmpty()) {
                search.setText("");
                rebuild();
            } else {
                close();
            }
            return;
        }
        if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
            if (!rows.isEmpty()) {
                int index = rows.indexOf(selected);
                index = Math.max(0, Math.min(rows.size() - 1,
                    index < 0 ? 0 : index + (keyCode == Keyboard.KEY_UP ? -1 : 1)));
                selected = rows.get(index);
                scrollTo(selected);
            }
            return;
        }
        // Typing anywhere searches.
        if (!search.isFocused() && typedChar >= ' ') {
            search.setFocused(true);
        }
        if (search.textboxKeyTyped(typedChar, keyCode)) {
            scroll = 0;
            rebuild();
        }
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
    }

    // ---------------------------------------------------------------- Formatting

    /** Bytes as B, KB, MB or GB. */
    static String bytes(double bytes) {
        double abs = Math.abs(bytes);
        if (abs >= 1024.0 * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        if (abs >= 1024.0 * 1024 * 100) {
            return String.format("%.0f MB", bytes / (1024.0 * 1024));
        }
        if (abs >= 1024.0 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        if (abs >= 1024) {
            return String.format("%.0f KB", bytes / 1024.0);
        }
        return String.format("%.0f B", bytes);
    }

    static String megabytes(double mb) {
        return bytes(mb * 1024 * 1024);
    }

    /** A count of objects, e.g. "1.2M", "35K", "812". */
    static String count(double count) {
        double abs = Math.abs(count);
        if (abs >= 1e9) {
            return String.format("%.2fG", count / 1e9);
        }
        if (abs >= 1e7) {
            return String.format("%.0fM", count / 1e6);
        }
        if (abs >= 1e6) {
            return String.format("%.1fM", count / 1e6);
        }
        if (abs >= 1e4) {
            return String.format("%.0fK", count / 1e3);
        }
        if (abs >= 1e3) {
            return String.format("%.1fK", count / 1e3);
        }
        return String.format("%.0f", count);
    }

    private static String signed(String text, long value) {
        return value > 0 ? "+" + text : value < 0 ? text : "0";
    }

    private static String bytesDelta(long value) {
        return signed(bytes(value), value);
    }

    private static String countDelta(long value) {
        return signed(count(value), value);
    }

    /** Growth is red, shrinking green, no change muted. */
    private static int deltaColor(long value, long base) {
        if (value == 0) {
            return Theme.TEXT_DISABLED;
        }
        double share = base <= 0 ? 1 : Math.abs(value) / (double) base;
        if (value > 0) {
            return share >= 0.1 ? Theme.DANGER : share >= 0.01 ? WARN : Theme.TEXT_MUTED;
        }
        return share >= 0.01 ? Theme.SUCCESS : Theme.TEXT_MUTED;
    }

    private static String percentChange(long from, long to) {
        if (from <= 0) {
            return to > 0 ? I18n.format("lagfinder.memory.new") : "";
        }
        double change = (to - from) * 100.0 / from;
        return (change > 0 ? "+" : "") + (Math.abs(change) >= 10 ? String.format("%.0f%%", change)
            : String.format("%.1f%%", change));
    }

    // ---------------------------------------------------------------- Drawing

    private void hit(int x0, int y0, int x1, int y1, Runnable action, String tooltip) {
        x0 = Math.max(x0, clipX0);
        y0 = Math.max(y0, clipY0);
        x1 = Math.min(x1, clipX1);
        y1 = Math.min(y1, clipY1);
        if (x1 > x0 && y1 > y0) {
            hits.add(new Hit(x0, y0, x1, y1, action, tooltip));
        }
    }

    private void clipHits(int x0, int y0, int x1, int y1) {
        clipX0 = x0;
        clipY0 = y0;
        clipX1 = x1;
        clipY1 = y1;
    }

    private void unclipHits() {
        clipX0 = clipY0 = Integer.MIN_VALUE;
        clipX1 = clipY1 = Integer.MAX_VALUE;
    }

    private double light(Object key, boolean on) {
        Smooth smooth = lights.get(key);
        if (smooth == null) {
            smooth = new Smooth(0);
            lights.put(key, smooth);
        }
        return smooth.update(on ? 1 : 0, 22);
    }

    private boolean tps() {
        return report.mode == ScanMode.TPS;
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        hits.clear();
        if (!search.getText()
            .equals(lastSearch)) {
            rebuild();
        }
        drawShell(mouseX, mouseY);
        String title = I18n.format(
            report.bothSides ? "lagfinder.memory.title_both"
                : tps() ? "lagfinder.memory.title_tps" : "lagfinder.memory.title_fps");
        float firstHeap = heap.buckets() == 0 ? 0 : heap.heapMb[0];
        float lastHeap = heap.buckets() == 0 ? 0 : heap.heapMb[heap.buckets() - 1];
        String subtitle = I18n.format(
            "lagfinder.memory.subtitle",
            megabytes(firstHeap),
            megabytes(lastHeap),
            heap.maxHeap > 0 ? bytes(heap.maxHeap) : "?");
        if (heap.allocKnown && report.durationSec > 0) {
            subtitle += "  ·  " + I18n.format("lagfinder.memory.alloc_rate",
                megabytes(heap.allocatedMb() / report.durationSec));
        }
        subtitle += "  ·  " + I18n.format("lagfinder.memory.collections", heap.collections(),
            Names.ms(heap.collectionMs()));
        WindowHeader.draw(
            fontRendererObj,
            panelLeft,
            panelTop,
            panelRight,
            panelRight - WindowHeader.CLOSE_ROOM,
            Icons.SMALL_MEMORY,
            title,
            subtitle,
            Theme.TEXT_MUTED,
            modFilter >= 0 ? heap.modName(modFilter) : null);

        drawMods(mouseX, mouseY);
        drawGraph(mouseX, mouseY);
        drawList(mouseX, mouseY);
        drawDetails();
        search.drawTextBox();
        super.drawScaled(mouseX, mouseY, partialTicks);
        drawShellTooltip(mouseX, mouseY);

        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mouseX, mouseY)) {
                if (hit.tooltip != null) {
                    showTooltip(hit.tooltip, mouseX, mouseY);
                }
                break;
            }
        }
        if (sortButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(I18n.format("lagfinder.tip.memory_sort"), mouseX, mouseY);
        }
    }

    /** A tooltip: its first line as a title, the rest (split at "\n" and wrapped) muted under it. */
    private void showTooltip(String text, int mouseX, int mouseY) {
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        String[] parts = text.split("\\\\n|\n");
        for (int i = 0; i < parts.length; i++) {
            for (Object line : fontRendererObj.listFormattedStringToWidth(parts[i], 280)) {
                lines.add((String) line);
                colors.add(i == 0 ? Theme.TEXT : Theme.TEXT_MUTED);
            }
        }
        Draw.tooltip(fontRendererObj, lines, colors, mouseX, mouseY, width, height);
    }

    // ---------------------------------------------------------------- The mods

    private void drawMods(int mouseX, int mouseY) {
        fontRendererObj.drawString(I18n.format("lagfinder.methods.mods").toUpperCase(), sideLeft + 2, toolbarY + 4,
            Theme.TEXT_MUTED);
        Theme.fill(sideLeft, sideTop, sideRight, bodyBottom, CARD);
        Theme.outline(sideLeft, sideTop, sideRight, bodyBottom, Theme.BORDER);
        int top = sideTop + 1, bottom = bodyBottom - 18;
        int offset = (int) Math.round(shownSideScroll.update(sideScroll, 18));
        Theme.clip(sideLeft + 1, top, sideRight - 1, bottom);
        clipHits(sideLeft + 1, top, sideRight - 1, bottom);
        long most = 1;
        for (int mod : modOrder) {
            most = Math.max(most, last(heap.modBytes.get(mod)));
        }
        for (int i = -1; i < modOrder.size(); i++) {
            int y = top + (i + 1) * MOD_ROW - offset;
            if (y + MOD_ROW < top || y > bottom) {
                continue;
            }
            drawModRow(i < 0 ? -1 : modOrder.get(i), y, most, mouseX, mouseY, top, bottom);
        }
        Theme.unclip();
        unclipHits();
        int total = (modOrder.size() + 1) * MOD_ROW;
        if (total > bottom - top) {
            Theme.scrollbar(sideRight - 4, top + 2, bottom - 2, bottom - top, total,
                offset / (double) Math.max(1, total - (bottom - top)),
                Theme.inside(mouseX, mouseY, sideLeft, top, sideRight, bottom));
        }
        // How the objects were counted.
        Theme.fill(sideLeft + 1, bottom, sideRight - 1, bottom + 1, Theme.BORDER);
        String hint = I18n.format(heap.liveOnly ? "lagfinder.memory.live" : "lagfinder.memory.all");
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, hint, SIDE_WIDTH - 12), sideLeft + 6, bottom + 5,
            Theme.TEXT_DISABLED);
        hit(sideLeft, bottom, sideRight, bodyBottom, null,
            hint + "\n" + I18n.format(heap.liveOnly ? "lagfinder.tip.memory_live" : "lagfinder.tip.memory_all"));
    }

    private void drawModRow(int mod, int y, long most, int mouseX, int mouseY, int top, int bottom) {
        boolean all = mod < 0;
        boolean picked = modFilter == mod;
        boolean hovered = Theme.inside(mouseX, mouseY, sideLeft + 1, Math.max(top, y), sideRight - 1,
            Math.min(bottom, y + MOD_ROW));
        double lit = light("mod" + mod, hovered);
        if (picked) {
            Theme.fill(sideLeft + 1, y, sideRight - 1, y + MOD_ROW, SELECTED);
            Theme.fill(sideLeft + 1, y, sideLeft + 3, y + MOD_ROW, Theme.ACCENT);
        } else if (lit > 0.01) {
            Theme.fill(sideLeft + 1, y, sideRight - 1, y + MOD_ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        String name = all ? I18n.format("lagfinder.methods.all_mods") : heap.modName(mod);
        long[] bytes = all ? heap.totalBytes : heap.modBytes.get(mod);
        long[] instances = all ? heap.totalInstances : heap.modInstances.get(mod);
        int color = all ? Theme.ACCENT : Draw.modColor(name);
        int x = sideLeft + 7;
        int textY = y + 4;
        if (all) {
            Icons.draw(Icons.SMALL_GRID, x - 1, textY, picked ? Theme.ACCENT : Theme.TEXT_MUTED);
        } else {
            Theme.fill(x, textY + 1, x + 6, textY + 7, color);
        }
        // Its size, or its change when sorted by change.
        boolean byChange = sort == Sort.GROWTH || sort == Sort.COUNT_GROWTH;
        boolean byCount = sort == Sort.COUNT || sort == Sort.COUNT_GROWTH;
        long[] values = byCount ? instances : bytes;
        String value;
        int valueColor;
        if (byChange) {
            long change = delta(values);
            value = byCount ? countDelta(change) : bytesDelta(change);
            valueColor = deltaColor(change, first(values));
        } else {
            value = byCount ? count(last(values)) : bytes(last(values));
            valueColor = all ? Theme.TEXT_MUTED : Theme.TEXT;
        }
        int valueWidth = fontRendererObj.getStringWidth(value);
        int right = sideRight - 8;
        fontRendererObj.drawString(value, right - valueWidth, textY, valueColor);
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, name, right - valueWidth - 6 - (x + 10)), x + 10,
            textY, picked || all ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, Math.max(lit, 0.6)));
        if (!all) {
            int barWidth = (int) Math.round((sideRight - sideLeft - 14) * Math.min(1, last(bytes) / (double) most));
            Theme.fill(sideLeft + 7, y + MOD_ROW - 3, sideLeft + 7 + Math.max(1, barWidth), y + MOD_ROW - 2,
                Theme.blend(color, 0x00000000, 0.35));
        }
        String tip = name + "\n" + I18n.format("lagfinder.memory.line", count(last(instances)), bytes(last(bytes)))
            + "\n" + I18n.format("lagfinder.memory.change", countDelta(delta(instances)), bytesDelta(delta(bytes)))
            + "\n" + I18n.format(all ? "lagfinder.tip.memory_all_mods" : picked ? "lagfinder.tip.unpick_mod"
                : "lagfinder.tip.memory_pick_mod");
        hit(sideLeft + 1, y, sideRight - 1, y + MOD_ROW, () -> setFilter(mod), tip);
    }

    // ---------------------------------------------------------------- The graph

    private void drawGraph(int mouseX, int mouseY) {
        int x0 = listLeft, y0 = graphTop, x1 = listRight, y1 = graphBottom;
        Theme.fill(x0, y0, x1, y1, CARD);
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);
        int n = heap.buckets();
        float bucketSec = report.bucketSec > 0 ? report.bucketSec : report.durationSec / Math.max(1, n);

        // Legend.
        int lx = x0 + 8, ly = y0 + 6;
        Theme.fill(lx, ly + 3, lx + 8, ly + 5, HEAP_COLOR);
        String heapName = I18n.format("lagfinder.memory.legend.heap");
        fontRendererObj.drawString(heapName, lx + 11, ly, Theme.TEXT);
        lx += 18 + fontRendererObj.getStringWidth(heapName);
        if (heap.allocKnown) {
            Theme.fill(lx, ly + 1, lx + 6, ly + 7, ALLOC_COLOR);
            String allocName = I18n.format("lagfinder.memory.legend.alloc");
            fontRendererObj.drawString(allocName, lx + 9, ly, Theme.TEXT_MUTED);
            lx += 16 + fontRendererObj.getStringWidth(allocName);
        }
        Theme.fill(lx + 2, ly + 1, lx + 4, ly + 7, GC_COLOR);
        String gcName = I18n.format("lagfinder.memory.legend.gc");
        fontRendererObj.drawString(gcName, lx + 8, ly, Theme.TEXT_MUTED);
        lx += 16 + fontRendererObj.getStringWidth(gcName);
        String pickedName = selected != null ? selected.simpleName()
            : modFilter >= 0 ? heap.modName(modFilter) : null;
        int pickedColor = selected != null ? Draw.modColor(heap.modName(selected.mod))
            : modFilter >= 0 ? Draw.modColor(heap.modName(modFilter)) : WARN;
        if (pickedName != null && snapshots() > 0) {
            Theme.fill(lx, ly + 3, lx + 8, ly + 5, pickedColor);
            String legend = I18n.format("lagfinder.memory.legend.picked", pickedName);
            fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, legend, x1 - lx - 20), lx + 11, ly,
                Theme.TEXT_MUTED);
        }

        int px0 = x0 + 40, px1 = x1 - 44, py0 = y0 + 22, py1 = y1 - 14;
        if (px1 - px0 < 20 || py1 - py0 < 20 || n == 0) {
            return;
        }
        // Left axis: the heap used.
        float heapMax = 1;
        for (int i = 0; i < n; i++) {
            heapMax = Math.max(heapMax, heap.heapHighMb[i]);
        }
        heapMax = (float) Draw.niceCeil(heapMax * 1.08);
        for (int i = 0; i <= 4; i++) {
            int y = py1 - Math.round((py1 - py0) * i / 4f);
            Theme.fill(px0, y, px1, y + 1, i == 0 ? Theme.BORDER : 0x14FFFFFF);
            String label = megabytes(heapMax * i / 4f).replace(" ", "");
            fontRendererObj.drawString(label, px0 - 4 - fontRendererObj.getStringWidth(label), y - 4,
                Theme.TEXT_DISABLED);
        }
        double step = (px1 - px0) / (double) n;
        double barWidth = Math.max(1, step - (step > 3 ? 1 : 0));

        // Right axis: the allocation, megabytes a second, as bars.
        float allocMax = 0;
        if (heap.allocKnown) {
            for (int i = 0; i < n; i++) {
                allocMax = Math.max(allocMax, heap.allocMb[i] / bucketSeconds(i, bucketSec, n));
            }
            allocMax = (float) Draw.niceCeil(Math.max(allocMax, 1) * 1.08);
            for (int i = 0; i <= 4; i++) {
                int y = py1 - Math.round((py1 - py0) * i / 4f);
                String label = i == 0 ? "0" : megabytes(allocMax * i / 4f).replace(" ", "") + "/s";
                fontRendererObj.drawString(label, px1 + 4, y - 4, Theme.blend(ALLOC_COLOR, Theme.TEXT_DISABLED, 0.6));
            }
            for (int i = 0; i < n; i++) {
                float rate = heap.allocMb[i] / bucketSeconds(i, bucketSec, n);
                if (rate <= 0) {
                    continue;
                }
                double bx = px0 + i * step;
                double top = py1 - (py1 - py0) * Math.min(1, rate / allocMax);
                Draw.gradientRect(bx, top, bx + barWidth, py1, (ALLOC_COLOR & 0x00FFFFFF) | 0x60000000,
                    (ALLOC_COLOR & 0x00FFFFFF) | 0x18000000);
            }
        }

        // The heap used: the band between its lowest and highest in each moment, and the line of what was used.
        for (int i = 0; i < n; i++) {
            if (heap.heapHighMb[i] <= heap.heapLowMb[i]) {
                continue;
            }
            double bx = px0 + i * step;
            double top = py1 - (py1 - py0) * Math.min(1, heap.heapHighMb[i] / heapMax);
            double low = py1 - (py1 - py0) * Math.min(1, heap.heapLowMb[i] / heapMax);
            Draw.gradientRect(bx, top, bx + barWidth, low, (HEAP_COLOR & 0x00FFFFFF) | 0x38000000,
                (HEAP_COLOR & 0x00FFFFFF) | 0x20000000);
        }
        double[] xs = new double[n + 2], ys = new double[n + 2];
        int[] colors = new int[n + 2];
        for (int i = 0; i < n; i++) {
            xs[i + 1] = px0 + (i + 0.5) * step;
            ys[i + 1] = py1 - (py1 - py0) * Math.min(1, heap.heapMb[i] / heapMax);
            colors[i + 1] = HEAP_COLOR;
        }
        xs[0] = px0;
        ys[0] = ys[1];
        colors[0] = HEAP_COLOR;
        xs[n + 1] = px1;
        ys[n + 1] = ys[n];
        colors[n + 1] = HEAP_COLOR;
        Draw.area(xs, ys, py1, 0x304C9AFF, 0x004C9AFF);
        Draw.line(xs, ys, colors, 1.5f);

        // Collections: a mark at the top of the moments with any.
        for (int i = 0; i < n; i++) {
            if (heap.gcCount[i] > 0) {
                int cx = (int) Math.round(px0 + (i + 0.5) * step);
                Theme.fill(cx - 1, py0 - 5, cx + 1, py0 - 1, GC_COLOR);
            }
        }

        // The counts of the objects, as dashed lines, and what is picked through them.
        double duration = Math.max(0.001, report.durationSec);
        int[] snapX = new int[snapshots()];
        for (int s = 0; s < snapshots(); s++) {
            snapX[s] = (int) Math.round(px0 + (px1 - px0) * Math.min(1, heap.snapshotSec[s] / duration));
            for (int y = py0; y < py1; y += 4) {
                Theme.fill(snapX[s], y, snapX[s] + 1, Math.min(py1, y + 2), (SNAPSHOT_COLOR & 0x00FFFFFF) | 0x90000000);
            }
            String label = String.valueOf(s + 1);
            int labelX = Math.max(px0, Math.min(px1 - fontRendererObj.getStringWidth(label), snapX[s] + 2));
            fontRendererObj.drawString(label, labelX, py0 + 1, Theme.TEXT_DISABLED);
        }
        long[] picked = pickedBytes();
        if (picked != null && picked.length > 0) {
            long pickedMax = 1;
            for (long value : picked) {
                pickedMax = Math.max(pickedMax, value);
            }
            double scale = Draw.niceCeil(pickedMax * 1.1);
            double[] pxs = new double[picked.length], pys = new double[picked.length];
            int[] pcolors = new int[picked.length];
            for (int s = 0; s < picked.length; s++) {
                pxs[s] = snapX[s] + 0.5;
                pys[s] = py1 - (py1 - py0) * Math.min(1, picked[s] / scale);
                pcolors[s] = pickedColor;
            }
            Draw.line(pxs, pys, pcolors, 2f);
            for (int s = 0; s < picked.length; s++) {
                Theme.disc(pxs[s], pys[s], 2.5, pickedColor);
                String label = bytes(picked[s]);
                int w = fontRendererObj.getStringWidth(label);
                int lx2 = (int) Math.max(px0, Math.min(px1 - w, pxs[s] - w / 2.0));
                int ly2 = (int) Math.max(py0 + 10, pys[s] - 11);
                Theme.fill(lx2 - 1, ly2 - 1, lx2 + w + 1, ly2 + 8, Theme.LABEL_BG);
                fontRendererObj.drawString(label, lx2, ly2, pickedColor);
            }
        }

        // Time along the bottom.
        for (int i = 0; i <= 4; i++) {
            String label = Names.clock(report.durationSec * i / 4);
            int x = px0 + (px1 - px0) * i / 4 - (i == 0 ? 0
                : i == 4 ? fontRendererObj.getStringWidth(label) : fontRendererObj.getStringWidth(label) / 2);
            fontRendererObj.drawString(label, x, py1 + 4, Theme.TEXT_DISABLED);
        }

        // What was at the moment under the mouse.
        if (Theme.inside(mouseX, mouseY, px0, py0 - 6, px1, py1)) {
            int i = Math.max(0, Math.min(n - 1, (int) ((mouseX - px0) / step)));
            int cx = (int) Math.round(px0 + (i + 0.5) * step);
            Theme.fill(cx, py0, cx + 1, py1, 0x50FFFFFF);
            List<String> lines = new ArrayList<>();
            List<Integer> lineColors = new ArrayList<>();
            float from = i * bucketSec, to = Math.min(report.durationSec, (i + 1) * bucketSec);
            lines.add(Names.clock(from) + " – " + Names.clock(to));
            lineColors.add(Theme.TEXT_MUTED);
            lines.add(I18n.format("lagfinder.memory.graph.heap", megabytes(heap.heapMb[i]),
                megabytes(heap.heapLowMb[i]), megabytes(heap.heapHighMb[i])));
            lineColors.add(HEAP_COLOR);
            if (heap.allocKnown) {
                float seconds = bucketSeconds(i, bucketSec, n);
                lines.add(I18n.format("lagfinder.memory.graph.alloc", megabytes(heap.allocMb[i] / seconds),
                    megabytes(heap.threadAllocMb[i] / seconds)));
                lineColors.add(ALLOC_COLOR);
            }
            if (heap.gcCount[i] > 0) {
                lines.add(I18n.format("lagfinder.memory.graph.gc", heap.gcCount[i], Names.ms(heap.gcMs[i])));
                lineColors.add(GC_COLOR);
            }
            // A count of the objects near the mouse.
            int near = -1;
            for (int s = 0; s < snapshots(); s++) {
                if (Math.abs(snapX[s] - mouseX) <= Math.max(3, step) && (near < 0
                    || Math.abs(snapX[s] - mouseX) < Math.abs(snapX[near] - mouseX))) {
                    near = s;
                }
            }
            if (near >= 0) {
                lines.add(I18n.format("lagfinder.memory.graph.snapshot", near + 1,
                    count(heap.totalInstances[near]), bytes(heap.totalBytes[near])));
                lineColors.add(Theme.TEXT);
                lines.add("  " + I18n.format("lagfinder.memory.graph.pause", Names.ms(heap.snapshotPauseMs[near])));
                lineColors.add(Theme.TEXT_MUTED);
                if (picked != null && near < picked.length) {
                    lines.add(pickedName + ": " + bytes(picked[near]) + "  ·  " + count(pickedInstances()[near]));
                    lineColors.add(pickedColor);
                }
            }
            Draw.tooltip(fontRendererObj, lines, lineColors, mouseX, mouseY, width, height);
        }
    }

    /** Seconds of the moment; the last may be shorter. */
    private float bucketSeconds(int bucket, float bucketSec, int buckets) {
        if (bucket == buckets - 1) {
            float rest = report.durationSec - bucket * bucketSec;
            if (rest > bucketSec * 0.05f) {
                return Math.min(bucketSec, rest);
            }
        }
        return Math.max(0.001f, bucketSec);
    }

    /** The bytes in each count of what is picked: a class, else the mod; null when nothing is. */
    private long[] pickedBytes() {
        if (selected != null) {
            return selected.bytes;
        }
        return modFilter >= 0 ? heap.modBytes.get(modFilter) : null;
    }

    private long[] pickedInstances() {
        if (selected != null) {
            return selected.instances;
        }
        return modFilter >= 0 ? heap.modInstances.get(modFilter) : new long[snapshots()];
    }

    // ---------------------------------------------------------------- The classes

    /** Where a column of values ends, from the left: objects, their change, size, its change, the trend. */
    private int columnRight(int column) {
        int right = listRight - 8;
        int[] widths = { COUNT_COLUMN, COUNT_DELTA_COLUMN, SIZE_COLUMN, SIZE_DELTA_COLUMN, TREND_COLUMN };
        for (int i = widths.length - 1; i > column; i--) {
            right -= widths[i];
        }
        return right;
    }

    private void drawList(int mouseX, int mouseY) {
        int headY = listTop - 11;
        fontRendererObj.drawString(I18n.format("lagfinder.memory.column.class"), listLeft + 4, headY,
            Theme.TEXT_DISABLED);
        String[] titles = { I18n.format("lagfinder.memory.column.count"),
            I18n.format("lagfinder.memory.column.count_delta"), I18n.format("lagfinder.memory.column.size"),
            I18n.format("lagfinder.memory.column.size_delta"), I18n.format("lagfinder.memory.column.trend") };
        String[] tips = { "lagfinder.tip.memory_count", "lagfinder.tip.memory_count_delta",
            "lagfinder.tip.memory_size", "lagfinder.tip.memory_size_delta", "lagfinder.tip.memory_trend" };
        Sort[] sorts = { Sort.COUNT, Sort.COUNT_GROWTH, Sort.SIZE, Sort.GROWTH, null };
        int[] widths = { COUNT_COLUMN, COUNT_DELTA_COLUMN, SIZE_COLUMN, SIZE_DELTA_COLUMN, TREND_COLUMN };
        for (int i = 0; i < titles.length; i++) {
            int right = columnRight(i);
            Sort columnSort = sorts[i];
            fontRendererObj.drawString(titles[i], right - fontRendererObj.getStringWidth(titles[i]), headY,
                sort == columnSort ? Theme.ACCENT : Theme.TEXT_DISABLED);
            hit(right - widths[i] + 4, headY - 2, right + 2, headY + 10, columnSort == null ? null : () -> {
                sort = columnSort;
                scroll = 0;
                updateButtons();
                rebuild();
            }, titles[i] + "\n" + I18n.format(tips[i]));
        }

        Theme.fill(listLeft, listTop, listRight, listBottom, CARD);
        Theme.outline(listLeft, listTop - 1, listRight, listBottom, Theme.BORDER);
        if (rows.isEmpty()) {
            String empty;
            if (heap.histogramError != null) {
                empty = I18n.format("lagfinder.memory.no_histogram");
            } else if (snapshots() == 0) {
                empty = I18n.format("lagfinder.memory.no_snapshots");
            } else {
                empty = I18n.format(lastSearch.isEmpty() ? "lagfinder.gui.nothing" : "lagfinder.gui.nothing_found");
            }
            Theme.centered(fontRendererObj, empty, (listLeft + listRight) / 2, listTop + 20, Theme.TEXT_DISABLED);
            if (heap.histogramError != null) {
                Theme.centered(fontRendererObj, Theme.ellipsize(fontRendererObj, heap.histogramError,
                    listRight - listLeft - 20), (listLeft + listRight) / 2, listTop + 34, Theme.TEXT_DISABLED);
            }
            return;
        }
        int offset = (int) Math.round(shownScroll.update(scroll, 18));
        int top = listTop + 1, bottom = listBottom - 1;
        Theme.clip(listLeft + 1, top, listRight - 1, bottom);
        clipHits(listLeft + 1, top, listRight - 1, bottom);
        long mostBytes = 1;
        for (HeapClass type : rows) {
            mostBytes = Math.max(mostBytes, last(type.bytes));
        }
        int firstRow = Math.max(0, offset / ROW - 1);
        for (int i = firstRow; i < rows.size(); i++) {
            int y = top + i * ROW - offset;
            if (y > bottom) {
                break;
            }
            drawRow(rows.get(i), y, mostBytes, mouseX, mouseY);
        }
        Theme.unclip();
        unclipHits();
        int contentHeight = rows.size() * ROW;
        if (contentHeight > listHeight()) {
            Theme.scrollbar(listRight - 4, top + 2, bottom - 2, listHeight(), contentHeight,
                offset / (double) Math.max(1, contentHeight - listHeight() + 2),
                Theme.inside(mouseX, mouseY, listLeft, top, listRight, bottom));
        }
    }

    private void drawRow(HeapClass type, int y, long mostBytes, int mouseX, int mouseY) {
        boolean hovered = Theme.inside(mouseX, mouseY, listLeft + 1, y, listRight - 1, y + ROW)
            && Theme.inside(mouseX, mouseY, listLeft, listTop, listRight, listBottom);
        boolean isSelected = type == selected;
        double lit = light(type, hovered);
        String modName = heap.modName(type.mod);
        int modColor = Draw.modColor(modName);

        // Its size as a soft bar behind the line.
        int barRight = listLeft + 1 + (int) Math.round((listRight - listLeft - 2) * Math.min(1,
            last(type.bytes) / (double) mostBytes));
        Theme.fill(listLeft + 1, y, Math.max(listLeft + 1, barRight), y + ROW, (modColor & 0x00FFFFFF) | 0x14000000);
        if (isSelected) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + ROW, SELECTED);
            Theme.fill(listLeft + 1, y, listLeft + 3, y + ROW, Theme.ACCENT);
        } else if (lit > 0.01) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        hit(listLeft + 1, y, listRight - 1, y + ROW, () -> selected = selected == type ? null : type, null);

        int textY = y + (ROW - 8) / 2;
        long countChange = delta(type.instances), sizeChange = delta(type.bytes);
        String[] values = { count(last(type.instances)), countDelta(countChange), bytes(last(type.bytes)),
            bytesDelta(sizeChange) };
        int[] colors = { Theme.TEXT, deltaColor(countChange, first(type.instances)), Theme.TEXT,
            deltaColor(sizeChange, first(type.bytes)) };
        for (int i = 0; i < values.length; i++) {
            fontRendererObj.drawString(values[i], columnRight(i) - fontRendererObj.getStringWidth(values[i]), textY,
                colors[i]);
        }
        drawTrend(type.bytes, columnRight(4) - TREND_COLUMN + 8, y + 2, columnRight(4), y + ROW - 2, modColor);
        int textRight = columnRight(0) - COUNT_COLUMN + 2;

        int x = listLeft + 7;
        Theme.fill(x, y + ROW / 2 - 2, x + 4, y + ROW / 2 + 2, modColor);
        x += 7;
        String mod = modFilter < 0 ? "  " + modName : "";
        int modWidth = mod.isEmpty() ? 0 : Math.min(fontRendererObj.getStringWidth(mod), 100);
        int room = textRight - x - modWidth;
        boolean matched = !query.isEmpty() && matches(type);
        String name = type.simpleName();
        String shown = Theme.ellipsize(fontRendererObj, name, room);
        fontRendererObj.drawString(shown, x, textY, matched ? WARN : Theme.TEXT);
        int after = x + fontRendererObj.getStringWidth(shown);
        String pkg = "  " + type.packageName();
        if (after + 20 < x + room && !type.packageName()
            .isEmpty()) {
            fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, pkg, x + room - after), after, textY,
                Theme.TEXT_DISABLED);
        }
        if (!mod.isEmpty()) {
            fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, mod, modWidth), textRight - modWidth, textY,
                Theme.blend(modColor, Theme.TEXT_DISABLED, 0.35));
        }
        if (hovered) {
            hit(x, y, textRight, y + ROW, () -> selected = selected == type ? null : type, tooltipOf(type));
        }
    }

    /** The bytes of each count as a small line, from its lowest to its highest. */
    private void drawTrend(long[] values, int x0, int y0, int x1, int y1, int color) {
        if (values.length < 2 || x1 - x0 < 6) {
            return;
        }
        long low = Long.MAX_VALUE, high = Long.MIN_VALUE;
        for (long value : values) {
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        double[] xs = new double[values.length], ys = new double[values.length];
        int[] colors = new int[values.length];
        int lineColor = high == low ? Theme.TEXT_DISABLED
            : last(values) > first(values) ? Theme.blend(Theme.DANGER, color, 0.3)
                : Theme.blend(Theme.SUCCESS, color, 0.3);
        for (int i = 0; i < values.length; i++) {
            xs[i] = x0 + (x1 - x0) * i / (double) (values.length - 1);
            ys[i] = high == low ? (y0 + y1) / 2.0 : y1 - (y1 - y0) * (values[i] - low) / (double) (high - low);
            colors[i] = lineColor;
        }
        Draw.line(xs, ys, colors, 1f);
    }

    private String tooltipOf(HeapClass type) {
        StringBuilder text = new StringBuilder(type.name);
        text.append("\n")
            .append(I18n.format("lagfinder.methods.mod_of", heap.modName(type.mod)));
        text.append("\n")
            .append(I18n.format("lagfinder.memory.line", count(last(type.instances)), bytes(last(type.bytes))));
        text.append("\n")
            .append(I18n.format("lagfinder.memory.change", countDelta(delta(type.instances)),
                bytesDelta(delta(type.bytes))));
        long objects = last(type.instances);
        if (objects > 0) {
            text.append("\n")
                .append(I18n.format("lagfinder.memory.object_size", bytes(last(type.bytes) / (double) objects)));
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- The pick

    private void drawDetails() {
        int x0 = listLeft, y0 = listBottom + 6, x1 = listRight, y1 = bodyBottom;
        Theme.fill(x0, y0, x1, y1, CARD);
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);
        HeapClass type = selected;
        int x = x0 + 8, y = y0 + 7;
        if (type == null) {
            Icons.draw(Icons.SMALL_SEARCH, x0 + 8, y0 + 8, Theme.TEXT_MUTED);
            String summary;
            if (snapshots() > 0) {
                int lastSnapshot = snapshots() - 1;
                summary = I18n.format("lagfinder.memory.summary", snapshots(),
                    count(heap.totalInstances[0]), count(heap.totalInstances[lastSnapshot]),
                    bytes(heap.totalBytes[0]), bytes(heap.totalBytes[lastSnapshot]),
                    heap.classCount[lastSnapshot]);
            } else {
                summary = I18n.format("lagfinder.memory.no_snapshots");
            }
            fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, summary, x1 - x0 - 30), x0 + 19, y0 + 8,
                Theme.TEXT_MUTED);
            fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.memory.hint"),
                x1 - x0 - 30), x0 + 19, y0 + 22, Theme.TEXT_DISABLED);
            return;
        }
        String modName = heap.modName(type.mod);
        int modColor = Draw.modColor(modName);
        Theme.fill(x, y + 1, x + 6, y + 7, modColor);
        int pillWidth = fontRendererObj.getStringWidth(modName) + 10;
        int textRight = x1 - 8;
        int pillX = textRight - pillWidth;
        Theme.fill(pillX, y - 2, textRight, y + 10, Theme.CONTROL);
        Theme.outline(pillX, y - 2, textRight, y + 10, modColor);
        fontRendererObj.drawString(modName, pillX + 5, y, modColor);
        fontRendererObj.drawStringWithShadow(Theme.ellipsize(fontRendererObj, type.name, pillX - 6 - (x + 10)),
            x + 10, y, Theme.TEXT);
        y += 14;
        // Each count of it, one after the other.
        StringBuilder line = new StringBuilder();
        for (int s = 0; s < type.bytes.length; s++) {
            if (s > 0) {
                line.append("  →  ");
            }
            line.append(Names.clock(heap.snapshotSec[s]))
                .append(' ')
                .append(count(type.instances[s]))
                .append(" / ")
                .append(bytes(type.bytes[s]));
        }
        line.append("   (")
            .append(percentChange(first(type.bytes), last(type.bytes)))
            .append(')');
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, line.toString(), textRight - x), x, y,
            Theme.TEXT_MUTED);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
