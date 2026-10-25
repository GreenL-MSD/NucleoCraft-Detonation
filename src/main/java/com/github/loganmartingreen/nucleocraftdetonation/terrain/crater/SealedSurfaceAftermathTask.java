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
 * Localized surface scarring above a shallow ENOUGH but still sealed subsurface
 * shot. This is rock settlement / ground damage, never surface fallout or a
 * breakout: no atmosphere shockwave, thermal cloud or new open vent is spawned.
 *
 * Incremental, deterministic and capped per tick. The true surface is sampled
 * beneath tree crowns, so trunks and leaves get processed without erasing the
 * natural ground. This runs before the timed subsidence pass.
 */
public final class SealedSurfaceAftermathTask {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
            | Block.UPDATE_SUPPRESS_DROPS;
    private static final int MAX_COLUMN_SEARCH = 180;
    private static final long DAMAGE_SALT = 0x517cc1b727220a95L;
    private static final long RUBBLE_SALT = 0x39D4F8C1A7B26E05L;

    private final DetonationEnvironmentProfile profile;
    private final int radius;
    private final int side;
    private int column;
    private int x;
    private int z;
    private int y;
    private int bottom;
    private int ground;
    private double strength;
    private boolean scanning;

    public SealedSurfaceAftermathTask(DetonationEnvironmentProfile profile) {
        if (profile.mode() != DetonationEnvironmentProfile.Mode.SUBTERRANEAN_SEALED)
            throw new IllegalArgumentException("Only sealed underground shots may scar the surface");
        this.profile = profile;
        this.radius = Math.clamp((int) Math.round(profile.cavityRadius()
                * TerrainGenerationTuning.Crater.SEALED_SURFACE_DAMAGE_RADIUS_RATIO), 4, 96);
        this.side = radius * 2 + 1;
    }

    public int refreshRadius() { return radius; }

    public boolean tick(ServerLevel level, int budget) {
        int checks = 0;
        while (checks < budget && column < side * side) {
            if (!scanning) {
                int dx = column / side - radius;
                int dz = column % side - radius;
                double distance = Math.hypot(dx + 0.5D, dz + 0.5D) / radius;
                if (distance >= 1.0D) { column++; continue; }
                x = profile.centre().getX() + dx;
                z = profile.centre().getZ() + dz;
                // Shallow contained events couple most strongly into the surface.
                // Deeper eligible shots remain visibly weaker; shots beyond the
                // eligibility limit never start this task at all.
                double depthFactor = Math.clamp(1.15D - profile.burialDepth()
                        / Math.max(1.0D, 240.0D * profile.dimensions().radius() / 32.0D),
                        0.28D, 1.0D);
                strength = Math.clamp((1.0D - distance * distance) * depthFactor,
                        0.0D, 1.0D);
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                ground = findNaturalGround(level, x, top, z);
                if (ground < level.getMinBuildHeight()) { column++; continue; }
                y = Math.min(top, level.getMaxBuildHeight() - 1);
                bottom = Math.max(level.getMinBuildHeight(), ground - 3);
                scanning = true;
            }
            BlockPos pos = new BlockPos(x, y, z);
            BlockState original = level.getBlockState(pos);
            BlockState replacement = surfaceDamage(original, x, y, z, ground, strength,
                    roll(x, y, z));
            if (replacement != null && !replacement.equals(original)) {
                level.setBlock(pos, replacement, FLAGS);
            }
            y--;
            checks++;
            if (y < bottom) { scanning = false; column++; }
        }
        return column >= side * side;
    }

    static int findNaturalGround(ServerLevel level, int x, int start, int z) {
        int lowestSnow = level.getMinBuildHeight() - 1;
        for (int y = start; y >= Math.max(level.getMinBuildHeight(),
                start - MAX_COLUMN_SEARCH); y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            // Snow caps and tall ice towers must not conceal the rock / soil
            // beneath them; scan through before establishing the true floor.
            if (state.is(Blocks.SNOW_BLOCK)) { lowestSnow = y; continue; }
            if (isFoundation(state)) return y;
        }
        return lowestSnow;
    }

