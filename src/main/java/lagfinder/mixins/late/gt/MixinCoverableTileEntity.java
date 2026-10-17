package lagfinder.mixins.late.gt;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import gregtech.api.metatileentity.CoverableTileEntity;
import gregtech.common.covers.Cover;
import lagfinder.core.ProfileSession;

/**
 * Times each GregTech cover's work (conveyors, pumps, robot arms, regulators...) by the block and side it is on. It
 * runs inside the tile's update, so it is left out of the time of the machine or pipe it sits on.
 */
@Mixin(value = CoverableTileEntity.class, remap = false)
public abstract class MixinCoverableTileEntity {

    @WrapOperation(
        method = "tickCoverAtSide",
        at = @At(value = "INVOKE", target = "Lgregtech/common/covers/Cover;doCoverThings(BJ)V"),
        require = 0)
    private void lagfinder$timeCover(Cover cover, byte redstone, long tickTimer, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(cover, redstone, tickTimer);
            return;
        }
        ForgeDirection side = cover.getSide();
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(cover, redstone, tickTimer);
        } finally {
            session.recordCover((TileEntity) (Object) this, side != null ? side.ordinal() : -1, cover, start, outer);
        }
    }
}
