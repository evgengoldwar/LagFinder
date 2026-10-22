package lagfinder.client;

import net.minecraft.client.Minecraft;
import lagfinder.client.gui.ui.FontRenderer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;

import lagfinder.Dims;
import lagfinder.client.gui.ui.Gfx;

import lagfinder.client.gui.Draw;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.gui.ui.Smooth;
import lagfinder.client.gui.ui.Theme;
import lagfinder.core.Report;
import lagfinder.core.ReportEntry;
import lagfinder.core.ScanMode;

/**
 * Beside the crosshair, while it is on a highlight: what the thing is, what it costs on average and at its worst,
 * how often it spiked and was busy, its load over the scan, and where it is. It pops in and out as the crosshair
 * comes and goes.
 */
final class HighlightCard {

    private static final int WIDTH = 196, HEIGHT = 140;
    private static final int PAD = 8;
    /** How far from the crosshair the card stands. */
    private static final int GAP = 18;

    private static final Smooth SHOWN = new Smooth(0);
    /** The highlight shown, kept while the card fades out after the crosshair has left it. */
    private static Highlighter.Highlight shown;

    private HighlightCard() {}

    static void draw(Minecraft mc, FontRenderer font, int width, int height) {
        Highlighter.Highlight hovered = mc.screen == null ? Highlighter.INSTANCE.hovered() : null;
        if (hovered != null && hovered != shown) {
            // Another one: start the pop again.
            if (shown != null) {
                SHOWN.set(0.4);
            }
            shown = hovered;
        }
        double t = SHOWN.update(hovered != null ? 1 : 0, 16);
        if (shown == null || t < 0.04) {
            if (hovered == null) {
                shown = null;
            }
            return;
        }
        int cx = width / 2, cy = height / 2;
        boolean right = cx + GAP + WIDTH <= width - 4;
        int x = right ? cx + GAP : Math.max(4, cx - GAP - WIDTH);
        int y = Math.max(4, Math.min(height - HEIGHT - 4, cy - HEIGHT / 2));
        // Grows out of the crosshair's side.
        double scale = 0.85 + 0.15 * t;
        int anchorX = right ? x : x + WIDTH;
        Gfx.pose().pushPose();
        Gfx.pose().translate((float) (anchorX + (right ? -8 : 8) * (1 - t)), cy, 0);
        Gfx.pose().scale((float) scale, (float) scale, 1);
        Gfx.pose().translate(-anchorX, -cy, 0);
        drawCard(mc, font, shown, x, y, t);
        Gfx.pose().popPose();
    }

    private static void drawCard(Minecraft mc, FontRenderer font, Highlighter.Highlight highlight, int x0, int y0,
        double t) {
        Report report = highlight.report;
        ReportEntry entry = highlight.entry;
        int x1 = x0 + WIDTH, y1 = y0 + HEIGHT;
        int color = highlight.color;
        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 300.0);

