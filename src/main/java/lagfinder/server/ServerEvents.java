package lagfinder.server;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;

/** The server's events LagFinder follows: its start and stop, and the ticks where their mixins didn't apply. */
public final class ServerEvents {

    private ServerEvents() {}

    @SubscribeEvent
    public static void onGatherPermissions(PermissionGatherEvent.Nodes event) {
        event.addNodes(Permissions.SCAN, Permissions.TELEPORT);
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        ServerScanner.INSTANCE.reset();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerScanner.INSTANCE.reset();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTickStart(ServerTickEvent.Pre event) {
        ServerScanner.INSTANCE.tickEventStarts();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTickEnd(ServerTickEvent.Post event) {
        ServerScanner.INSTANCE.tickEventEnds();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLevelTickStart(LevelTickEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ServerScanner.INSTANCE.worldEventStarts(level);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLevelTickEnd(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ServerScanner.INSTANCE.worldEventEnds(level);
        }
    }
}
