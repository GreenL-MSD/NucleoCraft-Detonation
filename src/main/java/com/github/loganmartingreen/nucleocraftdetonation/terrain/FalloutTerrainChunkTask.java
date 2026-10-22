package com.github.loganmartingreen.nucleocraftdetonation.terrain;

import com.github.loganmartingreen.nucleocraftdetonation.block.DetonationBlocks;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.fluid.DetonationFluids;
import com.github.loganmartingreen.nucleocraftdetonation.persistence.DetonationSavedData.TerrainZone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Applies the v24-style charred fallout replacement to either one complete
 * loaded chunk or one individual world column.
 */
final class FalloutTerrainChunkTask {
    private static final int UPDATE_FLAGS =
            Block.UPDATE_CLIENTS
                    | Block.UPDATE_KNOWN_SHAPE
                    | Block.UPDATE_SUPPRESS_DROPS;

    private static final long BROAD_NOISE_SALT = 0x6A09E667F3BCC909L;
    private static final long DETAIL_NOISE_SALT = 0xBB67AE8584CAA73BL;
    private static final long BLOCK_ROLL_SALT = 0x3C6EF372FE94F82BL;
    private static final long GROUND_CHAR_ROLL_SALT = 0xA54FF53A5F1D36F1L;
    private static final long VARIANT_ROLL_SALT = 0x510E527FADE682D1L;
    private static final long SECONDARY_BLAST_SALT = 0x1F83D9ABFB41BD6BL;
    private static final long BUILDING_DAMAGE_SALT = 0x5BE0CD19137E2179L;
    private static final long BAND_BLEND_SALT = 0x243F6A8885A308D3L;
    private static final long OUTER_FADE_SALT = 0x13198A2E03707344L;

    private final TerrainZone zone;
    private final ChunkPos chunkPos;
    private final boolean singleColumn;
    private final int fixedWorldX;
    private final int fixedWorldZ;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private int localX;
    private int localZ;
    private int currentY;
    private int minimumY;
    private int topY;
    private int groundY;
    private int terrainDepth;

    private ColumnProfile columnProfile;
    private boolean columnReady;
    private boolean complete;

    FalloutTerrainChunkTask(TerrainZone zone, ChunkPos chunkPos) {
        this.zone = zone;
        this.chunkPos = chunkPos;
        this.singleColumn = false;
        this.fixedWorldX = 0;
        this.fixedWorldZ = 0;
    }

    FalloutTerrainChunkTask(TerrainZone zone, int worldX, int worldZ) {
        this.zone = zone;
        this.chunkPos = new ChunkPos(worldX >> 4, worldZ >> 4);
        this.singleColumn = true;
        this.fixedWorldX = worldX;
        this.fixedWorldZ = worldZ;
    }

    TerrainZone zone() {
        return zone;
    }

    ChunkPos chunkPos() {
        return chunkPos;
    }

    StepResult tick(ServerLevel level, int maximumBlockChecks) {
        int checks = 0;

        while (!complete && checks < maximumBlockChecks) {
            if (!columnReady) {
                prepareColumn(level);
            }

            if (!columnReady) {
                advanceColumn();
                continue;
            }

            cursor.set(worldX(), currentY, worldZ());
            BlockState currentState = level.getBlockState(cursor);
            BlockState replacement = chooseReplacement(
                    currentState,
                    cursor.getX(),
                    cursor.getY(),
                    cursor.getZ(),
                    terrainDepth,
                    columnProfile
            );

            if (replacement != null && !replacement.equals(currentState)) {
                level.setBlock(cursor, replacement, UPDATE_FLAGS);
            }

            if (countsAsTerrainDepth(currentState)) {
                terrainDepth++;
            }

            currentY--;
            checks++;

            if (currentY < minimumY) {
                advanceColumn();
            }
        }

        return new StepResult(checks, complete);
    }

    private int worldX() {
        return singleColumn
                ? fixedWorldX
                : chunkPos.getMinBlockX() + localX;
    }

    private int worldZ() {
        return singleColumn
                ? fixedWorldZ
                : chunkPos.getMinBlockZ() + localZ;
    }

    private void prepareColumn(ServerLevel level) {
        int worldX = worldX();
        int worldZ = worldZ();

        double dx = worldX + 0.5D - zone.centre().getX();
        double dz = worldZ + 0.5D - zone.centre().getZ();
        if (dx * dx + dz * dz > zone.falloutRadius() * zone.falloutRadius()) {
            columnReady = false;
            return;
        }

        topY = level.getHeight(
                Heightmap.Types.WORLD_SURFACE,
                worldX,
                worldZ
        ) - 1;

        if (topY < level.getMinBuildHeight()) {
            columnReady = false;
            return;
        }

        groundY = findGroundSurfaceY(level, worldX, topY, worldZ);
        if (groundY < level.getMinBuildHeight()) {
            columnReady = false;
            return;
        }

        columnProfile = profileColumn(worldX, worldZ, groundY);
        if (columnProfile.severity <= 0.0D) {
            columnReady = false;
            return;
        }

        int scanDepth = columnProfile.inStoneCore
                ? scaledStoneCoreDepth()
                : DetonationTuning.FalloutTerrain.VEGETATION_SCAN_DEPTH;

        int requiredBottom = groundY - scanDepth;
        if (columnProfile.inBlastClearZone) {
            requiredBottom = Math.min(
                    requiredBottom,
                    columnProfile.blastClearFloorY - 2
            );
        }

        currentY = Math.min(topY, level.getMaxBuildHeight() - 1);
        minimumY = Math.max(level.getMinBuildHeight(), requiredBottom);
        terrainDepth = 0;
        columnReady = true;
    }

