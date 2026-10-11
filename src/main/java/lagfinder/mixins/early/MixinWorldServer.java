package lagfinder.mixins.early;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.ProfileSession;

/** Times scheduled block ticks ({@code tickUpdates}) and random ones ({@code func_147456_g}). */
@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    @WrapOperation(
        method = { "tickUpdates", "func_147456_g" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/block/Block;updateTick(Lnet/minecraft/world/World;IIILjava/util/Random;)V"))
    private void lagfinder$timeBlockTick(Block block, World world, int x, int y, int z, Random random,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(block, world, x, y, z, random);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(block, world, x, y, z, random);
        } finally {
            session.recordBlockTick(world, block, x, y, z, start, outer);
        }
    }
}
