package LagFinder.client.gui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;

import LagFinder.client.ClientScanner;
import LagFinder.client.KeyHandler;
import LagFinder.client.Names;
import LagFinder.client.ReportStore;
import LagFinder.client.gui.ui.FlatButton;
import LagFinder.client.gui.ui.Icons;
import LagFinder.client.gui.ui.ScaledScreen;
import LagFinder.client.gui.ui.Smooth;
import LagFinder.client.gui.ui.Theme;
import LagFinder.client.gui.ui.WindowHeader;
import LagFinder.core.Report;
import LagFinder.core.ScanMode;

/**
 * The scanner: what to measure (the server's ticks or this client's frames) and for how long, a start button that
 * turns into a progress bar, and the button to the report, which opens once there is one. Older reports are listed
 * under it.
 */
public class GuiLagFinder extends ScaledScreen {

    private static final int WIDTH = 400;
    private static final int ID_START = 0, ID_OPEN = 1, ID_CLOSE = 2, ID_DURATION = 10;
    private static final int[] DURATIONS = { 10, 30, 60, 120, 300, 600 };
    private static final int CARD_HEIGHT = 54, RECENT_ROW = 16, RECENT_SHOWN = 4;

    /** The choices stay while the game runs. */
    private static ScanMode chosenMode = ScanMode.TPS;
    private static int chosenDuration = 30;

    private int left, top, height_;
    private FlatButton startButton, openButton;
    private final List<FlatButton> durationButtons = new ArrayList<>();
    private final Smooth[] cardLight = { new Smooth(0), new Smooth(0) };
    private final Smooth shownProgress = new Smooth(0);
    private final List<ReportStore.Saved> recent = new ArrayList<>();
    private final List<Smooth> recentLight = new ArrayList<>();
    private int cardsY, durationY, statusY, buttonsY, recentY;

    public GuiLagFinder() {
        ClientScanner.INSTANCE.query();
    }

