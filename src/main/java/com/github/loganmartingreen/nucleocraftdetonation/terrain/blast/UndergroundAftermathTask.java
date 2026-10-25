package com.github.loganmartingreen.nucleocraftdetonation.terrain.blast;

import com.github.loganmartingreen.nucleocraftdetonation.block.DetonationBlocks;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.crater.DetonationEnvironmentProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Bounded passage propagation rather than a second isotropic crater.
 * Finds cave air beyond the new cavity at a few deterministic ports, then
 * scorches exposed organic surfaces and clears living mobs in severe zones.
 * Never bores open a new tunnel; fully sealed shots cannot vent above ground.
 */
public final class UndergroundAftermathTask {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
            | Block.UPDATE_SUPPRESS_DROPS;
    private static final Direction[] FACES = Direction.values();
    private record VentNode(BlockPos pos, int distance) {}

    private final DetonationEnvironmentProfile profile;
    private final ArrayDeque<VentNode> open = new ArrayDeque<>();
    private final Set<Long> visited = new HashSet<>();
    private final int maximumNodes;
    private final int maximumReach;
    private int processed;
    private boolean started;

    public UndergroundAftermathTask(DetonationEnvironmentProfile profile) {
        this.profile = profile;
        double scale = profile.dimensions().radius() / 32.0D;
        this.maximumReach = Math.clamp((int) Math.round(Math.max(
                24 * scale, profile.burialDepth() * 1.10D)), 12, 160);
        this.maximumNodes = Math.clamp((int) Math.round(3_500 * scale), 800, 9_000);
    }

    public boolean tick(ServerLevel level, int budget) {
        if (!started) {
            started = true;
            damageCavityMobs(level);
            seedCaveMouths(level);
        }
        int examined = 0;
        while (!open.isEmpty() && examined < budget && processed < maximumNodes) {
            VentNode node = open.removeFirst();
            BlockPos at = node.pos();
            examined++;
            processed++;
            if (!level.getBlockState(at).isAir()) continue;
            // A few brief incandescent jets into exposed tunnels, not thousands of particles.
            if (processed % 12 == 0) {
                level.sendParticles(ParticleTypes.FLAME, at.getX() + 0.5D,
                        at.getY() + 0.5D, at.getZ() + 0.5D,
                        2, 0.25D, 0.25D, 0.25D, 0.02D);
                level.sendParticles(ParticleTypes.SMOKE, at.getX() + 0.5D,
                        at.getY() + 0.5D, at.getZ() + 0.5D,
                        2, 0.4D, 0.25D, 0.4D, 0.01D);
            }
            for (Direction face : FACES) {
                BlockPos next = at.relative(face);
                if (!allowed(level, next)) continue;
                BlockState state = level.getBlockState(next);
                if (state.isAir()) {
                    if (withinOriginalCavity(next)) continue;
                    if (node.distance() < maximumReach && visited.size() < maximumNodes
                            && visited.add(next.asLong())) {
                        open.addLast(new VentNode(next, node.distance() + 1));
                    }
                } else if (isOrganic(state)) {
                    // Every newly exposed organic surface is cooked, including mineshaft timber.
                    BlockState scorched = state.is(Blocks.MOSS_BLOCK)
                            || state.is(Blocks.ROOTED_DIRT)
                            ? DetonationBlocks.CHARRED_DIRT.get().defaultBlockState()
                            : Blocks.AIR.defaultBlockState();
                    level.setBlock(next, scorched, FLAGS);
                }
            }
            if (processed % 30 == 0) {
                AABB box = new AABB(at).inflate(1.5D);
                for (Mob mob : level.getEntitiesOfClass(Mob.class, box)) {
                    mob.hurt(level.damageSources().generic(), mob.getMaxHealth() + 100.0F);
                    mob.setRemainingFireTicks(20 * 10);
                }
            }
        }
        return open.isEmpty() || processed >= maximumNodes;
    }

