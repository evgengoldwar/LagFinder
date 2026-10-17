package lagfinder.client.gui.ui;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

import net.minecraft.client.gui.FontRenderer;

import org.lwjgl.input.Mouse;

/**
 * A slider for a length of time, with marks under it at the lengths most often picked. The marks are spread evenly
 * along it and the time grows between them, so the short lengths get as much room as the long ones. Dragging lands on a
 * mark when it comes close to one, and a click on a mark's label picks it. Values past the last mark keep the knob at
 * the end.
 */
public class TimeSlider {

    /** Height taken: the track, the marks and their labels. */
    public static final int HEIGHT = 24;
    /** How close the knob must come to a mark to land on it (GUI pixels). */
    private static final int SNAP = 3;

    private final int x, y, width;
    private final int[] marks;
    private final String[] labels;
    private final IntConsumer onChange;
    public boolean enabled = true;
    public boolean round;

    private int value;
    private boolean dragging;
    private final Smooth knob = new Smooth(-1);
    private final Smooth hover = new Smooth(0);
    private IntFunction<String> formatted;
    /** The step a value is rounded to, by its size; null for the one that suits seconds. */
    private IntUnaryOperator step;

    /**
     * @param marks    the marked lengths (seconds), growing
     * @param labels   what is written under each mark
     * @param onChange told of each value the player picks
     */
    public TimeSlider(int x, int y, int width, int[] marks, String[] labels, int value, IntConsumer onChange) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.marks = marks;
        this.labels = labels;
        this.onChange = onChange;
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /** Sets the value without telling anyone (it was typed in, or set by the screen). */
    public void setValue(int value) {
        this.value = value;
    }

    private int markX(int index) {
        return x + (int) Math.round(width * index / (double) (marks.length - 1));
    }

    /** Where along the track (0 to 1) the value is. */
    private double fractionOf(int seconds) {
        if (seconds <= marks[0]) {
            return 0;
        }
        for (int i = 1; i < marks.length; i++) {
            if (seconds <= marks[i]) {
                double between = (seconds - marks[i - 1]) / (double) (marks[i] - marks[i - 1]);
                return (i - 1 + between) / (marks.length - 1);
            }
        }
        return 1;
    }

    /** The value at a place along the track (0 to 1), rounded to a step that suits its size. */
    private int valueAt(double fraction) {
        double place = Math.max(0, Math.min(1, fraction)) * (marks.length - 1);
        int i = Math.min(marks.length - 2, (int) place);
        double seconds = marks[i] + (marks[i + 1] - marks[i]) * (place - i);
        int step = this.step != null ? this.step.applyAsInt((int) seconds)
            : seconds < 60 ? 1 : seconds < 120 ? 5 : seconds < 300 ? 10 : 15;
        return (int) Math.round(seconds / step) * step;
    }

    private boolean onTrack(int mouseX, int mouseY) {
        return Theme.inside(mouseX, mouseY, x - 5, y, x + width + 5, y + 14);
    }

    /** The mark whose label is under the mouse, or -1. */
    private int labelAt(FontRenderer font, int mouseX, int mouseY) {
        if (mouseY < y + 13 || mouseY >= y + HEIGHT) {
            return -1;
        }
        for (int i = 0; i < marks.length; i++) {
            int half = font.getStringWidth(labels[i]) / 2 + 3;
            int center = labelCenter(font, i);
            if (mouseX >= center - half && mouseX < center + half) {
                return i;
            }
        }
        return -1;
    }

    /** Labels at the ends are kept inside the track's width. */
    private int labelCenter(FontRenderer font, int index) {
        int half = font.getStringWidth(labels[index]) / 2;
        return Math.max(x + half, Math.min(x + width - half, markX(index)));
    }

    /** A click: starts dragging on the track, or picks the mark whose label was clicked. Whether it was used. */
    public boolean mousePressed(FontRenderer font, int mouseX, int mouseY) {
        if (!enabled) {
            return false;
        }
        int label = labelAt(font, mouseX, mouseY);
        if (label >= 0) {
            pick(marks[label]);
            return true;
        }
        if (onTrack(mouseX, mouseY)) {
            dragging = true;
            drag(mouseX);
            return true;
        }
        return false;
    }

