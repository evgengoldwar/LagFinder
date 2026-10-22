package lagfinder.client.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Text field drawn as a flat box, with an optional grey hint while it is empty. A right click on it clears its text
 * like the search fields of the game's creative inventory. The typing itself is the game's own field's.
 */
public class FlatTextField {

    private final FontRenderer font;
    private final EditBox box;
    private boolean editable = true;
    public final int boxX, boxY, boxWidth, boxHeight;
    private String hint = "";
    private Runnable onCleared;

    public FlatTextField(FontRenderer font, int x, int y, int width, int height) {
        this.font = font;
        this.boxX = x;
        this.boxY = y;
        this.boxWidth = width;
        this.boxHeight = height;
        box = new EditBox(font.font(), x + 4, y + (height - 8) / 2, width - 8, 9, Component.empty());
        box.setBordered(false);
        box.setTextColor(Theme.TEXT & 0xFFFFFF);
    }

    public FlatTextField setHint(String hint) {
        this.hint = hint;
        return this;
    }

    /** Called after a right click cleared the text, so the screen can follow it (a search, a check). */
    public FlatTextField setOnCleared(Runnable onCleared) {
        this.onCleared = onCleared;
        return this;
    }

    public void setMaxStringLength(int length) {
        box.setMaxLength(length);
    }

    public String getText() {
        return box.getValue();
    }

    public void setText(String text) {
        box.setValue(text == null ? "" : text);
    }

    public boolean isFocused() {
        return box.isFocused();
    }

    public void setFocused(boolean focused) {
        box.setFocused(focused);
    }

    public void setEnabled(boolean enabled) {
        editable = enabled;
        box.setEditable(enabled);
        if (!enabled) {
            box.setFocused(false);
        }
    }

    public void updateCursorCounter() {}

    public boolean isMouseOver(int mouseX, int mouseY) {
        return Theme.inside(mouseX, mouseY, boxX, boxY, boxX + boxWidth, boxY + boxHeight);
    }

    public void mouseClicked(int mouseX, int mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY)) {
            box.setFocused(false);
            return;
        }
        if (!editable) {
            return;
        }
        box.setFocused(true);
        if (button == 1 && !getText().isEmpty()) {
            setText("");
            if (onCleared != null) {
                onCleared.run();
            }
            return;
        }
        int textY = boxY + (boxHeight - 8) / 2 + 4;
        box.mouseClicked(Math.max(boxX + 4, Math.min(boxX + boxWidth - 5, mouseX)), textY, button);
    }

    /**
     * A key typed while the field has the focus: a character ({@code typedChar} not 0) or a key the field acts on
     * (moving, deleting, copying). Whether the field took it.
     */
    public boolean textboxKeyTyped(char typedChar, int keyCode) {
        if (!box.isFocused()) {
            return false;
        }
        if (typedChar != 0) {
            return box.charTyped(typedChar, ScaledScreen.modifiers());
        }
        return box.keyPressed(keyCode, 0, ScaledScreen.modifiers());
    }

    public void drawTextBox() {
        if (isFocused()) {
            Theme.glow(boxX, boxY, boxX + boxWidth, boxY + boxHeight, Theme.ACCENT, 0.6);
        }
        Theme.roundBox(
            boxX,
            boxY,
            boxX + boxWidth,
            boxY + boxHeight,
            0xFF0A1424,
            isFocused() ? Theme.ACCENT : 0xFF2A3D5C);
        if (getText().isEmpty() && !isFocused() && !hint.isEmpty()) {
            font.drawString(
                Theme.ellipsize(font, hint, boxWidth - 8),
                boxX + 4,
                boxY + (boxHeight - 8) / 2,
                Theme.TEXT_DISABLED);
        }
        box.render(Gfx.g(), -1, -1, Minecraft.getInstance()
            .getTimer()
            .getGameTimeDeltaPartialTick(true));
    }
}
