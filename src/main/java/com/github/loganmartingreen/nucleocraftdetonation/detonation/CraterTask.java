package com.github.loganmartingreen.nucleocraftdetonation.detonation;

import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Incrementally excavates a yield-scaled nuclear crater.
 * <p>
 * The crater is generated as overlapping horizontal waves:
 * the surface layer begins first, then one lower layer begins at a fixed delay.
 * Every layer expands from the detonation centre outwards.
 */
final class CraterTask {
    private static final int UPDATE_FLAGS =
            Block.UPDATE_CLIENTS
                    | Block.UPDATE_KNOWN_SHAPE
                    | Block.UPDATE_SUPPRESS_DROPS;

    private static final int UNPLANNED_COLUMN =
            Integer.MIN_VALUE;

    /**
     * All easily adjustable crater behaviour lives here.
     * <p>
     * Crater radius, depth, rim width and rim height remain controlled by
     * CraterDimensions.
     */
    private static final class Tuning {
        private Tuning() {
        }

        /*
         * ================================================================
         * ANIMATION
         * ================================================================
         */

        /**
         * Delay between each lower excavation layer starting.
         * <p>
         * Minecraft runs at 20 ticks per second:
         * 10 ticks = 0.5 seconds.
         */
        private static final int LAYER_START_INTERVAL_TICKS =
                DetonationTuning.CraterAnimation.LAYER_START_INTERVAL_TICKS;

        /**
         * Horizontal expansion speed of every excavation wave.
         * <p>
         * 5 blocks per tick = 100 blocks per second.
         */
        private static final double WAVE_SPEED_BLOCKS_PER_TICK =
                DetonationTuning.CraterAnimation.WAVE_SPEED_BLOCKS_PER_TICK;

        /*
         * ================================================================
         * RIM
         * ================================================================
         */

        /**
         * Maximum height difference from the crater's original central
         * surface where terrain may support the rim.
         * <p>
         * Lower this to stop rims appearing across uneven terrain.
         * Raise it to allow rims across hills.
         */
        private static final int RIM_SUPPORT_TOLERANCE = 6;

        /**
         * Maximum depth searched for a suitable rim material.
         */
        private static final int RIM_MATERIAL_SEARCH_DEPTH = 12;

        /**
         * Controls the rounded shape of the rim.
         * <p> <p>
         * Higher values produce a sharper, narrower crest.
         * Lower values produce a broader, rounder crest.
         */
        private static final double RIM_PROFILE_EXPONENT = 1.45D;

        /*
         * ================================================================
         * CRATER OUTLINE
         * ================================================================
         */

        /**
         * Maximum edge displacement as a proportion of crater radius.
         */
        private static final double EDGE_NOISE_RADIUS_FACTOR = 0.05D;

        private static final int EDGE_NOISE_MIN_BLOCKS = 2;
        private static final int EDGE_NOISE_MAX_BLOCKS = 10;

        /**
         * Higher values create larger, more obvious steps in the outline.
         */
        private static final double EDGE_QUANTISATION_STEP = 0.20D;

        /**
         * How much local terrain noise affects the crater outline.
         * <p>
         * Keep this fairly low so the crater remains recognisable.
         */
        private static final double EDGE_LOCAL_NOISE_WEIGHT = 0.18D;

        /*
         * ================================================================
         * FRACTURED GROUND OUTSIDE THE MAIN CRATER
         * ================================================================
         */

        private static final double FRACTURE_WIDTH_RADIUS_FACTOR = 0.06D;

        private static final int FRACTURE_WIDTH_MIN_BLOCKS = 3;
        private static final int FRACTURE_WIDTH_MAX_BLOCKS = 14;

        /**
         * Higher threshold means fewer chunks are removed outside the crater.
         */
        private static final double FRACTURE_DAMAGE_BASE_THRESHOLD = 0.42D;

        /**
         * Makes damage increasingly rare toward the outer fracture boundary.
         */
        private static final double FRACTURE_DAMAGE_OUTER_INCREASE = 0.52D;

        private static final double FRACTURE_DEPTH_BASE = 0.20D;
        private static final double FRACTURE_DEPTH_STRENGTH = 0.50D;

        /*
         * ================================================================
         * MAIN BOWL
         * ================================================================
         */

        /**
         * Controls the crater's general bowl profile.
         * <p>
         * Higher values flatten the centre and steepen the walls.
         * Lower values create a smoother, rounder bowl.
         */
        private static final double BOWL_PROFILE_EXPONENT = 1.05D;

        /*
         * ================================================================
         * WALL AND FLOOR NOISE
         * ================================================================
         */

        /**
         * Maximum vertical noise as a proportion of crater depth.
         */
        private static final double WALL_NOISE_DEPTH_FACTOR = 0.14D;

        private static final int WALL_NOISE_MIN_BLOCKS = 3;
        private static final int WALL_NOISE_MAX_BLOCKS = 16;

        /**
         * Noise strength around the central floor.
         */
        private static final double WALL_NOISE_CENTRE_STRENGTH = 0.12D;

        /**
         * Additional noise strength toward the crater walls.
         */
        private static final double WALL_NOISE_EDGE_STRENGTH = 0.38D;

        /**
         * Larger scales produce larger, less frequent terrain features.
         */
        private static final double TERRAIN_NOISE_BROAD_SCALE = 26.0D;
        private static final double TERRAIN_NOISE_MEDIUM_SCALE = 15.0D;
        private static final double TERRAIN_NOISE_DETAIL_SCALE = 8.0D;

        private static final double TERRAIN_NOISE_BROAD_WEIGHT = 0.68D;
        private static final double TERRAIN_NOISE_MEDIUM_WEIGHT = 0.24D;
        private static final double TERRAIN_NOISE_DETAIL_WEIGHT = 0.08D;

