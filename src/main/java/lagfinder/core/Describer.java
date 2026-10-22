package lagfinder.core;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import lagfinder.Dims;
import lagfinder.compat.Ae2Networks;
import lagfinder.server.EventTiming;

/**
 * Finds out what a measured thing is: its mod, its kind (the type it is counted under), its name, the item standing
 * for it and where it is. Runs once per thing, at the end of the tick it was first seen in.
 */
public class Describer {

    public static final String UNKNOWN_MOD = "Unknown";

    /** What the AE2 networks of the scan are called, each once: the line they are put under. */
    private final Map<Object, String> networkNames = new IdentityHashMap<>();

    public void describe(Node leaf) {
        switch (leaf.category) {
            case TILE_ENTITIES:
                describeTile(leaf, (BlockEntity) leaf.key);
                break;
            case ENTITIES:
                describeEntity(leaf, (Entity) leaf.key);
                break;
            case BLOCK_TICKS:
                describeBlockTick(leaf, leaf.key, (Level) leaf.live);
                break;
            case WORLD_TASKS:
                describeWorldTask(leaf, (WorldTask.Key) leaf.key);
                break;
            case EVENTS:
                describeListener(leaf, leaf.key);
                break;
            case WORLDS:
                describeWorld(leaf, (Level) leaf.key);
                break;
            case SERVER:
                describeServerPart(leaf, (ServerPart) leaf.key);
                break;
            case AE2_NETWORKS:
                Ae2Networks.describe(leaf, networkNames);
                break;
            case PIPE_NETWORKS:
                Ae2Networks.describeGrid(leaf, leaf.key);
                break;
            default:
                fallback(leaf);
        }
    }

    /** Reads the position of things that move again, for the report. */
    public void updateLocation(Node leaf) {
        if (leaf.live instanceof Entity entity) {
            leaf.x = (int) Math.floor(entity.getX());
            leaf.y = (int) Math.floor(entity.getY());
            leaf.z = (int) Math.floor(entity.getZ());
            leaf.dim = Dims.id(entity.level());
        }
    }

