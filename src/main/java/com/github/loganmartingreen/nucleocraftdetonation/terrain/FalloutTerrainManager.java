package com.github.loganmartingreen.nucleocraftdetonation.terrain;

import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.persistence.DetonationSavedData;
import com.github.loganmartingreen.nucleocraftdetonation.persistence.DetonationSavedData.TerrainZone;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Applies the permanent fallout terrain pass as a fast block-column wave.
 *
 * <p>The initial loaded area is not processed one square chunk at a time.
 * Instead, loaded columns are ordered by their distance from ground zero and
 * processed from the crater rim outwards, matching the way the crater itself
 * visibly grows. Chunks loaded later receive the same v24 replacement pass as
 * a catch-up job.</p>
 */
public final class FalloutTerrainManager {
    private static final Deque<ActiveWave> ACTIVE_WAVES = new ArrayDeque<>();
    private static final Map<Long, ActiveWave> WAVES_BY_EFFECT = new HashMap<>();
    private static final Deque<FalloutTerrainChunkTask> CHUNK_TASKS = new ArrayDeque<>();
    private static final Set<ChunkJobKey> QUEUED_CHUNKS = new HashSet<>();
    private static final Set<EntityEffectKey> AFFECTED_MOBS = new HashSet<>();

    private static MinecraftServer activeServer;
    private static int entityCheckCountdown;

    private FalloutTerrainManager() {
    }

    public static void clear() {
        ACTIVE_WAVES.clear();
        WAVES_BY_EFFECT.clear();
        CHUNK_TASKS.clear();
        QUEUED_CHUNKS.clear();
        AFFECTED_MOBS.clear();
        entityCheckCountdown = 0;
        activeServer = null;
    }

    /** Starts the fast radial pass immediately after crater excavation. */
    public static void startInitialSweep(
            ServerLevel level,
            long terrainEffectId
    ) {
        DetonationSavedData data = DetonationSavedData.get(level.getServer());
        TerrainZone zone = data.terrainZoneById(terrainEffectId);
        if (zone == null) {
            return;
        }

        removeWave(terrainEffectId);
        ActiveWave wave = new ActiveWave(zone);
        wave.seedLoadedChunks(level, data);
        ACTIVE_WAVES.addLast(wave);
        WAVES_BY_EFFECT.put(terrainEffectId, wave);
    }

