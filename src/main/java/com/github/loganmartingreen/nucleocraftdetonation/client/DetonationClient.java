package com.github.loganmartingreen.nucleocraftdetonation.client;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.DetonationEffectsPayload;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.FalloutVisualPayload;
import com.github.loganmartingreen.nucleocraftdetonation.sound.DetonationSounds;
import com.mojang.blaze3d.shaders.FogShape;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.material.FogType;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * All client-only detonation state and rendering hooks.
 *
 * <p>This keeps the flash, delayed blast sound, fallout atmosphere, mushroom
 * cloud and shockwave visuals in one place so they tick consistently and clean
 * up reliably on world changes.</p>
 */
@EventBusSubscriber(
        modid = NucleoCraftDetonation.MOD_ID,
        value = Dist.CLIENT
)
public final class DetonationClient {
    private static final DustParticleOptions FALLOUT_DUST =
            new DustParticleOptions(
                    new Vector3f(
                            DetonationTuning.Fallout.RED,
                            DetonationTuning.Fallout.GREEN,
                            DetonationTuning.Fallout.BLUE
                    ),
                    DetonationTuning.Fallout.DUST_SCALE
            );

    private static final List<FalloutZone> FALLOUT_ZONES =
            new ArrayList<>();

    private static final List<ActiveDetonation> ACTIVE_DETONATIONS =
            new ArrayList<>();

    private static ClientLevel previousLevel;
    private static float currentFalloutIntensity;
    private static int previousWaterTintStage = -1;

    private DetonationClient() {
    }

    public static void startFallout(
            FalloutVisualPayload payload
    ) {
        int durationTicks =
                payload.durationTicks() <= 0
                        ? -1
                        : payload.durationTicks();

        FALLOUT_ZONES.removeIf(zone ->
                zone.centreX == payload.centre().getX() + 0.5D
                        && zone.centreZ == payload.centre().getZ() + 0.5D
                        && Math.abs(zone.radius - payload.radius()) < 0.001F
        );

        FALLOUT_ZONES.add(
                new FalloutZone(
                        payload.centre().getX() + 0.5D,
                        payload.centre().getZ() + 0.5D,
                        Math.max(1.0F, payload.radius()),
                        durationTicks,
                        Math.clamp(payload.strength(), 0.0F, 1.0F)
                )
        );
    }

    public static void startDetonation(
            DetonationEffectsPayload payload
    ) {
        Minecraft minecraft =
                Minecraft.getInstance();

        if (
                minecraft.level == null
                        || minecraft.player == null
        ) {
            return;
        }

        double centreX = payload.centre().getX() + 0.5D;
        double centreY = payload.centre().getY() + 0.5D;
        double centreZ = payload.centre().getZ() + 0.5D;

        double offsetX = minecraft.player.getX() - centreX;
        double offsetY = minecraft.player.getY() - centreY;
        double offsetZ = minecraft.player.getZ() - centreZ;

        double distance = Math.sqrt(
                offsetX * offsetX
                        + offsetY * offsetY
                        + offsetZ * offsetZ
        );

        float flashStrength =
                calculateDistanceStrength(
                        distance,
                        payload.flashRadius(),
                        DetonationTuning.ImmediateEffects.FULL_FLASH_RADIUS_RATIO
                ) * payload.strength();

        float soundStrength =
                calculateDistanceStrength(
                        distance,
                        payload.soundRadius(),
                        DetonationTuning.ImmediateEffects.FULL_SOUND_RADIUS_RATIO
                ) * payload.strength();

        int soundDelayTicks =
                (int) Math.round(
                        distance
                                / DetonationTuning.ImmediateEffects.SPEED_OF_SOUND_BLOCKS_PER_SECOND
                                * DetonationTuning.ImmediateEffects.TICKS_PER_SECOND
                );

        ACTIVE_DETONATIONS.add(
                new ActiveDetonation(
                        centreX,
                        centreY,
                        centreZ,
                        Math.max(1.0F, payload.visualScale()),
                        Math.clamp(payload.strength(), 0.0F, 1.0F),
                        Math.clamp(flashStrength, 0.0F, 1.0F),
                        Math.clamp(soundStrength, 0.0F, 1.0F),
                        Math.max(0, soundDelayTicks)
                )
        );
    }

    /**
     * Used by the sky and water mixins.
     */
    public static float falloutIntensity() {
        return Math.clamp(
                currentFalloutIntensity,
                0.0F,
                1.0F
        );
    }

