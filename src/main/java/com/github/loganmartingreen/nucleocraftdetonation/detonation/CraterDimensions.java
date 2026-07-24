package com.github.loganmartingreen.nucleocraftdetonation.detonation;

import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;

/**
 * Minecraft-compressed crater scaling.
 *
 * <p>The relationship between yields uses cube-root scaling. Constants are
 * intentionally game-scaled and live in {@link DetonationTuning.CraterScale}.</p>
 */
public record CraterDimensions(
        int radius,
        int depth,
        int rimWidth,
        int rimHeight
) {
    public static CraterDimensions fromYield(NuclearYield yield) {
        double yieldScale = Math.cbrt(
                yield.kilotonnes()
                        / DetonationTuning.CraterScale.REFERENCE_YIELD_KT
        );

        int radius = Math.max(
                DetonationTuning.CraterScale.MINIMUM_RADIUS_BLOCKS,
                (int) Math.round(
                        DetonationTuning.CraterScale.REFERENCE_RADIUS_BLOCKS
                                * yieldScale
                )
        );

        int depth = Math.max(
                DetonationTuning.CraterScale.MINIMUM_DEPTH_BLOCKS,
                (int) Math.round(
                        radius
                                * DetonationTuning.CraterScale.DEPTH_RADIUS_RATIO
                )
        );

        int rimWidth = Math.clamp(
                (int) Math.round(
                        DetonationTuning.CraterScale.RIM_WIDTH_BASE
                                + yieldScale
                ),
                DetonationTuning.CraterScale.MINIMUM_RIM_WIDTH,
                DetonationTuning.CraterScale.MAXIMUM_RIM_WIDTH
        );

        int rimHeight = Math.clamp(
                (int) Math.round(
                        DetonationTuning.CraterScale.RIM_HEIGHT_BASE
                                + yieldScale
                ),
                DetonationTuning.CraterScale.MINIMUM_RIM_HEIGHT,
                DetonationTuning.CraterScale.MAXIMUM_RIM_HEIGHT
        );

        return new CraterDimensions(
                radius,
                depth,
                rimWidth,
                rimHeight
        );
    }

    public int edgeNoise() {
        return Math.clamp(
                (int) Math.round(radius * 0.08D),
                3,
                14
        );
    }

    public int outerRadius() {
        return radius
                + edgeNoise()
                + fractureWidth()
                + rimWidth;
    }

    public int fractureWidth() {
        return Math.clamp(
                (int) Math.round(radius * 0.06D),
                3,
                14
        );
    }

    public int wallTearDepth() {
        return Math.clamp(
                (int) Math.round(depth * 0.20D),
                3,
                20
        );
    }
}
