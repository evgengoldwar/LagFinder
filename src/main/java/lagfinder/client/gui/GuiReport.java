package lagfinder.client.gui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
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
import lagfinder.core.Category;
import lagfinder.core.Report;
import lagfinder.core.ReportEntry;
import lagfinder.core.ScanMode;
import lagfinder.network.Net;

/**
 * A finished scan. Across the top, how the server (or the client) did; under it, one tab per kind of work. On the
 * left, the tab's tree: mods, the kinds of things of each mod, and each of those things in the world, sorted by
 * their average time, their worst single tick or how often they spiked. On the right, the rate over the scan with
 * the load of what is picked over it (its average per tick in each moment and, lighter, its worst tick then), and
 * under it all about the pick, with buttons to teleport to it and highlight it in the world.
 */
public class GuiReport extends ScaledScreen {

    private static final int ID_CLOSE = 0, ID_SORT = 1;
    private static final int MOD_ROW = 18, TYPE_ROW = 16;
    private static final int AVG_COLUMN = 54, PEAK_COLUMN = 50;
    private static final int SELECTED = 0xFF1E2C40;
    private static final int WARN = 0xFFF2C14E, ORANGE = 0xFFFF8C42;
    /** The bars of the graph with nothing picked: the whole tick or frame. */
    private static final int TOTAL_COLOR = 0xFF7D8CA3;

    private enum Sort {

        AVERAGE("lagfinder.sort.average"),
        PEAK("lagfinder.sort.peak"),
        SPIKES("lagfinder.sort.spikes");

        final String key;

        Sort(String key) {
            this.key = key;
        }
    }

    private static Sort sort = Sort.AVERAGE;

    private final Report report;
    private final GuiScreen parent;
    private int tab;
    private final Set<ReportEntry> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
    private ReportEntry selected;

    /** A line of the tree as shown: the entry, how deep it is, and where (GUI pixels from the list's top). */
    private static final class Row {

        final ReportEntry entry;
        final int y, height;

        Row(ReportEntry entry, int y, int height) {
            this.entry = entry;
            this.y = y;
            this.height = height;
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
    private final List<Hit> hits = new ArrayList<>();
    private int clipX0 = Integer.MIN_VALUE, clipY0 = Integer.MIN_VALUE, clipX1 = Integer.MAX_VALUE,
        clipY1 = Integer.MAX_VALUE;
    private final Map<Object, Smooth> lights = new HashMap<>();
    private int scroll;
    private final Smooth shownScroll = new Smooth(0);
    private final Smooth tabMark = new Smooth(-1);

    private int panelLeft, panelTop, panelRight, panelBottom;
    private int tilesY, tabsY, bodyTop, bodyBottom;
    private int listLeft, listRight, listTop;
    private int rightLeft, rightRight, graphBottom;
    private FlatTextField search;
    private FlatButton sortButton;
    private String lastSearch = "";
    /** The worst things of the whole scan, for the overview: by time in all, and by worst tick. */
    private List<ReportEntry> byAverage, bySpike;
    /** What takes the most time of all: the kind of thing, or the one of them taking most of its time. */
    private ReportEntry suspect;
    /** The moment of the graph picked to see what lagged then (its index), -1 for none. */
    private int pinned = -1;
    /** What took the most time, and had the worst tick, in the moment picked. */
    private List<ReportEntry> pinnedByAverage, pinnedBySpike;
    /** The moment the rate was lowest in, -1 when it never dropped. */
    private int worstMoment = -1;

    public GuiReport(Report report) {
        this.report = report;
        GuiScreen open = net.minecraft.client.Minecraft.getMinecraft().currentScreen;
        this.parent = open instanceof GuiLagFinder || open instanceof GuiLogs ? open : null;
        Names.clear();
        ClientScanner.INSTANCE.markSeen();
        // Starts on the tab that took the most time.
        double most = -1;
        for (int i = 0; i < report.categories.size(); i++) {
            ReportEntry category = report.categories.get(i);
            if (category.totalMs > most && category.category != Category.WORLDS) {
                most = category.totalMs;
                tab = i;
            }
        }
        collectTops();
        suspect = findSuspect();
        float lowest = Float.MAX_VALUE;
        for (int i = 0; i < report.buckets(); i++) {
            if (report.rate[i] < lowest) {
                lowest = report.rate[i];
                worstMoment = i;
            }
        }
        if (worstMoment >= 0 && report.mode == ScanMode.TPS && lowest >= report.mode.targetRate() * 0.95f) {
            worstMoment = -1;
        }
    }

    /** The worst things of the whole scan, in all tabs but the worlds': by time in all, and by worst tick. */
    private void collectTops() {
        List<ReportEntry> leaves = new ArrayList<>();
        for (ReportEntry category : report.categories) {
            if (category.category != Category.WORLDS) {
                collectLeaves(category, leaves);
            }
        }
        byAverage = new ArrayList<>(leaves);
        byAverage.sort((a, b) -> Double.compare(b.totalMs, a.totalMs));
        bySpike = new ArrayList<>();
        for (ReportEntry leaf : leaves) {
            if (leaf.maxMs >= report.spikeMs) {
                bySpike.add(leaf);
            }
        }
        bySpike.sort((a, b) -> Float.compare(b.maxMs, a.maxMs));
    }

    /**
     * The kind of thing taking the most time of all; when one of them takes most of its kind's time, that one, so it
     * can be gone to.
     */
    private ReportEntry findSuspect() {
        ReportEntry best = null;
        for (ReportEntry category : report.categories) {
            if (category.category == Category.WORLDS) {
                continue;
            }
            for (ReportEntry mod : category.children) {
                for (ReportEntry type : mod.children) {
                    if (best == null || type.totalMs > best.totalMs) {
                        best = type;
                    }
                }
            }
        }
        if (best == null || best.totalMs <= 0) {
            return null;
        }
        ReportEntry top = null;
        for (ReportEntry child : best.children) {
            if (top == null || child.totalMs > top.totalMs) {
                top = child;
            }
        }
        return top != null && top.totalMs >= best.totalMs * 0.5 ? top : best;
    }

    /** Picks the moment of the graph to see what lagged then, or lets it go when it is picked already. */
    private void pin(int moment) {
        pinned = pinned == moment ? -1 : moment;
        selected = null;
        pinnedByAverage = pinnedBySpike = null;
        if (pinned < 0) {
            return;
        }
        List<ReportEntry> entries = new ArrayList<>();
        for (ReportEntry category : report.categories) {
            if (category.category != Category.WORLDS) {
                collectTimed(category, entries);
            }
        }
        int i = pinned;
        pinnedByAverage = new ArrayList<>();
        pinnedBySpike = new ArrayList<>();
        for (ReportEntry entry : entries) {
            if (i >= entry.sum.length || i >= entry.max.length) {
                continue;
            }
            if (report.perPeriod(entry, i) > 0) {
                pinnedByAverage.add(entry);
            }
            if (entry.max[i] >= report.spikeMs) {
                pinnedBySpike.add(entry);
            }
        }
        pinnedByAverage.sort((a, b) -> Float.compare(report.perPeriod(b, i), report.perPeriod(a, i)));
        pinnedBySpike.sort((a, b) -> Float.compare(b.max[i], a.max[i]));
    }

    /**
     * The lowest lines of the entry that kept their graph: the instances that did, or the kind when none of them did.
     */
    private static void collectTimed(ReportEntry entry, List<ReportEntry> out) {
        if (entry.level == 2) {
            boolean any = false;
            for (ReportEntry child : entry.children) {
                if (child.sum != null && child.max != null) {
                    out.add(child);
                    any = true;
                }
            }
            if (!any && entry.sum != null && entry.max != null) {
                out.add(entry);
            }
            return;
        }
        for (ReportEntry child : entry.children) {
            collectTimed(child, out);
        }
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        panelLeft = 6;
        panelTop = 4;
        panelRight = width - 6;
        panelBottom = height - 4;
        tilesY = panelTop + WindowHeader.HEIGHT + 8;
        tabsY = tilesY + 30 + 8;
        bodyTop = tabsY + 20 + 8;
        bodyBottom = panelBottom - 8;
        int inner = panelRight - panelLeft - 24;
        int listWidth = Math.max(250, Math.min(420, inner * 42 / 100));
        listLeft = panelLeft + 8;
        listRight = listLeft + listWidth;
        listTop = bodyTop + 34;
        rightLeft = listRight + 8;
        rightRight = panelRight - 8;
        graphBottom = bodyTop + (bodyBottom - bodyTop) * 52 / 100;

        String text = search != null ? search.getText() : "";
        search = new FlatTextField(fontRendererObj, listLeft, bodyTop, listWidth - 86, 16);
        search.setHint(I18n.format("lagfinder.gui.search"));
        search.setMaxStringLength(64);
        search.setText(text);
        search.setOnCleared(this::rebuild);

        buttonList.clear();
        sortButton = new FlatButton(ID_SORT, listRight - 82, bodyTop, 82, 16, "");
        sortButton.icon = Icons.SMALL_SORT;
        buttonList.add(sortButton);
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, panelRight, panelTop));
        updateSortButton();
        rebuild();
    }

