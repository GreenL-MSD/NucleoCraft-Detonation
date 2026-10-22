package com.github.loganmartingreen.nucleocraftdetonation.particle;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.compat.distanthorizons.DistantHorizonsCompat;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationClientConfig;
import com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticles.TintedPoofOptions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Generates the fireball and mushroom cloud using a custom, tinted version of
 * the vanilla poof sprite animation.
 *
 * <p>All particle layout and colour progression lives here rather than inside
 * the main client event class. The shockwave geometry is handled separately by
 * {@code DetonationShockwaveRenderer}.</p>
 */
@EventBusSubscriber(
        modid = NucleoCraftDetonation.MOD_ID,
        value = Dist.CLIENT
)
public final class DetonationParticleSystem {
    private static final int WHITE_HOT = 0xFFFDF2;
    private static final int PALE_YELLOW = 0xFFF1A8;
    private static final int HOT_YELLOW = 0xFFD45A;
    private static final int BRIGHT_ORANGE = 0xFF8A2A;
    private static final int DEEP_ORANGE = 0xB8461F;
    private static final int BURNT_BROWN = 0x684035;
    private static final int WARM_GREY = 0x4B4A46;
    private static final int DARK_GREY = 0x292C2B;
    private static final int NEAR_BLACK = 0x111312;

    private static final List<ActiveCloud> ACTIVE_CLOUDS =
            new ArrayList<>();

    private DetonationParticleSystem() {
    }

    @SubscribeEvent
    public static void registerProviders(
            RegisterParticleProvidersEvent event
    ) {
        event.registerSpriteSet(
                DetonationParticles.TINTED_POOF.get(),
                TintedPoofProvider::new
        );
    }

    public static void start(
            double centreX,
            double centreY,
            double centreZ,
            float visualScale,
            float strength
    ) {
        start(
                centreX,
                centreY,
                centreZ,
                visualScale,
                strength,
                0
        );
    }

    public static void start(
            double centreX,
            double centreY,
            double centreZ,
            float visualScale,
            float strength,
            int ageTicks
    ) {
        float safeScale = Math.max(1.0F, visualScale);

        ACTIVE_CLOUDS.removeIf(cloud ->
                Math.abs(cloud.centreX - centreX) < 0.001D
                        && Math.abs(cloud.centreY - centreY) < 0.001D
                        && Math.abs(cloud.centreZ - centreZ) < 0.001D
                        && Math.abs(cloud.visualScale - safeScale) < 0.001F
        );

        ACTIVE_CLOUDS.add(
                new ActiveCloud(
                        centreX,
                        centreY,
                        centreZ,
                        safeScale,
                        Math.clamp(strength, 0.0F, 1.0F),
                        Math.clamp(
                                ageTicks,
                                0,
                                DetonationTuning.ParticleCloud.TOTAL_LIFETIME_TICKS
                        )
                )
        );
    }

    public static void tick(
            Minecraft minecraft
    ) {
        if (
                minecraft.level == null
                        || minecraft.player == null
        ) {
            clear();
            return;
        }

        ClientLevel level = minecraft.level;
        Iterator<ActiveCloud> iterator = ACTIVE_CLOUDS.iterator();

        while (iterator.hasNext()) {
            ActiveCloud cloud = iterator.next();

            double viewDistance = getViewDistance(cloud.visualScale);
            double distanceSquared = minecraft.player.distanceToSqr(
                    cloud.centreX,
                    cloud.centreY + getStemHeight(cloud.visualScale) * 0.55D,
                    cloud.centreZ
            );

            boolean distantLodActive = DistantHorizonsCompat.isDistantCloudActive(
                    cloud.centreX,
                    cloud.centreY,
                    cloud.centreZ,
                    cloud.visualScale
            );
            boolean particleRendererSelected =
                    DetonationClientConfig.mushroomCloudRenderer()
                            == DetonationClientConfig.MushroomCloudRenderer.PARTICLES;

            cloud.detailedParticlesVisible = particleRendererSelected
                    && !distantLodActive
                    && hasCloudLineOfSight(minecraft, cloud);

            if (
                    cloud.detailedParticlesVisible
                            && distanceSquared <= viewDistance * viewDistance
            ) {
                spawnCloudTick(level, cloud);
            }

            cloud.ageTicks++;

            if (
                    cloud.ageTicks
                            > DetonationTuning.ParticleCloud.TOTAL_LIFETIME_TICKS
            ) {
                iterator.remove();
            }
        }
    }

    private static boolean hasCloudLineOfSight(
            Minecraft minecraft,
            ActiveCloud cloud
    ) {
        if (minecraft.player == null || minecraft.level == null) {
            return false;
        }

        double capCentreY = getFireballCentreY(
                cloud.centreY,
                cloud.visualScale,
                cloud.ageTicks
        );
        double capRadius = getCurrentHorizontalRadius(
                cloud.visualScale,
                cloud.ageTicks
        );
        double capVerticalRadius = getCurrentVerticalRadius(
                cloud.visualScale,
                cloud.ageTicks
        );
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 centre = new Vec3(cloud.centreX, capCentreY, cloud.centreZ);

        if (eye.distanceToSqr(centre)
                <= DetonationTuning.ParticleCloud.PARTICLE_OCCLUSION_START_DISTANCE
                * DetonationTuning.ParticleCloud.PARTICLE_OCCLUSION_START_DISTANCE) {
            return true;
        }

        double sideOffset = capRadius
                * DetonationTuning.ParticleCloud.PARTICLE_OCCLUSION_SAMPLE_RADIUS_RATIO;
        Vec3[] samples = new Vec3[]{
                centre,
                centre.add(0.0D, capVerticalRadius * 0.82D, 0.0D),
                centre.add(sideOffset, 0.0D, 0.0D),
                centre.add(-sideOffset, 0.0D, 0.0D),
                centre.add(0.0D, 0.0D, sideOffset),
                centre.add(0.0D, 0.0D, -sideOffset)
        };

        for (Vec3 target : samples) {
            HitResult result = minecraft.level.clip(
                    new ClipContext(
                            eye,
                            target,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            minecraft.player
                    )
            );
            if (
                    result.getType() == HitResult.Type.MISS
                            || result.getLocation().distanceToSqr(eye)
                            >= target.distanceToSqr(eye) - 1.0D
            ) {
                return true;
            }
        }

        return false;
    }

