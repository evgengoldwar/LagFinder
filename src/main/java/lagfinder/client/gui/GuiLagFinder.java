package lagfinder.client.gui;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;

import lagfinder.client.ClientScanner;
import lagfinder.client.KeyHandler;
import lagfinder.client.Names;
import lagfinder.client.gui.ui.FlatButton;
import lagfinder.client.gui.ui.FlatTextField;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.TimeSlider;
import lagfinder.core.LagFilter;
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
public class GuiLagFinder extends GuiWindow {

    private static final int ID_START = 0, ID_OPEN = 1;
    /** The buttons picking the side, by {@link ScanSide} ordinal. */
    private static final int ID_SIDE = 10;
    /** The buttons picking what a tick scan's log leaves out: by average time, or by the worst tick. */
    private static final int ID_FILTER_AVERAGE = 20, ID_FILTER_PEAK = 21;
    /** The marks on the time slider (seconds). */
    private static final int[] MARKS = { 10, 30, 60, 120, 300, 600 };
    /** Shortest and longest scan that can be typed in (seconds); the server may allow less. */
    private static final int MIN_SECONDS = 5, MAX_SECONDS = 3600;
    /** Heights of the cards of the first row (the lag scans) and of the second (the deeper looks). */
    private static final int CARD_HEIGHT = 50, SMALL_CARD_HEIGHT = 40, CARD_GAP = 5, TIME_BOX = 58;
    /** Heights of the sections, and the gap between them. */
    private static final int WHAT_HEIGHT = 20 + CARD_HEIGHT + CARD_GAP + SMALL_CARD_HEIGHT + 6, WHERE_HEIGHT = 44,
        SLIDER_HEIGHT = 46, STATUS_HEIGHT = 34, SECTION_GAP = 6;
    /** The marks on the slider of the least time a tick scan's log shows (hundredths of a millisecond). */
    private static final int[] FILTER_MARKS = { 1, 10, 50, 100, 200, 500, 1000, 5000 };
    private static final int FILTER_BUTTON = 44;
    private static final int WARN = 0xFFF2C14E;

    /** The cards to pick from: what each one scans. */
    private enum Card {

        TPS(ScanFocus.LAG, Icons.SERVER, Theme.ACCENT, "lagfinder.gui.tps_title", "lagfinder.gui.tps_text", null),
        FPS(ScanFocus.LAG, Icons.MONITOR, 0xFFB37FEB, "lagfinder.gui.fps_title", "lagfinder.gui.fps_text", null),
        METHODS(ScanFocus.METHODS, Icons.METHODS, 0xFFFF8C42, "lagfinder.gui.methods_title",
            "lagfinder.gui.methods_text", "lagfinder.gui.methods_help"),
        OBJECTS(ScanFocus.OBJECTS, Icons.OBJECTS, 0xFF3FB950, "lagfinder.gui.objects_title",
            "lagfinder.gui.objects_text", "lagfinder.gui.objects_help"),
        ALLOCATIONS(ScanFocus.ALLOCATIONS, Icons.ALLOCATIONS, 0xFF39C5CF, "lagfinder.gui.alloc_title",
            "lagfinder.gui.alloc_text", "lagfinder.gui.alloc_help");

        final ScanFocus focus;
        final String[] icon;
        /** The kind's own color, as in the saved logs. */
        final int color;
        final String title, text;
        /** What the "?" after the title tells, or null for none. */
        final String help;

        Card(ScanFocus focus, String[] icon, int color, String title, String text, String help) {
            this.focus = focus;
            this.icon = icon;
            this.color = color;
            this.title = title;
            this.text = text;
            this.help = help;
        }
    }

    /** The choices stay while the game runs. */
    private static ScanSide chosenSide = ScanSide.SERVER;
    /** Both sides were picked for a deeper scan before a lag card: picked again on going back to one. */
    private static boolean bothKept;
    private static ScanFocus chosenFocus = ScanFocus.LAG;
    private static int chosenSeconds = 30;
    /** What a tick scan's log leaves out: what took less, by average or at its worst tick; null shows everything. */
    private static LagFilter.By chosenFilter;
    /** The least time it shows (hundredths of a millisecond). */
    private static int chosenFilterHundredths = 100;
    /** A log closed with Esc: the key opens it again, instead of this window, so the search can go on. */
    private static GuiScreen reopen;

