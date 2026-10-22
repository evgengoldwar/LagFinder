package lagfinder.client.gui.ui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base of the mod's screens: they are laid out at their own scale instead of Minecraft's GUI scale, so they look the
 * same whatever that is set to. It is the largest whole scale that still leaves about 800x450 GUI pixels, so the
 * report's panes fit and nothing ends up tiny. Subclasses draw in {@link #drawScaled}, lay out in {@link #initGui}
 * and get the mouse and the keys in their own GUI pixels.
 */
public abstract class ScaledScreen extends Screen {

    private static final int MIN_WIDTH = 800, MIN_HEIGHT = 450;
    /** How long a screen takes to slide into place when it opens, and how far it comes from (GUI pixels). */
    private static final long OPEN_MS = 180;
    private static final int OPEN_DISTANCE = 8;
    private static final long REOPEN_MS = 5000;
    private static long frame;

    /** Scale the screen being drawn uses (window pixels per GUI pixel), 0 when none of ours is drawing. */
    private static int activeFactor;
    /** How far down the screen being drawn is moved while it slides in (GUI pixels). */
    private static int activeOffset;
    /** The modifier keys held with the last key pressed. */
    private static int modifiers;

    private static boolean drawingBehind;
    private static final int MAX_BEHIND = 4;
    private static int behindDepth;

    protected final Minecraft mc = Minecraft.getInstance();
    protected final FontRenderer fontRendererObj = FontRenderer.INSTANCE;
    protected final List<GuiButton> buttonList = new ArrayList<>();
    /** The button pressed with the mouse, told when it is let go. */
    private GuiButton pressedButton;
    private int appliedFactor;
    private long openedAt, lastDrawn;
    private long lastFrame = -2;
    private final ScaledScreen behind;
    private long clickedAt;
    private int heldButton = -1;

    private static ScaledScreen lastCovered;
    private static long lastCoveredAt;

    protected ScaledScreen() {
        this(true);
    }

    protected ScaledScreen(boolean overOpen) {
        super(Component.literal("LagFinder"));
        Screen open = Minecraft.getInstance().screen;
        behind = overOpen && open instanceof ScaledScreen && open != this ? (ScaledScreen) open : null;
        if (behind != null) {
            lastCovered = behind;
            lastCoveredAt = System.currentTimeMillis();
        }
    }

    public static boolean isBeingCovered(ScaledScreen screen) {
        return lastCovered == screen && System.currentTimeMillis() - lastCoveredAt < 2000;
    }

    public boolean showsBehind(Class<?> type) {
        for (ScaledScreen screen = behind; screen != null; screen = screen.behind) {
            if (type.isInstance(screen)) {
                return true;
            }
        }
        return false;
    }

    protected static boolean isDrawnBehind() {
        return drawingBehind;
    }

    /** Window pixels per GUI pixel of the mod's screens. */
    public static int factor(Minecraft mc) {
        Window window = mc.getWindow();
        int fit = Math.min(window.getWidth() / MIN_WIDTH, window.getHeight() / MIN_HEIGHT);
        return Math.max(1, fit);
    }

    /**
     * Window pixels per GUI pixel of what is being drawn right now: the mod's screen scale inside its screens,
     * Minecraft's GUI scale elsewhere (the HUD).
     */
    public static int currentFactor() {
        if (activeFactor > 0) {
            return activeFactor;
        }
        return (int) Math.max(
            1,
            Math.round(
                Minecraft.getInstance()
                    .getWindow()
                    .getGuiScale()));
    }

    public static int currentOffset() {
        return activeOffset;
    }

    /** The modifier keys (shift, control...) held with the last key pressed in one of the mod's screens. */
    public static int modifiers() {
        return modifiers;
    }

    protected boolean slidesIn() {
        return true;
    }

    protected boolean showsScreenBehind() {
        return true;
    }

    @Override
    protected final void init() {
        appliedFactor = factor(mc);
        Window window = mc.getWindow();
        width = (window.getWidth() + appliedFactor - 1) / appliedFactor;
        height = (window.getHeight() + appliedFactor - 1) / appliedFactor;
        initGui();
    }

    /** Lays the screen out for its width and height (GUI pixels of its own scale). */
    public void initGui() {}

    private void relayoutIfNeeded() {
        Window window = mc.getWindow();
        int expectedWidth = (window.getWidth() + appliedFactor - 1) / Math.max(1, appliedFactor);
        if (factor(mc) != appliedFactor || width != expectedWidth) {
            init();
        }
    }