    private static boolean shouldCullForDistantLod(
            double particleX,
            double particleY,
            double particleZ
    ) {
        for (ActiveCloud cloud : ACTIVE_CLOUDS) {
            if (cloud.detailedParticlesVisible) {
                continue;
            }

            double horizontalRadius = getLateExpandedCloudRadius(cloud.visualScale)
                    * DetonationTuning.DistantHorizons.PARTICLE_CULL_CLOUD_RADIUS_MULTIPLIER;
            double dx = particleX - cloud.centreX;
            double dz = particleZ - cloud.centreZ;
            if (dx * dx + dz * dz > horizontalRadius * horizontalRadius) {
                continue;
            }

            double minimumY = cloud.centreY
                    - getStemGroundExtension(cloud.visualScale)
                    - 8.0D;
            double maximumY = cloud.centreY
                    + getStemHeight(cloud.visualScale)
                    + getCurrentVerticalRadius(cloud.visualScale, cloud.ageTicks) * 2.0D;
            if (particleY >= minimumY && particleY <= maximumY) {
                return true;
            }
        }
        return false;
    }

    public static void clear() {
        ACTIVE_CLOUDS.clear();
    }

    /**
     * Returns immutable geometry snapshots for optional distant renderers.
     * The normal particle system remains the source of truth for all timings
     * and dimensions, so a compatibility renderer cannot drift out of sync.
     */
    public static List<CloudSnapshot> snapshotClouds() {
        List<CloudSnapshot> snapshots =
                new ArrayList<>(ACTIVE_CLOUDS.size());

        for (ActiveCloud cloud : ACTIVE_CLOUDS) {
            double capCentreY = getFireballCentreY(
                    cloud.centreY,
                    cloud.visualScale,
                    cloud.ageTicks
            );
            double capVerticalRadius = getCurrentVerticalRadius(
                    cloud.visualScale,
                    cloud.ageTicks
            );
            double stemAboveGroundHeight = Math.max(
                    0.0D,
                    capCentreY
                            - cloud.centreY
                            - capVerticalRadius * 0.36D
            );
            double stemGroundExtension = getStemGroundExtension(
                    cloud.visualScale
            );
            double stemBaseY = cloud.centreY - stemGroundExtension;
            double stemHeight = stemAboveGroundHeight + stemGroundExtension;

            snapshots.add(
                    new CloudSnapshot(
                            cloud.centreX,
                            cloud.centreY,
                            cloud.centreZ,
                            cloud.visualScale,
                            cloud.strength,
                            cloud.ageTicks,
                            capCentreY,
                            getCurrentHorizontalRadius(
                                    cloud.visualScale,
                                    cloud.ageTicks
                            ),
                            capVerticalRadius,
                            stemBaseY,
                            stemHeight,
                            getAscentProgress(
                                    cloud.visualScale,
                                    cloud.ageTicks
                            ) >= 1.0D
                    )
            );
        }

        return List.copyOf(snapshots);
    }

    /**
     * Gives the renderer the current top of the rising fireball/cloud head so
     * the atmospheric shockwave stays attached to it.
     */
    public static double cloudTopY(
            double centreY,
            float visualScale,
            int ageTicks
    ) {
        return getFireballCentreY(
                centreY,
                visualScale,
                ageTicks
        ) + getCurrentVerticalRadius(
                visualScale,
                ageTicks
        );
    }

    private static void spawnCloudTick(
            ClientLevel level,
            ActiveCloud cloud
    ) {
        int age = cloud.ageTicks;

        /*
         * There is deliberately no separate cap generator. The original
         * fireball rises, cools, widens and becomes the mushroom-cloud head.
         */
        if (age <= DetonationTuning.ParticleCloud.FIREBALL_SPAWN_END_TICKS) {
            spawnFireball(level, cloud);
        }

        if (age >= DetonationTuning.ParticleCloud.STEM_START_TICKS) {
            spawnStem(level, cloud);
            spawnStemBaseRing(level, cloud);
        }

        if (age >= DetonationTuning.ParticleCloud.CRATER_EDGE_RING_START_TICKS) {
            spawnCraterEdgeRing(level, cloud);
        }

        if (age >= getAscentDuration(cloud.visualScale)) {
            spawnCapUndersideRing(level, cloud);
        }
    }

