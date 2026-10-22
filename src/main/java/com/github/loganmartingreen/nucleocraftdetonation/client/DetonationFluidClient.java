package com.github.loganmartingreen.nucleocraftdetonation.client;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.fluid.DetonationFluids;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/** Client rendering for irradiated water. */
@EventBusSubscriber(
        modid = NucleoCraftDetonation.MOD_ID,
        value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD
)
public final class DetonationFluidClient {
    private static final ResourceLocation WATER_STILL =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_still");
    private static final ResourceLocation WATER_FLOW =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_flow");
    private static final ResourceLocation WATER_OVERLAY =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_overlay");

    private DetonationFluidClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemBlockRenderTypes.setRenderLayer(
                    DetonationFluids.IRRADIATED_WATER.get(),
                    RenderType.translucent()
            );
            ItemBlockRenderTypes.setRenderLayer(
                    DetonationFluids.FLOWING_IRRADIATED_WATER.get(),
                    RenderType.translucent()
            );
        });
    }

    @SubscribeEvent
    public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerFluidType(
                new IClientFluidTypeExtensions() {
                    @Override
                    public ResourceLocation getStillTexture() {
                        return WATER_STILL;
                    }

                    @Override
                    public ResourceLocation getFlowingTexture() {
                        return WATER_FLOW;
                    }

                    @Override
                    public ResourceLocation getOverlayTexture() {
                        return WATER_OVERLAY;
                    }

                    @Override
                    public int getTintColor() {
                        return 0xFF000000 | DetonationTuning.Fallout.COLOUR_RGB;
                    }
                },
                DetonationFluids.IRRADIATED_WATER_TYPE.get()
        );
    }
}
