package lagfinder.server;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

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
    static boolean teleport(EntityPlayerMP player, Net.Teleport message) {
        // Only a world that is loaded: an entity is in no other.
        WorldServer world = message.entityId < 0 ? null : DimensionManager.getWorld(message.dim);
        Entity entity = world == null ? null : world.getEntityByID(message.entityId);
        if (entity != null && !entity.isDead
            && entity.getClass()
                .getName()
                .equals(message.entityClass)) {
            return teleport(
                player,
                message.dim,
                (int) Math.floor(entity.posX),
                (int) Math.floor(entity.posY),
                (int) Math.floor(entity.posZ));
        }
        return teleport(player, message.dim, message.x, message.y, message.z);
    }

    static boolean teleport(EntityPlayerMP player, int dim, int x, int y, int z) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || !DimensionManager.isDimensionRegistered(dim) || !inWorld(x) || !inWorld(z)) {
            // Nothing measured is out there: no chunks are made that far for a place no report has.
            return false;
        }
        WorldServer world = server.worldServerForDimension(dim);
        if (world == null) {
            return false;
        }
        int[] spot = freeSpot(world, x, y, z);
        if (player.ridingEntity != null) {
            player.mountEntity(null);
        }
        if (player.dimension != dim) {
            server.getConfigurationManager()
                .transferPlayerToDimension(player, dim, new Staying(world));
        }
        player.playerNetServerHandler
            .setPlayerLocation(spot[0] + 0.5, spot[1], spot[2] + 0.5, player.rotationYaw, player.rotationPitch);
        player.fallDistance = 0;
        return true;
    }

    private static boolean inWorld(int coordinate) {
        return coordinate >= -WORLD_LIMIT && coordinate <= WORLD_LIMIT;
    }

    /** Where to stand: two free blocks over a place near (x, y, z), or right there if there is none. */
    private static int[] freeSpot(World world, int x, int y, int z) {
        y = Math.max(1, Math.min(254, y));
        for (int[] offset : AROUND) {
            int sx = x + offset[0], sy = y + offset[1], sz = z + offset[2];
            if (isFree(world, sx, sy, sz) && isFree(world, sx, sy + 1, sz)) {
                return new int[] { sx, sy, sz };
            }
        }
        for (int sy = y + 2; sy < 255; sy++) {
            if (isFree(world, x, sy, z) && isFree(world, x, sy + 1, z)) {
                return new int[] { x, sy, z };
            }
        }
        return new int[] { x, y + 1, z };
    }

    private static boolean isFree(World world, int x, int y, int z) {
        if (y < 0 || y > 255) {
            return false;
        }
        Block block = world.getBlock(x, y, z);
        return !block.getMaterial()
            .blocksMovement()
            && !block.getMaterial()
                .isLiquid();
    }

    /** Changes the dimension without looking for or making a portal. */
    private static final class Staying extends Teleporter {

        Staying(WorldServer world) {
            super(world);
        }

        @Override
        public void placeInPortal(Entity entity, double x, double y, double z, float yaw) {}

        @Override
        public boolean placeInExistingPortal(Entity entity, double x, double y, double z, float yaw) {
            return true;
        }

        @Override
        public boolean makePortal(Entity entity) {
            return true;
        }

        @Override
        public void removeStalePortalLocations(long time) {}
    }
}
