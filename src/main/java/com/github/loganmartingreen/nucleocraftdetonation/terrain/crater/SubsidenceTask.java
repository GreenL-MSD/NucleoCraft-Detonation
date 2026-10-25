package com.github.loganmartingreen.nucleocraftdetonation.terrain.crater;

import com.github.loganmartingreen.nucleocraftdetonation.config.TerrainGenerationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.block.DetonationBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Gradual, bounded surface settlement above an already-finished contained shot.
 * Moves the natural terrain surface down only a few blocks over several seconds.
 * It cannot create a shaft, surface blast, shockwave or mushroom cloud.
 */
public final class SubsidenceTask {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
            | Block.UPDATE_SUPPRESS_DROPS;
    private final DetonationEnvironmentProfile profile;
    private final int radius;
    private final byte[] settled;
    private int tick;
    private int cursor;

    public SubsidenceTask(DetonationEnvironmentProfile profile) {
        this.profile = profile;
        this.radius = Math.clamp((int) Math.round(profile.cavityRadius()
                * TerrainGenerationTuning.Crater.SUBSIDENCE_MAX_RADIUS_RATIO), 3, 48);
        int side = radius * 2 + 1;
        this.settled = new byte[side * side];
    }

    public boolean tick(ServerLevel level, int budget) {
        tick++;
        int delay = TerrainGenerationTuning.Crater.SUBSIDENCE_DELAY_TICKS;
        int duration = TerrainGenerationTuning.Crater.SUBSIDENCE_DURATION_TICKS;
        if (tick < delay) return false;
        double progress = Math.clamp((tick - delay) / (double) Math.max(1, duration), 0.0D, 1.0D);
        int operations = 0;
        while (operations < budget && cursor < settled.length) {
            int index = cursor++;
            int side = radius * 2 + 1;
            int dx = index / side - radius;
            int dz = index % side - radius;
            double d = Math.hypot(dx + 0.5D, dz + 0.5D) / radius;
            if (d >= 1.0D) continue;
            double shape = Math.pow(1.0D - d * d, 1.6D);
            double depth = TerrainGenerationTuning.Crater.SUBSIDENCE_MAX_DEPTH * shape
                    * Math.clamp(1.25D - profile.burialDepth() / Math.max(1.0D,
                            175.0D * profile.dimensions().radius() / 32.0D), 0.15D, 1.0D);
            int goal = (int) Math.floor(depth * progress + 0.3D);
            int prior = Byte.toUnsignedInt(settled[index]);
            int x = profile.centre().getX() + dx;
            int z = profile.centre().getZ() + dz;
            while (prior < goal) {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (y <= level.getMinBuildHeight() + 2 || y < profile.centre().getY()) break;
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (!isNatural(state)) break;
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                prior++;
                operations++;
            }
            settled[index] = (byte) prior;
            if (goal > 0) {
                // The lowered ground must look broken, not reveal pristine stone.
                SealedSurfaceAftermathTask.finishSettledSurface(level, x, z);
            }
        }
        if (cursor >= settled.length) cursor = 0;
        // Iterate until the full timed lowering is finished and the final pass has run.
        return tick >= delay + duration && cursor == 0;
    }

    private static boolean isNatural(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.MUD)
                || state.is(Blocks.CLAY) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)
                || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.TERRACOTTA)
                || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(DetonationBlocks.CHARRED_STONE_0.get())
                || state.is(DetonationBlocks.CHARRED_STONE_1.get())
                || state.is(DetonationBlocks.CHARRED_GRAVEL.get())
                || state.is(DetonationBlocks.CHARRED_DIRT.get());
    }
}