    private void updateSortButton() {
        sortButton.displayString = I18n.format(sort.key);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private ReportEntry category() {
        return tab >= 0 && tab < report.categories.size() ? report.categories.get(tab) : null;
    }

    // ---------------------------------------------------------------- Tree

    private Comparator<ReportEntry> order() {
        switch (sort) {
            case PEAK:
                return (a, b) -> Float.compare(b.maxMs, a.maxMs);
            case SPIKES:
                return (a, b) -> a.spikes != b.spikes ? Integer.compare(b.spikes, a.spikes)
                    : Double.compare(b.totalMs, a.totalMs);
            default:
                return (a, b) -> Double.compare(b.totalMs, a.totalMs);
        }
    }

    private List<ReportEntry> sorted(List<ReportEntry> entries) {
        List<ReportEntry> list = new ArrayList<>(entries);
        list.sort(order());
        return list;
    }

    private static boolean matches(ReportEntry entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (Names.of(entry)
            .toLowerCase(Locale.ROOT)
            .contains(query)) {
            return true;
        }
        if (entry.detail != null && entry.detail.toLowerCase(Locale.ROOT)
            .contains(query)) {
            return true;
        }
        if (entry.label != null && entry.label.toLowerCase(Locale.ROOT)
            .contains(query)) {
            return true;
        }
        return entry.level == 3 && Names.position(entry)
            .contains(query);
    }

    /** Lays the tree out again: after a change of tab, sorting, search or what is open. */
    private void rebuild() {
        rows.clear();
        lastSearch = search.getText();
        String query = lastSearch.trim()
            .toLowerCase(Locale.ROOT);
        ReportEntry category = category();
        int y = 0;
        if (category != null) {
            for (ReportEntry mod : sorted(category.children)) {
                boolean modMatches = matches(mod, query);
                List<ReportEntry> types = new ArrayList<>();
                for (ReportEntry type : sorted(mod.children)) {
                    // A kind is found by its own name, or by one of its things (coordinates, a label).
                    if (modMatches || matches(type, query) || anyMatches(type.children, query)) {
                        types.add(type);
                    }
                }
                if (!modMatches && types.isEmpty()) {
                    continue;
                }
                rows.add(new Row(mod, y, MOD_ROW));
                y += MOD_ROW;
                if (!expanded.contains(mod) && query.isEmpty()) {
                    continue;
                }
                // The things of a kind are not lines of the tree: its button opens them as cards.
                for (ReportEntry type : types) {
                    rows.add(new Row(type, y, TYPE_ROW));
                    y += TYPE_ROW;
                }
            }
        }
        contentHeight = y;
        clampScroll();
    }

    private static boolean anyMatches(List<ReportEntry> entries, String query) {
        if (query.isEmpty()) {
            return false;
        }
        for (ReportEntry entry : entries) {
            if (matches(entry, query)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the line opens and closes: only mods do, their kinds are under them. */
    private static boolean expandable(ReportEntry entry) {
        return entry.level == 1 && !entry.children.isEmpty();
    }

    private int listHeight() {
        return bodyBottom - listTop;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, contentHeight - listHeight()));
    }

    /** Picks the entry, opening the lines above it and scrolling it into view (from the overview or the details). */
    private void reveal(ReportEntry entry) {
        if (entry.level == 3) {
            // Things aren't lines of the tree: their kind is picked, and its cards open at this one.
            reveal(entry.parent);
            mc.displayGuiScreen(new GuiInstances(report, entry.parent, this).focusOn(entry));
            return;
        }
        int index = report.categories.indexOf(root(entry));
        if (index >= 0 && index != tab) {
            tab = index;
            if (!search.getText()
                .isEmpty()) {
                search.setText("");
            }
        }
        for (ReportEntry up = entry.parent; up != null; up = up.parent) {
            expanded.add(up);
        }
        selected = entry;
        rebuild();
        for (Row row : rows) {
            if (row.entry == entry) {
                if (row.y < scroll || row.y + row.height > scroll + listHeight()) {
                    scroll = row.y - listHeight() / 3;
                    clampScroll();
                }
                break;
            }
        }
    }

    private static ReportEntry root(ReportEntry entry) {
        while (entry.parent != null) {
            entry = entry.parent;
        }
        return entry;
    }

    private void select(ReportEntry entry) {
        selected = entry;
    }

    private void toggle(ReportEntry entry) {
        if (!expanded.remove(entry)) {
            expanded.add(entry);
        }
        rebuild();
    }

    // ---------------------------------------------------------------- Input

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            close();
        } else if (button.id == ID_SORT) {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            updateSortButton();
            rebuild();
        }
    }

