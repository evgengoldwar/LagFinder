package lagfinder.core;

public enum WorldTask {

    MOB_SPAWNING(Category.WORLD_TASKS, "mob_spawning", "Mob spawning", "SpawnerAnimals.findChunksForSpawning"),
    CHUNK_UNLOADING(Category.WORLD_TASKS, "chunk_unloading", "Chunk unloading and saving",
        "ChunkProviderServer.unloadQueuedChunks"),
    RANDOM_TICK_SWEEP(Category.WORLD_TASKS, "random_tick_sweep", "Random tick sweep, snow and ice",
        "WorldServer.func_147456_g"),
    PLAYER_CHUNKS(Category.WORLD_TASKS, "player_chunks", "Chunks sent to players",
        "PlayerManager.updatePlayerInstances"),
    VILLAGES(Category.WORLD_TASKS, "villages", "Villages", "VillageCollection.tick, VillageSiege.tick"),
    PORTALS(Category.WORLD_TASKS, "portals", "Portals", "Teleporter.removeStaleTeleportLocations"),
    WEATHER(Category.WORLD_TASKS, "weather", "Weather", "World.updateWeather"),
    TERRAIN(Category.WORLD_GEN, "terrain", "Terrain generation", "IChunkProvider.provideChunk"),
    DECORATION(Category.WORLD_GEN, "decoration", "Decoration: trees, ores, structures", "IChunkProvider.populate"),
    MOD_GENERATORS(Category.WORLD_GEN, "mod_generators", "Mod world generators", "GameRegistry.generateWorld");

    public final Category category;
    public final String id;
    public final String englishName;
    public final String detail;

    WorldTask(Category category, String id, String englishName, String detail) {
        this.category = category;
        this.id = id;
        this.englishName = englishName;
        this.detail = detail;
    }

    public static final class Key {

        public final int dim;
        public final WorldTask task;
        public final Object world;

        Key(int dim, WorldTask task, Object world) {
            this.dim = dim;
            this.task = task;
            this.world = world;
        }
    }
}
