package com.github.loganmartingreen.nucleocraftdetonation.terrain.blast;

import com.github.loganmartingreen.nucleocraftdetonation.block.DetonationBlocks;
import com.github.loganmartingreen.nucleocraftdetonation.config.TerrainGenerationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.fluid.DetonationFluids;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.crater.DetonationEnvironmentProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.common.Tags;

/**
 * Radial underground shock/thermal damage surrounding a subterranean cavity.
 * Unlike surface fallout this scans around the cavity in all directions, so
 * the floor and lower walls are altered just as strongly as the roof.
 */
public final class SubterraneanDamageTask {
    private static final int UPDATE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static final long DAMAGE_SALT = 0xD1B54A32D192ED03L;
    private static final long VARIANT_SALT = 0x94D049BB133111EBL;

    private final DetonationEnvironmentProfile profile;
    private final BlockPos centre;
    private final long seed;
    private final int horizontalRadius;
    private final int verticalRadius;

    private int offsetX;
    private int offsetZ;
    private int currentY;
    private int minimumY;
    private boolean columnReady;
    private boolean complete;

    public SubterraneanDamageTask(DetonationEnvironmentProfile profile) {
        this.profile = profile;
        this.centre = profile.centre();
        this.seed = mix64(centre.asLong() ^ 0xC13FA9A902A6328FL);
        this.horizontalRadius = (int) Math.ceil(
                profile.cavityRadius() + profile.fractureShellDepth()
        );
        this.verticalRadius = (int) Math.ceil(
                profile.cavityVerticalRadius() + profile.fractureShellDepth()
        );
        this.offsetX = -horizontalRadius;
        this.offsetZ = -horizontalRadius;
    }

    public int refreshRadius() {
        return horizontalRadius + 16;
    }

    public boolean tick(ServerLevel level, int maximumOperations) {
        if (complete) return true;
        int operations = 0;

        while (!complete && operations < maximumOperations) {
            if (!columnReady) {
                prepareColumn(level);
                if (!columnReady) {
                    advanceColumn();
                    continue;
                }
            }

            int x = centre.getX() + offsetX;
            int z = centre.getZ() + offsetZ;
            BlockPos pos = new BlockPos(x, currentY, z);
            BlockState state = level.getBlockState(pos);
            BlockState replacement = replacementFor(state, x, currentY, z);
            if (replacement != null && !replacement.equals(state)) {
                level.setBlock(pos, replacement, UPDATE_FLAGS);
            }

            currentY--;
            operations++;
            if (currentY < minimumY) {
                advanceColumn();
            }
        }

        return complete;
    }

    private void prepareColumn(ServerLevel level) {
        double horizontal = Math.hypot(offsetX + 0.5D, offsetZ + 0.5D);
        if (horizontal > horizontalRadius + 1.0D) {
            return;
        }

        int x = centre.getX() + offsetX;
        int z = centre.getZ() + offsetZ;
        level.getChunk(x >> 4, z >> 4);
        int damageTop = Math.min(
                level.getMaxBuildHeight() - 1,
                centre.getY() + verticalRadius
        );
        if (profile.mode() == DetonationEnvironmentProfile.Mode.SUBTERRANEAN_SEALED) {
            damageTop = Math.min(
                    damageTop,
                    profile.terrainSurfaceY()
                            - TerrainGenerationTuning.SubterraneanDamage
                            .SEALED_SURFACE_PROTECTION_DEPTH
            );
        }
        currentY = damageTop;
        minimumY = Math.max(
                level.getMinBuildHeight(),
                centre.getY() - verticalRadius
        );
        columnReady = currentY >= minimumY;
    }

    private BlockState replacementFor(BlockState state, int x, int y, int z) {
        if (state.isAir() || isProtected(state)) {
            return null;
        }

        double dx = x + 0.5D - (centre.getX() + 0.5D);
        double dy = y + 0.5D - (centre.getY() + 0.5D);
        double dz = z + 0.5D - (centre.getZ() + 0.5D);
        double nx = dx / Math.max(1.0D, profile.cavityRadius());
        double ny = dy / Math.max(1.0D, profile.cavityVerticalRadius());
        double nz = dz / Math.max(1.0D, profile.cavityRadius());
        double normalised = Math.sqrt(nx * nx + ny * ny + nz * nz);

        if (normalised <= 0.92D) {
            return null;
        }

        double physicalRadius = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double boundaryRadius = normalised > 0.0001D
                ? physicalRadius / normalised
                : physicalRadius;
        double wallDistance = Math.max(0.0D, physicalRadius - boundaryRadius);
        if (wallDistance > profile.fractureShellDepth()) {
            return null;
        }

        double shellProgress = Math.clamp(
                wallDistance / Math.max(1.0D, profile.fractureShellDepth()),
                0.0D,
                1.0D
        );
        double intensity = Math.pow(1.0D - shellProgress, 1.35D);

        // Exposed organic cave growth and mineshaft timber cannot survive pristine
        // against the freshly excavated thermal/shock shell.
        if (isOrganic(state) && intensity > 0.06D) {
            return state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.ROOTED_DIRT)
                    ? DetonationBlocks.CHARRED_DIRT.get().defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        }

