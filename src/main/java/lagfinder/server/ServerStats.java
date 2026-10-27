package lagfinder.server;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import lagfinder.core.Timeline;
import lagfinder.stats.CpuWatch;
import lagfinder.stats.StatReports;

public final class ServerStats {

    public static final ServerStats INSTANCE = new ServerStats();

    private static final long GAP_NANOS = 1_000_000_000L;

    public final Timeline ticks = new Timeline();
    public final Timeline memory = new Timeline();
    public final CpuWatch cpu = new CpuWatch();
    private long lastTickEnd;
    private final Set<UUID> withMod = ConcurrentHashMap.newKeySet();

    private ServerStats() {}

    public void tick(long start, long end) {
        ticks.add(end, (end - start) / 1e6);
        ticks.cover(lastTickEnd != 0 && start - lastTickEnd < GAP_NANOS ? lastTickEnd : start, end);
        lastTickEnd = end;
        memory.add(end, StatReports.usedMb());
        cpu.sample(end);
    }

    public void reset() {
        ticks.clear();
        memory.clear();
        cpu.clear();
        lastTickEnd = 0;
        withMod.clear();
    }

    public void markWithMod(EntityPlayerMP player) {
        withMod.add(player.getUniqueID());
    }

    public boolean hasMod(EntityPlayerMP player) {
        return withMod.contains(player.getUniqueID());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        withMod.remove(event.player.getUniqueID());
    }
}
