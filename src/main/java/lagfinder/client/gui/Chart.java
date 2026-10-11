package lagfinder.client.gui;

import java.util.Arrays;

import net.minecraft.client.gui.FontRenderer;

import lagfinder.client.Names;
import lagfinder.client.gui.ui.Theme;

/**
 * A graph over the time of a scan, drawn the same way in every window: a plot of moments from left to right, faint
 * grid lines, the axes' labels in the colors of what they measure, time along the bottom, and what is under the mouse
 * marked on the lines with its values beside the axes.
 */
public final class Chart {

    /** The grid lines across the plot. */
    private static final int GRID = 0x10FFFFFF;
    /** The column of the moment under the mouse. */
    private static final int HOVER_BAND = 0x12FFFFFF;
    private static final int HOVER_LINE = 0x48FFFFFF;

    private final FontRenderer font;
    /** The plot, without the axes' labels around it. */
    public final int x0, y0, x1, y1;
    /** The moments along the plot, and how wide each one is. */
    public final int buckets;
    public final double step;

    public Chart(FontRenderer font, int x0, int y0, int x1, int y1, int buckets) {
        this.font = font;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.buckets = Math.max(1, buckets);
        this.step = (x1 - x0) / (double) this.buckets;
    }

    // ---------------------------------------------------------------- Where things are

    /** The left edge of the moment's column. */
    public double left(int bucket) {
        return x0 + bucket * step;
    }

    /** The middle of the moment's column, where its point of a line is. */
    public double center(int bucket) {
        return x0 + (bucket + 0.5) * step;
    }

    /** How wide a bar is: its column with a pixel of space when there is room for it. */
    public double barWidth() {
        return Math.max(1, step - (step > 3 ? 1 : 0));
    }

    /** The height of the value on a scale from 0 to {@code max}, kept inside the plot. */
    public double y(double value, double max) {
        return y1 - (y1 - y0) * Math.max(0, Math.min(1, max <= 0 ? 0 : value / max));
    }

    /** The moment under the mouse. */
    public int bucketAt(int mouseX) {
        return Math.max(0, Math.min(buckets - 1, (int) ((mouseX - x0) / step)));
    }

    public boolean contains(int mouseX, int mouseY) {
        return Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
    }

    // ---------------------------------------------------------------- The frame

    /** The plot a little lighter at the top, faint lines across it and its base line. */
    public void grid(int lines) {
        Theme.gradient(x0, y0, x1, y1, 0x08FFFFFF, 0x00FFFFFF);
        for (int i = 1; i <= lines; i++) {
            int y = gridY(i, lines);
            // Dotted, so they stay behind the lines and bars drawn over them.
            for (int x = x0; x < x1; x += 3) {
                Theme.fill(x, y, Math.min(x1, x + 2), y + 1, GRID);
            }
        }
        Theme.fill(x0, y1, x1, y1 + 1, 0xFF333C48);
    }

    private int gridY(int line, int lines) {
        return y1 - Math.round((y1 - y0) * line / (float) lines);
    }

    /** The labels of the grid lines on the left, from the base up, in the color of what they measure. */
    public void leftAxis(String[] labels, int color) {
        int muted = Theme.blend(color, Theme.TEXT_DISABLED, 0.45);
        for (int i = 0; i < labels.length; i++) {
            int y = gridY(i, labels.length - 1);
            font.drawString(labels[i], x0 - 4 - font.getStringWidth(labels[i]), y - 4, muted);
        }
    }

    /** The labels of the grid lines on the right, from the base up, in the color of what they measure. */
    public void rightAxis(String[] labels, int color) {
        int muted = Theme.blend(color, Theme.TEXT_DISABLED, 0.45);
        for (int i = 0; i < labels.length; i++) {
            int y = gridY(i, labels.length - 1);
            font.drawString(labels[i], x1 + 4, y - 4, muted);
        }
    }

    /** Time along the bottom: as many marks as fit, each with a small tick on the base line. */
    public void timeAxis(float durationSec) {
        int marks = Math.max(2, Math.min(6, (x1 - x0) / 60));
        for (int i = 0; i <= marks; i++) {
            String label = Names.clock(durationSec * i / marks);
            int x = x0 + (x1 - x0) * i / marks;
            Theme.fill(x, y1 + 1, x + 1, y1 + 3, 0xFF333C48);
            int width = font.getStringWidth(label);
            int textX = i == 0 ? x : i == marks ? x - width + 1 : x - width / 2;
            font.drawString(label, textX, y1 + 4, Theme.TEXT_DISABLED);
        }
    }

