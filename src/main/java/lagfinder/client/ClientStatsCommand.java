package lagfinder.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import lagfinder.stats.ChatOut;
import lagfinder.stats.StatReports;

public class ClientStatsCommand extends CommandBase {

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
        Minecraft mc = Minecraft.getMinecraft();
        ChatOut out = new ChatOut(false);
        String command = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (command) {
            case "fps":
                StatReports.fps(out, ClientStats.INSTANCE.frames, System.nanoTime());
                break;
            case "memory-client":
                StatReports.memory(
                    out,
                    "lagfinder.cmd.memory_client.title",
                    ClientStats.INSTANCE.memory,
                    System.nanoTime(),
                    mc.isSingleplayer());
                break;
            case "tps":
            case "memory-server":
                if (!mc.isSingleplayer() && !ClientScanner.INSTANCE.serverHasMod()) {
                    sender.addChatMessage(
                        ChatOut.colored(out.tr("lagfinder.cmd.no_server_mod"), EnumChatFormatting.RED));
                    return;
                }
                mc.thePlayer.sendChatMessage("/lagfinder " + command);
                return;
            default:
                StatReports.help(out);
        }
        for (IChatComponent line : out.lines()) {
            sender.addChatMessage(line);
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, StatReports.COMMANDS) : null;
    }
}
