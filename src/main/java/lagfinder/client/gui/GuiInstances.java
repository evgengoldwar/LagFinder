package lagfinder.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import lagfinder.client.ClientScanner;
import lagfinder.client.Highlighter;
import lagfinder.client.Names;
import lagfinder.client.gui.ui.FlatButton;
import lagfinder.client.gui.ui.FlatTextField;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.ScaledScreen;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.WindowHeader;
import lagfinder.core.Report;
import lagfinder.core.ReportEntry;
import lagfinder.core.ScanMode;
import lagfinder.network.Net;

/**
 * Every one of a kind of thing (or of a mod) in the world, as cards in a grid: what it is, where, in which dimension,
 * its average time and its worst tick, with buttons to teleport to it, highlight it and copy its place. Only the
 * cards in view are drawn, so tens of thousands of them scroll as smoothly as ten. Sorted by average, by peak, by
 * spikes or by distance, cut to one dimension and searched by name or coordinates.
 */
public class GuiInstances extends ScaledScreen {

    private static final int ID_CLOSE = 0, ID_SORT = 1, ID_DIM = 2, ID_BACK = 3, ID_CLEAR = 4;
    private static final int CARD_MIN_WIDTH = 196, CARD_HEIGHT = 62, GAP = 6;
    private static final int DIM_COLOR = 0xFFB37FEB;

    private enum Sort {

        AVERAGE("lagfinder.sort.average"),
        PEAK("lagfinder.sort.peak"),
        SPIKES("lagfinder.sort.spikes"),
        DISTANCE("lagfinder.sort.distance");

        final String key;

        Sort(String key) {
            this.key = key;
        }
    }

    private static Sort sort = Sort.AVERAGE;

    /** Clickable area registered while drawing, cut to what shows of it. */
    private static final class Hit {

        final int x0, y0, x1, y1;
        final Runnable action;
        final String tooltip;

        Hit(int x0, int y0, int x1, int y1, Runnable action, String tooltip) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.action = action;
            this.tooltip = tooltip;
        }

