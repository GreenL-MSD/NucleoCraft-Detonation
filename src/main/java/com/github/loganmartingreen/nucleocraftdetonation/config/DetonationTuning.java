package com.github.loganmartingreen.nucleocraftdetonation.config;

/**
 * Central tuning values for NucleoCraft: Detonation.
 *
 * <p>Visual and immediate-effect tuning lives here. Physical terrain tuning is
 * intentionally separated into {@link TerrainGenerationTuning}, so crater and
 * fallout generation can be adjusted without touching mushroom-cloud values.</p>
 */
public final class DetonationTuning {
    private DetonationTuning() {
    }

    public static final class Command {
        public static final double TARGET_RANGE_BLOCKS = 512.0D;
        public static final int COUNTDOWN_TICKS = 5 * 20;

        private Command() {
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
        /* The entire saved fallout zone now uses the centre-strength atmosphere. */
        public static final double FULL_STRENGTH_RADIUS_RATIO = 1.00D;
        public static final float INTENSITY_SMOOTHING = 0.14F;

        /* Fog is deliberately darker than the irradiated-water tint. */
        public static final float FOG_RED = 44.0F / 255.0F;
        public static final float FOG_GREEN = 54.0F / 255.0F;
        public static final float FOG_BLUE = 45.0F / 255.0F;
        public static final float FOG_COLOUR_STRENGTH = 0.84F;
        /* Vanilla/near fog: use a hard distance cap so high render distance
         * does not push the fallout wall hundreds of blocks away. */
        public static final float FOG_END_DISTANCE_BLOCKS = 120.0F;
        public static final float FOG_MIN_END_DISTANCE_BLOCKS = 72.0F;
        public static final float FOG_END_RENDER_RATIO = 0.60F;
        public static final float FOG_START_RATIO = 0.18F;

        /* Distant Horizons fog values are normalized to the vanilla render
         * distance. These deliberately overlap vanilla fog so there is no
         * clear LOD band when DH is enabled. */
        public static final double DH_FOG_START = 0.10D;
        public static final double DH_FOG_END = 0.68D;
        public static final double DH_FOG_MIN_THICKNESS = 0.02D;
        public static final double DH_FOG_MAX_THICKNESS = 0.88D;
        public static final double DH_FOG_DENSITY = 1.45D;

        public static final double SKY_COLOUR_STRENGTH = 1.0D;
        public static final double CLOUD_COLOUR_STRENGTH = 0.85D;

        public static final int MAX_DUST_PARTICLES_PER_TICK = 12;
        public static final double DUST_HORIZONTAL_RADIUS = 24.0D;
        public static final double DUST_VERTICAL_RANGE = 12.0D;
        public static final float DUST_SCALE = 1.25F;

        private Fallout() {
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
     * <p>These values control the client-side fireball, mushroom cloud and both
     * shockwaves. Terrain excavation is configured separately.</p>
     */
    public static final class ParticleCloud {
        public static final int TOTAL_LIFETIME_TICKS = 20 * 60 * 20;

        /*
         * The fireball is the cloud head. It rises slowly, darkens and widens
         * into the mushroom shape instead of spawning a separate cap.
         */
        public static final int FIREBALL_COLOUR_TRANSITION_TICKS = 600;
        public static final int FIREBALL_ASCENT_BASE_TICKS = 1_800;
        public static final double FIREBALL_ASCENT_TICKS_PER_SCALE = 2.50D;
        public static final int MAX_FIREBALL_ASCENT_TICKS = 2_600;
        public static final int FIREBALL_SPAWN_END_TICKS = TOTAL_LIFETIME_TICKS;
        public static final int STEM_START_TICKS = 45;

        public static final float FIREBALL_RADIUS_MULTIPLIER = 1.00F;
        public static final float MINIMUM_FIREBALL_RADIUS = 14.0F;
        public static final float MAXIMUM_FIREBALL_RADIUS = 1_024.0F;

        /* The cap begins compact, stays restrained through the screen flash,
         * then rapidly blooms to the legacy starting radius. */
        public static final double EARLY_CAP_INITIAL_RADIUS_RATIO = 0.38D;
        public static final int EARLY_CAP_BLOOM_START_TICKS =
                ImmediateEffects.FLASH_HOLD_TICKS + ImmediateEffects.FLASH_FADE_TICKS;
        public static final int EARLY_CAP_BLOOM_DURATION_TICKS = 20 * 5;

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

        /* Extends the visible stem down to approximately the crater floor. */
        public static final double STEM_GROUND_EXTENSION_RADIUS_RATIO = 0.34D;
        public static final double STEM_GROUND_EXTENSION_MINIMUM = 8.0D;
        public static final double STEM_GROUND_EXTENSION_MAXIMUM = 160.0D;
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
        public static final int CRATER_EDGE_RING_FADE_START_TICKS = 20 * 18;
        public static final int CRATER_EDGE_RING_FADE_END_TICKS = 20 * 50;
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
        public static final int MATURE_GREY_LIGHTEN_DURATION_TICKS = 20 * 60;
        public static final int MATURE_HEAD_PARTICLES_PER_TICK = 10;
        public static final int MATURE_STEM_PARTICLES_PER_TICK = 4;
        public static final int MATURE_PARTICLE_LIFETIME_MIN = 300;
        public static final int MATURE_PARTICLE_LIFETIME_VARIATION = 180;

        public static final float MIN_POOF_SCALE = 1.50F;
        public static final float MAX_FIREBALL_POOF_SCALE = 13.0F;
        public static final float MAX_SMOKE_POOF_SCALE = 18.0F;
        public static final double PARTICLE_OCCLUSION_START_DISTANCE = 96.0D;
        public static final double PARTICLE_OCCLUSION_SAMPLE_RADIUS_RATIO = 0.58D;

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
        public static final int CRATER_REFRESH_CHUNKS_PER_TICK = 16;
        public static final int CRATER_REFRESH_SCAN_PER_TICK = 64;
        public static final int CRATER_REFRESH_MAX_ATTEMPTS = 1_200;

        public static final int CLOUD_FORMING_UPDATE_TICKS = 40;
        public static final int CLOUD_MATURE_UPDATE_TICKS = 200;
        public static final int CLOUD_FAST_UPDATE_END_TICKS = 20 * 60 * 6;

        public static final double MINIMUM_CLOUD_LOD_DISTANCE = 256.0D;
        public static final double CLOUD_LOD_DISTANCE_RADIUS_RATIO = 2.25D;
        public static final double PARTICLE_CULL_CLOUD_RADIUS_MULTIPLIER = 1.65D;

        /*
         * DH cloud models use overlapping filled voxel slices rather than
         * sparse circular outlines. This prevents the distant cap and stem
         * from breaking into visibly separated blocks.
         */
        public static final int STEM_LAYERS = 12;
        public static final int CAP_LAYERS = 7;
        public static final double BOX_SPACING_RATIO = 0.88D;
        public static final double STEM_BOX_RADIUS_RATIO = 0.44D;
        public static final double CAP_BOX_RADIUS_RATIO = 0.22D;

        public static final int RING_MINIMUM_SEGMENTS = 28;
        public static final int RING_MAXIMUM_SEGMENTS = 96;

        private DistantHorizons() {
        }
    }

}
