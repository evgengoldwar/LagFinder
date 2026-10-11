package lagfinder.mixins.early.client;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.client.ClientScanner;

/**
 * Times what a frame spends waiting rather than working: the frame limiter's sleep, and with vsync on the wait for
 * the screen as the frame is shown. A frame held to a limit gains nothing from less work while it waits anyway: the
 * report's cost of things in frames a second counts only the frame's work.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @WrapOperation(
        method = "runGameLoop",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;sync(I)V", remap = false))
    private void lagfinder$timeLimiter(int fps, Operation<Void> original) {
        long start = System.nanoTime();
        original.call(fps);
        ClientScanner.INSTANCE.frameWaited(System.nanoTime() - start);
    }

    @WrapOperation(
        method = "runGameLoop",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;func_147120_f()V"))
    private void lagfinder$timeShow(Minecraft mc, Operation<Void> original) {
        long start = System.nanoTime();
        original.call(mc);
        if (mc.gameSettings.enableVsync) {
            // Showing the frame waits for the screen's refresh.
            ClientScanner.INSTANCE.frameWaited(System.nanoTime() - start);
        }
    }
}
