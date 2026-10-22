package lagfinder.mixins;

import java.util.function.BooleanSupplier;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.Category;
import lagfinder.core.ProfileSession;
import lagfinder.core.ServerPart;
import lagfinder.core.WorldTask;

/**
 * Times a world's chunk work: tickets and loading levels, mob spawning, the sweep of random ticks over the chunks,
 * entity tracking, and unloading and saving.
 */
@Mixin(ServerChunkCache.class)
public abstract class MixinServerChunkCache {

    @Shadow
    @Final
    ServerLevel level;

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;runDistanceManagerUpdates()Z"))
    private boolean lagfinder$timeTickets(ServerChunkCache cache, Operation<Boolean> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            return original.call(cache);
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            return original.call(cache);
        } finally {
            session.recordTask(level, WorldTask.PLAYER_CHUNKS, start, outer);
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;tick()V"))
    private void lagfinder$timeTracking(ChunkMap map, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(map);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(map);
        } finally {
            session.record(Category.SERVER, ServerPart.ENTITY_TRACKING, start, outer);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void lagfinder$timeUnloading(ChunkMap map, BooleanSupplier hasTimeLeft, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(map, hasTimeLeft);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(map, hasTimeLeft);
        } finally {
            session.recordTask(level, WorldTask.CHUNK_UNLOADING, start, outer);
        }
    }

    @WrapOperation(
        method = "tickChunks",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZZ)V"))
    private void lagfinder$timeSpawning(ServerLevel world, LevelChunk chunk, NaturalSpawner.SpawnState state,
        boolean friendlies, boolean enemies, boolean persistent, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(world, chunk, state, friendlies, enemies, persistent);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(world, chunk, state, friendlies, enemies, persistent);
        } finally {
            session.recordTask(world, WorldTask.MOB_SPAWNING, start, outer);
        }
    }

    @WrapOperation(
        method = "tickChunks",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V"))
    private void lagfinder$timeChunkTick(ServerLevel world, LevelChunk chunk, int randomTickSpeed,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(world, chunk, randomTickSpeed);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(world, chunk, randomTickSpeed);
        } finally {
            session.recordTask(world, WorldTask.RANDOM_TICK_SWEEP, start, outer);
        }
    }

    @WrapOperation(
        method = "tickChunks",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickCustomSpawners(ZZ)V"))
    private void lagfinder$timeCustomSpawners(ServerLevel world, boolean enemies, boolean friendlies,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(world, enemies, friendlies);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(world, enemies, friendlies);
        } finally {
            session.recordTask(world, WorldTask.MOB_SPAWNING, start, outer);
        }
    }
}