        boolean contains(int x, int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final Report report;
    /** The kind (or mod) whose things are shown. */
    private final ReportEntry root;
    /** The report this was opened from, gone back to. */
    private final GuiReport back;
    /** All its things in the report, and those shown: in the dimension picked, matching the search, sorted. */
    private final List<ReportEntry> all = new ArrayList<>();
    private final List<ReportEntry> shown = new ArrayList<>();
    /** The dimensions its things are in, the most of them first. */
    private final List<Integer> dimensions = new ArrayList<>();
    /** The dimension picked, null for all. */
    private Integer dimension;
    /** The card clicked last, outlined. */
    private ReportEntry picked;
    /** The most time one of them takes: the full length of the cards' bars. */
    private double heaviest;

    private final List<Hit> hits = new ArrayList<>();
    private int clipX0 = Integer.MIN_VALUE, clipY0 = Integer.MIN_VALUE, clipX1 = Integer.MAX_VALUE,
        clipY1 = Integer.MAX_VALUE;
    private final Map<Object, Smooth> lights = new HashMap<>();
    private int scroll;
    private final Smooth shownScroll = new Smooth(0);

    private int panelLeft, panelTop, panelRight, panelBottom;
    private int toolbarY, gridLeft, gridRight, gridTop, gridBottom;
    private int columns, cardWidth;
    private FlatTextField search;
    private FlatButton sortButton, dimButton, clearButton;
    private String lastSearch = "";
    /** Searched for when the window opens (what the report was searched for), or null. */
    private String startSearch;
    /** A thing to show picked and scrolled to when the window opens, or null. */
    private ReportEntry focus;

    public GuiInstances(Report report, ReportEntry root, GuiReport back) {
        this.report = report;
        this.root = root;
        this.back = back;
        collect(root, all);
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (ReportEntry entry : all) {
            heaviest = Math.max(heaviest, entry.totalMs);
            if (entry.hasLocation()) {
                counts.merge(entry.dim, 1, Integer::sum);
            }
        }
        dimensions.addAll(counts.keySet());
        dimensions.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));
    }

    /** Opens with the search already typed in: the report's, when it was for some of these things. */
    public GuiInstances withSearch(String text) {
        startSearch = text == null || text.trim()
            .isEmpty() ? null : text.trim();
        return this;
    }

    /** Opens with this one of them picked and scrolled into view. */
    public GuiInstances focusOn(ReportEntry entry) {
        focus = entry;
        return this;
    }

    /** Whether the entry has things in the world under it to show as cards. */
    public static boolean hasInstances(ReportEntry entry) {
        if (entry.level == 3) {
            return entry.hasLocation();
        }
        for (ReportEntry child : entry.children) {
            if (hasInstances(child)) {
                return true;
            }
        }
        return false;
    }

    /** How many things in the world are under the entry in the report. */
    public static int countInstances(ReportEntry entry) {
        if (entry.level == 3) {
            return entry.hasLocation() ? 1 : 0;
        }
        int count = 0;
        for (ReportEntry child : entry.children) {
            count += countInstances(child);
        }
        return count;
    }

    private static void collect(ReportEntry entry, List<ReportEntry> out) {
        if (entry.level == 3) {
            if (entry.hasLocation()) {
                out.add(entry);
            }
            return;
        }
        for (ReportEntry child : entry.children) {
            collect(child, out);
        }
    }

    /** Things of the kind seen in the scan, even those the report left out. */
    private int seen() {
        if (root.level == 2) {
            return Math.max(root.instances, all.size());
        }
        int count = 0;
        for (ReportEntry type : root.children) {
            count += Math.max(type.instances, countInstances(type));
        }
        return Math.max(count, all.size());
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        panelLeft = 6;
        panelTop = 4;
        panelRight = width - 6;
        panelBottom = height - 4;
        toolbarY = panelTop + WindowHeader.HEIGHT + 8;
        gridLeft = panelLeft + 8;
        gridRight = panelRight - 8;
        gridTop = toolbarY + 16 + 22;
        gridBottom = panelBottom - 8;

        buttonList.clear();
        FlatButton backButton = new FlatButton(
            ID_BACK,
            gridLeft,
            toolbarY,
            70,
            16,
            I18n.format("lagfinder.cards.back"));
        backButton.icon = Icons.BACK;
        buttonList.add(backButton);
        int right = gridRight;
        sortButton = new FlatButton(ID_SORT, right - 96, toolbarY, 96, 16, "");
        sortButton.icon = Icons.SMALL_SORT;
        buttonList.add(sortButton);
        right -= 96 + 4;
        dimButton = new FlatButton(ID_DIM, right - 130, toolbarY, 130, 16, "");
        dimButton.icon = Icons.SMALL_GLOBE;
        dimButton.enabled = dimensions.size() > 1;
        buttonList.add(dimButton);
        right -= 130 + 4;
        clearButton = new FlatButton(ID_CLEAR, right - 110, toolbarY, 110, 16, I18n.format("lagfinder.cards.clear"));
        clearButton.icon = Icons.SMALL_EYE;
        buttonList.add(clearButton);
        right -= 110 + 4;
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, panelRight, panelTop));

        int searchLeft = gridLeft + 70 + 6;
        String text = search != null ? search.getText() : startSearch != null ? startSearch : "";
        search = new FlatTextField(fontRendererObj, searchLeft, toolbarY, Math.max(80, right - searchLeft), 16);
        search.setHint(I18n.format("lagfinder.cards.search"));
        search.setMaxStringLength(64);
        search.setText(text);
        search.setOnCleared(this::rebuild);

        int room = gridRight - gridLeft - 6;
        columns = Math.max(1, (room + GAP) / (CARD_MIN_WIDTH + GAP));
        cardWidth = (room - GAP * (columns - 1)) / columns;
        updateButtons();
        rebuild();
        if (focus != null) {
            // Opened for one of them: picked, in view, a third down the grid.
            int index = shown.indexOf(focus);
            if (index >= 0) {
                picked = focus;
                scroll = index / columns * rowHeight() - gridHeight() / 3;
                clampScroll();
                shownScroll.set(scroll);
            }
            focus = null;
        }
    }

    private void updateButtons() {
        sortButton.displayString = I18n.format(sort.key);
        dimButton.displayString = dimension == null ? I18n.format("lagfinder.cards.all_dims")
            : report.dimensionName(dimension);
        clearButton.enabled = !Highlighter.INSTANCE.all()
            .isEmpty();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    // ---------------------------------------------------------------- What is shown

    private boolean playerIn(int dim) {
        return mc.thePlayer != null && mc.theWorld != null && mc.theWorld.provider.dimensionId == dim;
    }

    /** How far the player is from it (blocks), or -1 in another dimension. */
    private double distance(ReportEntry entry) {
        if (!playerIn(entry.dim)) {
            return -1;
        }
        double dx = entry.x + 0.5 - mc.thePlayer.posX, dy = entry.y + 0.5 - mc.thePlayer.posY,
            dz = entry.z + 0.5 - mc.thePlayer.posZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Comparator<ReportEntry> order() {
        switch (sort) {
            case PEAK:
                return (a, b) -> Float.compare(b.maxMs, a.maxMs);
            case SPIKES:
                return (a, b) -> a.spikes != b.spikes ? Integer.compare(b.spikes, a.spikes)
                    : Double.compare(b.totalMs, a.totalMs);
            case DISTANCE: {
                // Those in the player's dimension first, the nearest first; the rest by their time.
                Map<ReportEntry, Double> far = new HashMap<>();
                for (ReportEntry entry : shown) {
                    far.put(entry, distance(entry));
                }
                return (a, b) -> {
                    double da = far.get(a), db = far.get(b);
                    if (da >= 0 && db >= 0) {
                        return Double.compare(da, db);
                    }
                    if (da >= 0 || db >= 0) {
                        return da >= 0 ? -1 : 1;
                    }
                    return Double.compare(b.totalMs, a.totalMs);
                };
            }
            default:
                return (a, b) -> Double.compare(b.totalMs, a.totalMs);
        }
    }

    private boolean matches(ReportEntry entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (Names.of(entry)
            .toLowerCase(Locale.ROOT)
            .contains(query)) {
            return true;
        }
        if (entry.label != null && entry.label.toLowerCase(Locale.ROOT)
            .contains(query)) {
            return true;
        }
        if (Names.position(entry)
            .contains(query) || (entry.x + " " + entry.y + " " + entry.z).contains(query)) {
            return true;
        }
        return report.dimensionName(entry.dim)
            .toLowerCase(Locale.ROOT)
            .contains(query);
    }

    /** Picks the things to show again: after a change of search, dimension or sorting. */
    private void rebuild() {
        lastSearch = search.getText();
        String query = lastSearch.trim()
            .toLowerCase(Locale.ROOT);
        shown.clear();
        for (ReportEntry entry : all) {
            if ((dimension == null || entry.dim == dimension) && matches(entry, query)) {
                shown.add(entry);
            }
        }
        shown.sort(order());
        clampScroll();
    }

    private int rowHeight() {
        return CARD_HEIGHT + GAP;
    }

    private int contentHeight() {
        int rows = (shown.size() + columns - 1) / columns;
        return Math.max(0, rows * rowHeight() - GAP);
    }

    private int gridHeight() {
        return gridBottom - gridTop - 2;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, contentHeight() - gridHeight()));
    }

    // ---------------------------------------------------------------- Input

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE || button.id == ID_BACK) {
            goBack();
        } else if (button.id == ID_SORT) {
            sort = Sort.values()[(sort.ordinal() + (isShiftKeyDown() ? Sort.values().length - 1 : 1))
                % Sort.values().length];
            scroll = 0;
            updateButtons();
            rebuild();
        } else if (button.id == ID_DIM) {
            int index = dimension == null ? -1 : dimensions.indexOf(dimension);
            index = (index + 2) % (dimensions.size() + 1) - 1;
            dimension = index < 0 ? null : dimensions.get(index);
            scroll = 0;
            updateButtons();
            rebuild();
        } else if (button.id == ID_CLEAR) {
            Highlighter.INSTANCE.clear();
            updateButtons();
        }
    }

    private void goBack() {
        mc.displayGuiScreen(back);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        search.mouseClicked(mouseX, mouseY, button);
        if (!search.getText()
            .equals(lastSearch)) {
            rebuild();
        }
        if (button != 0) {
            return;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mouseX, mouseY) && hit.action != null) {
                hit.action.run();
                updateButtons();
                return;
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int x = Mouse.getEventX() * width / mc.displayWidth;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (Theme.inside(x, y, gridLeft, gridTop, gridRight, gridBottom)) {
            scroll -= Integer.signum(wheel) * rowHeight();
            clampScroll();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (search.isFocused() && !search.getText()
                .isEmpty()) {
                search.setText("");
                rebuild();
            } else {
                GuiLagFinder.closeToGame(mc, this);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_BACK && !search.isFocused()) {
            goBack();
            return;
        }
        int page = Math.max(rowHeight(), gridHeight() - rowHeight());
        boolean inText = search.isFocused();
        if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
            scroll += keyCode == Keyboard.KEY_UP ? -rowHeight() : rowHeight();
        } else if (keyCode == Keyboard.KEY_PRIOR || keyCode == Keyboard.KEY_NEXT) {
            scroll += keyCode == Keyboard.KEY_PRIOR ? -page : page;
        } else if (!inText && (keyCode == Keyboard.KEY_HOME || keyCode == Keyboard.KEY_END)) {
            scroll = keyCode == Keyboard.KEY_HOME ? 0 : contentHeight();
        } else {
            // Typing anywhere searches.
            if (!inText && typedChar >= ' ') {
                search.setFocused(true);
            }
            if (search.textboxKeyTyped(typedChar, keyCode)) {
                scroll = 0;
                rebuild();
            }
            return;
        }
        clampScroll();
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
    }

    // ---------------------------------------------------------------- Drawing

    private void hit(int x0, int y0, int x1, int y1, Runnable action, String tooltip) {
        x0 = Math.max(x0, clipX0);
        y0 = Math.max(y0, clipY0);
        x1 = Math.min(x1, clipX1);
        y1 = Math.min(y1, clipY1);
        if (x1 > x0 && y1 > y0) {
            hits.add(new Hit(x0, y0, x1, y1, action, tooltip));
        }
    }

    private double light(Object key, boolean on) {
        Smooth smooth = lights.get(key);
        if (smooth == null) {
            if (!on) {
                return 0;
            }
            smooth = new Smooth(0);
            lights.put(key, smooth);
        }
        double value = smooth.update(on ? 1 : 0, 22);
        if (!on && value < 0.005) {
            // Cards scrolled by leave nothing behind.
            lights.remove(key);
        }
        return value;
    }

    private boolean fps() {
        return report.mode == ScanMode.FPS;
    }

    private String avgText(double perPeriodMs) {
        return report.costsInFps() ? Names.fpsLoss(report.fpsCost(perPeriodMs)) : Names.ms(perPeriodMs);
    }

    private String peakText(ReportEntry entry) {
        return report.costsInFps() ? Names.fpsLoss(report.fpsDrop(report.perPeriod(entry), entry.maxMs))
            : Names.ms(entry.maxMs);
    }

    private int severity(double perPeriodMs) {
        float budget = report.mode.budgetMs();
        if (perPeriodMs >= budget * 0.1) {
            return Theme.DANGER;
        }
        if (perPeriodMs >= budget * 0.02) {
            return Theme.WARN;
        }
        return Theme.TEXT;
    }

    private int peakColor(ReportEntry entry) {
        float budget = report.mode.budgetMs();
        if (entry.maxMs >= budget * 0.5f) {
            return Theme.DANGER;
        }
        if (entry.maxMs >= report.spikeMs) {
            return Theme.ORANGE;
        }
        return Theme.TEXT_MUTED;
    }

    private static String modOf(ReportEntry entry) {
        for (ReportEntry up = entry; up != null; up = up.parent) {
            if (up.level == 1) {
                return up.name;
            }
        }
        return "";
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        hits.clear();
        if (!search.getText()
            .equals(lastSearch)) {
            rebuild();
        }
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(panelLeft, panelTop, panelRight, panelBottom);
        String title = Names.of(root);
        String subtitle = (root.level == 2 ? modOf(root) + "  ·  " : "") + Names.category(root.category);
        int seen = seen();
        if (seen > all.size()) {
            subtitle += "  ·  " + I18n.format("lagfinder.cards.kept", all.size(), seen);
        }
        String pill = shown.size() == all.size() ? String.valueOf(all.size()) : shown.size() + " / " + all.size();
        WindowHeader.draw(
            fontRendererObj,
            panelLeft,
            panelTop,
            panelRight,
            panelRight - WindowHeader.CLOSE_ROOM,
            Icons.SMALL_GRID,
            title,
            subtitle,
            Theme.TEXT_MUTED,
            pill);

        drawSummary();
        drawGrid(mouseX, mouseY);
        search.drawTextBox();
        super.drawScaled(mouseX, mouseY, partialTicks);

        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mouseX, mouseY)) {
                if (hit.tooltip != null) {
                    showTooltip(hit.tooltip, mouseX, mouseY);
                }
                break;
            }
        }
        if (sortButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(I18n.format("lagfinder.tip.cards_sort"), mouseX, mouseY);
        } else if (dimButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(I18n.format("lagfinder.tip.cards_dim"), mouseX, mouseY);
        }
    }

    private void showTooltip(String text, int mouseX, int mouseY) {
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        String[] parts = text.split("\\\\n|\n");
        for (int i = 0; i < parts.length; i++) {
            for (Object line : fontRendererObj.listFormattedStringToWidth(parts[i], 240)) {
                lines.add((String) line);
                colors.add(i == 0 ? Theme.TEXT : Theme.TEXT_MUTED);
            }
        }
        Draw.tooltip(fontRendererObj, lines, colors, mouseX, mouseY, width, height);
    }

    /** One line over the cards: how much those shown take together, and how many of them spiked. */
    private void drawSummary() {
        int y = toolbarY + 16 + 7;
        double total = 0;
        int spiky = 0;
        for (ReportEntry entry : shown) {
            total += entry.totalMs;
            if (entry.maxMs >= report.spikeMs) {
                spiky++;
            }
        }
        double perPeriod = report.periods == 0 ? 0 : total / report.periods;
        int x = gridLeft + 2;
        String label = I18n.format(report.costsInFps() ? "lagfinder.cards.together_fps" : "lagfinder.cards.together");
        fontRendererObj.drawString(label, x, y, Theme.TEXT_DISABLED);
        x += fontRendererObj.getStringWidth(label) + 4;
        String value = avgText(perPeriod);
        fontRendererObj.drawString(value, x, y, severity(perPeriod));
        x += fontRendererObj.getStringWidth(value) + 12;
        if (spiky > 0) {
            Icons.draw(Icons.SMALL_SPIKE, x, y, Theme.ORANGE);
            String spikes = I18n.format("lagfinder.cards.spiked", spiky);
            fontRendererObj.drawString(spikes, x + 10, y, Theme.TEXT_MUTED);
            x += 10 + fontRendererObj.getStringWidth(spikes) + 12;
        }
        int highlighted = Highlighter.INSTANCE.all()
            .size();
        if (highlighted > 0) {
            Icons.draw(Icons.SMALL_EYE, x, y, Theme.ACCENT);
            fontRendererObj
                .drawString(I18n.format("lagfinder.cards.highlighted", highlighted), x + 10, y, Theme.TEXT_MUTED);
        }
        String hint = I18n.format("lagfinder.cards.hint");
        fontRendererObj.drawString(hint, gridRight - 2 - fontRendererObj.getStringWidth(hint), y, Theme.TEXT_DISABLED);
    }

    private void drawGrid(int mouseX, int mouseY) {
        Theme.card(gridLeft, gridTop - 1, gridRight, gridBottom);
        if (shown.isEmpty()) {
            String empty = I18n.format(all.isEmpty() ? "lagfinder.cards.none" : "lagfinder.gui.nothing_found");
            Theme.centered(fontRendererObj, empty, (gridLeft + gridRight) / 2, gridTop + 24, Theme.TEXT_DISABLED);
            return;
        }
        int offset = (int) Math.round(shownScroll.update(scroll, 18));
        int top = gridTop + 1, bottom = gridBottom - 1;
        Theme.clip(gridLeft + 1, top, gridRight - 1, bottom);
        clipX0 = gridLeft + 1;
        clipY0 = top;
        clipX1 = gridRight - 1;
        clipY1 = bottom;
        boolean mouseInGrid = Theme.inside(mouseX, mouseY, gridLeft, top, gridRight, bottom);
        // Only the rows in view.
        int firstRow = Math.max(0, offset / rowHeight());
        int lastRow = (offset + gridHeight()) / rowHeight();
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= shown.size()) {
                    break;
                }
                int x0 = gridLeft + 3 + column * (cardWidth + GAP);
                int y0 = top + 2 + row * rowHeight() - offset;
                int y1 = y0 + CARD_HEIGHT;
                drawCard(shown.get(index), index, x0, y0, x0 + cardWidth, y1, mouseInGrid, mouseX, mouseY);
            }
        }
        Theme.unclip();
        clipX0 = clipY0 = Integer.MIN_VALUE;
        clipX1 = clipY1 = Integer.MAX_VALUE;
        if (contentHeight() > gridHeight()) {
            Theme.scrollbar(
                gridRight - 4,
                top + 2,
                bottom - 2,
                gridHeight(),
                contentHeight(),
                offset / (double) Math.max(1, contentHeight() - gridHeight()),
                mouseInGrid);
        }
    }

    private void drawCard(ReportEntry entry, int index, int x0, int y0, int x1, int y1, boolean mouseInGrid, int mouseX,
        int mouseY) {
        boolean hovered = mouseInGrid && Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
        boolean highlighted = Highlighter.INSTANCE.isHighlighted(entry);
        boolean isPicked = entry == picked;
        double lit = light(entry, hovered);
        double average = report.perPeriod(entry);
        int heat = severity(average);
        int accent = heat == Theme.TEXT ? Draw.modColor(modOf(entry)) : heat;

        // The card, lit by the mouse, outlined when picked or highlighted.
        Theme.gradient(
            x0,
            y0,
            x1,
            y1,
            Theme.blend(0xFF161B22, 0xFF1D242E, lit),
            Theme.blend(0xFF0F1216, 0xFF151A21, lit));
        int border = isPicked ? Theme.ACCENT
            : highlighted ? Theme.blend(Theme.BORDER, Theme.ACCENT, 0.6)
                : Theme.blend(Theme.BORDER, accent, 0.25 + 0.35 * lit);
        Theme.outline(x0, y0, x1, y1, border);
        Theme.gradient(x0, y0, x0 + 2, y1, accent, Theme.blend(accent, 0xFF000000, 0.4));
        // The card's own click (the buttons drawn on it later take precedence).
        hit(x0, y0, x1, y1, () -> picked = picked == entry ? null : entry, null);

        // What it is.
        int x = x0 + 8;
        ItemStack stack = Names.icon(entry);
        if (stack != null) {
            Draw.item(stack, x, y0 + 5, 16);
        } else {
            Icons.draw(Icons.SMALL_CUBE, x + 4, y0 + 9, Theme.TEXT_MUTED);
        }
        String rank = "#" + (index + 1);
        int rankWidth = fontRendererObj.getStringWidth(rank);
        fontRendererObj.drawString(rank, x1 - 6 - rankWidth, y0 + 5, Theme.TEXT_DISABLED);
        String name = Names.of(entry) + (entry.label != null ? " · " + entry.label : "");
        fontRendererObj.drawStringWithShadow(
            Theme.ellipsize(fontRendererObj, name, x1 - 6 - rankWidth - 4 - (x + 21)),
            x + 21,
            y0 + 5,
            Theme.TEXT);

        // Where it is.
        String where = Names.position(entry);
        int whereY = y0 + 16;
        fontRendererObj.drawString(where, x + 21, whereY, Theme.TEXT_MUTED);
        int after = x + 21 + fontRendererObj.getStringWidth(where) + 6;
        String dimName = report.dimensionName(entry.dim);
        double distance = distance(entry);
        String far = distance >= 0 ? String.format("%.0f m", distance) : null;
        int farWidth = far == null ? 0 : fontRendererObj.getStringWidth(far) + 6;
        if (far != null) {
            fontRendererObj.drawString(far, x1 - 6 - farWidth + 6, whereY, Theme.TEXT_DISABLED);
        }
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, dimName, x1 - 6 - farWidth - after),
            after,
            whereY,
            playerIn(entry.dim) ? Theme.blend(DIM_COLOR, Theme.TEXT_MUTED, 0.5) : DIM_COLOR);

        // Its numbers: average and worst tick, and a bar of its average against the heaviest of the list.
        int statsY = y0 + 29;
        int half = (x1 - x0 - 16) / 2;
        String avgLabel = I18n.format("lagfinder.cards.avg");
        fontRendererObj.drawString(avgLabel, x, statsY, Theme.TEXT_DISABLED);
        fontRendererObj.drawString(avgText(average), x + fontRendererObj.getStringWidth(avgLabel) + 4, statsY, heat);
        int px = x + half;
        String peakLabel = I18n.format("lagfinder.cards.peak");
        fontRendererObj.drawString(peakLabel, px, statsY, Theme.TEXT_DISABLED);
        int peakX = px + fontRendererObj.getStringWidth(peakLabel) + 4;
        String peak = peakText(entry);
        fontRendererObj.drawString(peak, peakX, statsY, peakColor(entry));
        if (entry.spikes > 0) {
            int sx = peakX + fontRendererObj.getStringWidth(peak) + 5;
            String spikes = "×" + entry.spikes;
            if (sx + 9 + fontRendererObj.getStringWidth(spikes) < x1 - 6) {
                Icons.draw(Icons.SMALL_SPIKE, sx, statsY, Theme.ORANGE);
                fontRendererObj.drawString(spikes, sx + 9, statsY, Theme.TEXT_DISABLED);
            }
        }
        hit(
            x,
            statsY - 2,
            x1 - 6,
            statsY + 10,
            () -> picked = picked == entry ? null : entry,
            I18n.format("lagfinder.cards.stats_tip") + "\n"
                + I18n.format(fps() ? "lagfinder.stat.avg_frame" : "lagfinder.stat.avg_tick")
                + ": "
                + avgText(average)
                + "\n"
                + I18n.format(report.costsInFps() ? "lagfinder.stat.peak_fps" : "lagfinder.stat.peak")
                + ": "
                + peak
                + "  @"
                + Names.clock(entry.maxAt)
                + "\n"
                + I18n.format("lagfinder.stat.spikes")
                + ": "
                + entry.spikes
                + "\n"
                + I18n.format("lagfinder.stat.active")
                + ": "
                + String.format("%.0f%%", Math.min(100, entry.activePeriods * 100.0 / Math.max(1, report.periods))));
        Theme.meter(x, y0 + 40, x1 - 6, y0 + 42, heaviest <= 0 ? 0 : entry.totalMs / heaviest, accent);

        drawButtons(entry, x, y0 + 45, x1 - 6, y1 - 4, highlighted, mouseX, mouseY);
    }

    /** Teleport, highlight and copy across the bottom of a card. */
    private void drawButtons(ReportEntry entry, int x0, int y0, int x1, int y1, boolean highlighted, int mouseX,
        int mouseY) {
        boolean canTeleport = ClientScanner.INSTANCE.teleportAllowed();
        String[] labels = { I18n.format("lagfinder.action.teleport"),
            I18n.format(highlighted ? "lagfinder.action.unhighlight" : "lagfinder.action.highlight"),
            I18n.format("lagfinder.action.copy") };
        String[][] icons = { Icons.SMALL_TELEPORT, Icons.SMALL_EYE, Icons.SMALL_COPY };
        int gap = 3;
        int each = (x1 - x0 - gap * 2) / 3;
        for (int i = 0; i < 3; i++) {
            int bx0 = x0 + i * (each + gap), bx1 = i == 2 ? x1 : bx0 + each;
            boolean enabled = i != 0 || canTeleport;
            boolean on = i == 1 && highlighted;
            boolean hovered = enabled && Theme.inside(mouseX, mouseY, bx0, y0, bx1, y1)
                && Theme.inside(mouseX, mouseY, clipX0, clipY0, clipX1, clipY1);
            int background = !enabled ? Theme.CONTROL_DISABLED
                : on ? (hovered ? Theme.ACCENT : Theme.ACCENT_DIM) : hovered ? Theme.CONTROL_HOVER : Theme.CONTROL;
            Theme.fill(bx0, y0, bx1, y1, background);
            Theme.outline(bx0, y0, bx1, y1, on ? Theme.ACCENT : hovered ? Theme.ACCENT : Theme.BORDER);
            int color = !enabled ? Theme.TEXT_DISABLED : on || hovered ? Theme.TEXT : Theme.TEXT_MUTED;
            int textWidth = fontRendererObj.getStringWidth(labels[i]);
            int iconWidth = Icons.width(icons[i]);
            int iconY = y0 + (y1 - y0 - icons[i].length) / 2;
            if (iconWidth + 4 + textWidth <= bx1 - bx0 - 6) {
                int start = (bx0 + bx1 - iconWidth - 4 - textWidth) / 2;
                Icons.draw(icons[i], start, iconY, color);
                fontRendererObj.drawString(labels[i], start + iconWidth + 4, y0 + (y1 - y0 - 8) / 2 + 1, color);
            } else {
                Icons.draw(icons[i], (bx0 + bx1 - iconWidth) / 2, iconY, color);
            }
            Runnable action;
            String tip;
            if (i == 0) {
                action = enabled ? () -> teleport(entry) : null;
                tip = I18n.format(canTeleport ? "lagfinder.tip.teleport" : "lagfinder.action.no_teleport");
            } else if (i == 1) {
                action = () -> {
                    Highlighter.INSTANCE.toggle(report, entry);
                    picked = entry;
                };
                tip = I18n.format("lagfinder.tip.highlight");
            } else {
                action = () -> {
                    setClipboardString(entry.x + " " + entry.y + " " + entry.z);
                    picked = entry;
                };
                tip = I18n.format("lagfinder.tip.copy");
            }
            hit(bx0, y0, bx1, y1, action, tip);
        }
    }

    private void teleport(ReportEntry entry) {
        if (!entry.hasLocation() || !ClientScanner.INSTANCE.teleportAllowed()) {
            return;
        }
        picked = entry;
        Net.sendToServer(Net.Teleport.to(entry));
        if (!Highlighter.INSTANCE.isHighlighted(entry)) {
            Highlighter.INSTANCE.toggle(report, entry);
        }
        // The key brings the cards back, to go on to the next one.
        GuiLagFinder.closeToGame(mc, this);
    }

    @Override
    protected boolean showsScreenBehind() {
        return false;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
