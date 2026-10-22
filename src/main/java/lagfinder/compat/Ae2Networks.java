package lagfinder.compat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.parts.AEBasePart;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.InWorldGridNode;
import lagfinder.Dims;
import lagfinder.core.LocationKind;
import lagfinder.core.ModResolver;
import lagfinder.core.Node;
import lagfinder.core.ReportEntry;

/**
 * Says what an AE2 network's ticks were spent on: each network is a line of its own (where its controller is and how
 * big it is), under it the machines it ticked, by kind and place, and its own work (channels, crafting, storage...).
 * Only loaded when AE2 is there: only its code records these.
 */
public final class Ae2Networks {

    /** AE2's own work by its class, in English; the client names it in its language by {@code lagfinder.ae2.<class>}. */
    private static final Map<String, String> WORK = new HashMap<>();

    static {
        WORK.put("PathingService", "Channels");
        WORK.put("TickManagerService", "Machine tick scheduling");
        WORK.put("CraftingService", "Crafting");
        WORK.put("EnergyService", "Energy");
        WORK.put("StorageService", "Storage");
        WORK.put("SpatialPylonService", "Spatial IO");
        WORK.put("P2PService", "P2P tunnels");
        WORK.put("StatisticsService", "Statistics");
    }

    private Ae2Networks() {}

    /** A place in the world: its dimension and block. */
    private record Place(int dim, BlockPos pos) {}

    public static void describe(Node leaf, Map<Object, String> names) {
        IGrid grid = (IGrid) leaf.live;
        leaf.mod = networkName(grid, names);
        if (leaf.depth == Node.INSTANCE) {
            describeMachine(leaf, (IGridNode) leaf.key);
        } else {
            describeWork(leaf, leaf.key);
        }
    }

    public static void describeGrid(Node leaf, Object network) {
        IGrid grid = (IGrid) network;
        leaf.mod = "Applied Energistics 2";
        leaf.typeKey = "lagfinder:ae2_network";
        leaf.name = "ME networks";
        leaf.translationKey = "lagfinder.pipes.ae2";
        leaf.detail = "Grid.onLevelEndTick, TickManagerService";
        Place at = controllerOf(grid);
        boolean controller = at != null;
        List<Place> places = new ArrayList<>();
        try {
            for (IGridNode node : grid.getNodes()) {
                Place c = placeOf(node);
                if (c != null) {
                    places.add(c);
                    if (at == null) {
                        at = c;
                    }
                }
            }
        } catch (Throwable ignored) {
            places.clear();
        }
        if (at != null) {
            leaf.kind = LocationKind.BLOCK;
            leaf.dim = at.dim();
            leaf.x = at.pos()
                .getX();
            leaf.y = at.pos()
                .getY();
            leaf.z = at.pos()
                .getZ();
        }
        Set<Long> seen = new HashSet<>();
        List<int[]> parts = new ArrayList<>();
        for (Place c : places) {
            if (at != null && c.dim() != at.dim() || parts.size() >= ReportEntry.MAX_PARTS) {
                continue;
            }
            if (seen.add(
                c.pos()
                    .asLong())) {
                parts.add(
                    new int[] { c.pos()
                        .getX(),
                        c.pos()
                            .getY(),
                        c.pos()
                            .getZ() });
            }
        }
        leaf.parts = new int[parts.size() * 3];
        for (int i = 0; i < parts.size(); i++) {
            System.arraycopy(parts.get(i), 0, leaf.parts, i * 3, 3);
        }
        int nodes = places.size();
        leaf.label = nodes + (nodes == 1 ? " node" : " nodes") + (controller ? "" : " · no controller");
    }

    /** Where the node is: its block, or the block of the cable or part it is on. */
    private static Place placeOf(IGridNode node) {
        try {
            Level level = node.getLevel();
            if (level == null) {
                return null;
            }
            if (node instanceof InWorldGridNode inWorld) {
                return new Place(Dims.id(level), inWorld.getLocation());
            }
            Object owner = node.getOwner();
            if (owner instanceof BlockEntity tile) {
                return new Place(Dims.id(level), tile.getBlockPos());
            }
            if (owner instanceof AEBasePart part && part.getBlockEntity() != null) {
                return new Place(
                    Dims.id(level),
                    part.getBlockEntity()
                        .getBlockPos());
            }
        } catch (Throwable ignored) {
            // No place known.
        }
        return null;
    }

