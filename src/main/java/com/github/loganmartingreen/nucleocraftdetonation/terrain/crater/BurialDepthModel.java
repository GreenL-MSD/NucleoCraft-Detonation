package com.github.loganmartingreen.nucleocraftdetonation.terrain.crater;

import com.github.loganmartingreen.nucleocraftdetonation.config.TerrainGenerationTuning;

/**
 * Pure, independently testable Minecraft depth/containment heuristic.
 * These are the user's 10 kt visual design bands, not nuclear test containment
 * criteria. The cube-root length scale and environmental perturbations keep
 * results continuous and deterministic; no arbitrary random depth roll.
 */
public final class BurialDepthModel {
    private BurialDepthModel() {}

    public record Assessment(boolean surface, boolean breakout,
                             double strength, String outcome,
                             double effectiveBurialBlocks, double scale) {}

    public static Assessment classify(double burialBlocks, double yieldKilotonnes,
                                      double rockIntegrity, double cavePorosity,
                                      double relief) {
        double yieldScale = Math.cbrt(yieldKilotonnes / 10.0D);
        double susceptibility = Math.clamp(
                (0.60D - rockIntegrity) * 0.18D
                        + cavePorosity * 0.22D + relief * 0.12D,
                -TerrainGenerationTuning.Crater.TERRAIN_SUSCEPTIBILITY_MAX_SHIFT,
                TerrainGenerationTuning.Crater.TERRAIN_SUSCEPTIBILITY_MAX_SHIFT);
        double effectiveDepth = Math.max(0.0D, burialBlocks * (1.0D - susceptibility));
        double normalized = effectiveDepth / Math.max(0.01D, yieldScale);
        double[] band = TerrainGenerationTuning.Crater.DEPTH_BANDS_AT_10KT;
        if (normalized <= band[0]) {
            return new Assessment(true, false, 1.0D, "SURFACE", effectiveDepth, yieldScale);
        }

        double strength;
        if (normalized <= band[1]) {
            strength = lerp(0.98D, 0.82D,
                    smoothstep((normalized - band[0]) / (band[1] - band[0])));
        } else if (normalized <= band[2]) {
            strength = lerp(0.82D, 0.48D,
                    smoothstep((normalized - band[1]) / (band[2] - band[1])));
        } else if (normalized <= band[3]) {
            strength = lerp(0.48D, 0.12D,
                    smoothstep((normalized - band[2]) / (band[3] - band[2])));
        } else if (normalized <= band[4]) {
            strength = lerp(0.12D, 0.0D,
                    smoothstep((normalized - band[3]) / (band[4] - band[3])));
        } else {
            strength = 0.0D;
        }
        boolean breakout = strength >= TerrainGenerationTuning.Crater.BREAKOUT_MIN_STRENGTH;
        String outcome = breakout
                ? (strength >= 0.70D ? "STRONG_BREAKOUT" : "RESTRICTED_BREAKOUT")
                : strength >= TerrainGenerationTuning.Crater.MARGINAL_VENT_MIN_STRENGTH
                    ? "MARGINAL_CONTAINED_VENT" : "CONTAINED";
        return new Assessment(false, breakout, breakout ? strength : 0.0D,
                outcome, effectiveDepth, yieldScale);
    }

    private static double smoothstep(double x) {
        x = Math.clamp(x, 0.0D, 1.0D);
        return x * x * (3.0D - 2.0D * x);
    }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