    private static void spawnFireball(
            ClientLevel level,
            ActiveCloud cloud
    ) {
        double thermalProgress = Math.clamp(
                cloud.ageTicks
                        / (double) DetonationTuning.ParticleCloud.FIREBALL_COLOUR_TRANSITION_TICKS,
                0.0D,
                1.0D
        );

        double ascentProgress = getAscentProgress(
                cloud.visualScale,
                cloud.ageTicks
        );

        double morphProgress = slowMorph(
                getMorphProgress(ascentProgress)
        );
        double horizontalRadius = getCurrentHorizontalRadius(
                cloud.visualScale,
                cloud.ageTicks
        );
        double verticalRadius = getCurrentVerticalRadius(
                cloud.visualScale,
                cloud.ageTicks
        );
        double centreY = getFireballCentreY(
                cloud.centreY,
                cloud.visualScale,
                cloud.ageTicks
        );

        int count = Math.clamp(
                (int) Math.round(
                        (DetonationTuning.ParticleCloud.FIREBALL_PARTICLES_BASE
                                + horizontalRadius
                                * DetonationTuning.ParticleCloud.FIREBALL_PARTICLES_PER_RADIUS)
                                * (0.65D + cloud.strength * 0.35D)
                ),
                DetonationTuning.ParticleCloud.FIREBALL_PARTICLES_MIN,
                DetonationTuning.ParticleCloud.FIREBALL_PARTICLES_MAX
        );

        boolean matureCloud =
                cloud.ageTicks
                        >= DetonationTuning.ParticleCloud.MATURE_CLOUD_START_TICKS;

        if (matureCloud) {
            count = DetonationTuning.ParticleCloud.MATURE_HEAD_PARTICLES_PER_TICK;
        }

        for (int index = 0; index < count; index++) {
            double azimuth = level.random.nextDouble() * Mth.TWO_PI;
            double verticalDirection = level.random.nextDouble() * 2.0D - 1.0D;
            double horizontalDirection = Math.sqrt(
                    Math.max(0.0D, 1.0D - verticalDirection * verticalDirection)
            );

            double radialFactor = level.random.nextDouble() < 0.76D
                    ? Math.cbrt(level.random.nextDouble())
                    : 0.78D + level.random.nextDouble() * 0.22D;

            double radialFromAxis = horizontalDirection * radialFactor;
            double offsetX = Math.cos(azimuth)
                    * horizontalRadius
                    * radialFromAxis;
            double offsetZ = Math.sin(azimuth)
                    * horizontalRadius
                    * radialFromAxis;

            /*
             * As the same fireball reaches altitude it flattens and rolls at
             * the edge, naturally becoming the mushroom head rather than
             * having a second cap spawned over it.
             */
            double dome = morphProgress
                    * (1.0D - radialFromAxis * radialFromAxis)
                    * verticalRadius
                    * 0.34D;

            double edgeRoll = morphProgress
                    * Math.max(0.0D, (radialFromAxis - 0.66D) / 0.34D)
                    * verticalRadius
                    * -0.46D;

            double offsetY = verticalDirection
                    * verticalRadius
                    * radialFactor
                    * (1.0D - morphProgress * 0.18D)
                    + dome
                    + edgeRoll;

            double horizontalNormaliser = Math.max(1.0D, horizontalRadius);
            double outwardSpeed = 0.012D
                    + radialFactor * 0.018D
                    + morphProgress * 0.004D;

            double velocityX = offsetX / horizontalNormaliser * outwardSpeed;
            double velocityZ = offsetZ / horizontalNormaliser * outwardSpeed;
            double velocityY = lerp(
                    0.022D
                            + Math.abs(verticalDirection) * 0.024D
                            + level.random.nextDouble() * 0.040D,
                    0.008D + level.random.nextDouble() * 0.020D,
                    morphProgress
            );

            float maximumScale = thermalProgress < 0.72D
                    ? DetonationTuning.ParticleCloud.MAX_FIREBALL_POOF_SCALE
                    : DetonationTuning.ParticleCloud.MAX_SMOKE_POOF_SCALE;

            float particleScale = (float) Math.clamp(
                    horizontalRadius
                            * (0.070D + level.random.nextDouble() * 0.085D),
                    DetonationTuning.ParticleCloud.MIN_POOF_SCALE,
                    maximumScale
            );

            FireballPalette palette = chooseFireballPalette(
                    level,
                    thermalProgress
            );

            boolean smokePhase = thermalProgress >= 0.70D;

            int lifetime = matureCloud
                    ? DetonationTuning.ParticleCloud.MATURE_PARTICLE_LIFETIME_MIN
                    + level.random.nextInt(
                    DetonationTuning.ParticleCloud.MATURE_PARTICLE_LIFETIME_VARIATION
            )
                    : smokePhase
                    ? 72 + level.random.nextInt(95)
                    : 34 + level.random.nextInt(42);

            TintedPoofOptions options = new TintedPoofOptions(
                    palette.startRgb,
                    palette.endRgb,
                    particleScale,
                    particleScale
                            * (smokePhase
                            ? 1.75F + level.random.nextFloat() * 0.80F
                            : 1.55F + level.random.nextFloat() * 0.75F),
                    lifetime,
                    matureCloud ? -0.0008F : smokePhase ? -0.0024F : -0.004F,
                    matureCloud ? 0.985F : smokePhase ? 0.958F : 0.92F,
                    matureCloud ? 0.025F : smokePhase ? 0.015F : 0.08F,
                    palette.fullBright
            );

            level.addParticle(
                    options,
                    cloud.centreX + offsetX,
                    centreY + offsetY,
                    cloud.centreZ + offsetZ,
                    velocityX,
                    velocityY,
                    velocityZ
            );
        }
    }

    private static FireballPalette chooseFireballPalette(
            ClientLevel level,
            double progress
    ) {
        float roll = level.random.nextFloat();

        if (progress < 0.20D) {
            if (roll < 0.36F) {
                return new FireballPalette(WHITE_HOT, HOT_YELLOW, true);
            }
            if (roll < 0.72F) {
                return new FireballPalette(PALE_YELLOW, BRIGHT_ORANGE, true);
            }
            return new FireballPalette(HOT_YELLOW, DEEP_ORANGE, true);
        }

        if (progress < 0.52D) {
            if (roll < 0.18F) {
                return new FireballPalette(PALE_YELLOW, BRIGHT_ORANGE, true);
            }
            if (roll < 0.68F) {
                return new FireballPalette(HOT_YELLOW, DEEP_ORANGE, true);
            }
            return new FireballPalette(BRIGHT_ORANGE, BURNT_BROWN, false);
        }

        if (progress < 0.78D) {
            if (roll < 0.28F) {
                return new FireballPalette(BRIGHT_ORANGE, BURNT_BROWN, false);
            }
            if (roll < 0.72F) {
                return new FireballPalette(DEEP_ORANGE, WARM_GREY, false);
            }
            return new FireballPalette(BURNT_BROWN, DARK_GREY, false);
        }

        if (roll < 0.35F) {
            return new FireballPalette(DEEP_ORANGE, DARK_GREY, false);
        }
        if (roll < 0.72F) {
            return new FireballPalette(BURNT_BROWN, DARK_GREY, false);
        }
        return new FireballPalette(WARM_GREY, NEAR_BLACK, false);
    }

