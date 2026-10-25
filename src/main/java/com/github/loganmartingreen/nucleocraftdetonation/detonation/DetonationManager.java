package com.github.loganmartingreen.nucleocraftdetonation.detonation;

import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.config.TerrainGenerationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.CraterLodRefreshPayload;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.DetonationEffectsPayload;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.FalloutVisualPayload;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.PersistentVisualEntry;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.PersistentVisualSyncPayload;
import com.github.loganmartingreen.nucleocraftdetonation.persistence.DetonationSavedData;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.fallout.FalloutTerrainManager;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.blast.SubterraneanDamageTask;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.blast.UndergroundAftermathTask;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.crater.SubsidenceTask;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.crater.SealedSurfaceAftermathTask;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.crater.SealedSurfaceFinishTask;
import com.github.loganmartingreen.nucleocraftdetonation.fallout.UndergroundSpawnSuppression;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.DebugModePayload;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.crater.CraterDimensions;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.crater.CraterExcavationTask;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.crater.SurfaceCraterHistory;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.crater.SurfaceCraterRepeatRules;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.crater.DetonationEnvironmentProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns development countdowns, immediate detonation effects and active terrain excavation jobs.
 */
public final class DetonationManager {
    private static final List<Countdown> COUNTDOWNS =
            new ArrayList<>();

    private static final List<ActiveExcavation> ACTIVE_EXCAVATIONS =
            new ArrayList<>();

    private static final Map<UUID, ResourceKey<Level>> LAST_SYNCED_DIMENSION =
            new HashMap<>();

    private static MinecraftServer previousServer;
    private static final Set<UUID> DEBUG_PLAYERS = new HashSet<>();

    public static void setDebug(ServerPlayer player, boolean enabled) {
        if (enabled) DEBUG_PLAYERS.add(player.getUUID());
        else DEBUG_PLAYERS.remove(player.getUUID());
        PacketDistributor.sendToPlayer(player, new DebugModePayload(enabled));
        player.sendSystemMessage(Component.literal("[NUKE] Detailed debug "
                + (enabled ? "enabled" : "disabled") + "."));
    }

    private static void debug(MinecraftServer server, UUID owner, String event) {
        if (DEBUG_PLAYERS.contains(owner)) {
            sendToOwner(server, owner, Component.literal("[NUKE DEBUG] " + event));
        }
    }


    private DetonationManager() {
    }

    public static void scheduleDetonation(
            ServerPlayer owner,
            BlockPos centre,
            NuclearYield yield
    ) {
        COUNTDOWNS.add(
                new Countdown(
                        owner.getUUID(),
                        owner.serverLevel().dimension(),
                        centre.immutable(),
                        yield,
                        DetonationTuning.Command.COUNTDOWN_TICKS
                )
        );

        owner.sendSystemMessage(
                Component.literal(
                        Integer.toString(
                                DetonationTuning.Command.COUNTDOWN_TICKS / 20
                        )
                )
        );
    }

    public static void onServerTick(
            ServerTickEvent.Post event
    ) {
        MinecraftServer server =
                event.getServer();

        if (previousServer != server) {
            COUNTDOWNS.clear();
            ACTIVE_EXCAVATIONS.clear();
            LAST_SYNCED_DIMENSION.clear();
            DEBUG_PLAYERS.clear();
            FalloutTerrainManager.clear();
            previousServer = server;
        }

        UndergroundSpawnSuppression.tick(server);
        DetonationSavedData.get(server).tick();
        syncPlayersWhenNeeded(server);
        tickCountdowns(server);
        tickExcavations(server);
        FalloutTerrainManager.tick(server);
    }


    private static void syncPlayersWhenNeeded(
            MinecraftServer server
    ) {
        Set<UUID> onlinePlayers = new HashSet<>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            ResourceKey<Level> dimension =
                    player.serverLevel().dimension();

            onlinePlayers.add(playerId);

            if (dimension.equals(LAST_SYNCED_DIMENSION.get(playerId))) {
                continue;
            }

            syncPersistentVisuals(player);
            PacketDistributor.sendToPlayer(player,
                    new DebugModePayload(DEBUG_PLAYERS.contains(playerId)));
            LAST_SYNCED_DIMENSION.put(playerId, dimension);
        }

