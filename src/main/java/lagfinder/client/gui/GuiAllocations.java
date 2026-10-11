package lagfinder.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.WindowHeader;
import lagfinder.core.AllocationProfile;
import lagfinder.core.AllocationProfile.Group;
import lagfinder.core.AllocationProfile.Site;
import lagfinder.core.Report;
import lagfinder.core.ScanMode;

/**
 * The objects created during a scan and who created them. Two views of the same samples: by the type of the objects
 * (each type opens to the classes that created it, each class to its methods and lines), or by the class that created
 * them (each opens to the types it created, then to its methods). On top, how fast the game allocated over the scan,
 * with what is picked drawn over it; on the left, the mods of the classes creating them; at the bottom, what is
 * picked, with a jump to it in the other view.
 */
public class GuiAllocations extends ScaledScreen {

    private static final int ID_CLOSE = 0, ID_TYPES = 1, ID_CREATORS = 2, ID_THREADS = 3, ID_SORT = 4, ID_EXPAND = 5,
        ID_COLLAPSE = 6;
    private static final int ROW = 13, MOD_ROW = 18, INDENT = 12;
    private static final int SIDE_WIDTH = 176, DETAILS_HEIGHT = 40;
    private static final int SHARE_COLUMN = 50, BYTES_COLUMN = 60, RATE_COLUMN = 64, TREND_COLUMN = 52;
    private static final int SELECTED = 0xFF1E2C40;
    private static final int WARN = 0xFFF2C14E;
    private static final int RATE_COLOR = 0xFFA371F7;

    private enum Sort {

        SIZE("lagfinder.alloc.sort.size"),
        NAME("lagfinder.alloc.sort.name"),
        MOD("lagfinder.alloc.sort.mod");

        final String key;

        Sort(String key) {
            this.key = key;
        }
    }

    /** The choices stay while the game runs. */
    private static boolean byCreator;
    private static boolean allThreads;
    private static Sort sort = Sort.SIZE;

    private final Report report;
    private final AllocationProfile profile;
    private final GuiScreen parent;
    /** The mod of the creating classes shown (index into the profile's mods), -1 for all. */
    private int modFilter = -1;

    /**
     * A line of the tree: a type or a class (the first two levels, {@link #group} set), or a place in a method (the
     * last, {@link #site} set). Lines are made anew on every change; what is open and picked goes by {@link #id}.
     */
    private static final class Line {

        final String id;
        final int level;
        final Line parent;
        /** For a type or a class: whether it is a type of objects (else a class creating them). */
        final boolean isType;
        final int group;
        final Site site;
        long bytes;
        int samples;
        final List<Line> children = new ArrayList<>();
        /** Children by their type or class, while they are added up. */
        Map<Integer, Line> byGroup;

        Line(String id, int level, Line parent, boolean isType, int group, Site site) {
            this.id = id;
            this.level = level;
            this.parent = parent;
            this.isType = isType;
            this.group = group;
            this.site = site;
        }

        Line top() {
            Line line = this;
            while (line.parent != null) {
                line = line.parent;
            }
            return line;
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

    private final List<Line> roots = new ArrayList<>();
    /** The lines shown, top to bottom. */
    private final List<Line> rows = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private String selectedId;
    private Line selected;
    /** Bytes of each mod's creating classes, with the search and the thread chosen. */
    private long[] modBytes = new long[0];
    private final List<Integer> modOrder = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int clipX0 = Integer.MIN_VALUE, clipY0 = Integer.MIN_VALUE, clipX1 = Integer.MAX_VALUE,
        clipY1 = Integer.MAX_VALUE;
    private final Map<Object, Smooth> lights = new HashMap<>();
    private int scroll, sideScroll;
    private final Smooth shownScroll = new Smooth(0), shownSideScroll = new Smooth(0);
    private String query = "";

    private int panelLeft, panelTop, panelRight, panelBottom;
    private int toolbarY, bodyBottom;
    private int sideLeft, sideRight, sideTop;
    private int graphTop, graphBottom;
    private int listLeft, listRight, listTop, listBottom;
    private FlatTextField search;
    private FlatButton typesButton, creatorsButton, threadsButton, sortButton;
    private IconButton expandButton, collapseButton;
    private String lastSearch = "";
    /** The first line, opened when the window opens, is picked once laid out. */
    private boolean firstLayout = true;

    public GuiAllocations(Report report) {
        this.report = report;
        this.profile = report.allocations;
        this.parent = net.minecraft.client.Minecraft.getMinecraft().currentScreen;
        if (!profile.typesKnown()) {
            byCreator = true;
        }
        if (profile.source == AllocationProfile.SOURCE_STACKS) {
            allThreads = false;
        }
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
        graphBottom = graphTop + Math.max(64, Math.min(120, body * 3 / 10));
        listTop = graphBottom + 18;
        listBottom = bodyBottom - DETAILS_HEIGHT - 6;

        buttonList.clear();
        int x = listLeft;
        String typesText = I18n.format("lagfinder.alloc.by_type");
        int typesWidth = fontRendererObj.getStringWidth(typesText) + 26;
        typesButton = new FlatButton(ID_TYPES, x, toolbarY, typesWidth, 16, typesText);
        typesButton.icon = Icons.SMALL_CUBE;
        typesButton.enabled = profile.typesKnown();
        buttonList.add(typesButton);
        x += typesWidth + 3;
        String creatorsText = I18n.format("lagfinder.alloc.by_creator");
        int creatorsWidth = fontRendererObj.getStringWidth(creatorsText) + 26;
        creatorsButton = new FlatButton(ID_CREATORS, x, toolbarY, creatorsWidth, 16, creatorsText);
        creatorsButton.icon = Icons.SMALL_ALLOC;
        buttonList.add(creatorsButton);
        x += creatorsWidth + 8;

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
            I18n.format("lagfinder.alloc.expand"));
        buttonList.add(expandButton);
        sortButton = new FlatButton(ID_SORT, right - 43 - 4 - 92, toolbarY, 92, 16, "");
        sortButton.icon = Icons.SMALL_SORT;
        buttonList.add(sortButton);
        threadsButton = new FlatButton(ID_THREADS, sortButton.xPosition - 4 - 104, toolbarY, 104, 16, "");
        threadsButton.icon = Icons.SMALL_GRID;
        threadsButton.enabled = profile.source == AllocationProfile.SOURCE_JFR;
        buttonList.add(threadsButton);

        String text = search != null ? search.getText() : "";
        search = new FlatTextField(fontRendererObj, x, toolbarY, Math.max(60, threadsButton.xPosition - 6 - x), 16);
        search.setHint(I18n.format("lagfinder.alloc.search"));
        search.setMaxStringLength(80);
        search.setText(text);
        search.setOnCleared(this::rebuild);

        buttonList.add(WindowHeader.closeButton(ID_CLOSE, panelRight, panelTop));
        updateButtons();
        rebuild();
        if (firstLayout) {
            firstLayout = false;
            openFirst();
        }
    }

