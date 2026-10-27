package lagfinder.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.StatCollector;

public final class ChatOut {

    public static final String RULE = "============================";
    public static final int ROWS = 6;
    private static final String FULL = "█", HALF = "▄";
    private static final String SPARKS = "▁▂▃▄▅▆▇█";
    private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?s");

    private final boolean translateHere;
    private final boolean sparkline;
    private final List<IChatComponent> lines = new ArrayList<>();

    public ChatOut(boolean translateHere, boolean sparkline) {
        this.translateHere = translateHere;
        this.sparkline = sparkline;
    }

    public List<IChatComponent> lines() {
        return lines;
    }

    public IChatComponent tr(String key, Object... args) {
        if (!translateHere) {
            return new ChatComponentTranslation(key, args);
        }
        String format = StatCollector.translateToLocal(key);
        IChatComponent out = new ChatComponentText("");
        Matcher matcher = ARG.matcher(format);
        int at = 0, next = 0;
        while (matcher.find()) {
            out.appendText(format.substring(at, matcher.start()));
            int index = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) - 1 : next++;
            Object arg = index >= 0 && index < args.length ? args[index] : "";
            out.appendSibling(
                arg instanceof IChatComponent ? (IChatComponent) arg : new ChatComponentText(String.valueOf(arg)));
            at = matcher.end();
        }
        out.appendText(format.substring(at));
        return out;
    }

    public static IChatComponent colored(IChatComponent text, EnumChatFormatting color) {
        text.getChatStyle()
            .setColor(color);
        return text;
    }

    public static IChatComponent colored(String text, EnumChatFormatting color) {
        return colored(new ChatComponentText(text), color);
    }

    public void line(IChatComponent line) {
        lines.add(line);
    }

    public void rule() {
        line(colored(RULE, EnumChatFormatting.GOLD));
    }

    public void title(String key) {
        IChatComponent name = colored("LagFinder", EnumChatFormatting.GOLD);
        name.getChatStyle()
            .setBold(true);
        IChatComponent line = new ChatComponentText("");
        line.appendSibling(name);
        line.appendSibling(colored(" · ", EnumChatFormatting.DARK_GRAY));
        line.appendSibling(colored(tr(key), EnumChatFormatting.WHITE));
        line(line);
    }

    public void stat(String labelKey, IChatComponent value) {
        IChatComponent line = new ChatComponentText("");
        line.appendSibling(colored(tr(labelKey), EnumChatFormatting.GRAY));
        line.appendSibling(colored(": ", EnumChatFormatting.DARK_GRAY));
        value.getChatStyle()
            .setColor(EnumChatFormatting.GRAY);
        line.appendSibling(value);
        line(line);
    }

    public void note(IChatComponent text) {
        line(colored(text, EnumChatFormatting.DARK_GRAY));
    }

    public void command(String command, IChatComponent what) {
        IChatComponent line = new ChatComponentText("");
        IChatComponent name = colored(command, EnumChatFormatting.YELLOW);
        name.getChatStyle()
            .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .setChatHoverEvent(
                new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    colored(tr("lagfinder.cmd.click"), EnumChatFormatting.GRAY)));
        line.appendSibling(name);
        line.appendSibling(colored(" - ", EnumChatFormatting.DARK_GRAY));
        line.appendSibling(colored(what, EnumChatFormatting.GRAY));
        line(line);
    }

    public static final class Column {

        final double value;
        final EnumChatFormatting color;
        final IChatComponent hover;

        public Column(double value, EnumChatFormatting color, IChatComponent hover) {
            this.value = value;
            this.color = color;
            this.hover = hover;
        }
    }

    public void chart(Column[] columns, double top, IChatComponent topLabel, IChatComponent midLabel) {
        if (sparkline) {
            StringBuilder spark = new StringBuilder();
            for (Column column : columns) {
                if (Double.isNaN(column.value)) {
                    spark.append(' ');
                } else {
                    int level = (int) Math.round(clamp(column.value / top) * (SPARKS.length() - 1));
                    spark.append(SPARKS.charAt(level));
                }
            }
            IChatComponent line = new ChatComponentText(spark.toString());
            line.appendText(" ");
            line.appendSibling(topLabel);
            line(line);
            return;
        }
        int levels = ROWS * 2;
        int[] heights = new int[columns.length];
        for (int i = 0; i < columns.length; i++) {
            double value = columns[i].value;
            if (!Double.isNaN(value) && value > 0) {
                heights[i] = Math.max(1, (int) Math.round(clamp(value / top) * levels));
            }
        }
        for (int row = 0; row < ROWS; row++) {
            int floor = (ROWS - 1 - row) * 2;
            IChatComponent line = new ChatComponentText("");
            for (int i = 0; i < columns.length; i++) {
                Column column = columns[i];
                boolean full = heights[i] >= floor + 2, half = heights[i] == floor + 1;
                IChatComponent cell = new ChatComponentText(half ? HALF : FULL);
                cell.setChatStyle(
                    new ChatStyle().setColor(full || half ? column.color : EnumChatFormatting.BLACK)
                        .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, column.hover)));
                line.appendSibling(cell);
            }
            IChatComponent label = row == 0 ? topLabel : row == ROWS / 2 ? midLabel : row == ROWS - 1 ? zero() : null;
            if (label != null) {
                line.appendSibling(colored(" ", EnumChatFormatting.GRAY));
                line.appendSibling(colored(label, EnumChatFormatting.GRAY));
            }
            line(line);
        }
    }

    private static IChatComponent zero() {
        return new ChatComponentText("0");
    }

    private static double clamp(double fraction) {
        return Math.max(0, Math.min(1, fraction));
    }
}
