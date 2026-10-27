package lagfinder.mixins.early;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.village.VillageCollection;
import net.minecraft.village.VillageSiege;
import net.minecraft.world.SpawnerAnimals;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.ProfileSession;
import lagfinder.core.WorldTask;

/** Times scheduled block ticks ({@code tickUpdates}) and random ones ({@code func_147456_g}). */
@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    @WrapOperation(
        method = { "tickUpdates", "func_147456_g" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/block/Block;updateTick(Lnet/minecraft/world/World;IIILjava/util/Random;)V"))
    private void lagfinder$timeBlockTick(Block block, World world, int x, int y, int z, Random random,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(block, world, x, y, z, random);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(block, world, x, y, z, random);
        } finally {
            session.recordBlockTick(world, block, x, y, z, start, outer);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/SpawnerAnimals;findChunksForSpawning(Lnet/minecraft/world/WorldServer;ZZZ)I"))
    private int lagfinder$timeMobSpawning(SpawnerAnimals spawner, WorldServer world, boolean hostile, boolean peaceful,
        boolean animals, Operation<Integer> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            return original.call(spawner, world, hostile, peaceful, animals);
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            return original.call(spawner, world, hostile, peaceful, animals);
        } finally {
            session.recordTask((World) (Object) this, WorldTask.MOB_SPAWNING, start, outer);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/IChunkProvider;unloadQueuedChunks()Z"))
    private boolean lagfinder$timeChunkUnloading(IChunkProvider provider, Operation<Boolean> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            return original.call(provider);
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            return original.call(provider);
        } finally {
            session.recordTask((World) (Object) this, WorldTask.CHUNK_UNLOADING, start, outer);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldServer;func_147456_g()V"))
    private void lagfinder$timeRandomTickSweep(WorldServer world, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(world);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(world);
        } finally {
            session.recordTask((World) (Object) this, WorldTask.RANDOM_TICK_SWEEP, start, outer);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/management/PlayerManager;updatePlayerInstances()V"))
    private void lagfinder$timePlayerChunks(PlayerManager manager, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(manager);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(manager);
        } finally {
            session.recordTask((World) (Object) this, WorldTask.PLAYER_CHUNKS, start, outer);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/village/VillageCollection;tick()V"))
    private void lagfinder$timeVillages(VillageCollection villages, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(villages);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(villages);
        } finally {
            session.recordTask((World) (Object) this, WorldTask.VILLAGES, start, outer);
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/village/VillageSiege;tick()V"))
    private void lagfinder$timeSieges(VillageSiege siege, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(siege);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(siege);
        } finally {
            session.recordTask((World) (Object) this, WorldTask.VILLAGES, start, outer);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Teleporter;removeStaleTeleportLocations(J)V"))
    private void lagfinder$timePortals(Teleporter teleporter, long time, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(teleporter, time);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(teleporter, time);
        } finally {
            session.recordTask((World) (Object) this, WorldTask.PORTALS, start, outer);
        }
    }
}
