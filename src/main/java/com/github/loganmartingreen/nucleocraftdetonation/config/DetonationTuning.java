package com.github.loganmartingreen.nucleocraftdetonation.config;

/**
 * Central tuning values for NucleoCraft: Detonation.
 *
 * <p>This is deliberately a plain constants class for now, so all frequently
 * adjusted gameplay and visual values are in one obvious place. It can later
 * be replaced by a NeoForge config without changing the rest of the code.</p>
 */
public final class DetonationTuning {
    private DetonationTuning() {
    }

    public static final class Command {
        public static final double TARGET_RANGE_BLOCKS = 512.0D;
        public static final int COUNTDOWN_TICKS = 5 * 20;
        public static final int CRATER_OPERATIONS_PER_TICK = 50_000;

        private Command() {
        }
    }

    public static final class CraterScale {
        public static final double REFERENCE_YIELD_KT = 10.0D;
        public static final double REFERENCE_RADIUS_BLOCKS = 32.0D;
        public static final int MINIMUM_RADIUS_BLOCKS = 24;

        public static final double DEPTH_RADIUS_RATIO = 0.34D;
        public static final int MINIMUM_DEPTH_BLOCKS = 9;

        public static final double RIM_WIDTH_BASE = 2.0D;
        public static final int MINIMUM_RIM_WIDTH = 6;
        public static final int MAXIMUM_RIM_WIDTH = 10;

        /*
         * The original implementation effectively produced a three-block rim
         * height for every supported yield. These values preserve that tested
         * appearance while making the behaviour explicit and easy to change.
         */
        public static final double RIM_HEIGHT_BASE = 5.0D;
        public static final int MINIMUM_RIM_HEIGHT = 1;
        public static final int MAXIMUM_RIM_HEIGHT = 3;

        private CraterScale() {
        }
    }

    public static final class CraterAnimation {
        public static final int LAYER_START_INTERVAL_TICKS = 2;
        public static final double WAVE_SPEED_BLOCKS_PER_TICK = 20.0D;

        private CraterAnimation() {
        }
    }

    public static final class Fallout {
        public static final int COLOUR_RGB = 0x5A6C56;
        public static final float RED = 90.0F / 255.0F;
        public static final float GREEN = 108.0F / 255.0F;
        public static final float BLUE = 86.0F / 255.0F;

        public static final double RADIUS_MULTIPLIER = 12.0D;
        public static final float MINIMUM_RADIUS = 800.0F;
        public static final float MAXIMUM_RADIUS = 8_000.0F;
        public static final int DURATION_TICKS = -1;
        public static final float STRENGTH = 1.0F;

        public static final int FADE_IN_TICKS = 20 * 4;
        public static final int FADE_OUT_TICKS = 20 * 20;
        public static final double FULL_STRENGTH_RADIUS_RATIO = 0.05D;
        public static final float INTENSITY_SMOOTHING = 0.14F;

        public static final float FOG_COLOUR_STRENGTH = 0.85F;
        public static final float FOG_START_RATIO = 0.25F;

        public static final double SKY_COLOUR_STRENGTH = 1.0D;
        public static final double CLOUD_COLOUR_STRENGTH = 0.85D;
        public static final float WATER_COLOUR_STRENGTH = 1.0F;

        public static final int MAX_DUST_PARTICLES_PER_TICK = 7;
        public static final double DUST_HORIZONTAL_RADIUS = 24.0D;
        public static final double DUST_VERTICAL_RANGE = 12.0D;
        public static final float DUST_SCALE = 1.25F;

        private Fallout() {
        }
    }

    /** Permanent block conversion, blast damage and outward fallout spread. */
    public static final class FalloutTerrain {
        /*
         * The v24 terrain rules are applied as a high-budget radial block wave.
         * This is deliberately not tied to a fixed metres-per-second timer.
         */
        public static final int SPREAD_MAX_AGE_TICKS = 1;
        public static final int BLOCK_CHECKS_PER_TICK = 250_000;
        public static final int CATCH_UP_BLOCK_CHECKS_PER_TICK = 80_000;

