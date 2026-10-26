package com.github.loganmartingreen.nucleocraftdetonation.terrain.fallout;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.config.TerrainGenerationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.TerrainChunkLodRefreshPayload;
import com.github.loganmartingreen.nucleocraftdetonation.persistence.DetonationSavedData;
import com.github.loganmartingreen.nucleocraftdetonation.persistence.DetonationSavedData.TerrainZone;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent per-chunk scheduler for permanent fallout terrain conversion.
 *
 * <p>Every chunk intersecting the circular effect range is calculated once in
 * exact nearest-first order. The saved completed set is subtracted from that
 * deterministic target list, so all other targets are implicitly incomplete.
 * Naturally loaded chunks are processed first. When a client with Distant
 * Horizons is present, up to two incomplete chunks are temporarily staged in
 * nearest-first order, processed, saved, released and marked for a later DH LOD
 * rebuild when a player receives that chunk.</p>
 */
public final class FalloutTerrainManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long NO_CHUNK = Long.MIN_VALUE;

    private static final TicketController TERRAIN_TICKET_CONTROLLER =
            new TicketController(
                    ResourceLocation.fromNamespaceAndPath(
                            NucleoCraftDetonation.MOD_ID,
                            "fallout_terrain"
                    ),
                    (level, ticketHelper) -> {
                        /* Never persist temporary background-load tickets. */
                        List<BlockPos> staleOwners = new ArrayList<>(
                                ticketHelper.getBlockTickets().keySet()
                        );
                        staleOwners.forEach(ticketHelper::removeAllTickets);
                    }
            );

    private static final Deque<TerrainScheduler> ACTIVE_SCHEDULERS =
            new ArrayDeque<>();
    private static final Map<Long, TerrainScheduler> SCHEDULERS_BY_EFFECT =
            new HashMap<>();
    private static final Set<UUID> DISTANT_HORIZONS_PLAYERS =
            new HashSet<>();
    private static final Set<EntityEffectKey> AFFECTED_MOBS =
            new HashSet<>();

    private static MinecraftServer activeServer;
    private static int entityCheckCountdown;

    private FalloutTerrainManager() {
    }

    public static void registerTicketController(
            RegisterTicketControllersEvent event
    ) {
        event.register(TERRAIN_TICKET_CONTROLLER);
    }

    /** Records whether this connected player can consume DH refresh work. */
    public static void setDistantHorizonsClient(
            ServerPlayer player,
            boolean installed
    ) {
        if (installed) {
            DISTANT_HORIZONS_PLAYERS.add(player.getUUID());

            if (activeServer != null) {
                ServerLevel level = player.serverLevel();
                DetonationSavedData data = DetonationSavedData.get(
                        level.getServer()
                );
                for (TerrainZone zone : data.terrainZonesFor(level.dimension())) {
                    if (
                            data.hasPendingTerrainLodRefreshes(zone.effectId())
                                    && !SCHEDULERS_BY_EFFECT.containsKey(
                                    zone.effectId()
                            )
                    ) {
                        addScheduler(level, data, zone);
                    }
                }
            }
        } else {
            DISTANT_HORIZONS_PLAYERS.remove(player.getUUID());
        }
    }

    /** Clears the saved DH-pending bit only after the client submits the LOD. */
    public static void acknowledgeDistantHorizonsRefresh(
            ServerPlayer player,
            long terrainEffectId,
            int chunkX,
            int chunkZ
    ) {
        DetonationSavedData data = DetonationSavedData.get(
                player.serverLevel().getServer()
        );
        TerrainZone zone = data.terrainZoneById(terrainEffectId);
        if (
                zone == null
                        || !zone.dimension().equals(
                        player.serverLevel().dimension()
                )
        ) {
            return;
        }

        long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
        if (data.isTerrainChunkProcessed(terrainEffectId, chunkLong)) {
            data.clearTerrainChunkLodRefreshPending(
                    terrainEffectId,
                    chunkLong
            );
        }
    }

    public static void clear() {
        if (activeServer != null) {
            for (TerrainScheduler scheduler : ACTIVE_SCHEDULERS) {
                ServerLevel level = activeServer.getLevel(
                        scheduler.zone.dimension()
                );
                if (level != null) {
                    scheduler.releaseForcedChunks(level);
                }
            }
        }

        ACTIVE_SCHEDULERS.clear();
        SCHEDULERS_BY_EFFECT.clear();
        DISTANT_HORIZONS_PLAYERS.clear();
        AFFECTED_MOBS.clear();
        entityCheckCountdown = 0;
        activeServer = null;
    }

    /** Starts or rebuilds the scheduler after crater excavation. */
    public static void startInitialSweep(
            ServerLevel level,
            long terrainEffectId
    ) {
        DetonationSavedData data = DetonationSavedData.get(level.getServer());
        TerrainZone zone = data.terrainZoneById(terrainEffectId);
        if (zone == null) {
            return;
        }

        removeScheduler(terrainEffectId, level);
        addScheduler(level, data, zone);
    }

    /**
     * Natural chunk loading is the primary trigger when DH background loading
     * is unavailable. No incomplete chunk is discarded merely because it later
     * unloads; its in-progress task is retained and resumed on the next load.
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
            ChunkPos chunkPos = chunk.getPos();
            if (!chunkIntersectsZone(chunkPos, zone)) {
                continue;
            }

            long chunkLong = chunkPos.toLong();
            if (data.isTerrainChunkProcessed(zone.effectId(), chunkLong)) {
                /*
                 * A correct vanilla chunk has now reached the normal load path.
                 * Keep the saved pending bit until a DH client acknowledges that
                 * overwriteChunkDataAsync accepted the refreshed LevelChunk.
                 */
                if (
                        data.isTerrainChunkLodRefreshPending(
                                zone.effectId(),
                                chunkLong
                        )
                                && hasDistantHorizonsPlayer(level)
                ) {
                    notifyTerrainChunkUpdated(
                            level,
                            zone.effectId(),
                            chunkPos
                    );
                }
                continue;
            }

            TerrainScheduler scheduler = SCHEDULERS_BY_EFFECT.get(
                    zone.effectId()
            );
            if (scheduler == null) {
                scheduler = addScheduler(level, data, zone);
            }
            scheduler.onChunkLoaded(level, data, chunkPos);
        }
    }

    public static void tick(MinecraftServer server) {
        if (activeServer != server) {
            clear();
            activeServer = server;

            DetonationSavedData data = DetonationSavedData.get(server);
            for (ServerLevel level : server.getAllLevels()) {
                for (TerrainZone zone : data.terrainZonesFor(level.dimension())) {
                    if (!data.isTerrainComplete(zone.effectId())) {
                        addScheduler(level, data, zone);
                    }
                }
            }
        }

        processSchedulers(server);

        entityCheckCountdown--;
        if (entityCheckCountdown <= 0) {
            entityCheckCountdown =
                    TerrainGenerationTuning.Fallout.ENTITY_CHECK_INTERVAL_TICKS;
            affectReachedMobs(server);
        }
    }

    private static TerrainScheduler addScheduler(
            ServerLevel level,
            DetonationSavedData data,
            TerrainZone zone
    ) {
        TerrainScheduler existing = SCHEDULERS_BY_EFFECT.get(zone.effectId());
        if (existing != null) {
            return existing;
        }

        TerrainScheduler scheduler = new TerrainScheduler(zone, data);
        ACTIVE_SCHEDULERS.addLast(scheduler);
        SCHEDULERS_BY_EFFECT.put(zone.effectId(), scheduler);
        if (!data.isTerrainComplete(zone.effectId())) {
            scheduler.discoverLoadedChunks(level, data, Integer.MAX_VALUE);
        }
        return scheduler;
    }

    private static void removeScheduler(
            long effectId,
            ServerLevel level
    ) {
        TerrainScheduler existing = SCHEDULERS_BY_EFFECT.remove(effectId);
        if (existing == null) {
            return;
        }

        existing.releaseForcedChunks(level);
        ACTIVE_SCHEDULERS.remove(existing);
    }

    private static void processSchedulers(MinecraftServer server) {
        int budget = TerrainGenerationTuning.Fallout.BLOCK_CHECKS_PER_TICK;
        DetonationSavedData data = DetonationSavedData.get(server);
        int schedulersToVisit = ACTIVE_SCHEDULERS.size();
        int activeBackgroundLoads = 0;
        for (TerrainScheduler scheduler : ACTIVE_SCHEDULERS) {
            activeBackgroundLoads += scheduler.forcedChunkCount();
        }

        while (
                budget > 0
                        && schedulersToVisit-- > 0
                        && !ACTIVE_SCHEDULERS.isEmpty()
        ) {
            TerrainScheduler scheduler = ACTIVE_SCHEDULERS.removeFirst();
            ServerLevel level = server.getLevel(scheduler.zone.dimension());

            if (level == null) {
                SCHEDULERS_BY_EFFECT.remove(scheduler.zone.effectId());
                continue;
            }

            int heldBackgroundTickets = scheduler.forcedChunkCount();
            int availableBackgroundSlots = Math.max(
                    0,
                    TerrainGenerationTuning.Fallout
                            .BACKGROUND_GLOBAL_FORCE_LOAD_LIMIT
                            - activeBackgroundLoads
            );
            TerrainScheduler.StepResult result = scheduler.tick(
                    level,
                    data,
                    budget,
                    hasDistantHorizonsPlayer(level),
                    availableBackgroundSlots
            );
            activeBackgroundLoads +=
                    scheduler.forcedChunkCount() - heldBackgroundTickets;
            budget -= Math.max(1, result.blockChecks());

            if (result.complete()) {
                scheduler.releaseForcedChunks(level);
                SCHEDULERS_BY_EFFECT.remove(scheduler.zone.effectId());
            } else {
                ACTIVE_SCHEDULERS.addLast(scheduler);
            }
        }

        /* Rotate the first scheduler so low remaining budget cannot starve later detonations. */
        if (ACTIVE_SCHEDULERS.size() > 1) {
            ACTIVE_SCHEDULERS.addLast(ACTIVE_SCHEDULERS.removeFirst());
        }
    }

    private static boolean hasDistantHorizonsPlayer(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (DISTANT_HORIZONS_PLAYERS.contains(player.getUUID())) {
                return true;
            }
        }
        return false;
    }

    private static void notifyTerrainChunkUpdated(
            ServerLevel level,
            long terrainEffectId,
            ChunkPos chunkPos
    ) {
        TerrainChunkLodRefreshPayload payload =
                new TerrainChunkLodRefreshPayload(
                        terrainEffectId,
                        chunkPos.x,
                        chunkPos.z
                );

        for (ServerPlayer player : level.players()) {
            if (DISTANT_HORIZONS_PLAYERS.contains(player.getUUID())) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private static void affectReachedMobs(MinecraftServer server) {
        DetonationSavedData data = DetonationSavedData.get(server);

        for (ServerLevel level : server.getAllLevels()) {
            for (TerrainZone zone : data.terrainZonesFor(level.dimension())) {
                TerrainScheduler scheduler = SCHEDULERS_BY_EFFECT.get(
                        zone.effectId()
                );
                double reachedRadius = scheduler == null
                        ? zone.falloutRadius()
                        : scheduler.currentRadius();

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
                    if (
                            distance > reachedRadius
                                    || distance > zone.falloutRadius()
                    ) {
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
        if (
                distanceRatio
                        <= TerrainGenerationTuning.Fallout
                        .ENTITY_KILL_DISTANCE_RATIO
        ) {
            mob.hurt(level.damageSources().explosion(null, null), Float.MAX_VALUE);
            return;
        }

        if (
                distanceRatio
                        <= TerrainGenerationTuning.Fallout
                        .ENTITY_HEAVY_DAMAGE_DISTANCE_RATIO
        ) {
            mob.igniteForSeconds(
                    TerrainGenerationTuning.Fallout.ENTITY_HEAVY_FIRE_SECONDS
            );
            mob.hurt(
                    level.damageSources().explosion(null, null),
                    Math.max(
                            TerrainGenerationTuning.Fallout
                                    .ENTITY_HEAVY_DAMAGE_MINIMUM,
                            mob.getMaxHealth()
                                    * TerrainGenerationTuning.Fallout
                                    .ENTITY_HEAVY_DAMAGE_FRACTION
                    )
            );
            return;
        }

        mob.igniteForSeconds(
                TerrainGenerationTuning.Fallout.ENTITY_OUTER_FIRE_SECONDS
        );
        mob.hurt(
                level.damageSources().explosion(null, null),
                Math.max(
                        TerrainGenerationTuning.Fallout
                                .ENTITY_OUTER_DAMAGE_MINIMUM,
                        mob.getMaxHealth()
                                * TerrainGenerationTuning.Fallout
                                .ENTITY_OUTER_DAMAGE_FRACTION
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

    private static double chunkDistanceSquared(
            ChunkPos chunkPos,
            TerrainZone zone
    ) {
        double x = chunkPos.getMinBlockX() + 8.0D;
        double z = chunkPos.getMinBlockZ() + 8.0D;
        double dx = x - zone.centre().getX();
        double dz = z - zone.centre().getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Builds a compact primitive array containing every intersecting chunk in
     * true nearest-first order. The temporary sort keys consume about 8 MB at
     * the maximum 8,000-block fallout radius, rather than hundreds of thousands
     * of Java queue objects.
     */
    private static ChunkTargetPlan buildOrderedChunkTargets(TerrainZone zone) {
        int radius = (int) Math.ceil(zone.falloutRadius());
        int minimumChunkX = Math.floorDiv(zone.centre().getX() - radius, 16) - 1;
        int maximumChunkX = Math.floorDiv(zone.centre().getX() + radius, 16) + 1;
        int minimumChunkZ = Math.floorDiv(zone.centre().getZ() - radius, 16) - 1;
        int maximumChunkZ = Math.floorDiv(zone.centre().getZ() + radius, 16) + 1;

        int width = maximumChunkX - minimumChunkX + 1;
        int depth = maximumChunkZ - minimumChunkZ + 1;
        long[] sortKeys = new long[Math.multiplyExact(width, depth)];
        int count = 0;

        int centreChunkX = zone.centre().getX() >> 4;
        int centreChunkZ = zone.centre().getZ() >> 4;

        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                if (!chunkIntersectsZone(chunkPos, zone)) {
                    continue;
                }

                int offsetX = chunkX - centreChunkX;
                int offsetZ = chunkZ - centreChunkZ;
                long distanceSquared = Math.round(
                        chunkDistanceSquared(chunkPos, zone)
                );

                long packedOffsets =
                        ((long) offsetX & 0xFFFFL) << 16
                                | ((long) offsetZ & 0xFFFFL);
                sortKeys[count++] = distanceSquared << 32 | packedOffsets;
            }
        }

        Arrays.sort(sortKeys, 0, count);
        long[] orderedChunks = new long[count];

        for (int index = 0; index < count; index++) {
            long key = sortKeys[index];
            int offsetX = (short) ((key >>> 16) & 0xFFFFL);
            int offsetZ = (short) (key & 0xFFFFL);
            orderedChunks[index] = ChunkPos.asLong(
                    centreChunkX + offsetX,
                    centreChunkZ + offsetZ
            );
        }

        return new ChunkTargetPlan(
                orderedChunks,
                minimumChunkX,
                minimumChunkZ,
                width,
                depth
        );
    }

    private record ChunkTargetPlan(
            long[] orderedChunks,
            int minimumChunkX,
            int minimumChunkZ,
            int width,
            int depth
    ) {
    }

    private record EntityEffectKey(
            ResourceKey<Level> dimension,
            long effectId,
            UUID entityId
    ) {
    }

    private record ReadyChunk(
            long chunkLong,
            double distanceSquared
    ) {
    }

    private static final class TerrainScheduler {
        private final TerrainZone zone;
        private final long[] orderedChunks;
        private final PriorityQueue<ReadyChunk> readyChunks =
                new PriorityQueue<>(
                        Comparator.comparingDouble(ReadyChunk::distanceSquared)
                                .thenComparingLong(ReadyChunk::chunkLong)
                );
        private final Set<Long> queuedChunks = new HashSet<>();
        private final Map<Long, FalloutChunkProcessor> pausedTasks =
                new HashMap<>();

        private int discoveryCursor;
        private int backgroundCursor;
        private int completedTargetChunks;
        private int progressLogCountdown;
        private int backgroundCooldown;
        private int lodDiscoveryCursor;
        private boolean distantHorizonsPreviouslyAvailable;

        private FalloutChunkProcessor activeTask;
        private long activeChunk = NO_CHUNK;
        private boolean activeForced;

        private final Map<Long, BackgroundChunk> forcedChunks =
                new HashMap<>();

        private double currentRadius;

        private TerrainScheduler(
                TerrainZone zone,
                DetonationSavedData data
        ) {
            this.zone = zone;
            ChunkTargetPlan targetPlan = buildOrderedChunkTargets(zone);
            this.orderedChunks = targetPlan.orderedChunks();
            data.configureTerrainChunkGrid(
                    zone.effectId(),
                    targetPlan.minimumChunkX(),
                    targetPlan.minimumChunkZ(),
                    targetPlan.width(),
                    targetPlan.depth(),
                    orderedChunks
            );
            this.currentRadius = zone.visualScale();
            this.progressLogCountdown =
                    TerrainGenerationTuning.Fallout.PROGRESS_LOG_INTERVAL_TICKS;

            for (long chunkLong : orderedChunks) {
                if (data.isTerrainChunkProcessed(zone.effectId(), chunkLong)) {
                    completedTargetChunks++;
                }
            }

        }

        private void onChunkLoaded(
                ServerLevel level,
                DetonationSavedData data,
                ChunkPos chunkPos
        ) {
            enqueueLoadedChunk(data, chunkPos);
        }

        private void discoverLoadedChunks(
                ServerLevel level,
                DetonationSavedData data,
                int requestedLimit
        ) {
            int limit = Math.min(
                    requestedLimit,
                    TerrainGenerationTuning.Fallout
                            .LOADED_DISCOVERY_CHUNKS_PER_TICK
            );
            int inspected = 0;

            while (
                    discoveryCursor < orderedChunks.length
                            && inspected++ < limit
            ) {
                long chunkLong = orderedChunks[discoveryCursor++];
                if (data.isTerrainChunkProcessed(zone.effectId(), chunkLong)) {
                    continue;
                }

                ChunkPos chunkPos = new ChunkPos(chunkLong);
                if (level.hasChunk(chunkPos.x, chunkPos.z)) {
                    enqueueLoadedChunk(data, chunkPos);
                }
            }
        }

        private void enqueueLoadedChunk(
                DetonationSavedData data,
                ChunkPos chunkPos
        ) {
            long chunkLong = chunkPos.toLong();
            if (data.isTerrainChunkProcessed(zone.effectId(), chunkLong)) {
                return;
            }
            if (chunkLong == activeChunk || !queuedChunks.add(chunkLong)) {
                return;
            }

            readyChunks.add(
                    new ReadyChunk(
                            chunkLong,
                            chunkDistanceSquared(chunkPos, zone)
                    )
            );
        }

        private StepResult tick(
                ServerLevel level,
                DetonationSavedData data,
                int maximumBlockChecks,
                boolean distantHorizonsAvailable,
                int availableBackgroundSlots
        ) {
            if (completedTargetChunks < orderedChunks.length) {
                discoverLoadedChunks(
                        level,
                        data,
                        TerrainGenerationTuning.Fallout
                                .LOADED_DISCOVERY_CHUNKS_PER_TICK
                );
                maintainBackgroundChunks(
                        level,
                        data,
                        distantHorizonsAvailable,
                        availableBackgroundSlots
                );
            } else {
                releaseForcedChunks(level);
            }
            discoverLoadedPendingLodChunks(
                    level,
                    data,
                    distantHorizonsAvailable
            );

            int checks = 0;
            while (checks < maximumBlockChecks) {
                if (activeTask == null) {
                    int remainingChecks = maximumBlockChecks - checks;
                    int atomicChunkChecks = maximumChunkBlockChecks(level);
                    if (
                            atomicChunkChecks
                                    <= TerrainGenerationTuning.Fallout
                                    .BLOCK_CHECKS_PER_TICK
                                    && remainingChecks < atomicChunkChecks
                    ) {
                        /* Start whole chunks only when this tick can finish them. */
                        break;
                    }

                    if (!startNextLoadedTask(level, data)) {
                        break;
                    }
                }

                ChunkPos chunkPos = new ChunkPos(activeChunk);
                if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
                    if (activeForced) {
                        /* The temporary ticket should recover this next tick. */
                        break;
                    }

                    pausedTasks.put(activeChunk, activeTask);
                    clearActiveTask();
                    continue;
                }

                FalloutChunkProcessor.StepResult result = activeTask.tick(
                        level,
                        maximumBlockChecks - checks
                );
                checks += Math.max(1, result.blockChecks());

                if (result.complete()) {
                    finishActiveChunk(level, data);
                }
            }

            tickProgressLog();
            boolean terrainComplete =
                    completedTargetChunks >= orderedChunks.length;
            boolean sessionLodScanComplete =
                    !distantHorizonsAvailable
                            || lodDiscoveryCursor >= orderedChunks.length;
            return new StepResult(
                    checks,
                    terrainComplete && sessionLodScanComplete
            );
        }

        private static int maximumChunkBlockChecks(ServerLevel level) {
            long worldHeight = Math.max(
                    1L,
                    (long) level.getMaxBuildHeight()
                            - level.getMinBuildHeight()
            );
            return (int) Math.min(
                    Integer.MAX_VALUE,
                    worldHeight * 16L * 16L
            );
        }

        private boolean startNextLoadedTask(
                ServerLevel level,
                DetonationSavedData data
        ) {
            while (!readyChunks.isEmpty()) {
                ReadyChunk ready = readyChunks.poll();
                queuedChunks.remove(ready.chunkLong());

                if (
                        data.isTerrainChunkProcessed(
                                zone.effectId(),
                                ready.chunkLong()
                        )
                ) {
                    continue;
                }

                ChunkPos chunkPos = new ChunkPos(ready.chunkLong());
                if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
                    /* Remains incomplete; onChunkLoad or DH will enqueue it. */
                    continue;
                }

                activeChunk = ready.chunkLong();
                activeForced = forcedChunks.containsKey(ready.chunkLong());
                activeTask = pausedTasks.remove(activeChunk);
                if (activeTask == null) {
                    activeTask = new FalloutChunkProcessor(zone, chunkPos);
                }
                return true;
            }

            return false;
        }

        private void finishActiveChunk(
                ServerLevel level,
                DetonationSavedData data
        ) {
            long completedChunk = activeChunk;
            boolean completedByBackgroundLoader = activeForced;
            ChunkPos chunkPos = new ChunkPos(completedChunk);

            if (
                    data.markTerrainChunkProcessed(
                            zone.effectId(),
                            completedChunk
                    )
            ) {
                completedTargetChunks++;
            }

            data.markTerrainChunkLodRefreshPending(
                    zone.effectId(),
                    completedChunk
            );

            currentRadius = Math.max(
                    currentRadius,
                    Math.sqrt(chunkDistanceSquared(chunkPos, zone)) + 12.0D
            );

            clearActiveTask();

            if (forcedChunks.containsKey(completedChunk)) {
                finishForcedChunk(level, data, completedChunk);
            }

            if (completedByBackgroundLoader) {
                /*
                 * Keep the DH-pending marker. A later natural chunk load sends
                 * the refresh only when the client can actually receive it.
                 */
                return;
            }

            if (hasDistantHorizonsPlayer(level)) {
                notifyTerrainChunkUpdated(
                        level,
                        zone.effectId(),
                        chunkPos
                );
            }
        }

        private void discoverLoadedPendingLodChunks(
                ServerLevel level,
                DetonationSavedData data,
                boolean distantHorizonsAvailable
        ) {
            if (!distantHorizonsAvailable) {
                distantHorizonsPreviouslyAvailable = false;
                return;
            }

            if (!distantHorizonsPreviouslyAvailable) {
                lodDiscoveryCursor = 0;
                distantHorizonsPreviouslyAvailable = true;
            }

            int inspected = 0;
            while (
                    lodDiscoveryCursor < orderedChunks.length
                            && inspected++
                            < TerrainGenerationTuning.Fallout
                            .LOADED_DISCOVERY_CHUNKS_PER_TICK
            ) {
                long chunkLong = orderedChunks[lodDiscoveryCursor++];
                if (
                        !data.isTerrainChunkLodRefreshPending(
                                zone.effectId(),
                                chunkLong
                        )
                ) {
                    continue;
                }

                ChunkPos chunkPos = new ChunkPos(chunkLong);
                if (level.hasChunk(chunkPos.x, chunkPos.z)) {
                    notifyTerrainChunkUpdated(
                            level,
                            zone.effectId(),
                            chunkPos
                    );
                }
            }
        }

        private void maintainBackgroundChunks(
                ServerLevel level,
                DetonationSavedData data,
                boolean distantHorizonsAvailable,
                int availableBackgroundSlots
        ) {
            if (backgroundCooldown > 0) {
                backgroundCooldown--;
            }

            if (!distantHorizonsAvailable) {
                for (long chunkLong : new ArrayList<>(forcedChunks.keySet())) {
                    if (activeChunk != chunkLong) {
                        releaseForcedChunk(level, chunkLong);
                    }
                }
                return;
            }

            for (long chunkLong : new ArrayList<>(forcedChunks.keySet())) {
                BackgroundChunk backgroundChunk = forcedChunks.get(chunkLong);
                if (backgroundChunk == null) {
                    continue;
                }

                if (data.isTerrainChunkProcessed(zone.effectId(), chunkLong)) {
                    finishForcedChunk(level, data, chunkLong);
                    continue;
                }

                ChunkPos chunkPos = new ChunkPos(chunkLong);
                if (level.hasChunk(chunkPos.x, chunkPos.z)) {
                    enqueueLoadedChunk(data, chunkPos);
                    continue;
                }

                backgroundChunk.waitTicks++;
                if (
                        backgroundChunk.waitTicks
                                == TerrainGenerationTuning.Fallout
                                .BACKGROUND_SYNC_LOAD_FALLBACK_TICKS
                ) {
                    /* Two chunks maximum: reliable without returning to square batches. */
                    level.getChunk(chunkPos.x, chunkPos.z);
                }

                if (
                        backgroundChunk.waitTicks
                                >= TerrainGenerationTuning.Fallout
                                .BACKGROUND_FORCE_LOAD_TIMEOUT_TICKS
                ) {
                    LOGGER.warn(
                            "Fallout terrain chunk {} timed out while force-loading; it remains incomplete and will retry.",
                            chunkPos
                    );
                    if (activeChunk == chunkLong && activeTask != null) {
                        pausedTasks.put(activeChunk, activeTask);
                        clearActiveTask();
                    }
                    releaseForcedChunk(level, chunkLong);
                    backgroundCooldown =
                            TerrainGenerationTuning.Fallout
                                    .BACKGROUND_CHUNK_INTERVAL_TICKS;
                }
            }

            if (backgroundCooldown > 0 || availableBackgroundSlots <= 0) {
                return;
            }

            int localCapacity = Math.min(
                    availableBackgroundSlots,
                    TerrainGenerationTuning.Fallout
                            .BACKGROUND_CHUNKS_PER_SCHEDULER
                            - forcedChunks.size()
            );
            if (localCapacity <= 0) {
                return;
            }

            advanceBackgroundCursor(data);
            int scanCursor = backgroundCursor;
            int scannedTargets = 0;

            while (
                    localCapacity > 0
                            && scanCursor < orderedChunks.length
                            && scannedTargets++
                            < TerrainGenerationTuning.Fallout
                            .BACKGROUND_TARGET_SCAN_PER_TICK
            ) {
                long chunkLong = orderedChunks[scanCursor];
                if (data.isTerrainChunkProcessed(zone.effectId(), chunkLong)) {
                    scanCursor++;
                    continue;
                }

                if (
                        chunkLong == activeChunk
                                || queuedChunks.contains(chunkLong)
                                || forcedChunks.containsKey(chunkLong)
                ) {
                    scanCursor++;
                    continue;
                }

                ChunkPos chunkPos = new ChunkPos(chunkLong);
                if (level.hasChunk(chunkPos.x, chunkPos.z)) {
                    enqueueLoadedChunk(data, chunkPos);
                    scanCursor++;
                    continue;
                }

                TERRAIN_TICKET_CONTROLLER.forceChunk(
                        level,
                        zone.centre(),
                        chunkPos.x,
                        chunkPos.z,
                        true,
                        false
                );
                forcedChunks.put(
                        chunkLong,
                        new BackgroundChunk()
                );
                localCapacity--;
                scanCursor++;
            }
        }

        private int forcedChunkCount() {
            return forcedChunks.size();
        }

        private void finishForcedChunk(
                ServerLevel level,
                DetonationSavedData data,
                long chunkLong
        ) {
            releaseForcedChunk(level, chunkLong);
            advanceBackgroundCursor(data);
            backgroundCooldown =
                    TerrainGenerationTuning.Fallout
                            .BACKGROUND_CHUNK_INTERVAL_TICKS;
        }

        private void advanceBackgroundCursor(DetonationSavedData data) {
            while (
                    backgroundCursor < orderedChunks.length
                            && data.isTerrainChunkProcessed(
                            zone.effectId(),
                            orderedChunks[backgroundCursor]
                    )
            ) {
                backgroundCursor++;
            }
        }

        private void releaseForcedChunk(
                ServerLevel level,
                long chunkLong
        ) {
            BackgroundChunk removed = forcedChunks.remove(chunkLong);
            if (removed == null) {
                return;
            }

            ChunkPos chunkPos = new ChunkPos(chunkLong);
            TERRAIN_TICKET_CONTROLLER.forceChunk(
                    level,
                    zone.centre(),
                    chunkPos.x,
                    chunkPos.z,
                    false,
                    false
            );
        }

        private void releaseForcedChunks(ServerLevel level) {
            for (long chunkLong : new ArrayList<>(forcedChunks.keySet())) {
                releaseForcedChunk(level, chunkLong);
            }
        }

        private void clearActiveTask() {
            activeTask = null;
            activeChunk = NO_CHUNK;
            activeForced = false;
        }

        private void tickProgressLog() {
            if (--progressLogCountdown > 0) {
                return;
            }

            progressLogCountdown =
                    TerrainGenerationTuning.Fallout.PROGRESS_LOG_INTERVAL_TICKS;
            LOGGER.info(
                    "Fallout terrain at {}: {} / {} chunks complete ({} queued, {} DH background chunks).",
                    zone.centre(),
                    completedTargetChunks,
                    orderedChunks.length,
                    readyChunks.size(),
                    forcedChunks.size()
            );
        }

        private double currentRadius() {
            return Math.min(
                    zone.falloutRadius(),
                    Math.max(zone.visualScale(), currentRadius)
            );
        }

        private static final class BackgroundChunk {
            private int waitTicks;
        }

        private record StepResult(int blockChecks, boolean complete) {
        }
    }
}
