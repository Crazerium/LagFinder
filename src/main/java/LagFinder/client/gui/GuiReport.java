package LagFinder.client.gui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import LagFinder.client.ClientScanner;
import LagFinder.client.Highlighter;
import LagFinder.client.Names;
import LagFinder.client.gui.ui.FlatButton;
import LagFinder.client.gui.ui.FlatTextField;
import LagFinder.client.gui.ui.Icons;
import LagFinder.client.gui.ui.ScaledScreen;
import LagFinder.client.gui.ui.Smooth;
import LagFinder.client.gui.ui.Theme;
import LagFinder.client.gui.ui.WindowHeader;
import LagFinder.core.Category;
import LagFinder.core.Report;
import LagFinder.core.ReportEntry;
import LagFinder.core.ScanMode;
import LagFinder.network.Net;

/**
 * A finished scan. Across the top, how the server (or the client) did; under it, one tab per kind of work. On the
 * left, the tab's tree: mods, the kinds of things of each mod, and each of those things in the world, sorted by
 * their average time, their worst single tick or how often they spiked. On the right, the rate over the scan with
 * the load of what is picked over it (its average per tick in each moment and, lighter, its worst tick then), and
 * under it all about the pick, with buttons to teleport to it and highlight it in the world.
 */
public class GuiReport extends ScaledScreen {

    private static final int ID_CLOSE = 0, ID_SORT = 1, ID_METHODS = 2;
    private static final int MOD_ROW = 18, TYPE_ROW = 16, INSTANCE_ROW = 14;
    private static final int AVG_COLUMN = 54, PEAK_COLUMN = 50;
    private static final int CARD = 0xFF12161B, SELECTED = 0xFF1E2C40;
    private static final int WARN = 0xFFF2C14E, ORANGE = 0xFFFF8C42;

    private enum Sort {

        AVERAGE("lagfinder.sort.average"),
        PEAK("lagfinder.sort.peak"),
        SPIKES("lagfinder.sort.spikes");

        final String key;

        Sort(String key) {
            this.key = key;
        }
    }

    private static Sort sort = Sort.AVERAGE;

    private final Report report;
    private final GuiScreen parent;
    private int tab;
    private final Set<ReportEntry> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
    private ReportEntry selected;

    /** A line of the tree as shown: the entry, how deep it is, and where (GUI pixels from the list's top). */
    private static final class Row {

        final ReportEntry entry;
        final int y, height;

        Row(ReportEntry entry, int y, int height) {
            this.entry = entry;
            this.y = y;
            this.height = height;
        }
    }

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

    private final List<Row> rows = new ArrayList<>();
    private int contentHeight;
    private final List<Hit> hits = new ArrayList<>();
    private int clipX0 = Integer.MIN_VALUE, clipY0 = Integer.MIN_VALUE, clipX1 = Integer.MAX_VALUE,
        clipY1 = Integer.MAX_VALUE;
    private final Map<Object, Smooth> lights = new HashMap<>();
    private int scroll;
    private final Smooth shownScroll = new Smooth(0);
    private final Smooth tabMark = new Smooth(-1);

    private int panelLeft, panelTop, panelRight, panelBottom;
    private int tilesY, tabsY, bodyTop, bodyBottom;
    private int listLeft, listRight, listTop;
    private int rightLeft, rightRight, graphBottom;
    private FlatTextField search;
    private FlatButton sortButton, methodsButton;
    private String lastSearch = "";
    /** The worst things of the whole scan, for the overview: by time in all, and by worst tick. */
    private List<ReportEntry> byAverage, bySpike;