    private static void spawnStem(
            ClientLevel level,
            ActiveCloud cloud
    ) {
        double headCentreY = getFireballCentreY(
                cloud.centreY,
                cloud.visualScale,
                cloud.ageTicks
        );
        double headVerticalRadius = getCurrentVerticalRadius(
                cloud.visualScale,
                cloud.ageTicks
        );

        /*
         * The stem follows directly behind the slowly rising fireball. It can
         * never outrun the head or create a disconnected pre-built cap.
         */
        double stemAboveGroundHeight = Math.max(
                0.0D,
                headCentreY
                        - cloud.centreY
                        - headVerticalRadius * 0.36D
        );
        double stemGroundExtension = getStemGroundExtension(cloud.visualScale);
        double stemBaseY = cloud.centreY - stemGroundExtension;
        double stemHeight = stemAboveGroundHeight + stemGroundExtension;

        if (stemHeight <= 0.5D) {
            return;
        }

        double matureCloudRadius = getMatureCloudRadius(cloud.visualScale);

        /*
         * The stem begins as a narrow column and does not start widening until
         * the rising fireball has already begun spreading into the mushroom
         * head. This prevents the stem from thickening before the cap forms.
         */
        double ascentProgress = getAscentProgress(
                cloud.visualScale,
                cloud.ageTicks
        );

        double headMorphProgress = getMorphProgress(ascentProgress);

        double stemWidenProgress = Math.clamp(
                (headMorphProgress
                        - DetonationTuning.ParticleCloud.STEM_WIDEN_START_MORPH_RATIO)
                        / (1.0D
                        - DetonationTuning.ParticleCloud.STEM_WIDEN_START_MORPH_RATIO),
                0.0D,
                1.0D
        );

        stemWidenProgress = Math.pow(
                smoothstep(stemWidenProgress),
                DetonationTuning.ParticleCloud.STEM_WIDEN_POWER
        );

        double stemRadiusRatio = lerp(
                DetonationTuning.ParticleCloud.STEM_INITIAL_RADIUS_RATIO,
                DetonationTuning.ParticleCloud.STEM_RADIUS_RATIO,
                stemWidenProgress
        );

        double stemRadius = Math.max(
                2.0D,
                matureCloudRadius * stemRadiusRatio
        );

        int count = Math.clamp(
                (int) Math.round(
                        DetonationTuning.ParticleCloud.STEM_PARTICLES_BASE
                                + stemHeight
                                * DetonationTuning.ParticleCloud.STEM_PARTICLES_PER_HEIGHT
                ),
                DetonationTuning.ParticleCloud.STEM_PARTICLES_MIN,
                DetonationTuning.ParticleCloud.STEM_PARTICLES_MAX
        );

        boolean matureCloud =
                cloud.ageTicks
                        >= DetonationTuning.ParticleCloud.MATURE_CLOUD_START_TICKS;

        if (matureCloud) {
            count = DetonationTuning.ParticleCloud.MATURE_STEM_PARTICLES_PER_TICK;
        }

        for (int index = 0; index < count; index++) {
            double heightProgress = level.random.nextDouble();

            /*
             * Preserve the exact bottom and top widths, but pull the middle
             * inward to give the stem a stronger curved silhouette. Because
             * sin(0) and sin(pi) are both zero, the end widths are unchanged.
             */
            double verticalTaper = 1.05D
                    - 0.47D * smoothstep(heightProgress);
            double middleCurve = 1.0D
                    - DetonationTuning.ParticleCloud.STEM_MIDDLE_CURVE_STRENGTH
                    * Math.pow(
                    Math.sin(heightProgress * Math.PI),
                    DetonationTuning.ParticleCloud.STEM_MIDDLE_CURVE_POWER
            );
            double localRadius = stemRadius * verticalTaper * middleCurve;
            double angle = level.random.nextDouble() * Mth.TWO_PI;
            double distance = localRadius * Math.sqrt(level.random.nextDouble());

            double x = cloud.centreX + Math.cos(angle) * distance;
            double y = stemBaseY + stemHeight * heightProgress;
            double z = cloud.centreZ + Math.sin(angle) * distance;

            float size = (float) Math.clamp(
                    localRadius * (0.24D + level.random.nextDouble() * 0.20D),
                    DetonationTuning.ParticleCloud.MIN_POOF_SCALE,
                    DetonationTuning.ParticleCloud.MAX_SMOKE_POOF_SCALE
            );

            int startColour;
            int endColour;

            if (
                    heightProgress > 0.62D
                            && cloud.ageTicks
                            < DetonationTuning.ParticleCloud.FIREBALL_COLOUR_TRANSITION_TICKS * 0.78D
            ) {
                startColour = level.random.nextBoolean()
                        ? DEEP_ORANGE
                        : BURNT_BROWN;
                endColour = DARK_GREY;
            } else {
                startColour = level.random.nextFloat() < 0.55F
                        ? WARM_GREY
                        : BURNT_BROWN;
                endColour = level.random.nextFloat() < 0.72F
                        ? DARK_GREY
                        : NEAR_BLACK;
            }

            int lifetime = matureCloud
                    ? DetonationTuning.ParticleCloud.MATURE_PARTICLE_LIFETIME_MIN
                    + level.random.nextInt(
                    DetonationTuning.ParticleCloud.MATURE_PARTICLE_LIFETIME_VARIATION
            )
                    : 70 + level.random.nextInt(70);

            TintedPoofOptions options = new TintedPoofOptions(
                    startColour,
                    endColour,
                    size,
                    size * (1.65F + level.random.nextFloat() * 0.60F),
                    lifetime,
                    matureCloud ? -0.0007F : -0.0025F,
                    matureCloud ? 0.985F : 0.955F,
                    0.02F,
                    false
            );

            level.addParticle(
                    options,
                    x,
                    y,
                    z,
                    Math.cos(angle) * 0.008D,
                    0.018D + level.random.nextDouble() * 0.028D,
                    Math.sin(angle) * 0.008D
            );
        }
    }

