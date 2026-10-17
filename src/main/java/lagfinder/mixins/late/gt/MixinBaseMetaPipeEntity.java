package lagfinder.mixins.late.gt;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaPipeEntity;
import gregtech.api.metatileentity.MetaPipeEntity;
import lagfinder.compat.GregTechPipes;
import lagfinder.core.ProfileSession;

@Mixin(value = BaseMetaPipeEntity.class, remap = false)
public abstract class MixinBaseMetaPipeEntity {

    @WrapOperation(
        method = "updateEntityProfiled",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/metatileentity/MetaPipeEntity;onPostTick(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;J)V"),
        require = 0)
    private void lagfinder$timePipe(MetaPipeEntity meta, IGregTechTileEntity base, long tick,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.currentServer();
        if (session == null || GregTechPipes.family(meta) < 0) {
            original.call(meta, base, tick);
            return;
        }
        TileEntity pipe = (TileEntity) (Object) this;
        TileEntity previous = GregTechPipes.beginTick(pipe);
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(meta, base, tick);
        } finally {
            session.recordPipe(ProfileSession.PIPE_GT_TICK, pipe, null, start, outer);
            GregTechPipes.endTick(previous);
        }
    }

    @WrapOperation(
        method = "injectEnergyUnits",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/metatileentity/MetaPipeEntity;injectEnergyUnits(Lnet/minecraftforge/common/util/ForgeDirection;JJ)J"),
        require = 0)
    private long lagfinder$timeEnergy(MetaPipeEntity meta, ForgeDirection side, long voltage, long amperage,
        Operation<Long> original) {
        ProfileSession session = ProfileSession.currentServer();
        if (session == null || side == null
            || side == ForgeDirection.UNKNOWN
            || GregTechPipes.family(meta) != GregTechPipes.CABLE) {
            return original.call(meta, side, voltage, amperage);
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            return original.call(meta, side, voltage, amperage);
        } finally {
            session.recordPipeSide((TileEntity) (Object) this, side.ordinal(), start, outer);
        }
    }
}
