package lagfinder.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import lagfinder.Config;
import lagfinder.network.LiveLagPackets;
import lagfinder.network.Net;

public final class LiveLagTracker {

    public static final LiveLagTracker INSTANCE = new LiveLagTracker();

    private static final long THRESHOLD_NANOS = 100_000;
    private static final int SUBSCRIPTION_TICKS = 120;

    private final Map<EntityPlayerMP, LiveLagPackets.Subscription> pending = new ConcurrentHashMap<>();
    private final Map<EntityPlayerMP, Watch> watches = new HashMap<>();
    private volatile Thread recordingThread;
    private int tick;

    private LiveLagTracker() {}

    private static final class Watch {

        final int id;
        final int expires;
        World world;
        List<LiveLagPackets.Hit> hits = new ArrayList<>();

        Watch(int id, int expires) {
            this.id = id;
            this.expires = expires;
        }
    }

    public static boolean isRecording() {
        return INSTANCE.recordingThread == Thread.currentThread();
    }

    public void request(EntityPlayerMP player, LiveLagPackets.Subscription message) {
        pending.put(player, message);
    }

    public void reset() {
        recordingThread = null;
        pending.clear();
        watches.clear();
        tick = 0;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            tick++;
            pending.forEach((player, request) -> {
                if (!pending.remove(player, request)) {
                    return;
                }
                if (request.enabled && ServerScanner.isOnline(player) && ServerScanner.canScan(player)) {
                    watches.put(player, new Watch(request.id, tick + SUBSCRIPTION_TICKS));
                } else {
                    watches.remove(player);
                }
            });
            watches.entrySet().removeIf(entry -> tick - entry.getValue().expires >= 0
                || !ServerScanner.isOnline(entry.getKey()) || !ServerScanner.canScan(entry.getKey()));
            recordingThread = watches.isEmpty() ? null : Thread.currentThread();
        } else {
            recordingThread = null;
            Iterator<Map.Entry<EntityPlayerMP, Watch>> entries = watches.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<EntityPlayerMP, Watch> entry = entries.next();
                EntityPlayerMP player = entry.getKey();
                Watch watch = entry.getValue();
                if (!watch.hits.isEmpty()) {
                    if (!ServerScanner.isOnline(player) || !ServerScanner.canScan(player)) {
                        entries.remove();
                        continue;
                    }
                    if (watch.world == player.worldObj) {
                        Net.sendTo(new LiveLagPackets.Batch(watch.id, player.dimension, watch.hits), player);
                    }
                    watch.hits = new ArrayList<>();
                }
            }
        }
    }

    public void entity(World world, Entity entity, long elapsed) {
        if (elapsed > THRESHOLD_NANOS && entity.worldObj == world) {
            record(
                world,
                MathHelper.floor_double(entity.posX),
                MathHelper.floor_double(entity.posY),
                MathHelper.floor_double(entity.posZ),
                entity.getEntityId());
        }
    }

    public void block(World world, int x, int y, int z, long elapsed) {
        if (elapsed > THRESHOLD_NANOS) {
            record(world, x, y, z, LiveLagPackets.BLOCK);
        }
    }

    private void record(World world, int x, int y, int z, int entityId) {
        for (Map.Entry<EntityPlayerMP, Watch> entry : watches.entrySet()) {
            EntityPlayerMP player = entry.getKey();
            if (player.worldObj != world
                || player.getDistanceSq(x + 0.5, y + 0.5, z + 0.5) > Config.worldMsRadius * Config.worldMsRadius) {
                continue;
            }
            Watch watch = entry.getValue();
            if (watch.world != world) {
                watch.hits.clear();
                watch.world = world;
            }
            if (watch.hits.size() < LiveLagPackets.MAX_HITS && !contains(watch.hits, x, y, z, entityId)) {
                watch.hits.add(new LiveLagPackets.Hit(x, y, z, entityId));
            }
        }
    }

    private static boolean contains(List<LiveLagPackets.Hit> hits, int x, int y, int z, int entityId) {
        for (LiveLagPackets.Hit hit : hits) {
            if (hit.entityId == entityId && (entityId != LiveLagPackets.BLOCK
                || (hit.x == x && hit.y == y && hit.z == z))) {
                return true;
            }
        }
        return false;
    }
}
