package lagfinder.core;

/**
 * What a measured piece of work is. Server scans (TPS) use the tick categories, client scans (FPS) the frame ones and
 * the ticks of the client's own world.
 */
public enum Category {

    /** Tile entities updating ({@code TileEntity.updateEntity}). */
    TILE_ENTITIES("tile_entities", true),
    /** Entities updating ({@code World.updateEntity}). */
    ENTITIES("entities", true),
    /** Scheduled and random block ticks ({@code Block.updateTick}), per block and chunk. */
    BLOCK_TICKS("block_ticks", true),
    /** Forge and FML event handlers (tick handlers included). */
    EVENTS("events", false),
    /** Whole worlds: everything a dimension does in a tick. */
    WORLDS("worlds", false),
    /** Special renderers of tile entities (chests, machines with animated parts). */
    TILE_RENDER("tile_render", true),
    /** Entity renderers. */
    ENTITY_RENDER("entity_render", true),
    /** Chunk sections rebuilt for drawing after something in them changed. */
    CHUNK_UPDATES("chunk_updates", true),
    /**
     * Applied Energistics 2 networks ticking: by network, the machines it ticks (interfaces, buses, assemblers...) and
     * its own work (channels, crafting, storage...). Last, so reports saved before it read the same.
     */
    AE2_NETWORKS("ae2_networks", true),
    /**
     * The server's own work in a tick, outside the worlds and the events: tracking entities for the players, the
     * network, the autosave, and the rest of the tick nothing else measured. Last, so older reports read the same.
     */
    SERVER("server", false);

    /** Name used in translations and in reports. */
    public final String id;
    /** Whether its entries have instances in the world (positions to teleport to and highlight). */
    public final boolean hasInstances;

    Category(String id, boolean hasInstances) {
        this.id = id;
        this.hasInstances = hasInstances;
    }

    /** The categories a scan of the mode shows, in order. */
    public static Category[] of(ScanMode mode) {
        return mode == ScanMode.TPS
            ? new Category[] { TILE_ENTITIES, ENTITIES, BLOCK_TICKS, AE2_NETWORKS, EVENTS, SERVER, WORLDS }
            : new Category[] { TILE_RENDER, ENTITY_RENDER, CHUNK_UPDATES, EVENTS, TILE_ENTITIES, ENTITIES };
    }

    public static Category byId(String id) {
        for (Category category : values()) {
            if (category.id.equals(id)) {
                return category;
            }
        }
        return null;
    }
}
