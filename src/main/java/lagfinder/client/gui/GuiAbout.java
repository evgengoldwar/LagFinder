package lagfinder.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

import org.lwjgl.Sys;
import org.lwjgl.input.Keyboard;

import lagfinder.LagFinder;
import lagfinder.Tags;
import lagfinder.client.KeyHandler;
import lagfinder.client.gui.ui.FlatButton;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.Theme;

/** What the mod is, what each of its scans looks at, a few tips, and where to find more. */
public class GuiAbout extends GuiWindow {

    private static final String URL = "https://github.com/evgengoldwar/LagFinder";
    private static final int ID_GITHUB = 1;
    private static final int TOP_HEIGHT = 62, KINDS_HEIGHT = 20 + 5 * 22 + 4, GAP = 6;

    /** The kinds of scans, with their icons and colors as on the Scan tab. */
    private static final String[][] KIND_ICONS = { Icons.SERVER, Icons.MONITOR, Icons.METHODS, Icons.OBJECTS,
        Icons.ALLOCATIONS };
    private static final int[] KIND_COLORS = { Theme.ACCENT, 0xFFB37FEB, 0xFFFF8C42, 0xFF3FB950, 0xFF39C5CF };
    private static final String[] KIND_TITLES = { "lagfinder.gui.tps_title", "lagfinder.gui.fps_title",
        "lagfinder.gui.methods_title", "lagfinder.gui.objects_title", "lagfinder.gui.alloc_title" };
    private static final String[] KIND_TEXTS = { "lagfinder.gui.tps_text", "lagfinder.gui.fps_text",
        "lagfinder.gui.methods_text", "lagfinder.gui.objects_text", "lagfinder.gui.alloc_text" };

    private int kindsY, tipsY;

    @Override
    protected Tab tab() {
        return Tab.ABOUT;
    }

    @Override
    protected void layOut() {
        kindsY = contentTop + TOP_HEIGHT + GAP;
        tipsY = kindsY + KINDS_HEIGHT + GAP;
        String github = "GitHub";
        FlatButton button = new FlatButton(
            ID_GITHUB,
            contentRight - 10 - fontRendererObj.getStringWidth(github) - 30,
            contentTop + (TOP_HEIGHT - 20) / 2,
            fontRendererObj.getStringWidth(github) + 30,
            20,
            github);
        button.icon = Icons.SMALL_LINK;
        buttonList.add(button);
    }

    @Override
    protected void pressed(GuiButton button) {
        if (button.id == ID_GITHUB) {
            try {
                Sys.openURL(URL);
            } catch (Throwable t) {
                LagFinder.LOG.warn("Could not open {}", URL, t);
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == mc.gameSettings.keyBindInventory.getKeyCode()
            || keyCode == KeyHandler.OPEN.getKeyCode()) {
            closeWindow();
        }
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        drawTop();
        drawKinds();
        drawTips();
    }

    /** The mod's mark, name, version and what it is for. */
    private void drawTop() {
        int x0 = contentLeft, y0 = contentTop, x1 = contentRight, y1 = y0 + TOP_HEIGHT;
        Theme.gradient(x0, y0, x1, y1, Theme.blend(0xFF181D25, Theme.ACCENT, 0.08), 0xFF141920);
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);
        Theme.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, Theme.SHEEN);
        int badge = 44, bx = x0 + 9, by = y0 + (TOP_HEIGHT - badge) / 2;
        Theme.glow(bx, by, bx + badge, by + badge, Theme.ACCENT, 0.6);
        Theme.gradient(bx, by, bx + badge, by + badge, 0xFF22344D, 0xFF1A2639);
        Theme.outline(bx, by, bx + badge, by + badge, 0xFF2E4A70);
        int scale = 3, iconWidth = Icons.width(Icons.LOGO) * scale, iconHeight = Icons.LOGO.length * scale;
        Icons.draw(Icons.LOGO, bx + (badge - iconWidth) / 2, by + (badge - iconHeight) / 2, Theme.ACCENT, scale);
        int textX = bx + badge + 12;
        fontRendererObj.drawStringWithShadow("LagFinder", textX, y0 + 12, Theme.TEXT);
        String version = I18n.format("lagfinder.about.version", Tags.VERSION);
        Theme.pill(
            fontRendererObj,
            version,
            textX + fontRendererObj.getStringWidth("LagFinder") + 8,
            y0 + 10,
            Theme.ACCENT);
        int room = x1 - 10 - 80 - textX;
        List<String> lines = fontRendererObj.listFormattedStringToWidth(I18n.format("lagfinder.about.text"), room);
        for (int i = 0; i < lines.size() && i < 2; i++) {
            fontRendererObj.drawString(lines.get(i), textX, y0 + 26 + i * 10, Theme.TEXT_MUTED);
        }
        fontRendererObj.drawString(
            I18n.format("lagfinder.about.author", "evgenwargold"),
            textX,
            y0 + TOP_HEIGHT - 13,
            Theme.TEXT_DISABLED);
    }

    /** Each kind of scan, in a line. */
    private void drawKinds() {
        int x0 = contentLeft, x1 = contentRight, y0 = kindsY;
        section(x0, y0, x1, y0 + KINDS_HEIGHT, null, I18n.format("lagfinder.about.kinds"));
        int titleWidth = 0;
        for (String title : KIND_TITLES) {
            titleWidth = Math.max(titleWidth, fontRendererObj.getStringWidth(I18n.format(title)));
        }
        for (int i = 0; i < KIND_TITLES.length; i++) {
            int y = y0 + 20 + i * 22;
            if (i > 0) {
                Theme.fill(x0 + 8, y - 1, x1 - 8, y, 0xFF1C2129);
            }
            int color = KIND_COLORS[i];
            Theme.gradient(x0 + 8, y + 3, x0 + 10, y + 18, color, Theme.blend(color, 0xFF000000, 0.5));
            Icons.draw(KIND_ICONS[i], x0 + 15, y + 11 - KIND_ICONS[i].length / 2, color);
            fontRendererObj.drawString(I18n.format(KIND_TITLES[i]), x0 + 32, y + 7, Theme.TEXT);
            int textX = x0 + 32 + titleWidth + 12;
            String text = Theme.ellipsize(fontRendererObj, I18n.format(KIND_TEXTS[i]), x1 - 10 - textX);
            fontRendererObj.drawString(text, textX, y + 7, Theme.TEXT_MUTED);
        }
    }

    /** How to get around: the key, the scan going on with the window closed, the logs. */
    private void drawTips() {
        int x0 = contentLeft, x1 = contentRight, y0 = tipsY, y1 = contentBottom;
        section(x0, y0, x1, y1, Icons.SMALL_BOLT, I18n.format("lagfinder.about.tips"));
        String[] tips = { I18n.format("lagfinder.about.tip_key", KeyHandler.keyName()),
            I18n.format("lagfinder.about.tip_background"), I18n.format("lagfinder.about.tip_logs"),
            I18n.format("lagfinder.about.tip_settings") };
        int y = y0 + 20;
        for (String tip : tips) {
            List<String> lines = fontRendererObj.listFormattedStringToWidth(tip, x1 - x0 - 30);
            for (int i = 0; i < lines.size() && y + 9 <= y1 - 4; i++) {
                if (i == 0) {
                    Theme.fill(x0 + 11, y + 3, x0 + 14, y + 6, Theme.ACCENT);
                }
                fontRendererObj.drawString(lines.get(i), x0 + 20, y, Theme.TEXT);
                y += 10;
            }
            y += 3;
        }
    }
}
