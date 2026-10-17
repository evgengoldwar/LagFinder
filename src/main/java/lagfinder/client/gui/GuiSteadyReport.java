package lagfinder.client.gui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import lagfinder.client.ClientReports;
import lagfinder.client.ClientScanner;
import lagfinder.client.Names;
import lagfinder.client.ReportStore;
import lagfinder.client.gui.ui.FlatButton;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.ScaledScreen;
import lagfinder.client.gui.ui.ScrollbarDrag;
import lagfinder.client.gui.ui.Theme;
import lagfinder.client.gui.ui.WindowHeader;
import lagfinder.core.Category;
import lagfinder.core.Report;
import lagfinder.core.ReportEntry;
import lagfinder.core.ReportFiles;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;

public final class GuiSteadyReport extends ScaledScreen {

    private static final int ID_CLOSE = 1, ID_DETAILS = 2, ID_SORT = 3, ID_COMPARE = 4;
    private static final int ROW = 23;
    private static final int HEADER = 16;
    private final Report report;
    private final List<ReportEntry> types = new ArrayList<>();
    private final List<ReportFiles.Saved> previous = new ArrayList<>();
    private final Set<ReportFiles.Saved> shared = new HashSet<>();
    private int archiveRevision = -1;
    private boolean baselinePending;
    private final ScrollbarDrag scrollbar = new ScrollbarDrag();
    private final Map<String, ReportEntry> baselineTypes = new HashMap<>();
    private int left, top, right, bottom, listTop, listBottom;
    private int scroll, sort, comparing = -1;
    private Report baseline;
    private ReportEntry selected;
    private FlatButton compareButton, sortButton;

    public GuiSteadyReport(Report report) {
        this.report = report;
        for (ReportEntry category : report.categories) {
            if (category.category == Category.WORLDS) {
                continue;
            }
            for (ReportEntry mod : category.children) {
                for (ReportEntry type : mod.children) {
                    if (type.totalMs > 0 && type.level == 2) {
                        types.add(type);
                    }
                }
            }
        }
        reloadPrevious();
        if (report.mode == ScanMode.TPS && ClientScanner.INSTANCE.serverArchiveAvailable()
            && ClientReports.INSTANCE.list()
                .isEmpty()) {
            ClientReports.INSTANCE.listReports();
        }
        sortTypes();
    }

