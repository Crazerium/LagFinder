package lagfinder.client.gui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import lagfinder.LagFinder;
import lagfinder.client.ClientScanner;
import lagfinder.client.Highlighter;
import lagfinder.client.Names;
import lagfinder.client.gui.ui.FlatTextField;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.ScaledScreen;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.WindowHeader;
import lagfinder.core.Category;
import lagfinder.core.Report;
import lagfinder.core.ReportEntry;
import lagfinder.core.ScanMode;
import lagfinder.network.Net;

/**
 * A finished scan. Across the top, tiles of how the server (or the client) did and what took the most; under them,
 * one tab per kind of work, and a bar of filters: search, a mod, a category, the sorting, only what is slow. On the
 * left, a table of the kinds of things of each category, the picked tab's open: how many there are, their time per
 * tick, per call, their worst tick and their share of the tick. On the right, the graph over the scan (the rate, the
 * tick's average and worst time, or the load of what is picked), and under it what took the most time and the worst
 * spikes, or all about the pick, with buttons to teleport to it and highlight it in the world. Along the bottom, what
 * the scan tells at a glance, and buttons to export the report and set up how it is shown.
 */
public class GuiReport extends ScaledScreen {

    private static final int ID_CLOSE = 0;
    private static final int TILE_HEIGHT = 42, TAB_HEIGHT = 22, TOOL_HEIGHT = 18, INSIGHTS_HEIGHT = 44;
    private static final int SECTION_ROW = 20, HEAD_ROW = 14, KIND_ROW = 24, COMPACT_ROW = 16, EMPTY_ROW = 30;
    private static final int FOOTER = 16, MENU_ROW = 14, MENU_ROWS = 14;
    private static final int SELECTED = 0xFF1E2C40;
    private static final int WARN = 0xFFF2C14E, ORANGE = 0xFFFF8C42;
    /** The bars of the tick's time on the graph. */
    private static final int TICK_COLOR = 0xFF3D8BFD;
    /** Where the ranges of the graph can start, seconds before the end of the scan; 0 for the whole scan. */
    private static final int[] RANGES = { 0, 300, 120, 60, 30, 10 };
    /** What counts as slow for "only slow" (ms per tick), picked in the settings. */
    private static final float[] SLOW_LIMITS = { 0.01f, 0.05f, 0.1f, 0.5f, 1f, 5f };

    private enum Sort {

        AVERAGE("lagfinder.sort.average"),
        CALL("lagfinder.sort.call"),
        PEAK("lagfinder.sort.peak"),
        SPIKES("lagfinder.sort.spikes"),
        COUNT("lagfinder.sort.count"),
        NAME("lagfinder.sort.name");

        final String key;

        Sort(String key) {
            this.key = key;
        }
    }

    /** What the graph shows: the rate with the tick's time, the tick's average, its worst, or the pick's load. */
    private enum ChartMode {
        RATE,
        AVERAGE,
        PEAK,
        PICK
    }

    // How the report is shown, kept while the game runs.
    private static Sort sort = Sort.AVERAGE;
    private static boolean reversed;
    private static boolean onlySlow;
    private static int slowLimit = 2;
    private static boolean compact;
    private static boolean subtitles = true;
    private static boolean bands = true;
    private static int range;
    private static ChartMode chartMode = ChartMode.RATE;

    private final Report report;
    private final GuiScreen parent;
    private int tab;
    /** Whether the picked tab's section of the table is open. */
    private boolean sectionOpen = true;
    private ReportEntry selected;
    /** The mod and the category the table is cut to, null for all. */
    private String modFilter;
    private Category categoryFilter;
    /** The mods of the report, the one taking the most time first. */
    private final List<String> mods = new ArrayList<>();

    private static final int SECTION = 0, HEAD = 1, ITEM = 2, EMPTY = 3;

    /** A line of the table as shown: a category's title, its column titles, a kind of thing, or "nothing here". */
    private static final class Row {

        final int type;
        final ReportEntry entry;
        /** The category's index among the tabs. */
        final int index;
        final int y, height;
        /** Of a kind: the most time of the kinds shown in its section, for the bars. */
        double sectionMax = 1e-9;

