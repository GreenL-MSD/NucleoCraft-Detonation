package com.github.loganmartingreen.nucleocraftdetonation.terrain.crater;

import com.github.loganmartingreen.nucleocraftdetonation.config.TerrainGenerationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.detonation.NuclearYield;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;

/**
 * One pre-blast environment classification shared by crater, damage, fallout
 * and cloud formation.
 *
 * <p>The profile deliberately samples a broad patch of terrain instead of only
 * looking straight above the bomb. A narrow player-dug shaft therefore does
 * not turn an otherwise deeply buried shot into a surface detonation.</p>
 */
public final class DetonationEnvironmentProfile {
    public enum Mode {
        SURFACE,
        SUBTERRANEAN_BREAKOUT,
        SUBTERRANEAN_SEALED
    }

    private final Mode mode;
    private final BlockPos centre;
    private final CraterDimensions dimensions;
    private final int terrainSurfaceY;
    private final int burialDepth;
    private final double cavityRadius;
    private final double cavityVerticalRadius;
    private final double cavityTopY;
    private final double breakoutStrength;
    private final double containmentFactor;
    private final double breakoutSurfaceRadius;
    private final double breakoutConnectionRadius;
    private final double breakoutCraterRadius;
    private final double surfaceFalloutMultiplier;
    private final double undergroundDamageMultiplier;
    private final double fractureIntensity;
    private final int fractureShellDepth;
    private final double rockIntegrity;
    private final double cavePorosity;
    private final double terrainRelief;
    private final String outcome;

    private DetonationEnvironmentProfile(
            Mode mode,
            BlockPos centre,
            CraterDimensions dimensions,
            int terrainSurfaceY,
            int burialDepth,
            double cavityRadius,
            double cavityVerticalRadius,
            double cavityTopY,
            double breakoutStrength,
            double containmentFactor,
            double breakoutSurfaceRadius,
            double breakoutConnectionRadius,
            double breakoutCraterRadius,
            double surfaceFalloutMultiplier,
            double undergroundDamageMultiplier,
            double fractureIntensity,
            int fractureShellDepth,
            double rockIntegrity,
            double cavePorosity,
            double terrainRelief,
            String outcome
    ) {
        this.mode = mode;
        this.centre = centre.immutable();
        this.dimensions = dimensions;
        this.terrainSurfaceY = terrainSurfaceY;
        this.burialDepth = burialDepth;
        this.cavityRadius = cavityRadius;
        this.cavityVerticalRadius = cavityVerticalRadius;
        this.cavityTopY = cavityTopY;
        this.breakoutStrength = breakoutStrength;
        this.containmentFactor = containmentFactor;
        this.breakoutSurfaceRadius = breakoutSurfaceRadius;
        this.breakoutConnectionRadius = breakoutConnectionRadius;
        this.breakoutCraterRadius = breakoutCraterRadius;
        this.surfaceFalloutMultiplier = surfaceFalloutMultiplier;
        this.undergroundDamageMultiplier = undergroundDamageMultiplier;
        this.fractureIntensity = fractureIntensity;
        this.fractureShellDepth = fractureShellDepth;
        this.rockIntegrity = rockIntegrity;
        this.cavePorosity = cavePorosity;
        this.terrainRelief = terrainRelief;
        this.outcome = outcome;
    }

    public static DetonationEnvironmentProfile analyse(
            ServerLevel level,
            BlockPos centre,
            NuclearYield yield
    ) {
        return analyse(level, centre, yield, false);
    }