    private int findGroundSurfaceY(
            ServerLevel level,
            int x,
            int startingY,
            int z
    ) {
        int minimum = Math.max(
                level.getMinBuildHeight(),
                startingY - DetonationTuning.FalloutTerrain.GROUND_SEARCH_DEPTH
        );

        for (int y = startingY; y >= minimum; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (isNaturalTerrainFoundation(state)) {
                return y;
            }
        }

        return -1;
    }

    private void advanceColumn() {
        columnReady = false;

        if (singleColumn) {
            complete = true;
            return;
        }

        localZ++;
        if (localZ >= 16) {
            localZ = 0;
            localX++;
        }
        if (localX >= 16) {
            complete = true;
        }
    }

    private BlockState chooseReplacement(
            BlockState state,
            int x,
            int y,
            int z,
            int currentTerrainDepth,
            ColumnProfile profile
    ) {
        if (state.isAir()) {
            return null;
        }

        /*
         * Fallout water is now a real water-like fluid rather than a client
         * tint. Preserve the vanilla liquid level when replacing flowing
         * water so lakes and rivers keep their existing surface shape.
         */
        if (
                state.getFluidState().is(FluidTags.WATER)
                        && !state.getFluidState().is(DetonationFluids.IRRADIATED_WATER.get())
                        && !state.getFluidState().is(DetonationFluids.FLOWING_IRRADIATED_WATER.get())
        ) {
            BlockState irradiated = DetonationBlocks.IRRADIATED_WATER.get().defaultBlockState();
            if (
                    state.hasProperty(LiquidBlock.LEVEL)
                            && irradiated.hasProperty(LiquidBlock.LEVEL)
            ) {
                irradiated = irradiated.setValue(
                        LiquidBlock.LEVEL,
                        state.getValue(LiquidBlock.LEVEL)
                );
            }
            return irradiated;
        }

        /*
         * The blast-bowl has a flat centre and a rapidly rising ellipsoidal
         * edge. Natural terrain above that curved floor is excavated, making a
         * rounded bite in mountains rather than a clean vertical slice. Any
         * building above the local ground surface is removed completely.
         */
        if (profile.inBlastClearZone && !isBlastProtected(state)) {
            if (y > groundY) {
                return Blocks.AIR.defaultBlockState();
            }

            if (y > profile.blastClearFloorY) {
                return Blocks.AIR.defaultBlockState();
            }

            if (
                    y >= profile.blastClearFloorY - 1
                            && isTerrainFoundation(state)
            ) {
                return chooseCharredStone(x, y, z);
            }
        }

        /*
         * Scattered top-down TNT-style pits. Their stepped depth varies per
         * column, producing jagged circular holes without any raised rim. The
         * final exposed floor is charred stone.
         */
        if (profile.secondaryBlastDepth > 0 && y <= groundY) {
            int pitFloorY = groundY - profile.secondaryBlastDepth;

            if (y > pitFloorY && !isBlastProtected(state)) {
                return Blocks.AIR.defaultBlockState();
            }

            if (y == pitFloorY && isTerrainFoundation(state)) {
                return chooseCharredStone(x, y, z);
            }
        }

        /*
         * The full inner charred zone is absolute rather than chance based.
         * This catches ores, gravel, clay and other solid terrain that are not
         * members of the vanilla base-stone tag, so the inside of very large
         * craters cannot retain random untouched patches.
         */
        if (
                profile.inStoneCore
                        && y <= groundY
                        && currentTerrainDepth < scaledStoneCoreDepth()
                        && state.getFluidState().isEmpty()
                        && !isBlastProtected(state)
        ) {
            return chooseCharredStone(x, y, z);
        }

        if (isFalloutBlock(state)) {
            return null;
        }

        if (isGiantMushroom(state) || isDestroyedProduce(state)) {
            return Blocks.AIR.defaultBlockState();
        }

        /* All glass and panes in the fallout pass are shattered. */
        if (isGlassLike(state)) {
            return Blocks.AIR.defaultBlockState();
        }

        if (state.is(BlockTags.LEAVES)) {
            if (profile.inTreeCutZone) {
                return Blocks.AIR.defaultBlockState();
            }
            return replaceLeaves(x, y, z, profile);
        }

        if (state.is(BlockTags.LOGS)) {
            if (profile.inTreeCutZone) {
                if (y > groundY + profile.allowedStumpHeight) {
                    return Blocks.AIR.defaultBlockState();
                }
                return copyLogAxis(
                        state,
                        DetonationBlocks.CHARRED_LOG.get().defaultBlockState()
                );
            }

            double chance = Math.clamp(
                    DetonationTuning.FalloutTerrain.LOG_CONVERSION_BASE_CHANCE
                            + profile.severity
                            * DetonationTuning.FalloutTerrain.LOG_CONVERSION_SEVERITY_BONUS
                            + profile.detailNoise * 0.08D,
                    0.0D,
                    1.0D
            );

            return random01(x, y, z, BLOCK_ROLL_SALT) <= chance
                    ? copyLogAxis(
                    state,
                    DetonationBlocks.CHARRED_LOG.get().defaultBlockState()
            )
                    : null;
        }

        if (
                y > groundY
                        && isPotentialBuildingBlock(state)
                        && shouldDestroyBuildingBlock(state, x, y, z, profile)
        ) {
            return Blocks.AIR.defaultBlockState();
        }

        if (state.is(BlockTags.WOODEN_STAIRS)) {
            return shouldConvertWood(x, y, z, profile)
                    ? copySharedProperties(
                    state,
                    DetonationBlocks.CHARRED_PLANK_STAIRS.get().defaultBlockState()
            )
                    : null;
        }

        if (state.is(BlockTags.WOODEN_SLABS)) {
            return shouldConvertWood(x, y, z, profile)
                    ? copySharedProperties(
                    state,
                    DetonationBlocks.CHARRED_PLANK_SLAB.get().defaultBlockState()
            )
                    : null;
        }

        if (state.is(BlockTags.PLANKS)) {
            return shouldConvertWood(x, y, z, profile)
                    ? DetonationBlocks.CHARRED_PLANKS.get().defaultBlockState()
                    : null;
        }

        if (isSurfaceVegetation(state)) {
            return Blocks.AIR.defaultBlockState();
        }

        if (isGrassSurface(state)) {
            double coverage = outerFalloutCoverage(x, z, profile);
            if (coverage <= 0.0D
                    || random01(x, y, z, OUTER_FADE_SALT) > coverage) {
                return null;
            }

            double charred = innerBandWeight(
                    profile.distanceRatio,
                    DetonationTuning.FalloutTerrain.CHARRED_SURFACE_END_RATIO
            );
            if (profile.inStoneCore
                    || random01(x, y, z, GROUND_CHAR_ROLL_SALT) < charred) {
                return chooseCharredStone(x, y, z);
            }

            return DetonationBlocks.RAD_GRASS.get().defaultBlockState();
        }

        if (isDirtLike(state)) {
            if (profile.inStoneCore) {
                return chooseCharredStone(x, y, z);
            }
            if (currentTerrainDepth >= DetonationTuning.FalloutTerrain.CHARRED_DIRT_DEPTH) {
                return null;
            }

            double coverage = innerBandWeight(
                    profile.distanceRatio,
                    DetonationTuning.FalloutTerrain.CHARRED_SOIL_END_RATIO
            );
            return random01(x, y, z, BAND_BLEND_SALT) < coverage
                    ? DetonationBlocks.CHARRED_DIRT.get().defaultBlockState()
                    : null;
        }

        if (isSandLike(state)) {
            if (profile.inStoneCore) {
                return chooseCharredStone(x, y, z);
            }
            if (currentTerrainDepth >= DetonationTuning.FalloutTerrain.CHARRED_SAND_DEPTH) {
                return null;
            }

            double coverage = innerBandWeight(
                    profile.distanceRatio,
                    DetonationTuning.FalloutTerrain.CHARRED_SOIL_END_RATIO
            );
            return random01(x, y, z, BAND_BLEND_SALT + 17L) < coverage
                    ? DetonationBlocks.CHARRED_SAND.get().defaultBlockState()
                    : null;
        }

        if (isStoneLike(state)) {
            if (profile.inStoneCore) {
                return chooseCharredStone(x, y, z);
            }
            if (currentTerrainDepth >= DetonationTuning.FalloutTerrain.SURFACE_STONE_DEPTH) {
                return null;
            }

            double coverage = innerBandWeight(
                    profile.distanceRatio,
                    DetonationTuning.FalloutTerrain.CHARRED_STONE_END_RATIO
            );
            return random01(x, y, z, BAND_BLEND_SALT + 31L) < coverage
                    ? chooseCharredStone(x, y, z)
                    : null;
        }

        return null;
    }

