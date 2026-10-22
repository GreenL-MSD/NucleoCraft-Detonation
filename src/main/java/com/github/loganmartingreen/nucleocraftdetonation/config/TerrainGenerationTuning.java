package com.github.loganmartingreen.nucleocraftdetonation.config;

/**
 * Human-readable tuning for physical terrain effects.
 *
 * <p>Cloud/visual tuning deliberately remains in {@link DetonationTuning}; this
 * class controls only crater excavation, blast damage, persistent fallout
 * terrain conversion and scheduler budgets.</p>
 */
public final class TerrainGenerationTuning {
    private TerrainGenerationTuning() {}

    public static final class Crater {
        public static final double REFERENCE_YIELD_KT = 10.0D;
        public static final double REFERENCE_RADIUS_BLOCKS = 32.0D;
        public static final int MINIMUM_RADIUS_BLOCKS = 24;
        public static final double DEPTH_RADIUS_RATIO = 0.34D;
        public static final int MINIMUM_DEPTH_BLOCKS = 9;

        /** Detonations buried less than this fraction of crater depth remain surface-style. */
        public static final double SURFACE_MODE_BURIAL_DEPTH_RATIO = 0.55D;
        public static final int SURFACE_MODE_MINIMUM_BURIAL_BLOCKS = 4;

        /** Surface bowl geometry. */
        public static final double SURFACE_INNER_ABSOLUTE_RADIUS_RATIO = 0.52D;
        public static final double SURFACE_BOWL_EXPONENT = 1.18D;
        public static final double SURFACE_EDGE_TAPER_START_RATIO = 0.78D;

        /** Underground cavity geometry. */
        public static final double CAVITY_HORIZONTAL_RADIUS_MULTIPLIER = 1.20D;
        public static final double CAVITY_VERTICAL_RADIUS_MULTIPLIER = 1.95D;
        public static final double CAVITY_BOTTOM_FLATTEN_RATIO = 0.10D;

        /** Breakout chimney geometry. */
        public static final double BREAKOUT_MAX_OVERBURDEN_RADIUS_RATIO = 0.48D;
        public static final int BREAKOUT_MINIMUM_OVERBURDEN_BLOCKS = 8;
        public static final double BREAKOUT_SURFACE_RADIUS_MULTIPLIER = 0.58D;
        public static final double BREAKOUT_CONNECTION_RADIUS_MULTIPLIER = 0.45D;
        public static final double BREAKOUT_SURFACE_BOWL_DEPTH_RATIO = 0.38D;
        public static final double BREAKOUT_THROAT_CURVE_POWER = 1.35D;

        /** Work budget; high enough to remain practical without doing the full crater in one tick. */
        public static final int OPERATIONS_PER_TICK = 50_000;

        private Crater() {}
    }

    public static final class Fallout {
        public static final int SPREAD_MAX_AGE_TICKS = 1;
        public static final int BLOCK_CHECKS_PER_TICK = 500_000;
        public static final int LOADED_DISCOVERY_CHUNKS_PER_TICK = 8_192;
        public static final int BACKGROUND_TARGET_SCAN_PER_TICK = 8_192;
        public static final int BACKGROUND_GLOBAL_FORCE_LOAD_LIMIT = 2;
        public static final int BACKGROUND_CHUNKS_PER_SCHEDULER = 2;
        public static final int BACKGROUND_CHUNK_INTERVAL_TICKS = 1;
        public static final int BACKGROUND_SYNC_LOAD_FALLBACK_TICKS = 3;
        public static final int BACKGROUND_FORCE_LOAD_TIMEOUT_TICKS = 200;
        public static final int PROGRESS_LOG_INTERVAL_TICKS = 20 * 60;

        public static final int GROUND_SEARCH_DEPTH = 512;

