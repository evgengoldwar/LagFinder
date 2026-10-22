package lagfinder.mixins;

import java.util.function.BooleanSupplier;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.Category;
import lagfinder.core.ProfileSession;
import lagfinder.core.ServerPart;
import lagfinder.server.ServerScanner;

/**
 * Marks where the server's tick, and each world's, starts and ends around all of it: the tick events a mod listens to
 * on the highest priority at the start (or the lowest at the end) may run before (after) LagFinder's own and be left
 * out otherwise. Times the server's own work outside the worlds too (the network, the autosave, functions...).
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void lagfinder$tickStarts(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerScanner.INSTANCE.tickStarts();
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void lagfinder$tickEnds(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerScanner.INSTANCE.tickEnds();
    }

    @WrapOperation(
        method = "tickChildren",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/event/EventHooks;fireLevelTickPre(Lnet/minecraft/world/level/Level;Ljava/util/function/BooleanSupplier;)V"))
    private void lagfinder$worldStarts(Level level, BooleanSupplier hasTimeLeft, Operation<Void> original) {
        ServerScanner.INSTANCE.worldStarts(level);
        original.call(level, hasTimeLeft);
    }

    @WrapOperation(
        method = "tickChildren",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/event/EventHooks;fireLevelTickPost(Lnet/minecraft/world/level/Level;Ljava/util/function/BooleanSupplier;)V"))
    private void lagfinder$worldEnds(Level level, BooleanSupplier hasTimeLeft, Operation<Void> original) {
        original.call(level, hasTimeLeft);
        ServerScanner.INSTANCE.worldEnds(level);
    }

    @WrapOperation(
        method = "tickChildren",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/ServerFunctionManager;tick()V"))
    private void lagfinder$timeFunctions(ServerFunctionManager functions, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(functions);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(functions);
        } finally {
            session.record(Category.SERVER, ServerPart.FUNCTIONS, start, outer);
        }
    }

    @WrapOperation(
        method = "tickChildren",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerConnectionListener;tick()V"))
    private void lagfinder$timeNetwork(ServerConnectionListener network, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(network);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(network);
        } finally {
            session.record(Category.SERVER, ServerPart.NETWORK, start, outer);
        }
    }

    @WrapOperation(
        method = "tickChildren",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;tick()V"))
    private void lagfinder$timePlayerList(PlayerList players, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(players);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(players);
        } finally {
            session.record(Category.SERVER, ServerPart.PLAYER_LIST, start, outer);
        }
    }

    @WrapOperation(
        method = "tickChildren",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/PlayerChunkSender;sendNextChunks(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void lagfinder$timeChunkSending(PlayerChunkSender sender, ServerPlayer player, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(sender, player);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(sender, player);
        } finally {
            session.record(Category.SERVER, ServerPart.CHUNK_SENDING, start, outer);
        }
    }

    @WrapOperation(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;saveEverything(ZZZ)Z"))
    private boolean lagfinder$timeAutosave(MinecraftServer server, boolean suppressLog, boolean flush, boolean forced,
        Operation<Boolean> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            return original.call(server, suppressLog, flush, forced);
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            return original.call(server, suppressLog, flush, forced);
        } finally {
            session.record(Category.SERVER, ServerPart.AUTOSAVE, start, outer);
        }
    }
}
