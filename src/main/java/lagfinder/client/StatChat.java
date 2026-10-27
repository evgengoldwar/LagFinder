package lagfinder.client;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import lagfinder.LagFinder;
import lagfinder.stats.ChatOut;

public final class StatChat {

    public static final StatChat INSTANCE = new StatChat();

    private static final int FIRST_ID = 0x4C460000;
    private static final int MAX_LINES = 64;

    private final Method print = find(new String[] { "func_146234_a", "printChatMessageWithOptionalDeletion" },
        IChatComponent.class, int.class);
    private final Method delete = find(new String[] { "func_146242_c", "deleteChatLine" }, int.class);
    private int printed;

    private StatChat() {}

    public void begin() {
        GuiNewChat chat = chat();
        if (chat != null && delete != null) {
            for (int i = 0; i < printed; i++) {
                try {
                    delete.invoke(chat, FIRST_ID + i);
                } catch (ReflectiveOperationException e) {
                    LagFinder.LOG.warn("LagFinder could not delete a chat line", e);
                    break;
                }
            }
        }
        printed = 0;
    }

    public void add(IChatComponent line) {
        GuiNewChat chat = chat();
        if (chat == null) {
            return;
        }
        if (print != null) {
            try {
                print.invoke(chat, line, FIRST_ID + Math.min(printed, MAX_LINES - 1));
                printed = Math.min(printed + 1, MAX_LINES);
                return;
            } catch (ReflectiveOperationException e) {
                LagFinder.LOG.warn("LagFinder could not print a chat line", e);
            }
        }
        chat.printChatMessage(line);
    }

    public void show(ChatOut out) {
        begin();
        append(out);
    }

    public void append(ChatOut out) {
        for (IChatComponent line : out.lines()) {
            add(line);
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        String mark = ChatOut.markOf(event.message);
        if (mark == null) {
            return;
        }
        event.setCanceled(true);
        if (ChatOut.MARK_START.equals(mark)) {
            begin();
        }
        add(event.message);
    }

    private static GuiNewChat chat() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.ingameGUI == null ? null : mc.ingameGUI.getChatGUI();
    }

    private static Method find(String[] names, Class<?>... parameters) {
        for (String name : names) {
            try {
                Method method = GuiNewChat.class.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        LagFinder.LOG.warn("LagFinder found no GuiNewChat.{}: earlier stats stay in the chat", names[1]);
        return null;
    }
}
