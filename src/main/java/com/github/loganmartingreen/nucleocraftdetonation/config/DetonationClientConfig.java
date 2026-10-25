package com.github.loganmartingreen.nucleocraftdetonation.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only visual preferences generated as nucleocraft_detonation-client.toml. */
public final class DetonationClientConfig {
    public enum MushroomCloudRenderer {
        PARTICLES,
        VOXEL
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<MushroomCloudRenderer> MUSHROOM_CLOUD_RENDERER;
    public static final ModConfigSpec.BooleanValue FALLOUT_VANILLA_FOG_TINT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("visuals");
        MUSHROOM_CLOUD_RENDERER = builder
                .comment(
                        "Close-range mushroom cloud renderer.",
                        "PARTICLES keeps the original poof-particle cloud.",
                        "VOXEL uses the filled LOD-style cloud geometry nearby.",
                        "Distant Horizons keeps the same existing far-distance handoff in both modes."
                )
                .defineEnum(
                        "mushroomCloudRenderer",
                        MushroomCloudRenderer.PARTICLES
                );
        FALLOUT_VANILLA_FOG_TINT = builder
                .comment(
                        "Tint only Minecraft's pre-existing fog colour when standing in fallout.",
                        "Does not modify fog distance, density or Distant Horizons fog settings.",
                        "Some DH fog presets may sample Minecraft's world fog colour."
                )
                .define("falloutVanillaFogTint", true);
        builder.pop();
        SPEC = builder.build();
    }

    private DetonationClientConfig() {
    }

    public static boolean falloutVanillaFogTint() {
        return FALLOUT_VANILLA_FOG_TINT.get();
    }

    public static MushroomCloudRenderer mushroomCloudRenderer() {
        return MUSHROOM_CLOUD_RENDERER.get();
    }
}
