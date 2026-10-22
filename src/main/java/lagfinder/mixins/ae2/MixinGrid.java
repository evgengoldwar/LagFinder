package lagfinder.mixins.ae2;

import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.networking.IGridServiceProvider;
import appeng.me.Grid;
import lagfinder.core.ProfileSession;

/**
 * Times the work of each AE2 network in a tick, service by service: its channels, crafting, storage, energy... The
 * machines its tick manager ticks are timed one by one ({@link MixinTickManagerService}) and left out of it.
 */
@Mixin(value = Grid.class, remap = false)
public abstract class MixinGrid {

    @WrapOperation(
        method = "onServerStartTick",
        at = @At(value = "INVOKE", target = "Lappeng/api/networking/IGridServiceProvider;onServerStartTick()V"),
        require = 0)
    private void lagfinder$timeServerStart(IGridServiceProvider service, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(service);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(service);
        } finally {
            session.recordNetwork(this, service, false, start, outer);
        }
    }

    @WrapOperation(
        method = "onLevelStartTick",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/IGridServiceProvider;onLevelStartTick(Lnet/minecraft/world/level/Level;)V"),
        require = 0)
    private void lagfinder$timeLevelStart(IGridServiceProvider service, Level level, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(service, level);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(service, level);
        } finally {
            session.recordNetwork(this, service, false, start, outer);
        }
    }

    @WrapOperation(
        method = "onLevelEndTick",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/IGridServiceProvider;onLevelEndTick(Lnet/minecraft/world/level/Level;)V"),
        require = 0)
    private void lagfinder$timeLevelEnd(IGridServiceProvider service, Level level, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(service, level);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(service, level);
        } finally {
            session.recordNetwork(this, service, false, start, outer);
        }
    }

    @WrapOperation(
        method = "onServerEndTick",
        at = @At(value = "INVOKE", target = "Lappeng/api/networking/IGridServiceProvider;onServerEndTick()V"),
        require = 0)
    private void lagfinder$timeServerEnd(IGridServiceProvider service, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(service);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(service);
        } finally {
            session.recordNetwork(this, service, false, start, outer);
        }
    }
}