    // ---------------------------------------------------------------- What is drawn in it

    /** A band across the plot between two values, tinting what it means (a range of good or bad rates). */
    public void zone(double from, double to, double max, int color) {
        int top = (int) Math.round(y(to, max)), bottom = (int) Math.round(y(from, max));
        if (bottom > top) {
            Theme.fill(x0, top, x1, bottom, color);
        }
    }

    /** A bar of the moment from one value up to another, colored {@code top} at its top and {@code bottom} below. */
    public void bar(int bucket, double from, double to, double max, int top, int bottom) {
        double bx = left(bucket);
        Draw.gradientRect(bx, y(to, max), bx + barWidth(), y(from, max), top, bottom);
    }

    /**
     * The values as a line with a soft area under it, each point in its color: a faint wide stroke under a crisp one
     * makes it stand out of the bars behind it. The line runs to both edges of the plot unless it is shorter.
     */
    public void line(float[] values, double max, int[] colors, float width, int areaAlpha) {
        int count = Math.min(buckets, values.length);
        if (count == 0) {
            return;
        }
        double[] xs = new double[count + 2], ys = new double[count + 2];
        int[] lineColors = new int[count + 2], tops = new int[count + 2], bottoms = new int[count + 2];
        for (int i = 0; i < count; i++) {
            xs[i + 1] = center(i);
            ys[i + 1] = y(values[i], max);
            lineColors[i + 1] = colors[i];
        }
        xs[0] = x0;
        ys[0] = ys[1];
        lineColors[0] = lineColors[1];
        xs[count + 1] = count == buckets ? x1 : left(count);
        ys[count + 1] = ys[count];
        lineColors[count + 1] = lineColors[count];
        for (int i = 0; i < count + 2; i++) {
            tops[i] = (lineColors[i] & 0x00FFFFFF) | areaAlpha << 24;
            bottoms[i] = lineColors[i] & 0x00FFFFFF;
        }
        Draw.area(xs, ys, y1, tops, bottoms);
        int[] glow = new int[count + 2];
        for (int i = 0; i < count + 2; i++) {
            glow[i] = (lineColors[i] & 0x00FFFFFF) | 0x30000000;
        }
        Draw.line(xs, ys, glow, width + 2.5f);
        Draw.line(xs, ys, lineColors, width);
    }

    /** One color for each of the points. */
    public static int[] solid(int color, int count) {
        int[] colors = new int[count];
        Arrays.fill(colors, color);
        return colors;
    }

    /** A dashed line across the plot at the value, with a label at its right end: a limit to stay under. */
    public void threshold(double value, double max, int color, String label) {
        if (value <= 0 || value >= max) {
            return;
        }
        int y = (int) Math.round(y(value, max));
        int dash = (color & 0x00FFFFFF) | 0xA0000000;
        for (int x = x0; x < x1; x += 6) {
            Theme.fill(x, y, Math.min(x1, x + 3), y + 1, dash);
        }
        if (label != null) {
            int w = font.getStringWidth(label);
            int lx = x1 - w - 4, ly = Math.max(y0 + 1, y - 10);
            Theme.fill(lx - 2, ly - 1, lx + w + 2, ly + 9, Theme.LABEL_BG);
            font.drawString(label, lx, ly, Theme.blend(color, Theme.TEXT, 0.25));
        }
    }

    /** A dashed upright line at the moment: a moment to look at, a count taken. */
    public void marker(int x, int color) {
        for (int y = y0; y < y1; y += 4) {
            Theme.fill(x, y, x + 1, Math.min(y1, y + 2), color);
        }
    }

    /** The moment picked, as a band behind all with its top lit. */
    public void picked(int bucket) {
        if (bucket < 0 || bucket >= buckets) {
            return;
        }
        int bx0 = (int) Math.floor(left(bucket)), bx1 = Math.max(bx0 + 2, (int) Math.ceil(left(bucket + 1)));
        Theme.gradient(bx0, y0, bx1, y1, 0x404C9AFF, 0x104C9AFF);
        Theme.fill(bx0, y0, bx1, y0 + 1, Theme.ACCENT);
    }

    // ---------------------------------------------------------------- What is under the mouse