    @SubscribeEvent
    public static void onClientTick(
            ClientTickEvent.Post event
    ) {
        Minecraft minecraft =
                Minecraft.getInstance();

        if (
                minecraft.level == null
                        || minecraft.player == null
        ) {
            clearAll();
            previousLevel = null;
            return;
        }

        if (previousLevel == null) {
            previousLevel = minecraft.level;
        } else if (previousLevel != minecraft.level) {
            clearAll();
            previousLevel = minecraft.level;
        }

        if (minecraft.isPaused()) {
            return;
        }

        tickFallout(
                minecraft.player.getX(),
                minecraft.player.getZ()
        );

        tickDetonations(minecraft);

        float intensity = falloutIntensity();

        refreshWaterTintWhenNeeded(
                minecraft,
                intensity
        );

        if (intensity > 0.001F) {
            spawnDust(
                    minecraft.level,
                    minecraft,
                    intensity
            );
        }
    }

    @SubscribeEvent
    public static void onRenderFlash(
            RenderGuiEvent.Post event
    ) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.screen != null) {
            return;
        }

        drawFlash(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onRenderFlashOverScreen(
            ScreenEvent.Render.Post event
    ) {
        drawFlash(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onFogColour(
            ViewportEvent.ComputeFogColor event
    ) {
        if (
                event.getCamera()
                        .getFluidInCamera()
                        != FogType.NONE
        ) {
            return;
        }

        float intensity = falloutIntensity();

        if (intensity <= 0.001F) {
            return;
        }

        float blendStrength = Math.clamp(
                intensity * DetonationTuning.Fallout.FOG_COLOUR_STRENGTH,
                0.0F,
                1.0F
        );

        event.setRed(
                lerp(
                        event.getRed(),
                        DetonationTuning.Fallout.RED,
                        blendStrength
                )
        );

        event.setGreen(
                lerp(
                        event.getGreen(),
                        DetonationTuning.Fallout.GREEN,
                        blendStrength
                )
        );

        event.setBlue(
                lerp(
                        event.getBlue(),
                        DetonationTuning.Fallout.BLUE,
                        blendStrength
                )
        );
    }

    @SubscribeEvent
    public static void onRenderFog(
            ViewportEvent.RenderFog event
    ) {
        if (event.getType() != FogType.NONE) {
            return;
        }

        float intensity = falloutIntensity();

        if (intensity <= 0.001F) {
            return;
        }

        float vanillaNear = event.getNearPlaneDistance();
        float vanillaFar = event.getFarPlaneDistance();

        float falloutNear =
                vanillaFar * DetonationTuning.Fallout.FOG_START_RATIO;

        falloutNear = Math.clamp(
                falloutNear,
                0.0F,
                Math.max(0.0F, vanillaFar - 16.0F)
        );

        event.setNearPlaneDistance(
                lerp(
                        vanillaNear,
                        falloutNear,
                        intensity
                )
        );

        event.setFarPlaneDistance(vanillaFar);
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLogout(
            ClientPlayerNetworkEvent.LoggingOut event
    ) {
        clearAll();
        previousLevel = null;
    }

    private static void tickFallout(
            double playerX,
            double playerZ
    ) {
        float targetIntensity = 0.0F;

        Iterator<FalloutZone> iterator = FALLOUT_ZONES.iterator();

        while (iterator.hasNext()) {
            FalloutZone zone = iterator.next();

            zone.ageTicks++;

            if (
                    zone.durationTicks > 0
                            && zone.ageTicks >= zone.durationTicks
            ) {
                iterator.remove();
                continue;
            }

            double distance = Math.hypot(
                    playerX - zone.centreX,
                    playerZ - zone.centreZ
            );

            if (distance >= zone.radius) {
                continue;
            }

            float zoneIntensity =
                    zone.strength
                            * calculateFalloutTimeStrength(zone)
                            * calculateFalloutDistanceStrength(
                            distance,
                            zone.radius
                    );

            targetIntensity = Math.max(targetIntensity, zoneIntensity);
        }

        currentFalloutIntensity +=
                (targetIntensity - currentFalloutIntensity)
                        * DetonationTuning.Fallout.INTENSITY_SMOOTHING;

        if (
                targetIntensity <= 0.0F
                        && currentFalloutIntensity < 0.001F
        ) {
            currentFalloutIntensity = 0.0F;
        }
    }

    private static void tickDetonations(
            Minecraft minecraft
    ) {
        Iterator<ActiveDetonation> iterator =
                ACTIVE_DETONATIONS.iterator();

        while (iterator.hasNext()) {
            ActiveDetonation effect = iterator.next();

            spawnDetonationParticles(minecraft, effect);

            /*
             * Check before incrementing so a delay of N ticks actually waits
             * N complete client ticks.
             */
            if (
                    !effect.soundPlayed
                            && effect.ageTicks >= effect.soundDelayTicks
            ) {
                playBlastSound(minecraft, effect.soundStrength);
                effect.soundPlayed = true;
            }

            effect.ageTicks++;

            boolean flashFinished =
                    effect.ageTicks
                            > DetonationTuning.ImmediateEffects.FLASH_HOLD_TICKS
                            + DetonationTuning.ImmediateEffects.FLASH_FADE_TICKS;

            boolean particlesFinished =
                    effect.ageTicks
                            > DetonationTuning.ParticleCloud.TOTAL_LIFETIME_TICKS;

            if (flashFinished && effect.soundPlayed && particlesFinished) {
                iterator.remove();
            }
        }
    }

    private static void spawnDetonationParticles(
            Minecraft minecraft,
            ActiveDetonation effect
    ) {
        if (
                minecraft.level == null
                        || minecraft.player == null
        ) {
            return;
        }

        ClientLevel level = minecraft.level;

        double fireballRadius = getFireballRadius(effect.visualScale);
        double stemHeight = getStemHeight(effect.visualScale);
        double capRadius = getCapRadius(effect.visualScale);
        double surfaceShockwaveRadius = getSurfaceShockwaveRadius(effect.visualScale);
        double viewDistance = Math.max(
                DetonationTuning.ParticleCloud.MINIMUM_VIEW_DISTANCE,
                Math.max(surfaceShockwaveRadius, capRadius * 2.5D)
                        * DetonationTuning.ParticleCloud.VIEW_DISTANCE_MULTIPLIER
        );

        double distanceToViewerSq = minecraft.player.distanceToSqr(
                effect.centreX,
                effect.centreY + stemHeight * 0.5D,
                effect.centreZ
        );

        if (distanceToViewerSq > viewDistance * viewDistance) {
            return;
        }

        int age = effect.ageTicks;

        if (age <= DetonationTuning.ParticleCloud.FIREBALL_TO_CLOUD_TICKS) {
            spawnFireball(level, effect, age, fireballRadius);
        }

        if (age <= DetonationTuning.ParticleCloud.CLOUD_FORMATION_TICKS) {
            spawnSurfaceShockwave(level, effect, age, surfaceShockwaveRadius);
        }

        if (age >= DetonationTuning.ParticleCloud.SKY_SHOCKWAVE_DELAY_TICKS
                && age <= DetonationTuning.ParticleCloud.SKY_SHOCKWAVE_DELAY_TICKS
                + DetonationTuning.ParticleCloud.SKY_SHOCKWAVE_DURATION_TICKS) {
            spawnSkyShockwave(level, effect, age, fireballRadius, stemHeight, capRadius);
        }

        if (age >= DetonationTuning.ParticleCloud.FIREBALL_GROWTH_TICKS / 2) {
            spawnMushroomStem(level, effect, age, stemHeight, capRadius);
            spawnMushroomCap(level, effect, age, fireballRadius, stemHeight, capRadius);
        }
    }

    private static void spawnFireball(
            ClientLevel level,
            ActiveDetonation effect,
            int age,
            double fireballRadius
    ) {
        double progress = Math.clamp(
                age / (double) DetonationTuning.ParticleCloud.FIREBALL_TO_CLOUD_TICKS,
                0.0D,
                1.0D
        );

        double growth = easeOutCubic(Math.min(1.0D, progress * 1.08D));
        double radius = fireballRadius * (0.35D + 0.65D * growth);
        double centreY =
                effect.centreY
                        + 2.0D
                        + effect.visualScale
                        * DetonationTuning.ParticleCloud.FIREBALL_RISE_MULTIPLIER
                        * Math.min(1.0D, progress * 1.25D);

        int particleCount = Math.clamp(
                (int) Math.round((180.0D + radius * 9.5D) * (0.70D + effect.visualStrength * 0.30D)),
                180,
                DetonationTuning.ParticleCloud.MAX_FIREBALL_PARTICLES_PER_TICK
        );

        float flashChance = (float) Math.max(0.0D, 0.18D - progress * 0.16D);
        float explosionChance = (float) Math.max(0.06D, 0.26D - progress * 0.12D);
        float lavaChance = (float) Math.max(0.03D, 0.12D - progress * 0.05D);
        float flameChance = (float) Math.max(0.30D, 0.56D - progress * 0.18D);
        float darkSmokeChance = (float) Math.min(0.58D, 0.06D + progress * 0.46D);

        for (int index = 0; index < particleCount; index++) {
            double azimuth = level.random.nextDouble() * Math.PI * 2.0D;
            double vertical = level.random.nextDouble() * 2.0D - 1.0D;
            double densityBias = level.random.nextDouble();
            double radialFactor = densityBias < 0.72D
                    ? Math.cbrt(level.random.nextDouble())
                    : 0.78D + level.random.nextDouble() * 0.22D;
            double sampleRadius = radius * radialFactor;
            double horizontal = Math.sqrt(Math.max(0.0D, 1.0D - vertical * vertical));

            double offsetX = Math.cos(azimuth) * horizontal * sampleRadius;
            double offsetY = vertical * sampleRadius * 0.90D;
            double offsetZ = Math.sin(azimuth) * horizontal * sampleRadius;

            double particleX = effect.centreX + offsetX;
            double particleY = centreY + offsetY;
            double particleZ = effect.centreZ + offsetZ;

            double outward = 0.020D + radialFactor * 0.020D;
            double velocityX = offsetX * outward / Math.max(1.0D, radius);
            double velocityY = 0.030D + Math.abs(vertical) * 0.026D + level.random.nextDouble() * 0.055D;
            double velocityZ = offsetZ * outward / Math.max(1.0D, radius);

            float roll = level.random.nextFloat();
            float cut1 = flashChance;
            float cut2 = cut1 + explosionChance;
            float cut3 = cut2 + lavaChance;
            float cut4 = cut3 + flameChance;
            float cut5 = cut4 + darkSmokeChance;

            if (roll < cut1) {
                level.addParticle(ParticleTypes.FLASH, particleX, particleY, particleZ, 0.0D, 0.0D, 0.0D);
            } else if (roll < cut2) {
                level.addParticle(ParticleTypes.EXPLOSION, particleX, particleY, particleZ, velocityX * 0.25D, velocityY * 0.20D, velocityZ * 0.25D);
            } else if (roll < cut3) {
                level.addParticle(ParticleTypes.LAVA, particleX, particleY, particleZ, velocityX * 0.18D, velocityY * 0.20D, velocityZ * 0.18D);
            } else if (roll < cut4) {
                level.addParticle(ParticleTypes.FLAME, particleX, particleY, particleZ, velocityX, velocityY, velocityZ);
            } else if (roll < cut5) {
                level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, particleX, particleY, particleZ, velocityX * 0.55D, velocityY + 0.020D, velocityZ * 0.55D);
            } else if (roll < 0.96F) {
                level.addParticle(ParticleTypes.SMOKE, particleX, particleY, particleZ, velocityX * 0.62D, velocityY * 0.80D, velocityZ * 0.62D);
            } else {
                level.addParticle(ParticleTypes.ASH, particleX, particleY, particleZ, velocityX * 0.30D, velocityY * 0.35D, velocityZ * 0.30D);
            }
        }

        if (progress < 0.40D) {
            int coreFlashes = Math.max(6, (int) Math.round(22.0D * (1.0D - progress)));
            for (int i = 0; i < coreFlashes; i++) {
                double angle = level.random.nextDouble() * Math.PI * 2.0D;
                double dist = level.random.nextDouble() * radius * 0.22D;
                double x = effect.centreX + Math.cos(angle) * dist;
                double y = centreY + (level.random.nextDouble() - 0.5D) * radius * 0.18D;
                double z = effect.centreZ + Math.sin(angle) * dist;
                level.addParticle(ParticleTypes.FLASH, x, y, z, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    private static void spawnMushroomStem(
            ClientLevel level,
            ActiveDetonation effect,
            int age,
            double fullStemHeight,
            double capRadius
    ) {
        double progress = getCloudProgress(age);
        double stemHeight = fullStemHeight * (0.18D + 0.82D * easeOutCubic(progress));
        double stemRadius = Math.max(4.0D, capRadius * DetonationTuning.ParticleCloud.STEM_RADIUS_RATIO);

        int particleCount = Math.clamp(
                (int) Math.round((40.0D + stemHeight * 1.80D) * (0.70D + effect.visualStrength * 0.30D)),
                40,
                DetonationTuning.ParticleCloud.MAX_STEM_PARTICLES_PER_TICK
        );

        for (int index = 0; index < particleCount; index++) {
            double heightProgress = level.random.nextDouble();
            double y = effect.centreY + stemHeight * heightProgress;
            double bulge = 0.72D + 0.48D * Math.sin(heightProgress * Math.PI * 0.92D);
            double localRadius = stemRadius * bulge;
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double radius = localRadius * Math.sqrt(level.random.nextDouble());

            double x = effect.centreX + Math.cos(angle) * radius;
            double z = effect.centreZ + Math.sin(angle) * radius;

            double vx = Math.cos(angle) * 0.010D;
            double vy = 0.022D + level.random.nextDouble() * 0.030D;
            double vz = Math.sin(angle) * 0.010D;

            float roll = level.random.nextFloat();

            if (roll < 0.40F) {
                level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x, y, z, vx, vy, vz);
            } else if (roll < 0.76F) {
                level.addParticle(ParticleTypes.SMOKE, x, y, z, vx, vy, vz);
            } else if (roll < 0.92F) {
                level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, vx * 0.80D, vy, vz * 0.80D);
            } else {
                level.addParticle(ParticleTypes.ASH, x, y, z, vx * 0.45D, vy * 0.55D, vz * 0.45D);
            }
        }
    }

    private static void spawnMushroomCap(
            ClientLevel level,
            ActiveDetonation effect,
            int age,
            double fireballRadius,
            double fullStemHeight,
            double fullCapRadius
    ) {
        double progress = getCloudProgress(age);
        double easedProgress = easeOutCubic(progress);
        double capRadius = fireballRadius + (fullCapRadius - fireballRadius) * easedProgress;
        double capThickness = Math.max(8.0D, capRadius * DetonationTuning.ParticleCloud.CAP_THICKNESS_MULTIPLIER);
        double capY = effect.centreY + fullStemHeight * (0.24D + 0.76D * easedProgress);

        int particleCount = Math.clamp(
                (int) Math.round((170.0D + capRadius * 6.0D) * (0.70D + effect.visualStrength * 0.30D)),
                170,
                DetonationTuning.ParticleCloud.MAX_CAP_PARTICLES_PER_TICK
        );

        float hotCoreChance = (float) Math.max(0.0D, 0.22D - progress * 0.22D);

        for (int index = 0; index < particleCount; index++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double disc = Math.sqrt(level.random.nextDouble());
            double outerBias = 0.35D + 0.65D * disc;
            double ringRadius = capRadius * outerBias;
            double normalized = ringRadius / Math.max(1.0D, capRadius);
            double domeTop = (1.0D - normalized * normalized) * capThickness * 0.65D;
            double underside = normalized > 0.72D
                    ? -(normalized - 0.72D) / 0.28D * capThickness * 0.45D
                    : 0.0D;
            double randomVertical = (level.random.nextDouble() - 0.5D) * capThickness * 0.70D;

            double x = effect.centreX + Math.cos(angle) * ringRadius;
            double y = capY + domeTop + underside + randomVertical;
            double z = effect.centreZ + Math.sin(angle) * ringRadius;

            double radial = 0.010D + outerBias * 0.012D;
            double vx = Math.cos(angle) * radial;
            double vy = 0.018D + level.random.nextDouble() * 0.032D;
            double vz = Math.sin(angle) * radial;

            float roll = level.random.nextFloat();

            if (roll < hotCoreChance) {
                level.addParticle(ParticleTypes.FLAME, x, y, z, vx * 0.35D, vy, vz * 0.35D);
            } else if (roll < 0.38F) {
                level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, vx * 0.90D, vy, vz * 0.90D);
            } else if (roll < 0.74F) {
                level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x, y, z, vx * 0.85D, vy + 0.012D, vz * 0.85D);
            } else if (roll < 0.94F) {
                level.addParticle(ParticleTypes.SMOKE, x, y, z, vx * 0.70D, vy * 0.85D, vz * 0.70D);
            } else {
                level.addParticle(ParticleTypes.ASH, x, y, z, vx * 0.35D, vy * 0.45D, vz * 0.35D);
            }
        }
    }

    private static void spawnSurfaceShockwave(
            ClientLevel level,
            ActiveDetonation effect,
            int age,
            double maxRadius
    ) {
        if (age < DetonationTuning.ParticleCloud.SURFACE_SHOCKWAVE_DELAY_TICKS) {
            return;
        }

        double progress = Math.clamp(
                (age - DetonationTuning.ParticleCloud.SURFACE_SHOCKWAVE_DELAY_TICKS)
                        / (double) DetonationTuning.ParticleCloud.SURFACE_SHOCKWAVE_DURATION_TICKS,
                0.0D,
                1.0D
        );

        double radius = 3.0D + maxRadius * easeOutCubic(progress);

        spawnShockwaveBand(
                level,
                effect.centreX,
                effect.centreY + 1.50D,
                effect.centreZ,
                radius,
                DetonationTuning.ParticleCloud.SURFACE_SHOCKWAVE_RING_THICKNESS,
                1.0D,
                0.050D,
                0.008D,
                DetonationTuning.ParticleCloud.MAX_SHOCKWAVE_PARTICLES_PER_TICK
        );

        spawnShockwaveBand(
                level,
                effect.centreX,
                effect.centreY + 1.55D,
                effect.centreZ,
                radius * 0.92D,
                DetonationTuning.ParticleCloud.SURFACE_SHOCKWAVE_RING_THICKNESS * 0.70D,
                0.55D,
                0.038D,
                0.006D,
                DetonationTuning.ParticleCloud.MAX_SHOCKWAVE_PARTICLES_PER_TICK / 2
        );
    }

    private static void spawnSkyShockwave(
            ClientLevel level,
            ActiveDetonation effect,
            int age,
            double fireballRadius,
            double fullStemHeight,
            double capRadius
    ) {
        double progress = Math.clamp(
                (age - DetonationTuning.ParticleCloud.SKY_SHOCKWAVE_DELAY_TICKS)
                        / (double) DetonationTuning.ParticleCloud.SKY_SHOCKWAVE_DURATION_TICKS,
                0.0D,
                1.0D
        );

        double cloudProgress = getCloudProgress(age);
        double capY = effect.centreY
                + fullStemHeight * (0.25D + 0.75D * easeOutCubic(cloudProgress));
        double initialRadius = Math.max(8.0D, fireballRadius * 0.90D);
        double maxRadius = Math.max(
                DetonationTuning.ParticleCloud.MINIMUM_SKY_SHOCKWAVE_RADIUS,
                capRadius * DetonationTuning.ParticleCloud.SKY_SHOCKWAVE_RADIUS_MULTIPLIER
        );
        double radius = initialRadius + (maxRadius - initialRadius) * easeOutCubic(progress);
        double y = capY + fireballRadius * 0.32D;

        spawnShockwaveBand(
                level,
                effect.centreX,
                y,
                effect.centreZ,
                radius,
                DetonationTuning.ParticleCloud.SKY_SHOCKWAVE_RING_THICKNESS,
                1.0D,
                0.032D,
                0.003D,
                DetonationTuning.ParticleCloud.MAX_SHOCKWAVE_PARTICLES_PER_TICK
        );

        spawnShockwaveBand(
                level,
                effect.centreX,
                y + 0.10D,
                effect.centreZ,
                radius * 0.84D,
                DetonationTuning.ParticleCloud.SKY_SHOCKWAVE_RING_THICKNESS * 0.90D,
                0.60D,
                0.028D,
                0.002D,
                DetonationTuning.ParticleCloud.MAX_SHOCKWAVE_PARTICLES_PER_TICK / 2
        );

        spawnShockwaveCenterCap(
                level,
                effect.centreX,
                y,
                effect.centreZ,
                Math.max(6.0D, radius * 0.10D),
                130
        );
    }

    private static void spawnShockwaveBand(
            ClientLevel level,
            double centreX,
            double centreY,
            double centreZ,
            double radius,
            double bandThickness,
            double densityScale,
            double radialVelocity,
            double verticalVelocity,
            int maximumParticles
    ) {
        int particleCount = Math.clamp(
                (int) Math.round((150.0D + radius * 8.0D) * densityScale),
                140,
                maximumParticles
        );

        for (int index = 0; index < particleCount; index++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double acrossBand = (level.random.nextDouble() - 0.5D) * bandThickness;
            double currentRadius = Math.max(0.0D, radius + acrossBand);

            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double x = centreX + cos * currentRadius;
            double y = centreY + (level.random.nextDouble() - 0.5D) * 0.18D;
            double z = centreZ + sin * currentRadius;

            double vx = cos * radialVelocity;
            double vy = verticalVelocity + (level.random.nextDouble() - 0.5D) * 0.004D;
            double vz = sin * radialVelocity;

            float roll = level.random.nextFloat();
            if (roll < 0.80F) {
                level.addParticle(ParticleTypes.CLOUD, x, y, z, vx, vy, vz);
            } else if (roll < 0.97F) {
                level.addParticle(ParticleTypes.POOF, x, y, z, vx * 0.85D, vy * 0.85D, vz * 0.85D);
            } else {
                level.addParticle(ParticleTypes.EXPLOSION, x, y, z, vx * 0.20D, vy * 0.20D, vz * 0.20D);
            }
        }
    }

    private static void spawnShockwaveCenterCap(
            ClientLevel level,
            double centreX,
            double centreY,
            double centreZ,
            double radius,
            int particleCount
    ) {
        for (int index = 0; index < particleCount; index++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double dist = Math.sqrt(level.random.nextDouble()) * radius;
            double x = centreX + Math.cos(angle) * dist;
            double y = centreY + (level.random.nextDouble() - 0.5D) * 0.15D;
            double z = centreZ + Math.sin(angle) * dist;
            level.addParticle(ParticleTypes.CLOUD, x, y, z, 0.0D, 0.001D, 0.0D);
        }
    }

    private static void drawFlash(
            GuiGraphics guiGraphics
    ) {

        float intensity = flashIntensity();

        if (intensity <= 0.001F) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        int alpha = Math.clamp(
                Math.round(intensity * 255.0F),
                0,
                255
        );

        int colour =
                alpha << 24
                        | DetonationTuning.ImmediateEffects.FLASH_RGB;

        guiGraphics.fill(
                0,
                0,
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight(),
                colour
        );
    }

    private static float calculateFalloutTimeStrength(
            FalloutZone zone
    ) {
        float fadeIn = Math.clamp(
                zone.ageTicks / (float) DetonationTuning.Fallout.FADE_IN_TICKS,
                0.0F,
                1.0F
        );

        if (zone.durationTicks <= 0) {
            return fadeIn;
        }

        int remainingTicks = zone.durationTicks - zone.ageTicks;

        float fadeOut = Math.clamp(
                remainingTicks / (float) DetonationTuning.Fallout.FADE_OUT_TICKS,
                0.0F,
                1.0F
        );

        return Math.min(fadeIn, fadeOut);
    }

    private static float calculateFalloutDistanceStrength(
            double distance,
            double radius
    ) {
        double normalisedDistance = distance / radius;

        if (
                normalisedDistance
                        <= DetonationTuning.Fallout.FULL_STRENGTH_RADIUS_RATIO
        ) {
            return 1.0F;
        }

        double fadeProgress =
                (
                        normalisedDistance
                                - DetonationTuning.Fallout.FULL_STRENGTH_RADIUS_RATIO
                ) / (
                        1.0D
                                - DetonationTuning.Fallout.FULL_STRENGTH_RADIUS_RATIO
                );

        return (float) (1.0D - smoothstep(fadeProgress));
    }

    private static float flashIntensity() {
        float greatestIntensity = 0.0F;

        for (ActiveDetonation effect : ACTIVE_DETONATIONS) {
            greatestIntensity = Math.max(
                    greatestIntensity,
                    calculateFlashIntensity(effect)
            );
        }

        return Math.clamp(greatestIntensity, 0.0F, 1.0F);
    }

    private static float calculateFlashIntensity(
            ActiveDetonation effect
    ) {
        if (effect.flashStrength <= 0.001F) {
            return 0.0F;
        }

        if (
                effect.ageTicks
                        <= DetonationTuning.ImmediateEffects.FLASH_HOLD_TICKS
        ) {
            return effect.flashStrength;
        }

        int fadeAge =
                effect.ageTicks
                        - DetonationTuning.ImmediateEffects.FLASH_HOLD_TICKS;

        if (
                fadeAge
                        >= DetonationTuning.ImmediateEffects.FLASH_FADE_TICKS
        ) {
            return 0.0F;
        }

        float fadeProgress = Math.clamp(
                fadeAge / (float) DetonationTuning.ImmediateEffects.FLASH_FADE_TICKS,
                0.0F,
                1.0F
        );

        float remaining = 1.0F - fadeProgress;
        return effect.flashStrength * remaining * remaining;
    }

    private static void playBlastSound(
            Minecraft minecraft,
            float strength
    ) {
        if (
                minecraft.level == null
                        || minecraft.player == null
                        || strength <= 0.001F
        ) {
            return;
        }

        float volume = lerp(
                DetonationTuning.ImmediateEffects.MINIMUM_SOUND_VOLUME,
                DetonationTuning.ImmediateEffects.MAXIMUM_SOUND_VOLUME,
                strength
        );

        float pitch =
                DetonationTuning.ImmediateEffects.MINIMUM_SOUND_PITCH
                        + minecraft.level.random.nextFloat()
                        * DetonationTuning.ImmediateEffects.SOUND_PITCH_VARIATION;

        minecraft.level.playLocalSound(
                minecraft.player.getX(),
                minecraft.player.getY(),
                minecraft.player.getZ(),
                DetonationSounds.NUCLEAR_BLAST.get(),
                SoundSource.MASTER,
                volume,
                pitch,
                false
        );
    }

    private static void refreshWaterTintWhenNeeded(
            Minecraft minecraft,
            float intensity
    ) {
        if (minecraft.level == null) {
            previousWaterTintStage = -1;
            return;
        }

        int tintStage;

        if (intensity <= 0.001F) {
            tintStage = 0;
        } else if (intensity < 0.35F) {
            tintStage = 1;
        } else if (intensity < 0.80F) {
            tintStage = 2;
        } else {
            tintStage = 3;
        }

        if (tintStage == previousWaterTintStage) {
            return;
        }

        previousWaterTintStage = tintStage;

        minecraft.level.clearTintCaches();
        minecraft.levelRenderer.allChanged();
    }

    private static void spawnDust(
            ClientLevel level,
            Minecraft minecraft,
            float intensity
    ) {
        if (minecraft.player == null) {
            return;
        }

        int particleCount = Math.max(
                1,
                Math.round(
                        DetonationTuning.Fallout.MAX_DUST_PARTICLES_PER_TICK
                                * intensity
                )
        );

        for (int index = 0; index < particleCount; index++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance =
                    Math.sqrt(level.random.nextDouble())
                            * DetonationTuning.Fallout.DUST_HORIZONTAL_RADIUS;

            double particleX =
                    minecraft.player.getX()
                            + Math.cos(angle) * distance;

            double particleZ =
                    minecraft.player.getZ()
                            + Math.sin(angle) * distance;

            double particleY =
                    minecraft.player.getY()
                            - 2.0D
                            + level.random.nextDouble()
                            * DetonationTuning.Fallout.DUST_VERTICAL_RANGE;

            double windX = -0.012D + level.random.nextDouble() * 0.010D;
            double windZ = 0.004D + level.random.nextDouble() * 0.010D;
            double verticalMovement = -0.003D + level.random.nextDouble() * 0.006D;

            level.addParticle(
                    FALLOUT_DUST,
                    particleX,
                    particleY,
                    particleZ,
                    windX,
                    verticalMovement,
                    windZ
            );
        }
    }

    private static float calculateDistanceStrength(
            double distance,
            double radius,
            double fullStrengthRatio
    ) {
        if (radius <= 0.0D || distance >= radius) {
            return 0.0F;
        }

        double fullStrengthRadius = radius * fullStrengthRatio;

        if (distance <= fullStrengthRadius) {
            return 1.0F;
        }

        double progress =
                (
                        distance - fullStrengthRadius
                ) / (
                        radius - fullStrengthRadius
                );

        return (float) (1.0D - smoothstep(progress));
    }

    private static double getFireballRadius(
            float visualScale
    ) {
        return Math.clamp(
                visualScale * DetonationTuning.ParticleCloud.FIREBALL_RADIUS_MULTIPLIER,
                (double) DetonationTuning.ParticleCloud.MINIMUM_FIREBALL_RADIUS,
                (double) DetonationTuning.ParticleCloud.MAXIMUM_FIREBALL_RADIUS
        );
    }

    private static double getStemHeight(
            float visualScale
    ) {
        return Math.max(
                DetonationTuning.ParticleCloud.MINIMUM_STEM_HEIGHT,
                visualScale * DetonationTuning.ParticleCloud.STEM_HEIGHT_MULTIPLIER
        );
    }

    private static double getCapRadius(
            float visualScale
    ) {
        return Math.max(
                DetonationTuning.ParticleCloud.MINIMUM_CAP_RADIUS,
                visualScale * DetonationTuning.ParticleCloud.CAP_RADIUS_MULTIPLIER
        );
    }

    private static double getSurfaceShockwaveRadius(
            float visualScale
    ) {
        return Math.max(
                DetonationTuning.ParticleCloud.MINIMUM_SURFACE_SHOCKWAVE_RADIUS,
                visualScale * DetonationTuning.ParticleCloud.SURFACE_SHOCKWAVE_RADIUS_MULTIPLIER
        );
    }

    private static double getCloudProgress(
            int age
    ) {
        return Math.clamp(
                (age - DetonationTuning.ParticleCloud.FIREBALL_GROWTH_TICKS / 2.0D)
                        / (double) DetonationTuning.ParticleCloud.CLOUD_FORMATION_TICKS,
                0.0D,
                1.0D
        );
    }

    private static double easeOutCubic(
            double value
    ) {
        double inverse = 1.0D - Math.clamp(value, 0.0D, 1.0D);
        return 1.0D - inverse * inverse * inverse;
    }

    private static double smoothstep(
            double value
    ) {
        value = Math.clamp(value, 0.0D, 1.0D);
        return value * value * (3.0D - 2.0D * value);
    }

    private static float lerp(
            float start,
            float end,
            float progress
    ) {
        return start + (end - start) * progress;
    }

    private static void clearAll() {
        FALLOUT_ZONES.clear();
        ACTIVE_DETONATIONS.clear();
        currentFalloutIntensity = 0.0F;
        previousWaterTintStage = -1;
    }

    private static final class FalloutZone {
        private final double centreX;
        private final double centreZ;
        private final float radius;
        private final int durationTicks;
        private final float strength;

        private int ageTicks;

        private FalloutZone(
                double centreX,
                double centreZ,
                float radius,
                int durationTicks,
                float strength
        ) {
            this.centreX = centreX;
            this.centreZ = centreZ;
            this.radius = radius;
            this.durationTicks = durationTicks;
            this.strength = strength;
        }
    }

    private static final class ActiveDetonation {
        private final double centreX;
        private final double centreY;
        private final double centreZ;
        private final float visualScale;
        private final float visualStrength;
        private final float flashStrength;
        private final float soundStrength;
        private final int soundDelayTicks;

        private int ageTicks;
        private boolean soundPlayed;

        private ActiveDetonation(
                double centreX,
                double centreY,
                double centreZ,
                float visualScale,
                float visualStrength,
                float flashStrength,
                float soundStrength,
                int soundDelayTicks
        ) {
            this.centreX = centreX;
            this.centreY = centreY;
            this.centreZ = centreZ;
            this.visualScale = visualScale;
            this.visualStrength = visualStrength;
            this.flashStrength = flashStrength;
            this.soundStrength = soundStrength;
            this.soundDelayTicks = soundDelayTicks;
            this.soundPlayed = soundStrength <= 0.001F;
        }
    }
}