    private void damageCavityMobs(ServerLevel level) {
        double r = profile.cavityRadius() * 1.17D;
        double h = profile.cavityVerticalRadius() * 1.25D;
        BlockPos c = profile.centre();
        AABB box = new AABB(c.getX() - r, c.getY() - h, c.getZ() - r,
                c.getX() + r, c.getY() + h, c.getZ() + r);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box)) {
            double dx = (mob.getX() - c.getX()) / r;
            double dy = (mob.getY() - c.getY()) / h;
            double dz = (mob.getZ() - c.getZ()) / r;
            if (dx * dx + dy * dy + dz * dz < 1.0D) {
                mob.hurt(level.damageSources().generic(), mob.getMaxHealth() + 100.0F);
                mob.setRemainingFireTicks(20 * 10);
            }
        }
    }

    private void seedCaveMouths(ServerLevel level) {
        BlockPos c = profile.centre();
        // Distributed ports along the actual cavity wall; capped search prevents
        // a connected ravine from costing an unbounded graph traversal.
        for (int vertical = -1; vertical <= 1; vertical++) {
            for (int angleIndex = 0; angleIndex < 12; angleIndex++) {
                double angle = 2.0D * Math.PI * angleIndex / 12.0D;
                BlockPos at = new BlockPos(
                        c.getX() + (int) Math.round(Math.cos(angle) * profile.cavityRadius() * 1.04D),
                        c.getY() + (int) Math.round(vertical * profile.cavityVerticalRadius() * 0.54D),
                        c.getZ() + (int) Math.round(Math.sin(angle) * profile.cavityRadius() * 1.04D));
                if (allowed(level, at) && level.getBlockState(at).isAir()
                        && visited.add(at.asLong())) {
                    open.addLast(new VentNode(at, 0));
                }
            }
        }
    }

    private boolean withinOriginalCavity(BlockPos pos) {
        double dx = (pos.getX() - profile.centre().getX()) / Math.max(1.0D, profile.cavityRadius());
        double dy = (pos.getY() - profile.centre().getY()) / Math.max(1.0D, profile.cavityVerticalRadius());
        double dz = (pos.getZ() - profile.centre().getZ()) / Math.max(1.0D, profile.cavityRadius());
        return dx * dx + dy * dy + dz * dz < 0.88D;
    }

    private boolean allowed(ServerLevel level, BlockPos at) {
        if (at.getY() <= level.getMinBuildHeight()
                || at.getY() >= level.getMaxBuildHeight() - 1) return false;
        double dx = at.getX() - profile.centre().getX();
        double dz = at.getZ() - profile.centre().getZ();
        double reach = profile.cavityRadius() + maximumReach;
        if (dx * dx + dz * dz > reach * reach) return false;
        // Closed shots remain closed: the vent algorithm never creates a surface opening.
        if (profile.mode() == DetonationEnvironmentProfile.Mode.SUBTERRANEAN_SEALED) {
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    at.getX(), at.getZ()) - 1;
            if (at.getY() >= surface - 3
                    && !profile.outcome().equals("MARGINAL_CONTAINED_VENT")) return false;
            // Marginal shots may send smoke through an existing open cave mouth;
            // no blocks are removed near the surface and no mushroom is produced.
            if (at.getY() > surface + 4) return false;
        }
        return true;
    }

    private static boolean isOrganic(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.LEAVES)
                || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MOSS_CARPET)
                || state.is(Blocks.AZALEA) || state.is(Blocks.FLOWERING_AZALEA)
                || state.is(Blocks.VINE) || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.GLOW_LICHEN) || state.is(Blocks.SPORE_BLOSSOM)
                || state.is(Blocks.HANGING_ROOTS) || state.is(Blocks.BIG_DRIPLEAF)
                || state.is(Blocks.SMALL_DRIPLEAF) || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS) || state.is(Blocks.BROWN_MUSHROOM)
                || state.is(Blocks.RED_MUSHROOM) || state.is(Blocks.OAK_FENCE)
                || state.is(Blocks.SPRUCE_FENCE);
    }
}