    private boolean shouldConvertWood(
            int x,
            int y,
            int z,
            ColumnProfile profile
    ) {
        double chance = Math.clamp(
                DetonationTuning.FalloutTerrain.PLANK_CONVERSION_BASE_CHANCE
                        + profile.severity
                        * DetonationTuning.FalloutTerrain.PLANK_CONVERSION_SEVERITY_BONUS
                        + profile.detailNoise * 0.08D,
                0.0D,
                1.0D
        );
        return random01(x, y, z, BLOCK_ROLL_SALT) <= chance;
    }

    private BlockState replaceLeaves(
            int x,
            int y,
            int z,
            ColumnProfile profile
    ) {
        double removalThreshold =
                DetonationTuning.FalloutTerrain.LEAF_FULL_REMOVAL_SEVERITY
                        - profile.broadNoise
                        * DetonationTuning.FalloutTerrain.LEAF_NOISE_STRENGTH;

        if (profile.severity >= removalThreshold) {
            return Blocks.AIR.defaultBlockState();
        }

        double removalChance = Math.clamp(
                DetonationTuning.FalloutTerrain.OUTER_LEAF_REMOVAL_BASE_CHANCE
                        + profile.severity * 0.75D,
                0.0D,
                1.0D
        );

        if (random01(x, y, z, BLOCK_ROLL_SALT) <= removalChance) {
            return Blocks.AIR.defaultBlockState();
        }

        double irradiatedChance = Math.clamp(
                0.35D + profile.severity * 1.10D,
                0.0D,
                1.0D
        );

        return random01(x, y, z, VARIANT_ROLL_SALT) <= irradiatedChance
                ? DetonationBlocks.RAD_LEAVES.get().defaultBlockState()
                : null;
    }

