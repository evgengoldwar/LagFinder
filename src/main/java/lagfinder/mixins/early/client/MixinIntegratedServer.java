package lagfinder.mixins.early.client;

import net.minecraft.server.integrated.IntegratedServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import lagfinder.server.ServerScanner;

/**
 * Marks each round of the single player world's server: it skips the server's tick while the game is paused, and a
 * tick scan leaves the pause out instead of counting it as ticks that never came.
 */
@Mixin(IntegratedServer.class)
public abstract class MixinIntegratedServer {

    @Inject(method = "tick", at = @At("HEAD"))
    private void lagfinder$roundStarts(CallbackInfo ci) {
        ServerScanner.INSTANCE.roundStarts();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void lagfinder$roundEnds(CallbackInfo ci) {
        ServerScanner.INSTANCE.roundEnds();
    }
}
