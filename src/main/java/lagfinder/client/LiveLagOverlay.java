package lagfinder.client;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import lagfinder.Config;
import lagfinder.network.LiveLagPackets;
import lagfinder.network.Net;

public final class LiveLagOverlay {

    public static final LiveLagOverlay INSTANCE = new LiveLagOverlay();

    private static final long VISIBLE_NANOS = 1_000_000_000L;
    private static final int MAX_MARKS = 128;
    private static final int COLOR = 0xFF6854;

    private final Map<Object, Long> marks = new LinkedHashMap<>();
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
        double radiusSquared = Config.worldMsRadius * Config.worldMsRadius;
        for (LiveLagPackets.Hit hit : batch.hits) {
            if (mc.thePlayer.getDistanceSq(hit.x + 0.5, hit.y + 0.5, hit.z + 0.5) > radiusSquared) {
                continue;
            }
            Object key = hit.entityId == LiveLagPackets.BLOCK ? new ChunkCoordinates(hit.x, hit.y, hit.z)
                : Integer.valueOf(hit.entityId);
            marks.remove(key);
            marks.put(key, expires);
            if (marks.size() > MAX_MARKS) {
                marks.remove(marks.keySet()
                    .iterator()
                    .next());
            }
        }
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (marks.isEmpty() || world != mc.theWorld || mc.thePlayer == null || mc.gameSettings.hideGUI) {
            return;
        }
        double radiusSquared = Config.worldMsRadius * Config.worldMsRadius;
        long now = System.nanoTime();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LINE_BIT | GL11.GL_DEPTH_BUFFER_BIT
            | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glLineWidth(2f);
            for (Map.Entry<Object, Long> mark : marks.entrySet()) {
                if (now - mark.getValue() >= 0) {
                    continue;
                }
                Object key = mark.getKey();
                double[] box;
                if (key instanceof ChunkCoordinates) {
                    ChunkCoordinates pos = (ChunkCoordinates) key;
                    int x = pos.posX, y = pos.posY, z = pos.posZ;
                    if (mc.thePlayer.getDistanceSq(x + 0.5, y + 0.5, z + 0.5) > radiusSquared
                        || !world.getChunkProvider()
                            .chunkExists(x >> 4, z >> 4)
                        || world.getBlock(x, y, z) == Blocks.air) {
                        continue;
                    }
                    box = new double[] { x - 0.02, y - 0.02, z - 0.02, x + 1.02, y + 1.02, z + 1.02 };
                } else {
                    Entity entity = world.getEntityByID((Integer) key);
                    if (entity == null || entity == mc.thePlayer || entity.isDead
                        || mc.thePlayer.getDistanceSqToEntity(entity) > radiusSquared) {
                        continue;
                    }
                    box = Highlighter.entityBox(entity, event.partialTicks);
                }
                Highlighter.fillBox(box, COLOR, 35);
                Highlighter.outlineBox(box, COLOR, 240);
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }
}
