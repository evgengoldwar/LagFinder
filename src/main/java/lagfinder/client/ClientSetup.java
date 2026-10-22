package lagfinder.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import lagfinder.network.Net;

/** Puts the client's part in place: the key, the HUD, the highlights, and what is done with the server's messages. */
public final class ClientSetup {

    private ClientSetup() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(KeyHandler::register);
        NeoForge.EVENT_BUS.addListener(KeyHandler::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, event -> ClientScanner.INSTANCE.onClientTick());
        NeoForge.EVENT_BUS.addListener(
            ClientPlayerNetworkEvent.LoggingIn.class,
            event -> ClientScanner.INSTANCE.onConnect());
        NeoForge.EVENT_BUS.addListener(
            ClientPlayerNetworkEvent.LoggingOut.class,
            event -> ClientScanner.INSTANCE.onDisconnect());
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, HudOverlay.INSTANCE::onOverlay);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, Highlighter.INSTANCE::onRenderLevel);
        Net.onStatus = ClientScanner.INSTANCE::onStatus;
        Net.onReportPart = ClientScanner.INSTANCE::onReportPart;
    }
}