        // The card: shadow, a soft glow in the highlight's color, its body and a strip of the color on the left.
        Theme.shadow(x0, y0, x1, y1, 6);
        Theme.glow(x0, y0, x1, y1, color, (0.35 + 0.25 * pulse) * t);
        Theme.gradient(x0, y0, x1, y1, 0xF61A1F27, 0xF4111519);
        Theme.outline(x0, y0, x1, y1, Theme.blend(Theme.BORDER, color, 0.45));
        Theme.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, Theme.SHEEN);
        Theme.gradient(x0, y0, x0 + 3, y1, color, Theme.blend(color, 0xFF000000, 0.5));

        // What it is: its item, its name, its mod and kind.
        int x = x0 + PAD + 2;
        ItemStack stack = Names.icon(entry);
        Theme.fill(x - 1, y0 + 6, x + 19, y0 + 26, 0x40000000);
        Theme.outline(x - 1, y0 + 6, x + 19, y0 + 26, Theme.blend(Theme.BORDER, color, 0.3));
        if (stack != null) {
            Draw.item(stack, x + 1, y0 + 8, 16);
        } else {
            Icons.draw(Icons.SMALL_CUBE, x + 6, y0 + 13, color);
        }
        int textX = x + 25;
        String name = highlight.name + (entry.label != null ? " · " + entry.label : "");
        font.drawStringWithShadow(Theme.ellipsize(font, name, x1 - PAD - textX), textX, y0 + 7, Theme.TEXT);
        String kind = modOf(entry);
        String category = entry.category == null ? "" : Names.category(entry.category, report.mode);
        String subtitle = kind.isEmpty() ? category : kind + "  ·  " + category;
        font.drawString(Theme.ellipsize(font, subtitle, x1 - PAD - textX), textX, y0 + 18, Theme.TEXT_MUTED);

        int y = y0 + 31;
        Theme.fill(x0 + PAD, y, x1 - PAD, y + 1, Theme.BORDER);

        // Its two numbers, big: average and worst.
        y += 5;
        int half = (WIDTH - PAD * 2 - 2) / 2;
        int rightX = x0 + PAD + 2 + half + 4;
        double average = report.perPeriod(entry);
        boolean inFps = report.costsInFps();
        String avgLabel = I18n.get(report.mode == ScanMode.FPS ? "lagfinder.stat.avg_frame" : "lagfinder.stat.avg_tick");
        String peakLabel = I18n.get(inFps ? "lagfinder.stat.peak_fps" : "lagfinder.stat.peak");
        font.drawString(Theme.ellipsize(font, avgLabel, half - 2), x, y, Theme.TEXT_DISABLED);
        font.drawString(Theme.ellipsize(font, peakLabel, half - 2), rightX, y, Theme.TEXT_DISABLED);
        // A line between them.
        Theme.fill(rightX - 5, y, rightX - 4, y + 22, Theme.BORDER);
        String avgText = inFps ? Names.fpsLoss(report.fpsCost(average)) : Names.ms(average);
        String peakText = inFps ? Names.fpsLoss(report.fpsDrop(average, entry.maxMs)) : Names.ms(entry.maxMs);
        big(font, avgText, x, y + 11, severity(report, average));
        big(font, peakText, rightX, y + 11, peakColor(report, entry));
        String at = "@ " + Names.clock(entry.maxAt);
        int peakWidth = (int) Math.ceil(font.getStringWidth(peakText) * 1.5);
        if (rightX + peakWidth + 5 + font.getStringWidth(at) <= x1 - PAD) {
            font.drawString(at, rightX + peakWidth + 5, y + 14, Theme.TEXT_DISABLED);
        }

        // How much of a tick (or frame) it takes, as a bar.
        y += 28;
        double periodMs = report.averagePeriodMs();
        double share = periodMs <= 0 ? 0 : average / periodMs;
        int heat = severity(report, average);
        int barColor = heat == Theme.TEXT ? color : heat;
        Theme.meter(x, y, x1 - PAD, y + 3, Math.max(share, average > 0 ? 0.01 : 0), barColor);
        y += 6;
        String shareLabel = I18n.get(report.mode == ScanMode.FPS ? "lagfinder.stat.share_frame" : "lagfinder.stat.share_tick");
        font.drawString(shareLabel, x, y, Theme.TEXT_DISABLED);
        String shareText = share * 100 >= 10 ? String.format("%.0f%%", share * 100)
            : String.format("%.1f%%", share * 100);
        font.drawString(shareText, x1 - PAD - font.getStringWidth(shareText), y, Theme.TEXT_MUTED);

        // Spikes and how busy it was, as two chips.
        y += 13;
        int spikeColor = entry.spikes > 0 ? Theme.ORANGE : Theme.TEXT_DISABLED;
        int chipX = chip(
            font,
            Icons.SMALL_SPIKE,
            I18n.get("lagfinder.stat.spikes") + " ×" + entry.spikes,
            x,
            y,
            spikeColor);
        double active = Math.min(100, entry.activePeriods * 100.0 / Math.max(1, report.periods));
        chip(
            font,
            Icons.SMALL_CLOCK,
            I18n.get("lagfinder.stat.active") + " " + String.format("%.0f%%", active),
            chipX + 4,
            y,
            Theme.ACCENT);

        // Its load over the scan, its worst moment marked.
        y += 17;
        sparkline(report, entry, x, y, x1 - PAD, y + 20, color);

        // Where it is, and how far and which way.
        y += 25;
        Icons.draw(Icons.SMALL_TELEPORT, x, y, Theme.TEXT_MUTED);
        String far = distance(mc, highlight);
        int farWidth = far == null ? 0 : font.getStringWidth(far) + 6;
        String where = Names.position(entry) + "  ·  " + report.dimensionName(entry.dim);
        font.drawString(Theme.ellipsize(font, where, x1 - PAD - farWidth - (x + 10)), x + 10, y, Theme.TEXT_MUTED);
        if (far != null) {
            font.drawString(far, x1 - PAD - font.getStringWidth(far), y, color);
        }
    }

    /** Text half as big again. */
    private static void big(FontRenderer font, String text, int x, int y, int color) {
        Gfx.pose().pushPose();
        Gfx.pose().translate(x, y, 0);
        Gfx.pose().scale(1.5f, 1.5f, 1f);
        font.drawStringWithShadow(text, 0, 0, color);
        Gfx.pose().popPose();
    }

    /** A small rounded chip with an icon and text, its edge in the color. Returns where it ends. */
    private static int chip(FontRenderer font, String[] icon, String text, int x, int y, int color) {
        int width = Icons.width(icon) + 4 + font.getStringWidth(text) + 10;
        int edge = Theme.blend(color, Theme.CONTROL, 0.55);
        Theme.fill(x + 1, y, x + width - 1, y + 12, Theme.CONTROL);
        Theme.fill(x, y + 1, x + width, y + 11, Theme.CONTROL);
        Theme.fill(x + 1, y, x + width - 1, y + 1, edge);
        Theme.fill(x + 1, y + 11, x + width - 1, y + 12, edge);
        Theme.fill(x, y + 1, x + 1, y + 11, edge);
        Theme.fill(x + width - 1, y + 1, x + width, y + 11, edge);
        Icons.draw(icon, x + 5, y + 3, color);
        font.drawString(text, x + 5 + Icons.width(icon) + 4, y + 2, Theme.TEXT);
        return x + width;
    }

    /** Its average in each moment of the scan, filled in its color, with a mark where it was at its worst. */
    private static void sparkline(Report report, ReportEntry entry, int x0, int y0, int x1, int y1, int color) {
        Theme.fill(x0, y0, x1, y1, 0x50000000);
        Theme.outline(x0, y0, x1, y1, 0xFF20262E);
        int buckets = entry.sum == null ? 0 : Math.min(report.buckets(), entry.sum.length);
        if (buckets < 2) {
            return;
        }
        double top = 0;
        double[] values = new double[buckets];
        for (int i = 0; i < buckets; i++) {
            values[i] = report.perPeriod(entry, i);
            top = Math.max(top, values[i]);
        }
        if (top <= 0) {
            return;
        }
        double left = x0 + 1, width = x1 - x0 - 2, base = y1 - 1, room = y1 - y0 - 4;
        double[] xs = new double[buckets], ys = new double[buckets];
        int[] tops = new int[buckets], bottoms = new int[buckets], lines = new int[buckets];
        int rgb = color & 0xFFFFFF;
        for (int i = 0; i < buckets; i++) {
            xs[i] = left + width * i / (buckets - 1);
            ys[i] = base - room * values[i] / top;
            tops[i] = 0x90000000 | rgb;
            bottoms[i] = 0x10000000 | rgb;
            lines[i] = 0xFF000000 | rgb;
        }
        Draw.area(xs, ys, base, tops, bottoms);
        Draw.line(xs, ys, lines, 1f);
        if (report.durationSec > 0 && entry.maxMs > 0) {
            int mark = (int) Math.round(left + width * Math.max(0, Math.min(1, entry.maxAt / report.durationSec)));
            Theme.fill(mark, y0 + 1, mark + 1, y1 - 1, 0xC0000000 | Theme.ORANGE & 0xFFFFFF);
            Theme.fill(mark - 1, y0 + 1, mark + 2, y0 + 3, Theme.ORANGE);
        }
    }

    private static String distance(Minecraft mc, Highlighter.Highlight highlight) {
        if (mc.player == null || mc.level == null || highlight.dim != Dims.id(mc.level)) {
            return null;
        }
        double dx = highlight.x + 0.5 - mc.player.getX(), dy = highlight.y - mc.player.getY(),
            dz = highlight.z + 0.5 - mc.player.getZ();
        return Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + " m";
    }

    private static int severity(Report report, double perPeriodMs) {
        float budget = report.mode.budgetMs();
        if (perPeriodMs >= budget * 0.1) {
            return Theme.DANGER;
        }
        if (perPeriodMs >= budget * 0.02) {
            return Theme.WARN;
        }
        return Theme.TEXT;
    }

    private static int peakColor(Report report, ReportEntry entry) {
        if (entry.maxMs >= report.mode.budgetMs() * 0.5f) {
            return Theme.DANGER;
        }
        if (entry.maxMs >= report.spikeMs) {
            return Theme.ORANGE;
        }
        return Theme.TEXT;
    }

    private static String modOf(ReportEntry entry) {
        for (ReportEntry up = entry; up != null; up = up.parent) {
            if (up.level == 1) {
                return up.name;
            }
        }
        return "";
    }
}