        /**
         * Higher values produce more obvious ledges but fewer height levels.
         */
        private static final double TERRAIN_TERRACE_STEP = 0.20D;

        /*
         * ================================================================
         * OCCASIONAL SHARP CUTS
         * ================================================================
         */

        private static final double RAVINE_BASE_DEPTH = 0.06D;
        private static final double RAVINE_WALL_DEPTH = 0.35D;

        private static final double RAVINE_WARP_SCALE = 42.0D;
        private static final double RAVINE_WARP_STRENGTH = 10.0D;

        private static final double RAVINE_PRIMARY_SCALE = 21.0D;
        private static final double RAVINE_SECONDARY_SCALE = 29.0D;

        /**
         * Raise these to make cuts rarer.
         */
        private static final double RAVINE_PRIMARY_START = 0.84D;
        private static final double RAVINE_PRIMARY_END = 0.97D;

        private static final double RAVINE_SECONDARY_START = 0.89D;
        private static final double RAVINE_SECONDARY_END = 0.98D;

        private static final double RAVINE_SECONDARY_WEIGHT = 0.50D;

        /**
         * Higher values make cuts narrower and sharper.
         */
        private static final double RAVINE_SHARPNESS = 1.85D;

        /*
         * ================================================================
         * RARE DEEP GOUGES
         * ================================================================
         */

        /**
         * More negative values make deep gouges rarer.
         */
        private static final double GOUGE_THRESHOLD = -0.78D;

        private static final double GOUGE_BASE_STRENGTH = 0.12D;
        private static final double GOUGE_WALL_STRENGTH = 0.35D;

        /*
         * Extra excavation layers reserved before every terrain column has
         * been examined. The value grows dynamically if taller terrain is
         * encountered.
         */
        private static final int INITIAL_LAYER_MARGIN = 4;
    }

    /*
     * Fixed deterministic noise salts.
     *
     * These are not gameplay configuration values and normally should not
     * be changed.
     */
    private static final long TERRAIN_SALT_A =
            0x3C6EF372FE94F82BL;

    private static final long TERRAIN_SALT_B =
            0xA54FF53A5F1D36F1L;

    private static final long TERRAIN_SALT_C =
            0x510E527FADE682D1L;

    private static final long WARP_SALT_X =
            0x9B05688C2B3E6C1FL;

    private static final long WARP_SALT_Z =
            0x1F83D9ABFB41BD6BL;

    private static final long RAVINE_SALT_A =
            0x5BE0CD19137E2179L;

    private static final long RAVINE_SALT_B =
            0xCBBB9D5DC1059ED8L;

    private final UUID owner;
    private final BlockPos centre;
    private final NuclearYield yield;
    private final CraterDimensions dimensions;

    private final long shapeSeed;

    private final double edgePhaseA;
    private final double edgePhaseB;
    private final double edgePhaseC;
    private final double edgePhaseD;

    /**
     * Only includes the crater bowl and fractured terrain.
     */
    private final long[] excavationOrder;

    /**
     * Includes the crater, fractured terrain and raised rim.
     */
    private final long[] rimOrder;

    /**
     * Original highest world block at each excavation position.
     * <p>
     * This may be water, a tree, a building or terrain.
     */
    private final int[] originalTopY;

    /**
     * Final terrain floor at each excavation position.
     */
    private final int[] targetFloorY;

    private final List<ExcavationWave> activeWaves =
            new ArrayList<>();

    private int referenceSurfaceY =
            UNPLANNED_COLUMN;

    private int elapsedTicks;
    private int nextLayerToStart;
    private int plannedLayerCount = 1;

    private int rimProcessingIndex;

    private boolean planningComplete;
    private boolean excavationComplete;
    private boolean complete;

    CraterTask(
            UUID owner,
            BlockPos centre,
            NuclearYield yield
    ) {
        this.owner = owner;
        this.centre = centre.immutable();
        this.yield = yield;
        this.dimensions =
                CraterDimensions.fromYield(yield);

        this.shapeSeed = mixSeed(
                centre,
                Double.doubleToLongBits(
                        yield.kilotonnes()
                )
        );

        this.edgePhaseA =
                phaseFromSeed(shapeSeed);

        this.edgePhaseB =
                phaseFromSeed(
                        Long.rotateLeft(
                                shapeSeed,
                                17
                        )
                );

        this.edgePhaseC =
                phaseFromSeed(
                        Long.rotateLeft(
                                shapeSeed,
                                37
                        )
                );

        this.edgePhaseD =
                phaseFromSeed(
                        Long.rotateLeft(
                                shapeSeed,
                                53
                        )
                );

        /*
         * Excavation waves do not waste time scanning the raised-rim band.
         */
        this.excavationOrder =
                buildProcessingOrder(
                        excavationRadius()
                );

        this.rimOrder =
                buildProcessingOrder(
                        totalRadius()
                );

        this.originalTopY =
                new int[excavationOrder.length];

        this.targetFloorY =
                new int[excavationOrder.length];

        Arrays.fill(
                originalTopY,
                UNPLANNED_COLUMN
        );

        Arrays.fill(
                targetFloorY,
                UNPLANNED_COLUMN
        );
    }

    UUID owner() {
        return owner;
    }

    NuclearYield yield() {
        return yield;
    }

    CraterDimensions dimensions() {
        return dimensions;
    }

