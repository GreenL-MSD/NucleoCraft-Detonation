package com.github.loganmartingreen.nucleocraftdetonation.client;

/**
 * Small, pure colour utility for blending the existing vanilla distance fog
 * toward the same subdued olive-grey as our fallout dust. No fog volume,
 * visibility-distance or render-pipeline state is created here.
 */
public final class FalloutAtmosphereColour {
    private static final float DUST_INFLUENCE = 0.45F;
    private static final float FOG_BLEND = 0.90F;

    private FalloutAtmosphereColour() { }

    public static float target(float sky, float dust, float falloutIntensity) {
        float influence = DUST_INFLUENCE * clamp01(falloutIntensity);
        return clamp01(lerp(sky, dust, influence));
    }

    public static float apply(float vanillaFog, float sky, float dust,
                              float falloutIntensity) {
        float influence = clamp01(falloutIntensity);
        return clamp01(lerp(vanillaFog, target(sky, dust, influence),
                FOG_BLEND * influence));
    }

    private static float lerp(float from, float to, float weight) {
        return from + (to - from) * weight;
    }

    private static float clamp01(float value) {
        return Math.clamp(value, 0.0F, 1.0F);
    }
}
