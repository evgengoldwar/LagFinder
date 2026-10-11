package lagfinder.mixins.late.ae2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.networking.IGridCache;
import appeng.me.Grid;
import lagfinder.core.ProfileSession;

/**
 * Times the work of each AE2 network in a tick, cache by cache: its channels, crafting, storage, energy... The machines
 * its tick manager ticks are timed one by one ({@link MixinTickManagerCache}) and left out of it.
 */
@Mixin(value = Grid.class, remap = false)
public abstract class MixinGrid {

    @WrapOperation(
        method = "update",
        at = @At(value = "INVOKE", target = "Lappeng/api/networking/IGridCache;onUpdateTick()V"),
        require = 0)
    private void lagfinder$timeCache(IGridCache cache, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(cache);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(cache);
        } finally {
            session.recordNetwork(this, cache, false, start, outer);
        }
    }
}