    /** The last argument is only for a recognised, previously excavated
     * SURFACE crater where the newly targeted floor is still sky-visible.
     */
    public static DetonationEnvironmentProfile analyse(
            ServerLevel level,
            BlockPos centre,
            NuclearYield yield,
            boolean previousSurfaceCrater
    ) {
        CraterDimensions dimensions = CraterDimensions.fromYield(yield);
        int terrainSurfaceY = sampleRepresentativeSurfaceY(level, centre, dimensions.radius());
        if (terrainSurfaceY < level.getMinBuildHeight()) {
            terrainSurfaceY = centre.getY();
        }

        int burialDepth = Math.max(0, terrainSurfaceY - centre.getY());
        // Profiled burial depth, not the reference excavation depth (11 at 10kt).

        GeologicalSample geological = sampleGeology(level, centre, terrainSurfaceY,
                Math.max(8, dimensions.radius()));
        BurialDepthModel.Assessment classification = BurialDepthModel.classify(
                burialDepth, yield.kilotonnes(), geological.integrity(),
                geological.porosity(), geological.relief());
        // A surface shot aimed at the bottom of an existing, open crater must
        // not turn into a sealed or breakout shot merely because the crater
        // walls raised the representative surface Y. Require BROAD sky access
        // so a one-block mine shaft does not count as open terrain.
        boolean exposedSurface = isBroadlySkyExposed(level, centre);
        double cavityRadius = dimensions.radius()
                * TerrainGenerationTuning.Crater.CAVITY_HORIZONTAL_RADIUS_MULTIPLIER;
        double cavityVerticalRadius = dimensions.depth()
                * TerrainGenerationTuning.Crater.CAVITY_VERTICAL_RADIUS_MULTIPLIER;
        double cavityTopY = centre.getY() + cavityVerticalRadius;

        if (classification.surface() || exposedSurface || previousSurfaceCrater) {
            return new DetonationEnvironmentProfile(
                    Mode.SURFACE,
                    centre,
                    dimensions,
                    terrainSurfaceY,
                    burialDepth,
                    cavityRadius,
                    cavityVerticalRadius,
                    cavityTopY,
                    1.0D,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D,
                    1.0D,
                    0.0D,
                    0.0D,
                    0,
                    geological.integrity(), geological.porosity(), geological.relief(),
                    "SURFACE"
            );
        }

        Mode mode = classification.breakout() ? Mode.SUBTERRANEAN_BREAKOUT
                : Mode.SUBTERRANEAN_SEALED;
        double breakoutStrength = classification.strength();
        String outcome = classification.outcome();

        double containment = 1.0D - breakoutStrength;
        double breakoutSurfaceRadius = dimensions.radius()
                * TerrainGenerationTuning.Crater.BREAKOUT_SURFACE_RADIUS_MULTIPLIER
                * Math.pow(breakoutStrength, 0.72D);
        double breakoutConnectionRadius = cavityRadius
                * TerrainGenerationTuning.Crater.BREAKOUT_CONNECTION_RADIUS_MULTIPLIER
                * (0.72D + breakoutStrength * 0.28D);
        double breakoutCraterRadius = breakoutSurfaceRadius
                * TerrainGenerationTuning.Crater.BREAKOUT_CRATER_OUTER_RADIUS_MULTIPLIER;

        double surfaceFalloutMultiplier = mode == Mode.SUBTERRANEAN_SEALED
                ? 0.0D
                : Math.clamp(
                        TerrainGenerationTuning.Crater.BREAKOUT_MINIMUM_SURFACE_FALLOUT_MULTIPLIER
                                + (1.0D
                                - TerrainGenerationTuning.Crater
                                .BREAKOUT_MINIMUM_SURFACE_FALLOUT_MULTIPLIER)
                                * Math.pow(breakoutStrength, 0.78D),
                        0.0D,
                        1.0D
                );

        double undergroundDamageMultiplier = lerp(
                TerrainGenerationTuning.SubterraneanDamage.BREAKOUT_DAMAGE_MULTIPLIER,
                TerrainGenerationTuning.SubterraneanDamage.SEALED_DAMAGE_MULTIPLIER,
                containment
        );
        double fractureIntensity = Math.clamp(
                TerrainGenerationTuning.SubterraneanDamage.BASE_FRACTURE_INTENSITY
                        + containment
                        * TerrainGenerationTuning.SubterraneanDamage.CONTAINMENT_FRACTURE_BONUS,
                0.0D,
                1.0D
        );
        int fractureShellDepth = Math.max(
                TerrainGenerationTuning.SubterraneanDamage.MINIMUM_SHELL_DEPTH,
                (int) Math.round(
                        (TerrainGenerationTuning.SubterraneanDamage.BASE_SHELL_DEPTH
                                + cavityRadius
                                * TerrainGenerationTuning.SubterraneanDamage.SHELL_RADIUS_RATIO)
                                * undergroundDamageMultiplier
                )
        );

        return new DetonationEnvironmentProfile(
                mode,
                centre,
                dimensions,
                terrainSurfaceY,
                burialDepth,
                cavityRadius,
                cavityVerticalRadius,
                cavityTopY,
                breakoutStrength,
                containment,
                breakoutSurfaceRadius,
                breakoutConnectionRadius,
                breakoutCraterRadius,
                surfaceFalloutMultiplier,
                undergroundDamageMultiplier,
                fractureIntensity,
                fractureShellDepth,
                geological.integrity(), geological.porosity(), geological.relief(), outcome
        );
    }

