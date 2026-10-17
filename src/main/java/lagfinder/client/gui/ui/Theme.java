package lagfinder.client.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import org.lwjgl.opengl.GL11;

import lagfinder.client.gui.Draw;

/** Colors and drawing helpers of the flat, minimal look shared by all LagFinder screens. */
public final class Theme {

    public static final int SCREEN_DIM = 0xC0050A12;
    public static final int PANEL = 0xF00D1728;
    public static final int PANEL_ALT = 0xF0122036;
    public static final int BORDER = 0xFF1F3150;
    public static final int CONTROL = 0xFF111F35;
    public static final int CONTROL_HOVER = 0xFF182B4A;
    public static final int CONTROL_DISABLED = 0xFF0E1829;
    public static final int ACCENT = 0xFF4D9CFF;
    public static final int ACCENT_DIM = 0xFF1F6FE0;
    public static final int DANGER = 0xFFE5534B;
    public static final int SUCCESS = 0xFF3FB950;
    public static final int TEXT = 0xFFE6EDF7;
    public static final int TEXT_MUTED = 0xFF8D9DB5;
    public static final int TEXT_DISABLED = 0xFF4B5B74;
    public static final int ROW_HOVER = 0x20FFFFFF;
    public static final int LABEL_BG = 0xC00A1322;
    public static final int WARN = 0xFFF2C14E;
    public static final int ORANGE = 0xFFFF8C42;
    /** The inside of the boxes in a window (lists, graphs, details), a little darker than the window. */
    public static final int CARD = 0xFF0B1526;
    /** The line picked in a list. */
    public static final int SELECTED = 0xFF15294A;
    /** A faint light along the top edge of raised things, as if lit from above. */
    public static final int SHEEN = 0x10FFFFFF;
    public static final int FRAME = 0xFF243650;
    public static final int WINDOW = 0xFF0A1322;
    public static final int CONTENT = 0xFF0C1729;
    public static final int BOX = 0xFF0F1B2F;
    public static final int BOX_BORDER = 0xFF1C2D47;
    public static final int ACCENT_FILL = 0xFF2F7CF0;
    public static final int ACCENT_EDGE = 0xFF4A90FF;
    public static final int TRACK = 0xFF1C2C46;

    private Theme() {}

    public static void fill(int x0, int y0, int x1, int y1, int color) {
        Gui.drawRect(x0, y0, x1, y1, color);
    }