        public static final int VEGETATION_SCAN_DEPTH = 160;
        public static final int GROUND_SEARCH_DEPTH = 160;
        public static final int SURFACE_STONE_DEPTH = 6;
        public static final int CHARRED_DIRT_DEPTH = 8;
        public static final int CHARRED_SAND_DEPTH = 12;
        public static final double CHARRED_SOIL_BASE_CHANCE = 0.72D;
        public static final double CHARRED_SOIL_SEVERITY_BONUS = 0.28D;

        public static final double REFERENCE_CRATER_RADIUS_BLOCKS = 32.0D;

        /* Original v24 fully-charred core scaling. */
        public static final double STONE_CORE_RADIUS_MULTIPLIER = 1.65D;
        public static final double STONE_CORE_RADIUS_PADDING = 24.0D;
        public static final double STONE_CORE_DEPTH_MULTIPLIER = 0.55D;
        public static final double STONE_CORE_DEPTH_PADDING = 24.0D;
        public static final int MINIMUM_STONE_CORE_DEPTH = 48;
        public static final int MAXIMUM_STONE_CORE_DEPTH = 192;

        public static final double BROAD_NOISE_SCALE = 82.0D;
        public static final double DETAIL_NOISE_SCALE = 23.0D;
        public static final double BROAD_NOISE_EDGE_STRENGTH = 0.095D;
        public static final double DETAIL_NOISE_EDGE_STRENGTH = 0.035D;

        public static final double CHARRED_STONE_0_RATIO = 0.60D;

        public static final double GROUND_CONVERSION_BASE_CHANCE = 0.22D;
        public static final double GROUND_CONVERSION_SEVERITY_BONUS = 0.95D;
        public static final double GROUND_CHAR_START_SEVERITY = 0.28D;
        public static final double GROUND_CHAR_SEVERITY_RANGE = 0.58D;
        public static final double OUTER_STONE_MAX_CHANCE = 0.78D;

        public static final double LOG_CONVERSION_BASE_CHANCE = 0.62D;
        public static final double LOG_CONVERSION_SEVERITY_BONUS = 0.45D;
        public static final double PLANK_CONVERSION_BASE_CHANCE = 0.48D;
        public static final double PLANK_CONVERSION_SEVERITY_BONUS = 0.55D;

        public static final double LEAF_FULL_REMOVAL_SEVERITY = 0.26D;
        public static final double LEAF_NOISE_STRENGTH = 0.10D;
        public static final double OUTER_LEAF_REMOVAL_BASE_CHANCE = 0.42D;

        /*
         * For a 10 kt crater, trees are completely stripped/cut within
         * 30 blocks beyond the crater edge. Stumps become taller farther out.
         */
        public static final double TREE_CUT_EDGE_DISTANCE_AT_REFERENCE = 30.0D;
        public static final int TREE_MAX_STUMP_HEIGHT = 8;
        public static final double TREE_STUMP_HEIGHT_POWER = 1.30D;

        /*
         * Clears everything above the crater rim inside a yield-scaled circular
         * blast volume. This can cut a large circular bite out of a mountain.
         */
        public static final double BLAST_CLEAR_EDGE_DISTANCE_AT_REFERENCE = 20.0D;
        public static final double BLAST_CLEAR_RADIUS_NOISE_STRENGTH = 0.045D;
        public static final int BLAST_CLEAR_ABOVE_RIM_OFFSET = 3;

        /*
         * Random TNT-style surface pits outside the main crater. Candidate
         * explosions are scattered independently in small cells, with their
         * density, radius and depth decreasing toward the fallout boundary.
         * No rubble lip is generated.
         */
        public static final double SECONDARY_BLAST_INNER_PADDING = 10.0D;
        public static final int SECONDARY_BLAST_CELL_SIZE = 9;
        public static final double SECONDARY_BLAST_NEAR_CELL_CHANCE = 0.72D;
        public static final double SECONDARY_BLAST_FAR_CELL_CHANCE = 0.04D;
        public static final double SECONDARY_BLAST_DENSITY_POWER = 1.20D;
        public static final double SECONDARY_BLAST_NEAR_RADIUS = 5.2D;
        public static final double SECONDARY_BLAST_FAR_RADIUS = 2.1D;
        public static final double SECONDARY_BLAST_RADIUS_VARIATION = 0.34D;
        public static final double SECONDARY_BLAST_NEAR_DEPTH = 4.5D;
        public static final double SECONDARY_BLAST_FAR_DEPTH = 1.25D;
        public static final double SECONDARY_BLAST_IRREGULARITY = 0.30D;

