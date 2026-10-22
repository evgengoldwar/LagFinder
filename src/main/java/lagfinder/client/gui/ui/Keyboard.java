package lagfinder.client.gui.ui;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;

/** The keys the mod's screens tell apart, as the game's window gives them. */
public final class Keyboard {

    public static final int KEY_NONE = 0;
    public static final int KEY_ESCAPE = GLFW.GLFW_KEY_ESCAPE;
    public static final int KEY_RETURN = GLFW.GLFW_KEY_ENTER;
    public static final int KEY_NUMPADENTER = GLFW.GLFW_KEY_KP_ENTER;
    public static final int KEY_BACK = GLFW.GLFW_KEY_BACKSPACE;
    public static final int KEY_DELETE = GLFW.GLFW_KEY_DELETE;
    public static final int KEY_TAB = GLFW.GLFW_KEY_TAB;
    public static final int KEY_UP = GLFW.GLFW_KEY_UP;
    public static final int KEY_DOWN = GLFW.GLFW_KEY_DOWN;
    public static final int KEY_LEFT = GLFW.GLFW_KEY_LEFT;
    public static final int KEY_RIGHT = GLFW.GLFW_KEY_RIGHT;
    public static final int KEY_PRIOR = GLFW.GLFW_KEY_PAGE_UP;
    public static final int KEY_NEXT = GLFW.GLFW_KEY_PAGE_DOWN;
    public static final int KEY_HOME = GLFW.GLFW_KEY_HOME;
    public static final int KEY_END = GLFW.GLFW_KEY_END;
    public static final int KEY_F = GLFW.GLFW_KEY_F;
    public static final int KEY_C = GLFW.GLFW_KEY_C;
    public static final int KEY_A = GLFW.GLFW_KEY_A;
    public static final int KEY_LCONTROL = GLFW.GLFW_KEY_LEFT_CONTROL;
    public static final int KEY_RCONTROL = GLFW.GLFW_KEY_RIGHT_CONTROL;
    public static final int KEY_LSHIFT = GLFW.GLFW_KEY_LEFT_SHIFT;
    public static final int KEY_RSHIFT = GLFW.GLFW_KEY_RIGHT_SHIFT;

    private Keyboard() {}

    public static void enableRepeatEvents(boolean enable) {}

    public static boolean isKeyDown(int key) {
        long window = Minecraft.getInstance()
            .getWindow()
            .getWindow();
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    public static boolean isCtrlDown() {
        return isKeyDown(KEY_LCONTROL) || isKeyDown(KEY_RCONTROL);
    }

    public static boolean isShiftDown() {
        return isKeyDown(KEY_LSHIFT) || isKeyDown(KEY_RSHIFT);
    }
}
