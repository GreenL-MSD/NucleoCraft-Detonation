package com.github.loganmartingreen.nucleocraftdetonation.block;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Dedicated creative inventory tab for NucleoCraft: Detonation content. */
public final class DetonationCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(
                    Registries.CREATIVE_MODE_TAB,
                    NucleoCraftDetonation.MOD_ID
            );

    public static final Supplier<CreativeModeTab> MAIN = TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable(
                            "itemGroup.nucleocraft_detonation.main"
                    ))
                    .icon(() -> new ItemStack(
                            DetonationBlocks.CHARRED_STONE_0_ITEM.get()
                    ))
                    .displayItems((parameters, output) -> {
                        output.accept(DetonationBlocks.CHARRED_STONE_0_ITEM.get());
                        output.accept(DetonationBlocks.CHARRED_STONE_1_ITEM.get());
                        output.accept(DetonationBlocks.IRRADIATED_ORE_ITEM.get());
                        output.accept(DetonationBlocks.CHARRED_DIRT_ITEM.get());
                        output.accept(DetonationBlocks.CHARRED_SAND_ITEM.get());
                        output.accept(DetonationBlocks.CHARRED_GRAVEL_ITEM.get());
                        output.accept(DetonationBlocks.RAD_GRASS_ITEM.get());
                        output.accept(DetonationBlocks.CHARRED_LOG_ITEM.get());
                        output.accept(DetonationBlocks.RAD_LEAVES_ITEM.get());
                        output.accept(DetonationBlocks.CHARRED_PLANKS_ITEM.get());
                        output.accept(DetonationBlocks.CHARRED_PLANK_STAIRS_ITEM.get());
                        output.accept(DetonationBlocks.CHARRED_PLANK_SLAB_ITEM.get());
                    })
                    .build()
    );

    private DetonationCreativeTabs() {
    }

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
