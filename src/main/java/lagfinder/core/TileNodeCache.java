package lagfinder.core;

/**
 * A tile entity keeps the line of the scan it is counted on (added by a mixin), so timing it every tick needs no
 * look-up in a map: with tens of thousands of tile entities a tick, that look-up was much of the scan's own cost.
 */
public interface TileNodeCache {

    /** The line kept for {@code session}, or null when kept for another scan (or none). */
    Node lagfinder$cachedNode(Object session);

    void lagfinder$cacheNode(Object session, Node node);
}
