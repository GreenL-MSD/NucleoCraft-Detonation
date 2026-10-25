package com.github.loganmartingreen.nucleocraftdetonation.particle;

/**
 * Complete surface-mushroom stem model. The stem keeps one deterministic
 * geometry frame shared by particles, close voxels and optional distant LOD,
 * while the voxel renderers may split that shape into an outer smoke skin and
 * a slightly inset inner body. Terrain heightmaps and chunk-loaded state are
 * never consulted when reconstructing the stem.
 */
public final class SurfaceMushroomStem {
    private static final int BEGIN_TICK = 45;
    private static final int TOTAL_TICKS = 20 * 60 * 20;
    private static final double START_RETREAT = 0.85D;
    private static final double END_RETREAT = 0.94D;

    private SurfaceMushroomStem() { }

    public record Frame(double bottomY, double height, double baseRadius,
                        int age, float scale) {
        public double topY() { return bottomY + height; }
        public boolean visible() { return height > 1.0D && baseRadius > 0.0D; }
    }

    /** World-independent anchor; for a surface shot its centre Y is the original
     * surface and visualScale is the crater reference radius. The predicted
     * floor inset never changes when a chunk loads, unloads or is rebuilt. */
    public static double anchorY(double detonationY, float visualScale) {
        return detonationY - Math.max(4.0D, Math.max(1.0F, visualScale) * 0.43D);
    }

    public static Frame frame(double detonationY, double capCentreY,
                              double capVerticalRadius, float visualScale, int age) {
        float scale = Math.max(1.0F, visualScale);
        int safeAge = Math.max(0, age);
        double bottom = anchorY(detonationY, scale);
        if (safeAge < BEGIN_TICK) return new Frame(bottom, 0.0D, 0.0D, safeAge, scale);

        // Attach the neck to the underbelly of the approved cap. Cap position
        // is already age-dependent, but is NOT re-derived from nearby blocks.
        double upper = capCentreY - Math.max(0.0D, capVerticalRadius) * 0.33D;
        double fullHeight = Math.max(0.0D, upper - bottom);
        double retreat = retreat(safeAge);
        double startGrowth = smooth((safeAge - BEGIN_TICK) / 310.0D);
        double fullRadius = Math.max(2.9D, scale * 0.92D);
        double radius = fullRadius * (0.56D + 0.44D * startGrowth);
        return new Frame(bottom + fullHeight * retreat,
                fullHeight * (1.0D - retreat), radius, safeAge, scale);
    }

    /** Distance to the full stem-and-cap vertical envelope. This avoids using
     * an arbitrary midpoint above mountains or below tall developed caps. */
    public static double distanceSquaredToCloud(double observerX, double observerY,
                                               double observerZ, double centreX,
                                               double centreZ, Frame frame,
                                               double capTopY) {
        double dx = observerX - centreX, dz = observerZ - centreZ;
        double low = frame.bottomY();
        double high = Math.max(low, Math.max(frame.topY(), capTopY));
        double vertical = observerY < low ? low - observerY
                : observerY > high ? observerY - high : 0.0D;
        return dx * dx + dz * dz + vertical * vertical;
    }

    /** Smooth narrowing contour: broad foot -> narrow neck, but with a slightly
     * fuller middle and a subtly wider neck than v42.6.4. */
    public static double radius(Frame frame, double heightFraction) {
        double h = clamp(heightFraction);
        return frame.baseRadius * (0.39D + 0.61D * Math.pow(1.0D - h, 1.22D));
    }

    /** Inner stem body used by voxel renderers. It sits safely inside the smoke
     * shell and stays present through the whole stem rather than creating a hot
     * detached core entity. */
    public static double innerRadius(Frame frame, double heightFraction) {
        double outer = radius(frame, heightFraction);
        double inset = Math.max(voxelSize(outer) * 0.92D, outer * 0.22D);
        return Math.max(outer * 0.48D, outer - inset);
    }

