package com.github.loganmartingreen.nucleocraftdetonation.terrain.blast;

import com.github.loganmartingreen.nucleocraftdetonation.config.TerrainGenerationTuning;

/**
 * Distance-only blast damage rules outside the physical crater/cavity.
 *
 * <p>These rules may destroy or convert blocks, but they never lower the
 * landscape surface as a second crater.</p>
 */
public final class BlastDamageRules {
    private BlastDamageRules() {}

    public static boolean evaporatesWater(double falloutDistanceRatio) {
        return falloutDistanceRatio
                <= TerrainGenerationTuning.Fallout.WATER_EVAPORATION_END_RATIO;
    }

    public static double treeCutRadius(double craterRadius) {
        double yieldScale = craterRadius
                / TerrainGenerationTuning.Fallout.REFERENCE_CRATER_RADIUS_BLOCKS;
        return craterRadius
                + TerrainGenerationTuning.Fallout.TREE_CUT_EDGE_DISTANCE_AT_REFERENCE
                * yieldScale;
    }

    public static int allowedStumpHeight(
            double distance,
            double craterRadius,
            double treeCutRadius
    ) {
        double progress = Math.clamp(
                (distance - craterRadius)
                        / Math.max(1.0D, treeCutRadius - craterRadius),
                0.0D,
                1.0D
        );
        return (int) Math.round(
                TerrainGenerationTuning.Fallout.TREE_MAX_STUMP_HEIGHT
                        * Math.pow(
                        progress,
                        TerrainGenerationTuning.Fallout.TREE_STUMP_HEIGHT_POWER
                )
        );
    }
}