    private void close() {
        GuiLagFinder.forgetReopen();
        mc.displayGuiScreen(parent);
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
        if (Theme.inside(x, y, listLeft, listTop, listRight, bodyBottom)) {
            scroll -= Integer.signum(wheel) * 3 * TYPE_ROW;
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
        if (keyCode == Keyboard.KEY_BACK && !search.isFocused() && (selected != null || pinned >= 0)) {
            // Back to the overview: of the moment picked, then of the whole scan.
            if (selected != null) {
                selected = null;
            } else {
                pin(pinned);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
            moveSelection(keyCode == Keyboard.KEY_UP ? -1 : 1);
            return;
        }
        if ((keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_RIGHT) && selected != null
            && !search.isFocused()) {
            boolean open = keyCode == Keyboard.KEY_RIGHT;
            if (open && expandable(selected)) {
                expanded.add(selected);
            } else if (!open && expanded.contains(selected)) {
                expanded.remove(selected);
            } else if (!open && selected.parent != null && selected.parent.level > 0) {
                selected = selected.parent;
            }
            rebuild();
            return;
        }
        if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) && selected != null
            && selected.level < 3
            && GuiInstances.hasInstances(selected)) {
            openCards(selected);
            return;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            tab = (tab + (isShiftKeyDown() ? report.categories.size() - 1 : 1)) % report.categories.size();
            scroll = 0;
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
            if (rows.get(i).entry == selected) {
                index = i;
            }
        }
        index = Math.max(0, Math.min(rows.size() - 1, index < 0 ? 0 : index + step));
        Row row = rows.get(index);
        selected = row.entry;
        if (row.y < scroll) {
            scroll = row.y;
        } else if (row.y + row.height > scroll + listHeight()) {
            scroll = row.y + row.height - listHeight();
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

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        hits.clear();
        if (!search.getText()
            .equals(lastSearch)) {
            rebuild();
        }
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(panelLeft, panelTop, panelRight, panelBottom);
        String title = I18n
            .format(report.mode == ScanMode.TPS ? "lagfinder.report.title_tps" : "lagfinder.report.title_fps");
        String date = new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(report.startedAt));
        String subtitle = date + "  ·  "
            + Names.clock(report.durationSec)
            + (report.source == null || report.source.isEmpty() ? "" : "  ·  " + report.source);
        boolean warned = !report.warnings.isEmpty();
        if (warned) {
            // Something the scan couldn't measure as it should: said up front, with what in the tooltip.
            subtitle = I18n.format("lagfinder.warning.count", report.warnings.size()) + "  ·  " + subtitle;
        }
        WindowHeader.draw(
            fontRendererObj,
            panelLeft,
            panelTop,
            panelRight,
            panelRight - WindowHeader.CLOSE_ROOM,
            Icons.STATS,
            title,
            subtitle,
            warned ? WARN : Theme.TEXT_MUTED,
            null);
        if (warned) {
            StringBuilder tip = new StringBuilder(I18n.format("lagfinder.warning.title"));
            for (String warning : report.warnings) {
                tip.append("\n- ")
                    .append(I18n.format(warning));
            }
            hit(
                panelLeft + 28,
                panelTop + 16,
                panelRight - WindowHeader.CLOSE_ROOM,
                panelTop + 28,
                null,
                tip.toString());
        }

        drawTiles(mouseX, mouseY);
        drawTabs(mouseX, mouseY);
        drawList(mouseX, mouseY);
        drawGraph(mouseX, mouseY);
        drawDetails(mouseX, mouseY);
        search.drawTextBox();
        super.drawScaled(mouseX, mouseY, partialTicks);

        // Tooltips of what is under the mouse, over everything else.
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
            showTooltip(I18n.format("lagfinder.tip.sort"), mouseX, mouseY);
        }
    }

    /** A tooltip: its first line as a title, the rest (split at "\n" and wrapped) muted under it. */
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

    /**
     * Health of the whole scan: rate, worst moment, average and worst tick; and on the right what takes the most
     * time of all, a click from it.
     */
    private void drawTiles(int mouseX, int mouseY) {
        boolean tps = report.mode == ScanMode.TPS;
        float target = report.mode.targetRate();
        float budget = report.mode.budgetMs();
        float average = report.averageRate(), minimum = report.minimumRate();
        float averageMs = report.averagePeriodMs(), worstMs = report.worstPeriodMs();
        String[] labels = { I18n.format(tps ? "lagfinder.tile.avg_tps" : "lagfinder.tile.avg_fps"),
            I18n.format(tps ? "lagfinder.tile.min_tps" : "lagfinder.tile.min_fps"),
            I18n.format(tps ? "lagfinder.tile.avg_tick" : "lagfinder.tile.avg_frame"),
            I18n.format(tps ? "lagfinder.tile.worst_tick" : "lagfinder.tile.worst_frame"),
            I18n.format(tps ? "lagfinder.tile.ticks" : "lagfinder.tile.frames") };
        String[] values = { String.format("%.1f", average), String.format("%.1f", minimum), Names.ms(averageMs),
            Names.ms(worstMs), String.valueOf(report.periods) };
        int[] colors = { Draw.rateColor(average, target), Draw.rateColor(minimum, target),
            averageMs > budget ? Theme.DANGER : averageMs > budget * 0.7f ? WARN : Theme.SUCCESS,
            worstMs > budget * 4 ? Theme.DANGER : worstMs > budget ? WARN : Theme.SUCCESS, Theme.TEXT };
        if (!tps) {
            // Frames have no fixed target: the rate is told plainly, and the worst frame as the rate it ran at.
            float worstFps = worstMs > 0 ? 1000f / worstMs : 0;
            labels = new String[] { labels[0], labels[1], I18n.format("lagfinder.tile.worst_frame_fps"), labels[4] };
            values = new String[] { values[0], values[1], Names.fps(worstFps), values[4] };
            colors = new int[] { fpsColor(average), fpsColor(minimum), fpsColor(worstFps), Theme.TEXT };
            double waitMs = report.averageWaitMs();
            if (waitMs > 0.05) {
                // How much of a frame is work, and what holds the frames: costs in FPS count only the work.
                double frameMs = report.wallPeriodMs();
                labels[3] = report.frameCap > 0
                    ? I18n.format("lagfinder.tile.frame_work_cap", Math.round(report.frameCap))
                    : I18n.format("lagfinder.tile.frame_work");
                values[3] = String.format("%.1f / %.1f ms", Math.max(0, frameMs - waitMs), frameMs);
                colors[3] = waitMs > frameMs * 0.25 ? Theme.SUCCESS : WARN;
            }
            if (report.rateBefore > 0) {
                // What the game ran at just before, to compare with.
                labels = new String[] { labels[0], I18n.format("lagfinder.tile.fps_before"), labels[1], labels[2],
                    labels[3] };
                values = new String[] { values[0], String.format("%.1f", report.rateBefore), values[1], values[2],
                    values[3] };
                colors = new int[] { colors[0], fpsColor(report.rateBefore), colors[1], colors[2], colors[3] };
            }
        }
        int count = labels.length, gap = 6;
        int left = panelLeft + 8, right = panelRight - 8;
        int suspectWidth = suspect == null ? 0 : Math.max(220, (right - left) * 36 / 100);
        int metricsRight = right - (suspect == null ? 0 : suspectWidth + gap);
        int each = (metricsRight - left - gap * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            int x0 = left + i * (each + gap), x1 = i == count - 1 ? metricsRight : x0 + each;
            Theme.card(x0, tilesY, x1, tilesY + 30);
            Theme.gradient(x0, tilesY, x0 + 2, tilesY + 30, colors[i], Theme.blend(colors[i], 0xFF000000, 0.4));
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, labels[i], each - 12),
                x0 + 8,
                tilesY + 5,
                Theme.TEXT_MUTED);
            fontRendererObj.drawStringWithShadow(values[i], x0 + 8, tilesY + 17, colors[i]);
            if (i == 0) {
                // The rate over the scan, small, beside its average.
                int sx0 = x0 + 14 + fontRendererObj.getStringWidth(values[0]), sx1 = x1 - 6;
                if (sx1 - sx0 >= 24) {
                    drawSparkline(sx0, tilesY + 14, sx1, tilesY + 26, target);
                }
            }
        }
        if (suspect != null) {
            drawSuspect(metricsRight + gap, tilesY, right, tilesY + 30, mouseX, mouseY);
        }
    }

    /** The rate over the whole scan as a small line, colored by how good it was. */
    private void drawSparkline(int x0, int y0, int x1, int y1, float target) {
        int n = report.buckets();
        if (n < 2) {
            return;
        }
        int points = Math.min(n, (x1 - x0) / 2);
        double[] xs = new double[points], ys = new double[points];
        int[] colors = new int[points];
        float top = report.mode == ScanMode.TPS ? 20f : (float) Math.max(30, max(report.rate));
        for (int p = 0; p < points; p++) {
            // The lowest rate of the moments under each point: the dips must not be smoothed away.
            int from = (int) ((long) p * n / points), to = Math.max(from + 1, (int) ((long) (p + 1) * n / points));
            float low = Float.MAX_VALUE;
            for (int i = from; i < to && i < n; i++) {
                low = Math.min(low, report.rate[i]);
            }
            xs[p] = x0 + (x1 - x0) * p / (double) (points - 1);
            ys[p] = y1 - (y1 - y0) * Math.min(1, low / top);
            colors[p] = Draw.rateColor(low, report.mode == ScanMode.TPS ? target : 60f);
        }
        int[] tops = new int[points], bottoms = new int[points];
        for (int p = 0; p < points; p++) {
            tops[p] = (colors[p] & 0x00FFFFFF) | 0x30000000;
            bottoms[p] = colors[p] & 0x00FFFFFF;
        }
        Draw.area(xs, ys, y1, tops, bottoms);
        Draw.line(xs, ys, colors, 1f);
    }

    /**
     * What takes the most time of all, with its share of the tick: named the cause of the lag when the rate dropped
     * and it takes a good part of it. A click shows it in its tab.
     */
    private void drawSuspect(int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        ReportEntry entry = suspect;
        double average = report.perPeriod(entry);
        double share = report.averagePeriodMs() <= 0 ? 0 : average / report.averagePeriodMs() * 100;
        boolean lagging = fps() || report.averageRate() < report.mode.targetRate() * 0.97f
            || report.minimumRate() < report.mode.targetRate() * 0.75f;
        boolean cause = lagging && share >= 15;
        int color = cause ? Theme.DANGER : share >= 5 ? ORANGE : Theme.ACCENT;
        boolean hovered = Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
        double lit = light("suspect", hovered);
        if (cause) {
            // Breathing softly, so it is seen first.
            double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 400.0);
            Theme.glow(x0, y0, x1, y1, color, 0.25 + 0.35 * pulse + 0.4 * lit);
        } else {
            Theme.glow(x0, y0, x1, y1, color, lit * 0.6);
        }
        Theme.gradient(
            x0,
            y0,
            x1,
            y1,
            Theme.blend(0xFF13171D, color, 0.10 + 0.06 * lit),
            Theme.blend(0xFF101318, color, 0.04));
        Theme.outline(x0, y0, x1, y1, Theme.blend(Theme.BORDER, color, 0.5 + 0.5 * lit));
        Theme.gradient(x0, y0, x0 + 2, y1, color, Theme.blend(color, 0xFF000000, 0.4));
        int x = x0 + 8;
        ItemStack stack = Names.icon(entry);
        if (stack != null) {
            Draw.item(stack, x, y0 + 7, 16);
        } else {
            Icons.draw(icon(entry.category), x + 4, y0 + 11, color);
        }
        x += 21;
        String value = avgText(average) + "  ·  " + String.format("%.0f%%", share);
        int valueWidth = fontRendererObj.getStringWidth(value);
        String label = I18n.format(cause ? "lagfinder.suspect.cause" : "lagfinder.suspect.heaviest")
            .toUpperCase();
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, label, x1 - 8 - valueWidth - 6 - x), x, y0 + 5, color);
        fontRendererObj.drawString(value, x1 - 8 - valueWidth, y0 + 5, severity(average));
        String name = Names.of(entry);
        String where = entry.level == 3 ? Names.position(entry)
            : entry.category.hasInstances ? I18n.format("lagfinder.suspect.kind", instancesOf(entry))
                : Names.category(entry.category);
        String mod = modOf(entry);
        String shownName = Theme.ellipsize(fontRendererObj, name, x1 - 8 - x);
        fontRendererObj.drawStringWithShadow(shownName, x, y0 + 17, Theme.TEXT);
        int after = x + fontRendererObj.getStringWidth(shownName) + 6;
        String rest = where + "  ·  " + mod;
        if (after + 20 < x1 - 8) {
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, rest, x1 - 8 - after),
                after,
                y0 + 17,
                Theme.TEXT_DISABLED);
        }
        hit(x0, y0, x1, y1, () -> {
            pinned = -1;
            reveal(entry);
        },
            name + "\n"
                + mod
                + "  ·  "
                + Names.category(entry.category)
                + "\n"
                + I18n.format(
                    cause ? "lagfinder.tip.suspect_cause" : "lagfinder.tip.suspect",
                    String.format("%.0f%%", share))
                + "\n"
                + I18n.format("lagfinder.tip.reveal"));
    }

    private static int fpsColor(float fps) {
        return fps >= 55 ? Theme.SUCCESS : fps >= 30 ? WARN : Theme.DANGER;
    }

    private static String[] icon(Category category) {
        switch (category) {
            case TILE_ENTITIES:
                return Icons.SMALL_CUBE;
            case ENTITIES:
                return Icons.SMALL_CREEPER;
            case BLOCK_TICKS:
                return Icons.SMALL_CLOCK;
            case EVENTS:
                return Icons.SMALL_BOLT;
            case WORLDS:
                return Icons.SMALL_GLOBE;
            case TILE_RENDER:
                return Icons.SMALL_FRAME;
            case ENTITY_RENDER:
                return Icons.SMALL_PERSON;
            case AE2_NETWORKS:
                return Icons.SMALL_NETWORK;
            default:
                return Icons.SMALL_GRID;
        }
    }

    private void drawTabs(int mouseX, int mouseY) {
        int count = report.categories.size();
        if (count == 0) {
            return;
        }
        int inner = panelRight - panelLeft - 16;
        int each = inner / count;
        if (tabMark.get() < 0) {
            tabMark.set(panelLeft + 8 + tab * each);
        }
        double markX = tabMark.update(panelLeft + 8 + tab * each, 18);
        Theme.fill(panelLeft + 8, tabsY + 19, panelRight - 8, tabsY + 20, Theme.BORDER);
        // The worlds' time holds the others': measured against the rest only, it has no bar.
        double heaviest = 1e-9;
        for (ReportEntry category : report.categories) {
            if (category.category != Category.WORLDS) {
                heaviest = Math.max(heaviest, category.totalMs);
            }
        }
        for (int i = 0; i < count; i++) {
            ReportEntry category = report.categories.get(i);
            int x0 = panelLeft + 8 + i * each, x1 = i == count - 1 ? panelRight - 8 : x0 + each;
            boolean active = i == tab;
            boolean hovered = Theme.inside(mouseX, mouseY, x0, tabsY, x1, tabsY + 19);
            double lit = light(category, hovered || active);
            if (active) {
                Theme.gradient(x0, tabsY, x1, tabsY + 19, Theme.blend(0x001A2330, 0xFF1F2B3B, lit), 0xFF1A2330);
            } else {
                Theme.fill(x0, tabsY, x1, tabsY + 19, Theme.blend(0x00000000, 0xFF1A1F27, lit));
            }
            // How much of all the time is spent in the tab, as a thin bar along its bottom.
            double load = category.totalMs / heaviest;
            if (load > 0.005 && category.category != Category.WORLDS) {
                int barRight = x0 + 3 + (int) Math.round((x1 - x0 - 6) * Math.min(1, load));
                int heat = severity(report.perPeriod(category));
                Theme.fill(
                    x0 + 3,
                    tabsY + 16,
                    Math.max(x0 + 4, barRight),
                    tabsY + 17,
                    Theme.blend(heat == Theme.TEXT ? Theme.ACCENT : heat, 0x00000000, active ? 0.2 : 0.5));
            }
            int color = active ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
            Icons.draw(icon(category.category), x0 + 6, tabsY + 6, active ? Theme.ACCENT : Theme.TEXT_MUTED);
            String value = avgText(report.perPeriod(category));
            int valueWidth = fontRendererObj.getStringWidth(value);
            String name = Theme
                .ellipsize(fontRendererObj, Names.category(category.category, report.mode), x1 - x0 - 26 - valueWidth);
            fontRendererObj.drawString(name, x0 + 17, tabsY + 6, color);
            fontRendererObj
                .drawString(value, x1 - 5 - valueWidth, tabsY + 6, active ? Theme.ACCENT : Theme.TEXT_DISABLED);
            int index = i;
            hit(x0, tabsY, x1, tabsY + 19, () -> {
                if (tab != index) {
                    tab = index;
                    scroll = 0;
                    rebuild();
                }
            },
                I18n.format("lagfinder.category." + category.category.id) + "\n"
                    + I18n.format("lagfinder.tip.category." + category.category.id));
        }
        int markWidth = (int) (each * 1.0);
        Theme.fill((int) markX, tabsY + 18, (int) markX + markWidth, tabsY + 20, Theme.ACCENT);
    }

    // ---------------------------------------------------------------- The tree

    private void drawList(int mouseX, int mouseY) {
        ReportEntry category = category();
        // Column titles.
        int headY = bodyTop + 21;
        fontRendererObj.drawString(I18n.format("lagfinder.column.name"), listLeft + 4, headY, Theme.TEXT_DISABLED);
        String avg = I18n
            .format(report.mode == ScanMode.TPS ? "lagfinder.column.avg_tick" : "lagfinder.column.avg_frame");
        String peak = I18n.format(inFps() ? "lagfinder.column.peak_fps" : "lagfinder.column.peak");
        fontRendererObj.drawString(
            avg,
            listRight - PEAK_COLUMN - 6 - fontRendererObj.getStringWidth(avg),
            headY,
            sort == Sort.AVERAGE ? Theme.ACCENT : Theme.TEXT_DISABLED);
        fontRendererObj.drawString(
            peak,
            listRight - 6 - fontRendererObj.getStringWidth(peak),
            headY,
            sort == Sort.PEAK ? Theme.ACCENT : Theme.TEXT_DISABLED);
        hit(
            listRight - PEAK_COLUMN - AVG_COLUMN,
            headY - 2,
            listRight - PEAK_COLUMN,
            headY + 10,
            null,
            I18n.format(inFps() ? "lagfinder.tip.avg_fps" : "lagfinder.tip.avg"));
        hit(
            listRight - PEAK_COLUMN,
            headY - 2,
            listRight,
            headY + 10,
            null,
            I18n.format(inFps() ? "lagfinder.tip.peak_fps" : "lagfinder.tip.peak"));

        Theme.card(listLeft, listTop - 1, listRight, bodyBottom);
        if (category == null || rows.isEmpty()) {
            String unhooked = category == null ? null : "lagfinder.warning.unhooked." + category.category.id;
            if (unhooked != null && lastSearch.isEmpty() && report.warnings.contains(unhooked)) {
                // Not "nothing here": it couldn't be measured.
                List<?> lines = fontRendererObj
                    .listFormattedStringToWidth(I18n.format(unhooked), listRight - listLeft - 24);
                for (int i = 0; i < lines.size(); i++) {
                    Theme.centered(
                        fontRendererObj,
                        (String) lines.get(i),
                        (listLeft + listRight) / 2,
                        listTop + 20 + i * 11,
                        WARN);
                }
                return;
            }
            String empty = I18n.format(lastSearch.isEmpty() ? "lagfinder.gui.nothing" : "lagfinder.gui.nothing_found");
            Theme.centered(fontRendererObj, empty, (listLeft + listRight) / 2, listTop + 20, Theme.TEXT_DISABLED);
            return;
        }
        int offset = (int) Math.round(shownScroll.update(scroll, 18));
        int top = listTop + 1, bottom = bodyBottom - 1;
        Theme.clip(listLeft + 1, top, listRight - 1, bottom);
        clipHits(listLeft + 1, top, listRight - 1, bottom);
        double categoryTotal = Math.max(1e-9, category.totalMs);
        for (Row row : rows) {
            int y = top + row.y - offset;
            if (y + row.height < top || y > bottom) {
                continue;
            }
            drawRow(row, y, mouseX, mouseY, categoryTotal);
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
                offset / (double) Math.max(1, contentHeight - listHeight()),
                Theme.inside(mouseX, mouseY, listLeft, top, listRight, bottom));
        }
    }

    private boolean fps() {
        return report.mode == ScanMode.FPS;
    }

    /** Whether costs are told in frames a second (not when the frames were held to a limit: ms of a frame then). */
    private boolean inFps() {
        return report.costsInFps();
    }

    /** Time per tick in milliseconds; in a frame scan, the frames a second it costs. */
    private String avgText(double perPeriodMs) {
        return inFps() ? Names.fpsLoss(report.fpsCost(perPeriodMs)) : Names.ms(perPeriodMs);
    }

    /** The entry's worst tick in milliseconds; in a frame scan, how far the frames a second fell then. */
    private String peakText(ReportEntry entry) {
        return inFps() ? Names.fpsLoss(report.fpsDrop(report.perPeriod(entry), entry.maxMs)) : Names.ms(entry.maxMs);
    }

    private int severity(double perPeriodMs) {
        float budget = report.mode.budgetMs();
        if (perPeriodMs >= budget * 0.1) {
            return Theme.DANGER;
        }
        if (perPeriodMs >= budget * 0.02) {
            return WARN;
        }
        return Theme.TEXT;
    }

    private int peakColor(ReportEntry entry) {
        float budget = report.mode.budgetMs();
        if (entry.maxMs >= budget * 0.5f) {
            return Theme.DANGER;
        }
        if (entry.maxMs >= report.spikeMs) {
            return ORANGE;
        }
        return Theme.TEXT_MUTED;
    }

    /** Whether it now and then takes far longer than it usually does: what lags "once in a while". */
    private boolean isSpiky(ReportEntry entry) {
        double average = report.perPeriod(entry);
        return entry.maxMs >= report.spikeMs * 2 && entry.maxMs > average * 8;
    }

    private void drawRow(Row row, int y, int mouseX, int mouseY, double categoryTotal) {
        ReportEntry entry = row.entry;
        boolean hovered = Theme.inside(mouseX, mouseY, listLeft + 1, y, listRight - 1, y + row.height)
            && Theme.inside(mouseX, mouseY, listLeft, listTop, listRight, bodyBottom);
        boolean isSelected = entry == selected;
        double lit = light(entry, hovered);
        // Its share of the tab as a soft bar behind the line, in its mod's color.
        int shareRight = listLeft + 1
            + (int) Math.round((listRight - listLeft - 2) * Math.min(1, entry.totalMs / categoryTotal));
        if (shareRight > listLeft + 1) {
            Theme.fill(
                listLeft + 1,
                y,
                shareRight,
                y + row.height,
                (Draw.modColor(modOf(entry)) & 0x00FFFFFF) | 0x12000000);
        }
        if (entry.level == 1 && row.y > 0) {
            // Each mod starts a group.
            Theme.fill(listLeft + 1, y, listRight - 1, y + 1, 0xFF1C2129);
        }
        if (isSelected) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + row.height, SELECTED);
            Theme.fill(listLeft + 1, y, listLeft + 3, y + row.height, Theme.ACCENT);
        } else if (lit > 0.01) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + row.height, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        int indent = (entry.level - 1) * 12;
        int x = listLeft + 6 + indent;
        int textY = y + (row.height - 8) / 2;
        boolean hasChildren = expandable(entry);
        // The line's own clicks first: the buttons drawn on it later take precedence.
        hit(
            listLeft + 1,
            y,
            listLeft + 15 + indent,
            y + row.height,
            hasChildren ? () -> toggle(entry) : () -> select(entry),
            null);
        hit(listLeft + 15 + indent, y, listRight - 1, y + row.height, () -> {
            boolean wasSelected = selected == entry;
            select(entry);
            if (hasChildren && (wasSelected || !expanded.contains(entry))) {
                toggle(entry);
            }
        }, null);
        if (hasChildren) {
            String[] arrow = expanded.contains(entry) || !lastSearch.isEmpty() ? Icons.SECTION_OPEN
                : Icons.SECTION_CLOSED;
            Icons.draw(
                arrow,
                x + (5 - Icons.width(arrow)) / 2,
                y + (row.height - arrow.length) / 2,
                hovered ? Theme.TEXT : Theme.TEXT_MUTED);
        }
        x += 9;

        // The values on the right.
        double average = report.perPeriod(entry);
        String avgText = avgText(average);
        String peakText = peakText(entry);
        int avgRight = listRight - PEAK_COLUMN - 6;
        fontRendererObj.drawString(
            avgText,
            avgRight - fontRendererObj.getStringWidth(avgText),
            textY,
            entry.level == 3 ? Theme.blend(severity(average), Theme.TEXT_MUTED, 0.3) : severity(average));
        fontRendererObj
            .drawString(peakText, listRight - 6 - fontRendererObj.getStringWidth(peakText), textY, peakColor(entry));
        int textRight = avgRight - AVG_COLUMN + 4;
        if (isSpiky(entry)) {
            Icons.draw(Icons.SMALL_SPIKE, textRight - 2, y + (row.height - 7) / 2, ORANGE);
            textRight -= 10;
        }

        if (entry.level == 2 && GuiInstances.hasInstances(entry)) {
            // How many of the kind there are, as a button opening them all as cards.
            textRight = drawCardsPill(entry, y, row.height, textRight, mouseX, mouseY) - 4;
        } else if (entry.level == 1 && hovered && GuiInstances.hasInstances(entry)) {
            // All of the mod's things as cards, in a window of their own.
            int size = 11;
            int bx = textRight - size, by = y + (row.height - size) / 2;
            drawSmallButton(Icons.SMALL_GRID, bx, by, size, Theme.TEXT_MUTED, false);
            hit(
                bx,
                by,
                bx + size,
                by + size,
                () -> openCards(entry),
                I18n.format("lagfinder.action.cards") + "\n" + I18n.format("lagfinder.tip.cards"));
            textRight = bx - 4;
        }

        if (entry.level == 1) {
            int color = Draw.modColor(entry.name);
            Theme.fill(x, y + row.height / 2 - 3, x + 6, y + row.height / 2 + 3, color);
            x += 10;
            String count = "  " + entry.children.size();
            int countWidth = fontRendererObj.getStringWidth(count);
            String name = Theme.ellipsize(fontRendererObj, entry.name, textRight - x - countWidth);
            fontRendererObj.drawStringWithShadow(name, x, textY, Theme.TEXT);
            fontRendererObj.drawString(count, x + fontRendererObj.getStringWidth(name), textY, Theme.TEXT_DISABLED);
            // Its share of the tab, as a thin bar along the bottom.
            int barWidth = (int) Math.round((listRight - listLeft - 10) * Math.min(1, entry.totalMs / categoryTotal));
            Theme.fill(
                listLeft + 5,
                y + row.height - 2,
                listLeft + 5 + Math.max(1, barWidth),
                y + row.height - 1,
                Theme.blend(color, 0x00000000, 0.35));
        } else if (entry.level == 2) {
            ItemStack stack = Names.icon(entry);
            if (stack != null) {
                Draw.item(stack, x, y + (row.height - 12) / 2, 12);
            } else {
                Icons.draw(icon(entry.category), x + 2, y + (row.height - 7) / 2, Theme.TEXT_MUTED);
            }
            x += 15;
            // The count is on its cards button; kinds with no place in the world still tell it here.
            String count = entry.instances > 1 && !GuiInstances.hasInstances(entry) ? "  ×" + entry.instances : "";
            int countWidth = fontRendererObj.getStringWidth(count);
            String name = Theme.ellipsize(fontRendererObj, Names.of(entry), textRight - x - countWidth);
            fontRendererObj.drawString(name, x, textY, Theme.TEXT);
            fontRendererObj.drawString(count, x + fontRendererObj.getStringWidth(name), textY, Theme.TEXT_DISABLED);
        }
    }

    /** The dimension of an instance when it isn't the player's, e.g. "[Nether]"; null otherwise. */
    private String dimensionTag(ReportEntry entry) {
        int playerDim = mc.theWorld != null ? mc.theWorld.provider.dimensionId : 0;
        if (!entry.hasLocation() || entry.dim == playerDim) {
            return null;
        }
        return "[" + report.dimensionName(entry.dim) + "]";
    }

    private void drawSmallButton(String[] icon, int x, int y, int size, int color, boolean on) {
        Theme.fill(x, y, x + size, y + size, on ? Theme.ACCENT_DIM : Theme.CONTROL);
        Theme.outline(x, y, x + size, y + size, on ? Theme.ACCENT : Theme.BORDER);
        Icons.draw(icon, x + (size - Icons.width(icon)) / 2, y + (size - icon.length) / 2, on ? Theme.TEXT : color);
    }

    /**
     * The button on a kind's line: a grid and how many of them there are ("10"), opening them all as cards. Always
     * shown, lit under the mouse; returns where it starts.
     */
    private int drawCardsPill(ReportEntry entry, int y, int height, int right, int mouseX, int mouseY) {
        int number = Math.max(entry.instances, GuiInstances.countInstances(entry));
        String count = String.valueOf(number);
        int w = 7 + 4 + fontRendererObj.getStringWidth(count) + 9;
        int h = 12;
        int x0 = right - w, y0 = y + (height - h) / 2;
        boolean over = Theme.inside(mouseX, mouseY, x0, y0, right, y0 + h)
            && Theme.inside(mouseX, mouseY, listLeft, listTop, listRight, bodyBottom);
        double lit = light("pill" + System.identityHashCode(entry), over);
        int color = Draw.modColor(modOf(entry));
        Theme.fill(x0, y0, right, y0 + h, Theme.blend(Theme.CONTROL, Theme.blend(Theme.CONTROL, color, 0.35), lit));
        Theme.outline(x0, y0, right, y0 + h, Theme.blend(Theme.BORDER, color, 0.3 + 0.7 * lit));
        Icons.draw(Icons.SMALL_GRID, x0 + 4, y0 + (h - 7) / 2, Theme.blend(Theme.TEXT_MUTED, color, 0.4 + 0.6 * lit));
        fontRendererObj
            .drawString(count, x0 + 15, y0 + (h - 8) / 2 + 1, Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit));
        hit(
            x0,
            y0,
            right,
            y0 + h,
            () -> openCards(entry),
            I18n.format("lagfinder.action.cards_count", number) + "\n" + I18n.format("lagfinder.tip.cards"));
        return x0;
    }

    /** Opens all the things of the kind (or the mod) as cards, to look through them one by one. */
    private void openCards(ReportEntry entry) {
        selected = entry;
        // Searched for one of its things (coordinates, a label): the cards open searched for it too.
        String query = lastSearch.trim()
            .toLowerCase(Locale.ROOT);
        boolean forThings = !query.isEmpty() && !matches(entry, query) && anyMatches(entry.children, query);
        mc.displayGuiScreen(new GuiInstances(report, entry, this).withSearch(forThings ? lastSearch : null));
    }

    private void teleport(ReportEntry entry) {
        if (!entry.hasLocation() || !ClientScanner.INSTANCE.teleportAllowed()) {
            return;
        }
        Net.sendToServer(Net.Teleport.to(entry));
        if (!Highlighter.INSTANCE.isHighlighted(entry)) {
            Highlighter.INSTANCE.toggle(report, entry);
        }
        // The key brings the log back, to go on to the next place.
        GuiLagFinder.closeToGame(mc, this);
    }

    // ---------------------------------------------------------------- The graph

    private void drawGraph(int mouseX, int mouseY) {
        int x0 = rightLeft, y0 = bodyTop, x1 = rightRight, y1 = graphBottom;
        Theme.card(x0, y0, x1, y1);
        boolean tps = report.mode == ScanMode.TPS;
        int n = report.buckets();
        ReportEntry shown = selected != null && selected.sum != null ? selected : null;
        int elementColor = selected == null ? TOTAL_COLOR : Draw.modColor(modOf(selected));
        String rateName = tps ? "TPS" : "FPS";
        float target = tps ? 20f : 60f;
        // Right axis: milliseconds of what is shown in bars; in a frame scan, the frames a second it costs (unless
        // the frames were held to a limit: milliseconds of a frame then, against the limit's frame).
        boolean inMs = !report.costsInFps();
        boolean noGraph = selected != null && shown == null;

        // Legend: a sample of each thing drawn, as it looks on the graph.
        String elementName = selected == null
            ? I18n.format(tps ? "lagfinder.graph.tick_time" : "lagfinder.graph.frame_drops")
            : Names.of(selected) + (selected.level == 3 ? " (" + Names.position(selected) + ")" : "");
        Chart.Legend legend = new Chart.Legend(fontRendererObj, x0 + 8, y0 + 6, x1 - 8);
        legend.line(rateName, Theme.SUCCESS, true);
        if (selected == null && !inMs) {
            legend.band(elementName, elementColor, false);
        } else {
            String avgName = I18n
                .format(inMs ? "lagfinder.graph.legend_avg" : "lagfinder.graph.legend_avg_fps", elementName);
            String peakName = I18n.format(inMs ? "lagfinder.graph.legend_peak" : "lagfinder.graph.legend_peak_fps");
            legend.keep(fontRendererObj.getStringWidth(peakName) + 21);
            legend.bar(avgName, elementColor, false);
            legend.band(peakName, elementColor, false);
        }

        if (x1 - x0 - 68 < 20 || y1 - y0 - 36 < 20 || n == 0) {
            return;
        }
        Chart chart = new Chart(fontRendererObj, x0 + 28, y0 + 22, x1 - 40, y1 - 14, n);
        float rateMax = tps ? 20f : (float) Draw.niceCeil(Math.max(30, max(report.rate)) * 1.05);
        // How good the rate is, as faint bands behind all: the same colors its line takes.
        chart.zone(0, target * 0.5f, rateMax, 0x0EE5534B);
        chart.zone(target * 0.5f, target * 0.75f, rateMax, 0x0AFF8C42);
        chart.zone(target * 0.75f, target * 0.95f, rateMax, 0x08F2C14E);
        chart.grid(4);
        String[] rateLabels = new String[5];
        for (int i = 0; i <= 4; i++) {
            float value = rateMax * i / 4;
            rateLabels[i] = value == Math.floor(value) ? String.valueOf((int) value) : String.format("%.1f", value);
        }
        chart.leftAxis(rateLabels, Theme.SUCCESS);

        float[] avg = new float[n], peak = new float[n];
        for (int i = 0; i < n; i++) {
            if (shown != null) {
                avg[i] = report.perPeriod(shown, i);
                peak[i] = shown.max[i];
                if (!inMs) {
                    peak[i] = (float) report.fpsDrop(avg[i], peak[i], i);
                    avg[i] = (float) report.fpsCost(avg[i], i);
                }
            } else if (selected == null) {
                avg[i] = report.avgMs[i];
                peak[i] = report.maxMs[i];
                if (!inMs) {
                    // How far the worst frame of the moment fell below its usual rate: the stutters.
                    avg[i] = 0;
                    peak[i] = (float) report.fpsDrop(report.avgMs[i], report.maxMs[i], i);
                }
            }
        }
        float msMax = (float) Draw.niceCeil(Math.max(max(peak), Math.max(max(avg), 0.01f)) * 1.08f);
        String[] costLabels = new String[5];
        for (int i = 0; i <= 4; i++) {
            costLabels[i] = costLabel(msMax * i / 4, inMs, i == 0);
        }
        chart.rightAxis(costLabels, elementColor);
        chart.picked(pinned);
        if (!noGraph) {
            for (int i = 0; i < n; i++) {
                if (peak[i] > avg[i]) {
                    // How far its worst tick in this moment went: the swing to look at for things that lag now and
                    // then.
                    chart.bar(
                        i,
                        avg[i],
                        peak[i],
                        msMax,
                        Theme.blend(elementColor, 0x00000000, 0.45) & 0x80FFFFFF,
                        (elementColor & 0x00FFFFFF) | 0x20000000);
                    double top = chart.y(peak[i], msMax), left = chart.left(i);
                    Draw.gradientRect(left, top, left + chart.barWidth(), top + 1, elementColor, elementColor);
                }
                if (avg[i] > 0) {
                    int bottom = Theme.blend(elementColor, 0xFF000000, 0.4);
                    chart.bar(i, 0, avg[i], msMax, elementColor | 0xFF000000, bottom);
                }
            }
            // The time a tick may take before the rate drops.
            float budget = tps || report.frameCap <= 0 ? report.mode.budgetMs() : 1000f / report.frameCap;
            if (inMs) {
                String limit = Names.ms(budget)
                    .replace(" ", "");
                chart.threshold(budget, msMax, Theme.DANGER, I18n.format("lagfinder.graph.budget", limit));
            }
        } else {
            Theme.centered(
                fontRendererObj,
                I18n.format("lagfinder.graph.no_graph"),
                (chart.x0 + chart.x1) / 2,
                chart.y0 + 10,
                Theme.TEXT_DISABLED);
        }
        // The rate as a line with an area under it, colored by how good it was.
        int[] rateColors = new int[n];
        for (int i = 0; i < n; i++) {
            rateColors[i] = Draw.rateColor(report.rate[i], target);
        }
        chart.line(report.rate, rateMax, rateColors, 1.5f, 0x30);
        chart.timeAxis(report.durationSec);

        // The moment the rate was lowest in: a mark over it, a click from what lagged then.
        if (worstMoment >= 0 && worstMoment < n) {
            int cx = (int) Math.round(chart.center(worstMoment));
            chart.marker(cx, 0x50E5534B);
            for (int row = 0; row < 4; row++) {
                Theme.fill(cx - 3 + row, chart.y0 - 6 + row, cx + 4 - row, chart.y0 - 5 + row, Theme.DANGER);
            }
            int moment = worstMoment;
            hit(
                cx - 5,
                chart.y0 - 8,
                cx + 6,
                chart.y0,
                () -> pin(moment),
                I18n.format(
                    "lagfinder.graph.worst",
                    String.format("%s %.1f", rateName, report.rate[moment]),
                    Names.clock(moment * report.bucketSec)) + "\n" + I18n.format("lagfinder.tip.pin"));
        }

        // What was at the moment under the mouse: marked on the lines with its values beside the axes; a click picks
        // it, to see what lagged then.
        if (chart.contains(mouseX, mouseY)) {
            int i = chart.bucketAt(mouseX);
            chart.hover(i);
            int rateColor = Draw.rateColor(report.rate[i], target);
            double rateY = chart.y(report.rate[i], rateMax);
            if (!noGraph && (avg[i] > 0 || peak[i] > 0)) {
                float cost = avg[i] > 0 ? avg[i] : peak[i];
                double costY = chart.y(cost, msMax);
                Chart.dot(chart.center(i), costY, elementColor);
                chart.rightValue(costY, costLabel(cost, inMs, false), elementColor);
            }
            Chart.dot(chart.center(i), rateY, rateColor);
            chart.leftValue(rateY, String.format("%.1f", report.rate[i]), rateColor);
            hit(chart.x0, chart.y0, chart.x1, chart.y1, () -> pin(i), null);

            List<String> lines = new ArrayList<>();
            List<Integer> lineColors = new ArrayList<>();
            float from = i * report.bucketSec, to = Math.min(report.durationSec, (i + 1) * report.bucketSec);
            lines.add(Names.clock(from) + " – " + Names.clock(to));
            lineColors.add(Theme.TEXT_MUTED);
            lines.add(String.format("%s %.1f", rateName, report.rate[i]));
            lineColors.add(rateColor);
            if (tps) {
                lines.add(I18n.format("lagfinder.graph.tick", Names.ms(report.avgMs[i]), Names.ms(report.maxMs[i])));
                lineColors.add(Theme.TEXT);
            } else {
                float worstFps = report.maxMs[i] > 0 ? 1000f / report.maxMs[i] : 0;
                lines.add(I18n.format("lagfinder.graph.frame_fps", Names.fps(worstFps)));
                lineColors.add(fpsColor(worstFps));
            }
            if (shown != null) {
                lines.add(Names.of(shown) + ":");
                lineColors.add(elementColor);
                lines.add(
                    "  " + (tps ? I18n.format("lagfinder.graph.element", Names.ms(avg[i]), Names.ms(peak[i]))
                        : I18n.format("lagfinder.graph.element_fps", Names.fpsLoss(avg[i]), Names.fpsLoss(peak[i]))));
                lineColors.add(Theme.TEXT);
            }
            lines.add(I18n.format(i == pinned ? "lagfinder.graph.click_unpin" : "lagfinder.graph.click_pin"));
            lineColors.add(Theme.TEXT_DISABLED);
            Draw.tooltip(fontRendererObj, lines, lineColors, mouseX, mouseY, width, height);
        }
    }

    /** A value of the right axis: milliseconds, or the frames a second it costs. */
    private static String costLabel(float value, boolean inMs, boolean zero) {
        if (inMs) {
            return Names.ms(value)
                .replace(" ", "");
        }
        if (zero) {
            return "0";
        }
        return "-"
            + (value >= 10 || value == Math.floor(value) ? String.format("%.0f", value) : String.format("%.1f", value));
    }

    private static float max(float[] values) {
        float max = 0;
        for (float value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    private static String modOf(ReportEntry entry) {
        for (ReportEntry up = entry; up != null; up = up.parent) {
            if (up.level == 1) {
                return up.name;
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- Details

    private void drawDetails(int mouseX, int mouseY) {
        int x0 = rightLeft, y0 = graphBottom + 8, x1 = rightRight, y1 = bodyBottom;
        Theme.card(x0, y0, x1, y1);
        if (y1 - y0 < 40) {
            return;
        }
        if (selected != null) {
            drawSelection(selected, x0, y0, x1, y1, mouseX, mouseY);
        } else if (pinned >= 0 && pinnedByAverage != null) {
            drawMoment(x0, y0, x1, y1, mouseX, mouseY);
        } else {
            drawOverview(x0, y0, x1, y1, mouseX, mouseY);
        }
    }

    /** With nothing picked: the worst things of the whole scan, in all tabs. */
    private void drawOverview(int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        int columnWidth = (x1 - x0 - 24) / 2;
        int rowsFit = Math.max(1, (y1 - y0 - 30) / 14);
        drawTopList(
            I18n.format("lagfinder.overview.most_time"),
            Icons.SMALL_CLOCK,
            Theme.ACCENT,
            byAverage,
            entry -> avgText(report.perPeriod(entry)),
            entry -> severity(report.perPeriod(entry)),
            I18n.format("lagfinder.overview.none_time"),
            x0 + 8,
            y0 + 8,
            x0 + 8 + columnWidth,
            rowsFit,
            mouseX,
            mouseY);
        drawTopList(
            I18n.format("lagfinder.overview.spikes"),
            Icons.SMALL_SPIKE,
            ORANGE,
            bySpike,
            this::peakText,
            this::peakColor,
            I18n.format("lagfinder.overview.none"),
            x0 + 16 + columnWidth,
            y0 + 8,
            x1 - 8,
            rowsFit,
            mouseX,
            mouseY);
    }

    /** With a moment of the graph picked: what took the most time then, and the worst ticks then. */
    private void drawMoment(int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        boolean tps = report.mode == ScanMode.TPS;
        int i = pinned;
        float from = i * report.bucketSec, to = Math.min(report.durationSec, (i + 1) * report.bucketSec);
        // Which moment it is and how it went, with a button back to the whole scan.
        String back = I18n.format("lagfinder.moment.back");
        int backWidth = fontRendererObj.getStringWidth(back) + 22;
        int bx = x1 - 8 - backWidth, by = y0 + 5;
        boolean overBack = Theme.inside(mouseX, mouseY, bx, by, bx + backWidth, by + 14);
        double backLit = light("back", overBack);
        Theme.gradient(
            bx,
            by,
            bx + backWidth,
            by + 14,
            Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, backLit),
            Theme.blend(0xFF1D232B, Theme.CONTROL_HOVER, backLit));
        Theme.outline(bx, by, bx + backWidth, by + 14, Theme.blend(Theme.BORDER, Theme.ACCENT, backLit));
        Icons.draw(Icons.SMALL_CLEAR, bx + 5, by + 4, overBack ? Theme.TEXT : Theme.TEXT_MUTED);
        fontRendererObj.drawString(back, bx + 16, by + 3, overBack ? Theme.TEXT : Theme.TEXT_MUTED);
        hit(bx, by, bx + backWidth, by + 14, () -> pin(i), I18n.format("lagfinder.tip.unpin"));

        Theme.fill(x0 + 8, y0 + 7, x0 + 10, y0 + 17, Theme.ACCENT);
        String moment = Names.clock(from) + " – " + Names.clock(to);
        fontRendererObj.drawStringWithShadow(moment, x0 + 14, y0 + 8, Theme.TEXT);
        int after = x0 + 14 + fontRendererObj.getStringWidth(moment) + 8;
        String rate = String.format("%s %.1f", tps ? "TPS" : "FPS", report.rate[i]);
        fontRendererObj.drawString(rate, after, y0 + 8, Draw.rateColor(report.rate[i], tps ? 20f : 60f));
        after += fontRendererObj.getStringWidth(rate) + 8;
        String tick = tps ? I18n.format("lagfinder.graph.tick", Names.ms(report.avgMs[i]), Names.ms(report.maxMs[i]))
            : I18n.format("lagfinder.graph.frame_fps", Names.fps(report.maxMs[i] > 0 ? 1000f / report.maxMs[i] : 0));
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, tick, bx - 8 - after), after, y0 + 8, Theme.TEXT_MUTED);

        int top = y0 + 24;
        Theme.fill(x0 + 6, top - 4, x1 - 6, top - 3, 0xFF1C2129);
        int columnWidth = (x1 - x0 - 24) / 2;
        int rowsFit = Math.max(1, (y1 - top - 22) / 14);
        drawTopList(
            I18n.format("lagfinder.moment.most_time"),
            Icons.SMALL_CLOCK,
            Theme.ACCENT,
            pinnedByAverage,
            entry -> momentAvgText(entry, i),
            entry -> severity(report.perPeriod(entry, i)),
            I18n.format("lagfinder.moment.none"),
            x0 + 8,
            top,
            x0 + 8 + columnWidth,
            rowsFit,
            mouseX,
            mouseY);
        drawTopList(
            I18n.format(tps ? "lagfinder.moment.spikes" : "lagfinder.moment.spikes_fps"),
            Icons.SMALL_SPIKE,
            ORANGE,
            pinnedBySpike,
            entry -> momentPeakText(entry, i),
            entry -> momentPeakColor(entry, i),
            I18n.format("lagfinder.overview.none"),
            x0 + 16 + columnWidth,
            top,
            x1 - 8,
            rowsFit,
            mouseX,
            mouseY);
    }

    /** The entry's time per tick in the moment; in a frame scan, the frames a second it cost then. */
    private String momentAvgText(ReportEntry entry, int moment) {
        float average = report.perPeriod(entry, moment);
        return inFps() ? Names.fpsLoss(report.fpsCost(average, moment)) : Names.ms(average);
    }

    private String momentPeakText(ReportEntry entry, int moment) {
        float peak = entry.max[moment];
        return inFps() ? Names.fpsLoss(report.fpsDrop(report.perPeriod(entry, moment), peak, moment)) : Names.ms(peak);
    }

    private int momentPeakColor(ReportEntry entry, int moment) {
        float peak = entry.max[moment];
        return peak >= report.mode.budgetMs() * 0.5f ? Theme.DANGER
            : peak >= report.spikeMs ? ORANGE : Theme.TEXT_MUTED;
    }

    /** The lowest lines of the entry: instances where there are, kinds otherwise. */
    private static void collectLeaves(ReportEntry entry, List<ReportEntry> out) {
        if (entry.level >= 2 && (entry.children.isEmpty() || entry.level == 3)) {
            out.add(entry);
            return;
        }
        for (ReportEntry child : entry.children) {
            collectLeaves(child, out);
        }
    }

    /**
     * A column of the overview: its title, then the first entries with a soft bar of their value against the first's,
     * each a click from showing it in its tab.
     */
    private void drawTopList(String title, String[] titleIcon, int titleColor, List<ReportEntry> entries,
        Function<ReportEntry, String> valueOf, ToIntFunction<ReportEntry> colorOf, String none, int x0, int y0, int x1,
        int count, int mouseX, int mouseY) {
        Icons.draw(titleIcon, x0, y0 + 1, titleColor);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, title.toUpperCase(), x1 - x0 - 12),
            x0 + 11,
            y0,
            Theme.TEXT_MUTED);
        if (entries.isEmpty()) {
            Icons.draw(Icons.SMALL_CHECK, x0, y0 + 17, Theme.SUCCESS);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, none, x1 - x0 - 12),
                x0 + 11,
                y0 + 16,
                Theme.TEXT_DISABLED);
            return;
        }
        for (int i = 0; i < entries.size() && i < count; i++) {
            ReportEntry entry = entries.get(i);
            int y = y0 + 14 + i * 14;
            boolean hovered = Theme.inside(mouseX, mouseY, x0 - 2, y, x1 + 2, y + 14);
            double lit = light(title + i, hovered);
            int color = colorOf.applyAsInt(entry);
            // Its rank, colored as bad as it is: the first lines are what to look at.
            int rank = Theme.blend(color, Theme.TEXT_DISABLED, Math.min(0.8, i * 0.2));
            Theme.fill(x0 - 2, y + 2, x0, y + 12, rank);
            if (lit > 0.01) {
                Theme.fill(x0, y, x1 + 2, y + 14, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
            }
            String value = valueOf.apply(entry);
            int valueWidth = fontRendererObj.getStringWidth(value);
            fontRendererObj.drawString(value, x1 - valueWidth, y + 3, color);
            ItemStack stack = Names.icon(entry);
            if (stack != null) {
                Draw.item(stack, x0 + 2, y + 1, 12);
            } else {
                Icons.draw(icon(entry.category), x0 + 4, y + 4, Theme.TEXT_DISABLED);
            }
            String name = Names.of(entry);
            String where = entry.level == 3 ? "  " + Names.position(entry) : "";
            int room = x1 - valueWidth - 6 - (x0 + 17);
            String shownName = Theme.ellipsize(fontRendererObj, name, room);
            fontRendererObj.drawString(shownName, x0 + 17, y + 3, Theme.TEXT);
            int after = x0 + 17 + fontRendererObj.getStringWidth(shownName);
            if (!where.isEmpty() && after < x1 - valueWidth - 6) {
                fontRendererObj.drawString(
                    Theme.ellipsize(fontRendererObj, where, x1 - valueWidth - 6 - after),
                    after,
                    y + 3,
                    Theme.TEXT_DISABLED);
            }
            hit(
                x0 - 2,
                y,
                x1 + 2,
                y + 14,
                () -> reveal(entry),
                name + "\n"
                    + modOf(entry)
                    + "  ·  "
                    + Names.category(entry.category)
                    + "\n"
                    + I18n.format("lagfinder.tip.reveal"));
        }
    }

    private void drawSelection(ReportEntry entry, int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        int x = x0 + 8, y = y0 + 8;
        // Title: icon, name, and the mod on the right.
        ItemStack stack = entry.level >= 2 ? Names.icon(entry) : null;
        if (stack != null) {
            Draw.item(stack, x, y - 2, 16);
        } else if (entry.level == 1) {
            Theme.fill(x + 3, y + 1, x + 11, y + 9, Draw.modColor(entry.name));
        } else {
            Icons.draw(icon(entry.category), x + 4, y + 1, Theme.ACCENT);
        }
        // Back to the overview, top right.
        int closeX = x1 - 8 - 12;
        boolean overClose = Theme.inside(mouseX, mouseY, closeX, y - 2, closeX + 12, y + 10);
        double closeLit = light("close", overClose);
        Theme.fill(closeX, y - 2, closeX + 12, y + 10, Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, closeLit));
        Theme.outline(closeX, y - 2, closeX + 12, y + 10, Theme.blend(Theme.BORDER, Theme.ACCENT, closeLit));
        Icons.draw(Icons.SMALL_CLEAR, closeX + 3, y + 1, overClose ? Theme.TEXT : Theme.TEXT_MUTED);
        hit(
            closeX,
            y - 2,
            closeX + 12,
            y + 10,
            () -> selected = null,
            I18n.format(pinned >= 0 ? "lagfinder.tip.back_moment" : "lagfinder.tip.back_overview"));
        String mod = modOf(entry);
        int modWidth = fontRendererObj.getStringWidth(mod) + 10;
        int titleRight = closeX - 4;
        if (entry.level > 1) {
            Theme.pill(fontRendererObj, mod, titleRight - modWidth, y - 2, Draw.modColor(mod));
            titleRight -= modWidth + 4;
        }
        String name = Names.of(entry) + (entry.level == 3 && entry.label != null ? " · " + entry.label : "");
        String shownName = Theme.ellipsize(fontRendererObj, name, titleRight - x - 22);
        fontRendererObj.drawStringWithShadow(shownName, x + 20, y, Theme.TEXT);
        String kind = Names.category(entry.category) + (entry.detail != null ? "  ·  " + entry.detail : "");
        String shownKind = Theme.ellipsize(fontRendererObj, kind, x1 - x - 28);
        fontRendererObj.drawString(shownKind, x + 20, y + 13, Theme.TEXT_DISABLED);
        if (!shownName.equals(name) || !shownKind.equals(kind)) {
            // Cut short (long handler and class names): all of it on hover.
            hit(
                x + 20,
                y - 2,
                titleRight,
                y + 22,
                null,
                name + "\n" + Names.category(entry.category) + (entry.detail != null ? "\n" + entry.detail : ""));
        }
        y += 27;

        // The numbers.
        boolean tps = report.mode == ScanMode.TPS;
        double average = report.perPeriod(entry);
        double share = report.averagePeriodMs() <= 0 ? 0 : average / report.averagePeriodMs() * 100;
        int periods = Math.max(1, report.periods);
        String[] labels = { I18n.format(tps ? "lagfinder.stat.avg_tick" : "lagfinder.stat.avg_frame"),
            I18n.format(tps ? "lagfinder.stat.share_tick" : "lagfinder.stat.share_frame"),
            I18n.format(tps ? "lagfinder.stat.peak" : "lagfinder.stat.peak_fps"), I18n.format("lagfinder.stat.spikes"),
            I18n.format(tps ? "lagfinder.stat.calls_tick" : "lagfinder.stat.calls_frame"),
            entry.level == 3 ? I18n.format("lagfinder.stat.active") : I18n.format("lagfinder.stat.instances") };
        String[] values = { avgText(average), String.format("%.1f%%", share),
            peakText(entry) + "  @" + Names.clock(entry.maxAt),
            entry.spikes + String.format("  (%.1f%%)", entry.spikes * 100.0 / periods),
            String.format("%.1f", entry.calls / (double) periods),
            entry.level == 3 ? String.format("%.0f%%", Math.min(100, entry.activePeriods * 100.0 / periods))
                : entry.category.hasInstances ? String.valueOf(instancesOf(entry))
                    : entry.level == 1 ? String.valueOf(entry.children.size()) : "\u2014" };
        int[] colors = { severity(average), severity(average), peakColor(entry), entry.spikes > 0 ? ORANGE : Theme.TEXT,
            Theme.TEXT, Theme.TEXT };
        String[] tips = { tps ? "lagfinder.tip.avg" : "lagfinder.tip.avg_fps", "lagfinder.tip.share",
            tps ? "lagfinder.tip.peak" : "lagfinder.tip.peak_fps", "lagfinder.tip.spikes", "lagfinder.tip.calls",
            entry.level == 3 ? "lagfinder.tip.active" : "lagfinder.tip.instances" };
        int columns = 3, gap = 6;
        int tileWidth = (x1 - x0 - 16 - gap * (columns - 1)) / columns;
        int tileHeight = 26;
        for (int i = 0; i < labels.length; i++) {
            int tx = x0 + 8 + (i % columns) * (tileWidth + gap), ty = y + (i / columns) * (tileHeight + 4);
            if (ty + tileHeight > y1 - 4) {
                break;
            }
            Theme.gradient(tx, ty, tx + tileWidth, ty + tileHeight, 0xFF161B22, 0xFF0F1216);
            Theme.outline(tx, ty, tx + tileWidth, ty + tileHeight, Theme.BORDER);
            Theme.fill(
                tx + 1,
                ty + tileHeight - 2,
                tx + tileWidth - 1,
                ty + tileHeight - 1,
                Theme.blend(colors[i], 0xFF0F1216, colors[i] == Theme.TEXT ? 0.85 : 0.45));
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, labels[i], tileWidth - 10),
                tx + 5,
                ty + 4,
                Theme.TEXT_DISABLED);
            fontRendererObj
                .drawString(Theme.ellipsize(fontRendererObj, values[i], tileWidth - 10), tx + 5, ty + 15, colors[i]);
            hit(tx, ty, tx + tileWidth, ty + tileHeight, null, labels[i] + "\n" + I18n.format(tips[i]));
        }
        y += 2 * (tileHeight + 4) + 4;
        if (y > y1 - 16) {
            return;
        }

        if (entry.level == 3 && entry.hasLocation()) {
            drawLocation(entry, x0, y, x1, mouseX, mouseY);
            y += 24;
            if (isSpiky(entry) && y + 12 < y1) {
                Icons.draw(Icons.SMALL_SPIKE, x0 + 8, y + 1, ORANGE);
                fontRendererObj.drawString(
                    Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.hint.spiky"), x1 - x0 - 30),
                    x0 + 19,
                    y,
                    ORANGE);
            }
            return;
        }
        if (isSpiky(entry) && y + 12 < y1) {
            Icons.draw(Icons.SMALL_SPIKE, x0 + 8, y + 1, ORANGE);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.hint.spiky"), x1 - x0 - 30),
                x0 + 19,
                y,
                ORANGE);
            y += 14;
        }
        if (entry.level < 3 && GuiInstances.hasInstances(entry) && y + 20 < y1) {
            drawCardsButton(entry, x0 + 8, y, x1 - 8, y + 18, mouseX, mouseY);
            y += 24;
        }
        // The worst ones under it.
        if (!entry.children.isEmpty() && y + 26 < y1) {
            fontRendererObj.drawString(
                I18n.format("lagfinder.details.worst")
                    .toUpperCase(),
                x0 + 8,
                y,
                Theme.TEXT_MUTED);
            y += 12;
            List<ReportEntry> children = sorted(entry.children);
            for (int i = 0; i < children.size() && y + 14 <= y1 - 4; i++) {
                ReportEntry child = children.get(i);
                boolean hovered = Theme.inside(mouseX, mouseY, x0 + 6, y, x1 - 6, y + 14);
                double lit = light("child" + i, hovered);
                if (lit > 0.01) {
                    Theme.fill(x0 + 6, y, x1 - 6, y + 14, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
                }
                String value = sort == Sort.PEAK ? peakText(child) : avgText(report.perPeriod(child));
                int valueWidth = fontRendererObj.getStringWidth(value);
                fontRendererObj.drawString(
                    value,
                    x1 - 10 - valueWidth,
                    y + 3,
                    sort == Sort.PEAK ? peakColor(child) : severity(report.perPeriod(child)));
                String text = child.level == 3 ? Names.position(child) + (child.label != null ? "  " + child.label : "")
                    : Names.of(child);
                String dim = dimensionTag(child);
                if (dim != null) {
                    text += "  " + dim;
                }
                fontRendererObj.drawString(
                    Theme.ellipsize(fontRendererObj, text, x1 - x0 - 30 - valueWidth),
                    x0 + 10,
                    y + 3,
                    Theme.TEXT);
                hit(x0 + 6, y, x1 - 6, y + 14, () -> reveal(child), I18n.format("lagfinder.tip.reveal"));
                y += 14;
            }
        }
    }

    /** A wide button opening all the things under the entry as cards. */
    private void drawCardsButton(ReportEntry entry, int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        boolean hovered = Theme.inside(mouseX, mouseY, x0, y0, x1, y1);
        double lit = light("cards", hovered);
        Theme.gradient(
            x0,
            y0,
            x1,
            y1,
            Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit),
            Theme.blend(0xFF1D232B, Theme.CONTROL_HOVER, lit));
        Theme.outline(x0, y0, x1, y1, Theme.blend(Theme.blend(Theme.BORDER, Theme.ACCENT, 0.4), Theme.ACCENT, lit));
        Icons.draw(Icons.SMALL_GRID, x0 + 7, y0 + 6, Theme.ACCENT);
        String text = I18n.format("lagfinder.action.cards_count", GuiInstances.countInstances(entry));
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, text, x1 - x0 - 40),
            x0 + 19,
            y0 + 5,
            hovered ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.5));
        String key = "Enter";
        fontRendererObj.drawString(key, x1 - 7 - fontRendererObj.getStringWidth(key), y0 + 5, Theme.TEXT_DISABLED);
        hit(x0, y0, x1, y1, () -> openCards(entry), I18n.format("lagfinder.tip.cards"));
    }

    private static int instancesOf(ReportEntry entry) {
        if (entry.level == 2) {
            return Math.max(entry.instances, entry.children.size());
        }
        int count = 0;
        for (ReportEntry child : entry.children) {
            count += instancesOf(child);
        }
        return entry.level == 1 && count == 0 ? entry.children.size() : count;
    }

    /** Where the instance is, with buttons to go there, highlight it and copy its place. */
    private void drawLocation(ReportEntry entry, int x0, int y, int x1, int mouseX, int mouseY) {
        int x = x0 + 8;
        Icons.draw(Icons.SMALL_TELEPORT, x, y + 6, Theme.TEXT_MUTED);
        String where = Names.position(entry) + "  ·  " + report.dimensionName(entry.dim);
        boolean canTeleport = ClientScanner.INSTANCE.teleportAllowed();
        boolean highlighted = Highlighter.INSTANCE.isHighlighted(entry);
        String[] labels = { I18n.format("lagfinder.action.teleport"),
            I18n.format(highlighted ? "lagfinder.action.unhighlight" : "lagfinder.action.highlight"),
            I18n.format("lagfinder.action.copy") };
        String[][] icons = { Icons.SMALL_TELEPORT, Icons.SMALL_EYE, Icons.SMALL_COPY };
        int buttonsWidth = 0;
        int[] widths = new int[3];
        for (int i = 0; i < 3; i++) {
            widths[i] = fontRendererObj.getStringWidth(labels[i]) + 22;
            buttonsWidth += widths[i] + 4;
        }
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, where, x1 - x - 14 - buttonsWidth), x + 11, y + 5, Theme.TEXT);
        int bx = x1 - 8 - buttonsWidth + 4;
        for (int i = 0; i < 3; i++) {
            boolean enabled = i != 0 || canTeleport;
            boolean on = i == 1 && highlighted;
            boolean hovered = enabled && Theme.inside(mouseX, mouseY, bx, y, bx + widths[i], y + 18);
            double lit = light(icons[i], hovered);
            int background = !enabled ? Theme.CONTROL_DISABLED
                : on ? Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, lit)
                    : Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
            Theme.fill(bx, y, bx + widths[i], y + 18, background);
            Theme.outline(
                bx,
                y,
                bx + widths[i],
                y + 18,
                on ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT, lit));
            int color = !enabled ? Theme.TEXT_DISABLED : Theme.TEXT;
            Icons.draw(icons[i], bx + 6, y + 6, color);
            fontRendererObj.drawString(labels[i], bx + 16, y + 5, color);
            Runnable action;
            String tip;
            if (i == 0) {
                action = enabled ? () -> teleport(entry) : null;
                tip = I18n.format(canTeleport ? "lagfinder.tip.teleport" : "lagfinder.action.no_teleport");
            } else if (i == 1) {
                action = () -> Highlighter.INSTANCE.toggle(report, entry);
                tip = I18n.format("lagfinder.tip.highlight");
            } else {
                action = () -> setClipboardString(entry.x + " " + entry.y + " " + entry.z);
                tip = I18n.format("lagfinder.tip.copy");
            }
            hit(bx, y, bx + widths[i], y + 18, action, tip);
            bx += widths[i] + 4;
        }
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
