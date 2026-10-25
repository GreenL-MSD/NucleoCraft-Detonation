package com.github.loganmartingreen.nucleocraftdetonation.fallout;

import com.github.loganmartingreen.nucleocraftdetonation.terrain.crater.DetonationEnvironmentProfile;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/** Temporary, local ambient-spawn suppression within freshly blasted caverns. */
public final class UndergroundSpawnSuppression {
    private record Zone(ResourceKey<Level> dimension, double x, double y, double z,
                        double horizontal, double vertical, long untilTick) {}
    private static final List<Zone> ZONES = new ArrayList<>();
    private static MinecraftServer activeServer;
    private static long ticks;

    private UndergroundSpawnSuppression() {}

    public static void tick(MinecraftServer server) {
        if (activeServer != server) {
            ZONES.clear();
            activeServer = server;
            ticks = 0;
        }
        ticks++;
        ZONES.removeIf(zone -> zone.untilTick < ticks);
    }

    public static void add(ResourceKey<Level> dimension, DetonationEnvironmentProfile p) {
        if (!p.isSubterranean()) return;
        ZONES.add(new Zone(dimension, p.centre().getX(), p.centre().getY(), p.centre().getZ(),
                p.cavityRadius() * 1.45D, p.cavityVerticalRadius() * 1.65D,
                ticks + 20L * 60L * 20L));
    }

    public static boolean contains(ResourceKey<Level> dimension, double x, double y, double z) {
        for (Zone zone : ZONES) {
            if (!zone.dimension.equals(dimension)) continue;
            double dx = (x - zone.x) / zone.horizontal;
            double dy = (y - zone.y) / zone.vertical;
            double dz = (z - zone.z) / zone.horizontal;
            if (dx * dx + dy * dy + dz * dz < 1.0D) return true;
        }
        return false;
    }
}
