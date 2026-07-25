package com.github.loganmartingreen.nucleocraftdetonation.detonation;

import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.CraterLodRefreshPayload;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.DetonationEffectsPayload;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.FalloutVisualPayload;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.PersistentVisualEntry;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.PersistentVisualSyncPayload;
import com.github.loganmartingreen.nucleocraftdetonation.persistence.DetonationSavedData;

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
 * Owns development countdowns, immediate detonation effects and active crater jobs.
 */
public final class DetonationManager {
    private static final List<Countdown> COUNTDOWNS =
            new ArrayList<>();

    private static final List<ActiveCrater> ACTIVE_CRATERS =
            new ArrayList<>();

    private static final Map<UUID, ResourceKey<Level>> LAST_SYNCED_DIMENSION =
            new HashMap<>();

    private static MinecraftServer previousServer;

    private DetonationManager() {
    }

    public static void scheduleCrater(
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
            ACTIVE_CRATERS.clear();
            LAST_SYNCED_DIMENSION.clear();
            previousServer = server;
        }

        DetonationSavedData.get(server).tick();
        syncPlayersWhenNeeded(server);
        tickCountdowns(server);
        tickCraters(server);
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
                                snapshot.cloudStrength()
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
                CraterTask craterTask =
                        new CraterTask(
                                countdown.owner,
                                countdown.centre,
                                countdown.yield
                        );

                /*
                 * Send the flash, delayed sound and permanent fallout before
                 * the first crater terrain pass begins.
                 */
                broadcastDetonation(
                        level,
                        countdown.centre,
                        craterTask.dimensions()
                );

                ACTIVE_CRATERS.add(
                        new ActiveCrater(
                                countdown.dimension,
                                craterTask
                        )
                );

                CraterDimensions size =
                        craterTask.dimensions();

                sendToOwner(
                        server,
                        countdown.owner,
                        Component.literal(
                                "Generating "
                                        + countdown.yield.displayName()
                                        + " crater: radius "
                                        + size.radius()
                                        + ", depth "
                                        + size.depth()
                                        + ", rim "
                                        + size.rimWidth()
                                        + "x"
                                        + size.rimHeight()
                        )
                );
            }

            iterator.remove();
        }
    }

    private static void tickCraters(
            MinecraftServer server
    ) {
        Iterator<ActiveCrater> iterator =
                ACTIVE_CRATERS.iterator();

        while (iterator.hasNext()) {
            ActiveCrater activeCrater =
                    iterator.next();

            ServerLevel level =
                    server.getLevel(
                            activeCrater.dimension
                    );

            if (level == null) {
                iterator.remove();
                continue;
            }

            CraterTask task =
                    activeCrater.task;

            if (
                    task.tick(
                            level,
                            DetonationTuning.Command.CRATER_OPERATIONS_PER_TICK
                    )
            ) {
                sendToOwner(
                        server,
                        task.owner(),
                        Component.literal(
                                task.yield().displayName()
                                        + " crater complete."
                        )
                );

                PacketDistributor.sendToPlayersInDimension(
                        level,
                        new CraterLodRefreshPayload(
                                task.centre(),
                                task.dimensions().radius()
                                        + task.dimensions().rimWidth()
                                        + 16
                        )
                );

                iterator.remove();
            }
        }
    }

    private static void broadcastDetonation(
            ServerLevel level,
            BlockPos centre,
            CraterDimensions dimensions
    ) {
        float falloutRadius =
                (float) Math.clamp(
                        dimensions.radius()
                                * DetonationTuning.Fallout.RADIUS_MULTIPLIER,
                        DetonationTuning.Fallout.MINIMUM_RADIUS,
                        DetonationTuning.Fallout.MAXIMUM_RADIUS
                );

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

        DetonationSavedData.get(level.getServer())
                .addDetonation(
                        level.dimension(),
                        centre,
                        falloutRadius,
                        DetonationTuning.Fallout.DURATION_TICKS,
                        DetonationTuning.Fallout.STRENGTH,
                        dimensions.radius(),
                        DetonationTuning.ImmediateEffects.STRENGTH
                );

        PacketDistributor.sendToPlayersInDimension(
                level,
                new DetonationEffectsPayload(
                        centre,
                        flashRadius,
                        soundRadius,
                        dimensions.radius(),
                        DetonationTuning.ImmediateEffects.STRENGTH
                )
        );

        PacketDistributor.sendToPlayersInDimension(
                level,
                new FalloutVisualPayload(
                        centre,
                        falloutRadius,
                        DetonationTuning.Fallout.DURATION_TICKS,
                        DetonationTuning.Fallout.STRENGTH
                )
        );
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

    private record ActiveCrater(
            ResourceKey<Level> dimension,
            CraterTask task
    ) {
    }
}