    /**
     * Small smoke collar where the stem meets the ground. This is separate
     * from the much larger crater-edge ring.
     */
    private static void spawnStemBaseRing(
            ClientLevel level,
            ActiveCloud cloud
    ) {
        boolean matureCloud =
                cloud.ageTicks
                        >= DetonationTuning.ParticleCloud.MATURE_CLOUD_START_TICKS;

        int count = matureCloud
                ? DetonationTuning.ParticleCloud.MATURE_STEM_BASE_RING_PARTICLES_PER_TICK
                : DetonationTuning.ParticleCloud.STEM_BASE_RING_PARTICLES_PER_TICK;

        double fireballRadius = getFireballRadius(cloud.visualScale);
        double ringRadius = Math.max(
                DetonationTuning.ParticleCloud.STEM_BASE_RING_MINIMUM_RADIUS,
                fireballRadius
                        * DetonationTuning.ParticleCloud.STEM_BASE_RING_RADIUS_RATIO
        );
        double ringThickness = Math.max(
                0.35D,
                ringRadius
                        * DetonationTuning.ParticleCloud.STEM_BASE_RING_THICKNESS_RATIO
        );

        for (int index = 0; index < count; index++) {
            double angle = level.random.nextDouble() * Mth.TWO_PI;
            double radius = ringRadius
                    + (level.random.nextDouble() - 0.5D) * ringThickness;

            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double x = cloud.centreX + cos * radius;
            double y = cloud.centreY
                    + 0.20D
                    + level.random.nextDouble() * 0.45D;
            double z = cloud.centreZ + sin * radius;

            float size = (float) Math.clamp(
                    ringRadius * (0.16D + level.random.nextDouble() * 0.12D),
                    DetonationTuning.ParticleCloud.MIN_POOF_SCALE,
                    DetonationTuning.ParticleCloud.STEM_BASE_RING_MAX_POOF_SCALE
            );

            int lifetime = matureCloud
                    ? DetonationTuning.ParticleCloud.MATURE_PARTICLE_LIFETIME_MIN
                    + level.random.nextInt(
                    DetonationTuning.ParticleCloud.MATURE_PARTICLE_LIFETIME_VARIATION
            )
                    : 65 + level.random.nextInt(55);

            int startColour = level.random.nextFloat() < 0.45F
                    ? BURNT_BROWN
                    : WARM_GREY;
            int endColour = level.random.nextFloat() < 0.78F
                    ? DARK_GREY
                    : NEAR_BLACK;

            TintedPoofOptions options = new TintedPoofOptions(
                    startColour,
                    endColour,
                    size,
                    size * (1.40F + level.random.nextFloat() * 0.35F),
                    lifetime,
                    matureCloud ? -0.0006F : -0.0018F,
                    matureCloud ? 0.986F : 0.960F,
                    matureCloud ? 0.025F : 0.035F,
                    false
            );

            level.addParticle(
                    options,
                    x,
                    y,
                    z,
                    cos * 0.006D,
                    0.008D + level.random.nextDouble() * 0.010D,
                    sin * 0.006D
            );
        }
    }

