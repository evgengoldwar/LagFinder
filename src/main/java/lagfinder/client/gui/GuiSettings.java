package lagfinder.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;

import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import lagfinder.Config;
import lagfinder.client.KeyHandler;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;

/**
 * The mod's settings, as in its config file, changed in the game and saved at once: how scans measure, how memory is
 * counted, how long a highlight stays, and what the players of this world may do. A number is stepped through the
 * values that make sense for it, a switch turns a setting on and off.
 */
public class GuiSettings extends GuiWindow {

    private static final int ROW = 28, HEADER = 18, STEP = 14, VALUE = 58, SWITCH = 24;
    /** The note at the bottom: where the settings are kept, and which apply on a server. */
    private static final int NOTE = 14;

    /** One line of the list: a heading, a number stepped through values, or a switch. */
    private static final class Entry {

        final String key;
        final String[] icon;
        /** The values a number steps through, growing; null for a switch, and for a heading. */
        final double[] steps;
        final DoubleSupplier get;
        final DoubleConsumer set;
        final DoubleFunction<String> format;
        final boolean toggle;
        final Smooth light = new Smooth(0), knob = new Smooth(-1);

        private Entry(String key, String[] icon, double[] steps, DoubleSupplier get, DoubleConsumer set,
            DoubleFunction<String> format, boolean toggle) {
            this.key = key;
            this.icon = icon;
            this.steps = steps;
            this.get = get;
            this.set = set;
            this.format = format;
            this.toggle = toggle;
        }

        static Entry heading(String key, String[] icon) {
            return new Entry(key, icon, null, null, null, null, false);
        }

        static Entry number(String key, double[] steps, DoubleSupplier get, DoubleConsumer set,
            DoubleFunction<String> format) {
            return new Entry(key, null, steps, get, set, format, false);
        }

        static Entry toggle(String key, DoubleSupplier get, DoubleConsumer set) {
            return new Entry(key, null, null, get, set, null, true);
        }

        boolean isHeading() {
            return get == null;
        }

        int height() {
            return isHeading() ? HEADER : ROW;
        }

        /** The next value up (or down), or NaN at the end. */
        double step(int direction) {
            double now = get.getAsDouble();
            if (direction > 0) {
                for (double step : steps) {
                    if (step > now + 1e-9) {
                        return step;
                    }
                }
            } else {
                for (int i = steps.length - 1; i >= 0; i--) {
                    if (steps[i] < now - 1e-9) {
                        return steps[i];
                    }
                }
            }
            return Double.NaN;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int listTop, listBottom;
    private int scroll;
    private final Smooth shownScroll = new Smooth(0);
    /** The whole description of the line under the mouse, when it is cut short. */
    private String hoveredText;

    public GuiSettings() {
        String ms = " " + I18n.format("lagfinder.unit.ms");
        DoubleFunction<String> millis = v -> number(v) + ms;
        DoubleFunction<String> seconds = v -> GuiLagFinder.formatTime((int) v);
        entries.add(Entry.heading("lagfinder.settings.scan", Icons.SMALL_SPIKE));
        entries.add(
            Entry.number(
                "lagfinder.settings.spike_tps",
                new double[] { 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 25, 50, 100 },
                () -> Config.spikeTpsMs,
                v -> Config.spikeTpsMs = (float) v,
                millis));
        entries.add(
            Entry.number(
                "lagfinder.settings.spike_fps",
                new double[] { 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 25, 50, 100 },
                () -> Config.spikeFpsMs,
                v -> Config.spikeFpsMs = (float) v,
                millis));
        entries.add(
            Entry.number(
                "lagfinder.settings.sample_interval",
                new double[] { 1, 2, 3, 5, 10, 20, 50, 100 },
                () -> Config.sampleIntervalMs,
                v -> Config.sampleIntervalMs = (int) v,
                millis));
        entries.add(Entry.heading("lagfinder.settings.memory", Icons.SMALL_MEMORY));
        entries.add(
            Entry.number(
                "lagfinder.settings.heap_snapshots",
                new double[] { 2, 3, 4, 5, 6, 8, 10, 15, 20 },
                () -> Config.heapSnapshots,
                v -> Config.heapSnapshots = (int) v,
                GuiSettings::number));
        entries.add(
            Entry.toggle(
                "lagfinder.settings.heap_live",
                () -> Config.heapLiveOnly ? 1 : 0,
                v -> Config.heapLiveOnly = v != 0));
        entries.add(
            Entry.number(
                "lagfinder.settings.alloc_rate",
                new double[] { 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000 },
                () -> Config.allocSampleRate,
                v -> Config.allocSampleRate = (int) v,
                v -> number(v) + I18n.format("lagfinder.settings.per_second")));
        entries.add(Entry.heading("lagfinder.settings.client", Icons.SMALL_EYE));
        entries.add(
            Entry.number(
                "lagfinder.settings.highlight",
                new double[] { 5, 10, 30, 60, 120, 300, 600, 1800, 3600 },
                () -> Config.highlightSeconds,
                v -> Config.highlightSeconds = (int) v,
                seconds));
        entries.add(Entry.heading("lagfinder.settings.server", Icons.SMALL_GRID));
        entries.add(
            Entry.toggle(
                "lagfinder.settings.require_op",
                () -> Config.requireOp ? 1 : 0,
                v -> Config.requireOp = v != 0));
        entries.add(
            Entry.toggle(
                "lagfinder.settings.allow_teleport",
                () -> Config.allowTeleport ? 1 : 0,
                v -> Config.allowTeleport = v != 0));
        entries.add(
            Entry.number(
                "lagfinder.settings.max_scan",
                new double[] { 30, 60, 120, 300, 600, 1200, 1800, 3600 },
                () -> Config.maxScanSeconds,
                v -> Config.maxScanSeconds = (int) v,
                seconds));
    }

    /** A number without the zeros after its point: "2.5", "10". */
    private static String number(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    @Override
    protected Tab tab() {
        return Tab.SETTINGS;
    }

    @Override
    protected void layOut() {
        listTop = contentTop;
        listBottom = contentBottom - NOTE;
        clampScroll();
    }

    private int contentHeight() {
        int total = 0;
        for (Entry entry : entries) {
            total += entry.height();
        }
        return total + 4;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, contentHeight() - (listBottom - listTop)));
    }