    /**
     * Advances crater generation.
     *
     * @param level             server world
     * @param maximumOperations maximum terrain positions processed this tick
     * @return true when excavation and rim generation have finished
     */
    boolean tick(
            ServerLevel level,
            int maximumOperations
    ) {
        if (complete) {
            return true;
        }

        if (maximumOperations <= 0) {
            return false;
        }

        ensureReferenceSurface(level);

        elapsedTicks++;

        if (!excavationComplete) {
            startReadyExcavationWaves();

            processExcavationWaves(
                    level,
                    maximumOperations
            );

            if (
                    planningComplete
                            && nextLayerToStart
                            >= plannedLayerCount
                            && activeWaves.isEmpty()
            ) {
                excavationComplete = true;
            }

            return false;
        }

        processRim(
                level,
                maximumOperations
        );

        if (
                rimProcessingIndex
                        >= rimOrder.length
        ) {
            complete = true;
        }

        return complete;
    }

    /**
     * Finds the original terrain height at the detonation centre.
     */
    private void ensureReferenceSurface(
            ServerLevel level
    ) {
        if (
                referenceSurfaceY
                        != UNPLANNED_COLUMN
        ) {
            return;
        }

        ensureChunkLoaded(
                level,
                centre.getX(),
                centre.getZ()
        );

        int topY = getTopY(
                level,
                centre.getX(),
                centre.getZ()
        );

        referenceSurfaceY =
                findTerrainSurfaceY(
                        level,
                        centre.getX(),
                        centre.getZ(),
                        topY
                );

        /*
         * Fallback for void-like areas where no normal terrain exists.
         */
        if (
                referenceSurfaceY
                        < level.getMinBuildHeight()
        ) {
            referenceSurfaceY =
                    Math.clamp(
                            centre.getY(),
                            level.getMinBuildHeight(),
                            level.getMaxBuildHeight() - 1
                    );
        }

        int burialDepth = Math.max(
                0,
                referenceSurfaceY
                        - centre.getY()
        );

        plannedLayerCount = Math.max(
                1,
                burialDepth
                        + dimensions.depth()
                        + wallNoiseDepth()
                        + Tuning.INITIAL_LAYER_MARGIN
        );
    }

    /**
     * Starts each lower layer according to its scheduled delay.
     */
    private void startReadyExcavationWaves() {
        while (
                nextLayerToStart
                        < plannedLayerCount
        ) {
            int requiredStartTick =
                    1
                            + nextLayerToStart
                            * Tuning.LAYER_START_INTERVAL_TICKS;

            if (
                    elapsedTicks
                            < requiredStartTick
            ) {
                break;
            }

            activeWaves.add(
                    new ExcavationWave(
                            nextLayerToStart,
                            elapsedTicks
                    )
            );

            nextLayerToStart++;
        }
    }

    /**
     * Splits the current operation allowance between every active layer.
     */
    private void processExcavationWaves(
            ServerLevel level,
            int maximumOperations
    ) {
        int remainingOperations =
                maximumOperations;

        while (
                remainingOperations > 0
                        && !activeWaves.isEmpty()
        ) {
            int operationsPerWave =
                    Math.max(
                            1,
                            remainingOperations
                                    / activeWaves.size()
                    );

            boolean anyWaveProgressed = false;

            Iterator<ExcavationWave> iterator =
                    activeWaves.iterator();

            while (
                    iterator.hasNext()
                            && remainingOperations > 0
            ) {
                ExcavationWave wave =
                        iterator.next();

                int waveBudget =
                        Math.min(
                                operationsPerWave,
                                remainingOperations
                        );

                int usedOperations =
                        processExcavationWave(
                                level,
                                wave,
                                waveBudget
                        );

                remainingOperations -=
                        usedOperations;

                anyWaveProgressed |=
                        usedOperations > 0;

                if (wave.complete) {
                    /*
                     * Layer zero plans every terrain column before lower
                     * layers are allowed to pass it.
                     */
                    if (wave.depthLayer == 0) {
                        planningComplete = true;
                    }

                    iterator.remove();
                }
            }

            /*
             * Every wave has reached its current permitted radius or is
             * waiting for layer zero to plan more columns.
             */
            if (!anyWaveProgressed) {
                break;
            }
        }
    }

    /**
     * Processes one expanding horizontal layer.
     */
    private int processExcavationWave(
            ServerLevel level,
            ExcavationWave wave,
            int maximumOperations
    ) {
        int waveAgeTicks =
                elapsedTicks
                        - wave.startTick
                        + 1;

        double permittedRadius =
                Math.min(
                        excavationRadius(),
                        waveAgeTicks
                                * Tuning.WAVE_SPEED_BLOCKS_PER_TICK
                );

        long permittedDistanceSquared =
                (long) Math.floor(
                        permittedRadius
                                * permittedRadius
                );

        int usedOperations = 0;

        while (
                usedOperations
                        < maximumOperations
                        && wave.processingIndex
                        < excavationOrder.length
        ) {
            long packed =
                    excavationOrder[
                            wave.processingIndex
                            ];

            long distanceSquared =
                    unpackDistanceSquared(packed);

            /*
             * The processing list is sorted by distance, so everything after
             * this position is also outside the wave's current radius.
             */
            if (
                    distanceSquared
                            > permittedDistanceSquared
            ) {
                break;
            }

            boolean handled =
                    processExcavationLayerPosition(
                            level,
                            wave.processingIndex,
                            wave.depthLayer
                    );

            /*
             * A lower wave encountered terrain that layer zero has not
             * planned yet. It must wait instead of recording an incorrect
             * surface after upper blocks have already been removed.
             */
            if (!handled) {
                break;
            }

            wave.processingIndex++;
            usedOperations++;
        }

        if (
                wave.processingIndex
                        >= excavationOrder.length
        ) {
            wave.complete = true;
        }

        return usedOperations;
    }