    private void updateButtons() {
        typesButton.active = !byCreator;
        creatorsButton.active = byCreator;
        sortButton.displayString = I18n.format(sort.key);
        threadsButton.displayString = allThreads ? I18n.format("lagfinder.alloc.threads_all")
            : I18n.format("lagfinder.alloc.threads_one");
        threadsButton.icon = allThreads ? Icons.SMALL_GLOBE : tps() ? Icons.SMALL_GRID : Icons.SMALL_FRAME;
        threadsButton.active = allThreads;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    // ---------------------------------------------------------------- What is shown

    private boolean target() {
        return !allThreads;
    }

    private Group type(int index) {
        return profile.types.get(index);
    }

    private Group creator(int index) {
        return profile.creators.get(index);
    }

    private Group groupOf(Line line) {
        if (line.group < 0) {
            return null;
        }
        return line.isType ? type(line.group) : creator(line.group);
    }

    /** The mod a line stands for: a type's own, else the creating class's. */
    private int modOf(Line line) {
        if (line.site != null) {
            return creator(line.site.creator).mod;
        }
        Group group = groupOf(line);
        return group == null ? -1 : group.mod;
    }

    private boolean matchesText(String text) {
        return text.toLowerCase(Locale.ROOT)
            .contains(query);
    }

    private boolean matches(Site site) {
        if (query.isEmpty()) {
            return true;
        }
        Group type = type(site.type), creator = creator(site.creator);
        return profile.typesKnown() && matchesText(type.name) || matchesText(creator.name)
            || matchesText(site.method)
            || matchesText(site.via)
            || matchesText(profile.modName(creator.mod))
            || profile.typesKnown() && matchesText(profile.modName(type.mod));
    }

    /** Whether the line's own name is what is searched, to light it. */
    private boolean lineMatches(Line line) {
        if (query.isEmpty()) {
            return false;
        }
        if (line.site != null) {
            return matchesText(line.site.method) || matchesText(line.site.via);
        }
        Group group = groupOf(line);
        return group != null && matchesText(group.name);
    }

    /** Lays the lines out again: after a change of view, thread, sorting, mod, search or what is open. */
    private void rebuild() {
        lastSearch = search.getText();
        query = lastSearch.trim()
            .toLowerCase(Locale.ROOT);
        boolean target = target();
        boolean types = profile.typesKnown();
        roots.clear();
        modBytes = new long[profile.mods.size()];
        Map<Integer, Line> top = new LinkedHashMap<>();
        for (int index = 0; index < profile.sites.size(); index++) {
            Site site = profile.sites.get(index);
            long bytes = site.bytes(target);
            if (bytes <= 0 || !matches(site)) {
                continue;
            }
            int mod = creator(site.creator).mod;
            if (mod >= 0 && mod < modBytes.length) {
                modBytes[mod] += bytes;
            }
            if (modFilter >= 0 && mod != modFilter) {
                continue;
            }
            int samples = site.samples(target);
            int first = byCreator ? site.creator : site.type;
            Line root = top.get(first);
            if (root == null) {
                root = new Line((byCreator ? "C" : "T") + first, 0, null, !byCreator, first, null);
                top.put(first, root);
                roots.add(root);
            }
            root.bytes += bytes;
            root.samples += samples;
            Line under = root;
            if (types) {
                // The second level: the classes that created a type, or the types a class created.
                int second = byCreator ? site.type : site.creator;
                if (root.byGroup == null) {
                    root.byGroup = new HashMap<>();
                }
                under = root.byGroup.get(second);
                if (under == null) {
                    under = new Line(
                        root.id + "/" + (byCreator ? "T" : "C") + second,
                        1,
                        root,
                        byCreator,
                        second,
                        null);
                    root.byGroup.put(second, under);
                    root.children.add(under);
                }
                under.bytes += bytes;
                under.samples += samples;
            }
            Line place = new Line(under.id + "/S" + index, under.level + 1, under, false, -1, site);
            place.bytes = bytes;
            place.samples = samples;
            under.children.add(place);
        }
        sortLines(roots);

        rows.clear();
        selected = null;
        for (Line root : roots) {
            addRows(root);
        }
        if (selected == null && selectedId != null) {
            // What was picked isn't shown any more: its nearest line that is.
            for (Line row : rows) {
                if (selectedId.startsWith(row.id + "/")) {
                    selected = row;
                }
            }
        }

        modOrder.clear();
        for (int i = 0; i < modBytes.length; i++) {
            if (modBytes[i] > 0 || i == modFilter) {
                modOrder.add(i);
            }
        }
        modOrder.sort((a, b) -> Long.compare(modBytes[b], modBytes[a]));
        clampScroll();
        clampSideScroll();
    }

    private Comparator<Line> order() {
        Comparator<Line> bySize = (a, b) -> Long.compare(b.bytes, a.bytes);
        switch (sort) {
            case NAME:
                return Comparator.comparing((Line line) -> nameOf(line).toLowerCase(Locale.ROOT))
                    .thenComparing(bySize);
            case MOD:
                return Comparator.comparing(
                    (Line line) -> profile.modName(modOf(line))
                        .toLowerCase(Locale.ROOT))
                    .thenComparing(bySize);
            default:
                return bySize;
        }
    }

    private void sortLines(List<Line> lines) {
        // Places in a method go by size: their order by name would say nothing.
        lines.sort(!lines.isEmpty() && lines.get(0).site != null ? (a, b) -> Long.compare(b.bytes, a.bytes) : order());
        for (Line line : lines) {
            line.byGroup = null;
            if (!line.children.isEmpty()) {
                sortLines(line.children);
            }
        }
    }

    private boolean isOpen(Line line) {
        // A search opens all it found, so the places in the methods show.
        return expanded.contains(line.id) || !query.isEmpty();
    }

    private void addRows(Line line) {
        rows.add(line);
        if (line.id.equals(selectedId)) {
            selected = line;
        }
        if (!line.children.isEmpty() && isOpen(line)) {
            for (Line child : line.children) {
                addRows(child);
            }
        }
    }

    private int contentHeight() {
        return rows.size() * ROW;
    }

    private int listHeight() {
        return listBottom - listTop;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, contentHeight() - listHeight() + 2));
    }

