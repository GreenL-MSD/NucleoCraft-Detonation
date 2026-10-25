package com.github.loganmartingreen.nucleocraftdetonation.terrain.crater;

import com.github.loganmartingreen.nucleocraftdetonation.config.TerrainGenerationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.detonation.NuclearYield;

/** Yield-scaled reference dimensions; surface excavation has an independent profile. */
public record CraterDimensions(int radius, int depth) {
    /** Surface excavation ONLY. The ordinary fromYield() remains the unchanged
     * 10 kt 32x11 reference used by all underground and effects systems. */
    public static CraterDimensions surfaceExcavationFromYield(NuclearYield yield) {
        double scale = Math.cbrt(yield.kilotonnes() / TerrainGenerationTuning.Crater.REFERENCE_YIELD_KT);
        return new CraterDimensions(
                Math.max(TerrainGenerationTuning.Crater.MINIMUM_RADIUS_BLOCKS,
                        (int) Math.round(TerrainGenerationTuning.Crater.SURFACE_EXCAVATION_REFERENCE_RADIUS * scale)),
                Math.max(TerrainGenerationTuning.Crater.MINIMUM_DEPTH_BLOCKS,
                        (int) Math.round(TerrainGenerationTuning.Crater.SURFACE_EXCAVATION_REFERENCE_DEPTH * scale))
        );
    }

    public static CraterDimensions fromYield(NuclearYield yield) {
        double scale = Math.cbrt(yield.kilotonnes() / TerrainGenerationTuning.Crater.REFERENCE_YIELD_KT);
        int radius = Math.max(
                TerrainGenerationTuning.Crater.MINIMUM_RADIUS_BLOCKS,
                (int) Math.round(TerrainGenerationTuning.Crater.REFERENCE_RADIUS_BLOCKS * scale)
        );
        int depth = Math.max(
                TerrainGenerationTuning.Crater.MINIMUM_DEPTH_BLOCKS,
                (int) Math.round(radius * TerrainGenerationTuning.Crater.DEPTH_RADIUS_RATIO)
        );
        return new CraterDimensions(radius, depth);
    }
}