    /** The first of the network's controllers, or null when it has none. */
    private static Place controllerOf(IGrid grid) {
        Place at = null;
        try {
            for (IGridNode node : grid.getMachineNodes(ControllerBlockEntity.class)) {
                Place c = placeOf(node);
                if (c != null && (at == null || before(c, at))) {
                    at = c;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return at;
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
        Place at = controllerOf(grid);
        boolean controller = at != null;
        if (at == null && grid.getPivot() != null) {
            at = placeOf(grid.getPivot());
        }
        int nodes = 0;
        try {
            nodes = grid.size();
        } catch (Throwable ignored) {
            // Named without its size.
        }
        StringBuilder text = new StringBuilder(controller ? "ME network" : "ME network without controller");
        if (at != null) {
            text.append(" · ")
                .append(dimensionName(grid, at.dim()))
                .append(' ')
                .append(
                    at.pos()
                        .getX())
                .append(", ")
                .append(
                    at.pos()
                        .getY())
                .append(", ")
                .append(
                    at.pos()
                        .getZ());
        }
        text.append(" · ")
            .append(nodes)
            .append(nodes == 1 ? " node" : " nodes");
        name = text.toString();
        String unique = name;
        for (int i = 2; names.containsValue(unique); i++) {
            unique = name + " #" + i;
        }
        names.put(grid, unique);
        return unique;
    }

    private static boolean before(Place a, Place b) {
        if (a.dim() != b.dim()) {
            return a.dim() < b.dim();
        }
        return a.pos()
            .compareTo(b.pos()) < 0;
    }

    private static String dimensionName(IGrid grid, int dim) {
        try {
            IGridNode pivot = grid.getPivot();
            if (pivot != null && pivot.getLevel() != null && Dims.id(pivot.getLevel()) == dim) {
                return Dims.name(pivot.getLevel());
            }
        } catch (Throwable ignored) {
            // By its number.
        }
        return dim == 0 ? "minecraft:overworld" : dim == -1 ? "minecraft:the_nether" : dim == 1 ? "minecraft:the_end" : "DIM " + dim;
    }

    /** A machine the network ticked (an interface, a bus, an assembler...): its kind is the item it is shown as. */
    private static void describeMachine(Node leaf, IGridNode node) {
        Object owner = node.getOwner();
        Class<?> type = owner != null ? owner.getClass() : node.getClass();
        leaf.detail = type.getName();
        Place at = placeOf(node);
        if (at != null) {
            leaf.kind = LocationKind.BLOCK;
            leaf.dim = at.dim();
            leaf.x = at.pos()
                .getX();
            leaf.y = at.pos()
                .getY();
            leaf.z = at.pos()
                .getZ();
        }
        if (owner instanceof AEBasePart part) {
            try {
                if (part.getSide() != null) {
                    leaf.label = part.getSide()
                        .getSerializedName();
                }
            } catch (Throwable ignored) {
                // Its place is enough.
            }
        }
        Item item = null;
        try {
            AEItemKey visual = node.getVisualRepresentation();
            item = visual != null ? visual.getItem() : null;
        } catch (Throwable ignored) {
            // Named by its class.
        }
        if (item != null) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            leaf.item = id.toString();
            leaf.translationKey = item.getDescriptionId();
            leaf.name = nameOf(leaf.translationKey, id);
            leaf.typeKey = type.getName() + "|" + id;
        } else {
            leaf.typeKey = type.getName();
        }
        if (leaf.name == null || leaf.name.isEmpty()) {
            leaf.name = type.getSimpleName();
        }
    }

    private static String nameOf(String key, ResourceLocation id) {
        try {
            if (net.minecraft.locale.Language.getInstance()
                .has(key)) {
                return net.minecraft.locale.Language.getInstance()
                    .getOrDefault(key);
            }
        } catch (Throwable ignored) {
            // By its id.
        }
        StringBuilder text = new StringBuilder();
        for (String word : id.getPath()
            .split("_")) {
            if (!word.isEmpty()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1));
            }
        }
        return text.toString();
    }

    /** The network's own work: one of its services (channels, crafting, storage...). */
    private static void describeWork(Node leaf, Object service) {
        String className = service.getClass()
            .getName();
        String simple = className.substring(className.lastIndexOf('.') + 1);
        leaf.typeKey = className;
        leaf.detail = className;
        String name = WORK.get(simple);
        leaf.name = name != null ? name : simple;
        leaf.translationKey = "lagfinder.ae2." + simple;
        leaf.instances = 1;
        if (!className.startsWith("appeng.")) {
            leaf.detail = className + " (" + ModResolver.modOf(service.getClass()) + ")";
        }
    }
}