    private int sideHeight() {
        return bodyBottom - sideTop - 18;
    }

    private void clampSideScroll() {
        sideScroll = Math.max(0, Math.min(sideScroll, (modOrder.size() + 1) * MOD_ROW - sideHeight()));
    }

    private void select(Line line) {
        selected = line;
        selectedId = line == null ? null : line.id;
    }

    /** Picks the biggest line and opens it to the biggest under it, so the window doesn't open empty-handed. */
    private void openFirst() {
        if (roots.isEmpty()) {
            return;
        }
        Line first = roots.get(0);
        expanded.add(first.id);
        if (!first.children.isEmpty() && first.children.get(0).site == null) {
            expanded.add(first.children.get(0).id);
        }
        select(first);
        rebuild();
    }

    /** Opens the line, and the lines under it while there is only one. */
    private void open(Line line) {
        Line at = line;
        while (at != null && !at.children.isEmpty()) {
            expanded.add(at.id);
            at = at.children.size() == 1 ? at.children.get(0) : null;
        }
    }

    private void toggle(Line line) {
        if (!expanded.remove(line.id)) {
            open(line);
        }
        rebuild();
    }

    /** Opens the way to the biggest place under the line. */
    private void expandBiggest(Line line) {
        Line at = line;
        while (at != null && !at.children.isEmpty()) {
            expanded.add(at.id);
            at = at.children.get(0);
        }
        rebuild();
    }

    private void setFilter(int mod) {
        modFilter = modFilter == mod ? -1 : mod;
        scroll = 0;
        rebuild();
        scrollTo(selected);
    }

    private void setView(boolean creators) {
        if (creators == byCreator || !creators && !profile.typesKnown()) {
            return;
        }
        byCreator = creators;
        scroll = 0;
        select(null);
        updateButtons();
        rebuild();
        openFirst();
    }

    /** Shows the type (or the class) in the view by it, opened, picked and scrolled to. */
    private void reveal(boolean isType, int group) {
        byCreator = !isType;
        updateButtons();
        String id = (isType ? "T" : "C") + group;
        expanded.add(id);
        selectedId = id;
        rebuild();
        if (selected == null && modFilter >= 0) {
            // Hidden by the mod picked: shown with all of them.
            modFilter = -1;
            rebuild();
        }
        scrollTo(selected);
    }

    private void scrollTo(Line line) {
        int index = rows.indexOf(line);
        if (index < 0) {
            return;
        }
        int y = index * ROW;
        if (y < scroll || y + ROW > scroll + listHeight()) {
            scroll = y - listHeight() / 3;
            clampScroll();
        }
    }

