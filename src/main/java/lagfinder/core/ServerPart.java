package lagfinder.core;

/** A part of the server's own work in a tick, outside the worlds and the events: a line of {@link Category#SERVER}. */
public enum ServerPart {

    /** Telling the players where the entities around them are ({@code EntityTracker.updateTrackedEntities}). */
    ENTITY_TRACKING("entity_tracking", "Entity tracking"),
    /** Reading what the players sent and sending them what is due ({@code NetworkSystem.networkTick}). */
    NETWORK("network", "Network"),
    /** The worlds and the players saved every 45 seconds. */
    AUTOSAVE("autosave", "Autosave"),
    /** The list of players sent to everyone. */
    PLAYER_LIST("player_list", "Player list"),
    /** Worlds no player is in unloaded ({@code DimensionManager.unloadWorlds}). */
    WORLD_UNLOADING("world_unloading", "World unloading"),
    /** Chunks loaded in the background handed to their worlds ({@code ChunkIOExecutor.tick}). */
    CHUNK_LOADING("chunk_loading", "Chunk loading"),
    /** The rest of the tick, that nothing else measured. */
    REST("rest", "Rest of the tick");

    /** Name used in translations. */
    public final String id;
    /** Its name where the translation is not known. */
    public final String englishName;

    ServerPart(String id, String englishName) {
        this.id = id;
        this.englishName = englishName;
    }
}
