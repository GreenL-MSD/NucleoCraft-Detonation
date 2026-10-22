package com.github.loganmartingreen.nucleocraftdetonation.client;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.client.render.DetonationShockwaveRenderer;
import com.github.loganmartingreen.nucleocraftdetonation.compat.distanthorizons.DistantHorizonsCompat;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.DetonationEffectsPayload;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.DistantHorizonsPresencePayload;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.FalloutVisualPayload;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.PersistentVisualEntry;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.PersistentVisualSyncPayload;
import com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticleSystem;
import com.github.loganmartingreen.nucleocraftdetonation.sound.DetonationSounds;
import com.mojang.blaze3d.shaders.FogShape;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.neoforged.neoforge.network.PacketDistributor;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Coordinates the client-side detonation systems.
 *
 * <p>The custom fireball and mushroom-cloud particles are implemented in
 * {@link DetonationParticleSystem}. The two solid shockwave rings are
 * implemented in {@link DetonationShockwaveRenderer}. This class retains the
 * flash, delayed sound, fallout atmosphere and client lifecycle cleanup.</p>
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

    private static final List<ImmediateDetonation> IMMEDIATE_DETONATIONS =
            new ArrayList<>();

    private static ClientLevel previousLevel;
    private static PersistentVisualSyncPayload pendingPersistentSync;
    private static float currentFalloutIntensity;
    private static boolean distantHorizonsPresenceSent;

    private DetonationClient() {
    }

    public static void startFallout(
            FalloutVisualPayload payload
    ) {
        startFallout(
                payload.centre(),
                payload.radius(),
                payload.durationTicks(),
                payload.strength(),
                0
        );
    }

    private static void startFallout(
            net.minecraft.core.BlockPos centre,
            float radius,
            int durationTicks,
            float strength,
            int ageTicks
    ) {
        durationTicks = durationTicks <= 0
                ? -1
                : durationTicks;

        double centreX = centre.getX() + 0.5D;
        double centreZ = centre.getZ() + 0.5D;
        float safeRadius = Math.max(1.0F, radius);

        FALLOUT_ZONES.removeIf(zone ->
                zone.centreX == centreX
                        && zone.centreZ == centreZ
                        && Math.abs(zone.radius - safeRadius) < 0.001F
        );

        FALLOUT_ZONES.add(
                new FalloutZone(
                        centreX,
                        centreZ,
                        safeRadius,
                        durationTicks,
                        Math.clamp(strength, 0.0F, 1.0F),
                        Math.max(0, ageTicks)
                )
        );
    }

    public static void replacePersistentVisuals(
            PersistentVisualSyncPayload payload
    ) {
        pendingPersistentSync = payload;
    }

    public static void clearMushroomCloudEffects() {
        DetonationParticleSystem.clear();
        DistantHorizonsCompat.clearMushroomClouds();
    }

    private static void applyPersistentVisuals(
            Minecraft minecraft,
            PersistentVisualSyncPayload payload
    ) {
        clearAll();
        previousLevel = minecraft.level;

        for (PersistentVisualEntry entry : payload.entries()) {
            if (entry.falloutActive() || entry.cloudActive()) {
                DistantHorizonsCompat.queueCraterRefresh(
                        entry.centre(),
                        Math.max(1, Math.round(entry.visualScale())) + 16
                );
            }

            if (entry.falloutActive()) {
                startFallout(
                        entry.centre(),
                        entry.falloutRadius(),
                        entry.falloutDurationTicks(),
                        entry.falloutStrength(),
                        entry.falloutAgeTicks()
                );
            }

            if (
                    entry.cloudActive()
                            && entry.cloudAgeTicks()
                            < DetonationTuning.ParticleCloud.TOTAL_LIFETIME_TICKS
            ) {
                DetonationParticleSystem.start(
                        entry.centre().getX() + 0.5D,
                        entry.centre().getY() + 0.5D,
                        entry.centre().getZ() + 0.5D,
                        entry.visualScale(),
                        entry.cloudStrength(),
                        entry.cloudAgeTicks()
                );
            }
        }
    }

    public static void startDetonation(
            DetonationEffectsPayload payload
    ) {
        Minecraft minecraft = Minecraft.getInstance();

        if (
                minecraft.level == null
                        || minecraft.player == null
        ) {
            return;
        }

        double centreX = payload.centre().getX() + 0.5D;
        double centreY = payload.centre().getY() + 0.5D;
        double centreZ = payload.centre().getZ() + 0.5D;
        float visualScale = Math.max(1.0F, payload.visualScale());
        float strength = Math.clamp(payload.strength(), 0.0F, 1.0F);

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
                ) * strength;

        float soundStrength =
                calculateDistanceStrength(
                        distance,
                        payload.soundRadius(),
                        DetonationTuning.ImmediateEffects.FULL_SOUND_RADIUS_RATIO
                ) * strength;

        int soundDelayTicks = (int) Math.round(
                distance
                        / DetonationTuning.ImmediateEffects.SPEED_OF_SOUND_BLOCKS_PER_SECOND
                        * DetonationTuning.ImmediateEffects.TICKS_PER_SECOND
        );

        if (
                flashStrength > 0.001F
                        || soundStrength > 0.001F
        ) {
            IMMEDIATE_DETONATIONS.add(
                    new ImmediateDetonation(
                            Math.clamp(flashStrength, 0.0F, 1.0F),
                            Math.clamp(soundStrength, 0.0F, 1.0F),
                            Math.max(0, soundDelayTicks)
                    )
            );
        }

        DetonationParticleSystem.start(
                centreX,
                centreY,
                centreZ,
                visualScale,
                strength
        );

        DetonationShockwaveRenderer.start(
                centreX,
                centreY,
                centreZ,
                visualScale,
                strength
        );
    }

    /**
     * Used by the sky mixin and fallout visual systems.
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
        Minecraft minecraft = Minecraft.getInstance();

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

        if (!distantHorizonsPresenceSent) {
            if (!DistantHorizonsCompat.isInstalled()) {
                PacketDistributor.sendToServer(
                        new DistantHorizonsPresencePayload(false)
                );
                distantHorizonsPresenceSent = true;
            } else if (DistantHorizonsCompat.isOperational()) {
                PacketDistributor.sendToServer(
                        new DistantHorizonsPresencePayload(true)
                );
                distantHorizonsPresenceSent = true;
            }
        }

        if (pendingPersistentSync != null) {
            PersistentVisualSyncPayload payload = pendingPersistentSync;
            pendingPersistentSync = null;
            applyPersistentVisuals(minecraft, payload);
        }

        if (minecraft.isPaused()) {
            return;
        }

        tickFallout(
                minecraft.player.getX(),
                minecraft.player.getZ()
        );

        tickImmediateDetonations(minecraft);
        DistantHorizonsCompat.tick(minecraft);
        DetonationParticleSystem.tick(minecraft);
        DetonationShockwaveRenderer.tick();

        float intensity = falloutIntensity();

        if (intensity > 0.001F) {
            spawnFalloutDust(
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
                event.getCamera().getFluidInCamera()
                        != FogType.NONE
        ) {
            return;
        }

        float intensity = falloutIntensity();

        if (intensity <= 0.001F) {
            return;
        }

        float blendStrength = Math.clamp(
                intensity
                        * DetonationTuning.Fallout.FOG_COLOUR_STRENGTH,
                0.0F,
                1.0F
        );

        event.setRed(
                lerp(
                        event.getRed(),
                        DetonationTuning.Fallout.FOG_RED,
                        blendStrength
                )
        );

        event.setGreen(
                lerp(
                        event.getGreen(),
                        DetonationTuning.Fallout.FOG_GREEN,
                        blendStrength
                )
        );

        event.setBlue(
                lerp(
                        event.getBlue(),
                        DetonationTuning.Fallout.FOG_BLUE,
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

        float targetFalloutFar = Math.max(
                DetonationTuning.Fallout.FOG_MIN_END_DISTANCE_BLOCKS,
                Math.min(
                        DetonationTuning.Fallout.FOG_END_DISTANCE_BLOCKS,
                        vanillaFar * DetonationTuning.Fallout.FOG_END_RENDER_RATIO
                )
        );
        float falloutFar = lerp(
                vanillaFar,
                targetFalloutFar,
                intensity
        );
        float falloutNear = falloutFar
                * DetonationTuning.Fallout.FOG_START_RATIO;

        falloutNear = Math.clamp(
                falloutNear,
                0.0F,
                Math.max(0.0F, falloutFar - 12.0F)
        );

        event.setNearPlaneDistance(
                lerp(
                        vanillaNear,
                        falloutNear,
                        intensity
                )
        );

        event.setFarPlaneDistance(falloutFar);
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLogout(
            ClientPlayerNetworkEvent.LoggingOut event
    ) {
        clearAll();
        pendingPersistentSync = null;
        previousLevel = null;
        distantHorizonsPresenceSent = false;
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

            targetIntensity = Math.max(
                    targetIntensity,
                    zoneIntensity
            );
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

    private static void tickImmediateDetonations(
            Minecraft minecraft
    ) {
        Iterator<ImmediateDetonation> iterator =
                IMMEDIATE_DETONATIONS.iterator();

        while (iterator.hasNext()) {
            ImmediateDetonation detonation = iterator.next();

            if (
                    !detonation.soundPlayed
                            && detonation.ageTicks >= detonation.soundDelayTicks
            ) {
                playBlastSound(
                        minecraft,
                        detonation.soundStrength
                );

                detonation.soundPlayed = true;
            }

            detonation.ageTicks++;

            boolean flashFinished =
                    detonation.ageTicks
                            > DetonationTuning.ImmediateEffects.FLASH_HOLD_TICKS
                            + DetonationTuning.ImmediateEffects.FLASH_FADE_TICKS;

            if (flashFinished && detonation.soundPlayed) {
                iterator.remove();
            }
        }
    }

    private static float calculateFalloutTimeStrength(
            FalloutZone zone
    ) {
        float fadeIn = Math.clamp(
                zone.ageTicks
                        / (float) DetonationTuning.Fallout.FADE_IN_TICKS,
                0.0F,
                1.0F
        );

        if (zone.durationTicks <= 0) {
            return fadeIn;
        }

        int remainingTicks =
                zone.durationTicks - zone.ageTicks;

        float fadeOut = Math.clamp(
                remainingTicks
                        / (float) DetonationTuning.Fallout.FADE_OUT_TICKS,
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
        double fullStrengthRatio = Math.clamp(
                DetonationTuning.Fallout.FULL_STRENGTH_RADIUS_RATIO,
                0.0D,
                1.0D
        );

        if (
                fullStrengthRatio >= 1.0D
                        || normalisedDistance <= fullStrengthRatio
        ) {
            return 1.0F;
        }

        double fadeProgress =
                (normalisedDistance - fullStrengthRatio)
                        / (1.0D - fullStrengthRatio);

        return (float) (1.0D - smoothstep(fadeProgress));
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

        int colour = alpha << 24
                | DetonationTuning.ImmediateEffects.FLASH_RGB;

        guiGraphics.fill(
                0,
                0,
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight(),
                colour
        );
    }

    private static float flashIntensity() {
        float greatestIntensity = 0.0F;

        for (ImmediateDetonation detonation : IMMEDIATE_DETONATIONS) {
            greatestIntensity = Math.max(
                    greatestIntensity,
                    calculateFlashIntensity(detonation)
            );
        }

        return Math.clamp(
                greatestIntensity,
                0.0F,
                1.0F
        );
    }

    private static float calculateFlashIntensity(
            ImmediateDetonation detonation
    ) {
        if (detonation.flashStrength <= 0.001F) {
            return 0.0F;
        }

        if (
                detonation.ageTicks
                        <= DetonationTuning.ImmediateEffects.FLASH_HOLD_TICKS
        ) {
            return detonation.flashStrength;
        }

        int fadeAge =
                detonation.ageTicks
                        - DetonationTuning.ImmediateEffects.FLASH_HOLD_TICKS;

        if (
                fadeAge
                        >= DetonationTuning.ImmediateEffects.FLASH_FADE_TICKS
        ) {
            return 0.0F;
        }

        float fadeProgress = Math.clamp(
                fadeAge
                        / (float) DetonationTuning.ImmediateEffects.FLASH_FADE_TICKS,
                0.0F,
                1.0F
        );

        float remaining = 1.0F - fadeProgress;
        return detonation.flashStrength * remaining * remaining;
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

    private static void spawnFalloutDust(
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

            double windX = -0.012D
                    + level.random.nextDouble() * 0.010D;
            double windZ = 0.004D
                    + level.random.nextDouble() * 0.010D;
            double verticalMovement = -0.003D
                    + level.random.nextDouble() * 0.006D;

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
                (distance - fullStrengthRadius)
                        / (radius - fullStrengthRadius);

        return (float) (1.0D - smoothstep(progress));
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
        IMMEDIATE_DETONATIONS.clear();
        DetonationParticleSystem.clear();
        DetonationShockwaveRenderer.clear();
        DistantHorizonsCompat.clear();

        currentFalloutIntensity = 0.0F;
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
                float strength,
                int ageTicks
        ) {
            this.centreX = centreX;
            this.centreZ = centreZ;
            this.radius = radius;
            this.durationTicks = durationTicks;
            this.strength = strength;
            this.ageTicks = Math.max(0, ageTicks);
        }
    }

    private static final class ImmediateDetonation {
        private final float flashStrength;
        private final float soundStrength;
        private final int soundDelayTicks;

        private int ageTicks;
        private boolean soundPlayed;

        private ImmediateDetonation(
                float flashStrength,
                float soundStrength,
                int soundDelayTicks
        ) {
            this.flashStrength = flashStrength;
            this.soundStrength = soundStrength;
            this.soundDelayTicks = soundDelayTicks;
            this.soundPlayed = soundStrength <= 0.001F;
        }
    }
}
