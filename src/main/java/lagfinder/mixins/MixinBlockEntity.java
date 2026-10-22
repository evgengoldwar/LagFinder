package lagfinder.mixins;

import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import lagfinder.core.Node;
import lagfinder.core.TileNodeCache;

/** Keeps on each block entity the line of the scan it is counted on (see {@link TileNodeCache}). */
@Mixin(BlockEntity.class)
public abstract class MixinBlockEntity implements TileNodeCache {

    @Unique
    private Object lagfinder$session;
    @Unique
    private Node lagfinder$node;

    @Override
    public Node lagfinder$cachedNode(Object session) {
        return lagfinder$session == session ? lagfinder$node : null;
    }

    @Override
    public void lagfinder$cacheNode(Object session, Node node) {
        lagfinder$session = session;
        lagfinder$node = node;
    }
}
