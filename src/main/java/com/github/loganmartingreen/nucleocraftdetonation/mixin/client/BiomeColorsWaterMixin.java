package com.github.loganmartingreen.nucleocraftdetonation.mixin.client;

/**
 * Compatibility placeholder for pre-v42 source overlays.
 *
 * <p>V42 replaced fallout-wide vanilla water tinting with the dedicated
 * irradiated-water fluid. This class intentionally contains no mixin and is
 * no longer listed in nucleocraft_detonation.mixins.json. Keeping the source
 * file here makes the overlay replacement-safe when copied over an older
 * project that still contains the former BiomeColorsWaterMixin.java.</p>
 */
@Deprecated(forRemoval = true)
public final class BiomeColorsWaterMixin {
    private BiomeColorsWaterMixin() {
    }
}
