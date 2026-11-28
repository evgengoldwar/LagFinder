package lagfinder.client;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import lagfinder.Config;
import lagfinder.network.LiveLagPackets;
import lagfinder.network.Net;

public final class LiveLagOverlay {

    public static final LiveLagOverlay INSTANCE = new LiveLagOverlay();

    private static final long VISIBLE_NANOS = 5_000_000_000L;
    private static final int COLOR = 0xFF5C5C;

    private final Map<LiveLagPackets.Hit, Long> marks = new LinkedHashMap<>();
    private World world;
    private int subscription;
    private int ticksToRenew;
    private boolean subscribed;

    private LiveLagOverlay() {}

    public void setEnabled(boolean enabled) {
        Config.showLiveLag = enabled;
        Config.save();
        updateSubscription();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            updateSubscription();
            long now = System.nanoTime();
            marks.values()
                .removeIf(expires -> now - expires >= 0);
        }
    }

    private void updateSubscription() {
        World current = Minecraft.getMinecraft().theWorld;
        boolean available = ClientScanner.INSTANCE.liveLagAvailable();
        boolean wanted = Config.showLiveLag && available && current != null;
        if (wanted != subscribed || world != current) {
            marks.clear();
            world = current;
            subscription++;
            ticksToRenew = 0;
            if (subscribed && !wanted && available) {
                Net.sendToServer(new LiveLagPackets.Subscription(subscription, false));
            }
            subscribed = wanted;
        }
        if (subscribed && ticksToRenew-- <= 0) {
            Net.sendToServer(new LiveLagPackets.Subscription(subscription, true));
            ticksToRenew = 39;
        }
    }

    public void accept(LiveLagPackets.Batch batch) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!Config.showLiveLag || !subscribed || !ClientScanner.INSTANCE.liveLagAvailable()
            || world == null || world != mc.theWorld || mc.thePlayer == null || batch.id != subscription
            || world.provider.dimensionId != batch.dimension) {
            return;
        }
        long expires = System.nanoTime() + VISIBLE_NANOS;
        for (LiveLagPackets.Hit hit : batch.hits) {
            marks.remove(hit);
            marks.put(hit, expires);
        }
    }

    boolean hasMarks(Minecraft mc) {
        return Config.showLiveLag && world == mc.theWorld && mc.thePlayer != null && !marks.isEmpty();
    }

    void drawMarks(Minecraft mc, float partialTicks, double pulse) {
        if (!hasMarks(mc)) {
            return;
        }
        long now = System.nanoTime();
        for (Map.Entry<LiveLagPackets.Hit, Long> mark : marks.entrySet()) {
            if (now - mark.getValue() >= 0) {
                continue;
            }
            LiveLagPackets.Hit hit = mark.getKey();
            double[] box;
            if (hit.entityId == LiveLagPackets.BLOCK) {
                if (!world.getChunkProvider().chunkExists(hit.x >> 4, hit.z >> 4)
                    || world.getBlock(hit.x, hit.y, hit.z) == Blocks.air) {
                    continue;
                }
                box = Highlighter.blockBox(hit.x, hit.y, hit.z);
            } else {
                Entity entity = world.getEntityByID(hit.entityId);
                if (entity == mc.thePlayer || (entity != null && entity.isDead)) {
                    continue;
                }
                if (entity == null && !world.getChunkProvider().chunkExists(hit.x >> 4, hit.z >> 4)) {
                    continue;
                }
                box = entity == null ? Highlighter.entityFallbackBox(hit.x, hit.y, hit.z)
                    : Highlighter.entityBox(entity, partialTicks);
            }
            Highlighter.drawHighlight(box, COLOR, false, pulse);
        }
    }
}
