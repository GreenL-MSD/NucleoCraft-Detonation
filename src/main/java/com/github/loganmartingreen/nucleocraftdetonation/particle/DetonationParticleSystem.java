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
import net.minecraft.world.level.levelgen.Heightmap;
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
    private static final int DARK_GREY = 0x383D3A;
    private static final int MATURE_SMOKE_GREY = 0x414541;
    private static final int LIGHT_MATURE_SMOKE_GREY = 0x686D69;
    private static final int NEAR_BLACK = 0x111312;

    private static final List<ActiveCloud> ACTIVE_CLOUDS =
            new ArrayList<>();
    private static int particleClearGeneration;
    private static final List<UndergroundEffect> UNDERGROUND_EFFECTS = new ArrayList<>();
    private static boolean debugMode;

    public static void setDebugMode(boolean enabled) { debugMode = enabled; }

    public static void startSealed(double x, double y, double z, float scale, float strength) {
        startUnderground(x, y, z, Double.NaN, scale, strength, 0);
    }

    private static void startUnderground(double x, double y, double z,
                                        double exitY, float scale, float strength, int age) {
        UNDERGROUND_EFFECTS.removeIf(effect ->
                Math.abs(effect.x - x) < 0.001D && Math.abs(effect.y - y) < 0.001D
                        && Math.abs(effect.z - z) < 0.001D);
        UNDERGROUND_EFFECTS.add(new UndergroundEffect(x, y, z, exitY,
                Math.max(1.0F, scale), Math.clamp(strength, 0.0F, 1.0F),
                Math.max(0, age)));
        if (Double.isFinite(exitY)) {
            debugVisual("Underground fireball ignition; separate breakout jet (no mushroom cap)");
        } else {
            debugVisual("Underground fireball ignition; contained; no surface jet or shockwave");
        }
    }

    private static void debugVisual(String stage) {
        Minecraft client = Minecraft.getInstance();
        if (debugMode && client.player != null) {
            client.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("[NUKE VISUAL] " + stage), false);
        }
    }


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
                        Double.NaN,
                        Math.clamp(
                                ageTicks,
                                0,
                                DetonationTuning.ParticleCloud.TOTAL_LIFETIME_TICKS
                        )
                )
        );
    }

    /** Breakout effects never join the surface mushroom-cloud list. */
    public static void startBreakout(double centreX, double centreY, double centreZ,
                                    double breakoutSurfaceY, float visualScale,
                                    float strength, int ageTicks) {
        startUnderground(centreX, centreY, centreZ, breakoutSurfaceY,
                visualScale, strength, ageTicks);
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
        Iterator<UndergroundEffect> underground = UNDERGROUND_EFFECTS.iterator();
        boolean particleMode = DetonationClientConfig.mushroomCloudRenderer()
                == DetonationClientConfig.MushroomCloudRenderer.PARTICLES;
        while (underground.hasNext()) {
            UndergroundEffect effect = underground.next();
            double view = getViewDistance(effect.scale);
            if (particleMode && minecraft.player.distanceToSqr(effect.x, effect.y, effect.z)
                    < view * view) {
                spawnUndergroundParticles(level, effect);
            }
            if (++effect.age >= UndergroundVisualTimeline.lifetime(effect.isBreakout())) {
                debugVisual(effect.isBreakout() ? "Breakout plume dissipated" :
                        "Underground fireball cooled and dissipated");
                underground.remove();
            }
        }
        Iterator<ActiveCloud> iterator = ACTIVE_CLOUDS.iterator();

        while (iterator.hasNext()) {
            ActiveCloud cloud = iterator.next();

            double viewDistance = getViewDistance(cloud.visualScale);
            SurfaceMushroomStem.Frame stemFrame = SurfaceMushroomStem.frame(
                    cloud.centreY, getCloudHeadCentreY(cloud),
                    getCloudVerticalRadius(cloud), cloud.visualScale, cloud.ageTicks);
            double distanceSquared = SurfaceMushroomStem.distanceSquaredToCloud(
                    minecraft.player.getX(), minecraft.player.getY(),
                    minecraft.player.getZ(), cloud.centreX, cloud.centreZ,
                    stemFrame, getCloudHeadCentreY(cloud)
                            + getCloudVerticalRadius(cloud));

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

            if (debugMode) {
                String stage = visualStage(cloud);
                if (!stage.equals(cloud.lastStage)) {
                    cloud.lastStage = stage;
                    debugVisual(stage);
                }
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

    private static String visualStage(ActiveCloud cloud) {
        if (isBreakoutTravelling(cloud)) return "Breakout plasma jet ascending from underground origin";
        int age = getFormationAge(cloud);
        if (isBreakoutCloud(cloud) && age < 20)
            return "Plasma emerged; cap bloom initiating";
        if (age < DetonationTuning.ParticleCloud.STEM_START_TICKS)
            return "Initial hot fireball forming";
        if (age < getAscentDuration(cloud.visualScale))
            return "Charcoal stem forming; hot thermal bands rising with cap";
        if (age < fullSizeTick(cloud.visualScale))
            return "Mushroom spreading; thermal bands frozen and cooling";
        if (getStemDissipation(cloud.ageTicks) > 0.0D)
            return getCapDissipation(cloud.ageTicks) > 0.0D
                    ? "Cap dissipating outside inward" : "Stem dissipating bottom upward";
        if (cloud.ageTicks >= fullSizeTick(cloud.visualScale)
                + DetonationTuning.ParticleCloud.MATURE_GREY_LIGHTEN_DURATION_TICKS)
            return "Mature medium-grey cloud; thermal bands static and cooling";
        return "Mushroom at full size; thermal bands remain static";
    }

    private static void spawnUndergroundParticles(ClientLevel level, UndergroundEffect effect) {
        final int age = effect.age;
        double radius = UndergroundVisualTimeline.fireballRadius(
                effect.scale, age, !effect.isBreakout());
        if (radius > 0.5D) {
            double cooled = UndergroundVisualTimeline.smooth((age - 42.0D) / 80.0D);
            for (int i = 0, count = age < 60 ? 90 : 28; i < count; i++) {
                double theta = level.random.nextDouble() * Mth.TWO_PI;
                double vertical = level.random.nextDouble() * 2.0D - 1.0D;
                double rho = Math.cbrt(level.random.nextDouble());
                double horizontal = Math.sqrt(1.0D - vertical * vertical) * rho;
                double x = effect.x + Math.cos(theta) * radius * horizontal;
                double y = effect.y + vertical * radius * rho
                        * UndergroundVisualTimeline.fireballVerticalRatio(!effect.isBreakout());
                double z = effect.z + Math.sin(theta) * radius * horizontal;
                if (!level.getBlockState(net.minecraft.core.BlockPos.containing(x,y,z)).isAir()) continue;
                int hot = UndergroundVisualTimeline.mix(HOT_YELLOW, BRIGHT_ORANGE, age / 62.0D);
                int smoke = UndergroundVisualTimeline.mix(hot, DARK_GREY, cooled);
                float size = (float) Math.clamp(radius * (0.10D + level.random.nextDouble() * 0.10D),
                        1.1D, 10.0D);
                level.addParticle(new TintedPoofOptions(hot, smoke, size, size * 1.2F,
                        15 + level.random.nextInt(22), 0.0F, 0.93F, 0.02F, age < 45),
                        x, y, z, 0.0D, 0.0D, 0.0D);
            }
        }
        if (!effect.isBreakout() || age < UndergroundVisualTimeline.JET_START_TICK) return;
        double top = UndergroundVisualTimeline.jetTop(effect.y, effect.exitY, effect.scale, age);
        if (top <= effect.y + 0.5D) return;
        double length = top - effect.y;
        int count = Math.clamp((int)(length * 2.0D), 90, 290);
        for (int i = 0; i < count; i++) {
            double h = level.random.nextDouble();
            double y = effect.y + length * h;
            double radiusAtY = UndergroundVisualTimeline.jetRadius(
                    effect.y, effect.exitY, y, effect.scale);
            double angle = level.random.nextDouble() * Mth.TWO_PI;
            double radial = Math.sqrt(level.random.nextDouble());
            // Keep the jet centred above the opening; turbulence belongs on
            // the edges, never as a swaying centreline.
            double x = effect.x + Math.cos(angle) * radial * radiusAtY;
            double z = effect.z + Math.sin(angle) * radial * radiusAtY;
            if (!level.getBlockState(net.minecraft.core.BlockPos.containing(x,y,z)).isAir()) continue;
            double cooling = UndergroundVisualTimeline.cooling(age, y, effect.y, top);
            double alpha = UndergroundVisualTimeline.jetAlpha(age, h, radial);
            if (alpha < 0.09D || level.random.nextDouble() > alpha) continue;
            int colour = UndergroundVisualTimeline.jetColour(age, h, cooling);
            int next = UndergroundVisualTimeline.mix(colour, 0x747874, cooling * 0.25D);
            float size = (float) Math.clamp(radiusAtY * (0.30D + level.random.nextDouble() * 0.22D),
                    1.3D, 12.0D);
            level.addParticle(new TintedPoofOptions(colour, next, size, size * 1.2F,
                    12 + level.random.nextInt(20), 0.0F, 0.90F, 0.01F,
                    cooling < 0.3D, (float) alpha), x, y, z,
                    0.0D, 0.07D + level.random.nextDouble() * 0.045D, 0.0D);
        }

        // A sparse, translucent sheath and torn wisps above the outlet soften
        // the column without ever forming a flat mushroom cap. Side wisps
        // disperse in different directions rather than moving the centreline.
        int wisps = Math.clamp((int)(effect.scale * 0.95D), 14, 48);
        for (int i = 0; i < wisps; i++) {
            double h = 0.28D + 0.72D * level.random.nextDouble();
            double y = effect.y + length * h;
            double breakup = UndergroundVisualTimeline.breakup(age, h);
            double angle = level.random.nextDouble() * Mth.TWO_PI;
            double r = UndergroundVisualTimeline.jetRadius(effect.y, effect.exitY, y, effect.scale);
            boolean head = h > 0.77D;
            double dist = r * (1.02D + level.random.nextDouble() * (0.30D + 0.62D * breakup));
            double x = effect.x + Math.cos(angle) * dist;
            double z = effect.z + Math.sin(angle) * dist;
            if (!level.getBlockState(net.minecraft.core.BlockPos.containing(x, y, z)).isAir()) continue;
            double alpha = UndergroundVisualTimeline.fringeAlpha(age, h, head);
            if (alpha <= 0.05D || level.random.nextDouble() > 0.75D) continue;
            double cool = UndergroundVisualTimeline.cooling(age, y, effect.y, top);
            int hot = UndergroundVisualTimeline.jetColour(age, h, cool);
            int c = UndergroundVisualTimeline.mix(hot, 0x797C78, 0.35D + 0.55D * cool);
            float size = (float) Math.clamp(r * (0.22D + level.random.nextDouble() * 0.25D),
                    1.2D, 10.0D);
            double outward = head ? 0.035D : 0.013D;
            level.addParticle(new TintedPoofOptions(c, 0x737875, size, size * 1.6F,
                    17 + level.random.nextInt(24), 0.0F, 0.93F, 0.01F, false,
                    (float) alpha), x, y, z,
                    Math.cos(angle) * outward, 0.055D + level.random.nextDouble() * 0.045D,
                    Math.sin(angle) * outward);
        }
    }

    /** Shared snapshots for the voxel renderer and future DH underground LOD path. */
    public static List<UndergroundSnapshot> snapshotUnderground() {
        List<UndergroundSnapshot> snapshots = new ArrayList<>(UNDERGROUND_EFFECTS.size());
        for (UndergroundEffect e : UNDERGROUND_EFFECTS) {
            snapshots.add(new UndergroundSnapshot(e.x, e.y, e.z, e.exitY,
                    e.scale, e.strength, e.age));
        }
        return List.copyOf(snapshots);
    }

    public record UndergroundSnapshot(double x, double y, double z, double exitY,
                                      float scale, float strength, int age) {
        public boolean breakout() { return Double.isFinite(exitY); }
    }

    private static final class UndergroundEffect {
        final double x, y, z, exitY;
        final float scale, strength;
        int age;
        UndergroundEffect(double x, double y, double z, double exitY,
                          float scale, float strength, int age) {
            this.x=x; this.y=y; this.z=z; this.exitY=exitY;
            this.scale=scale; this.strength=strength; this.age=age;
        }
        boolean isBreakout() { return Double.isFinite(exitY); }
    }

    private static boolean hasCloudLineOfSight(
            Minecraft minecraft,
            ActiveCloud cloud
    ) {
        if (minecraft.player == null || minecraft.level == null) {
            return false;
        }

        double capCentreY = getCloudHeadCentreY(cloud);
        double capRadius = getCloudHorizontalRadius(cloud);
        double capVerticalRadius = getCloudVerticalRadius(cloud);
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

    // Existing smoke particles are not destroyed when a cloud temporarily
    // leaves view. Spawning is already visibility-gated in tick(); particles
    // themselves expire by lifetime, or by explicit clear generation changes.
    // Previously every tinted smoke particle could be removed here, including
    // the stem, when a cap-only visibility check failed for one tick.

    public static void clear() {
        UNDERGROUND_EFFECTS.clear();
        ACTIVE_CLOUDS.clear();
        particleClearGeneration++;
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
            double capCentreY = getCloudHeadCentreY(cloud);
            double capVerticalRadius = getCloudVerticalRadius(cloud);
            // Surface stems use an immutable height/radius frame. No terrain
            // sampling, no old ground-extension correction, no renderer-specific
            // reconstruction after chunks unload.
            SurfaceMushroomStem.Frame stem = SurfaceMushroomStem.frame(
                    cloud.centreY, capCentreY, capVerticalRadius,
                    cloud.visualScale, cloud.ageTicks);
            double stemBaseY = stem.bottomY();
            double stemHeight = stem.height();

            snapshots.add(
                    new CloudSnapshot(
                            cloud.centreX,
                            cloud.centreY,
                            cloud.centreZ,
                            cloud.visualScale,
                            cloud.strength,
                            cloud.ageTicks,
                            capCentreY,
                            getCloudHorizontalRadius(cloud)
                                    * (1.0D - getCapDissipation(cloud.ageTicks) * 0.96D),
                            capVerticalRadius * (1.0D - getCapDissipation(cloud.ageTicks) * 0.70D),
                            stemBaseY,
                            stemHeight,
                            getAscentProgress(
                                    cloud.visualScale,
                                    getFormationAge(cloud)
                            ) >= 1.0D
                    )
            );
        }

        return List.copyOf(snapshots);
    }

    private static boolean isBreakoutCloud(ActiveCloud cloud) {
        return Double.isFinite(cloud.breakoutSurfaceY);
    }

    private static boolean isBreakoutTravelling(ActiveCloud cloud) {
        return isBreakoutCloud(cloud) && cloud.ageTicks < cloud.breakoutTravelTicks;
    }

    private static int getFormationAge(ActiveCloud cloud) {
        if (!isBreakoutCloud(cloud)) {
            return cloud.ageTicks;
        }
        return Math.max(0, cloud.ageTicks - cloud.breakoutTravelTicks);
    }

    private static int getBloomAge(ActiveCloud cloud) {
        if (!isBreakoutCloud(cloud)) {
            return cloud.ageTicks;
        }
        if (isBreakoutTravelling(cloud)) {
            return 0;
        }
        return DetonationTuning.ParticleCloud.EARLY_CAP_BLOOM_START_TICKS
                + Math.max(
                0,
                getFormationAge(cloud)
                        - DetonationTuning.ParticleCloud.BREAKOUT_PRECAP_BLOOM_TICKS
        );
    }

    private static double getCloudHorizontalRadius(ActiveCloud cloud) {
        double legacyRadius = getFireballRadius(cloud.visualScale);
        if (isBreakoutTravelling(cloud)) {
            return legacyRadius
                    * DetonationTuning.ParticleCloud.BREAKOUT_FIREBALL_TRAVEL_RADIUS_RATIO;
        }
        if (isBreakoutCloud(cloud)
                && getFormationAge(cloud)
                < DetonationTuning.ParticleCloud.BREAKOUT_PRECAP_BLOOM_TICKS) {
            double preCapProgress = easeOutCubic(
                    getFormationAge(cloud)
                            / (double) Math.max(
                            1,
                            DetonationTuning.ParticleCloud.BREAKOUT_PRECAP_BLOOM_TICKS
                    )
            );
            return legacyRadius * lerp(
                    DetonationTuning.ParticleCloud.BREAKOUT_FIREBALL_TRAVEL_RADIUS_RATIO,
                    DetonationTuning.ParticleCloud.EARLY_CAP_INITIAL_RADIUS_RATIO,
                    preCapProgress
            );
        }
        return getCurrentHorizontalRadius(
                cloud.visualScale,
                getFormationAge(cloud),
                getBloomAge(cloud)
        );
    }

    private static double getCloudVerticalRadius(ActiveCloud cloud) {
        double legacyRadius = getFireballRadius(cloud.visualScale);
        if (isBreakoutTravelling(cloud)) {
            return legacyRadius
                    * DetonationTuning.ParticleCloud.BREAKOUT_FIREBALL_TRAVEL_RADIUS_RATIO
                    * 0.90D;
        }
        if (isBreakoutCloud(cloud)
                && getFormationAge(cloud)
                < DetonationTuning.ParticleCloud.BREAKOUT_PRECAP_BLOOM_TICKS) {
            double preCapProgress = easeOutCubic(
                    getFormationAge(cloud)
                            / (double) Math.max(
                            1,
                            DetonationTuning.ParticleCloud.BREAKOUT_PRECAP_BLOOM_TICKS
                    )
            );
            return legacyRadius * lerp(
                    DetonationTuning.ParticleCloud.BREAKOUT_FIREBALL_TRAVEL_RADIUS_RATIO,
                    DetonationTuning.ParticleCloud.EARLY_CAP_INITIAL_RADIUS_RATIO,
                    preCapProgress
            ) * 0.90D;
        }
        return getCurrentVerticalRadius(
                cloud.visualScale,
                getFormationAge(cloud),
                getBloomAge(cloud)
        );
    }

    private static double getCloudHeadCentreY(ActiveCloud cloud) {
        if (!isBreakoutCloud(cloud)) {
            return getFireballCentreY(
                    cloud.centreY,
                    cloud.visualScale,
                    cloud.ageTicks
            );
        }

        double exitY = cloud.breakoutSurfaceY
                + DetonationTuning.ParticleCloud.BREAKOUT_FIREBALL_SURFACE_CLEARANCE_BLOCKS;
        if (cloud.ageTicks < cloud.breakoutTravelTicks) {
            double travelProgress = easeOutCubic(
                    cloud.ageTicks / (double) Math.max(1, cloud.breakoutTravelTicks)
            );
            return lerp(cloud.centreY + 2.0D, exitY, travelProgress);
        }

        double ascentProgress = getAscentProgress(
                cloud.visualScale,
                getFormationAge(cloud)
        );
        return exitY + getStemHeight(cloud.visualScale) * smoothstep(ascentProgress);
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
        int formationAge = getFormationAge(cloud);
        boolean travellingUnderground = isBreakoutTravelling(cloud);

        /*
         * There is deliberately no separate cap generator. The original
         * fireball rises, cools, widens and becomes the mushroom-cloud head.
         */
        if (age <= DetonationTuning.ParticleCloud.FIREBALL_SPAWN_END_TICKS) {
            spawnFireball(level, cloud);
        }
        if (travellingUnderground) {
            spawnBreakoutPlasmaJet(level, cloud);
        }

        if (!travellingUnderground
                && formationAge >= DetonationTuning.ParticleCloud.STEM_START_TICKS) {
            spawnStem(level, cloud);
            spawnStemBaseRing(level, cloud);
        }

        if (!travellingUnderground
                && formationAge >= DetonationTuning.ParticleCloud.CRATER_EDGE_RING_START_TICKS) {
            spawnCraterEdgeRing(level, cloud);
        }

        if (!travellingUnderground
                && formationAge >= getAscentDuration(cloud.visualScale)) {
            spawnCapUndersideRing(level, cloud);
        }
    }

    /** A narrow incandescent jet trails the rising core along the excavated throat. */
    private static void spawnBreakoutPlasmaJet(ClientLevel level, ActiveCloud cloud) {
        double topY = getCloudHeadCentreY(cloud);
        double originY = cloud.centreY;
        double length = Math.max(0.0D, topY - originY);
        double jetRadius = Math.clamp(getFireballRadius(cloud.visualScale) * 0.075D,
                1.2D, 10.0D);
        for (int i = 0; i < 42; i++) {
            double progress = level.random.nextDouble();
            double angle = level.random.nextDouble() * Mth.TWO_PI;
            double radial = Math.sqrt(level.random.nextDouble()) * jetRadius
                    * (0.35D + progress * 0.65D);
            double x = cloud.centreX + Math.cos(angle) * radial;
            double y = originY + length * progress;
            double z = cloud.centreZ + Math.sin(angle) * radial;
            if (!level.getBlockState(net.minecraft.core.BlockPos.containing(x, y, z)).isAir()) continue;
            int start = i % 5 == 0 ? HOT_YELLOW : BRIGHT_ORANGE;
            float size = (float) Math.clamp(jetRadius * 0.45D, 1.0D, 5.5D);
            level.addParticle(new TintedPoofOptions(start, DEEP_ORANGE, size,
                    size * 1.4F, 20 + level.random.nextInt(22),
                    -0.001F, 0.93F, 0.03F, true), x, y, z,
                    Math.cos(angle) * 0.01D, 0.11D, Math.sin(angle) * 0.01D);
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
                getFormationAge(cloud)
        );

        double morphProgress = slowMorph(
                getMorphProgress(ascentProgress)
        );
        double horizontalRadius = getCloudHorizontalRadius(cloud);
        double verticalRadius = getCloudVerticalRadius(cloud);
        double centreY = getCloudHeadCentreY(cloud);

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
                getFormationAge(cloud)
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
            if (radialFactor > 1.0D - getCapDissipation(cloud.ageTicks) * 0.96D) continue;
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
                    cloud.visualScale,
                    cloud.ageTicks
            );
            if (radialFactor > 0.72D && cloud.ageTicks < fullSizeTick(cloud.visualScale)) {
                // Mostly grey exterior, with intermittent incandescent pockets
                // across the sides so the internal heat is visible off-axis.
                int shell = getCapShellColour(cloud.visualScale, cloud.ageTicks, radialFactor);
                if (cloud.ageTicks < 1250 && level.random.nextFloat() < 0.19F) {
                    shell = interpolateRgb(shell, HOT_YELLOW,
                            0.22D + level.random.nextDouble() * 0.28D);
                }
                palette = new FireballPalette(shell, shell, false);
            } else if (radialFactor < 0.32D
                    && cloud.ageTicks < getAscentDuration(cloud.visualScale)
                    && level.random.nextFloat() < 0.32F) {
                // Small, intermittent yellow pockets inside the orange head.
                int core = getCapShellColour(cloud.visualScale, cloud.ageTicks, radialFactor);
                palette = new FireballPalette(core, getCloudColour(cloud.visualScale,
                        cloud.ageTicks + 25), cloud.ageTicks < 140);
            }

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
            float visualScale,
            int ageTicks
    ) {
        int current = getCloudColour(visualScale, ageTicks);
        int next = getCloudColour(visualScale, ageTicks + 40);
        int startOffset = level.random.nextInt(17) - 10;
        int endOffset = level.random.nextInt(15) - 18;
        boolean fullBright = ageTicks < 140;
        return new FireballPalette(
                adjustRgb(current, startOffset),
                adjustRgb(next, endOffset),
                fullBright
        );
    }

    /** One continuous particulate stem using the same deterministic frame as
     * the voxel renderer. No separate core, sheath, or terrain-height lookup. */
    private static void spawnStem(ClientLevel level, ActiveCloud cloud) {
        SurfaceMushroomStem.Frame frame = SurfaceMushroomStem.frame(
                cloud.centreY, getCloudHeadCentreY(cloud), getCloudVerticalRadius(cloud),
                cloud.visualScale, cloud.ageTicks);
        if (!frame.visible()) return;
        boolean mature = getFormationAge(cloud)
                >= DetonationTuning.ParticleCloud.MATURE_CLOUD_START_TICKS;
        int count = mature ? 18 : Math.clamp(
                (int) Math.round(38.0D + frame.height() * 0.60D), 40, 185);
        for (int i = 0; i < count; i++) {
            double h = level.random.nextDouble();
            double radius = SurfaceMushroomStem.radius(frame, h);
            double angle = level.random.nextDouble() * Mth.TWO_PI;
            double radialFraction = Math.sqrt(level.random.nextDouble());
            double x = cloud.centreX + Math.cos(angle) * radius * radialFraction;
            double y = frame.bottomY() + frame.height() * h;
            double z = cloud.centreZ + Math.sin(angle) * radius * radialFraction;
            int start = SurfaceMushroomStem.colour(cloud.visualScale, cloud.ageTicks, h);
            int end = SurfaceMushroomStem.colour(cloud.visualScale, cloud.ageTicks + 65, h);
            float size = (float) Math.clamp(radius * (0.20D + level.random.nextDouble() * 0.17D),
                    1.5D, 14.0D);
            int lifetime = mature ? 300 + level.random.nextInt(180)
                    : 85 + level.random.nextInt(60);
            level.addParticle(new TintedPoofOptions(start, end, size,
                            size * (1.48F + level.random.nextFloat() * 0.38F),
                            lifetime, mature ? -0.0006F : -0.002F,
                            mature ? 0.984F : 0.957F, 0.02F, false),
                    x, y, z, Math.cos(angle) * 0.004D,
                    0.010D + level.random.nextDouble() * 0.016D,
                    Math.sin(angle) * 0.004D);
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
                getFormationAge(cloud)
                        >= DetonationTuning.ParticleCloud.MATURE_CLOUD_START_TICKS;

        int count = matureCloud
                ? DetonationTuning.ParticleCloud.MATURE_STEM_BASE_RING_PARTICLES_PER_TICK
                : DetonationTuning.ParticleCloud.STEM_BASE_RING_PARTICLES_PER_TICK;

        SurfaceMushroomStem.Frame frame = SurfaceMushroomStem.frame(
                cloud.centreY, getCloudHeadCentreY(cloud), getCloudVerticalRadius(cloud),
                cloud.visualScale, cloud.ageTicks);
        if (!frame.visible()) return;
        double ringRadius = Math.max(1.75D, frame.baseRadius() * 0.73D);
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
            double y = frame.bottomY() + 0.20D
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
        double visibility = getCraterRingVisibility(getFormationAge(cloud));
        if (visibility <= 0.0D) {
            return;
        }

        boolean matureCloud =
                getFormationAge(cloud)
                        >= DetonationTuning.ParticleCloud.MATURE_CLOUD_START_TICKS;

        int baseCount = matureCloud
                ? DetonationTuning.ParticleCloud.MATURE_CRATER_EDGE_RING_PARTICLES_PER_TICK
                : DetonationTuning.ParticleCloud.CRATER_EDGE_RING_PARTICLES_PER_TICK;
        int count = Math.max(1, (int) Math.round(baseCount * visibility));

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
            double z = cloud.centreZ + sin * radius;
            int terrainTop = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Mth.floor(x),
                    Mth.floor(z)
            );
            double y = terrainTop
                    + 0.10D
                    + level.random.nextDouble() * 0.90D;

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

            int ringBase = getCloudColour(cloud.visualScale, cloud.ageTicks);
            int startColour = adjustRgb(ringBase, -22 + level.random.nextInt(13));
            int endColour = level.random.nextFloat() < 0.72F
                    ? MATURE_SMOKE_GREY
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
        double fadeProgress = getCapUndersideRingVisibility(
                cloud.visualScale, getFormationAge(cloud));

        if (fadeProgress <= 0.0D) {
            return;
        }

        boolean matureCloud =
                getFormationAge(cloud)
                        >= DetonationTuning.ParticleCloud.MATURE_CLOUD_START_TICKS;

        int targetCount = matureCloud
                ? DetonationTuning.ParticleCloud.MATURE_CAP_UNDERSIDE_RING_PARTICLES_PER_TICK
                : DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_PARTICLES_PER_TICK;
        // No first-tick smoke burst: the underside collar builds gradually.
        int count = Math.max(0,
                (int) Math.floor(targetCount * fadeProgress));
        if (count == 0) return;

        double capRadius = getCloudHorizontalRadius(cloud);
        double capVerticalRadius = getCloudVerticalRadius(cloud);
        double capCentreY = getCloudHeadCentreY(cloud);

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

    private static double getEarlyCapBloomProgress(int ageTicks) {
        return easeOutCubic(Math.clamp(
                (ageTicks - DetonationTuning.ParticleCloud.EARLY_CAP_BLOOM_START_TICKS)
                        / (double) Math.max(
                        1,
                        DetonationTuning.ParticleCloud.EARLY_CAP_BLOOM_DURATION_TICKS
                ),
                0.0D,
                1.0D
        ));
    }

    private static double getCurrentHorizontalRadius(
            float visualScale,
            int ageTicks
    ) {
        return getCurrentHorizontalRadius(visualScale, ageTicks, ageTicks);
    }

    private static double getCurrentHorizontalRadius(
            float visualScale,
            int ageTicks,
            int bloomAgeTicks
    ) {
        double legacyStartRadius = getFireballRadius(visualScale);
        double bloomProgress = getEarlyCapBloomProgress(bloomAgeTicks);
        double compactStartRadius = legacyStartRadius
                * DetonationTuning.ParticleCloud.EARLY_CAP_INITIAL_RADIUS_RATIO;
        double fireballRadius = lerp(
                compactStartRadius,
                legacyStartRadius,
                bloomProgress
        );

        double ascentProgress = getAscentProgress(visualScale, ageTicks);
        double earlyRiseSpread = smoothstep(ascentProgress) * 0.30D;
        double normalCapSpread = slowMorph(getMorphProgress(ascentProgress));
        double formationProgress = Math.max(earlyRiseSpread, normalCapSpread)
                * bloomProgress;
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
        return getCurrentVerticalRadius(visualScale, ageTicks, ageTicks);
    }

    private static double getCurrentVerticalRadius(
            float visualScale,
            int ageTicks,
            int bloomAgeTicks
    ) {
        double legacyStartRadius = getFireballRadius(visualScale);
        double bloomProgress = getEarlyCapBloomProgress(bloomAgeTicks);
        double fireballRadius = lerp(
                legacyStartRadius * DetonationTuning.ParticleCloud.EARLY_CAP_INITIAL_RADIUS_RATIO,
                legacyStartRadius,
                bloomProgress
        );

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


    /** Post-fireball exterior: dusty tan/brown-grey with a hot inner cap.
     * The cap darkens gradually without making its entire outer volume stay
     * bright orange for minutes. All near and distant voxel render paths use
     * this same palette, while getCapShellColour adds the yellow hot centre. */
    public static int getThermalCloudColour(int ageTicks) {
        int age = Math.max(0, ageTicks);
        if (age < 24) return interpolateRgb(WHITE_HOT, HOT_YELLOW, age / 24.0D);
        if (age < 95) return interpolateRgb(HOT_YELLOW, BRIGHT_ORANGE,
                (age - 24) / 71.0D);
        int thermal = DetonationTuning.ParticleCloud.FIREBALL_COLOUR_TRANSITION_TICKS;
        int brownStart = Math.max(110, (int) Math.round(thermal * 0.10D));
        int brownEnd = Math.max(brownStart + 1, (int) Math.round(thermal * 0.22D));
        int greyStart = Math.max(brownEnd + 1, (int) Math.round(thermal * 0.54D));
        int greyEnd = Math.max(greyStart + 1, (int) Math.round(thermal * 0.82D));
        if (age < brownStart) return BRIGHT_ORANGE;
        if (age < brownEnd) return interpolateRgb(BRIGHT_ORANGE, 0x847164,
                smoothstep((age - brownStart) / (double) (brownEnd - brownStart)));
        if (age < greyStart) return interpolateRgb(0x847164, 0x60574F,
                smoothstep((age - brownEnd) / (double) (greyStart - brownEnd)));
        if (age < greyEnd) return interpolateRgb(0x60574F, DARK_GREY,
                smoothstep((age - greyStart) / (double) (greyEnd - greyStart)));
        if (age < thermal) return interpolateRgb(DARK_GREY, MATURE_SMOKE_GREY,
                smoothstep((age - greyEnd) / (double) Math.max(1, thermal - greyEnd)));
        return MATURE_SMOKE_GREY;
    }

    public static int fullSizeTick(float visualScale) {
        return getAscentDuration(visualScale)
                + DetonationTuning.ParticleCloud.LATE_CAP_EXPANSION_DELAY_TICKS
                + DetonationTuning.ParticleCloud.LATE_CAP_EXPANSION_DURATION_TICKS;
    }

    public static double getStemDissipation(int ageTicks) {
        return SurfaceMushroomStem.retreat(ageTicks);
    }

    public static double getCapDissipation(int ageTicks) {
        return smoothstep(Math.clamp(
                (ageTicks / (double) DetonationTuning.ParticleCloud.TOTAL_LIFETIME_TICKS
                - DetonationTuning.ParticleCloud.CAP_DISSIPATION_START_FRACTION)
                / (DetonationTuning.ParticleCloud.CAP_DISSIPATION_END_FRACTION
                - DetonationTuning.ParticleCloud.CAP_DISSIPATION_START_FRACTION), 0.0D, 1.0D));
    }

    /** Compatibility helpers: all stem colour now comes from the new model. */
    public static int getStemLayerColour(float scale, int age,
                                         double heightProgress, int shade) {
        return adjustRgb(SurfaceMushroomStem.colour(scale, age, heightProgress), shade);
    }

    public static int getStemBandColour(float scale, int age,
                                        double heightProgress, int ignoredUnderlyingRgb) {
        return SurfaceMushroomStem.colour(scale, age, heightProgress);
    }

    /** Dark smoke skin around a still-hot yellow/orange/red interior.
     *
     * The outside cools earlier than the inner volume, but stays charcoal,
     * never the pale red-grey of the previous iteration. Radial colour is
     * shared by the close voxel renderer and the DH cloud builder so their
     * silhouettes and hot internal pockets remain consistent.
     */
    public static int getCapShellColour(float visualScale, int ageTicks, double radialProgress) {
        double radial = Math.clamp(radialProgress, 0.0D, 1.0D);
        int age = Math.max(0, ageTicks);
        int smokeBase = getCloudColour(visualScale, age);

        // During the first ignition frames keep the familiar orange fireball.
        double smokeFormation = smoothstep(Math.clamp((age - 85.0D) / 340.0D, 0.0D, 1.0D));
        int charcoal = interpolateRgb(0x494B45, 0x343B39,
                smoothstep(Math.clamp((age - 100.0D) / 500.0D, 0.0D, 1.0D)));
        // The shell still ages to normal grey; only its *formation* is dark.
        int cooledShell = interpolateRgb(charcoal, MATURE_SMOKE_GREY,
                smoothstep(Math.clamp((age - 1250.0D) / 850.0D, 0.0D, 1.0D)));
        double shell = smoothstep(Math.clamp((radial - 0.51D) / 0.47D, 0.0D, 1.0D));
        int smoky = interpolateRgb(smokeBase, cooledShell,
                smokeFormation * (0.46D + shell * 0.54D));

        // The hot cap's interior remains red at the fringes, orange inside,
        // and yellow nearer the centre. As the heat cools, the smoke is still
        // visible but the inner glow dies away before maturity.
        double heat = 1.0D - smoothstep(Math.clamp((age - 320.0D) / 1290.0D, 0.0D, 1.0D));
        double core = 1.0D - smoothstep(Math.clamp((radial - 0.13D) / 0.79D, 0.0D, 1.0D));
        int hot = interpolateRgb(0xDB5732, 0xF6AE37,
                1.0D - smoothstep(Math.clamp((radial - 0.24D) / 0.49D, 0.0D, 1.0D)));
        // Reserve a small bright-yellow kernel rather than bleaching the cap.
        hot = interpolateRgb(hot, 0xFFD965,
                (1.0D - smoothstep(Math.clamp(radial / 0.33D, 0.0D, 1.0D))) * 0.38D);
        return interpolateRgb(smoky, hot, core * heat * (0.55D + 0.25D * (1.0D - shell)));
    }

    /** A dense centre with a continuously translucent outer smoke layer.
     * Kept throughout the cloud lifecycle; dissipation is an additional
     * multiplier, not a sudden change from opaque to transparent.
     */
    public static int getCapShellAlpha(float visualScale, int ageTicks, double radialProgress) {
        double radial = Math.clamp(radialProgress, 0.0D, 1.0D);
        double outer = smoothstep(Math.clamp((radial - 0.43D) / 0.57D, 0.0D, 1.0D));
        int base = (int) Math.round(248.0D - 40.0D * outer);
        return Math.clamp((int) Math.round(base
                * getCloudVoxelAlpha(ageTicks) / 255.0D), 0, 255);
    }

    public static int getStemVoxelAlpha(int ageTicks, double radialProgress) {
        return SurfaceMushroomStem.opacity(ageTicks, radialProgress);
    }

    public static int getCloudColour(float visualScale, int ageTicks) {
        int base = getThermalCloudColour(ageTicks);
        int fullSize = fullSizeTick(visualScale);
        if (ageTicks <= fullSize) return base;
        double matureProgress = smoothstep(Math.clamp((ageTicks - fullSize)
                / (double) Math.max(1,
                    DetonationTuning.ParticleCloud.MATURE_GREY_LIGHTEN_DURATION_TICKS),
                0.0D, 1.0D));
        return interpolateRgb(base, LIGHT_MATURE_SMOKE_GREY, matureProgress);
    }

    /** Stay dense throughout maturity; opacity only drops after dissipation begins. */
    public static int getCloudVoxelAlpha(int ageTicks) {
        double fraction = ageTicks / (double) DetonationTuning.ParticleCloud.TOTAL_LIFETIME_TICKS;
        // Radius shrinks from the outside first. The tiny remaining central cap
        // gets an opacity fade only during the final moments.
        double lastFade = smoothstep(Math.clamp((fraction - 0.985D) / 0.015D,
                0.0D, 1.0D));
        return (int) Math.round(255.0D * (1.0D - lastFade));
    }

    public static double getCapUndersideRingVisibility(float visualScale, int ageTicks) {
        int sinceArrival = ageTicks - getAscentDuration(visualScale);
        if (sinceArrival <= 0) return 0.0D;
        return smoothstep(Math.clamp(sinceArrival / (double) Math.max(1,
                DetonationTuning.ParticleCloud.CAP_UNDERSIDE_RING_FADE_IN_TICKS), 0.0D, 1.0D));
    }

    public static double getCraterRingVisibility(int ageTicks) {
        int start = DetonationTuning.ParticleCloud.CRATER_EDGE_RING_START_TICKS;
        int fadeStart = DetonationTuning.ParticleCloud.CRATER_EDGE_RING_FADE_START_TICKS;
        int fadeEnd = DetonationTuning.ParticleCloud.CRATER_EDGE_RING_FADE_END_TICKS;
        if (ageTicks < start || ageTicks >= fadeEnd) {
            return 0.0D;
        }
        if (ageTicks <= fadeStart) {
            return 1.0D;
        }
        return 1.0D - smoothstep(
                (ageTicks - fadeStart) / (double) Math.max(1, fadeEnd - fadeStart)
        );
    }

    public static int adjustRgb(int rgb, int offset) {
        int red = Math.clamp(((rgb >>> 16) & 0xFF) + offset, 0, 255);
        int green = Math.clamp(((rgb >>> 8) & 0xFF) + offset, 0, 255);
        int blue = Math.clamp((rgb & 0xFF) + offset, 0, 255);
        return (red << 16) | (green << 8) | blue;
    }

    private static int interpolateRgb(int from, int to, double progress) {
        progress = Math.clamp(progress, 0.0D, 1.0D);
        int red = (int) Math.round(lerp((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, progress));
        int green = (int) Math.round(lerp((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, progress));
        int blue = (int) Math.round(lerp(from & 0xFF, to & 0xFF, progress));
        return (red << 16) | (green << 8) | blue;
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
        private final double breakoutSurfaceY;
        private final int breakoutTravelTicks;

        private int ageTicks;
        private boolean detailedParticlesVisible = true;
        private String lastStage = "";

        private ActiveCloud(
                double centreX,
                double centreY,
                double centreZ,
                float visualScale,
                float strength,
                double breakoutSurfaceY,
                int ageTicks
        ) {
            this.centreX = centreX;
            this.centreY = centreY;
            this.centreZ = centreZ;
            this.visualScale = visualScale;
            this.strength = strength;
            this.breakoutSurfaceY = breakoutSurfaceY;
            this.breakoutTravelTicks = Double.isFinite(breakoutSurfaceY)
                    ? Math.clamp(
                    (int) Math.ceil(
                            Math.max(0.0D, breakoutSurfaceY - centreY)
                                    / DetonationTuning.ParticleCloud
                                    .BREAKOUT_FIREBALL_SPEED_BLOCKS_PER_TICK
                    ),
                    DetonationTuning.ParticleCloud.BREAKOUT_FIREBALL_MINIMUM_TRAVEL_TICKS,
                    DetonationTuning.ParticleCloud.BREAKOUT_FIREBALL_MAXIMUM_TRAVEL_TICKS
            )
                    : 0;
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
        private final float initialAlpha;
        private final boolean fullBright;
        private final int creationClearGeneration;

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
            this.initialAlpha = Math.clamp(options.initialAlpha(), 0.0F, 1.0F);
            this.fullBright = options.fullBright();
            this.creationClearGeneration = particleClearGeneration;

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
            if (this.creationClearGeneration != particleClearGeneration) {
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

            // Individual poofs overlap densely throughout their life; fade only
            // at their very end to avoid an unnaturally hollow mushroom.
            float lateFade = progress <= 0.90F
                    ? 1.0F
                    : 1.0F - (progress - 0.90F) / 0.10F;

            this.setAlpha(
                    Math.min(initialAlpha, Math.max(endAlpha, lateFade))
            );
        }
    }
}
