package lagfinder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import lagfinder.client.gui.GuiLagFinder;

public final class KeyHandler {

    public static final KeyBinding OPEN = new KeyBinding(
        "key.lagfinder.open",
        Keyboard.KEY_L,
        "key.categories.lagfinder");

    public static final KeyBinding WORLD_MS = new KeyBinding(
        "key.lagfinder.world_ms",
        Keyboard.KEY_NUMPAD0,
        "key.categories.lagfinder");

    public static void register() {
        ClientRegistry.registerKeyBinding(OPEN);
        ClientRegistry.registerKeyBinding(WORLD_MS);
    }

    public static String keyName() {
        return GameSettings.getKeyDisplayString(OPEN.getKeyCode());
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null) {
            return;
        }
        if (OPEN.isPressed() && (!ClientScanner.INSTANCE.serverHasMod() || ClientScanner.INSTANCE.serverAllowed())) {
            mc.displayGuiScreen(GuiLagFinder.toOpen());
        }
        if (WORLD_MS.isPressed()
            && (!ClientScanner.INSTANCE.serverHasMod() || ClientScanner.INSTANCE.serverAllowed())) {
            WorldMsOverlay.INSTANCE.toggle();
        }
    }
}
