package lagfinder.mixins.early;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.Category;
import lagfinder.core.ProfileSession;
import lagfinder.core.WorldTask;

/**
 * Times each tile entity and entity update of a world's tick, on the server and in the client's own world: their own
 * time, without what is measured inside them (the events they post, the riders they carry). Weather effects
 * (lightning) too, which are updated apart.
 */
@Mixin(World.class)
public abstract class MixinWorld {

    @WrapOperation(
        method = "updateEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;updateEntity()V"))
    private void lagfinder$timeTileEntity(TileEntity tile, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(tile);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(tile);
        } finally {
            session.recordTile(tile, start, outer);
        }
    }

    @WrapOperation(
        method = "updateEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;updateEntity(Lnet/minecraft/entity/Entity;)V"))
    private void lagfinder$timeEntity(World world, Entity entity, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(world, entity);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(world, entity);
        } finally {
            session.record(Category.ENTITIES, entity, start, outer);
        }
    }

    /** Weather effects (lightning) are updated on their own, not through updateEntity. */
    @WrapOperation(
        method = "updateEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;onUpdate()V"))
    private void lagfinder$timeWeather(Entity entity, Operation<Void> original) {
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

    /**
     * A rider is updated inside its mount's update: timed as itself, and left out of the mount's own time (the time
     * measured inside a thing is its inner thing's).
     */
    @WrapOperation(
        method = "updateEntityWithOptionalForce",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;updateEntity(Lnet/minecraft/entity/Entity;)V"))
    private void lagfinder$timeRider(World world, Entity rider, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(world, rider);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(world, rider);
        } finally {
            session.record(Category.ENTITIES, rider, start, outer);
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;updateWeather()V"))
    private void lagfinder$timeWeather(World world, Operation<Void> original) {
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
            session.recordTask(world, WorldTask.WEATHER, start, outer);
        }
    }

    @WrapOperation(
        method = "notifyBlockOfNeighborChange",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/block/Block;onNeighborBlockChange(Lnet/minecraft/world/World;IIILnet/minecraft/block/Block;)V"))
    private void lagfinder$timeNeighborUpdate(Block block, World world, int x, int y, int z, Block neighbor,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(block, world, x, y, z, neighbor);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(block, world, x, y, z, neighbor);
        } finally {
            session.recordNeighborUpdate(world, block, x, y, z, start, outer);
        }
    }

    @Inject(method = { "updateLightByType", "func_147463_c" }, at = @At("HEAD"))
    private void lagfinder$lightStarts(EnumSkyBlock type, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        ProfileSession session = ProfileSession.current();
        if (session != null) {
            session.spanBegin(ProfileSession.SPAN_LIGHT);
        }
    }

    @Inject(method = { "updateLightByType", "func_147463_c" }, at = @At("RETURN"))
    private void lagfinder$lightEnds(EnumSkyBlock type, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        ProfileSession session = ProfileSession.current();
        if (session != null) {
            session.spanEndLight((World) (Object) this, type, x, y, z);
        }
    }

    @Inject(method = "getPathEntityToEntity", at = @At("HEAD"))
    private void lagfinder$pathToEntityStarts(Entity entity, Entity target, float range, boolean openDoors,
        boolean closedDoors, boolean avoidWater, boolean swim, CallbackInfoReturnable<PathEntity> cir) {
        ProfileSession session = ProfileSession.current();
        if (session != null) {
            session.spanBegin(ProfileSession.SPAN_PATH);
        }
    }

    @Inject(method = "getPathEntityToEntity", at = @At("RETURN"))
    private void lagfinder$pathToEntityEnds(Entity entity, Entity target, float range, boolean openDoors,
        boolean closedDoors, boolean avoidWater, boolean swim, CallbackInfoReturnable<PathEntity> cir) {
        ProfileSession session = ProfileSession.current();
        if (session != null) {
            session.spanEndPath(entity);
        }
    }

    @Inject(method = "getEntityPathToXYZ", at = @At("HEAD"))
    private void lagfinder$pathToPlaceStarts(Entity entity, int x, int y, int z, float range, boolean openDoors,
        boolean closedDoors, boolean avoidWater, boolean swim, CallbackInfoReturnable<PathEntity> cir) {
        ProfileSession session = ProfileSession.current();
        if (session != null) {
            session.spanBegin(ProfileSession.SPAN_PATH);
        }
    }

    @Inject(method = "getEntityPathToXYZ", at = @At("RETURN"))
    private void lagfinder$pathToPlaceEnds(Entity entity, int x, int y, int z, float range, boolean openDoors,
        boolean closedDoors, boolean avoidWater, boolean swim, CallbackInfoReturnable<PathEntity> cir) {
        ProfileSession session = ProfileSession.current();
        if (session != null) {
            session.spanEndPath(entity);
        }
    }
}