    private double innerBandWeight(
            double distanceRatio,
            double bandEndRatio
    ) {
        double halfBlend = DetonationTuning.FalloutTerrain.BAND_BLEND_WIDTH_RATIO * 0.5D;
        double inner = bandEndRatio - halfBlend;
        double outer = bandEndRatio + halfBlend;
        if (distanceRatio <= inner) {
            return 1.0D;
        }
        if (distanceRatio >= outer) {
            return 0.0D;
        }
        return 1.0D - smoothstep((distanceRatio - inner) / Math.max(0.0001D, outer - inner));
    }

    private double outerFalloutCoverage(
            int x,
            int z,
            ColumnProfile profile
    ) {
        double start = DetonationTuning.FalloutTerrain.OUTER_RAD_GRASS_FADE_START_RATIO;
        if (profile.distanceRatio <= start) {
            return 1.0D;
        }
        if (profile.distanceRatio >= 1.0D) {
            return 0.0D;
        }

        double radial = 1.0D - smoothstep(
                (profile.distanceRatio - start) / Math.max(0.0001D, 1.0D - start)
        );
        double edgeNoise = valueNoise(
                zone.effectId() ^ OUTER_FADE_SALT,
                x,
                z,
                DetonationTuning.FalloutTerrain.OUTER_RAD_GRASS_NOISE_SCALE
        ) * DetonationTuning.FalloutTerrain.OUTER_RAD_GRASS_NOISE_STRENGTH;

        return Math.clamp(radial + edgeNoise * radial * (1.0D - radial), 0.0D, 1.0D);
    }

    private BlockState chooseCharredStone(int x, int y, int z) {
        return random01(x, y, z, VARIANT_ROLL_SALT)
                < DetonationTuning.FalloutTerrain.CHARRED_STONE_0_RATIO
                ? DetonationBlocks.CHARRED_STONE_0.get().defaultBlockState()
                : DetonationBlocks.CHARRED_STONE_1.get().defaultBlockState();
    }

    private ColumnProfile profileColumn(int x, int z, int localGroundY) {
        double dx = x + 0.5D - zone.centre().getX();
        double dz = z + 0.5D - zone.centre().getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance > zone.falloutRadius()) {
            return ColumnProfile.OUTSIDE;
        }

        double broadNoise = valueNoise(
                zone.effectId() ^ BROAD_NOISE_SALT,
                x,
                z,
                DetonationTuning.FalloutTerrain.BROAD_NOISE_SCALE
        );
        double detailNoise = valueNoise(
                zone.effectId() ^ DETAIL_NOISE_SALT,
                x,
                z,
                DetonationTuning.FalloutTerrain.DETAIL_NOISE_SCALE
        );

        /* Fallout level boundaries are intentionally circular. */
        double distanceRatio = Math.clamp(
                distance / Math.max(1.0D, zone.falloutRadius()),
                0.0D,
                1.0D
        );

        double severity = smoothstep(
                Math.clamp(1.0D - distanceRatio, 0.0D, 1.0D)
        );

        double coreRadius = Math.max(
                zone.visualScale()
                        + DetonationTuning.FalloutTerrain.STONE_CORE_RADIUS_PADDING,
                zone.visualScale()
                        * DetonationTuning.FalloutTerrain.STONE_CORE_RADIUS_MULTIPLIER
        );

        double yieldScale = zone.visualScale()
                / DetonationTuning.FalloutTerrain.REFERENCE_CRATER_RADIUS_BLOCKS;
        double treeCutRadius = zone.visualScale()
                + DetonationTuning.FalloutTerrain.TREE_CUT_EDGE_DISTANCE_AT_REFERENCE
                * yieldScale;

        double blastClearRadius = Math.min(
                zone.visualScale()
                        * DetonationTuning.FalloutTerrain.BLAST_BOWL_CRATER_RADIUS_MULTIPLIER,
                zone.falloutRadius()
                        * DetonationTuning.FalloutTerrain.BLAST_BOWL_MAX_FALLOUT_RADIUS_RATIO
        );
        blastClearRadius = Math.max(
                zone.visualScale()
                        + DetonationTuning.FalloutTerrain.BLAST_BOWL_INNER_PROTECTION_PADDING
                        + 1.0D,
                blastClearRadius
        );