    public Mode mode() { return mode; }
    public BlockPos centre() { return centre; }
    public CraterDimensions dimensions() { return dimensions; }
    public int terrainSurfaceY() { return terrainSurfaceY; }
    public int burialDepth() { return burialDepth; }
    public double cavityRadius() { return cavityRadius; }
    public double cavityVerticalRadius() { return cavityVerticalRadius; }
    public double cavityTopY() { return cavityTopY; }
    public double breakoutStrength() { return breakoutStrength; }
    public double containmentFactor() { return containmentFactor; }
    public double breakoutSurfaceRadius() { return breakoutSurfaceRadius; }
    public double breakoutConnectionRadius() { return breakoutConnectionRadius; }
    public double breakoutCraterRadius() { return breakoutCraterRadius; }
    public double surfaceFalloutMultiplier() { return surfaceFalloutMultiplier; }
    public double undergroundDamageMultiplier() { return undergroundDamageMultiplier; }
    public double fractureIntensity() { return fractureIntensity; }
    public int fractureShellDepth() { return fractureShellDepth; }
    public double rockIntegrity() { return rockIntegrity; }
    public double cavePorosity() { return cavePorosity; }
    public double terrainRelief() { return terrainRelief; }
    public String outcome() { return outcome; }
    public double overburdenAboveCavity() { return Math.max(0.0D, terrainSurfaceY - cavityTopY); }
    public boolean eligibleForSubsidence() {
        return mode == Mode.SUBTERRANEAN_SEALED
                && burialDepth < 200.0D * Math.cbrt(
                        Math.max(0.1D, Math.pow(dimensions.radius() / 32.0D, 3.0D)))
                && (cavePorosity > 0.11D || burialDepth < 130.0D * dimensions.radius() / 32.0D);
    }

    public boolean isSubterranean() {
        return mode != Mode.SURFACE;
    }

    public boolean hasSurfaceBreakout() {
        return mode == Mode.SUBTERRANEAN_BREAKOUT;
    }

    public boolean hasSurfaceFallout() {
        return surfaceFalloutMultiplier > 0.001D;
    }

    /**
     * Y target used by the client fireball. Integer.MIN_VALUE means that the
     * blast stayed sealed and must not create a surface mushroom cloud.
     */
    public int cloudExitY() {
        if (mode == Mode.SUBTERRANEAN_SEALED) {
            return Integer.MIN_VALUE;
        }
        return mode == Mode.SUBTERRANEAN_BREAKOUT
                ? terrainSurfaceY + 1
                : centre.getY();
    }

    private static boolean isBroadlySkyExposed(ServerLevel level, BlockPos centre) {
        int skyExposed = 0;
        int[][] offsets = {{0, 0}, {4, 0}, {-4, 0}, {0, 4}, {0, -4}};
        for (int[] offset : offsets) {
            BlockPos above = centre.offset(offset[0], 1, offset[1]);
            level.getChunk(above.getX() >> 4, above.getZ() >> 4);
            if (level.canSeeSky(above)) skyExposed++;
        }
        return skyExposed >= 3;
    }

