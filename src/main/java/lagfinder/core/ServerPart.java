package lagfinder.core;

/** A part of the server's own work in a tick, outside the worlds and the events: a line of {@link Category#SERVER}. */
public enum ServerPart {

    /** Telling the players where the entities around them are ({@code ChunkMap.tick()}). */
    ENTITY_TRACKING("entity_tracking", "Entity tracking"),
    /** Reading what the players sent and sending them what is due ({@code ServerConnectionListener.tick}). */
    NETWORK("network", "Network"),
    /** The worlds and the players saved every few minutes. */
    AUTOSAVE("autosave", "Autosave"),
    /** The players' own tick on the server: the player list, pings ({@code PlayerList.tick}). */
    PLAYER_LIST("player_list", "Player list"),
    /** Worlds no player is in unloaded. */
    WORLD_UNLOADING("world_unloading", "World unloading"),
    /** The tick waiting for chunks it needs to be loaded or generated ({@code ServerChunkCache.getChunk}). */
    CHUNK_LOADING("chunk_loading", "Waiting for chunks"),
    /** Datapack functions run every tick ({@code ServerFunctionManager.tick}). */
    FUNCTIONS("functions", "Datapack functions"),
    /** Chunks sent to the players ({@code PlayerChunkSender.sendNextChunks}). */
    CHUNK_SENDING("chunk_sending", "Chunks sent to players"),
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