    /**
     * Removes one block from one vertical excavation layer.
     *
     * @return false only when a lower wave must wait for layer zero
     */
    private boolean processExcavationLayerPosition(
            ServerLevel level,
            int processingPosition,
            int depthLayer
    ) {
        long packed =
                excavationOrder[processingPosition];

        int offsetX =
                unpackOffsetX(packed);

        int offsetZ =
                unpackOffsetZ(packed);

        int worldX =
                centre.getX() + offsetX;

        int worldZ =
                centre.getZ() + offsetZ;

        /*
         * This guarantees that large detonations affect chunks outside the
         * player's current simulation distance.
         */
        ensureChunkLoaded(
                level,
                worldX,
                worldZ
        );

        double distance =
                Math.hypot(
                        offsetX,
                        offsetZ
                );

        double angle =
                Math.atan2(
                        offsetZ,
                        offsetX
                );

        double craterEdge =
                noisyCraterRadius(
                        angle,
                        worldX,
                        worldZ
                );

        double fractureEdge =
                craterEdge
                        + fractureWidth();

        /*
         * This position is inside the maximum processing circle but outside
         * the actual irregular crater and fractured boundary.
         */
        if (distance > fractureEdge) {
            return true;
        }

        if (
                originalTopY[processingPosition]
                        == UNPLANNED_COLUMN
        ) {
            if (depthLayer != 0) {
                return false;
            }

            planColumn(
                    level,
                    processingPosition,
                    worldX,
                    worldZ,
                    distance,
                    craterEdge,
                    fractureEdge
            );
        }

        int topY =
                originalTopY[processingPosition];

        int floorY =
                targetFloorY[processingPosition];

        int blockY =
                topY - depthLayer;

        /*
         * The final floor block remains in place.
         */
        if (
                blockY <= floorY
                        || blockY
                        < level.getMinBuildHeight()
                        || blockY
                        >= level.getMaxBuildHeight()
        ) {
            return true;
        }

        BlockPos position =
                new BlockPos(
                        worldX,
                        blockY,
                        worldZ
                );

        BlockState state =
                level.getBlockState(position);

        if (
                !state.isAir()
                        && !state.is(Blocks.BEDROCK)
        ) {
            level.setBlock(
                    position,
                    Blocks.AIR.defaultBlockState(),
                    UPDATE_FLAGS
            );
        }

        return true;
    }

    /**
     * Records the original top block and final floor for a terrain column.
     * <p>
     * The top may be a tree, building or water surface. The crater floor is
     * based on the actual terrain beneath those blocks.
     */
    private void planColumn(
            ServerLevel level,
            int processingPosition,
            int worldX,
            int worldZ,
            double distance,
            double craterEdge,
            double fractureEdge
    ) {
        int topY =
                getTopY(
                        level,
                        worldX,
                        worldZ
                );

        int terrainSurfaceY =
                findTerrainSurfaceY(
                        level,
                        worldX,
                        worldZ,
                        topY
                );

        originalTopY[processingPosition] =
                topY;

        if (
                terrainSurfaceY
                        < level.getMinBuildHeight()
        ) {
            targetFloorY[processingPosition] =
                    topY;

            return;
        }

        int floorY =
                terrainSurfaceY;

        if (distance <= craterEdge) {
            floorY =
                    calculateCraterFloorY(
                            level,
                            worldX,
                            worldZ,
                            terrainSurfaceY,
                            distance,
                            craterEdge
                    );
        } else if (
                distance <= fractureEdge
        ) {
            floorY =
                    calculateFractureFloorY(
                            level,
                            worldX,
                            worldZ,
                            terrainSurfaceY,
                            distance,
                            craterEdge
                    );
        }

        targetFloorY[processingPosition] =
                floorY;

        /*
         * Expand the number of required waves if a tall structure, tree,
         * hill or deep local cut requires more vertical layers.
         */
        plannedLayerCount =
                Math.max(
                        plannedLayerCount,
                        Math.max(
                                0,
                                topY - floorY
                        )
                );
    }

    /**
     * Calculates the finished floor inside the main crater.
     */
    private int calculateCraterFloorY(
            ServerLevel level,
            int worldX,
            int worldZ,
            int terrainSurfaceY,
            double distance,
            double craterEdge
    ) {
        double normalisedDistance =
                Math.clamp(
                        distance / craterEdge,
                        0.0D,
                        1.0D
                );

        double targetFloor = getTargetFloor(level, terrainSurfaceY, normalisedDistance);

        double wallStrength =
                smoothstep(
                        0.20D,
                        1.0D,
                        normalisedDistance
                );

        double terracedNoise =
                sharpTerracedTerrainNoise(
                        worldX,
                        worldZ
                );

        double noiseStrength =
                Tuning.WALL_NOISE_CENTRE_STRENGTH
                        + wallStrength
                        * Tuning.WALL_NOISE_EDGE_STRENGTH;

        targetFloor +=
                terracedNoise
                        * wallNoiseDepth()
                        * noiseStrength;

        double ravineStrength =
                ravineNoise(
                        worldX,
                        worldZ
                );

        double ravineDepthMultiplier =
                Tuning.RAVINE_BASE_DEPTH
                        + wallStrength
                        * Tuning.RAVINE_WALL_DEPTH;

        targetFloor -=
                ravineStrength
                        * wallNoiseDepth()
                        * ravineDepthMultiplier;

        /*
         * Very low terrain-noise values occasionally remove a deeper,
         * sharply bounded section.
         */
        if (
                terracedNoise
                        < Tuning.GOUGE_THRESHOLD
        ) {
            double gougeStrength =
                    Math.clamp(
                            (
                                    Tuning.GOUGE_THRESHOLD
                                            - terracedNoise
                            ) / (
                                    1.0D
                                            + Tuning.GOUGE_THRESHOLD
                            ),
                            0.0D,
                            1.0D
                    );

            targetFloor -=
                    wallNoiseDepth()
                            * gougeStrength
                            * (
                            Tuning.GOUGE_BASE_STRENGTH
                                    + wallStrength
                                    * Tuning.GOUGE_WALL_STRENGTH
                    );
        }

        return Math.clamp(
                (int) Math.round(targetFloor),
                level.getMinBuildHeight(),
                terrainSurfaceY
        );
    }

