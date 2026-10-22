package com.github.loganmartingreen.nucleocraftdetonation.terrain.crater;

import com.github.loganmartingreen.nucleocraftdetonation.config.TerrainGenerationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.detonation.NuclearYield;

/** Yield-scaled dimensions shared by surface and subterranean excavation. */
public record CraterDimensions(int radius, int depth) {
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
