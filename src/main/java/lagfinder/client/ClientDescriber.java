package lagfinder.client;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import lagfinder.core.Category;
import lagfinder.core.Describer;
import lagfinder.core.LocationKind;
import lagfinder.core.ModResolver;
import lagfinder.core.Node;

/** Describes what a frame scan measures, chunk sections being rebuilt included. */
public class ClientDescriber extends Describer {

    @Override
    public void describe(Node leaf) {
        if (leaf.key instanceof WorldRenderer) {
            WorldRenderer renderer = (WorldRenderer) leaf.key;
            leaf.kind = LocationKind.SECTION;
            leaf.mod = ModResolver.MINECRAFT;
            leaf.typeKey = WorldRenderer.class;
            leaf.name = "Chunk section";
            leaf.translationKey = "lagfinder.type.chunk_section";
            leaf.detail = renderer.getClass()
                .getName();
            leaf.x = renderer.posX;
            leaf.y = renderer.posY;
            leaf.z = renderer.posZ;
            Minecraft mc = Minecraft.getMinecraft();
            leaf.dim = mc.theWorld != null ? mc.theWorld.provider.dimensionId : 0;
            leaf.label = (renderer.posX >> 4) + ", " + (renderer.posY >> 4) + ", " + (renderer.posZ >> 4);
            return;
        }
        super.describe(leaf);
    }

    @Override
    public boolean isGone(Object thing) {
        if (thing instanceof World) {
            // The client has one world: any other is left behind.
            return Minecraft.getMinecraft().theWorld != thing;
        }
        return super.isGone(thing);
    }

    @Override
    public boolean expects(Category category) {
        World world = Minecraft.getMinecraft().theWorld;
        switch (category) {
            case EVENTS:
                return true;
            case TILE_ENTITIES:
            case ENTITIES:
                return world != null && hasSome(world, category);
            case TILE_RENDER:
                return world != null && drawsSpecialTile(world);
            case ENTITY_RENDER:
                return world != null && seesEntity(world);
            default:
                // No chunk rebuilt may well be all there was.
                return false;
        }
    }

    /** Whether a tile entity with a special renderer is close enough to the player to be drawn. */
    private static boolean drawsSpecialTile(World world) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.renderViewEntity == null) {
            return false;
        }
        for (Object object : world.loadedTileEntityList) {
            TileEntity tile = (TileEntity) object;
            if (TileEntityRendererDispatcher.instance.hasSpecialRenderer(tile)
                && tile.getDistanceFrom(mc.renderViewEntity.posX, mc.renderViewEntity.posY, mc.renderViewEntity.posZ)
                    < tile.getMaxRenderDistanceSquared()) {
                return true;
            }
        }
        return false;
    }

    /** Whether another entity than the player is near enough to be drawn. */
    private static boolean seesEntity(World world) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.renderViewEntity == null) {
            return false;
        }
        for (Object object : world.loadedEntityList) {
            Entity entity = (Entity) object;
            if (entity != mc.renderViewEntity && entity.getDistanceSqToEntity(mc.renderViewEntity) < 32 * 32) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<Integer, String> dimensionNames() {
        Map<Integer, String> names = new HashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null) {
            names.put(mc.theWorld.provider.dimensionId, dimensionName(mc.theWorld));
        }
        return names;
    }
}
