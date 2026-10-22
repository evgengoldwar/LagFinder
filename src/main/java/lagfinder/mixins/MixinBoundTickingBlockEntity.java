package lagfinder.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.ProfileSession;

/** Times each block entity's tick: its own time, without what is measured inside it (the events it posts). */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class MixinBoundTickingBlockEntity {

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private void lagfinder$timeBlockEntity(BlockEntityTicker<BlockEntity> ticker, Level level, BlockPos pos,
        BlockState state, BlockEntity tile, Operation<Void> original) {
        ProfileSession session = level.isClientSide() ? null : ProfileSession.current();
        if (session == null) {
            original.call(ticker, level, pos, state, tile);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(ticker, level, pos, state, tile);
        } finally {
            session.recordTile(tile, start, outer);
        }
    }
}
