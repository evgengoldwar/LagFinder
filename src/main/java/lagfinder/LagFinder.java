package lagfinder;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

import lagfinder.client.ClientSetup;
import lagfinder.network.Net;
import lagfinder.server.ServerEvents;

@Mod(LagFinder.MODID)
public final class LagFinder {

    public static final String MODID = "lagfinder";
    public static final String NAME = "LagFinder";
    public static final Logger LOG = LogUtils.getLogger();
    public static String version = "";

    public LagFinder(IEventBus modBus, ModContainer container) {
        version = container.getModInfo()
            .getVersion()
            .toString();
        Config.register(container, modBus);
        modBus.addListener(Net::register);
        NeoForge.EVENT_BUS.register(ServerEvents.class);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.init(modBus);
        }
    }
}
