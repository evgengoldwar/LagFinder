package lagfinder.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.EnumChatFormatting;

import lagfinder.server.StatsCommand;
import lagfinder.stats.ChatOut;
import lagfinder.stats.StatReports;

public class ClientStatsCommand extends CommandBase {

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
        Minecraft mc = Minecraft.getMinecraft();
        ChatOut out = new ChatOut(false);
        String command = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean singleplayer = mc.isSingleplayer();
        boolean serverHasMod = singleplayer || ClientScanner.INSTANCE.serverHasMod();
        long now = System.nanoTime();
        ClientStats stats = ClientStats.INSTANCE;
        switch (command) {
            case "fps":
                StatReports.fps(out, stats.frames, now);
                break;
            case "tps":
                if (serverHasMod) {
                    mc.thePlayer.sendChatMessage("/lagfinder tps");
                    return;
                }
                out.line(ChatOut.colored(out.tr("lagfinder.cmd.no_server_mod"), EnumChatFormatting.RED));
                break;
            case "memory":
            case "cpu":
                StatReports.head(out, "lagfinder.cmd." + command + ".title");
                if (command.equals("memory")) {
                    StatReports.memory(out, "lagfinder.cmd.side.client", stats.memory, now);
                } else {
                    StatReports.cpu(out, "lagfinder.cmd.side.client", stats.cpu.process, stats.cpu.system, now);
                }
                if (!singleplayer && serverHasMod) {
                    StatChat.INSTANCE.show(out);
                    mc.thePlayer.sendChatMessage("/lagfinder " + command + " " + StatsCommand.PART);
                    return;
                }
                break;
            default:
                StatReports.help(out);
        }
        StatChat.INSTANCE.show(out);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, StatReports.COMMANDS) : null;
    }
}
