package lagfinder.core;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StatCollector;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.eventhandler.ASMEventHandler;
import cpw.mods.fml.common.eventhandler.IEventListener;
import cpw.mods.fml.common.registry.EntityRegistry;
import lagfinder.compat.Ae2Networks;

/**
 * Finds out what a measured thing is: its mod, its kind (the type it is counted under), its name, the item standing
 * for it and where it is. Runs once per thing, at the end of the tick it was first seen in.
 */
public class Describer {

    public static final String UNKNOWN_MOD = "Unknown";

    private static Field listenerOwner, listenerReadable, listenerHandler;
    private static boolean listenerFieldsLooked;

    /** What the AE2 networks of the scan are called, each once: the line they are put under. */
    private final Map<Object, String> networkNames = new IdentityHashMap<>();

    public void describe(Node leaf) {
        switch (leaf.category) {
            case TILE_ENTITIES:
            case TILE_RENDER:
                describeTile(leaf, (TileEntity) leaf.key);
                break;
            case ENTITIES:
            case ENTITY_RENDER:
                describeEntity(leaf, (Entity) leaf.key);
                break;
            case BLOCK_TICKS:
                describeBlockTick(leaf, (Block) leaf.key, (World) leaf.live);
                break;
            case EVENTS:
                describeListener(leaf, leaf.key);
                break;
            case WORLDS:
                describeWorld(leaf, (World) leaf.key);
                break;
            case SERVER:
                describeServerPart(leaf, (ServerPart) leaf.key);
                break;
            case AE2_NETWORKS:
                // Only AE2's own code records these, so its classes are there.
                Ae2Networks.describe(leaf, networkNames);
                break;
            default:
                fallback(leaf);
        }
    }

    /** Reads the position of things that move again, for the report. */
    public void updateLocation(Node leaf) {
        if (leaf.live instanceof Entity) {
            Entity entity = (Entity) leaf.live;
            leaf.x = (int) Math.floor(entity.posX);
            leaf.y = (int) Math.floor(entity.posY);
            leaf.z = (int) Math.floor(entity.posZ);
            leaf.dim = entity.dimension;
        }
    }

    /**
     * Whether the thing is gone from the world for good: a dead entity, an unloaded tile entity, an unloaded world.
     * What was measured of it is kept; the scan lets go of the thing.
     */
    public boolean isGone(Object thing) {
        if (thing instanceof Entity) {
            return ((Entity) thing).isDead;
        }
        if (thing instanceof TileEntity) {
            return ((TileEntity) thing).isInvalid();
        }
        if (thing instanceof World) {
            World world = (World) thing;
            return DimensionManager.getWorld(world.provider.dimensionId) != world;
        }
        return false;
    }

    /**
     * Whether a scan should have measured some of the category: there were such things in the worlds. One that measured
     * none of them likely lost its hook to another mod changing that part of the game.
     */
    public boolean expects(Category category) {
        Integer[] ids = DimensionManager.getIDs();
        switch (category) {
            case EVENTS:
            case WORLDS:
                return true;
            case TILE_ENTITIES:
            case ENTITIES:
            case BLOCK_TICKS:
                for (Integer id : ids) {
                    World world = DimensionManager.getWorld(id);
                    if (world != null && hasSome(world, category)) {
                        return true;
                    }
                }
                return false;
            default:
                return false;
        }
    }

    /** Whether the world has things of the category: tile entities, entities, or players to tick blocks around. */
    protected static boolean hasSome(World world, Category category) {
        switch (category) {
            case TILE_ENTITIES:
                return !world.loadedTileEntityList.isEmpty();
            case ENTITIES:
                return !world.loadedEntityList.isEmpty();
            case BLOCK_TICKS:
                return !world.playerEntities.isEmpty();
            default:
                return false;
        }
    }

