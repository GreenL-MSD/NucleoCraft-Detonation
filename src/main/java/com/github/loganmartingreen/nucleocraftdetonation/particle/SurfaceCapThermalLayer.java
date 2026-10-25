package com.github.loganmartingreen.nucleocraftdetonation.particle;

/**
 * Separate internal heat volume and external smoke skin for SURFACE mushroom
 * clouds. No entity, world state, or independent animation is created: the
 * surface cap's own age, radius and layer heights drive both components.
 *
 * This geometry/colour contract is shared by the close voxel renderer and
 * the distant-horizon object builder. Underground visuals do not use it.
 */
public final class SurfaceCapThermalLayer {
    private SurfaceCapThermalLayer() { }

    /** One approximately voxel-sized inset, scaled with the existing box size. */
    public static double coreRadius(double sliceRadius, double boxSize) {
        return Math.max(0.0D, sliceRadius - boxSize * 0.83D);
    }

    /** Retain one outer smoke-cell band. The top closes over the hot volume;
     * the underside is left open enough to reveal the fire from below. */
    public static boolean smokeCell(double distance, double sliceRadius,
                                    double boxSize, double verticalNormal) {
        if (verticalNormal > 0.94D) return true;
        return distance >= Math.max(0.0D, sliceRadius - boxSize * 0.85D);
    }

    /** Early cap contains an opaque, self-contained yellow/orange/red body;
     * its *colour* cools into grey rather than all inner voxels vanishing. */
    public static int thermalRgb(int ageTicks, double radiusFraction,
                                  double verticalNormal) {
        double r = clamp(radiusFraction);
        double lowerHeat = clamp((0.45D - verticalNormal) * 0.35D);
        double band = clamp(r * 0.80D + Math.max(0.0D, verticalNormal) * 0.20D
                - lowerHeat * 0.18D);
        int hot = band < 0.53D
                ? rgbMix(0xFFE069, 0xF18C2C, band / 0.53D)
                : rgbMix(0xF18C2C, 0xA44734, (band - 0.53D) / 0.47D);
        // The colour transitions rapidly after initial fireball expansion;
        // the thermal volume remains fully present underneath the smoke.
        double cooling = smooth((ageTicks - 350.0D) / 1500.0D);
        return rgbMix(hot, 0x444A46, cooling);
    }

    /** The actual smoke envelope remains moderately transparent; unlike the
     * previous many-layer smoke fill, it has only one outer voxel thickness. */
    public static int smokeAlpha(double verticalNormal) {
        double top = smooth((verticalNormal + 0.40D) / 1.25D);
        return (int) Math.round(113.0D + 39.0D * top);
    }

    private static double clamp(double x) {
        return Math.max(0.0D, Math.min(1.0D, x));
    }

    private static double smooth(double x) {
        x = clamp(x);
        return x * x * (3.0D - 2.0D * x);
    }

    private static int rgbMix(int a, int b, double weight) {
        double t = clamp(weight);
        int red = (int) Math.round(((a >>> 16) & 255) * (1.0D - t)
                + ((b >>> 16) & 255) * t);
        int green = (int) Math.round(((a >>> 8) & 255) * (1.0D - t)
                + ((b >>> 8) & 255) * t);
        int blue = (int) Math.round((a & 255) * (1.0D - t) + (b & 255) * t);
        return (red << 16) | (green << 8) | blue;
    }
}
