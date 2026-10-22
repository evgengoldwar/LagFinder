package lagfinder.client.gui.ui;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;

/** The graphics the mod's screen or overlay being drawn now draws with. */
public final class Gfx {

    private static GuiGraphics current;

    private Gfx() {}

    public static GuiGraphics g() {
        return current;
    }

    public static PoseStack pose() {
        return current.pose();
    }

    /** Draws with {@code graphics} until the one before is set back. Returns the one before. */
    public static GuiGraphics set(GuiGraphics graphics) {
        GuiGraphics before = current;
        current = graphics;
        return before;
    }
}