    @Override
    public final void render(GuiGraphics graphics, int ignoredX, int ignoredY, float partialTicks) {
        relayoutIfNeeded();
        if (!drawingBehind) {
            frame++;
        }
        if (behind != null && showsScreenBehind() && behindDepth < MAX_BEHIND) {
            boolean was = drawingBehind;
            drawingBehind = true;
            behindDepth++;
            try {
                behind.render(graphics, ignoredX, ignoredY, partialTicks);
            } finally {
                drawingBehind = was;
                behindDepth--;
            }
        }
        Window window = mc.getWindow();
        float scale = (float) (appliedFactor / window.getGuiScale());
        int x = drawingBehind ? -10000 : mouseX();
        int y = drawingBehind ? -10000 : mouseY();
        long now = System.currentTimeMillis();
        if (lastFrame < frame - 1 || now - lastDrawn > REOPEN_MS) {
            openedAt = now;
        }
        lastDrawn = now;
        lastFrame = frame;
        int offset = 0;
        if (slidesIn()) {
            double t = Math.min(1, (now - openedAt) / (double) OPEN_MS);
            offset = (int) Math.round(Math.pow(1 - t, 3) * OPEN_DISTANCE);
        }
        GuiGraphics before = Gfx.set(graphics);
        graphics.pose()
            .pushPose();
        graphics.pose()
            .scale(scale, scale, 1f);
        int wasFactor = activeFactor, wasOffset = activeOffset;
        try {
            if (offset > 0) {
                Theme.fill(0, 0, width, offset, Theme.SCREEN_DIM);
                graphics.pose()
                    .translate(0f, offset, 0f);
            }
            activeFactor = appliedFactor;
            activeOffset = offset;
            drawScaled(x, y, partialTicks);
            graphics.flush();
        } finally {
            activeFactor = wasFactor;
            activeOffset = wasOffset;
            graphics.pose()
                .popPose();
            Gfx.set(before);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    /** Draws the screen in its own GUI pixels; the default draws the buttons. */
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        for (GuiButton button : new ArrayList<>(buttonList)) {
            button.drawButton(mc, mouseX, mouseY);
        }
    }

    /** Where the mouse is, in the screen's GUI pixels. */
    protected int mouseX() {
        Window window = mc.getWindow();
        return (int) (mc.mouseHandler.xpos() * window.getWidth() / Math.max(1, window.getScreenWidth())
            / appliedFactor);
    }

    protected int mouseY() {
        Window window = mc.getWindow();
        return (int) (mc.mouseHandler.ypos() * window.getHeight() / Math.max(1, window.getScreenHeight())
            / appliedFactor) - activeOffsetNow();
    }

    private int activeOffsetNow() {
        if (!slidesIn()) {
            return 0;
        }
        double t = Math.min(1, (System.currentTimeMillis() - openedAt) / (double) OPEN_MS);
        return (int) Math.round(Math.pow(1 - t, 3) * OPEN_DISTANCE);
    }

    @Override
    public final boolean mouseClicked(double screenX, double screenY, int button) {
        heldButton = button;
        clickedAt = System.currentTimeMillis();
        mouseClicked(mouseX(), mouseY(), button);
        return true;
    }

    @Override
    public final boolean mouseReleased(double screenX, double screenY, int button) {
        heldButton = -1;
        if (pressedButton != null && button == 0) {
            pressedButton.mouseReleased(mouseX(), mouseY());
            pressedButton = null;
        }
        mouseMovedOrUp(mouseX(), mouseY(), button);
        return true;
    }

    @Override
    public final boolean mouseDragged(double screenX, double screenY, int button, double dragX, double dragY) {
        mouseClickMove(mouseX(), mouseY(), button, System.currentTimeMillis() - clickedAt);
        return true;
    }

    @Override
    public final boolean mouseScrolled(double screenX, double screenY, double scrollX, double scrollY) {
        Mouse.wheel(scrollY, screenX, screenY);
        try {
            handleMouseInput();
        } finally {
            Mouse.clearEvent();
        }
        return true;
    }

    @Override
    public final boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        ScaledScreen.modifiers = modifiers;
        keyTyped((char) 0, keyCode);
        return true;
    }

    @Override
    public final boolean charTyped(char typedChar, int modifiers) {
        ScaledScreen.modifiers = modifiers;
        keyTyped(typedChar, Keyboard.KEY_NONE);
        return true;
    }

    @Override
    public final void tick() {
        updateScreen();
    }

    @Override
    public final void removed() {
        onGuiClosed();
    }

    @Override
    public final boolean isPauseScreen() {
        return doesGuiPauseGame();
    }

    /** A mouse button pressed; the default presses the button under the mouse. */
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0) {
            return;
        }
        for (GuiButton guiButton : new ArrayList<>(buttonList)) {
            if (guiButton.mousePressed(mc, mouseX, mouseY)) {
                pressedButton = guiButton;
                guiButton.playPressSound();
                actionPerformed(guiButton);
                return;
            }
        }
    }

    /** A mouse button let go. */
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {}

    /** The mouse moved with a button held. */
    protected void mouseClickMove(int mouseX, int mouseY, int button, long sinceClick) {}

    /** The wheel turned: {@link Mouse#getEventDWheel()} tells how far. */
    public void handleMouseInput() {}

    /** A key: a character typed ({@code typedChar} not 0), or a key pressed ({@code keyCode}). */
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.setScreen(null);
        }
    }

    protected void actionPerformed(GuiButton button) {}

    public void updateScreen() {}

    public void onGuiClosed() {}

    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Whether a mouse button is held down over the screen now. */
    protected boolean mouseHeld() {
        return heldButton >= 0;
    }
}
