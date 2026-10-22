package lagfinder.client.gui;

import java.util.List;

import lagfinder.client.gui.ui.GuiButton;
import net.minecraft.client.resources.language.I18n;

import lagfinder.client.ReportStore;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.ScaledScreen;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.WindowHeader;

/**
 * The mod's main window: its header, a menu of tabs down the left (Scan, Logs, About) and the tab's content
 * on the right. Each tab is a screen of its own, of the same size in the same place; picking another one swaps the
 * screen in place, without sliding in again.
 */
public abstract class GuiWindow extends ScaledScreen {

    public static final int WIDTH = 560, HEIGHT = 392;
    /** Width of the menu of tabs. */
    protected static final int MENU = 84;
    private static final int TAB_HEIGHT = 25, TAB_GAP = 0;
    /** The header's close button; the tabs' own buttons take other ids. */
    protected static final int ID_CLOSE = 900;

    /** The tabs, in the menu's order. */
    protected enum Tab {

        SCAN(Icons.STATS, "lagfinder.tab.scan"),
        LOGS(Icons.LOGS, "lagfinder.tab.logs"),
        ABOUT(Icons.INFO, "lagfinder.tab.about");

        final String[] icon;
        final String name;

        Tab(String[] icon, String name) {
            this.icon = icon;
            this.name = name;
        }

        GuiWindow open() {
            switch (this) {
                case LOGS:
                    return new GuiLogs();
                case ABOUT:
                    return new GuiAbout();
                default:
                    return new GuiLagFinder();
            }
        }
    }

    protected int left, top;
    /** Where the tab's content goes. */
    protected int contentLeft, contentTop, contentRight, contentBottom;
    /** How many reports are saved, for the Logs tab. */
    protected int savedCount;
    private final Smooth[] tabLight = new Smooth[Tab.values().length];
    /** Opened from another tab of the window: it is already in place. */
    private final boolean switched;
    private final long createdAt = System.currentTimeMillis();

    protected GuiWindow() {
        // The window is never drawn over another of the mod's screens: a tab takes the place of the one before.
        super(false);
        switched = mc().screen instanceof GuiWindow;
        for (int i = 0; i < tabLight.length; i++) {
            tabLight[i] = new Smooth(0);
        }
    }

    private static net.minecraft.client.Minecraft mc() {
        return net.minecraft.client.Minecraft.getInstance();
    }

    /** The tab this screen shows. */
    protected abstract Tab tab();

    /** Puts the tab's buttons in place; the button list is empty, but for the close button. */
    protected abstract void layOut();

    /** Draws the tab's content, under its buttons. */
    protected abstract void drawContent(int mouseX, int mouseY, float partialTicks);

    /** Draws over the buttons: tooltips. */
    protected void drawOver(int mouseX, int mouseY) {}

    /** A button of the tab was pressed. */
    protected void pressed(GuiButton button) {}

    @Override
    protected boolean slidesIn() {
        return !switched || System.currentTimeMillis() - createdAt > 1000;
    }

