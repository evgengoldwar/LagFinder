package lagfinder.compat;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.util.DimensionalCoord;
import lagfinder.core.LocationKind;
import lagfinder.core.Node;

/**
 * Says what an AE2 network's ticks were spent on: each network is a line of its own (where its controller is and how
 * big it is), under it the machines it ticked, by kind and place, and its own work (channels, crafting, storage...).
 * Only loaded when AE2 is there: only its code records these.
 */
public final class Ae2Networks {

    /**
     * AE2's own work by its class, in English; the client names it in its language by {@code lagfinder.ae2.<class>}.
     */
    private static final Map<String, String> WORK = new HashMap<>();

    static {
        WORK.put("PathGridCache", "Channels");
        WORK.put("TickManagerCache", "Machine tick scheduling");
        WORK.put("CraftingGridCache", "Crafting");
        WORK.put("EnergyGridCache", "Energy");
        WORK.put("GridStorageCache", "Storage");
        WORK.put("SecurityCache", "Security");
        WORK.put("SpatialPylonCache", "Spatial IO");
        WORK.put("P2PCache", "P2P tunnels");
    }

    private Ae2Networks() {}

    public static void describe(Node leaf, Map<Object, String> names) {
        IGrid grid = (IGrid) leaf.live;
        leaf.mod = networkName(grid, names);
        if (leaf.depth == Node.INSTANCE) {
            describeMachine(leaf, (IGridNode) leaf.key);
        } else {
            describeWork(leaf, leaf.key);
        }
    }

    /**
     * The network's line, the same all scan long: where its controller is (the lowest one of them; where it was seen
     * from when it has none) and how many nodes it had when first seen.
     */
    private static String networkName(IGrid grid, Map<Object, String> names) {
        String name = names.get(grid);
        if (name != null) {
            return name;
        }
        DimensionalCoord at = null;
        try {
            // By name: the controller's class has GregTech's interfaces on it, not needed to compile.
            Class<? extends IGridHost> controllers = Class
                .forName("appeng.tile.networking.TileController", false, Ae2Networks.class.getClassLoader())
                .asSubclass(IGridHost.class);
            for (IGridNode node : grid.getMachines(controllers)) {
                DimensionalCoord c = node.getGridBlock()
                    .getLocation();
                if (c != null && (at == null || before(c, at))) {
                    at = c;
                }
            }
        } catch (Throwable ignored) {
            // Named from its pivot instead.
        }
        boolean controller = at != null;
        if (at == null && grid.getPivot() != null) {
            try {
                at = grid.getPivot()
                    .getGridBlock()
                    .getLocation();
            } catch (Throwable ignored) {
                // Named without a place.
            }
        }
        int nodes = 0;
        try {
            nodes = grid.getNodes()
                .size();
        } catch (Throwable ignored) {
            // Named without its size.
        }
        StringBuilder text = new StringBuilder(controller ? "ME network" : "ME network without controller");
        if (at != null) {
            text.append(" · ")
                .append(dimensionName(at.getDimension()))
                .append(' ')
                .append(at.x)
                .append(", ")
                .append(at.y)
                .append(", ")
                .append(at.z);
        }
        text.append(" · ")
            .append(nodes)
            .append(nodes == 1 ? " node" : " nodes");
        name = text.toString();
        // Two networks never share a line.
        String unique = name;
        for (int i = 2; names.containsValue(unique); i++) {
            unique = name + " #" + i;
        }
        names.put(grid, unique);
        return unique;
    }

    private static boolean before(DimensionalCoord a, DimensionalCoord b) {
        if (a.getDimension() != b.getDimension()) {
            return a.getDimension() < b.getDimension();
        }
        if (a.x != b.x) {
            return a.x < b.x;
        }
        return a.y != b.y ? a.y < b.y : a.z < b.z;
    }

    private static String dimensionName(int dim) {
        try {
            World world = DimensionManager.getWorld(dim);
            if (world != null) {
                return world.provider.getDimensionName();
            }
        } catch (Throwable ignored) {
            // By its number.
        }
        return "DIM " + dim;
    }

    /** A machine the network ticked (an interface, a bus, an assembler...): its kind is the item it is shown as. */
    private static void describeMachine(Node leaf, IGridNode node) {
        IGridHost host = node.getMachine();
        Class<?> type = host != null ? host.getClass() : node.getClass();
        leaf.detail = type.getName();
        IGridBlock block = node.getGridBlock();
        DimensionalCoord at = block != null ? block.getLocation() : null;
        if (at != null) {
            leaf.kind = LocationKind.BLOCK;
            leaf.dim = at.getDimension();
            leaf.x = at.x;
            leaf.y = at.y;
            leaf.z = at.z;
        }
        if (host != null && host.getClass()
            .getName()
            .startsWith("appeng.parts.")) {
            try {
                // Parts on one cable are told apart by their side (read by name: parts aren't needed to compile).
                Object side = host.getClass()
                    .getMethod("getSide")
                    .invoke(host);
                if (side instanceof Enum) {
                    leaf.label = ((Enum<?>) side).name()
                        .toLowerCase();
                }
            } catch (Throwable ignored) {
                // Its place is enough.
            }
        }
        ItemStack stack = null;
        try {
            stack = block != null ? block.getMachineRepresentation() : null;
        } catch (Throwable ignored) {
            // Named by its class.
        }
        if (stack != null && stack.getItem() != null) {
            leaf.itemId = Item.getIdFromItem(stack.getItem());
            leaf.itemDamage = stack.getItemDamage();
            try {
                leaf.name = stack.getDisplayName();
            } catch (Throwable ignored) {
                // Named by its class.
            }
            // Parts share classes with their kinds (all of AE2's buses are told apart by the item).
            leaf.typeKey = type.getName() + "|" + leaf.itemId + ":" + leaf.itemDamage;
        } else {
            leaf.typeKey = type.getName();
        }
        if (leaf.name == null || leaf.name.isEmpty()) {
            leaf.name = type.getSimpleName();
        }
    }

    /** The network's own work: one of its caches (channels, crafting, storage...). */
    private static void describeWork(Node leaf, Object cache) {
        String className = cacheClass(cache);
        String simple = className.substring(className.lastIndexOf('.') + 1);
        leaf.typeKey = className;
        leaf.detail = className;
        String name = WORK.get(simple);
        leaf.name = name != null ? name : simple;
        leaf.translationKey = "lagfinder.ae2." + simple;
        leaf.instances = 1;
    }

    /** The class of the cache, through the wrapper older AE2s put around each. */
    private static String cacheClass(Object cache) {
        String name = cache.getClass()
            .getName();
        if (name.equals("appeng.me.GridCacheWrapper")) {
            try {
                return (String) cache.getClass()
                    .getMethod("getName")
                    .invoke(cache);
            } catch (Throwable ignored) {
                // The wrapper's.
            }
        }
        return name;
    }
}