        /* Noisy top-down structural damage outside the fully cleared core. */
        public static final double BUILDING_DAMAGE_BASE_CHANCE = 0.08D;
        public static final double BUILDING_DAMAGE_SEVERITY_BONUS = 0.56D;
        public static final double BUILDING_DAMAGE_HEIGHT_BONUS = 0.28D;
        public static final double BUILDING_DAMAGE_CLUSTER_SCALE = 7.0D;
        public static final double BUILDING_DAMAGE_CLUSTER_STRENGTH = 0.18D;

        /* One-time effects applied to mobs as the radial terrain wave reaches them. */
        public static final int ENTITY_CHECK_INTERVAL_TICKS = 4;
        public static final double ENTITY_KILL_DISTANCE_RATIO = 0.30D;
        public static final double ENTITY_HEAVY_DAMAGE_DISTANCE_RATIO = 0.68D;
        public static final int ENTITY_HEAVY_FIRE_SECONDS = 18;
        public static final int ENTITY_OUTER_FIRE_SECONDS = 7;
        public static final float ENTITY_HEAVY_DAMAGE_FRACTION = 0.72F;
        public static final float ENTITY_HEAVY_DAMAGE_MINIMUM = 12.0F;
        public static final float ENTITY_OUTER_DAMAGE_FRACTION = 0.28F;
        public static final float ENTITY_OUTER_DAMAGE_MINIMUM = 4.0F;

        private FalloutTerrain() {
        }
    }

    public static final class ImmediateEffects {
        public static final int FLASH_RGB = 0xFFFBEA;
        public static final int FLASH_HOLD_TICKS = 80;
        public static final int FLASH_FADE_TICKS = 160;
        public static final double FULL_FLASH_RADIUS_RATIO = 0.30D;

        public static final double FLASH_RADIUS_MULTIPLIER = 50.0D;
        public static final float MINIMUM_FLASH_RADIUS = 1_500.0F;
        public static final float MAXIMUM_FLASH_RADIUS = 24_000.0F;

        public static final double SOUND_RADIUS_MULTIPLIER = 80.0D;
        public static final float MINIMUM_SOUND_RADIUS = 2_500.0F;
        public static final float MAXIMUM_SOUND_RADIUS = 36_000.0F;

        public static final float STRENGTH = 1.0F;

        public static final double SPEED_OF_SOUND_BLOCKS_PER_SECOND = 343.0D;
        public static final double TICKS_PER_SECOND = 20.0D;
        public static final double FULL_SOUND_RADIUS_RATIO = 0.20D;
        public static final float MINIMUM_SOUND_VOLUME = 0.45F;
        public static final float MAXIMUM_SOUND_VOLUME = 3.5F;
        public static final float MINIMUM_SOUND_PITCH = 0.96F;
        public static final float SOUND_PITCH_VARIATION = 0.04F;

        private ImmediateEffects() {
        }
    }

    /**
     * Cinematic particle-only visual detonation effect.
     *
     * <p>The crater is still the gameplay effect. These values control the
     * client-side fireball, mushroom cloud and both shockwaves.</p>
     */
    public static final class ParticleCloud {
        public static final int TOTAL_LIFETIME_TICKS = 20 * 60 * 20;

        /*
         * The fireball is the cloud head. It rises slowly, darkens and widens
         * into the mushroom shape instead of spawning a separate cap.
         */
        public static final int FIREBALL_COLOUR_TRANSITION_TICKS = 480;
        public static final int FIREBALL_ASCENT_BASE_TICKS = 1_800;
        public static final double FIREBALL_ASCENT_TICKS_PER_SCALE = 2.50D;
        public static final int MAX_FIREBALL_ASCENT_TICKS = 2_600;
        public static final int FIREBALL_SPAWN_END_TICKS = TOTAL_LIFETIME_TICKS;
        public static final int STEM_START_TICKS = 45;

        public static final float FIREBALL_RADIUS_MULTIPLIER = 1.00F;
        public static final float MINIMUM_FIREBALL_RADIUS = 14.0F;
        public static final float MAXIMUM_FIREBALL_RADIUS = 300.0F;