    @Override
    public void initGui() {
        savedCount = ReportStore.list()
            .size();
        left = (width - WIDTH) / 2;
        top = Math.max(4, (height - HEIGHT) / 2);
        contentLeft = left + MENU + 12;
        contentRight = left + WIDTH - 12;
        contentTop = top + WindowHeader.HEIGHT + 10;
        contentBottom = top + HEIGHT - 10;
        buttonList.clear();
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, left + WIDTH, top));
        layOut();
    }

    /** The saved reports were changed: the Logs tab's count follows. */
    protected void countSaved() {
        savedCount = ReportStore.list()
            .size();
    }

    @Override
    protected final void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            closeWindow();
            return;
        }
        pressed(button);
    }

    /** Closes the window to the game. */
    protected void closeWindow() {
        GuiLagFinder.forgetReopen();
        mc.setScreen(null);
    }

    /** Shows another tab in place of this one. */
    protected void switchTo(Tab tab) {
        if (tab != tab()) {
            mc.setScreen(tab.open());
        }
    }

    private int tabY(Tab tab) {
        return top + WindowHeader.HEIGHT + 8 + tab.ordinal() * (TAB_HEIGHT + TAB_GAP);
    }

    private Tab tabAt(int mouseX, int mouseY) {
        for (Tab tab : Tab.values()) {
            int y = tabY(tab);
            if (Theme.inside(mouseX, mouseY, left + 1, y, left + MENU, y + TAB_HEIGHT)) {
                return tab;
            }
        }
        return null;
    }

    @Override
    protected final void mouseClicked(int mouseX, int mouseY, int button) {
        Tab tab = button == 0 ? tabAt(mouseX, mouseY) : null;
        if (tab != null) {
            switchTo(tab);
            return;
        }
        clicked(mouseX, mouseY, button);
    }

    /** A click in the window, not on the menu; the default presses the buttons. */
    protected void clicked(int mouseX, int mouseY, int button) {
        clickButtons(mouseX, mouseY, button);
    }

    /** Presses the button under the mouse, if any. */
    protected final void clickButtons(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public final void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, left + WIDTH, top + HEIGHT);
        Theme.fill(left + MENU + 1, top + WindowHeader.HEIGHT, left + WIDTH - 1, top + HEIGHT - 1, Theme.CONTENT);
        WindowHeader.draw(
            fontRendererObj,
            left,
            top,
            left + WIDTH,
            left + WIDTH - WindowHeader.CLOSE_ROOM,
            Icons.STATS,
            "LagFinder",
            I18n.get("lagfinder.gui.subtitle"),
            Theme.TEXT_MUTED,
            null);
        drawMenu(mouseX, mouseY);
        drawContent(mouseX, mouseY, partialTicks);
        super.drawScaled(mouseX, mouseY, partialTicks);
        drawOver(mouseX, mouseY);
        Tab hovered = tabAt(mouseX, mouseY);
        if (hovered == Tab.LOGS) {
            tooltip(I18n.get("lagfinder.tip.logs", savedCount), mouseX, mouseY);
        }
    }

    private void drawMenu(int mouseX, int mouseY) {
        int x0 = left + 1, x1 = left + MENU, y0 = top + WindowHeader.HEIGHT, y1 = top + HEIGHT - 1;
        Theme.fill(x0, y0, x1, y1, Theme.WINDOW);
        Theme.fill(x1, y0, x1 + 1, y1, Theme.BORDER);
        Tab hovered = tabAt(mouseX, mouseY);
        for (Tab tab : Tab.values()) {
            int y = tabY(tab);
            boolean chosen = tab == tab();
            double lit = tabLight[tab.ordinal()].update(tab == hovered ? 1 : 0, 22);
            if (chosen) {
                Theme.fill(x0, y, x1, y + TAB_HEIGHT, Theme.SELECTED);
                Theme.fill(x0, y, x0 + 2, y + TAB_HEIGHT, Theme.ACCENT_FILL);
            } else if (lit > 0.01) {
                Theme.fill(x0, y, x1, y + TAB_HEIGHT, Theme.blend(0x00FFFFFF, 0x14FFFFFF, lit));
            }
            int iconColor = chosen ? Theme.ACCENT : Theme.blend(0xFF98A6BA, Theme.TEXT, lit);
            int iconWidth = Icons.width(tab.icon);
            Icons.draw(tab.icon, x0 + 8 + (11 - iconWidth) / 2, y + (TAB_HEIGHT - tab.icon.length) / 2, iconColor);
            int textColor = chosen ? Theme.TEXT : Theme.blend(0xFFB8C4D6, Theme.TEXT, lit);
            int room = x1 - x0 - 24 - 4;
            String count = tab == Tab.LOGS && savedCount > 0 ? String.valueOf(savedCount) : null;
            if (count != null) {
                int countWidth = fontRendererObj.getStringWidth(count) + 6;
                int cx1 = x1 - 5, cx0 = cx1 - countWidth;
                int cy0 = y + (TAB_HEIGHT - 12) / 2;
                Theme.roundRect(cx0, cy0, cx1, cy0 + 12, chosen ? Theme.ACCENT_DIM : 0xFF1F2E47);
                fontRendererObj.drawString(count, cx0 + 3, cy0 + 2, chosen ? Theme.TEXT : Theme.TEXT_MUTED);
                room -= countWidth + 3;
            }
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.get(tab.name), room),
                x0 + 24,
                y + (TAB_HEIGHT - 8) / 2,
                textColor);
        }
        String version = "v" + lagfinder.LagFinder.version;
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, version, MENU - 12), x0 + 8, y1 - 12, Theme.TEXT_MUTED);
    }

    /**
     * A box of the content with its title on top: an icon (an accent bar when null) and the title in capitals. The
     * box's own content starts 20 pixels under its top.
     */
    protected void section(int x0, int y0, int x1, int y1, String[] icon, String title) {
        section(x0, y0, x1, y1, icon, title, null);
    }

    protected void section(int x0, int y0, int x1, int y1, String[] icon, String title, String subtitle) {
        Theme.card(x0, y0, x1, y1);
        int textX;
        if (icon == null) {
            Theme.fill(x0 + 7, y0 + 6, x0 + 9, y0 + 14, Theme.ACCENT);
            textX = x0 + 13;
        } else {
            Icons.draw(icon, x0 + 7, y0 + 10 - icon.length / 2, Theme.ACCENT);
            textX = x0 + 7 + Icons.width(icon) + 5;
        }
        String text = Theme.ellipsize(fontRendererObj, title.toUpperCase(), x1 - 8 - textX);
        fontRendererObj.drawString(text, textX, y0 + 6, Theme.TEXT);
        if (subtitle != null) {
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, subtitle, x1 - 8 - textX),
                textX,
                y0 + 16,
                Theme.TEXT_MUTED);
        }
    }

    /** A tooltip: its first line bright, the others (after "\n") muted. */
    protected void tooltip(String text, int mouseX, int mouseY) {
        List<String> lines = new java.util.ArrayList<>();
        List<Integer> colors = new java.util.ArrayList<>();
        String[] parts = text.split("\\\\n|\n");
        for (int i = 0; i < parts.length; i++) {
            for (Object line : fontRendererObj.listFormattedStringToWidth(parts[i], 240)) {
                lines.add((String) line);
                colors.add(i == 0 ? Theme.TEXT : Theme.TEXT_MUTED);
            }
        }
        Draw.tooltip(fontRendererObj, lines, colors, mouseX, mouseY, width, height);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
