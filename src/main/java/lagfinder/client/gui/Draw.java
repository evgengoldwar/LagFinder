package lagfinder.client.gui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import lagfinder.LagFinder;
import lagfinder.client.gui.ui.ScaledScreen;
import lagfinder.client.gui.ui.Theme;

/** Drawing the screens need beyond rectangles: smooth lines and areas for the graphs, item icons, tooltips. */
public final class Draw {

    private static final RenderItem RENDER_ITEM = new RenderItem();
    /** Items whose renderer failed once: never drawn again, so they can't break the screen. */
    private static final Set<String> BROKEN = new HashSet<>();

    private Draw() {}

    /** Starts drawing untextured shapes with per-vertex colors. */
    public static void beginShapes() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
    }

    public static void endShapes() {
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void color(Tessellator tess, int argb) {
        tess.setColorRGBA_I(argb & 0xFFFFFF, argb >>> 24);
    }

    /** The area under a line down to {@code baseY}, each point in its own colors at the line and at the base. */
    public static void area(double[] xs, double[] ys, double baseY, int[] tops, int[] bottoms) {
        if (xs.length < 2) {
            return;
        }
        beginShapes();
        Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i < xs.length; i++) {
            color(tess, tops[i]);
            tess.addVertex(xs[i], ys[i], 0);
            color(tess, bottoms[i]);
            tess.addVertex(xs[i], baseY, 0);
        }
        tess.draw();
        endShapes();
    }

    /** A line through the points, each point in its own color, {@code width} GUI pixels thick. */
    public static void line(double[] xs, double[] ys, int[] colors, float width) {
        if (xs.length < 2) {
            return;
        }
        beginShapes();
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glLineWidth(Math.max(1f, width * ScaledScreen.currentFactor()));
        Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_LINE_STRIP);
        for (int i = 0; i < xs.length; i++) {
            color(tess, colors[i]);
            tess.addVertex(xs[i], ys[i], 0);
        }
        tess.draw();
        GL11.glLineWidth(1f);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        endShapes();
    }

    /** A rectangle with fractional edges, colored {@code top} at the top and {@code bottom} at the bottom. */
    public static void gradientRect(double x0, double y0, double x1, double y1, int top, int bottom) {
        beginShapes();
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        color(tess, top);
        tess.addVertex(x1, y0, 0);
        tess.addVertex(x0, y0, 0);
        color(tess, bottom);
        tess.addVertex(x0, y1, 0);
        tess.addVertex(x1, y1, 0);
        tess.draw();
        endShapes();
    }

    /** A rectangle with fractional edges, colored {@code left} at its left and {@code right} at its right. */
    public static void horizontalGradient(double x0, double y0, double x1, double y1, int left, int right) {
        beginShapes();
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        color(tess, right);
        tess.addVertex(x1, y0, 0);
        color(tess, left);
        tess.addVertex(x0, y0, 0);
        tess.addVertex(x0, y1, 0);
        color(tess, right);
        tess.addVertex(x1, y1, 0);
        tess.draw();
        endShapes();
    }

    /** A box of lines of text near the mouse, kept on the screen. */
    public static void tooltip(FontRenderer font, List<String> lines, List<Integer> colors, int mouseX, int mouseY,
        int screenWidth, int screenHeight) {
        if (lines.isEmpty()) {
            return;
        }
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.getStringWidth(line));
        }
        int height = lines.size() * 10 + 6;
        int x = mouseX + 12, y = mouseY - 4;
        if (x + width + 8 > screenWidth) {
            x = mouseX - 12 - width - 8;
        }
        y = Math.max(2, Math.min(screenHeight - height - 2, y));
        GL11.glPushMatrix();
        GL11.glTranslatef(0f, 0f, 300f);
        Theme.shadow(x, y, x + width + 8, y + height, 3);
        Theme.gradient(x, y, x + width + 8, y + height, 0xF8141920, 0xF80C0F13);
        Theme.outline(x, y, x + width + 8, y + height, 0xFF323B47);
        // A line of the title's color along the top, so it reads as part of what it tells about.
        Theme.fill(x + 1, y, x + width + 7, y + 1, Theme.blend(colors.get(0), 0xFF323B47, 0.45));
        for (int i = 0; i < lines.size(); i++) {
            font.drawString(lines.get(i), x + 4, y + 4 + i * 10, colors.get(i));
        }
        GL11.glPopMatrix();
    }

    /** Draws the item's icon at (x, y), {@code size} GUI pixels square. */
    public static void item(ItemStack stack, int x, int y, float size) {
        if (stack == null || stack.getItem() == null) {
            return;
        }
        String key = Item.getIdFromItem(stack.getItem()) + ":" + stack.getItemDamage();
        if (BROKEN.contains(key)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int depth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        float zLevel = RENDER_ITEM.zLevel;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glTranslatef(x, y, 0f);
        GL11.glScalef(size / 16f, size / 16f, 1f);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderHelper.enableGUIStandardItemLighting();
        try {
            RENDER_ITEM.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
        } catch (Throwable t) {
            BROKEN.add(key);
            LagFinder.LOG.warn("Could not draw the icon of item {}; it is left out", key, t);
            try {
                Tessellator.instance.draw();
            } catch (Throwable ignored) {
                // Wasn't drawing.
            }
            RENDER_ITEM.zLevel = zLevel;
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glPopAttrib();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        for (int extra = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) - depth; extra > 1; extra--) {
            GL11.glPopMatrix();
        }
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glDisable(GL11.GL_LIGHTING);
    }

    /** A rounded-looking value: 1, 2, 5, 10, 20, 50... at least {@code value}. */
    public static double niceCeil(double value) {
        if (value <= 0) {
            return 1;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        for (double step : new double[] { 1, 2, 2.5, 5, 10 }) {
            if (step * magnitude >= value) {
                return step * magnitude;
            }
        }
        return 10 * magnitude;
    }

    /** Color of a rate: green when fine, yellow when it drops, red when it is bad. */
    public static int rateColor(float rate, float target) {
        float ratio = target <= 0 ? 1 : rate / target;
        if (ratio >= 0.95f) {
            return Theme.SUCCESS;
        }
        if (ratio >= 0.75f) {
            return Theme.blend(0xFFF2C14E, Theme.SUCCESS, (ratio - 0.75f) / 0.2f);
        }
        if (ratio >= 0.5f) {
            return Theme.blend(Theme.DANGER, 0xFFF2C14E, (ratio - 0.5f) / 0.25f);
        }
        return Theme.DANGER;
    }

    /** A color for the mod, the same every time. */
    public static int modColor(String mod) {
        int[] colors = { 0xFF4C9AFF, 0xFF3FB950, 0xFFF2C14E, 0xFFE5534B, 0xFFB37FEB, 0xFF39C5CF, 0xFFFF8C42, 0xFFE67AB8,
            0xFF8BD450, 0xFF6C8CFF };
        return colors[Math.floorMod(mod == null ? 0 : mod.hashCode(), colors.length)];
    }
}
