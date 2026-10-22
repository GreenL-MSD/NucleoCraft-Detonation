package com.github.loganmartingreen.nucleocraftdetonation.compat.distanthorizons;

import com.github.loganmartingreen.nucleocraftdetonation.client.DetonationClient;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.TerrainChunkLodRefreshCompletePayload;
import com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticleSystem;
import com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticleSystem.CloudSnapshot;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import org.slf4j.Logger;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional Distant Horizons integration.
 *
 * <p>Provides three compatibility paths:</p>
 * <ol>
 *     <li>DH far fog follows NucleoCraft fallout intensity and colour.</li>
 *     <li>Completed crater chunks are submitted for LOD regeneration.</li>
 *     <li>A low-detail persistent mushroom cloud is rendered through DH's
 *     generic object renderer outside the normal close-up particle range.</li>
 * </ol>
 */
public final class DistantHorizonsCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean INSTALLED =
            ModList.get().isLoaded("distanthorizons");
    private static final DistantHorizonsReflectionApi API =
            new DistantHorizonsReflectionApi(LOGGER);

    private static final Map<CloudKey, DistantCloud> DISTANT_CLOUDS =
            new HashMap<>();
    private static final ArrayDeque<PendingChunkRefresh> PENDING_CHUNKS =
            new ArrayDeque<>();
    private static final Set<PendingRefreshKey> QUEUED_CHUNKS =
            new HashSet<>();

    private static ClientLevel previousLevel;
    private static float lastFogIntensity = -1.0F;
    private static boolean falloutFogOverrideActive;
    private static int ticks;

    private DistantHorizonsCompat() {
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    /** True only after the installed DH API v3 bridge is usable. */
    public static boolean isOperational() {
        return INSTALLED && API.tryInitialize();
    }

    /** True only while DH has a registered, active LOD model for this cloud. */
    public static boolean isDistantCloudActive(
            double centreX,
            double centreY,
            double centreZ,
            float visualScale
    ) {
        if (!INSTALLED) {
            return false;
        }

        DistantCloud cloud = DISTANT_CLOUDS.get(
                new CloudKey(
                        Double.doubleToLongBits(centreX),
                        Double.doubleToLongBits(centreY),
                        Double.doubleToLongBits(centreZ),
                        Float.floatToIntBits(visualScale)
                )
        );
        return cloud != null
                && cloud.active
                && cloud.registeredGroup != null;
    }

    public static void tick(Minecraft minecraft) {
        if (!INSTALLED
                || minecraft.level == null
                || minecraft.player == null) {
            return;
        }

        if (previousLevel != minecraft.level) {
            clearRendererState();
            API.clearConfigOverrides();
            previousLevel = minecraft.level;
            lastFogIntensity = -1.0F;
            falloutFogOverrideActive = false;
        }

        ticks++;
        if (!API.tryInitialize()) {
            return;
        }

        syncFalloutFog();
        processCraterRefreshes(minecraft);
        syncDistantClouds(minecraft);
    }

    public static void queueCraterRefresh(
            BlockPos centre,
            int affectedRadius
    ) {
        if (!INSTALLED) {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        int chunkRadius = Math.max(
                1,
                (int) Math.ceil(Math.max(1, affectedRadius) / 16.0D) + 1
        );
        int centreChunkX = centre.getX() >> 4;
        int centreChunkZ = centre.getZ() >> 4;

        for (int chunkX = centreChunkX - chunkRadius;
             chunkX <= centreChunkX + chunkRadius;
             chunkX++) {
            for (int chunkZ = centreChunkZ - chunkRadius;
                 chunkZ <= centreChunkZ + chunkRadius;
                 chunkZ++) {
                if (level.hasChunk(chunkX, chunkZ)) {
                    queueChunkRefresh(0L, chunkX, chunkZ);
                }
            }
        }
    }

    /** Queues one exact loaded chunk for a targeted DH LOD rebuild. */
    public static void queueChunkRefresh(
            long terrainEffectId,
            int chunkX,
            int chunkZ
    ) {
        if (!INSTALLED) {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        PendingRefreshKey key = new PendingRefreshKey(
                terrainEffectId,
                ChunkPos.asLong(chunkX, chunkZ)
        );
        if (QUEUED_CHUNKS.add(key)) {
            /*
             * The server packet commonly arrives a few ticks before the client
             * LevelChunk. Keep it queued instead of dropping the DH refresh.
             */
            PENDING_CHUNKS.addLast(
                    new PendingChunkRefresh(
                            terrainEffectId,
                            chunkX,
                            chunkZ
                    )
            );
        }
    }

    public static void clear() {
        if (!INSTALLED) {
            return;
        }

        clearRendererState();
        API.clearConfigOverrides();
        previousLevel = null;
        lastFogIntensity = -1.0F;
        falloutFogOverrideActive = false;
    }

    public static void clearMushroomClouds() {
        if (!INSTALLED) {
            return;
        }
        for (DistantCloud cloud : DISTANT_CLOUDS.values()) {
            API.removeGroup(cloud.registeredGroup);
            API.removeGroup(cloud.retiringGroup);
        }
        DISTANT_CLOUDS.clear();
    }

    private static void syncFalloutFog() {
        float intensity = DetonationClient.falloutIntensity();
        lastFogIntensity = intensity;

        /* DH config writes can trigger renderer/config rebuilds. Apply one
         * stable fallout override when entering the zone and clear it once on
         * exit instead of rewriting the values as intensity changes. */
        if (intensity > 0.02F) {
            if (!falloutFogOverrideActive) {
                API.applyFalloutFog(1.0F);
                falloutFogOverrideActive = true;
            }
            return;
        }

        if (falloutFogOverrideActive) {
            API.clearFogOverrides();
            falloutFogOverrideActive = false;
        }
    }

    private static void processCraterRefreshes(Minecraft minecraft) {
        if (PENDING_CHUNKS.isEmpty()) {
            return;
        }

        Object levelWrapper = API.findClientLevelWrapper(minecraft.level);
        if (levelWrapper == null) {
            return;
        }

        List<Object> chunks = new ArrayList<>();
        List<PendingChunkRefresh> submitted = new ArrayList<>();
        int scanLimit = Math.min(
                PENDING_CHUNKS.size(),
                DetonationTuning.DistantHorizons.CRATER_REFRESH_SCAN_PER_TICK
        );

        for (int scanned = 0;
             scanned < scanLimit
                     && chunks.size()
                     < DetonationTuning.DistantHorizons.CRATER_REFRESH_CHUNKS_PER_TICK;
             scanned++) {
            PendingChunkRefresh pending = PENDING_CHUNKS.removeFirst();
            PendingRefreshKey key = pending.key();

            if (!minecraft.level.hasChunk(pending.chunkX, pending.chunkZ)) {
                retryOrDiscard(pending, key);
                continue;
            }

            chunks.add(
                    minecraft.level.getChunk(
                            pending.chunkX,
                            pending.chunkZ
                    )
            );
            submitted.add(pending);
        }

        if (chunks.isEmpty()) {
            return;
        }

        if (API.overwriteChunkData(
                levelWrapper,
                minecraft.level,
                chunks
        )) {
            for (PendingChunkRefresh pending : submitted) {
                QUEUED_CHUNKS.remove(pending.key());
                if (pending.terrainEffectId != 0L) {
                    PacketDistributor.sendToServer(
                            new TerrainChunkLodRefreshCompletePayload(
                                    pending.terrainEffectId,
                                    pending.chunkX,
                                    pending.chunkZ
                            )
                    );
                }
            }
        } else {
            for (PendingChunkRefresh pending : submitted) {
                retryOrDiscard(pending, pending.key());
            }
        }
    }

    private static void retryOrDiscard(
            PendingChunkRefresh pending,
            PendingRefreshKey key
    ) {
        pending.attempts++;
        if (
                pending.attempts
                        < DetonationTuning.DistantHorizons
                        .CRATER_REFRESH_MAX_ATTEMPTS
        ) {
            PENDING_CHUNKS.addLast(pending);
        } else {
            QUEUED_CHUNKS.remove(key);
        }
    }

    private static void syncDistantClouds(Minecraft minecraft) {
        List<CloudSnapshot> snapshots =
                DetonationParticleSystem.snapshotClouds();
        Set<CloudKey> present = new HashSet<>();
        Object levelWrapper = API.findClientLevelWrapper(minecraft.level);

        for (CloudSnapshot snapshot : snapshots) {
            CloudKey key = CloudKey.from(snapshot);
            present.add(key);

            DistantCloud distantCloud = DISTANT_CLOUDS.computeIfAbsent(
                    key,
                    ignored -> new DistantCloud()
            );

            /* Keep the previous group for one complete client tick after a
             * swap. DH therefore never sees a remove-before-add blank frame. */
            if (distantCloud.retiringGroup != null) {
                API.removeGroup(distantCloud.retiringGroup);
                distantCloud.retiringGroup = null;
            }

            int updateInterval = snapshot.ageTicks()
                    < DetonationTuning.DistantHorizons.CLOUD_FAST_UPDATE_END_TICKS
                    ? DetonationTuning.DistantHorizons.CLOUD_FORMING_UPDATE_TICKS
                    : DetonationTuning.DistantHorizons.CLOUD_MATURE_UPDATE_TICKS;
            int ageBucket = snapshot.ageTicks() / Math.max(1, updateInterval);

            double distanceSquared = minecraft.player.distanceToSqr(
                    snapshot.centreX(),
                    snapshot.capCentreY(),
                    snapshot.centreZ()
            );
            double nearDistance = Math.max(
                    DetonationTuning.DistantHorizons.MINIMUM_CLOUD_LOD_DISTANCE,
                    snapshot.capRadius()
                            * DetonationTuning.DistantHorizons.CLOUD_LOD_DISTANCE_RADIUS_RATIO
            );
            boolean shouldBeActive = distanceSquared >= nearDistance * nearDistance;

            if (distantCloud.registeredGroup == null
                    || distantCloud.ageBucket != ageBucket) {
                int replacementSlot = 1 - distantCloud.activeSlot;
                Object replacement = buildCloudGroup(
                        levelWrapper,
                        minecraft.level,
                        snapshot,
                        replacementSlot,
                        shouldBeActive
                );

                if (replacement != null) {
                    Object previous = distantCloud.registeredGroup;
                    distantCloud.registeredGroup = replacement;
                    distantCloud.activeSlot = replacementSlot;
                    distantCloud.ageBucket = ageBucket;
                    distantCloud.retiringGroup = previous;
                }
            }

            boolean active = distantCloud.registeredGroup != null
                    && shouldBeActive;
            distantCloud.active = active;
            API.setGroupActive(distantCloud.registeredGroup, active);
        }

        Iterator<Map.Entry<CloudKey, DistantCloud>> iterator =
                DISTANT_CLOUDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CloudKey, DistantCloud> entry = iterator.next();
            if (present.contains(entry.getKey())) {
                continue;
            }

            API.removeGroup(entry.getValue().registeredGroup);
            API.removeGroup(entry.getValue().retiringGroup);
            iterator.remove();
        }

        if (!DISTANT_CLOUDS.isEmpty()) {
            API.enableGenericRendering();
        } else {
            API.clearGenericRenderingOverride();
        }
    }

    private static Object buildCloudGroup(
            Object levelWrapper,
            ClientLevel level,
            CloudSnapshot cloud,
            int bufferSlot,
            boolean initiallyActive
    ) {
        if (levelWrapper == null) {
            return null;
        }

        List<Object> boxes = new ArrayList<>(1_200);
        boolean illuminated = cloud.ageTicks()
                < (int) (DetonationTuning.ParticleCloud.FIREBALL_COLOUR_TRANSITION_TICKS * 0.46D);

        try {
            addStemBoxes(boxes, cloud);
            addCapBoxes(boxes, cloud);
            addCapUndersideRing(boxes, cloud);
            addCraterRing(boxes, level, cloud);

            /* Relative positioning gives DH a compact local object/bounds
             * around the cloud instead of a group whose geometry is expressed
             * thousands of blocks away from its origin. This improves
             * high-angle/frustum stability without bypassing terrain depth. */
            return API.createAndRegisterGroup(
                    levelWrapper,
                    cloudResourceId(cloud, bufferSlot),
                    cloud.centreX(),
                    cloud.centreY(),
                    cloud.centreZ(),
                    boxes,
                    illuminated,
                    initiallyActive
            );
        } catch (ReflectiveOperationException exception) {
            LOGGER.warn(
                    "Unable to build the Distant Horizons mushroom cloud model.",
                    exception
            );
            return null;
        }
    }

    private static void addStemBoxes(
            List<Object> boxes,
            CloudSnapshot cloud
    ) throws ReflectiveOperationException {
        if (cloud.stemHeight() <= 1.0D) {
            return;
        }

        double matureRadius = DetonationParticleSystem.getMatureCloudRadius(
                cloud.visualScale()
        );
        int layers = DetonationTuning.DistantHorizons.STEM_LAYERS;

        for (int layer = 0; layer < layers; layer++) {
            double heightProgress = layer / (double) (layers - 1);
            double verticalTaper = 1.05D
                    - 0.47D * smoothstep(heightProgress);
            double bulge = 0.96D
                    + 0.10D * Math.sin(heightProgress * Math.PI);
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
            double y = cloud.stemBaseY()
                    + cloud.stemHeight() * heightProgress;
            double boxSize = Math.max(
                    1.75D,
                    localRadius
                            * 0.30D
            );
            double layerSpacing = cloud.stemHeight()
                    / Math.max(1.0D, layers - 1.0D);
            double boxHeight = Math.max(
                    boxSize * 1.20D,
                    layerSpacing * 1.16D
            );
            Color colour = cloudColour(cloud.visualScale(), cloud.ageTicks(), -12);

            addFilledDiscBoxes(
                    boxes,
                    0.0D,
                    y - cloud.centreY(),
                    0.0D,
                    localRadius,
                    boxSize,
                    boxHeight,
                    colour,
                    false,
                    layer * 17
            );
        }
    }

    private static void addCapBoxes(
            List<Object> boxes,
            CloudSnapshot cloud
    ) throws ReflectiveOperationException {
        int layers = Math.max(DetonationTuning.DistantHorizons.CAP_LAYERS + 2, 6);
        Color baseColour = cloudColour(cloud.visualScale(), cloud.ageTicks(), 0);
        double verticalSpan = cloud.capVerticalRadius() * 1.84D;
        double layerSpacing = verticalSpan / Math.max(1.0D, layers - 1.0D);

        for (int layer = 0; layer < layers; layer++) {
            double verticalNormal = layers == 1
                    ? 0.0D
                    : (layer / (double) (layers - 1)) * 2.0D - 1.0D;
            double layerY = cloud.capCentreY()
                    + verticalNormal * cloud.capVerticalRadius() * 0.92D;
            double radiusFactor = Math.sqrt(
                    Math.max(
                            0.16D,
                            1.0D - verticalNormal * verticalNormal * 0.76D
                    )
            );
            double layerRadius = cloud.capRadius() * (radiusFactor + 0.04D);
            double boxSize = Math.max(
                    4.0D,
                    layerRadius
                            * DetonationTuning.DistantHorizons.CAP_BOX_RADIUS_RATIO
            );
            double boxHeight = Math.max(
                    4.0D,
                    Math.max(
                            cloud.capVerticalRadius() * 0.34D,
                            layerSpacing * 1.14D
                    )
            );

            addFilledDiscBoxes(
                    boxes,
                    0.0D,
                    layerY - cloud.centreY(),
                    0.0D,
                    layerRadius,
                    boxSize,
                    boxHeight,
                    baseColour,
                    cloud.ageTicks() < (int) (DetonationTuning.ParticleCloud.FIREBALL_COLOUR_TRANSITION_TICKS * 0.46D),
                    layer * 31
            );
        }
    }

    private static void addCapUndersideRing(
            List<Object> boxes,
            CloudSnapshot cloud
    ) throws ReflectiveOperationException {
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
            Color colour = segment % 4 == 0
                    ? new Color(75, 74, 70, 230)
                    : new Color(41, 44, 43, 235);
            addBoxCentred(
                    boxes,
                    Math.cos(angle) * ringRadius,
                    ringY - cloud.centreY(),
                    Math.sin(angle) * ringRadius,
                    boxSize,
                    boxSize * 0.90D,
                    colour,
                    false
            );
        }
    }

    private static void addCraterRing(
            List<Object> boxes,
            ClientLevel level,
            CloudSnapshot cloud
    ) throws ReflectiveOperationException {
        double visibility = DetonationParticleSystem.getCraterRingVisibility(cloud.ageTicks());
        if (visibility <= 0.0D) {
            return;
        }

        double radius = cloud.visualScale();
        double boxSize = Math.max(3.0D, radius * 0.12D);
        int segments = getOverlappingRingSegments(radius, boxSize);
        int alpha = (int) Math.round(225.0D * visibility);

        for (int segment = 0; segment < segments; segment++) {
            double angle = segment / (double) segments * Math.PI * 2.0D;
            double offsetX = Math.cos(angle) * radius;
            double offsetZ = Math.sin(angle) * radius;
            int worldX = Mth.floor(cloud.centreX() + offsetX);
            int worldZ = Mth.floor(cloud.centreZ() + offsetZ);
            double worldY = cloud.centreY() + 0.7D;
            if (level != null && level.hasChunk(worldX >> 4, worldZ >> 4)) {
                worldY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        worldX,
                        worldZ
                ) + 0.35D;
            }

            Color colour = segment % 3 == 0
                    ? new Color(104, 64, 53, Math.max(1, alpha - 5))
                    : new Color(75, 74, 70, alpha);
            addBoxCentred(
                    boxes,
                    offsetX,
                    worldY - cloud.centreY(),
                    offsetZ,
                    boxSize,
                    boxSize * 0.70D,
                    colour,
                    false
            );
        }
    }

    /**
     * Builds one completely filled horizontal voxel slice. The boxes overlap
     * slightly, which is intentional: DH's distant rasterisation otherwise
     * exaggerates tiny spaces into very large visible holes.
     */
    private static void addFilledDiscBoxes(
            List<Object> boxes,
            double centreX,
            double centreY,
            double centreZ,
            double radius,
            double boxSize,
            double boxHeight,
            Color baseColour,
            boolean illuminated,
            int shadeSeed
    ) throws ReflectiveOperationException {
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
                if (offsetX * offsetX + offsetZ * offsetZ
                        > acceptedRadiusSquared) {
                    continue;
                }

                addBoxCentred(
                        boxes,
                        centreX + offsetX,
                        centreY,
                        centreZ + offsetZ,
                        boxSize,
                        boxHeight,
                        organicShade(baseColour, shadeSeed, gridX, gridZ),
                        illuminated
                );
            }
        }
    }

    private static int getOverlappingRingSegments(
            double radius,
            double boxSize
    ) {
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
            List<Object> boxes,
            double x,
            double y,
            double z,
            double horizontalSize,
            double verticalSize,
            Color colour,
            boolean illuminated
    ) throws ReflectiveOperationException {
        double halfHorizontal = horizontalSize * 0.5D;
        double halfVertical = verticalSize * 0.5D;
        boxes.add(
                API.createBox(
                        x - halfHorizontal,
                        y - halfVertical,
                        z - halfHorizontal,
                        x + halfHorizontal,
                        y + halfVertical,
                        z + halfHorizontal,
                        colour,
                        illuminated
                )
        );
    }

    private static Color cloudColour(float visualScale, int ageTicks, int shadeOffset) {
        int rgb = DetonationParticleSystem.adjustRgb(
                DetonationParticleSystem.getCloudColour(visualScale, ageTicks),
                shadeOffset
        );
        return new Color(
                (rgb >>> 16) & 0xFF,
                (rgb >>> 8) & 0xFF,
                rgb & 0xFF,
                DetonationParticleSystem.getCloudVoxelAlpha(ageTicks)
        );
    }

    private static Color organicShade(
            Color colour,
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
        int rgb = (colour.getRed() << 16)
                | (colour.getGreen() << 8)
                | colour.getBlue();
        rgb = DetonationParticleSystem.adjustRgb(rgb, offset);
        return new Color(
                (rgb >>> 16) & 0xFF,
                (rgb >>> 8) & 0xFF,
                rgb & 0xFF,
                colour.getAlpha()
        );
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

    private static String cloudResourceId(CloudSnapshot cloud, int bufferSlot) {
        long hash = Double.doubleToLongBits(cloud.centreX());
        hash = 31L * hash + Double.doubleToLongBits(cloud.centreY());
        hash = 31L * hash + Double.doubleToLongBits(cloud.centreZ());
        hash = 31L * hash + Float.floatToIntBits(cloud.visualScale());
        return "nucleocraft_detonation:mushroom_cloud_"
                + Long.toUnsignedString(hash, 36)
                + (bufferSlot == 0 ? "_a" : "_b");
    }

    private static double smoothstep(double value) {
        value = Math.clamp(value, 0.0D, 1.0D);
        return value * value * (3.0D - 2.0D * value);
    }

    private static void clearRendererState() {
        clearMushroomClouds();
        PENDING_CHUNKS.clear();
        QUEUED_CHUNKS.clear();
    }

    private record CloudKey(
            long x,
            long y,
            long z,
            int scaleBits
    ) {
        private static CloudKey from(CloudSnapshot cloud) {
            return new CloudKey(
                    Double.doubleToLongBits(cloud.centreX()),
                    Double.doubleToLongBits(cloud.centreY()),
                    Double.doubleToLongBits(cloud.centreZ()),
                    Float.floatToIntBits(cloud.visualScale())
            );
        }
    }

    private static final class DistantCloud {
        private Object registeredGroup;
        private Object retiringGroup;
        private int activeSlot;
        private int ageBucket = Integer.MIN_VALUE;
        private boolean active;
    }

    private record PendingRefreshKey(
            long terrainEffectId,
            long chunkLong
    ) {
    }

    private static final class PendingChunkRefresh {
        private final long terrainEffectId;
        private final int chunkX;
        private final int chunkZ;
        private int attempts;

        private PendingChunkRefresh(
                long terrainEffectId,
                int chunkX,
                int chunkZ
        ) {
            this.terrainEffectId = terrainEffectId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        private PendingRefreshKey key() {
            return new PendingRefreshKey(
                    terrainEffectId,
                    ChunkPos.asLong(chunkX, chunkZ)
            );
        }
    }
}
