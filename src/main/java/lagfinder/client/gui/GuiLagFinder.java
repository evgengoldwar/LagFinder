package lagfinder.client.gui;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;

import lagfinder.client.ClientScanner;
import lagfinder.client.KeyHandler;
import lagfinder.client.Names;
import lagfinder.client.gui.ui.FlatButton;
import lagfinder.client.gui.ui.FlatTextField;
import lagfinder.client.gui.ui.GuiButton;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.Keyboard;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.TimeSlider;
import lagfinder.core.LagFilter;
import lagfinder.core.Report;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanSide;

/**
 * The scanner: the server's ticks, for how long (a slider, or typed in), how they are measured and what the log leaves
 * out, a start button that turns into a progress bar, and the button to the report, which opens once there is one.
 */
public class GuiLagFinder extends GuiWindow {

    private static final int ID_START = 0, ID_OPEN = 1;
    /** The buttons picking what a tick scan's log leaves out: by average time, or by the worst tick. */
    private static final int ID_FILTER_AVERAGE = 20, ID_FILTER_PEAK = 21;
    /** The buttons picking how a tick scan measures: one tick in a few (light), or every tick (full). */
    private static final int ID_MODE_LIGHT = 22, ID_MODE_FULL = 23, ID_FILTER_ALL = 24;
    /** The marks on the time slider (seconds). */
    private static final int[] MARKS = { 10, 30, 60, 120, 300, 600 };
    /** Shortest and longest scan that can be typed in (seconds); the server may allow less. */
    private static final int MIN_SECONDS = 5, MAX_SECONDS = 3600;
    private static final int CARD_HEIGHT = 70, TIME_BOX = 58;
    private static final int WHAT_HEIGHT = 20 + CARD_HEIGHT + 6, SLIDER_HEIGHT = 46, FILTER_HEIGHT = 48,
        STATUS_HEIGHT = 34, SECTION_GAP = 8;
    /** The marks on the slider of the least time a tick scan's log shows (hundredths of a millisecond). */
    private static final int[] FILTER_MARKS = { 1, 10, 50, 100, 200, 500, 1000, 5000 };
    private static final int FILTER_BUTTON = 44, SEGMENT_HEIGHT = 15, SEGMENT_PADDING = 10;
    private static final int WARN = 0xFFF2C14E;
    private static final int TPS_COLOR = Theme.ACCENT;

    private static int chosenSeconds = 30;
    /** What a tick scan's log leaves out: what took less, by average or at its worst tick; null shows everything. */
    private static LagFilter.By chosenFilter;
    /** The least time it shows (hundredths of a millisecond). */
    private static int chosenFilterHundredths = 100;
    /** A tick scan measures one tick in a few: the server barely slows down. */
    private static boolean chosenLight = true;
    /** A log closed with Esc: the key opens it again, instead of this window, so the search can go on. */
    private static Screen reopen;

    private FlatButton startButton, openButton;
    private TimeSlider slider;
    private FlatButton averageButton, peakButton;
    private FlatButton lightButton, fullButton, allButton;
    private TimeSlider filterSlider;
    private FlatTextField timeField;
    /** What was typed in the time box can't be read as a time. */
    private boolean timeInvalid;
    private final Smooth cardLight = new Smooth(0);
    private final Smooth shownProgress = new Smooth(0);
    private int whatY, durationY, filterY, statusY, buttonsY;

    public GuiLagFinder() {
        ClientScanner.INSTANCE.query();
    }

    @Override
    protected Tab tab() {
        return Tab.SCAN;
    }

