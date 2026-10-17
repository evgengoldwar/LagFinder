package lagfinder.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import lagfinder.client.Names;
import lagfinder.client.gui.ui.FlatButton;
import lagfinder.client.gui.ui.FlatTextField;
import lagfinder.client.gui.ui.IconButton;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.ScaledScreen;
import lagfinder.client.gui.ui.ScrollbarDrag;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.WindowHeader;
import lagfinder.core.CallNode;
import lagfinder.core.Profile;
import lagfinder.core.Report;
import lagfinder.core.ScanMode;

/**
 * The methods of a scan, as Spark shows them: the call tree of the scanned thread from its start down to the methods
 * that took the time, or the hot methods (the most time of their own, wherever they were called from). On the left,
 * the mods with the time each is to blame for; picking one shows only its part of the tree. The lines can be sorted
 * by their time in all, by their own time, or grouped by mod.
 */
public class GuiMethods extends ScaledScreen {

    private static final int ID_CLOSE = 0, ID_SORT = 1, ID_TREE = 2, ID_HOT = 3, ID_EXPAND = 4, ID_COLLAPSE = 5;
    private static final int ROW = 13, MOD_ROW = 18, INDENT = 7, MAX_INDENT = 40;
    private static final int SIDE_WIDTH = 176, DETAILS_HEIGHT = 40;
    private static final int SHARE_COLUMN = 50, TOTAL_COLUMN = 62, SELF_COLUMN = 62;
    private static final int SELECTED = 0xFF1E2C40;
    private static final int WARN = 0xFFF2C14E;

    private enum Sort {

        TOTAL("lagfinder.methods.sort.total"),
        SELF("lagfinder.methods.sort.self"),
        MOD("lagfinder.methods.sort.mod");

        final String key;

        Sort(String key) {
            this.key = key;
        }
    }

    /** The choices stay while the game runs; each view has its own sorting. */
    private static boolean hotView;
    private static Sort treeSort = Sort.TOTAL, hotSort = Sort.SELF;

    private final Report report;
    private final Profile profile;
    private final GuiScreen parent;
    /** The mod whose part is shown (index into the profile's mods), -1 for all. */
    private int modFilter = -1;
    private final Set<CallNode> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
    private CallNode selected;

    private static final class Row {

        final CallNode node;
        final int y, depth;

        Row(CallNode node, int y, int depth) {
            this.node = node;
            this.y = y;
            this.depth = depth;
        }
    }

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

    private final List<Row> rows = new ArrayList<>();
    private int contentHeight;
    /** Mods with any time, the most first. */
    private final List<Integer> modOrder = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int clipX0 = Integer.MIN_VALUE, clipY0 = Integer.MIN_VALUE, clipX1 = Integer.MAX_VALUE,
        clipY1 = Integer.MAX_VALUE;
    private final Map<Object, Smooth> lights = new HashMap<>();
    private int scroll, sideScroll;
    private final Smooth shownScroll = new Smooth(0), shownSideScroll = new Smooth(0);
    private final ScrollbarDrag listBar = new ScrollbarDrag(), sideBar = new ScrollbarDrag();
    /** Lines with a line matching the search somewhere under them. */
    private final Map<CallNode, Boolean> matchBelow = new IdentityHashMap<>();
    private String query = "";

    private int panelLeft, panelTop, panelRight, panelBottom;
    private int toolbarY, bodyBottom;
    private int sideLeft, sideRight, sideTop;
    private int listLeft, listRight, listTop, listBottom;
    private FlatTextField search;
    private FlatButton sortButton, treeButton, hotButton;
    private IconButton expandButton, collapseButton;
    private String lastSearch = "";
    /** The hottest line, opened when the window opens, is scrolled to once laid out. */
    private boolean scrollToSelected = true;

    public GuiMethods(Report report) {
        this.report = report;
        this.profile = report.profile;
        GuiScreen open = net.minecraft.client.Minecraft.getMinecraft().currentScreen;
        this.parent = open;
        for (int i = 0; i < profile.mods.size(); i++) {
            if (profile.modMs[i] > 0) {
                modOrder.add(i);
            }
        }
        modOrder.sort((a, b) -> Double.compare(profile.modMs[b], profile.modMs[a]));
        if (profile.root != null) {
            expanded.add(profile.root);
            expandHot(profile.root);
        }
    }

    /** Opens the methods with only the mod's part shown, when the scan has it. */
    public GuiMethods filtered(String mod) {
        int index = mod == null ? -1 : profile.modIndex(mod);
        if (index >= 0 && profile.modMs[index] > 0) {
            modFilter = index;
            expanded.clear();
            if (profile.root != null) {
                expanded.add(profile.root);
                expandHot(profile.root);
            }
        }
        return this;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        panelLeft = 6;
        panelTop = 4;
        panelRight = width - 6;
        panelBottom = height - 4;
        toolbarY = panelTop + WindowHeader.HEIGHT + 8;
        bodyBottom = panelBottom - 8;
        sideLeft = panelLeft + 8;
        sideRight = sideLeft + SIDE_WIDTH;
        sideTop = toolbarY + 22;
        listLeft = sideRight + 8;
        listRight = panelRight - 8;
        listTop = toolbarY + 34;
        listBottom = bodyBottom - DETAILS_HEIGHT - 6;

        buttonList.clear();
        int x = listLeft;
        treeButton = new FlatButton(ID_TREE, x, toolbarY, 84, 16, I18n.format("lagfinder.methods.tree"));
        treeButton.icon = Icons.SMALL_TREE;
        buttonList.add(treeButton);
        x += 84 + 3;
        hotButton = new FlatButton(ID_HOT, x, toolbarY, 104, 16, I18n.format("lagfinder.methods.hot"));
        hotButton.icon = Icons.SMALL_FLAME;
        buttonList.add(hotButton);
        x += 104 + 8;

        int right = listRight;
        collapseButton = new IconButton(
            ID_COLLAPSE,
            right - 20,
            toolbarY,
            Icons.SMALL_COLLAPSE,
            I18n.format("lagfinder.methods.collapse"));
        buttonList.add(collapseButton);
        expandButton = new IconButton(
            ID_EXPAND,
            right - 43,
            toolbarY,
            Icons.SMALL_EXPAND,
            I18n.format("lagfinder.methods.expand_hot"));
        buttonList.add(expandButton);
        sortButton = new FlatButton(ID_SORT, right - 43 - 4 - 116, toolbarY, 116, 16, "");
        sortButton.icon = Icons.SMALL_SORT;
        buttonList.add(sortButton);

        String text = search != null ? search.getText() : "";
        search = new FlatTextField(fontRendererObj, x, toolbarY, Math.max(60, sortButton.xPosition - 6 - x), 16);
        search.setHint(I18n.format("lagfinder.methods.search"));
        search.setMaxStringLength(80);
        search.setText(text);
        search.setOnCleared(this::rebuild);

        buttonList.add(WindowHeader.closeButton(ID_CLOSE, panelRight, panelTop));
        updateButtons();
        rebuild();
        if (scrollToSelected) {
            scrollToSelected = false;
            scrollTo(selected);
        }
    }

