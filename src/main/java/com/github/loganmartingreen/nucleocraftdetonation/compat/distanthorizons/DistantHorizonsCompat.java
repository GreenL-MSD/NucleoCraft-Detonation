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
    }

    private static void syncFalloutFog() {
        float intensity = DetonationClient.falloutIntensity();

        if (Math.abs(intensity - lastFogIntensity) < 0.015F
                && ticks % 20 != 0) {
            return;
        }

        lastFogIntensity = intensity;
        if (intensity > 0.001F) {
            API.applyFalloutFog(intensity);
        } else {
            API.clearFogOverrides();
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

            int updateInterval = snapshot.ageTicks()
                    < DetonationTuning.DistantHorizons.CLOUD_FAST_UPDATE_END_TICKS
                    ? DetonationTuning.DistantHorizons.CLOUD_FORMING_UPDATE_TICKS
                    : DetonationTuning.DistantHorizons.CLOUD_MATURE_UPDATE_TICKS;

            int ageBucket = snapshot.ageTicks() / Math.max(1, updateInterval);
            if (distantCloud.registeredGroup == null
                    || distantCloud.ageBucket != ageBucket) {
                API.removeGroup(distantCloud.registeredGroup);
                distantCloud.registeredGroup = buildCloudGroup(
                        levelWrapper,
                        snapshot
                );
                distantCloud.ageBucket = ageBucket;
            }

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

            boolean active = distantCloud.registeredGroup != null
                    && distanceSquared >= nearDistance * nearDistance;
            distantCloud.active = active;
            API.setGroupActive(
                    distantCloud.registeredGroup,
                    active
            );
        }

        Iterator<Map.Entry<CloudKey, DistantCloud>> iterator =
                DISTANT_CLOUDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CloudKey, DistantCloud> entry = iterator.next();
            if (present.contains(entry.getKey())) {
                continue;
            }

            API.removeGroup(entry.getValue().registeredGroup);
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
            CloudSnapshot cloud
    ) {
        if (levelWrapper == null) {
            return null;
        }

        List<Object> boxes = new ArrayList<>(1_200);
        double thermalProgress = Math.clamp(
                cloud.ageTicks()
                        / (double) DetonationTuning.ParticleCloud.FIREBALL_COLOUR_TRANSITION_TICKS,
                0.0D,
                1.0D
        );
        boolean illuminated = thermalProgress < 0.66D;

        try {
            addStemBoxes(boxes, cloud, thermalProgress);
            addCapBoxes(boxes, cloud, thermalProgress);
            addCapUndersideRing(boxes, cloud);
            addCraterRing(boxes, cloud);

            return API.createAndRegisterGroup(
                    levelWrapper,
                    cloudResourceId(cloud),
                    boxes,
                    illuminated
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
            CloudSnapshot cloud,
            double thermalProgress
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
                    3.0D,
                    localRadius
                            * DetonationTuning.DistantHorizons.STEM_BOX_RADIUS_RATIO
            );
            double layerSpacing = cloud.stemHeight()
                    / Math.max(1.0D, layers - 1.0D);
            double boxHeight = Math.max(
                    boxSize * 1.15D,
                    layerSpacing * 1.30D
            );
            Color colour = stemColour(thermalProgress, layer);

            addFilledDiscBoxes(
                    boxes,
                    cloud.centreX(),
                    y,
                    cloud.centreZ(),
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
            CloudSnapshot cloud,
            double thermalProgress
    ) throws ReflectiveOperationException {
        int layers = DetonationTuning.DistantHorizons.CAP_LAYERS;
        Color baseColour = capColour(thermalProgress);
        double verticalSpan = cloud.capVerticalRadius() * 1.72D;
        double layerSpacing = verticalSpan / Math.max(1.0D, layers - 1.0D);

        for (int layer = 0; layer < layers; layer++) {
            double verticalNormal = layers == 1
                    ? 0.0D
                    : (layer / (double) (layers - 1)) * 2.0D - 1.0D;
            double layerY = cloud.capCentreY()
                    + verticalNormal * cloud.capVerticalRadius() * 0.86D;
            double radiusFactor = Math.sqrt(
                    Math.max(
                            0.10D,
                            1.0D - verticalNormal * verticalNormal * 0.82D
                    )
            );
            double layerRadius = cloud.capRadius() * radiusFactor;
            double boxSize = Math.max(
                    4.0D,
                    layerRadius
                            * DetonationTuning.DistantHorizons.CAP_BOX_RADIUS_RATIO
            );
            double boxHeight = Math.max(
                    4.0D,
                    Math.max(
                            cloud.capVerticalRadius() * 0.34D,
                            layerSpacing * 1.28D
                    )
            );

            addFilledDiscBoxes(
                    boxes,
                    cloud.centreX(),
                    layerY,
                    cloud.centreZ(),
                    layerRadius,
                    boxSize,
                    boxHeight,
                    baseColour,
                    thermalProgress < 0.66D,
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
                    cloud.centreX() + Math.cos(angle) * ringRadius,
                    ringY,
                    cloud.centreZ() + Math.sin(angle) * ringRadius,
                    boxSize,
                    boxSize * 0.90D,
                    colour,
                    false
            );
        }
    }

    private static void addCraterRing(
            List<Object> boxes,
            CloudSnapshot cloud
    ) throws ReflectiveOperationException {
        double radius = cloud.visualScale();
        double boxSize = Math.max(3.0D, radius * 0.12D);
        int segments = getOverlappingRingSegments(radius, boxSize);

        for (int segment = 0; segment < segments; segment++) {
            double angle = segment / (double) segments * Math.PI * 2.0D;
            Color colour = segment % 3 == 0
                    ? new Color(104, 64, 53, 220)
                    : new Color(75, 74, 70, 225);
            addBoxCentred(
                    boxes,
                    cloud.centreX() + Math.cos(angle) * radius,
                    cloud.centreY() + 0.7D,
                    cloud.centreZ() + Math.sin(angle) * radius,
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
        double acceptedRadius = radius + boxSize * 0.16D;
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
                        shade(baseColour, shadeSeed + gridX * 7 + gridZ * 13),
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

    private static Color capColour(double progress) {
        if (progress < 0.20D) {
            return new Color(255, 224, 102, 235);
        }
        if (progress < 0.52D) {
            return new Color(255, 138, 42, 235);
        }
        if (progress < 0.78D) {
            return new Color(104, 64, 53, 235);
        }
        return new Color(75, 74, 70, 235);
    }

    private static Color stemColour(double progress, int layer) {
        if (progress < 0.58D && layer > 5) {
            return new Color(184, 70, 31, 235);
        }
        return layer % 3 == 0
                ? new Color(104, 64, 53, 235)
                : new Color(75, 74, 70, 235);
    }

    private static Color shade(Color colour, int seed) {
        int offset = switch (Math.floorMod(seed, 4)) {
            case 0 -> 14;
            case 1 -> 4;
            case 2 -> -8;
            default -> -15;
        };
        return new Color(
                Math.clamp(colour.getRed() + offset, 0, 255),
                Math.clamp(colour.getGreen() + offset, 0, 255),
                Math.clamp(colour.getBlue() + offset, 0, 255),
                colour.getAlpha()
        );
    }

    private static String cloudResourceId(CloudSnapshot cloud) {
        long hash = Double.doubleToLongBits(cloud.centreX());
        hash = 31L * hash + Double.doubleToLongBits(cloud.centreY());
        hash = 31L * hash + Double.doubleToLongBits(cloud.centreZ());
        hash = 31L * hash + Float.floatToIntBits(cloud.visualScale());
        return "nucleocraft_detonation:mushroom_cloud_"
                + Long.toUnsignedString(hash, 36);
    }

    private static double smoothstep(double value) {
        value = Math.clamp(value, 0.0D, 1.0D);
        return value * value * (3.0D - 2.0D * value);
    }

    private static void clearRendererState() {
        for (DistantCloud cloud : DISTANT_CLOUDS.values()) {
            API.removeGroup(cloud.registeredGroup);
        }
        DISTANT_CLOUDS.clear();
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