    private double getTargetFloor(ServerLevel level, int terrainSurfaceY, double normalisedDistance) {
        int deepestFloorY =
                Math.max(
                        level.getMinBuildHeight(),
                        centre.getY()
                                - dimensions.depth()
                );

        double bowlStrength =
                Math.pow(
                        1.0D
                                - normalisedDistance
                                * normalisedDistance,
                        Tuning.BOWL_PROFILE_EXPONENT
                );

        return terrainSurfaceY
                + (
                deepestFloorY
                        - terrainSurfaceY
        ) * bowlStrength;
    }

    /**
     * Calculates scattered damage outside the main crater bowl.
     */
    private int calculateFractureFloorY(
            ServerLevel level,
            int worldX,
            int worldZ,
            int terrainSurfaceY,
            double distance,
            double craterEdge
    ) {
        double fractureProgress =
                Math.clamp(
                        (
                                distance - craterEdge
                        ) / fractureWidth(),
                        0.0D,
                        1.0D
                );

        double terracedNoise =
                sharpTerracedTerrainNoise(
                        worldX,
                        worldZ
                );

        double ravineStrength =
                ravineNoise(
                        worldX,
                        worldZ
                );

        double chunkDamage =
                Math.max(
                        0.0D,
                        -terracedNoise
                );

        double damageSignal =
                Math.max(
                        ravineStrength,
                        chunkDamage
                );

        double requiredDamage =
                Tuning.FRACTURE_DAMAGE_BASE_THRESHOLD
                        + fractureProgress
                        * Tuning.FRACTURE_DAMAGE_OUTER_INCREASE;

        if (
                damageSignal
                        <= requiredDamage
        ) {
            return terrainSurfaceY;
        }

        double damageStrength =
                Math.clamp(
                        (
                                damageSignal
                                        - requiredDamage
                        ) / (
                                1.0D
                                        - requiredDamage
                        ),
                        0.0D,
                        1.0D
                );

        double inwardStrength =
                Math.pow(
                        1.0D
                                - fractureProgress,
                        0.65D
                );

        int cutDepth =
                Math.max(
                        1,
                        (int) Math.round(
                                wallNoiseDepth()
                                        * inwardStrength
                                        * (
                                        Tuning.FRACTURE_DEPTH_BASE
                                                + damageStrength
                                                * Tuning.FRACTURE_DEPTH_STRENGTH
                                )
                        )
                );

        return Math.max(
                level.getMinBuildHeight(),
                terrainSurfaceY - cutDepth
        );
    }

    /**
     * Generates the raised rim after every excavation layer has finished.
     */
    private void processRim(
            ServerLevel level,
            int maximumOperations
    ) {
        int usedOperations = 0;

        while (
                usedOperations < maximumOperations
                        && rimProcessingIndex
                        < rimOrder.length
        ) {
            long packed =
                    rimOrder[rimProcessingIndex++];

            int offsetX =
                    unpackOffsetX(packed);

            int offsetZ =
                    unpackOffsetZ(packed);

            int worldX =
                    centre.getX() + offsetX;

            int worldZ =
                    centre.getZ() + offsetZ;

            double distance =
                    Math.hypot(
                            offsetX,
                            offsetZ
                    );

            double angle =
                    Math.atan2(
                            offsetZ,
                            offsetX
                    );

            double craterEdge =
                    noisyCraterRadius(
                            angle,
                            worldX,
                            worldZ
                    );


            double rimEnd =
                    craterEdge
                            + dimensions.rimWidth();

            if (
                    distance > craterEdge
                            && distance <= rimEnd
            ) {
                ensureChunkLoaded(
                        level,
                        worldX,
                        worldZ
                );

                buildRimColumn(
                        level,
                        worldX,
                        worldZ,
                        distance,
                        craterEdge
                );
            }

            usedOperations++;
        }
    }

    /**
     * Builds one raised-rim terrain column.
     */
    private void buildRimColumn(
            ServerLevel level,
            int worldX,
            int worldZ,
            double distance,
            double rimStart
    ) {
        int supportY =
                findRimSupportY(
                        level,
                        worldX,
                        worldZ
                );

        /*
         * No valid terrain exists here, so do not generate a floating or
         * internal rim.
         */
        if (
                supportY
                        == UNPLANNED_COLUMN
        ) {
            return;
        }

        int addedHeight = getAddedHeight(distance, rimStart);

        if (addedHeight <= 0) {
            return;
        }

        BlockState rimMaterial =
                findRimMaterial(
                        level,
                        worldX,
                        worldZ,
                        supportY
                );

        if (rimMaterial == null) {
            return;
        }

        int maximumY =
                Math.min(
                        level.getMaxBuildHeight() - 1,
                        supportY + addedHeight
                );

        BlockPos.MutableBlockPos cursor =
                new BlockPos.MutableBlockPos(
                        worldX,
                        supportY,
                        worldZ
                );

        for (
                int y = supportY + 1;
                y <= maximumY;
                y++
        ) {
            cursor.setY(y);

            /*
             * The rim may only fill existing air.
             *
             * It will not replace a structure, another rim or surviving
             * terrain.
             */
            if (
                    !level.getBlockState(cursor)
                            .isAir()
            ) {
                break;
            }

            level.setBlock(
                    cursor,
                    rimMaterial,
                    UPDATE_FLAGS
            );
        }
    }

