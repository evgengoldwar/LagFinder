package lagfinder.client.gui.ui;

import net.minecraft.client.Minecraft;
import lagfinder.client.gui.ui.GuiButton;

/** Flat button without the vanilla stone texture. */
public class FlatButton extends GuiButton {

    /** Highlighted with the accent color, e.g. a toggle that is on or the selected tab. */
    public boolean active;
    /** Red text, for destructive actions. */
    public boolean danger;
    /** Drawn left of the text, which then starts after it instead of being centered; null for none. */
    public String[] icon;
    /** The icon's own color (ARGB), 0 to draw it in the text colors. */
    public int iconColor;
    /**
     * Width kept for the icon, 0 for its own: buttons in a column with icons of different widths give them all the
     * widest one's, so the icons are centered on one line and the text starts at the same place.
     */
    public int iconSlot;
    public boolean segment;
    public boolean outlined;
    /** How lit the button is by the mouse, fading in and out. */
    private final Smooth hover = new Smooth(0);

    public FlatButton(int id, int x, int y, int width, int height, String text) {
        super(id, x, y, width, height, text);
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        return visible && Theme.inside(mouseX, mouseY, xPosition, yPosition, xPosition + width, yPosition + height);
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        boolean hovered = enabled && isMouseOver(mouseX, mouseY);
        drawBackground(hovered);

        int color = !enabled ? Theme.TEXT_DISABLED
            : danger ? Theme.DANGER : active && outlined ? 0xFF6FB0FF : Theme.TEXT;
        if (icon != null) {
            int shade;
            if (!enabled) {
                shade = Theme.TEXT_DISABLED;
            } else if (iconColor != 0) {
                shade = Theme.iconShade(iconColor, hovered, active, false);
            } else {
                shade = hovered || active ? Theme.TEXT : Theme.TEXT_MUTED;
            }
            int iconWidth = Icons.width(icon);
            int slot = Math.max(iconSlot, iconWidth);
            Icons.draw(icon, xPosition + 6 + (slot - iconWidth) / 2, yPosition + (height - icon.length) / 2, shade);
            int textX = xPosition + 6 + slot + 5;
            // Room left on the right for a mark the screen may put there.
            String text = Theme.ellipsize(lagfinder.client.gui.ui.FontRenderer.INSTANCE, displayString, xPosition + width - 10 - textX);
            Theme.text(lagfinder.client.gui.ui.FontRenderer.INSTANCE, text, textX, yPosition + (height - 8) / 2, color);
            return;
        }
        String text = Theme.ellipsize(lagfinder.client.gui.ui.FontRenderer.INSTANCE, displayString, width - 6);
        Theme.centered(lagfinder.client.gui.ui.FontRenderer.INSTANCE, text, xPosition + width / 2, yPosition + (height - 8) / 2, color);
    }

    /** The box: lit by the mouse with a short fade, in the accent color when active. */
    protected void drawBackground(boolean hovered) {
        double lit = hover.update(hovered ? 1 : 0, 22);
        int x1 = xPosition + width, y1 = yPosition + height;
        if (segment && !active) {
            if (enabled && lit > 0.01) {
                Theme.fill(
                    xPosition + 1,
                    yPosition + 1,
                    x1 - 1,
                    y1 - 1,
                    Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
            }
            return;
        }
        int background, border;
        if (!enabled) {
            background = Theme.CONTROL_DISABLED;
            border = Theme.BORDER;
        } else if (active && outlined) {
            background = Theme.blend(0xFF13305C, 0xFF183A6E, lit);
            border = Theme.ACCENT;
        } else if (active) {
            background = Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT_FILL, 0.5 + 0.5 * lit);
            border = Theme.ACCENT_EDGE;
        } else if (danger) {
            background = Theme.blend(Theme.CONTROL, 0xFF3A2326, lit);
            border = Theme.blend(Theme.BORDER, Theme.DANGER, lit);
        } else {
            background = Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
            border = Theme.blend(0xFF2A3D5C, 0xFF3A5A88, lit);
        }
        if (enabled && !segment && lit > 0.01) {
            Theme.glow(xPosition, yPosition, x1, y1, danger && !active ? Theme.DANGER : Theme.ACCENT, lit * 0.4);
        }
        Theme.roundBox(xPosition, yPosition, x1, y1, background, border);
    }
}
