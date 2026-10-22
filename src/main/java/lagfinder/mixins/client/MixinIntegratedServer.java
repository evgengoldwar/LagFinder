package lagfinder.mixins.client;

import java.util.function.BooleanSupplier;

import net.minecraft.client.server.IntegratedServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import lagfinder.server.ServerScanner;

/** Marks each round of the single player world's server: it runs the server's tick only while the game isn't paused. */
@Mixin(IntegratedServer.class)
public abstract class MixinIntegratedServer {

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void lagfinder$roundStarts(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerScanner.INSTANCE.roundStarts();
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void lagfinder$roundEnds(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerScanner.INSTANCE.roundEnds();
    }
}