    /**
     * Newly loaded chunks are added to an active wave. Once a wave has
     * completed, the chunk receives an immediate catch-up pass.
     */
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }

        DetonationSavedData data = DetonationSavedData.get(level.getServer());
        for (TerrainZone zone : data.terrainZonesFor(level.dimension())) {
            if (!chunkIntersectsZone(chunk.getPos(), zone)) {
                continue;
            }

            ActiveWave wave = WAVES_BY_EFFECT.get(zone.effectId());
            if (wave != null) {
                wave.addChunk(level, data, chunk.getPos());
            } else {
                queueChunk(level, zone, chunk.getPos());
            }
        }
    }

    public static void tick(MinecraftServer server) {
        if (activeServer != server) {
            clear();
            activeServer = server;

            DetonationSavedData data = DetonationSavedData.get(server);
            for (ServerLevel level : server.getAllLevels()) {
                for (TerrainZone zone : data.terrainZonesFor(level.dimension())) {
                    ActiveWave wave = new ActiveWave(zone);
                    wave.seedLoadedChunks(level, data);
                    ACTIVE_WAVES.addLast(wave);
                    WAVES_BY_EFFECT.put(zone.effectId(), wave);
                }
            }
        }

        processWaves(server);
        processChunkTasks(server);

        entityCheckCountdown--;
        if (entityCheckCountdown <= 0) {
            entityCheckCountdown = DetonationTuning.FalloutTerrain.ENTITY_CHECK_INTERVAL_TICKS;
            affectReachedMobs(server);
        }
    }

    private static void processWaves(MinecraftServer server) {
        int budget = DetonationTuning.FalloutTerrain.BLOCK_CHECKS_PER_TICK;
        DetonationSavedData data = DetonationSavedData.get(server);
        int wavesToVisit = ACTIVE_WAVES.size();

        while (budget > 0 && wavesToVisit-- > 0 && !ACTIVE_WAVES.isEmpty()) {
            ActiveWave wave = ACTIVE_WAVES.removeFirst();
            ServerLevel level = server.getLevel(wave.zone.dimension());

            if (level == null) {
                WAVES_BY_EFFECT.remove(wave.zone.effectId());
                continue;
            }

            ActiveWave.StepResult result = wave.tick(level, data, budget);
            budget -= Math.max(1, result.blockChecks());

            if (result.complete()) {
                WAVES_BY_EFFECT.remove(wave.zone.effectId());
            } else {
                ACTIVE_WAVES.addLast(wave);
            }
        }
    }

    private static void processChunkTasks(MinecraftServer server) {
        int budget = DetonationTuning.FalloutTerrain.CATCH_UP_BLOCK_CHECKS_PER_TICK;
        DetonationSavedData data = DetonationSavedData.get(server);

        while (budget > 0 && !CHUNK_TASKS.isEmpty()) {
            FalloutTerrainChunkTask task = CHUNK_TASKS.peekFirst();
            ServerLevel level = server.getLevel(task.zone().dimension());

            if (
                    level == null
                            || !level.hasChunk(task.chunkPos().x, task.chunkPos().z)
            ) {
                removeFirstTask(task);
                continue;
            }

            FalloutTerrainChunkTask.StepResult result = task.tick(level, budget);
            budget -= Math.max(1, result.blockChecks());

            if (result.complete()) {
                data.markTerrainChunkProcessed(
                        task.zone().effectId(),
                        task.chunkPos().toLong()
                );
                removeFirstTask(task);
            }
        }
    }

    private static void queueChunk(
            ServerLevel level,
            TerrainZone zone,
            ChunkPos chunkPos
    ) {
        DetonationSavedData data = DetonationSavedData.get(level.getServer());
        long chunkLong = chunkPos.toLong();

        if (data.isTerrainChunkProcessed(zone.effectId(), chunkLong)) {
            return;
        }

        ChunkJobKey key = new ChunkJobKey(
                zone.dimension(),
                zone.effectId(),
                chunkLong
        );
        if (!QUEUED_CHUNKS.add(key)) {
            return;
        }

        CHUNK_TASKS.addLast(new FalloutTerrainChunkTask(zone, chunkPos));
    }

    private static void removeFirstTask(FalloutTerrainChunkTask task) {
        CHUNK_TASKS.removeFirst();
        QUEUED_CHUNKS.remove(
                new ChunkJobKey(
                        task.zone().dimension(),
                        task.zone().effectId(),
                        task.chunkPos().toLong()
                )
        );
    }

    private static void removeWave(long effectId) {
        ActiveWave existing = WAVES_BY_EFFECT.remove(effectId);
        if (existing != null) {
            ACTIVE_WAVES.remove(existing);
        }
    }

    private static void affectReachedMobs(MinecraftServer server) {
        DetonationSavedData data = DetonationSavedData.get(server);

        for (ServerLevel level : server.getAllLevels()) {
            for (TerrainZone zone : data.terrainZonesFor(level.dimension())) {
                ActiveWave wave = WAVES_BY_EFFECT.get(zone.effectId());
                double reachedRadius = wave == null
                        ? zone.falloutRadius()
                        : wave.currentRadius();

                if (reachedRadius <= 0.0D) {
                    continue;
                }

                AABB bounds = new AABB(
                        zone.centre().getX() - reachedRadius,
                        level.getMinBuildHeight(),
                        zone.centre().getZ() - reachedRadius,
                        zone.centre().getX() + reachedRadius,
                        level.getMaxBuildHeight(),
                        zone.centre().getZ() + reachedRadius
                );

                for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
                    if (!mob.isAlive()) {
                        continue;
                    }

                    double dx = mob.getX() - zone.centre().getX();
                    double dz = mob.getZ() - zone.centre().getZ();
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    if (distance > reachedRadius || distance > zone.falloutRadius()) {
                        continue;
                    }

                    EntityEffectKey key = new EntityEffectKey(
                            level.dimension(),
                            zone.effectId(),
                            mob.getUUID()
                    );
                    if (!AFFECTED_MOBS.add(key)) {
                        continue;
                    }

                    applyMobEffect(level, mob, distance / zone.falloutRadius());
                }
            }
        }
    }

    private static void applyMobEffect(
            ServerLevel level,
            Mob mob,
            double distanceRatio
    ) {
        if (distanceRatio <= DetonationTuning.FalloutTerrain.ENTITY_KILL_DISTANCE_RATIO) {
            mob.hurt(level.damageSources().explosion(null, null), Float.MAX_VALUE);
            return;
        }

        if (distanceRatio <= DetonationTuning.FalloutTerrain.ENTITY_HEAVY_DAMAGE_DISTANCE_RATIO) {
            mob.igniteForSeconds(DetonationTuning.FalloutTerrain.ENTITY_HEAVY_FIRE_SECONDS);
            mob.hurt(
                    level.damageSources().explosion(null, null),
                    Math.max(
                            DetonationTuning.FalloutTerrain.ENTITY_HEAVY_DAMAGE_MINIMUM,
                            mob.getMaxHealth()
                                    * DetonationTuning.FalloutTerrain.ENTITY_HEAVY_DAMAGE_FRACTION
                    )
            );
            return;
        }

        mob.igniteForSeconds(DetonationTuning.FalloutTerrain.ENTITY_OUTER_FIRE_SECONDS);
        mob.hurt(
                level.damageSources().explosion(null, null),
                Math.max(
                        DetonationTuning.FalloutTerrain.ENTITY_OUTER_DAMAGE_MINIMUM,
                        mob.getMaxHealth()
                                * DetonationTuning.FalloutTerrain.ENTITY_OUTER_DAMAGE_FRACTION
                )
        );
    }

    private static boolean chunkIntersectsZone(
            ChunkPos chunkPos,
            TerrainZone zone
    ) {
        double nearestX = Math.clamp(
                zone.centre().getX(),
                chunkPos.getMinBlockX(),
                chunkPos.getMaxBlockX()
        );
        double nearestZ = Math.clamp(
                zone.centre().getZ(),
                chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockZ()
        );
        double dx = nearestX - zone.centre().getX();
        double dz = nearestZ - zone.centre().getZ();
        return dx * dx + dz * dz
                <= zone.falloutRadius() * zone.falloutRadius();
    }

    private static long packColumn(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    private static int unpackX(long packed) {
        return (int) packed;
    }

    private static int unpackZ(long packed) {
        return (int) (packed >>> 32);
    }

    private record ChunkJobKey(
            ResourceKey<Level> dimension,
            long effectId,
            long chunkPos
    ) {
    }

    private record EntityEffectKey(
            ResourceKey<Level> dimension,
            long effectId,
            UUID entityId
    ) {
    }

    private record ColumnEntry(
            long packedColumn,
            double distanceSquared,
            long chunkLong
    ) {
    }

    private static final class ActiveWave {
        private final TerrainZone zone;
        private final PriorityQueue<ColumnEntry> columns;
        private final Set<Long> queuedColumns = new HashSet<>();
        private final Map<Long, Integer> remainingColumnsByChunk = new HashMap<>();

        private FalloutTerrainChunkTask activeColumnTask;
        private ColumnEntry activeColumnEntry;
        private double currentRadius;

        private ActiveWave(TerrainZone zone) {
            this.zone = zone;
            double craterRadiusSquared = zone.visualScale() * zone.visualScale();
            this.columns = new PriorityQueue<>(
                    Comparator.comparingDouble(entry ->
                            entry.distanceSquared() <= craterRadiusSquared
                                    ? -1.0D
                                    : entry.distanceSquared()
                    )
            );
            this.currentRadius = zone.visualScale();
        }

        private void seedLoadedChunks(
                ServerLevel level,
                DetonationSavedData data
        ) {
            int minimumChunkX = (int) Math.floor(
                    (zone.centre().getX() - zone.falloutRadius()) / 16.0D
            );
            int maximumChunkX = (int) Math.floor(
                    (zone.centre().getX() + zone.falloutRadius()) / 16.0D
            );
            int minimumChunkZ = (int) Math.floor(
                    (zone.centre().getZ() - zone.falloutRadius()) / 16.0D
            );
            int maximumChunkZ = (int) Math.floor(
                    (zone.centre().getZ() + zone.falloutRadius()) / 16.0D
            );

            for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
                for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                    if (level.hasChunk(chunkX, chunkZ)) {
                        addChunk(level, data, new ChunkPos(chunkX, chunkZ));
                    }
                }
            }
        }

        private void addChunk(
                ServerLevel level,
                DetonationSavedData data,
                ChunkPos chunkPos
        ) {
            long chunkLong = chunkPos.toLong();
            if (data.isTerrainChunkProcessed(zone.effectId(), chunkLong)) {
                return;
            }
            if (!chunkIntersectsZone(chunkPos, zone)) {
                return;
            }

            int added = 0;
            double maximumDistanceSquared = zone.falloutRadius() * zone.falloutRadius();

            for (int localX = 0; localX < 16; localX++) {
                int worldX = chunkPos.getMinBlockX() + localX;
                for (int localZ = 0; localZ < 16; localZ++) {
                    int worldZ = chunkPos.getMinBlockZ() + localZ;
                    double dx = worldX + 0.5D - zone.centre().getX();
                    double dz = worldZ + 0.5D - zone.centre().getZ();
                    double distanceSquared = dx * dx + dz * dz;
                    if (distanceSquared > maximumDistanceSquared) {
                        continue;
                    }

                    long packed = packColumn(worldX, worldZ);
                    if (!queuedColumns.add(packed)) {
                        continue;
                    }

                    columns.add(new ColumnEntry(packed, distanceSquared, chunkLong));
                    added++;
                }
            }

            if (added > 0) {
                remainingColumnsByChunk.merge(chunkLong, added, Integer::sum);
            }
        }

        private StepResult tick(
                ServerLevel level,
                DetonationSavedData data,
                int maximumBlockChecks
        ) {
            int checks = 0;

            while (checks < maximumBlockChecks) {
                if (activeColumnTask == null) {
                    activeColumnEntry = columns.poll();
                    if (activeColumnEntry == null) {
                        break;
                    }

                    int x = unpackX(activeColumnEntry.packedColumn());
                    int z = unpackZ(activeColumnEntry.packedColumn());
                    ChunkPos chunkPos = new ChunkPos(x >> 4, z >> 4);

                    if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
                        finishColumn(data, activeColumnEntry);
                        activeColumnEntry = null;
                        continue;
                    }

                    currentRadius = Math.max(
                            currentRadius,
                            Math.sqrt(activeColumnEntry.distanceSquared())
                    );
                    activeColumnTask = new FalloutTerrainChunkTask(zone, x, z);
                }

                FalloutTerrainChunkTask.StepResult result = activeColumnTask.tick(
                        level,
                        maximumBlockChecks - checks
                );
                checks += Math.max(1, result.blockChecks());

                if (result.complete()) {
                    finishColumn(data, activeColumnEntry);
                    activeColumnTask = null;
                    activeColumnEntry = null;
                }
            }

            return new StepResult(checks, columns.isEmpty() && activeColumnTask == null);
        }

        private void finishColumn(
                DetonationSavedData data,
                ColumnEntry entry
        ) {
            if (entry == null) {
                return;
            }

            Integer remaining = remainingColumnsByChunk.get(entry.chunkLong());
            if (remaining == null) {
                return;
            }

            if (remaining <= 1) {
                remainingColumnsByChunk.remove(entry.chunkLong());
                data.markTerrainChunkProcessed(zone.effectId(), entry.chunkLong());
            } else {
                remainingColumnsByChunk.put(entry.chunkLong(), remaining - 1);
            }
        }

        private double currentRadius() {
            return Math.max(zone.visualScale(), currentRadius);
        }

        private record StepResult(int blockChecks, boolean complete) {
        }
    }
}
