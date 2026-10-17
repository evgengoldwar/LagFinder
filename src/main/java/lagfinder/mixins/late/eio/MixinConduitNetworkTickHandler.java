package lagfinder.mixins.late.eio;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import crazypants.enderio.conduit.AbstractConduitNetwork;
import lagfinder.core.ProfileSession;

@Mixin(targets = "crazypants.enderio.conduit.ConduitNetworkTickHandler", remap = false)
public abstract class MixinConduitNetworkTickHandler {

    @WrapOperation(
        method = "tickEnd",
        at = @At(value = "INVOKE", target = "Lcrazypants/enderio/conduit/AbstractConduitNetwork;doNetworkTick()V"),
        require = 0)
    private void lagfinder$timeNetwork(AbstractConduitNetwork<?, ?> network, Operation<Void> original) {
        ProfileSession session = ProfileSession.currentServer();
        if (session == null) {
            original.call(network);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(network);
        } finally {
            session.recordPipe(ProfileSession.PIPE_EIO_NETWORK, network, null, start, outer);
        }
    }
}