    public GuiReport(Report report) {
        this.report = report;
        GuiScreen open = net.minecraft.client.Minecraft.getMinecraft().currentScreen;
        this.parent = open instanceof GuiLagFinder ? open : null;
        Names.clear();
        ClientScanner.INSTANCE.markSeen();
        // Starts on the tab that took the most time.
        double most = -1;
        for (int i = 0; i < report.categories.size(); i++) {
            ReportEntry category = report.categories.get(i);
            if (category.totalMs > most && category.category != Category.WORLDS) {
                most = category.totalMs;
                tab = i;
            }
        }
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        panelLeft = 6;
        panelTop = 4;
        panelRight = width - 6;
        panelBottom = height - 4;
        tilesY = panelTop + WindowHeader.HEIGHT + 8;
        tabsY = tilesY + 30 + 8;
        bodyTop = tabsY + 20 + 8;
        bodyBottom = panelBottom - 8;
        int inner = panelRight - panelLeft - 24;
        int listWidth = Math.max(250, Math.min(420, inner * 42 / 100));
        listLeft = panelLeft + 8;
        listRight = listLeft + listWidth;
        listTop = bodyTop + 34;
        rightLeft = listRight + 8;
        rightRight = panelRight - 8;
        graphBottom = bodyTop + (bodyBottom - bodyTop) * 52 / 100;

        String text = search != null ? search.getText() : "";
        search = new FlatTextField(fontRendererObj, listLeft, bodyTop, listWidth - 86, 16);
        search.setHint(I18n.format("lagfinder.gui.search"));
        search.setMaxStringLength(64);
        search.setText(text);
        search.setOnCleared(this::rebuild);

        buttonList.clear();
        sortButton = new FlatButton(ID_SORT, listRight - 82, bodyTop, 82, 16, "");
        sortButton.icon = Icons.SMALL_SORT;
        buttonList.add(sortButton);
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, panelRight, panelTop));
        // The call tree of the scan's thread, when it was sampled.
        String methods = I18n.format("lagfinder.gui.methods");
        int methodsWidth = fontRendererObj.getStringWidth(methods) + 24;
        methodsButton = new FlatButton(ID_METHODS, panelRight - WindowHeader.CLOSE_ROOM - methodsWidth, panelTop + 7,
            methodsWidth, 16, methods);
        methodsButton.icon = Icons.SMALL_TREE;
        methodsButton.visible = report.profile != null;
        buttonList.add(methodsButton);
        updateSortButton();
        rebuild();
    }

    private void updateSortButton() {
        sortButton.displayString = I18n.format(sort.key);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private ReportEntry category() {
        return tab >= 0 && tab < report.categories.size() ? report.categories.get(tab) : null;
    }

    // ---------------------------------------------------------------- Tree

    private Comparator<ReportEntry> order() {
        switch (sort) {
            case PEAK:
                return (a, b) -> Float.compare(b.maxMs, a.maxMs);
            case SPIKES:
                return (a, b) -> a.spikes != b.spikes ? Integer.compare(b.spikes, a.spikes)
                    : Double.compare(b.totalMs, a.totalMs);
            default:
                return (a, b) -> Double.compare(b.totalMs, a.totalMs);
        }
    }

    private List<ReportEntry> sorted(List<ReportEntry> entries) {
        List<ReportEntry> list = new ArrayList<>(entries);
        list.sort(order());
        return list;
    }

    private static boolean matches(ReportEntry entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (Names.of(entry)
            .toLowerCase(Locale.ROOT)
            .contains(query)) {
            return true;
        }
        if (entry.detail != null && entry.detail.toLowerCase(Locale.ROOT)
            .contains(query)) {
            return true;
        }
        if (entry.label != null && entry.label.toLowerCase(Locale.ROOT)
            .contains(query)) {
            return true;
        }
        return entry.level == 3 && Names.position(entry)
            .contains(query);
    }

    /** Lays the tree out again: after a change of tab, sorting, search or what is open. */
    private void rebuild() {
        rows.clear();
        lastSearch = search.getText();
        String query = lastSearch.trim()
            .toLowerCase(Locale.ROOT);
        ReportEntry category = category();
        int y = 0;
        if (category != null) {
            for (ReportEntry mod : sorted(category.children)) {
                boolean modMatches = matches(mod, query);
                List<ReportEntry> types = new ArrayList<>();
                List<List<ReportEntry>> typeInstances = new ArrayList<>();
                for (ReportEntry type : sorted(mod.children)) {
                    boolean typeMatches = modMatches || matches(type, query);
                    List<ReportEntry> instances = new ArrayList<>();
                    for (ReportEntry instance : sorted(type.children)) {
                        if (typeMatches || matches(instance, query)) {
                            instances.add(instance);
                        }
                    }
                    if (typeMatches || !instances.isEmpty()) {
                        types.add(type);
                        typeInstances.add(instances);
                    }
                }
                if (!modMatches && types.isEmpty()) {
                    continue;
                }
                rows.add(new Row(mod, y, MOD_ROW));
                y += MOD_ROW;
                if (!expanded.contains(mod) && query.isEmpty()) {
                    continue;
                }
                for (int i = 0; i < types.size(); i++) {
                    ReportEntry type = types.get(i);
                    rows.add(new Row(type, y, TYPE_ROW));
                    y += TYPE_ROW;
                    boolean openByQuery = !query.isEmpty() && !modMatches && !matches(type, query);
                    if (!expanded.contains(type) && !openByQuery) {
                        continue;
                    }
                    for (ReportEntry instance : typeInstances.get(i)) {
                        rows.add(new Row(instance, y, INSTANCE_ROW));
                        y += INSTANCE_ROW;
                    }
                }
            }
        }
        contentHeight = y;
        clampScroll();
    }

    private int listHeight() {
        return bodyBottom - listTop;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, contentHeight - listHeight()));
    }

    /** Picks the entry, opening the lines above it and scrolling it into view (from the overview or the details). */
    private void reveal(ReportEntry entry) {
        int index = report.categories.indexOf(root(entry));
        if (index >= 0 && index != tab) {
            tab = index;
            if (!search.getText()
                .isEmpty()) {
                search.setText("");
            }
        }
        for (ReportEntry up = entry.parent; up != null; up = up.parent) {
            expanded.add(up);
        }
        selected = entry;
        rebuild();
        for (Row row : rows) {
            if (row.entry == entry) {
                if (row.y < scroll || row.y + row.height > scroll + listHeight()) {
                    scroll = row.y - listHeight() / 3;
                    clampScroll();
                }
                break;
            }
        }
    }

    private static ReportEntry root(ReportEntry entry) {
        while (entry.parent != null) {
            entry = entry.parent;
        }
        return entry;
    }

    private void select(ReportEntry entry) {
        selected = entry;
    }

    private void toggle(ReportEntry entry) {
        if (!expanded.remove(entry)) {
            expanded.add(entry);
        }
        rebuild();
    }

    // ---------------------------------------------------------------- Input

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            close();
        } else if (button.id == ID_METHODS && report.profile != null) {
            GuiMethods methods = new GuiMethods(report);
            // A mod picked in the report: its part of the call tree.
            ReportEntry mod = selected;
            while (mod != null && mod.level > 1) {
                mod = mod.parent;
            }
            mc.displayGuiScreen(mod != null && mod.level == 1 ? methods.filtered(mod.name) : methods);
        } else if (button.id == ID_SORT) {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            updateSortButton();
            rebuild();
        }
    }

    private void close() {
        mc.displayGuiScreen(parent);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
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
        if (Theme.inside(x, y, listLeft, listTop, listRight, bodyBottom)) {
            scroll -= Integer.signum(wheel) * 3 * TYPE_ROW;
            clampScroll();
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
            moveSelection(keyCode == Keyboard.KEY_UP ? -1 : 1);
            return;
        }
        if ((keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_RIGHT) && selected != null
            && !search.isFocused()) {
            boolean open = keyCode == Keyboard.KEY_RIGHT;
            if (open && !selected.children.isEmpty()) {
                expanded.add(selected);
            } else if (!open && expanded.contains(selected)) {
                expanded.remove(selected);
            } else if (!open && selected.parent != null && selected.parent.level > 0) {
                selected = selected.parent;
            }
            rebuild();
            return;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            tab = (tab + (isShiftKeyDown() ? report.categories.size() - 1 : 1)) % report.categories.size();
            scroll = 0;
            rebuild();
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

    private void moveSelection(int step) {
        if (rows.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).entry == selected) {
                index = i;
            }
        }
        index = Math.max(0, Math.min(rows.size() - 1, index < 0 ? 0 : index + step));
        Row row = rows.get(index);
        selected = row.entry;
        if (row.y < scroll) {
            scroll = row.y;
        } else if (row.y + row.height > scroll + listHeight()) {
            scroll = row.y + row.height - listHeight();
        }
        clampScroll();
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
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

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        hits.clear();
        if (!search.getText()
            .equals(lastSearch)) {
            rebuild();
        }
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(panelLeft, panelTop, panelRight, panelBottom);
        String title = I18n.format(report.mode == ScanMode.TPS ? "lagfinder.report.title_tps" : "lagfinder.report.title_fps");
        String date = new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(report.startedAt));
        String subtitle = date + "  ·  " + Names.clock(report.durationSec)
            + (report.source == null || report.source.isEmpty() ? "" : "  ·  " + report.source);
        WindowHeader.draw(
            fontRendererObj,
            panelLeft,
            panelTop,
            panelRight,
            methodsButton.visible ? methodsButton.xPosition - 4 : panelRight - WindowHeader.CLOSE_ROOM,
            Icons.STATS,
            title,
            subtitle,
            Theme.TEXT_MUTED,
            null);

        drawTiles();
        drawTabs(mouseX, mouseY);
        drawList(mouseX, mouseY);
        drawGraph(mouseX, mouseY);
        drawDetails(mouseX, mouseY);
        search.drawTextBox();
        super.drawScaled(mouseX, mouseY, partialTicks);

        // Tooltips of what is under the mouse, over everything else.
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
            showTooltip(I18n.format("lagfinder.tip.sort"), mouseX, mouseY);
        } else if (methodsButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(I18n.format("lagfinder.gui.methods") + "\n" + I18n.format("lagfinder.tip.methods"), mouseX,
                mouseY);
        }
    }

    /** A tooltip: its first line as a title, the rest (split at "\n" and wrapped) muted under it. */
    private void showTooltip(String text, int mouseX, int mouseY) {
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        String[] parts = text.split("\\\\n|\n");
        for (int i = 0; i < parts.length; i++) {
            for (Object line : fontRendererObj.listFormattedStringToWidth(parts[i], 240)) {
                lines.add((String) line);
                colors.add(i == 0 ? Theme.TEXT : Theme.TEXT_MUTED);
            }
        }
        Draw.tooltip(fontRendererObj, lines, colors, mouseX, mouseY, width, height);
    }

    /** Health of the whole scan: rate, worst moment, average and worst tick. */
    private void drawTiles() {
        boolean tps = report.mode == ScanMode.TPS;
        float target = report.mode.targetRate();
        float budget = report.mode.budgetMs();
        float average = report.averageRate(), minimum = report.minimumRate();
        float averageMs = report.averagePeriodMs(), worstMs = report.worstPeriodMs();
        String[] labels = { I18n.format(tps ? "lagfinder.tile.avg_tps" : "lagfinder.tile.avg_fps"),
            I18n.format(tps ? "lagfinder.tile.min_tps" : "lagfinder.tile.min_fps"),
            I18n.format(tps ? "lagfinder.tile.avg_tick" : "lagfinder.tile.avg_frame"),
            I18n.format(tps ? "lagfinder.tile.worst_tick" : "lagfinder.tile.worst_frame"),
            I18n.format(tps ? "lagfinder.tile.ticks" : "lagfinder.tile.frames") };
        String[] values = { String.format("%.1f", average), String.format("%.1f", minimum), Names.ms(averageMs),
            Names.ms(worstMs), String.valueOf(report.periods) };
        int[] colors = { Draw.rateColor(average, target), Draw.rateColor(minimum, target),
            averageMs > budget ? Theme.DANGER : averageMs > budget * 0.7f ? WARN : Theme.SUCCESS,
            worstMs > budget * 4 ? Theme.DANGER : worstMs > budget ? WARN : Theme.SUCCESS, Theme.TEXT };
        if (!tps) {
            // Frames have no fixed target: the rate is told plainly.
            colors[0] = average >= 55 ? Theme.SUCCESS : average >= 30 ? WARN : Theme.DANGER;
            colors[1] = minimum >= 55 ? Theme.SUCCESS : minimum >= 30 ? WARN : Theme.DANGER;
        }
        int count = labels.length, gap = 6;
        int inner = panelRight - panelLeft - 16;
        int each = (inner - gap * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            int x0 = panelLeft + 8 + i * (each + gap), x1 = x0 + each;
            Theme.fill(x0, tilesY, x1, tilesY + 30, CARD);
            Theme.outline(x0, tilesY, x1, tilesY + 30, Theme.BORDER);
            Theme.fill(x0, tilesY, x0 + 2, tilesY + 30, colors[i]);
            fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, labels[i], each - 12), x0 + 8, tilesY + 5, Theme.TEXT_MUTED);
            fontRendererObj.drawStringWithShadow(values[i], x0 + 8, tilesY + 17, colors[i]);
        }
    }

    private static String[] icon(Category category) {
        switch (category) {
            case TILE_ENTITIES:
                return Icons.SMALL_CUBE;
            case ENTITIES:
                return Icons.SMALL_CREEPER;
            case BLOCK_TICKS:
                return Icons.SMALL_CLOCK;
            case EVENTS:
                return Icons.SMALL_BOLT;
            case WORLDS:
                return Icons.SMALL_GLOBE;
            case TILE_RENDER:
                return Icons.SMALL_FRAME;
            case ENTITY_RENDER:
                return Icons.SMALL_PERSON;
            default:
                return Icons.SMALL_GRID;
        }
    }

    private void drawTabs(int mouseX, int mouseY) {
        int count = report.categories.size();
        if (count == 0) {
            return;
        }
        int inner = panelRight - panelLeft - 16;
        int each = inner / count;
        if (tabMark.get() < 0) {
            tabMark.set(panelLeft + 8 + tab * each);
        }
        double markX = tabMark.update(panelLeft + 8 + tab * each, 18);
        Theme.fill(panelLeft + 8, tabsY + 19, panelRight - 8, tabsY + 20, Theme.BORDER);
        for (int i = 0; i < count; i++) {
            ReportEntry category = report.categories.get(i);
            int x0 = panelLeft + 8 + i * each, x1 = i == count - 1 ? panelRight - 8 : x0 + each;
            boolean active = i == tab;
            boolean hovered = Theme.inside(mouseX, mouseY, x0, tabsY, x1, tabsY + 19);
            double lit = light(category, hovered || active);
            Theme.fill(x0, tabsY, x1, tabsY + 19, Theme.blend(0x00000000, active ? 0xFF1A2330 : 0xFF1A1F27, lit));
            int color = active ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
            Icons.draw(icon(category.category), x0 + 6, tabsY + 6, active ? Theme.ACCENT : Theme.TEXT_MUTED);
            String value = Names.ms(report.perPeriod(category));
            int valueWidth = fontRendererObj.getStringWidth(value);
            String name = Theme.ellipsize(
                fontRendererObj,
                Names.category(category.category, report.mode),
                x1 - x0 - 26 - valueWidth);
            fontRendererObj.drawString(name, x0 + 17, tabsY + 6, color);
            fontRendererObj.drawString(value, x1 - 5 - valueWidth, tabsY + 6, active ? Theme.ACCENT : Theme.TEXT_DISABLED);
            int index = i;
            hit(x0, tabsY, x1, tabsY + 19, () -> {
                if (tab != index) {
                    tab = index;
                    scroll = 0;
                    rebuild();
                }
            }, I18n.format("lagfinder.category." + category.category.id) + "\n"
                + I18n.format("lagfinder.tip.category." + category.category.id));
        }
        int markWidth = (int) (each * 1.0);
        Theme.fill((int) markX, tabsY + 18, (int) markX + markWidth, tabsY + 20, Theme.ACCENT);
    }

    // ---------------------------------------------------------------- The tree

    private void drawList(int mouseX, int mouseY) {
        ReportEntry category = category();
        // Column titles.
        int headY = bodyTop + 21;
        fontRendererObj.drawString(I18n.format("lagfinder.column.name"), listLeft + 4, headY, Theme.TEXT_DISABLED);
        String avg = I18n.format(report.mode == ScanMode.TPS ? "lagfinder.column.avg_tick" : "lagfinder.column.avg_frame");
        String peak = I18n.format("lagfinder.column.peak");
        fontRendererObj.drawString(avg, listRight - PEAK_COLUMN - 6 - fontRendererObj.getStringWidth(avg), headY,
            sort == Sort.AVERAGE ? Theme.ACCENT : Theme.TEXT_DISABLED);
        fontRendererObj.drawString(peak, listRight - 6 - fontRendererObj.getStringWidth(peak), headY,
            sort == Sort.PEAK ? Theme.ACCENT : Theme.TEXT_DISABLED);
        hit(listRight - PEAK_COLUMN - AVG_COLUMN, headY - 2, listRight - PEAK_COLUMN, headY + 10, null,
            I18n.format("lagfinder.tip.avg"));
        hit(listRight - PEAK_COLUMN, headY - 2, listRight, headY + 10, null, I18n.format("lagfinder.tip.peak"));

        Theme.fill(listLeft, listTop, listRight, bodyBottom, CARD);
        Theme.outline(listLeft, listTop - 1, listRight, bodyBottom, Theme.BORDER);
        if (category == null || rows.isEmpty()) {
            String empty = I18n.format(
                lastSearch.isEmpty() ? "lagfinder.gui.nothing" : "lagfinder.gui.nothing_found");
            Theme.centered(fontRendererObj, empty, (listLeft + listRight) / 2, listTop + 20, Theme.TEXT_DISABLED);
            return;
        }
        int offset = (int) Math.round(shownScroll.update(scroll, 18));
        int top = listTop + 1, bottom = bodyBottom - 1;
        Theme.clip(listLeft + 1, top, listRight - 1, bottom);
        clipHits(listLeft + 1, top, listRight - 1, bottom);
        double categoryTotal = Math.max(1e-9, category.totalMs);
        for (Row row : rows) {
            int y = top + row.y - offset;
            if (y + row.height < top || y > bottom) {
                continue;
            }
            drawRow(row, y, mouseX, mouseY, categoryTotal);
        }
        Theme.unclip();
        unclipHits();
        if (contentHeight > listHeight()) {
            Theme.scrollbar(
                listRight - 4,
                top + 2,
                bottom - 2,
                listHeight(),
                contentHeight,
                offset / (double) Math.max(1, contentHeight - listHeight()),
                Theme.inside(mouseX, mouseY, listLeft, top, listRight, bottom));
        }
    }

    private int severity(double perPeriodMs) {
        float budget = report.mode.budgetMs();
        if (perPeriodMs >= budget * 0.1) {
            return Theme.DANGER;
        }
        if (perPeriodMs >= budget * 0.02) {
            return WARN;
        }
        return Theme.TEXT;
    }

    private int peakColor(ReportEntry entry) {
        float budget = report.mode.budgetMs();
        if (entry.maxMs >= budget * 0.5f) {
            return Theme.DANGER;
        }
        if (entry.maxMs >= report.spikeMs) {
            return ORANGE;
        }
        return Theme.TEXT_MUTED;
    }

    /** Whether it now and then takes far longer than it usually does: what lags "once in a while". */
    private boolean isSpiky(ReportEntry entry) {
        double average = report.perPeriod(entry);
        return entry.maxMs >= report.spikeMs * 2 && entry.maxMs > average * 8;
    }

    private void drawRow(Row row, int y, int mouseX, int mouseY, double categoryTotal) {
        ReportEntry entry = row.entry;
        boolean hovered = Theme.inside(mouseX, mouseY, listLeft + 1, y, listRight - 1, y + row.height)
            && Theme.inside(mouseX, mouseY, listLeft, listTop, listRight, bodyBottom);
        boolean isSelected = entry == selected;
        double lit = light(entry, hovered);
        if (isSelected) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + row.height, SELECTED);
            Theme.fill(listLeft + 1, y, listLeft + 3, y + row.height, Theme.ACCENT);
        } else if (lit > 0.01) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + row.height, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        int indent = (entry.level - 1) * 12;
        int x = listLeft + 6 + indent;
        int textY = y + (row.height - 8) / 2;
        boolean hasChildren = !entry.children.isEmpty();
        // The line's own clicks first: the buttons drawn on it later take precedence.
        hit(listLeft + 1, y, listLeft + 15 + indent, y + row.height,
            hasChildren ? () -> toggle(entry) : () -> select(entry), null);
        hit(listLeft + 15 + indent, y, listRight - 1, y + row.height, () -> {
            boolean wasSelected = selected == entry;
            select(entry);
            if (hasChildren && (wasSelected || !expanded.contains(entry))) {
                toggle(entry);
            }
        }, null);
        if (hasChildren) {
            String[] arrow = expanded.contains(entry) || !lastSearch.isEmpty() ? Icons.SECTION_OPEN
                : Icons.SECTION_CLOSED;
            Icons.draw(arrow, x + (5 - Icons.width(arrow)) / 2, y + (row.height - arrow.length) / 2,
                hovered ? Theme.TEXT : Theme.TEXT_MUTED);
        }
        x += 9;

        // The values on the right.
        double average = report.perPeriod(entry);
        String avgText = Names.ms(average);
        String peakText = Names.ms(entry.maxMs);
        int avgRight = listRight - PEAK_COLUMN - 6;
        fontRendererObj.drawString(avgText, avgRight - fontRendererObj.getStringWidth(avgText), textY,
            entry.level == 3 ? Theme.blend(severity(average), Theme.TEXT_MUTED, 0.3) : severity(average));
        fontRendererObj.drawString(peakText, listRight - 6 - fontRendererObj.getStringWidth(peakText), textY,
            peakColor(entry));
        int textRight = avgRight - AVG_COLUMN + 4;
        if (isSpiky(entry)) {
            Icons.draw(Icons.SMALL_SPIKE, textRight - 2, y + (row.height - 7) / 2, ORANGE);
            textRight -= 10;
        }

        if (entry.level == 1) {
            int color = Draw.modColor(entry.name);
            Theme.fill(x, y + row.height / 2 - 3, x + 6, y + row.height / 2 + 3, color);
            x += 10;
            String count = "  " + entry.children.size();
            int countWidth = fontRendererObj.getStringWidth(count);
            String name = Theme.ellipsize(fontRendererObj, entry.name, textRight - x - countWidth);
            fontRendererObj.drawStringWithShadow(name, x, textY, Theme.TEXT);
            fontRendererObj.drawString(count, x + fontRendererObj.getStringWidth(name), textY, Theme.TEXT_DISABLED);
            // Its share of the tab, as a thin bar along the bottom.
            int barWidth = (int) Math.round((listRight - listLeft - 10) * Math.min(1, entry.totalMs / categoryTotal));
            Theme.fill(listLeft + 5, y + row.height - 2, listLeft + 5 + Math.max(1, barWidth), y + row.height - 1,
                Theme.blend(color, 0x00000000, 0.35));
        } else if (entry.level == 2) {
            ItemStack stack = Names.icon(entry);
            if (stack != null) {
                Draw.item(stack, x, y + (row.height - 12) / 2, 12);
            } else {
                Icons.draw(icon(entry.category), x + 2, y + (row.height - 7) / 2, Theme.TEXT_MUTED);
            }
            x += 15;
            String count = entry.instances > 1 ? "  ×" + entry.instances : "";
            int countWidth = fontRendererObj.getStringWidth(count);
            String name = Theme.ellipsize(fontRendererObj, Names.of(entry), textRight - x - countWidth);
            fontRendererObj.drawString(name, x, textY, Theme.TEXT);
            fontRendererObj.drawString(count, x + fontRendererObj.getStringWidth(name), textY, Theme.TEXT_DISABLED);
        } else {
            boolean lit2 = Highlighter.INSTANCE.isHighlighted(entry);
            Theme.fill(x + 1, y + row.height / 2 - 1, x + 3, y + row.height / 2 + 1,
                lit2 ? Theme.ACCENT : Theme.TEXT_DISABLED);
            x += 7;
            // Action buttons while the mouse is over it (and the eye while it is highlighted).
            int actionsRight = textRight;
            if (entry.hasLocation() && (hovered || lit2)) {
                actionsRight = drawRowActions(entry, y, row.height, textRight, hovered, lit2);
            }
            String position = Names.position(entry);
            String text = entry.label != null ? position + "  " : position;
            String dim = dimensionTag(entry);
            fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, text, actionsRight - x), x, textY, Theme.TEXT_MUTED);
            int after = x + fontRendererObj.getStringWidth(text);
            if (entry.label != null && after < actionsRight) {
                fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, entry.label, actionsRight - after), after,
                    textY, Theme.TEXT_DISABLED);
                after += fontRendererObj.getStringWidth(entry.label) + 4;
            }
            if (dim != null && after + fontRendererObj.getStringWidth(dim) < actionsRight) {
                fontRendererObj.drawString(dim, after + 2, textY, 0xFFB37FEB);
            }
        }
    }

    /** The dimension of an instance when it isn't the player's, e.g. "[Nether]"; null otherwise. */
    private String dimensionTag(ReportEntry entry) {
        int playerDim = mc.theWorld != null ? mc.theWorld.provider.dimensionId : 0;
        if (!entry.hasLocation() || entry.dim == playerDim) {
            return null;
        }
        return "[" + report.dimensionName(entry.dim) + "]";
    }

    /** Teleport and highlight buttons on an instance's line; returns where the text must end. */
    private int drawRowActions(ReportEntry entry, int y, int height, int right, boolean hovered, boolean highlighted) {
        int size = 11;
        int x = right - size;
        int top = y + (height - size) / 2;
        boolean canTeleport = ClientScanner.INSTANCE.teleportAllowed();
        // Highlight.
        drawSmallButton(Icons.SMALL_EYE, x, top, size, highlighted ? Theme.ACCENT : Theme.TEXT_MUTED, highlighted);
        hit(x, top, x + size, top + size, () -> Highlighter.INSTANCE.toggle(entry),
            I18n.format(highlighted ? "lagfinder.action.unhighlight" : "lagfinder.action.highlight"));
        x -= size + 3;
        if (hovered) {
            drawSmallButton(Icons.SMALL_TELEPORT, x, top, size, canTeleport ? Theme.TEXT_MUTED : Theme.TEXT_DISABLED,
                false);
            hit(x, top, x + size, top + size, canTeleport ? () -> teleport(entry) : null,
                I18n.format(canTeleport ? "lagfinder.action.teleport" : "lagfinder.action.no_teleport"));
            x -= 3;
        } else {
            x += size;
        }
        return x - 2;
    }

    private void drawSmallButton(String[] icon, int x, int y, int size, int color, boolean on) {
        Theme.fill(x, y, x + size, y + size, on ? Theme.ACCENT_DIM : Theme.CONTROL);
        Theme.outline(x, y, x + size, y + size, on ? Theme.ACCENT : Theme.BORDER);
        Icons.draw(icon, x + (size - Icons.width(icon)) / 2, y + (size - icon.length) / 2, on ? Theme.TEXT : color);
    }

    private void teleport(ReportEntry entry) {
        if (!entry.hasLocation() || !ClientScanner.INSTANCE.teleportAllowed()) {
            return;
        }
        Net.sendToServer(new Net.Teleport(entry.dim, entry.x, entry.y, entry.z));
        if (!Highlighter.INSTANCE.isHighlighted(entry)) {
            Highlighter.INSTANCE.toggle(entry);
        }
        mc.displayGuiScreen(null);
    }

    // ---------------------------------------------------------------- The graph

    private void drawGraph(int mouseX, int mouseY) {
        int x0 = rightLeft, y0 = bodyTop, x1 = rightRight, y1 = graphBottom;
        Theme.fill(x0, y0, x1, y1, CARD);
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);
        boolean tps = report.mode == ScanMode.TPS;
        int n = report.buckets();
        ReportEntry shown = selected != null && selected.sum != null ? selected : null;
        int elementColor = selected == null ? 0xFF6E7681 : Draw.modColor(modOf(selected));

        // Legend.
        int lx = x0 + 8, ly = y0 + 6;
        Theme.fill(lx, ly + 3, lx + 8, ly + 5, Theme.SUCCESS);
        String rateName = tps ? "TPS" : "FPS";
        fontRendererObj.drawString(rateName, lx + 11, ly, Theme.TEXT);
        lx += 16 + fontRendererObj.getStringWidth(rateName);
        Theme.fill(lx, ly + 1, lx + 6, ly + 7, elementColor);
        String elementName = selected == null ? I18n.format(tps ? "lagfinder.graph.tick_time" : "lagfinder.graph.frame_time")
            : Names.of(selected) + (selected.level == 3 ? " (" + Names.position(selected) + ")" : "");
        String legend = I18n.format("lagfinder.graph.legend", elementName);
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, legend, x1 - lx - 18), lx + 9, ly, Theme.TEXT_MUTED);

        int px0 = x0 + 28, px1 = x1 - 40, py0 = y0 + 22, py1 = y1 - 14;
        if (px1 - px0 < 20 || py1 - py0 < 20 || n == 0) {
            return;
        }
        // Left axis: the rate.
        float rateMax = tps ? 20f : (float) Draw.niceCeil(Math.max(30, max(report.rate)) * 1.05);
        for (int i = 0; i <= 4; i++) {
            float value = rateMax * i / 4;
            int y = py1 - Math.round((py1 - py0) * i / 4f);
            Theme.fill(px0, y, px1, y + 1, i == 0 ? Theme.BORDER : 0x14FFFFFF);
            String label = value == Math.floor(value) ? String.valueOf((int) value) : String.format("%.1f", value);
            fontRendererObj.drawString(label, px0 - 4 - fontRendererObj.getStringWidth(label), y - 4, Theme.TEXT_DISABLED);
        }
        // Right axis: milliseconds of what is shown in bars.
        float[] avg = new float[n], peak = new float[n];
        boolean noGraph = selected != null && shown == null;
        for (int i = 0; i < n; i++) {
            if (shown != null) {
                avg[i] = report.perPeriod(shown, i);
                peak[i] = shown.max[i];
            } else if (selected == null) {
                avg[i] = report.avgMs[i];
                peak[i] = report.maxMs[i];
            }
        }
        float msMax = (float) Draw.niceCeil(Math.max(max(peak), 0.01f) * 1.08f);
        for (int i = 0; i <= 4; i++) {
            int y = py1 - Math.round((py1 - py0) * i / 4f);
            String label = Names.ms(msMax * i / 4).replace(" ", "");
            fontRendererObj.drawString(label, px1 + 4, y - 4, Theme.TEXT_DISABLED);
        }
        double step = (px1 - px0) / (double) n;
        double barWidth = Math.max(1, step - (step > 3 ? 1 : 0));
        if (!noGraph) {
            for (int i = 0; i < n; i++) {
                double bx = px0 + i * step;
                double avgY = py1 - (py1 - py0) * Math.min(1, avg[i] / msMax);
                double peakY = py1 - (py1 - py0) * Math.min(1, peak[i] / msMax);
                if (peak[i] > avg[i]) {
                    // How far its worst tick in this moment went: the swing to look at for things that lag now and then.
                    Draw.gradientRect(bx, peakY, bx + barWidth, avgY, Theme.blend(elementColor, 0x00000000, 0.45) & 0x80FFFFFF,
                        (elementColor & 0x00FFFFFF) | 0x20000000);
                    Draw.gradientRect(bx, peakY, bx + barWidth, peakY + 1, elementColor, elementColor);
                }
                if (avg[i] > 0) {
                    Draw.gradientRect(bx, avgY, bx + barWidth, py1, elementColor | 0xFF000000, Theme.blend(elementColor, 0xFF000000, 0.4));
                }
            }
            // The time a tick may take before the rate drops.
            float budget = report.mode.budgetMs();
            if (budget < msMax) {
                int by = py1 - Math.round((py1 - py0) * budget / msMax);
                for (int x = px0; x < px1; x += 6) {
                    Theme.fill(x, by, Math.min(px1, x + 3), by + 1, 0x80E5534B);
                }
                String label = Names.ms(budget).replace(" ", "");
                fontRendererObj.drawString(label, px1 - 2 - fontRendererObj.getStringWidth(label), by - 9, 0xA0E5534B);
            }
        } else {
            Theme.centered(fontRendererObj, I18n.format("lagfinder.graph.no_graph"), (px0 + px1) / 2, py0 + 10, Theme.TEXT_DISABLED);
        }
        // The rate as an area with its line on top, colored by how good it was.
        double[] xs = new double[n + 2], ys = new double[n + 2];
        int[] colors = new int[n + 2];
        for (int i = 0; i < n; i++) {
            xs[i + 1] = px0 + (i + 0.5) * step;
            ys[i + 1] = py1 - (py1 - py0) * Math.min(1, report.rate[i] / rateMax);
            colors[i + 1] = Draw.rateColor(report.rate[i], tps ? 20f : 60f);
        }
        xs[0] = px0;
        ys[0] = ys[1];
        colors[0] = colors[1];
        xs[n + 1] = px1;
        ys[n + 1] = ys[n];
        colors[n + 1] = colors[n];
        Draw.area(xs, ys, py1, 0x304C9AFF, 0x004C9AFF);
        Draw.line(xs, ys, colors, 1.5f);

        // Time along the bottom.
        for (int i = 0; i <= 4; i++) {
            String label = Names.clock(report.durationSec * i / 4);
            int x = px0 + (px1 - px0) * i / 4 - (i == 0 ? 0 : i == 4 ? fontRendererObj.getStringWidth(label) : fontRendererObj.getStringWidth(label) / 2);
            fontRendererObj.drawString(label, x, py1 + 4, Theme.TEXT_DISABLED);
        }

        // What was at the moment under the mouse.
        if (Theme.inside(mouseX, mouseY, px0, py0, px1, py1)) {
            int i = Math.max(0, Math.min(n - 1, (int) ((mouseX - px0) / step)));
            int cx = (int) Math.round(px0 + (i + 0.5) * step);
            Theme.fill(cx, py0, cx + 1, py1, 0x50FFFFFF);
            List<String> lines = new ArrayList<>();
            List<Integer> lineColors = new ArrayList<>();
            float from = i * report.bucketSec, to = Math.min(report.durationSec, (i + 1) * report.bucketSec);
            lines.add(Names.clock(from) + " – " + Names.clock(to));
            lineColors.add(Theme.TEXT_MUTED);
            lines.add(String.format("%s %.1f", rateName, report.rate[i]));
            lineColors.add(Draw.rateColor(report.rate[i], tps ? 20f : 60f));
            lines.add(I18n.format(tps ? "lagfinder.graph.tick" : "lagfinder.graph.frame", Names.ms(report.avgMs[i]),
                Names.ms(report.maxMs[i])));
            lineColors.add(Theme.TEXT);
            if (shown != null) {
                lines.add(Names.of(shown) + ":");
                lineColors.add(elementColor);
                lines.add("  " + I18n.format("lagfinder.graph.element", Names.ms(avg[i]), Names.ms(peak[i])));
                lineColors.add(Theme.TEXT);
            }
            Draw.tooltip(fontRendererObj, lines, lineColors, mouseX, mouseY, width, height);
        }
    }

    private static float max(float[] values) {
        float max = 0;
        for (float value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    private static String modOf(ReportEntry entry) {
        for (ReportEntry up = entry; up != null; up = up.parent) {
            if (up.level == 1) {
                return up.name;
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- Details

    private void drawDetails(int mouseX, int mouseY) {
        int x0 = rightLeft, y0 = graphBottom + 8, x1 = rightRight, y1 = bodyBottom;
        Theme.fill(x0, y0, x1, y1, CARD);
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);
        if (y1 - y0 < 40) {
            return;
        }
        if (selected == null) {
            drawOverview(x0, y0, x1, y1, mouseX, mouseY);
        } else {
            drawSelection(selected, x0, y0, x1, y1, mouseX, mouseY);
        }
    }

    /** With nothing picked: the worst things of the whole scan, in all tabs. */
    private void drawOverview(int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        if (byAverage == null) {
            List<ReportEntry> leaves = new ArrayList<>();
            for (ReportEntry category : report.categories) {
                if (category.category != Category.WORLDS) {
                    collectLeaves(category, leaves);
                }
            }
            byAverage = new ArrayList<>(leaves);
            byAverage.sort((a, b) -> Double.compare(b.totalMs, a.totalMs));
            bySpike = new ArrayList<>();
            for (ReportEntry leaf : leaves) {
                if (leaf.maxMs >= report.spikeMs) {
                    bySpike.add(leaf);
                }
            }
            bySpike.sort((a, b) -> Float.compare(b.maxMs, a.maxMs));
        }
        int columnWidth = (x1 - x0 - 24) / 2;
        int rowsFit = Math.max(1, (y1 - y0 - 30) / 14);
        drawTopList(I18n.format("lagfinder.overview.most_time"), Icons.SMALL_CLOCK, byAverage, false,
            x0 + 8, y0 + 8, x0 + 8 + columnWidth, rowsFit, mouseX, mouseY);
        drawTopList(I18n.format("lagfinder.overview.spikes"), Icons.SMALL_SPIKE, bySpike, true,
            x0 + 16 + columnWidth, y0 + 8, x1 - 8, rowsFit, mouseX, mouseY);
    }

    /** The lowest lines of the entry: instances where there are, kinds otherwise. */
    private static void collectLeaves(ReportEntry entry, List<ReportEntry> out) {
        if (entry.level >= 2 && (entry.children.isEmpty() || entry.level == 3)) {
            out.add(entry);
            return;
        }
        for (ReportEntry child : entry.children) {
            collectLeaves(child, out);
        }
    }

    private void drawTopList(String title, String[] titleIcon, List<ReportEntry> entries, boolean byPeak, int x0,
        int y0, int x1, int count, int mouseX, int mouseY) {
        Icons.draw(titleIcon, x0, y0 + 1, byPeak ? ORANGE : Theme.ACCENT);
        fontRendererObj.drawString(title.toUpperCase(), x0 + 11, y0, Theme.TEXT_MUTED);
        if (entries.isEmpty()) {
            fontRendererObj.drawString(I18n.format("lagfinder.overview.none"), x0, y0 + 16, Theme.TEXT_DISABLED);
            return;
        }
        for (int i = 0; i < entries.size() && i < count; i++) {
            ReportEntry entry = entries.get(i);
            int y = y0 + 14 + i * 14;
            boolean hovered = Theme.inside(mouseX, mouseY, x0 - 2, y, x1 + 2, y + 14);
            double lit = light(byPeak ? "peak" + i : "avg" + i, hovered);
            if (lit > 0.01) {
                Theme.fill(x0 - 2, y, x1 + 2, y + 14, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
            }
            String value = byPeak ? Names.ms(entry.maxMs) : Names.ms(report.perPeriod(entry));
            int valueWidth = fontRendererObj.getStringWidth(value);
            fontRendererObj.drawString(value, x1 - valueWidth, y + 3, byPeak ? peakColor(entry) : severity(report.perPeriod(entry)));
            Icons.draw(icon(entry.category), x0, y + 4, Theme.TEXT_DISABLED);
            String name = Names.of(entry);
            String where = entry.level == 3 ? "  " + Names.position(entry) : "";
            int room = x1 - valueWidth - 6 - (x0 + 10);
            String shownName = Theme.ellipsize(fontRendererObj, name, room);
            fontRendererObj.drawString(shownName, x0 + 10, y + 3, Theme.TEXT);
            int after = x0 + 10 + fontRendererObj.getStringWidth(shownName);
            if (!where.isEmpty() && after < x1 - valueWidth - 6) {
                fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, where, x1 - valueWidth - 6 - after), after, y + 3,
                    Theme.TEXT_DISABLED);
            }
            hit(x0 - 2, y, x1 + 2, y + 14, () -> reveal(entry), modOf(entry) + "\n" + I18n.format("lagfinder.tip.reveal"));
        }
    }

    private void drawSelection(ReportEntry entry, int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        int x = x0 + 8, y = y0 + 8;
        // Title: icon, name, and the mod on the right.
        ItemStack stack = entry.level >= 2 ? Names.icon(entry) : null;
        if (stack != null) {
            Draw.item(stack, x, y - 2, 16);
        } else if (entry.level == 1) {
            Theme.fill(x + 3, y + 1, x + 11, y + 9, Draw.modColor(entry.name));
        } else {
            Icons.draw(icon(entry.category), x + 4, y + 1, Theme.ACCENT);
        }
        String mod = modOf(entry);
        int modWidth = fontRendererObj.getStringWidth(mod) + 10;
        if (entry.level > 1) {
            int pillX = x1 - 8 - modWidth;
            Theme.fill(pillX, y - 2, x1 - 8, y + 10, Theme.CONTROL);
            Theme.outline(pillX, y - 2, x1 - 8, y + 10, Draw.modColor(mod));
            fontRendererObj.drawString(mod, pillX + 5, y, Draw.modColor(mod));
        }
        String name = Names.of(entry) + (entry.level == 3 && entry.label != null ? " · " + entry.label : "");
        fontRendererObj.drawStringWithShadow(Theme.ellipsize(fontRendererObj, name, x1 - x - 30 - (entry.level > 1 ? modWidth : 0)),
            x + 20, y, Theme.TEXT);
        y += 13;
        String kind = Names.category(entry.category) + (entry.detail != null ? "  ·  " + entry.detail : "");
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, kind, x1 - x - 28), x + 20, y, Theme.TEXT_DISABLED);
        y += 14;

        // The numbers.
        boolean tps = report.mode == ScanMode.TPS;
        double average = report.perPeriod(entry);
        double share = report.averagePeriodMs() <= 0 ? 0 : average / report.averagePeriodMs() * 100;
        int periods = Math.max(1, report.periods);
        String[] labels = { I18n.format(tps ? "lagfinder.stat.avg_tick" : "lagfinder.stat.avg_frame"),
            I18n.format(tps ? "lagfinder.stat.share_tick" : "lagfinder.stat.share_frame"),
            I18n.format("lagfinder.stat.peak"), I18n.format("lagfinder.stat.spikes"),
            I18n.format(tps ? "lagfinder.stat.calls_tick" : "lagfinder.stat.calls_frame"),
            entry.level == 3 ? I18n.format("lagfinder.stat.active") : I18n.format("lagfinder.stat.instances") };
        String[] values = { Names.ms(average), String.format("%.1f%%", share),
            Names.ms(entry.maxMs) + "  @" + Names.clock(entry.maxAt),
            entry.spikes + String.format("  (%.1f%%)", entry.spikes * 100.0 / periods),
            String.format("%.1f", entry.calls / (double) periods),
            entry.level == 3 ? String.format("%.0f%%", Math.min(100, entry.activePeriods * 100.0 / periods))
                : entry.category.hasInstances ? String.valueOf(instancesOf(entry))
                    : entry.level == 1 ? String.valueOf(entry.children.size()) : "\u2014" };
        int[] colors = { severity(average), severity(average), peakColor(entry),
            entry.spikes > 0 ? ORANGE : Theme.TEXT, Theme.TEXT, Theme.TEXT };
        String[] tips = { "lagfinder.tip.avg", "lagfinder.tip.share", "lagfinder.tip.peak", "lagfinder.tip.spikes",
            "lagfinder.tip.calls", entry.level == 3 ? "lagfinder.tip.active" : "lagfinder.tip.instances" };
        int columns = 3, gap = 6;
        int tileWidth = (x1 - x0 - 16 - gap * (columns - 1)) / columns;
        int tileHeight = 26;
        for (int i = 0; i < labels.length; i++) {
            int tx = x0 + 8 + (i % columns) * (tileWidth + gap), ty = y + (i / columns) * (tileHeight + 4);
            if (ty + tileHeight > y1 - 4) {
                break;
            }
            Theme.fill(tx, ty, tx + tileWidth, ty + tileHeight, 0xFF0F1216);
            Theme.outline(tx, ty, tx + tileWidth, ty + tileHeight, Theme.BORDER);
            fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, labels[i], tileWidth - 10), tx + 5, ty + 4, Theme.TEXT_DISABLED);
            fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, values[i], tileWidth - 10), tx + 5, ty + 15, colors[i]);
            hit(tx, ty, tx + tileWidth, ty + tileHeight, null, labels[i] + "\n" + I18n.format(tips[i]));
        }
        y += 2 * (tileHeight + 4) + 4;
        if (y > y1 - 16) {
            return;
        }

        if (entry.level == 3 && entry.hasLocation()) {
            drawLocation(entry, x0, y, x1, mouseX, mouseY);
            y += 24;
            if (isSpiky(entry) && y + 12 < y1) {
                Icons.draw(Icons.SMALL_SPIKE, x0 + 8, y + 1, ORANGE);
                fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.hint.spiky"), x1 - x0 - 30),
                    x0 + 19, y, ORANGE);
            }
            return;
        }
        if (isSpiky(entry) && y + 12 < y1) {
            Icons.draw(Icons.SMALL_SPIKE, x0 + 8, y + 1, ORANGE);
            fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.hint.spiky"), x1 - x0 - 30),
                x0 + 19, y, ORANGE);
            y += 14;
        }
        // The worst ones under it.
        if (!entry.children.isEmpty() && y + 26 < y1) {
            fontRendererObj.drawString(I18n.format("lagfinder.details.worst").toUpperCase(), x0 + 8, y, Theme.TEXT_MUTED);
            y += 12;
            List<ReportEntry> children = sorted(entry.children);
            for (int i = 0; i < children.size() && y + 14 <= y1 - 4; i++) {
                ReportEntry child = children.get(i);
                boolean hovered = Theme.inside(mouseX, mouseY, x0 + 6, y, x1 - 6, y + 14);
                double lit = light("child" + i, hovered);
                if (lit > 0.01) {
                    Theme.fill(x0 + 6, y, x1 - 6, y + 14, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
                }
                String value = sort == Sort.PEAK ? Names.ms(child.maxMs) : Names.ms(report.perPeriod(child));
                int valueWidth = fontRendererObj.getStringWidth(value);
                fontRendererObj.drawString(value, x1 - 10 - valueWidth, y + 3,
                    sort == Sort.PEAK ? peakColor(child) : severity(report.perPeriod(child)));
                String text = child.level == 3 ? Names.position(child) + (child.label != null ? "  " + child.label : "")
                    : Names.of(child);
                String dim = dimensionTag(child);
                if (dim != null) {
                    text += "  " + dim;
                }
                fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, text, x1 - x0 - 30 - valueWidth), x0 + 10, y + 3,
                    Theme.TEXT);
                hit(x0 + 6, y, x1 - 6, y + 14, () -> reveal(child), I18n.format("lagfinder.tip.reveal"));
                y += 14;
            }
        }
    }

    private static int instancesOf(ReportEntry entry) {
        if (entry.level == 2) {
            return Math.max(entry.instances, entry.children.size());
        }
        int count = 0;
        for (ReportEntry child : entry.children) {
            count += instancesOf(child);
        }
        return entry.level == 1 && count == 0 ? entry.children.size() : count;
    }

    /** Where the instance is, with buttons to go there, highlight it and copy its place. */
    private void drawLocation(ReportEntry entry, int x0, int y, int x1, int mouseX, int mouseY) {
        int x = x0 + 8;
        Icons.draw(Icons.SMALL_TELEPORT, x, y + 6, Theme.TEXT_MUTED);
        String where = Names.position(entry) + "  ·  " + report.dimensionName(entry.dim);
        boolean canTeleport = ClientScanner.INSTANCE.teleportAllowed();
        boolean highlighted = Highlighter.INSTANCE.isHighlighted(entry);
        String[] labels = { I18n.format("lagfinder.action.teleport"),
            I18n.format(highlighted ? "lagfinder.action.unhighlight" : "lagfinder.action.highlight"),
            I18n.format("lagfinder.action.copy") };
        String[][] icons = { Icons.SMALL_TELEPORT, Icons.SMALL_EYE, Icons.SMALL_COPY };
        int buttonsWidth = 0;
        int[] widths = new int[3];
        for (int i = 0; i < 3; i++) {
            widths[i] = fontRendererObj.getStringWidth(labels[i]) + 22;
            buttonsWidth += widths[i] + 4;
        }
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, where, x1 - x - 14 - buttonsWidth), x + 11, y + 5, Theme.TEXT);
        int bx = x1 - 8 - buttonsWidth + 4;
        for (int i = 0; i < 3; i++) {
            boolean enabled = i != 0 || canTeleport;
            boolean on = i == 1 && highlighted;
            boolean hovered = enabled && Theme.inside(mouseX, mouseY, bx, y, bx + widths[i], y + 18);
            double lit = light(icons[i], hovered);
            int background = !enabled ? Theme.CONTROL_DISABLED
                : on ? Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, lit) : Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
            Theme.fill(bx, y, bx + widths[i], y + 18, background);
            Theme.outline(bx, y, bx + widths[i], y + 18, on ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT, lit));
            int color = !enabled ? Theme.TEXT_DISABLED : Theme.TEXT;
            Icons.draw(icons[i], bx + 6, y + 6, color);
            fontRendererObj.drawString(labels[i], bx + 16, y + 5, color);
            Runnable action;
            String tip;
            if (i == 0) {
                action = enabled ? () -> teleport(entry) : null;
                tip = I18n.format(canTeleport ? "lagfinder.tip.teleport" : "lagfinder.action.no_teleport");
            } else if (i == 1) {
                action = () -> Highlighter.INSTANCE.toggle(entry);
                tip = I18n.format("lagfinder.tip.highlight");
            } else {
                action = () -> setClipboardString(entry.x + " " + entry.y + " " + entry.z);
                tip = I18n.format("lagfinder.tip.copy");
            }
            hit(bx, y, bx + widths[i], y + 18, action, tip);
            bx += widths[i] + 4;
        }
    }

    @Override
    protected boolean showsScreenBehind() {
        return false;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