    /**
     * Refreshes a low, dark smoke ring around the crater rim. visualScale is
     * supplied by the server as the crater radius, so the ring follows the
     * generated crater edge rather than the stem or mushroom-head radius.
     */
    private static void spawnCraterEdgeRing(
            ClientLevel level,
            ActiveCloud cloud
    ) {
        boolean matureCloud =
                cloud.ageTicks
                        >= DetonationTuning.ParticleCloud.MATURE_CLOUD_START_TICKS;

        int count = matureCloud
                ? DetonationTuning.ParticleCloud.MATURE_CRATER_EDGE_RING_PARTICLES_PER_TICK
                : DetonationTuning.ParticleCloud.CRATER_EDGE_RING_PARTICLES_PER_TICK;

        double ringRadius = Math.max(
                1.0D,
                cloud.visualScale
                        * DetonationTuning.ParticleCloud.CRATER_EDGE_RING_RADIUS_MULTIPLIER
        );
        double ringThickness = Math.max(
                DetonationTuning.ParticleCloud.CRATER_EDGE_RING_MINIMUM_THICKNESS,
                ringRadius
                        * DetonationTuning.ParticleCloud.CRATER_EDGE_RING_THICKNESS_RATIO
        );

        for (int index = 0; index < count; index++) {
            double angle = level.random.nextDouble() * Mth.TWO_PI;
            double radius = ringRadius
                    + (level.random.nextDouble() - 0.5D) * ringThickness;

            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double x = cloud.centreX + cos * radius;
            double y = cloud.centreY
                    + 0.35D
                    + level.random.nextDouble() * 1.10D;
            double z = cloud.centreZ + sin * radius;

            float size = (float) Math.clamp(
                    ringThickness * (0.75D + level.random.nextDouble() * 0.65D),
                    DetonationTuning.ParticleCloud.MIN_POOF_SCALE,
                    DetonationTuning.ParticleCloud.CRATER_EDGE_RING_MAX_POOF_SCALE
            );

            int lifetime = matureCloud
                    ? DetonationTuning.ParticleCloud.MATURE_PARTICLE_LIFETIME_MIN
                    + level.random.nextInt(
                    DetonationTuning.ParticleCloud.MATURE_PARTICLE_LIFETIME_VARIATION
            )
                    : 90 + level.random.nextInt(90);

            int startColour;
            if (cloud.ageTicks < DetonationTuning.ParticleCloud.FIREBALL_COLOUR_TRANSITION_TICKS) {
                startColour = level.random.nextFloat() < 0.32F
                        ? DEEP_ORANGE
                        : BURNT_BROWN;
            } else {
                startColour = level.random.nextFloat() < 0.58F
                        ? BURNT_BROWN
                        : WARM_GREY;
            }

            int endColour = level.random.nextFloat() < 0.72F
                    ? DARK_GREY
                    : NEAR_BLACK;

            TintedPoofOptions options = new TintedPoofOptions(
                    startColour,
                    endColour,
                    size,
                    size * (1.55F + level.random.nextFloat() * 0.45F),
                    lifetime,
                    matureCloud ? -0.0007F : -0.0022F,
                    matureCloud ? 0.987F : 0.962F,
                    matureCloud ? 0.020F : 0.030F,
                    false
            );

            level.addParticle(
                    options,
                    x,
                    y,
                    z,
                    cos * (0.004D + level.random.nextDouble() * 0.005D),
                    0.010D + level.random.nextDouble() * 0.018D,
                    sin * (0.004D + level.random.nextDouble() * 0.005D)
            );
        }
    }

    /**
     * Fades in a compact near-black smoke ring beneath the completed cap. The
     * ring sits below the cap's lowest radius with a clear vertical gap, while
     * its width follows the cap slowly as the mature cloud continues spreading.
     */
    private static void spawnCapUndersideRing(
            ClientLevel level,
            ActiveCloud cloud
    ) {
        int ticksSinceTop = Math.max(
                0,
                cloud.ageTicks - getAscentDuration(cloud.visualScale)
        );

        double fadeProgress = smoothstep(
                Math.clamp(
                        ticksSinceTop
                                / (double) DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_FADE_IN_TICKS,
                        0.0D,
                        1.0D
                )
        );

        if (fadeProgress <= 0.0D) {
            return;
        }

        boolean matureCloud =
                cloud.ageTicks
                        >= DetonationTuning.ParticleCloud.MATURE_CLOUD_START_TICKS;

        int targetCount = matureCloud
                ? DetonationTuning.ParticleCloud.MATURE_CAP_UNDERSIDE_RING_PARTICLES_PER_TICK
                : DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_PARTICLES_PER_TICK;
        int count = Math.max(
                1,
                (int) Math.ceil(targetCount * fadeProgress)
        );

        double capRadius = getCurrentHorizontalRadius(
                cloud.visualScale,
                cloud.ageTicks
        );
        double capVerticalRadius = getCurrentVerticalRadius(
                cloud.visualScale,
                cloud.ageTicks
        );
        double capCentreY = getFireballCentreY(
                cloud.centreY,
                cloud.visualScale,
                cloud.ageTicks
        );

        double ringRadius = Math.max(
                DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_MINIMUM_RADIUS,
                capRadius
                        * DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_RADIUS_RATIO
        );
        double ringThickness = Math.max(
                DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_MINIMUM_THICKNESS,
                ringRadius
                        * DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_THICKNESS_RATIO
        );
        double verticalGap = Math.max(
                DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_MINIMUM_GAP,
                capVerticalRadius
                        * DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_GAP_RATIO
        );
        double ringY = capCentreY - capVerticalRadius - verticalGap;

        for (int index = 0; index < count; index++) {
            double angle = level.random.nextDouble() * Mth.TWO_PI;
            double radius = ringRadius
                    + (level.random.nextDouble() - 0.5D) * ringThickness;

            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double x = cloud.centreX + cos * radius;
            double y = ringY
                    + (level.random.nextDouble() - 0.5D)
                    * Math.max(0.6D, ringThickness * 0.35D);
            double z = cloud.centreZ + sin * radius;

            float size = (float) Math.clamp(
                    ringThickness
                            * (0.75D + level.random.nextDouble() * 0.70D)
                            * (0.55D + fadeProgress * 0.45D),
                    DetonationTuning.ParticleCloud.MIN_POOF_SCALE,
                    DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_MAX_POOF_SCALE
            );

            int lifetime = matureCloud
                    ? DetonationTuning.ParticleCloud.MATURE_PARTICLE_LIFETIME_MIN
                    + level.random.nextInt(
                    DetonationTuning.ParticleCloud.MATURE_PARTICLE_LIFETIME_VARIATION
            )
                    : 100 + level.random.nextInt(90);

            int startColour = level.random.nextFloat() < 0.38F
                    ? WARM_GREY
                    : DARK_GREY;

            int endColour = level.random.nextFloat() < 0.24F
                    ? WARM_GREY
                    : DARK_GREY;

            TintedPoofOptions options = new TintedPoofOptions(
                    startColour,
                    endColour,
                    size,
                    size * (1.45F + level.random.nextFloat() * 0.40F),
                    lifetime,
                    matureCloud ? -0.0005F : -0.0014F,
                    matureCloud ? 0.988F : 0.968F,
                    matureCloud ? 0.03F : 0.02F,
                    false
            );

            level.addParticle(
                    options,
                    x,
                    y,
                    z,
                    cos * (0.002D + level.random.nextDouble() * 0.003D),
                    0.004D + level.random.nextDouble() * 0.009D,
                    sin * (0.002D + level.random.nextDouble() * 0.003D)
            );
        }
    }

