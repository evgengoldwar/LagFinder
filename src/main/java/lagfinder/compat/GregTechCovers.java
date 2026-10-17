package lagfinder.compat;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.common.covers.Cover;
import lagfinder.core.LocationKind;
import lagfinder.core.ModResolver;
import lagfinder.core.Node;

/**
 * Says what a GregTech cover is: its item's name (one line per kind of cover, "Conveyor Module (LV)"...), and as an
 * instance the block it is on with the side. Only loaded when GregTech is there: only its code records covers.
 */
public final class GregTechCovers {

    private GregTechCovers() {}

    public static void describe(Node leaf) {
        Cover cover = (Cover) leaf.key;
        Class<?> type = cover.getClass();
        leaf.detail = type.getName();
        leaf.mod = ModResolver.modOf(type);

        if (leaf.live instanceof TileEntity) {
            TileEntity tile = (TileEntity) leaf.live;
            World world = tile.getWorldObj();
            leaf.kind = LocationKind.BLOCK;
            leaf.x = tile.xCoord;
            leaf.y = tile.yCoord;
            leaf.z = tile.zCoord;
            leaf.dim = world != null ? world.provider.dimensionId : 0;
        }
        if (leaf.coverSide >= 0 && leaf.coverSide < ForgeDirection.VALID_DIRECTIONS.length) {
            leaf.label = ForgeDirection.VALID_DIRECTIONS[leaf.coverSide].name()
                .toLowerCase();
        }

        ItemStack stack = null;
        try {
            stack = cover.asItemStack();
        } catch (Throwable ignored) {
            // Named by its class instead.
        }
        if (stack != null && stack.getItem() != null) {
            leaf.itemId = Item.getIdFromItem(stack.getItem());
            leaf.itemDamage = stack.getItemDamage();
            String name = null;
            try {
                name = stack.getDisplayName();
            } catch (Throwable ignored) {
                // Its class name stands for it.
            }
            leaf.name = name != null && !name.isEmpty() ? name : type.getSimpleName();
            // Covers sharing one class (tiers of a conveyor) are told apart by the item they are made of.
            leaf.typeKey = type.getName() + "|" + leaf.itemId + ":" + leaf.itemDamage;
        } else {
            leaf.name = type.getSimpleName();
            leaf.typeKey = type.getName();
        }
    }
}
