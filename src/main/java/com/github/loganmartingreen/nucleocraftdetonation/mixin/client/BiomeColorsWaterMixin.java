package com.github.loganmartingreen.nucleocraftdetonation.mixin.client;

import com.github.loganmartingreen.nucleocraftdetonation.client.DetonationClient;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;

import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blends vanilla biome water tint toward the fallout colour.
 */
@Mixin(BiomeColors.class)
public abstract class BiomeColorsWaterMixin {
    @Inject(
            method = "getAverageWaterColor",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void nucleocraftDetonation$colourWater(
            BlockAndTintGetter getter,
            BlockPos position,
            CallbackInfoReturnable<Integer> callback
    ) {
        float intensity =
                DetonationClient.falloutIntensity();

        if (intensity <= 0.001F) {
            return;
        }

        float blend =
                Math.clamp(
                        intensity
                                * DetonationTuning.Fallout
                                .WATER_COLOUR_STRENGTH,
                        0.0F,
                        1.0F
                );

        callback.setReturnValue(
                blendRgb(
                        callback.getReturnValue(),
                        DetonationTuning.Fallout.COLOUR_RGB,
                        blend
                )
        );
    }

    private static int blendRgb(
            int original,
            int target,
            float progress
    ) {
        int originalRed =
                original >> 16 & 0xFF;

        int originalGreen =
                original >> 8 & 0xFF;

        int originalBlue =
                original & 0xFF;

        int targetRed =
                target >> 16 & 0xFF;

        int targetGreen =
                target >> 8 & 0xFF;

        int targetBlue =
                target & 0xFF;

        int red =
                Math.clamp(
                        Math.round(
                                originalRed
                                        + (
                                        targetRed - originalRed
                                )
                                        * progress
                        ),
                        0,
                        255
                );

        int green =
                Math.clamp(
                        Math.round(
                                originalGreen
                                        + (
                                        targetGreen - originalGreen
                                )
                                        * progress
                        ),
                        0,
                        255
                );

        int blue =
                Math.clamp(
                        Math.round(
                                originalBlue
                                        + (
                                        targetBlue - originalBlue
                                )
                                        * progress
                        ),
                        0,
                        255
                );

        return red << 16
                | green << 8
                | blue;
    }
}
