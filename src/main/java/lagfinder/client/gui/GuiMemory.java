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
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.ScaledScreen;
import lagfinder.client.gui.ui.ScrollbarDrag;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.WindowHeader;
import lagfinder.core.HeapProfile;
import lagfinder.core.HeapProfile.HeapClass;
import lagfinder.core.Report;
import lagfinder.core.ScanMode;

/**
 * The Java heap of a scan: on top, how much of it was used, how fast the game allocated and when it collected, over the
 * whole scan; under it, the objects in the heap by class (how many and how big) as counted along the scan, with how
 * each grew or shrank from the first count to the last. On the left, the mods with the bytes of all their classes;
 * picking one shows only its classes.
 */
public class GuiMemory extends ScaledScreen {

    private static final int ID_CLOSE = 0, ID_SORT = 1;
    private static final int ROW = 13, MOD_ROW = 18;
    private static final int SIDE_WIDTH = 176, DETAILS_HEIGHT = 40;
    private static final int COUNT_COLUMN = 54, COUNT_DELTA_COLUMN = 56, SIZE_COLUMN = 56, SIZE_DELTA_COLUMN = 60,
        TREND_COLUMN = 46;
    private static final int SELECTED = 0xFF1E2C40;
    private static final int WARN = 0xFFF2C14E;
    private static final int HEAP_COLOR = 0xFF4C9AFF, ALLOC_COLOR = 0xFFA371F7, GC_COLOR = 0xFFF0883E,
        SNAPSHOT_COLOR = 0xFF8A93A0;

    private enum Sort {

        SIZE("lagfinder.memory.sort.size"),
        GROWTH("lagfinder.memory.sort.growth"),
        COUNT("lagfinder.memory.sort.count"),
        COUNT_GROWTH("lagfinder.memory.sort.count_growth");

        final String key;

        Sort(String key) {
            this.key = key;
        }
    }

    /** The sorting stays while the game runs. */
    private static Sort sort = Sort.SIZE;

    private final Report report;
    private final HeapProfile heap;
    private final GuiScreen parent;
    /** The mod whose classes are shown (index into the heap's mods), -1 for all. */
    private int modFilter = -1;
    private HeapClass selected;

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

    private final List<HeapClass> rows = new ArrayList<>();
    /** Mods with any objects, the biggest (or the most grown) first. */
    private final List<Integer> modOrder = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int clipX0 = Integer.MIN_VALUE, clipY0 = Integer.MIN_VALUE, clipX1 = Integer.MAX_VALUE,
        clipY1 = Integer.MAX_VALUE;
    private final Map<Object, Smooth> lights = new HashMap<>();
    private int scroll, sideScroll;
    private final Smooth shownScroll = new Smooth(0), shownSideScroll = new Smooth(0);
    private final ScrollbarDrag listBar = new ScrollbarDrag(), sideBar = new ScrollbarDrag();
    private String query = "";

    private int panelLeft, panelTop, panelRight, panelBottom;
    private int toolbarY, bodyBottom;
    private int sideLeft, sideRight, sideTop;
    private int graphTop, graphBottom;
    private int listLeft, listRight, listTop, listBottom;
    private FlatTextField search;
    private FlatButton sortButton;
    private String lastSearch = "";

    /** Classes that grew at every count, by how much they grew: what may be leaking. */
    private final List<HeapClass> leaks = new ArrayList<>();
    private final Set<HeapClass> leaking = Collections.newSetFromMap(new IdentityHashMap<>());

    public GuiMemory(Report report) {
        this.report = report;
        this.heap = report.memory;
        this.parent = net.minecraft.client.Minecraft.getMinecraft().currentScreen;
        for (HeapClass type : heap.classes) {
            if (isLeaking(type)) {
                leaks.add(type);
            }
        }
        leaks.sort((a, b) -> Long.compare(delta(b.bytes), delta(a.bytes)));
        leaking.addAll(leaks);
    }

    /**
     * Whether the class grew at every count, by a megabyte at least and a tenth of what it was: the objects of a leak
     * pile up like that, while those merely in use come and go.
     */
    private static boolean isLeaking(HeapClass type) {
        long[] bytes = type.bytes;
        if (bytes.length < 3) {
            return false;
        }
        for (int s = 1; s < bytes.length; s++) {
            if (bytes[s] <= bytes[s - 1]) {
                return false;
            }
        }
        long growth = delta(bytes);
        return growth >= 1024 * 1024 && growth >= first(bytes) / 10;
    }