        public static final double FIREBALL_CAP_SPREAD_START_RATIO = 0.15D;
        public static final float MATURE_CLOUD_RADIUS_MULTIPLIER = 2.80F;
        public static final float MINIMUM_MATURE_CLOUD_RADIUS = 30.0F;
        public static final double MATURE_CLOUD_VERTICAL_RATIO = 0.42D;

        /*
         * After the normal v12 mushroom-head formation has completed, the
         * cap begins a second, much slower width-only expansion. This preserves
         * the v12 formation shape and adds a long atmospheric spreading phase.
         */
        public static final int LATE_CAP_EXPANSION_DELAY_TICKS = 20 * 20;
        public static final int LATE_CAP_EXPANSION_DURATION_TICKS = 20 * 60 * 5;
        public static final float LATE_CAP_RADIUS_MULTIPLIER = 3.55F;
        public static final double LATE_CAP_EXPANSION_POWER = 2.20D;

        public static final float STEM_HEIGHT_MULTIPLIER = 4.50F;
        public static final float MINIMUM_STEM_HEIGHT = 48.0F;
        public static final float STEM_INITIAL_RADIUS_RATIO = 0.34F;
        public static final float STEM_RADIUS_RATIO = 0.34F;
        public static final double STEM_WIDEN_START_MORPH_RATIO = 0.18D;
        public static final double STEM_WIDEN_POWER = 2.35D;

        /*
         * Narrows only the middle of the stem. The sine profile is zero at
         * both ends, preserving the existing bottom and top widths exactly.
         */
        public static final double STEM_MIDDLE_CURVE_STRENGTH = 0.18D;
        public static final double STEM_MIDDLE_CURVE_POWER = 1.25D;

        public static final int FIREBALL_PARTICLES_BASE = 110;
        public static final double FIREBALL_PARTICLES_PER_RADIUS = 3.2D;
        public static final int FIREBALL_PARTICLES_MIN = 110;
        public static final int FIREBALL_PARTICLES_MAX = 340;

        public static final int STEM_PARTICLES_BASE = 30;
        public static final double STEM_PARTICLES_PER_HEIGHT = 0.55D;
        public static final int STEM_PARTICLES_MIN = 30;
        public static final int STEM_PARTICLES_MAX = 130;

        /* Small smoke collar at the foot of the stem. */
        public static final double STEM_BASE_RING_RADIUS_RATIO = 0.13D;
        public static final double STEM_BASE_RING_MINIMUM_RADIUS = 1.75D;
        public static final double STEM_BASE_RING_THICKNESS_RATIO = 0.24D;
        public static final int STEM_BASE_RING_PARTICLES_PER_TICK = 9;
        public static final int MATURE_STEM_BASE_RING_PARTICLES_PER_TICK = 1;
        public static final float STEM_BASE_RING_MAX_POOF_SCALE = 5.0F;

        /*
         * Dark smoke laid around the actual crater edge. visualScale is the
         * crater radius, so a radius multiplier of 1.0 follows the rim.
         */
        public static final int CRATER_EDGE_RING_START_TICKS = 25;
        public static final double CRATER_EDGE_RING_RADIUS_MULTIPLIER = 1.00D;
        public static final double CRATER_EDGE_RING_THICKNESS_RATIO = 0.110D;
        public static final double CRATER_EDGE_RING_MINIMUM_THICKNESS = 1.70D;
        public static final int CRATER_EDGE_RING_PARTICLES_PER_TICK = 32;
        public static final int MATURE_CRATER_EDGE_RING_PARTICLES_PER_TICK = 4;
        public static final float CRATER_EDGE_RING_MAX_POOF_SCALE = 13.0F;

        /*
         * A compact grey smoke ring forms beneath the cap once the head has
         * reached full height. Particle density and size ramp up gradually so
         * the ring fades in rather than appearing in a single tick.
         */
        public static final int CAP_UNDERSIDE_RING_FADE_IN_TICKS = 20 * 8;
        public static final double CAP_UNDERSIDE_RING_RADIUS_RATIO = 0.50D;
        public static final double CAP_UNDERSIDE_RING_MINIMUM_RADIUS = 4.0D;
        public static final double CAP_UNDERSIDE_RING_THICKNESS_RATIO = 0.13D;
        public static final double CAP_UNDERSIDE_RING_MINIMUM_THICKNESS = 0.85D;
        public static final double CAP_UNDERSIDE_RING_GAP_RATIO = 0.20D;
        public static final double CAP_UNDERSIDE_RING_MINIMUM_GAP = 3.5D;
        public static final int CAP_UNDERSIDE_RING_PARTICLES_PER_TICK = 12;
        public static final int MATURE_CAP_UNDERSIDE_RING_PARTICLES_PER_TICK = 2;
        public static final float CAP_UNDERSIDE_RING_MAX_POOF_SCALE = 7.0F;


