package lagfinder.client.gui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.TimeSlider;
import lagfinder.client.gui.ui.WindowHeader;
import lagfinder.core.Report;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;
import lagfinder.core.ScanSide;

/**
 * The scanner, the first page of the mod: on the left what to scan (the lag of the server's ticks or of this client's
 * frames, the methods a thread spends its time in like Spark, the objects in memory, or the objects being created and
 * who creates them), where (the server, this client, or both at once), for how long (a slider, or typed in), how the
 * scan goes and the button that starts it; on the right the last report, a click from opening, and the reports saved
 * lately.
 */
public class GuiLagFinder extends LagFinderScreen {

    private static final int ID_START = 0, ID_OPEN = 1, ID_CLOSE = 2, ID_LOGS = 3;
    /** The buttons picking the side, by {@link ScanSide} ordinal. */
    private static final int ID_SIDE = 10;
    /** The marks on the time slider (seconds). */
    private static final int[] MARKS = { 10, 30, 60, 120, 300, 600 };
    /** Shortest and longest scan that can be typed in (seconds); the server may allow less. */
    private static final int MIN_SECONDS = 5, MAX_SECONDS = 3600;
    private static final int CARD_HEIGHT = 54, CARD_GAP = 6, TIME_BOX = 58;
    private static final int STATUS_HEIGHT = 40, START_HEIGHT = 24;
    /** The last report's card, and a line of the recent ones. */
    private static final int LAST_HEIGHT = 104, RECENT_ROW = 20;
    private static final int WARN = 0xFFF2C14E;
    private static final int BOX = 0xFF12161B;

    /** The cards to pick from: what each one scans, with its icon and color. */
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

    private FlatButton startButton, openButton, logsButton;
    private final FlatButton[] sideButtons = new FlatButton[ScanSide.values().length];
    private TimeSlider slider;
    private FlatTextField timeField;
    /** What was typed in the time box can't be read as a time. */
    private boolean timeInvalid;
    private final Smooth[] cardLight = new Smooth[Card.values().length];
    private final Smooth shownProgress = new Smooth(0);
    private final List<Smooth> recentLight = new ArrayList<>();
    /** The reports saved lately, newest first. */
    private List<ReportStore.Saved> recent = new ArrayList<>();
    /** Something to tell about the recent reports (one couldn't be read), and when. */
    private String recentMessage;
    private long recentMessageAt;

    /** The page's two columns, and where their parts are. */
    private int leftX0, leftX1, rightX0, rightX1;
    private int cardsY, sideY, durationY, statusY, startY, lastY, recentY, recentBottom;

    public GuiLagFinder() {
        ClientScanner.INSTANCE.query();
        for (int i = 0; i < cardLight.length; i++) {
            cardLight[i] = new Smooth(0);
        }
    }

