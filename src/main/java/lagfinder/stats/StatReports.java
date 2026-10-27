package lagfinder.stats;

import java.util.Locale;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import lagfinder.core.Timeline;

public final class StatReports {

    public static final String[] COMMANDS = { "fps", "tps", "memory", "cpu" };
    private static final int FIVE_MINUTES = 300;

    private StatReports() {}

    public static double usedMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0;
    }

    public static void help(ChatOut out) {
        out.rule();
        out.title("lagfinder.cmd.help.title");
        for (String command : COMMANDS) {
            out.command("/lagfinder " + command, out.tr("lagfinder.cmd.help." + command.replace('-', '_')));
        }
        out.rule();
    }

    public static void fps(ChatOut out, Timeline frames, long nowNanos) {
        long now = Timeline.second(nowNanos);
        out.rule();
        out.title("lagfinder.cmd.fps.title");
        Timeline.Span last = frames.span(now - 1, now - 1);
        out.stat(
            "lagfinder.cmd.now",
            last.isEmpty() ? noData(out)
                : out.tr(
                    "lagfinder.cmd.fps.now",
                    value(whole(last.count), fpsColor(last.count)),
                    value(ms(last.mean()), frameColor(last.mean()))));
        fpsWindow(out, "lagfinder.cmd.min1", frames.span(now - 60, now - 1), 60);
        fpsWindow(out, "lagfinder.cmd.min5", frames.span(now - FIVE_MINUTES, now - 1), FIVE_MINUTES);
        out.rule();
    }

    private static void fpsWindow(ChatOut out, String label, Timeline.Span span, int seconds) {
        out.stat(label, span.isEmpty() ? noData(out) : partial(out, fpsSpan(out, span), span, seconds));
    }

    private static IChatComponent fpsSpan(ChatOut out, Timeline.Span span) {
        return out.tr(
            "lagfinder.cmd.fps.span",
            value(whole(span.perSecond()), fpsColor(span.perSecond())),
            value(whole(span.fewest), fpsColor(span.fewest)),
            value(span.hasValues() ? ms(span.high) : "-", frameColor(span.hasValues() ? span.high : 0)));
    }

    public static void tps(ChatOut out, Timeline ticks, long nowNanos) {
        long now = Timeline.second(nowNanos);
        out.rule();
        out.title("lagfinder.cmd.tps.title");
        tpsWindow(out, "lagfinder.cmd.now", ticks.span(now - 5, now - 1), 0);
        tpsWindow(out, "lagfinder.cmd.min1", ticks.span(now - 60, now - 1), 60);
        tpsWindow(out, "lagfinder.cmd.min5", ticks.span(now - FIVE_MINUTES, now - 1), FIVE_MINUTES);
        out.rule();
    }

    private static void tpsWindow(ChatOut out, String label, Timeline.Span span, int seconds) {
        if (span.isEmpty()) {
            out.stat(label, noData(out));
            return;
        }
        IChatComponent text = tpsSpan(out, span);
        out.stat(label, seconds > 0 ? partial(out, text, span, seconds) : text);
    }

    private static IChatComponent tpsSpan(ChatOut out, Timeline.Span span) {
        double tps = tps(span);
        return out.tr(
            "lagfinder.cmd.tps.span",
            value(decimal(tps), tpsColor(tps)),
            value(span.hasValues() ? ms(span.mean()) : "-", msptColor(span.mean())),
            value(span.hasValues() ? ms(span.high) : "-", msptColor(span.hasValues() ? span.high : 0)));
    }

    private static double tps(Timeline.Span span) {
        return Math.min(20, span.perSecond());
    }

    public static void head(ChatOut out, String titleKey) {
        out.rule();
        out.title(titleKey);
    }

    public static void memory(ChatOut out, String sideKey, Timeline used, long nowNanos) {
        long now = Timeline.second(nowNanos);
        Runtime runtime = Runtime.getRuntime();
        double allocated = runtime.totalMemory() / 1048576.0;
        double max = runtime.maxMemory() == Long.MAX_VALUE ? allocated : runtime.maxMemory() / 1048576.0;
        double usedNow = allocated - runtime.freeMemory() / 1048576.0;
        out.section(sideKey);
        out.stat(
            "lagfinder.cmd.now",
            out.tr(
                "lagfinder.cmd.memory.now",
                size(out, usedNow, max),
                value(out, max),
                value(Math.round(usedNow / max * 100) + "%", memoryColor(usedNow / max)),
                value(out, allocated)));
        memoryWindow(out, "lagfinder.cmd.min1", used.span(now - 60, now - 1), 60, max);
        memoryWindow(out, "lagfinder.cmd.min5", used.span(now - FIVE_MINUTES, now - 1), FIVE_MINUTES, max);
    }

    public static void cpu(ChatOut out, String sideKey, Timeline process, Timeline system, long nowNanos) {
        long now = Timeline.second(nowNanos);
        out.section(sideKey);
        Timeline.Span lastProcess = process.span(now - 3, now - 1), lastSystem = system.span(now - 3, now - 1);
        out.stat(
            "lagfinder.cmd.now",
            !lastProcess.hasValues() ? noData(out)
                : out.tr(
                    "lagfinder.cmd.cpu.now",
                    percent(lastProcess.mean()),
                    lastSystem.hasValues() ? percent(lastSystem.mean()) : value("-", EnumChatFormatting.GRAY),
                    value(
                        String.valueOf(
                            Runtime.getRuntime()
                                .availableProcessors()),
                        EnumChatFormatting.WHITE)));
        cpuWindow(out, "lagfinder.cmd.min1", process.span(now - 60, now - 1), system.span(now - 60, now - 1), 60);
        cpuWindow(
            out,
            "lagfinder.cmd.min5",
            process.span(now - FIVE_MINUTES, now - 1),
            system.span(now - FIVE_MINUTES, now - 1),
            FIVE_MINUTES);
    }

    private static void cpuWindow(ChatOut out, String label, Timeline.Span process, Timeline.Span system,
        int seconds) {
        if (!process.hasValues()) {
            out.stat(label, noData(out));
            return;
        }
        IChatComponent text = out.tr(
            "lagfinder.cmd.cpu.span",
            percent(process.mean()),
            percent(process.high),
            system.hasValues() ? percent(system.mean()) : value("-", EnumChatFormatting.GRAY));
        out.stat(label, partial(out, text, process, seconds));
    }

    private static IChatComponent percent(double value) {
        return value(Math.round(value) + "%", cpuColor(value));
    }

    private static void memoryWindow(ChatOut out, String label, Timeline.Span span, int seconds, double max) {
        out.stat(
            label,
            !span.hasValues() ? noData(out) : partial(out, memorySpan(out, span, max), span, seconds));
    }

    private static IChatComponent memorySpan(ChatOut out, Timeline.Span span, double max) {
        return out.tr(
            "lagfinder.cmd.memory.span",
            size(out, span.mean(), max),
            size(out, span.low, max),
            size(out, span.high, max));
    }

    private static IChatComponent size(ChatOut out, double mb, double max) {
        return ChatOut.colored(sizeText(out, mb), memoryColor(mb / max));
    }

    private static IChatComponent value(ChatOut out, double mb) {
        return ChatOut.colored(sizeText(out, mb), EnumChatFormatting.WHITE);
    }

    private static IChatComponent sizeText(ChatOut out, double mb) {
        return mb < 1024 ? out.tr("lagfinder.cmd.mb", whole(mb)) : out.tr("lagfinder.cmd.gb", decimal(mb / 1024));
    }

    private static IChatComponent partial(ChatOut out, IChatComponent text, Timeline.Span span, int seconds) {
        if (span.seconds >= seconds - 1) {
            return text;
        }
        IChatComponent line = new ChatComponentText("");
        line.appendSibling(text);
        line.appendSibling(
            ChatOut.colored(out.tr("lagfinder.cmd.partial", clock(span.seconds)), EnumChatFormatting.DARK_GRAY));
        return line;
    }

    private static IChatComponent noData(ChatOut out) {
        return ChatOut.colored(out.tr("lagfinder.cmd.no_data"), EnumChatFormatting.DARK_GRAY);
    }

    private static IChatComponent value(String text, EnumChatFormatting color) {
        return ChatOut.colored(text, color);
    }

    static String clock(long seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String whole(double value) {
        return String.valueOf(Math.round(value));
    }

    private static String ms(double value) {
        return value >= 100 ? whole(value) : decimal(value);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static EnumChatFormatting fpsColor(double fps) {
        return fps >= 50 ? EnumChatFormatting.GREEN
            : fps >= 30 ? EnumChatFormatting.YELLOW : fps >= 15 ? EnumChatFormatting.GOLD : EnumChatFormatting.RED;
    }

    static EnumChatFormatting frameColor(double ms) {
        return ms <= 33 ? EnumChatFormatting.GREEN
            : ms <= 66 ? EnumChatFormatting.YELLOW : ms <= 150 ? EnumChatFormatting.GOLD : EnumChatFormatting.RED;
    }

    static EnumChatFormatting tpsColor(double tps) {
        return tps >= 19.5 ? EnumChatFormatting.GREEN
            : tps >= 17 ? EnumChatFormatting.YELLOW : tps >= 12 ? EnumChatFormatting.GOLD : EnumChatFormatting.RED;
    }

    static EnumChatFormatting msptColor(double ms) {
        return ms <= 35 ? EnumChatFormatting.GREEN
            : ms <= 50 ? EnumChatFormatting.YELLOW : ms <= 100 ? EnumChatFormatting.GOLD : EnumChatFormatting.RED;
    }

    static EnumChatFormatting cpuColor(double percent) {
        return percent < 50 ? EnumChatFormatting.GREEN
            : percent < 75 ? EnumChatFormatting.YELLOW
                : percent < 90 ? EnumChatFormatting.GOLD : EnumChatFormatting.RED;
    }

    static EnumChatFormatting memoryColor(double fraction) {
        return fraction < 0.7 ? EnumChatFormatting.GREEN
            : fraction < 0.85 ? EnumChatFormatting.YELLOW
                : fraction < 0.95 ? EnumChatFormatting.GOLD : EnumChatFormatting.RED;
    }
}
