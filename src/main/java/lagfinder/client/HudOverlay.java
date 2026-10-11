package lagfinder.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import lagfinder.client.gui.GuiLagFinder;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.Theme;

/**
 * On the game's screen: a small bar while a scan runs (the window can be closed meanwhile), a note when its report
 * is ready, the highlighted things with how far away and which way they are, and the numbers of the one under the
 * crosshair.
 */
public final class HudOverlay {

    public static final HudOverlay INSTANCE = new HudOverlay();

    private HudOverlay() {}

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.showDebugInfo || mc.currentScreen instanceof GuiLagFinder) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        int width = event.resolution.getScaledWidth();
        GL11.glDisable(GL11.GL_LIGHTING);
        ClientScanner scanner = ClientScanner.INSTANCE;
        if (scanner.isActive()) {
            drawProgress(font, width, scanner);
        } else if (scanner.hasUnseenReport()) {
            String text = I18n.format("lagfinder.hud.ready", KeyHandler.keyName());
            int w = font.getStringWidth(text) + 24;
            int x = (width - w) / 2, y = 4;
            Theme.shadow(x, y, x + w, y + 16, 3);
            Theme.glow(x, y, x + w, y + 16, Theme.SUCCESS, 0.4 + 0.3 * Math.sin(System.currentTimeMillis() / 300.0));
            Theme.fill(x, y, x + w, y + 16, Theme.PANEL);
            Theme.outline(x, y, x + w, y + 16, Theme.SUCCESS);
            Icons.draw(Icons.SMALL_CHECK, x + 6, y + 5, Theme.SUCCESS);
            font.drawString(text, x + 17, y + 4, Theme.TEXT);
        }
        drawHighlights(mc, font);
        HighlightCard.draw(mc, font, width, event.resolution.getScaledHeight());
    }

    private static void drawProgress(FontRenderer font, int width, ClientScanner scanner) {
        String mode = GuiLagFinder.kindName(scanner.side(), scanner.focus());
        String state;
        switch (scanner.phase()) {
            case REQUESTED:
                state = I18n.format("lagfinder.hud.starting");
                break;
            case RECEIVING:
                state = I18n.format("lagfinder.hud.receiving");
                break;
            default:
                state = Names.clock(scanner.secondsLeft());
        }
        String text = "LagFinder · " + mode;
        int w = Math.max(150, font.getStringWidth(text) + font.getStringWidth(state) + 30);
        int x = (width - w) / 2, y = 4;
        Theme.shadow(x, y, x + w, y + 22, 3);
        Theme.fill(x, y, x + w, y + 22, Theme.PANEL);
        Theme.outline(x, y, x + w, y + 22, Theme.BORDER);
        Icons.draw(Icons.SMALL_CLOCK, x + 6, y + 5, Theme.ACCENT);
        font.drawString(text, x + 17, y + 4, Theme.TEXT);
        font.drawString(state, x + w - 6 - font.getStringWidth(state), y + 4, Theme.TEXT_MUTED);
        int barLeft = x + 6, barRight = x + w - 6;
        Theme.fill(barLeft, y + 16, barRight, y + 18, 0xFF0B0E12);
        int filled = barLeft + Math.round((barRight - barLeft) * scanner.progress());
        if (filled > barLeft) {
            Theme.gradient(barLeft, y + 16, filled, y + 18, 0xFF8CC2FF, Theme.ACCENT);
        }
    }

    private static void drawHighlights(Minecraft mc, FontRenderer font) {
        List<Highlighter.Highlight> highlights = Highlighter.INSTANCE.all();
        if (highlights.isEmpty() || mc.thePlayer == null) {
            return;
        }
        int y = 30;
        int dim = mc.theWorld.provider.dimensionId;
        Highlighter.Highlight hovered = Highlighter.INSTANCE.hovered();
        for (Highlighter.Highlight highlight : highlights) {
            String where;
            String arrow = "";
            if (highlight.dim != dim) {
                where = I18n.format("lagfinder.hud.other_dimension", highlight.dim);
            } else {
                double dx = highlight.x + 0.5 - mc.thePlayer.posX, dz = highlight.z + 0.5 - mc.thePlayer.posZ;
                double dy = highlight.y - mc.thePlayer.posY;
                where = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + " m";
                arrow = arrow(dx, dz, mc.thePlayer.rotationYaw) + " ";
            }
            String text = Theme.ellipsize(font, highlight.name, 140);
            int w = font.getStringWidth(text) + font.getStringWidth(arrow + where) + 22;
            boolean lit = highlight == hovered;
            Theme.fill(4, y, 4 + w, y + 13, lit ? Theme.blend(Theme.LABEL_BG, highlight.color, 0.25) : Theme.LABEL_BG);
            if (lit) {
                Theme.outline(4, y, 4 + w, y + 13, highlight.color);
            }
            Theme.fill(4, y, lit ? 7 : 6, y + 13, highlight.color);
            font.drawString(text, 10, y + 3, Theme.TEXT);
            font.drawString(arrow + where, 4 + w - 4 - font.getStringWidth(arrow + where), y + 3, Theme.TEXT_MUTED);
            y += 15;
        }
    }

    /** An arrow pointing the way to go, relative to where the player looks. */
    private static String arrow(double dx, double dz, float yaw) {
        double angle = Math.toDegrees(Math.atan2(-dx, dz)) - yaw;
        int step = MathHelper.floor_double((angle % 360 + 360 + 22.5) / 45) % 8;
        return new String[] { "↑", "↗", "→", "↘", "↓", "↙", "←", "↖" }[step];
    }
}
