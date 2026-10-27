package lagfinder.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import lagfinder.core.Describer;
import lagfinder.core.LocationKind;
import lagfinder.core.Node;

public final class EnderIoConduits {

    private static final Map<String, String> FAMILY = new HashMap<>();
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final Method MISSING;

    static {
        FAMILY.put("PowerConduitNetwork", "Energy conduit networks");
        FAMILY.put("ItemConduitNetwork", "Item conduit networks");
        FAMILY.put("LiquidConduitNetwork", "Fluid conduit networks");
        FAMILY.put("AdvancedLiquidConduitNetwork", "Pressurized fluid conduit networks");
        FAMILY.put("EnderLiquidConduitNetwork", "Ender fluid conduit networks");
        FAMILY.put("RedstoneConduitNetwork", "Redstone conduit networks");
        FAMILY.put("MEConduitNetwork", "ME conduit networks");
        FAMILY.put("GasConduitNetwork", "Gas conduit networks");
        FAMILY.put("OCConduitNetwork", "OC conduit networks");
        Method missing = null;
        try {
            missing = Object.class.getMethod("toString");
        } catch (Throwable ignored) {}
        MISSING = missing;
    }

    private EnderIoConduits() {}

    public static Object networkOf(Object conduit) {
        return conduit == null ? null : call(conduit, "getNetwork");
    }

    public static void describeNetwork(Node leaf, Object network) {
        Class<?> type = network.getClass();
        String simple = type.getSimpleName();
        List<TileEntity> tiles = tilesOf(network);
        TileEntity at = lowest(tiles);
        String family = FAMILY.get(simple);
        leaf.mod = "EnderIO";
        leaf.typeKey = "lagfinder:eio_network|" + type.getName();
        leaf.name = family != null ? family : simple;
        leaf.translationKey = family != null ? "lagfinder.pipes.eio." + simple : null;
        leaf.detail = type.getName() + ".doNetworkTick, IConduit.updateEntity";
        if (at != null) {
            World world = at.getWorldObj();
            leaf.kind = LocationKind.BLOCK;
            leaf.x = at.xCoord;
            leaf.y = at.yCoord;
            leaf.z = at.zCoord;
            leaf.dim = world != null ? world.provider.dimensionId : 0;
        }
        leaf.parts = Describer.partsOf(tiles);
        int count = tiles.size();
        leaf.label = count + (count == 1 ? " conduit" : " conduits");
    }

    private static List<TileEntity> tilesOf(Object network) {
        List<TileEntity> tiles = new ArrayList<>();
        Object conduits = call(network, "getConduits");
        if (!(conduits instanceof List)) {
            return tiles;
        }
        for (Object conduit : new ArrayList<>((List<?>) conduits)) {
            Object bundle = conduit != null ? call(conduit, "getBundle") : null;
            Object entity = bundle != null ? call(bundle, "getEntity") : null;
            if (entity instanceof TileEntity) {
                tiles.add((TileEntity) entity);
            }
        }
        return tiles;
    }

    private static TileEntity lowest(List<TileEntity> tiles) {
        TileEntity lowest = null;
        for (TileEntity tile : tiles) {
            if (lowest == null || tile.xCoord < lowest.xCoord
                || tile.xCoord == lowest.xCoord
                    && (tile.yCoord < lowest.yCoord || tile.yCoord == lowest.yCoord && tile.zCoord < lowest.zCoord)) {
                lowest = tile;
            }
        }
        return lowest;
    }

    private static Object call(Object target, String name) {
        Class<?> type = target.getClass();
        String key = type.getName() + "#" + name;
        Method method = METHODS.get(key);
        if (method == null) {
            try {
                method = type.getMethod(name);
                method.setAccessible(true);
            } catch (Throwable t) {
                method = MISSING;
            }
            METHODS.put(key, method);
        }
        if (method == MISSING) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
