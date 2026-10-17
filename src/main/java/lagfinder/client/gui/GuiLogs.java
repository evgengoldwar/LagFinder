package lagfinder.client.gui;

import java.awt.Desktop;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

import org.lwjgl.Sys;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import lagfinder.LagFinder;
import lagfinder.client.ClientReports;
import lagfinder.client.ClientScanner;
import lagfinder.client.ReportStore;
import lagfinder.client.gui.ui.FlatButton;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.ScrollbarDrag;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.core.Report;
import lagfinder.core.ReportFiles;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;

/**
 * The saved reports, newest first, to open again or delete when no longer needed: all of them or those of one kind of
 * scan. A delete asks once more before it is done.
 */
public class GuiLogs extends GuiWindow {

    private static final int ROW = 22, OPEN_WIDTH = 64, DELETE_WIDTH = 22;
    private static final int ID_FOLDER = 1, ID_DELETE_ALL = 2, ID_SOURCE = 3, ID_FILTER = 10;
    /** How long a delete waits for its second click (ms). */
    private static final long CONFIRM_MS = 4000;

    /** The kinds of scans the list can be cut to; null shows all. */
    private static final ScanFocus[] FILTERS = { null, ScanFocus.LAG, ScanFocus.METHODS, ScanFocus.OBJECTS,
        ScanFocus.ALLOCATIONS, ScanFocus.STEADY };
    private static final String[] FILTER_KEYS = { "lagfinder.logs.all", "lagfinder.logs.lag", "lagfinder.logs.methods",
        "lagfinder.logs.objects", "lagfinder.logs.alloc", "lagfinder.logs.steady" };
    private static int filter;

    private final List<ReportFiles.Saved> all = new ArrayList<>();
    private final List<ReportFiles.Saved> shown = new ArrayList<>();
    private final Map<ReportFiles.Saved, Smooth> lights = new HashMap<>();
    private final List<FlatButton> filterButtons = new ArrayList<>();
    private FlatButton deleteAllButton, sourceButton, folderButton;
    /** The list shows the server's shared archive instead of this computer's reports. */
    private boolean serverSource;
    private int serverRevision = -1;
    /** The report waiting for a second click to be deleted, and since when. */
    private ReportFiles.Saved confirming;
    private long confirmingAt;
    /** Deleting all that is shown waits for a second click since then; 0 when it doesn't. */
    private long confirmingAllAt;
    /** Something to tell (a translation key, already formatted), and when. */
    private String message;
    private long messageAt;

    private int listTop, listHeight;
    private int scroll;
    private final Smooth shownScroll = new Smooth(0);
    private final ScrollbarDrag listBar = new ScrollbarDrag();

    @Override
    protected Tab tab() {
        return Tab.LOGS;
    }

    @Override
    protected void layOut() {
        reload();
        int sourceY = contentTop;
        int filtersY = sourceY + 24;
        listTop = filtersY + 16 + 8;
        int buttonsY = contentBottom - 20;
        listHeight = buttonsY - 8 - listTop;

        filterButtons.clear();
        // This computer's reports, or the server's shared archive (operators and allowed ranks).
        sourceButton = new FlatButton(ID_SOURCE, contentLeft, sourceY, contentRight - contentLeft, 20, "");
        buttonList.add(sourceButton);
        int x = contentLeft;
        for (int i = 0; i < FILTERS.length; i++) {
            String text = I18n.format(FILTER_KEYS[i]);
            FlatButton button = new FlatButton(
                ID_FILTER + i,
                x,
                filtersY,
                fontRendererObj.getStringWidth(text) + 16,
                16,
                text);
            x += button.getWidth() + 4;
            filterButtons.add(button);
            buttonList.add(button);
        }
        String folder = I18n.format("lagfinder.logs.folder");
        folderButton = new FlatButton(
            ID_FOLDER,
            contentLeft,
            buttonsY,
            fontRendererObj.getStringWidth(folder) + 30,
            20,
            folder);
        folderButton.icon = Icons.SMALL_FOLDER;
        buttonList.add(folderButton);
        deleteAllButton = new FlatButton(ID_DELETE_ALL, contentRight - 170, buttonsY, 170, 20, "");
        deleteAllButton.icon = Icons.SMALL_TRASH;
        deleteAllButton.danger = true;
        buttonList.add(deleteAllButton);
        refresh();
    }

