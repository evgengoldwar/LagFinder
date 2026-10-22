package lagfinder.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import lagfinder.Dims;
import lagfinder.network.Net;

/**
 * Moves a player next to something found in a report, in any dimension: to a free spot beside or above it, so the
 * player doesn't end up inside a machine.
 */
final class Teleports {

    /** Places beside a block tried before going up: above it, then its four sides. */
    private static final int[][] AROUND = { { 0, 1, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 },
        { 1, 1, 0 }, { -1, 1, 0 }, { 0, 1, 1 }, { 0, 1, -1 } };

    /** How far from the middle a world goes (blocks), as the game lets players go. */
    private static final int WORLD_LIMIT = 30_000_000;

    private Teleports() {}

    /** To the entity of the message where it is now, if it is still in that world, else to the place. */
    static boolean teleport(ServerPlayer player, Net.Teleport message) {
        MinecraftServer server = player.getServer();
        ServerLevel world = Dims.level(server, message.dim());
        if (world == null) {
            return false;
        }
        Entity entity = message.entityId() < 0 ? null : world.getEntity(message.entityId());
        if (entity != null && !entity.isRemoved()
            && entity.getClass()
                .getName()
                .equals(message.entityClass())) {
            return teleport(player, world, entity.getBlockX(), entity.getBlockY(), entity.getBlockZ());
        }
        return teleport(player, world, message.x(), message.y(), message.z());
    }

    private static boolean teleport(ServerPlayer player, ServerLevel world, int x, int y, int z) {
        if (!inWorld(x) || !inWorld(z)) {
            return false;
        }
        BlockPos spot = freeSpot(world, x, y, z);
        if (player.isPassenger()) {
            player.stopRiding();
        }
        player.teleportTo(
            world,
            spot.getX() + 0.5,
            spot.getY(),
            spot.getZ() + 0.5,
            player.getYRot(),
            player.getXRot());
        player.fallDistance = 0;
        return true;
    }

    private static boolean inWorld(int coordinate) {
        return coordinate >= -WORLD_LIMIT && coordinate <= WORLD_LIMIT;
    }

    /** Where to stand: two free blocks over a place near (x, y, z), or right there if there is none. */
    private static BlockPos freeSpot(Level world, int x, int y, int z) {
        int bottom = world.getMinBuildHeight() + 1, top = world.getMaxBuildHeight() - 2;
        y = Math.max(bottom, Math.min(top, y));
        for (int[] offset : AROUND) {
            int sx = x + offset[0], sy = y + offset[1], sz = z + offset[2];
            if (isFree(world, sx, sy, sz) && isFree(world, sx, sy + 1, sz)) {
                return new BlockPos(sx, sy, sz);
            }
        }
        for (int sy = y + 2; sy < top; sy++) {
            if (isFree(world, x, sy, z) && isFree(world, x, sy + 1, z)) {
                return new BlockPos(x, sy, z);
            }
        }
        return new BlockPos(x, y + 1, z);
    }

    private static boolean isFree(Level world, int x, int y, int z) {
        if (y < world.getMinBuildHeight() || y >= world.getMaxBuildHeight()) {
            return false;
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = world.getBlockState(pos);
        return !state.blocksMotion() && state.getFluidState()
            .isEmpty();
    }
}
