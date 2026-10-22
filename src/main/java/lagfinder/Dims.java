package lagfinder;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Dimensions as the numbers reports keep: the overworld 0, the Nether -1, the End 1, the others a number made from
 * their name, the same on every start of the server.
 */
public final class Dims {

    private Dims() {}

    public static int id(Level level) {
        return id(level.dimension());
    }

    public static int id(ResourceKey<Level> key) {
        if (key == Level.OVERWORLD) {
            return 0;
        }
        if (key == Level.NETHER) {
            return -1;
        }
        if (key == Level.END) {
            return 1;
        }
        int hash = key.location()
            .toString()
            .hashCode();
        return hash >= -1 && hash <= 1 ? hash + 3 : hash;
    }

    public static String name(Level level) {
        return level.dimension()
            .location()
            .toString();
    }

    /** The loaded level with the number, or null. */
    public static ServerLevel level(MinecraftServer server, int id) {
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (id(level) == id) {
                return level;
            }
        }
        return null;
    }
}
