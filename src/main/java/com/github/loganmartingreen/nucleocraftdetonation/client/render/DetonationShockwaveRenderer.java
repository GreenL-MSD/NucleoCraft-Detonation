package com.github.loganmartingreen.nucleocraftdetonation.client.render;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticleSystem;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Renders both detonation shockwaves as actual translucent geometry rather
 * than smoke particles.
 *
 * <p>The ground wall and atmospheric waves share one expansion curve. The
 * ground wall has a fixed radial lead, while a second atmospheric ring travels
 * above the first to create the stacked cloud-layer shockwaves.</p>
 */
@EventBusSubscriber(
        modid = NucleoCraftDetonation.MOD_ID,
        value = Dist.CLIENT
)
public final class DetonationShockwaveRenderer {
    private static final int SEGMENTS = 128;

    private static final int PALE_CYAN = 0xDDFBFF;
    private static final int WHITE = 0xFFFFFF;

    private static final List<ActiveShockwave> ACTIVE_SHOCKWAVES =
            new ArrayList<>();

    private DetonationShockwaveRenderer() {
    }

    public static void start(
            double centreX,
            double centreY,
            double centreZ,
            float visualScale,
            float strength
    ) {
        ACTIVE_SHOCKWAVES.add(
                new ActiveShockwave(
                        centreX,
                        centreY,
                        centreZ,
                        Math.max(1.0F, visualScale),
                        Math.clamp(strength, 0.0F, 1.0F),
                        Double.doubleToLongBits(centreX)
                                ^ Long.rotateLeft(Double.doubleToLongBits(centreZ), 21)
                )
        );
    }

    public static void tick() {
        Iterator<ActiveShockwave> iterator = ACTIVE_SHOCKWAVES.iterator();

        while (iterator.hasNext()) {
            ActiveShockwave wave = iterator.next();
            wave.ageTicks++;

            int lastTick =
                    DetonationTuning.ParticleCloud.SHOCKWAVE_DELAY_TICKS
                            + DetonationTuning.ParticleCloud.UPPER_SKY_SHOCKWAVE_EXTRA_DELAY_TICKS
                            + DetonationTuning.ParticleCloud.SHOCKWAVE_DURATION_TICKS;

            if (wave.ageTicks > lastTick) {
                iterator.remove();
            }
        }
    }

    public static void clear() {
        ACTIVE_SHOCKWAVES.clear();
    }

