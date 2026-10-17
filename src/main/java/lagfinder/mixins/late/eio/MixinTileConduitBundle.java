package lagfinder.mixins.late.eio;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import crazypants.enderio.conduit.IConduit;
import lagfinder.core.ProfileSession;

@Mixin(targets = "crazypants.enderio.conduit.TileConduitBundle", remap = false)
public abstract class MixinTileConduitBundle {

    @WrapOperation(
        method = "doUpdate",
        at = @At(
            value = "INVOKE",
            target = "Lcrazypants/enderio/conduit/IConduit;updateEntity(Lnet/minecraft/world/World;)V"),
        require = 0)
    private void lagfinder$timeConduit(IConduit conduit, World world, Operation<Void> original) {
        ProfileSession session = ProfileSession.currentServer();
        if (session == null || world == null || world.isRemote) {
            original.call(conduit, world);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(conduit, world);
        } finally {
            session.recordPipe(ProfileSession.PIPE_EIO_CONDUIT, conduit, (TileEntity) (Object) this, start, outer);
        }
    }
}