    // ---------------------------------------------------------------- Input

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_CLOSE:
                close();
                return;
            case ID_TYPES:
            case ID_CREATORS:
                setView(button.id == ID_CREATORS);
                return;
            case ID_THREADS:
                allThreads = !allThreads;
                break;
            case ID_SORT:
                sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
                break;
            case ID_EXPAND:
                expandBiggest(selected != null ? selected.top() : roots.isEmpty() ? null : roots.get(0));
                scrollTo(selected);
                return;
            case ID_COLLAPSE:
                expanded.clear();
                if (selected != null) {
                    select(selected.top());
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
        if (!search.isFocused() && selected != null) {
            boolean open = isOpen(selected);
            if (keyCode == Keyboard.KEY_RIGHT || keyCode == Keyboard.KEY_RETURN && !open) {
                if (!selected.children.isEmpty()) {
                    open(selected);
                    rebuild();
                }
                return;
            }
            if (keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_RETURN) {
                if (open && !selected.children.isEmpty()) {
                    expanded.remove(selected.id);
                } else if (selected.parent != null) {
                    select(selected.parent);
                    scrollTo(selected);
                }
                rebuild();
                return;
            }
        }
        if (keyCode == Keyboard.KEY_TAB) {
            setView(!byCreator);
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
        int index = rows.indexOf(selected);
        index = Math.max(0, Math.min(rows.size() - 1, index < 0 ? 0 : index + step));
        select(rows.get(index));
        int y = index * ROW;
        if (y < scroll) {
            scroll = y;
        } else if (y + ROW > scroll + listHeight()) {
            scroll = y + ROW - listHeight();
        }
        clampScroll();
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
    }

    // ---------------------------------------------------------------- Formatting

    private boolean tps() {
        return report.mode == ScanMode.TPS;
    }

    private static String bytes(double bytes) {
        return GuiMemory.bytes(bytes);
    }

    /** Seconds the allocations were seen for: from when the Flight Recorder started to the end. */
    private double seconds() {
        return Math.max(0.001, report.durationSec - profile.recordedFromSec);
    }

    /** Bytes over the scan as bytes a second. */
    private String rate(double bytes) {
        return I18n.format("lagfinder.alloc.per_second", bytes(bytes / seconds()));
    }

    /** The share of all the bytes allocated (in the thread chosen), in percent. */
    private double share(long bytes) {
        long all = profile.bytes(target());
        return all <= 0 ? 0 : bytes * 100.0 / all;
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

    /** A type or class as in Java code, or what stands for one not known. */
    private static String displayName(String name) {
        return AllocationProfile.UNKNOWN_TYPE.equals(name) ? I18n.format("lagfinder.alloc.unknown") : name;
    }

    private static String simpleName(String name) {
        return AllocationProfile.UNKNOWN_TYPE.equals(name) ? I18n.format("lagfinder.alloc.unknown")
            : Group.simpleName(name);
    }

    /** A method as written in the lines: "update()", "new" for a constructor. */
    private static String methodName(String method) {
        if ("<init>".equals(method)) {
            return I18n.format("lagfinder.alloc.constructor");
        }
        if ("<clinit>".equals(method)) {
            return I18n.format("lagfinder.alloc.static_init");
        }
        return method + "()";
    }

    /** What Java's code was called to create the objects: "ArrayList.add", "new HashMap". */
    private static String viaName(String via) {
        if (via.isEmpty()) {
            return "";
        }
        int dot = via.lastIndexOf('.');
        String className = Group.simpleName(dot < 0 ? via : via.substring(0, dot));
        String method = dot < 0 ? "" : via.substring(dot + 1);
        if ("<init>".equals(method)) {
            return "new " + className;
        }
        return className + "." + method;
    }

    private String nameOf(Line line) {
        if (line.site != null) {
            return line.site.method;
        }
        Group group = groupOf(line);
        return group == null ? "" : group.name;
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
        String title = I18n.format(
            report.bothSides ? "lagfinder.alloc.title_both"
                : tps() ? "lagfinder.alloc.title_tps" : "lagfinder.alloc.title_fps");
        long all = profile.bytes(target());
        String subtitle = I18n.format(
            "lagfinder.alloc.subtitle",
            allThreads ? I18n.format("lagfinder.alloc.threads_all") : profile.thread,
            Names.clock(report.durationSec),
            bytes(all),
            rate(all),
            GuiMemory.count(profile.samples(target())));
        if (profile.source == AllocationProfile.SOURCE_STACKS) {
            subtitle += "  ·  " + I18n.format("lagfinder.alloc.rough");
        }
        WindowHeader.draw(
            fontRendererObj,
            panelLeft,
            panelTop,
            panelRight,
            panelRight - WindowHeader.CLOSE_ROOM,
            Icons.SMALL_ALLOC,
            title,
            subtitle,
            Theme.TEXT_MUTED,
            modFilter >= 0 ? profile.modName(modFilter)
                : profile.source == AllocationProfile.SOURCE_JFR ? "JFR" : I18n.format("lagfinder.alloc.stacks"));

        drawMods(mouseX, mouseY);
        drawList(mouseX, mouseY);
        drawDetails(mouseX, mouseY);
        search.drawTextBox();
        super.drawScaled(mouseX, mouseY, partialTicks);
        // Over the buttons, its tooltip on top of all.
        drawGraph(mouseX, mouseY);

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
            showTooltip(I18n.format("lagfinder.tip.alloc_sort"), mouseX, mouseY);
        } else if (threadsButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(
                I18n.format("lagfinder.alloc.threads") + "\n"
                    + I18n.format(
                        threadsButton.enabled ? "lagfinder.tip.alloc_threads" : "lagfinder.tip.alloc_threads_off",
                        profile.thread),
                mouseX,
                mouseY);
        } else if (expandButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(expandButton.tooltip, mouseX, mouseY);
        } else if (collapseButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(collapseButton.tooltip, mouseX, mouseY);
        } else if (typesButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(
                I18n.format("lagfinder.alloc.by_type") + "\n"
                    + I18n
                        .format(profile.typesKnown() ? "lagfinder.tip.alloc_by_type" : "lagfinder.tip.alloc_no_types"),
                mouseX,
                mouseY);
        } else if (creatorsButton.isMouseOver(mouseX, mouseY)) {
            showTooltip(
                I18n.format("lagfinder.alloc.by_creator") + "\n" + I18n.format("lagfinder.tip.alloc_by_creator"),
                mouseX,
                mouseY);
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
            I18n.format("lagfinder.alloc.mods")
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
            most = Math.max(most, modBytes[mod]);
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
        // How the allocations were seen.
        Theme.fill(sideLeft + 1, bottom, sideRight - 1, bottom + 1, Theme.BORDER);
        boolean jfr = profile.source == AllocationProfile.SOURCE_JFR;
        String hint = I18n.format(jfr ? "lagfinder.alloc.how_jfr" : "lagfinder.alloc.how_stacks");
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, hint, SIDE_WIDTH - 12),
            sideLeft + 6,
            bottom + 5,
            jfr ? Theme.TEXT_DISABLED : WARN);
        String tip = hint + "\n" + I18n.format(jfr ? "lagfinder.tip.alloc_jfr" : "lagfinder.tip.alloc_stacks");
        if (profile.note != null && !jfr) {
            tip += "\n" + I18n.format("lagfinder.alloc.why", profile.note);
        }
        hit(sideLeft, bottom, sideRight, bodyBottom, null, tip);
    }