    private void reload() {
        all.clear();
        lights.clear();
        if (serverSource) {
            all.addAll(ClientReports.INSTANCE.list());
        } else {
            all.addAll(ReportStore.list());
            // The menu counts this computer's reports.
            savedCount = all.size();
        }
        serverRevision = ClientReports.INSTANCE.revision();
        applyFilter();
    }

    private void applyFilter() {
        shown.clear();
        for (ReportFiles.Saved saved : all) {
            if (FILTERS[filter] == null || saved.focus == FILTERS[filter]) {
                shown.add(saved);
            }
        }
        clampScroll();
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (confirming != null && now - confirmingAt > CONFIRM_MS) {
            confirming = null;
        }
        if (confirmingAllAt != 0 && now - confirmingAllAt > CONFIRM_MS) {
            confirmingAllAt = 0;
        }
        for (int i = 0; i < filterButtons.size(); i++) {
            filterButtons.get(i).active = i == filter;
        }
        sourceButton.enabled = ClientScanner.INSTANCE.serverArchiveAvailable() || serverSource;
        sourceButton.displayString = I18n.format(serverSource ? "lagfinder.logs.server" : "lagfinder.logs.local");
        folderButton.displayString = I18n.format(serverSource ? "lagfinder.logs.refresh" : "lagfinder.logs.folder");
        folderButton.enabled = !serverSource || !ClientReports.INSTANCE.loading();
        // The server's reports are only read from here, never deleted.
        deleteAllButton.visible = !serverSource;
        deleteAllButton.enabled = !serverSource && !shown.isEmpty();
        deleteAllButton.active = confirmingAllAt != 0;
        deleteAllButton.displayString = confirmingAllAt != 0 ? I18n.format("lagfinder.logs.delete_all_sure")
            : I18n.format(filter == 0 ? "lagfinder.logs.delete_all" : "lagfinder.logs.delete_shown") + " ("
                + shown.size()
                + ")";
    }

    private void tell(String text) {
        message = text;
        messageAt = System.currentTimeMillis();
    }

    private void clampScroll() {
        int content = shown.size() * ROW;
        scroll = Math.max(0, Math.min(scroll, content - listHeight));
    }

    // ---------------------------------------------------------------- Input

    @Override
    protected void pressed(GuiButton button) {
        if (button.id == ID_SOURCE) {
            serverSource = !serverSource;
            confirming = null;
            confirmingAllAt = 0;
            scroll = 0;
            shownScroll.set(0);
            if (serverSource) {
                ClientReports.INSTANCE.listReports();
            } else {
                ClientReports.INSTANCE.reset();
            }
            reload();
        } else if (button.id == ID_FOLDER) {
            if (serverSource) {
                ClientReports.INSTANCE.listReports();
            } else {
                openFolder();
            }
        } else if (button.id == ID_DELETE_ALL && !serverSource) {
            if (confirmingAllAt == 0) {
                confirmingAllAt = System.currentTimeMillis();
                confirming = null;
            } else {
                int deleted = 0;
                for (ReportFiles.Saved saved : new ArrayList<>(shown)) {
                    if (ReportStore.delete(saved)) {
                        deleted++;
                    }
                }
                confirmingAllAt = 0;
                reload();
                tell(I18n.format("lagfinder.logs.deleted_many", deleted));
            }
        } else if (button.id >= ID_FILTER && button.id < ID_FILTER + FILTERS.length) {
            filter = button.id - ID_FILTER;
            confirming = null;
            confirmingAllAt = 0;
            scroll = 0;
            shownScroll.set(0);
            applyFilter();
        }
        refresh();
    }