    private int getAddedHeight(double distance, double rimStart) {
        double rimProgress =
                Math.clamp(
                        (
                                distance - rimStart
                        ) / dimensions.rimWidth(),
                        0.0D,
                        1.0D
                );

        double rimProfile =
                Math.pow(
                        Math.sin(
                                Math.PI
                                        * rimProgress
                        ),
                        Tuning.RIM_PROFILE_EXPONENT
                );

        return (int) Math.round(
                dimensions.rimHeight()
                        * rimProfile
        );
    }

    /**
     * Finds terrain close enough to the original central surface to support
     * the rim.
     * <p>
     * This prevents an overlapping crater's deep floor from becoming the
     * base of a new floating or internal rim.
     */
    private int findRimSupportY(
            ServerLevel level,
            int worldX,
            int worldZ
    ) {
        int searchTop =
                Math.min(
                        level.getMaxBuildHeight() - 1,
                        referenceSurfaceY
                                + Tuning.RIM_SUPPORT_TOLERANCE
                );

        int searchBottom =
                Math.max(
                        level.getMinBuildHeight(),
                        referenceSurfaceY
                                - Tuning.RIM_SUPPORT_TOLERANCE
                );

        BlockPos.MutableBlockPos cursor =
                new BlockPos.MutableBlockPos(
                        worldX,
                        searchTop,
                        worldZ
                );

        for (
                int y = searchTop;
                y >= searchBottom;
                y--
        ) {
            cursor.setY(y);

            BlockState state =
                    level.getBlockState(cursor);

            if (isUsableTerrainBlock(state)) {
                return y;
            }
        }

        return UNPLANNED_COLUMN;
    }

    /**
     * Samples an appropriate local material for the rim.
     */
    private BlockState findRimMaterial(
            ServerLevel level,
            int worldX,
            int worldZ,
            int supportY
    ) {
        int minimumY =
                Math.max(
                        level.getMinBuildHeight(),
                        supportY
                                - Tuning.RIM_MATERIAL_SEARCH_DEPTH
                );

        BlockPos.MutableBlockPos cursor =
                new BlockPos.MutableBlockPos(
                        worldX,
                        supportY,
                        worldZ
                );

        for (
                int y = supportY;
                y >= minimumY;
                y--
        ) {
            cursor.setY(y);

            BlockState state =
                    level.getBlockState(cursor);

            if (!isUsableTerrainBlock(state)) {
                continue;
            }

            /*
             * Avoid stacking grass-covered blocks throughout the rim.
             */
            if (
                    state.is(Blocks.GRASS_BLOCK)
                            || state.is(Blocks.PODZOL)
                            || state.is(Blocks.MYCELIUM)
            ) {
                return Blocks.DIRT
                        .defaultBlockState();
            }

            return state
                    .getBlock()
                    .defaultBlockState();
        }

        return null;
    }

    /**
     * Returns the highest world-surface block, including water, trees and
     * structures.
     */
    private int getTopY(
            ServerLevel level,
            int worldX,
            int worldZ
    ) {
        return Math.clamp(
                level.getHeight(
                        Heightmap.Types.WORLD_SURFACE,
                        worldX,
                        worldZ
                ) - 1,
                level.getMinBuildHeight() - 1,
                level.getMaxBuildHeight() - 1
        );
    }

    /**
     * Searches beneath water, trees and wooden structures for ordinary
     * terrain.
     */
    private int findTerrainSurfaceY(
            ServerLevel level,
            int worldX,
            int worldZ,
            int startY
    ) {
        if (
                startY
                        < level.getMinBuildHeight()
        ) {
            return startY;
        }

        BlockPos.MutableBlockPos cursor =
                new BlockPos.MutableBlockPos(
                        worldX,
                        startY,
                        worldZ
                );

        for (
                int y = startY;
                y >= level.getMinBuildHeight();
                y--
        ) {
            cursor.setY(y);

            BlockState state =
                    level.getBlockState(cursor);

            if (isUsableTerrainBlock(state)) {
                return y;
            }
        }

        return level.getMinBuildHeight() - 1;
    }

    /**
     * Returns true for blocks that may behave as ordinary terrain.
     */
    private static boolean isUsableTerrainBlock(
            BlockState state
    ) {
        return !state.isAir()
                && state.getFluidState()
                .isEmpty()
                && !state.is(BlockTags.LEAVES)
                && !state.is(BlockTags.LOGS)
                && !state.is(BlockTags.PLANKS)
                && !state.is(Blocks.SNOW)
                && !state.is(Blocks.BEDROCK);
    }

    /**
     * Calculates the irregular crater outline.
     */
    private double noisyCraterRadius(
            double angle,
            int worldX,
            int worldZ
    ) {
        double angularNoise =
                0.44D
                        * sharpWave(
                        angle * 5.0D
                                + edgePhaseA
                )
                        + 0.28D
                        * sharpWave(
                        angle * 11.0D
                                + edgePhaseB
                )
                        + 0.18D
                        * sharpWave(
                        angle * 23.0D
                                + edgePhaseC
                )
                        + 0.10D
                        * sharpWave(
                        angle * 37.0D
                                + edgePhaseD
                );

        angularNoise =
                quantise(
                        angularNoise,
                        Tuning.EDGE_QUANTISATION_STEP
                );

        double localNoise =
                sharpTerracedTerrainNoise(
                        worldX,
                        worldZ
                );

        double combinedNoise =
                angularNoise
                        * (
                        1.0D
                                - Tuning.EDGE_LOCAL_NOISE_WEIGHT
                )
                        + localNoise
                        * Tuning.EDGE_LOCAL_NOISE_WEIGHT;

        return dimensions.radius()
                + edgeNoiseAmount()
                * Math.clamp(
                combinedNoise,
                -1.0D,
                1.0D
        );
    }

