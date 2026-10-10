package lagfinder.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import lagfinder.client.ClientScanner;
import lagfinder.client.Names;
import lagfinder.client.ReportStore;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.ScaledScreen;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.core.Report;

/**
 * The frame all of the mod's windows share, as in Wayfarer's Map's settings: a sidebar on the left with the mod's
 * name, a tab for each page (a new scan; the report's lag, methods, memory and allocations; the saved logs) with a
 * mark sliding to the open one, and at its foot how the scan is going; the page itself on the right. A tab goes
 * straight to its page, so a report's views are a click apart; the ones the report has nothing for are dimmed.
 */
public abstract class LagFinderScreen extends ScaledScreen {

    /** Width of the sidebar, its line on the right included. */
    public static final int SIDEBAR = 120;
    private static final int TAB_HEIGHT = 20, TAB_GAP = 2;
    /** Height of the card at the foot of the sidebar, telling how the scan is going. */
    private static final int STATUS_HEIGHT = 46;

    /** The pages of the sidebar. */
    public enum Page {

        SCAN("lagfinder.nav.scan", Icons.CLOCK, Theme.ACCENT),
        REPORT("lagfinder.nav.report", Icons.STATS, 0xFFF2C14E),
        METHODS("lagfinder.nav.methods", Icons.METHODS, 0xFFFF8C42),
        MEMORY("lagfinder.nav.memory", Icons.OBJECTS, 0xFF3FB950),
        ALLOCATIONS("lagfinder.nav.alloc", Icons.ALLOCATIONS, 0xFF39C5CF),
        LOGS("lagfinder.nav.logs", Icons.LOGS, 0xFFAAB4C3);

        final String key;
        final String[] icon;
        public final int color;

        Page(String key, String[] icon, int color) {
            this.key = key;
            this.icon = icon;
            this.color = color;
        }
    }

    /** Where the mark beside the open tab is, kept from page to page so it slides to the new one. */
    private static final Smooth MARK = new Smooth(-1);
    /** Saved reports, counted when a page opens (for the logs' tab). */
    private static int savedCount = -1;

    /** The whole frame, and where the page in it starts (right of the sidebar). */
    protected int shellLeft, shellTop, shellRight, shellBottom, pageLeft;

    /** Opened from another page: it doesn't slide in, only the sidebar's mark moves. */
    private final boolean fromPage;
    private final Smooth[] tabLight = new Smooth[Page.values().length];
    private final Smooth statusLight = new Smooth(0);

    protected LagFinderScreen() {
        fromPage = Minecraft.getMinecraft().currentScreen instanceof LagFinderScreen;
        for (int i = 0; i < tabLight.length; i++) {
            tabLight[i] = new Smooth(0);
        }
        if (!fromPage) {
            MARK.set(-1);
        }
    }

    /** The page this screen is. */
    protected abstract Page page();

    /** The report the report's tabs go to: the one shown, or the last one made. */
    protected Report shownReport() {
        return ClientScanner.INSTANCE.report();
    }

    /** Lays out the frame over the whole window; call first in {@link #initGui}. */
    protected void layoutShell() {
        shellLeft = 6;
        shellTop = 4;
        shellRight = width - 6;
        shellBottom = height - 4;
        pageLeft = shellLeft + SIDEBAR;
        savedCount = ReportStore.list()
            .size();
    }

    /** Called when the saved reports changed (deleted, saved), so the logs' tab counts right. */
    protected static void savedChanged() {
        savedCount = ReportStore.list()
            .size();
    }

    @Override
    protected boolean slidesIn() {
        return !fromPage;
    }

    @Override
    protected boolean showsScreenBehind() {
        return false;
    }

    // ---------------------------------------------------------------- Tabs

    /** Top of the page's tab: the new scan, then the report's title and its four views, then the logs. */
    private int tabY(Page page) {
        int y = shellTop + 34;
        if (page == Page.SCAN) {
            return y;
        }
        y += TAB_HEIGHT + TAB_GAP + 26;
        if (page == Page.LOGS) {
            return y + 4 * (TAB_HEIGHT + TAB_GAP) + 10;
        }
        return y + (page.ordinal() - Page.REPORT.ordinal()) * (TAB_HEIGHT + TAB_GAP);
    }

    /** Whether the page has something to show: a report's views, only what the report has. */
    protected boolean available(Page page) {
        Report report = shownReport();
        switch (page) {
            case REPORT:
                return report != null && !report.categories.isEmpty();
            case METHODS:
                return report != null && report.profile != null;
            case MEMORY:
                return report != null && report.memory != null;
            case ALLOCATIONS:
                return report != null && report.allocations != null;
            default:
                return true;
        }
    }