    public static double retreat(int age) {
        double ageFraction = Math.max(0, age) / (double) TOTAL_TICKS;
        return smooth((ageFraction - START_RETREAT) / (END_RETREAT - START_RETREAT));
    }

    /** Translucent outer smoke skin. */
    public static int opacity(int age, double normalizedRadius) {
        return shellOpacity(age, normalizedRadius);
    }

    public static int shellOpacity(int age, double normalizedRadius) {
        double edge = smooth((clamp(normalizedRadius) - 0.66D) / 0.34D);
        double lastFade = smooth((Math.max(0, age) / (double) TOTAL_TICKS - 0.931D) / 0.009D);
        return clampByte((int) Math.round((170.0D - 54.0D * edge) * (1.0D - lastFade)));
    }

    /** Fairly solid inner body. */
    public static int innerOpacity(int age, double normalizedRadius, double heightFraction) {
        double radialFade = smooth((clamp(normalizedRadius) - 0.80D) / 0.20D);
        double topFade = smooth((clamp(heightFraction) - 0.92D) / 0.08D);
        double lastFade = smooth((Math.max(0, age) / (double) TOTAL_TICKS - 0.931D) / 0.009D);
        return clampByte((int) Math.round((224.0D - 26.0D * radialFade - 10.0D * topFade)
                * (1.0D - lastFade)));
    }

    /** Slightly darker ash-grey smoke with only a tiny warm low-stem hint early
     * on. This remains the shared default colour for particles and smoke shell. */
    public static int colour(float visualScale, int age, double heightFraction) {
        return shellColour(visualScale, age, heightFraction);
    }

    public static int shellColour(float visualScale, int age, double heightFraction) {
        double h = clamp(heightFraction);
        double aged = smooth((age - 260.0D) / 2000.0D);
        int smoke = mix(0x2E3433, 0x4B514D, aged);
        double lowWarm = (1.0D - smooth((h - 0.34D) / 0.30D))
                * (1.0D - smooth((age - 430.0D) / 1150.0D));
        return mix(smoke, 0x6F574A, lowWarm * 0.10D);
    }

    /** Mostly grey inner stem, with only a very faint subdued warm hint low in
     * the stem during early stages. */
    public static int innerColour(int age, double heightFraction, double radiusFraction) {
        double h = clamp(heightFraction);
        double r = clamp(radiusFraction);
        double aged = smooth((age - 260.0D) / 1900.0D);
        int base = mix(0x3A413F, 0x575D58, aged);
        double early = 1.0D - smooth((age - 520.0D) / 1280.0D);
        double lower = 1.0D - smooth((h - 0.40D) / 0.38D);
        double centre = 1.0D - smooth((r - 0.72D) / 0.28D);
        double warmth = early * lower * centre;
        int warm = mix(0xA06E4D, 0x755347, smooth(r * 0.78D + h * 0.22D));
        return mix(base, warm, warmth * 0.22D);
    }

    /** Detailed boxes remain size-bounded at large yields. */
    public static double voxelSize(double radius) {
        return Math.max(1.65D, radius * 0.20D);
    }

    public static int slices(Frame frame) {
        if (!frame.visible()) return 0;
        return Math.max(18, Math.min(48, (int) Math.ceil(frame.height / 4.6D)));
    }

    private static double clamp(double x) { return Math.max(0.0D, Math.min(1.0D, x)); }
    private static double smooth(double x) { x=clamp(x); return x*x*(3.0D-2.0D*x); }
    private static int clampByte(int x) { return Math.max(0, Math.min(255, x)); }
    private static int mix(int a, int b, double strength) {
        double t=clamp(strength);
        int r=clampByte((int)Math.round(((a>>16)&255)*(1-t)+((b>>16)&255)*t));
        int g=clampByte((int)Math.round(((a>>8)&255)*(1-t)+((b>>8)&255)*t));
        int v=clampByte((int)Math.round((a&255)*(1-t)+(b&255)*t));
        return r<<16|g<<8|v;
    }
}
