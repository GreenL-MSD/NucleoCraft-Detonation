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
        builder.pop();
        SPEC = builder.build();
    }

    private DetonationClientConfig() {
    }

    public static MushroomCloudRenderer mushroomCloudRenderer() {
        return MUSHROOM_CLOUD_RENDERER.get();
    }
}