    private FlatButton startButton, openButton;
    private final FlatButton[] sideButtons = new FlatButton[ScanSide.values().length];
    private TimeSlider slider;
    /** The filter of a tick scan: it can be set only while the tick scan is picked. */
    private FlatButton averageButton, peakButton;
    private TimeSlider filterSlider;
    private FlatTextField timeField;
    /** What was typed in the time box can't be read as a time. */
    private boolean timeInvalid;
    private final Smooth[] cardLight = { new Smooth(0), new Smooth(0), new Smooth(0), new Smooth(0), new Smooth(0) };
    private final Smooth shownProgress = new Smooth(0);
    private int whatY, sideY, durationY, filterY, statusY, buttonsY;

    public GuiLagFinder() {
        ClientScanner.INSTANCE.query();
    }

    @Override
    protected Tab tab() {
        return Tab.SCAN;
    }

    @Override
    protected void layOut() {
        Keyboard.enableRepeatEvents(true);
        whatY = contentTop;
        sideY = whatY + WHAT_HEIGHT + SECTION_GAP;
        durationY = sideY + WHERE_HEIGHT + SECTION_GAP;
        filterY = durationY + SLIDER_HEIGHT + SECTION_GAP;
        statusY = filterY + SLIDER_HEIGHT + SECTION_GAP;
        buttonsY = statusY + STATUS_HEIGHT + SECTION_GAP;

        int inner = contentRight - contentLeft - 16;
        String[][] sideIcons = { Icons.SERVER, Icons.MONITOR, Icons.TARGET };
        String[] sideLabels = { "lagfinder.gui.side_server", "lagfinder.gui.side_client", "lagfinder.gui.side_both" };
        int sideWidth = (inner - 2 * 8) / 3;
        for (ScanSide side : ScanSide.values()) {
            int i = side.ordinal();
            int x = contentLeft + 8 + i * (sideWidth + 8);
            int w = i == sideButtons.length - 1 ? contentRight - 8 - x : sideWidth;
            sideButtons[i] = new FlatButton(ID_SIDE + i, x, sideY + 20, w, 18, I18n.format(sideLabels[i]));
            sideButtons[i].icon = sideIcons[i];
            sideButtons[i].iconSlot = 11;
            buttonList.add(sideButtons[i]);
        }
        String[] labels = new String[MARKS.length];
        for (int i = 0; i < MARKS.length; i++) {
            labels[i] = shortLabel(MARKS[i]);
        }
        int boxLeft = contentRight - 8 - TIME_BOX;
        int sliderRight = boxLeft - 16;
        slider = new TimeSlider(
            contentLeft + 12,
            durationY + 21,
            sliderRight - contentLeft - 12,
            MARKS,
            labels,
            chosenSeconds,
            s -> {
                chosenSeconds = s;
                timeField.setText(formatTime(s));
                timeInvalid = false;
            }).setFormat(GuiLagFinder::formatTime);
        String typed = timeField != null && timeField.isFocused() ? timeField.getText() : formatTime(chosenSeconds);
        timeField = new FlatTextField(fontRendererObj, boxLeft, durationY + 19, TIME_BOX, 18);
        timeField.setMaxStringLength(12);
        timeField.setText(typed);

        averageButton = new FlatButton(
            ID_FILTER_AVERAGE,
            contentLeft + 8,
            filterY + 19,
            FILTER_BUTTON,
            18,
            I18n.format("lagfinder.gui.filter_avg"));
        peakButton = new FlatButton(
            ID_FILTER_PEAK,
            contentLeft + 8 + FILTER_BUTTON + 4,
            filterY + 19,
            FILTER_BUTTON,
            18,
            I18n.format("lagfinder.gui.filter_peak"));
        buttonList.add(averageButton);
        buttonList.add(peakButton);
        String[] filterLabels = new String[FILTER_MARKS.length];
        for (int i = 0; i < FILTER_MARKS.length; i++) {
            filterLabels[i] = filterLabel(FILTER_MARKS[i]);
        }
        int filterLeft = contentLeft + 8 + 2 * FILTER_BUTTON + 4 + 16;
        filterSlider = new TimeSlider(
            filterLeft,
            filterY + 21,
            sliderRight - filterLeft,
            FILTER_MARKS,
            filterLabels,
            chosenFilterHundredths,
            h -> chosenFilterHundredths = h);
        filterSlider.setStep(h -> h < 100 ? 1 : h < 1000 ? 10 : 50);
        filterSlider.setFormat(h -> filterLabel(h) + " " + I18n.format("lagfinder.unit.ms"));

        int half = (contentRight - contentLeft - 8) / 2;
        startButton = new FlatButton(ID_START, contentLeft, buttonsY, half, 20, "");
        startButton.icon = Icons.PLAY;
        buttonList.add(startButton);
        openButton = new FlatButton(
            ID_OPEN,
            contentRight - half,
            buttonsY,
            half,
            20,
            I18n.format("lagfinder.gui.open_report"));
        openButton.icon = Icons.STATS;
        buttonList.add(openButton);
        refresh();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    /** A least time as written on the filter's slider (hundredths of a millisecond): "0.05", "1", "2.5". */
    private static String filterLabel(int hundredths) {
        String text = String.format(Locale.ROOT, "%.2f", hundredths / 100.0);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** What the next tick scan's log leaves out, null for nothing. */
    private static LagFilter filter() {
        return chosenCard() == Card.TPS && chosenFilter != null
            ? new LagFilter(chosenFilter, chosenFilterHundredths / 100.0)
            : null;
    }

    /** A mark's label: "10s", "1m". */
    private static String shortLabel(int seconds) {
        return seconds < 60 ? seconds + I18n.format("lagfinder.unit.s")
            : seconds / 60 + I18n.format("lagfinder.unit.m");
    }

    /** A length as written in the time box: "45s", "2m", "1m 30s". */
    public static String formatTime(int seconds) {
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
        // The filter is for tick scans only: grey for the others.
        boolean tickScan = chosenCard() == Card.TPS;
        averageButton.active = tickScan && chosenFilter == LagFilter.By.AVERAGE;
        peakButton.active = tickScan && chosenFilter == LagFilter.By.PEAK;
        averageButton.enabled = peakButton.enabled = !active && tickScan;
        // Grey, and not to be moved, until it is said what the time is compared with.
        filterSlider.enabled = !active && tickScan && chosenFilter != null;
        if (active && timeField.isFocused()) {
            leaveTimeField();
        }
        if (active) {
            startButton.displayString = I18n.format("lagfinder.gui.cancel");
            startButton.icon = Icons.CLOSE;
            startButton.iconColor = Theme.DANGER;
            startButton.danger = true;
            startButton.active = false;
            startButton.enabled = scanner.phase() != ClientScanner.Phase.RECEIVING;
        } else {
            startButton.displayString = I18n.format("lagfinder.gui.start") + "  \u00b7  " + formatTime(chosenSeconds);
            startButton.icon = Icons.PLAY;
            startButton.iconColor = 0;
            startButton.danger = false;
            startButton.active = canStart();
            startButton.enabled = canStart();
        }
        openButton.enabled = !active && scanner.report() != null;
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

    /** What the key opens: the log closed last with Esc, or this window. */
    public static GuiScreen toOpen() {
        GuiScreen screen = reopen;
        reopen = null;
        return screen != null ? screen : new GuiLagFinder();
    }

    /** Closes a log to the game; the key opens it again as it was. */
    static void closeToGame(Minecraft mc, GuiScreen log) {
        mc.displayGuiScreen(null);
        reopen = log;
    }

    /** The key opens this window again (a log was closed with its own button, or a new report came). */
    public static void forgetReopen() {
        reopen = null;
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
    protected void pressed(GuiButton button) {
        ClientScanner scanner = ClientScanner.INSTANCE;
        if (timeField.isFocused()) {
            leaveTimeField();
        }
        if (button.id == ID_START) {
            if (scanner.isActive()) {
                scanner.cancel();
            } else if (canStart()) {
                scanner.start(chosenSide, chosenFocus, chosenSeconds, filter());
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
        } else if (button.id == ID_FILTER_AVERAGE || button.id == ID_FILTER_PEAK) {
            // A click on the one picked turns the filter off: the log shows everything.
            LagFilter.By by = button.id == ID_FILTER_AVERAGE ? LagFilter.By.AVERAGE : LagFilter.By.PEAK;
            chosenFilter = chosenFilter == by ? null : by;
        }
        refresh();
    }

    @Override
    protected void clicked(int mouseX, int mouseY, int button) {
        boolean wasFocused = timeField.isFocused();
        timeField.mouseClicked(mouseX, mouseY, button);
        if (wasFocused && !timeField.isFocused()) {
            leaveTimeField();
        } else if (button == 1 && timeField.isMouseOver(mouseX, mouseY)) {
            // Cleared by a right click: something new is being typed.
            timeInvalid = true;
        }
        clickButtons(mouseX, mouseY, button);
        if (button != 0 || ClientScanner.INSTANCE.isActive()) {
            return;
        }
        if (slider.mousePressed(fontRendererObj, mouseX, mouseY)) {
            return;
        }
        if (chosenCard() == Card.TPS && filterSlider.mousePressed(fontRendererObj, mouseX, mouseY)) {
            return;
        }
        for (Card card : Card.values()) {
            int x0 = cardX(card), y0 = cardY(card);
            if (usable(card) && Theme.inside(mouseX, mouseY, x0, y0, x0 + cardWidth(card), y0 + cardHeight(card))) {
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
        if (typedChar >= '1' && typedChar < '1' + Card.values().length && !ClientScanner.INSTANCE.isActive()) {
            // The number on a card picks it.
            Card card = Card.values()[typedChar - '1'];
            if (usable(card)) {
                choose(card);
                refresh();
            }
            return;
        }
        if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) && startButton.enabled) {
            // Enter does what the big button does: start, or cancel the scan running.
            pressed(startButton);
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
        return (contentRight - contentLeft - 12 - (columns - 1) * CARD_GAP) / columns;
    }

    private static int cardHeight(Card card) {
        return row(card) == 0 ? CARD_HEIGHT : SMALL_CARD_HEIGHT;
    }

    private int cardX(Card card) {
        return contentLeft + 6 + column(card) * (cardWidth(card) + CARD_GAP);
    }

    private int cardY(Card card) {
        return whatY + 20 + row(card) * (CARD_HEIGHT + CARD_GAP);
    }

    @Override
    public void updateScreen() {
        timeField.updateCursorCounter();
        refresh();
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        refresh();
        section(contentLeft, whatY, contentRight, whatY + WHAT_HEIGHT, null, I18n.format("lagfinder.gui.what"));
        for (Card card : Card.values()) {
            drawCard(card, mouseX, mouseY);
        }
        section(contentLeft, sideY, contentRight, sideY + WHERE_HEIGHT, Icons.PIN, I18n.format("lagfinder.gui.side"));
        section(
            contentLeft,
            durationY,
            contentRight,
            durationY + SLIDER_HEIGHT,
            Icons.SMALL_CLOCK,
            I18n.format("lagfinder.gui.duration"));
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
        drawFilter(mouseX, mouseY);
        drawStatus();
    }

    /** The filter's title, its slider and the least time it shows: grey while it is off, or not for this scan. */
    private void drawFilter(int mouseX, int mouseY) {
        section(
            contentLeft,
            filterY,
            contentRight,
            filterY + SLIDER_HEIGHT,
            Icons.SMALL_EYE,
            I18n.format("lagfinder.gui.filter"));
        boolean tickScan = chosenCard() == Card.TPS;
        if (!tickScan) {
            String only = I18n.format("lagfinder.gui.filter_tps_only");
            fontRendererObj.drawString(
                only,
                contentRight - 8 - fontRendererObj.getStringWidth(only),
                filterY + 6,
                Theme.TEXT_DISABLED);
        }
        filterSlider.draw(fontRendererObj, mouseX, mouseY);
        int x0 = contentRight - 8 - TIME_BOX, y0 = filterY + 19, x1 = x0 + TIME_BOX, y1 = y0 + 18;
        boolean on = tickScan && chosenFilter != null;
        Theme.fill(x0, y0, x1, y1, on ? 0xFF0F1216 : Theme.CONTROL_DISABLED);
        Theme.outline(x0, y0, x1, y1, on ? Theme.blend(Theme.BORDER, Theme.ACCENT, 0.5) : Theme.BORDER);
        String text = on ? filterLabel(chosenFilterHundredths) + " " + I18n.format("lagfinder.unit.ms")
            : I18n.format("lagfinder.gui.filter_all");
        text = Theme.ellipsize(fontRendererObj, text, TIME_BOX - 6);
        fontRendererObj.drawString(
            text,
            x0 + (TIME_BOX - fontRendererObj.getStringWidth(text)) / 2,
            y0 + 5,
            on ? Theme.TEXT : tickScan ? Theme.TEXT_MUTED : Theme.TEXT_DISABLED);
    }

    /** Where the "?" of a card is, after its title. */
    private int helpX(Card card) {
        String title = Theme.ellipsize(fontRendererObj, I18n.format(card.title), cardWidth(card) - 56);
        return cardX(card) + 26 + fontRendererObj.getStringWidth(title) + 5;
    }

    private boolean overHelp(Card card, int mouseX, int mouseY) {
        if (card.help == null) {
            return false;
        }
        int x = helpX(card), y = cardY(card) + 6;
        return Theme.inside(mouseX, mouseY, x - 1, y - 1, x + 10, y + 10);
    }

    /** What a button will do, when that isn't plain from it. */
    @Override
    protected void drawOver(int mouseX, int mouseY) {
        String text = tooltipText(mouseX, mouseY);
        if (text != null) {
            tooltip(text, mouseX, mouseY);
        }
    }

    private String tooltipText(int mouseX, int mouseY) {
        ClientScanner scanner = ClientScanner.INSTANCE;
        for (Card card : Card.values()) {
            if (overHelp(card, mouseX, mouseY)) {
                return I18n.format(card.title) + "\n" + I18n.format(card.help);
            }
        }
        if (openButton.isMouseOver(mouseX, mouseY) && scanner.report() != null) {
            return I18n.format(scanner.hasUnseenReport() ? "lagfinder.tip.open_new" : "lagfinder.tip.open_last") + "\n"
                + summary(scanner.report());
        }
        if (chosenCard() != Card.TPS) {
            boolean overFilter = Theme
                .inside(mouseX, mouseY, contentLeft, filterY, contentRight, filterY + SLIDER_HEIGHT);
            return overFilter ? I18n.format("lagfinder.tip.filter_tps_only") : bothTip(mouseX, mouseY);
        }
        if (averageButton.isMouseOver(mouseX, mouseY)) {
            return I18n.format("lagfinder.tip.filter_avg");
        }
        if (peakButton.isMouseOver(mouseX, mouseY)) {
            return I18n.format("lagfinder.tip.filter_peak");
        }
        if (chosenFilter == null
            && Theme.inside(mouseX, mouseY, contentLeft + 8, filterY + 18, contentRight - 8, filterY + SLIDER_HEIGHT)) {
            return I18n.format("lagfinder.tip.filter_off");
        }
        return bothTip(mouseX, mouseY);
    }

    /** Why both sides can't be picked for a lag scan, under the mouse. */
    private String bothTip(int mouseX, int mouseY) {
        FlatButton both = sideButtons[ScanSide.BOTH.ordinal()];
        return both.isMouseOver(mouseX, mouseY) && chosenFocus == ScanFocus.LAG ? I18n.format("lagfinder.tip.both_lag")
            : null;
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
        int x0 = cardX(card), x1 = x0 + cardWidth(card), y0 = cardY(card), y1 = y0 + cardHeight(card);
        boolean chosen = chosenCard() == card;
        boolean usable = usable(card);
        boolean hovered = usable && !scanner.isActive() && Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
        double lit = cardLight[card.ordinal()].update(hovered ? 1 : 0, 22);
        // In the kind's own color: lit when picked, a hint of it under the mouse.
        int background = chosen ? Theme.blend(0xFF1B2028, card.color | 0xFF000000, 0.16)
            : Theme.blend(0xFF1B2028, 0xFF232A34, lit);
        int border = chosen ? card.color : Theme.blend(Theme.BORDER, Theme.blend(Theme.BORDER, card.color, 0.6), lit);
        if (chosen || lit > 0.01) {
            Theme.glow(x0, y0, x1, y1, card.color, chosen ? 0.55 + 0.45 * lit : lit * 0.5);
        }
        Theme.gradient(
            x0,
            y0,
            x1,
            y1,
            Theme.blend(background, card.color | 0xFF000000, chosen ? 0.08 : 0.03 * lit),
            Theme.blend(background, 0xFF000000, 0.18));
        Theme.outline(x0, y0, x1, y1, border);
        Theme.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, Theme.SHEEN);
        // A box in the corner, ticked on the card picked.
        int boxX = x1 - 15, boxY = y0 + 6;
        if (chosen) {
            Theme.fill(boxX, boxY, boxX + 10, boxY + 10, Theme.blend(card.color, 0xFF000000, 0.55));
            Theme.outline(boxX, boxY, boxX + 10, boxY + 10, card.color);
            Icons.draw(Icons.SMALL_CHECK, boxX + 2, boxY + 2, Theme.blend(card.color, 0xFFFFFFFF, 0.5));
        } else {
            Theme.fill(boxX, boxY, boxX + 10, boxY + 10, 0xFF0F1216);
            int edge = Theme.blend(Theme.BORDER, Theme.TEXT_MUTED, 0.3 + 0.4 * lit);
            Theme.outline(boxX, boxY, boxX + 10, boxY + 10, edge);
        }
        if (chosen) {
            Theme.gradient(x0, y0, x0 + 2, y1, card.color, Theme.blend(card.color, 0xFF000000, 0.4));
        }
        int iconColor = !usable ? Theme.TEXT_DISABLED
            : chosen ? card.color : Theme.blend(Theme.TEXT_MUTED, card.color, 0.35 + 0.65 * lit);
        Icons.draw(card.icon, x0 + 9, y0 + 7, iconColor);
        fontRendererObj.drawStringWithShadow(
            Theme.ellipsize(fontRendererObj, I18n.format(card.title), x1 - x0 - 56),
            x0 + 26,
            y0 + 8,
            usable ? Theme.TEXT : Theme.TEXT_DISABLED);
        if (card.help != null) {
            boolean overHelp = overHelp(card, mouseX, mouseY);
            Icons.draw(
                Icons.HELP,
                helpX(card),
                y0 + 6,
                overHelp ? Theme.ACCENT : Theme.blend(Theme.TEXT_DISABLED, Theme.TEXT_MUTED, 0.5 + 0.5 * lit));
        }
        String description = I18n.format(card.text);
        int descriptionColor = Theme.TEXT_MUTED;
        String problem = serverProblem();
        if (card == Card.TPS && problem != null) {
            description = I18n.format(problem);
            descriptionColor = WARN;
        }
        List<String> lines = fontRendererObj.listFormattedStringToWidth(description, x1 - x0 - 16);
        int room = (y1 - y0 - 20) / 10;
        for (int i = 0; i < lines.size() && i < room; i++) {
            String line = lines.get(i);
            if (i == room - 1 && lines.size() > room) {
                line = Theme.ellipsize(fontRendererObj, line + "...", x1 - x0 - 16);
            }
            fontRendererObj.drawString(line, x0 + 9, y0 + 20 + i * 10, descriptionColor);
        }
    }

    private void drawStatus() {
        ClientScanner scanner = ClientScanner.INSTANCE;
        int x0 = contentLeft, x1 = contentRight, y0 = statusY, y1 = statusY + STATUS_HEIGHT;
        Theme.card(x0, y0, x1, y1);
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
            // Something the server said meanwhile (the scan shortened to what it allows) shows in place of the title.
            String said = message != null ? scanner.messageText() : null;
            fontRendererObj.drawString(
                Theme.ellipsize(
                    fontRendererObj,
                    said != null ? said : title,
                    x1 - x0 - 30 - fontRendererObj.getStringWidth(right)),
                x0 + 19,
                y0 + 7,
                said != null ? WARN : Theme.TEXT);
            fontRendererObj.drawString(right, x1 - 7 - fontRendererObj.getStringWidth(right), y0 + 7, Theme.TEXT_MUTED);
            int barX0 = x0 + 7, barX1 = x1 - 7, barY = y0 + 20;
            Theme.fill(barX0, barY, barX1, barY + 8, 0xFF0B0E12);
            int filled = barX0 + (int) Math.round((barX1 - barX0) * Math.max(0, Math.min(1, shown)));
            int color = chosenCardColor(scanner.side(), scanner.focus());
            Draw.gradientRect(
                barX0,
                barY,
                filled,
                barY + 8,
                Theme.blend(color, 0xFFFFFFFF, 0.3),
                Theme.blend(color, 0xFF000000, 0.35));
            // Stripes sliding along the bar, so it is seen to be alive.
            if (filled - barX0 > 4) {
                Theme.clip(barX0, barY, filled, barY + 8);
                int shift = (int) (System.currentTimeMillis() / 60 % 12);
                for (int sx = barX0 - 12 + shift; sx < filled; sx += 12) {
                    for (int row = 0; row < 8; row++) {
                        Theme.fill(sx + row / 2, barY + row, sx + row / 2 + 4, barY + row + 1, 0x18FFFFFF);
                    }
                }
                Theme.unclip();
                Theme.fill(filled - 1, barY, filled, barY + 8, 0x80FFFFFF);
            }
            Theme.outline(barX0, barY, barX1, barY + 8, Theme.BORDER);
            return;
        }
        shownProgress.set(0);
        if (message != null) {
            warning(scanner.messageText(), x0, y0, x1);
            return;
        }
        if (timeInvalid) {
            warning(
                I18n.format("lagfinder.status.bad_time", formatTime(MIN_SECONDS), formatTime(MAX_SECONDS)),
                x0,
                y0,
                x1);
            return;
        }
        if (chosenSide.server() && serverProblem() != null) {
            warning(I18n.format(serverProblem()), x0, y0, x1);
            return;
        }
        int serverMax = scanner.serverMaxSeconds();
        if (chosenSide.server() && serverMax > 0 && chosenSeconds > serverMax) {
            // Said before starting, not cut short without a word.
            warning(I18n.format("lagfinder.status.server_max", formatTime(serverMax)), x0, y0, x1);
            return;
        }
        Report report = scanner.report();
        if (report != null && scanner.hasUnseenReport()) {
            String line = summary(report);
            Icons.draw(Icons.SMALL_CHECK, x0 + 7, y0 + 7, Theme.SUCCESS);
            fontRendererObj.drawString(I18n.format("lagfinder.status.done"), x0 + 19, y0 + 7, Theme.TEXT);
            fontRendererObj
                .drawString(Theme.ellipsize(fontRendererObj, line, x1 - x0 - 26), x0 + 19, y0 + 19, Theme.TEXT_MUTED);
            return;
        }
        Icons.draw(Icons.SMALL_CHECK, x0 + 7, y0 + 7, Theme.TEXT_MUTED);
        String ready = I18n.format("lagfinder.status.ready") + " - "
            + kindName(chosenSide, chosenFocus)
            + ", "
            + formatTime(chosenSeconds);
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, ready, x1 - x0 - 26), x0 + 19, y0 + 7, Theme.TEXT);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, I18n.format(hintKey()), x1 - x0 - 26),
            x0 + 19,
            y0 + 19,
            Theme.TEXT_MUTED);
    }

    /** The color of the card of what is scanned, for its progress bar. */
    private static int chosenCardColor(ScanSide side, ScanFocus focus) {
        switch (focus) {
            case METHODS:
                return Card.METHODS.color;
            case OBJECTS:
                return Card.OBJECTS.color;
            case ALLOCATIONS:
                return Card.ALLOCATIONS.color;
            default:
                return side == ScanSide.CLIENT ? Card.FPS.color : Card.TPS.color;
        }
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
