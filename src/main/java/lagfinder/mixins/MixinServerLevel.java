package lagfinder.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.Category;
import lagfinder.core.ProfileSession;
import lagfinder.core.WorldTask;

/**
 * Times each entity of a world's tick (riders apart from what they ride), its scheduled and random block and fluid
 * ticks per block and chunk, and the weather and raids.
 */
@Mixin(ServerLevel.class)
public abstract class MixinServerLevel {

    @WrapMethod(method = "tickNonPassenger")
    private void lagfinder$timeEntity(Entity entity, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(entity);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(entity);
        } finally {
            session.record(Category.ENTITIES, entity, start, outer);
        }
    }

    @WrapOperation(
        method = "tickPassenger",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V"))
    private void lagfinder$timeRider(Entity rider, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(rider);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(rider);
        } finally {
            session.record(Category.ENTITIES, rider, start, outer);
        }
    }

    @WrapOperation(
        method = "tickBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void lagfinder$timeBlockTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(state, level, pos, random);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(state, level, pos, random);
        } finally {
            session.recordBlockTick(level, state.getBlock(), pos, start, outer);
        }
    }

    @WrapOperation(
        method = "tickFluid",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void lagfinder$timeFluidTick(FluidState state, Level level, BlockPos pos, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(state, level, pos);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(state, level, pos);
        } finally {
            session.recordBlockTick(level, state.getType(), pos, start, outer);
        }
    }

    @WrapOperation(
        method = "tickChunk",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void lagfinder$timeRandomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(state, level, pos, random);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(state, level, pos, random);
        } finally {
            session.recordBlockTick(level, state.getBlock(), pos, start, outer);
        }
    }

    @WrapOperation(
        method = "tickChunk",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;randomTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void lagfinder$timeRandomFluidTick(FluidState state, Level level, BlockPos pos, RandomSource random,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(state, level, pos, random);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(state, level, pos, random);
        } finally {
            session.recordBlockTick(level, state.getType(), pos, start, outer);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;advanceWeatherCycle()V"))
    private void lagfinder$timeWeather(ServerLevel level, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(level);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(level);
        } finally {
            session.recordTask(level, WorldTask.WEATHER, start, outer);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/raid/Raids;tick()V"))
    private void lagfinder$timeRaids(Raids raids, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(raids);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(raids);
        } finally {
            session.recordTask((ServerLevel) (Object) this, WorldTask.VILLAGES, start, outer);
        }
    }
}