        /*
         * Once the cloud has fully formed, it is refreshed at a much lower
         * particle rate so it can remain visible for twenty minutes without
         * continuing to spawn formation-density particle counts.
         */
        public static final int MATURE_CLOUD_START_TICKS = 2_800;
        public static final int MATURE_HEAD_PARTICLES_PER_TICK = 10;
        public static final int MATURE_STEM_PARTICLES_PER_TICK = 4;
        public static final int MATURE_PARTICLE_LIFETIME_MIN = 300;
        public static final int MATURE_PARTICLE_LIFETIME_VARIATION = 180;

        public static final float MIN_POOF_SCALE = 1.50F;
        public static final float MAX_FIREBALL_POOF_SCALE = 13.0F;
        public static final float MAX_SMOKE_POOF_SCALE = 18.0F;

        /*
         * Ground and atmospheric waves share the same delay, duration and
         * expansion curve. The ground wall receives a fixed radial lead so it
         * travels at the same speed while remaining visibly in front.
         */
        public static final int SHOCKWAVE_DELAY_TICKS = 12;
        public static final int SHOCKWAVE_DURATION_TICKS = 460;
        public static final float SHOCKWAVE_RADIUS_MULTIPLIER = 6.50F;
        public static final float MINIMUM_SHOCKWAVE_RADIUS = 34.0F;

        public static final float SURFACE_SHOCKWAVE_RING_THICKNESS = 2.40F;
        public static final float SKY_SHOCKWAVE_RING_THICKNESS = 9.50F;
        public static final double GROUND_SHOCKWAVE_RADIUS_LEAD_MULTIPLIER = 0.55D;

        /*
         * A second atmospheric ring sits above the first. It begins slightly
         * later but uses the same travel curve and final radius.
         */
        public static final int UPPER_SKY_SHOCKWAVE_EXTRA_DELAY_TICKS = 28;
        public static final double UPPER_SKY_SHOCKWAVE_HEIGHT_RADIUS_RATIO = 0.62D;
        public static final float UPPER_SKY_SHOCKWAVE_SCALE = 0.92F;

        public static final double MINIMUM_VIEW_DISTANCE = 256.0D;
        public static final double VIEW_DISTANCE_MULTIPLIER = 6.50D;

        private ParticleCloud() {
        }
    }

    /**
     * Optional Distant Horizons integration tuning.
     *
     * <p>The integration is reflection-only, so these values are ignored when
     * Distant Horizons is not installed.</p>
     */
    public static final class DistantHorizons {
        public static final int CRATER_REFRESH_CHUNKS_PER_TICK = 8;
        public static final int CRATER_REFRESH_SCAN_PER_TICK = 32;
        public static final int CRATER_REFRESH_MAX_ATTEMPTS = 200;

        public static final int CLOUD_FORMING_UPDATE_TICKS = 40;
        public static final int CLOUD_MATURE_UPDATE_TICKS = 200;
        public static final int CLOUD_FAST_UPDATE_END_TICKS = 20 * 60 * 6;

        public static final double MINIMUM_CLOUD_LOD_DISTANCE = 256.0D;
        public static final double CLOUD_LOD_DISTANCE_RADIUS_RATIO = 2.25D;

        /*
         * DH cloud models use overlapping filled voxel slices rather than
         * sparse circular outlines. This prevents the distant cap and stem
         * from breaking into visibly separated blocks.
         */
        public static final int STEM_LAYERS = 12;
        public static final int CAP_LAYERS = 7;
        public static final double BOX_SPACING_RATIO = 0.78D;
        public static final double STEM_BOX_RADIUS_RATIO = 0.44D;
        public static final double CAP_BOX_RADIUS_RATIO = 0.22D;

        public static final int RING_MINIMUM_SEGMENTS = 28;
        public static final int RING_MAXIMUM_SEGMENTS = 96;

        private DistantHorizons() {
        }
    }

}
