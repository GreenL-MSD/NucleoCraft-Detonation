package com.github.loganmartingreen.nucleocraftdetonation.client.render;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.compat.distanthorizons.DistantHorizonsCompat;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationClientConfig;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticleSystem;
import com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticleSystem.CloudSnapshot;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix4f;

import java.util.List;

/**
 * Close-range filled voxel mushroom cloud renderer.
 *
 * <p>This intentionally mirrors the geometry formulas used by the Distant
 * Horizons cloud. The existing DH model remains authoritative once its normal
 * handoff distance is reached; without DH, the voxel renderer remains usable
 * as the normal close cloud renderer.</p>
 */
@EventBusSubscriber(
        modid = NucleoCraftDetonation.MOD_ID,
        value = Dist.CLIENT
)
public final class DetonationVoxelCloudRenderer {
    private DetonationVoxelCloudRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || DetonationClientConfig.mushroomCloudRenderer()
                != DetonationClientConfig.MushroomCloudRenderer.VOXEL) {
            return;
        }

        List<CloudSnapshot> clouds = DetonationParticleSystem.snapshotClouds();
        if (clouds.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        if (poseStack == null) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.getPosition();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        for (CloudSnapshot cloud : clouds) {
            if (DistantHorizonsCompat.isDistantCloudActive(
                    cloud.centreX(),
                    cloud.centreY(),
                    cloud.centreZ(),
                    cloud.visualScale()
            )) {
                continue;
            }

            double dx = cameraPosition.x - cloud.centreX();
            double dy = cameraPosition.y - (cloud.centreY() + cloud.stemHeight() * 0.55D);
            double dz = cameraPosition.z - cloud.centreZ();
            double viewDistance = Math.max(
                    DetonationTuning.ParticleCloud.MINIMUM_VIEW_DISTANCE,
                    DetonationParticleSystem.getLateExpandedCloudRadius(cloud.visualScale())
                            * DetonationTuning.ParticleCloud.VIEW_DISTANCE_MULTIPLIER
            );
            if (dx * dx + dy * dy + dz * dz > viewDistance * viewDistance) {
                continue;
            }

            renderCloud(poseStack, cameraPosition, cloud);
        }

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderCloud(
            PoseStack poseStack,
            Vec3 cameraPosition,
            CloudSnapshot cloud
    ) {
        poseStack.pushPose();
        poseStack.translate(
                -cameraPosition.x,
                -cameraPosition.y,
                -cameraPosition.z
        );

        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR
        );

        addStemBoxes(buffer, matrix, cloud);
        addCapBoxes(buffer, matrix, cloud);
        addCapUndersideRing(buffer, matrix, cloud);
        addCraterRing(buffer, matrix, cloud);

        BufferUploader.drawWithShader(buffer.buildOrThrow());
        poseStack.popPose();
    }

    private static void addStemBoxes(
            BufferBuilder buffer,
            Matrix4f matrix,
            CloudSnapshot cloud
    ) {
        if (cloud.stemHeight() <= 1.0D) {
            return;
        }

        double matureRadius = DetonationParticleSystem.getMatureCloudRadius(cloud.visualScale());
        int layers = DetonationTuning.DistantHorizons.STEM_LAYERS;

        for (int layer = 0; layer < layers; layer++) {
            double heightProgress = layer / (double) Math.max(1, layers - 1);
            double verticalTaper = 1.05D - 0.47D * smoothstep(heightProgress);
            double bulge = 0.96D + 0.10D * Math.sin(heightProgress * Math.PI);
            double middleCurve = 1.0D
                    - DetonationTuning.ParticleCloud.STEM_MIDDLE_CURVE_STRENGTH
                    * Math.pow(
                    Math.sin(heightProgress * Math.PI),
                    DetonationTuning.ParticleCloud.STEM_MIDDLE_CURVE_POWER
            );
            double localRadius = matureRadius
                    * DetonationTuning.ParticleCloud.STEM_RADIUS_RATIO
                    * verticalTaper
                    * bulge
                    * middleCurve;
            double y = cloud.stemBaseY() + cloud.stemHeight() * heightProgress;
            double boxSize = Math.max(
                    1.75D,
                    localRadius * 0.30D
            );
            double layerSpacing = cloud.stemHeight() / Math.max(1.0D, layers - 1.0D);
            double boxHeight = Math.max(boxSize * 1.20D, layerSpacing * 1.16D);

            addFilledDiscBoxes(
                    buffer,
                    matrix,
                    cloud.centreX(),
                    y,
                    cloud.centreZ(),
                    localRadius,
                    boxSize,
                    boxHeight,
                    cloudColour(cloud.visualScale(), cloud.ageTicks(), -12),
                    layer * 17
            );
        }
    }

    private static void addCapBoxes(
            BufferBuilder buffer,
            Matrix4f matrix,
            CloudSnapshot cloud
    ) {
        int layers = Math.max(DetonationTuning.DistantHorizons.CAP_LAYERS + 2, 6);
        int baseColour = cloudColour(cloud.visualScale(), cloud.ageTicks(), 0);
        double verticalSpan = cloud.capVerticalRadius() * 1.84D;
        double layerSpacing = verticalSpan / Math.max(1.0D, layers - 1.0D);

        for (int layer = 0; layer < layers; layer++) {
            double verticalNormal = layers == 1
                    ? 0.0D
                    : (layer / (double) (layers - 1)) * 2.0D - 1.0D;
            double layerY = cloud.capCentreY()
                    + verticalNormal * cloud.capVerticalRadius() * 0.92D;
            double radiusFactor = Math.sqrt(
                    Math.max(0.16D, 1.0D - verticalNormal * verticalNormal * 0.76D)
            );
            double layerRadius = cloud.capRadius() * (radiusFactor + 0.04D);
            double boxSize = Math.max(
                    4.0D,
                    layerRadius * DetonationTuning.DistantHorizons.CAP_BOX_RADIUS_RATIO
            );
            double boxHeight = Math.max(
                    4.0D,
                    Math.max(cloud.capVerticalRadius() * 0.34D, layerSpacing * 1.14D)
            );

            addFilledDiscBoxes(
                    buffer,
                    matrix,
                    cloud.centreX(),
                    layerY,
                    cloud.centreZ(),
                    layerRadius,
                    boxSize,
                    boxHeight,
                    baseColour,
                    layer * 31
            );
        }
    }

    private static void addCapUndersideRing(
            BufferBuilder buffer,
            Matrix4f matrix,
            CloudSnapshot cloud
    ) {
        if (!cloud.atMaximumHeight()) {
            return;
        }

        double ringRadius = cloud.capRadius()
                * DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_RADIUS_RATIO;
        double ringY = cloud.capCentreY()
                - cloud.capVerticalRadius()
                - Math.max(
                DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_MINIMUM_GAP,
                cloud.capVerticalRadius()
                        * DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_GAP_RATIO
        );
        double boxSize = Math.max(3.0D, ringRadius * 0.13D);
        int segments = getOverlappingRingSegments(ringRadius, boxSize);

        for (int segment = 0; segment < segments; segment++) {
            double angle = segment / (double) segments * Math.PI * 2.0D;
            int colour = segment % 4 == 0
                    ? argb(230, 75, 74, 70)
                    : argb(235, 41, 44, 43);
            addBoxCentred(
                    buffer,
                    matrix,
                    cloud.centreX() + Math.cos(angle) * ringRadius,
                    ringY,
                    cloud.centreZ() + Math.sin(angle) * ringRadius,
                    boxSize,
                    boxSize * 0.90D,
                    colour
            );
        }
    }

    private static void addCraterRing(
            BufferBuilder buffer,
            Matrix4f matrix,
            CloudSnapshot cloud
    ) {
        double visibility = DetonationParticleSystem.getCraterRingVisibility(cloud.ageTicks());
        if (visibility <= 0.0D) {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        double radius = cloud.visualScale();
        double boxSize = Math.max(3.0D, radius * 0.12D);
        int segments = getOverlappingRingSegments(radius, boxSize);
        int alpha = (int) Math.round(225.0D * visibility);

        for (int segment = 0; segment < segments; segment++) {
            double angle = segment / (double) segments * Math.PI * 2.0D;
            double x = cloud.centreX() + Math.cos(angle) * radius;
            double z = cloud.centreZ() + Math.sin(angle) * radius;
            double y = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Mth.floor(x),
                    Mth.floor(z)
            ) + 0.35D;
            int colour = segment % 3 == 0
                    ? argb(Math.max(1, alpha - 5), 104, 64, 53)
                    : argb(alpha, 75, 74, 70);
            addBoxCentred(
                    buffer,
                    matrix,
                    x,
                    y,
                    z,
                    boxSize,
                    boxSize * 0.70D,
                    colour
            );
        }
    }

    private static void addFilledDiscBoxes(
            BufferBuilder buffer,
            Matrix4f matrix,
            double centreX,
            double centreY,
            double centreZ,
            double radius,
            double boxSize,
            double boxHeight,
            int baseColour,
            int shadeSeed
    ) {
        if (radius <= 0.0D || boxSize <= 0.0D) {
            return;
        }

        double spacing = Math.max(
                1.0D,
                boxSize * DetonationTuning.DistantHorizons.BOX_SPACING_RATIO
        );
        int extent = Math.max(0, (int) Math.ceil(radius / spacing));
        double acceptedRadius = radius + boxSize * 0.28D;
        double acceptedRadiusSquared = acceptedRadius * acceptedRadius;

        for (int gridX = -extent; gridX <= extent; gridX++) {
            double offsetX = gridX * spacing;
            for (int gridZ = -extent; gridZ <= extent; gridZ++) {
                double offsetZ = gridZ * spacing;
                if (offsetX * offsetX + offsetZ * offsetZ > acceptedRadiusSquared) {
                    continue;
                }

                addBoxCentred(
                        buffer,
                        matrix,
                        centreX + offsetX,
                        centreY,
                        centreZ + offsetZ,
                        boxSize,
                        boxHeight,
                        organicShade(baseColour, shadeSeed, gridX, gridZ)
                );
            }
        }
    }

    private static int getOverlappingRingSegments(double radius, double boxSize) {
        double spacing = Math.max(
                1.0D,
                boxSize * DetonationTuning.DistantHorizons.BOX_SPACING_RATIO
        );
        int calculated = (int) Math.ceil(
                Math.PI * 2.0D * Math.max(1.0D, radius) / spacing
        );
        return Math.clamp(
                calculated,
                DetonationTuning.DistantHorizons.RING_MINIMUM_SEGMENTS,
                DetonationTuning.DistantHorizons.RING_MAXIMUM_SEGMENTS
        );
    }

    private static void addBoxCentred(
            BufferBuilder buffer,
            Matrix4f matrix,
            double x,
            double y,
            double z,
            double horizontalSize,
            double verticalSize,
            int colour
    ) {
        float x0 = (float) (x - horizontalSize * 0.5D);
        float x1 = (float) (x + horizontalSize * 0.5D);
        float y0 = (float) (y - verticalSize * 0.5D);
        float y1 = (float) (y + verticalSize * 0.5D);
        float z0 = (float) (z - horizontalSize * 0.5D);
        float z1 = (float) (z + horizontalSize * 0.5D);

        // Bottom / top.
        quad(buffer, matrix, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, colour);
        quad(buffer, matrix, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, colour);
        // North / south.
        quad(buffer, matrix, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, colour);
        quad(buffer, matrix, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, colour);
        // West / east.
        quad(buffer, matrix, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, colour);
        quad(buffer, matrix, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, colour);
    }

    private static void quad(
            BufferBuilder buffer,
            Matrix4f matrix,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            int colour
    ) {
        buffer.addVertex(matrix, x0, y0, z0).setColor(colour);
        buffer.addVertex(matrix, x1, y1, z1).setColor(colour);
        buffer.addVertex(matrix, x2, y2, z2).setColor(colour);
        buffer.addVertex(matrix, x3, y3, z3).setColor(colour);
    }

    private static int cloudColour(float visualScale, int ageTicks, int shadeOffset) {
        int rgb = DetonationParticleSystem.adjustRgb(
                DetonationParticleSystem.getCloudColour(visualScale, ageTicks),
                shadeOffset
        );
        return argb(
                DetonationParticleSystem.getCloudVoxelAlpha(ageTicks),
                (rgb >>> 16) & 0xFF,
                (rgb >>> 8) & 0xFF,
                rgb & 0xFF
        );
    }

    private static int organicShade(
            int colour,
            int layerSeed,
            int gridX,
            int gridZ
    ) {
        long hash = cloudHash(layerSeed, gridX, gridZ);
        double cellNoise = unitNoise(hash);
        double patchNoise = unitNoise(cloudHash(
                layerSeed * 7 + 11,
                Math.floorDiv(gridX, 2),
                Math.floorDiv(gridZ, 2)
        ));
        int offset = (int) Math.round(
                -20.0D + cellNoise * 24.0D + (patchNoise - 0.5D) * 18.0D
        );
        int alpha = (colour >>> 24) & 0xFF;
        int rgb = DetonationParticleSystem.adjustRgb(colour & 0xFFFFFF, offset);
        return argb(alpha, (rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF);
    }

    private static long cloudHash(int layerSeed, int gridX, int gridZ) {
        long value = 0x9E3779B97F4A7C15L;
        value ^= (long) layerSeed * 0xBF58476D1CE4E5B9L;
        value ^= (long) gridX * 0x94D049BB133111EBL;
        value ^= (long) gridZ * 0xD6E8FEB86659FD93L;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static double unitNoise(long value) {
        return ((value >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (alpha & 0xFF) << 24
                | (red & 0xFF) << 16
                | (green & 0xFF) << 8
                | (blue & 0xFF);
    }

    private static double smoothstep(double value) {
        value = Math.clamp(value, 0.0D, 1.0D);
        return value * value * (3.0D - 2.0D * value);
    }
}
