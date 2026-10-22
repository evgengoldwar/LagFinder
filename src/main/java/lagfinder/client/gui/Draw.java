package lagfinder.client.gui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import lagfinder.LagFinder;
import lagfinder.client.gui.ui.FontRenderer;
import lagfinder.client.gui.ui.Gfx;
import lagfinder.client.gui.ui.ScaledScreen;
import lagfinder.client.gui.ui.Theme;

/** Drawing the screens need beyond rectangles: smooth lines and areas for the graphs, item icons, tooltips. */
public final class Draw {

    /** Items whose renderer failed once: never drawn again, so they can't break the screen. */
    private static final Set<String> BROKEN = new HashSet<>();

    private Draw() {}

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, double x, double y, int argb) {
        buffer.addVertex(matrix, (float) x, (float) y, 0f)
            .setColor(argb);
    }

    /** The area under a line down to {@code baseY}, each point in its own colors at the line and at the base. */
    public static void area(double[] xs, double[] ys, double baseY, int[] tops, int[] bottoms) {
        if (xs.length < 2) {
            return;
        }
        GuiGraphics g = Gfx.g();
        Matrix4f matrix = g.pose()
            .last()
            .pose();
        VertexConsumer buffer = g.bufferSource()
            .getBuffer(RenderType.gui());
        for (int i = 0; i + 1 < xs.length; i++) {
            vertex(buffer, matrix, xs[i], ys[i], tops[i]);
            vertex(buffer, matrix, xs[i], baseY, bottoms[i]);
            vertex(buffer, matrix, xs[i + 1], baseY, bottoms[i + 1]);
            vertex(buffer, matrix, xs[i + 1], ys[i + 1], tops[i + 1]);
        }
        g.flush();
    }

    /** A line through the points, each point in its own color, {@code width} GUI pixels thick. */
    public static void line(double[] xs, double[] ys, int[] colors, float width) {
        if (xs.length < 2) {
            return;
        }
        GuiGraphics g = Gfx.g();
        Matrix4f matrix = g.pose()
            .last()
            .pose();
        VertexConsumer buffer = g.bufferSource()
            .getBuffer(RenderType.gui());
        double half = Math.max(0.5 / ScaledScreen.currentFactor(), width / 2.0);
        for (int i = 0; i + 1 < xs.length; i++) {
            double dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i];
            double length = Math.sqrt(dx * dx + dy * dy);
            if (length < 1e-6) {
                continue;
            }
            double nx = -dy / length * half, ny = dx / length * half;
            vertex(buffer, matrix, xs[i] + nx, ys[i] + ny, colors[i]);
            vertex(buffer, matrix, xs[i] - nx, ys[i] - ny, colors[i]);
            vertex(buffer, matrix, xs[i + 1] - nx, ys[i + 1] - ny, colors[i + 1]);
            vertex(buffer, matrix, xs[i + 1] + nx, ys[i + 1] + ny, colors[i + 1]);
        }
        g.flush();
    }

    /** A rectangle with fractional edges, colored {@code top} at the top and {@code bottom} at the bottom. */
    public static void gradientRect(double x0, double y0, double x1, double y1, int top, int bottom) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        GuiGraphics g = Gfx.g();
        Matrix4f matrix = g.pose()
            .last()
            .pose();
        VertexConsumer buffer = g.bufferSource()
            .getBuffer(RenderType.gui());
        vertex(buffer, matrix, x0, y0, top);
        vertex(buffer, matrix, x0, y1, bottom);
        vertex(buffer, matrix, x1, y1, bottom);
        vertex(buffer, matrix, x1, y0, top);
        g.flush();
    }

    /** A rectangle with fractional edges, colored {@code left} at its left and {@code right} at its right. */
    public static void horizontalGradient(double x0, double y0, double x1, double y1, int left, int right) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        GuiGraphics g = Gfx.g();
        Matrix4f matrix = g.pose()
            .last()
            .pose();
        VertexConsumer buffer = g.bufferSource()
            .getBuffer(RenderType.gui());
        vertex(buffer, matrix, x0, y0, left);
        vertex(buffer, matrix, x0, y1, left);
        vertex(buffer, matrix, x1, y1, right);
        vertex(buffer, matrix, x1, y0, right);
        g.flush();
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
        GuiGraphics g = Gfx.g();
        g.pose()
            .pushPose();
        g.pose()
            .translate(0f, 0f, 400f);
        Theme.shadow(x, y, x + width + 8, y + height, 3);
        Theme.gradient(x, y, x + width + 8, y + height, 0xF8141920, 0xF80C0F13);
        Theme.outline(x, y, x + width + 8, y + height, 0xFF323B47);
        Theme.fill(x + 1, y, x + width + 7, y + 1, Theme.blend(colors.get(0), 0xFF323B47, 0.45));
        for (int i = 0; i < lines.size(); i++) {
            font.drawString(lines.get(i), x + 4, y + 4 + i * 10, colors.get(i));
        }
        g.pose()
            .popPose();
    }

    /** Draws the item's icon at (x, y), {@code size} GUI pixels square. */
    public static void item(ItemStack stack, int x, int y, float size) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String key = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (BROKEN.contains(key)) {
            return;
        }
        GuiGraphics g = Gfx.g();
        g.pose()
            .pushPose();
        g.pose()
            .translate(x, y, 0f);
        g.pose()
            .scale(size / 16f, size / 16f, 1f);
        try {
            g.renderItem(stack, 0, 0);
            g.flush();
        } catch (Throwable t) {
            BROKEN.add(key);
            LagFinder.LOG.warn("Could not draw the icon of item {}; it is left out", key, t);
        }
        g.pose()
            .popPose();
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