    @SubscribeEvent
    public static void onRenderLevel(
            RenderLevelStageEvent event
    ) {
        if (
                event.getStage()
                        != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                        || ACTIVE_SHOCKWAVES.isEmpty()
        ) {
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
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        for (ActiveShockwave wave : ACTIVE_SHOCKWAVES) {
            renderWave(
                    poseStack,
                    cameraPosition,
                    wave
            );
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderWave(
            PoseStack poseStack,
            Vec3 cameraPosition,
            ActiveShockwave wave
    ) {
        renderGroundWave(
                poseStack,
                cameraPosition,
                wave
        );

        renderSkyWave(
                poseStack,
                cameraPosition,
                wave
        );
    }

    private static void renderGroundWave(
            PoseStack poseStack,
            Vec3 cameraPosition,
            ActiveShockwave wave
    ) {
        double progress = getProgress(
                wave.ageTicks,
                DetonationTuning.ParticleCloud.SHOCKWAVE_DELAY_TICKS,
                DetonationTuning.ParticleCloud.SHOCKWAVE_DURATION_TICKS
        );

        if (progress < 0.0D || progress > 1.0D) {
            return;
        }

        /*
         * The surface wall uses the same progress curve as the sky waves, then
         * receives a fixed radial offset. It therefore expands at the same
         * speed while staying visibly in front for the entire journey.
         */
        double radius = getSharedShockwaveRadius(
                wave.visualScale,
                progress
        ) + DetonationParticleSystem.getFireballRadius(wave.visualScale)
                * DetonationTuning.ParticleCloud.GROUND_SHOCKWAVE_RADIUS_LEAD_MULTIPLIER;

        double thickness = Math.max(
                1.25D,
                DetonationTuning.ParticleCloud.SURFACE_SHOCKWAVE_RING_THICKNESS
        );

        /*
         * Keep the restored vertical shock front while allowing its radius to
         * run ahead of the two atmospheric rings.
         */
        double height = Math.clamp(
                2.0D + wave.visualScale * 0.035D,
                2.0D,
                8.0D
        );

        float fade = shockwaveFade(progress) * wave.strength;

        poseStack.pushPose();
        poseStack.translate(
                wave.centreX - cameraPosition.x,
                wave.centreY + 1.10D - cameraPosition.y,
                wave.centreZ - cameraPosition.z
        );

        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR
        );

        addHorizontalBand(
                buffer,
                matrix,
                radius,
                thickness * 1.25D,
                0.02F,
                argb(fade * 0.22F, PALE_CYAN),
                wave.seed
        );

        addHorizontalBand(
                buffer,
                matrix,
                radius,
                thickness * 0.66D,
                0.05F,
                argb(fade * 0.82F, PALE_CYAN),
                wave.seed
        );

        addHorizontalBand(
                buffer,
                matrix,
                radius,
                thickness * 0.24D,
                0.08F,
                argb(fade, WHITE),
                wave.seed
        );

        addVerticalRingWall(
                buffer,
                matrix,
                radius,
                thickness * 0.52D,
                height,
                argb(fade * 0.60F, PALE_CYAN),
                argb(fade * 0.08F, PALE_CYAN),
                wave.seed
        );

        BufferUploader.drawWithShader(buffer.buildOrThrow());
        poseStack.popPose();
    }

    private static void renderSkyWave(
            PoseStack poseStack,
            Vec3 cameraPosition,
            ActiveShockwave wave
    ) {
        double lowerProgress = getProgress(
                wave.ageTicks,
                DetonationTuning.ParticleCloud.SHOCKWAVE_DELAY_TICKS,
                DetonationTuning.ParticleCloud.SHOCKWAVE_DURATION_TICKS
        );

        if (lowerProgress >= 0.0D && lowerProgress <= 1.0D) {
            double lowerY = DetonationParticleSystem.cloudTopY(
                    wave.centreY,
                    wave.visualScale,
                    wave.ageTicks
            );

            renderAtmosphericRing(
                    poseStack,
                    cameraPosition,
                    wave,
                    lowerProgress,
                    lowerY,
                    1.0F,
                    wave.seed ^ 0x51D7348DL,
                    true
            );
        }

        double upperProgress = getProgress(
                wave.ageTicks,
                DetonationTuning.ParticleCloud.SHOCKWAVE_DELAY_TICKS
                        + DetonationTuning.ParticleCloud.UPPER_SKY_SHOCKWAVE_EXTRA_DELAY_TICKS,
                DetonationTuning.ParticleCloud.SHOCKWAVE_DURATION_TICKS
        );

        if (upperProgress >= 0.0D && upperProgress <= 1.0D) {
            double baseY = DetonationParticleSystem.cloudTopY(
                    wave.centreY,
                    wave.visualScale,
                    wave.ageTicks
            );

            double upperOffset = Math.clamp(
                    DetonationParticleSystem.getFireballRadius(wave.visualScale)
                            * DetonationTuning.ParticleCloud.UPPER_SKY_SHOCKWAVE_HEIGHT_RADIUS_RATIO,
                    7.0D,
                    48.0D
            );

            renderAtmosphericRing(
                    poseStack,
                    cameraPosition,
                    wave,
                    upperProgress,
                    baseY + upperOffset,
                    DetonationTuning.ParticleCloud.UPPER_SKY_SHOCKWAVE_SCALE,
                    wave.seed ^ 0x6EEB27A91L,
                    false
            );
        }
    }

    private static void renderAtmosphericRing(
            PoseStack poseStack,
            Vec3 cameraPosition,
            ActiveShockwave wave,
            double progress,
            double y,
            float visualStrength,
            long seed,
            boolean renderCentrePatch
    ) {
        double radius = getSharedShockwaveRadius(
                wave.visualScale,
                progress
        );

        double thickness = Math.max(
                3.0D,
                DetonationTuning.ParticleCloud.SKY_SHOCKWAVE_RING_THICKNESS
                        * visualStrength
        );

        float fade = shockwaveFade(progress)
                * wave.strength
                * visualStrength;

        poseStack.pushPose();
        poseStack.translate(
                wave.centreX - cameraPosition.x,
                y - cameraPosition.y,
                wave.centreZ - cameraPosition.z
        );

        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR
        );

        addHorizontalBand(
                buffer,
                matrix,
                radius,
                thickness * 1.60D,
                0.00F,
                argb(fade * 0.18F, PALE_CYAN),
                seed
        );

        addHorizontalBand(
                buffer,
                matrix,
                radius,
                thickness,
                0.04F,
                argb(fade * 0.72F, PALE_CYAN),
                seed
        );

        addHorizontalBand(
                buffer,
                matrix,
                radius,
                thickness * 0.34D,
                0.08F,
                argb(fade, WHITE),
                seed
        );

        if (renderCentrePatch && progress < 0.58D) {
            addFilledDisc(
                    buffer,
                    matrix,
                    Math.max(3.0D, radius * 0.075D),
                    0.12F,
                    argb(fade * (float) (1.0D - progress / 0.58D), WHITE)
            );
        }

        BufferUploader.drawWithShader(buffer.buildOrThrow());
        poseStack.popPose();
    }

    private static double getSharedShockwaveRadius(
            float visualScale,
            double progress
    ) {
        double matureCloudRadius = DetonationParticleSystem.getMatureCloudRadius(
                visualScale
        );

        double maximumRadius = Math.max(
                DetonationTuning.ParticleCloud.MINIMUM_SHOCKWAVE_RADIUS,
                matureCloudRadius
                        * DetonationTuning.ParticleCloud.SHOCKWAVE_RADIUS_MULTIPLIER
        );

        double initialRadius = Math.max(
                6.0D,
                DetonationParticleSystem.getFireballRadius(visualScale) * 0.80D
        );

        return initialRadius
                + (maximumRadius - initialRadius) * smoothstep(progress);
    }

    private static void addHorizontalBand(
            BufferBuilder buffer,
            Matrix4f matrix,
            double radius,
            double thickness,
            float y,
            int colour,
            long seed
    ) {
        double innerBase = Math.max(0.0D, radius - thickness * 0.5D);
        double outerBase = radius + thickness * 0.5D;

        for (int segment = 0; segment < SEGMENTS; segment++) {
            int next = (segment + 1) % SEGMENTS;

            double angleA = segment * MthTwoPi() / SEGMENTS;
            double angleB = next * MthTwoPi() / SEGMENTS;

            double jitterA = edgeJitter(segment, seed, thickness);
            double jitterB = edgeJitter(next, seed, thickness);

            double innerA = Math.max(0.0D, innerBase + jitterA * 0.40D);
            double innerB = Math.max(0.0D, innerBase + jitterB * 0.40D);
            double outerA = Math.max(innerA, outerBase + jitterA);
            double outerB = Math.max(innerB, outerBase + jitterB);

            float innerAx = (float) (Math.cos(angleA) * innerA);
            float innerAz = (float) (Math.sin(angleA) * innerA);
            float innerBx = (float) (Math.cos(angleB) * innerB);
            float innerBz = (float) (Math.sin(angleB) * innerB);
            float outerAx = (float) (Math.cos(angleA) * outerA);
            float outerAz = (float) (Math.sin(angleA) * outerA);
            float outerBx = (float) (Math.cos(angleB) * outerB);
            float outerBz = (float) (Math.sin(angleB) * outerB);

            buffer.addVertex(matrix, innerAx, y, innerAz).setColor(colour);
            buffer.addVertex(matrix, innerBx, y, innerBz).setColor(colour);
            buffer.addVertex(matrix, outerBx, y, outerBz).setColor(colour);
            buffer.addVertex(matrix, outerAx, y, outerAz).setColor(colour);
        }
    }

    private static void addVerticalRingWall(
            BufferBuilder buffer,
            Matrix4f matrix,
            double radius,
            double thickness,
            double height,
            int lowerColour,
            int upperColour,
            long seed
    ) {
        double centreRadius = radius + thickness * 0.10D;

        for (int segment = 0; segment < SEGMENTS; segment++) {
            int next = (segment + 1) % SEGMENTS;

            double angleA = segment * MthTwoPi() / SEGMENTS;
            double angleB = next * MthTwoPi() / SEGMENTS;

            double radiusA = centreRadius
                    + edgeJitter(segment, seed, thickness);
            double radiusB = centreRadius
                    + edgeJitter(next, seed, thickness);

            float ax = (float) (Math.cos(angleA) * radiusA);
            float az = (float) (Math.sin(angleA) * radiusA);
            float bx = (float) (Math.cos(angleB) * radiusB);
            float bz = (float) (Math.sin(angleB) * radiusB);

            buffer.addVertex(matrix, ax, 0.0F, az).setColor(lowerColour);
            buffer.addVertex(matrix, bx, 0.0F, bz).setColor(lowerColour);
            buffer.addVertex(matrix, bx, (float) height, bz).setColor(upperColour);
            buffer.addVertex(matrix, ax, (float) height, az).setColor(upperColour);
        }
    }

    private static void addFilledDisc(
            BufferBuilder buffer,
            Matrix4f matrix,
            double radius,
            float y,
            int colour
    ) {
        for (int segment = 0; segment < SEGMENTS; segment++) {
            int next = (segment + 1) % SEGMENTS;
            double angleA = segment * MthTwoPi() / SEGMENTS;
            double angleB = next * MthTwoPi() / SEGMENTS;

            float ax = (float) (Math.cos(angleA) * radius);
            float az = (float) (Math.sin(angleA) * radius);
            float bx = (float) (Math.cos(angleB) * radius);
            float bz = (float) (Math.sin(angleB) * radius);

            buffer.addVertex(matrix, 0.0F, y, 0.0F).setColor(colour);
            buffer.addVertex(matrix, ax, y, az).setColor(colour);
            buffer.addVertex(matrix, bx, y, bz).setColor(colour);
            buffer.addVertex(matrix, 0.0F, y, 0.0F).setColor(colour);
        }
    }

    private static double getProgress(
            int age,
            int delay,
            int duration
    ) {
        if (age < delay) {
            return -1.0D;
        }

        return (age - delay) / (double) Math.max(1, duration);
    }

    private static float shockwaveFade(
            double progress
    ) {
        double fadeIn = Math.clamp(progress / 0.08D, 0.0D, 1.0D);
        double fadeOut = Math.clamp((1.0D - progress) / 0.36D, 0.0D, 1.0D);
        return (float) (fadeIn * fadeOut);
    }

    private static double smoothstep(
            double value
    ) {
        value = Math.clamp(value, 0.0D, 1.0D);
        return value * value * (3.0D - 2.0D * value);
    }

    private static double edgeJitter(
            int segment,
            long seed,
            double thickness
    ) {
        long value = seed
                + segment * 0x9E3779B97F4A7C15L;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;

        double normalised = ((value >>> 11) * 0x1.0p-53) * 2.0D - 1.0D;
        return normalised * thickness * 0.24D;
    }

    private static int argb(
            float alpha,
            int rgb
    ) {
        int alphaByte = Math.clamp(
                Math.round(Math.clamp(alpha, 0.0F, 1.0F) * 255.0F),
                0,
                255
        );

        return alphaByte << 24
                | rgb & 0xFFFFFF;
    }

    private static double MthTwoPi() {
        return Math.PI * 2.0D;
    }

    private static final class ActiveShockwave {
        private final double centreX;
        private final double centreY;
        private final double centreZ;
        private final float visualScale;
        private final float strength;
        private final long seed;

        private int ageTicks;

        private ActiveShockwave(
                double centreX,
                double centreY,
                double centreZ,
                float visualScale,
                float strength,
                long seed
        ) {
            this.centreX = centreX;
            this.centreY = centreY;
            this.centreZ = centreZ;
            this.visualScale = visualScale;
            this.strength = strength;
            this.seed = seed;
        }
    }
}
