package com.github.loganmartingreen.nucleocraftdetonation.client.render;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.compat.distanthorizons.DistantHorizonsCompat;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationClientConfig;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticleSystem;
import com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticleSystem.CloudSnapshot;
import com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticleSystem.UndergroundSnapshot;
import com.github.loganmartingreen.nucleocraftdetonation.particle.UndergroundVisualTimeline;
import com.github.loganmartingreen.nucleocraftdetonation.particle.SurfaceCapThermalLayer;
import com.github.loganmartingreen.nucleocraftdetonation.particle.SurfaceMushroomStem;
import net.minecraft.core.BlockPos;
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
        List<UndergroundSnapshot> underground = DetonationParticleSystem.snapshotUnderground();
        if (clouds.isEmpty() && underground.isEmpty()) {
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

            SurfaceMushroomStem.Frame stem = SurfaceMushroomStem.frame(
                    cloud.centreY(), cloud.capCentreY(), cloud.capVerticalRadius(),
                    cloud.visualScale(), cloud.ageTicks());
            double distanceSquared = SurfaceMushroomStem.distanceSquaredToCloud(
                    cameraPosition.x, cameraPosition.y, cameraPosition.z,
                    cloud.centreX(), cloud.centreZ(), stem,
                    cloud.capCentreY() + cloud.capVerticalRadius());
            double viewDistance = Math.max(
                    DetonationTuning.ParticleCloud.MINIMUM_VIEW_DISTANCE,
                    DetonationParticleSystem.getLateExpandedCloudRadius(cloud.visualScale())
                            * DetonationTuning.ParticleCloud.VIEW_DISTANCE_MULTIPLIER
            );
            if (distanceSquared > viewDistance * viewDistance) {
                continue;
            }

            renderCloud(poseStack, cameraPosition, cloud);
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            for (UndergroundSnapshot shot : underground) {
                double distanceSquared = cameraPosition.distanceToSqr(shot.x(), shot.y(), shot.z());
                double viewDistance = Math.max(220.0D, shot.scale() * 15.0D);
                if (distanceSquared <= viewDistance * viewDistance) {
                    renderUnderground(poseStack, cameraPosition, level, shot);
                }
            }
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

        // Pass 1: the slightly more solid inner stem plus the cap's opaque
        // heated body. Both sit beneath their smoke skins.
        addStemInnerBoxes(buffer, matrix, cloud);
        addCapThermalBoxes(buffer, matrix, cloud);
        addCapUndersideRing(buffer, matrix, cloud);
        addCraterRing(buffer, matrix, cloud);
        var mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }

        // Pass 2: a THIN translucent exterior, not a full cloud of nested grey
        // voxels. Do not write depth here: the solid inner layer remains visible
        // through the near-facing smoke. This avoids the old hidden-core bug.
        RenderSystem.depthMask(false);
        try {
            BufferBuilder smoke = Tesselator.getInstance().begin(
                    VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            addStemSmokeShellBoxes(smoke, matrix, cloud);
            addCapSmokeShellBoxes(smoke, matrix, cloud);
            var smokeMesh = smoke.build();
            if (smokeMesh != null) {
                BufferUploader.drawWithShader(smokeMesh);
            }
        } finally {
            RenderSystem.depthMask(true);
        }
        poseStack.popPose();
    }

    /** Underground fireball and breakout jet use the same timeline as particles. */
    private static void renderUnderground(PoseStack poses, Vec3 camera,
                                          ClientLevel level, UndergroundSnapshot shot) {
        poses.pushPose();
        poses.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = poses.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        double radius = UndergroundVisualTimeline.fireballRadius(
                shot.scale(), shot.age(), !shot.breakout());
        if (radius > 0.5D) {
            int slices = 12;
            double interval = Math.max(1.6D, radius / 4.0D);
            for (int layer = -slices / 2; layer <= slices / 2; layer++) {
                double vertical = layer / (double) (slices / 2);
                double y = shot.y() + radius * vertical
                        * UndergroundVisualTimeline.fireballVerticalRatio(!shot.breakout());
                double r = radius * Math.sqrt(Math.max(0.0D, 1.0D - vertical * vertical));
                double cooling = UndergroundVisualTimeline.smooth((shot.age() - 42.0D) / 80.0D);
                int rgb = UndergroundVisualTimeline.mix(0xFF8A2A, 0x444945, cooling);
                addVisibleDisc(buffer, matrix, level, shot.x(), y, shot.z(), r,
                        interval, interval * 1.15D, rgb, 245, layer * 19);
            }
        }
        if (shot.breakout() && shot.age() >= UndergroundVisualTimeline.JET_START_TICK) {
            double top = UndergroundVisualTimeline.jetTop(
                    shot.y(), shot.exitY(), shot.scale(), shot.age());
            double length = top - shot.y();
            if (length > 0.5D) {
                int slices = Math.clamp((int) Math.ceil(length / Math.max(1.5D, shot.scale() * 0.10D)),
                        12, 58);
                for (int layer = 0; layer <= slices; layer++) {
                    double h = layer / (double) slices;
                    double y = shot.y() + length * h;
                    double radiusAtY = UndergroundVisualTimeline.jetRadius(
                            shot.y(), shot.exitY(), y, shot.scale());
                    double cell = Math.max(1.5D, radiusAtY * 0.42D);
                    double cooling = UndergroundVisualTimeline.cooling(shot.age(), y, shot.y(), top);
                    int rgb = UndergroundVisualTimeline.jetColour(shot.age(), h, cooling);
                    int alpha = (int) Math.round(255.0D *
                            UndergroundVisualTimeline.jetAlpha(shot.age(), h, 0.3D));
                    if (alpha < 6) continue;
                    // The inner column stays upright and translucent; the
                    // radial alpha is calculated for each individual voxel.
                    addVisibleDisc(buffer, matrix, level,
                            shot.x(), y, shot.z(), radiusAtY,
                            cell, Math.max(cell * 1.2D, length / slices * 1.12D),
                            rgb, alpha, layer * 23, shot.age(), h);
                    if (layer % 3 == 0 && y >= shot.exitY()) {
                        addJetFringe(buffer, matrix, level, shot,
                                y, radiusAtY, cell, length / slices, h, top, layer);
                    }
                }
            }
        }
        // A voxel effect can produce no quads when the remaining fireball is
        // completely occluded by terrain or the jet has fully faded.
        // buildOrThrow() crashes in exactly that legitimate situation.
        // build() returns null for an empty frame, which should be skipped.
        var mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        poses.popPose();
    }

    /** Fixed upper bound on geometry to avoid overwhelming the game at huge yields. */
    private static void addVisibleDisc(BufferBuilder buffer, Matrix4f matrix,
            ClientLevel level, double x, double y, double z, double radius,
            double cell, double height, int rgb, int alpha, int seed) {
        addVisibleDisc(buffer, matrix, level, x, y, z, radius, cell,
                height, rgb, alpha, seed, -1, 0.0D);
    }

    private static void addVisibleDisc(BufferBuilder buffer, Matrix4f matrix,
            ClientLevel level, double x, double y, double z, double radius,
            double cell, double height, int rgb, int alpha, int seed,
            int jetAge, double heightProgress) {
        if (radius <= 0.0D) return;
        double spacing = Math.max(1.0D, cell * 0.78D);
        int extent = Math.min(13, (int) Math.ceil(radius / spacing));
        for (int gx = -extent; gx <= extent; gx++) {
            for (int gz = -extent; gz <= extent; gz++) {
                double dx = gx * spacing, dz = gz * spacing;
                double radial = Math.hypot(dx, dz) / Math.max(1.0D, radius);
                if (radial > 1.07D) continue;
                double bx = x + dx, bz = z + dz;
                // A true underground visual: never draw through still-solid terrain.
                if (!level.getBlockState(BlockPos.containing(bx, y, bz)).isAir()) continue;
                int fadeAlpha = jetAge >= 0
                        ? (int) Math.round(255.0D * UndergroundVisualTimeline.jetAlpha(
                                jetAge, heightProgress, radial))
                        : (int) (alpha * (1.0D - 0.08D *
                                UndergroundVisualTimeline.smooth((radial - 0.8D) / 0.27D)));
                if (fadeAlpha < 6) continue;
                int base = (fadeAlpha << 24) | rgb;
                addBoxCentred(buffer, matrix, bx, y, bz, cell, height,
                        organicShade(base, seed, gx, gz));
            }
        }
    }

    /** Light, ragged side shedding and an outward-breaking head. Never a cap. */
    private static void addJetFringe(BufferBuilder buffer, Matrix4f matrix,
            ClientLevel level, UndergroundSnapshot shot, double y, double radius,
            double cell, double sliceHeight, double heightProgress,
            double jetTop, int layer) {
        boolean head = heightProgress > 0.78D;
        double breakup = UndergroundVisualTimeline.breakup(shot.age(), heightProgress);
        int alpha = (int) Math.round(255.0D * UndergroundVisualTimeline.fringeAlpha(
                shot.age(), heightProgress, head));
        if (alpha < 6) return;
        double cool = UndergroundVisualTimeline.cooling(shot.age(), y, shot.y(), jetTop);
        int hot = UndergroundVisualTimeline.jetColour(shot.age(), heightProgress, cool);
        int rgb = UndergroundVisualTimeline.mix(hot, 0x747874, 0.32D + cool * 0.54D);
        int parts = head ? 16 : 10;
        for (int i = 0; i < parts; i++) {
            double angle = i * Math.PI * 2.0D / parts + layer * 0.37D;
            double irregular = (Math.sin(i * 12.73D + layer * 7.1D) + 1.0D) * 0.5D;
            double distance = radius * (1.05D + irregular * (0.27D + breakup * 0.65D));
            double x = shot.x() + Math.cos(angle) * distance;
            double z = shot.z() + Math.sin(angle) * distance;
            double fy = y + (irregular - 0.5D) * Math.max(cell, sliceHeight);
            if (!level.getBlockState(BlockPos.containing(x, fy, z)).isAir()) continue;
            int shade = UndergroundVisualTimeline.mix(rgb, 0x797C78, irregular * 0.20D);
            addBoxCentred(buffer, matrix, x, fy, z,
                    cell * (head ? 0.87D : 0.72D),
                    Math.max(cell, sliceHeight * 1.55D),
                    argb(alpha, (shade >> 16) & 255, (shade >> 8) & 255, shade & 255));
        }
    }

    /** Slightly more solid inset body for the rewritten v42.7 stem. */
    private static void addStemInnerBoxes(BufferBuilder buffer, Matrix4f matrix,
                                          CloudSnapshot cloud) {
        addStemComponent(buffer, matrix, cloud, true);
    }

    /** Thin translucent outer smoke skin for the rewritten v42.7 stem. */
    private static void addStemSmokeShellBoxes(BufferBuilder buffer, Matrix4f matrix,
                                               CloudSnapshot cloud) {
        addStemComponent(buffer, matrix, cloud, false);
    }

    private static void addStemComponent(BufferBuilder buffer, Matrix4f matrix,
                                         CloudSnapshot cloud, boolean inner) {
        SurfaceMushroomStem.Frame frame = SurfaceMushroomStem.frame(
                cloud.centreY(), cloud.capCentreY(), cloud.capVerticalRadius(),
                cloud.visualScale(), cloud.ageTicks());
        if (!frame.visible()) return;
        int slices = SurfaceMushroomStem.slices(frame);
        double verticalStep = frame.height() / Math.max(1.0D, slices - 1.0D);
        for (int layer = 0; layer < slices; layer++) {
            double h = layer / (double) (slices - 1);
            double radius = SurfaceMushroomStem.radius(frame, h);
            double innerRadius = SurfaceMushroomStem.innerRadius(frame, h);
            double cell = SurfaceMushroomStem.voxelSize(radius);
            double boxHeight = Math.max(cell * 1.18D, verticalStep * 1.20D);
            double cy = frame.bottomY() + frame.height() * h;
            double spacing = Math.max(1.2D, cell * 0.92D);
            int extent = Math.max(0, (int) Math.ceil(radius / spacing));
            for (int gx = -extent; gx <= extent; gx++) {
                for (int gz = -extent; gz <= extent; gz++) {
                    double x = gx * spacing, z = gz * spacing;
                    double distance = Math.hypot(x, z);
                    double usedRadius = inner ? innerRadius : radius;
                    if (distance > usedRadius + cell * 0.14D) continue;
                    if (!inner) {
                        double shellStart = Math.max(0.0D, innerRadius - cell * 0.22D);
                        if (distance < shellStart && h < 0.985D) continue;
                    }
                    double radial = distance / Math.max(1.0D, usedRadius);
                    int rgb = inner
                            ? SurfaceMushroomStem.innerColour(frame.age(), h, radial)
                            : SurfaceMushroomStem.shellColour(frame.scale(), frame.age(), h);
                    int alpha = inner
                            ? SurfaceMushroomStem.innerOpacity(frame.age(), radial, h)
                            : SurfaceMushroomStem.shellOpacity(frame.age(),
                                    distance / Math.max(1.0D, radius));
                    if (alpha <= 5) continue;
                    int colour = argb(alpha, (rgb >>> 16) & 255,
                            (rgb >>> 8) & 255, rgb & 255);
                    addBoxCentred(buffer, matrix, cloud.centreX() + x, cy,
                            cloud.centreZ() + z, cell, boxHeight,
                            organicShade(colour, layer * 17 + (inner ? 5 : 11), gx, gz));
                }
            }
        }
    }

    /** Separate thermal voxel component. Surface mushrooms only. */
    private static void addCapThermalBoxes(BufferBuilder buffer, Matrix4f matrix,
                                           CloudSnapshot cloud) {
        addCapComponent(buffer, matrix, cloud, true);
    }

    /** Single outer smoke layer; no nested grey voxels hiding the inner heat. */
    private static void addCapSmokeShellBoxes(BufferBuilder buffer, Matrix4f matrix,
                                              CloudSnapshot cloud) {
        addCapComponent(buffer, matrix, cloud, false);
    }

    private static void addCapComponent(BufferBuilder buffer, Matrix4f matrix,
                                        CloudSnapshot cloud, boolean thermal) {
        int layers = Math.max(DetonationTuning.DistantHorizons.CAP_LAYERS + 5, 12);
        double verticalSpan = cloud.capVerticalRadius() * 1.84D;
        double layerSpacing = verticalSpan / Math.max(1.0D, layers - 1.0D);
        for (int layer = 0; layer < layers; layer++) {
            double v = layers == 1 ? 0.0D
                    : layer / (double) (layers - 1) * 2.0D - 1.0D;
            double layerY = cloud.capCentreY() + v * cloud.capVerticalRadius() * 0.92D;
            double factor = Math.sqrt(Math.max(0.16D, 1.0D - v * v * 0.76D));
            double radius = cloud.capRadius() * (factor + 0.04D);
            double box = Math.max(4.0D,
                    radius * DetonationTuning.DistantHorizons.CAP_BOX_RADIUS_RATIO);
            double height = Math.max(4.0D,
                    Math.max(cloud.capVerticalRadius() * 0.34D, layerSpacing * 1.14D));
            addCapComponentDisc(buffer, matrix, cloud.centreX(), layerY, cloud.centreZ(),
                    radius, box, height, cloud.visualScale(), cloud.ageTicks(),
                    layer * 31, v, thermal);
        }
    }

    private static void addCapComponentDisc(BufferBuilder buffer, Matrix4f matrix,
            double cx, double y, double cz, double radius, double box,
            double height, float scale, int age, int seed, double v,
            boolean thermal) {
        double spacing = Math.max(1.0D, box * Math.min(0.72D,
                DetonationTuning.DistantHorizons.BOX_SPACING_RATIO));
        double coreRadius = SurfaceCapThermalLayer.coreRadius(radius, box);
        int extent = Math.max(0, (int) Math.ceil(radius / spacing));
        double accepted = radius + box * 0.44D;
        for (int gx = -extent; gx <= extent; gx++) {
            for (int gz = -extent; gz <= extent; gz++) {
                double px = gx * spacing, pz = gz * spacing;
                double distance = Math.hypot(px, pz);
                if (thermal) {
                    if (distance > coreRadius) continue;
                } else {
                    if (distance > accepted || !SurfaceCapThermalLayer.smokeCell(
                            distance, radius, box, v)) continue;
                }
                int rgb = thermal
                        ? SurfaceCapThermalLayer.thermalRgb(age,
                                distance / Math.max(1.0D, coreRadius), v)
                        : DetonationParticleSystem.getCapShellColour(scale, age, 0.99D);
                int fade = DetonationParticleSystem.getCloudVoxelAlpha(age);
                int alpha = thermal ? fade : (int) Math.round(
                        SurfaceCapThermalLayer.smokeAlpha(v) * fade / 255.0D);
                if (alpha < 6) continue;
                int colour = argb(alpha, (rgb >>> 16) & 255, (rgb >>> 8) & 255, rgb & 255);
                addBoxCentred(buffer, matrix, cx + px, y, cz + pz,
                        box, height, organicShade(colour, seed, gx, gz));
            }
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
        // The dark underside ring must accumulate over time instead of
        // appearing as a fully opaque mechanical collar in a single frame.
        double visibility = DetonationParticleSystem.getCapUndersideRingVisibility(
                cloud.visualScale(), cloud.ageTicks());
        if (visibility <= 0.0D) return;

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
                    ? argb((int) Math.round(230 * visibility), 75, 74, 70)
                    : argb((int) Math.round(235 * visibility), 41, 44, 43);
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

    private static int stemRingColour(float scale, int age, double height, int shade) {
        int rgb = DetonationParticleSystem.getStemBandColour(scale, age, height,
                DetonationParticleSystem.getStemLayerColour(scale, age, height, shade));
        int alpha = (int) Math.round(255.0D *
                (1.0D - DetonationParticleSystem.getStemDissipation(age)));
        return argb(alpha, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
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