    /**
     * Produces broad connected regions with sharp height transitions.
     */
    private double sharpTerracedTerrainNoise(
            int worldX,
            int worldZ
    ) {
        double broad =
                valueNoise(
                        worldX,
                        worldZ,
                        Tuning.TERRAIN_NOISE_BROAD_SCALE,
                        TERRAIN_SALT_A
                );

        double medium =
                valueNoise(
                        worldX,
                        worldZ,
                        Tuning.TERRAIN_NOISE_MEDIUM_SCALE,
                        TERRAIN_SALT_B
                );

        double detail =
                valueNoise(
                        worldX,
                        worldZ,
                        Tuning.TERRAIN_NOISE_DETAIL_SCALE,
                        TERRAIN_SALT_C
                );

        double combined =
                broad
                        * Tuning.TERRAIN_NOISE_BROAD_WEIGHT
                        + medium
                        * Tuning.TERRAIN_NOISE_MEDIUM_WEIGHT
                        + detail
                        * Tuning.TERRAIN_NOISE_DETAIL_WEIGHT;

        return Math.clamp(
                quantise(
                        combined,
                        Tuning.TERRAIN_TERRACE_STEP
                ),
                -1.0D,
                1.0D
        );
    }

    /**
     * Produces occasional narrow, meandering cuts.
     */
    private double ravineNoise(
            int worldX,
            int worldZ
    ) {
        double warpX =
                valueNoise(
                        worldX,
                        worldZ,
                        Tuning.RAVINE_WARP_SCALE,
                        WARP_SALT_X
                )
                        * Tuning.RAVINE_WARP_STRENGTH;

        double warpZ =
                valueNoise(
                        worldX,
                        worldZ,
                        Tuning.RAVINE_WARP_SCALE,
                        WARP_SALT_Z
                )
                        * Tuning.RAVINE_WARP_STRENGTH;

        double warpedX =
                worldX + warpX;

        double warpedZ =
                worldZ + warpZ;

        double fieldA =
                valueNoise(
                        warpedX,
                        warpedZ,
                        Tuning.RAVINE_PRIMARY_SCALE,
                        RAVINE_SALT_A
                );

        double fieldB =
                valueNoise(
                        warpedX + 31.7D,
                        warpedZ - 19.4D,
                        Tuning.RAVINE_SECONDARY_SCALE,
                        RAVINE_SALT_B
                );

        double ridgeA =
                smoothstep(
                        Tuning.RAVINE_PRIMARY_START,
                        Tuning.RAVINE_PRIMARY_END,
                        1.0D - Math.abs(fieldA)
                );

        double ridgeB =
                smoothstep(
                        Tuning.RAVINE_SECONDARY_START,
                        Tuning.RAVINE_SECONDARY_END,
                        1.0D - Math.abs(fieldB)
                )
                        * Tuning.RAVINE_SECONDARY_WEIGHT;

        return Math.pow(
                Math.max(
                        ridgeA,
                        ridgeB
                ),
                Tuning.RAVINE_SHARPNESS
        );
    }

    /**
     * Smooth deterministic two-dimensional value noise.
     */
    private double valueNoise(
            double worldX,
            double worldZ,
            double cellSize,
            long salt
    ) {
        double scaledX =
                worldX / cellSize;

        double scaledZ =
                worldZ / cellSize;

        int cellX =
                fastFloor(scaledX);

        int cellZ =
                fastFloor(scaledZ);

        double localX =
                scaledX - cellX;

        double localZ =
                scaledZ - cellZ;

        double smoothX =
                smootherStep(localX);

        double smoothZ =
                smootherStep(localZ);

        double bottomLeft =
                hashedNoise(
                        cellX,
                        cellZ,
                        salt
                );

        double bottomRight =
                hashedNoise(
                        cellX + 1,
                        cellZ,
                        salt
                );

        double topLeft =
                hashedNoise(
                        cellX,
                        cellZ + 1,
                        salt
                );

        double topRight =
                hashedNoise(
                        cellX + 1,
                        cellZ + 1,
                        salt
                );

        double bottom =
                lerp(
                        bottomLeft,
                        bottomRight,
                        smoothX
                );

        double top =
                lerp(
                        topLeft,
                        topRight,
                        smoothX
                );

        return lerp(
                bottom,
                top,
                smoothZ
        );
    }

    private double hashedNoise(
            int cellX,
            int cellZ,
            long salt
    ) {
        long value =
                shapeSeed ^ salt;

        value ^=
                (long) cellX
                        * 0x632BE59BD9B4E019L;

        value =
                Long.rotateLeft(
                        value,
                        21
                );

        value ^=
                (long) cellZ
                        * 0x9E3779B185EBCA87L;

        value =
                mix64(value);

        double unit =
                (value >>> 11)
                        * 0x1.0p-53;

        return unit
                * 2.0D
                - 1.0D;
    }

    private int edgeNoiseAmount() {
        return Math.clamp(
                (int) Math.round(
                        dimensions.radius()
                                * Tuning.EDGE_NOISE_RADIUS_FACTOR
                ),
                Tuning.EDGE_NOISE_MIN_BLOCKS,
                Tuning.EDGE_NOISE_MAX_BLOCKS
        );
    }