    private Sort sort() {
        return hotView ? hotSort : treeSort;
    }

    private void updateButtons() {
        treeButton.active = !hotView;
        hotButton.active = hotView;
        sortButton.displayString = I18n.format(sort().key);
        expandButton.visible = !hotView;
        collapseButton.visible = !hotView;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    // ---------------------------------------------------------------- What is shown

    /** The line's time in all, of the mod picked. */
    private double total(CallNode node) {
        return node.total(modFilter);
    }

    private double self(CallNode node) {
        return node.self(modFilter);
    }

    /** What the lines are sorted by and compared with. */
    private double value(CallNode node) {
        return sort() == Sort.SELF ? self(node) : total(node);
    }

    private Comparator<CallNode> order() {
        Comparator<CallNode> byValue = (a, b) -> Double.compare(value(b), value(a));
        if (sort() == Sort.MOD) {
            return (a, b) -> {
                int byMod = profile.modName(a.mod)
                    .compareToIgnoreCase(profile.modName(b.mod));
                return byMod != 0 ? byMod : Double.compare(total(b), total(a));
            };
        }
        return byValue;
    }

    private boolean matches(CallNode node) {
        if (query.isEmpty()) {
            return true;
        }
        return node.fullName()
            .toLowerCase(Locale.ROOT)
            .contains(query)
            || profile.modName(node.mod)
                .toLowerCase(Locale.ROOT)
                .contains(query);
    }

    private boolean hasMatchBelow(CallNode node) {
        Boolean known = matchBelow.get(node);
        if (known != null) {
            return known;
        }
        boolean found = false;
        for (CallNode child : node.children) {
            if (total(child) > 0 && (matches(child) || hasMatchBelow(child))) {
                found = true;
                break;
            }
        }
        matchBelow.put(node, found);
        return found;
    }

    /** The lines under the node shown: with time of the mod picked, and on the way to what is searched. */
    private List<CallNode> visibleChildren(CallNode node) {
        List<CallNode> list = new ArrayList<>();
        for (CallNode child : node.children) {
            if (total(child) <= 0) {
                continue;
            }
            if (!query.isEmpty() && !matches(child) && !hasMatchBelow(child)) {
                continue;
            }
            list.add(child);
        }
        list.sort(order());
        return list;
    }

    private boolean hasVisibleChild(CallNode node) {
        for (CallNode child : node.children) {
            if (total(child) > 0 && (query.isEmpty() || matches(child) || hasMatchBelow(child))) {
                return true;
            }
        }
        return false;
    }

    /** Lays the lines out again: after a change of view, sorting, mod, search or what is open. */
    private void rebuild() {
        rows.clear();
        lastSearch = search.getText();
        String newQuery = lastSearch.trim()
            .toLowerCase(Locale.ROOT);
        if (!newQuery.equals(query)) {
            query = newQuery;
        }
        matchBelow.clear();
        contentHeight = 0;
        if (hotView) {
            List<CallNode> methods = new ArrayList<>();
            for (CallNode method : profile.methods) {
                if ((self(method) > 0 || total(method) > 0 && sort() != Sort.SELF) && matches(method)) {
                    methods.add(method);
                }
            }
            methods.sort(order());
            for (CallNode method : methods) {
                rows.add(new Row(method, contentHeight, 0));
                contentHeight += ROW;
            }
        } else if (profile.root != null && total(profile.root) > 0) {
            rows.add(new Row(profile.root, 0, 0));
            contentHeight = ROW;
            addRows(profile.root, 1);
        }
        clampScroll();
    }

    private void addRows(CallNode node, int depth) {
        for (CallNode child : visibleChildren(node)) {
            rows.add(new Row(child, contentHeight, depth));
            contentHeight += ROW;
            boolean open = expanded.contains(child) || !query.isEmpty() && hasMatchBelow(child);
            if (open) {
                addRows(child, depth + 1);
            }
        }
    }

    private boolean isOpen(CallNode node) {
        return expanded.contains(node) || !query.isEmpty() && hasMatchBelow(node);
    }

    private int listHeight() {
        return listBottom - listTop;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, contentHeight - listHeight() + 2));
    }

    private int sideHeight() {
        return bodyBottom - sideTop - 18;
    }

    private void clampSideScroll() {
        sideScroll = Math.max(0, Math.min(sideScroll, (modOrder.size() + 1) * MOD_ROW - sideHeight()));
    }

    /** Opens the line, and the lines under it while there is only one. */
    private void open(CallNode node) {
        CallNode at = node;
        while (true) {
            expanded.add(at);
            List<CallNode> children = visibleChildren(at);
            if (children.size() != 1) {
                break;
            }
            at = children.get(0);
        }
    }

    /** Opens the way down from the node to where its time goes, following the line taking the most each step. */
    private void expandHot(CallNode node) {
        double rootTotal = Math.max(1e-9, total(profile.root));
        CallNode at = node;
        for (int guard = 0; guard < 1000; guard++) {
            expanded.add(at);
            CallNode best = null;
            for (CallNode child : at.children) {
                if (best == null || total(child) > total(best)) {
                    best = child;
                }
            }
            if (best == null || total(best) < rootTotal * 0.02) {
                selected = at;
                break;
            }
            at = best;
        }
    }

    private void toggle(CallNode node) {
        if (!expanded.remove(node)) {
            open(node);
        }
        rebuild();
    }

    private void setFilter(int mod) {
        modFilter = modFilter == mod ? -1 : mod;
        // What the mod's time goes through is opened anew.
        expanded.clear();
        if (profile.root != null) {
            expanded.add(profile.root);
            expandHot(profile.root);
        }
        scroll = 0;
        rebuild();
        scrollTo(selected);
    }

    /** Shows the method in the call tree where it took the most time, opening the way to it. */
    private void revealInTree(CallNode method) {
        CallNode best = find(profile.root, method.className, method.method, null);
        hotView = false;
        updateButtons();
        if (best == null) {
            rebuild();
            return;
        }
        for (CallNode up = best.parent; up != null; up = up.parent) {
            expanded.add(up);
        }
        selected = best;
        rebuild();
        scrollTo(best);
    }

    private CallNode find(CallNode node, String className, String method, CallNode best) {
        if (node == null) {
            return best;
        }
        if (node.className.equals(className) && node.method.equals(method)
            && (best == null || total(node) > total(best))) {
            best = node;
        }
        for (CallNode child : node.children) {
            if (total(child) > 0) {
                best = find(child, className, method, best);
            }
        }
        return best;
    }

    private void scrollTo(CallNode node) {
        for (Row row : rows) {
            if (row.node == node) {
                if (row.y < scroll || row.y + ROW > scroll + listHeight()) {
                    scroll = row.y - listHeight() / 3;
                    clampScroll();
                }
                return;
            }
        }
    }

    // ---------------------------------------------------------------- Input

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_CLOSE:
                close();
                return;
            case ID_SORT:
                if (hotView) {
                    hotSort = Sort.values()[(hotSort.ordinal() + 1) % Sort.values().length];
                } else {
                    treeSort = Sort.values()[(treeSort.ordinal() + 1) % Sort.values().length];
                }
                break;
            case ID_TREE:
            case ID_HOT:
                boolean hot = button.id == ID_HOT;
                if (hot != hotView) {
                    hotView = hot;
                    scroll = 0;
                }
                break;
            case ID_EXPAND:
                expandHot(selected != null && !hotView ? selected : profile.root);
                rebuild();
                scrollTo(selected);
                return;
            case ID_COLLAPSE:
                expanded.clear();
                if (profile.root != null) {
                    expanded.add(profile.root);
                }
                scroll = 0;
                break;
            default:
                return;
        }
        updateButtons();
        rebuild();
    }

    private void close() {
        GuiLagFinder.forgetReopen();
        mc.displayGuiScreen(parent);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0) {
            int next = listBar.press(
                mouseX,
                mouseY,
                listRight - 4,
                listTop + 3,
                listBottom - 3,
                listHeight(),
                contentHeight,
                (int) Math.round(shownScroll.get()),
                Math.max(0, contentHeight - listHeight() + 2));
            if (next >= 0) {
                scroll = next;
                shownScroll.set(scroll);
                return;
            }
            int total = (modOrder.size() + 1) * MOD_ROW;
            next = sideBar.press(
                mouseX,
                mouseY,
                sideRight - 4,
                sideTop + 3,
                bodyBottom - 20,
                sideHeight() - 1,
                total,
                (int) Math.round(shownSideScroll.get()),
                Math.max(0, total - sideHeight()));
            if (next >= 0) {
                sideScroll = next;
                shownSideScroll.set(sideScroll);
                return;
            }
        }
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
                return;
            }
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (button == 0) {
            int next = listBar.drag(mouseY);
            if (next >= 0) {
                scroll = next;
                shownScroll.set(scroll);
                return;
            }
            next = sideBar.drag(mouseY);
            if (next >= 0) {
                sideScroll = next;
                shownSideScroll.set(sideScroll);
                return;
            }
        }
        super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        if (button == 0) {
            listBar.release();
            sideBar.release();
        }
        super.mouseMovedOrUp(mouseX, mouseY, button);
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
        if (Theme.inside(x, y, listLeft, listTop, listRight, listBottom)) {
            scroll -= Integer.signum(wheel) * 4 * ROW;
            clampScroll();
        } else if (Theme.inside(x, y, sideLeft, sideTop, sideRight, bodyBottom)) {
            sideScroll -= Integer.signum(wheel) * 2 * MOD_ROW;
            clampSideScroll();
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
        if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
            moveSelection(keyCode == Keyboard.KEY_UP ? -1 : 1);
            return;
        }
        if (!search.isFocused() && selected != null && !hotView) {
            if (keyCode == Keyboard.KEY_RIGHT || keyCode == Keyboard.KEY_RETURN && !expanded.contains(selected)) {
                open(selected);
                rebuild();
                return;
            }
            if (keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_RETURN) {
                if (expanded.contains(selected)) {
                    expanded.remove(selected);
                } else if (selected.parent != null) {
                    selected = selected.parent;
                    scrollTo(selected);
                }
                rebuild();
                return;
            }
        }
        if (!search.isFocused() && selected != null && hotView && keyCode == Keyboard.KEY_RETURN) {
            revealInTree(selected);
            return;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            hotView = !hotView;
            scroll = 0;
            updateButtons();
            rebuild();
            return;
        }
        // Typing anywhere searches.
        if (!search.isFocused() && typedChar >= ' ') {
            search.setFocused(true);
        }
        if (search.textboxKeyTyped(typedChar, keyCode)) {
            scroll = 0;
            rebuild();
        }
    }

    private void moveSelection(int step) {
        if (rows.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).node == selected) {
                index = i;
            }
        }
        index = Math.max(0, Math.min(rows.size() - 1, index < 0 ? 0 : index + step));
        Row row = rows.get(index);
        selected = row.node;
        if (row.y < scroll) {
            scroll = row.y;
        } else if (row.y + ROW > scroll + listHeight()) {
            scroll = row.y + ROW - listHeight();
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

    private void clipHits(int x0, int y0, int x1, int y1) {
        clipX0 = x0;
        clipY0 = y0;
        clipX1 = x1;
        clipY1 = y1;
    }

    private void unclipHits() {
        clipX0 = clipY0 = Integer.MIN_VALUE;
        clipX1 = clipY1 = Integer.MAX_VALUE;
    }

    private double light(Object key, boolean on) {
        Smooth smooth = lights.get(key);
        if (smooth == null) {
            smooth = new Smooth(0);
            lights.put(key, smooth);
        }
        return smooth.update(on ? 1 : 0, 22);
    }

    private boolean tps() {
        return report.mode == ScanMode.TPS;
    }

    /** Milliseconds over the scan as milliseconds a tick (or frame). */
    private double perPeriod(double ms) {
        return report.periods <= 0 ? 0 : ms / report.periods;
    }

    /** Milliseconds over the scan as time a tick; in a frame scan, the frames a second it costs. */
    private String cost(double ms) {
        return report.costsInFps() ? Names.fpsLoss(report.fpsCost(perPeriod(ms))) : Names.ms(perPeriod(ms));
    }

    /** The share of all the sampled time, in percent. */
    private double share(double ms) {
        double all = profile.totalMs();
        return all <= 0 ? 0 : ms * 100 / all;
    }

    private static int shareColor(double percent) {
        if (percent >= 10) {
            return Theme.DANGER;
        }
        if (percent >= 2) {
            return WARN;
        }
        return percent >= 0.1 ? Theme.TEXT : Theme.TEXT_MUTED;
    }

    private static String percent(double value) {
        return value >= 10 ? String.format("%.1f%%", value) : String.format("%.2f%%", value);
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
        String title = I18n.format(
            report.bothSides ? "lagfinder.methods.title_both"
                : tps() ? "lagfinder.methods.title_tps" : "lagfinder.methods.title_fps");
        String subtitle = I18n.format(
            tps() ? "lagfinder.methods.subtitle" : "lagfinder.methods.subtitle_fps",
            profile.thread,
            profile.samples,
            formatInterval(profile.intervalMs),
            cost(profile.totalMs()));
        WindowHeader.draw(
            fontRendererObj,
            panelLeft,
            panelTop,
            panelRight,
            panelRight - WindowHeader.CLOSE_ROOM,
            Icons.SMALL_TREE,
            title,
            subtitle,
            Theme.TEXT_MUTED,
            modFilter >= 0 ? profile.modName(modFilter) : null);

        drawMods(mouseX, mouseY);
        drawList(mouseX, mouseY);
        drawDetails(mouseX, mouseY);
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
            showTooltip(I18n.format("lagfinder.tip.methods_sort"), mouseX, mouseY);
        } else if (expandButton.visible && expandButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(expandButton.tooltip, mouseX, mouseY);
        } else if (collapseButton.visible && collapseButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(collapseButton.tooltip, mouseX, mouseY);
        } else if (hotButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(I18n.format("lagfinder.methods.hot") + "\n" + I18n.format("lagfinder.tip.hot"), mouseX, mouseY);
        } else if (treeButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(
                I18n.format("lagfinder.methods.tree") + "\n" + I18n.format("lagfinder.tip.tree"),
                mouseX,
                mouseY);
        }
    }

    private static String formatInterval(float ms) {
        return ms == Math.floor(ms) ? String.valueOf((int) ms) : String.format("%.1f", ms);
    }

    /** A tooltip: its first line as a title, the rest (split at "\n" and wrapped) muted under it. */
    private void showTooltip(String text, int mouseX, int mouseY) {
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        String[] parts = text.split("\\\\n|\n");
        for (int i = 0; i < parts.length; i++) {
            for (Object line : fontRendererObj.listFormattedStringToWidth(parts[i], 280)) {
                lines.add((String) line);
                colors.add(i == 0 ? Theme.TEXT : Theme.TEXT_MUTED);
            }
        }
        Draw.tooltip(fontRendererObj, lines, colors, mouseX, mouseY, width, height);
    }

    // ---------------------------------------------------------------- The mods

    private void drawMods(int mouseX, int mouseY) {
        fontRendererObj.drawString(
            I18n.format("lagfinder.methods.mods")
                .toUpperCase(),
            sideLeft + 2,
            toolbarY + 2,
            Theme.TEXT_MUTED);
        drawModsBar(sideLeft, toolbarY + 13, sideRight, toolbarY + 18);
        Theme.card(sideLeft, sideTop, sideRight, bodyBottom);
        int top = sideTop + 1, bottom = bodyBottom - 18;
        int offset = (int) Math.round(shownSideScroll.update(sideScroll, 18));
        Theme.clip(sideLeft + 1, top, sideRight - 1, bottom);
        clipHits(sideLeft + 1, top, sideRight - 1, bottom);
        double most = modOrder.isEmpty() ? 1 : Math.max(1e-9, profile.modMs[modOrder.get(0)]);
        for (int i = -1; i < modOrder.size(); i++) {
            int y = top + (i + 1) * MOD_ROW - offset;
            if (y + MOD_ROW < top || y > bottom) {
                continue;
            }
            int mod = i < 0 ? -1 : modOrder.get(i);
            drawModRow(mod, y, most, mouseX, mouseY, top, bottom);
        }
        Theme.unclip();
        unclipHits();
        int total = (modOrder.size() + 1) * MOD_ROW;
        if (total > bottom - top) {
            Theme.scrollbar(
                sideRight - 4,
                top + 2,
                bottom - 2,
                bottom - top,
                total,
                offset / (double) Math.max(1, total - (bottom - top)),
                Theme.inside(mouseX, mouseY, sideLeft, top, sideRight, bottom));
        }
        // How the time is put down to mods.
        Theme.fill(sideLeft + 1, bottom, sideRight - 1, bottom + 1, Theme.BORDER);
        String hint = I18n.format("lagfinder.methods.blame");
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, hint, SIDE_WIDTH - 12),
            sideLeft + 6,
            bottom + 5,
            Theme.TEXT_DISABLED);
        hit(
            sideLeft,
            bottom,
            sideRight,
            bodyBottom,
            null,
            I18n.format("lagfinder.methods.blame") + "\n" + I18n.format("lagfinder.tip.blame"));
    }

    /** The mods' shares of all the time as one bar under their title: who takes how much, at a glance. */
    private void drawModsBar(int x0, int y0, int x1, int y1) {
        int shown = Math.min(8, modOrder.size());
        double[] values = new double[shown + 1];
        int[] colors = new int[shown + 1];
        double rest = profile.totalMs();
        for (int i = 0; i < shown; i++) {
            int mod = modOrder.get(i);
            values[i] = profile.modMs[mod];
            colors[i] = Draw.modColor(profile.modName(mod));
            rest -= values[i];
        }
        values[shown] = Math.max(0, rest);
        colors[shown] = 0xFF3A4350;
        int[] starts = Theme.stackedBar(x0, y0, x1, y1, values, colors);
        for (int i = 0; i < shown; i++) {
            int mod = modOrder.get(i);
            int from = starts[i], to = Math.max(from + 1, starts[i + 1]);
            if (modFilter == mod) {
                Theme.outline(from - 1, y0 - 1, to + 1, y1 + 1, Theme.TEXT);
            }
            hit(
                from,
                y0 - 2,
                to,
                y1 + 2,
                () -> setFilter(mod),
                profile.modName(mod) + "\n"
                    + I18n.format(tps() ? "lagfinder.methods.per_tick" : "lagfinder.methods.per_frame", cost(values[i]))
                    + "  ·  "
                    + percent(share(values[i]))
                    + "\n"
                    + I18n.format(modFilter == mod ? "lagfinder.tip.unpick_mod" : "lagfinder.tip.pick_mod"));
        }
    }

    private void drawModRow(int mod, int y, double most, int mouseX, int mouseY, int top, int bottom) {
        boolean all = mod < 0;
        boolean picked = modFilter == mod;
        boolean hovered = Theme
            .inside(mouseX, mouseY, sideLeft + 1, Math.max(top, y), sideRight - 1, Math.min(bottom, y + MOD_ROW));
        double lit = light("mod" + mod, hovered);
        if (picked) {
            Theme.fill(sideLeft + 1, y, sideRight - 1, y + MOD_ROW, SELECTED);
            Theme.fill(sideLeft + 1, y, sideLeft + 3, y + MOD_ROW, Theme.ACCENT);
        } else if (lit > 0.01) {
            Theme.fill(sideLeft + 1, y, sideRight - 1, y + MOD_ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        String name = all ? I18n.format("lagfinder.methods.all_mods") : profile.modName(mod);
        double ms = all ? profile.totalMs() : profile.modMs[mod];
        int color = all ? Theme.ACCENT : Draw.modColor(name);
        int x = sideLeft + 7;
        int textY = y + 4;
        if (all) {
            Icons.draw(Icons.SMALL_GRID, x - 1, textY, picked ? Theme.ACCENT : Theme.TEXT_MUTED);
        } else {
            Theme.fill(x, textY + 1, x + 6, textY + 7, color);
        }
        String value = all ? cost(ms) : percent(share(ms));
        int valueWidth = fontRendererObj.getStringWidth(value);
        int right = sideRight - 8;
        fontRendererObj.drawString(value, right - valueWidth, textY, all ? Theme.TEXT_MUTED : shareColor(share(ms)));
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, name, right - valueWidth - 6 - (x + 10)),
            x + 10,
            textY,
            picked || all ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, Math.max(lit, 0.6)));
        if (!all) {
            // Its time against the mod taking the most, as a thin bar along the bottom.
            int barWidth = (int) Math.round((sideRight - sideLeft - 14) * Math.min(1, ms / most));
            Theme.fill(
                sideLeft + 7,
                y + MOD_ROW - 3,
                sideLeft + 7 + Math.max(1, barWidth),
                y + MOD_ROW - 2,
                Theme.blend(color, 0x00000000, 0.35));
        }
        String tip = name + "\n"
            + I18n.format(tps() ? "lagfinder.methods.per_tick" : "lagfinder.methods.per_frame", cost(ms))
            + "  ·  "
            + percent(share(ms))
            + "\n"
            + I18n.format(
                all ? "lagfinder.tip.all_mods" : picked ? "lagfinder.tip.unpick_mod" : "lagfinder.tip.pick_mod");
        hit(sideLeft + 1, y, sideRight - 1, y + MOD_ROW, () -> setFilter(mod), tip);
    }

    // ---------------------------------------------------------------- The lines

    private void drawList(int mouseX, int mouseY) {
        int headY = toolbarY + 23;
        String nameTitle = I18n.format(hotView ? "lagfinder.methods.column.method" : "lagfinder.methods.column.call");
        fontRendererObj.drawString(nameTitle, listLeft + 4, headY, Theme.TEXT_DISABLED);
        String[] titles = { I18n.format("lagfinder.methods.column.share"),
            I18n.format(tps() ? "lagfinder.methods.column.total_tick" : "lagfinder.methods.column.total_frame"),
            I18n.format("lagfinder.methods.column.self") };
        String[] tips = { "lagfinder.tip.method_share", "lagfinder.tip.method_total", "lagfinder.tip.method_self" };
        int[] rights = { columnRight(0), columnRight(1), columnRight(2) };
        int[] widths = { SHARE_COLUMN, TOTAL_COLUMN, SELF_COLUMN };
        for (int i = 0; i < 3; i++) {
            boolean sorted = i == 2 ? sort() == Sort.SELF : i == 1 && sort() == Sort.TOTAL;
            fontRendererObj.drawString(
                titles[i],
                rights[i] - fontRendererObj.getStringWidth(titles[i]),
                headY,
                sorted ? Theme.ACCENT : Theme.TEXT_DISABLED);
            hit(
                rights[i] - widths[i] + 4,
                headY - 2,
                rights[i] + 2,
                headY + 10,
                null,
                titles[i] + "\n" + I18n.format(tips[i]));
        }

        Theme.card(listLeft, listTop - 1, listRight, listBottom);
        if (rows.isEmpty()) {
            String empty = I18n.format(
                profile.samples == 0 ? "lagfinder.methods.no_samples"
                    : lastSearch.isEmpty() ? "lagfinder.gui.nothing" : "lagfinder.gui.nothing_found");
            Theme.centered(fontRendererObj, empty, (listLeft + listRight) / 2, listTop + 20, Theme.TEXT_DISABLED);
            return;
        }
        int offset = (int) Math.round(shownScroll.update(scroll, 18));
        int top = listTop + 1, bottom = listBottom - 1;
        Theme.clip(listLeft + 1, top, listRight - 1, bottom);
        clipHits(listLeft + 1, top, listRight - 1, bottom);
        for (Row row : rows) {
            int y = top + row.y - offset;
            if (y + ROW < top || y > bottom) {
                continue;
            }
            drawRow(row, y, mouseX, mouseY);
        }
        Theme.unclip();
        unclipHits();
        if (contentHeight > listHeight()) {
            Theme.scrollbar(
                listRight - 4,
                top + 2,
                bottom - 2,
                listHeight(),
                contentHeight,
                offset / (double) Math.max(1, contentHeight - listHeight() + 2),
                Theme.inside(mouseX, mouseY, listLeft, top, listRight, bottom));
        }
    }

    /** Where a column of values ends: share, time in all, own time from the left. */
    private int columnRight(int column) {
        int right = listRight - 8;
        if (column == 2) {
            return right;
        }
        if (column == 1) {
            return right - SELF_COLUMN;
        }
        return right - SELF_COLUMN - TOTAL_COLUMN;
    }

    private void drawRow(Row row, int y, int mouseX, int mouseY) {
        CallNode node = row.node;
        boolean isRoot = !hotView && node == profile.root;
        boolean hovered = Theme.inside(mouseX, mouseY, listLeft + 1, y, listRight - 1, y + ROW)
            && Theme.inside(mouseX, mouseY, listLeft, listTop, listRight, listBottom);
        boolean isSelected = node == selected;
        double lit = light(node, hovered);
        double total = total(node), self = self(node);
        double share = share(total);
        int modColor = isRoot ? Theme.ACCENT : Draw.modColor(profile.modName(node.mod));

        // Its share as a soft bar behind the line, as Spark draws it.
        int barRight = listLeft + 1
            + (int) Math.round(
                (listRight - listLeft - 2) * Math.min(1, (hotView && sort() == Sort.SELF ? share(self) : share) / 100));
        Theme.fill(listLeft + 1, y, Math.max(listLeft + 1, barRight), y + ROW, (modColor & 0x00FFFFFF) | 0x14000000);
        if (isSelected) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + ROW, SELECTED);
            Theme.fill(listLeft + 1, y, listLeft + 3, y + ROW, Theme.ACCENT);
        } else if (lit > 0.01) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }

        int textY = y + (ROW - 8) / 2;
        // The values on the right.
        String shareText = percent(hotView && sort() == Sort.SELF ? share(self) : share);
        String totalText = cost(total);
        String selfText = self > 0 ? cost(self) : "—";
        fontRendererObj.drawString(
            shareText,
            columnRight(0) - fontRendererObj.getStringWidth(shareText),
            textY,
            shareColor(hotView && sort() == Sort.SELF ? share(self) : share));
        fontRendererObj
            .drawString(totalText, columnRight(1) - fontRendererObj.getStringWidth(totalText), textY, Theme.TEXT);
        fontRendererObj.drawString(
            selfText,
            columnRight(2) - fontRendererObj.getStringWidth(selfText),
            textY,
            self > 0 ? shareColor(share(self)) : Theme.TEXT_DISABLED);
        int textRight = columnRight(0) - SHARE_COLUMN + 2;
        if (!hotView && isHotspot(node)) {
            // Where the time is spent in its own code, not in what it calls: the place to look at.
            Icons.draw(
                Icons.SMALL_FLAME,
                textRight - 9,
                y + (ROW - 7) / 2,
                share(self) >= 5 ? Theme.DANGER : Theme.ORANGE);
            textRight -= 11;
        }

        int indent = Math.min(row.depth, MAX_INDENT) * INDENT;
        int x = listLeft + 6 + indent;
        boolean hasChildren = !hotView && hasVisibleChild(node);
        if (hotView) {
            hit(listLeft + 1, y, listRight - 1, y + ROW, () -> {
                if (selected == node) {
                    revealInTree(node);
                } else {
                    selected = node;
                }
            }, null);
        } else {
            hit(listLeft + 1, y, x + 8, y + ROW, hasChildren ? () -> toggle(node) : () -> selected = node, null);
            hit(x + 8, y, listRight - 1, y + ROW, () -> {
                boolean wasSelected = selected == node;
                selected = node;
                if (hasChildren && (wasSelected || !expanded.contains(node))) {
                    toggle(node);
                }
            }, null);
        }
        if (row.depth > MAX_INDENT) {
            // Deeper than the indent goes: told by a number instead.
            String depth = String.valueOf(row.depth);
            fontRendererObj
                .drawString(depth, x - fontRendererObj.getStringWidth(depth) - 3, textY, Theme.TEXT_DISABLED);
        }
        if (hasChildren) {
            String[] arrow = isOpen(node) ? Icons.SECTION_OPEN : Icons.SECTION_CLOSED;
            Icons.draw(
                arrow,
                x + (5 - Icons.width(arrow)) / 2,
                y + (ROW - arrow.length) / 2,
                hovered ? Theme.TEXT : Theme.TEXT_MUTED);
        }
        x += 9;

        if (isRoot) {
            Icons.draw(Icons.SMALL_TREE, x, y + 3, Theme.ACCENT);
            fontRendererObj.drawStringWithShadow(
                Theme.ellipsize(fontRendererObj, profile.thread, textRight - x - 10),
                x + 10,
                textY,
                Theme.TEXT);
            return;
        }
        Theme.fill(x, y + ROW / 2 - 2, x + 4, y + ROW / 2 + 2, modColor);
        x += 7;
        // In the list of hot methods, and when grouped by mod, the mod is named on the line.
        String mod = hotView || sort() == Sort.MOD ? "  " + profile.modName(node.mod) : "";
        int modWidth = mod.isEmpty() ? 0 : Math.min(fontRendererObj.getStringWidth(mod), 110);
        int room = textRight - x - modWidth;
        String className = node.simpleClassName();
        String method = "." + node.method + "()";
        boolean matched = !query.isEmpty() && matches(node);
        int classColor = matched ? WARN : Theme.TEXT_MUTED;
        int methodColor = matched ? WARN : Theme.TEXT;
        int classWidth = fontRendererObj.getStringWidth(className);
        int methodWidth = fontRendererObj.getStringWidth(method);
        if (classWidth + methodWidth <= room) {
            fontRendererObj.drawString(className, x, textY, classColor);
            fontRendererObj.drawString(method, x + classWidth, textY, methodColor);
            int after = x + classWidth + methodWidth;
            String pkg = "  " + node.packageName();
            if (after + 20 < x + room && !node.packageName()
                .isEmpty()) {
                fontRendererObj.drawString(
                    Theme.ellipsize(fontRendererObj, pkg, x + room - after),
                    after,
                    textY,
                    Theme.TEXT_DISABLED);
            }
        } else {
            // The method's name matters most: the class is cut first.
            String shownClass = Theme.ellipsize(fontRendererObj, className, Math.max(0, room - methodWidth));
            fontRendererObj.drawString(shownClass, x, textY, classColor);
            int after = x + fontRendererObj.getStringWidth(shownClass);
            fontRendererObj
                .drawString(Theme.ellipsize(fontRendererObj, method, x + room - after), after, textY, methodColor);
        }
        if (!mod.isEmpty()) {
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, mod, modWidth),
                textRight - modWidth,
                textY,
                Theme.blend(modColor, Theme.TEXT_DISABLED, 0.35));
        }
        if (hovered) {
            hit(x, y, textRight, y + ROW, null, tooltipOf(node));
        }
    }

    /** Whether a good part of all the time is spent in the node's own code. */
    private boolean isHotspot(CallNode node) {
        return node != profile.root && share(self(node)) >= 1;
    }

    private String tooltipOf(CallNode node) {
        StringBuilder text = new StringBuilder(node.fullName());
        text.append("\n")
            .append(I18n.format("lagfinder.methods.mod_of", profile.modName(node.mod)));
        text.append("\n")
            .append(
                I18n.format(
                    tps() ? "lagfinder.methods.line_tick" : "lagfinder.methods.line_frame",
                    cost(total(node)),
                    percent(share(total(node))),
                    cost(self(node))));
        if (!hotView && isHotspot(node)) {
            text.append("\n")
                .append(I18n.format("lagfinder.methods.hotspot", percent(share(self(node)))));
        }
        if (hotView) {
            text.append("\n")
                .append(I18n.format("lagfinder.tip.reveal_tree"));
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- The pick

    private void drawDetails(int mouseX, int mouseY) {
        int x0 = listLeft, y0 = listBottom + 6, x1 = listRight, y1 = bodyBottom;
        Theme.card(x0, y0, x1, y1);
        CallNode node = selected;
        CallNode hottest = node == null || node == profile.root && !hotView ? hottest() : null;
        if (hottest != null) {
            drawHottest(hottest, x0, y0, x1, mouseX, mouseY);
            return;
        }
        if (node == null || node == profile.root && !hotView) {
            Icons.draw(Icons.SMALL_SEARCH, x0 + 8, y0 + 8, Theme.TEXT_MUTED);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.methods.hint"), x1 - x0 - 30),
                x0 + 19,
                y0 + 8,
                Theme.TEXT_MUTED);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.methods.hint2"), x1 - x0 - 30),
                x0 + 19,
                y0 + 22,
                Theme.TEXT_DISABLED);
            return;
        }
        String modName = profile.modName(node.mod);
        int modColor = Draw.modColor(modName);
        // A button on the right: open the way down (tree), or show it in the tree (hot methods).
        String action = I18n.format(hotView ? "lagfinder.methods.show_tree" : "lagfinder.methods.expand_hot");
        String[] actionIcon = hotView ? Icons.SMALL_TREE : Icons.SMALL_EXPAND;
        int buttonWidth = fontRendererObj.getStringWidth(action) + 24;
        int bx = x1 - 8 - buttonWidth, by = y0 + 11;
        boolean overButton = Theme.inside(mouseX, mouseY, bx, by, bx + buttonWidth, by + 18);
        double buttonLit = light("action", overButton);
        Theme.glow(bx, by, bx + buttonWidth, by + 18, Theme.ACCENT, buttonLit * 0.6);
        Theme.gradient(
            bx,
            by,
            bx + buttonWidth,
            by + 18,
            Theme.blend(0xFF283040, 0xFF323C4C, buttonLit),
            Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, buttonLit));
        Theme.outline(bx, by, bx + buttonWidth, by + 18, Theme.blend(Theme.BORDER, Theme.ACCENT, buttonLit));
        Icons.draw(actionIcon, bx + 7, by + 6, Theme.TEXT);
        fontRendererObj.drawString(action, bx + 18, by + 5, Theme.TEXT);
        hit(bx, by, bx + buttonWidth, by + 18, () -> {
            if (hotView) {
                revealInTree(node);
            } else {
                expandHot(node);
                rebuild();
                scrollTo(selected);
            }
        }, null);

        int x = x0 + 8, y = y0 + 7;
        Theme.fill(x, y + 1, x + 6, y + 7, modColor);
        int pillWidth = fontRendererObj.getStringWidth(modName) + 10;
        int textRight = bx - 10;
        int pillX = textRight - pillWidth;
        Theme.pill(fontRendererObj, modName, pillX, y - 2, modColor);
        fontRendererObj.drawStringWithShadow(
            Theme.ellipsize(fontRendererObj, node.fullName(), pillX - 6 - (x + 10)),
            x + 10,
            y,
            Theme.TEXT);
        y += 14;
        double total = total(node), self = self(node);
        StringBuilder line = new StringBuilder();
        line.append(
            I18n.format(
                tps() ? "lagfinder.methods.line_tick" : "lagfinder.methods.line_frame",
                cost(total),
                percent(share(total)),
                cost(self)));
        line.append("  ·  ")
            .append(I18n.format("lagfinder.methods.samples", node.samples));
        if (!hotView && node.parent != null && node.parent != profile.root) {
            line.append("  ·  ")
                .append(I18n.format("lagfinder.methods.called_from", node.parent.shortName()));
        }
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, line.toString(), textRight - x), x, y, Theme.TEXT_MUTED);
    }

    /** The method with the most time of its own (of the mod picked), or null when none has any. */
    private CallNode hottest() {
        CallNode best = null;
        for (CallNode method : profile.methods) {
            if (self(method) > 0 && (best == null || self(method) > self(best))) {
                best = method;
            }
        }
        return best;
    }

    /** With nothing picked: the method taking the most time of its own, with a button to it in the tree. */
    private void drawHottest(CallNode method, int x0, int y0, int x1, int mouseX, int mouseY) {
        String action = I18n.format("lagfinder.methods.show_tree");
        int buttonWidth = fontRendererObj.getStringWidth(action) + 24;
        int bx = x1 - 8 - buttonWidth, by = y0 + 11;
        boolean overButton = Theme.inside(mouseX, mouseY, bx, by, bx + buttonWidth, by + 18);
        double buttonLit = light("action", overButton);
        Theme.glow(bx, by, bx + buttonWidth, by + 18, Theme.ORANGE, 0.3 + 0.5 * buttonLit);
        Theme.gradient(bx, by, bx + buttonWidth, by + 18, Theme.blend(0xFF3A2A1C, 0xFF4A3420, buttonLit), 0xFF2A1F17);
        Theme.outline(bx, by, bx + buttonWidth, by + 18, Theme.blend(0xFF6A4A2A, Theme.ORANGE, buttonLit));
        Icons.draw(Icons.SMALL_TREE, bx + 7, by + 6, Theme.TEXT);
        fontRendererObj.drawString(action, bx + 18, by + 5, Theme.TEXT);
        hit(bx, by, bx + buttonWidth, by + 18, () -> revealInTree(method), I18n.format("lagfinder.tip.hottest"));

        int x = x0 + 8, y = y0 + 7;
        double own = self(method);
        int color = share(own) >= 5 ? Theme.DANGER : Theme.ORANGE;
        Icons.draw(Icons.SMALL_FLAME, x, y, color);
        String label = I18n.format("lagfinder.methods.hottest")
            .toUpperCase() + "  ";
        fontRendererObj.drawString(label, x + 10, y, color);
        int after = x + 10 + fontRendererObj.getStringWidth(label);
        String modName = profile.modName(method.mod);
        int pillWidth = fontRendererObj.getStringWidth(modName) + 10;
        int textRight = bx - 10;
        Theme.pill(fontRendererObj, modName, textRight - pillWidth, y - 2, Draw.modColor(modName));
        fontRendererObj.drawStringWithShadow(
            Theme.ellipsize(fontRendererObj, method.shortName(), textRight - pillWidth - 6 - after),
            after,
            y,
            Theme.TEXT);
        y += 14;
        String line = I18n.format(
            tps() ? "lagfinder.methods.hottest_line" : "lagfinder.methods.hottest_line_fps",
            cost(own),
            percent(share(own)),
            cost(total(method)));
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, line, textRight - x), x, y, Theme.TEXT_MUTED);
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
