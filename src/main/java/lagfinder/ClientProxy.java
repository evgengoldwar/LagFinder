package lagfinder;

import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import lagfinder.client.ClientReports;
import lagfinder.client.ClientScanner;
import lagfinder.client.ClientStats;
import lagfinder.client.ClientStatsCommand;
import lagfinder.client.Highlighter;
import lagfinder.client.HudOverlay;
import lagfinder.client.StatChat;
import lagfinder.client.KeyHandler;
import lagfinder.client.WorldMsOverlay;
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
        FMLCommonHandler.instance()
            .bus()
            .register(ClientStats.INSTANCE);
        ClientCommandHandler.instance.registerCommand(new ClientStatsCommand());
        MinecraftForge.EVENT_BUS.register(StatChat.INSTANCE);
        MinecraftForge.EVENT_BUS.register(Highlighter.INSTANCE);
        MinecraftForge.EVENT_BUS.register(HudOverlay.INSTANCE);
        MinecraftForge.EVENT_BUS.register(WorldMsOverlay.INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(WorldMsOverlay.INSTANCE);
    }

    @Override
    public void onStatus(Net.Status status) {
        ClientScanner.INSTANCE.onStatus(status);
    }

    @Override
    public void onReportPart(Net.ReportPart part) {
        ClientScanner.INSTANCE.onReportPart(part);
    }

    @Override
    public void onArchiveCatalog(Net.ArchiveCatalog catalog) {
        ClientReports.INSTANCE.onCatalog(catalog);
    }

    @Override
    public void onArchivePart(Net.ArchivePart part) {
        ClientReports.INSTANCE.onPart(part.part);
    }
}