    /** The column of the moment under the mouse, lit, with a thin line through its middle. */
    public void hover(int bucket) {
        int bx0 = (int) Math.floor(left(bucket)), bx1 = Math.max(bx0 + 1, (int) Math.ceil(left(bucket + 1)));
        Theme.fill(bx0, y0, bx1, y1, HOVER_BAND);
        int cx = (int) Math.round(center(bucket));
        Theme.fill(cx, y0, cx + 1, y1, HOVER_LINE);
    }

    /** A point of a line marked: a dot in its color with a dark ring, so it shows on anything under it. */
    public static void dot(double x, double y, int color) {
        Theme.disc(x + 0.5, y, 3.5, 0xFF0B0E12);
        Theme.disc(x + 0.5, y, 2.5, color | 0xFF000000);
    }

    /** The value of a point at its height beside the left axis, over its labels. */
    public void leftValue(double y, String text, int color) {
        int w = font.getStringWidth(text);
        valueLabel(x0 - w - 5, y, text, color);
    }

    /** The value of a point at its height beside the right axis, over its labels. */
    public void rightValue(double y, String text, int color) {
        valueLabel(x1 + 3, y, text, color);
    }

    private void valueLabel(int x, double y, String text, int color) {
        int w = font.getStringWidth(text);
        int ty = (int) Math.round(Math.max(y0 - 4, Math.min(y1 - 4, y - 4)));
        Theme.fill(x - 1, ty - 2, x + w + 2, ty + 9, Theme.blend(color, 0xFF0B0E12, 0.8));
        Theme.outline(x - 1, ty - 2, x + w + 2, ty + 9, Theme.blend(color, 0xFF0B0E12, 0.35));
        font.drawString(text, x + 1, ty, color);
    }

    // ---------------------------------------------------------------- The legend

    /**
     * The legend along the top of a graph: each thing drawn with a small sample of how it looks (a line, a bar, a
     * band, a mark) and its name, one after another while there is room.
     */
    public static final class Legend {

        private final FontRenderer font;
        private final int right, y;
        private int x, reserve;

        public Legend(FontRenderer font, int x, int y, int right) {
            this.font = font;
            this.x = x;
            this.y = y;
            this.right = right;
        }

        /** Keeps room for the entries after the next one, so a long name does not push them out. */
        public Legend keep(int pixels) {
            reserve = pixels;
            return this;
        }

        /** A line: what the line of the graph shows. */
        public Legend line(String text, int color, boolean main) {
            if (!room()) {
                return this;
            }
            Theme.fill(x, y + 3, x + 10, y + 5, color);
            Theme.fill(x + 4, y + 2, x + 6, y + 6, color);
            return text(14, text, main);
        }

        /** A bar: what the bars of the graph show. */
        public Legend bar(String text, int color, boolean main) {
            if (!room()) {
                return this;
            }
            Theme.gradient(x, y, x + 7, y + 8, color | 0xFF000000, Theme.blend(color | 0xFF000000, 0xFF000000, 0.4));
            return text(11, text, main);
        }

        /** A light bar with its top marked: the range the bars of the graph swing up to. */
        public Legend band(String text, int color, boolean main) {
            if (!room()) {
                return this;
            }
            Theme.fill(x, y, x + 7, y + 8, (color & 0x00FFFFFF) | 0x50000000);
            Theme.fill(x, y, x + 7, y + 1, color);
            return text(11, text, main);
        }

        /** A dashed line: a limit drawn across the graph. */
        public Legend dashed(String text, int color) {
            if (!room()) {
                return this;
            }
            Theme.fill(x, y + 4, x + 3, y + 5, color);
            Theme.fill(x + 5, y + 4, x + 8, y + 5, color);
            return text(12, text, false);
        }

        /** A small upright mark: things that happened at a moment. */
        public Legend mark(String text, int color) {
            if (!room()) {
                return this;
            }
            Theme.fill(x + 1, y + 1, x + 3, y + 7, color);
            return text(7, text, false);
        }

        private boolean room() {
            return x < right - 24;
        }

        private Legend text(int offset, String text, boolean main) {
            x += offset;
            String shown = Theme.ellipsize(font, text, Math.max(24, right - x - reserve));
            reserve = 0;
            font.drawString(shown, x, y, main ? Theme.TEXT : Theme.TEXT_MUTED);
            x += font.getStringWidth(shown) + 10;
            return this;
        }
    }
}