    /** Opens the memory with only the mod's classes shown, when the scan counted any of them. */
    public GuiMemory filtered(String mod) {
        int index = mod == null ? -1 : heap.modIndex(mod);
        if (index >= 0 && last(heap.modBytes.get(index)) > 0) {
            modFilter = index;
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
        graphTop = toolbarY + 22;
        int body = bodyBottom - DETAILS_HEIGHT - 6 - graphTop;
        graphBottom = graphTop + Math.max(70, Math.min(150, body * 2 / 5));
        listTop = graphBottom + 18;
        listBottom = bodyBottom - DETAILS_HEIGHT - 6;

        buttonList.clear();
        sortButton = new FlatButton(ID_SORT, listRight - 128, toolbarY, 128, 16, "");
        sortButton.icon = Icons.SMALL_SORT;
        buttonList.add(sortButton);
        String text = search != null ? search.getText() : "";
        int searchWidth = Math.max(60, sortButton.xPosition - 6 - listLeft);
        search = new FlatTextField(fontRendererObj, listLeft, toolbarY, searchWidth, 16);
        search.setHint(I18n.format("lagfinder.memory.search"));
        search.setMaxStringLength(80);
        search.setText(text);
        search.setOnCleared(this::rebuild);
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, panelRight, panelTop));
        updateButtons();
        rebuild();
    }

    private void updateButtons() {
        sortButton.displayString = I18n.format(sort.key);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    // ---------------------------------------------------------------- What is shown

    private int snapshots() {
        return heap.snapshots();
    }

    private static long first(long[] values) {
        return values.length == 0 ? 0 : values[0];
    }

    private static long last(long[] values) {
        return values.length == 0 ? 0 : values[values.length - 1];
    }

    private static long delta(long[] values) {
        return last(values) - first(values);
    }

    /** What the classes are sorted by. */
    private static long value(HeapClass type) {
        switch (sort) {
            case GROWTH:
                return delta(type.bytes);
            case COUNT:
                return last(type.instances);
            case COUNT_GROWTH:
                return delta(type.instances);
            default:
                return last(type.bytes);
        }
    }

    private long modValue(int mod) {
        long[] bytes = heap.modBytes.get(mod), instances = heap.modInstances.get(mod);
        switch (sort) {
            case GROWTH:
                return delta(bytes);
            case COUNT:
                return last(instances);
            case COUNT_GROWTH:
                return delta(instances);
            default:
                return last(bytes);
        }
    }

    private boolean matches(HeapClass type) {
        if (query.isEmpty()) {
            return true;
        }
        return type.name.toLowerCase(Locale.ROOT)
            .contains(query)
            || heap.modName(type.mod)
                .toLowerCase(Locale.ROOT)
                .contains(query);
    }

    /** Lays the lines out again: after a change of sorting, mod or search. */
    private void rebuild() {
        lastSearch = search.getText();
        query = lastSearch.trim()
            .toLowerCase(Locale.ROOT);
        rows.clear();
        for (HeapClass type : heap.classes) {
            if ((modFilter < 0 || type.mod == modFilter) && matches(type)) {
                rows.add(type);
            }
        }
        rows.sort(
            Comparator.comparingLong(GuiMemory::value)
                .reversed()
                .thenComparing(type -> type.name));
        modOrder.clear();
        for (int i = 0; i < heap.mods.size(); i++) {
            if (heap.modBytes.get(i).length > 0 && heap.modBytes.get(i)[0] + last(heap.modBytes.get(i)) > 0) {
                modOrder.add(i);
            }
        }
        modOrder.sort((a, b) -> Long.compare(modValue(b), modValue(a)));
        clampScroll();
        clampSideScroll();
    }

    private int listHeight() {
        return listBottom - listTop;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, rows.size() * ROW - listHeight() + 2));
    }

    private int sideHeight() {
        return bodyBottom - sideTop - 18;
    }

    private void clampSideScroll() {
        sideScroll = Math.max(0, Math.min(sideScroll, (modOrder.size() + 1) * MOD_ROW - sideHeight()));
    }

    private void setFilter(int mod) {
        modFilter = modFilter == mod ? -1 : mod;
        scroll = 0;
        if (selected != null && modFilter >= 0 && selected.mod != modFilter) {
            selected = null;
        }
        rebuild();
    }

    private void scrollTo(HeapClass type) {
        int index = rows.indexOf(type);
        if (index < 0) {
            return;
        }
        int y = index * ROW;
        if (y < scroll) {
            scroll = y;
        } else if (y + ROW > scroll + listHeight()) {
            scroll = y + ROW - listHeight();
        }
        clampScroll();
    }

    // ---------------------------------------------------------------- Input

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            close();
        } else if (button.id == ID_SORT) {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            scroll = 0;
            updateButtons();
            rebuild();
        }
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
                rows.size() * ROW,
                (int) Math.round(shownScroll.get()),
                Math.max(0, rows.size() * ROW - listHeight() + 2));
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
            if (!rows.isEmpty()) {
                int index = rows.indexOf(selected);
                index = Math
                    .max(0, Math.min(rows.size() - 1, index < 0 ? 0 : index + (keyCode == Keyboard.KEY_UP ? -1 : 1)));
                selected = rows.get(index);
                scrollTo(selected);
            }
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

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
    }

    // ---------------------------------------------------------------- Formatting

    /** Bytes as B, KB, MB or GB. */
    static String bytes(double bytes) {
        double abs = Math.abs(bytes);
        if (abs >= 1024.0 * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        if (abs >= 1024.0 * 1024 * 100) {
            return String.format("%.0f MB", bytes / (1024.0 * 1024));
        }
        if (abs >= 1024.0 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        if (abs >= 1024) {
            return String.format("%.0f KB", bytes / 1024.0);
        }
        return String.format("%.0f B", bytes);
    }

    static String megabytes(double mb) {
        return bytes(mb * 1024 * 1024);
    }

    /** A count of objects, e.g. "1.2M", "35K", "812". */
    static String count(double count) {
        double abs = Math.abs(count);
        if (abs >= 1e9) {
            return String.format("%.2fG", count / 1e9);
        }
        if (abs >= 1e7) {
            return String.format("%.0fM", count / 1e6);
        }
        if (abs >= 1e6) {
            return String.format("%.1fM", count / 1e6);
        }
        if (abs >= 1e4) {
            return String.format("%.0fK", count / 1e3);
        }
        if (abs >= 1e3) {
            return String.format("%.1fK", count / 1e3);
        }
        return String.format("%.0f", count);
    }

    private static String signed(String text, long value) {
        return value > 0 ? "+" + text : value < 0 ? text : "0";
    }

    private static String bytesDelta(long value) {
        return signed(bytes(value), value);
    }

    private static String countDelta(long value) {
        return signed(count(value), value);
    }

    /** Growth is red, shrinking green, no change muted. */
    private static int deltaColor(long value, long base) {
        if (value == 0) {
            return Theme.TEXT_DISABLED;
        }
        double share = base <= 0 ? 1 : Math.abs(value) / (double) base;
        if (value > 0) {
            return share >= 0.1 ? Theme.DANGER : share >= 0.01 ? WARN : Theme.TEXT_MUTED;
        }
        return share >= 0.01 ? Theme.SUCCESS : Theme.TEXT_MUTED;
    }

    private static String percentChange(long from, long to) {
        if (from <= 0) {
            return to > 0 ? I18n.format("lagfinder.memory.new") : "";
        }
        double change = (to - from) * 100.0 / from;
        return (change > 0 ? "+" : "")
            + (Math.abs(change) >= 10 ? String.format("%.0f%%", change) : String.format("%.1f%%", change));
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
            report.bothSides ? "lagfinder.memory.title_both"
                : tps() ? "lagfinder.memory.title_tps" : "lagfinder.memory.title_fps");
        float firstHeap = heap.buckets() == 0 ? 0 : heap.heapMb[0];
        float lastHeap = heap.buckets() == 0 ? 0 : heap.heapMb[heap.buckets() - 1];
        String subtitle = I18n.format(
            "lagfinder.memory.subtitle",
            megabytes(firstHeap),
            megabytes(lastHeap),
            heap.maxHeap > 0 ? bytes(heap.maxHeap) : "?");
        if (heap.allocKnown && report.durationSec > 0) {
            subtitle += "  ·  "
                + I18n.format("lagfinder.memory.alloc_rate", megabytes(heap.allocatedMb() / report.durationSec));
        }
        subtitle += "  ·  "
            + I18n.format("lagfinder.memory.collections", heap.collections(), Names.ms(heap.collectionMs()));
        WindowHeader.draw(
            fontRendererObj,
            panelLeft,
            panelTop,
            panelRight,
            panelRight - WindowHeader.CLOSE_ROOM,
            Icons.SMALL_MEMORY,
            title,
            subtitle,
            Theme.TEXT_MUTED,
            modFilter >= 0 ? heap.modName(modFilter) : null);

        drawMods(mouseX, mouseY);
        drawGraph(mouseX, mouseY);
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
            showTooltip(I18n.format("lagfinder.tip.memory_sort"), mouseX, mouseY);
        }
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
        long most = 1;
        for (int mod : modOrder) {
            most = Math.max(most, last(heap.modBytes.get(mod)));
        }
        for (int i = -1; i < modOrder.size(); i++) {
            int y = top + (i + 1) * MOD_ROW - offset;
            if (y + MOD_ROW < top || y > bottom) {
                continue;
            }
            drawModRow(i < 0 ? -1 : modOrder.get(i), y, most, mouseX, mouseY, top, bottom);
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
        // How the objects were counted.
        Theme.fill(sideLeft + 1, bottom, sideRight - 1, bottom + 1, Theme.BORDER);
        String hint = I18n.format(heap.liveOnly ? "lagfinder.memory.live" : "lagfinder.memory.all");
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
            hint + "\n" + I18n.format(heap.liveOnly ? "lagfinder.tip.memory_live" : "lagfinder.tip.memory_all"));
    }

    /** The mods' shares of the heap (at the last count) as one bar under their title. */
    private void drawModsBar(int x0, int y0, int x1, int y1) {
        List<Integer> biggest = new ArrayList<>();
        for (int i = 0; i < heap.mods.size(); i++) {
            if (last(heap.modBytes.get(i)) > 0) {
                biggest.add(i);
            }
        }
        biggest.sort((a, b) -> Long.compare(last(heap.modBytes.get(b)), last(heap.modBytes.get(a))));
        int shown = Math.min(8, biggest.size());
        double[] values = new double[shown + 1];
        int[] colors = new int[shown + 1];
        double all = 0;
        for (int mod : biggest) {
            all += last(heap.modBytes.get(mod));
        }
        double rest = all;
        for (int i = 0; i < shown; i++) {
            int mod = biggest.get(i);
            values[i] = last(heap.modBytes.get(mod));
            colors[i] = Draw.modColor(heap.modName(mod));
            rest -= values[i];
        }
        values[shown] = Math.max(0, rest);
        colors[shown] = 0xFF3A4350;
        int[] starts = Theme.stackedBar(x0, y0, x1, y1, values, colors);
        for (int i = 0; i < shown; i++) {
            int mod = biggest.get(i);
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
                heap.modName(mod) + "\n"
                    + bytes(values[i])
                    + String.format("  ·  %.1f%%", all <= 0 ? 0 : values[i] * 100 / all)
                    + "\n"
                    + I18n.format(modFilter == mod ? "lagfinder.tip.unpick_mod" : "lagfinder.tip.memory_pick_mod"));
        }
    }

    private void drawModRow(int mod, int y, long most, int mouseX, int mouseY, int top, int bottom) {
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
        String name = all ? I18n.format("lagfinder.methods.all_mods") : heap.modName(mod);
        long[] bytes = all ? heap.totalBytes : heap.modBytes.get(mod);
        long[] instances = all ? heap.totalInstances : heap.modInstances.get(mod);
        int color = all ? Theme.ACCENT : Draw.modColor(name);
        int x = sideLeft + 7;
        int textY = y + 4;
        if (all) {
            Icons.draw(Icons.SMALL_GRID, x - 1, textY, picked ? Theme.ACCENT : Theme.TEXT_MUTED);
        } else {
            Theme.fill(x, textY + 1, x + 6, textY + 7, color);
        }
        // Its size, or its change when sorted by change.
        boolean byChange = sort == Sort.GROWTH || sort == Sort.COUNT_GROWTH;
        boolean byCount = sort == Sort.COUNT || sort == Sort.COUNT_GROWTH;
        long[] values = byCount ? instances : bytes;
        String value;
        int valueColor;
        if (byChange) {
            long change = delta(values);
            value = byCount ? countDelta(change) : bytesDelta(change);
            valueColor = deltaColor(change, first(values));
        } else {
            value = byCount ? count(last(values)) : bytes(last(values));
            valueColor = all ? Theme.TEXT_MUTED : Theme.TEXT;
        }
        int valueWidth = fontRendererObj.getStringWidth(value);
        int right = sideRight - 8;
        fontRendererObj.drawString(value, right - valueWidth, textY, valueColor);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, name, right - valueWidth - 6 - (x + 10)),
            x + 10,
            textY,
            picked || all ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, Math.max(lit, 0.6)));
        if (!all) {
            int barWidth = (int) Math.round((sideRight - sideLeft - 14) * Math.min(1, last(bytes) / (double) most));
            Theme.fill(
                sideLeft + 7,
                y + MOD_ROW - 3,
                sideLeft + 7 + Math.max(1, barWidth),
                y + MOD_ROW - 2,
                Theme.blend(color, 0x00000000, 0.35));
        }
        String tip = name + "\n"
            + I18n.format("lagfinder.memory.line", count(last(instances)), bytes(last(bytes)))
            + "\n"
            + I18n.format("lagfinder.memory.change", countDelta(delta(instances)), bytesDelta(delta(bytes)))
            + "\n"
            + I18n.format(
                all ? "lagfinder.tip.memory_all_mods"
                    : picked ? "lagfinder.tip.unpick_mod" : "lagfinder.tip.memory_pick_mod");
        hit(sideLeft + 1, y, sideRight - 1, y + MOD_ROW, () -> setFilter(mod), tip);
    }

    // ---------------------------------------------------------------- The graph

    private void drawGraph(int mouseX, int mouseY) {
        int x0 = listLeft, y0 = graphTop, x1 = listRight, y1 = graphBottom;
        Theme.card(x0, y0, x1, y1);
        int n = heap.buckets();
        float bucketSec = report.bucketSec > 0 ? report.bucketSec : report.durationSec / Math.max(1, n);

        // Legend: a sample of each thing drawn, as it looks on the graph.
        String pickedName = selected != null ? selected.simpleName() : modFilter >= 0 ? heap.modName(modFilter) : null;
        int pickedColor = selected != null ? Draw.modColor(heap.modName(selected.mod))
            : modFilter >= 0 ? Draw.modColor(heap.modName(modFilter)) : WARN;
        Chart.Legend legend = new Chart.Legend(fontRendererObj, x0 + 8, y0 + 6, x1 - 8);
        legend.line(I18n.format("lagfinder.memory.legend.heap"), HEAP_COLOR, true);
        if (heap.allocKnown) {
            legend.bar(I18n.format("lagfinder.memory.legend.alloc"), ALLOC_COLOR, false);
        }
        legend.mark(I18n.format("lagfinder.memory.legend.gc"), GC_COLOR);
        if (pickedName != null && snapshots() > 0) {
            legend.line(I18n.format("lagfinder.memory.legend.picked", pickedName), pickedColor, false);
        }

        if (x1 - x0 - 84 < 20 || y1 - y0 - 36 < 20 || n == 0) {
            return;
        }
        Chart chart = new Chart(fontRendererObj, x0 + 40, y0 + 22, x1 - 44, y1 - 14, n);
        chart.grid(4);
        // Left axis: the heap used.
        float heapMax = 1;
        for (int i = 0; i < n; i++) {
            heapMax = Math.max(heapMax, heap.heapHighMb[i]);
        }
        heapMax = (float) Draw.niceCeil(heapMax * 1.08);
        String[] heapLabels = new String[5];
        for (int i = 0; i <= 4; i++) {
            heapLabels[i] = megabytes(heapMax * i / 4f).replace(" ", "");
        }
        chart.leftAxis(heapLabels, HEAP_COLOR);

        // Right axis: the allocation, megabytes a second, as bars.
        float allocMax = 0;
        if (heap.allocKnown) {
            for (int i = 0; i < n; i++) {
                allocMax = Math.max(allocMax, heap.allocMb[i] / bucketSeconds(i, bucketSec, n));
            }
            allocMax = (float) Draw.niceCeil(Math.max(allocMax, 1) * 1.08);
            String[] allocLabels = new String[5];
            for (int i = 0; i <= 4; i++) {
                allocLabels[i] = i == 0 ? "0" : megabytes(allocMax * i / 4f).replace(" ", "") + "/s";
            }
            chart.rightAxis(allocLabels, ALLOC_COLOR);
            for (int i = 0; i < n; i++) {
                float rate = heap.allocMb[i] / bucketSeconds(i, bucketSec, n);
                if (rate > 0) {
                    chart.bar(
                        i,
                        0,
                        rate,
                        allocMax,
                        (ALLOC_COLOR & 0x00FFFFFF) | 0x60000000,
                        (ALLOC_COLOR & 0x00FFFFFF) | 0x18000000);
                }
            }
        }

        // The heap used: the band between its lowest and highest in each moment, and the line of what was used.
        for (int i = 0; i < n; i++) {
            if (heap.heapHighMb[i] > heap.heapLowMb[i]) {
                chart.bar(
                    i,
                    heap.heapLowMb[i],
                    heap.heapHighMb[i],
                    heapMax,
                    (HEAP_COLOR & 0x00FFFFFF) | 0x38000000,
                    (HEAP_COLOR & 0x00FFFFFF) | 0x20000000);
            }
        }
        chart.line(heap.heapMb, heapMax, Chart.solid(HEAP_COLOR, n), 1.5f, 0x30);

        // Collections: a mark at the top of the moments with any.
        for (int i = 0; i < n; i++) {
            if (heap.gcCount[i] > 0) {
                int cx = (int) Math.round(chart.center(i));
                Theme.fill(cx - 1, chart.y0 - 5, cx + 1, chart.y0 - 1, GC_COLOR);
            }
        }

        // The counts of the objects, as dashed lines, and what is picked through them.
        double duration = Math.max(0.001, report.durationSec);
        int[] snapX = new int[snapshots()];
        for (int s = 0; s < snapshots(); s++) {
            snapX[s] = (int) Math.round(chart.x0 + (chart.x1 - chart.x0) * Math.min(1, heap.snapshotSec[s] / duration));
            chart.marker(snapX[s], (SNAPSHOT_COLOR & 0x00FFFFFF) | 0x90000000);
            String label = String.valueOf(s + 1);
            int labelX = Math.max(chart.x0, Math.min(chart.x1 - fontRendererObj.getStringWidth(label), snapX[s] + 2));
            fontRendererObj.drawString(label, labelX, chart.y0 + 1, Theme.TEXT_DISABLED);
        }
        long[] picked = pickedBytes();
        if (picked != null && picked.length > 0) {
            long pickedMax = 1;
            for (long value : picked) {
                pickedMax = Math.max(pickedMax, value);
            }
            double scale = Draw.niceCeil(pickedMax * 1.1);
            double[] pxs = new double[picked.length], pys = new double[picked.length];
            for (int s = 0; s < picked.length; s++) {
                pxs[s] = snapX[s] + 0.5;
                pys[s] = chart.y(picked[s], scale);
            }
            int[] glow = Chart.solid((pickedColor & 0x00FFFFFF) | 0x30000000, picked.length);
            Draw.line(pxs, pys, glow, 4.5f);
            Draw.line(pxs, pys, Chart.solid(pickedColor, picked.length), 2f);
            for (int s = 0; s < picked.length; s++) {
                Chart.dot(pxs[s] - 0.5, pys[s], pickedColor);
                String label = bytes(picked[s]);
                int w = fontRendererObj.getStringWidth(label);
                int lx2 = (int) Math.max(chart.x0, Math.min(chart.x1 - w, pxs[s] - w / 2.0));
                int ly2 = (int) Math.max(chart.y0 + 10, pys[s] - 12);
                Theme.fill(lx2 - 2, ly2 - 1, lx2 + w + 2, ly2 + 9, Theme.LABEL_BG);
                fontRendererObj.drawString(label, lx2, ly2, pickedColor);
            }
        }
        chart.timeAxis(report.durationSec);

        // What was at the moment under the mouse: marked on the lines with its values beside the axes.
        if (Theme.inside(mouseX, mouseY, chart.x0, chart.y0 - 6, chart.x1, chart.y1)) {
            int i = chart.bucketAt(mouseX);
            chart.hover(i);
            float seconds = bucketSeconds(i, bucketSec, n);
            if (heap.allocKnown) {
                double allocY = chart.y(heap.allocMb[i] / seconds, allocMax);
                chart.rightValue(allocY, megabytes(heap.allocMb[i] / seconds).replace(" ", "") + "/s", ALLOC_COLOR);
            }
            double heapY = chart.y(heap.heapMb[i], heapMax);
            Chart.dot(chart.center(i), heapY, HEAP_COLOR);
            chart.leftValue(heapY, megabytes(heap.heapMb[i]).replace(" ", ""), HEAP_COLOR);

            List<String> lines = new ArrayList<>();
            List<Integer> lineColors = new ArrayList<>();
            float from = i * bucketSec, to = Math.min(report.durationSec, (i + 1) * bucketSec);
            lines.add(Names.clock(from) + " – " + Names.clock(to));
            lineColors.add(Theme.TEXT_MUTED);
            lines.add(
                I18n.format(
                    "lagfinder.memory.graph.heap",
                    megabytes(heap.heapMb[i]),
                    megabytes(heap.heapLowMb[i]),
                    megabytes(heap.heapHighMb[i])));
            lineColors.add(HEAP_COLOR);
            if (heap.allocKnown) {
                lines.add(
                    I18n.format(
                        "lagfinder.memory.graph.alloc",
                        megabytes(heap.allocMb[i] / seconds),
                        megabytes(heap.threadAllocMb[i] / seconds)));
                lineColors.add(ALLOC_COLOR);
            }
            if (heap.gcCount[i] > 0) {
                lines.add(I18n.format("lagfinder.memory.graph.gc", heap.gcCount[i], Names.ms(heap.gcMs[i])));
                lineColors.add(GC_COLOR);
            }
            // A count of the objects near the mouse.
            int near = -1;
            for (int s = 0; s < snapshots(); s++) {
                if (Math.abs(snapX[s] - mouseX) <= Math.max(3, chart.step)
                    && (near < 0 || Math.abs(snapX[s] - mouseX) < Math.abs(snapX[near] - mouseX))) {
                    near = s;
                }
            }
            if (near >= 0) {
                lines.add(
                    I18n.format(
                        "lagfinder.memory.graph.snapshot",
                        near + 1,
                        count(heap.totalInstances[near]),
                        bytes(heap.totalBytes[near])));
                lineColors.add(Theme.TEXT);
                lines.add("  " + I18n.format("lagfinder.memory.graph.pause", Names.ms(heap.snapshotPauseMs[near])));
                lineColors.add(Theme.TEXT_MUTED);
                if (picked != null && near < picked.length) {
                    lines.add(pickedName + ": " + bytes(picked[near]) + "  ·  " + count(pickedInstances()[near]));
                    lineColors.add(pickedColor);
                }
            }
            Draw.tooltip(fontRendererObj, lines, lineColors, mouseX, mouseY, width, height);
        }
    }

    /** Seconds of the moment; the last may be shorter. */
    private float bucketSeconds(int bucket, float bucketSec, int buckets) {
        if (bucket == buckets - 1) {
            float rest = report.durationSec - bucket * bucketSec;
            if (rest > bucketSec * 0.05f) {
                return Math.min(bucketSec, rest);
            }
        }
        return Math.max(0.001f, bucketSec);
    }

    /** The bytes in each count of what is picked: a class, else the mod; null when nothing is. */
    private long[] pickedBytes() {
        if (selected != null) {
            return selected.bytes;
        }
        return modFilter >= 0 ? heap.modBytes.get(modFilter) : null;
    }

    private long[] pickedInstances() {
        if (selected != null) {
            return selected.instances;
        }
        return modFilter >= 0 ? heap.modInstances.get(modFilter) : new long[snapshots()];
    }

    // ---------------------------------------------------------------- The classes

    /** Where a column of values ends, from the left: objects, their change, size, its change, the trend. */
    private int columnRight(int column) {
        int right = listRight - 8;
        int[] widths = { COUNT_COLUMN, COUNT_DELTA_COLUMN, SIZE_COLUMN, SIZE_DELTA_COLUMN, TREND_COLUMN };
        for (int i = widths.length - 1; i > column; i--) {
            right -= widths[i];
        }
        return right;
    }

    private void drawList(int mouseX, int mouseY) {
        int headY = listTop - 11;
        fontRendererObj
            .drawString(I18n.format("lagfinder.memory.column.class"), listLeft + 4, headY, Theme.TEXT_DISABLED);
        String[] titles = { I18n.format("lagfinder.memory.column.count"),
            I18n.format("lagfinder.memory.column.count_delta"), I18n.format("lagfinder.memory.column.size"),
            I18n.format("lagfinder.memory.column.size_delta"), I18n.format("lagfinder.memory.column.trend") };
        String[] tips = { "lagfinder.tip.memory_count", "lagfinder.tip.memory_count_delta", "lagfinder.tip.memory_size",
            "lagfinder.tip.memory_size_delta", "lagfinder.tip.memory_trend" };
        Sort[] sorts = { Sort.COUNT, Sort.COUNT_GROWTH, Sort.SIZE, Sort.GROWTH, null };
        int[] widths = { COUNT_COLUMN, COUNT_DELTA_COLUMN, SIZE_COLUMN, SIZE_DELTA_COLUMN, TREND_COLUMN };
        for (int i = 0; i < titles.length; i++) {
            int right = columnRight(i);
            Sort columnSort = sorts[i];
            fontRendererObj.drawString(
                titles[i],
                right - fontRendererObj.getStringWidth(titles[i]),
                headY,
                sort == columnSort ? Theme.ACCENT : Theme.TEXT_DISABLED);
            hit(right - widths[i] + 4, headY - 2, right + 2, headY + 10, columnSort == null ? null : () -> {
                sort = columnSort;
                scroll = 0;
                updateButtons();
                rebuild();
            }, titles[i] + "\n" + I18n.format(tips[i]));
        }

        Theme.card(listLeft, listTop - 1, listRight, listBottom);
        if (rows.isEmpty()) {
            String empty;
            if (heap.histogramError != null) {
                empty = I18n.format("lagfinder.memory.no_histogram");
            } else if (snapshots() == 0) {
                empty = I18n.format("lagfinder.memory.no_snapshots");
            } else {
                empty = I18n.format(lastSearch.isEmpty() ? "lagfinder.gui.nothing" : "lagfinder.gui.nothing_found");
            }
            Theme.centered(fontRendererObj, empty, (listLeft + listRight) / 2, listTop + 20, Theme.TEXT_DISABLED);
            if (heap.histogramError != null) {
                Theme.centered(
                    fontRendererObj,
                    Theme.ellipsize(fontRendererObj, heap.histogramError, listRight - listLeft - 20),
                    (listLeft + listRight) / 2,
                    listTop + 34,
                    Theme.TEXT_DISABLED);
            }
            return;
        }
        int offset = (int) Math.round(shownScroll.update(scroll, 18));
        int top = listTop + 1, bottom = listBottom - 1;
        Theme.clip(listLeft + 1, top, listRight - 1, bottom);
        clipHits(listLeft + 1, top, listRight - 1, bottom);
        long mostBytes = 1;
        for (HeapClass type : rows) {
            mostBytes = Math.max(mostBytes, last(type.bytes));
        }
        int firstRow = Math.max(0, offset / ROW - 1);
        for (int i = firstRow; i < rows.size(); i++) {
            int y = top + i * ROW - offset;
            if (y > bottom) {
                break;
            }
            drawRow(rows.get(i), y, mostBytes, mouseX, mouseY);
        }
        Theme.unclip();
        unclipHits();
        int contentHeight = rows.size() * ROW;
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

    private void drawRow(HeapClass type, int y, long mostBytes, int mouseX, int mouseY) {
        boolean hovered = Theme.inside(mouseX, mouseY, listLeft + 1, y, listRight - 1, y + ROW)
            && Theme.inside(mouseX, mouseY, listLeft, listTop, listRight, listBottom);
        boolean isSelected = type == selected;
        double lit = light(type, hovered);
        String modName = heap.modName(type.mod);
        int modColor = Draw.modColor(modName);

        // Its size as a soft bar behind the line.
        int barRight = listLeft + 1
            + (int) Math.round((listRight - listLeft - 2) * Math.min(1, last(type.bytes) / (double) mostBytes));
        Theme.fill(listLeft + 1, y, Math.max(listLeft + 1, barRight), y + ROW, (modColor & 0x00FFFFFF) | 0x14000000);
        if (isSelected) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + ROW, SELECTED);
            Theme.fill(listLeft + 1, y, listLeft + 3, y + ROW, Theme.ACCENT);
        } else if (lit > 0.01) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        hit(listLeft + 1, y, listRight - 1, y + ROW, () -> selected = selected == type ? null : type, null);

        int textY = y + (ROW - 8) / 2;
        long countChange = delta(type.instances), sizeChange = delta(type.bytes);
        String[] values = { count(last(type.instances)), countDelta(countChange), bytes(last(type.bytes)),
            bytesDelta(sizeChange) };
        int[] colors = { Theme.TEXT, deltaColor(countChange, first(type.instances)), Theme.TEXT,
            deltaColor(sizeChange, first(type.bytes)) };
        for (int i = 0; i < values.length; i++) {
            fontRendererObj
                .drawString(values[i], columnRight(i) - fontRendererObj.getStringWidth(values[i]), textY, colors[i]);
        }
        drawTrend(type.bytes, columnRight(4) - TREND_COLUMN + 8, y + 2, columnRight(4), y + ROW - 2, modColor);
        int textRight = columnRight(0) - COUNT_COLUMN + 2;

        int x = listLeft + 7;
        Theme.fill(x, y + ROW / 2 - 2, x + 4, y + ROW / 2 + 2, modColor);
        x += 7;
        if (leaking.contains(type)) {
            // Grew at every count: what a leak looks like.
            Icons.draw(Icons.SMALL_WARN, x, y + (ROW - 7) / 2, Theme.DANGER);
            x += 10;
        }
        String mod = modFilter < 0 ? "  " + modName : "";
        int modWidth = mod.isEmpty() ? 0 : Math.min(fontRendererObj.getStringWidth(mod), 100);
        int room = textRight - x - modWidth;
        boolean matched = !query.isEmpty() && matches(type);
        String name = type.simpleName();
        String shown = Theme.ellipsize(fontRendererObj, name, room);
        fontRendererObj.drawString(shown, x, textY, matched ? WARN : Theme.TEXT);
        int after = x + fontRendererObj.getStringWidth(shown);
        String pkg = "  " + type.packageName();
        if (after + 20 < x + room && !type.packageName()
            .isEmpty()) {
            fontRendererObj
                .drawString(Theme.ellipsize(fontRendererObj, pkg, x + room - after), after, textY, Theme.TEXT_DISABLED);
        }
        if (!mod.isEmpty()) {
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, mod, modWidth),
                textRight - modWidth,
                textY,
                Theme.blend(modColor, Theme.TEXT_DISABLED, 0.35));
        }
        if (hovered) {
            hit(x, y, textRight, y + ROW, () -> selected = selected == type ? null : type, tooltipOf(type));
        }
    }

    /** The bytes of each count as a small line, from its lowest to its highest. */
    private void drawTrend(long[] values, int x0, int y0, int x1, int y1, int color) {
        if (values.length < 2 || x1 - x0 < 6) {
            return;
        }
        long low = Long.MAX_VALUE, high = Long.MIN_VALUE;
        for (long value : values) {
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        double[] xs = new double[values.length], ys = new double[values.length];
        int[] colors = new int[values.length];
        int lineColor = high == low ? Theme.TEXT_DISABLED
            : last(values) > first(values) ? Theme.blend(Theme.DANGER, color, 0.3)
                : Theme.blend(Theme.SUCCESS, color, 0.3);
        for (int i = 0; i < values.length; i++) {
            xs[i] = x0 + (x1 - x0) * i / (double) (values.length - 1);
            ys[i] = high == low ? (y0 + y1) / 2.0 : y1 - (y1 - y0) * (values[i] - low) / (double) (high - low);
            colors[i] = lineColor;
        }
        Draw.line(xs, ys, colors, 1f);
    }

    private String tooltipOf(HeapClass type) {
        StringBuilder text = new StringBuilder(type.name);
        text.append("\n")
            .append(I18n.format("lagfinder.methods.mod_of", heap.modName(type.mod)));
        text.append("\n")
            .append(I18n.format("lagfinder.memory.line", count(last(type.instances)), bytes(last(type.bytes))));
        text.append("\n")
            .append(
                I18n.format(
                    "lagfinder.memory.change",
                    countDelta(delta(type.instances)),
                    bytesDelta(delta(type.bytes))));
        long objects = last(type.instances);
        if (objects > 0) {
            text.append("\n")
                .append(I18n.format("lagfinder.memory.object_size", bytes(last(type.bytes) / (double) objects)));
        }
        if (leaking.contains(type)) {
            text.append("\n")
                .append(I18n.format("lagfinder.memory.leak_tip", snapshots()));
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- The pick

    private void drawDetails(int mouseX, int mouseY) {
        int x0 = listLeft, y0 = listBottom + 6, x1 = listRight, y1 = bodyBottom;
        Theme.card(x0, y0, x1, y1);
        HeapClass type = selected;
        int x = x0 + 8, y = y0 + 7;
        if (type == null && snapshots() >= 3) {
            drawLeaks(x0, y0, x1, mouseX, mouseY);
            return;
        }
        if (type == null) {
            Icons.draw(Icons.SMALL_SEARCH, x0 + 8, y0 + 8, Theme.TEXT_MUTED);
            String summary;
            if (snapshots() > 0) {
                int lastSnapshot = snapshots() - 1;
                summary = I18n.format(
                    "lagfinder.memory.summary",
                    snapshots(),
                    count(heap.totalInstances[0]),
                    count(heap.totalInstances[lastSnapshot]),
                    bytes(heap.totalBytes[0]),
                    bytes(heap.totalBytes[lastSnapshot]),
                    heap.classCount[lastSnapshot]);
            } else {
                summary = I18n.format("lagfinder.memory.no_snapshots");
            }
            fontRendererObj
                .drawString(Theme.ellipsize(fontRendererObj, summary, x1 - x0 - 30), x0 + 19, y0 + 8, Theme.TEXT_MUTED);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.memory.hint"), x1 - x0 - 30),
                x0 + 19,
                y0 + 22,
                Theme.TEXT_DISABLED);
            return;
        }
        String modName = heap.modName(type.mod);
        int modColor = Draw.modColor(modName);
        Theme.fill(x, y + 1, x + 6, y + 7, modColor);
        int pillWidth = fontRendererObj.getStringWidth(modName) + 10;
        int textRight = x1 - 8;
        int pillX = textRight - pillWidth;
        Theme.pill(fontRendererObj, modName, pillX, y - 2, modColor);
        fontRendererObj.drawStringWithShadow(
            Theme.ellipsize(fontRendererObj, type.name, pillX - 6 - (x + 10)),
            x + 10,
            y,
            Theme.TEXT);
        y += 14;
        // Each count of it, one after the other.
        StringBuilder line = new StringBuilder();
        for (int s = 0; s < type.bytes.length; s++) {
            if (s > 0) {
                line.append("  →  ");
            }
            line.append(Names.clock(heap.snapshotSec[s]))
                .append(' ')
                .append(count(type.instances[s]))
                .append(" / ")
                .append(bytes(type.bytes[s]));
        }
        line.append("   (")
            .append(percentChange(first(type.bytes), last(type.bytes)))
            .append(')');
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, line.toString(), textRight - x), x, y, Theme.TEXT_MUTED);
    }

    /**
     * With nothing picked: the class that may be leaking the most (grew at every count), with a button to it; or that
     * none grew like that.
     */
    private void drawLeaks(int x0, int y0, int x1, int mouseX, int mouseY) {
        int x = x0 + 8, y = y0 + 7;
        if (leaks.isEmpty()) {
            Icons.draw(Icons.SMALL_CHECK, x, y, Theme.SUCCESS);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.memory.no_leak"), x1 - x - 20),
                x + 11,
                y,
                Theme.SUCCESS);
            int last = snapshots() - 1;
            String summary = I18n.format(
                "lagfinder.memory.summary",
                snapshots(),
                count(heap.totalInstances[0]),
                count(heap.totalInstances[last]),
                bytes(heap.totalBytes[0]),
                bytes(heap.totalBytes[last]),
                heap.classCount[last]);
            fontRendererObj
                .drawString(Theme.ellipsize(fontRendererObj, summary, x1 - x - 8), x, y + 14, Theme.TEXT_MUTED);
            return;
        }
        HeapClass type = leaks.get(0);
        String action = I18n.format("lagfinder.memory.show_leak");
        int buttonWidth = fontRendererObj.getStringWidth(action) + 24;
        int bx = x1 - 8 - buttonWidth, by = y0 + 11;
        boolean overButton = Theme.inside(mouseX, mouseY, bx, by, bx + buttonWidth, by + 18);
        double buttonLit = light("action", overButton);
        Theme.glow(bx, by, bx + buttonWidth, by + 18, Theme.DANGER, 0.3 + 0.5 * buttonLit);
        Theme.gradient(bx, by, bx + buttonWidth, by + 18, Theme.blend(0xFF3A2224, 0xFF4A2A2C, buttonLit), 0xFF2A1A1C);
        Theme.outline(bx, by, bx + buttonWidth, by + 18, Theme.blend(0xFF6A3A3A, Theme.DANGER, buttonLit));
        Icons.draw(Icons.SMALL_SEARCH, bx + 7, by + 6, Theme.TEXT);
        fontRendererObj.drawString(action, bx + 18, by + 5, Theme.TEXT);
        hit(bx, by, bx + buttonWidth, by + 18, () -> showClass(type), I18n.format("lagfinder.tip.show_leak"));

        Icons.draw(Icons.SMALL_WARN, x, y, Theme.DANGER);
        String label = I18n.format("lagfinder.memory.leak")
            .toUpperCase() + "  ";
        fontRendererObj.drawString(label, x + 10, y, Theme.DANGER);
        int after = x + 10 + fontRendererObj.getStringWidth(label);
        String modName = heap.modName(type.mod);
        int pillWidth = fontRendererObj.getStringWidth(modName) + 10;
        int textRight = bx - 10;
        Theme.pill(fontRendererObj, modName, textRight - pillWidth, y - 2, Draw.modColor(modName));
        fontRendererObj.drawStringWithShadow(
            Theme.ellipsize(fontRendererObj, type.simpleName(), textRight - pillWidth - 6 - after),
            after,
            y,
            Theme.TEXT);
        y += 14;
        String line = I18n.format(
            "lagfinder.memory.leak_line",
            bytesDelta(delta(type.bytes)),
            percentChange(first(type.bytes), last(type.bytes)),
            snapshots());
        if (leaks.size() > 1) {
            line += "  ·  " + I18n.format("lagfinder.memory.leak_more", leaks.size() - 1);
        }
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, line, textRight - x), x, y, Theme.TEXT_MUTED);
    }

    /** Picks the class and scrolls to it, showing all mods and dropping the search when they hide it. */
    private void showClass(HeapClass type) {
        if (modFilter >= 0 && type.mod != modFilter) {
            modFilter = -1;
        }
        if (!matches(type)) {
            search.setText("");
        }
        selected = type;
        rebuild();
        scrollTo(type);
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