    private int fractureWidth() {
        return Math.clamp(
                (int) Math.round(
                        dimensions.radius()
                                * Tuning.FRACTURE_WIDTH_RADIUS_FACTOR
                ),
                Tuning.FRACTURE_WIDTH_MIN_BLOCKS,
                Tuning.FRACTURE_WIDTH_MAX_BLOCKS
        );
    }

    private int wallNoiseDepth() {
        return Math.clamp(
                (int) Math.round(
                        dimensions.depth()
                                * Tuning.WALL_NOISE_DEPTH_FACTOR
                ),
                Tuning.WALL_NOISE_MIN_BLOCKS,
                Tuning.WALL_NOISE_MAX_BLOCKS
        );
    }

    private int excavationRadius() {
        return dimensions.radius()
                + edgeNoiseAmount()
                + fractureWidth();
    }

    private int totalRadius() {
        return excavationRadius()
                + dimensions.rimWidth();
    }

    private static void ensureChunkLoaded(
            ServerLevel level,
            int worldX,
            int worldZ
    ) {
        level.getChunk(
                worldX >> 4,
                worldZ >> 4
        );
    }

    /**
     * Builds a position list sorted from the centre outward.
     */
    private static long[] buildProcessingOrder(
            int radius
    ) {
        int diameter =
                radius * 2 + 1;

        long[] temporary =
                new long[
                        diameter * diameter
                        ];

        int count = 0;

        long maximumDistanceSquared =
                (long) radius * radius;

        for (
                int offsetX = -radius;
                offsetX <= radius;
                offsetX++
        ) {
            for (
                    int offsetZ = -radius;
                    offsetZ <= radius;
                    offsetZ++
            ) {
                long distanceSquared =
                        (long) offsetX
                                * offsetX
                                + (long) offsetZ
                                * offsetZ;

                if (
                        distanceSquared
                                > maximumDistanceSquared
                ) {
                    continue;
                }

                long encodedOffsets =
                        (
                                (long) (
                                        offsetX + 32768
                                ) << 16
                        )
                                | (
                                (
                                        offsetZ + 32768
                                ) & 0xFFFFL
                        );

                temporary[count++] =
                        (
                                distanceSquared << 32
                        ) | encodedOffsets;
            }
        }

        long[] result =
                Arrays.copyOf(
                        temporary,
                        count
                );

        /*
         * Distance is stored in the upper bits, so sorting places the
         * nearest positions first.
         */
        Arrays.sort(result);

        return result;
    }

    private static long unpackDistanceSquared(
            long packed
    ) {
        return packed >>> 32;
    }

    private static int unpackOffsetX(
            long packed
    ) {
        return (int) (
                (
                        packed >>> 16
                ) & 0xFFFFL
        ) - 32768;
    }

    private static int unpackOffsetZ(
            long packed
    ) {
        return (int) (
                packed & 0xFFFFL
        ) - 32768;
    }

    private static double sharpWave(
            double value
    ) {
        double wave =
                Math.sin(value);

        return Math.copySign(
                Math.pow(
                        Math.abs(wave),
                        0.28D
                ),
                wave
        );
    }

    private static double quantise(
            double value,
            double step
    ) {
        return Math.rint(
                value / step
        ) * step;
    }

    private static int fastFloor(
            double value
    ) {
        int integer =
                (int) value;

        return value < integer
                ? integer - 1
                : integer;
    }

    private static double lerp(
            double start,
            double end,
            double progress
    ) {
        return start
                + (
                end - start
        ) * progress;
    }

    private static double smootherStep(
            double value
    ) {
        value =
                Math.clamp(
                        value,
                        0.0D,
                        1.0D
                );

        return value
                * value
                * value
                * (
                value
                        * (
                        value * 6.0D
                                - 15.0D
                )
                        + 10.0D
        );
    }

    private static double smoothstep(
            double minimum,
            double maximum,
            double value
    ) {
        if (maximum <= minimum) {
            return value >= maximum
                    ? 1.0D
                    : 0.0D;
        }

        double progress =
                Math.clamp(
                        (
                                value - minimum
                        ) / (
                                maximum - minimum
                        ),
                        0.0D,
                        1.0D
                );

        return progress
                * progress
                * (
                3.0D
                        - 2.0D
                        * progress
        );
    }

    private static long mixSeed(
            BlockPos position,
            long extra
    ) {
        long value =
                0x9E3779B97F4A7C15L;

        value ^=
                (long) position.getX()
                        * 0x632BE59BD9B4E019L;

        value =
                Long.rotateLeft(
                        value,
                        17
                );

        value ^=
                (long) position.getY()
                        * 0x9E3779B185EBCA87L;

        value =
                Long.rotateLeft(
                        value,
                        23
                );

        value ^=
                (long) position.getZ()
                        * 0xC2B2AE3D27D4EB4FL;

        value ^= extra;

        return mix64(value);
    }

    private static long mix64(
            long value
    ) {
        value ^= value >>> 30;

        value *=
                0xBF58476D1CE4E5B9L;

        value ^= value >>> 27;

        value *=
                0x94D049BB133111EBL;

        value ^= value >>> 31;

        return value;
    }

    private static double phaseFromSeed(
            long seed
    ) {
        long positive =
                seed & Long.MAX_VALUE;

        double unit =
                positive
                        / (double) Long.MAX_VALUE;

        return unit
                * Math.PI
                * 2.0D;
    }

    /**
     * State for one expanding horizontal excavation layer.
     */
    private static final class ExcavationWave {
        private final int depthLayer;
        private final int startTick;

        private int processingIndex;
        private boolean complete;

        private ExcavationWave(
                int depthLayer,
                int startTick
        ) {
            this.depthLayer = depthLayer;
            this.startTick = startTick;
        }
    }
}