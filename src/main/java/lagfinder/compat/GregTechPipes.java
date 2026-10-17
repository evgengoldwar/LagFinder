package lagfinder.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import lagfinder.core.Describer;
import lagfinder.core.LocationKind;
import lagfinder.core.Node;

public final class GregTechPipes {

    public static final int CABLE = 0, ITEM = 1, FLUID = 2;
    private static final int LIMIT = 50000;
    private static final String[] FAMILY = { "Cable networks", "Item pipe networks", "Fluid pipe networks" };
    private static final String[] IDS = { "cable", "item", "fluid" };
    private static final Class<?> PIPE_TILE = find("gregtech.api.metatileentity.BaseMetaPipeEntity");
    private static final Class<?> META_PIPE = find("gregtech.api.metatileentity.MetaPipeEntity");
    private static final Class<?> CABLE_META = find("gregtech.api.metatileentity.implementations.MTECable");
    private static final Class<?> ITEM_META = find("gregtech.api.interfaces.metatileentity.IMetaTileEntityItemPipe");
    private static final Class<?> FLUID_META = find("gregtech.api.metatileentity.implementations.MTEFluidPipe");
    private static final Method META = method(PIPE_TILE, "getMetaTileEntity");
    private static final Field CONNECTIONS = field(META_PIPE, "mConnections");

    private static TileEntity ticking;

    private GregTechPipes() {}

    public static int family(Object meta) {
        if (meta == null) {
            return -1;
        }
        if (CABLE_META != null && CABLE_META.isInstance(meta)) {
            return CABLE;
        }
        if (ITEM_META != null && ITEM_META.isInstance(meta)) {
            return ITEM;
        }
        if (FLUID_META != null && FLUID_META.isInstance(meta)) {
            return FLUID;
        }
        return -1;
    }

    public static TileEntity beginTick(TileEntity pipe) {
        TileEntity previous = ticking;
        ticking = pipe;
        return previous;
    }

    public static void endTick(TileEntity previous) {
        ticking = previous;
    }

    public static TileEntity ticking() {
        return ticking;
    }

    public static final class Network {

        public final int family;
        public final List<TileEntity> members = new ArrayList<>();
        public TileEntity lowest;
        public boolean capped;

        Network(int family) {
            this.family = family;
        }
    }

    public static Network networkOf(TileEntity pipe, Map<TileEntity, Network> known) {
        Network found = known.get(pipe);
        if (found != null) {
            return found;
        }
        int family = family(metaOf(pipe));
        World world = pipe.getWorldObj();
        List<TileEntity> members = new ArrayList<>();
        Set<TileEntity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<TileEntity> queue = new ArrayDeque<>();
        seen.add(pipe);
        queue.add(pipe);
        boolean capped = false;
        while (!queue.isEmpty()) {
            TileEntity tile = queue.poll();
            members.add(tile);
            if (found == null) {
                found = known.get(tile);
            }
            if (world == null) {
                continue;
            }
            if (seen.size() >= LIMIT) {
                capped = true;
                continue;
            }
            int connections = connections(metaOf(tile));
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                if ((connections & side.flag) == 0) {
                    continue;
                }
                TileEntity next = neighbor(tile, side);
                if (next == null || seen.contains(next)
                    || !PIPE_TILE.isInstance(next)
                    || family(metaOf(next)) != family) {
                    continue;
                }
                seen.add(next);
                queue.add(next);
            }
        }
        Network network = found != null ? found : new Network(family);
        network.capped |= capped;
        Set<TileEntity> already = Collections.newSetFromMap(new IdentityHashMap<>());
        already.addAll(network.members);
        for (TileEntity member : members) {
            if (already.add(member)) {
                network.members.add(member);
            }
            if (network.lowest == null || before(member, network.lowest)) {
                network.lowest = member;
            }
            known.put(member, network);
        }
        return network;
    }

    public static void describeNetwork(Node leaf, Network network) {
        int family = network.family >= 0 && network.family < IDS.length ? network.family : -1;
        leaf.mod = "GregTech";
        leaf.typeKey = "lagfinder:gt_network|" + family;
        leaf.name = family >= 0 ? FAMILY[family] : "GT pipe networks";
        leaf.translationKey = family >= 0 ? "lagfinder.pipes.gt_" + IDS[family] : null;
        leaf.detail = "MetaPipeEntity.onPostTick, IMetaTileEntityItemPipe.Util.scanPipes, injectEnergyUnits";
        TileEntity at = network.lowest;
        if (at != null) {
            World world = at.getWorldObj();
            leaf.kind = LocationKind.BLOCK;
            leaf.x = at.xCoord;
            leaf.y = at.yCoord;
            leaf.z = at.zCoord;
            leaf.dim = world != null ? world.provider.dimensionId : 0;
        }
        leaf.parts = Describer.partsOf(network.members);
        int count = network.members.size();
        leaf.label = count + (network.capped ? "+" : "") + (count == 1 ? " segment" : " segments");
    }

    private static TileEntity neighbor(TileEntity tile, ForgeDirection side) {
        World world = tile.getWorldObj();
        if (world == null || side == ForgeDirection.UNKNOWN) {
            return null;
        }
        int x = tile.xCoord + side.offsetX, y = tile.yCoord + side.offsetY, z = tile.zCoord + side.offsetZ;
        if (y < 0 || y >= 256 || !world.blockExists(x, y, z)) {
            return null;
        }
        return world.getTileEntity(x, y, z);
    }

    private static boolean before(TileEntity a, TileEntity b) {
        if (a.xCoord != b.xCoord) {
            return a.xCoord < b.xCoord;
        }
        return a.yCoord != b.yCoord ? a.yCoord < b.yCoord : a.zCoord < b.zCoord;
    }

    private static Object metaOf(TileEntity tile) {
        if (META == null || tile == null || !PIPE_TILE.isInstance(tile)) {
            return null;
        }
        try {
            return META.invoke(tile);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int connections(Object meta) {
        if (CONNECTIONS == null || meta == null || !META_PIPE.isInstance(meta)) {
            return 0;
        }
        try {
            return CONNECTIONS.getByte(meta) & 0x3F;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Class<?> find(String name) {
        try {
            return Class.forName(name, false, GregTechPipes.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getField(name);
        } catch (Throwable t) {
            return null;
        }
    }
}
