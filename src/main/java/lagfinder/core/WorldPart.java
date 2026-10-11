package lagfinder.core;

/**
 * A part of what a world does in a tick: a line under the world's own in {@link Category#WORLDS}. The parts of a world
 * add up to its tick: those the game does in steps we time ({@link #derived} false) and those found by what was
 * measured inside the world's tick.
 */
public enum WorldPart {

    /** Tile entities updating: their own time, as the Tile Entities tab counts it. */
    TILE_ENTITIES("tile_entities", "Tile entities", "World.updateEntities", true),
    /** Entities updating, weather effects included. */
    ENTITIES("entities", "Entities", "World.updateEntities", true),
    /** Scheduled and random block ticks. */
    BLOCK_TICKS("block_ticks", "Block ticks", "Block.updateTick", true),
    /** Mods' handlers of the world's tick and the other events fired in it. */
    EVENTS("events", "Event handlers", "WorldTickEvent", true),
    /** Hostile and peaceful mobs spawning around the players. */
    MOB_SPAWNING("mob_spawning", "Mob spawning", "SpawnerAnimals.findChunksForSpawning", false),
    /** Light recalculated after blocks changed. */
    LIGHTING("lighting", "Lighting", "World.updateLightByType", false),
    /** Rain and thunder: the clocks and what the weather does. */
    WEATHER("weather", "Weather", "World.updateWeather", false),
    /** The sweep of loaded chunks for random ticks, lightning, snow and ice (the blocks' own ticks left out). */
    RANDOM_TICKS("random_ticks", "Random tick sweep, snow and ice", "WorldServer.func_147456_g", false),
    /** Villages and village sieges ticking. */
    VILLAGES("villages", "Villages", "VillageCollection.tick, VillageSiege.tick", false),
    /** Remembered portal exits expiring. */
    PORTALS("portals", "Portals", "Teleporter.removeStaleTeleportLocations", false),
    /** Chunks no player needs unloaded. */
    CHUNK_UNLOADING("chunk_unloading", "Chunk unloading", "ChunkProviderServer.unloadQueuedChunks", false),
    /** Chunks read from the disk (and waited for while they are read). */
    CHUNK_LOADING("chunk_loading", "Chunk loading", "ChunkProviderServer.loadChunk", false),
    /** The terrain of chunks never visited before made. */
    CHUNK_GENERATION("chunk_generation", "Terrain generation", "ChunkProviderServer.originalLoadChunk", false),
    /** Generated chunks decorated: trees, ores, structures and what mods add. */
    CHUNK_POPULATION("chunk_population", "World generation", "ChunkProviderServer.populate", false),
    /** Block changes and chunks sent to the players. */
    PLAYER_CHUNKS("player_chunks", "Chunks sent to players", "PlayerManager.updatePlayerInstances", false),
    /** The rest of the world's tick that nothing above measured. */
    OTHER("other", "Other", "WorldServer.tick", true);

    /** Name used in translations. */
    public final String id;
    /** Its name where the translation is not known. */
    public final String englishName;
    /** What it measures in the game's code, shown in the details. */
    public final String detail;
    /**
     * Whether it is found from what other hooks measured (or is what none did), not timed in a step of its own: the
     * Tile Entities, Entities, Block Ticks and Events tabs show those already.
     */
    public final boolean derived;

    WorldPart(String id, String englishName, String detail, boolean derived) {
        this.id = id;
        this.englishName = englishName;
        this.detail = detail;
        this.derived = derived;
    }

    /** The translation key of the part's name. */
    public static final String KEY_PREFIX = "lagfinder.world_part.";

    /** The part a report line with this translation key is, or null. */
    public static WorldPart ofKey(String translationKey) {
        if (translationKey == null || !translationKey.startsWith(KEY_PREFIX)) {
            return null;
        }
        String id = translationKey.substring(KEY_PREFIX.length());
        for (WorldPart part : values()) {
            if (part.id.equals(id)) {
                return part;
            }
        }
        return null;
    }
}