        double noisyBlastClearRadius = blastClearRadius
                * (1.0D + broadNoise
                * DetonationTuning.FalloutTerrain.BLAST_BOWL_RADIUS_NOISE_STRENGTH);
        double protectedInnerRadius = zone.visualScale()
                + DetonationTuning.FalloutTerrain.BLAST_BOWL_INNER_PROTECTION_PADDING;

        boolean inBlastClearZone = distance > protectedInnerRadius
                && distance <= noisyBlastClearRadius;
        int blastClearFloorY = Integer.MAX_VALUE;

        if (inBlastClearZone) {
            double progress = Math.clamp(
                    (distance - protectedInnerRadius)
                            / Math.max(
                            1.0D,
                            noisyBlastClearRadius - protectedInnerRadius
                    ),
                    0.0D,
                    1.0D
            );
            double curvedProgress = Math.pow(
                    progress,
                    DetonationTuning.FalloutTerrain.BLAST_BOWL_CURVE_POWER
            );
            double maximumRise = Math.min(
                    DetonationTuning.FalloutTerrain.BLAST_BOWL_MAX_RISE_BLOCKS,
                    blastClearRadius
                            * DetonationTuning.FalloutTerrain.BLAST_BOWL_MAX_RISE_RATIO
            );
            double verticalNoise = (broadNoise * 0.72D + detailNoise * 0.28D)
                    * blastClearRadius
                    * DetonationTuning.FalloutTerrain.BLAST_BOWL_VERTICAL_NOISE_RATIO;

            int absoluteBowlFloorY = (int) Math.round(
                    zone.centre().getY()
                            + DetonationTuning.FalloutTerrain.BLAST_CLEAR_ABOVE_RIM_OFFSET
                            + maximumRise * curvedProgress
                            + verticalNoise
            );

            int maximumLocalCutDepth = Math.clamp(
                    (int) Math.round(
                            zone.visualScale()
                                    * DetonationTuning.FalloutTerrain
                                    .BLAST_BOWL_LOCAL_CUT_DEPTH_RATIO
                    ),
                    DetonationTuning.FalloutTerrain
                            .BLAST_BOWL_LOCAL_CUT_DEPTH_MIN,
                    DetonationTuning.FalloutTerrain
                            .BLAST_BOWL_LOCAL_CUT_DEPTH_MAX
            );
            double localCutStrength = Math.pow(
                    1.0D - smoothstep(progress),
                    DetonationTuning.FalloutTerrain
                            .BLAST_BOWL_LOCAL_CUT_TAPER_POWER
            );
            int localSurfaceFloorY = localGroundY
                    - (int) Math.round(maximumLocalCutDepth * localCutStrength);

            /*
             * The absolute envelope still creates the broad curved bowl, while
             * the local floor prevents a mountain from being cut hundreds of
             * blocks down into a one-sided shell wall.
             */
            blastClearFloorY = Math.max(
                    absoluteBowlFloorY,
                    localSurfaceFloorY
            );
        }

        int secondaryBlastDepth = secondaryBlastDepthAt(x, z, distance);

        boolean inTreeCutZone = distance <= treeCutRadius;
        double stumpProgress = Math.clamp(
                (distance - zone.visualScale())
                        / Math.max(1.0D, treeCutRadius - zone.visualScale()),
                0.0D,
                1.0D
        );
        int allowedStumpHeight = (int) Math.round(
                DetonationTuning.FalloutTerrain.TREE_MAX_STUMP_HEIGHT
                        * Math.pow(
                        stumpProgress,
                        DetonationTuning.FalloutTerrain.TREE_STUMP_HEIGHT_POWER
                )
        );