    /** One pixel outline inside the rectangle. */
    public static void outline(int x0, int y0, int x1, int y1, int color) {
        fill(x0, y0, x1, y0 + 1, color);
        fill(x0, y1 - 1, x1, y1, color);
        fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    /** A window: a soft shadow around it, its body a little lighter at the top, and a thin border. */
    public static void panel(int x0, int y0, int x1, int y1) {
        shadow(x0, y0, x1, y1, 6);
        roundRect(x0, y0, x1, y1, 0xF60B1424);
        roundOutline(x0, y0, x1, y1, FRAME);
    }

    /** A box inside a window (a list, a graph, details): sunk a little, with a thin border. */
    public static void card(int x0, int y0, int x1, int y1) {
        roundRect(x0, y0, x1, y1, BOX);
        roundOutline(x0, y0, x1, y1, BOX_BORDER);
    }

    public static void roundRect(int x0, int y0, int x1, int y1, int color) {
        fill(x0 + 1, y0, x1 - 1, y1, color);
        fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    public static void roundOutline(int x0, int y0, int x1, int y1, int color) {
        fill(x0 + 1, y0, x1 - 1, y0 + 1, color);
        fill(x0 + 1, y1 - 1, x1 - 1, y1, color);
        fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    public static void roundBox(int x0, int y0, int x1, int y1, int background, int border) {
        roundRect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, background);
        roundOutline(x0, y0, x1, y1, border);
    }

    public static void checkCircle(int x, int y, int color, int mark) {
        disc(x + 5, y + 5, 5.5, color);
        Icons.draw(Icons.SMALL_CHECK, x + 2, y + 2, mark);
    }

    /** A shadow fading out {@code size} GUI pixels around the rectangle, darkest at its edges. */
    public static void shadow(int x0, int y0, int x1, int y1, int size) {
        for (int i = size; i >= 1; i--) {
            int alpha = (int) Math.round(0x38 * Math.pow(1 - (i - 1) / (double) size, 2));
            int color = alpha << 24;
            // Each ring one pixel wide, so the corners stay square and nothing is drawn twice.
            fill(x0 - i, y0 - i, x1 + i, y0 - i + 1, color);
            fill(x0 - i, y1 + i - 1, x1 + i, y1 + i, color);
            fill(x0 - i, y0 - i + 1, x0 - i + 1, y1 + i - 1, color);
            fill(x1 + i - 1, y0 - i + 1, x1 + i, y1 + i - 1, color);
        }
    }

    /** A soft light around the rectangle in the color, {@code strength} 0 (none) to 1. */
    public static void glow(int x0, int y0, int x1, int y1, int color, double strength) {
        if (strength <= 0.01) {
            return;
        }
        int rgb = color & 0x00FFFFFF;
        outline(x0 - 1, y0 - 1, x1 + 1, y1 + 1, rgb | (int) Math.round(0x70 * strength) << 24);
        outline(x0 - 2, y0 - 2, x1 + 2, y1 + 2, rgb | (int) Math.round(0x28 * strength) << 24);
    }

    /** A rectangle colored {@code top} at its top and {@code bottom} at its bottom. */
    public static void gradient(int x0, int y0, int x1, int y1, int top, int bottom) {
        if (top == bottom) {
            fill(x0, y0, x1, y1, top);
            return;
        }
        Draw.gradientRect(x0, y0, x1, y1, top, bottom);
    }

    /**
     * A small rounded pill with text, its border in the color: a mod, a count, a state. Returns its width.
     */
    public static int pill(FontRenderer font, String text, int x, int y, int color) {
        int width = font.getStringWidth(text) + 10;
        fill(x + 1, y, x + width - 1, y + 12, CONTROL);
        fill(x, y + 1, x + width, y + 11, CONTROL);
        int edge = blend(color, CONTROL, 0.35);
        fill(x + 1, y, x + width - 1, y + 1, edge);
        fill(x + 1, y + 11, x + width - 1, y + 12, edge);
        fill(x, y + 1, x + 1, y + 11, edge);
        fill(x + width - 1, y + 1, x + width, y + 11, edge);
        font.drawString(text, x + 5, y + 2, color);
        return width;
    }

    /** A horizontal bar filled to {@code fraction} (0 to 1) in the color, on a dark track. */
    public static void meter(int x0, int y0, int x1, int y1, double fraction, int color) {
        fill(x0, y0, x1, y1, 0xFF0B0E12);
        int filled = x0 + (int) Math.round((x1 - x0) * Math.max(0, Math.min(1, fraction)));
        if (filled > x0) {
            gradient(x0, y0, filled, y1, blend(color, 0xFFFFFFFF, 0.15), blend(color, 0xFF000000, 0.25));
        }
    }

    /**
     * One bar split into parts by their share, each in its color, a thin gap between them: who takes how much at a
     * glance. Returns where each part starts and, last, where the bar ends (for the parts' tooltips and clicks).
     */
    public static int[] stackedBar(int x0, int y0, int x1, int y1, double[] values, int[] colors) {
        double total = 0;
        for (double value : values) {
            total += Math.max(0, value);
        }
        fill(x0, y0, x1, y1, 0xFF0B0E12);
        int[] starts = new int[values.length + 1];
        double at = x0;
        for (int i = 0; i < values.length; i++) {
            starts[i] = (int) Math.round(at);
            at += total <= 0 ? 0 : (x1 - x0) * Math.max(0, values[i]) / total;
            int end = (int) Math.round(at);
            if (end - starts[i] >= 1) {
                int right = end - starts[i] >= 3 ? end - 1 : end;
                gradient(
                    starts[i],
                    y0,
                    right,
                    y1,
                    blend(colors[i], 0xFFFFFFFF, 0.12),
                    blend(colors[i], 0xFF000000, 0.3));
            }
        }
        starts[values.length] = (int) Math.round(at);
        return starts;
    }

    public static void text(FontRenderer font, String text, int x, int y, int color) {
        font.drawString(text, x, y, color);
    }

    public static void centered(FontRenderer font, String text, int centerX, int y, int color) {
        font.drawString(text, centerX - font.getStringWidth(text) / 2, y, color);
    }

    /** Cuts the text to {@code maxWidth} pixels, ending with "..." when it doesn't fit. */
    public static String ellipsize(FontRenderer font, String text, int maxWidth) {
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String dots = "...";
        return font.trimStringToWidth(text, Math.max(0, maxWidth - font.getStringWidth(dots))) + dots;
    }

    public static boolean inside(int mouseX, int mouseY, int x0, int y0, int x1, int y1) {
        return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
    }

    /** The color between {@code from} (t = 0) and {@code to} (t = 1), alpha included. */
    public static int blend(int from, int to, double t) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = (from >>> shift) & 0xFF, b = (to >>> shift) & 0xFF;
            result |= (int) Math.round(a + (b - a) * t) << shift;
        }
        return result;
    }