    @Override
    public void initGui() {
        left = Math.max(4, (width - 785) / 2);
        right = Math.min(width - 4, left + 785);
        top = 6;
        bottom = height - 6;
        listTop = top + WindowHeader.HEIGHT + 99;
        listBottom = bottom - 58;
        int buttonsY = bottom - 28;
        buttonList.clear();
        FlatButton back = new FlatButton(ID_CLOSE, left + 12, buttonsY, 95, 20, I18n.format("lagfinder.steady.back"));
        back.icon = Icons.BACK;
        buttonList.add(back);
        FlatButton details = new FlatButton(
            ID_DETAILS,
            left + 113,
            buttonsY,
            115,
            20,
            I18n.format("lagfinder.steady.details"));
        details.icon = Icons.STATS;
        buttonList.add(details);
        sortButton = new FlatButton(ID_SORT, right - 302, buttonsY, 140, 20, "");
        buttonList.add(sortButton);
        compareButton = new FlatButton(ID_COMPARE, right - 156, buttonsY, 144, 20, "");
        buttonList.add(compareButton);
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, right, top));
        updateButtons();
    }

    private void updateButtons() {
        sortButton.displayString = I18n.format("lagfinder.steady.sort") + ": "
            + I18n.format(
                new String[] { "lagfinder.steady.median", "lagfinder.steady.average", "lagfinder.steady.calls",
                    "lagfinder.steady.idle" }[sort]);
        compareButton.displayString = comparing >= 0 ? I18n.format("lagfinder.steady.next_compare")
            : I18n.format("lagfinder.steady.compare");
        compareButton.enabled = !previous.isEmpty() && !baselinePending && !ClientReports.INSTANCE.loading();
    }

    private void sortTypes() {
        Comparator<ReportEntry> cmp;
        switch (sort) {
            case 1:
                cmp = Comparator.comparingDouble(e -> report.perPeriod(e));
                break;
            case 2:
                cmp = Comparator.comparingDouble(e -> report.periods <= 0 ? 0 : e.calls / (double) report.periods);
                break;
            case 3:
                cmp = Comparator.comparingDouble(e -> report.periods <= 0 ? 0 : e.gtIdleMs / report.periods);
                break;
            default:
                cmp = Comparator.comparingDouble(e -> e.medianMs);
        }
        types.sort(
            cmp.reversed()
                .thenComparing(
                    Comparator.comparingDouble((ReportEntry e) -> e.totalMs)
                        .reversed()));
        scroll = 0;
    }

    private static String identity(ReportEntry e) {
        String mod = e.parent == null ? "" : e.parent.name;
        String type = e.detail != null && !e.detail.isEmpty() ? e.detail : e.name;
        return e.category.id + "|" + mod + "|" + type + "|" + e.itemId + ":" + e.itemDamage;
    }

    private void reloadPrevious() {
        previous.clear();
        shared.clear();
        archiveRevision = ClientReports.INSTANCE.revision();
        List<ReportFiles.Saved> available = new ArrayList<>(ReportStore.list());
        if (report.mode == ScanMode.TPS && ClientScanner.INSTANCE.serverArchiveAvailable()) {
            shared.addAll(ClientReports.INSTANCE.list());
            available.addAll(ClientReports.INSTANCE.list());
        }
        for (ReportFiles.Saved saved : available) {
            if (saved.focus == ScanFocus.STEADY && saved.mode == report.mode
                && Math.abs(saved.time - report.startedAt) >= 1000) {
                previous.add(saved);
            }
        }
        comparing = -1;
    }

    private boolean setBaseline(Report loaded) {
        if (loaded == null || loaded.focus != ScanFocus.STEADY
            || loaded.mode != report.mode
            || !report.source.equals(loaded.source)) {
            return false;
        }
        baseline = loaded;
        baselineTypes.clear();
        for (ReportEntry category : loaded.categories) {
            for (ReportEntry mod : category.children) {
                for (ReportEntry type : mod.children) {
                    baselineTypes.put(identity(type), type);
                }
            }
        }
        return true;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (archiveRevision != ClientReports.INSTANCE.revision()) {
            reloadPrevious();
        }
        if (baselinePending && !ClientReports.INSTANCE.loading()) {
            baselinePending = false;
            setBaseline(ClientReports.INSTANCE.takeReport());
        }
        updateButtons();
    }

    private void nextComparison() {
        if (previous.isEmpty()) {
            return;
        }
        for (int i = 0; i < previous.size(); i++) {
            comparing = (comparing + 1) % previous.size();
            ReportFiles.Saved saved = previous.get(comparing);
            if (shared.contains(saved)) {
                if (!ClientScanner.INSTANCE.serverArchiveAvailable()) {
                    continue;
                }
                ClientReports.INSTANCE.open(saved);
                baselinePending = ClientReports.INSTANCE.loading();
                updateButtons();
                return;
            }
            Report loaded = ReportStore.load(saved.name);
            if (setBaseline(loaded)) {
                updateButtons();
                return;
            }
        }
        comparing = -1;
        baseline = null;
        baselineTypes.clear();
        updateButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_CLOSE:
                mc.displayGuiScreen(new GuiLagFinder());
                break;
            case ID_DETAILS:
                mc.displayGuiScreen(new GuiReport(report));
                break;
            case ID_SORT:
                sort = (sort + 1) % 4;
                sortTypes();
                updateButtons();
                break;
            case ID_COMPARE:
                nextComparison();
                break;
            default:
                break;
        }
    }

    private int maxScroll() {
        return Math.max(0, types.size() * ROW - (listBottom - listTop - HEADER));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0) {
            int visible = listBottom - listTop - HEADER;
            int next = scrollbar.press(
                mouseX,
                mouseY,
                right - 17,
                listTop + HEADER,
                listBottom,
                visible,
                types.size() * ROW,
                scroll,
                maxScroll());
            if (next >= 0) {
                scroll = next;
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
        if (button != 0 || !Theme.inside(mouseX, mouseY, left + 12, listTop + HEADER, right - 20, listBottom)) {
            return;
        }
        int index = (mouseY - listTop - HEADER + scroll) / ROW;
        if (index >= 0 && index < types.size()) {
            selected = types.get(index);
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (button == 0) {
            int next = scrollbar.drag(mouseY);
            if (next >= 0) {
                scroll = next;
                return;
            }
        }
        super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        if (button == 0 || button == -1) {
            scrollbar.release();
        }
        super.mouseMovedOrUp(mouseX, mouseY, button);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta != 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - Integer.signum(delta) * ROW * 3));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(new GuiLagFinder());
        }
    }

    private static String ms(double ms) {
        double magnitude = Math.abs(ms);
        if (magnitude < 0.0005) {
            return "0";
        }
        String value = String.format(Locale.US, magnitude < 0.01 ? "%.3f" : "%.2f", magnitude);
        return ms < 0 ? "-" + value : value;
    }

    private String difference(double now, double before) {
        double delta = now - before;
        return (delta > 0.00005 ? "+" : "") + ms(delta) + " ms";
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);
        WindowHeader.draw(
            fontRendererObj,
            left,
            top,
            right,
            right - WindowHeader.CLOSE_ROOM,
            Icons.CLOCK,
            I18n.format("lagfinder.steady.title"),
            I18n.format("lagfinder.steady.subtitle", Math.round(report.durationSec), report.periods),
            Theme.TEXT_MUTED,
            I18n.format("lagfinder.steady.types", types.size()));

        int summaryTop = top + WindowHeader.HEIGHT + 9;
        int summaryBottom = summaryTop + 50;
        Theme.card(left + 12, summaryTop, right - 12, summaryBottom);
        int x = left + 23;
        int[] widths = { 175, 172, 175, 175 };
        String[] labels = { I18n.format("lagfinder.steady.tick_p50"), I18n.format("lagfinder.steady.tick_p90"),
            I18n.format("lagfinder.steady.tick_avg"), I18n.format("lagfinder.steady.gt_idle") };
        double idle = 0;
        for (ReportEntry category : report.categories) {
            if (category.category == Category.TILE_ENTITIES) {
                idle = report.perPeriod(category) <= 0 ? 0 : category.gtIdleMs / Math.max(1, report.periods);
            }
        }
        double[] values = { report.steadyMedianTickMs, report.steadyP90TickMs, report.averagePeriodMs(), idle };
        double[] previousValues = { baseline == null ? 0 : baseline.steadyMedianTickMs,
            baseline == null ? 0 : baseline.steadyP90TickMs, baseline == null ? 0 : baseline.averagePeriodMs(), 0 };
        if (baseline != null) {
            for (ReportEntry category : baseline.categories) {
                if (category.category == Category.TILE_ENTITIES) {
                    previousValues[3] = category.gtIdleMs / Math.max(1, baseline.periods);
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            fontRendererObj.drawString(labels[i], x, summaryTop + 7, Theme.TEXT_MUTED);
            fontRendererObj.drawString(
                ms(values[i]) + " ms",
                x,
                summaryTop + 22,
                i == 3 && values[i] > 1 ? Theme.WARN : Theme.TEXT);
            if (baseline != null) {
                String change = difference(values[i], previousValues[i]);
                fontRendererObj.drawString(
                    change,
                    x,
                    summaryTop + 35,
                    values[i] > previousValues[i] ? Theme.DANGER : Theme.SUCCESS);
            }
            x += widths[i];
        }
        String note = baseline != null
            ? I18n.format(
                "lagfinder.steady.baseline",
                new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(baseline.startedAt)))
            : I18n.format("lagfinder.steady.hint");
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, note, right - left - 28),
            left + 14,
            summaryBottom + 9,
            Theme.TEXT_MUTED);

        drawList(mouseX, mouseY);
        if (selected != null) {
            String detail = Names.of(selected) + "  |  "
                + I18n.format("lagfinder.steady.active")
                + " "
                + Math.round(selected.activePeriods * 100.0 / Math.max(1, report.periods))
                + "%"
                + "  |  "
                + I18n.format("lagfinder.steady.gt_active")
                + " "
                + ms(selected.gtActiveMs / Math.max(1, report.periods))
                + " ms/t"
                + "  |  "
                + I18n.format("lagfinder.steady.gt_idle")
                + " "
                + ms(selected.gtIdleMs / Math.max(1, report.periods))
                + " ms/t";
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, detail, right - left - 28),
                left + 14,
                bottom - 43,
                Theme.TEXT_MUTED);
        }
        super.drawScaled(mouseX, mouseY, partialTicks);
    }

    private void drawList(int mouseX, int mouseY) {
        int x0 = left + 12, x1 = right - 12;
        Theme.card(x0, listTop, x1, listBottom);
        int avail = x1 - x0;
        int wName = Math.max(120, avail - 422);
        int[] positions = { x0 + 10, x0 + 10 + wName, x0 + 10 + wName + 67, x0 + 10 + wName + 132,
            x0 + 10 + wName + 197, x0 + 10 + wName + 255, x0 + 10 + wName + 329 };
        String[] labels = { I18n.format("lagfinder.steady.machine"), "P50", "P90",
            I18n.format("lagfinder.steady.average"), I18n.format("lagfinder.steady.active"),
            I18n.format("lagfinder.steady.calls"),
            baseline == null ? I18n.format("lagfinder.steady.idle") : "Delta avg" };
        for (int i = 0; i < labels.length; i++) {
            fontRendererObj.drawString(labels[i], positions[i], listTop + 5, Theme.TEXT_MUTED);
        }
        Theme.fill(x0 + 1, listTop + HEADER - 1, x1 - 1, listTop + HEADER, Theme.BORDER);
        int yTop = listTop + HEADER;
        Theme.clip(x0 + 1, yTop, x1 - 2, listBottom - 1);
        int first = scroll / ROW;
        int last = Math.min(types.size(), first + (listBottom - yTop) / ROW + 3);
        for (int i = first; i < last; i++) {
            ReportEntry e = types.get(i);
            int y = yTop + i * ROW - scroll;
            if (y + ROW <= yTop || y >= listBottom) {
                continue;
            }
            boolean hovered = Theme.inside(mouseX, mouseY, x0, y, x1, y + ROW);
            if (selected == e || hovered) {
                Theme.fill(x0 + 1, y, x1 - 3, y + ROW - 1, selected == e ? Theme.SELECTED : Theme.ROW_HOVER);
            }
            if ((i & 1) == 0) {
                Theme.fill(x0 + 1, y + ROW - 1, x1 - 1, y + ROW, 0x252F3740);
            }
            String name = Names.of(e);
            String mod = e.parent == null ? "" : e.parent.name;
            String shown = Theme.ellipsize(fontRendererObj, name, wName - 6);
            fontRendererObj.drawString(shown, positions[0], y + 3, Theme.TEXT);
            fontRendererObj
                .drawString(Theme.ellipsize(fontRendererObj, mod, wName - 6), positions[0], y + 13, Theme.TEXT_MUTED);
            fontRendererObj.drawString(ms(e.medianMs), positions[1], y + 7, e.medianMs > 1 ? Theme.WARN : Theme.TEXT);
            fontRendererObj.drawString(ms(e.p90Ms), positions[2], y + 7, Theme.TEXT_MUTED);
            fontRendererObj.drawString(ms(report.perPeriod(e)), positions[3], y + 7, Theme.TEXT);
            fontRendererObj.drawString(
                Math.round(100.0 * e.activePeriods / Math.max(1, report.periods)) + "%",
                positions[4],
                y + 7,
                Theme.TEXT_MUTED);
            fontRendererObj.drawString(
                String.format(Locale.US, "%.1f", e.calls / (double) Math.max(1, report.periods)),
                positions[5],
                y + 7,
                Theme.TEXT_MUTED);
            if (baseline == null) {
                fontRendererObj.drawString(
                    ms(e.gtIdleMs / Math.max(1, report.periods)),
                    positions[6],
                    y + 7,
                    e.gtIdleMs > 0 ? Theme.WARN : Theme.TEXT_MUTED);
            } else {
                ReportEntry before = baselineTypes.get(identity(e));
                double previous = before == null ? 0 : before.totalMs / Math.max(1, baseline.periods);
                double delta = report.perPeriod(e) - previous;
                fontRendererObj.drawString(
                    (delta > 0.00005 ? "+" : "") + ms(delta),
                    positions[6],
                    y + 7,
                    delta > 0 ? Theme.DANGER : Theme.SUCCESS);
            }
        }
        Theme.unclip();
        int max = maxScroll();
        if (max > 0) {
            int track = listBottom - yTop;
            int thumb = Math.max(12, track * track / Math.max(track, types.size() * ROW));
            int sy = yTop + (track - thumb) * scroll / max;
            Theme.fill(x1 - 7, yTop + 1, x1 - 5, listBottom - 2, Theme.BORDER);
            Theme.fill(x1 - 9, sy, x1 - 3, sy + thumb, Theme.ACCENT_DIM);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