    private void drag(int mouseX) {
        for (int i = 0; i < marks.length; i++) {
            if (Math.abs(mouseX - markX(i)) <= SNAP) {
                pick(marks[i]);
                return;
            }
        }
        pick(valueAt((mouseX - x) / (double) width));
    }

    private void pick(int seconds) {
        if (seconds != value) {
            value = seconds;
            onChange.accept(seconds);
        }
    }

    public void draw(FontRenderer font, int mouseX, int mouseY) {
        if (dragging) {
            if (enabled && Mouse.isButtonDown(0)) {
                drag(mouseX);
            } else {
                dragging = false;
            }
        }
        double target = fractionOf(value);
        if (knob.get() < 0) {
            knob.set(target);
        }
        double shown = dragging ? target : knob.update(target, 18);
        if (dragging) {
            knob.set(target);
        }
        boolean hovered = enabled && (dragging || onTrack(mouseX, mouseY));
        double lit = hover.update(hovered ? 1 : 0, 22);

        int trackY = y + 6;
        int knobX = x + (int) Math.round(width * shown);
        int fill = enabled ? Theme.blend(Theme.ACCENT_FILL, Theme.ACCENT, lit) : Theme.BORDER;
        Theme.fill(x, trackY, x + width, trackY + 2, Theme.TRACK);
        Theme.fill(x, trackY, knobX, trackY + 2, fill);

        int under = labelAt(font, mouseX, mouseY);
        int labelsRight = Integer.MIN_VALUE;
        for (int i = 0; i < marks.length; i++) {
            int mx = markX(i);
            boolean reached = value >= marks[i];
            boolean exact = value == marks[i];
            int tick = enabled && reached ? fill : 0xFF3B4D6B;
            if (round) {
                Theme.fill(mx, trackY + 4, mx + 1, trackY + 7, tick);
            } else if (!reached || !enabled) {
                Theme.fill(mx - 1, trackY, mx + 1, trackY + 2, 0xFF3B4D6B);
            }
            int color;
            if (!enabled) {
                color = Theme.TEXT_DISABLED;
            } else if (exact) {
                color = Theme.ACCENT;
            } else if (i == under) {
                color = Theme.TEXT;
            } else {
                color = Theme.TEXT_MUTED;
            }
            int center = labelCenter(font, i);
            int labelLeft = center - font.getStringWidth(labels[i]) / 2;
            if (labelLeft >= labelsRight + 3 || exact) {
                font.drawString(labels[i], labelLeft, y + 15, color);
                labelsRight = labelLeft + font.getStringWidth(labels[i]);
            }
        }

        int knobColor = !enabled ? Theme.CONTROL_DISABLED
            : Theme.blend(Theme.ACCENT_FILL, Theme.ACCENT, 0.4 + 0.6 * lit);
        if (enabled && lit > 0.01) {
            Theme.disc(knobX + 0.5, trackY + 1, 6.5, (Theme.ACCENT & 0x00FFFFFF) | (int) Math.round(0x40 * lit) << 24);
        }
        if (round) {
            Theme.disc(knobX + 0.5, trackY + 1, 4.5, knobColor);
        } else {
            Theme.roundRect(knobX - 4, trackY - 3, knobX + 5, trackY + 5, knobColor);
        }
        if (dragging) {
            // The length being picked, over the knob.
            String text = formatted == null ? "" : formatted.apply(value);
            int w = font.getStringWidth(text) + 8;
            int bx = Math.max(x - 4, Math.min(x + width + 4 - w, knobX - w / 2));
            Theme.shadow(bx, y - 15, bx + w, y - 3, 2);
            Theme.fill(bx, y - 15, bx + w, y - 3, Theme.ACCENT_DIM);
            Theme.outline(bx, y - 15, bx + w, y - 3, Theme.ACCENT);
            font.drawString(text, bx + 4, y - 13, Theme.TEXT);
        }
    }

    /** The step values are rounded to while dragging, by their size (for a slider of something else than seconds). */
    public TimeSlider setStep(IntUnaryOperator step) {
        this.step = step;
        return this;
    }

    /** How the value is written over the knob while it is dragged. */
    public TimeSlider setFormat(IntFunction<String> formatted) {
        this.formatted = formatted;
        return this;
    }
}
