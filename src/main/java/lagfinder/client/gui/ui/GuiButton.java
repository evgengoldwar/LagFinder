package lagfinder.client.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** A button of the mod's screens: a box with an id, drawn and clicked by the screen it is in. */
public class GuiButton {

    public int id;
    public int xPosition, yPosition;
    public int width, height;
    public String displayString;
    public boolean enabled = true;
    public boolean visible = true;

    public GuiButton(int id, int x, int y, int width, int height, String text) {
        this.id = id;
        this.xPosition = x;
        this.yPosition = y;
        this.width = width;
        this.height = height;
        this.displayString = text;
    }

    public void drawButton(Minecraft mc, int mouseX, int mouseY) {}

    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        return enabled && visible
            && mouseX >= xPosition
            && mouseY >= yPosition
            && mouseX < xPosition + width
            && mouseY < yPosition + height;
    }

    public void mouseReleased(int mouseX, int mouseY) {}

    public void playPressSound() {
        Minecraft.getInstance()
            .getSoundManager()
            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