        Row(int type, ReportEntry entry, int index, int y, int height) {
            this.type = type;
            this.entry = entry;
            this.index = index;
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

    /** A list dropped down from a control: the choices, the one chosen, and what a pick does. */
    private static final class Menu {

        final Object owner;
        final int x, y, width, anchorTop;
        final String[] items;
        /** A square of color before each item (mods), or null. */
        final int[] colors;
        /** A box ticked or not before each item (settings), null for none or for an item without one. */
        final Boolean[] checked;
        final int current;
        final IntConsumer pick;
        /** Whether it stays open after a pick, to change more (settings). */
        final boolean stays;
        int scroll;

        Menu(Object owner, int x, int y, int width, int anchorTop, String[] items, int[] colors, Boolean[] checked,
            int current, IntConsumer pick, boolean stays) {
            this.owner = owner;
            this.x = x;
            this.y = y;
            this.width = width;
            this.anchorTop = anchorTop;
            this.items = items;
            this.colors = colors;
            this.checked = checked;
            this.current = current;
            this.pick = pick;
            this.stays = stays;
        }
    }

    /** Something the scan tells at a glance, along the bottom. */
    private static final class Insight {

        final int color;
        final String[] icon;
        final String title, text, link;
        final Runnable action;

        Insight(int color, String[] icon, String title, String text, String link, Runnable action) {
            this.color = color;
            this.icon = icon;
            this.title = title;
            this.text = text;
            this.link = link;
            this.action = action;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private int contentHeight, shownKinds, allKinds;
    private final List<Hit> hits = new ArrayList<>();
    private int clipX0 = Integer.MIN_VALUE, clipY0 = Integer.MIN_VALUE, clipX1 = Integer.MAX_VALUE,
        clipY1 = Integer.MAX_VALUE;
    private final Map<Object, Smooth> lights = new HashMap<>();
    private int scroll;
    private final Smooth shownScroll = new Smooth(0);
    private final Smooth tabMark = new Smooth(-1);

    private int panelLeft, panelTop, panelRight, panelBottom;
    private int tilesY, tabsY, toolY, bodyTop, bodyBottom, insightsTop;
    private int listLeft, listRight;
    private int rightLeft, rightRight, graphBottom;
    /** The columns of the table: where each ends (its right edge), the total's from its left edge. */
    private int colShare, colPeak, colCall, colTotalLeft, colTotalRight, colCount, nameRight;
    private boolean showCall;
    /** The controls of the bar of filters: where each starts, and how wide. */
    private int modsX, catsX, sortX, slowX, cardsX, settingsX;
    private static final int MODS_W = 92, CATS_W = 106, SORT_W = 112;
    private FlatTextField search;
    private String lastSearch = "";
    /** The worst things of the whole scan, for the overview: by time in all, and by worst tick. */
    private List<ReportEntry> byAverage, bySpike;
    /** What takes the most time of all: the kind of thing, or the one of them taking most of its time. */
    private ReportEntry suspect;
    /** The moment of the graph picked to see what lagged then (its index), -1 for none. */
    private int pinned = -1;
    /** What took the most time, and had the worst tick, in the moment picked. */
    private List<ReportEntry> pinnedByAverage, pinnedBySpike;
    /** The moment the rate was lowest in, -1 when it never dropped. */
    private int worstMoment = -1;
    /** The lowest lines of the report that kept their graph, and what took the most time in each moment. */
    private List<ReportEntry> timed;
    private ReportEntry[] topAt;
    private final List<Insight> insights = new ArrayList<>();

    private Menu menu;
    /** Where the open menu was drawn, for the mouse not to light up what is under it. */
    private int menuX0, menuY0, menuX1, menuY1;
    /** The list of all the spikes over the report, and how far it is scrolled. */
    private boolean spikesOpen;
    private int spikesScroll;
    /** Whether the graph takes all the right side. */
    private boolean chartExpanded;
    /** The last export, said for a few seconds: its file and when. */
    private File exported;
    private String exportMessage;
    private long exportedAt;

    public GuiReport(Report report) {
        this.report = report;
        GuiScreen open = net.minecraft.client.Minecraft.getMinecraft().currentScreen;
        this.parent = open instanceof GuiLagFinder || open instanceof GuiLogs ? open : null;
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
        collectTops();
        collectMods();
        suspect = findSuspect();
        float lowest = Float.MAX_VALUE;
        for (int i = 0; i < report.buckets(); i++) {
            if (report.rate[i] < lowest) {
                lowest = report.rate[i];
                worstMoment = i;
            }
        }
        if (worstMoment >= 0 && report.mode == ScanMode.TPS && lowest >= report.mode.targetRate() * 0.95f) {
            worstMoment = -1;
        }
        if (chartMode == ChartMode.PICK) {
            chartMode = ChartMode.RATE;
        }
        collectInsights();
    }

    /** The worst things of the whole scan, in all tabs but the worlds': by time in all, and by worst tick. */
    private void collectTops() {
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

    /** The mods of all tabs but the worlds', the one taking the most time first. */
    private void collectMods() {
        Map<String, Double> times = new HashMap<>();
        for (ReportEntry category : report.categories) {
            if (category.category == Category.WORLDS) {
                continue;
            }
            for (ReportEntry mod : category.children) {
                times.merge(mod.name, mod.totalMs, Double::sum);
            }
        }
        mods.addAll(times.keySet());
        mods.sort((a, b) -> Double.compare(times.get(b), times.get(a)));
    }

    /**
     * The kind of thing taking the most time of all; when one of them takes most of its kind's time, that one, so it
     * can be gone to.
     */
    private ReportEntry findSuspect() {
        ReportEntry best = null;
        for (ReportEntry category : report.categories) {
            if (category.category == Category.WORLDS) {
                continue;
            }
            for (ReportEntry mod : category.children) {
                for (ReportEntry type : mod.children) {
                    if (best == null || type.totalMs > best.totalMs) {
                        best = type;
                    }
                }
            }
        }
        if (best == null || best.totalMs <= 0) {
            return null;
        }
        ReportEntry top = null;
        for (ReportEntry child : best.children) {
            if (top == null || child.totalMs > top.totalMs) {
                top = child;
            }
        }
        return top != null && top.totalMs >= best.totalMs * 0.5 ? top : best;
    }

    /** Whether the suspect is named the cause of the lag: the rate dropped and it takes a good part of the tick. */
    private boolean suspectIsCause() {
        if (suspect == null) {
            return false;
        }
        boolean lagging = fps() || report.averageRate() < report.mode.targetRate() * 0.97f
            || report.minimumRate() < report.mode.targetRate() * 0.75f;
        return lagging && shareOf(report.perPeriod(suspect)) >= 15;
    }

    /** What the scan tells at a glance: how the rate went, the worst spike, and what to look at. */
    private void collectInsights() {
        boolean tps = report.mode == ScanMode.TPS;
        float average = report.averageRate(), minimum = report.minimumRate();
        float target = report.mode.targetRate();
        if (tps) {
            if (average >= target * 0.97f && minimum >= target * 0.9f) {
                insights.add(
                    new Insight(
                        Theme.SUCCESS,
                        Icons.SMALL_CHECK,
                        I18n.format("lagfinder.insight.stable"),
                        I18n.format("lagfinder.insight.stable_text", String.format("%.0f", target)),
                        null,
                        null));
            } else {
                boolean bad = average < target * 0.75f;
                insights.add(
                    new Insight(
                        bad ? Theme.DANGER : WARN,
                        Icons.SMALL_WARN,
                        I18n.format(bad ? "lagfinder.insight.lagging" : "lagfinder.insight.dips"),
                        I18n.format(
                            "lagfinder.insight.rate_text",
                            String.format("%.1f", average),
                            String.format("%.1f", minimum),
                            Names.ms(report.averagePeriodMs())),
                        worstMoment >= 0 ? I18n.format("lagfinder.insight.to_moment") : null,
                        worstMoment >= 0 ? () -> pin(worstMoment) : null));
            }
        } else {
            int color = fpsColor(average);
            insights.add(
                new Insight(
                    color,
                    color == Theme.SUCCESS ? Icons.SMALL_CHECK : Icons.SMALL_WARN,
                    I18n.format(
                        color == Theme.SUCCESS ? "lagfinder.insight.fps_smooth"
                            : color == WARN ? "lagfinder.insight.fps_dips" : "lagfinder.insight.fps_low"),
                    I18n.format(
                        "lagfinder.insight.fps_text",
                        String.format("%.0f", average),
                        String.format("%.0f", minimum)),
                    worstMoment >= 0 ? I18n.format("lagfinder.insight.to_moment") : null,
                    worstMoment >= 0 ? () -> pin(worstMoment) : null));
        }
        if (bySpike.isEmpty()) {
            insights.add(
                new Insight(
                    Theme.SUCCESS,
                    Icons.SMALL_CHECK,
                    I18n.format("lagfinder.insight.no_spikes"),
                    I18n.format("lagfinder.insight.no_spikes_text", Names.ms(report.spikeMs)),
                    null,
                    null));
        } else {
            ReportEntry worst = bySpike.get(0);
            insights.add(
                new Insight(
                    WARN,
                    Icons.SMALL_SPIKE,
                    I18n.format(
                        "lagfinder.insight.spike",
                        Names.category(worst.category, report.mode)
                            .toLowerCase(Locale.ROOT)),
                    I18n.format("lagfinder.insight.spike_text", Names.of(worst), peakText(worst)),
                    I18n.format("lagfinder.insight.to_spikes"),
                    () -> {
                        spikesOpen = true;
                        spikesScroll = 0;
                    }));
        }
        if (!report.warnings.isEmpty()) {
            insights.add(
                new Insight(
                    WARN,
                    Icons.SMALL_WARN,
                    I18n.format("lagfinder.warning.title"),
                    I18n.format(report.warnings.get(0)),
                    null,
                    null));
            return;
        }
        ReportEntry heaviest = null;
        int index = -1;
        for (int i = 0; i < report.categories.size(); i++) {
            ReportEntry category = report.categories.get(i);
            if (category.category != Category.WORLDS && (heaviest == null || category.totalMs > heaviest.totalMs)) {
                heaviest = category;
                index = i;
            }
        }
        if (heaviest != null && heaviest.totalMs > 0) {
            int to = index;
            insights.add(
                new Insight(
                    Theme.ACCENT,
                    Icons.SMALL_INFO,
                    I18n.format("lagfinder.insight.advice"),
                    I18n.format("lagfinder.insight.advice." + heaviest.category.id),
                    I18n.format("lagfinder.insight.to_tab", Names.category(heaviest.category, report.mode)),
                    () -> openTab(to)));
        }
    }

    /** Picks the moment of the graph to see what lagged then, or lets it go when it is picked already. */
    private void pin(int moment) {
        pinned = pinned == moment ? -1 : moment;
        setSelected(null);
        pinnedByAverage = pinnedBySpike = null;
        if (pinned < 0) {
            return;
        }
        if (pinned < rangeFrom()) {
            range = 0;
        }
        int i = pinned;
        pinnedByAverage = new ArrayList<>();
        pinnedBySpike = new ArrayList<>();
        for (ReportEntry entry : timed()) {
            if (i >= entry.sum.length || i >= entry.max.length) {
                continue;
            }
            if (report.perPeriod(entry, i) > 0) {
                pinnedByAverage.add(entry);
            }
            if (entry.max[i] >= report.spikeMs) {
                pinnedBySpike.add(entry);
            }
        }
        pinnedByAverage.sort((a, b) -> Float.compare(report.perPeriod(b, i), report.perPeriod(a, i)));
        pinnedBySpike.sort((a, b) -> Float.compare(b.max[i], a.max[i]));
    }

    /** The lowest lines of the report that kept their graph, in all tabs but the worlds'. */
    private List<ReportEntry> timed() {
        if (timed == null) {
            timed = new ArrayList<>();
            for (ReportEntry category : report.categories) {
                if (category.category != Category.WORLDS) {
                    collectTimed(category, timed);
                }
            }
        }
        return timed;
    }

    /** What took the most time in the moment, null when nothing kept its graph. */
    private ReportEntry topAt(int moment) {
        if (topAt == null) {
            topAt = new ReportEntry[report.buckets()];
            float[] best = new float[report.buckets()];
            for (ReportEntry entry : timed()) {
                for (int i = 0; i < topAt.length && i < entry.sum.length; i++) {
                    float value = report.perPeriod(entry, i);
                    if (value > best[i]) {
                        best[i] = value;
                        topAt[i] = entry;
                    }
                }
            }
        }
        return moment >= 0 && moment < topAt.length ? topAt[moment] : null;
    }

    /**
     * The lowest lines of the entry that kept their graph: the instances that did, or the kind when none of them did.
     */
    private static void collectTimed(ReportEntry entry, List<ReportEntry> out) {
        if (entry.level == 2) {
            boolean any = false;
            for (ReportEntry child : entry.children) {
                if (child.sum != null && child.max != null) {
                    out.add(child);
                    any = true;
                }
            }
            if (!any && entry.sum != null && entry.max != null) {
                out.add(entry);
            }
            return;
        }
        for (ReportEntry child : entry.children) {
            collectTimed(child, out);
        }
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        panelLeft = 6;
        panelTop = 4;
        panelRight = width - 6;
        panelBottom = height - 4;
        tilesY = panelTop + WindowHeader.HEIGHT + 6;
        tabsY = tilesY + TILE_HEIGHT + 6;
        toolY = tabsY + TAB_HEIGHT + 6;
        bodyTop = toolY + TOOL_HEIGHT + 6;
        insightsTop = panelBottom - 6 - INSIGHTS_HEIGHT;
        bodyBottom = insightsTop - 6;
        int inner = panelRight - panelLeft - 16;
        int listWidth = Math.max(300, Math.min(560, inner * 41 / 100));
        listLeft = panelLeft + 8;
        listRight = listLeft + listWidth;
        rightLeft = listRight + 8;
        rightRight = panelRight - 8;
        graphBottom = bodyTop + (bodyBottom - bodyTop) * 54 / 100;

        // The columns of the table, from the right.
        showCall = listWidth >= 380;
        colShare = listRight - 8;
        colPeak = colShare - Math.max(40, fontRendererObj.getStringWidth(shareTitle())) - 8;
        int peakWidth = Math.max(44, fontRendererObj.getStringWidth(peakTitle()));
        colCall = colPeak - peakWidth - 8;
        colTotalRight = showCall
            ? colCall - Math.max(44, fontRendererObj.getStringWidth(I18n.format("lagfinder.column.per_call"))) - 8
            : colPeak - peakWidth - 8;
        colTotalLeft = colTotalRight - Math.max(48, fontRendererObj.getStringWidth(totalTitle()));
        colCount = colTotalLeft - 10;
        nameRight = colCount - Math.max(30, fontRendererObj.getStringWidth(I18n.format("lagfinder.column.count"))) - 6;

        // The bar of filters: the buttons on the right, the controls after the search, the search taking the rest.
        settingsX = panelRight - 8 - 18;
        cardsX = settingsX - 4 - 18;
        int slowWidth = slowWidth();
        int fixed = MODS_W + CATS_W + SORT_W + slowWidth + 4 * 6;
        int searchWidth = Math.max(110, Math.min(listWidth, cardsX - 12 - listLeft - fixed));
        modsX = listLeft + searchWidth + 6;
        catsX = modsX + MODS_W + 6;
        sortX = catsX + CATS_W + 6;
        slowX = sortX + SORT_W + 8;

        String text = search != null ? search.getText() : "";
        search = new FlatTextField(fontRendererObj, listLeft, toolY, searchWidth, TOOL_HEIGHT, Icons.SMALL_SEARCH);
        search.setHint(I18n.format("lagfinder.gui.search"));
        search.setMaxStringLength(64);
        search.setText(text);
        search.setOnCleared(this::rebuild);

        buttonList.clear();
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, panelRight, panelTop));
        menu = null;
        rebuild();
    }

    private String shareTitle() {
        return I18n.format(fps() ? "lagfinder.column.share_frame" : "lagfinder.column.share_tick");
    }

    private String peakTitle() {
        return I18n.format(inFps() ? "lagfinder.column.peak_fps" : "lagfinder.column.peak_col");
    }

    private String totalTitle() {
        return I18n.format(
            inFps() ? "lagfinder.column.cost" : fps() ? "lagfinder.column.total_frame" : "lagfinder.column.total_tick");
    }

    private int slowWidth() {
        return 14 + fontRendererObj.getStringWidth(slowLabel());
    }

    private String slowLabel() {
        return I18n.format("lagfinder.filter.slow", Names.ms(slowMs()));
    }

    private static float slowMs() {
        return SLOW_LIMITS[Math.max(0, Math.min(SLOW_LIMITS.length - 1, slowLimit))];
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private ReportEntry category() {
        return tab >= 0 && tab < report.categories.size() ? report.categories.get(tab) : null;
    }

    private void setSelected(ReportEntry entry) {
        selected = entry;
        if (entry != null && entry.sum != null) {
            chartMode = ChartMode.PICK;
        } else if (chartMode == ChartMode.PICK) {
            chartMode = ChartMode.RATE;
        }
    }

    // ---------------------------------------------------------------- Table

    private Comparator<ReportEntry> order() {
        Comparator<ReportEntry> order;
        switch (sort) {
            case CALL:
                order = (a, b) -> Double.compare(perCall(b), perCall(a));
                break;
            case PEAK:
                order = (a, b) -> Float.compare(b.maxMs, a.maxMs);
                break;
            case SPIKES:
                order = (a, b) -> a.spikes != b.spikes ? Integer.compare(b.spikes, a.spikes)
                    : Double.compare(b.totalMs, a.totalMs);
                break;
            case COUNT:
                order = (a, b) -> countOf(a) != countOf(b) ? Integer.compare(countOf(b), countOf(a))
                    : Double.compare(b.totalMs, a.totalMs);
                break;
            case NAME:
                order = Comparator.comparing(
                    (ReportEntry entry) -> Names.of(entry)
                        .toLowerCase(Locale.ROOT));
                break;
            default:
                order = (a, b) -> Double.compare(b.totalMs, a.totalMs);
        }
        return reversed ? order.reversed() : order;
    }

    private List<ReportEntry> sorted(List<ReportEntry> entries) {
        List<ReportEntry> list = new ArrayList<>(entries);
        list.sort(order());
        return list;
    }

    /** Its time per call (ms), 0 when it wasn't counted. */
    private static double perCall(ReportEntry entry) {
        return entry.calls > 0 ? entry.totalMs / entry.calls : 0;
    }

    /** How many of the kind there are. */
    private static int countOf(ReportEntry entry) {
        return Math.max(entry.instances, entry.children.size());
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

    private static boolean anyMatches(List<ReportEntry> entries, String query) {
        if (query.isEmpty()) {
            return false;
        }
        for (ReportEntry entry : entries) {
            if (matches(entry, query)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lays the table out again: after a change of tab, filter, sorting or search. Each category is a section; the
     * picked tab's is open (and, while searching, each with something found), the others a line to open it.
     */
    private void rebuild() {
        rows.clear();
        lastSearch = search.getText();
        String query = lastSearch.trim()
            .toLowerCase(Locale.ROOT);
        boolean filtered = !query.isEmpty() || modFilter != null || onlySlow;
        int y = 0;
        shownKinds = 0;
        allKinds = 0;
        for (int i = 0; i < report.categories.size(); i++) {
            ReportEntry category = report.categories.get(i);
            if (categoryFilter != null && category.category != categoryFilter) {
                continue;
            }
            List<ReportEntry> kinds = new ArrayList<>();
            for (ReportEntry mod : category.children) {
                boolean modMatches = matches(mod, query);
                for (ReportEntry kind : mod.children) {
                    allKinds++;
                    if (modFilter != null && !modFilter.equals(mod.name)) {
                        continue;
                    }
                    if (onlySlow && report.perPeriod(kind) < slowMs()) {
                        continue;
                    }
                    // A kind is found by its own name, its mod's, or one of its things (coordinates, a label).
                    if (modMatches || matches(kind, query) || anyMatches(kind.children, query)) {
                        kinds.add(kind);
                    }
                }
            }
            if (filtered && kinds.isEmpty() && i != tab && categoryFilter == null) {
                continue;
            }
            boolean open = categoryFilter != null || i == tab && sectionOpen || !query.isEmpty() && !kinds.isEmpty();
            rows.add(new Row(SECTION, category, i, y, SECTION_ROW));
            y += SECTION_ROW;
            if (!open) {
                continue;
            }
            rows.add(new Row(HEAD, category, i, y, HEAD_ROW));
            y += HEAD_ROW;
            if (kinds.isEmpty()) {
                rows.add(new Row(EMPTY, category, i, y, EMPTY_ROW));
                y += EMPTY_ROW;
                continue;
            }
            kinds.sort(order());
            double most = 1e-9;
            for (ReportEntry kind : kinds) {
                most = Math.max(most, kind.totalMs);
            }
            for (ReportEntry kind : kinds) {
                Row row = new Row(ITEM, kind, i, y, kindRow());
                row.sectionMax = most;
                rows.add(row);
                y += kindRow();
            }
            shownKinds += kinds.size();
        }
        contentHeight = y;
        clampScroll();
    }

    private static int kindRow() {
        return compact ? COMPACT_ROW : KIND_ROW;
    }

    private int listTop() {
        return bodyTop + 1;
    }

    private int listBottom() {
        return bodyBottom - FOOTER;
    }

    private int listHeight() {
        return listBottom() - listTop();
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, contentHeight - listHeight()));
    }

    /** Opens the tab and its section of the table, scrolled to it. */
    private void openTab(int index) {
        tab = index;
        sectionOpen = true;
        ReportEntry category = category();
        if (categoryFilter != null && category != null) {
            categoryFilter = category.category;
        }
        rebuild();
        for (Row row : rows) {
            if (row.type == SECTION && row.index == index) {
                scroll = row.y;
                clampScroll();
                break;
            }
        }
    }

    /** Picks the entry, opening its section and scrolling it into view (from the overview, the details, a tile). */
    private void reveal(ReportEntry entry) {
        if (entry.level == 3) {
            // Things aren't lines of the table: their kind is picked, and its cards open at this one.
            reveal(entry.parent);
            mc.displayGuiScreen(new GuiInstances(report, entry.parent, this).focusOn(entry));
            return;
        }
        ReportEntry root = root(entry);
        int index = report.categories.indexOf(root);
        if (index >= 0) {
            tab = index;
        }
        sectionOpen = true;
        if (categoryFilter != null && categoryFilter != root.category) {
            categoryFilter = null;
        }
        if (entry.level == 1) {
            modFilter = entry.name;
        } else if (modFilter != null && !modFilter.equals(modOf(entry))) {
            modFilter = null;
        }
        if (onlySlow && report.perPeriod(entry) < slowMs()) {
            onlySlow = false;
        }
        if (!search.getText()
            .isEmpty()) {
            search.setText("");
        }
        setSelected(entry.level == 2 ? entry : null);
        rebuild();
        for (Row row : rows) {
            boolean target = entry.level == 2 ? row.entry == entry : row.type == SECTION && row.index == index;
            if (target) {
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

    private void sortBy(Sort by) {
        if (sort == by) {
            reversed = !reversed;
        } else {
            sort = by;
            reversed = false;
        }
        rebuild();
    }

    // ---------------------------------------------------------------- Menus

    private void openMenu(Menu next) {
        menu = menu != null && menu.owner == next.owner ? null : next;
    }

    private void openModsMenu() {
        String[] items = new String[mods.size() + 1];
        int[] colors = new int[items.length];
        items[0] = I18n.format("lagfinder.filter.all_mods");
        colors[0] = 0;
        int current = 0;
        for (int i = 0; i < mods.size(); i++) {
            items[i + 1] = mods.get(i);
            colors[i + 1] = Draw.modColor(mods.get(i));
            if (mods.get(i)
                .equals(modFilter)) {
                current = i + 1;
            }
        }
        openMenu(
            new Menu(
                "mods",
                modsX,
                toolY + TOOL_HEIGHT + 2,
                Math.max(MODS_W, 150),
                toolY,
                items,
                colors,
                null,
                current,
                i -> {
                    modFilter = i == 0 ? null : mods.get(i - 1);
                    scroll = 0;
                    rebuild();
                },
                false));
    }

    private void openCategoriesMenu() {
        String[] items = new String[report.categories.size() + 1];
        items[0] = I18n.format("lagfinder.filter.all_categories");
        int current = 0;
        for (int i = 0; i < report.categories.size(); i++) {
            Category category = report.categories.get(i).category;
            items[i + 1] = Names.category(category, report.mode);
            if (category == categoryFilter) {
                current = i + 1;
            }
        }
        openMenu(
            new Menu(
                "categories",
                catsX,
                toolY + TOOL_HEIGHT + 2,
                Math.max(CATS_W, 140),
                toolY,
                items,
                null,
                null,
                current,
                i -> {
                    if (i == 0) {
                        categoryFilter = null;
                    } else {
                        tab = i - 1;
                        sectionOpen = true;
                        categoryFilter = report.categories.get(i - 1).category;
                    }
                    scroll = 0;
                    rebuild();
                },
                false));
    }

    private void openSortMenu() {
        Sort[] sorts = Sort.values();
        String[] items = new String[sorts.length];
        for (int i = 0; i < sorts.length; i++) {
            items[i] = I18n.format(sorts[i].key);
        }
        openMenu(
            new Menu(
                "sort",
                sortX,
                toolY + TOOL_HEIGHT + 2,
                SORT_W,
                toolY,
                items,
                null,
                null,
                sort.ordinal(),
                i -> {
                    sort = sorts[i];
                    reversed = false;
                    rebuild();
                },
                false));
    }

    /** The ranges of the graph that are shorter than the scan, the whole scan first. */
    private List<Integer> ranges() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < RANGES.length; i++) {
            if (RANGES[i] == 0 || RANGES[i] < report.durationSec - 1) {
                list.add(i);
            }
        }
        return list;
    }

    private String rangeLabel(int index) {
        int seconds = RANGES[index];
        if (seconds == 0) {
            return I18n.format("lagfinder.range.all");
        }
        return seconds >= 60 ? I18n.format("lagfinder.range.minutes", seconds / 60)
            : I18n.format("lagfinder.range.seconds", seconds);
    }

    private void openRangeMenu(int x, int y, int width) {
        List<Integer> options = ranges();
        String[] items = new String[options.size()];
        int current = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = rangeLabel(options.get(i));
            if (options.get(i) == range) {
                current = i;
            }
        }
        openMenu(new Menu("range", x, y + 17, width, y, items, null, null, current, i -> {
            range = options.get(i);
            if (pinned >= 0 && pinned < rangeFrom()) {
                pin(pinned);
            }
        }, false));
    }

    /** The first moment of the graph shown, by the range picked. */
    private int rangeFrom() {
        int n = report.buckets();
        int seconds = range >= 0 && range < RANGES.length ? RANGES[range] : 0;
        if (seconds <= 0 || report.bucketSec <= 0 || seconds >= report.durationSec) {
            return 0;
        }
        int count = (int) Math.ceil(seconds / report.bucketSec);
        return Math.max(0, Math.min(n - 2, n - count));
    }

    /** How the report is shown, a box for each on and off. */
    private void openSettings(int x, int y, int anchorTop, boolean reopen) {
        String[] items = { I18n.format("lagfinder.settings.subtitles"), I18n.format("lagfinder.settings.compact"),
            I18n.format("lagfinder.settings.bands"), I18n.format("lagfinder.settings.slow", Names.ms(slowMs())) };
        Boolean[] checked = { subtitles, compact, bands, null };
        Menu next = new Menu("settings", x, y, 220, anchorTop, items, null, checked, -1, i -> {
            if (i == 0) {
                subtitles = !subtitles;
            } else if (i == 1) {
                compact = !compact;
            } else if (i == 2) {
                bands = !bands;
            } else {
                slowLimit = (slowLimit + 1) % SLOW_LIMITS.length;
                initGui();
            }
            rebuild();
            openSettings(x, y, anchorTop, true);
        }, true);
        if (reopen) {
            menu = next;
        } else {
            openMenu(next);
        }
    }

    // ---------------------------------------------------------------- Input

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            close();
        }
    }

    private void close() {
        GuiLagFinder.forgetReopen();
        mc.displayGuiScreen(parent);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (menu != null || spikesOpen) {
            // What is open over the rest takes the click: a pick, or closing it.
            if (button == 0) {
                runHit(mouseX, mouseY);
            } else {
                menu = null;
            }
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
        search.mouseClicked(mouseX, mouseY, button);
        if (!search.getText()
            .equals(lastSearch)) {
            rebuild();
        }
        if (button == 0) {
            runHit(mouseX, mouseY);
        }
    }

    private void runHit(int mouseX, int mouseY) {
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
        int step = Integer.signum(wheel);
        if (menu != null) {
            if (Theme.inside(x, y, menuX0, menuY0, menuX1, menuY1)) {
                menu.scroll = Math.max(0, Math.min(menu.items.length - MENU_ROWS, menu.scroll - step));
            }
            return;
        }
        if (spikesOpen) {
            spikesScroll = Math.max(0, spikesScroll - step * 3);
            return;
        }
        if (Theme.inside(x, y, listLeft, listTop(), listRight, listBottom())) {
            scroll -= step * 3 * COMPACT_ROW;
            clampScroll();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (menu != null) {
                menu = null;
            } else if (spikesOpen) {
                spikesOpen = false;
            } else if (search.isFocused() && !search.getText()
                .isEmpty()) {
                search.setText("");
                rebuild();
            } else {
                GuiLagFinder.closeToGame(mc, this);
            }
            return;
        }
        if (menu != null || spikesOpen) {
            return;
        }
        if (keyCode == Keyboard.KEY_BACK && !search.isFocused() && (selected != null || pinned >= 0)) {
            // Back to the overview: of the moment picked, then of the whole scan.
            if (selected != null) {
                setSelected(null);
            } else {
                pin(pinned);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
            moveSelection(keyCode == Keyboard.KEY_UP ? -1 : 1);
            return;
        }
        if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) && selected != null
            && selected.level < 3
            && GuiInstances.hasInstances(selected)) {
            openCards(selected);
            return;
        }
        if (keyCode == Keyboard.KEY_TAB && !report.categories.isEmpty()) {
            openTab((tab + (isShiftKeyDown() ? report.categories.size() - 1 : 1)) % report.categories.size());
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
        List<Row> items = new ArrayList<>();
        for (Row row : rows) {
            if (row.type == ITEM) {
                items.add(row);
            }
        }
        if (items.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).entry == selected) {
                index = i;
            }
        }
        index = Math.max(0, Math.min(items.size() - 1, index < 0 ? 0 : index + step));
        Row row = items.get(index);
        setSelected(row.entry);
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
        // Under what is open over the rest, nothing lights up.
        boolean covered = spikesOpen || menu != null && Theme.inside(mouseX, mouseY, menuX0, menuY0, menuX1, menuY1);
        int mx = covered ? -1000 : mouseX, my = covered ? -1000 : mouseY;
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(panelLeft, panelTop, panelRight, panelBottom);
        drawHeader();
        drawTiles(mx, my);
        drawTabs(mx, my);
        drawToolbar(mx, my);
        drawList(mx, my);
        if (chartExpanded) {
            drawChart(rightLeft, bodyTop, rightRight, bodyBottom, mx, my);
        } else {
            drawChart(rightLeft, bodyTop, rightRight, graphBottom, mx, my);
            drawDetails(mx, my);
        }
        drawInsights(mx, my);
        search.drawTextBox();
        super.drawScaled(mx, my, partialTicks);
        drawToast(mx, my);

        if (spikesOpen) {
            drawSpikes(mouseX, mouseY);
        }
        if (menu != null) {
            drawMenu(mouseX, mouseY);
        } else {
            menuX0 = menuY0 = menuX1 = menuY1 = 0;
        }

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
    }

    private void drawHeader() {
        String title = I18n
            .format(report.mode == ScanMode.TPS ? "lagfinder.report.title_tps" : "lagfinder.report.title_fps");
        String date = new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(report.startedAt));
        String subtitle = date + "  ·  "
            + Names.clock(report.durationSec)
            + (report.source == null || report.source.isEmpty() ? "" : "  ·  " + report.source);
        boolean warned = !report.warnings.isEmpty();
        if (warned) {
            // Something the scan couldn't measure as it should: said up front, with what in the tooltip.
            subtitle = I18n.format("lagfinder.warning.count", report.warnings.size()) + "  ·  " + subtitle;
        }
        WindowHeader.draw(
            fontRendererObj,
            panelLeft,
            panelTop,
            panelRight,
            panelRight - WindowHeader.CLOSE_ROOM,
            Icons.STATS,
            title,
            subtitle,
            warned ? WARN : Theme.TEXT_MUTED,
            null);
        if (warned) {
            StringBuilder tip = new StringBuilder(I18n.format("lagfinder.warning.title"));
            for (String warning : report.warnings) {
                tip.append("\n- ")
                    .append(I18n.format(warning));
            }
            hit(
                panelLeft + 28,
                panelTop + 16,
                panelRight - WindowHeader.CLOSE_ROOM,
                panelTop + 28,
                null,
                tip.toString());
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

    /** Text half again as big, or as is (cut to fit) when it would not fit; returns how wide it was drawn. */
    private int drawBig(String text, int x, int y, int color, int room) {
        int textWidth = fontRendererObj.getStringWidth(text);
        if (textWidth * 3 / 2 > room) {
            String shown = Theme.ellipsize(fontRendererObj, text, room);
            fontRendererObj.drawStringWithShadow(shown, x, y + 2, color);
            return fontRendererObj.getStringWidth(shown);
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0f);
        GL11.glScalef(1.5f, 1.5f, 1f);
        fontRendererObj.drawStringWithShadow(text, 0, 0, color);
        GL11.glPopMatrix();
        return textWidth * 3 / 2;
    }

    /** A small icon at twice its size, centered on the point. */
    private static void drawIconLarge(String[] icon, int centerX, int centerY, int color) {
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX - Icons.width(icon), centerY - icon.length, 0f);
        GL11.glScalef(2f, 2f, 1f);
        Icons.draw(icon, 0, 0, color);
        GL11.glPopMatrix();
    }

    // ---------------------------------------------------------------- Tiles

    /**
     * How the scan went, at a glance: the rate, the average and worst tick, the time a tick may take, how many were
     * measured, and what takes the most time of all, a click from it.
     */
    private void drawTiles(int mouseX, int mouseY) {
        boolean tps = report.mode == ScanMode.TPS;
        float target = report.mode.targetRate();
        float average = report.averageRate(), minimum = report.minimumRate();
        float averageMs = report.averagePeriodMs(), worstMs = report.worstPeriodMs();
        float budget = budgetMs();
        int gap = 6, left = panelLeft + 8, right = panelRight - 8;
        int each = (right - left - gap * 5) / 6;
        int y0 = tilesY, y1 = tilesY + TILE_HEIGHT;
        int[] xs = new int[7];
        for (int i = 0; i < 6; i++) {
            xs[i] = left + i * (each + gap);
        }
        xs[6] = right + gap;

        // The rate, with how it was and how it went from the start to the end.
        int rateColor = tps ? Draw.rateColor(average, target) : fpsColor(average);
        String status;
        if (tps) {
            status = I18n.format(
                average >= target * 0.97f && minimum >= target * 0.9f ? "lagfinder.tile.stable"
                    : average >= target * 0.75f ? "lagfinder.tile.unstable" : "lagfinder.tile.lagging");
        } else {
            status = I18n.format(
                average >= 55 ? "lagfinder.tile.smooth" : average >= 30 ? "lagfinder.tile.playable"
                    : "lagfinder.tile.choppy");
        }
        int x1 = xs[1] - gap;
        int valueX = drawTile(
            xs[0],
            y0,
            x1,
            y1,
            Icons.SMALL_PULSE,
            rateColor,
            I18n.format(tps ? "lagfinder.tile.avg_tps" : "lagfinder.tile.avg_fps"),
            String.format("%.1f", average),
            rateColor,
            status,
            rateColor,
            I18n.format(tps ? "lagfinder.tip.tile_tps" : "lagfinder.tip.tile_fps", String.format("%.1f", minimum)));
        float trend = trend();
        String trendText = String.format("%.1f", Math.abs(trend));
        int tx = valueX + 6;
        if (tx + 8 + fontRendererObj.getStringWidth(trendText) < x1 - 4) {
            int trendColor = Math.abs(trend) < 0.05f ? Theme.TEXT_MUTED : trend > 0 ? Theme.SUCCESS : Theme.DANGER;
            if (trend < 0) {
                Icons.draw(Icons.SECTION_OPEN, tx, y0 + 21, trendColor);
            } else {
                Icons.draw(Icons.SMALL_UP, tx, y0 + 20, trendColor);
            }
            fontRendererObj.drawString(trendText, tx + 8, y0 + 18, trendColor);
            hit(
                tx,
                y0 + 16,
                tx + 8 + fontRendererObj.getStringWidth(trendText),
                y0 + 28,
                null,
                I18n.format("lagfinder.tip.trend"));
        }

        // The average tick, against the time it may take.
        double share = budget <= 0 ? 0 : averageMs / budget * 100;
        int averageColor = averageMs > budget ? Theme.DANGER : WARN;
        drawTile(
            xs[1],
            y0,
            xs[2] - gap,
            y1,
            Icons.SMALL_CLOCK,
            averageColor,
            I18n.format(tps ? "lagfinder.tile.avg_tick" : "lagfinder.tile.avg_frame"),
            Names.ms(averageMs),
            Theme.TEXT,
            I18n.format("lagfinder.tile.of_budget", String.format("%.1f%%", share)),
            Theme.TEXT_MUTED,
            I18n.format("lagfinder.tip.avg_period"));

        // The worst tick, and when.
        int worstAt = -1;
        for (int i = 0; i < report.buckets(); i++) {
            if (worstAt < 0 || report.maxMs[i] > report.maxMs[worstAt]) {
                worstAt = i;
            }
        }
        int worstColor = worstMs > budget ? Theme.DANGER : ORANGE;
        String worstSub = tps
            ? I18n.format("lagfinder.tile.peak_at", Names.clock(Math.max(0, worstAt) * report.bucketSec))
            : I18n.format("lagfinder.tile.peak_fps", Names.fps(worstMs > 0 ? 1000f / worstMs : 0));
        int worst = worstAt;
        drawTile(
            xs[2],
            y0,
            xs[3] - gap,
            y1,
            Icons.SMALL_WARN,
            worstColor,
            I18n.format(tps ? "lagfinder.tile.worst_tick" : "lagfinder.tile.worst_frame"),
            Names.ms(worstMs),
            worstColor,
            worstSub,
            Theme.blend(worstColor, Theme.TEXT_MUTED, 0.4),
            I18n.format("lagfinder.tip.worst_period"));
        if (worst >= 0) {
            hit(xs[2], y0, xs[3] - gap, y1, () -> pin(worst), I18n.format("lagfinder.tip.worst_period"));
        }

        // The time a tick may take.
        String budgetSub = tps ? I18n.format("lagfinder.tile.target_tps", String.format("%.0f", target))
            : report.frameCap > 0 ? I18n.format("lagfinder.tile.target_cap", Math.round(report.frameCap))
                : I18n.format("lagfinder.tile.target_fps", 60);
        drawTile(
            xs[3],
            y0,
            xs[4] - gap,
            y1,
            Icons.SMALL_STORE,
            Theme.ACCENT,
            I18n.format(tps ? "lagfinder.tile.budget_tick" : "lagfinder.tile.budget_frame"),
            Names.ms(budget),
            Theme.TEXT,
            budgetSub,
            Theme.TEXT_MUTED,
            I18n.format("lagfinder.tip.budget"));

        // How many were measured, over how long.
        String over = report.durationSec < 120
            ? I18n.format("lagfinder.tile.seconds", Math.round(report.durationSec))
            : I18n.format("lagfinder.tile.over", Names.clock(report.durationSec));
        drawTile(
            xs[4],
            y0,
            xs[5] - gap,
            y1,
            Icons.SMALL_CUBE,
            Theme.ACCENT,
            I18n.format(tps ? "lagfinder.tile.ticks" : "lagfinder.tile.frames"),
            String.valueOf(report.periods),
            Theme.TEXT,
            over,
            Theme.TEXT_MUTED,
            null);

        drawSuspect(xs[5], y0, right, y1, mouseX, mouseY);
    }

    /** How the rate went from the first third of the scan to the last. */
    private float trend() {
        int n = report.buckets();
        if (n < 3) {
            return 0;
        }
        int third = Math.max(1, n / 3);
        double first = 0, last = 0;
        for (int i = 0; i < third; i++) {
            first += report.rate[i];
            last += report.rate[n - 1 - i];
        }
        return (float) ((last - first) / third);
    }

    /** The time a tick (or frame) may take: 50 ms of a tick, a frame at the limit or at 60 FPS. */
    private float budgetMs() {
        if (report.mode == ScanMode.TPS) {
            return report.mode.budgetMs();
        }
        return report.frameCap > 0 ? 1000f / report.frameCap : 1000f / 60f;
    }

    /**
     * A tile: an icon on a badge in its color, a label, a big value and a line under it. Returns where the value
     * ends, for something to go after it.
     */
    private int drawTile(int x0, int y0, int x1, int y1, String[] icon, int color, String label, String value,
        int valueColor, String sub, int subColor, String tooltip) {
        Theme.gradient(x0, y0, x1, y1, Theme.blend(0xFF161B22, color, 0.09), Theme.blend(0xFF11151A, color, 0.03));
        Theme.outline(x0, y0, x1, y1, Theme.blend(Theme.BORDER, color, 0.45));
        Theme.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, Theme.SHEEN);
        int badge = 26;
        int bx = x0 + 7, by = y0 + (y1 - y0 - badge) / 2;
        Theme.fill(bx, by, bx + badge, by + badge, Theme.blend(0xFF12161B, color, 0.16));
        Theme.outline(bx, by, bx + badge, by + badge, Theme.blend(Theme.BORDER, color, 0.55));
        drawIconLarge(icon, bx + badge / 2, by + badge / 2, color);
        int x = bx + badge + 7, room = x1 - 6 - x;
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, label, room), x, y0 + 5, Theme.TEXT_MUTED);
        int valueWidth = drawBig(value, x, y0 + 16, valueColor, room);
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, sub, room), x, y0 + 30, subColor);
        if (tooltip != null) {
            hit(x0, y0, x1, y1, null, label + "\n" + tooltip);
        }
        return x + valueWidth;
    }

    /**
     * What takes the most time of all, with its time per tick: named the cause of the lag when the rate dropped and it
     * takes a good part of it. A click shows it in its tab.
     */
    private void drawSuspect(int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        ReportEntry entry = suspect;
        if (entry == null) {
            drawTile(
                x0,
                y0,
                x1,
                y1,
                Icons.SMALL_TARGET,
                Theme.ACCENT,
                I18n.format("lagfinder.tile.offender"),
                "—",
                Theme.TEXT_MUTED,
                I18n.format("lagfinder.overview.none_time"),
                Theme.TEXT_DISABLED,
                null);
            return;
        }
        double average = report.perPeriod(entry);
        double share = shareOf(average);
        boolean cause = suspectIsCause();
        int color = cause ? Theme.DANGER : share >= 5 ? ORANGE : Theme.ACCENT;
        boolean hovered = Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
        double lit = light("suspect", hovered);
        if (cause) {
            // Breathing softly, so it is seen first.
            double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 400.0);
            Theme.glow(x0, y0, x1, y1, color, 0.25 + 0.35 * pulse + 0.4 * lit);
        } else {
            Theme.glow(x0, y0, x1, y1, color, lit * 0.6);
        }
        String sub = I18n.format(inFps() ? "lagfinder.tile.costs" : "lagfinder.tile.per_tick", avgText(average))
            + "  ·  "
            + String.format("%.0f%%", share);
        drawTile(
            x0,
            y0,
            x1,
            y1,
            Icons.SMALL_TARGET,
            color,
            I18n.format(cause ? "lagfinder.suspect.cause" : "lagfinder.tile.offender"),
            Names.of(entry),
            color == Theme.ACCENT ? Theme.blend(Theme.ACCENT, Theme.TEXT, 0.35) : color,
            sub,
            Theme.TEXT_MUTED,
            null);
        ItemStack stack = Names.icon(entry);
        if (stack != null) {
            // The thing itself on the badge, instead of the cross hairs.
            int bx = x0 + 7, by = y0 + (y1 - y0 - 26) / 2;
            Theme.fill(bx + 1, by + 1, bx + 25, by + 25, Theme.blend(0xFF12161B, color, 0.16));
            Draw.item(stack, bx + 5, by + 5, 16);
        }
        String mod = modOf(entry);
        String where = entry.level == 3 ? Names.position(entry)
            : entry.category.hasInstances ? I18n.format("lagfinder.suspect.kind", instancesOf(entry))
                : Names.category(entry.category);
        hit(x0, y0, x1, y1, () -> {
            pinned = -1;
            reveal(entry);
        },
            Names.of(entry) + "\n"
                + mod
                + "  ·  "
                + Names.category(entry.category)
                + "  ·  "
                + where
                + "\n"
                + I18n.format(
                    cause ? "lagfinder.tip.suspect_cause" : "lagfinder.tip.suspect",
                    String.format("%.0f%%", share))
                + "\n"
                + I18n.format("lagfinder.tip.reveal"));
    }

    private static int fpsColor(float fps) {
        return fps >= 55 ? Theme.SUCCESS : fps >= 30 ? WARN : Theme.DANGER;
    }

    private static String[] icon(Category category) {
        switch (category) {
            case TILE_ENTITIES:
                return Icons.SMALL_CUBE;
            case ENTITIES:
                return Icons.SMALL_PERSON;
            case BLOCK_TICKS:
                return Icons.SMALL_CLOCK;
            case EVENTS:
                return Icons.SMALL_BOLT;
            case WORLDS:
                return Icons.SMALL_GLOBE;
            case TILE_RENDER:
                return Icons.SMALL_FRAME;
            case ENTITY_RENDER:
                return Icons.SMALL_CREEPER;
            case AE2_NETWORKS:
                return Icons.SMALL_NETWORK;
            case SERVER:
                return Icons.SMALL_STORE;
            default:
                return Icons.SMALL_GRID;
        }
    }

    // ---------------------------------------------------------------- Tabs

    private void drawTabs(int mouseX, int mouseY) {
        int count = report.categories.size();
        if (count == 0) {
            return;
        }
        int left = panelLeft + 8, right = panelRight - 8;
        int each = (right - left) / count;
        int y0 = tabsY, y1 = tabsY + TAB_HEIGHT;
        if (tabMark.get() < 0) {
            tabMark.set(left + tab * each);
        }
        double markX = tabMark.update(left + tab * each, 18);
        Theme.fill(left, y1 - 1, right, y1, Theme.BORDER);
        for (int i = 0; i < count; i++) {
            ReportEntry category = report.categories.get(i);
            int x0 = left + i * each, x1 = i == count - 1 ? right : x0 + each;
            boolean active = i == tab;
            boolean hovered = Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
            double lit = light(category, hovered || active);
            if (active) {
                Theme.gradient(x0, y0, x1, y1 - 1, Theme.blend(0xFF161C25, Theme.ACCENT, 0.10), 0xFF151C26);
            } else {
                Theme.fill(x0, y0, x1, y1 - 1, Theme.blend(0x00000000, 0xFF1A1F27, lit));
            }
            if (i > 0) {
                Theme.fill(x0, y0 + 5, x0 + 1, y1 - 5, 0xFF222831);
            }
            int color = active ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
            Icons.draw(icon(category.category), x0 + 8, y0 + 7, active ? Theme.ACCENT : Theme.TEXT_MUTED);
            String value = avgText(report.perPeriod(category));
            int valueWidth = fontRendererObj.getStringWidth(value);
            String name = Theme
                .ellipsize(fontRendererObj, Names.category(category.category, report.mode), x1 - x0 - 32 - valueWidth);
            fontRendererObj.drawString(name, x0 + 20, y0 + 7, color);
            fontRendererObj
                .drawString(value, x1 - 8 - valueWidth, y0 + 7, active ? Theme.ACCENT : Theme.TEXT_DISABLED);
            int index = i;
            hit(x0, y0, x1, y1, () -> {
                if (tab != index || !sectionOpen) {
                    openTab(index);
                }
            },
                I18n.format("lagfinder.category." + category.category.id) + "\n"
                    + I18n.format("lagfinder.tip.category." + category.category.id));
        }
        Theme.fill((int) markX, y1 - 2, (int) markX + each, y1, Theme.ACCENT);
    }

    // ---------------------------------------------------------------- The bar of filters

    private void drawToolbar(int mouseX, int mouseY) {
        int y = toolY;
        drawDropdown(
            "mods",
            modsX,
            y,
            MODS_W,
            Icons.SMALL_FILTER,
            modFilter == null ? I18n.format("lagfinder.filter.all_mods") : modFilter,
            modFilter != null,
            mouseX,
            mouseY,
            this::openModsMenu,
            I18n.format("lagfinder.tip.filter_mods"));
        drawDropdown(
            "categories",
            catsX,
            y,
            CATS_W,
            Icons.SMALL_GRID,
            categoryFilter == null ? I18n.format("lagfinder.filter.all_categories")
                : Names.category(categoryFilter, report.mode),
            categoryFilter != null,
            mouseX,
            mouseY,
            this::openCategoriesMenu,
            I18n.format("lagfinder.tip.filter_categories"));
        drawDropdown(
            "sort",
            sortX,
            y,
            SORT_W,
            Icons.SMALL_SORT,
            I18n.format("lagfinder.filter.sort", I18n.format(sort.key)),
            false,
            mouseX,
            mouseY,
            this::openSortMenu,
            I18n.format("lagfinder.tip.sort"));

        // Only what is slow.
        int boxY = y + (TOOL_HEIGHT - 10) / 2;
        int slowRight = slowX + slowWidth();
        boolean overSlow = Theme.inside(mouseX, mouseY, slowX, y, slowRight, y + TOOL_HEIGHT);
        double slowLit = light("slow", overSlow);
        Theme.fill(slowX, boxY, slowX + 10, boxY + 10, onlySlow ? Theme.ACCENT_DIM : Theme.CONTROL);
        Theme.outline(
            slowX,
            boxY,
            slowX + 10,
            boxY + 10,
            onlySlow ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT, slowLit));
        if (onlySlow) {
            Icons.draw(Icons.SMALL_CHECK, slowX + 2, boxY + 2, Theme.TEXT);
        }
        fontRendererObj.drawString(
            slowLabel(),
            slowX + 14,
            y + 5,
            onlySlow ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, slowLit));
        hit(slowX, y, slowRight, y + TOOL_HEIGHT, () -> {
            onlySlow = !onlySlow;
            scroll = 0;
            rebuild();
        }, I18n.format("lagfinder.tip.only_slow", Names.ms(slowMs())));

        // All of the pick (or of the tab) as cards, and the settings.
        ReportEntry cardsOf = selected != null && GuiInstances.hasInstances(selected) ? selected
            : category() != null && GuiInstances.hasInstances(category()) ? category() : null;
        drawIconButton(
            "cards",
            cardsX,
            y,
            Icons.SMALL_GRID,
            cardsOf != null,
            false,
            mouseX,
            mouseY,
            cardsOf == null ? null : () -> openCards(cardsOf),
            I18n.format("lagfinder.action.cards") + "\n" + I18n.format("lagfinder.tip.cards"));
        drawIconButton(
            "settings",
            settingsX,
            y,
            Icons.SMALL_GEAR,
            true,
            menu != null && "settings".equals(menu.owner),
            mouseX,
            mouseY,
            () -> openSettings(settingsX + 18 - 220, y + TOOL_HEIGHT + 2, y, false),
            I18n.format("lagfinder.settings.title"));
    }

    /** A control showing what is chosen, dropping a list of the choices down. */
    private void drawDropdown(Object key, int x, int y, int w, String[] icon, String text, boolean on, int mouseX,
        int mouseY, Runnable open, String tooltip) {
        boolean opened = menu != null && key.equals(menu.owner);
        boolean hovered = Theme.inside(mouseX, mouseY, x, y, x + w, y + TOOL_HEIGHT);
        double lit = light("drop" + key, hovered || opened);
        Theme.gradient(
            x,
            y,
            x + w,
            y + TOOL_HEIGHT,
            Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit),
            Theme.blend(0xFF1D232B, Theme.CONTROL_HOVER, lit));
        Theme.outline(
            x,
            y,
            x + w,
            y + TOOL_HEIGHT,
            opened || on ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT, lit * 0.6));
        Icons.draw(icon, x + 5, y + (TOOL_HEIGHT - icon.length) / 2, on ? Theme.ACCENT : Theme.TEXT_MUTED);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, text, w - 30),
            x + 16,
            y + 5,
            on || hovered ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.5));
        String[] arrow = opened ? Icons.SMALL_UP : Icons.SECTION_OPEN;
        Icons.draw(arrow, x + w - 10, y + 8, Theme.TEXT_MUTED);
        hit(x, y, x + w, y + TOOL_HEIGHT, open, opened ? null : tooltip);
    }

    /** A square button with an icon. */
    private void drawIconButton(Object key, int x, int y, String[] icon, boolean enabled, boolean on, int mouseX,
        int mouseY, Runnable action, String tooltip) {
        int size = TOOL_HEIGHT;
        boolean hovered = enabled && Theme.inside(mouseX, mouseY, x, y, x + size, y + size);
        double lit = light("button" + key, hovered);
        Theme.fill(
            x,
            y,
            x + size,
            y + size,
            !enabled ? Theme.CONTROL_DISABLED
                : on ? Theme.ACCENT_DIM : Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
        Theme.outline(x, y, x + size, y + size, on ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT, lit));
        Icons.draw(
            icon,
            x + (size - Icons.width(icon)) / 2,
            y + (size - icon.length) / 2,
            !enabled ? Theme.TEXT_DISABLED : hovered || on ? Theme.TEXT : Theme.TEXT_MUTED);
        hit(x, y, x + size, y + size, enabled ? action : null, tooltip);
    }

    // ---------------------------------------------------------------- The table

    private void drawList(int mouseX, int mouseY) {
        Theme.card(listLeft, bodyTop, listRight, bodyBottom);
        int top = listTop(), bottom = listBottom();
        if (rows.isEmpty()) {
            String empty = I18n.format(lastSearch.isEmpty() ? "lagfinder.gui.nothing" : "lagfinder.gui.nothing_found");
            Theme.centered(fontRendererObj, empty, (listLeft + listRight) / 2, top + 20, Theme.TEXT_DISABLED);
        } else {
            int offset = (int) Math.round(shownScroll.update(scroll, 18));
            Theme.clip(listLeft + 1, top, listRight - 1, bottom);
            clipHits(listLeft + 1, top, listRight - 1, bottom);
            boolean inside = Theme.inside(mouseX, mouseY, listLeft, top, listRight, bottom);
            int mx = inside ? mouseX : -1000, my = inside ? mouseY : -1000;
            for (Row row : rows) {
                int y = top + row.y - offset;
                if (y + row.height < top || y > bottom) {
                    continue;
                }
                switch (row.type) {
                    case SECTION:
                        drawSection(row, y, mx, my);
                        break;
                    case HEAD:
                        drawColumns(row, y, mx, my);
                        break;
                    case ITEM:
                        drawKind(row, y, mx, my, row.sectionMax);
                        break;
                    default:
                        drawEmpty(row, y);
                }
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
                    inside);
            }
        }
        // How much is shown, and how long a tick took.
        int fy = bottom;
        Theme.fill(listLeft + 1, fy, listRight - 1, fy + 1, Theme.BORDER);
        Theme.fill(listLeft + 1, fy + 1, listRight - 1, bodyBottom - 1, 0xFF0F1216);
        Icons.draw(Icons.SMALL_CLOCK, listLeft + 7, fy + 5, Theme.TEXT_DISABLED);
        String showing = I18n.format("lagfinder.list.showing", shownKinds, allKinds);
        fontRendererObj.drawString(showing, listLeft + 18, fy + 4, Theme.TEXT_MUTED);
        float averageMs = report.averagePeriodMs();
        String total = Names.ms(averageMs) + String.format(" (%.1f%%)", shareOfBudget(averageMs));
        String label = I18n.format(fps() ? "lagfinder.list.total_frame" : "lagfinder.list.total_tick") + "  ";
        int totalWidth = fontRendererObj.getStringWidth(total);
        int labelX = listRight - 8 - totalWidth - fontRendererObj.getStringWidth(label);
        if (labelX > listLeft + 24 + fontRendererObj.getStringWidth(showing)) {
            fontRendererObj.drawString(label, labelX, fy + 4, Theme.TEXT_DISABLED);
        }
        fontRendererObj.drawString(
            total,
            listRight - 8 - totalWidth,
            fy + 4,
            averageMs > budgetMs() ? Theme.DANGER : Theme.ACCENT);
    }

    /** A category's title line: how many things, its time per tick and share; a click opens or closes it. */
    private void drawSection(Row row, int y, int mouseX, int mouseY) {
        ReportEntry category = row.entry;
        boolean active = row.index == tab;
        boolean open = row.y + row.height < contentHeight && rows.indexOf(row) + 1 < rows.size()
            && rows.get(rows.indexOf(row) + 1).type == HEAD;
        boolean hovered = Theme.inside(mouseX, mouseY, listLeft + 1, y, listRight - 1, y + row.height);
        double lit = light("section" + row.index, hovered);
        int background = open ? 0xFF161C25 : 0xFF12161B;
        Theme.fill(listLeft + 1, y, listRight - 1, y + row.height, Theme.blend(background, 0xFF1D2430, lit));
        if (row.y > 0) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + 1, 0xFF1C2129);
        }
        int textY = y + (row.height - 8) / 2;
        String[] arrow = open ? Icons.SECTION_OPEN : Icons.SECTION_CLOSED;
        Icons.draw(
            arrow,
            listLeft + 8 + (5 - Icons.width(arrow)) / 2,
            y + (row.height - arrow.length) / 2,
            hovered ? Theme.TEXT : Theme.TEXT_MUTED);
        Icons.draw(
            icon(category.category),
            listLeft + 18,
            y + (row.height - 7) / 2,
            active ? Theme.ACCENT : Theme.TEXT_MUTED);
        String name = Names.category(category.category, report.mode);
        fontRendererObj.drawStringWithShadow(
            name,
            listLeft + 30,
            textY,
            active || open ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.5 + 0.5 * lit));
        int after = listLeft + 30 + fontRendererObj.getStringWidth(name) + 10;
        String count = sectionCount(category);
        double average = report.perPeriod(category);
        String value = I18n.format(
            fps() && inFps() ? "lagfinder.list.section_fps" : "lagfinder.list.section_tick",
            avgText(average),
            String.format("%.1f%%", shareOf(average)));
        int valueWidth = fontRendererObj.getStringWidth(value);
        int valueRight = listRight - 8 - (open ? 0 : 10);
        if (after + fontRendererObj.getStringWidth(count) < valueRight - valueWidth - 8) {
            fontRendererObj.drawString(count, after, textY, Theme.TEXT_DISABLED);
        }
        fontRendererObj.drawString(
            value,
            valueRight - valueWidth,
            textY,
            open ? Theme.ACCENT : Theme.blend(severityOrAccent(average), Theme.TEXT_MUTED, 0.35));
        if (!open) {
            Icons.draw(Icons.SECTION_CLOSED, listRight - 12, y + (row.height - 5) / 2, Theme.TEXT_DISABLED);
        }
        hit(listLeft + 1, y, listRight - 1, y + row.height, () -> {
            if (row.index == tab && categoryFilter == null && lastSearch.isEmpty()) {
                sectionOpen = !sectionOpen;
                rebuild();
            } else if (row.index != tab) {
                openTab(row.index);
            }
        }, I18n.format("lagfinder.category." + category.category.id) + "\n"
            + I18n.format("lagfinder.tip.category." + category.category.id));
    }

    /** "12 objects", "128 handlers": what a category holds. */
    private String sectionCount(ReportEntry category) {
        int count = 0;
        for (ReportEntry mod : category.children) {
            for (ReportEntry kind : mod.children) {
                count += category.category.hasInstances ? Math.max(1, countOf(kind)) : 1;
            }
        }
        String key = "lagfinder.list.count." + category.category.id;
        String text = I18n.format(key, count);
        return text.equals(key) ? I18n.format("lagfinder.list.count", count) : text;
    }

    private int severityOrAccent(double perPeriodMs) {
        int severity = severity(perPeriodMs);
        return severity == Theme.TEXT ? Theme.ACCENT : severity;
    }

    /** The titles of the columns; a click sorts by one, again the other way round. */
    private void drawColumns(Row row, int y, int mouseX, int mouseY) {
        Theme.fill(listLeft + 1, y, listRight - 1, y + row.height, 0xFF0F1318);
        Theme.fill(listLeft + 1, y + row.height - 1, listRight - 1, y + row.height, 0xFF1C2129);
        int textY = y + 3;
        drawColumn(I18n.format("lagfinder.column.name"), listLeft + 30, false, textY, Sort.NAME, null, mouseX, mouseY);
        drawColumn(
            I18n.format("lagfinder.column.count"),
            colCount,
            true,
            textY,
            Sort.COUNT,
            I18n.format("lagfinder.tip.instances"),
            mouseX,
            mouseY);
        drawColumn(
            totalTitle(),
            colTotalLeft,
            false,
            textY,
            Sort.AVERAGE,
            I18n.format(inFps() ? "lagfinder.tip.avg_fps" : "lagfinder.tip.avg"),
            mouseX,
            mouseY);
        if (showCall) {
            drawColumn(
                I18n.format("lagfinder.column.per_call"),
                colCall,
                true,
                textY,
                Sort.CALL,
                I18n.format("lagfinder.tip.per_call"),
                mouseX,
                mouseY);
        }
        drawColumn(
            peakTitle(),
            colPeak,
            true,
            textY,
            Sort.PEAK,
            I18n.format(inFps() ? "lagfinder.tip.peak_fps" : "lagfinder.tip.peak"),
            mouseX,
            mouseY);
        drawColumn(
            shareTitle(),
            colShare,
            true,
            textY,
            Sort.AVERAGE,
            I18n.format("lagfinder.tip.share"),
            mouseX,
            mouseY);
    }

    private void drawColumn(String title, int x, boolean alignRight, int y, Sort by, String tooltip, int mouseX,
        int mouseY) {
        int w = fontRendererObj.getStringWidth(title);
        int x0 = alignRight ? x - w : x;
        boolean sorted = sort == by && !(by == Sort.AVERAGE && x == colShare);
        boolean hovered = Theme.inside(mouseX, mouseY, x0 - 2, y - 3, x0 + w + 8, y + 11);
        fontRendererObj.drawString(
            title,
            x0,
            y,
            sorted ? Theme.ACCENT : hovered ? Theme.TEXT_MUTED : Theme.TEXT_DISABLED);
        if (sorted) {
            String[] arrow = reversed ? Icons.SMALL_UP : Icons.SECTION_OPEN;
            Icons.draw(arrow, alignRight ? x0 - 7 : x0 + w + 2, y + 3, Theme.ACCENT);
        }
        hit(
            x0 - 2,
            y - 3,
            x0 + w + 8,
            y + 11,
            () -> sortBy(by),
            tooltip == null ? I18n.format(
                "lagfinder.tip.sort_column") : tooltip + "\n" + I18n.format("lagfinder.tip.sort_column"));
    }

    /** Nothing in the open section: nothing measured, nothing found, or (said apart) nothing could be measured. */
    private void drawEmpty(Row row, int y) {
        String unhooked = "lagfinder.warning.unhooked." + row.entry.category.id;
        if (lastSearch.isEmpty() && modFilter == null && !onlySlow && report.warnings.contains(unhooked)) {
            List<?> lines = fontRendererObj
                .listFormattedStringToWidth(I18n.format(unhooked), listRight - listLeft - 24);
            for (int i = 0; i < lines.size() && i < 2; i++) {
                Theme.centered(
                    fontRendererObj,
                    (String) lines.get(i),
                    (listLeft + listRight) / 2,
                    y + 6 + i * 10,
                    WARN);
            }
            return;
        }
        boolean filtered = !lastSearch.isEmpty() || modFilter != null || onlySlow;
        String empty = I18n.format(filtered ? "lagfinder.gui.nothing_found" : "lagfinder.gui.nothing");
        Theme.centered(fontRendererObj, empty, (listLeft + listRight) / 2, y + 11, Theme.TEXT_DISABLED);
    }

    private boolean fps() {
        return report.mode == ScanMode.FPS;
    }

    /** Whether costs are told in frames a second (not when the frames were held to a limit: ms of a frame then). */
    private boolean inFps() {
        return report.costsInFps();
    }

    /** Time per tick in milliseconds; in a frame scan, the frames a second it costs. */
    private String avgText(double perPeriodMs) {
        return inFps() ? Names.fpsLoss(report.fpsCost(perPeriodMs)) : Names.ms(perPeriodMs);
    }

    /** The entry's worst tick in milliseconds; in a frame scan, how far the frames a second fell then. */
    private String peakText(ReportEntry entry) {
        return inFps() ? Names.fpsLoss(report.fpsDrop(report.perPeriod(entry), entry.maxMs)) : Names.ms(entry.maxMs);
    }

    /** Part of an average tick (or frame) this much time per tick is (percent). */
    private double shareOf(double perPeriodMs) {
        return report.averagePeriodMs() <= 0 ? 0 : perPeriodMs / report.averagePeriodMs() * 100;
    }

    /** Part of the time a tick may take this much is (percent). */
    private double shareOfBudget(double perPeriodMs) {
        return budgetMs() <= 0 ? 0 : perPeriodMs / budgetMs() * 100;
    }

    private static String percent(double share) {
        return share >= 10 ? String.format("%.1f%%", share) : String.format("%.2f%%", share);
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

    /** A short name of a class: what follows its package. */
    private static String shortClass(String name) {
        int dot = name.lastIndexOf('.');
        int dollar = name.lastIndexOf('$');
        return name.substring(Math.max(dot, dollar) + 1);
    }

    /** A kind of thing: its icon, name and mod, how many there are, and its numbers in the columns. */
    private void drawKind(Row row, int y, int mouseX, int mouseY, double sectionMax) {
        ReportEntry entry = row.entry;
        int h = row.height;
        boolean hovered = Theme.inside(mouseX, mouseY, listLeft + 1, y, listRight - 1, y + h);
        boolean isSelected = entry == selected;
        double lit = light(entry, hovered);
        if (isSelected) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + h, SELECTED);
            Theme.fill(listLeft + 1, y, listLeft + 3, y + h, Theme.ACCENT);
        } else if (lit > 0.01) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + h, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        Theme.fill(listLeft + 8, y + h - 1, listRight - 8, y + h, 0xFF171B21);
        // The line's own click first: the buttons drawn on it later take precedence.
        hit(listLeft + 1, y, listRight - 1, y + h, () -> {
            if (selected == entry && GuiInstances.hasInstances(entry)) {
                openCards(entry);
            } else {
                setSelected(entry);
            }
        }, null);

        boolean twoLines = !compact && subtitles;
        int nameY = twoLines ? y + 3 : y + (h - 8) / 2;
        // Its icon.
        int iconSize = compact ? 12 : 16;
        int ix = listLeft + 10, iy = y + (h - iconSize) / 2;
        ItemStack stack = Names.icon(entry);
        if (stack != null) {
            Draw.item(stack, ix, iy, iconSize);
        } else {
            Icons.draw(icon(entry.category), ix + (iconSize - 7) / 2, y + (h - 7) / 2, Theme.TEXT_MUTED);
        }
        int x = listLeft + 30;
        int room = nameRight - x;
        if (isSpiky(entry)) {
            Icons.draw(Icons.SMALL_SPIKE, nameRight - 8, nameY, ORANGE);
            room -= 11;
        }
        String name = Theme.ellipsize(fontRendererObj, Names.of(entry), room);
        fontRendererObj.drawString(
            name,
            x,
            nameY,
            isSelected ? Theme.TEXT : Theme.blend(Theme.TEXT, 0xFFFFFFFF, lit * 0.3));
        String mod = modOf(entry);
        if (twoLines) {
            String sub = mod + (entry.detail != null ? "  ·  " + shortClass(entry.detail) : "");
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, sub, nameRight - x),
                x,
                y + 13,
                Theme.TEXT_DISABLED);
        }
        hit(x, nameY - 1, x + fontRendererObj.getStringWidth(name), nameY + 9, null, Names.of(entry) + "\n" + mod
            + (entry.detail != null ? "\n" + entry.detail : ""));

        int textY = y + (h - 8) / 2;
        // How many there are: a button opening them all as cards.
        if (GuiInstances.hasInstances(entry)) {
            drawCardsPill(entry, y, h, colCount + 4, mouseX, mouseY);
        } else {
            String count = entry.instances > 1 ? String.valueOf(entry.instances) : "—";
            fontRendererObj.drawString(
                count,
                colCount - fontRendererObj.getStringWidth(count),
                textY,
                Theme.TEXT_DISABLED);
        }

        // Its time per tick, with a bar of it against the most of the section.
        double average = report.perPeriod(entry);
        String total = avgText(average);
        int totalColor = severity(average);
        int barY = compact ? y + h - 4 : y + 15;
        int totalY = compact ? textY - 1 : y + 4;
        fontRendererObj.drawString(total, colTotalLeft, totalY, totalColor);
        int barColor = totalColor == Theme.TEXT ? Theme.ACCENT : totalColor;
        Theme.meter(colTotalLeft, barY, colTotalRight, barY + 2, entry.totalMs / sectionMax, barColor);

        if (showCall) {
            double call = perCall(entry);
            String perCall = call > 0 ? Names.ms(call) : "—";
            fontRendererObj.drawString(
                perCall,
                colCall - fontRendererObj.getStringWidth(perCall),
                textY,
                Theme.TEXT_MUTED);
        }
        String peak = peakText(entry);
        fontRendererObj.drawString(peak, colPeak - fontRendererObj.getStringWidth(peak), textY, peakColor(entry));
        String share = percent(shareOf(average));
        fontRendererObj.drawString(share, colShare - fontRendererObj.getStringWidth(share), textY, Theme.TEXT_MUTED);
    }

    /**
     * The button in the count column: a grid and how many of them there are ("10"), opening them all as cards. Lit
     * under the mouse; returns where it starts.
     */
    private int drawCardsPill(ReportEntry entry, int y, int height, int right, int mouseX, int mouseY) {
        int number = Math.max(entry.instances, GuiInstances.countInstances(entry));
        String count = String.valueOf(number);
        int w = 7 + 4 + fontRendererObj.getStringWidth(count) + 9;
        int h = 12;
        int x0 = right - w, y0 = y + (height - h) / 2;
        boolean over = Theme.inside(mouseX, mouseY, x0, y0, right, y0 + h);
        double lit = light("pill" + System.identityHashCode(entry), over);
        int color = Draw.modColor(modOf(entry));
        Theme.fill(x0, y0, right, y0 + h, Theme.blend(Theme.CONTROL, Theme.blend(Theme.CONTROL, color, 0.35), lit));
        Theme.outline(x0, y0, right, y0 + h, Theme.blend(Theme.BORDER, color, 0.3 + 0.7 * lit));
        Icons.draw(Icons.SMALL_GRID, x0 + 4, y0 + (h - 7) / 2, Theme.blend(Theme.TEXT_MUTED, color, 0.4 + 0.6 * lit));
        fontRendererObj
            .drawString(count, x0 + 15, y0 + (h - 8) / 2 + 1, Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit));
        hit(
            x0,
            y0,
            right,
            y0 + h,
            () -> openCards(entry),
            I18n.format("lagfinder.action.cards_count", number) + "\n" + I18n.format("lagfinder.tip.cards"));
        return x0;
    }

    /** Opens all the things of the kind (or the tab) as cards, to look through them one by one. */
    private void openCards(ReportEntry entry) {
        if (entry.level > 0) {
            setSelected(entry);
        }
        // Searched for one of its things (coordinates, a label): the cards open searched for it too.
        String query = lastSearch.trim()
            .toLowerCase(Locale.ROOT);
        boolean forThings = !query.isEmpty() && !matches(entry, query) && anyMatches(entry.children, query);
        mc.displayGuiScreen(new GuiInstances(report, entry, this).withSearch(forThings ? lastSearch : null));
    }

    /** The dimension of an instance when it isn't the player's, e.g. "[Nether]"; null otherwise. */
    private String dimensionTag(ReportEntry entry) {
        int playerDim = mc.theWorld != null ? mc.theWorld.provider.dimensionId : 0;
        if (!entry.hasLocation() || entry.dim == playerDim) {
            return null;
        }
        return "[" + report.dimensionName(entry.dim) + "]";
    }

    private void teleport(ReportEntry entry) {
        if (!entry.hasLocation() || !ClientScanner.INSTANCE.teleportAllowed()) {
            return;
        }
        Net.sendToServer(new Net.Teleport(entry.dim, entry.x, entry.y, entry.z));
        if (!Highlighter.INSTANCE.isHighlighted(entry)) {
            Highlighter.INSTANCE.toggle(report, entry);
        }
        // The key brings the log back, to go on to the next place.
        GuiLagFinder.closeToGame(mc, this);
    }

    // ---------------------------------------------------------------- The graph

    private void drawChart(int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        Theme.card(x0, y0, x1, y1);
        boolean tps = report.mode == ScanMode.TPS;
        int n = report.buckets();
        int from = rangeFrom();
        int count = n - from;

        // Its tabs: what it shows.
        ChartMode[] modes = selected != null && selected.sum != null
            ? new ChartMode[] { ChartMode.RATE, ChartMode.AVERAGE, ChartMode.PEAK, ChartMode.PICK }
            : new ChartMode[] { ChartMode.RATE, ChartMode.AVERAGE, ChartMode.PEAK };
        if (chartMode == ChartMode.PICK && modes.length < 4) {
            chartMode = ChartMode.RATE;
        }
        // The range and the button making the graph take all the right side, at the right.
        int expandX = x1 - 6 - 16, controlsY = y0 + 5;
        drawChartButton(expandX, controlsY, mouseX, mouseY);
        String rangeText = rangeLabel(range);
        int rangeWidth = Math.min(110, fontRendererObj.getStringWidth(rangeText) + 32);
        int rangeX = expandX - 4 - rangeWidth;
        drawSmallDropdown(rangeX, controlsY, rangeWidth, rangeText, mouseX, mouseY);

        int tx = x0 + 6;
        for (ChartMode mode : modes) {
            String label = chartLabel(mode);
            int room = rangeX - 6 - tx - (mode == ChartMode.PICK ? 0 : 40);
            if (room < 30) {
                break;
            }
            label = Theme.ellipsize(fontRendererObj, label, Math.min(room - 16, mode == ChartMode.PICK ? 140 : 200));
            int w = fontRendererObj.getStringWidth(label) + (mode == ChartMode.RATE ? 26 : 16);
            boolean active = chartMode == mode;
            boolean hovered = Theme.inside(mouseX, mouseY, tx, controlsY, tx + w, controlsY + 16);
            double lit = light("chart" + mode, hovered || active);
            Theme.fill(
                tx,
                controlsY,
                tx + w,
                controlsY + 16,
                active ? Theme.blend(0xFF161C25, Theme.ACCENT, 0.16) : Theme.blend(0x00000000, 0xFF1A1F27, lit));
            if (active) {
                Theme.outline(tx, controlsY, tx + w, controlsY + 16, Theme.blend(Theme.BORDER, Theme.ACCENT, 0.5));
                Theme.fill(tx + 1, controlsY + 14, tx + w - 1, controlsY + 16, Theme.ACCENT);
            }
            int textX = tx + 8;
            if (mode == ChartMode.RATE) {
                Icons.draw(Icons.SMALL_PULSE, tx + 7, controlsY + 4, active ? Theme.SUCCESS : Theme.TEXT_MUTED);
                textX += 10;
            }
            int color = mode == ChartMode.PICK && selected != null ? Draw.modColor(modOf(selected))
                : active ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
            fontRendererObj.drawString(label, textX, controlsY + 4, color);
            hit(tx, controlsY, tx + w, controlsY + 16, () -> chartMode = mode, null);
            tx += w + 2;
        }

        if (x1 - x0 - 80 < 20 || y1 - y0 - 60 < 20 || count <= 0) {
            return;
        }
        Chart chart = new Chart(fontRendererObj, x0 + 30, y0 + 30, x1 - 42, y1 - 28, count);
        float target = tps ? 20f : 60f;
        float rateMax = tps ? 20f : (float) Draw.niceCeil(Math.max(30, max(report.rate)) * 1.05);
        float budget = budgetMs();
        String rateName = tps ? "TPS" : "FPS";
        int tickColor = TICK_COLOR;
        // In each moment: what the bars show and up to where (the light top), on the scale of the right axis.
        float[] low = new float[count], high = new float[count];
        boolean drawRate = chartMode == ChartMode.RATE || chartMode == ChartMode.PICK;
        boolean inMs = chartMode != ChartMode.PICK || !report.costsInFps();
        ReportEntry shown = chartMode == ChartMode.PICK ? selected : null;
        int elementColor = shown != null ? Draw.modColor(modOf(shown)) : tickColor;
        float redLimit = tps ? budget * 0.5f : Math.max(budget * 1.5f, 25f);
        for (int k = 0; k < count; k++) {
            int i = from + k;
            switch (chartMode) {
                case PICK:
                    low[k] = report.perPeriod(shown, i);
                    high[k] = shown.max[i];
                    if (!inMs) {
                        high[k] = (float) report.fpsDrop(low[k], high[k], i);
                        low[k] = (float) report.fpsCost(low[k], i);
                    }
                    break;
                case PEAK:
                    low[k] = report.maxMs[i];
                    high[k] = report.maxMs[i];
                    break;
                default:
                    low[k] = report.avgMs[i];
                    high[k] = report.maxMs[i];
            }
        }
        float scale;
        if (chartMode == ChartMode.RATE) {
            // Scaled to the average ticks, the worst ones over the line cut at the top: one long tick must not
            // flatten all the others.
            float red = 0;
            for (int k = 0; k < count; k++) {
                if (high[k] >= redLimit) {
                    red = Math.max(red, high[k]);
                }
            }
            scale = (float) Draw.niceCeil(Math.max(Math.max(max(low) * 1.3f, Math.min(red, budget * 1.2f)), 0.05f));
        } else if (chartMode == ChartMode.AVERAGE) {
            scale = (float) Draw.niceCeil(Math.max(max(low) * 1.15f, 0.05f));
        } else {
            scale = (float) Draw.niceCeil(Math.max(Math.max(max(high), max(low)) * 1.08f, 0.01f));
        }

        // How good the rate is, as faint bands behind all: the same colors its line takes.
        if (drawRate && bands) {
            chart.zone(0, target * 0.5f, rateMax, 0x0EE5534B);
            chart.zone(target * 0.5f, target * 0.75f, rateMax, 0x0AFF8C42);
            chart.zone(target * 0.75f, target * 0.95f, rateMax, 0x08F2C14E);
        }
        chart.grid(4);
        String[] scaleLabels = new String[5];
        for (int i = 0; i <= 4; i++) {
            scaleLabels[i] = costLabel(scale * i / 4, inMs, i == 0);
        }
        if (drawRate) {
            String[] rateLabels = new String[5];
            for (int i = 0; i <= 4; i++) {
                float value = rateMax * i / 4;
                rateLabels[i] = value == Math.floor(value) ? String.valueOf((int) value) : String.format("%.1f", value);
            }
            chart.leftAxis(rateLabels, Theme.SUCCESS);
            chart.rightAxis(scaleLabels, chartMode == ChartMode.RATE ? Theme.DANGER : elementColor);
        } else {
            chart.leftAxis(scaleLabels, tickColor);
        }
        chart.picked(pinned - from);

        boolean noGraph = chartMode == ChartMode.PICK && shown == null;
        if (!noGraph) {
            for (int k = 0; k < count; k++) {
                switch (chartMode) {
                    case RATE:
                        if (high[k] >= redLimit) {
                            // A tick far over its usual: drawn up to its worst, red.
                            chart.bar(k, 0, high[k], scale, Theme.DANGER, Theme.blend(Theme.DANGER, 0xFF000000, 0.45));
                        }
                        if (low[k] > 0) {
                            chart.bar(k, 0, low[k], scale, tickColor, Theme.blend(tickColor, 0xFF000000, 0.45));
                        }
                        break;
                    case PEAK: {
                        int color = high[k] >= budget ? Theme.DANGER : high[k] >= report.spikeMs ? ORANGE : tickColor;
                        chart.bar(k, 0, high[k], scale, color, Theme.blend(color, 0xFF000000, 0.45));
                        break;
                    }
                    case AVERAGE:
                        break;
                    default:
                        if (high[k] > low[k]) {
                            // How far its worst tick in this moment went: the swing to look at for things that lag
                            // now and then.
                            chart.bar(
                                k,
                                low[k],
                                high[k],
                                scale,
                                Theme.blend(elementColor, 0x00000000, 0.45) & 0x80FFFFFF,
                                (elementColor & 0x00FFFFFF) | 0x20000000);
                            double top = chart.y(high[k], scale), left = chart.left(k);
                            Draw.gradientRect(left, top, left + chart.barWidth(), top + 1, elementColor, elementColor);
                        }
                        if (low[k] > 0) {
                            chart.bar(
                                k,
                                0,
                                low[k],
                                scale,
                                elementColor | 0xFF000000,
                                Theme.blend(elementColor, 0xFF000000, 0.4));
                        }
                }
            }
            if (chartMode == ChartMode.AVERAGE) {
                chart.line(low, scale, Chart.solid(tickColor, count), 1.5f, 0x40);
            }
            // The time a tick may take before the rate drops.
            if (inMs) {
                String limit = Names.ms(budget)
                    .replace(" ", "");
                chart.threshold(budget, scale, Theme.DANGER, I18n.format("lagfinder.graph.budget", limit));
            }
        } else {
            Theme.centered(
                fontRendererObj,
                I18n.format("lagfinder.graph.no_graph"),
                (chart.x0 + chart.x1) / 2,
                chart.y0 + 10,
                Theme.TEXT_DISABLED);
        }
        // The rate as a line with an area under it, colored by how good it was.
        float[] rates = new float[count];
        if (drawRate) {
            int[] rateColors = new int[count];
            for (int k = 0; k < count; k++) {
                rates[k] = report.rate[from + k];
                rateColors[k] = Draw.rateColor(rates[k], target);
            }
            chart.line(rates, rateMax, rateColors, 1.5f, 0x30);
        }
        chart.timeAxis(from * report.bucketSec, report.durationSec);

        // The legend under it: each thing with how it looks.
        String period = I18n.format(tps ? "lagfinder.graph.tick_time" : "lagfinder.graph.frame_time");
        int legendWidth = x1 - x0 - 16;
        Chart.Legend legend = new Chart.Legend(
            fontRendererObj,
            x0 + 8 + Math.max(0, legendWidth / 2 - 150),
            y1 - 11,
            x1 - 8);
        switch (chartMode) {
            case RATE:
                legend.line(rateName, Theme.SUCCESS, true);
                legend.bar(I18n.format("lagfinder.graph.legend_avg_only", period), tickColor, false);
                legend.bar(
                    I18n.format("lagfinder.graph.legend_over", period, Names.ms(redLimit).replace(" ", "")),
                    Theme.DANGER,
                    false);
                break;
            case AVERAGE:
                legend.line(I18n.format("lagfinder.graph.legend_avg_only", period), tickColor, true);
                legend.dashed(I18n.format("lagfinder.graph.budget", Names.ms(budget).replace(" ", "")), Theme.DANGER);
                break;
            case PEAK:
                legend.bar(I18n.format("lagfinder.graph.legend_max", period), tickColor, true);
                legend.bar(
                    I18n.format("lagfinder.graph.legend_spike", Names.ms(report.spikeMs).replace(" ", "")),
                    ORANGE,
                    false);
                legend.bar(I18n.format("lagfinder.graph.legend_over_budget"), Theme.DANGER, false);
                break;
            default:
                legend.line(rateName, Theme.SUCCESS, true);
                legend.bar(
                    I18n.format(
                        inMs ? "lagfinder.graph.legend_avg" : "lagfinder.graph.legend_avg_fps",
                        Names.of(shown)),
                    elementColor,
                    false);
                legend.band(
                    I18n.format(inMs ? "lagfinder.graph.legend_peak" : "lagfinder.graph.legend_peak_fps"),
                    elementColor,
                    false);
        }

        // The moment the rate was lowest in: a mark over it, a click from what lagged then.
        if (worstMoment >= from && worstMoment < n && drawRate) {
            int cx = (int) Math.round(chart.center(worstMoment - from));
            chart.marker(cx, 0x50E5534B);
            for (int row = 0; row < 4; row++) {
                Theme.fill(cx - 3 + row, chart.y0 - 6 + row, cx + 4 - row, chart.y0 - 5 + row, Theme.DANGER);
            }
            int moment = worstMoment;
            hit(
                cx - 5,
                chart.y0 - 8,
                cx + 6,
                chart.y0,
                () -> pin(moment),
                I18n.format(
                    "lagfinder.graph.worst",
                    String.format("%s %.1f", rateName, report.rate[moment]),
                    Names.clock(moment * report.bucketSec)) + "\n" + I18n.format("lagfinder.tip.pin"));
        }

        // What was at the moment under the mouse: marked on the lines with its values beside the axes, told in a box
        // with what took the most time then; a click picks it, to see what lagged then.
        if (chart.contains(mouseX, mouseY)) {
            int k = chart.bucketAt(mouseX);
            int i = from + k;
            chart.hover(k);
            int rateColor = Draw.rateColor(report.rate[i], target);
            if (!noGraph) {
                float value = chartMode == ChartMode.PEAK ? high[k] : low[k] > 0 ? low[k] : high[k];
                double valueY = chart.y(value, scale);
                int valueColor = chartMode == ChartMode.PICK ? elementColor : tickColor;
                Chart.dot(chart.center(k), valueY, valueColor);
                if (drawRate) {
                    chart.rightValue(valueY, costLabel(value, inMs, false), valueColor);
                } else {
                    chart.leftValue(valueY, costLabel(value, inMs, false), valueColor);
                }
            }
            if (drawRate) {
                double rateY = chart.y(report.rate[i], rateMax);
                Chart.dot(chart.center(k), rateY, rateColor);
                chart.leftValue(rateY, String.format("%.1f", report.rate[i]), rateColor);
            }
            hit(chart.x0, chart.y0, chart.x1, chart.y1, () -> pin(i), null);

            List<String> lines = new ArrayList<>();
            List<Integer> lineColors = new ArrayList<>();
            float start = i * report.bucketSec, end = Math.min(report.durationSec, (i + 1) * report.bucketSec);
            lines.add(clockOf(start) + "  (" + Names.clock(start) + " – " + Names.clock(end) + ")");
            lineColors.add(Theme.TEXT);
            lines.add(String.format("%s: %.1f", rateName, report.rate[i]));
            lineColors.add(rateColor);
            lines.add(I18n.format("lagfinder.graph.tick_avg", period, Names.ms(report.avgMs[i])));
            lineColors.add(Theme.TEXT_MUTED);
            lines.add(I18n.format("lagfinder.graph.tick_max", period, Names.ms(report.maxMs[i])));
            lineColors.add(report.maxMs[i] >= budget ? Theme.DANGER : Theme.TEXT_MUTED);
            ReportEntry top = topAt(i);
            if (top != null) {
                lines.add(I18n.format("lagfinder.graph.top", Names.of(top), Names.ms(report.perPeriod(top, i))));
                lineColors.add(Theme.DANGER);
            }
            if (shown != null) {
                lines.add(Names.of(shown) + ":");
                lineColors.add(elementColor);
                lines.add(
                    "  " + (inMs ? I18n.format("lagfinder.graph.element", Names.ms(low[k]), Names.ms(high[k]))
                        : I18n.format("lagfinder.graph.element_fps", Names.fpsLoss(low[k]), Names.fpsLoss(high[k]))));
                lineColors.add(Theme.TEXT);
            }
            lines.add(I18n.format(i == pinned ? "lagfinder.graph.click_unpin" : "lagfinder.graph.click_pin"));
            lineColors.add(Theme.TEXT_DISABLED);
            Draw.tooltip(fontRendererObj, lines, lineColors, mouseX, mouseY, width, height);
        }
    }

    /** The time of a moment of the scan: the time of day it was, or since the start when that isn't known. */
    private String clockOf(float seconds) {
        if (report.startedAt <= 0) {
            return Names.clock(seconds);
        }
        return new SimpleDateFormat("HH:mm:ss").format(new Date(report.startedAt + Math.round(seconds * 1000)));
    }

    private String chartLabel(ChartMode mode) {
        boolean tps = report.mode == ScanMode.TPS;
        switch (mode) {
            case RATE:
                return tps ? "TPS" : "FPS";
            case AVERAGE:
                return I18n.format(tps ? "lagfinder.graph.tab_avg" : "lagfinder.graph.tab_avg_frame");
            case PEAK:
                return I18n.format(tps ? "lagfinder.graph.tab_max" : "lagfinder.graph.tab_max_frame");
            default:
                return selected == null ? "" : Names.of(selected);
        }
    }

    /** The control of the graph's range. */
    private void drawSmallDropdown(int x, int y, int w, String text, int mouseX, int mouseY) {
        boolean opened = menu != null && "range".equals(menu.owner);
        boolean hovered = Theme.inside(mouseX, mouseY, x, y, x + w, y + 16);
        double lit = light("range", hovered || opened);
        Theme.fill(x, y, x + w, y + 16, Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
        Theme.outline(
            x,
            y,
            x + w,
            y + 16,
            opened || range != 0 ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT, lit * 0.6));
        Icons.draw(Icons.SMALL_CALENDAR, x + 5, y + 4, range != 0 ? Theme.ACCENT : Theme.TEXT_MUTED);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, text, w - 30),
            x + 16,
            y + 4,
            hovered ? Theme.TEXT : Theme.TEXT_MUTED);
        Icons.draw(opened ? Icons.SMALL_UP : Icons.SECTION_OPEN, x + w - 10, y + 7, Theme.TEXT_MUTED);
        if (ranges().size() > 1) {
            hit(
                x,
                y,
                x + w,
                y + 16,
                () -> openRangeMenu(x, y, Math.max(w, 110)),
                opened ? null : I18n.format("lagfinder.tip.range"));
        } else {
            hit(x, y, x + w, y + 16, null, I18n.format("lagfinder.tip.range"));
        }
    }

    /** The button making the graph take all the right side, or giving the room back. */
    private void drawChartButton(int x, int y, int mouseX, int mouseY) {
        boolean hovered = Theme.inside(mouseX, mouseY, x, y, x + 16, y + 16);
        double lit = light("expand", hovered);
        Theme.fill(
            x,
            y,
            x + 16,
            y + 16,
            chartExpanded ? Theme.ACCENT_DIM : Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
        Theme.outline(
            x,
            y,
            x + 16,
            y + 16,
            chartExpanded ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT, lit));
        String[] icon = chartExpanded ? Icons.SMALL_COLLAPSE : Icons.SMALL_EXPAND;
        Icons.draw(icon, x + 5, y + 5, hovered || chartExpanded ? Theme.TEXT : Theme.TEXT_MUTED);
        hit(
            x,
            y,
            x + 16,
            y + 16,
            () -> chartExpanded = !chartExpanded,
            I18n.format(chartExpanded ? "lagfinder.tip.chart_shrink" : "lagfinder.tip.chart_expand"));
    }

    /** A value of a scale: milliseconds, or the frames a second it costs. */
    private static String costLabel(float value, boolean inMs, boolean zero) {
        if (inMs) {
            return zero ? "0" : Names.ms(value)
                .replace(" ", "");
        }
        if (zero) {
            return "0";
        }
        return "-" + (value >= 10 || value == Math.floor(value) ? String.format("%.0f", value)
            : String.format("%.1f", value));
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

    // ---------------------------------------------------------------- Under the graph

    private void drawDetails(int mouseX, int mouseY) {
        int x0 = rightLeft, y0 = graphBottom + 6, x1 = rightRight, y1 = bodyBottom;
        if (y1 - y0 < 40) {
            return;
        }
        if (selected != null) {
            Theme.card(x0, y0, x1, y1);
            drawSelection(selected, x0, y0, x1, y1, mouseX, mouseY);
        } else if (pinned >= 0 && pinnedByAverage != null) {
            Theme.card(x0, y0, x1, y1);
            drawMoment(x0, y0, x1, y1, mouseX, mouseY);
        } else {
            int middle = (x0 + x1) / 2;
            drawMostTime(x0, y0, middle - 3, y1, mouseX, mouseY);
            drawWorstSpikes(middle + 3, y0, x1, y1, mouseX, mouseY);
        }
    }

    /** The title of a card under the graph: an icon on a badge, its name, and what it is about under it. */
    private void drawCardTitle(int x0, int y0, String[] icon, int color, String title, String subtitle) {
        Theme.disc(x0 + 16, y0 + 16, 8.5, Theme.blend(0xFF12161B, color, 0.25));
        Icons.draw(icon, x0 + 13, y0 + 13, color);
        fontRendererObj.drawStringWithShadow(title, x0 + 30, y0 + 7, Theme.TEXT);
        fontRendererObj.drawString(subtitle, x0 + 30, y0 + 17, Theme.TEXT_DISABLED);
    }

    /** What took the most time over the whole scan: the steady load. */
    private void drawMostTime(int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        Theme.card(x0, y0, x1, y1);
        drawCardTitle(
            x0,
            y0,
            Icons.SMALL_CLOCK,
            Theme.ACCENT,
            I18n.format("lagfinder.overview.most_time"),
            I18n.format("lagfinder.overview.most_time_sub"));
        int top = y0 + 30;
        int rowsFit = Math.max(1, (y1 - top - 4) / 12);
        if (byAverage.isEmpty() || byAverage.get(0).totalMs <= 0) {
            Icons.draw(Icons.SMALL_CHECK, x0 + 10, top + 3, Theme.SUCCESS);
            fontRendererObj.drawString(
                I18n.format("lagfinder.overview.none_time"),
                x0 + 21,
                top + 2,
                Theme.TEXT_DISABLED);
            return;
        }
        for (int i = 0; i < byAverage.size() && i < rowsFit; i++) {
            ReportEntry entry = byAverage.get(i);
            double average = report.perPeriod(entry);
            if (average <= 0) {
                break;
            }
            drawRankRow(
                entry,
                "time" + i,
                x0,
                top + i * 12,
                x1,
                avgText(average),
                severity(average) == Theme.TEXT ? Theme.TEXT : severity(average),
                percent(shareOf(average)),
                Theme.TEXT_DISABLED,
                38,
                mouseX,
                mouseY);
        }
    }

    /** The worst single ticks: rare heavy work, with where it was, and a button to all of them. */
    private void drawWorstSpikes(int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        Theme.card(x0, y0, x1, y1);
        drawCardTitle(
            x0,
            y0,
            Icons.SMALL_BOLT,
            WARN,
            I18n.format("lagfinder.overview.spikes"),
            I18n.format("lagfinder.overview.spikes_sub"));
        int top = y0 + 30;
        if (bySpike.isEmpty()) {
            Icons.draw(Icons.SMALL_CHECK, x0 + 10, top + 3, Theme.SUCCESS);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.overview.none"), x1 - x0 - 30),
                x0 + 21,
                top + 2,
                Theme.TEXT_DISABLED);
            return;
        }
        int rowsFit = Math.max(1, (y1 - top - 16) / 12);
        for (int i = 0; i < bySpike.size() && i < rowsFit; i++) {
            ReportEntry entry = bySpike.get(i);
            drawRankRow(
                entry,
                "spike" + i,
                x0,
                top + i * 12,
                x1,
                entry.level == 3 ? Names.position(entry) : "",
                Theme.TEXT_DISABLED,
                peakText(entry),
                peakColor(entry),
                44,
                mouseX,
                mouseY);
        }
        // All of them.
        String all = I18n.format("lagfinder.overview.all_spikes", bySpike.size());
        int ax = x0 + 10, ay = y1 - 12;
        int aw = fontRendererObj.getStringWidth(all);
        boolean over = Theme.inside(mouseX, mouseY, ax - 2, ay - 2, ax + aw + 12, ay + 10);
        fontRendererObj.drawString(all, ax, ay, over ? Theme.TEXT : Theme.ACCENT);
        Icons.draw(Icons.SMALL_ARROW, ax + aw + 4, ay + 1, over ? Theme.TEXT : Theme.ACCENT);
        hit(ax - 2, ay - 2, ax + aw + 12, ay + 10, () -> {
            spikesOpen = true;
            spikesScroll = 0;
        }, null);
    }

    /**
     * A line of a list under the graph: its icon and name, then two columns at the right (a value and something more),
     * a click from showing it in its tab.
     */
    private void drawRankRow(ReportEntry entry, String key, int x0, int y, int x1, String middle, int middleColor,
        String right, int rightColor, int rightWidth, int mouseX, int mouseY) {
        int left = x0 + 8, end = x1 - 8;
        boolean hovered = Theme.inside(mouseX, mouseY, left - 2, y, end + 2, y + 12);
        double lit = light(key, hovered);
        if (lit > 0.01) {
            Theme.fill(left - 2, y, end + 2, y + 12, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        Icons.draw(
            icon(entry.category),
            left + 1,
            y + 2,
            Theme.blend(Draw.modColor(modOf(entry)), Theme.TEXT_DISABLED, 0.4));
        fontRendererObj.drawString(right, end - fontRendererObj.getStringWidth(right), y + 2, rightColor);
        int middleRight = end - rightWidth - 6;
        int middleWidth = fontRendererObj.getStringWidth(middle);
        fontRendererObj.drawString(middle, middleRight - middleWidth, y + 2, middleColor);
        int nameRoom = middleRight - middleWidth - 8 - (left + 12);
        String name = Theme.ellipsize(fontRendererObj, Names.of(entry), Math.max(20, nameRoom));
        fontRendererObj.drawString(
            name,
            left + 12,
            y + 2,
            hovered ? Theme.TEXT : Theme.blend(Theme.TEXT, Theme.TEXT_MUTED, 0.2));
        hit(
            left - 2,
            y,
            end + 2,
            y + 12,
            () -> reveal(entry),
            Names.of(entry) + "\n"
                + modOf(entry)
                + "  ·  "
                + Names.category(entry.category)
                + "\n"
                + I18n.format("lagfinder.tip.reveal"));
    }

    /** With a moment of the graph picked: what took the most time then, and the worst ticks then. */
    private void drawMoment(int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        boolean tps = report.mode == ScanMode.TPS;
        int i = pinned;
        float from = i * report.bucketSec, to = Math.min(report.durationSec, (i + 1) * report.bucketSec);
        // Which moment it is and how it went, with a button back to the whole scan.
        String back = I18n.format("lagfinder.moment.back");
        int backWidth = fontRendererObj.getStringWidth(back) + 22;
        int bx = x1 - 8 - backWidth, by = y0 + 5;
        boolean overBack = Theme.inside(mouseX, mouseY, bx, by, bx + backWidth, by + 14);
        double backLit = light("back", overBack);
        Theme.gradient(
            bx,
            by,
            bx + backWidth,
            by + 14,
            Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, backLit),
            Theme.blend(0xFF1D232B, Theme.CONTROL_HOVER, backLit));
        Theme.outline(bx, by, bx + backWidth, by + 14, Theme.blend(Theme.BORDER, Theme.ACCENT, backLit));
        Icons.draw(Icons.SMALL_CLEAR, bx + 5, by + 4, overBack ? Theme.TEXT : Theme.TEXT_MUTED);
        fontRendererObj.drawString(back, bx + 16, by + 3, overBack ? Theme.TEXT : Theme.TEXT_MUTED);
        hit(bx, by, bx + backWidth, by + 14, () -> pin(i), I18n.format("lagfinder.tip.unpin"));

        Theme.fill(x0 + 8, y0 + 7, x0 + 10, y0 + 17, Theme.ACCENT);
        String moment = Names.clock(from) + " – " + Names.clock(to);
        fontRendererObj.drawStringWithShadow(moment, x0 + 14, y0 + 8, Theme.TEXT);
        int after = x0 + 14 + fontRendererObj.getStringWidth(moment) + 8;
        String rate = String.format("%s %.1f", tps ? "TPS" : "FPS", report.rate[i]);
        fontRendererObj.drawString(rate, after, y0 + 8, Draw.rateColor(report.rate[i], tps ? 20f : 60f));
        after += fontRendererObj.getStringWidth(rate) + 8;
        String tick = tps ? I18n.format("lagfinder.graph.tick", Names.ms(report.avgMs[i]), Names.ms(report.maxMs[i]))
            : I18n.format("lagfinder.graph.frame_fps", Names.fps(report.maxMs[i] > 0 ? 1000f / report.maxMs[i] : 0));
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, tick, bx - 8 - after), after, y0 + 8, Theme.TEXT_MUTED);

        int top = y0 + 24;
        Theme.fill(x0 + 6, top - 4, x1 - 6, top - 3, 0xFF1C2129);
        int columnWidth = (x1 - x0 - 24) / 2;
        int rowsFit = Math.max(1, (y1 - top - 22) / 14);
        drawTopList(
            I18n.format("lagfinder.moment.most_time"),
            Icons.SMALL_CLOCK,
            Theme.ACCENT,
            pinnedByAverage,
            entry -> momentAvgText(entry, i),
            entry -> severity(report.perPeriod(entry, i)),
            I18n.format("lagfinder.moment.none"),
            x0 + 8,
            top,
            x0 + 8 + columnWidth,
            rowsFit,
            mouseX,
            mouseY);
        drawTopList(
            I18n.format(tps ? "lagfinder.moment.spikes" : "lagfinder.moment.spikes_fps"),
            Icons.SMALL_SPIKE,
            ORANGE,
            pinnedBySpike,
            entry -> momentPeakText(entry, i),
            entry -> momentPeakColor(entry, i),
            I18n.format("lagfinder.overview.none"),
            x0 + 16 + columnWidth,
            top,
            x1 - 8,
            rowsFit,
            mouseX,
            mouseY);
    }

    /** The entry's time per tick in the moment; in a frame scan, the frames a second it cost then. */
    private String momentAvgText(ReportEntry entry, int moment) {
        float average = report.perPeriod(entry, moment);
        return inFps() ? Names.fpsLoss(report.fpsCost(average, moment)) : Names.ms(average);
    }

    private String momentPeakText(ReportEntry entry, int moment) {
        float peak = entry.max[moment];
        return inFps() ? Names.fpsLoss(report.fpsDrop(report.perPeriod(entry, moment), peak, moment))
            : Names.ms(peak);
    }

    private int momentPeakColor(ReportEntry entry, int moment) {
        float peak = entry.max[moment];
        return peak >= report.mode.budgetMs() * 0.5f ? Theme.DANGER
            : peak >= report.spikeMs ? ORANGE : Theme.TEXT_MUTED;
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

    /**
     * A column of a moment's overview: its title, then the first entries, each a click from showing it in its tab.
     */
    private void drawTopList(String title, String[] titleIcon, int titleColor, List<ReportEntry> entries,
        Function<ReportEntry, String> valueOf, ToIntFunction<ReportEntry> colorOf, String none, int x0, int y0, int x1,
        int count, int mouseX, int mouseY) {
        Icons.draw(titleIcon, x0, y0 + 1, titleColor);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, title.toUpperCase(), x1 - x0 - 12),
            x0 + 11,
            y0,
            Theme.TEXT_MUTED);
        if (entries.isEmpty()) {
            Icons.draw(Icons.SMALL_CHECK, x0, y0 + 17, Theme.SUCCESS);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, none, x1 - x0 - 12),
                x0 + 11,
                y0 + 16,
                Theme.TEXT_DISABLED);
            return;
        }
        for (int i = 0; i < entries.size() && i < count; i++) {
            ReportEntry entry = entries.get(i);
            int y = y0 + 14 + i * 14;
            boolean hovered = Theme.inside(mouseX, mouseY, x0 - 2, y, x1 + 2, y + 14);
            double lit = light(title + i, hovered);
            int color = colorOf.applyAsInt(entry);
            // Its rank, colored as bad as it is: the first lines are what to look at.
            int rank = Theme.blend(color, Theme.TEXT_DISABLED, Math.min(0.8, i * 0.2));
            Theme.fill(x0 - 2, y + 2, x0, y + 12, rank);
            if (lit > 0.01) {
                Theme.fill(x0, y, x1 + 2, y + 14, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
            }
            String value = valueOf.apply(entry);
            int valueWidth = fontRendererObj.getStringWidth(value);
            fontRendererObj.drawString(value, x1 - valueWidth, y + 3, color);
            ItemStack stack = Names.icon(entry);
            if (stack != null) {
                Draw.item(stack, x0 + 2, y + 1, 12);
            } else {
                Icons.draw(icon(entry.category), x0 + 4, y + 4, Theme.TEXT_DISABLED);
            }
            String name = Names.of(entry);
            String where = entry.level == 3 ? "  " + Names.position(entry) : "";
            int room = x1 - valueWidth - 6 - (x0 + 17);
            String shownName = Theme.ellipsize(fontRendererObj, name, room);
            fontRendererObj.drawString(shownName, x0 + 17, y + 3, Theme.TEXT);
            int after = x0 + 17 + fontRendererObj.getStringWidth(shownName);
            if (!where.isEmpty() && after < x1 - valueWidth - 6) {
                fontRendererObj.drawString(
                    Theme.ellipsize(fontRendererObj, where, x1 - valueWidth - 6 - after),
                    after,
                    y + 3,
                    Theme.TEXT_DISABLED);
            }
            hit(
                x0 - 2,
                y,
                x1 + 2,
                y + 14,
                () -> reveal(entry),
                name + "\n"
                    + modOf(entry)
                    + "  ·  "
                    + Names.category(entry.category)
                    + "\n"
                    + I18n.format("lagfinder.tip.reveal"));
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
        // Back to the overview, top right.
        int closeX = x1 - 8 - 12;
        boolean overClose = Theme.inside(mouseX, mouseY, closeX, y - 2, closeX + 12, y + 10);
        double closeLit = light("close", overClose);
        Theme.fill(closeX, y - 2, closeX + 12, y + 10, Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, closeLit));
        Theme.outline(closeX, y - 2, closeX + 12, y + 10, Theme.blend(Theme.BORDER, Theme.ACCENT, closeLit));
        Icons.draw(Icons.SMALL_CLEAR, closeX + 3, y + 1, overClose ? Theme.TEXT : Theme.TEXT_MUTED);
        hit(
            closeX,
            y - 2,
            closeX + 12,
            y + 10,
            () -> setSelected(null),
            I18n.format(pinned >= 0 ? "lagfinder.tip.back_moment" : "lagfinder.tip.back_overview"));
        String mod = modOf(entry);
        int modWidth = fontRendererObj.getStringWidth(mod) + 10;
        int titleRight = closeX - 4;
        if (entry.level > 1) {
            Theme.pill(fontRendererObj, mod, titleRight - modWidth, y - 2, Draw.modColor(mod));
            titleRight -= modWidth + 4;
        }
        String name = Names.of(entry) + (entry.level == 3 && entry.label != null ? " · " + entry.label : "");
        fontRendererObj
            .drawStringWithShadow(Theme.ellipsize(fontRendererObj, name, titleRight - x - 22), x + 20, y, Theme.TEXT);
        y += 13;
        String kind = Names.category(entry.category) + (entry.detail != null ? "  ·  " + entry.detail : "");
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, kind, x1 - x - 28), x + 20, y, Theme.TEXT_DISABLED);
        y += 14;

        // The numbers.
        boolean tps = report.mode == ScanMode.TPS;
        double average = report.perPeriod(entry);
        double share = shareOf(average);
        int periods = Math.max(1, report.periods);
        String[] labels = { I18n.format(tps ? "lagfinder.stat.avg_tick" : "lagfinder.stat.avg_frame"),
            I18n.format(tps ? "lagfinder.stat.share_tick" : "lagfinder.stat.share_frame"),
            I18n.format(tps ? "lagfinder.stat.peak" : "lagfinder.stat.peak_fps"), I18n.format("lagfinder.stat.spikes"),
            I18n.format(tps ? "lagfinder.stat.calls_tick" : "lagfinder.stat.calls_frame"),
            entry.level == 3 ? I18n.format("lagfinder.stat.active") : I18n.format("lagfinder.stat.instances") };
        String[] values = { avgText(average), String.format("%.1f%%", share),
            peakText(entry) + "  @" + Names.clock(entry.maxAt),
            entry.spikes + String.format("  (%.1f%%)", entry.spikes * 100.0 / periods),
            String.format("%.1f", entry.calls / (double) periods),
            entry.level == 3 ? String.format("%.0f%%", Math.min(100, entry.activePeriods * 100.0 / periods))
                : entry.category.hasInstances ? String.valueOf(instancesOf(entry))
                    : entry.level == 1 ? String.valueOf(entry.children.size()) : "—" };
        int[] colors = { severity(average), severity(average), peakColor(entry), entry.spikes > 0 ? ORANGE : Theme.TEXT,
            Theme.TEXT, Theme.TEXT };
        String[] tips = { tps ? "lagfinder.tip.avg" : "lagfinder.tip.avg_fps", "lagfinder.tip.share",
            tps ? "lagfinder.tip.peak" : "lagfinder.tip.peak_fps", "lagfinder.tip.spikes", "lagfinder.tip.calls",
            entry.level == 3 ? "lagfinder.tip.active" : "lagfinder.tip.instances" };
        int columns = 3, gap = 6;
        int tileWidth = (x1 - x0 - 16 - gap * (columns - 1)) / columns;
        int tileHeight = 26;
        for (int i = 0; i < labels.length; i++) {
            int tx = x0 + 8 + (i % columns) * (tileWidth + gap), ty = y + (i / columns) * (tileHeight + 4);
            if (ty + tileHeight > y1 - 4) {
                break;
            }
            Theme.gradient(tx, ty, tx + tileWidth, ty + tileHeight, 0xFF161B22, 0xFF0F1216);
            Theme.outline(tx, ty, tx + tileWidth, ty + tileHeight, Theme.BORDER);
            Theme.fill(
                tx + 1,
                ty + tileHeight - 2,
                tx + tileWidth - 1,
                ty + tileHeight - 1,
                Theme.blend(colors[i], 0xFF0F1216, colors[i] == Theme.TEXT ? 0.85 : 0.45));
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, labels[i], tileWidth - 10),
                tx + 5,
                ty + 4,
                Theme.TEXT_DISABLED);
            fontRendererObj
                .drawString(Theme.ellipsize(fontRendererObj, values[i], tileWidth - 10), tx + 5, ty + 15, colors[i]);
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
                fontRendererObj.drawString(
                    Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.hint.spiky"), x1 - x0 - 30),
                    x0 + 19,
                    y,
                    ORANGE);
            }
            return;
        }
        if (isSpiky(entry) && y + 12 < y1) {
            Icons.draw(Icons.SMALL_SPIKE, x0 + 8, y + 1, ORANGE);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.hint.spiky"), x1 - x0 - 30),
                x0 + 19,
                y,
                ORANGE);
            y += 14;
        }
        if (entry.level < 3 && GuiInstances.hasInstances(entry) && y + 20 < y1) {
            drawCardsButton(entry, x0 + 8, y, x1 - 8, y + 18, mouseX, mouseY);
            y += 24;
        }
        // The worst ones under it.
        if (!entry.children.isEmpty() && y + 26 < y1) {
            fontRendererObj.drawString(
                I18n.format("lagfinder.details.worst")
                    .toUpperCase(),
                x0 + 8,
                y,
                Theme.TEXT_MUTED);
            y += 12;
            List<ReportEntry> children = sorted(entry.children);
            for (int i = 0; i < children.size() && y + 14 <= y1 - 4; i++) {
                ReportEntry child = children.get(i);
                boolean hovered = Theme.inside(mouseX, mouseY, x0 + 6, y, x1 - 6, y + 14);
                double lit = light("child" + i, hovered);
                if (lit > 0.01) {
                    Theme.fill(x0 + 6, y, x1 - 6, y + 14, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
                }
                String value = sort == Sort.PEAK ? peakText(child) : avgText(report.perPeriod(child));
                int valueWidth = fontRendererObj.getStringWidth(value);
                fontRendererObj.drawString(
                    value,
                    x1 - 10 - valueWidth,
                    y + 3,
                    sort == Sort.PEAK ? peakColor(child) : severity(report.perPeriod(child)));
                String text = child.level == 3 ? Names.position(child) + (child.label != null ? "  " + child.label : "")
                    : Names.of(child);
                String dim = dimensionTag(child);
                if (dim != null) {
                    text += "  " + dim;
                }
                fontRendererObj.drawString(
                    Theme.ellipsize(fontRendererObj, text, x1 - x0 - 30 - valueWidth),
                    x0 + 10,
                    y + 3,
                    Theme.TEXT);
                hit(x0 + 6, y, x1 - 6, y + 14, () -> reveal(child), I18n.format("lagfinder.tip.reveal"));
                y += 14;
            }
        }
    }

    /** A wide button opening all the things under the entry as cards. */
    private void drawCardsButton(ReportEntry entry, int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        boolean hovered = Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
        double lit = light("cards", hovered);
        Theme.gradient(
            x0,
            y0,
            x1,
            y1,
            Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit),
            Theme.blend(0xFF1D232B, Theme.CONTROL_HOVER, lit));
        Theme.outline(x0, y0, x1, y1, Theme.blend(Theme.blend(Theme.BORDER, Theme.ACCENT, 0.4), Theme.ACCENT, lit));
        Icons.draw(Icons.SMALL_GRID, x0 + 7, y0 + 6, Theme.ACCENT);
        String text = I18n.format("lagfinder.action.cards_count", GuiInstances.countInstances(entry));
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, text, x1 - x0 - 40),
            x0 + 19,
            y0 + 5,
            hovered ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.5));
        String key = "Enter";
        fontRendererObj.drawString(key, x1 - 7 - fontRendererObj.getStringWidth(key), y0 + 5, Theme.TEXT_DISABLED);
        hit(x0, y0, x1, y1, () -> openCards(entry), I18n.format("lagfinder.tip.cards"));
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
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, where, x1 - x - 14 - buttonsWidth), x + 11, y + 5, Theme.TEXT);
        int bx = x1 - 8 - buttonsWidth + 4;
        for (int i = 0; i < 3; i++) {
            boolean enabled = i != 0 || canTeleport;
            boolean on = i == 1 && highlighted;
            boolean hovered = enabled && Theme.inside(mouseX, mouseY, bx, y, bx + widths[i], y + 18);
            double lit = light(icons[i], hovered);
            int background = !enabled ? Theme.CONTROL_DISABLED
                : on ? Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, lit)
                    : Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
            Theme.fill(bx, y, bx + widths[i], y + 18, background);
            Theme.outline(
                bx,
                y,
                bx + widths[i],
                y + 18,
                on ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT, lit));
            int color = !enabled ? Theme.TEXT_DISABLED : Theme.TEXT;
            Icons.draw(icons[i], bx + 6, y + 6, color);
            fontRendererObj.drawString(labels[i], bx + 16, y + 5, color);
            Runnable action;
            String tip;
            if (i == 0) {
                action = enabled ? () -> teleport(entry) : null;
                tip = I18n.format(canTeleport ? "lagfinder.tip.teleport" : "lagfinder.action.no_teleport");
            } else if (i == 1) {
                action = () -> Highlighter.INSTANCE.toggle(report, entry);
                tip = I18n.format("lagfinder.tip.highlight");
            } else {
                action = () -> setClipboardString(entry.x + " " + entry.y + " " + entry.z);
                tip = I18n.format("lagfinder.tip.copy");
            }
            hit(bx, y, bx + widths[i], y + 18, action, tip);
            bx += widths[i] + 4;
        }
    }

    // ---------------------------------------------------------------- Insights and actions

    private void drawInsights(int mouseX, int mouseY) {
        int x0 = panelLeft + 8, x1 = panelRight - 8, y0 = insightsTop, y1 = insightsTop + INSIGHTS_HEIGHT;
        Theme.card(x0, y0, x1, y1);
        // The title.
        String title = I18n.format("lagfinder.insight.title");
        int titleWidth = Math.min(120, Math.max(70, fontRendererObj.getStringWidth(title) + 26));
        Theme.disc(x0 + 14, (y0 + y1) / 2.0, 8.5, Theme.blend(0xFF12161B, WARN, 0.2));
        Icons.draw(Icons.SMALL_BULB, x0 + 11, (y0 + y1) / 2 - 3, WARN);
        List<?> titleLines = fontRendererObj.listFormattedStringToWidth(title, titleWidth - 28);
        int ty = (y0 + y1) / 2 - titleLines.size() * 5 + 1;
        for (int i = 0; i < titleLines.size() && i < 2; i++) {
            fontRendererObj.drawStringWithShadow((String) titleLines.get(i), x0 + 28, ty + i * 10, Theme.TEXT);
        }

        // The buttons on the right: export and settings.
        String settings = I18n.format("lagfinder.settings.button");
        String export = I18n.format("lagfinder.export.button");
        int settingsWidth = fontRendererObj.getStringWidth(settings) + 26;
        int exportWidth = fontRendererObj.getStringWidth(export) + 26;
        int by = (y0 + y1) / 2 - 10;
        int settingsLeft = x1 - 8 - settingsWidth;
        int exportLeft = settingsLeft - 6 - exportWidth;
        drawActionButton(
            "export",
            exportLeft,
            by,
            exportWidth,
            Icons.SMALL_EXPORT,
            export,
            mouseX,
            mouseY,
            this::export,
            I18n.format("lagfinder.tip.export"));
        drawActionButton(
            "settingsButton",
            settingsLeft,
            by,
            settingsWidth,
            Icons.SMALL_GEAR,
            settings,
            mouseX,
            mouseY,
            () -> openSettings(x1 - 220, by - 4 * MENU_ROW - 6, by, false),
            I18n.format("lagfinder.settings.title"));

        // What the scan tells.
        int left = x0 + titleWidth + 8, right = exportLeft - 10;
        int count = insights.size();
        if (count == 0 || right - left < 60) {
            return;
        }
        int gap = 6;
        int each = (right - left - gap * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            int cx0 = left + i * (each + gap), cx1 = i == count - 1 ? right : cx0 + each;
            drawInsight(insights.get(i), cx0, y0 + 4, cx1, y1 - 4, mouseX, mouseY, i);
        }
    }

    private void drawInsight(Insight insight, int x0, int y0, int x1, int y1, int mouseX, int mouseY, int index) {
        int color = insight.color;
        Theme.gradient(x0, y0, x1, y1, Theme.blend(0xFF151A21, color, 0.07), Theme.blend(0xFF12161B, color, 0.03));
        Theme.outline(x0, y0, x1, y1, Theme.blend(Theme.BORDER, color, 0.35));
        Theme.disc(x0 + 12, y0 + 12, 7.5, Theme.blend(0xFF12161B, color, 0.3));
        Icons.draw(insight.icon, x0 + 9, y0 + 9, color);
        int x = x0 + 24, room = x1 - 6 - x;
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, insight.title, room), x, y0 + 4, color);
        List<?> lines = fontRendererObj.listFormattedStringToWidth(insight.text, room);
        int textLines = insight.link != null ? 1 : 2;
        for (int i = 0; i < lines.size() && i < textLines; i++) {
            String line = (String) lines.get(i);
            if (i == textLines - 1 && lines.size() > textLines) {
                line = Theme.ellipsize(fontRendererObj, line + "...", room);
            }
            fontRendererObj.drawString(line, x, y0 + 14 + i * 9, Theme.TEXT_MUTED);
        }
        hit(x0, y0, x1, y1, null, insight.title + "\n" + insight.text);
        if (insight.link != null) {
            int ly = y0 + 24;
            int lw = Math.min(room - 10, fontRendererObj.getStringWidth(insight.link));
            String link = Theme.ellipsize(fontRendererObj, insight.link, lw);
            boolean over = Theme.inside(mouseX, mouseY, x - 1, ly - 1, x + lw + 10, ly + 9);
            double lit = light("insight" + index, over);
            int linkColor = Theme.blend(Theme.ACCENT, Theme.TEXT, lit);
            fontRendererObj.drawString(link, x, ly, linkColor);
            Icons.draw(Icons.SMALL_ARROW, x + fontRendererObj.getStringWidth(link) + 3, ly + 1, linkColor);
            hit(x - 1, ly - 1, x + lw + 10, ly + 9, insight.action, null);
        }
    }

    private void drawActionButton(Object key, int x, int y, int w, String[] icon, String text, int mouseX, int mouseY,
        Runnable action, String tooltip) {
        boolean hovered = Theme.inside(mouseX, mouseY, x, y, x + w, y + 20);
        double lit = light(key, hovered);
        Theme.gradient(
            x,
            y,
            x + w,
            y + 20,
            Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit),
            Theme.blend(0xFF1D232B, Theme.CONTROL_HOVER, lit));
        Theme.outline(x, y, x + w, y + 20, Theme.blend(Theme.BORDER, Theme.ACCENT, lit));
        Icons.draw(icon, x + 8, y + 7, hovered ? Theme.TEXT : Theme.TEXT_MUTED);
        fontRendererObj.drawString(
            text,
            x + 19,
            y + 6,
            hovered ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.6));
        hit(x, y, x + w, y + 20, action, tooltip);
    }

    /** What the last export did, for a few seconds over the bottom of the window; a click opens its folder. */
    private void drawToast(int mouseX, int mouseY) {
        if (exportMessage == null) {
            return;
        }
        long age = System.currentTimeMillis() - exportedAt;
        if (age > 5000) {
            exportMessage = null;
            return;
        }
        double fade = age > 4500 ? (5000 - age) / 500.0 : 1;
        int w = fontRendererObj.getStringWidth(exportMessage) + 24;
        int x0 = (panelLeft + panelRight) / 2 - w / 2, y0 = insightsTop - 22;
        int color = exported != null ? Theme.SUCCESS : Theme.DANGER;
        int alpha = (int) Math.round(0xF0 * fade) << 24;
        Theme.shadow(x0, y0, x0 + w, y0 + 16, 3);
        Theme.fill(x0, y0, x0 + w, y0 + 16, (0x00141920) | alpha);
        Theme.outline(x0, y0, x0 + w, y0 + 16, (color & 0x00FFFFFF) | alpha);
        Icons.draw(exported != null ? Icons.SMALL_CHECK : Icons.SMALL_WARN, x0 + 6, y0 + 5, color);
        fontRendererObj.drawString(exportMessage, x0 + 17, y0 + 4, Theme.TEXT);
        if (exported != null) {
            File folder = exported.getParentFile();
            hit(x0, y0, x0 + w, y0 + 16, () -> GuiLogs.open(folder), I18n.format("lagfinder.tip.export_open"));
        }
    }

    /**
     * Writes the report as a table (CSV) next to the saved reports: a few lines about the scan, then every kind of
     * thing, and every one of them in the world with its place.
     */
    private void export() {
        File folder = new File(new File(mc.mcDataDir, "lagfinder"), "exports");
        String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date(report.startedAt)) + "_"
            + report.mode.name()
            + ".csv";
        File file = new File(folder, name);
        exportedAt = System.currentTimeMillis();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            exported = null;
            exportMessage = I18n.format("lagfinder.export.failed");
            return;
        }
        try (PrintWriter out = new PrintWriter(
            new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            boolean tps = report.mode == ScanMode.TPS;
            out.println("# LagFinder report," + csv(report.mode.name()) + "," + csv(report.source));
            out.println(
                "# started,"
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(report.startedAt)));
            out.println("# duration_s," + number(report.durationSec));
            out.println("# " + (tps ? "ticks" : "frames") + "," + report.periods);
            out.println("# average_rate," + number(report.averageRate()));
            out.println("# lowest_rate," + number(report.minimumRate()));
            out.println("# average_ms," + number(report.averagePeriodMs()));
            out.println("# worst_ms," + number(report.worstPeriodMs()));
            out.println(
                "category,mod,name,class,count,avg_ms_per_" + (tps ? "tick" : "frame")
                    + ",share_percent,avg_ms_per_call,peak_ms,spikes,calls_per_"
                    + (tps ? "tick" : "frame")
                    + ",dimension,x,y,z");
            double periods = Math.max(1, report.periods);
            for (ReportEntry category : report.categories) {
                for (ReportEntry mod : sorted(category.children)) {
                    for (ReportEntry kind : sorted(mod.children)) {
                        exportLine(out, category, mod, kind, periods);
                        for (ReportEntry thing : kind.children) {
                            if (thing.level == 3) {
                                exportLine(out, category, mod, thing, periods);
                            }
                        }
                    }
                }
            }
            exported = file;
            exportMessage = I18n.format("lagfinder.export.done", name);
        } catch (Throwable t) {
            LagFinder.LOG.warn("Could not export the report to {}", file, t);
            exported = null;
            exportMessage = I18n.format("lagfinder.export.failed");
        }
    }

    private void exportLine(PrintWriter out, ReportEntry category, ReportEntry mod, ReportEntry entry, double periods) {
        double average = report.perPeriod(entry);
        StringBuilder line = new StringBuilder();
        line.append(csv(category.category.id))
            .append(',')
            .append(csv(mod.name))
            .append(',')
            .append(csv(Names.of(entry) + (entry.level == 3 && entry.label != null ? " " + entry.label : "")))
            .append(',')
            .append(csv(entry.detail == null ? "" : entry.detail))
            .append(',')
            .append(entry.level == 3 ? 1 : countOf(entry))
            .append(',')
            .append(number(average))
            .append(',')
            .append(number(shareOf(average)))
            .append(',')
            .append(number(perCall(entry)))
            .append(',')
            .append(number(entry.maxMs))
            .append(',')
            .append(entry.spikes)
            .append(',')
            .append(number(entry.calls / periods))
            .append(',');
        if (entry.hasLocation()) {
            line.append(csv(report.dimensionName(entry.dim)))
                .append(',')
                .append(entry.x)
                .append(',')
                .append(entry.y)
                .append(',')
                .append(entry.z);
        } else {
            line.append(",,,");
        }
        out.println(line);
    }

    private static String csv(String text) {
        if (text == null) {
            return "";
        }
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    // ---------------------------------------------------------------- What opens over the rest

    private void drawMenu(int mouseX, int mouseY) {
        Menu open = menu;
        int shown = Math.min(open.items.length, MENU_ROWS);
        int h = shown * MENU_ROW + 4;
        int x0 = Math.max(4, Math.min(width - 4 - open.width, open.x)), x1 = x0 + open.width;
        int y0 = open.y;
        if (y0 + h > height - 4) {
            // No room under its control: over it.
            y0 = Math.max(4, open.anchorTop - 2 - h);
        }
        int y1 = y0 + h;
        menuX0 = x0;
        menuY0 = y0;
        menuX1 = x1;
        menuY1 = y1;
        GL11.glPushMatrix();
        GL11.glTranslatef(0f, 0f, 250f);
        // A click anywhere else closes it.
        hit(0, 0, width, height, () -> menu = null, null);
        Theme.shadow(x0, y0, x1, y1, 4);
        Theme.gradient(x0, y0, x1, y1, 0xFA1A1F27, 0xFA141920);
        Theme.outline(x0, y0, x1, y1, Theme.blend(Theme.BORDER, Theme.ACCENT, 0.35));
        hit(x0, y0, x1, y1, () -> {}, null);
        for (int r = 0; r < shown; r++) {
            int index = r + open.scroll;
            if (index >= open.items.length) {
                break;
            }
            int ry = y0 + 2 + r * MENU_ROW;
            boolean hovered = Theme.inside(mouseX, mouseY, x0 + 1, ry, x1 - 1, ry + MENU_ROW);
            boolean current = index == open.current;
            if (current) {
                Theme.fill(x0 + 1, ry, x1 - 1, ry + MENU_ROW, SELECTED);
                Theme.fill(x0 + 1, ry, x0 + 3, ry + MENU_ROW, Theme.ACCENT);
            } else if (hovered) {
                Theme.fill(x0 + 1, ry, x1 - 1, ry + MENU_ROW, Theme.ROW_HOVER);
            }
            int x = x0 + 7;
            if (open.colors != null && open.colors[index] != 0) {
                Theme.fill(x, ry + 4, x + 6, ry + 10, open.colors[index]);
                x += 10;
            } else if (open.colors != null) {
                x += 10;
            }
            if (open.checked != null && open.checked[index] != null) {
                boolean on = open.checked[index];
                Theme.fill(x, ry + 2, x + 10, ry + 12, on ? Theme.ACCENT_DIM : Theme.CONTROL);
                Theme.outline(x, ry + 2, x + 10, ry + 12, on ? Theme.ACCENT : Theme.BORDER);
                if (on) {
                    Icons.draw(Icons.SMALL_CHECK, x + 2, ry + 4, Theme.TEXT);
                }
                x += 14;
            } else if (open.checked != null) {
                Icons.draw(Icons.SMALL_SORT, x + 2, ry + 4, Theme.TEXT_MUTED);
                x += 14;
            }
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, open.items[index], x1 - 8 - x),
                x,
                ry + 3,
                current || hovered ? Theme.TEXT : Theme.TEXT_MUTED);
            hit(x0 + 1, ry, x1 - 1, ry + MENU_ROW, () -> {
                if (!open.stays) {
                    menu = null;
                }
                open.pick.accept(index);
            }, null);
        }
        if (open.items.length > MENU_ROWS) {
            Theme.scrollbar(
                x1 - 4,
                y0 + 2,
                y1 - 2,
                MENU_ROWS,
                open.items.length,
                open.scroll / (double) (open.items.length - MENU_ROWS),
                true);
        }
        GL11.glPopMatrix();
    }

    /** All the spikes of the scan in a window over the report: a click shows one in its tab. */
    private void drawSpikes(int mouseX, int mouseY) {
        GL11.glPushMatrix();
        GL11.glTranslatef(0f, 0f, 200f);
        Theme.fill(0, 0, width, height, 0x90080A0D);
        hit(0, 0, width, height, () -> spikesOpen = false, null);
        int w = Math.min(560, width - 60);
        int rowHeight = 16;
        int fit = Math.max(3, (height - 120) / rowHeight);
        int shown = Math.min(bySpike.size(), fit);
        int h = 44 + shown * rowHeight + 8;
        int x0 = (width - w) / 2, y0 = (height - h) / 2, x1 = x0 + w, y1 = y0 + h;
        Theme.panel(x0, y0, x1, y1);
        hit(x0, y0, x1, y1, () -> {}, null);
        Icons.draw(Icons.SMALL_BOLT, x0 + 10, y0 + 10, WARN);
        fontRendererObj.drawStringWithShadow(
            I18n.format("lagfinder.overview.all_spikes_title", bySpike.size()),
            x0 + 22,
            y0 + 10,
            Theme.TEXT);
        // Close.
        int cx = x1 - 20, cy = y0 + 7;
        boolean overClose = Theme.inside(mouseX, mouseY, cx, cy, cx + 13, cy + 13);
        Theme.fill(cx, cy, cx + 13, cy + 13, overClose ? Theme.CONTROL_HOVER : Theme.CONTROL);
        Theme.outline(cx, cy, cx + 13, cy + 13, overClose ? Theme.ACCENT : Theme.BORDER);
        Icons.draw(Icons.SMALL_CLEAR, cx + 3, cy + 3, overClose ? Theme.TEXT : Theme.TEXT_MUTED);
        hit(cx, cy, cx + 13, cy + 13, () -> spikesOpen = false, null);

        int hy = y0 + 28;
        int peakRight = x1 - 14, spikesRight = peakRight - 56, whereRight = spikesRight - 50;
        fontRendererObj.drawString(I18n.format("lagfinder.column.name"), x0 + 30, hy, Theme.TEXT_DISABLED);
        String peakTitle = peakTitle();
        fontRendererObj.drawString(
            peakTitle,
            peakRight - fontRendererObj.getStringWidth(peakTitle),
            hy,
            Theme.TEXT_DISABLED);
        String spikesTitle = I18n.format("lagfinder.stat.spikes");
        fontRendererObj.drawString(
            spikesTitle,
            spikesRight - fontRendererObj.getStringWidth(spikesTitle),
            hy,
            Theme.TEXT_DISABLED);
        Theme.fill(x0 + 8, hy + 11, x1 - 8, hy + 12, Theme.BORDER);

        spikesScroll = Math.max(0, Math.min(spikesScroll, bySpike.size() - shown));
        int top = hy + 14;
        for (int r = 0; r < shown; r++) {
            int index = r + spikesScroll;
            ReportEntry entry = bySpike.get(index);
            int y = top + r * rowHeight;
            boolean hovered = Theme.inside(mouseX, mouseY, x0 + 6, y, x1 - 6, y + rowHeight);
            if (hovered) {
                Theme.fill(x0 + 6, y, x1 - 6, y + rowHeight, Theme.ROW_HOVER);
            }
            String rank = String.valueOf(index + 1);
            fontRendererObj.drawString(
                rank,
                x0 + 24 - fontRendererObj.getStringWidth(rank),
                y + 4,
                Theme.TEXT_DISABLED);
            ItemStack stack = Names.icon(entry);
            if (stack != null) {
                Draw.item(stack, x0 + 28, y + 2, 12);
            } else {
                Icons.draw(icon(entry.category), x0 + 30, y + 4, Theme.TEXT_MUTED);
            }
            String peak = peakText(entry);
            fontRendererObj.drawString(peak, peakRight - fontRendererObj.getStringWidth(peak), y + 4, peakColor(entry));
            String spikes = String.valueOf(entry.spikes);
            fontRendererObj.drawString(
                spikes,
                spikesRight - fontRendererObj.getStringWidth(spikes),
                y + 4,
                entry.spikes > 0 ? ORANGE : Theme.TEXT_MUTED);
            String where = entry.level == 3 ? Names.position(entry) : "";
            String dim = dimensionTag(entry);
            if (dim != null) {
                where += " " + dim;
            }
            int whereWidth = fontRendererObj.getStringWidth(where);
            fontRendererObj.drawString(where, whereRight - whereWidth, y + 4, Theme.TEXT_DISABLED);
            String name = Names.of(entry);
            String mod = "  " + modOf(entry) + "  ·  " + Names.category(entry.category, report.mode);
            int room = whereRight - whereWidth - 8 - (x0 + 44);
            String shownName = Theme.ellipsize(fontRendererObj, name, room);
            fontRendererObj.drawString(shownName, x0 + 44, y + 4, Theme.TEXT);
            int after = x0 + 44 + fontRendererObj.getStringWidth(shownName);
            if (after + 20 < whereRight - whereWidth - 8) {
                fontRendererObj.drawString(
                    Theme.ellipsize(fontRendererObj, mod, whereRight - whereWidth - 8 - after),
                    after,
                    y + 4,
                    Theme.TEXT_DISABLED);
            }
            hit(x0 + 6, y, x1 - 6, y + rowHeight, () -> {
                spikesOpen = false;
                reveal(entry);
            }, null);
        }
        if (bySpike.size() > shown) {
            Theme.scrollbar(
                x1 - 6,
                top,
                top + shown * rowHeight,
                shown,
                bySpike.size(),
                spikesScroll / (double) Math.max(1, bySpike.size() - shown),
                true);
        }
        GL11.glPopMatrix();
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