        LAST_SYNCED_DIMENSION.keySet().retainAll(onlinePlayers);
    }

    private static void syncPersistentVisuals(
            ServerPlayer player
    ) {
        List<PersistentVisualEntry> entries =
                DetonationSavedData.get(
                                player.serverLevel().getServer()
                        )
                        .snapshotsFor(
                                player.serverLevel().dimension()
                        )
                        .stream()
                        .map(snapshot -> new PersistentVisualEntry(
                                snapshot.centre(),
                                snapshot.falloutActive(),
                                snapshot.falloutRadius(),
                                snapshot.falloutDurationTicks(),
                                snapshot.falloutAgeTicks(),
                                snapshot.falloutStrength(),
                                snapshot.cloudActive(),
                                snapshot.visualScale(),
                                snapshot.cloudAgeTicks(),
                                snapshot.cloudStrength(),
                                snapshot.cloudExitY(),
                                snapshot.subterranean()
                        ))
                        .toList();

        PacketDistributor.sendToPlayer(
                player,
                new PersistentVisualSyncPayload(entries)
        );
    }

    private static void tickCountdowns(
            MinecraftServer server
    ) {
        Iterator<Countdown> iterator =
                COUNTDOWNS.iterator();

        while (iterator.hasNext()) {
            Countdown countdown =
                    iterator.next();

            countdown.remainingTicks--;

            if (
                    countdown.remainingTicks > 0
                            && countdown.remainingTicks % 20 == 0
            ) {
                sendToOwner(
                        server,
                        countdown.owner,
                        Component.literal(
                                Integer.toString(
                                        countdown.remainingTicks / 20
                                )
                        )
                );
            }

            if (countdown.remainingTicks > 0) {
                continue;
            }

            ServerLevel level =
                    server.getLevel(countdown.dimension);

            if (level != null) {
                int surfaceRadius = CraterDimensions.surfaceExcavationFromYield(
                        countdown.yield).radius();
                // Import surviving pre-history surface craters before this
                // shot overwrites any persistent visual record at its centre.
                seedLegacySurfaceCraterHistory(server, level);
                boolean alreadyExcavated = SurfaceCraterHistory.get(level).alreadyExcavated(
                        countdown.centre, surfaceRadius)
                        || previouslyRecordedSurfaceCrater(server, countdown.dimension,
                                countdown.centre, surfaceRadius)
                        || surfaceExcavationAlreadyActive(countdown.dimension,
                                countdown.centre, surfaceRadius);
                // A new underground shot below a previous crater is still
                // underground. Only force surface if its actual target can
                // see the sky through the existing opening.
                boolean previousOpenSurface = alreadyExcavated
                        && level.canSeeSky(countdown.centre.above());
                DetonationEnvironmentProfile environment =
                        DetonationEnvironmentProfile.analyse(
                                level,
                                countdown.centre,
                                countdown.yield,
                                previousOpenSurface
                        );
                debug(server, countdown.owner,
                        "Stage 1: environment classified: " + environment.outcome()
                                + " (" + environment.mode() + ")");
                debug(server, countdown.owner,
                        "Detonation Y=" + countdown.centre.getY()
                                + ", representative surface Y=" + environment.terrainSurfaceY()
                                + ", burial=" + environment.burialDepth() + " blocks");
                debug(server, countdown.owner,
                        "Reference radius=" + environment.dimensions().radius()
                                + ", reference excavation depth=" + environment.dimensions().depth()
                                + "; cavity R=" + Math.round(environment.cavityRadius())
                                + ", cavity vertical R=" + Math.round(environment.cavityVerticalRadius())
                                + ", intact overburden=" + Math.round(environment.overburdenAboveCavity()));
                debug(server, countdown.owner,
                        "Rock integrity=" + Math.round(environment.rockIntegrity() * 100) + "%"
                                + ", void porosity=" + Math.round(environment.cavePorosity() * 100) + "%"
                                + ", relief=" + Math.round(environment.terrainRelief() * 100) + "%"
                                + ", breakout strength=" + Math.round(environment.breakoutStrength() * 100) + "%"
                                + ", surface opening radius=" + Math.round(environment.breakoutSurfaceRadius()));
                // Never re-excavate a completed surface crater from its
                // already-lowered floor. Also account for active overlapping
                // jobs, which are not yet written to the saved history.
                boolean repeatSurface = environment.mode()
                        == DetonationEnvironmentProfile.Mode.SURFACE
                        && alreadyExcavated;
                debug(server, countdown.owner, repeatSurface
                        ? "Stage 2: repeat surface crater detected; physical re-excavation skipped"
                        : "Stage 2: cavity/crater excavation started");
                CraterExcavationTask craterTask =
                        new CraterExcavationTask(
                                countdown.owner,
                                countdown.centre,
                                countdown.yield,
                                environment,
                                repeatSurface
                        );

                /*
                 * Immediate effects use the same environment classification as
                 * terrain. Sealed shots therefore never create surface fallout
                 * or a mushroom cloud, while breakout strength scales venting.
                 */
                long terrainEffectId = broadcastDetonation(
                        level,
                        countdown.centre,
                        environment
                );

                ACTIVE_EXCAVATIONS.add(
                        new ActiveExcavation(
                                countdown.dimension,
                                craterTask,
                                environment,
                                terrainEffectId,
                                repeatSurface
                        )
                );

                CraterDimensions size = craterTask.dimensions();

                sendToOwner(
                        server,
                        countdown.owner,
                        Component.literal(
                                "Generating "
                                        + countdown.yield.displayName()
                                        + " "
                                        + environment.mode().name().toLowerCase()
                                        + " excavation: reference radius "
                                        + size.radius()
                                        + ", depth "
                                        + size.depth()
                                        + ", burial "
                                        + environment.burialDepth()
                        )
                );
            }

            iterator.remove();
        }
    }

    /** Make old visual records useful for *overlapping* craters, not only
     * exact repeat centres. Existing active excavation jobs must not be
     * mistaken for previously completed terrain.
     */
    private static void seedLegacySurfaceCraterHistory(MinecraftServer server,
                                                        ServerLevel level) {
        double ratio = TerrainGenerationTuning.Crater.SURFACE_EXCAVATION_REFERENCE_RADIUS
                / TerrainGenerationTuning.Crater.REFERENCE_RADIUS_BLOCKS;
        SurfaceCraterHistory history = SurfaceCraterHistory.get(level);
        for (DetonationSavedData.Snapshot old :
                DetonationSavedData.get(server).snapshotsFor(level.dimension())) {
            if (old.subterranean()) continue;
            if (isActiveExcavationAt(level.dimension(), old.centre())) continue;
            int radius = Math.max(1, (int) Math.round(old.visualScale() * ratio));
            history.recordCompleted(old.centre(), radius);
        }
    }

    private static boolean isActiveExcavationAt(ResourceKey<Level> dimension,
                                               BlockPos centre) {
        for (ActiveExcavation active : ACTIVE_EXCAVATIONS) {
            if (active.dimension.equals(dimension)
                    && active.task.centre().equals(centre)
                    && !active.excavationComplete) return true;
        }
        return false;
    }

    /** Also recognise pre-v42.5.8 craters from persistent fallout/cloud data.
     * Read this BEFORE broadcastDetonation replaces an existing effect entry.
     */
    private static boolean previouslyRecordedSurfaceCrater(MinecraftServer server,
                                                           ResourceKey<Level> dimension,
                                                           BlockPos centre, int radius) {
        double surfaceToReference = TerrainGenerationTuning.Crater.SURFACE_EXCAVATION_REFERENCE_RADIUS
                / TerrainGenerationTuning.Crater.REFERENCE_RADIUS_BLOCKS;
        for (DetonationSavedData.Snapshot old : DetonationSavedData.get(server).snapshotsFor(dimension)) {
            if (old.subterranean()) continue;
            int oldRadius = (int) Math.round(old.visualScale() * surfaceToReference);
            if (SurfaceCraterRepeatRules.sameSite(old.centre().getX(), old.centre().getZ(),
                    oldRadius, centre.getX(), centre.getZ(), radius)) return true;
        }
        return false;
    }

    private static boolean surfaceExcavationAlreadyActive(ResourceKey<Level> dimension,
                                                           BlockPos centre, int radius) {
        for (ActiveExcavation active : ACTIVE_EXCAVATIONS) {
            if (!active.dimension.equals(dimension)
                    || active.environment.mode() != DetonationEnvironmentProfile.Mode.SURFACE
                    || active.repeatSurface) continue;
            if (SurfaceCraterRepeatRules.sameSite(
                    active.task.centre().getX(), active.task.centre().getZ(),
                    active.task.dimensions().radius(), centre.getX(), centre.getZ(), radius)) {
                return true;
            }
        }
        return false;
    }

    private static void tickExcavations(MinecraftServer server) {
        Iterator<ActiveExcavation> iterator = ACTIVE_EXCAVATIONS.iterator();
        while (iterator.hasNext()) {
            ActiveExcavation active = iterator.next();
            ServerLevel level = server.getLevel(active.dimension);
            if (level == null) {
                iterator.remove();
                continue;
            }
            if (!active.excavationComplete) {
                if (!active.task.tick(level, TerrainGenerationTuning.Crater.OPERATIONS_PER_TICK))
                    continue;
                active.excavationComplete = true;
                if (active.environment.mode() == DetonationEnvironmentProfile.Mode.SURFACE
                        && !active.repeatSurface) {
                    SurfaceCraterHistory.get(level).recordCompleted(active.task.centre(),
                            active.task.dimensions().radius());
                }
                debug(server, active.task.owner(), "Stage 3: excavation complete");
                if (active.environment.isSubterranean()) {
                    active.subterraneanDamage = new SubterraneanDamageTask(active.environment);
                    debug(server, active.task.owner(), "Stage 4: underground rock damage started");
                }
            }
            if (active.subterraneanDamage != null && !active.damageComplete) {
                if (!active.subterraneanDamage.tick(level,
                        TerrainGenerationTuning.SubterraneanDamage.OPERATIONS_PER_TICK)) continue;
                active.damageComplete = true;
                active.undergroundAftermath = new UndergroundAftermathTask(active.environment);
                UndergroundSpawnSuppression.add(active.dimension, active.environment);
                debug(server, active.task.owner(), "Stage 5: radial rock damage complete; cave vent and mob cleanup started");
            }
            if (active.undergroundAftermath != null && !active.aftermathComplete) {
                if (!active.undergroundAftermath.tick(level, 650)) continue;
                active.aftermathComplete = true;
                debug(server, active.task.owner(), "Stage 6: cave vent/organic cleanup completed");
                if (active.environment.eligibleForSubsidence()) {
                    active.sealedSurfaceAftermath = new SealedSurfaceAftermathTask(active.environment);
                    active.subsidence = new SubsidenceTask(active.environment);
                    debug(server, active.task.owner(), "Stage 7: sealed surface scarring scheduled ahead of delayed subsidence");
                }
            }
            if (!active.terrainCompleted) {
                active.terrainCompleted = true;
                DetonationSavedData savedData = DetonationSavedData.get(server);
                if (active.environment.hasSurfaceFallout()) {
                    savedData.startTerrainConversion(active.terrainEffectId);
                    FalloutTerrainManager.startInitialSweep(level, active.terrainEffectId);
                    debug(server, active.task.owner(), "Stage 8: persistent surface fallout started");
                } else {
                    debug(server, active.task.owner(), "Stage 8: surface fallout skipped (contained)");
                }
                sendToOwner(server, active.task.owner(), Component.literal(
                        active.task.yield().displayName() + " terrain generation complete ("
                                + active.environment.mode().name().toLowerCase()
                                + ", containment "
                                + Math.round(active.environment.containmentFactor() * 100.0D)
                                + "%)."));
                refreshLod(level, active);
            }
            if (active.sealedSurfaceAftermath != null) {
                if (!active.sealedSurfaceAftermath.tick(level,
                        TerrainGenerationTuning.Crater.SEALED_SURFACE_DAMAGE_BLOCK_CHECKS_PER_TICK)) continue;
                debug(server, active.task.owner(), "Stage 9: sealed surface rubble / vegetation cleanup completed");
                refreshLod(level, active);
                active.sealedSurfaceAftermath = null;
            }
            if (active.subsidence != null) {
                if (!active.subsidence.tick(level,
                        TerrainGenerationTuning.Crater.SUBSIDENCE_OPERATIONS_PER_TICK)) continue;
                debug(server, active.task.owner(), "Stage 9: surface subsidence completed");
                refreshLod(level, active);
                active.subsidence = null;
                active.sealedSurfaceFinish = new SealedSurfaceFinishTask(active.environment);
            }
            if (active.sealedSurfaceFinish != null) {
                if (!active.sealedSurfaceFinish.tick(level,
                        TerrainGenerationTuning.Crater.SEALED_SURFACE_FINISH_CHECKS_PER_TICK)) continue;
                debug(server, active.task.owner(), "Stage 10: shallow ground tears and debris cleanup completed");
                active.sealedSurfaceFinish = null;
                refreshLod(level, active);
            }
            debug(server, active.task.owner(), "Terrain lifecycle finished");
            iterator.remove();
        }
    }

    private static void refreshLod(ServerLevel level, ActiveExcavation active) {
        int refreshRadius = active.task.refreshRadius();
        if (active.subterraneanDamage != null) {
            refreshRadius = Math.max(refreshRadius, active.subterraneanDamage.refreshRadius());
        }
        PacketDistributor.sendToPlayersInDimension(level,
                new CraterLodRefreshPayload(active.task.centre(), refreshRadius));
    }

    private static long broadcastDetonation(
            ServerLevel level,
            BlockPos centre,
            DetonationEnvironmentProfile environment
    ) {
        CraterDimensions dimensions = environment.dimensions();
        float baseFalloutRadius =
                (float) Math.clamp(
                        dimensions.radius()
                                * DetonationTuning.Fallout.RADIUS_MULTIPLIER,
                        DetonationTuning.Fallout.MINIMUM_RADIUS,
                        DetonationTuning.Fallout.MAXIMUM_RADIUS
                );
        float falloutRadius = (float) Math.max(
                1.0D,
                baseFalloutRadius * environment.surfaceFalloutMultiplier()
        );
        float falloutStrength = (float) Math.clamp(
                DetonationTuning.Fallout.STRENGTH
                        * environment.surfaceFalloutMultiplier(),
                0.0D,
                1.0D
        );
        int falloutDuration = environment.hasSurfaceFallout()
                ? DetonationTuning.Fallout.DURATION_TICKS
                : 1;

        float flashRadius =
                (float) Math.clamp(
                        dimensions.radius()
                                * DetonationTuning.ImmediateEffects
                                .FLASH_RADIUS_MULTIPLIER,
                        DetonationTuning.ImmediateEffects
                                .MINIMUM_FLASH_RADIUS,
                        DetonationTuning.ImmediateEffects
                                .MAXIMUM_FLASH_RADIUS
                );

        float soundRadius =
                (float) Math.clamp(
                        dimensions.radius()
                                * DetonationTuning.ImmediateEffects
                                .SOUND_RADIUS_MULTIPLIER,
                        DetonationTuning.ImmediateEffects
                                .MINIMUM_SOUND_RADIUS,
                        DetonationTuning.ImmediateEffects
                                .MAXIMUM_SOUND_RADIUS
                );

        float cloudStrength = environment.mode()
                == DetonationEnvironmentProfile.Mode.SUBTERRANEAN_SEALED
                ? 0.0F
                : DetonationTuning.ImmediateEffects.STRENGTH;

        long terrainEffectId =
                DetonationSavedData.get(level.getServer())
                        .addDetonation(
                                level.dimension(),
                                centre,
                                falloutRadius,
                                falloutDuration,
                                falloutStrength,
                                dimensions.radius(),
                                cloudStrength,
                                environment.cloudExitY(),
                                environment.isSubterranean()
                        );

        PacketDistributor.sendToPlayersInDimension(
                level,
                new DetonationEffectsPayload(
                        centre,
                        flashRadius,
                        soundRadius,
                        dimensions.radius(),
                        DetonationTuning.ImmediateEffects.STRENGTH,
                        environment.cloudExitY(),
                        environment.mode() == DetonationEnvironmentProfile.Mode.SURFACE
                                ? DetonationTuning.ImmediateEffects.STRENGTH : 0.0F,
                        environment.isSubterranean()
                )
        );

        if (environment.hasSurfaceFallout()) {
            PacketDistributor.sendToPlayersInDimension(
                    level,
                    new FalloutVisualPayload(
                            centre,
                            falloutRadius,
                            falloutDuration,
                            falloutStrength
                    )
            );
        }

        return terrainEffectId;
    }

    private static void sendToOwner(
            MinecraftServer server,
            UUID owner,
            Component message
    ) {
        ServerPlayer player =
                server.getPlayerList()
                        .getPlayer(owner);

        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    private static final class Countdown {
        private final UUID owner;
        private final ResourceKey<Level> dimension;
        private final BlockPos centre;
        private final NuclearYield yield;

        private int remainingTicks;

        private Countdown(
                UUID owner,
                ResourceKey<Level> dimension,
                BlockPos centre,
                NuclearYield yield,
                int remainingTicks
        ) {
            this.owner = owner;
            this.dimension = dimension;
            this.centre = centre;
            this.yield = yield;
            this.remainingTicks = remainingTicks;
        }
    }

    private static final class ActiveExcavation {
        private final ResourceKey<Level> dimension;
        private final CraterExcavationTask task;
        private final DetonationEnvironmentProfile environment;
        private final long terrainEffectId;
        private final boolean repeatSurface;

        private boolean excavationComplete;
        private SubterraneanDamageTask subterraneanDamage;
        private UndergroundAftermathTask undergroundAftermath;
        private SubsidenceTask subsidence;
        private SealedSurfaceAftermathTask sealedSurfaceAftermath;
        private SealedSurfaceFinishTask sealedSurfaceFinish;
        private boolean damageComplete;
        private boolean aftermathComplete;
        private boolean terrainCompleted;

        private ActiveExcavation(
                ResourceKey<Level> dimension,
                CraterExcavationTask task,
                DetonationEnvironmentProfile environment,
                long terrainEffectId,
                boolean repeatSurface
        ) {
            this.dimension = dimension;
            this.task = task;
            this.environment = environment;
            this.terrainEffectId = terrainEffectId;
            this.repeatSurface = repeatSurface;
        }
    }
}
