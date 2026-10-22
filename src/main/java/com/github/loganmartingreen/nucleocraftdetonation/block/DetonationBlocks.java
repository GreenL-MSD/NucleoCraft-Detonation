package com.github.loganmartingreen.nucleocraftdetonation.block;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.fluid.DetonationFluids;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registers the permanent charred and irradiated fallout blocks. */
public final class DetonationBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(NucleoCraftDetonation.MOD_ID);

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(NucleoCraftDetonation.MOD_ID);

    public static final DeferredBlock<Block> CHARRED_STONE_0 =
            BLOCKS.registerSimpleBlock(
                    "charred_stone_0",
                    BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
            );

    public static final DeferredBlock<Block> CHARRED_STONE_1 =
            BLOCKS.registerSimpleBlock(
                    "charred_stone_1",
                    BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE)
            );


    public static final DeferredBlock<Block> CHARRED_DIRT =
            BLOCKS.registerSimpleBlock(
                    "charred_dirt",
                    BlockBehaviour.Properties.ofFullCopy(Blocks.DIRT)
            );

    public static final DeferredBlock<Block> CHARRED_SAND =
            BLOCKS.registerSimpleBlock(
                    "charred_sand",
                    BlockBehaviour.Properties.ofFullCopy(Blocks.SAND)
            );

    public static final DeferredBlock<Block> CHARRED_PLANKS =
            BLOCKS.registerSimpleBlock(
                    "charred_planks",
                    BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS)
            );

    public static final DeferredBlock<StairBlock> CHARRED_PLANK_STAIRS =
            BLOCKS.registerBlock(
                    "charred_plank_stairs",
                    properties -> new StairBlock(
                            CHARRED_PLANKS.get().defaultBlockState(),
                            properties
                    ),
                    BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_STAIRS)
            );

    public static final DeferredBlock<SlabBlock> CHARRED_PLANK_SLAB =
            BLOCKS.registerBlock(
                    "charred_plank_slab",
                    SlabBlock::new,
                    BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_SLAB)
            );

    public static final DeferredBlock<Block> RAD_GRASS =
            BLOCKS.registerSimpleBlock(
                    "rad_grass",
                    BlockBehaviour.Properties.ofFullCopy(Blocks.GRASS_BLOCK)
            );

    public static final DeferredBlock<LiquidBlock> IRRADIATED_WATER =
            BLOCKS.registerBlock(
                    "irradiated_water",
                    properties -> new LiquidBlock(
                            DetonationFluids.IRRADIATED_WATER.get(),
                            properties
                    ),
                    BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)
            );

    public static final DeferredBlock<RotatedPillarBlock> CHARRED_LOG =
            BLOCKS.registerBlock(
                    "charred_log",
                    RotatedPillarBlock::new,
                    BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
            );

    public static final DeferredBlock<Block> RAD_LEAVES =
            BLOCKS.registerSimpleBlock(
                    "rad_leaves",
                    BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LEAVES)
                            .noOcclusion()
            );

    public static final DeferredItem<BlockItem> CHARRED_STONE_0_ITEM =
            ITEMS.registerSimpleBlockItem(CHARRED_STONE_0);
    public static final DeferredItem<BlockItem> CHARRED_STONE_1_ITEM =
            ITEMS.registerSimpleBlockItem(CHARRED_STONE_1);
    public static final DeferredItem<BlockItem> CHARRED_DIRT_ITEM =
            ITEMS.registerSimpleBlockItem(CHARRED_DIRT);
    public static final DeferredItem<BlockItem> CHARRED_SAND_ITEM =
            ITEMS.registerSimpleBlockItem(CHARRED_SAND);
    public static final DeferredItem<BlockItem> CHARRED_PLANKS_ITEM =
            ITEMS.registerSimpleBlockItem(CHARRED_PLANKS);
    public static final DeferredItem<BlockItem> CHARRED_PLANK_STAIRS_ITEM =
            ITEMS.registerSimpleBlockItem(CHARRED_PLANK_STAIRS);
    public static final DeferredItem<BlockItem> CHARRED_PLANK_SLAB_ITEM =
            ITEMS.registerSimpleBlockItem(CHARRED_PLANK_SLAB);
    public static final DeferredItem<BlockItem> RAD_GRASS_ITEM =
            ITEMS.registerSimpleBlockItem(RAD_GRASS);
    public static final DeferredItem<BlockItem> CHARRED_LOG_ITEM =
            ITEMS.registerSimpleBlockItem(CHARRED_LOG);
    public static final DeferredItem<BlockItem> RAD_LEAVES_ITEM =
            ITEMS.registerSimpleBlockItem(RAD_LEAVES);

    private DetonationBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(DetonationBlocks::addCreativeTabContents);
    }

    private static void addCreativeTabContents(
            BuildCreativeModeTabContentsEvent event
    ) {
        if (event.getTabKey().equals(CreativeModeTabs.BUILDING_BLOCKS)) {
            event.accept(CHARRED_STONE_0_ITEM.get());
            event.accept(CHARRED_STONE_1_ITEM.get());
            event.accept(CHARRED_DIRT_ITEM.get());
            event.accept(CHARRED_SAND_ITEM.get());
            event.accept(CHARRED_PLANKS_ITEM.get());
            event.accept(CHARRED_PLANK_STAIRS_ITEM.get());
            event.accept(CHARRED_PLANK_SLAB_ITEM.get());
            event.accept(CHARRED_LOG_ITEM.get());
        }

        if (event.getTabKey().equals(CreativeModeTabs.NATURAL_BLOCKS)) {
            event.accept(RAD_GRASS_ITEM.get());
            event.accept(RAD_LEAVES_ITEM.get());
        }
    }
}