    /** The mods' shares of what was created as one bar under their title. */
    private void drawModsBar(int x0, int y0, int x1, int y1) {
        List<Integer> biggest = new ArrayList<>();
        double all = 0;
        for (int i = 0; i < modBytes.length; i++) {
            if (modBytes[i] > 0) {
                biggest.add(i);
                all += modBytes[i];
            }
        }
        biggest.sort((a, b) -> Long.compare(modBytes[b], modBytes[a]));
        int shown = Math.min(8, biggest.size());
        double[] values = new double[shown + 1];
        int[] colors = new int[shown + 1];
        double rest = all;
        for (int i = 0; i < shown; i++) {
            int mod = biggest.get(i);
            values[i] = modBytes[mod];
            colors[i] = Draw.modColor(profile.modName(mod));
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
                profile.modName(mod) + "\n"
                    + I18n.format(
                        "lagfinder.alloc.line",
                        bytes(values[i]),
                        percent(share((long) values[i])),
                        rate(values[i]))
                    + "\n"
                    + I18n.format(modFilter == mod ? "lagfinder.tip.unpick_mod" : "lagfinder.tip.alloc_pick_mod"));
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
        String name = all ? I18n.format("lagfinder.methods.all_mods") : profile.modName(mod);
        long bytes = all ? profile.bytes(target()) : modBytes[mod];
        int color = all ? Theme.ACCENT : Draw.modColor(name);
        int x = sideLeft + 7;
        int textY = y + 4;
        if (all) {
            Icons.draw(Icons.SMALL_GRID, x - 1, textY, picked ? Theme.ACCENT : Theme.TEXT_MUTED);
        } else {
            Theme.fill(x, textY + 1, x + 6, textY + 7, color);
        }
        String value = all ? rate(bytes) : percent(share(bytes));
        int valueWidth = fontRendererObj.getStringWidth(value);
        int right = sideRight - 8;
        fontRendererObj.drawString(value, right - valueWidth, textY, all ? Theme.TEXT_MUTED : shareColor(share(bytes)));
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, name, right - valueWidth - 6 - (x + 10)),
            x + 10,
            textY,
            picked || all ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, Math.max(lit, 0.6)));
        if (!all) {
            // Its bytes against the mod creating the most, as a thin bar along the bottom.
            int barWidth = (int) Math.round((sideRight - sideLeft - 14) * Math.min(1, bytes / (double) most));
            Theme.fill(
                sideLeft + 7,
                y + MOD_ROW - 3,
                sideLeft + 7 + Math.max(1, barWidth),
                y + MOD_ROW - 2,
                Theme.blend(color, 0x00000000, 0.35));
        }
        String tip = name + "\n"
            + I18n.format("lagfinder.alloc.line", bytes(bytes), percent(share(bytes)), rate(bytes))
            + "\n"
            + I18n.format(
                all ? "lagfinder.tip.alloc_all_mods"
                    : picked ? "lagfinder.tip.unpick_mod" : "lagfinder.tip.alloc_pick_mod");
        hit(sideLeft + 1, y, sideRight - 1, y + MOD_ROW, () -> setFilter(mod), tip);
    }

    // ---------------------------------------------------------------- The graph

    /** The type or class whose timeline is drawn over all: the picked line's, or the one it is under. */
    private Group pickedGroup() {
        if (selected == null) {
            return null;
        }
        Group group = groupOf(selected.top());
        return group != null && group.timeline(target()) != null ? group : null;
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

    private void drawGraph(int mouseX, int mouseY) {
        int x0 = listLeft, y0 = graphTop, x1 = listRight, y1 = graphBottom;
        Theme.card(x0, y0, x1, y1);
        float[] all = profile.timeline(target());
        int n = all.length;
        float bucketSec = report.bucketSec > 0 ? report.bucketSec : report.durationSec / Math.max(1, n);
        Group picked = pickedGroup();
        int pickedColor = picked == null ? WARN : Draw.modColor(profile.modName(picked.mod));

        // Legend: a sample of each thing drawn, as it looks on the graph.
        String allName = I18n
            .format(allThreads ? "lagfinder.alloc.legend.all" : "lagfinder.alloc.legend.thread", profile.thread);
        Chart.Legend legend = new Chart.Legend(fontRendererObj, x0 + 8, y0 + 6, x1 - 8);
        legend.bar(allName, RATE_COLOR, true);
        if (picked != null) {
            legend.line(simpleName(picked.name), pickedColor, false);
        }

        if (x1 - x0 - 56 < 20 || y1 - y0 - 34 < 16 || n == 0) {
            return;
        }
        Chart chart = new Chart(fontRendererObj, x0 + 46, y0 + 20, x1 - 10, y1 - 14, n);
        chart.grid(2);
        float max = 0;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, all[i] / bucketSeconds(i, bucketSec, n));
        }
        max = (float) Draw.niceCeil(Math.max(max, 0.1f) * 1.08);
        String[] labels = new String[3];
        for (int i = 0; i <= 2; i++) {
            labels[i] = i == 0 ? "0"
                : GuiMemory.megabytes(max * i / 2f)
                    .replace(" ", "") + "/s";
        }
        chart.leftAxis(labels, RATE_COLOR);
        for (int i = 0; i < n; i++) {
            float value = all[i] / bucketSeconds(i, bucketSec, n);
            if (value > 0) {
                chart.bar(
                    i,
                    0,
                    value,
                    max,
                    (RATE_COLOR & 0x00FFFFFF) | 0x70000000,
                    (RATE_COLOR & 0x00FFFFFF) | 0x18000000);
            }
        }
        float[] line = picked == null ? null : picked.timeline(target());
        float[] rates = null;
        if (line != null && line.length > 0) {
            rates = new float[Math.min(n, line.length)];
            for (int i = 0; i < rates.length; i++) {
                rates[i] = line[i] / bucketSeconds(i, bucketSec, n);
            }
            chart.line(rates, max, Chart.solid(pickedColor, rates.length), 1.5f, 0x38);
        }
        chart.timeAxis(report.durationSec);

        // What was at the moment under the mouse: marked on the line with its value beside the axis.
        if (Theme.inside(mouseX, mouseY, chart.x0, chart.y0 - 6, chart.x1, chart.y1)) {
            int i = chart.bucketAt(mouseX);
            chart.hover(i);
            float seconds = bucketSeconds(i, bucketSec, n);
            if (rates != null && i < rates.length) {
                double pickedY = chart.y(rates[i], max);
                Chart.dot(chart.center(i), pickedY, pickedColor);
                String value = GuiMemory.megabytes(rates[i])
                    .replace(" ", "") + "/s";
                chart.leftValue(pickedY, value, pickedColor);
            } else {
                double allY = chart.y(all[i] / seconds, max);
                String value = GuiMemory.megabytes(all[i] / seconds)
                    .replace(" ", "") + "/s";
                chart.leftValue(allY, value, RATE_COLOR);
            }

            List<String> lines = new ArrayList<>();
            List<Integer> lineColors = new ArrayList<>();
            float from = i * bucketSec, to = Math.min(report.durationSec, (i + 1) * bucketSec);
            lines.add(Names.clock(from) + " – " + Names.clock(to));
            lineColors.add(Theme.TEXT_MUTED);
            lines
                .add(allName + ": " + I18n.format("lagfinder.alloc.per_second", GuiMemory.megabytes(all[i] / seconds)));
            lineColors.add(RATE_COLOR);
            if (line != null && i < line.length) {
                lines.add(
                    simpleName(picked.name) + ": "
                        + I18n.format("lagfinder.alloc.per_second", GuiMemory.megabytes(line[i] / seconds))
                        + (all[i] > 0 ? "  (" + percent(line[i] * 100.0 / all[i]) + ")" : ""));
                lineColors.add(pickedColor);
            }
            Draw.tooltip(fontRendererObj, lines, lineColors, mouseX, mouseY, width, height);
        }
    }

    // ---------------------------------------------------------------- The lines

    /** Where a column of values ends, from the left: share, bytes, bytes a second, the trend. */
    private int columnRight(int column) {
        int right = listRight - 8;
        switch (column) {
            case 3:
                return right;
            case 2:
                return right - TREND_COLUMN;
            case 1:
                return right - TREND_COLUMN - RATE_COLUMN;
            default:
                return right - TREND_COLUMN - RATE_COLUMN - BYTES_COLUMN;
        }
    }

    private void drawList(int mouseX, int mouseY) {
        int headY = graphBottom + 6;
        String nameTitle = I18n.format(byCreator ? "lagfinder.alloc.column.creator" : "lagfinder.alloc.column.type");
        fontRendererObj.drawString(nameTitle, listLeft + 4, headY, Theme.TEXT_DISABLED);
        String[] titles = { I18n.format("lagfinder.methods.column.share"), I18n.format("lagfinder.alloc.column.bytes"),
            I18n.format("lagfinder.alloc.column.rate"), I18n.format("lagfinder.alloc.column.trend") };
        String[] tips = { "lagfinder.tip.alloc_share", "lagfinder.tip.alloc_bytes", "lagfinder.tip.alloc_rate",
            "lagfinder.tip.alloc_trend" };
        int[] widths = { SHARE_COLUMN, BYTES_COLUMN, RATE_COLUMN, TREND_COLUMN };
        for (int i = 0; i < 4; i++) {
            int right = columnRight(i);
            boolean sorted = i == 1 && sort == Sort.SIZE;
            fontRendererObj.drawString(
                titles[i],
                right - fontRendererObj.getStringWidth(titles[i]),
                headY,
                sorted ? Theme.ACCENT : Theme.TEXT_DISABLED);
            hit(right - widths[i] + 4, headY - 2, right + 2, headY + 10, null, titles[i] + "\n" + I18n.format(tips[i]));
        }

        Theme.card(listLeft, listTop - 1, listRight, listBottom);
        if (rows.isEmpty()) {
            String empty;
            if (profile.samples == 0) {
                empty = I18n.format("lagfinder.alloc.no_samples");
            } else if (!lastSearch.isEmpty()) {
                empty = I18n.format("lagfinder.gui.nothing_found");
            } else {
                empty = I18n.format("lagfinder.gui.nothing");
            }
            Theme.centered(fontRendererObj, empty, (listLeft + listRight) / 2, listTop + 20, Theme.TEXT_DISABLED);
            if (profile.samples == 0 && profile.note != null) {
                Theme.centered(
                    fontRendererObj,
                    Theme.ellipsize(
                        fontRendererObj,
                        I18n.format("lagfinder.alloc.why", profile.note),
                        listRight - listLeft - 20),
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
        for (int i = 0; i < rows.size(); i++) {
            int y = top + i * ROW - offset;
            if (y + ROW < top || y > bottom) {
                continue;
            }
            drawRow(rows.get(i), i, y, mouseX, mouseY);
        }
        Theme.unclip();
        unclipHits();
        if (contentHeight() > listHeight()) {
            Theme.scrollbar(
                listRight - 4,
                top + 2,
                bottom - 2,
                listHeight(),
                contentHeight(),
                offset / (double) Math.max(1, contentHeight() - listHeight() + 2),
                Theme.inside(mouseX, mouseY, listLeft, top, listRight, bottom));
        }
    }

    private void drawRow(Line line, int index, int y, int mouseX, int mouseY) {
        boolean hovered = Theme.inside(mouseX, mouseY, listLeft + 1, y, listRight - 1, y + ROW)
            && Theme.inside(mouseX, mouseY, listLeft, listTop, listRight, listBottom);
        boolean isSelected = line == selected;
        double lit = light(line.id, hovered);
        int mod = modOf(line);
        int modColor = Draw.modColor(profile.modName(mod));
        double share = share(line.bytes);

        // The groups of a type or class stand out a little from the places under them.
        if (line.level == 0 && index > 0) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + 1, 0xFF1C2129);
        }
        // Its share as a soft bar behind the line.
        int barRight = listLeft + 1 + (int) Math.round((listRight - listLeft - 2) * Math.min(1, share / 100));
        Theme.fill(listLeft + 1, y, Math.max(listLeft + 1, barRight), y + ROW, (modColor & 0x00FFFFFF) | 0x14000000);
        if (isSelected) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + ROW, SELECTED);
            Theme.fill(listLeft + 1, y, listLeft + 3, y + ROW, Theme.ACCENT);
        } else if (lit > 0.01) {
            Theme.fill(listLeft + 1, y, listRight - 1, y + ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }

        int textY = y + (ROW - 8) / 2;
        // The values on the right.
        String shareText = percent(share);
        String bytesText = bytes(line.bytes);
        String rateText = rate(line.bytes);
        fontRendererObj.drawString(
            shareText,
            columnRight(0) - fontRendererObj.getStringWidth(shareText),
            textY,
            shareColor(share));
        fontRendererObj.drawString(
            bytesText,
            columnRight(1) - fontRendererObj.getStringWidth(bytesText),
            textY,
            line.level == 0 ? Theme.TEXT : Theme.TEXT_MUTED);
        fontRendererObj
            .drawString(rateText, columnRight(2) - fontRendererObj.getStringWidth(rateText), textY, Theme.TEXT_MUTED);
        if (line.level == 0) {
            drawTrend(groupOf(line), columnRight(3) - TREND_COLUMN + 8, y + 2, columnRight(3), y + ROW - 2, modColor);
        }
        int textRight = columnRight(0) - SHARE_COLUMN + 2;

        int x = listLeft + 6 + line.level * INDENT;
        if (line.level > 0) {
            // A guide down from the line above, to tell the levels apart.
            int guide = listLeft + 6 + (line.level - 1) * INDENT + 2;
            Theme.fill(guide, y, guide + 1, y + ROW, 0xFF2A313B);
            Theme.fill(guide, y + ROW / 2, x - 1, y + ROW / 2 + 1, 0xFF2A313B);
        }
        boolean hasChildren = !line.children.isEmpty();
        hit(listLeft + 1, y, x + 8, y + ROW, hasChildren ? () -> {
            select(line);
            toggle(line);
        } : () -> select(line), null);
        hit(x + 8, y, listRight - 1, y + ROW, () -> {
            boolean wasSelected = selected == line;
            select(line);
            if (hasChildren && (wasSelected || !isOpen(line))) {
                toggle(line);
            }
        }, null);
        if (hasChildren) {
            String[] arrow = isOpen(line) ? Icons.SECTION_OPEN : Icons.SECTION_CLOSED;
            Icons.draw(
                arrow,
                x + (5 - Icons.width(arrow)) / 2,
                y + (ROW - arrow.length) / 2,
                hovered ? Theme.TEXT : Theme.TEXT_MUTED);
        }
        x += 9;

        boolean matched = lineMatches(line);
        if (line.site != null) {
            drawPlace(line, x, textY, textRight, matched);
        } else {
            drawGroup(line, x, y, textY, textRight, modColor, matched);
        }
        if (hovered) {
            hit(x, y, textRight, y + ROW, null, tooltipOf(line));
        }
    }

    /** A type or a class: its icon, its short name and its package, and its mod on the right. */
    private void drawGroup(Line line, int x, int y, int textY, int textRight, int modColor, boolean matched) {
        Group group = groupOf(line);
        String name = group == null ? "" : group.name;
        if (line.isType) {
            Icons.draw(Icons.SMALL_CUBE, x, y + 3, Theme.blend(modColor, Theme.TEXT_MUTED, 0.3));
        } else {
            Theme.fill(x + 1, y + ROW / 2 - 2, x + 5, y + ROW / 2 + 2, modColor);
        }
        x += 10;
        // The mod is named on the line of a class, and of a type when grouped by mod.
        String mod = !line.isType || sort == Sort.MOD ? "  " + profile.modName(group == null ? -1 : group.mod) : "";
        int modWidth = mod.isEmpty() ? 0 : Math.min(fontRendererObj.getStringWidth(mod), 110);
        int room = textRight - x - modWidth;
        String simple = simpleName(name);
        int color = matched ? WARN : line.level == 0 ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.6);
        String shown = Theme.ellipsize(fontRendererObj, simple, room);
        if (line.level == 0) {
            fontRendererObj.drawStringWithShadow(shown, x, textY, color);
        } else {
            fontRendererObj.drawString(shown, x, textY, color);
        }
        int after = x + fontRendererObj.getStringWidth(shown);
        String pkg = AllocationProfile.UNKNOWN_TYPE.equals(name) ? "" : Group.packageName(name);
        if (!pkg.isEmpty() && after + 20 < x + room) {
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, "  " + pkg, x + room - after),
                after,
                textY,
                Theme.TEXT_DISABLED);
        }
        if (!mod.isEmpty()) {
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, mod, modWidth),
                textRight - modWidth,
                textY,
                Theme.blend(modColor, Theme.TEXT_DISABLED, 0.35));
        }
    }

    /**
     * A place in a method of the class above it: "update():120", and what of Java it called to create the objects.
     */
    private void drawPlace(Line line, int x, int textY, int textRight, boolean matched) {
        Site site = line.site;
        String where = site.line >= 0 ? ":" + site.line : "";
        String via = viaName(site.via);
        Icons.draw(Icons.SMALL_METHOD, x, textY, Theme.TEXT_DISABLED);
        x += 9;
        String text = Theme.ellipsize(fontRendererObj, methodName(site.method) + where, textRight - x);
        fontRendererObj.drawString(text, x, textY, matched ? WARN : Theme.TEXT);
        int after = x + fontRendererObj.getStringWidth(text);
        if (!via.isEmpty() && after + 30 < textRight) {
            String viaText = "  " + I18n.format("lagfinder.alloc.via", via);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, viaText, textRight - after),
                after,
                textY,
                matched && matchesText(site.via) ? WARN : Theme.TEXT_DISABLED);
        }
    }

    /** The type's or class's bytes over the scan, as small bars. */
    private void drawTrend(Group group, int x0, int y0, int x1, int y1, int color) {
        float[] line = group == null ? null : group.timeline(target());
        if (line == null || line.length == 0 || x1 - x0 < 8) {
            return;
        }
        int bars = Math.min(line.length, x1 - x0);
        float[] sums = new float[bars];
        float max = 0;
        for (int i = 0; i < line.length; i++) {
            int bar = (int) ((long) i * bars / line.length);
            sums[bar] += line[i];
        }
        for (float value : sums) {
            max = Math.max(max, value);
        }
        if (max <= 0) {
            return;
        }
        double width = (x1 - x0) / (double) bars;
        for (int i = 0; i < bars; i++) {
            if (sums[i] <= 0) {
                continue;
            }
            double top = y1 - (y1 - y0) * sums[i] / max;
            double bx = x0 + i * width;
            Draw.gradientRect(
                bx,
                top,
                bx + Math.max(1, width - (width > 2 ? 0.5 : 0)),
                y1,
                (color & 0x00FFFFFF) | 0xC0000000,
                (color & 0x00FFFFFF) | 0x40000000);
        }
    }

    private String tooltipOf(Line line) {
        StringBuilder text = new StringBuilder();
        if (line.site != null) {
            Site site = line.site;
            text.append(creator(site.creator).name)
                .append('.')
                .append(methodName(site.method));
            if (site.line >= 0) {
                text.append(':')
                    .append(site.line);
            }
            if (profile.typesKnown()) {
                text.append("\n")
                    .append(I18n.format("lagfinder.alloc.creates", displayName(type(site.type).name)));
            }
            if (!site.via.isEmpty()) {
                text.append("\n")
                    .append(I18n.format("lagfinder.alloc.via_full", site.via));
            }
        } else {
            Group group = groupOf(line);
            text.append(group == null ? "" : displayName(group.name));
            text.append("\n")
                .append(I18n.format(line.isType ? "lagfinder.alloc.is_type" : "lagfinder.alloc.is_creator"));
        }
        text.append("\n")
            .append(I18n.format("lagfinder.methods.mod_of", profile.modName(modOf(line))));
        text.append("\n")
            .append(
                I18n.format("lagfinder.alloc.line", bytes(line.bytes), percent(share(line.bytes)), rate(line.bytes)));
        return text.toString();
    }

    // ---------------------------------------------------------------- The pick

    private void drawDetails(int mouseX, int mouseY) {
        int x0 = listLeft, y0 = listBottom + 6, x1 = listRight, y1 = bodyBottom;
        Theme.card(x0, y0, x1, y1);
        Line line = selected;
        if (line == null) {
            Icons.draw(Icons.SMALL_SEARCH, x0 + 8, y0 + 8, Theme.TEXT_MUTED);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.alloc.hint"), x1 - x0 - 30),
                x0 + 19,
                y0 + 8,
                Theme.TEXT_MUTED);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.format("lagfinder.alloc.hint2"), x1 - x0 - 30),
                x0 + 19,
                y0 + 22,
                Theme.TEXT_DISABLED);
            return;
        }
        int mod = modOf(line);
        String modName = profile.modName(mod);
        int modColor = Draw.modColor(modName);

        // A button on the right: the same type or class in the other view.
        int textRight = x1 - 8;
        Runnable jump = jumpOf(line);
        if (jump != null) {
            String action = I18n.format(jumpKey(line));
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
            Icons.draw(byCreator ? Icons.SMALL_CUBE : Icons.SMALL_ALLOC, bx + 7, by + 6, Theme.TEXT);
            fontRendererObj.drawString(action, bx + 18, by + 5, Theme.TEXT);
            hit(bx, by, bx + buttonWidth, by + 18, jump, null);
            textRight = bx - 10;
        }

        int x = x0 + 8, y = y0 + 7;
        Theme.fill(x, y + 1, x + 6, y + 7, modColor);
        int pillWidth = fontRendererObj.getStringWidth(modName) + 10;
        int pillX = textRight - pillWidth;
        Theme.pill(fontRendererObj, modName, pillX, y - 2, modColor);
        String name;
        if (line.site != null) {
            name = creator(line.site.creator).name + "."
                + methodName(line.site.method)
                + (line.site.line >= 0 ? ":" + line.site.line : "");
        } else {
            Group group = groupOf(line);
            name = group == null ? "" : displayName(group.name);
        }
        fontRendererObj
            .drawStringWithShadow(Theme.ellipsize(fontRendererObj, name, pillX - 6 - (x + 10)), x + 10, y, Theme.TEXT);
        y += 14;
        StringBuilder text = new StringBuilder(
            I18n.format("lagfinder.alloc.line", bytes(line.bytes), percent(share(line.bytes)), rate(line.bytes)));
        text.append("  ·  ")
            .append(I18n.format("lagfinder.methods.samples", line.samples));
        if (line.site != null && profile.typesKnown()) {
            text.append("  ·  ")
                .append(I18n.format("lagfinder.alloc.creates", simpleName(type(line.site.type).name)));
        }
        if (line.site != null && !line.site.via.isEmpty()) {
            text.append("  ·  ")
                .append(I18n.format("lagfinder.alloc.via", viaName(line.site.via)));
        }
        if (line.site == null && line.level == 0 && !line.children.isEmpty()) {
            text.append("  ·  ")
                .append(
                    I18n.format(
                        line.isType ? "lagfinder.alloc.creators_count" : "lagfinder.alloc.types_count",
                        line.children.size()));
        }
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, text.toString(), textRight - x), x, y, Theme.TEXT_MUTED);
    }

    /** What the button of the pick does: shows its type or class in the other view; null when there is none. */
    private Runnable jumpOf(Line line) {
        if (!profile.typesKnown()) {
            return null;
        }
        if (line.site != null) {
            Site site = line.site;
            return byCreator ? () -> reveal(true, site.type) : () -> reveal(false, site.creator);
        }
        if (line.level == 0) {
            return null;
        }
        // A class under a type, or a type under a class: shown in its own view.
        int group = line.group;
        boolean isType = line.isType;
        return () -> reveal(isType, group);
    }

    private String jumpKey(Line line) {
        boolean toType = line.site != null ? byCreator : line.isType;
        return toType ? "lagfinder.alloc.show_type" : "lagfinder.alloc.show_creator";
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
