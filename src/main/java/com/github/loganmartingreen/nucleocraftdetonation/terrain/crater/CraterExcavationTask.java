package com.github.loganmartingreen.nucleocraftdetonation.terrain.crater;

import com.github.loganmartingreen.nucleocraftdetonation.config.TerrainGenerationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.detonation.NuclearYield;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.UUID;

/**
 * Incremental physical excavation for a detonation.
 *
 * <p>Surface / near-surface blasts carve one terrain-relative crater and stop
 * at the true crater edge. Buried blasts carve an underground ellipsoidal
 * cavity and, only when overburden is thin enough, a tapered breakout throat
 * and smaller surface crater. No raised rim, outer blast bowl, fracture field
 * or secondary TNT-style pits are generated here.</p>
 */
public final class CraterExcavationTask {
    private static final int UPDATE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    public enum Mode {
        SURFACE,
        SUBTERRANEAN_BREAKOUT,
        SUBTERRANEAN_SEALED
    }

    private final UUID owner;
    private final BlockPos centre;
    private final NuclearYield yield;
    private final CraterDimensions dimensions;

    private boolean initialised;
    private Mode mode = Mode.SURFACE;
    private int centreSurfaceY;
    private double cavityRadius;
    private double cavityVerticalRadius;
    private double breakoutStrength;
    private double breakoutSurfaceRadius;
    private double breakoutConnectionRadius;
    private double cavityTopY;
    private int processingRadius;

    private int offsetX;
    private int offsetZ;
    private boolean columnReady;
    private int currentY;
    private int minimumY;
    private int localTerrainSurfaceY;
    private double localDistance;
    private boolean complete;

    public CraterExcavationTask(UUID owner, BlockPos centre, NuclearYield yield) {
        this.owner = owner;
        this.centre = centre.immutable();
        this.yield = yield;
        this.dimensions = CraterDimensions.fromYield(yield);
    }

    public UUID owner() { return owner; }
    public BlockPos centre() { return centre; }
    public NuclearYield yield() { return yield; }
    public CraterDimensions dimensions() { return dimensions; }
    public Mode mode() { return mode; }
    public int refreshRadius() { return Math.max(dimensions.radius(), processingRadius) + 16; }

    public boolean tick(ServerLevel level, int maximumOperations) {
        if (complete) return true;
        if (maximumOperations <= 0) return false;
        if (!initialised) initialise(level);

        int operations = 0;
        while (!complete && operations < maximumOperations) {
            if (!columnReady) {
                prepareColumn(level);
                if (!columnReady) {
                    advanceColumn();
                    continue;
                }
            }

            int worldX = centre.getX() + offsetX;
            int worldZ = centre.getZ() + offsetZ;
            BlockPos pos = new BlockPos(worldX, currentY, worldZ);
            BlockState state = level.getBlockState(pos);

            if (shouldExcavate(currentY) && !isProtected(state) && !state.isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_FLAGS);
            }

            currentY--;
            operations++;
            if (currentY < minimumY) {
                advanceColumn();
            }
        }

