package lagfinder.mixins.early;

import java.util.Hashtable;

import net.minecraft.entity.EntityTracker;
import net.minecraft.network.NetworkSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import cpw.mods.fml.common.FMLCommonHandler;
import lagfinder.core.Category;
import lagfinder.core.ProfileSession;
import lagfinder.core.ServerPart;
import lagfinder.server.ServerScanner;

/**
 * Marks where the server's tick, and each world's, starts and ends around all of it: the tick events a mod listens to
 * on the highest priority at the start (or the lowest at the end) may run before (after) LagFinder's own and be left
 * out otherwise. Times the server's own work outside the worlds too (entity tracking, the network, the autosave...).
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {

    @Inject(method = "tick", at = @At("HEAD"))
    private void lagfinder$tickStarts(CallbackInfo ci) {
        ServerScanner.INSTANCE.tickStarts();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void lagfinder$tickEnds(CallbackInfo ci) {
        ServerScanner.INSTANCE.tickEnds();
    }

    /** A world's tick starts before FML tells the mods, so all their handlers of it are in the world's time. */
    @WrapOperation(
        method = "updateTimeLightAndEntities",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/common/FMLCommonHandler;onPreWorldTick(Lnet/minecraft/world/World;)V",
            remap = false))
    private void lagfinder$worldStarts(FMLCommonHandler fml, World world, Operation<Void> original) {
        ServerScanner.INSTANCE.worldStarts(world);
        original.call(fml, world);
    }

    /** ...and ends after FML told them it ended. */
    @WrapOperation(
        method = "updateTimeLightAndEntities",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/common/FMLCommonHandler;onPostWorldTick(Lnet/minecraft/world/World;)V",
            remap = false))
    private void lagfinder$worldEnds(FMLCommonHandler fml, World world, Operation<Void> original) {
        original.call(fml, world);
        ServerScanner.INSTANCE.worldEnds(world);
    }

    @WrapOperation(
        method = "updateTimeLightAndEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityTracker;updateTrackedEntities()V"))
    private void lagfinder$timeTracking(EntityTracker tracker, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(tracker);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(tracker);
        } finally {
            session.record(Category.SERVER, ServerPart.ENTITY_TRACKING, start, outer);
        }
    }

    @WrapOperation(
        method = "updateTimeLightAndEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/NetworkSystem;networkTick()V"))
    private void lagfinder$timeNetwork(NetworkSystem network, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(network);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(network);
        } finally {
            session.record(Category.SERVER, ServerPart.NETWORK, start, outer);
        }
    }

    @WrapOperation(
        method = "updateTimeLightAndEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/management/ServerConfigurationManager;sendPlayerInfoToAllPlayers()V"))
    private void lagfinder$timePlayerList(ServerConfigurationManager players, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(players);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(players);
        } finally {
            session.record(Category.SERVER, ServerPart.PLAYER_LIST, start, outer);
        }
    }

    @WrapOperation(
        method = "updateTimeLightAndEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/common/DimensionManager;unloadWorlds(Ljava/util/Hashtable;)V",
            remap = false))
    private void lagfinder$timeUnloading(Hashtable<Integer, long[]> times, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(times);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(times);
        } finally {
            session.record(Category.SERVER, ServerPart.WORLD_UNLOADING, start, outer);
        }
    }

    @WrapOperation(
        method = "updateTimeLightAndEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/common/chunkio/ChunkIOExecutor;tick()V",
            remap = false))
    private void lagfinder$timeChunkLoading(Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call();
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call();
        } finally {
            session.record(Category.SERVER, ServerPart.CHUNK_LOADING, start, outer);
        }
    }

    /** The autosave every 900 ticks: the players' data... */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/management/ServerConfigurationManager;saveAllPlayerData()V"))
    private void lagfinder$timeSavePlayers(ServerConfigurationManager players, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(players);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(players);
        } finally {
            session.record(Category.SERVER, ServerPart.AUTOSAVE, start, outer);
        }
    }

    /** ...and the worlds. */
    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;saveAllWorlds(Z)V"))
    private void lagfinder$timeSaveWorlds(MinecraftServer server, boolean dontLog, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(server, dontLog);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(server, dontLog);
        } finally {
            session.record(Category.SERVER, ServerPart.AUTOSAVE, start, outer);
        }
    }
}
