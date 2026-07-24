package com.github.loganmartingreen.nucleocraftdetonation.mixin.client;

import com.github.loganmartingreen.nucleocraftdetonation.client.DetonationClient;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Changes vanilla sky and cloud colours while the player is inside fallout.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSkyColourMixin {
    @Inject(
            method = "getSkyColor(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void nucleocraftDetonation$colourSky(
            Vec3 cameraPosition,
            float partialTick,
            CallbackInfoReturnable<Vec3> callback
    ) {
        float intensity =
                DetonationClient.falloutIntensity();

        if (intensity <= 0.001F) {
            return;
        }

        callback.setReturnValue(
                blendColour(
                        callback.getReturnValue(),
                        Math.clamp(
                                intensity
                                        * DetonationTuning.Fallout
                                        .SKY_COLOUR_STRENGTH,
                                0.0D,
                                1.0D
                        )
                )
        );
    }

    @Inject(
            method = "getCloudColor(F)Lnet/minecraft/world/phys/Vec3;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void nucleocraftDetonation$colourClouds(
            float partialTick,
            CallbackInfoReturnable<Vec3> callback
    ) {
        float intensity =
                DetonationClient.falloutIntensity();

        if (intensity <= 0.001F) {
            return;
        }

        callback.setReturnValue(
                blendColour(
                        callback.getReturnValue(),
                        Math.clamp(
                                intensity
                                        * DetonationTuning.Fallout
                                        .CLOUD_COLOUR_STRENGTH,
                                0.0D,
                                1.0D
                        )
                )
        );
    }

    private static Vec3 blendColour(
            Vec3 original,
            double strength
    ) {
        return new Vec3(
                lerp(
                        original.x,
                        DetonationTuning.Fallout.RED,
                        strength
                ),
                lerp(
                        original.y,
                        DetonationTuning.Fallout.GREEN,
                        strength
                ),
                lerp(
                        original.z,
                        DetonationTuning.Fallout.BLUE,
                        strength
                )
        );
    }

    private static double lerp(
            double start,
            double end,
            double progress
    ) {
        return start
                + (
                end - start
        )
                * progress;
    }
}
