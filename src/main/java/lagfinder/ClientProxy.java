package lagfinder;

import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import lagfinder.client.ClientScanner;
import lagfinder.client.Highlighter;
import lagfinder.client.HudOverlay;
import lagfinder.client.KeyHandler;
import lagfinder.network.Net;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        KeyHandler.register();
        FMLCommonHandler.instance()
            .bus()
            .register(new KeyHandler());
        FMLCommonHandler.instance()
            .bus()
            .register(ClientScanner.INSTANCE);
        MinecraftForge.EVENT_BUS.register(Highlighter.INSTANCE);
        MinecraftForge.EVENT_BUS.register(HudOverlay.INSTANCE);
    }

    @Override
    public void onStatus(Net.Status status) {
        ClientScanner.INSTANCE.onStatus(status);
    }

    @Override
    public void onReportPart(Net.ReportPart part) {
        ClientScanner.INSTANCE.onReportPart(part);
    }
}
