package com.github.loganmartingreen.nucleocraftdetonation.particle;

/**
 * Opaque low-detail surface-cloud colour treatment for DH's generic model.
 * Nested translucent near-cloud layers are represented by one stable LOD
 * voxel per position: smoke predominates, with some fire-lit lower-cap cells.
 * This is a rendering fallback only, never the close mushroom geometry.
 */
public final class SurfaceCloudLodProfile {
    private SurfaceCloudLodProfile() { }

    public static int capColour(int smokeRgb, int hotRgb, int ageTicks,
                                double radial, double v, int seed, int x, int z) {
        double lower = clamp((0.5D - v) / 1.45D);
        double inner = 1.0D - smooth((radial - 0.12D) / 0.90D);
        double active = 1.0D - smooth((ageTicks - 210.0D) / 1420.0D);
        // Small deterministic openings in the lower grey shell, never actual
        // transparent gaps in the distant cap silhouette.
        long hash = ((long) seed * 1315423911L) ^ ((long) x * 2654435761L)
                ^ ((long) z * 889523592379L);
        hash ^= hash >>> 19;
        hash *= 0x9E3779B97F4A7C15L;
        double speckle = (hash >>> 40) / (double) (1L << 24);
        double warmth = lower * inner * active;
        double amount = speckle < 0.67D ? 0.0D : warmth * 0.67D;
        return mix(smokeRgb, hotRgb, amount);
    }

    private static int mix(int a, int b, double amount) {
        double t = clamp(amount);
        int r = (int)Math.round(((a >>> 16) & 255) * (1 - t) + ((b >>> 16) & 255) * t);
        int g = (int)Math.round(((a >>> 8) & 255) * (1 - t) + ((b >>> 8) & 255) * t);
        int bl = (int)Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static double smooth(double value) {
        double x = clamp(value);
        return x * x * (3 - 2 * x);
    }

    private static double clamp(double x) {
        return Math.max(0.0D, Math.min(1.0D, x));
    }
}