    private static double getViewDistance(
            float visualScale
    ) {
        return Math.max(
                DetonationTuning.ParticleCloud.MINIMUM_VIEW_DISTANCE,
                getLateExpandedCloudRadius(visualScale)
                        * DetonationTuning.ParticleCloud.VIEW_DISTANCE_MULTIPLIER
        );
    }

    public static double getFireballRadius(
            float visualScale
    ) {
        return Math.clamp(
                visualScale
                        * DetonationTuning.ParticleCloud.FIREBALL_RADIUS_MULTIPLIER,
                (double) DetonationTuning.ParticleCloud.MINIMUM_FIREBALL_RADIUS,
                (double) DetonationTuning.ParticleCloud.MAXIMUM_FIREBALL_RADIUS
        );
    }

    public static double getStemHeight(
            float visualScale
    ) {
        return Math.max(
                DetonationTuning.ParticleCloud.MINIMUM_STEM_HEIGHT,
                visualScale
                        * DetonationTuning.ParticleCloud.STEM_HEIGHT_MULTIPLIER
        );
    }

    public static double getStemGroundExtension(
            float visualScale
    ) {
        return Math.clamp(
                visualScale
                        * DetonationTuning.ParticleCloud
                        .STEM_GROUND_EXTENSION_RADIUS_RATIO,
                DetonationTuning.ParticleCloud
                        .STEM_GROUND_EXTENSION_MINIMUM,
                DetonationTuning.ParticleCloud
                        .STEM_GROUND_EXTENSION_MAXIMUM
        );
    }

    public static double getMatureCloudRadius(
            float visualScale
    ) {
        return Math.max(
                DetonationTuning.ParticleCloud.MINIMUM_MATURE_CLOUD_RADIUS,
                visualScale
                        * DetonationTuning.ParticleCloud.MATURE_CLOUD_RADIUS_MULTIPLIER
        );
    }

    public static double getLateExpandedCloudRadius(
            float visualScale
    ) {
        return Math.max(
                getMatureCloudRadius(visualScale),
                visualScale
                        * DetonationTuning.ParticleCloud.LATE_CAP_RADIUS_MULTIPLIER
        );
    }

    private static int getAscentDuration(
            float visualScale
    ) {
        return Math.clamp(
                (int) Math.round(
                        DetonationTuning.ParticleCloud.FIREBALL_ASCENT_BASE_TICKS
                                + visualScale
                                * DetonationTuning.ParticleCloud.FIREBALL_ASCENT_TICKS_PER_SCALE
                ),
                DetonationTuning.ParticleCloud.FIREBALL_ASCENT_BASE_TICKS,
                DetonationTuning.ParticleCloud.MAX_FIREBALL_ASCENT_TICKS
        );
    }

    private static double getAscentProgress(
            float visualScale,
            int ageTicks
    ) {
        return Math.clamp(
                ageTicks / (double) getAscentDuration(visualScale),
                0.0D,
                1.0D
        );
    }

    private static double getMorphProgress(
            double ascentProgress
    ) {
        return Math.clamp(
                (ascentProgress
                        - DetonationTuning.ParticleCloud.FIREBALL_CAP_SPREAD_START_RATIO)
                        / (1.0D
                        - DetonationTuning.ParticleCloud.FIREBALL_CAP_SPREAD_START_RATIO),
                0.0D,
                1.0D
        );
    }

    private static double getLateCapExpansionProgress(
            float visualScale,
            int ageTicks
    ) {
        int expansionStart = getAscentDuration(visualScale)
                + DetonationTuning.ParticleCloud.LATE_CAP_EXPANSION_DELAY_TICKS;

        return Math.clamp(
                (ageTicks - expansionStart)
                        / (double) DetonationTuning.ParticleCloud.LATE_CAP_EXPANSION_DURATION_TICKS,
                0.0D,
                1.0D
        );
    }

    private static double getCurrentHorizontalRadius(
            float visualScale,
            int ageTicks
    ) {
        /*
         * The cloud head begins at the crater radius instead of growing from
         * a small ball. It then broadens gradually as it rises, before the
         * original v12/v14 late cap formation takes over near the top.
         */
        double fireballRadius = getFireballRadius(visualScale);
        double ascentProgress = getAscentProgress(visualScale, ageTicks);

        double earlyRiseSpread =
                smoothstep(ascentProgress) * 0.30D;

        double normalCapSpread = slowMorph(
                getMorphProgress(ascentProgress)
        );

        double formationProgress = Math.max(
                earlyRiseSpread,
                normalCapSpread
        );

        double normallyFormedRadius = lerp(
                fireballRadius,
                getMatureCloudRadius(visualScale),
                formationProgress
        );

        double lateExpansion = slowLateExpansion(
                getLateCapExpansionProgress(visualScale, ageTicks)
        );

        return lerp(
                normallyFormedRadius,
                getLateExpandedCloudRadius(visualScale),
                lateExpansion
        );
    }

    private static double getCurrentVerticalRadius(
            float visualScale,
            int ageTicks
    ) {
        double fireballRadius = getFireballRadius(visualScale);

        double morphProgress = slowMorph(
                getMorphProgress(
                        getAscentProgress(visualScale, ageTicks)
                )
        );

        return lerp(
                fireballRadius * 0.90D,
                getMatureCloudRadius(visualScale)
                        * DetonationTuning.ParticleCloud.MATURE_CLOUD_VERTICAL_RATIO,
                morphProgress
        );
    }

