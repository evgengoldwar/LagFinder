package lagfinder.client;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import lagfinder.core.Timeline;
import lagfinder.stats.CpuWatch;
import lagfinder.stats.StatReports;

public final class ClientStats {

    public static final ClientStats INSTANCE = new ClientStats();

    private static final long GAP_NANOS = 5_000_000_000L;

    public final Timeline frames = new Timeline();
    public final Timeline memory = new Timeline();
    public final CpuWatch cpu = new CpuWatch();
    private long lastFrame;

    private ClientStats() {}

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrame != 0 && now - lastFrame < GAP_NANOS) {
            frames.add(now, (now - lastFrame) / 1e6);
            frames.cover(lastFrame, now);
        }
        lastFrame = now;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            long now = System.nanoTime();
            memory.add(now, StatReports.usedMb());
            cpu.sample(now);
        }
    }
}
