package lagfinder;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import lagfinder.network.Net;
import lagfinder.server.ServerScanner;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
    }

    public void init(FMLInitializationEvent event) {
        Net.register();
        FMLCommonHandler.instance()
            .bus()
            .register(ServerScanner.INSTANCE);
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        ServerScanner.INSTANCE.reset();
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        ServerScanner.INSTANCE.reset();
    }

    /** The server's state arrived (network thread); only the client cares. */
    public void onStatus(Net.Status status) {}

    /** A part of a report arrived (network thread); only the client cares. */
    public void onReportPart(Net.ReportPart part) {}

    public void onArchiveCatalog(Net.ArchiveCatalog catalog) {}

    public void onArchivePart(Net.ArchivePart part) {}
}
