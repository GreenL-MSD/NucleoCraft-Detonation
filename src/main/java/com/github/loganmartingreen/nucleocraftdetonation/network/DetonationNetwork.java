package com.github.loganmartingreen.nucleocraftdetonation.network;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.client.DetonationClient;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Contains both client-bound detonation payloads and their registration.
 */
@EventBusSubscriber(
        modid = NucleoCraftDetonation.MOD_ID,
        bus = EventBusSubscriber.Bus.MOD
)
public final class DetonationNetwork {
    private DetonationNetwork() {
    }

    @SubscribeEvent
    public static void registerPayloads(
            RegisterPayloadHandlersEvent event
    ) {
        PayloadRegistrar registrar =
                event.registrar("1");

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
            float strength
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
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC,
                        DetonationEffectsPayload::centre,

                        ByteBufCodecs.FLOAT,
                        DetonationEffectsPayload::flashRadius,

                        ByteBufCodecs.FLOAT,
                        DetonationEffectsPayload::soundRadius,

                        ByteBufCodecs.FLOAT,
                        DetonationEffectsPayload::visualScale,

                        ByteBufCodecs.FLOAT,
                        DetonationEffectsPayload::strength,

                        DetonationEffectsPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
