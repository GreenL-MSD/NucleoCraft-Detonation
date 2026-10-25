package com.github.loganmartingreen.nucleocraftdetonation.network;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.client.DetonationClient;
import com.github.loganmartingreen.nucleocraftdetonation.compat.distanthorizons.DistantHorizonsCompat;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.fallout.FalloutTerrainManager;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains client-bound detonation payloads and their registration.
 */
@EventBusSubscriber(
        modid = NucleoCraftDetonation.MOD_ID
)
public final class DetonationNetwork {
    private static final int MAX_PERSISTENT_EFFECTS_PER_SYNC = 4_096;

    private DetonationNetwork() {
    }

    @SubscribeEvent
    public static void registerPayloads(
            RegisterPayloadHandlersEvent event
    ) {
        PayloadRegistrar registrar =
                event.registrar("7");

        registrar.playToClient(
                FalloutVisualPayload.TYPE,
                FalloutVisualPayload.STREAM_CODEC,
                DetonationNetwork::handleFalloutVisual
        );

        registrar.playToClient(
                DetonationEffectsPayload.TYPE,
                DetonationEffectsPayload.STREAM_CODEC,
                DetonationNetwork::handleDetonationEffects
        );

        registrar.playToClient(
                PersistentVisualSyncPayload.TYPE,
                PersistentVisualSyncPayload.STREAM_CODEC,
                DetonationNetwork::handlePersistentVisualSync
        );

        registrar.playToClient(
                CraterLodRefreshPayload.TYPE,
                CraterLodRefreshPayload.STREAM_CODEC,
                DetonationNetwork::handleCraterLodRefresh
        );

        registrar.playToClient(
                TerrainChunkLodRefreshPayload.TYPE,
                TerrainChunkLodRefreshPayload.STREAM_CODEC,
                DetonationNetwork::handleTerrainChunkLodRefresh
        );

        registrar.playToClient(DebugModePayload.TYPE, DebugModePayload.STREAM_CODEC,
                DetonationNetwork::handleDebugMode);

        registrar.playToClient(
                ClearMushroomCloudsPayload.TYPE,
                ClearMushroomCloudsPayload.STREAM_CODEC,
                DetonationNetwork::handleClearMushroomClouds
        );

        registrar.playToServer(
                DistantHorizonsPresencePayload.TYPE,
                DistantHorizonsPresencePayload.STREAM_CODEC,
                DetonationNetwork::handleDistantHorizonsPresence
        );

        registrar.playToServer(
                TerrainChunkLodRefreshCompletePayload.TYPE,
                TerrainChunkLodRefreshCompletePayload.STREAM_CODEC,
                DetonationNetwork::handleTerrainChunkLodRefreshComplete
        );
    }

    private static void handleFalloutVisual(
            FalloutVisualPayload payload,
            IPayloadContext context
    ) {
        DetonationClient.startFallout(payload);
    }

    private static void handleDetonationEffects(
            DetonationEffectsPayload payload,
            IPayloadContext context
    ) {
        DetonationClient.startDetonation(payload);
    }

    private static void handlePersistentVisualSync(
            PersistentVisualSyncPayload payload,
            IPayloadContext context
    ) {
        DetonationClient.replacePersistentVisuals(payload);
    }

    private static void handleCraterLodRefresh(
            CraterLodRefreshPayload payload,
            IPayloadContext context
    ) {
        DistantHorizonsCompat.queueCraterRefresh(
                payload.centre(),
                payload.affectedRadius()
        );
    }

    private static void handleTerrainChunkLodRefresh(
            TerrainChunkLodRefreshPayload payload,
            IPayloadContext context
    ) {
        DistantHorizonsCompat.queueChunkRefresh(
                payload.terrainEffectId(),
                payload.chunkX(),
                payload.chunkZ()
        );
    }

    private static void handleDebugMode(DebugModePayload payload, IPayloadContext context) {
        com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticleSystem
                .setDebugMode(payload.enabled());
    }

    private static void handleClearMushroomClouds(
            ClearMushroomCloudsPayload payload,
            IPayloadContext context
    ) {
        if (payload.clear()) {
            DetonationClient.clearMushroomCloudEffects();
        }
    }

    private static void handleDistantHorizonsPresence(
            DistantHorizonsPresencePayload payload,
            IPayloadContext context
    ) {
        if (context.player() instanceof ServerPlayer serverPlayer) {
            FalloutTerrainManager.setDistantHorizonsClient(
                    serverPlayer,
                    payload.installed()
            );
        }
    }

    private static void handleTerrainChunkLodRefreshComplete(
            TerrainChunkLodRefreshCompletePayload payload,
            IPayloadContext context
    ) {
        if (context.player() instanceof ServerPlayer serverPlayer) {
            FalloutTerrainManager.acknowledgeDistantHorizonsRefresh(
                    serverPlayer,
                    payload.terrainEffectId(),
                    payload.chunkX(),
                    payload.chunkZ()
            );
        }
    }

