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
        public static final int TOTAL_LIFETIME_TICKS = 20 * 40;

        public static final int FIREBALL_GROWTH_TICKS = 90;
        public static final int FIREBALL_TO_CLOUD_TICKS = 220;
        public static final int CLOUD_FORMATION_TICKS = 420;

        public static final float FIREBALL_RADIUS_MULTIPLIER = 0.95F;
        public static final float MINIMUM_FIREBALL_RADIUS = 14.0F;
        public static final float MAXIMUM_FIREBALL_RADIUS = 300.0F;
        public static final float FIREBALL_RISE_MULTIPLIER = 0.62F;

        public static final float STEM_HEIGHT_MULTIPLIER = 2.05F;
        public static final float MINIMUM_STEM_HEIGHT = 24.0F;
        public static final float CAP_RADIUS_MULTIPLIER = 2.80F;
        public static final float MINIMUM_CAP_RADIUS = 30.0F;
        public static final float CAP_THICKNESS_MULTIPLIER = 0.90F;
        public static final float STEM_RADIUS_RATIO = 0.40F;

        public static final int SURFACE_SHOCKWAVE_DELAY_TICKS = 2;
        public static final int SURFACE_SHOCKWAVE_DURATION_TICKS = 160;
        public static final float SURFACE_SHOCKWAVE_RADIUS_MULTIPLIER = 7.50F;
        public static final float MINIMUM_SURFACE_SHOCKWAVE_RADIUS = 34.0F;
        public static final float SURFACE_SHOCKWAVE_RING_THICKNESS = 7.00F;

        public static final int SKY_SHOCKWAVE_DELAY_TICKS = 12;
        public static final int SKY_SHOCKWAVE_DURATION_TICKS = 170;
        public static final float SKY_SHOCKWAVE_RADIUS_MULTIPLIER = 6.50F;
        public static final float MINIMUM_SKY_SHOCKWAVE_RADIUS = 34.0F;
        public static final float SKY_SHOCKWAVE_RING_THICKNESS = 9.50F;

        public static final double MINIMUM_VIEW_DISTANCE = 256.0D;
        public static final double VIEW_DISTANCE_MULTIPLIER = 6.50D;

        public static final int MAX_FIREBALL_PARTICLES_PER_TICK = 700;
        public static final int MAX_STEM_PARTICLES_PER_TICK = 260;
        public static final int MAX_CAP_PARTICLES_PER_TICK = 900;
        public static final int MAX_SHOCKWAVE_PARTICLES_PER_TICK = 1600;

        private ParticleCloud() {
        }
    }
}
