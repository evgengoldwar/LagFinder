package lagfinder.server;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.Loader;
import lagfinder.LagFinder;

public final class ServerUtilitiesAccess {

    private static final String RANK_PREFIX = "lagfinder_";
    private static boolean warned;

    private ServerUtilitiesAccess() {}

    public static boolean allowed(EntityPlayerMP player) {
        if (player == null || !Loader.isModLoaded("serverutilities")) {
            return false;
        }
        try {
            Class<?> ranks = Class.forName("serverutils.ranks.Ranks");
            if (!Boolean.TRUE.equals(
                ranks.getMethod("isActive")
                    .invoke(null))) {
                return false;
            }
            Object instance = ranks.getField("INSTANCE")
                .get(null);
            if (instance == null) {
                return false;
            }
            Object playerRank = ranks.getMethod("getPlayerRank", net.minecraft.entity.player.EntityPlayer.class)
                .invoke(instance, player);
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            return matches(playerRank, seen);
        } catch (ReflectiveOperationException | LinkageError | SecurityException ex) {
            if (!warned) {
                warned = true;
                LagFinder.LOG.warn("Could not query ServerUtilities ranks for LagFinder access", ex);
            }
            return false;
        }
    }

    static boolean matches(Object rank, Set<Object> seen) throws ReflectiveOperationException {
        if (rank == null || !seen.add(rank)) {
            return false;
        }
        Deque<Object> remaining = new ArrayDeque<>();
        remaining.add(rank);
        while (!remaining.isEmpty()) {
            Object current = remaining.removeFirst();
            if (seen.size() > 1024) {
                return false;
            }
            Object id = current.getClass()
                .getMethod("getId")
                .invoke(current);
            if (id instanceof String && ((String) id).startsWith(RANK_PREFIX)) {
                return true;
            }
            Object parents = current.getClass()
                .getMethod("getActualParents")
                .invoke(current);
            if (parents instanceof Iterable<?>) {
                for (Object parent : (Iterable<?>) parents) {
                    if (parent != null && seen.add(parent)) {
                        if (seen.size() > 1024) {
                            return false;
                        }
                        remaining.addLast(parent);
                    }
                }
            }
        }
        return false;
    }
}