        if (state.getFluidState().is(FluidTags.WATER)
                || state.getFluidState().is(DetonationFluids.IRRADIATED_WATER.get())
                || state.getFluidState().is(DetonationFluids.FLOWING_IRRADIATED_WATER.get())) {
            return intensity > 0.16D
                    ? Blocks.AIR.defaultBlockState()
                    : null;
        }

        double maxFailureChance = lerp(
                TerrainGenerationTuning.SubterraneanDamage
                        .BREAKOUT_MAX_BLOCK_FAILURE_CHANCE,
                TerrainGenerationTuning.SubterraneanDamage
                        .SEALED_MAX_BLOCK_FAILURE_CHANCE,
                profile.containmentFactor()
        );
        double failureChance = maxFailureChance
                * Math.pow(
                        intensity,
                        TerrainGenerationTuning.SubterraneanDamage.BLOCK_FAILURE_POWER
                );

        if (isOreLike(state)) {
            return DetonationBlocks.IRRADIATED_ORE.get().defaultBlockState();
        }

        if (random01(x, y, z, DAMAGE_SALT) < failureChance) {
            return Blocks.AIR.defaultBlockState();
        }

        if (state.is(Blocks.GRAVEL)) {
            return DetonationBlocks.CHARRED_GRAVEL.get().defaultBlockState();
        }
        if (isSandstoneOrTerracotta(state)) {
            return DetonationBlocks.CHARRED_STONE_1.get().defaultBlockState();
        }
        if (isDirtLike(state)) {
            return DetonationBlocks.CHARRED_DIRT.get().defaultBlockState();
        }
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) {
            return DetonationBlocks.CHARRED_SAND.get().defaultBlockState();
        }
        if (state.is(Blocks.CLAY)) {
            return DetonationBlocks.CHARRED_STONE_0.get().defaultBlockState();
        }
        if (isStoneLike(state)) {
            return chooseCharredStone(x, y, z);
        }

        /* Underground structures inside the shock shell can fail too, with
         * sealed detonations receiving the strongest coupling. */
        if (state.getFluidState().isEmpty()
                && random01(x, y, z, DAMAGE_SALT ^ 0x55AA55AA55AA55AAL)
                < failureChance * 1.35D) {
            return Blocks.AIR.defaultBlockState();
        }

        return null;
    }

    private void advanceColumn() {
        columnReady = false;
        offsetZ++;
        if (offsetZ > horizontalRadius) {
            offsetZ = -horizontalRadius;
            offsetX++;
        }
        if (offsetX > horizontalRadius) {
            complete = true;
        }
    }

    private BlockState chooseCharredStone(int x, int y, int z) {
        return random01(x, y, z, VARIANT_SALT)
                < TerrainGenerationTuning.Fallout.CHARRED_STONE_0_RATIO
                ? DetonationBlocks.CHARRED_STONE_0.get().defaultBlockState()
                : DetonationBlocks.CHARRED_STONE_1.get().defaultBlockState();
    }

    private static boolean isOrganic(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.LEAVES) || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.MOSS_CARPET) || state.is(Blocks.VINE)
                || state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.HANGING_ROOTS) || state.is(Blocks.BIG_DRIPLEAF)
                || state.is(Blocks.SMALL_DRIPLEAF) || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS) || state.is(Blocks.SPORE_BLOSSOM)
                || state.is(Blocks.GLOW_LICHEN) || state.is(Blocks.OAK_FENCE)
                || state.is(Blocks.SPRUCE_FENCE);
    }

    private static boolean isDirtLike(BlockState state) {
        return state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.MUD)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.FARMLAND);
    }

    private static boolean isSandstoneOrTerracotta(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id.getPath();
        return path.contains("sandstone")
                || path.equals("terracotta")
                || path.endsWith("_terracotta");
    }

    private static boolean isOreLike(BlockState state) {
        if (state.is(Tags.Blocks.ORES)) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id.getPath();
        return path.endsWith("_ore")
                || path.contains("_ore_")
                || path.equals("ancient_debris")
                || path.endsWith("ancient_debris");
    }

    private static boolean isStoneLike(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.CRACKED_STONE_BRICKS)
                || state.is(Blocks.MOSSY_STONE_BRICKS)
                || state.is(Blocks.CHISELED_STONE_BRICKS)
                || state.is(Blocks.SMOOTH_STONE)
                || state.is(Blocks.TUFF_BRICKS)
                || state.is(Blocks.CHISELED_TUFF_BRICKS)
                || state.is(Blocks.POLISHED_TUFF);
    }

    private static boolean isProtected(BlockState state) {
        return state.is(Blocks.BEDROCK)
                || state.is(Blocks.BARRIER)
                || state.is(Blocks.END_PORTAL_FRAME)
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.NETHER_PORTAL)
                || state.is(Blocks.COMMAND_BLOCK)
                || state.is(Blocks.CHAIN_COMMAND_BLOCK)
                || state.is(Blocks.REPEATING_COMMAND_BLOCK);
    }

    private double random01(int x, int y, int z, long salt) {
        long value = seed ^ salt;
        value ^= (long) x * 0x9E3779B97F4A7C15L;
        value ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        value ^= (long) z * 0x165667B19E3779F9L;
        value = mix64(value);
        return (value >>> 11) * 0x1.0p-53;
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }
}