    public record DebugModePayload(boolean enabled) implements CustomPacketPayload {
        public static final Type<DebugModePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(NucleoCraftDetonation.MOD_ID, "debug_mode"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DebugModePayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, DebugModePayload::enabled, DebugModePayload::new);
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClearMushroomCloudsPayload(
            boolean clear
    ) implements CustomPacketPayload {
        public static final Type<ClearMushroomCloudsPayload> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                NucleoCraftDetonation.MOD_ID,
                                "clear_mushroom_clouds"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                ClearMushroomCloudsPayload
                > STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL,
                        ClearMushroomCloudsPayload::clear,
                        ClearMushroomCloudsPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client capability handshake used to enable slow DH background work. */
    public record DistantHorizonsPresencePayload(
            boolean installed
    ) implements CustomPacketPayload {
        public static final Type<DistantHorizonsPresencePayload> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                NucleoCraftDetonation.MOD_ID,
                                "distant_horizons_presence"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                DistantHorizonsPresencePayload
                > STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL,
                        DistantHorizonsPresencePayload::installed,
                        DistantHorizonsPresencePayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record FalloutVisualPayload(
            BlockPos centre,
            float radius,
            int durationTicks,
            float strength
    ) implements CustomPacketPayload {
        public static final Type<FalloutVisualPayload> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                NucleoCraftDetonation.MOD_ID,
                                "start_fallout_visual"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                FalloutVisualPayload
                > STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC,
                        FalloutVisualPayload::centre,

                        ByteBufCodecs.FLOAT,
                        FalloutVisualPayload::radius,

                        ByteBufCodecs.VAR_INT,
                        FalloutVisualPayload::durationTicks,

                        ByteBufCodecs.FLOAT,
                        FalloutVisualPayload::strength,

                        FalloutVisualPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record DetonationEffectsPayload(
            BlockPos centre,
            float flashRadius,
            float soundRadius,
            float visualScale,
            float strength,
            int cloudExitY,
            float shockwaveStrength,
            boolean subterranean
    ) implements CustomPacketPayload {
        public static final Type<DetonationEffectsPayload> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                NucleoCraftDetonation.MOD_ID,
                                "start_detonation_effects"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                DetonationEffectsPayload
                > STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public DetonationEffectsPayload decode(RegistryFriendlyByteBuf buffer) {
                        return new DetonationEffectsPayload(
                                BlockPos.STREAM_CODEC.decode(buffer),
                                buffer.readFloat(),
                                buffer.readFloat(),
                                buffer.readFloat(),
                                buffer.readFloat(),
                                buffer.readVarInt(),
                                buffer.readFloat(),
                                buffer.readBoolean()
                        );
                    }

                    @Override
                    public void encode(
                            RegistryFriendlyByteBuf buffer,
                            DetonationEffectsPayload value
                    ) {
                        BlockPos.STREAM_CODEC.encode(buffer, value.centre());
                        buffer.writeFloat(value.flashRadius());
                        buffer.writeFloat(value.soundRadius());
                        buffer.writeFloat(value.visualScale());
                        buffer.writeFloat(value.strength());
                        buffer.writeVarInt(value.cloudExitY());
                        buffer.writeFloat(value.shockwaveStrength());
                        buffer.writeBoolean(value.subterranean());
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Tells compatible clients that a completed crater's real chunks should
     * replace any stale distant terrain cached by Distant Horizons.
     */
    public record CraterLodRefreshPayload(
            BlockPos centre,
            int affectedRadius
    ) implements CustomPacketPayload {
        public static final Type<CraterLodRefreshPayload> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                NucleoCraftDetonation.MOD_ID,
                                "refresh_crater_lod"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                CraterLodRefreshPayload
                > STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC,
                        CraterLodRefreshPayload::centre,

                        ByteBufCodecs.VAR_INT,
                        CraterLodRefreshPayload::affectedRadius,

                        CraterLodRefreshPayload::new
                );

        public CraterLodRefreshPayload {
            affectedRadius = Math.max(1, affectedRadius);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Refreshes one exact chunk after the fallout terrain pass has finished
     * changing it. This avoids clearing Distant Horizons' entire render cache.
     */
    public record TerrainChunkLodRefreshPayload(
            long terrainEffectId,
            int chunkX,
            int chunkZ
    ) implements CustomPacketPayload {
        public static final Type<TerrainChunkLodRefreshPayload> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                NucleoCraftDetonation.MOD_ID,
                                "refresh_terrain_chunk_lod"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                TerrainChunkLodRefreshPayload
                > STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_LONG,
                        TerrainChunkLodRefreshPayload::terrainEffectId,

                        ByteBufCodecs.VAR_INT,
                        TerrainChunkLodRefreshPayload::chunkX,

                        ByteBufCodecs.VAR_INT,
                        TerrainChunkLodRefreshPayload::chunkZ,

                        TerrainChunkLodRefreshPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client acknowledgement after DH accepted the refreshed LevelChunk. */
    public record TerrainChunkLodRefreshCompletePayload(
            long terrainEffectId,
            int chunkX,
            int chunkZ
    ) implements CustomPacketPayload {
        public static final Type<TerrainChunkLodRefreshCompletePayload> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                NucleoCraftDetonation.MOD_ID,
                                "terrain_chunk_lod_refresh_complete"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                TerrainChunkLodRefreshCompletePayload
                > STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_LONG,
                        TerrainChunkLodRefreshCompletePayload::terrainEffectId,

                        ByteBufCodecs.VAR_INT,
                        TerrainChunkLodRefreshCompletePayload::chunkX,

                        ByteBufCodecs.VAR_INT,
                        TerrainChunkLodRefreshCompletePayload::chunkZ,

                        TerrainChunkLodRefreshCompletePayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Replaces the receiving client's persistent fallout/cloud list for its
     * current dimension. Flash, sound and shockwaves are deliberately omitted
     * so reconnecting never replays the original blast.
     */
    public record PersistentVisualSyncPayload(
            List<PersistentVisualEntry> entries
    ) implements CustomPacketPayload {
        public static final Type<PersistentVisualSyncPayload> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                NucleoCraftDetonation.MOD_ID,
                                "sync_persistent_visuals"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                PersistentVisualSyncPayload
                > STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public PersistentVisualSyncPayload decode(
                            RegistryFriendlyByteBuf buffer
                    ) {
                        int count = buffer.readVarInt();

                        if (
                                count < 0
                                        || count > MAX_PERSISTENT_EFFECTS_PER_SYNC
                        ) {
                            throw new IllegalArgumentException(
                                    "Invalid persistent detonation count: "
                                            + count
                            );
                        }

                        List<PersistentVisualEntry> entries =
                                new ArrayList<>(count);

                        for (int index = 0; index < count; index++) {
                            entries.add(
                                    new PersistentVisualEntry(
                                            BlockPos.STREAM_CODEC.decode(buffer),
                                            buffer.readBoolean(),
                                            buffer.readFloat(),
                                            buffer.readVarInt(),
                                            buffer.readVarInt(),
                                            buffer.readFloat(),
                                            buffer.readBoolean(),
                                            buffer.readFloat(),
                                            buffer.readVarInt(),
                                            buffer.readFloat(),
                                            buffer.readVarInt(),
                                            buffer.readBoolean()
                                    )
                            );
                        }

                        return new PersistentVisualSyncPayload(
                                List.copyOf(entries)
                        );
                    }

                    @Override
                    public void encode(
                            RegistryFriendlyByteBuf buffer,
                            PersistentVisualSyncPayload value
                    ) {
                        List<PersistentVisualEntry> entries =
                                value.entries();

                        if (
                                entries.size()
                                        > MAX_PERSISTENT_EFFECTS_PER_SYNC
                        ) {
                            throw new IllegalArgumentException(
                                    "Too many persistent detonations to sync: "
                                            + entries.size()
                            );
                        }

                        buffer.writeVarInt(entries.size());

                        for (PersistentVisualEntry entry : entries) {
                            BlockPos.STREAM_CODEC.encode(
                                    buffer,
                                    entry.centre()
                            );
                            buffer.writeBoolean(entry.falloutActive());
                            buffer.writeFloat(entry.falloutRadius());
                            buffer.writeVarInt(entry.falloutDurationTicks());
                            buffer.writeVarInt(entry.falloutAgeTicks());
                            buffer.writeFloat(entry.falloutStrength());
                            buffer.writeBoolean(entry.cloudActive());
                            buffer.writeFloat(entry.visualScale());
                            buffer.writeVarInt(entry.cloudAgeTicks());
                            buffer.writeFloat(entry.cloudStrength());
                            buffer.writeVarInt(entry.cloudExitY());
                            buffer.writeBoolean(entry.subterranean());
                        }
                    }
                };

        public PersistentVisualSyncPayload {
            entries = List.copyOf(entries);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PersistentVisualEntry(
            BlockPos centre,
            boolean falloutActive,
            float falloutRadius,
            int falloutDurationTicks,
            int falloutAgeTicks,
            float falloutStrength,
            boolean cloudActive,
            float visualScale,
            int cloudAgeTicks,
            float cloudStrength,
            int cloudExitY,
            boolean subterranean
    ) {
    }
}
