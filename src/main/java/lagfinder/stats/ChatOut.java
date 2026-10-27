package lagfinder.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.StatCollector;

public final class ChatOut {

    public static final String RULE = "============================";
    private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?s");

    private final boolean translateHere;
    private final List<IChatComponent> lines = new ArrayList<>();

    public ChatOut(boolean translateHere) {
        this.translateHere = translateHere;
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
}
