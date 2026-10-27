package lagfinder.server;

import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import lagfinder.stats.ChatOut;
import lagfinder.stats.StatReports;

public class StatsCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "lagfinder";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/lagfinder <fps|tps|memory-client|memory-server>";
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
        boolean translateHere = player == null || !ServerStats.INSTANCE.hasMod(player);
        ChatOut out = new ChatOut(translateHere);
        String command = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (command) {
            case "tps":
            case "memory-server":
                if (player != null && !ServerScanner.canScan(player)) {
                    say(sender, out, "lagfinder.cmd.not_allowed");
                    return;
                }
                if (command.equals("tps")) {
                    StatReports.tps(out, ServerStats.INSTANCE.ticks, System.nanoTime());
                } else {
                    MinecraftServer server = MinecraftServer.getServer();
                    StatReports.memory(
                        out,
                        "lagfinder.cmd.memory_server.title",
                        ServerStats.INSTANCE.memory,
                        System.nanoTime(),
                        server != null && server.isSinglePlayer());
                }
                break;
            case "fps":
            case "memory-client":
                say(sender, out, "lagfinder.cmd.client_only");
                return;
            default:
                StatReports.help(out);
        }
        for (IChatComponent line : out.lines()) {
            sender.addChatMessage(line);
        }
    }

    private static void say(ICommandSender sender, ChatOut out, String key) {
        sender.addChatMessage(ChatOut.colored(out.tr(key), EnumChatFormatting.RED));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, StatReports.COMMANDS) : null;
    }
}