    @Override
    protected Page page() {
        return Page.SCAN;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        layoutShell();
        recent = ReportStore.list();
        recentLight.clear();

        int pageWidth = shellRight - pageLeft;
        int rightWidth = Math.max(190, Math.min(260, pageWidth * 36 / 100));
        rightX1 = shellRight - 12;
        rightX0 = rightX1 - rightWidth;
        leftX0 = pageLeft + 12;
        leftX1 = rightX0 - 14;
        int headerBottom = shellTop + WindowHeader.HEIGHT;

        cardsY = headerBottom + 12 + 10;
        sideY = cardsY + 2 * CARD_HEIGHT + CARD_GAP + 14 + 10;
        durationY = sideY + 18 + 14 + 12;
        // The start button at the foot of the page, how the scan goes over it.
        startY = Math.max(shellBottom - 12 - START_HEIGHT, durationY + TimeSlider.HEIGHT + 12 + STATUS_HEIGHT + 8);
        statusY = startY - 8 - STATUS_HEIGHT;
        lastY = headerBottom + 12 + 10;
        recentY = lastY + LAST_HEIGHT + 14 + 10;
        recentBottom = shellBottom - 12 - 20 - 6;

        buttonList.clear();
        String[][] sideIcons = { Icons.SMALL_GRID, Icons.SMALL_FRAME, Icons.SMALL_GLOBE };
        String[] sideLabels = { "lagfinder.gui.side_server", "lagfinder.gui.side_client", "lagfinder.gui.side_both" };
        int sideWidth = (leftX1 - leftX0 - 2 * 6) / 3;
        for (ScanSide side : ScanSide.values()) {
            int i = side.ordinal();
            int x = leftX0 + i * (sideWidth + 6);
            int w = i == sideButtons.length - 1 ? leftX1 - x : sideWidth;
            sideButtons[i] = new FlatButton(ID_SIDE + i, x, sideY, w, 18, I18n.format(sideLabels[i]));
            sideButtons[i].icon = sideIcons[i];
            buttonList.add(sideButtons[i]);
        }
        String[] labels = new String[MARKS.length];
        for (int i = 0; i < MARKS.length; i++) {
            labels[i] = shortLabel(MARKS[i]);
        }
        int sliderRight = leftX1 - TIME_BOX - 14;
        slider = new TimeSlider(leftX0 + 4, durationY + 1, sliderRight - leftX0 - 4, MARKS, labels, chosenSeconds, s -> {
            chosenSeconds = s;
            timeField.setText(formatTime(s));
            timeInvalid = false;
        });
        String typed = timeField != null && timeField.isFocused() ? timeField.getText() : formatTime(chosenSeconds);
        timeField = new FlatTextField(fontRendererObj, leftX1 - TIME_BOX, durationY - 1, TIME_BOX, 18);
        timeField.setMaxStringLength(12);
        timeField.setText(typed);

        startButton = new FlatButton(ID_START, leftX0, startY, leftX1 - leftX0, START_HEIGHT, "");
        startButton.icon = Icons.PLAY;
        buttonList.add(startButton);
        openButton = new FlatButton(
            ID_OPEN,
            rightX0 + 8,
            lastY + LAST_HEIGHT - 8 - 20,
            rightX1 - rightX0 - 16,
            20,
            I18n.format("lagfinder.gui.open_report"));
        openButton.icon = Icons.STATS;
        buttonList.add(openButton);
        logsButton = new FlatButton(
            ID_LOGS,
            rightX0,
            shellBottom - 12 - 20,
            rightX1 - rightX0,
            20,
            I18n.format("lagfinder.gui.all_logs"));
        logsButton.icon = Icons.LOGS;
        buttonList.add(logsButton);
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, shellRight, shellTop));
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
            startButton.displayString = I18n.format("lagfinder.gui.start") + "  ·  " + kindName(chosenSide, chosenFocus)
                + ", " + formatTime(chosenSeconds);
            startButton.icon = Icons.PLAY;
            startButton.danger = false;
            startButton.active = canStart();
            startButton.enabled = canStart();
        }
        openButton.visible = scanner.report() != null;
        openButton.enabled = !active;
        openButton.active = openButton.enabled && scanner.hasUnseenReport();
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
            openPage(Page.LOGS);
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
        if (button != 0 || mc.currentScreen != this) {
            return;
        }
        int recentIndex = recentAt(mouseX, mouseY);
        if (recentIndex >= 0) {
            openSaved(recent.get(recentIndex));
            return;
        }
        if (ClientScanner.INSTANCE.isActive()) {
            return;
        }
        if (slider.mousePressed(fontRendererObj, mouseX, mouseY)) {
            return;
        }
        for (Card card : Card.values()) {
            int x0 = cardX(card), y0 = cardY(card);
            if (Theme.inside(mouseX, mouseY, x0, y0, x0 + cardWidth(card), y0 + CARD_HEIGHT)) {
                choose(card);
                refresh();
            }
        }
    }

    /** Opens a saved report, as the logs do. */
    private void openSaved(ReportStore.Saved saved) {
        Report report = ReportStore.load(saved.file);
        if (report == null) {
            recentMessage = I18n.format("lagfinder.logs.unreadable");
            recentMessageAt = System.currentTimeMillis();
            return;
        }
        ClientScanner.INSTANCE.setReport(report);
        open(mc, report);
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
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == mc.gameSettings.keyBindInventory.getKeyCode()
            || keyCode == KeyHandler.OPEN.getKeyCode()) {
            mc.displayGuiScreen(null);
        } else if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)
            && !ClientScanner.INSTANCE.isActive() && canStart()) {
                // Enter starts the scan, as the button does.
                actionPerformed(startButton);
            }
    }

    // ---------------------------------------------------------------- Layout

    /** The lag scans side by side on the first row; the three deeper looks on the second. */
    private static int row(Card card) {
        return card.ordinal() < 2 ? 0 : 1;
    }

    private static int column(Card card) {
        return card.ordinal() < 2 ? card.ordinal() : card.ordinal() - 2;
    }

    private int cardWidth(Card card) {
        int columns = row(card) == 0 ? 2 : 3;
        return (leftX1 - leftX0 - (columns - 1) * CARD_GAP) / columns;
    }

    private int cardX(Card card) {
        return leftX0 + column(card) * (cardWidth(card) + CARD_GAP);
    }

    private int cardY(Card card) {
        return cardsY + row(card) * (CARD_HEIGHT + CARD_GAP);
    }

    /** The recent reports that fit in their card. */
    private int recentShown() {
        return Math.max(0, Math.min(recent.size(), (recentBottom - recentY - 2) / RECENT_ROW));
    }

    private int recentAt(int mouseX, int mouseY) {
        if (!Theme.inside(mouseX, mouseY, rightX0 + 1, recentY + 1, rightX1 - 1, recentBottom - 1)) {
            return -1;
        }
        int index = (mouseY - recentY - 1) / RECENT_ROW;
        return index >= 0 && index < recentShown() ? index : -1;
    }

    @Override
    public void updateScreen() {
        timeField.updateCursorCounter();
        refresh();
    }

    // ---------------------------------------------------------------- Drawing

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        refresh();
        ClientScanner scanner = ClientScanner.INSTANCE;
        drawShell(mouseX, mouseY);
        String pill = scanner.serverHasMod() ? I18n.format("lagfinder.gui.server_ok")
            : I18n.format("lagfinder.gui.client_only");
        WindowHeader.draw(
            fontRendererObj,
            pageLeft,
            shellTop,
            shellRight,
            shellRight - WindowHeader.CLOSE_ROOM,
            Icons.CLOCK,
            I18n.format("lagfinder.nav.scan"),
            I18n.format("lagfinder.gui.subtitle"),
            Theme.TEXT_MUTED,
            pill);

        sectionTitle(I18n.format("lagfinder.gui.what"), leftX0, cardsY - 11);
        for (Card card : Card.values()) {
            drawCard(card, mouseX, mouseY);
        }
        sectionTitle(I18n.format("lagfinder.gui.side"), leftX0, sideY - 11);
        sectionTitle(I18n.format("lagfinder.gui.duration"), leftX0, durationY - 13);
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
        drawLastReport();
        drawRecent(mouseX, mouseY);
        super.drawScaled(mouseX, mouseY, partialTicks);
        drawShellTooltip(mouseX, mouseY);
    }

    /** A section's title: small capitals, muted, with a line running to the column's end. */
    private void sectionTitle(String text, int x, int y) {
        String upper = text.toUpperCase();
        fontRendererObj.drawString(upper, x, y, Theme.TEXT_MUTED);
        int lineX = x + fontRendererObj.getStringWidth(upper) + 6;
        int right = x < rightX0 ? leftX1 : rightX1;
        if (lineX < right) {
            Theme.fill(lineX, y + 4, right, y + 5, 0xFF222831);
        }
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

    private void drawCard(Card card, int mouseX, int mouseY) {
        ClientScanner scanner = ClientScanner.INSTANCE;
        int x0 = cardX(card), x1 = x0 + cardWidth(card), y0 = cardY(card), y1 = y0 + CARD_HEIGHT;
        boolean chosen = chosenCard() == card;
        boolean usable = card != Card.TPS || scanner.serverAllowed();
        boolean hovered = !scanner.isActive() && Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
        double lit = cardLight[card.ordinal()].update(hovered ? 1 : 0, 22);
        int background = chosen ? Theme.blend(card.color, 0xFF161A20, 0.84)
            : Theme.blend(0xFF1B2028, 0xFF232A34, lit);
        int border = chosen ? card.color : Theme.blend(Theme.BORDER, Theme.blend(card.color, Theme.BORDER, 0.5), lit);
        Theme.fill(x0, y0, x1, y1, background);
        Theme.outline(x0, y0, x1, y1, border);
        if (chosen) {
            Theme.fill(x0, y0, x0 + 2, y1, card.color);
            // A check in the corner: this is what will be scanned.
            Icons.draw(Icons.SMALL_CHECK, x1 - 12, y0 + 6, card.color);
        }
        int iconColor = !usable ? Theme.TEXT_DISABLED
            : Theme.iconShade(card.color, chosen || hovered, false, !chosen);
        Icons.draw(card.icon, x0 + 9, y0 + 8, iconColor);
        fontRendererObj.drawStringWithShadow(
            Theme.ellipsize(fontRendererObj, I18n.format(card.title), x1 - x0 - 44),
            x0 + 26,
            y0 + 9,
            usable ? Theme.TEXT : Theme.TEXT_DISABLED);
        String description = I18n.format(card.text);
        int descriptionColor = chosen ? Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.35) : Theme.TEXT_MUTED;
        String problem = serverProblem();
        if (card == Card.TPS && problem != null) {
            description = I18n.format(problem);
            descriptionColor = WARN;
        }
        List<String> lines = fontRendererObj.listFormattedStringToWidth(description, x1 - x0 - 16);
        for (int i = 0; i < lines.size() && i < 3; i++) {
            fontRendererObj.drawString(lines.get(i), x0 + 9, y0 + 22 + i * 10, descriptionColor);
        }
    }

    private void drawStatus() {
        ClientScanner scanner = ClientScanner.INSTANCE;
        int x0 = leftX0, x1 = leftX1, y0 = statusY, y1 = statusY + STATUS_HEIGHT;
        Theme.fill(x0, y0, x1, y1, BOX);
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
            int barX0 = x0 + 7, barX1 = x1 - 7, barY = y0 + 23;
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
        Icons.draw(Icons.SMALL_CHECK, x0 + 7, y0 + 7, Theme.SUCCESS);
        String ready = I18n.format("lagfinder.status.ready") + " - " + kindName(chosenSide, chosenFocus) + ", "
            + formatTime(chosenSeconds);
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, ready, x1 - x0 - 26), x0 + 19, y0 + 7, Theme.TEXT);
        List<String> hint = fontRendererObj.listFormattedStringToWidth(I18n.format(hintKey()), x1 - x0 - 26);
        for (int i = 0; i < hint.size() && i < 2; i++) {
            fontRendererObj.drawString(hint.get(i), x0 + 19, y0 + 19 + i * 10, Theme.TEXT_MUTED);
        }
    }

    private void warning(String text, int x0, int y0, int x1) {
        Icons.draw(Icons.SMALL_WARN, x0 + 7, y0 + 7, WARN);
        List<String> lines = fontRendererObj.listFormattedStringToWidth(text, x1 - x0 - 30);
        for (int i = 0; i < lines.size() && i < 3; i++) {
            fontRendererObj.drawString(lines.get(i), x0 + 19, y0 + 7 + i * 10, WARN);
        }
    }

    /** The last report: what it was, when, its rates and the button to open it. */
    private void drawLastReport() {
        ClientScanner scanner = ClientScanner.INSTANCE;
        Report report = scanner.report();
        sectionTitle(I18n.format("lagfinder.status.last"), rightX0, lastY - 11);
        int x0 = rightX0, x1 = rightX1, y0 = lastY, y1 = lastY + LAST_HEIGHT;
        boolean fresh = report != null && scanner.hasUnseenReport();
        Theme.fill(x0, y0, x1, y1, fresh ? Theme.blend(Theme.SUCCESS, BOX, 0.9) : BOX);
        Theme.outline(x0, y0, x1, y1, fresh ? Theme.blend(Theme.SUCCESS, Theme.BORDER, 0.4) : Theme.BORDER);
        if (report == null) {
            Icons.draw(Icons.STATS, (x0 + x1) / 2 - 5, y0 + 26, Theme.TEXT_DISABLED);
            List<String> lines = fontRendererObj.listFormattedStringToWidth(
                I18n.format("lagfinder.gui.no_report_yet"),
                x1 - x0 - 20);
            for (int i = 0; i < lines.size() && i < 3; i++) {
                Theme.centered(fontRendererObj, lines.get(i), (x0 + x1) / 2, y0 + 44 + i * 10, Theme.TEXT_MUTED);
            }
            return;
        }
        int color = GuiLogs.color(report.side(), report.focus);
        Icons.draw(GuiLogs.icon(report.side(), report.focus), x0 + 8, y0 + 9, color);
        int titleRight = x1 - 8;
        if (fresh) {
            String badge = I18n.format("lagfinder.gui.new_badge");
            int badgeWidth = fontRendererObj.getStringWidth(badge) + 8;
            Theme.fill(x1 - 8 - badgeWidth, y0 + 6, x1 - 8, y0 + 18, Theme.blend(Theme.SUCCESS, BOX, 0.6));
            fontRendererObj.drawString(badge, x1 - 4 - badgeWidth, y0 + 8, Theme.TEXT);
            titleRight -= badgeWidth + 4;
        }
        fontRendererObj.drawStringWithShadow(
            Theme.ellipsize(fontRendererObj, kindName(report.side(), report.focus), titleRight - x0 - 22),
            x0 + 20,
            y0 + 8,
            Theme.TEXT);
        String when = new SimpleDateFormat("dd.MM HH:mm").format(new Date(report.startedAt)) + "  ·  "
            + GuiLogs.ago(report.startedAt);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, when, x1 - x0 - 28),
            x0 + 20,
            y0 + 19,
            Theme.TEXT_DISABLED);

        // Three numbers: the average and the lowest rate, and how long it ran.
        boolean tps = report.mode == ScanMode.TPS;
        float target = report.mode.targetRate();
        String[] labels = { I18n.format(tps ? "lagfinder.tile.avg_tps" : "lagfinder.tile.avg_fps"),
            I18n.format(tps ? "lagfinder.tile.min_tps" : "lagfinder.tile.min_fps"),
            I18n.format("lagfinder.gui.length") };
        String[] values = { String.format("%.1f", report.averageRate()), String.format("%.1f", report.minimumRate()),
            Names.clock(report.durationSec) };
        int[] colors = { Draw.rateColor(report.averageRate(), target), Draw.rateColor(report.minimumRate(), target),
            Theme.TEXT };
        int gap = 4, tileWidth = (x1 - x0 - 16 - 2 * gap) / 3, ty = y0 + 34;
        for (int i = 0; i < 3; i++) {
            int tx = x0 + 8 + i * (tileWidth + gap);
            Theme.fill(tx, ty, tx + tileWidth, ty + 30, 0xFF0F1216);
            Theme.outline(tx, ty, tx + tileWidth, ty + 30, Theme.BORDER);
            Theme.fill(tx, ty, tx + 2, ty + 30, colors[i]);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, labels[i], tileWidth - 10),
                tx + 6,
                ty + 5,
                Theme.TEXT_DISABLED);
            fontRendererObj.drawStringWithShadow(values[i], tx + 6, ty + 17, colors[i]);
        }
    }

    /** The reports saved lately: a click opens one. */
    private void drawRecent(int mouseX, int mouseY) {
        sectionTitle(I18n.format("lagfinder.gui.recent"), rightX0, recentY - 11);
        int x0 = rightX0, x1 = rightX1, y0 = recentY, y1 = recentBottom;
        if (y1 - y0 < RECENT_ROW) {
            return;
        }
        Theme.fill(x0, y0, x1, y1, BOX);
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);
        String note = recentMessage != null && System.currentTimeMillis() - recentMessageAt < 4000 ? recentMessage
            : null;
        if (recent.isEmpty()) {
            List<String> lines = fontRendererObj.listFormattedStringToWidth(
                I18n.format("lagfinder.gui.no_recent"),
                x1 - x0 - 20);
            for (int i = 0; i < lines.size() && i < 3; i++) {
                Theme.centered(fontRendererObj, lines.get(i), (x0 + x1) / 2, y0 + 12 + i * 10, Theme.TEXT_DISABLED);
            }
            return;
        }
        int hovered = recentAt(mouseX, mouseY);
        while (recentLight.size() < recent.size()) {
            recentLight.add(new Smooth(0));
        }
        for (int i = 0; i < recentShown(); i++) {
            ReportStore.Saved saved = recent.get(i);
            int y = y0 + 1 + i * RECENT_ROW;
            double lit = recentLight.get(i)
                .update(i == hovered ? 1 : 0, 22);
            if (lit > 0.01) {
                Theme.fill(x0 + 1, y, x1 - 1, y + RECENT_ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
            }
            if (i > 0) {
                Theme.fill(x0 + 6, y, x1 - 6, y + 1, 0xFF1C2129);
            }
            int textY = y + (RECENT_ROW - 8) / 2;
            Icons.draw(
                GuiLogs.smallIcon(saved.side, saved.focus),
                x0 + 7,
                y + (RECENT_ROW - 7) / 2,
                GuiLogs.color(saved.side, saved.focus));
            String ago = GuiLogs.ago(saved.time);
            int agoWidth = fontRendererObj.getStringWidth(ago);
            fontRendererObj.drawString(ago, x1 - 7 - agoWidth, textY, Theme.TEXT_DISABLED);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, kindName(saved.side, saved.focus), x1 - x0 - 30 - agoWidth),
                x0 + 19,
                textY,
                i == hovered ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.5));
        }
        if (note != null) {
            Theme.centered(fontRendererObj, note, (x0 + x1) / 2, y1 - 11, WARN);
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

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