    @Override
    public void initGui() {
        recent.clear();
        recentLight.clear();
        List<ReportStore.Saved> saved = ReportStore.list();
        for (int i = 0; i < saved.size() && i < RECENT_SHOWN; i++) {
            recent.add(saved.get(i));
            recentLight.add(new Smooth(0));
        }
        int content = 12 + 10 + CARD_HEIGHT + 14 + 10 + 18 + 12 + 38 + 10 + 20 + 10;
        int recentHeight = recent.isEmpty() ? 0 : 14 + recent.size() * RECENT_ROW + 4;
        height_ = WindowHeader.HEIGHT + content + recentHeight;
        left = (width - WIDTH) / 2;
        top = Math.max(4, (height - height_) / 2);

        int y = top + WindowHeader.HEIGHT + 12;
        cardsY = y + 10;
        durationY = cardsY + CARD_HEIGHT + 14 + 10;
        statusY = durationY + 18 + 12;
        buttonsY = statusY + 38 + 10;
        recentY = buttonsY + 20 + 12;

        buttonList.clear();
        durationButtons.clear();
        int gap = 4, inner = WIDTH - 24;
        int each = (inner - gap * (DURATIONS.length - 1)) / DURATIONS.length;
        for (int i = 0; i < DURATIONS.length; i++) {
            FlatButton button = new FlatButton(
                ID_DURATION + i,
                left + 12 + i * (each + gap),
                durationY,
                each,
                18,
                durationLabel(DURATIONS[i]));
            durationButtons.add(button);
            buttonList.add(button);
        }
        int startWidth = (inner - 6) * 3 / 5;
        startButton = new FlatButton(ID_START, left + 12, buttonsY, startWidth, 20, "");
        startButton.icon = Icons.PLAY;
        buttonList.add(startButton);
        openButton = new FlatButton(
            ID_OPEN,
            left + 12 + startWidth + 6,
            buttonsY,
            inner - startWidth - 6,
            20,
            I18n.format("lagfinder.gui.open_report"));
        openButton.icon = Icons.LOGS;
        buttonList.add(openButton);
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, left + WIDTH, top));
        refresh();
    }

    private static String durationLabel(int seconds) {
        return seconds < 60 ? seconds + I18n.format("lagfinder.unit.s") : seconds / 60 + I18n.format("lagfinder.unit.m");
    }

    /** Puts the buttons in line with the scan's state. */
    private void refresh() {
        ClientScanner scanner = ClientScanner.INSTANCE;
        boolean active = scanner.isActive();
        for (int i = 0; i < durationButtons.size(); i++) {
            FlatButton button = durationButtons.get(i);
            button.active = DURATIONS[i] == chosenDuration;
            button.enabled = !active;
        }
        if (active) {
            startButton.displayString = I18n.format("lagfinder.gui.cancel");
            startButton.icon = Icons.STOP;
            startButton.danger = true;
            startButton.active = false;
            startButton.enabled = scanner.phase() != ClientScanner.Phase.RECEIVING;
        } else {
            startButton.displayString = I18n.format("lagfinder.gui.start");
            startButton.icon = Icons.PLAY;
            startButton.danger = false;
            startButton.active = canStart();
            startButton.enabled = canStart();
        }
        openButton.enabled = !active && scanner.report() != null;
        openButton.active = openButton.enabled && scanner.hasUnseenReport();
    }

    private boolean canStart() {
        ClientScanner scanner = ClientScanner.INSTANCE;
        if (chosenMode == ScanMode.FPS) {
            return mc.theWorld != null;
        }
        return scanner.serverAllowed() && !scanner.serverBusy();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        ClientScanner scanner = ClientScanner.INSTANCE;
        if (button.id == ID_CLOSE) {
            mc.displayGuiScreen(null);
        } else if (button.id == ID_START) {
            if (scanner.isActive()) {
                scanner.cancel();
            } else if (canStart()) {
                scanner.start(chosenMode, chosenDuration);
                if (chosenMode == ScanMode.FPS && scanner.isActive()) {
                    // Frames are measured while playing: this window would only be in the way.
                    mc.displayGuiScreen(null);
                    return;
                }
            }
        } else if (button.id == ID_OPEN && scanner.report() != null) {
            mc.displayGuiScreen(new GuiReport(scanner.report()));
            return;
        } else if (button.id >= ID_DURATION && button.id < ID_DURATION + DURATIONS.length) {
            chosenDuration = DURATIONS[button.id - ID_DURATION];
        }
        refresh();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (button != 0) {
            return;
        }
        if (!ClientScanner.INSTANCE.isActive()) {
            for (int i = 0; i < 2; i++) {
                if (Theme.inside(mouseX, mouseY, cardX(i), cardsY, cardX(i) + cardWidth(), cardsY + CARD_HEIGHT)) {
                    chosenMode = i == 0 ? ScanMode.TPS : ScanMode.FPS;
                    refresh();
                }
            }
        }
        for (int i = 0; i < recent.size(); i++) {
            int y = recentY + 14 + i * RECENT_ROW;
            if (Theme.inside(mouseX, mouseY, left + 8, y, left + WIDTH - 8, y + RECENT_ROW)) {
                Report report = ReportStore.load(recent.get(i).file);
                if (report != null) {
                    ClientScanner.INSTANCE.setReport(report);
                    mc.displayGuiScreen(new GuiReport(report));
                }
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == mc.gameSettings.keyBindInventory.getKeyCode()
            || keyCode == KeyHandler.OPEN.getKeyCode()) {
            mc.displayGuiScreen(null);
        }
    }

    private int cardWidth() {
        return (WIDTH - 24 - 8) / 2;
    }

    private int cardX(int index) {
        return left + 12 + index * (cardWidth() + 8);
    }

    @Override
    public void updateScreen() {
        refresh();
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        refresh();
        ClientScanner scanner = ClientScanner.INSTANCE;
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, left + WIDTH, top + height_);
        String pill = scanner.serverHasMod() ? I18n.format("lagfinder.gui.server_ok")
            : I18n.format("lagfinder.gui.client_only");
        WindowHeader.draw(
            fontRendererObj,
            left,
            top,
            left + WIDTH,
            left + WIDTH - WindowHeader.CLOSE_ROOM,
            Icons.STATS,
            "LagFinder",
            I18n.format("lagfinder.gui.subtitle"),
            Theme.TEXT_MUTED,
            pill);

        sectionTitle(I18n.format("lagfinder.gui.what"), cardsY - 10);
        drawCard(0, ScanMode.TPS, mouseX, mouseY);
        drawCard(1, ScanMode.FPS, mouseX, mouseY);
        sectionTitle(I18n.format("lagfinder.gui.duration"), durationY - 10);
        drawStatus();
        super.drawScaled(mouseX, mouseY, partialTicks);
        drawRecent(mouseX, mouseY);
    }

    private void sectionTitle(String text, int y) {
        fontRendererObj.drawString(text.toUpperCase(), left + 12, y, Theme.TEXT_MUTED);
    }

    private void drawCard(int index, ScanMode mode, int mouseX, int mouseY) {
        ClientScanner scanner = ClientScanner.INSTANCE;
        int x0 = cardX(index), x1 = x0 + cardWidth(), y0 = cardsY, y1 = cardsY + CARD_HEIGHT;
        boolean chosen = chosenMode == mode;
        boolean usable = mode == ScanMode.FPS || scanner.serverAllowed();
        boolean hovered = !scanner.isActive() && Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
        double lit = cardLight[index].update(hovered ? 1 : 0, 22);
        int background = chosen ? 0xFF1E2C40 : Theme.blend(0xFF1B2028, 0xFF232A34, lit);
        int border = chosen ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT_DIM, lit);
        Theme.fill(x0, y0, x1, y1, background);
        Theme.outline(x0, y0, x1, y1, border);
        if (chosen) {
            Theme.fill(x0, y0, x0 + 2, y1, Theme.ACCENT);
        }
        String[] icon = mode == ScanMode.TPS ? Icons.SERVER : Icons.MONITOR;
        int iconColor = !usable ? Theme.TEXT_DISABLED : chosen ? Theme.ACCENT : Theme.TEXT_MUTED;
        Icons.draw(icon, x0 + 9, y0 + 8, iconColor);
        String title = I18n.format(mode == ScanMode.TPS ? "lagfinder.gui.tps_title" : "lagfinder.gui.fps_title");
        fontRendererObj.drawStringWithShadow(title, x0 + 26, y0 + 8, usable ? Theme.TEXT : Theme.TEXT_DISABLED);
        String description;
        int descriptionColor = Theme.TEXT_MUTED;
        if (mode == ScanMode.TPS && !scanner.serverHasMod()) {
            description = I18n.format("lagfinder.gui.tps_no_mod");
            descriptionColor = 0xFFF2C14E;
        } else if (mode == ScanMode.TPS && !scanner.serverAllowed()) {
            description = I18n.format("lagfinder.gui.tps_not_allowed");
            descriptionColor = 0xFFF2C14E;
        } else {
            description = I18n.format(mode == ScanMode.TPS ? "lagfinder.gui.tps_text" : "lagfinder.gui.fps_text");
        }
        List<String> lines = fontRendererObj.listFormattedStringToWidth(description, x1 - x0 - 16);
        for (int i = 0; i < lines.size() && i < 3; i++) {
            fontRendererObj.drawString(lines.get(i), x0 + 9, y0 + 22 + i * 10, descriptionColor);
        }
    }

    private void drawStatus() {
        ClientScanner scanner = ClientScanner.INSTANCE;
        int x0 = left + 12, x1 = left + WIDTH - 12, y0 = statusY, y1 = statusY + 38;
        Theme.fill(x0, y0, x1, y1, 0xFF12161B);
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);
        String message = scanner.message();
        if (scanner.isActive()) {
            float progress = scanner.progress();
            double shown = shownProgress.update(progress, 10);
            String title;
            String right;
            switch (scanner.phase()) {
                case REQUESTED:
                    title = I18n.format("lagfinder.status.starting");
                    right = "";
                    break;
                case RECEIVING:
                    title = I18n.format("lagfinder.status.receiving");
                    right = Math.round(progress * 100) + "%";
                    break;
                default:
                    title = I18n.format(
                        scanner.mode() == ScanMode.TPS ? "lagfinder.status.running_tps"
                            : "lagfinder.status.running_fps");
                    right = I18n.format("lagfinder.status.left", Names.clock(scanner.secondsLeft()));
            }
            Icons.draw(Icons.SMALL_CLOCK, x0 + 7, y0 + 7, Theme.ACCENT);
            fontRendererObj.drawString(title, x0 + 19, y0 + 7, Theme.TEXT);
            fontRendererObj.drawString(right, x1 - 7 - fontRendererObj.getStringWidth(right), y0 + 7, Theme.TEXT_MUTED);
            int barX0 = x0 + 7, barX1 = x1 - 7, barY = y0 + 22;
            Theme.fill(barX0, barY, barX1, barY + 8, Theme.CONTROL);
            int filled = barX0 + (int) Math.round((barX1 - barX0) * Math.max(0, Math.min(1, shown)));
            Draw.gradientRect(barX0, barY, filled, barY + 8, 0xFF6AB0FF, Theme.ACCENT_DIM);
            // A light running along the bar, so it is seen to be alive.
            long t = System.currentTimeMillis() % 1600;
            int glow = barX0 + (int) ((filled - barX0) * (t / 1600.0));
            if (filled - barX0 > 12) {
                Theme.fill(Math.max(barX0, glow - 6), barY, Math.min(filled, glow + 6), barY + 8, 0x30FFFFFF);
            }
            Theme.outline(barX0, barY, barX1, barY + 8, Theme.BORDER);
            return;
        }
        shownProgress.set(0);
        if (message != null) {
            Icons.draw(Icons.SMALL_WARN, x0 + 7, y0 + 7, 0xFFF2C14E);
            List<String> lines = fontRendererObj.listFormattedStringToWidth(I18n.format(message), x1 - x0 - 30);
            for (int i = 0; i < lines.size() && i < 2; i++) {
                fontRendererObj.drawString(lines.get(i), x0 + 19, y0 + 7 + i * 11, 0xFFF2C14E);
            }
            return;
        }
        Report report = scanner.report();
        if (report != null) {
            Icons.draw(Icons.SMALL_CHECK, x0 + 7, y0 + 7, Theme.SUCCESS);
            fontRendererObj.drawString(
                I18n.format(scanner.hasUnseenReport() ? "lagfinder.status.done" : "lagfinder.status.last"),
                x0 + 19,
                y0 + 7,
                Theme.TEXT);
            fontRendererObj.drawString(summary(report), x0 + 19, y0 + 21, Theme.TEXT_MUTED);
            return;
        }
        Icons.draw(Icons.SMALL_CHECK, x0 + 7, y0 + 7, Theme.TEXT_MUTED);
        fontRendererObj.drawString(I18n.format("lagfinder.status.ready"), x0 + 19, y0 + 7, Theme.TEXT);
        String hint = I18n.format(chosenMode == ScanMode.TPS ? "lagfinder.status.hint_tps" : "lagfinder.status.hint_fps");
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, hint, x1 - x0 - 26),
            x0 + 19,
            y0 + 21,
            Theme.TEXT_MUTED);
    }

    /** One line about a report: its mode, length and rate. */
    static String summary(Report report) {
        String rate = report.mode == ScanMode.TPS ? "TPS" : "FPS";
        return String.format(
            "%s · %s · %s %.1f · min %.1f",
            I18n.format(report.mode == ScanMode.TPS ? "lagfinder.mode.tps" : "lagfinder.mode.fps"),
            Names.clock(report.durationSec),
            rate,
            report.averageRate(),
            report.minimumRate());
    }

    private void drawRecent(int mouseX, int mouseY) {
        if (recent.isEmpty()) {
            return;
        }
        Theme.fill(left + 1, recentY - 6, left + WIDTH - 1, recentY - 5, Theme.BORDER);
        sectionTitle(I18n.format("lagfinder.gui.recent"), recentY);
        SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy  HH:mm:ss");
        for (int i = 0; i < recent.size(); i++) {
            ReportStore.Saved saved = recent.get(i);
            int y = recentY + 14 + i * RECENT_ROW;
            boolean hovered = Theme.inside(mouseX, mouseY, left + 8, y, left + WIDTH - 8, y + RECENT_ROW);
            double lit = recentLight.get(i)
                .update(hovered ? 1 : 0, 22);
            if (lit > 0.01) {
                Theme.fill(left + 8, y, left + WIDTH - 8, y + RECENT_ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
            }
            boolean tps = saved.mode == ScanMode.TPS;
            Icons.draw(tps ? Icons.SMALL_GRID : Icons.SMALL_FRAME, left + 14, y + 4, tps ? Theme.ACCENT : 0xFFB37FEB);
            fontRendererObj.drawString(I18n.format(tps ? "lagfinder.mode.tps" : "lagfinder.mode.fps"), left + 26, y + 4, Theme.TEXT);
            String date = format.format(new Date(saved.time));
            fontRendererObj.drawString(date, left + 110, y + 4, Theme.TEXT_MUTED);
            String open = I18n.format("lagfinder.gui.open");
            fontRendererObj.drawString(
                open,
                left + WIDTH - 14 - fontRendererObj.getStringWidth(open),
                y + 4,
                hovered ? Theme.ACCENT : Theme.TEXT_DISABLED);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
