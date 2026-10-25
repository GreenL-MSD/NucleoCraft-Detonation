package com.github.loganmartingreen.nucleocraftdetonation.terrain.crater;

import com.github.loganmartingreen.nucleocraftdetonation.block.DetonationBlocks;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The shared solid-ground predicate for crater excavation and environment
 * profiling. Fallout replacements remain physical terrain: ignoring them
 * causes later detonations to mistake buried vanilla stone for the surface.
 *
 * Decorative wood, leaves and both kinds of water are intentionally excluded.
 */
public final class SurfaceTerrainBlocks {
    private SurfaceTerrainBlocks() { }

    public static boolean isTerrain(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.MUD)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.TERRACOTTA)
                || state.is(DetonationBlocks.CHARRED_STONE_0.get())
                || state.is(DetonationBlocks.CHARRED_STONE_1.get())
                || state.is(DetonationBlocks.CHARRED_DIRT.get())
                || state.is(DetonationBlocks.CHARRED_SAND.get())
                || state.is(DetonationBlocks.CHARRED_GRAVEL.get())
                || state.is(DetonationBlocks.RAD_GRASS.get())
                || state.is(DetonationBlocks.IRRADIATED_ORE.get());
    }
}
