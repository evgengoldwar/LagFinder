package lagfinder.client.gui.ui;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;

/**
 * The mouse as the mod's screens read it: in pixels of the window from its lower left corner, with the wheel turned in
 * the event being handled (120 a notch).
 */
public final class Mouse {

    private static int eventWheel;
    private static int eventX, eventY;

    private Mouse() {}

    /** The wheel was turned (notches, up positive) with the mouse at the window point given. */
    static void wheel(double notches, double screenX, double screenY) {
        eventWheel = (int) Math.round(notches * 120);
        eventX = toPixelX(screenX);
        eventY = toPixelY(screenY);
    }

    static void clearEvent() {
        eventWheel = 0;
    }

    public static int getEventDWheel() {
        return eventWheel;
    }

    public static int getEventX() {
        return eventX;
    }

    public static int getEventY() {
        return eventY;
    }

    public static int getX() {
        return toPixelX(
            Minecraft.getInstance().mouseHandler.xpos());
    }

    public static int getY() {
        return toPixelY(
            Minecraft.getInstance().mouseHandler.ypos());
    }

    public static boolean isButtonDown(int button) {
        long window = Minecraft.getInstance()
            .getWindow()
            .getWindow();
        return GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
    }

    private static int toPixelX(double screenX) {
        Window window = Minecraft.getInstance()
            .getWindow();
        return (int) (screenX * window.getWidth() / Math.max(1, window.getScreenWidth()));
    }

    private static int toPixelY(double screenY) {
        Window window = Minecraft.getInstance()
            .getWindow();
        return window.getHeight() - 1 - (int) (screenY * window.getHeight() / Math.max(1, window.getScreenHeight()));
    }
}