    /** Opens the page (for the report shown); nothing when it is the open one or has nothing to show. */
    protected void openPage(Page page) {
        if (page == page() || !available(page)) {
            return;
        }
        Report report = shownReport();
        GuiScreen screen;
        switch (page) {
            case SCAN:
                screen = new GuiLagFinder();
                break;
            case REPORT:
                screen = new GuiReport(report);
                break;
            case METHODS:
                screen = new GuiMethods(report);
                break;
            case MEMORY:
                screen = new GuiMemory(report);
                break;
            case ALLOCATIONS:
                screen = new GuiAllocations(report);
                break;
            default:
                screen = new GuiLogs();
        }
        ClientScanner.INSTANCE.markSeen();
        mc.displayGuiScreen(screen);
    }

    /** A click on a tab, or on the scan's card at the foot of the sidebar: whether it was one. */
    private boolean clickShell(int mouseX, int mouseY) {
        if (mouseX < shellLeft || mouseX >= pageLeft) {
            return false;
        }
        for (Page page : Page.values()) {
            int y = tabY(page);
            if (Theme.inside(mouseX, mouseY, shellLeft + 6, y, pageLeft - 7, y + TAB_HEIGHT)) {
                openPage(page);
                return true;
            }
        }
        int statusTop = shellBottom - 8 - STATUS_HEIGHT;
        if (Theme.inside(mouseX, mouseY, shellLeft + 6, statusTop, pageLeft - 7, shellBottom - 8)) {
            openPage(Page.SCAN);
            return true;
        }
        return false;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0 && clickShell(mouseX, mouseY)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    // ---------------------------------------------------------------- Drawing

    /** Draws the frame and the sidebar; the page is drawn over the rest after it. */
    protected void drawShell(int mouseX, int mouseY) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(shellLeft, shellTop, shellRight, shellBottom);
        Theme.fill(shellLeft + 1, shellTop + 1, pageLeft - 1, shellBottom - 1, Theme.PANEL_ALT);
        Theme.fill(pageLeft - 1, shellTop + 1, pageLeft, shellBottom - 1, Theme.BORDER);

        // The mod's name, with its mark.
        Icons.draw(Icons.LOGO, shellLeft + 9, shellTop + 9, Theme.ACCENT);
        fontRendererObj.drawStringWithShadow("LagFinder", shellLeft + 28, shellTop + 10, Theme.TEXT);
        Theme.fill(shellLeft + 8, shellTop + 27, pageLeft - 8, shellTop + 28, Theme.BORDER);

        int slot = 0;
        for (Page page : Page.values()) {
            slot = Math.max(slot, Icons.width(page.icon));
        }
        Page open = page();
        float markTarget = tabY(open);
        if (MARK.get() < 0) {
            MARK.set(markTarget);
        }
        int markY = (int) Math.round(MARK.update(markTarget, 16));

        // The report the report's tabs are of: its kind and when it was made.
        Report report = shownReport();
        int titleY = tabY(Page.REPORT) - 24;
        fontRendererObj.drawString(I18n.format("lagfinder.nav.this_report"), shellLeft + 10, titleY, Theme.TEXT_MUTED);
        String about = report == null ? I18n.format("lagfinder.nav.no_report")
            : GuiLagFinder.kindName(report.side(), report.focus) + " · "
                + new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(report.startedAt));
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, about, pageLeft - shellLeft - 20),
            shellLeft + 10,
            titleY + 11,
            Theme.TEXT_DISABLED);
        int logsY = tabY(Page.LOGS) - 6;
        Theme.fill(shellLeft + 8, logsY, pageLeft - 8, logsY + 1, Theme.BORDER);

        String tip = null;
        for (Page page : Page.values()) {
            int x0 = shellLeft + 6, x1 = pageLeft - 7, y0 = tabY(page), y1 = y0 + TAB_HEIGHT;
            boolean usable = available(page);
            boolean isOpen = page == open;
            boolean hovered = usable && !isOpen && Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
            double lit = tabLight[page.ordinal()].update(hovered ? 1 : 0, 22);
            if (isOpen) {
                Theme.fill(x0, y0, x1, y1, Theme.blend(page.color, Theme.PANEL_ALT | 0xFF000000, 0.82));
                Theme.outline(x0, y0, x1, y1, Theme.blend(page.color, Theme.BORDER, 0.55));
            } else if (lit > 0.01) {
                Theme.fill(x0, y0, x1, y1, Theme.blend(0x00FFFFFF, 0x14FFFFFF, lit));
            }
            int iconWidth = Icons.width(page.icon);
            int iconColor = !usable ? Theme.TEXT_DISABLED
                : Theme.iconShade(page.color, hovered || isOpen, false, !isOpen);
            Icons.draw(page.icon, x0 + 7 + (slot - iconWidth) / 2, y0 + (TAB_HEIGHT - page.icon.length) / 2, iconColor);
            String name = I18n.format(page.key);
            String count = page == Page.LOGS && savedCount > 0 ? String.valueOf(savedCount) : "";
            int countWidth = count.isEmpty() ? 0 : fontRendererObj.getStringWidth(count) + 6;
            int textX = x0 + 7 + slot + 6;
            int textColor = !usable ? Theme.TEXT_DISABLED
                : isOpen ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, name, x1 - 6 - countWidth - textX),
                textX,
                y0 + 6,
                textColor);
            if (!count.isEmpty()) {
                int cx1 = x1 - 5, cx0 = cx1 - countWidth + 2;
                Theme.fill(cx0, y0 + 5, cx1, y0 + 15, Theme.CONTROL);
                fontRendererObj.drawString(count, cx0 + 2, y0 + 6, Theme.TEXT_MUTED);
            }
            if (!usable && Theme.inside(mouseX, mouseY, x0, y0, x1, y1)) {
                tip = I18n.format(report == null ? "lagfinder.nav.need_report" : "lagfinder.nav.not_in_report");
            }
        }
        // The mark beside the open tab, sliding to it.
        Theme.fill(shellLeft + 2, markY + 4, shellLeft + 4, markY + TAB_HEIGHT - 4, open.color);

        drawStatus(mouseX, mouseY);
        if (tip != null) {
            List<String> lines = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (Object line : fontRendererObj.listFormattedStringToWidth(tip, 160)) {
                lines.add((String) line);
                colors.add(Theme.TEXT_MUTED);
            }
            pendingTooltip = lines;
            pendingColors = colors;
        } else {
            pendingTooltip = null;
        }
    }

    private List<String> pendingTooltip;
    private List<Integer> pendingColors;

    /** Draws the sidebar's tooltip, if the mouse is on a dimmed tab; call last, over the page. */
    protected void drawShellTooltip(int mouseX, int mouseY) {
        if (pendingTooltip != null) {
            Draw.tooltip(fontRendererObj, pendingTooltip, pendingColors, mouseX, mouseY, width, height);
        }
    }

    /** The card at the foot of the sidebar: the scan running and how far it is, or whether the server can scan. */
    private void drawStatus(int mouseX, int mouseY) {
        ClientScanner scanner = ClientScanner.INSTANCE;
        int x0 = shellLeft + 6, x1 = pageLeft - 7, y1 = shellBottom - 8, y0 = y1 - STATUS_HEIGHT;
        boolean hovered = page() != Page.SCAN && Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
        double lit = statusLight.update(hovered ? 1 : 0, 22);
        Theme.fill(x0, y0, x1, y1, Theme.blend(0xFF12161B, 0xFF1A2029, lit));
        Theme.outline(x0, y0, x1, y1, Theme.blend(Theme.BORDER, Theme.ACCENT_DIM, lit));
        if (scanner.isActive()) {
            String kind = GuiLagFinder.kindName(scanner.side(), scanner.focus());
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, kind, x1 - x0 - 12),
                x0 + 6,
                y0 + 6,
                Theme.TEXT);
            String state = scanner.phase() == ClientScanner.Phase.RUNNING
                ? I18n.format("lagfinder.status.left", Names.clock(scanner.secondsLeft()))
                : I18n.format(
                    scanner.phase() == ClientScanner.Phase.REQUESTED ? "lagfinder.hud.starting"
                        : "lagfinder.hud.receiving");
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, state, x1 - x0 - 12),
                x0 + 6,
                y0 + 18,
                Theme.TEXT_MUTED);
            int barX0 = x0 + 6, barX1 = x1 - 6, barY = y1 - 11;
            Theme.fill(barX0, barY, barX1, barY + 4, Theme.CONTROL);
            int filled = barX0 + Math.round((barX1 - barX0) * Math.max(0, Math.min(1, scanner.progress())));
            Draw.gradientRect(barX0, barY, filled, barY + 4, 0xFF6AB0FF, Theme.ACCENT_DIM);
            return;
        }
        boolean server = scanner.serverAllowed();
        Theme.disc(x0 + 9.5, y0 + 10.5, 2.5, server ? Theme.SUCCESS : scanner.serverHasMod() ? 0xFFF2C14E : 0xFF8A93A0);
        String where = I18n.format(
            server ? "lagfinder.nav.server_ok"
                : scanner.serverHasMod() ? "lagfinder.nav.server_no_rights" : "lagfinder.gui.client_only");
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, where, x1 - x0 - 22), x0 + 16, y0 + 7, Theme.TEXT);
        String hint = I18n.format(
            scanner.hasUnseenReport() ? "lagfinder.nav.report_ready"
                : page() == Page.SCAN ? "lagfinder.nav.idle" : "lagfinder.nav.new_scan");
        List<String> lines = fontRendererObj.listFormattedStringToWidth(hint, x1 - x0 - 12);
        for (int i = 0; i < lines.size() && i < 2; i++) {
            fontRendererObj.drawString(
                lines.get(i),
                x0 + 6,
                y0 + 20 + i * 10,
                scanner.hasUnseenReport() ? Theme.SUCCESS : Theme.TEXT_MUTED);
        }
    }
}