    @Override
    protected void layOut() {
        whatY = contentTop;
        durationY = whatY + WHAT_HEIGHT + SECTION_GAP;
        filterY = durationY + SLIDER_HEIGHT + SECTION_GAP;
        statusY = filterY + FILTER_HEIGHT + SECTION_GAP;
        buttonsY = statusY + STATUS_HEIGHT + SECTION_GAP;

        String[] labels = new String[MARKS.length];
        for (int i = 0; i < MARKS.length; i++) {
            labels[i] = shortLabel(MARKS[i]);
        }
        int boxLeft = contentRight - 8 - TIME_BOX;
        int sliderRight = boxLeft - 16;
        slider = new TimeSlider(
            contentLeft + 14,
            durationY + 21,
            sliderRight - contentLeft - 14,
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

        int rowY = filterY + 26;
        String[] filterKeys = { "lagfinder.gui.filter_avg", "lagfinder.gui.filter_peak" };
        int filterButton = segmentWidth(FILTER_BUTTON, filterKeys);
        averageButton = segment(ID_FILTER_AVERAGE, contentLeft + 8, rowY, filterButton, filterKeys[0]);
        averageButton.outlined = true;
        peakButton = segment(ID_FILTER_PEAK, contentLeft + 8 + filterButton, rowY, filterButton, filterKeys[1]);
        peakButton.outlined = true;
        String[] modeKeys = { "lagfinder.gui.mode_light", "lagfinder.gui.mode_full", "lagfinder.gui.filter_all" };
        int modeButton = segmentWidth(30, modeKeys);
        int modeLeft = contentRight - 8 - 3 * modeButton;
        lightButton = segment(ID_MODE_LIGHT, modeLeft, rowY, modeButton, modeKeys[0]);
        fullButton = segment(ID_MODE_FULL, modeLeft + modeButton, rowY, modeButton, modeKeys[1]);
        allButton = segment(ID_FILTER_ALL, modeLeft + 2 * modeButton, rowY, modeButton, modeKeys[2]);
        String[] filterLabels = new String[FILTER_MARKS.length];
        for (int i = 0; i < FILTER_MARKS.length; i++) {
            filterLabels[i] = filterLabel(FILTER_MARKS[i]);
        }
        int filterLeft = contentLeft + 8 + 2 * filterButton + 14;
        filterSlider = new TimeSlider(
            filterLeft,
            filterY + 23,
            modeLeft - 14 - filterLeft,
            FILTER_MARKS,
            filterLabels,
            chosenFilterHundredths,
            h -> chosenFilterHundredths = h);
        filterSlider.round = true;
        filterSlider.setStep(h -> h < 100 ? 1 : h < 1000 ? 10 : 50);
        filterSlider.setFormat(h -> filterLabel(h) + " " + I18n.get("lagfinder.unit.ms"));

        int startWidth = (contentRight - contentLeft - 8) * 5 / 12;
        startButton = new FlatButton(ID_START, contentLeft, buttonsY, startWidth, 20, "");
        startButton.icon = Icons.PLAY;
        buttonList.add(startButton);
        openButton = new FlatButton(
            ID_OPEN,
            contentLeft + startWidth + 8,
            buttonsY,
            contentRight - contentLeft - startWidth - 8,
            20,
            I18n.get("lagfinder.gui.open_report"));
        openButton.icon = Icons.STATS;
        buttonList.add(openButton);
        refresh();
    }

    private int segmentWidth(int least, String[] keys) {
        int width = least;
        for (String key : keys) {
            width = Math.max(width, fontRendererObj.getStringWidth(I18n.get(key)) + SEGMENT_PADDING);
        }
        return width;
    }

    private FlatButton segment(int id, int x, int y, int width, String key) {
        FlatButton button = new FlatButton(id, x, y, width, SEGMENT_HEIGHT, I18n.get(key));
        button.segment = true;
        buttonList.add(button);
        return button;
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
        return chosenFilter != null ? new LagFilter(chosenFilter, chosenFilterHundredths / 100.0) : null;
    }

    /** A mark's label: "10s", "1m". */
    private static String shortLabel(int seconds) {
        return seconds < 60 ? seconds + I18n.get("lagfinder.unit.s") : seconds / 60 + I18n.get("lagfinder.unit.m");
    }

    /** A length as written in the time box: "45s", "2m", "1m 30s". */
    public static String formatTime(int seconds) {
        if (seconds < 60 || seconds % 60 == 0) {
            return shortLabel(seconds);
        }
        return seconds / 60 + I18n.get("lagfinder.unit.m") + " " + seconds % 60 + I18n.get("lagfinder.unit.s");
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

    /** Puts the buttons in line with the scan's state. */
    private void refresh() {
        ClientScanner scanner = ClientScanner.INSTANCE;
        boolean active = scanner.isActive();
        slider.enabled = !active;
        timeField.setEnabled(!active);
        averageButton.active = chosenFilter == LagFilter.By.AVERAGE;
        peakButton.active = chosenFilter == LagFilter.By.PEAK;
        averageButton.enabled = peakButton.enabled = !active;
        filterSlider.enabled = !active && chosenFilter != null;
        lightButton.active = chosenLight;
        fullButton.active = !chosenLight;
        allButton.active = chosenFilter == null;
        allButton.outlined = true;
        lightButton.enabled = fullButton.enabled = allButton.enabled = !active;
        if (active && timeField.isFocused()) {
            leaveTimeField();
        }
        if (active) {
            startButton.displayString = I18n.get("lagfinder.gui.cancel");
            startButton.icon = Icons.CLOSE;
            startButton.iconColor = Theme.DANGER;
            startButton.danger = true;
            startButton.active = false;
            startButton.enabled = scanner.phase() != ClientScanner.Phase.RECEIVING;
        } else {
            startButton.displayString = I18n.get("lagfinder.gui.start") + "  ·  " + formatTime(chosenSeconds);
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
        return !timeInvalid && scanner.serverAllowed() && !scanner.serverBusy();
    }

    /** What the key opens: the log closed last with Esc, or this window. */
    public static Screen toOpen() {
        Screen screen = reopen;
        reopen = null;
        return screen != null ? screen : new GuiLagFinder();
    }

    /** Closes a log to the game; the key opens it again as it was. */
    static void closeToGame(Minecraft mc, Screen log) {
        mc.setScreen(null);
        reopen = log;
    }

    /** The key opens this window again (a log was closed with its own button, or a new report came). */
    public static void forgetReopen() {
        reopen = null;
    }

    /** Opens the report in its window. */
    static void open(Minecraft mc, Report report) {
        ClientScanner.INSTANCE.markSeen();
        mc.setScreen(new GuiReport(report));
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
                scanner.start(chosenSeconds, filter(), chosenLight);
            }
        } else if (button.id == ID_OPEN && scanner.report() != null) {
            open(mc, scanner.report());
            return;
        } else if (button.id == ID_MODE_LIGHT || button.id == ID_MODE_FULL) {
            chosenLight = button.id == ID_MODE_LIGHT;
        } else if (button.id == ID_FILTER_AVERAGE || button.id == ID_FILTER_PEAK) {
            LagFilter.By by = button.id == ID_FILTER_AVERAGE ? LagFilter.By.AVERAGE : LagFilter.By.PEAK;
            chosenFilter = chosenFilter == by ? null : by;
        } else if (button.id == ID_FILTER_ALL) {
            chosenFilter = null;
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
            timeInvalid = true;
        }
        clickButtons(mouseX, mouseY, button);
        if (button != 0 || ClientScanner.INSTANCE.isActive()) {
            return;
        }
        if (slider.mousePressed(fontRendererObj, mouseX, mouseY)) {
            return;
        }
        filterSlider.mousePressed(fontRendererObj, mouseX, mouseY);
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
            pressed(startButton);
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || mc.options.keyInventory.matches(keyCode, 0)
            || KeyHandler.isOpenKey(keyCode)) {
            mc.setScreen(null);
        }
    }