    /**
     * Whether the thing is gone from the world for good: a removed entity, an unloaded block entity, an unloaded
     * world. What was measured of it is kept; the scan lets go of the thing.
     */
    public boolean isGone(Object thing) {
        if (thing instanceof Entity entity) {
            return entity.isRemoved();
        }
        if (thing instanceof BlockEntity tile) {
            return tile.isRemoved();
        }
        if (thing instanceof ServerLevel level) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            return server == null || server.getLevel(level.dimension()) != level;
        }
        return false;
    }

    /**
     * Whether a scan should have measured some of the category: there were such things in the worlds. One that measured
     * none of them likely lost its hook to another mod changing that part of the game.
     */
    public boolean expects(Category category) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return false;
        }
        switch (category) {
            case EVENTS:
            case WORLDS:
                return true;
            case ENTITIES:
            case BLOCK_TICKS:
                return server.getPlayerCount() > 0;
            default:
                return false;
        }
    }

    /** Names of the dimensions, for the report. */
    public Map<Integer, String> dimensionNames() {
        Map<Integer, String> names = new HashMap<>();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                names.put(Dims.id(level), Dims.name(level));
            }
        }
        return names;
    }

    /** What is known of anything: its class and the mod it comes from. */
    public static void fallback(Node leaf) {
        Class<?> type = leaf.key.getClass();
        leaf.mod = ModResolver.modOf(type);
        leaf.typeKey = type;
        leaf.name = type.getSimpleName();
        leaf.detail = type.getName();
    }

    /** The text of a translation key where the server knows it, else a name made from the registry id. */
    static String nameOf(String key, ResourceLocation id) {
        try {
            if (key != null && Language.getInstance()
                .has(key)) {
                return Language.getInstance()
                    .getOrDefault(key);
            }
        } catch (Throwable ignored) {
            // Named by its id.
        }
        return id == null ? key : pretty(id.getPath());
    }

    /** "basic_energy_cube" as "Basic Energy Cube". */
    static String pretty(String path) {
        StringBuilder text = new StringBuilder();
        for (String word : path.replace('/', '_')
            .split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(Character.toUpperCase(word.charAt(0)))
                .append(word.substring(1));
        }
        return text.toString();
    }

    /** The registry name of the item, or null for none. */
    public static String itemId(Item item) {
        if (item == null || item == Items.AIR) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? null : id.toString();
    }

    protected void describeTile(Node leaf, BlockEntity tile) {
        Class<?> type = tile.getClass();
        Level world = tile.getLevel();
        BlockPos pos = tile.getBlockPos();
        leaf.kind = LocationKind.BLOCK;
        leaf.x = pos.getX();
        leaf.y = pos.getY();
        leaf.z = pos.getZ();
        leaf.dim = world != null ? Dims.id(world) : 0;
        leaf.detail = type.getName();
        BlockState state = tile.getBlockState();
        Block block = state.getBlock();
        leaf.mod = ModResolver.modOfBlock(block);
        if (leaf.mod == null || ModResolver.MINECRAFT.equals(leaf.mod) && !type.getName()
            .startsWith("net.minecraft.")) {
            leaf.mod = ModResolver.modOf(type);
        }
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        leaf.translationKey = block.getDescriptionId();
        leaf.name = nameOf(leaf.translationKey, blockId);
        leaf.item = itemId(block.asItem());
        leaf.typeKey = type.getName() + "|" + (blockId != null ? blockId : "");
        if (leaf.name == null || leaf.name.isEmpty()) {
            leaf.name = type.getSimpleName();
        }
    }

    protected void describeEntity(Node leaf, Entity entity) {
        Class<?> type = entity.getClass();
        leaf.kind = LocationKind.ENTITY;
        leaf.live = entity;
        leaf.entityId = entity.getId();
        updateLocation(leaf);
        leaf.typeKey = entity.getType();
        leaf.detail = type.getName();
        if (entity instanceof Player player) {
            leaf.mod = ModResolver.MINECRAFT;
            leaf.name = "Player";
            leaf.translationKey = "lagfinder.entity.player";
            leaf.typeKey = Player.class;
            leaf.label = player.getGameProfile()
                .getName();
            return;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        leaf.mod = id != null ? ModResolver.nameOfModId(id.getNamespace()) : ModResolver.modOf(type);
        leaf.translationKey = entity.getType()
            .getDescriptionId();
        leaf.name = nameOf(leaf.translationKey, id);
        try {
            if (entity instanceof ItemEntity item) {
                ItemStack stack = item.getItem();
                if (!stack.isEmpty()) {
                    ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    leaf.label = nameOf(stack.getItem()
                        .getDescriptionId(), itemKey) + " x" + stack.getCount();
                }
            } else if (entity.hasCustomName() && entity.getCustomName() != null) {
                leaf.label = entity.getCustomName()
                    .getString();
            }
        } catch (Throwable ignored) {
            // A name is a nicety.
        }
    }

    protected void describeBlockTick(Node leaf, Object key, Level world) {
        leaf.kind = LocationKind.CHUNK;
        leaf.dim = Dims.id(world);
        leaf.typeKey = key;
        leaf.detail = key.getClass()
            .getName();
        if (key instanceof Block block) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            leaf.mod = ModResolver.modOfBlock(block);
            leaf.translationKey = block.getDescriptionId();
            leaf.name = nameOf(leaf.translationKey, id);
            leaf.item = itemId(block.asItem());
        } else if (key instanceof Fluid fluid) {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
            leaf.mod = id != null ? ModResolver.nameOfModId(id.getNamespace()) : null;
            leaf.translationKey = fluid.getFluidType()
                .getDescriptionId();
            leaf.name = nameOf(leaf.translationKey, id);
            leaf.item = itemId(fluid.getBucket());
        }
        if (leaf.mod == null) {
            leaf.mod = ModResolver.modOf(key.getClass());
        }
        if (leaf.name == null) {
            leaf.name = key.getClass()
                .getSimpleName();
        }
        leaf.label = "chunk " + (leaf.x >> 4) + ", " + (leaf.z >> 4);
    }

    protected void describeListener(Node leaf, Object listener) {
        leaf.typeKey = listener;
        String readable = EventTiming.describe(listener);
        String targetClass = null;
        if (readable != null && readable.startsWith("@SubscribeEvent: ")) {
            int space = readable.lastIndexOf(' ');
            String target = readable.substring("@SubscribeEvent: ".length(), Math.max(17, space));
            if (target.startsWith("class ")) {
                target = target.substring("class ".length());
            }
            int at = target.indexOf('@');
            targetClass = at > 0 ? target.substring(0, at) : target;
            String method = readable.substring(space + 1);
            int paren = method.indexOf('(');
            String methodName = paren > 0 ? method.substring(0, paren) : method;
            String event = paren > 0 ? method.substring(paren + 1) : "";
            int end = event.indexOf(';');
            event = end > 0 ? event.substring(0, end) : event;
            event = event.substring(event.lastIndexOf('/') + 1)
                .replace('$', '.');
            leaf.name = simpleName(targetClass) + "." + methodName + "  ← " + event;
            leaf.detail = targetClass + "#" + methodName + "(" + event + ")";
        } else if (readable != null) {
            int lambda = readable.indexOf("$$Lambda");
            targetClass = lambda > 0 ? readable.substring(0, lambda) : readable;
            int at = targetClass.indexOf('@');
            if (at > 0) {
                targetClass = targetClass.substring(0, at);
            }
            leaf.name = simpleName(targetClass) + (lambda > 0 ? " (lambda)" : "");
            leaf.detail = readable;
        } else {
            leaf.name = listener.getClass()
                .getSimpleName();
            leaf.detail = listener.getClass()
                .getName();
        }
        String event = EventTiming.eventOf(listener);
        if (event != null && (readable == null || !readable.startsWith("@SubscribeEvent: "))) {
            leaf.name = leaf.name + "  ← " + event;
        }
        if (targetClass != null) {
            try {
                leaf.mod = ModResolver.modOf(Class.forName(targetClass, false, Describer.class.getClassLoader()));
            } catch (Throwable t) {
                leaf.mod = ModResolver.modOfClassName(targetClass);
            }
        } else {
            leaf.mod = ModResolver.modOf(listener.getClass());
        }
    }

    private static String simpleName(String className) {
        String name = className.substring(className.lastIndexOf('.') + 1);
        return name.substring(name.lastIndexOf('$') + 1);
    }

    protected void describeServerPart(Node leaf, ServerPart part) {
        leaf.typeKey = part;
        leaf.mod = ModResolver.MINECRAFT;
        leaf.name = part.englishName;
        leaf.translationKey = "lagfinder.server_part." + part.id;
        leaf.detail = part.englishName;
    }

    protected void describeWorldTask(Node leaf, WorldTask.Key key) {
        Level world = key.world instanceof Level ? (Level) key.world : null;
        leaf.typeKey = key;
        leaf.dim = key.dim;
        leaf.mod = (world != null ? Dims.name(world) : "DIM") + " (" + key.dim + ")";
        leaf.name = key.task.englishName;
        leaf.translationKey = "lagfinder.world_task." + key.task.id;
        leaf.detail = key.task.detail;
    }

    protected void describeWorld(Node leaf, Level world) {
        leaf.typeKey = world;
        leaf.dim = Dims.id(world);
        String name = Dims.name(world);
        int colon = name.indexOf(':');
        leaf.mod = colon > 0 ? ModResolver.nameOfModId(name.substring(0, colon)) : ModResolver.MINECRAFT;
        leaf.name = name;
        leaf.detail = world.getClass()
            .getName() + " " + name.toLowerCase(Locale.ROOT);
    }
}
