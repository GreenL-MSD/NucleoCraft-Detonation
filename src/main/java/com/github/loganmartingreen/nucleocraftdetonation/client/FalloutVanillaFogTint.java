package com.github.loganmartingreen.nucleocraftdetonation.client;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationClientConfig;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Colours Minecraft's already-computed distance fog, without adding a second
 * fog pass or changing density, draw distance, skybox, or Distant Horizons APIs.
 *
 * DH has its own fog pipeline. This event does not touch DH's settings; certain
 * DH presets may nevertheless inherit world fog colour. Verify in-game.
 */
@EventBusSubscriber(modid = NucleoCraftDetonation.MOD_ID, value = Dist.CLIENT)
public final class FalloutVanillaFogTint {
    private FalloutVanillaFogTint() {}

    @SubscribeEvent
    public static void onFogColour(ViewportEvent.ComputeFogColor event) {
        if (!DetonationClientConfig.falloutVanillaFogTint()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        // Never recolour underwater, lava or powder-snow vision.
        if (event.getCamera().getFluidInCamera() != FogType.NONE) return;

        float fallout = DetonationClient.falloutIntensity();
        if (fallout <= 0.001F) return;
        float intensity = Math.clamp(fallout, 0.0F, 1.0F);
        // Keep the horizon matched to Minecraft's existing, already-tinted
        // sky but lean it gently toward the *actual fallout dust* palette.
        // That palette is a desaturated olive-grey (#5A6C56), not neon green.
        // Using a sky/palette blend preserves sunlight and biome variation;
        // no additional fog layer, density or distance changes are made.
        Vec3 sky = minecraft.level.getSkyColor(event.getCamera().getPosition(),
                1.0F);
        event.setRed(FalloutAtmosphereColour.apply(event.getRed(), (float) sky.x,
                DetonationTuning.Fallout.RED, intensity));
        event.setGreen(FalloutAtmosphereColour.apply(event.getGreen(), (float) sky.y,
                DetonationTuning.Fallout.GREEN, intensity));
        event.setBlue(FalloutAtmosphereColour.apply(event.getBlue(), (float) sky.z,
                DetonationTuning.Fallout.BLUE, intensity));
    }
}
