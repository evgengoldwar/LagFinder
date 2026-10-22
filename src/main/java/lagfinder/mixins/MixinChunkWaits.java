package lagfinder.mixins;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import lagfinder.core.Category;
import lagfinder.core.ProfileSession;
import lagfinder.core.ServerPart;

/** Times the server's tick waiting for a chunk it needs to be loaded or generated. */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
public abstract class MixinChunkWaits {

    @WrapMethod(method = "managedBlock")
    private void lagfinder$timeWait(BooleanSupplier done, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(done);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(done);
        } finally {
            session.record(Category.SERVER, ServerPart.CHUNK_LOADING, start, outer);
        }
    }
}