    /**
     * Draws only inside the rectangle (GUI pixels of the screen being drawn) until {@link #unclip}: a scrolled list
     * is cut at its edges instead of rows popping in and out.
     */
    public static void clip(int x0, int y0, int x1, int y1) {
        int factor = ScaledScreen.currentFactor();
        int displayHeight = Minecraft.getMinecraft().displayHeight;
        // The scissor is in window pixels: the screen's slide while it opens is not applied to it.
        int offset = ScaledScreen.currentOffset();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x0 * factor, displayHeight - (y1 + offset) * factor, (x1 - x0) * factor, (y1 - y0) * factor);
    }

    public static void unclip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /**
     * The color of an icon that has a color of its own: full when lit (hovered, on or chosen), brighter on an accent
     * background, a darker shade of it when dimmed (off, not chosen), so it can still be told apart.
     */
    public static int iconShade(int color, boolean lit, boolean onAccent, boolean dimmed) {
        if (onAccent) {
            return blend(color, 0xFFFFFFFF, 0.35);
        }
        if (dimmed && !lit) {
            return blend(color, PANEL | 0xFF000000, 0.55);
        }
        return color;
    }

    /** A filled circle, one rectangle per row of pixels. */
    public static void disc(double centerX, double centerY, double radius, int color) {
        int top = (int) Math.floor(centerY - radius), bottom = (int) Math.ceil(centerY + radius);
        for (int y = top; y < bottom; y++) {
            double dy = y + 0.5 - centerY;
            double reach = Math.sqrt(Math.max(0, radius * radius - dy * dy));
            int from = (int) Math.round(centerX - reach), to = (int) Math.round(centerX + reach);
            if (to > from) {
                fill(from, y, to, y + 1, color);
            }
        }
    }

    /** A thin scrollbar: its track, and the thumb lit while {@code lit}; {@code position} is 0 at the top to 1. */
    public static void scrollbar(int x, int y0, int y1, int visible, int total, double position, boolean lit) {
        int track = y1 - y0;
        int bar = Math.max(12, track * visible / Math.max(visible, total));
        int barY = y0 + (int) Math.round((track - bar) * Math.max(0, Math.min(1, position)));
        fill(x, y0, x + 2, y1, 0x60000000);
        fill(x, barY, x + 2, barY + bar, lit ? ACCENT : 0xFF3A4350);
    }
}
