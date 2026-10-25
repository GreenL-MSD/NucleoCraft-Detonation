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
import java.util.List;

/**
 * Incremental physical excavation for one detonation.
 *
 * <p>Surface shots carve only the true terrain-relative crater. Subterranean
 * shots carve an irregular ellipsoidal cavity. Breakout shots additionally
 * carve a wide double-cone throat that necks in between the cavity and surface;
 * sealed shots never force a surface opening.</p>
 */
public final class CraterExcavationTask {
    private static final int UPDATE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private final UUID owner;
    private final BlockPos centre;
    private final NuclearYield yield;
    private final DetonationEnvironmentProfile profile;
    private final CraterDimensions dimensions;
    private final long fractureSeed;

    private final int processingRadius;
    private int offsetX;
    private int offsetZ;
    private boolean columnReady;
    private int currentY;
    private int minimumY;
    private int localTerrainSurfaceY;
    private double localDistance;
    private boolean complete;
    private boolean loadedProtection;
    private List<SurfaceCraterHistory.CraterFootprint> priorCraterFootprints = List.of();

    public CraterExcavationTask(
            UUID owner,
            BlockPos centre,
            NuclearYield yield,
            DetonationEnvironmentProfile profile,
            boolean skipRepeatedSurfaceExcavation
    ) {
        this.owner = owner;
        this.centre = centre.immutable();
        this.yield = yield;
        this.profile = profile;
        // Different footprint only for actual surface excavation. Underground
        // cavity, breakout profile, geology sampling and fallout retain the
        // original 32x11 (10 kt) reference dimensions.
        this.dimensions = profile.mode() == DetonationEnvironmentProfile.Mode.SURFACE
                ? CraterDimensions.surfaceExcavationFromYield(yield)
                : profile.dimensions();
        this.fractureSeed = mix64(
                centre.asLong()
                        ^ Double.doubleToLongBits(yield.kilotonnes())
                        ^ 0x5F3759DF9E3779B9L
        );

        double fractureReach = profile.isSubterranean()
                ? profile.fractureShellDepth()
                * TerrainGenerationTuning.Crater.CAVITY_TEAR_REACH_SHELL_RATIO
                : 0.0D;
        double horizontalExtent = profile.mode() == DetonationEnvironmentProfile.Mode.SURFACE
                ? dimensions.radius()
                : Math.max(
                        profile.cavityRadius() + fractureReach,
                        Math.max(
                                profile.breakoutConnectionRadius(),
                                profile.breakoutCraterRadius()
                        )
                );
        this.processingRadius = Math.max(1, (int) Math.ceil(horizontalExtent));
        this.offsetX = -processingRadius;
        this.offsetZ = -processingRadius;
        // Visuals and fallout may still run, but a duplicate surface crater
        // must not take another depth increment from its already-cut floor.
        this.complete = skipRepeatedSurfaceExcavation
                && profile.mode() == DetonationEnvironmentProfile.Mode.SURFACE;
    }

    public UUID owner() { return owner; }
    public BlockPos centre() { return centre; }
    public NuclearYield yield() { return yield; }
    public CraterDimensions dimensions() { return dimensions; }
    public DetonationEnvironmentProfile profile() { return profile; }
    public DetonationEnvironmentProfile.Mode mode() { return profile.mode(); }

    public int refreshRadius() {
        return Math.max(dimensions.radius(), processingRadius) + 16;
    }

