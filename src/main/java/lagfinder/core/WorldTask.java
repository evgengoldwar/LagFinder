package lagfinder.core;

public enum WorldTask {

    MOB_SPAWNING(Category.WORLD_TASKS, "mob_spawning", "Mob spawning",
        "NaturalSpawner.spawnForChunk, ServerLevel.tickCustomSpawners"),
    CHUNK_UNLOADING(Category.WORLD_TASKS, "chunk_unloading", "Chunk unloading and saving", "ChunkMap.tick"),
    RANDOM_TICK_SWEEP(Category.WORLD_TASKS, "random_tick_sweep", "Random tick sweep, snow and ice",
        "ServerLevel.tickChunk"),
    PLAYER_CHUNKS(Category.WORLD_TASKS, "player_chunks", "Chunk tickets and loading levels",
        "ServerChunkCache.runDistanceManagerUpdates"),
    VILLAGES(Category.WORLD_TASKS, "villages", "Raids", "Raids.tick"),
    PORTALS(Category.WORLD_TASKS, "portals", "Portals", "PortalForcer"),
    WEATHER(Category.WORLD_TASKS, "weather", "Weather", "ServerLevel.advanceWeatherCycle"),
    TERRAIN(Category.WORLD_GEN, "terrain", "Terrain generation", "ChunkGenerator"),
    DECORATION(Category.WORLD_GEN, "decoration", "Decoration: trees, ores, structures", "ChunkGenerator.applyBiomeDecoration"),
    MOD_GENERATORS(Category.WORLD_GEN, "mod_generators", "Mod world generators", "BiomeModifiers");

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