        return complete;
    }

    private void initialise(ServerLevel level) {
        ensureChunkLoaded(level, centre.getX(), centre.getZ());
        centreSurfaceY = findTerrainSurfaceY(
                level,
                centre.getX(),
                centre.getZ(),
                level.getHeight(Heightmap.Types.WORLD_SURFACE, centre.getX(), centre.getZ()) - 1
        );
        if (centreSurfaceY < level.getMinBuildHeight()) {
            centreSurfaceY = centre.getY();
        }

        int burialDepth = Math.max(0, centreSurfaceY - centre.getY());
        int surfaceThreshold = Math.max(
                TerrainGenerationTuning.Crater.SURFACE_MODE_MINIMUM_BURIAL_BLOCKS,
                (int) Math.round(dimensions.depth()
                        * TerrainGenerationTuning.Crater.SURFACE_MODE_BURIAL_DEPTH_RATIO)
        );

        cavityRadius = dimensions.radius()
                * TerrainGenerationTuning.Crater.CAVITY_HORIZONTAL_RADIUS_MULTIPLIER;
        cavityVerticalRadius = dimensions.depth()
                * TerrainGenerationTuning.Crater.CAVITY_VERTICAL_RADIUS_MULTIPLIER;
        cavityTopY = centre.getY() + cavityVerticalRadius;

        if (burialDepth <= surfaceThreshold) {
            mode = Mode.SURFACE;
            processingRadius = dimensions.radius();
        } else {
            double overburdenAboveCavity = Math.max(0.0D, centreSurfaceY - cavityTopY);
            double maxBreakoutOverburden = Math.max(
                    TerrainGenerationTuning.Crater.BREAKOUT_MINIMUM_OVERBURDEN_BLOCKS,
                    dimensions.radius()
                            * TerrainGenerationTuning.Crater.BREAKOUT_MAX_OVERBURDEN_RADIUS_RATIO
            );
            breakoutStrength = 1.0D - smoothstep(Math.clamp(
                    overburdenAboveCavity / maxBreakoutOverburden,
                    0.0D,
                    1.0D
            ));
            if (overburdenAboveCavity >= maxBreakoutOverburden) {
                breakoutStrength = 0.0D;
            }

            breakoutSurfaceRadius = dimensions.radius()
                    * TerrainGenerationTuning.Crater.BREAKOUT_SURFACE_RADIUS_MULTIPLIER
                    * breakoutStrength;
            breakoutConnectionRadius = Math.max(
                    breakoutSurfaceRadius * 1.35D,
                    cavityRadius
                            * TerrainGenerationTuning.Crater.BREAKOUT_CONNECTION_RADIUS_MULTIPLIER
                            * (0.45D + breakoutStrength * 0.55D)
            );
            mode = breakoutStrength > 0.02D
                    ? Mode.SUBTERRANEAN_BREAKOUT
                    : Mode.SUBTERRANEAN_SEALED;
            processingRadius = (int) Math.ceil(Math.max(cavityRadius, breakoutConnectionRadius));
        }

        offsetX = -processingRadius;
        offsetZ = -processingRadius;
        initialised = true;
    }

    private void prepareColumn(ServerLevel level) {
        localDistance = Math.hypot(offsetX + 0.5D, offsetZ + 0.5D);
        if (localDistance > processingRadius + 1.0D) {
            return;
        }

        int worldX = centre.getX() + offsetX;
        int worldZ = centre.getZ() + offsetZ;
        ensureChunkLoaded(level, worldX, worldZ);
        int topY = Math.min(
                level.getMaxBuildHeight() - 1,
                level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1
        );
        localTerrainSurfaceY = findTerrainSurfaceY(level, worldX, worldZ, topY);
        if (localTerrainSurfaceY < level.getMinBuildHeight()) {
            return;
        }

        if (mode == Mode.SURFACE) {
            if (localDistance > dimensions.radius()) return;
            int floor = surfaceCraterFloor(localTerrainSurfaceY, localDistance);
            currentY = topY;
            minimumY = Math.max(level.getMinBuildHeight(), floor + 1);
            columnReady = currentY >= minimumY;
            return;
        }

        boolean cavityColumn = localDistance <= cavityRadius;
        boolean throatColumn = mode == Mode.SUBTERRANEAN_BREAKOUT
                && localDistance <= breakoutConnectionRadius;
        if (!cavityColumn && !throatColumn) return;

        double cavityHalfHeight = cavityColumn
                ? cavityVerticalRadius * Math.sqrt(Math.max(
                        0.0D,
                        1.0D - square(localDistance / Math.max(1.0D, cavityRadius))
                ))
                : 0.0D;
        int cavityHigh = (int) Math.ceil(centre.getY() + cavityHalfHeight);
        int cavityLow = (int) Math.floor(
                centre.getY() - cavityHalfHeight
                        - cavityVerticalRadius
                        * TerrainGenerationTuning.Crater.CAVITY_BOTTOM_FLATTEN_RATIO
                        * (1.0D - localDistance / Math.max(1.0D, cavityRadius))
        );

        int scanTop = cavityHigh;
        if (throatColumn) {
            scanTop = localDistance <= breakoutSurfaceRadius
                    ? topY
                    : Math.max(scanTop, throatTopY(localTerrainSurfaceY));
        }
        currentY = Math.min(level.getMaxBuildHeight() - 1, scanTop);
        minimumY = Math.max(level.getMinBuildHeight(), cavityLow);
        columnReady = currentY >= minimumY;
    }

    private boolean shouldExcavate(int y) {
        if (mode == Mode.SURFACE) {
            int floor = surfaceCraterFloor(localTerrainSurfaceY, localDistance);
            return y > floor;
        }

        boolean insideCavity = false;
        if (localDistance <= cavityRadius) {
            double halfHeight = cavityVerticalRadius * Math.sqrt(Math.max(
                    0.0D,
                    1.0D - square(localDistance / Math.max(1.0D, cavityRadius))
            ));
            double bottom = centre.getY() - halfHeight
                    - cavityVerticalRadius
                    * TerrainGenerationTuning.Crater.CAVITY_BOTTOM_FLATTEN_RATIO
                    * (1.0D - localDistance / Math.max(1.0D, cavityRadius));
            insideCavity = y >= bottom && y <= centre.getY() + halfHeight;
        }
        if (insideCavity) return true;

        if (mode != Mode.SUBTERRANEAN_BREAKOUT
                || localDistance > breakoutConnectionRadius
                || y < cavityTopY) {
            return false;
        }

        if (localDistance <= breakoutSurfaceRadius) {
            return y >= cavityTopY - 1.0D;
        }

        int top = throatTopY(localTerrainSurfaceY);
        return y <= top && y >= cavityTopY - 1.0D;
    }

    private int surfaceCraterFloor(int localSurfaceY, double distance) {
        double n = Math.clamp(distance / Math.max(1.0D, dimensions.radius()), 0.0D, 1.0D);
        double excavation = Math.pow(
                Math.max(0.0D, 1.0D - n * n),
                TerrainGenerationTuning.Crater.SURFACE_BOWL_EXPONENT
        );
        double edgeStart = TerrainGenerationTuning.Crater.SURFACE_EDGE_TAPER_START_RATIO;
        if (n > edgeStart) {
            excavation *= 1.0D - smoothstep((n - edgeStart) / (1.0D - edgeStart));
        }

        double innerRadius = TerrainGenerationTuning.Crater.SURFACE_INNER_ABSOLUTE_RADIUS_RATIO;
        double innerBlend = 1.0D - smoothstep(Math.clamp(n / innerRadius, 0.0D, 1.0D));
        double terrainReference = lerp(localSurfaceY, centreSurfaceY, innerBlend * 0.55D);
        double floor = terrainReference - dimensions.depth() * excavation;
        return (int) Math.round(Math.min(localSurfaceY, floor));
    }

    private int throatTopY(int localSurfaceY) {
        if (localDistance <= breakoutSurfaceRadius) {
            double n = localDistance / Math.max(1.0D, breakoutSurfaceRadius);
            double depth = dimensions.depth()
                    * TerrainGenerationTuning.Crater.BREAKOUT_SURFACE_BOWL_DEPTH_RATIO
                    * breakoutStrength
                    * Math.pow(Math.max(0.0D, 1.0D - n * n), 1.15D);
            return (int) Math.round(localSurfaceY - depth);
        }

        double between = Math.clamp(
                (localDistance - breakoutSurfaceRadius)
                        / Math.max(1.0D, breakoutConnectionRadius - breakoutSurfaceRadius),
                0.0D,
                1.0D
        );
        double curved = Math.pow(
                between,
                TerrainGenerationTuning.Crater.BREAKOUT_THROAT_CURVE_POWER
        );
        return (int) Math.round(lerp(localSurfaceY, cavityTopY, curved));
    }

    private void advanceColumn() {
        columnReady = false;
        offsetZ++;
        if (offsetZ > processingRadius) {
            offsetZ = -processingRadius;
            offsetX++;
        }
        if (offsetX > processingRadius) {
            complete = true;
        }
    }

    private static int findTerrainSurfaceY(ServerLevel level, int x, int z, int startingY) {
        int min = Math.max(level.getMinBuildHeight(), startingY - 512);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = startingY; y >= min; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (isTerrain(state)) return y;
        }
        return level.getMinBuildHeight() - 1;
    }

    private static boolean isTerrain(BlockState state) {
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
                || state.is(Blocks.SNOW_BLOCK);
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

    private static void ensureChunkLoaded(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
    }

    private static double square(double value) { return value * value; }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double smoothstep(double value) {
        value = Math.clamp(value, 0.0D, 1.0D);
        return value * value * (3.0D - 2.0D * value);
    }
}
