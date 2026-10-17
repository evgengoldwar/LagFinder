package lagfinder.mixins.early;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.ProfileSession;
import lagfinder.core.WorldTask;

@Mixin(ChunkProviderServer.class)
public abstract class MixinChunkProviderServer {

    @Shadow
    public WorldServer worldObj;

    @WrapOperation(
        method = "originalLoadChunk",
        remap = false,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/IChunkProvider;provideChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            remap = true))
    private Chunk lagfinder$timeTerrain(IChunkProvider provider, int x, int z, Operation<Chunk> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null || worldObj == null) {
            return original.call(provider, x, z);
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            return original.call(provider, x, z);
        } finally {
            session.recordTask(worldObj, WorldTask.TERRAIN, start, outer);
        }
    }

    @WrapOperation(
        method = "populate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/IChunkProvider;populate(Lnet/minecraft/world/chunk/IChunkProvider;II)V"))
    private void lagfinder$timeDecoration(IChunkProvider provider, IChunkProvider filled, int x, int z,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null || worldObj == null) {
            original.call(provider, filled, x, z);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(provider, filled, x, z);
        } finally {
            session.recordTask(worldObj, WorldTask.DECORATION, start, outer);
        }
    }

    @WrapOperation(
        method = "populate",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/common/registry/GameRegistry;generateWorld(IILnet/minecraft/world/World;Lnet/minecraft/world/chunk/IChunkProvider;Lnet/minecraft/world/chunk/IChunkProvider;)V",
            remap = false))
    private void lagfinder$timeModGenerators(int x, int z, World world, IChunkProvider generator,
        IChunkProvider provider, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null || world == null) {
            original.call(x, z, world, generator, provider);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(x, z, world, generator, provider);
        } finally {
            session.recordTask(world, WorldTask.MOD_GENERATORS, start, outer);
        }
    }
}
