package lagfinder.client.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

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

        int color = !enabled ? Theme.TEXT_DISABLED : danger ? Theme.DANGER : Theme.TEXT;
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
            String text = Theme.ellipsize(mc.fontRenderer, displayString, xPosition + width - 10 - textX);
            Theme.text(mc.fontRenderer, text, textX, yPosition + (height - 8) / 2, color);
            return;
        }
        String text = Theme.ellipsize(mc.fontRenderer, displayString, width - 6);
        Theme.centered(mc.fontRenderer, text, xPosition + width / 2, yPosition + (height - 8) / 2, color);
    }

    /** The box: lit by the mouse with a short fade, in the accent color when active. */
    protected void drawBackground(boolean hovered) {
        double lit = hover.update(hovered ? 1 : 0, 22);
        int background, border;
        if (!enabled) {
            background = Theme.CONTROL_DISABLED;
            border = Theme.BORDER;
        } else if (active) {
            background = Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, lit);
            border = Theme.ACCENT;
        } else if (danger) {
            background = Theme.blend(Theme.CONTROL, 0xFF3A2326, lit);
            border = Theme.blend(Theme.BORDER, Theme.DANGER, lit);
        } else {
            background = Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
            border = Theme.blend(Theme.BORDER, Theme.ACCENT, lit);
        }
        int x1 = xPosition + width, y1 = yPosition + height;
        if (enabled && (active || lit > 0.01)) {
            Theme.glow(
                xPosition,
                yPosition,
                x1,
                y1,
                danger && !active ? Theme.DANGER : Theme.ACCENT,
                active ? 0.5 + 0.5 * lit : lit * 0.6);
        }
        // A little lighter at the top, as if lit from above.
        Theme.gradient(
            xPosition,
            yPosition,
            x1,
            y1,
            Theme.blend(background, 0xFFFFFFFF, enabled ? 0.06 : 0),
            Theme.blend(background, 0xFF000000, 0.12));
        Theme.outline(xPosition, yPosition, x1, y1, border);
        if (enabled) {
            Theme.fill(xPosition + 1, yPosition + 1, x1 - 1, yPosition + 2, Theme.SHEEN);
        }
    }
}
