package lagfinder.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import lagfinder.LagFinder;

/**
 * Who may scan the server and teleport from its reports: {@code lagfinder.scan} and {@code lagfinder.teleport}, given
 * by the server's permission handler (LuckPerms...), to operators and the owner of a single player world by default.
 */
public final class Permissions {

    public static final PermissionNode<Boolean> SCAN = new PermissionNode<>(
        LagFinder.MODID,
        "scan",
        PermissionTypes.BOOLEAN,
        (player, uuid, context) -> player != null && isOperator(player));
    public static final PermissionNode<Boolean> TELEPORT = new PermissionNode<>(
        LagFinder.MODID,
        "teleport",
        PermissionTypes.BOOLEAN,
        (player, uuid, context) -> player != null && isOperator(player));

    private Permissions() {}

    /** The owner of a single player world, or an operator. */
    public static boolean isOperator(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        return server.isSingleplayerOwner(player.getGameProfile()) || server.getPlayerList()
            .isOp(player.getGameProfile());
    }

    public static boolean has(ServerPlayer player, PermissionNode<Boolean> node) {
        if (player == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(PermissionAPI.getPermission(player, node));
        } catch (RuntimeException e) {
            return isOperator(player);
        }
    }
}