    private record GeologicalSample(double integrity, double porosity, double relief) {}

    private static GeologicalSample sampleGeology(ServerLevel level, BlockPos centre,
                                                   int surfaceY, int radius) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int solid = 0;
        int strong = 0;
        int voids = 0;
        int total = 0;
        int relief = 0;
        int samples = 0;
        int step = Math.max(4, radius / 2);
        for (int dx = -step; dx <= step; dx += step) {
            for (int dz = -step; dz <= step; dz += step) {
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                level.getChunk(x >> 4, z >> 4);
                int localSurface = findTerrainSurfaceY(level, x, z,
                        Math.min(level.getMaxBuildHeight() - 1,
                                level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1));
                relief += Math.abs(localSurface - surfaceY);
                samples++;
                int top = Math.min(localSurface - 3, surfaceY - 3);
                int bottom = Math.max(level.getMinBuildHeight() + 1, centre.getY() + 3);
                if (top <= bottom) continue;
                for (int j = 1; j <= TerrainGenerationTuning.Crater.MATERIAL_SAMPLE_VERTICAL_STEPS; j++) {
                    int y = bottom + (top - bottom) * j
                            / (TerrainGenerationTuning.Crater.MATERIAL_SAMPLE_VERTICAL_STEPS + 1);
                    BlockState state = level.getBlockState(at.set(x, y, z));
                    total++;
                    if (state.isAir() || !state.getFluidState().isEmpty()) voids++;
                    else {
                        solid++;
                        if (state.is(BlockTags.BASE_STONE_OVERWORLD)) strong++;
                    }
                }
            }
        }
        return new GeologicalSample(
                solid == 0 ? 0.5D : (strong * 0.8D + (solid - strong) * 0.3D) / solid,
                total == 0 ? 0.0D : voids / (double) total,
                Math.clamp(relief / (double) Math.max(1, samples) / Math.max(8, radius), 0.0D, 1.0D));
    }

    private static int sampleRepresentativeSurfaceY(
            ServerLevel level,
            BlockPos centre,
            int craterRadius
    ) {
        int sampleRadius = Math.max(
                TerrainGenerationTuning.Crater.ENVIRONMENT_SAMPLE_MINIMUM_RADIUS,
                (int) Math.round(
                        craterRadius
                                * TerrainGenerationTuning.Crater
                                .ENVIRONMENT_SAMPLE_RADIUS_MULTIPLIER
                )
        );
        int half = Math.max(2, sampleRadius / 2);
        int[] offsets = new int[]{-sampleRadius, -half, 0, half, sampleRadius};
        int[] heights = new int[offsets.length * offsets.length];
        int count = 0;

        for (int offsetX : offsets) {
            for (int offsetZ : offsets) {
                int x = centre.getX() + offsetX;
                int z = centre.getZ() + offsetZ;
                level.getChunk(x >> 4, z >> 4);
                int startingY = Math.min(
                        level.getMaxBuildHeight() - 1,
                        level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
                );
                int surfaceY = findTerrainSurfaceY(level, x, z, startingY);
                if (surfaceY >= level.getMinBuildHeight()) {
                    heights[count++] = surfaceY;
                }
            }
        }

        if (count == 0) {
            return centre.getY();
        }

        Arrays.sort(heights, 0, count);
        return heights[count / 2];
    }

    private static int findTerrainSurfaceY(
            ServerLevel level,
            int x,
            int z,
            int startingY
    ) {
        int minimum = Math.max(
                level.getMinBuildHeight(),
                startingY - TerrainGenerationTuning.Crater.ENVIRONMENT_SURFACE_SCAN_DEPTH
        );
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = startingY; y >= minimum; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (SurfaceTerrainBlocks.isTerrain(state)) {
                return y;
            }
        }
        return level.getMinBuildHeight() - 1;
    }



    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    private static double smoothstep(double value) {
        value = Math.clamp(value, 0.0D, 1.0D);
        return value * value * (3.0D - 2.0D * value);
    }
}