    private void openFolder() {
        File folder = ReportStore.folder();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            return;
        }
        // The desktop can take a moment to answer: not on the game's thread.
        Thread opener = new Thread(() -> {
            try {
                Desktop.getDesktop()
                    .open(folder);
            } catch (Throwable t) {
                try {
                    Sys.openURL("file://" + folder.getAbsolutePath());
                } catch (Throwable u) {
                    LagFinder.LOG.warn("Could not open the reports' folder {}", folder, u);
                }
            }
        }, "LagFinder folder opener");
        opener.setDaemon(true);
        opener.start();
    }

    /** Where the buttons of a row start: "open", then the bin (or, while it asks, one button over both). */
    private int openX() {
        return contentRight - 6 - DELETE_WIDTH - 4 - OPEN_WIDTH;
    }

    private int deleteX() {
        return contentRight - 6 - DELETE_WIDTH;
    }

    private int rowAt(int mouseX, int mouseY) {
        if (!Theme.inside(mouseX, mouseY, contentLeft, listTop, contentRight, listTop + listHeight)) {
            return -1;
        }
        int index = (mouseY - listTop + scroll) / ROW;
        return index >= 0 && index < shown.size() ? index : -1;
    }

    @Override
    protected void clicked(int mouseX, int mouseY, int button) {
        if (button == 0) {
            // The list's scrollbar can be dragged.
            int total = shown.size() * ROW;
            int next = listBar.press(
                mouseX,
                mouseY,
                contentRight - 4,
                listTop + 2,
                listTop + listHeight - 2,
                listHeight,
                total,
                (int) Math.round(shownScroll.get()),
                Math.max(0, total - listHeight));
            if (next >= 0) {
                scroll = next;
                shownScroll.set(scroll);
                return;
            }
        }
        clickButtons(mouseX, mouseY, button);
        if (button != 0) {
            return;
        }
        int index = rowAt(mouseX, mouseY);
        if (index < 0) {
            if (!Theme.inside(mouseX, mouseY, left, top, left + WIDTH, top + HEIGHT)) {
                confirming = null;
            }
            return;
        }
        ReportFiles.Saved saved = shown.get(index);
        int rowY = listTop + index * ROW - scroll;
        boolean onButtons = mouseY >= rowY + 3 && mouseY < rowY + ROW - 3;
        if (!serverSource && confirming == saved
            && onButtons
            && mouseX >= openX()
            && mouseX < deleteX() + DELETE_WIDTH) {
            confirming = null;
            if (ReportStore.delete(saved)) {
                reload();
                tell(I18n.format("lagfinder.logs.deleted"));
            } else {
                tell(I18n.format("lagfinder.logs.not_deleted"));
            }
            refresh();
            return;
        }
        if (!serverSource && onButtons && mouseX >= deleteX() && mouseX < deleteX() + DELETE_WIDTH) {
            confirming = saved;
            confirmingAt = System.currentTimeMillis();
            confirmingAllAt = 0;
            return;
        }
        confirming = null;
        open(saved);
    }

    private void open(ReportFiles.Saved saved) {
        if (serverSource) {
            // Asked of the server; it opens when it arrives (see updateScreen).
            ClientReports.INSTANCE.open(saved);
            return;
        }
        Report report = ReportStore.load(saved.name);
        if (report == null) {
            tell(I18n.format("lagfinder.logs.unreadable"));
            return;
        }
        ClientScanner.INSTANCE.setReport(report);
        GuiLagFinder.open(mc, report);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        int next = button == 0 ? listBar.drag(mouseY) : -1;
        if (next >= 0) {
            scroll = next;
            shownScroll.set(scroll);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        if (button == 0) {
            listBar.release();
        }
        super.mouseMovedOrUp(mouseX, mouseY, button);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (serverSource) {
            if (serverRevision != ClientReports.INSTANCE.revision()) {
                reload();
            }
            Report report = ClientReports.INSTANCE.takeReport();
            if (report != null) {
                ClientScanner.INSTANCE.setReport(report);
                GuiLagFinder.open(mc, report);
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll -= Integer.signum(wheel) * 2 * ROW;
            clampScroll();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (confirming != null || confirmingAllAt != 0) {
                confirming = null;
                confirmingAllAt = 0;
            } else {
                GuiLagFinder.closeToGame(mc, this);
            }
        }
    }

    // ---------------------------------------------------------------- Drawing

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        refresh();
        long bytes = 0;
        for (ReportFiles.Saved saved : all) {
            bytes += saved.size;
        }
        // How many there are and how much room they take, right of the filters.
        String count = I18n.format("lagfinder.logs.count", all.size(), size(bytes));
        FlatButton lastFilter = filterButtons.get(filterButtons.size() - 1);
        int countLeft = lastFilter.xPosition + lastFilter.getWidth() + 8;
        count = Theme.ellipsize(fontRendererObj, count, contentRight - countLeft);
        fontRendererObj
            .drawString(count, contentRight - fontRendererObj.getStringWidth(count), contentTop + 4, Theme.TEXT_MUTED);

        int x0 = contentLeft, x1 = contentRight, y0 = listTop, y1 = listTop + listHeight;
        Theme.card(x0, y0, x1, y1);
        boolean loading = serverSource && ClientReports.INSTANCE.loading();
        String error = serverSource ? ClientReports.INSTANCE.error() : "";
        if (shown.isEmpty()) {
            String empty = I18n.format(
                loading ? "lagfinder.logs.loading"
                    : !error.isEmpty() ? error
                        : all.isEmpty() ? "lagfinder.logs.empty" : "lagfinder.logs.empty_filter");
            Theme.centered(fontRendererObj, empty, (x0 + x1) / 2, (y0 + y1) / 2 - 4, Theme.TEXT_DISABLED);
        } else {
            drawRows(mouseX, mouseY, x0, y0, x1, y1);
        }

        String note = loading ? I18n.format("lagfinder.logs.loading")
            : !error.isEmpty() ? I18n.format(error)
                : serverSource ? I18n.format("lagfinder.logs.server_subtitle")
                    : message != null && System.currentTimeMillis() - messageAt < 4000 ? message : null;
        if (note != null) {
            int noteX = serverSource ? folderButton.xPosition + folderButton.getWidth() + 8
                : deleteAllButton.xPosition - 8 - fontRendererObj.getStringWidth(note);
            String text = serverSource ? Theme.ellipsize(fontRendererObj, note, contentRight - noteX) : note;
            fontRendererObj.drawString(text, noteX, folderButton.yPosition + 6, Theme.TEXT_MUTED);
        }
    }

    private void drawRows(int mouseX, int mouseY, int x0, int y0, int x1, int y1) {
        int smooth = (int) Math.round(shownScroll.update(scroll, 18));
        SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy  HH:mm:ss");
        SimpleDateFormat hours = new SimpleDateFormat("HH:mm");
        int hoveredIndex = rowAt(mouseX, mouseY);
        Theme.clip(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
        for (int i = 0; i < shown.size(); i++) {
            int y = y0 + i * ROW - smooth;
            if (y + ROW < y0 || y > y1) {
                continue;
            }
            ReportFiles.Saved saved = shown.get(i);
            boolean hovered = i == hoveredIndex;
            Smooth light = lights.computeIfAbsent(saved, k -> new Smooth(0));
            double lit = light.update(hovered ? 1 : 0, 22);
            if (lit > 0.01) {
                Theme.fill(x0 + 1, y, x1 - 1, y + ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
            }
            if (i > 0) {
                Theme.fill(x0 + 6, y, x1 - 6, y + 1, 0xFF1C2129);
            }
            int textY = y + (ROW - 8) / 2;
            boolean tps = saved.mode == ScanMode.TPS;
            int color = saved.focus == ScanFocus.METHODS ? 0xFFFF8C42
                : saved.focus == ScanFocus.OBJECTS ? 0xFF3FB950
                    : saved.focus == ScanFocus.ALLOCATIONS ? 0xFF39C5CF : tps ? Theme.ACCENT : 0xFFB37FEB;
            String[] icon = saved.focus == ScanFocus.METHODS ? Icons.SMALL_TREE
                : saved.focus == ScanFocus.OBJECTS ? Icons.SMALL_MEMORY
                    : saved.focus == ScanFocus.ALLOCATIONS ? Icons.SMALL_ALLOC
                        : tps ? Icons.SMALL_GRID : Icons.SMALL_FRAME;
            // The kind's color down its left edge, brighter under the mouse.
            Theme.gradient(
                x0 + 1,
                y + 2,
                x0 + 3,
                y + ROW - 2,
                Theme.blend(color, 0xFF000000, 0.3 - 0.3 * lit),
                Theme.blend(color, 0xFF000000, 0.6 - 0.3 * lit));
            Icons.draw(icon, x0 + 8, y + (ROW - 7) / 2, color);
            String kind = GuiLagFinder.kindName(saved.side, saved.focus);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, kind, 140),
                x0 + 22,
                textY,
                Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.6 + 0.4 * lit));
            // Lately made ones tell how long ago, the others their date.
            long age = System.currentTimeMillis() - saved.time;
            String when = age >= 0 && age < 24 * 3600 * 1000L ? ago(age) + "  ·  " + hours.format(new Date(saved.time))
                : format.format(new Date(saved.time));
            fontRendererObj
                .drawString(when, x0 + 166, textY, age >= 0 && age < 3600 * 1000L ? Theme.TEXT : Theme.TEXT_MUTED);
            String size = size(saved.size);
            fontRendererObj
                .drawString(size, openX() - 10 - fontRendererObj.getStringWidth(size), textY, Theme.TEXT_DISABLED);
            drawRowButtons(saved, y, hovered, mouseX, mouseY);
        }
        Theme.unclip();
        if (shown.size() * ROW > listHeight) {
            Theme.scrollbar(
                x1 - 4,
                y0 + 2,
                y1 - 2,
                listHeight,
                shown.size() * ROW,
                smooth / (double) (shown.size() * ROW - listHeight),
                Theme.inside(mouseX, mouseY, x0, y0, x1, y1));
        }
    }

    private void drawRowButtons(ReportFiles.Saved saved, int y, boolean hovered, int mouseX, int mouseY) {
        int by0 = y + 3, by1 = y + ROW - 3;
        if (confirming == saved) {
            int bx0 = openX(), bx1 = deleteX() + DELETE_WIDTH;
            boolean over = Theme.inside(mouseX, mouseY, bx0, by0, bx1, by1);
            Theme.fill(bx0, by0, bx1, by1, over ? 0xFFB8403A : 0xFF7A2A26);
            Theme.outline(bx0, by0, bx1, by1, Theme.DANGER);
            Theme.centered(
                fontRendererObj,
                I18n.format("lagfinder.logs.delete_sure"),
                (bx0 + bx1) / 2,
                by0 + (by1 - by0 - 8) / 2,
                Theme.TEXT);
            return;
        }
        boolean overOpen = Theme.inside(mouseX, mouseY, openX(), by0, openX() + OPEN_WIDTH, by1);
        boolean overDelete = Theme.inside(mouseX, mouseY, deleteX(), by0, deleteX() + DELETE_WIDTH, by1);
        int openBorder = overOpen || hovered && !overDelete ? Theme.ACCENT : Theme.BORDER;
        Theme.fill(openX(), by0, openX() + OPEN_WIDTH, by1, overOpen ? Theme.ACCENT_DIM : Theme.CONTROL);
        Theme.outline(openX(), by0, openX() + OPEN_WIDTH, by1, openBorder);
        Theme.centered(
            fontRendererObj,
            I18n.format("lagfinder.logs.open"),
            openX() + OPEN_WIDTH / 2,
            by0 + (by1 - by0 - 8) / 2,
            hovered ? Theme.TEXT : Theme.TEXT_MUTED);
        if (serverSource) {
            // The server's reports are only read from here: no bin.
            return;
        }
        Theme.fill(deleteX(), by0, deleteX() + DELETE_WIDTH, by1, overDelete ? 0xFF4A2224 : Theme.CONTROL);
        Theme.outline(deleteX(), by0, deleteX() + DELETE_WIDTH, by1, overDelete ? Theme.DANGER : Theme.BORDER);
        Icons.draw(
            Icons.SMALL_TRASH,
            deleteX() + (DELETE_WIDTH - 7) / 2,
            by0 + (by1 - by0 - 7) / 2,
            overDelete ? Theme.DANGER : Theme.TEXT_MUTED);
    }

    /** How long ago, for less than a day: "just now", "5 min ago", "3 h ago". */
    private static String ago(long ms) {
        long minutes = ms / 60000;
        if (minutes < 1) {
            return I18n.format("lagfinder.logs.just_now");
        }
        if (minutes < 60) {
            return I18n.format("lagfinder.logs.min_ago", minutes);
        }
        return I18n.format("lagfinder.logs.hours_ago", minutes / 60);
    }

    /** A size in bytes, as KB or MB. */
    private static String size(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