    /** Names of the dimensions, for the report. */
    public Map<Integer, String> dimensionNames() {
        Map<Integer, String> names = new HashMap<>();
        for (Integer id : DimensionManager.getIDs()) {
            World world = DimensionManager.getWorld(id);
            if (world != null) {
                names.put(id, dimensionName(world));
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

    protected static String dimensionName(World world) {
        try {
            return world.provider.getDimensionName();
        } catch (Throwable t) {
            return "DIM " + world.provider.dimensionId;
        }
    }

    protected void describeTile(Node leaf, TileEntity tile) {
        Class<?> type = tile.getClass();
        World world = tile.getWorldObj();
        leaf.kind = LocationKind.BLOCK;
        leaf.x = tile.xCoord;
        leaf.y = tile.yCoord;
        leaf.z = tile.zCoord;
        leaf.dim = world != null ? world.provider.dimensionId : 0;
        leaf.detail = type.getName();
        Block block = world != null ? world.getBlock(tile.xCoord, tile.yCoord, tile.zCoord) : null;
        ItemStack stack = null;
        if (block != null && block != Blocks.air) {
            stack = pickBlock(block, world, tile.xCoord, tile.yCoord, tile.zCoord);
            leaf.mod = ModResolver.modOfBlock(block);
        }
        if (leaf.mod == null) {
            leaf.mod = ModResolver.modOf(type);
        }
        if (stack != null) {
            leaf.itemId = Item.getIdFromItem(stack.getItem());
            leaf.itemDamage = stack.getItemDamage();
            leaf.name = displayName(stack);
            // Machines sharing one class (GregTech's) are told apart by the item they are picked as.
            leaf.typeKey = type.getName() + "|" + leaf.itemId + ":" + leaf.itemDamage;
        } else {
            leaf.name = block != null && block != Blocks.air ? safeLocalizedName(block) : type.getSimpleName();
            leaf.typeKey = type.getName();
        }
        if (leaf.name == null || leaf.name.isEmpty()) {
            leaf.name = type.getSimpleName();
        }
    }

    /** The item the block is picked as with the middle mouse button, or null. */
    protected static ItemStack pickBlock(Block block, World world, int x, int y, int z) {
        ItemStack stack = null;
        try {
            MovingObjectPosition target = new MovingObjectPosition(
                x,
                y,
                z,
                1,
                Vec3.createVectorHelper(x + 0.5, y + 0.5, z + 0.5));
            stack = block.getPickBlock(target, world, x, y, z);
        } catch (Throwable ignored) {
            // Some blocks can only be picked by a player on the client.
        }
        if (stack == null || stack.getItem() == null) {
            try {
                Item item = Item.getItemFromBlock(block);
                stack = item == null ? null
                    : new ItemStack(item, 1, block.damageDropped(world.getBlockMetadata(x, y, z)));
            } catch (Throwable ignored) {
                stack = null;
            }
        }
        return stack != null && stack.getItem() != null ? stack : null;
    }

    protected static String displayName(ItemStack stack) {
        try {
            return stack.getDisplayName();
        } catch (Throwable t) {
            return stack.getItem()
                .getClass()
                .getSimpleName();
        }
    }

    private static String safeLocalizedName(Block block) {
        try {
            return block.getLocalizedName();
        } catch (Throwable t) {
            return block.getClass()
                .getSimpleName();
        }
    }

    protected void describeEntity(Node leaf, Entity entity) {
        Class<?> type = entity.getClass();
        leaf.kind = LocationKind.ENTITY;
        leaf.live = entity;
        leaf.entityId = entity.getEntityId();
        updateLocation(leaf);
        leaf.typeKey = type;
        leaf.detail = type.getName();
        if (entity instanceof EntityPlayer) {
            leaf.mod = ModResolver.MINECRAFT;
            leaf.name = "Player";
            leaf.translationKey = "lagfinder.entity.player";
            leaf.typeKey = EntityPlayer.class;
            leaf.label = entity.getCommandSenderName();
            return;
        }
        String mod = null;
        try {
            EntityRegistry.EntityRegistration registration = EntityRegistry.instance()
                .lookupModSpawn(type.asSubclass(Entity.class), true);
            if (registration != null && registration.getContainer() != null) {
                mod = registration.getContainer()
                    .getName();
            }
        } catch (Throwable ignored) {
            // Not registered through FML.
        }
        leaf.mod = mod != null ? mod : ModResolver.modOf(type);
        String id = EntityList.getEntityString(entity);
        if (id != null) {
            leaf.translationKey = "entity." + id + ".name";
            leaf.name = StatCollector.canTranslate(leaf.translationKey)
                ? StatCollector.translateToLocal(leaf.translationKey)
                : id;
        } else {
            leaf.name = type.getSimpleName();
        }
        try {
            if (entity instanceof EntityItem) {
                ItemStack stack = ((EntityItem) entity).getEntityItem();
                if (stack != null && stack.getItem() != null) {
                    leaf.label = displayName(stack) + " x" + stack.stackSize;
                }
            } else if (entity.getCommandSenderName() != null && !entity.getCommandSenderName()
                .equals(leaf.name)) {
                    leaf.label = entity.getCommandSenderName();
                }
        } catch (Throwable ignored) {
            // A name is a nicety.
        }
    }

    protected void describeBlockTick(Node leaf, Block block, World world) {
        leaf.kind = LocationKind.CHUNK;
        leaf.dim = world.provider.dimensionId;
        leaf.typeKey = block;
        leaf.detail = block.getClass()
            .getName();
        leaf.mod = ModResolver.modOfBlock(block);
        if (leaf.mod == null) {
            leaf.mod = ModResolver.modOf(block.getClass());
        }
        leaf.name = safeLocalizedName(block);
        try {
            leaf.translationKey = block.getUnlocalizedName() + ".name";
            Item item = Item.getItemFromBlock(block);
            if (item != null) {
                leaf.itemId = Item.getIdFromItem(item);
            }
        } catch (Throwable ignored) {
            // No item for it.
        }
        leaf.label = "chunk " + (leaf.x >> 4) + ", " + (leaf.z >> 4);
    }

    protected void describeListener(Node leaf, Object listener) {
        ModContainer owner = null;
        String readable = null;
        if (listener instanceof ASMEventHandler) {
            lookListenerFields();
            try {
                if (listenerOwner != null) {
                    owner = (ModContainer) listenerOwner.get(listener);
                }
                if (listenerReadable != null) {
                    readable = (String) listenerReadable.get(listener);
                }
            } catch (Throwable ignored) {
                // Read what could be.
            }
        }
        if (readable == null) {
            // Other buses' generated handlers (GTNHLib's StaticASMEventHandler, for static methods) say what they call
            // the same way, in toString.
            try {
                String text = listener.toString();
                if (text != null && text.startsWith("ASM: ")) {
                    readable = text;
                }
            } catch (Throwable ignored) {
                // Not told.
            }
        }
        leaf.typeKey = listener;
        String targetClass = null;
        if (readable != null && readable.startsWith("ASM: ")) {
            // "ASM: <handler object> <method><descriptor>"
            int space = readable.lastIndexOf(' ');
            String target = readable.substring(5, Math.max(5, space));
            int at = target.indexOf('@');
            targetClass = at > 0 ? target.substring(0, at) : target;
            String method = readable.substring(space + 1);
            int paren = method.indexOf('(');
            String methodName = paren > 0 ? method.substring(0, paren) : method;
            String event = method.substring(paren + 1);
            int end = event.indexOf(';');
            event = end > 0 ? event.substring(0, end) : event;
            event = event.substring(event.lastIndexOf('/') + 1)
                .replace('$', '.');
            leaf.name = simpleName(targetClass) + "." + methodName + "  ← " + event;
            leaf.detail = targetClass + "#" + methodName + "(" + event + ")";
        } else {
            Object target = handlerTarget(listener);
            Class<?> type = target != null ? target.getClass() : listener.getClass();
            targetClass = target != null ? type.getName() : null;
            leaf.name = type.getSimpleName();
            leaf.detail = type.getName();
        }
        if (owner != null) {
            leaf.mod = owner.getName();
        } else if (targetClass != null) {
            try {
                leaf.mod = ModResolver.modOf(Class.forName(targetClass, false, Describer.class.getClassLoader()));
            } catch (Throwable t) {
                leaf.mod = UNKNOWN_MOD;
            }
        } else {
            leaf.mod = ModResolver.modOf(listener.getClass());
        }
    }

    /** The object whose method a generated event handler calls, or null. */
    private static Object handlerTarget(Object listener) {
        try {
            if (!(listener instanceof ASMEventHandler) || listenerHandler == null) {
                return null;
            }
            Object handler = listenerHandler.get(listener);
            return handler == null ? null
                : handler.getClass()
                    .getField("instance")
                    .get(handler);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void lookListenerFields() {
        if (listenerFieldsLooked) {
            return;
        }
        listenerFieldsLooked = true;
        for (Field field : ASMEventHandler.class.getDeclaredFields()) {
            if (field.getType() == ModContainer.class) {
                field.setAccessible(true);
                listenerOwner = field;
            } else if (field.getType() == String.class && "readable".equals(field.getName())) {
                field.setAccessible(true);
                listenerReadable = field;
            } else if (field.getType() == IEventListener.class) {
                field.setAccessible(true);
                listenerHandler = field;
            }
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

    protected void describeWorld(Node leaf, World world) {
        leaf.typeKey = world;
        leaf.dim = world.provider.dimensionId;
        leaf.mod = ModResolver.modOf(world.provider.getClass());
        leaf.name = dimensionName(world) + " (" + world.provider.dimensionId + ")";
        leaf.detail = world.provider.getClass()
            .getName();
    }
}
