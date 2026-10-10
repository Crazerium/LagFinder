package lagfinder.client.gui;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;

import lagfinder.client.ClientScanner;
import lagfinder.client.KeyHandler;
import lagfinder.client.Names;
import lagfinder.client.ReportStore;
import lagfinder.client.gui.ui.FlatButton;
import lagfinder.client.gui.ui.FlatTextField;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.ScaledScreen;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.TimeSlider;
import lagfinder.client.gui.ui.WindowHeader;
import lagfinder.core.Report;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;
import lagfinder.core.ScanSide;

/**
 * The scanner: what to scan (the lag of the server's ticks or of this client's frames, the methods a thread spends its
 * time in like Spark, the objects in memory, or the objects being created and who creates them), for how long (a
 * slider, or typed in), where (the server, this client, or both at once), a start button that turns into a progress
 * bar, the button to the report (the two sides' added up when both were scanned), which opens once there is one, and
 * the one to the saved reports.
 */
public class GuiLagFinder extends ScaledScreen {

    private static final int WIDTH = 440;
    private static final int ID_START = 0, ID_OPEN = 1, ID_CLOSE = 2, ID_LOGS = 3;
    /** The buttons picking the side, by {@link ScanSide} ordinal. */
    private static final int ID_SIDE = 10;
    /** The marks on the time slider (seconds). */
    private static final int[] MARKS = { 10, 30, 60, 120, 300, 600 };
    /** Shortest and longest scan that can be typed in (seconds); the server may allow less. */
    private static final int MIN_SECONDS = 5, MAX_SECONDS = 3600;
    private static final int CARD_HEIGHT = 50, CARD_GAP = 6, TIME_BOX = 58;
    private static final int WARN = 0xFFF2C14E;

    /** The cards to pick from: what each one scans. */
    private enum Card {

        TPS(ScanFocus.LAG, Icons.SERVER, Theme.ACCENT, "lagfinder.gui.tps_title", "lagfinder.gui.tps_text"),
        FPS(ScanFocus.LAG, Icons.MONITOR, 0xFFB37FEB, "lagfinder.gui.fps_title", "lagfinder.gui.fps_text"),
        METHODS(ScanFocus.METHODS, Icons.METHODS, 0xFFFF8C42, "lagfinder.gui.methods_title",
            "lagfinder.gui.methods_text"),
        OBJECTS(ScanFocus.OBJECTS, Icons.OBJECTS, 0xFF3FB950, "lagfinder.gui.objects_title",
            "lagfinder.gui.objects_text"),
        ALLOCATIONS(ScanFocus.ALLOCATIONS, Icons.ALLOCATIONS, 0xFF39C5CF, "lagfinder.gui.alloc_title",
            "lagfinder.gui.alloc_text");

        final ScanFocus focus;
        final String[] icon;
        /** The kind's own color, as in the saved logs. */
        final int color;
        final String title, text;

        Card(ScanFocus focus, String[] icon, int color, String title, String text) {
            this.focus = focus;
            this.icon = icon;
            this.color = color;
            this.title = title;
            this.text = text;
        }
    }

    /** The choices stay while the game runs. */
    private static ScanSide chosenSide = ScanSide.SERVER;
    /** Both sides were picked for a deeper scan before a lag card: picked again on going back to one. */
    private static boolean bothKept;
    private static ScanFocus chosenFocus = ScanFocus.LAG;
    private static int chosenSeconds = 30;

    private int left, top, height_;
    private FlatButton startButton, openButton, logsButton;
    private final FlatButton[] sideButtons = new FlatButton[ScanSide.values().length];
    private TimeSlider slider;
    private FlatTextField timeField;
    /** What was typed in the time box can't be read as a time. */
    private boolean timeInvalid;
    private final Smooth[] cardLight = { new Smooth(0), new Smooth(0), new Smooth(0), new Smooth(0), new Smooth(0) };
    private final Smooth shownProgress = new Smooth(0);
    private int savedCount;
    private int cardsY, sideY, durationY, statusY, buttonsY;

