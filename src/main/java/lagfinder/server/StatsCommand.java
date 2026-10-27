package lagfinder.server;

import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import lagfinder.stats.ChatOut;
import lagfinder.stats.StatReports;

public class StatsCommand extends CommandBase {

    public static final String PART = "part";

    @Override
    public String getCommandName() {
        return "lagfinder";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/lagfinder <fps|tps|memory|cpu>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        EntityPlayerMP player = sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null;
        boolean withMod = player != null && ServerStats.INSTANCE.hasMod(player);
        boolean allowed = player == null || ServerScanner.canScan(player);
        ChatOut out = new ChatOut(!withMod);
        String command = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean part = args.length > 1 && PART.equals(args[1]);
        long now = System.nanoTime();
        ServerStats stats = ServerStats.INSTANCE;
        switch (command) {
            case "tps":
                if (allowed) {
                    StatReports.tps(out, stats.ticks, now);
                } else {
                    error(out, "lagfinder.cmd.not_allowed");
                }
                break;
            case "memory":
            case "cpu":
                if (!part) {
                    StatReports.head(out, "lagfinder.cmd." + command + ".title");
                }
                if (!allowed) {
                    out.section("lagfinder.cmd.side.server");
                    error(out, "lagfinder.cmd.not_allowed");
                } else if (command.equals("memory")) {
                    StatReports.memory(out, "lagfinder.cmd.side.server", stats.memory, now);
                } else {
                    StatReports.cpu(out, "lagfinder.cmd.side.server", stats.cpu.process, stats.cpu.system, now);
                }
                out.rule();
                break;
            case "fps":
                error(out, "lagfinder.cmd.client_only");
                break;
            default:
                StatReports.help(out);
        }
        for (IChatComponent line : withMod ? out.marked(!part) : out.lines()) {
            sender.addChatMessage(line);
        }
    }

    private static void error(ChatOut out, String key) {
        out.line(ChatOut.colored(out.tr(key), EnumChatFormatting.RED));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, StatReports.COMMANDS) : null;
    }
}