    /** Where each entry starts, as now scrolled. */
    private int entryY(int index, int shown) {
        int y = listTop + 2 - shown;
        for (int i = 0; i < index; i++) {
            y += entries.get(i)
                .height();
        }
        return y;
    }

    private int valueRight() {
        return contentRight - 10;
    }

    /** The parts of a number's line: the step down, the value, the step up. */
    private int downX() {
        return valueRight() - STEP - VALUE - STEP;
    }

    private int upX() {
        return valueRight() - STEP;
    }

    private int switchX() {
        return valueRight() - SWITCH;
    }

    @Override
    protected void clicked(int mouseX, int mouseY, int button) {
        clickButtons(mouseX, mouseY, button);
        if (button != 0 || !Theme.inside(mouseX, mouseY, contentLeft, listTop, contentRight, listBottom)) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.isHeading()) {
                continue;
            }
            int y = entryY(i, scroll);
            if (mouseY < y || mouseY >= y + ROW) {
                continue;
            }
            if (entry.toggle) {
                // Anywhere on its line.
                entry.set.accept(entry.get.getAsDouble() != 0 ? 0 : 1);
                changed();
                return;
            }
            int controlY = y + (ROW - 14) / 2;
            if (mouseY < controlY || mouseY >= controlY + 14) {
                return;
            }
            int direction = mouseX >= downX() && mouseX < downX() + STEP ? -1
                : mouseX >= upX() && mouseX < upX() + STEP ? 1 : 0;
            if (direction != 0) {
                double next = entry.step(direction);
                if (!Double.isNaN(next)) {
                    entry.set.accept(next);
                    changed();
                }
            }
            return;
        }
    }

    private void changed() {
        Config.save();
        mc.getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
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
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == mc.gameSettings.keyBindInventory.getKeyCode()
            || keyCode == KeyHandler.OPEN.getKeyCode()) {
            closeWindow();
        }
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        int x0 = contentLeft, x1 = contentRight;
        Theme.card(x0, listTop, x1, listBottom);
        int shown = (int) Math.round(shownScroll.update(scroll, 18));
        Theme.clip(x0 + 1, listTop + 1, x1 - 1, listBottom - 1);
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int y = entryY(i, shown);
            if (y + entry.height() < listTop || y > listBottom) {
                continue;
            }
            if (entry.isHeading()) {
                drawHeading(entry, y, i == 0);
            } else {
                drawEntry(entry, y, mouseX, mouseY);
            }
        }
        Theme.unclip();
        int total = contentHeight(), visible = listBottom - listTop;
        if (total > visible) {
            Theme.scrollbar(
                x1 - 4,
                listTop + 2,
                listBottom - 2,
                visible,
                total,
                shown / (double) (total - visible),
                Theme.inside(mouseX, mouseY, x0, listTop, x1, listBottom));
        }
        String note = Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.settings.note"), x1 - x0 - 12);
        Icons.draw(Icons.SMALL_FOLDER, x0 + 1, contentBottom - 8, Theme.TEXT_DISABLED);
        fontRendererObj.drawString(note, x0 + 12, contentBottom - 8, Theme.TEXT_DISABLED);
    }

    private void drawHeading(Entry entry, int y, boolean first) {
        int x0 = contentLeft + 8;
        if (!first) {
            Theme.fill(contentLeft + 6, y, contentRight - 6, y + 1, 0xFF1C2129);
        }
        Icons.draw(entry.icon, x0, y + 6, Theme.ACCENT);
        String title = I18n.format(entry.key)
            .toUpperCase();
        fontRendererObj.drawString(title, x0 + Icons.width(entry.icon) + 5, y + 6, Theme.ACCENT);
    }

    private void drawEntry(Entry entry, int y, int mouseX, int mouseY) {
        boolean hovered = Theme.inside(mouseX, mouseY, contentLeft + 1, y, contentRight - 1, y + ROW)
            && Theme.inside(mouseX, mouseY, contentLeft, listTop, contentRight, listBottom);
        double lit = entry.light.update(hovered ? 1 : 0, 22);
        if (lit > 0.01) {
            Theme.fill(contentLeft + 1, y, contentRight - 1, y + ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        int textX = contentLeft + 14;
        int room = (entry.toggle ? switchX() : downX()) - 10 - textX;
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, I18n.format(entry.key), room),
            textX,
            y + 5,
            Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.7 + 0.3 * lit));
        String text = I18n.format(entry.key + "_text");
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, text, room), textX, y + 15, Theme.TEXT_DISABLED);
        int controlY = y + (ROW - 14) / 2;
        if (entry.toggle) {
            drawSwitch(entry, controlY, hovered);
        } else {
            drawStepper(entry, controlY, mouseX, mouseY);
        }
        if (hovered && fontRendererObj.getStringWidth(text) > room
            && Theme.inside(mouseX, mouseY, textX, y, textX + room, y + ROW)) {
            hoveredText = I18n.format(entry.key) + "\n" + text;
        }
    }

    private void drawSwitch(Entry entry, int y, boolean hovered) {
        boolean on = entry.get.getAsDouble() != 0;
        double at = entry.knob.update(on ? 1 : 0, 18);
        int x0 = switchX(), x1 = x0 + SWITCH, y1 = y + 14;
        int track = Theme.blend(Theme.CONTROL, Theme.ACCENT_DIM, at);
        Theme.fill(x0 + 1, y, x1 - 1, y1, track);
        Theme.fill(x0, y + 1, x1, y1 - 1, track);
        Theme.outline(
            x0,
            y,
            x1,
            y1,
            Theme.blend(hovered ? Theme.blend(Theme.BORDER, Theme.ACCENT, 0.5) : Theme.BORDER, Theme.ACCENT, at));
        int knobX = x0 + 2 + (int) Math.round(at * (SWITCH - 14));
        Theme.fill(knobX, y + 2, knobX + 10, y1 - 2, Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, at));
    }

    private void drawStepper(Entry entry, int y, int mouseX, int mouseY) {
        int y1 = y + 14;
        boolean canDown = !Double.isNaN(entry.step(-1)), canUp = !Double.isNaN(entry.step(1));
        stepButton(downX(), y, y1, Icons.BACK, canDown, mouseX, mouseY);
        stepButton(upX(), y, y1, Icons.SECTION_CLOSED, canUp, mouseX, mouseY);
        int vx0 = downX() + STEP, vx1 = upX();
        Theme.fill(vx0, y, vx1, y1, 0xFF0F1216);
        Theme.fill(vx0, y, vx1, y + 1, Theme.BORDER);
        Theme.fill(vx0, y1 - 1, vx1, y1, Theme.BORDER);
        String value = Theme.ellipsize(fontRendererObj, entry.format.apply(entry.get.getAsDouble()), VALUE - 4);
        Theme.centered(fontRendererObj, value, (vx0 + vx1) / 2, y + 3, Theme.TEXT);
    }

    private void stepButton(int x, int y0, int y1, String[] icon, boolean enabled, int mouseX, int mouseY) {
        boolean over = enabled && Theme.inside(mouseX, mouseY, x, y0, x + STEP, y1)
            && Theme.inside(mouseX, mouseY, contentLeft, listTop, contentRight, listBottom);
        Theme.fill(x, y0, x + STEP, y1, !enabled ? Theme.CONTROL_DISABLED : over ? Theme.CONTROL_HOVER : Theme.CONTROL);
        Theme.outline(x, y0, x + STEP, y1, over ? Theme.ACCENT : Theme.BORDER);
        Icons.draw(
            icon,
            x + (STEP - Icons.width(icon)) / 2,
            y0 + (y1 - y0 - icon.length) / 2,
            !enabled ? Theme.TEXT_DISABLED : over ? Theme.TEXT : Theme.TEXT_MUTED);
    }

    @Override
    protected void drawOver(int mouseX, int mouseY) {
        if (hoveredText != null) {
            tooltip(hoveredText, mouseX, mouseY);
            hoveredText = null;
        }
        if (Theme.inside(mouseX, mouseY, contentLeft, contentBottom - 10, contentRight, contentBottom)) {
            tooltip(I18n.format("lagfinder.settings.note_tip"), mouseX, mouseY);
        }
    }
}