    public GuiLagFinder() {
        ClientScanner.INSTANCE.query();
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        savedCount = ReportStore.list()
            .size();
        int content = 12 + 10 + 2 * CARD_HEIGHT + CARD_GAP + 12 + 10 + 18 + 12 + 10 + TimeSlider.HEIGHT + 10 + 38
            + 10 + 20 + 12;
        height_ = WindowHeader.HEIGHT + content;
        left = (width - WIDTH) / 2;
        top = Math.max(4, (height - height_) / 2);

        cardsY = top + WindowHeader.HEIGHT + 12 + 10;
        sideY = cardsY + 2 * CARD_HEIGHT + CARD_GAP + 12 + 10;
        durationY = sideY + 18 + 12 + 10;
        statusY = durationY + TimeSlider.HEIGHT + 10;
        buttonsY = statusY + 38 + 10;

        int inner = WIDTH - 24;
        buttonList.clear();
        String[][] sideIcons = { Icons.SMALL_GRID, Icons.SMALL_FRAME, Icons.SMALL_GLOBE };
        String[] sideLabels = { "lagfinder.gui.side_server", "lagfinder.gui.side_client", "lagfinder.gui.side_both" };
        int sideWidth = (inner - 2 * 6) / 3;
        for (ScanSide side : ScanSide.values()) {
            int i = side.ordinal();
            int x = left + 12 + i * (sideWidth + 6);
            int w = i == sideButtons.length - 1 ? left + WIDTH - 12 - x : sideWidth;
            sideButtons[i] = new FlatButton(ID_SIDE + i, x, sideY, w, 18, I18n.format(sideLabels[i]));
            sideButtons[i].icon = sideIcons[i];
            buttonList.add(sideButtons[i]);
        }
        String[] labels = new String[MARKS.length];
        for (int i = 0; i < MARKS.length; i++) {
            labels[i] = shortLabel(MARKS[i]);
        }
        int sliderRight = left + WIDTH - 12 - TIME_BOX - 14;
        slider = new TimeSlider(left + 16, durationY + 1, sliderRight - left - 16, MARKS, labels, chosenSeconds, s -> {
            chosenSeconds = s;
            timeField.setText(formatTime(s));
            timeInvalid = false;
        });
        String typed = timeField != null && timeField.isFocused() ? timeField.getText() : formatTime(chosenSeconds);
        timeField = new FlatTextField(fontRendererObj, left + WIDTH - 12 - TIME_BOX, durationY - 1, TIME_BOX, 18);
        timeField.setMaxStringLength(12);
        timeField.setText(typed);

        int startWidth = inner * 2 / 5;
        int logsWidth = inner / 4;
        startButton = new FlatButton(ID_START, left + 12, buttonsY, startWidth, 20, "");
        startButton.icon = Icons.PLAY;
        buttonList.add(startButton);
        openButton = new FlatButton(
            ID_OPEN,
            left + 12 + startWidth + 6,
            buttonsY,
            inner - startWidth - logsWidth - 12,
            20,
            I18n.format("lagfinder.gui.open_report"));
        openButton.icon = Icons.STATS;
        buttonList.add(openButton);
        logsButton = new FlatButton(ID_LOGS, left + WIDTH - 12 - logsWidth, buttonsY, logsWidth, 20, "");
        logsButton.icon = Icons.LOGS;
        buttonList.add(logsButton);
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, left + WIDTH, top));
        refresh();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    /** A mark's label: "10s", "1m". */
    private static String shortLabel(int seconds) {
        return seconds < 60 ? seconds + I18n.format("lagfinder.unit.s") : seconds / 60 + I18n.format("lagfinder.unit.m");
    }

    /** A length as written in the time box: "45s", "2m", "1m 30s". */
    static String formatTime(int seconds) {
        if (seconds < 60 || seconds % 60 == 0) {
            return shortLabel(seconds);
        }
        return seconds / 60 + I18n.format("lagfinder.unit.m") + " " + seconds % 60 + I18n.format("lagfinder.unit.s");
    }

    private static final Pattern CLOCK = Pattern.compile("(\\d{1,3}):(\\d{1,2})");
    private static final Pattern PART = Pattern.compile("(\\d{1,4})\\s*([^\\d\\s]*)");

    /**
     * Reads a typed length: "90" or "90s" (seconds), "2m", "1m 30s", "1:30", "1h"; in English or Russian letters.
     * -1 when it can't be read.
     */
    static int parseTime(String text) {
        String typed = text.trim()
            .toLowerCase(Locale.ROOT)
            .replace(',', '.');
        if (typed.isEmpty()) {
            return -1;
        }
        Matcher clock = CLOCK.matcher(typed);
        if (clock.matches()) {
            return Integer.parseInt(clock.group(1)) * 60 + Integer.parseInt(clock.group(2));
        }
        Matcher part = PART.matcher(typed);
        int total = 0, at = 0;
        while (part.find()) {
            if (!typed.substring(at, part.start())
                .trim()
                .isEmpty()) {
                return -1;
            }
            at = part.end();
            int number = Integer.parseInt(part.group(1));
            String unit = part.group(2);
            if (unit.isEmpty() || unit.startsWith("s") || unit.startsWith("с")) {
                total += number;
            } else if (unit.startsWith("m") || unit.startsWith("м")) {
                total += number * 60;
            } else if (unit.startsWith("h") || unit.startsWith("ч")) {
                total += number * 3600;
            } else {
                return -1;
            }
        }
        if (at == 0 || !typed.substring(at)
            .trim()
            .isEmpty()) {
            return -1;
        }
        return total;
    }

    /** Takes what is typed in the time box, when it is a length that can be scanned. */
    private void readTimeField() {
        int seconds = parseTime(timeField.getText());
        timeInvalid = seconds < MIN_SECONDS || seconds > MAX_SECONDS;
        if (!timeInvalid) {
            chosenSeconds = seconds;
            slider.setValue(seconds);
        }
    }

    /** Leaves the time box: it shows the length chosen, written the usual way. */
    private void leaveTimeField() {
        timeField.setFocused(false);
        timeField.setText(formatTime(chosenSeconds));
        timeInvalid = false;
    }

    private static Card chosenCard() {
        switch (chosenFocus) {
            case METHODS:
                return Card.METHODS;
            case OBJECTS:
                return Card.OBJECTS;
            case ALLOCATIONS:
                return Card.ALLOCATIONS;
            default:
                return chosenSide == ScanSide.CLIENT ? Card.FPS : Card.TPS;
        }
    }

    private static void choose(Card card) {
        chosenFocus = card.focus;
        if (card == Card.TPS || card == Card.FPS) {
            // A lag card scans one side only.
            bothKept |= chosenSide == ScanSide.BOTH;
            chosenSide = card == Card.TPS ? ScanSide.SERVER : ScanSide.CLIENT;
            return;
        }
        if (bothKept) {
            bothKept = false;
            chosenSide = ScanSide.BOTH;
        }
        if (chosenSide.server() && !ClientScanner.INSTANCE.serverAllowed()) {
            // Only this client can be scanned here.
            chosenSide = ScanSide.CLIENT;
        }
    }

    /** Puts the buttons in line with the scan's state. */
    private void refresh() {
        ClientScanner scanner = ClientScanner.INSTANCE;
        boolean active = scanner.isActive();
        for (FlatButton button : sideButtons) {
            button.active = button.id - ID_SIDE == chosenSide.ordinal();
            // Ticks and frames are scanned apart: both at once only for methods, objects and allocations.
            boolean both = button.id - ID_SIDE == ScanSide.BOTH.ordinal();
            button.enabled = !active && !(both && chosenFocus == ScanFocus.LAG);
        }
        slider.enabled = !active;
        timeField.setEnabled(!active);
        if (active && timeField.isFocused()) {
            leaveTimeField();
        }
        if (active) {
            startButton.displayString = I18n.format("lagfinder.gui.cancel");
            startButton.icon = Icons.STOP;
            startButton.danger = true;
            startButton.active = false;
            startButton.enabled = scanner.phase() != ClientScanner.Phase.RECEIVING;
        } else {
            startButton.displayString = I18n.format("lagfinder.gui.start") + "  \u00b7  " + formatTime(chosenSeconds);
            startButton.icon = Icons.PLAY;
            startButton.danger = false;
            startButton.active = canStart();
            startButton.enabled = canStart();
        }
        openButton.enabled = !active && scanner.report() != null;
        openButton.active = openButton.enabled && scanner.hasUnseenReport();
        logsButton.displayString = I18n.format("lagfinder.gui.logs") + (savedCount > 0 ? " (" + savedCount + ")" : "");
    }

    private boolean canStart() {
        ClientScanner scanner = ClientScanner.INSTANCE;
        if (timeInvalid) {
            return false;
        }
        if (chosenSide.client() && mc.theWorld == null) {
            return false;
        }
        return !chosenSide.server() || scanner.serverAllowed() && !scanner.serverBusy();
    }

    /** Opens the report in the window that suits what its scan was for. */
    static void open(Minecraft mc, Report report) {
        ClientScanner.INSTANCE.markSeen();
        if (report.focus == ScanFocus.METHODS && report.profile != null) {
            mc.displayGuiScreen(new GuiMethods(report));
        } else if (report.focus == ScanFocus.OBJECTS && report.memory != null) {
            mc.displayGuiScreen(new GuiMemory(report));
        } else if (report.focus == ScanFocus.ALLOCATIONS && report.allocations != null) {
            mc.displayGuiScreen(new GuiAllocations(report));
        } else {
            mc.displayGuiScreen(new GuiReport(report));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        ClientScanner scanner = ClientScanner.INSTANCE;
        if (timeField.isFocused()) {
            leaveTimeField();
        }
        if (button.id == ID_CLOSE) {
            mc.displayGuiScreen(null);
        } else if (button.id == ID_START) {
            if (scanner.isActive()) {
                scanner.cancel();
            } else if (canStart()) {
                scanner.start(chosenSide, chosenFocus, chosenSeconds);
                if (chosenSide.client() && scanner.isActive()) {
                    // Frames are measured while playing: this window would only be in the way.
                    mc.displayGuiScreen(null);
                    return;
                }
            }
        } else if (button.id == ID_OPEN && scanner.report() != null) {
            open(mc, scanner.report());
            return;
        } else if (button.id >= ID_SIDE && button.id < ID_SIDE + sideButtons.length) {
            // The lag cards follow the side: the server's ticks or this client's frames.
            chosenSide = ScanSide.values()[button.id - ID_SIDE];
            bothKept = false;
        } else if (button.id == ID_LOGS) {
            mc.displayGuiScreen(new GuiLogs());
            return;
        }
        refresh();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        boolean wasFocused = timeField.isFocused();
        timeField.mouseClicked(mouseX, mouseY, button);
        if (wasFocused && !timeField.isFocused()) {
            leaveTimeField();
        } else if (button == 1 && timeField.isMouseOver(mouseX, mouseY)) {
            // Cleared by a right click: something new is being typed.
            timeInvalid = true;
        }
        super.mouseClicked(mouseX, mouseY, button);
        if (button != 0 || ClientScanner.INSTANCE.isActive()) {
            return;
        }
        if (slider.mousePressed(fontRendererObj, mouseX, mouseY)) {
            return;
        }
        for (Card card : Card.values()) {
            int x0 = cardX(card), y0 = cardY(card);
            if (usable(card) && Theme.inside(mouseX, mouseY, x0, y0, x0 + cardWidth(card), y0 + CARD_HEIGHT)) {
                choose(card);
                refresh();
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (timeField.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER
                || keyCode == Keyboard.KEY_ESCAPE) {
                if (keyCode != Keyboard.KEY_ESCAPE) {
                    readTimeField();
                }
                leaveTimeField();
            } else if (timeField.textboxKeyTyped(typedChar, keyCode)) {
                readTimeField();
            }
            refresh();
            return;
        }
        if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) && startButton.enabled) {
            // Enter does what the big button does: start, or cancel the scan running.
            actionPerformed(startButton);
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == mc.gameSettings.keyBindInventory.getKeyCode()
            || keyCode == KeyHandler.OPEN.getKeyCode()) {
            mc.displayGuiScreen(null);
        }
    }

    /** The lag scans side by side on the first row; the three deeper looks on the second. */
    private static int row(Card card) {
        return card.ordinal() < 2 ? 0 : 1;
    }

    private static int column(Card card) {
        return card.ordinal() < 2 ? card.ordinal() : card.ordinal() - 2;
    }

    private int cardWidth(Card card) {
        int columns = row(card) == 0 ? 2 : 3;
        return (WIDTH - 24 - (columns - 1) * CARD_GAP) / columns;
    }

    private int cardX(Card card) {
        return left + 12 + column(card) * (cardWidth(card) + CARD_GAP);
    }

    private int cardY(Card card) {
        return cardsY + row(card) * (CARD_HEIGHT + CARD_GAP);
    }

    @Override
    public void updateScreen() {
        timeField.updateCursorCounter();
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
        for (Card card : Card.values()) {
            drawCard(card, mouseX, mouseY);
        }
        sectionTitle(I18n.format("lagfinder.gui.side"), sideY - 10);
        sectionTitle(I18n.format("lagfinder.gui.duration"), durationY - 12);
        slider.draw(fontRendererObj, mouseX, mouseY);
        timeField.drawTextBox();
        if (timeInvalid) {
            Theme.outline(
                timeField.boxX,
                timeField.boxY,
                timeField.boxX + timeField.boxWidth,
                timeField.boxY + timeField.boxHeight,
                Theme.DANGER);
        }
        drawStatus();
        super.drawScaled(mouseX, mouseY, partialTicks);
        drawTooltips(mouseX, mouseY);
    }

    /** What a button will do, when that isn't plain from it. */
    private void drawTooltips(int mouseX, int mouseY) {
        ClientScanner scanner = ClientScanner.INSTANCE;
        String text = null;
        if (openButton.isMouseOver(mouseX, mouseY) && scanner.report() != null) {
            text = I18n.format(scanner.hasUnseenReport() ? "lagfinder.tip.open_new" : "lagfinder.tip.open_last") + "\n"
                + summary(scanner.report());
        } else if (logsButton.isMouseOver(mouseX, mouseY)) {
            text = I18n.format("lagfinder.tip.logs", savedCount);
        } else {
            FlatButton both = sideButtons[ScanSide.BOTH.ordinal()];
            if (both.isMouseOver(mouseX, mouseY) && chosenFocus == ScanFocus.LAG) {
                text = I18n.format("lagfinder.tip.both_lag");
            }
        }
        if (text == null) {
            return;
        }
        List<String> lines = new java.util.ArrayList<>();
        List<Integer> colors = new java.util.ArrayList<>();
        String[] parts = text.split("\\\\n|\n");
        for (int i = 0; i < parts.length; i++) {
            for (Object line : fontRendererObj.listFormattedStringToWidth(parts[i], 240)) {
                lines.add((String) line);
                colors.add(i == 0 ? Theme.TEXT : Theme.TEXT_MUTED);
            }
        }
        Draw.tooltip(fontRendererObj, lines, colors, mouseX, mouseY, width, height);
    }

    private void sectionTitle(String text, int y) {
        fontRendererObj.drawString(text.toUpperCase(), left + 12, y, Theme.TEXT_MUTED);
    }

    /** Why the server's ticks can't be scanned, or null when they can. */
    private static String serverProblem() {
        ClientScanner scanner = ClientScanner.INSTANCE;
        if (!scanner.serverHasMod()) {
            return "lagfinder.gui.tps_no_mod";
        }
        if (!scanner.serverAllowed()) {
            return "lagfinder.gui.tps_not_allowed";
        }
        return null;
    }

    /** Whether the card can be picked here: the server's ticks only on a server that lets the player scan them. */
    private static boolean usable(Card card) {
        return card != Card.TPS || ClientScanner.INSTANCE.serverAllowed();
    }

    private void drawCard(Card card, int mouseX, int mouseY) {
        ClientScanner scanner = ClientScanner.INSTANCE;
        int x0 = cardX(card), x1 = x0 + cardWidth(card), y0 = cardY(card), y1 = y0 + CARD_HEIGHT;
        boolean chosen = chosenCard() == card;
        boolean usable = usable(card);
        boolean hovered = usable && !scanner.isActive() && Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
        double lit = cardLight[card.ordinal()].update(hovered ? 1 : 0, 22);
        // In the kind's own color: lit when picked, a hint of it under the mouse.
        int background = chosen ? Theme.blend(0xFF1B2028, card.color | 0xFF000000, 0.16)
            : Theme.blend(0xFF1B2028, 0xFF232A34, lit);
        int border = chosen ? card.color : Theme.blend(Theme.BORDER, Theme.blend(Theme.BORDER, card.color, 0.6), lit);
        Theme.fill(x0, y0, x1, y1, background);
        Theme.outline(x0, y0, x1, y1, border);
        if (chosen) {
            Theme.fill(x0, y0, x0 + 2, y1, card.color);
            Icons.draw(Icons.SMALL_CHECK, x1 - 12, y0 + 6, card.color);
        }
        int iconColor = !usable ? Theme.TEXT_DISABLED
            : chosen ? card.color : Theme.blend(Theme.TEXT_MUTED, card.color, 0.35 + 0.65 * lit);
        Icons.draw(card.icon, x0 + 9, y0 + 7, iconColor);
        fontRendererObj.drawStringWithShadow(
            Theme.ellipsize(fontRendererObj, I18n.format(card.title), x1 - x0 - 44),
            x0 + 26,
            y0 + 8,
            usable ? Theme.TEXT : Theme.TEXT_DISABLED);
        String description = I18n.format(card.text);
        int descriptionColor = Theme.TEXT_MUTED;
        String problem = serverProblem();
        if (card == Card.TPS && problem != null) {
            description = I18n.format(problem);
            descriptionColor = WARN;
        }
        List<String> lines = fontRendererObj.listFormattedStringToWidth(description, x1 - x0 - 16);
        for (int i = 0; i < lines.size() && i < 3; i++) {
            fontRendererObj.drawString(lines.get(i), x0 + 9, y0 + 20 + i * 10, descriptionColor);
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
                    title = I18n.format(runningKey(scanner.side(), scanner.focus()));
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
            warning(I18n.format(message), x0, y0, x1);
            return;
        }
        if (timeInvalid) {
            warning(I18n.format("lagfinder.status.bad_time", formatTime(MIN_SECONDS), formatTime(MAX_SECONDS)), x0, y0, x1);
            return;
        }
        if (chosenSide.server() && serverProblem() != null) {
            warning(I18n.format(serverProblem()), x0, y0, x1);
            return;
        }
        Report report = scanner.report();
        if (report != null && scanner.hasUnseenReport()) {
            String line = summary(report);
            Icons.draw(Icons.SMALL_CHECK, x0 + 7, y0 + 7, Theme.SUCCESS);
            fontRendererObj.drawString(I18n.format("lagfinder.status.done"), x0 + 19, y0 + 7, Theme.TEXT);
            fontRendererObj
                .drawString(Theme.ellipsize(fontRendererObj, line, x1 - x0 - 26), x0 + 19, y0 + 21, Theme.TEXT_MUTED);
            return;
        }
        Icons.draw(Icons.SMALL_CHECK, x0 + 7, y0 + 7, Theme.TEXT_MUTED);
        String ready = I18n.format("lagfinder.status.ready") + " - " + kindName(chosenSide, chosenFocus) + ", "
            + formatTime(chosenSeconds);
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, ready, x1 - x0 - 26), x0 + 19, y0 + 7, Theme.TEXT);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, I18n.format(hintKey()), x1 - x0 - 26),
            x0 + 19,
            y0 + 21,
            Theme.TEXT_MUTED);
    }

    private void warning(String text, int x0, int y0, int x1) {
        Icons.draw(Icons.SMALL_WARN, x0 + 7, y0 + 7, WARN);
        List<String> lines = fontRendererObj.listFormattedStringToWidth(text, x1 - x0 - 30);
        for (int i = 0; i < lines.size() && i < 2; i++) {
            fontRendererObj.drawString(lines.get(i), x0 + 19, y0 + 7 + i * 11, WARN);
        }
    }

    private static String hintKey() {
        switch (chosenFocus) {
            case METHODS:
                return "lagfinder.status.hint_methods";
            case OBJECTS:
                return "lagfinder.status.hint_objects";
            case ALLOCATIONS:
                return "lagfinder.status.hint_alloc";
            default:
                return chosenSide == ScanSide.CLIENT ? "lagfinder.status.hint_fps" : "lagfinder.status.hint_tps";
        }
    }

    private static String runningKey(ScanSide side, ScanFocus focus) {
        switch (focus) {
            case METHODS:
                return "lagfinder.status.running_methods";
            case OBJECTS:
                return "lagfinder.status.running_objects";
            case ALLOCATIONS:
                return "lagfinder.status.running_alloc";
            default:
                return side == ScanSide.CLIENT ? "lagfinder.status.running_fps" : "lagfinder.status.running_tps";
        }
    }

    /** What a scan was: "Tick scan", "Methods - server", "Objects - client", "Allocations - server". */
    public static String kindName(ScanMode mode, ScanFocus focus) {
        return kindName(ScanSide.of(mode), focus);
    }

    /** What a scan is: "Tick scan", "Frame scan", "Methods - server + client", "Objects - client". */
    public static String kindName(ScanSide where, ScanFocus focus) {
        if (focus == ScanFocus.LAG) {
            // Never both: ticks and frames are scanned apart.
            return I18n.format(where == ScanSide.CLIENT ? "lagfinder.mode.fps" : "lagfinder.mode.tps");
        }
        String side = I18n.format(
            where == ScanSide.SERVER ? "lagfinder.side.server"
                : where == ScanSide.CLIENT ? "lagfinder.side.client" : "lagfinder.side.both");
        String kind = focus == ScanFocus.METHODS ? "lagfinder.mode.methods"
            : focus == ScanFocus.ALLOCATIONS ? "lagfinder.mode.alloc" : "lagfinder.mode.objects";
        return I18n.format(kind) + " - " + side;
    }

    /** One line about a report: what it was, its length and rate. */
    static String summary(Report report) {
        String rate = report.mode == ScanMode.TPS ? "TPS" : "FPS";
        return String.format(
            "%s · %s · %s %.1f · min %.1f",
            kindName(report.side(), report.focus),
            Names.clock(report.durationSec),
            rate,
            report.averageRate(),
            report.minimumRate());
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