        /** Deep blast-altered ground, separate from surface fallout. */
        public static final int CHARRED_STONE_INNER_DEPTH = 24;
        public static final int CHARRED_STONE_MINIMUM_DEPTH = 5;
        public static final int CHARRED_DIRT_DEPTH = 8;
        public static final int CHARRED_SAND_DEPTH = 12;
        public static final double CHARRED_SURFACE_END_RATIO = 0.56D;
        public static final double CHARRED_SOIL_END_RATIO = 0.78D;
        public static final double CHARRED_STONE_END_RATIO = 0.62D;
        public static final double BAND_BLEND_WIDTH_RATIO = 0.045D;
        public static final double OUTER_RAD_GRASS_FADE_START_RATIO = 0.90D;
        public static final double OUTER_RAD_GRASS_NOISE_SCALE = 17.0D;
        public static final double OUTER_RAD_GRASS_NOISE_STRENGTH = 0.16D;

        /** Existing strongly altered core remains deeper than the general 24->5 taper. */
        public static final double STONE_CORE_RADIUS_MULTIPLIER = 1.65D;
        public static final double STONE_CORE_RADIUS_PADDING = 24.0D;
        public static final double STONE_CORE_DEPTH_MULTIPLIER = 0.55D;
        public static final double STONE_CORE_DEPTH_PADDING = 24.0D;
        public static final int MINIMUM_STONE_CORE_DEPTH = 48;
        public static final int MAXIMUM_STONE_CORE_DEPTH = 192;

        public static final double BROAD_NOISE_SCALE = 82.0D;
        public static final double DETAIL_NOISE_SCALE = 23.0D;
        public static final double CHARRED_STONE_0_RATIO = 0.60D;

        /** Hot blast water treatment: evaporate centrally, irradiate farther out. */
        public static final double WATER_EVAPORATION_END_RATIO = 0.35D;
        public static final double WATER_IRRADIATION_FADE_START_RATIO = 0.90D;
        public static final double WATER_IRRADIATION_FADE_NOISE_SCALE = 19.0D;
        public static final double WATER_IRRADIATION_FADE_NOISE_STRENGTH = 0.18D;

        /** Trees / wood / structures are damaged without lowering the landscape. */
        public static final double REFERENCE_CRATER_RADIUS_BLOCKS = 32.0D;
        public static final double TREE_CUT_EDGE_DISTANCE_AT_REFERENCE = 30.0D;
        public static final int TREE_MAX_STUMP_HEIGHT = 8;
        public static final double TREE_STUMP_HEIGHT_POWER = 1.30D;
        public static final double LOG_CONVERSION_BASE_CHANCE = 0.62D;
        public static final double LOG_CONVERSION_SEVERITY_BONUS = 0.45D;
        public static final double PLANK_CONVERSION_BASE_CHANCE = 0.48D;
        public static final double PLANK_CONVERSION_SEVERITY_BONUS = 0.55D;
        public static final double LEAF_FULL_REMOVAL_SEVERITY = 0.26D;
        public static final double LEAF_NOISE_STRENGTH = 0.10D;
        public static final double OUTER_LEAF_REMOVAL_BASE_CHANCE = 0.42D;
        public static final double BUILDING_DAMAGE_BASE_CHANCE = 0.08D;
        public static final double BUILDING_DAMAGE_SEVERITY_BONUS = 0.56D;
        public static final double BUILDING_DAMAGE_HEIGHT_BONUS = 0.28D;
        public static final double BUILDING_DAMAGE_CLUSTER_SCALE = 7.0D;
        public static final double BUILDING_DAMAGE_CLUSTER_STRENGTH = 0.18D;

        /** Mob effects as the persistent fallout scheduler reaches them. */
        public static final int ENTITY_CHECK_INTERVAL_TICKS = 4;
        public static final double ENTITY_KILL_DISTANCE_RATIO = 0.30D;
        public static final double ENTITY_HEAVY_DAMAGE_DISTANCE_RATIO = 0.68D;
        public static final int ENTITY_HEAVY_FIRE_SECONDS = 18;
        public static final int ENTITY_OUTER_FIRE_SECONDS = 7;
        public static final float ENTITY_HEAVY_DAMAGE_FRACTION = 0.72F;
        public static final float ENTITY_HEAVY_DAMAGE_MINIMUM = 12.0F;
        public static final float ENTITY_OUTER_DAMAGE_FRACTION = 0.28F;
        public static final float ENTITY_OUTER_DAMAGE_MINIMUM = 4.0F;

        private Fallout() {}
    }
}