    private static double getFireballCentreY(
            double centreY,
            float visualScale,
            int ageTicks
    ) {
        double ascentProgress = getAscentProgress(
                visualScale,
                ageTicks
        );

        double slowRise = smoothstep(ascentProgress);

        return centreY
                + 2.0D
                + getStemHeight(visualScale) * slowRise;
    }


    /**
     * Deliberately slow-starting mushroom-head spread. The rising fireball
     * remains compact for most of its ascent, then broadens gradually instead
     * of snapping outward as soon as the cap phase begins.
     */
    private static double slowMorph(
            double value
    ) {
        value = smoothstep(value);
        return Math.pow(value, 1.85D);
    }

    private static double slowLateExpansion(
            double value
    ) {
        value = smoothstep(value);
        return Math.pow(
                value,
                DetonationTuning.ParticleCloud.LATE_CAP_EXPANSION_POWER
        );
    }

    private static double smoothstep(
            double value
    ) {
        value = Math.clamp(value, 0.0D, 1.0D);
        return value * value * (3.0D - 2.0D * value);
    }

    private static double lerp(
            double start,
            double end,
            double progress
    ) {
        return start + (end - start) * progress;
    }

    private static double easeOutCubic(
            double value
    ) {
        double inverse = 1.0D - Math.clamp(value, 0.0D, 1.0D);
        return 1.0D - inverse * inverse * inverse;
    }

    /**
     * Immutable current cloud geometry exposed to optional client renderers.
     */
    public record CloudSnapshot(
            double centreX,
            double centreY,
            double centreZ,
            float visualScale,
            float strength,
            int ageTicks,
            double capCentreY,
            double capRadius,
            double capVerticalRadius,
            double stemBaseY,
            double stemHeight,
            boolean atMaximumHeight
    ) {
    }

    private record FireballPalette(
            int startRgb,
            int endRgb,
            boolean fullBright
    ) {
    }

    private static final class ActiveCloud {
        private final double centreX;
        private final double centreY;
        private final double centreZ;
        private final float visualScale;
        private final float strength;

        private int ageTicks;
        private boolean detailedParticlesVisible = true;

        private ActiveCloud(
                double centreX,
                double centreY,
                double centreZ,
                float visualScale,
                float strength,
                int ageTicks
        ) {
            this.centreX = centreX;
            this.centreY = centreY;
            this.centreZ = centreZ;
            this.visualScale = visualScale;
            this.strength = strength;
            this.ageTicks = Math.max(0, ageTicks);
        }
    }

    private static final class TintedPoofProvider
            implements ParticleProvider<TintedPoofOptions> {
        private final SpriteSet sprites;

        private TintedPoofProvider(
                SpriteSet sprites
        ) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(
                TintedPoofOptions options,
                ClientLevel level,
                double x,
                double y,
                double z,
                double xSpeed,
                double ySpeed,
                double zSpeed
        ) {
            return new TintedPoofParticle(
                    level,
                    x,
                    y,
                    z,
                    xSpeed,
                    ySpeed,
                    zSpeed,
                    options,
                    sprites
            );
        }
    }

    private static final class TintedPoofParticle
            extends TextureSheetParticle {
        private final SpriteSet sprites;
        private final int startRgb;
        private final int endRgb;
        private final float startScale;
        private final float endScale;
        private final float endAlpha;
        private final boolean fullBright;

        private TintedPoofParticle(
                ClientLevel level,
                double x,
                double y,
                double z,
                double xSpeed,
                double ySpeed,
                double zSpeed,
                TintedPoofOptions options,
                SpriteSet sprites
        ) {
            super(
                    level,
                    x,
                    y,
                    z,
                    xSpeed,
                    ySpeed,
                    zSpeed
            );

            this.sprites = sprites;
            this.startRgb = options.startRgb();
            this.endRgb = options.endRgb();
            this.startScale = options.startScale();
            this.endScale = options.endScale();
            this.endAlpha = options.endAlpha();
            this.fullBright = options.fullBright();

            this.lifetime = options.lifetime();
            this.gravity = options.gravity();
            this.friction = options.drag();
            this.quadSize = options.startScale();
            this.hasPhysics = false;
            this.alpha = 1.0F;

            this.setSpriteFromAge(sprites);
            updateAppearance(0.0F);
        }

        @Override
        public void tick() {
            if (shouldCullForDistantLod(this.x, this.y, this.z)) {
                this.remove();
                return;
            }

            super.tick();

            if (this.removed) {
                return;
            }

            float progress = Math.clamp(
                    this.age / (float) Math.max(1, this.lifetime),
                    0.0F,
                    1.0F
            );

            updateAppearance(progress);
            this.setSpriteFromAge(sprites);
        }

        @Override
        public int getLightColor(
                float partialTick
        ) {
            if (fullBright) {
                return 0xF000F0;
            }

            return super.getLightColor(partialTick);
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }

        private void updateAppearance(
                float progress
        ) {
            float eased = progress * progress * (3.0F - 2.0F * progress);

            int startRed = (startRgb >> 16) & 0xFF;
            int startGreen = (startRgb >> 8) & 0xFF;
            int startBlue = startRgb & 0xFF;

            int endRed = (endRgb >> 16) & 0xFF;
            int endGreen = (endRgb >> 8) & 0xFF;
            int endBlue = endRgb & 0xFF;

            this.setColor(
                    Mth.lerp(eased, startRed, endRed) / 255.0F,
                    Mth.lerp(eased, startGreen, endGreen) / 255.0F,
                    Mth.lerp(eased, startBlue, endBlue) / 255.0F
            );

            this.quadSize = Mth.lerp(eased, startScale, endScale);

            float lateFade = progress <= 0.72F
                    ? 1.0F
                    : 1.0F - (progress - 0.72F) / 0.28F;

            this.setAlpha(
                    Math.max(
                            endAlpha,
                            lateFade
                    )
            );
        }
    }
}
