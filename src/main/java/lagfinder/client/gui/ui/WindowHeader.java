package lagfinder.client.gui.ui;

import net.minecraft.client.gui.FontRenderer;

/**
 * The header across the top of a window, the same on every screen of the mod: the window's icon in the accent color,
 * its title with a muted line under it, an optional pill on the right (a count, a color, a size) and a close button,
 * over a line with an accent part under the title.
 */
public final class WindowHeader {

    /** Height of the header, line included: a window's content starts under it. */
    public static final int HEIGHT = 30;
    /** Width taken on the right by the close button, with its margin. */
    public static final int CLOSE_ROOM = 30;

    /** Scrolling of a subtitle too long for its room: speed (GUI pixels a second) and the pause at each end (ms). */
    private static final double SCROLL_SPEED = 18;
    private static final long SCROLL_PAUSE_MS = 1500;

    private WindowHeader() {}

    /**
     * The line under the title. When it is longer than its room it isn't cut short: it slides slowly to its end and
     * back, pausing at each end, so all of it can be read.
     */
    private static void drawSubtitle(FontRenderer font, String text, int x, int y, int room, int color) {
        int overflow = font.getStringWidth(text) - room;
        if (overflow <= 0) {
            font.drawString(text, x, y, color);
            return;
        }
        long travel = Math.round(overflow / SCROLL_SPEED * 1000);
        long cycle = 2 * (SCROLL_PAUSE_MS + travel);
        long t = System.currentTimeMillis() % cycle;
        double shift;
        if (t < SCROLL_PAUSE_MS) {
            shift = 0;
        } else if (t < SCROLL_PAUSE_MS + travel) {
            shift = (t - SCROLL_PAUSE_MS) / (double) travel;
        } else if (t < 2 * SCROLL_PAUSE_MS + travel) {
            shift = 1;
        } else {
            shift = 1 - (t - 2 * SCROLL_PAUSE_MS - travel) / (double) travel;
        }
        // Eased at both ends of each way, so it starts and stops softly.
        shift = shift * shift * (3 - 2 * shift);
        Theme.clip(x, y - 1, x + room, y + 9);
        font.drawString(text, x - (int) Math.round(shift * overflow), y, color);
        Theme.unclip();
    }

    /**
     * The close button for the header of a window ending at {@code right}; give it the id of the window's cancel or
     * done button, so it does the same.
     */
    public static IconButton closeButton(int id, int right, int top) {
        IconButton button = new IconButton(id, right - 26, top + 7, Icons.CLOSE, "");
        button.bare = true;
        return button;
    }

    /**
     * Draws the header over the top of the panel ({@code left} to {@code right} at {@code top}).
     *
     * @param textRight     where the title, the subtitle and the pill must end: left of the buttons in the header
     * @param subtitle      the line under the title, or null for none
     * @param subtitleColor its color, {@link Theme#TEXT_MUTED} normally
     * @param pill          short text in a pill on the right, or null for none
     */
    public static void draw(FontRenderer font, int left, int top, int right, int textRight, String[] icon, String title,
        String subtitle, int subtitleColor, String pill) {
        Theme.fill(left + 1, top + 1, right - 1, top + HEIGHT - 1, Theme.WINDOW);
        Theme.fill(left + 1, top + HEIGHT - 1, right - 1, top + HEIGHT, Theme.BORDER);

        Theme.roundBox(left + 6, top + 6, left + 24, top + 24, 0xFF13305C, 0xFF3B7FE0);
        int iconX = left + 15 - Icons.width(icon) / 2, iconY = top + 15 - icon.length / 2;
        Icons.draw(icon, iconX, iconY, 0xFF6DB2FF);

        int textLeft = left + 30;
        if (pill != null) {
            int pillWidth = font.getStringWidth(pill) + 10;
            int pillLeft = textRight - 4 - pillWidth;
            Theme.pill(font, pill, pillLeft, top + 9, Theme.TEXT_MUTED);
            textRight = pillLeft;
        }
        int room = Math.max(0, textRight - 6 - textLeft);
        String shownTitle = Theme.ellipsize(font, title, room);
        int titleY = subtitle == null ? top + 11 : top + 6;
        font.drawStringWithShadow(shownTitle, textLeft, titleY, Theme.TEXT);
        if (subtitle != null) {
            drawSubtitle(font, subtitle, textLeft, top + 17, room, subtitleColor);
        }
    }
}
