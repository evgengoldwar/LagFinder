package lagfinder.mixins.late.gt;

import java.util.Map;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import gregtech.api.interfaces.metatileentity.IMetaTileEntityItemPipe;
import gregtech.api.metatileentity.implementations.MTEItemPipe;
import lagfinder.compat.GregTechPipes;
import lagfinder.core.ProfileSession;

@Mixin(value = MTEItemPipe.class, remap = false)
public abstract class MixinMTEItemPipe {

    @WrapOperation(
        method = "onPostTick",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/interfaces/metatileentity/IMetaTileEntityItemPipe$Util;scanPipes(Lgregtech/api/interfaces/metatileentity/IMetaTileEntityItemPipe;Ljava/util/Map;JZZ)Ljava/util/Map;"),
        require = 0)
    private Map<IMetaTileEntityItemPipe, Long> lagfinder$timeRouteSearch(IMetaTileEntityItemPipe pipe,
        Map<IMetaTileEntityItemPipe, Long> map, long step, boolean suck, boolean ignoreCapacity,
        Operation<Map<IMetaTileEntityItemPipe, Long>> original) {
        ProfileSession session = ProfileSession.currentServer();
        TileEntity tile = GregTechPipes.ticking();
        if (session == null || tile == null) {
            return original.call(pipe, map, step, suck, ignoreCapacity);
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            return original.call(pipe, map, step, suck, ignoreCapacity);
        } finally {
            session.recordPipe(ProfileSession.PIPE_GT_ROUTE, tile, null, start, outer);
        }
    }
}