    @Override
    public void updateScreen() {
        refresh();
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        refresh();
        section(contentLeft, whatY, contentRight, whatY + WHAT_HEIGHT, Icons.SCAN, I18n.get("lagfinder.gui.what"));
        drawCard(mouseX, mouseY);
        section(
            contentLeft,
            durationY,
            contentRight,
            durationY + SLIDER_HEIGHT,
            Icons.SMALL_CLOCK,
            I18n.get("lagfinder.gui.duration"));
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

    /** The filter's title, its slider and the least time it shows: grey while it is off. */
    private void drawFilter(int mouseX, int mouseY) {
        boolean on = chosenFilter != null;
        String subtitle = I18n.get("lagfinder.gui.filter");
        if (on) {
            subtitle += "  ·  " + filterLabel(chosenFilterHundredths) + " " + I18n.get("lagfinder.unit.ms");
        }
        section(
            contentLeft,
            filterY,
            contentRight,
            filterY + FILTER_HEIGHT,
            Icons.FUNNEL,
            I18n.get("lagfinder.gui.filter_title"),
            subtitle);
        segmentGroup(averageButton, peakButton);
        segmentGroup(lightButton, fullButton, allButton);
        filterSlider.draw(fontRendererObj, mouseX, mouseY);
    }

    private void segmentGroup(FlatButton... buttons) {
        FlatButton first = buttons[0], last = buttons[buttons.length - 1];
        int y0 = first.yPosition, y1 = y0 + SEGMENT_HEIGHT;
        int x1 = last.xPosition + last.getWidth();
        boolean enabled = first.enabled;
        Theme.roundBox(first.xPosition, y0, x1, y1, enabled ? Theme.CONTROL : Theme.CONTROL_DISABLED, 0xFF2A3D5C);
        for (int i = 1; i < buttons.length; i++) {
            if (!buttons[i].active && !buttons[i - 1].active) {
                Theme.fill(buttons[i].xPosition, y0 + 1, buttons[i].xPosition + 1, y1 - 1, 0xFF2A3D5C);
            }
        }
    }

    @Override
    protected void drawOver(int mouseX, int mouseY) {
        String text = tooltipText(mouseX, mouseY);
        if (text != null) {
            tooltip(text, mouseX, mouseY);
        }
    }

    private String tooltipText(int mouseX, int mouseY) {
        ClientScanner scanner = ClientScanner.INSTANCE;
        if (openButton.isMouseOver(mouseX, mouseY) && scanner.report() != null) {
            return I18n.get(scanner.hasUnseenReport() ? "lagfinder.tip.open_new" : "lagfinder.tip.open_last") + "\n"
                + summary(scanner.report());
        }
        if (averageButton.isMouseOver(mouseX, mouseY)) {
            return I18n.get("lagfinder.tip.filter_avg");
        }
        if (peakButton.isMouseOver(mouseX, mouseY)) {
            return I18n.get("lagfinder.tip.filter_peak");
        }
        if (lightButton.isMouseOver(mouseX, mouseY)) {
            return I18n.get("lagfinder.tip.mode_light");
        }
        if (fullButton.isMouseOver(mouseX, mouseY)) {
            return I18n.get("lagfinder.tip.mode_full");
        }
        if (allButton.isMouseOver(mouseX, mouseY)) {
            return I18n.get("lagfinder.tip.filter_off");
        }
        if (chosenFilter == null
            && Theme.inside(mouseX, mouseY, contentLeft + 8, filterY + 24, contentRight - 8, filterY + FILTER_HEIGHT)) {
            return I18n.get("lagfinder.tip.filter_off");
        }
        return null;
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

    /** What is scanned: the server's ticks, with what keeps it from being scanned, if anything. */
    private void drawCard(int mouseX, int mouseY) {
        int x0 = contentLeft + 6, x1 = contentRight - 6, y0 = whatY + 20, y1 = y0 + CARD_HEIGHT;
        boolean usable = ClientScanner.INSTANCE.serverAllowed();
        double lit = cardLight.update(usable && Theme.inside(mouseX, mouseY, x0, y0, x1, y1) ? 1 : 0, 22);
        int color = TPS_COLOR;
        Theme.roundBox(x0, y0, x1, y1, Theme.blend(0xFF0F2545, color, 0.06 + 0.04 * lit), color);
        Theme.roundOutline(x0 + 1, y0 + 1, x1 - 1, y1 - 1, color);
        int boxX = x1 - 15, boxY = y0 + 6;
        Theme.roundRect(boxX, boxY, boxX + 10, boxY + 10, usable ? color : Theme.TEXT_DISABLED);
        Icons.draw(Icons.SMALL_CHECK, boxX + 2, boxY + 2, Theme.TEXT);
        Icons.draw(Icons.SERVER, x0 + 9, y0 + 7, usable ? color : Theme.TEXT_DISABLED);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, I18n.get("lagfinder.gui.tps_title"), x1 - x0 - 56),
            x0 + 26,
            y0 + 8,
            usable ? Theme.TEXT : Theme.TEXT_DISABLED);
        String description = I18n.get("lagfinder.gui.tps_text");
        int descriptionColor = 0xFFA3B1C6;
        String problem = serverProblem();
        if (problem != null) {
            description = I18n.get(problem);
            descriptionColor = WARN;
        }
        int textWidth = x1 - x0 - 26 - 7;
        List<String> lines = fontRendererObj.listFormattedStringToWidth(description, textWidth);
        int room = (y1 - y0 - 19) / 9;
        for (int i = 0; i < lines.size() && i < room; i++) {
            String line = lines.get(i);
            if (i == room - 1 && lines.size() > room) {
                line = Theme.ellipsize(fontRendererObj, line + "...", textWidth);
            }
            fontRendererObj.drawString(line, x0 + 26, y0 + 19 + i * 9, descriptionColor);
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
                    title = I18n.get("lagfinder.status.starting");
                    right = "";
                    break;
                case RECEIVING:
                    title = I18n.get("lagfinder.status.receiving");
                    right = Math.round(progress * 100) + "%";
                    break;
                default:
                    title = I18n.get("lagfinder.status.running_tps");
                    right = I18n.get("lagfinder.status.left", Names.clock(scanner.secondsLeft()));
            }
            Icons.draw(Icons.SMALL_CLOCK, x0 + 7, y0 + 7, Theme.ACCENT);
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
            Theme.fill(barX0, barY, barX1, barY + 8, 0xFF0A1424);
            int filled = barX0 + (int) Math.round((barX1 - barX0) * Math.max(0, Math.min(1, shown)));
            Draw.gradientRect(
                barX0,
                barY,
                filled,
                barY + 8,
                Theme.blend(TPS_COLOR, 0xFFFFFFFF, 0.3),
                Theme.blend(TPS_COLOR, 0xFF000000, 0.35));
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
                I18n.get("lagfinder.status.bad_time", formatTime(MIN_SECONDS), formatTime(MAX_SECONDS)),
                x0,
                y0,
                x1);
            return;
        }
        if (serverProblem() != null) {
            warning(I18n.get(serverProblem()), x0, y0, x1);
            return;
        }
        int serverMax = scanner.serverMaxSeconds();
        if (serverMax > 0 && chosenSeconds > serverMax) {
            warning(I18n.get("lagfinder.status.server_max", formatTime(serverMax)), x0, y0, x1);
            return;
        }
        Report report = scanner.report();
        if (report != null && scanner.hasUnseenReport()) {
            String line = summary(report);
            Theme.checkCircle(x0 + 8, y0 + 7, Theme.SUCCESS, 0xFF0C1729);
            fontRendererObj.drawString(I18n.get("lagfinder.status.done"), x0 + 25, y0 + 7, Theme.SUCCESS);
            fontRendererObj
                .drawString(Theme.ellipsize(fontRendererObj, line, x1 - x0 - 32), x0 + 25, y0 + 19, Theme.TEXT_MUTED);
            return;
        }
        Theme.checkCircle(x0 + 8, y0 + 7, Theme.ACCENT, 0xFF0C1729);
        String ready = I18n.get("lagfinder.status.ready");
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, ready, x1 - x0 - 32), x0 + 25, y0 + 7, Theme.ACCENT);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, I18n.get("lagfinder.status.hint_tps"), x1 - x0 - 32),
            x0 + 25,
            y0 + 19,
            Theme.TEXT_MUTED);
    }

    private void warning(String text, int x0, int y0, int x1) {
        Icons.draw(Icons.SMALL_WARN, x0 + 7, y0 + 7, WARN);
        List<String> lines = fontRendererObj.listFormattedStringToWidth(text, x1 - x0 - 30);
        for (int i = 0; i < lines.size() && i < 2; i++) {
            fontRendererObj.drawString(lines.get(i), x0 + 19, y0 + 7 + i * 11, WARN);
        }
    }

    /** What a scan was: "Tick scan". */
    public static String kindName(ScanSide where, ScanFocus focus) {
        return I18n.get(where == ScanSide.CLIENT ? "lagfinder.mode.fps" : "lagfinder.mode.tps");
    }

    /** One line about a report: what it was, its length and rate. */
    static String summary(Report report) {
        return String.format(
            "%s · %s · TPS %.1f · min %.1f",
            kindName(report.side(), report.focus),
            Names.clock(report.durationSec),
            report.averageRate(),
            report.minimumRate());
    }
}