    private static boolean isFoundation(BlockState state) {
        return (state.is(BlockTags.BASE_STONE_OVERWORLD) && !state.is(Blocks.BEDROCK))
                || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MUD)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND) || state.is(Blocks.CLAY)
                || state.is(Blocks.TERRACOTTA) || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(DetonationBlocks.CHARRED_STONE_0.get())
                || state.is(DetonationBlocks.CHARRED_STONE_1.get())
                || state.is(DetonationBlocks.CHARRED_DIRT.get())
                || state.is(DetonationBlocks.CHARRED_GRAVEL.get());
    }

    private static BlockState surfaceDamage(BlockState state, int x, int y, int z,
                                            int ground, double strength, double noise) {
        if (state.isAir() || state.is(Blocks.BEDROCK)) return null;
        // Core: every tree is stripped. Outer edge: patchy snapped trees.
        double organicChance = Math.clamp(strength * 1.8D, 0.0D, 1.0D);
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.FLOWERS)
                || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.VINE) || state.is(Blocks.MOSS_CARPET)
                || state.is(Blocks.AZALEA) || state.is(Blocks.FLOWERING_AZALEA)
                || state.is(Blocks.DEAD_BUSH) || state.is(Blocks.BAMBOO)
                || state.is(Blocks.SNOW) || state.is(Blocks.POWDER_SNOW)) {
            return noise < organicChance ? Blocks.AIR.defaultBlockState() : null;
        }
        // Large frozen formations cannot remain pristine in the severe scar.
        if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE) || state.is(Blocks.FROSTED_ICE)) {
            if (noise < organicChance) return Blocks.AIR.defaultBlockState();
            return null;
        }
        if (y > ground + 1 || y < ground - 3) return null;
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MOSS_BLOCK)) {
            return noise < Math.clamp(strength * 2.1D, 0.0D, 1.0D)
                    ? Blocks.DIRT.defaultBlockState() : null;
        }
        if (state.is(Blocks.SNOW_BLOCK)) {
            return noise < organicChance ? Blocks.AIR.defaultBlockState() : null;
        }
        // Vary the rubble by deterministic patches; cobble must not dominate the
        // whole collapse basin. Unaltered stone is part of the intended mix.
        if (noise < strength * 0.95D && isStony(state)) {
            return mixedRubble(state, x, y, z);
        }
        return null;
    }

    private static boolean isStony(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLED_DEEPSLATE);
    }

    /** Returns null where native stone should remain exposed. Keep the
     * coarse 4-block patches for a geological texture, but interleave smaller
     * shards so the result is not a checkerboard or a cobblestone carpet. */
    static BlockState mixedRubble(BlockState state, int x, int y, int z) {
        if (!isStony(state) || state.is(Blocks.BEDROCK)) return null;
        double patch = roll(x >> 2, y >> 2, z >> 2, RUBBLE_SALT);
        double grain = roll(x, y, z, RUBBLE_SALT ^ 0x631C2E3D49A5B870L);
        double choice = patch * 0.63D + grain * 0.37D;
        boolean deep = state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE);
        if (choice < 0.45D) return null; // preserve existing stone
        if (choice < 0.66D) return (deep ? Blocks.COBBLED_DEEPSLATE
                : Blocks.COBBLESTONE).defaultBlockState();
        if (choice < 0.76D) return Blocks.GRAVEL.defaultBlockState();
        if (choice < 0.92D) return (deep ? DetonationBlocks.CHARRED_STONE_1.get()
                : DetonationBlocks.CHARRED_STONE_0.get()).defaultBlockState();
        return DetonationBlocks.CHARRED_GRAVEL.get().defaultBlockState();
    }

    /** Re-char the NEW exposed floor after each subsidence column settles;
     * otherwise the lowered ground could uncover pristine stone beneath the
     * first layer treated by the earlier surface-damage pass. */
    static void finishSettledSurface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        BlockState replacement = null;
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL)) {
            replacement = Blocks.DIRT.defaultBlockState();
        } else if (isStony(state)) {
            replacement = mixedRubble(state, x, y, z);
        }
        if (replacement != null && !replacement.equals(state)) {
            level.setBlock(pos, replacement, FLAGS);
        }
    }

    private static double roll(int x, int y, int z) {
        return roll(x, y, z, DAMAGE_SALT);
    }

    private static double roll(int x, int y, int z, long salt) {
        long hash = salt ^ ((long) x * 0x9E3779B97F4A7C15L)
                ^ ((long) y * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) z * 0x165667B19E3779F9L);
        hash ^= hash >>> 30;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 27;
        hash *= 0x94D049BB133111EBL;
        hash ^= hash >>> 31;
        return (hash >>> 11) * 0x1.0p-53;
    }
}