        return new ColumnProfile(
                severity,
                distanceRatio,
                broadNoise,
                detailNoise,
                distance <= coreRadius,
                inTreeCutZone,
                allowedStumpHeight,
                inBlastClearZone,
                blastClearFloorY,
                secondaryBlastDepth
        );
    }

    private int secondaryBlastDepthAt(
            int x,
            int z,
            double columnDistance
    ) {
        double innerRadius = zone.visualScale()
                + DetonationTuning.FalloutTerrain.SECONDARY_BLAST_INNER_PADDING;

        if (
                columnDistance <= innerRadius
                        || columnDistance > zone.falloutRadius()
        ) {
            return 0;
        }

        int cellSize = DetonationTuning.FalloutTerrain.SECONDARY_BLAST_CELL_SIZE;
        int baseCellX = Math.floorDiv(x, cellSize);
        int baseCellZ = Math.floorDiv(z, cellSize);
        int deepest = 0;

        for (int cellOffsetX = -1; cellOffsetX <= 1; cellOffsetX++) {
            for (int cellOffsetZ = -1; cellOffsetZ <= 1; cellOffsetZ++) {
                int cellX = baseCellX + cellOffsetX;
                int cellZ = baseCellZ + cellOffsetZ;

                double centreX = cellX * (double) cellSize
                        + cellRandom01(cellX, cellZ, SECONDARY_BLAST_SALT + 1L)
                        * cellSize;
                double centreZ = cellZ * (double) cellSize
                        + cellRandom01(cellX, cellZ, SECONDARY_BLAST_SALT + 2L)
                        * cellSize;

                double blastDx = centreX - zone.centre().getX();
                double blastDz = centreZ - zone.centre().getZ();
                double blastDistance = Math.sqrt(
                        blastDx * blastDx + blastDz * blastDz
                );

                if (
                        blastDistance <= innerRadius
                                || blastDistance > zone.falloutRadius()
                ) {
                    continue;
                }

                double distanceProgress = Math.clamp(
                        (blastDistance - innerRadius)
                                / Math.max(1.0D, zone.falloutRadius() - innerRadius),
                        0.0D,
                        1.0D
                );

                double densityProgress = Math.pow(
                        distanceProgress,
                        DetonationTuning.FalloutTerrain.SECONDARY_BLAST_DENSITY_POWER
                );
                double spawnChance = lerp(
                        DetonationTuning.FalloutTerrain.SECONDARY_BLAST_NEAR_CELL_CHANCE,
                        DetonationTuning.FalloutTerrain.SECONDARY_BLAST_FAR_CELL_CHANCE,
                        densityProgress
                );

                if (
                        cellRandom01(cellX, cellZ, SECONDARY_BLAST_SALT + 3L)
                                > spawnChance
                ) {
                    continue;
                }

                double radius = lerp(
                        DetonationTuning.FalloutTerrain.SECONDARY_BLAST_NEAR_RADIUS,
                        DetonationTuning.FalloutTerrain.SECONDARY_BLAST_FAR_RADIUS,
                        distanceProgress
                );
                radius *= 1.0D
                        - DetonationTuning.FalloutTerrain.SECONDARY_BLAST_RADIUS_VARIATION * 0.5D
                        + cellRandom01(cellX, cellZ, SECONDARY_BLAST_SALT + 4L)
                        * DetonationTuning.FalloutTerrain.SECONDARY_BLAST_RADIUS_VARIATION;

                double angle = cellRandom01(
                        cellX,
                        cellZ,
                        SECONDARY_BLAST_SALT + 5L
                ) * Math.PI * 2.0D;
                double stretch = 0.78D
                        + cellRandom01(cellX, cellZ, SECONDARY_BLAST_SALT + 6L)
                        * 0.44D;

                double localX = x + 0.5D - centreX;
                double localZ = z + 0.5D - centreZ;
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double rotatedX = localX * cos + localZ * sin;
                double rotatedZ = -localX * sin + localZ * cos;

                double radiusX = radius * stretch;
                double radiusZ = radius / stretch;
                double shapeDistance =
                        rotatedX * rotatedX / (radiusX * radiusX)
                                + rotatedZ * rotatedZ / (radiusZ * radiusZ);

                double irregularity = (
                        random01(x, 0, z,
                                SECONDARY_BLAST_SALT
                                        ^ ((long) cellX << 32)
                                        ^ (cellZ & 0xFFFFFFFFL))
                                - 0.5D
                ) * 2.0D
                        * DetonationTuning.FalloutTerrain.SECONDARY_BLAST_IRREGULARITY;
                double edge = Math.max(0.55D, 1.0D + irregularity);

                if (shapeDistance > edge) {
                    continue;
                }

                double bowl = Math.sqrt(
                        Math.max(0.0D, 1.0D - shapeDistance / edge)
                );
                double maximumDepth = lerp(
                        DetonationTuning.FalloutTerrain.SECONDARY_BLAST_NEAR_DEPTH,
                        DetonationTuning.FalloutTerrain.SECONDARY_BLAST_FAR_DEPTH,
                        distanceProgress
                );
                maximumDepth *= 0.78D
                        + cellRandom01(cellX, cellZ, SECONDARY_BLAST_SALT + 7L)
                        * 0.44D;

                int depth = Math.max(
                        1,
                        (int) Math.round(maximumDepth * (0.28D + bowl * 0.72D))
                );
                deepest = Math.max(deepest, depth);
            }
        }

        return deepest;
    }

    private boolean shouldDestroyBuildingBlock(
            BlockState state,
            int x,
            int y,
            int z,
            ColumnProfile profile
    ) {
        if (!isPotentialBuildingBlock(state)) {
            return false;
        }

        double heightProgress = Math.clamp(
                (y - groundY)
                        / (double) Math.max(1, topY - groundY),
                0.0D,
                1.0D
        );
        double cluster = valueNoise(
                zone.effectId() ^ BUILDING_DAMAGE_SALT,
                x + y * 3,
                z - y * 2,
                DetonationTuning.FalloutTerrain.BUILDING_DAMAGE_CLUSTER_SCALE
        );

        double chance = Math.clamp(
                DetonationTuning.FalloutTerrain.BUILDING_DAMAGE_BASE_CHANCE
                        + profile.severity
                        * DetonationTuning.FalloutTerrain.BUILDING_DAMAGE_SEVERITY_BONUS
                        + heightProgress
                        * DetonationTuning.FalloutTerrain.BUILDING_DAMAGE_HEIGHT_BONUS
                        + cluster
                        * DetonationTuning.FalloutTerrain.BUILDING_DAMAGE_CLUSTER_STRENGTH,
                0.0D,
                0.96D
        );

        return random01(x, y, z, BUILDING_DAMAGE_SALT) <= chance;
    }

    private double cellRandom01(int cellX, int cellZ, long salt) {
        long value = zone.effectId() ^ salt;
        value ^= (long) cellX * 0x9E3779B97F4A7C15L;
        value ^= (long) cellZ * 0xC2B2AE3D27D4EB4FL;
        value = mix64(value);
        return (value >>> 11) * 0x1.0p-53;
    }

    private int scaledStoneCoreDepth() {
        return Math.clamp(
                (int) Math.round(
                        zone.visualScale()
                                * DetonationTuning.FalloutTerrain.STONE_CORE_DEPTH_MULTIPLIER
                                + DetonationTuning.FalloutTerrain.STONE_CORE_DEPTH_PADDING
                ),
                DetonationTuning.FalloutTerrain.MINIMUM_STONE_CORE_DEPTH,
                DetonationTuning.FalloutTerrain.MAXIMUM_STONE_CORE_DEPTH
        );
    }

    private static BlockState copyLogAxis(BlockState source, BlockState target) {
        if (
                source.hasProperty(RotatedPillarBlock.AXIS)
                        && target.hasProperty(RotatedPillarBlock.AXIS)
        ) {
            return target.setValue(
                    RotatedPillarBlock.AXIS,
                    source.getValue(RotatedPillarBlock.AXIS)
            );
        }
        return target;
    }

    private static boolean isBlastProtected(BlockState state) {
        return state.is(Blocks.BEDROCK)
                || state.is(Blocks.BARRIER)
                || state.is(Blocks.END_PORTAL_FRAME)
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.NETHER_PORTAL)
                || state.is(Blocks.COMMAND_BLOCK)
                || state.is(Blocks.CHAIN_COMMAND_BLOCK)
                || state.is(Blocks.REPEATING_COMMAND_BLOCK);
    }

    private static boolean isGiantMushroom(BlockState state) {
        return state.is(Blocks.RED_MUSHROOM_BLOCK)
                || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || state.is(Blocks.MUSHROOM_STEM);
    }

    private static boolean isDestroyedProduce(BlockState state) {
        return state.is(Blocks.MELON)
                || state.is(Blocks.PUMPKIN)
                || state.is(Blocks.CARVED_PUMPKIN)
                || state.is(Blocks.JACK_O_LANTERN)
                || state.is(Blocks.HAY_BLOCK)
                || state.is(Blocks.DRIED_KELP_BLOCK);
    }

    private static boolean isFalloutBlock(BlockState state) {
        return state.is(DetonationBlocks.CHARRED_STONE_0.get())
                || state.is(DetonationBlocks.CHARRED_STONE_1.get())
                || state.is(DetonationBlocks.CHARRED_DIRT.get())
                || state.is(DetonationBlocks.CHARRED_SAND.get())
                || state.is(DetonationBlocks.CHARRED_PLANKS.get())
                || state.is(DetonationBlocks.CHARRED_PLANK_STAIRS.get())
                || state.is(DetonationBlocks.CHARRED_PLANK_SLAB.get())
                || state.is(DetonationBlocks.RAD_GRASS.get())
                || state.is(DetonationBlocks.CHARRED_LOG.get())
                || state.is(DetonationBlocks.RAD_LEAVES.get());
    }

    private static boolean isGlassLike(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id.getPath();
        return path.contains("glass") || path.contains("window");
    }

    private static boolean isPotentialBuildingBlock(BlockState state) {
        if (
                state.isAir()
                        || !state.getFluidState().isEmpty()
                        || isBlastProtected(state)
                        || isNaturalTerrainFoundation(state)
                        || state.is(BlockTags.LEAVES)
                        || state.is(BlockTags.LOGS)
                        || isSurfaceVegetation(state)
                        || isGiantMushroom(state)
                        || isDestroyedProduce(state)
        ) {
            return false;
        }

        return !isFalloutBlock(state);
    }

    private static boolean isNaturalTerrainFoundation(BlockState state) {
        return isGrassSurface(state)
                || isDirtLike(state)
                || isSandLike(state)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(DetonationBlocks.RAD_GRASS.get())
                || state.is(DetonationBlocks.CHARRED_STONE_0.get())
                || state.is(DetonationBlocks.CHARRED_STONE_1.get())
                || state.is(DetonationBlocks.CHARRED_DIRT.get())
                || state.is(DetonationBlocks.CHARRED_SAND.get());
    }

    private static boolean isGrassSurface(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.MOSS_BLOCK);
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

    private static boolean isSandLike(BlockState state) {
        return state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND);
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

    private static boolean isTerrainFoundation(BlockState state) {
        return isGrassSurface(state)
                || isDirtLike(state)
                || isSandLike(state)
                || isStoneLike(state)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(DetonationBlocks.RAD_GRASS.get())
                || state.is(DetonationBlocks.CHARRED_STONE_0.get())
                || state.is(DetonationBlocks.CHARRED_STONE_1.get())
                || state.is(DetonationBlocks.CHARRED_DIRT.get())
                || state.is(DetonationBlocks.CHARRED_SAND.get());
    }

    private static boolean isSurfaceVegetation(BlockState state) {
        if (
                state.is(BlockTags.FLOWERS)
                        || state.is(BlockTags.SAPLINGS)
                        || state.is(Blocks.SNOW)
                        || state.is(Blocks.SNOW_BLOCK)
                        || state.is(Blocks.POWDER_SNOW)
                        || state.is(Blocks.SHORT_GRASS)
                        || state.is(Blocks.TALL_GRASS)
                        || state.is(Blocks.FERN)
                        || state.is(Blocks.LARGE_FERN)
                        || state.is(Blocks.DEAD_BUSH)
                        || state.is(Blocks.VINE)
                        || state.is(Blocks.GLOW_LICHEN)
                        || state.is(Blocks.MOSS_CARPET)
                        || state.is(Blocks.SMALL_DRIPLEAF)
                        || state.is(Blocks.BIG_DRIPLEAF)
                        || state.is(Blocks.BIG_DRIPLEAF_STEM)
                        || state.is(Blocks.CACTUS)
                        || state.is(Blocks.SUGAR_CANE)
                        || state.is(Blocks.BAMBOO)
                        || state.is(Blocks.KELP)
                        || state.is(Blocks.KELP_PLANT)
                        || state.is(Blocks.SEAGRASS)
                        || state.is(Blocks.TALL_SEAGRASS)
                        || state.is(Blocks.LILY_PAD)
        ) {
            return true;
        }

        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id.getPath();

        return path.equals("wheat")
                || path.equals("carrots")
                || path.equals("potatoes")
                || path.equals("beetroots")
                || path.equals("nether_wart")
                || path.equals("cocoa")
                || path.equals("sweet_berry_bush")
                || path.equals("torchflower_crop")
                || path.equals("pitcher_crop")
                || path.equals("pumpkin_stem")
                || path.equals("attached_pumpkin_stem")
                || path.equals("melon_stem")
                || path.equals("attached_melon_stem")
                || path.equals("hanging_roots")
                || path.contains("vine")
                || path.contains("azalea")
                || path.contains("flower")
                || path.endsWith("_crop")
                || path.endsWith("_bush")
                || path.endsWith("_roots")
                || path.endsWith("_fungus")
                || path.endsWith("_sprouts")
                || path.endsWith("_petals")
                || path.endsWith("_coral")
                || path.endsWith("_coral_fan");
    }

    private static boolean countsAsTerrainDepth(BlockState state) {
        return isNaturalTerrainFoundation(state);
    }

    private static BlockState copySharedProperties(
            BlockState source,
            BlockState target
    ) {
        BlockState result = target;
        for (Property<?> property : source.getProperties()) {
            if (result.hasProperty(property)) {
                result = copyProperty(source, result, property);
            }
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(
            BlockState source,
            BlockState target,
            Property<T> property
    ) {
        return target.setValue(property, source.getValue(property));
    }

    private double random01(int x, int y, int z, long salt) {
        long value = zone.effectId() ^ salt;
        value ^= (long) x * 0x9E3779B97F4A7C15L;
        value ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        value ^= (long) z * 0x165667B19E3779F9L;
        value = mix64(value);
        return (value >>> 11) * 0x1.0p-53;
    }

    private static double valueNoise(
            long seed,
            int x,
            int z,
            double scale
    ) {
        double sampleX = x / scale;
        double sampleZ = z / scale;
        int x0 = (int) Math.floor(sampleX);
        int z0 = (int) Math.floor(sampleZ);
        int x1 = x0 + 1;
        int z1 = z0 + 1;

        double tx = smoothstep(sampleX - x0);
        double tz = smoothstep(sampleZ - z0);

        double n00 = latticeNoise(seed, x0, z0);
        double n10 = latticeNoise(seed, x1, z0);
        double n01 = latticeNoise(seed, x0, z1);
        double n11 = latticeNoise(seed, x1, z1);

        double nx0 = lerp(n00, n10, tx);
        double nx1 = lerp(n01, n11, tx);
        return lerp(nx0, nx1, tz);
    }

    private static double latticeNoise(long seed, int x, int z) {
        long value = seed;
        value ^= (long) x * 0x9E3779B97F4A7C15L;
        value ^= (long) z * 0xC2B2AE3D27D4EB4FL;
        value = mix64(value);
        return ((value >>> 11) * 0x1.0p-52) - 1.0D;
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static double smoothstep(double value) {
        value = Math.clamp(value, 0.0D, 1.0D);
        return value * value * (3.0D - 2.0D * value);
    }

    private static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    record StepResult(int blockChecks, boolean complete) {
    }

    private record ColumnProfile(
            double severity,
            double distanceRatio,
            double broadNoise,
            double detailNoise,
            boolean inStoneCore,
            boolean inTreeCutZone,
            int allowedStumpHeight,
            boolean inBlastClearZone,
            int blastClearFloorY,
            int secondaryBlastDepth
    ) {
        private static final ColumnProfile OUTSIDE = new ColumnProfile(
                0.0D,
                1.0D,
                0.0D,
                0.0D,
                false,
                false,
                0,
                false,
                Integer.MAX_VALUE,
                0
        );
    }
}
