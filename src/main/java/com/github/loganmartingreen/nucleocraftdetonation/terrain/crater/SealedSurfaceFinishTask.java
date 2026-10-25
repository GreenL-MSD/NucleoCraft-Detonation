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
 * Final *sealed* surface pass after delayed subsidence. Removes the orphaned
 * tree stems/decorations left above lowered terrain and carves a handful of
 * shallow, discontinuous ground fractures in the subsidence basin. Never
 * excavates a chimney or connects the surface to the underground cavity.
 * Column-scanned and bounded per tick, so a large event cannot stall a tick.
 */
public final class SealedSurfaceFinishTask {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
            | Block.UPDATE_SUPPRESS_DROPS;
    private final DetonationEnvironmentProfile profile;
    private final int radius;
    private final int settlementRadius;
    private final int side;
    private int column;
    private boolean scanning;
    private int x, y, z, ground;
    private double normalisedDistance;

    public SealedSurfaceFinishTask(DetonationEnvironmentProfile profile) {
        if (profile.mode() != DetonationEnvironmentProfile.Mode.SUBTERRANEAN_SEALED) {
            throw new IllegalArgumentException("Surface finish is only for sealed detonations");
        }
        this.profile = profile;
        this.radius = Math.clamp((int) Math.round(profile.cavityRadius()
                * TerrainGenerationTuning.Crater.SEALED_SURFACE_DAMAGE_RADIUS_RATIO), 4, 96);
        this.settlementRadius = Math.clamp((int) Math.round(profile.cavityRadius()
                * TerrainGenerationTuning.Crater.SUBSIDENCE_MAX_RADIUS_RATIO), 3, 48);
        this.side = radius * 2 + 1;
    }

    public boolean tick(ServerLevel level, int budget) {
        int checks = 0;
        while (checks < budget && column < side * side) {
            if (!scanning) {
                int dx = column / side - radius;
                int dz = column % side - radius;
                normalisedDistance = Math.hypot(dx + 0.5D, dz + 0.5D) / radius;
                if (normalisedDistance >= 1.0D) { column++; continue; }
                x = profile.centre().getX() + dx;
                z = profile.centre().getZ() + dz;
                int top = Math.min(level.getMaxBuildHeight() - 1,
                        level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
                ground = SealedSurfaceAftermathTask.findNaturalGround(level, x, top, z);
                if (ground < level.getMinBuildHeight()) { column++; continue; }
                y = top;
                scanning = true;
            }
            if (y > ground) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                // Remove the snapped log pillars, pumpkin caps, hanging canopy
                // and loose decoration especially visible after ground lowering.
                // No player-owned block inventories or structural stone is touched.
                if (normalisedDistance <= 0.85D && isFragileRemnant(state)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                }
                y--;
                checks++;
                continue;
            }
            // One-time work per column: heterogeneous newly exposed surface,
            // then a narrow, shallow crack where local ground stress is high.
            SealedSurfaceAftermathTask.finishSettledSurface(level, x, z);
            int tearDepth = surfaceTearDepth(x, z);
            int yTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            for (int cut = 0; cut < tearDepth && checks < budget; cut++) {
                int cutY = yTop - cut;
                if (cutY <= profile.centre().getY() + 8
                        || cutY < level.getMinBuildHeight() + 3) break;
                BlockPos pos = new BlockPos(x, cutY, z);
                BlockState state = level.getBlockState(pos);
                if (!isFracturableSurface(state)) break;
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                checks++;
            }
            // Keep per-column processing deterministic even if budget was
            // reached while opening a small crack (maximum three blocks).
            scanning = false;
            column++;
            checks++;
        }
        return column >= side * side;
    }

    private static boolean isFragileRemnant(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.FLOWERS)
                || state.is(Blocks.PUMPKIN) || state.is(Blocks.CARVED_PUMPKIN)
                || state.is(Blocks.JACK_O_LANTERN) || state.is(Blocks.MELON)
                || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || state.is(Blocks.RED_MUSHROOM_BLOCK)
                || state.is(Blocks.MUSHROOM_STEM)
                || state.is(Blocks.MOSS_CARPET) || state.is(Blocks.VINE)
                || state.is(Blocks.BAMBOO) || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS) || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.SNOW);
    }

    private static boolean isFracturableSurface(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY)
                || state.is(Blocks.TERRACOTTA)
                || state.is(DetonationBlocks.CHARRED_STONE_0.get())
                || state.is(DetonationBlocks.CHARRED_STONE_1.get())
                || state.is(DetonationBlocks.CHARRED_GRAVEL.get());
    }

    /** 6–9 broken radial arms with a slight deterministic wobble and optional
     * short fork. The width is measured in *blocks*, avoiding wide trenches.
     * Does not run outside the closed subsidence bowl or at its centre. */
    private int surfaceTearDepth(int x, int z) {
        double dx = x + 0.5D - (profile.centre().getX() + 0.5D);
        double dz = z + 0.5D - (profile.centre().getZ() + 0.5D);
        double r = Math.hypot(dx, dz);
        if (r < settlementRadius * 0.22D || r > settlementRadius * 0.94D) return 0;
        double bearing = Math.atan2(dz, dx);
        double phase = 0.35D + pseudo(profile.centre().getX(), profile.centre().getZ()) * 6.28D;
        int arms = 7;
        double nearest = Double.POSITIVE_INFINITY;
        for (int branch = 0; branch < arms; branch++) {
            double base = phase + branch * (Math.PI * 2.0D / arms);
            double curve = 0.075D * Math.sin(r * 0.22D + branch * 2.31D);
            double delta = Math.atan2(Math.sin(bearing - base - curve),
                    Math.cos(bearing - base - curve));
            nearest = Math.min(nearest, Math.abs(delta) * r);
        }
        double jitter = pseudo(x * 3, z * 5) * 0.55D;
        if (nearest > 0.72D + jitter) return 0;
        double skip = pseudo(x * 7 + 9, z * 11 - 5);
        if (skip < 0.12D) return 0; // irregular cracks, not ruler lines
        return 1 + (nearest < 0.53D ? 1 : 0)
                + (nearest < 0.32D && skip > 0.73D ? 1 : 0);
    }

    private static double pseudo(int x, int z) {
        long h = ((long) x * 0x9E3779B97F4A7C15L) ^
                ((long) z * 0xC2B2AE3D27D4EB4FL) ^ 0x425FADC319B70E11L;
        h ^= h >>> 30; h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27; h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }
}