    public boolean tick(ServerLevel level, int maximumOperations) {
        if (complete) return true;
        if (maximumOperations <= 0) return false;
        if (!loadedProtection && profile.mode() == DetonationEnvironmentProfile.Mode.SURFACE) {
            priorCraterFootprints = SurfaceCraterHistory.get(level)
                    .protectorsNear(centre, dimensions.radius());
            loadedProtection = true;
        }

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

            if (shouldExcavate(worldX, currentY, worldZ)
                    && !isProtected(state)
                    && !state.isAir()) {
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

        if (profile.mode() == DetonationEnvironmentProfile.Mode.SURFACE) {
            if (localDistance > dimensions.radius()) return;
            // Protect the whole overlapping *old crater*, not just shots
            // whose centres happen to meet the repeat-site tolerance.
            // The new shot may still excavate untouched columns around it.
            for (SurfaceCraterHistory.CraterFootprint prior : priorCraterFootprints) {
                if (SurfaceCraterRepeatRules.protectsColumn(
                        prior.x(), prior.z(), prior.radius(),
                        dimensions.radius(), worldX, worldZ)) return;
            }
            int floor = surfaceCraterFloor(localTerrainSurfaceY, localDistance);
            currentY = topY;
            minimumY = Math.max(level.getMinBuildHeight(), floor + 1);
            columnReady = currentY >= minimumY;
            return;
        }

        double tearReach = profile.fractureShellDepth()
                * TerrainGenerationTuning.Crater.CAVITY_TEAR_REACH_SHELL_RATIO;
        boolean cavityColumn = localDistance <= profile.cavityRadius() + tearReach;
        boolean breakoutColumn = profile.hasSurfaceBreakout()
                && localDistance <= Math.max(
                        profile.breakoutConnectionRadius(),
                        profile.breakoutCraterRadius()
                );
        if (!cavityColumn && !breakoutColumn) return;

        int cavityHigh = (int) Math.ceil(
                centre.getY() + profile.cavityVerticalRadius() + tearReach
        );
        int cavityLow = (int) Math.floor(
                centre.getY()
                        - profile.cavityVerticalRadius()
                        - tearReach
                        - profile.cavityVerticalRadius()
                        * TerrainGenerationTuning.Crater.CAVITY_BOTTOM_FLATTEN_RATIO
        );

        int scanTop = cavityHigh;
        if (breakoutColumn) {
            scanTop = topY;
        }

        currentY = Math.min(level.getMaxBuildHeight() - 1, scanTop);
        minimumY = Math.max(level.getMinBuildHeight(), cavityLow);
        columnReady = currentY >= minimumY;
    }

    private boolean shouldExcavate(int worldX, int y, int worldZ) {
        if (profile.mode() == DetonationEnvironmentProfile.Mode.SURFACE) {
            return y > surfaceCraterFloor(localTerrainSurfaceY, localDistance);
        }

        if (insideIrregularCavity(worldX, y, worldZ)) {
            return true;
        }

        if (!profile.hasSurfaceBreakout()) {
            return false;
        }

        if (insideBreakoutThroat(y)) {
            return true;
        }

        return insideBreakoutSurfaceBowl(y);
    }

    private boolean insideIrregularCavity(int worldX, int y, int worldZ) {
        double dx = worldX + 0.5D - (centre.getX() + 0.5D);
        double dz = worldZ + 0.5D - (centre.getZ() + 0.5D);
        double dy = y + 0.5D - (centre.getY() + 0.5D);

        double horizontalRadius = Math.max(1.0D, profile.cavityRadius());
        double verticalRadius = Math.max(1.0D, profile.cavityVerticalRadius());

        if (dy < 0.0D) {
            double centreBias = Math.max(
                    0.0D,
                    1.0D - Math.hypot(dx, dz) / horizontalRadius
            );
            dy /= 1.0D
                    + TerrainGenerationTuning.Crater.CAVITY_BOTTOM_FLATTEN_RATIO
                    * centreBias;
        }

        double nx = dx / horizontalRadius;
        double ny = dy / verticalRadius;
        double nz = dz / horizontalRadius;
        double normalisedRadius = Math.sqrt(nx * nx + ny * ny + nz * nz);

        double wallNoise = signedHashNoise3D(
                worldX,
                y,
                worldZ,
                fractureSeed ^ 0xA24BAED4963EE407L
        );
        double roughThreshold = 1.0D
                + wallNoise * TerrainGenerationTuning.Crater.CAVITY_WALL_ROUGHNESS;

        double tearReachBlocks = profile.fractureShellDepth()
                * TerrainGenerationTuning.Crater.CAVITY_TEAR_REACH_SHELL_RATIO
                * profile.fractureIntensity();
        double tearReachNormalised = tearReachBlocks
                / Math.max(1.0D, Math.min(horizontalRadius, verticalRadius));
        double tear = directionalTearStrength(dx, dy, dz) * tearReachNormalised;

        return normalisedRadius <= roughThreshold + tear;
    }

    /**
     * Produces narrow deterministic wedges rather than random spherical holes.
     * The base ellipsoid remains recognisable while the walls read as torn rock.
     */
    private double directionalTearStrength(double dx, double dy, double dz) {
        double horizontal = Math.max(0.001D, Math.hypot(dx, dz));
        double azimuth = Math.atan2(dz, dx);
        double elevation = Math.atan2(
                dy / Math.max(1.0D, profile.cavityVerticalRadius()),
                horizontal / Math.max(1.0D, profile.cavityRadius())
        );

        double phaseA = ((fractureSeed >>> 12) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        double phaseB = ((fractureSeed >>> 31) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        double phaseC = ((fractureSeed >>> 47) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;

        double ridgeA = narrowRidge(Math.sin(azimuth * 5.0D + phaseA));
        double ridgeB = narrowRidge(Math.sin(azimuth * 8.0D + elevation * 3.0D + phaseB));
        double ridgeC = narrowRidge(Math.sin(azimuth * 3.0D - elevation * 6.0D + phaseC));
        double verticalVariation = 0.55D + 0.45D * Math.abs(Math.cos(elevation * 2.0D));

        return Math.max(ridgeA, Math.max(ridgeB * 0.86D, ridgeC * 0.72D))
                * verticalVariation;
    }

    private static double narrowRidge(double sine) {
        double value = Math.max(0.0D, 1.0D - Math.abs(sine) * 5.5D);
        return value * value * value;
    }

    private boolean insideBreakoutThroat(int y) {
        double bottomY = profile.cavityTopY() - 1.0D;
        double topY = Math.max(bottomY + 1.0D, localTerrainSurfaceY + 1.0D);
        if (y < bottomY || y > topY) {
            return false;
        }

        double progress = Math.clamp(
                (y - bottomY) / Math.max(1.0D, topY - bottomY),
                0.0D,
                1.0D
        );
        double waistProgress = TerrainGenerationTuning.Crater
                .BREAKOUT_THROAT_WAIST_HEIGHT_RATIO;
        double waistRadius = Math.max(
                TerrainGenerationTuning.Crater.BREAKOUT_MINIMUM_WAIST_RADIUS_BLOCKS,
                Math.min(
                        profile.breakoutConnectionRadius(),
                        profile.breakoutSurfaceRadius()
                ) * TerrainGenerationTuning.Crater.BREAKOUT_THROAT_WAIST_RADIUS_RATIO
        );

        double radius;
        if (progress <= waistProgress) {
            double t = smoothstep(progress / Math.max(0.001D, waistProgress));
            radius = lerp(profile.breakoutConnectionRadius(), waistRadius, t);
        } else {
            double t = smoothstep(
                    (progress - waistProgress)
                            / Math.max(0.001D, 1.0D - waistProgress)
            );
            radius = lerp(waistRadius, profile.breakoutSurfaceRadius(), t);
        }

        double roughness = 1.0D + signedHashNoise2D(
                centre.getX() + offsetX,
                centre.getZ() + offsetZ,
                fractureSeed ^ 0x9E3779B97F4A7C15L
        ) * 0.08D * profile.fractureIntensity();
        return localDistance <= radius * roughness;
    }

    private boolean insideBreakoutSurfaceBowl(int y) {
        double radius = profile.breakoutCraterRadius();
        if (radius <= 0.0D || localDistance > radius) {
            return false;
        }

        double n = Math.clamp(localDistance / Math.max(1.0D, radius), 0.0D, 1.0D);
        double depth = dimensions.depth()
                * TerrainGenerationTuning.Crater.BREAKOUT_SURFACE_BOWL_DEPTH_RATIO
                * profile.breakoutStrength()
                * Math.pow(Math.max(0.0D, 1.0D - n * n), 1.18D);
        int floor = (int) Math.round(localTerrainSurfaceY - depth);
        return y > floor;
    }

    private int surfaceCraterFloor(int localSurfaceY, double distance) {
        // Maintain the original terrain-relative philosophy: never add blocks,
        // never excavate an outer blast bowl, and preserve surrounding mountains.
        // Irregularity only pulls parts of the rim INWARD, keeping a strict
        // maximum excavation radius.
        double angle = Math.atan2(offsetZ + 0.5D, offsetX + 0.5D);
        double irregular = (0.5D + 0.27D * Math.sin(7.0D * angle + fractureSeed * 1.0e-16D)
                + 0.23D * Math.sin(11.0D * angle - 0.6D));
        double localRadius = dimensions.radius() * (1.0D
                - TerrainGenerationTuning.Crater.SURFACE_WALL_ROUGHNESS
                * Math.clamp(irregular, 0.0D, 1.0D));
        double n = Math.clamp(distance / Math.max(1.0D, localRadius), 0.0D, 1.0D);
        // Wide, deeper central basin; stronger main wall; gentle final merge.
        // Smooth interpolation prevents a blocky artificial rim.
        double slopeProgress = Math.clamp((n - 0.16D) / 0.84D, 0.0D, 1.0D);
        double excavation = 1.0D - smoothstep(slopeProgress);
        excavation = Math.pow(excavation, 1.13D);

        // Mild deterministic wall terraces add fractured variation without
        // making a sawtooth floor or flattening real hills outside the crater.
        double wall = smoothstep(Math.clamp((n - 0.25D) / 0.55D, 0.0D, 1.0D));
        double micro = Math.sin((offsetX + 0.5D) * 0.47D + (offsetZ + 0.5D) * 0.31D)
                * Math.sin((offsetX + 0.5D) * 0.21D - (offsetZ + 0.5D) * 0.51D);
        double irregularDepth = micro * 0.055D * wall * (1.0D - n);
        double innerRadius = TerrainGenerationTuning.Crater.SURFACE_INNER_ABSOLUTE_RADIUS_RATIO;
        double innerBlend = 1.0D - smoothstep(Math.clamp(n / innerRadius, 0.0D, 1.0D));
        double terrainReference = lerp(localSurfaceY, profile.terrainSurfaceY(),
                innerBlend * 0.55D);
        double floor = terrainReference - dimensions.depth()
                * Math.clamp(excavation + irregularDepth, 0.0D, 1.0D);
        return (int) Math.round(Math.min(localSurfaceY, floor));
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
            if (SurfaceTerrainBlocks.isTerrain(state)) return y;
        }
        return level.getMinBuildHeight() - 1;
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

    private static double signedHashNoise2D(int x, int z, long salt) {
        return hash01(x, 0, z, salt) * 2.0D - 1.0D;
    }

    private static double signedHashNoise3D(int x, int y, int z, long salt) {
        return hash01(x, y, z, salt) * 2.0D - 1.0D;
    }

    private static double hash01(int x, int y, int z, long salt) {
        long value = salt;
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

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double smoothstep(double value) {
        value = Math.clamp(value, 0.0D, 1.0D);
        return value * value * (3.0D - 2.0D * value);
    }
}
