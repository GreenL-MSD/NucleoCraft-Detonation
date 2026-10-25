package com.github.loganmartingreen.nucleocraftdetonation.particle;

/**
 * Renderer-neutral progression for underground effects.  Neither a sealed
 * detonation nor a breakout ever creates a mushroom cap.
 *
 * <p>Kept free of Minecraft types so particle and voxel modes cannot drift in
 * timing or geometry, and so the progression can be verified independently.</p>
 */
public final class UndergroundVisualTimeline {
    private UndergroundVisualTimeline() { }

    public static final int SEALED_LIFETIME = 140;
    public static final int BREAKOUT_LIFETIME = 290;
    public static final int JET_START_TICK = 10;
    public static final int JET_REACH_SURFACE_TICK = 37;
    public static final int JET_FULL_HEIGHT_TICK = 132;
    public static final int JET_COOL_START_TICK = 126;
    public static final int JET_FADE_START_TICK = 190;

    public static int lifetime(boolean breakout) {
        return breakout ? BREAKOUT_LIFETIME : SEALED_LIFETIME;
    }

    public static double clamp(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }

    public static double smooth(double x) {
        double c = clamp(x);
        return c * c * (3.0 - 2.0 * c);
    }

    /** Sealed fireball almost fills the main cavity but not the fracture shell.
     * The wider horizontal radius is flattened vertically for the cavity profile.
     * Breakout retains its original smaller ignition sphere. */
    public static double fireballRadius(double scale, int age) {
        return fireballRadius(scale, age, false);
    }

    public static double fireballRadius(double scale, int age, boolean sealed) {
        double full = Math.max(3.0, scale * (sealed ? 1.15 : 0.43));
        double rise = smooth(age / (sealed ? 38.0 : 30.0));
        double retreat = 1.0 - smooth((age - 46.0) / 92.0);
        return full * (0.13 + 0.87 * rise) * Math.max(0.0, retreat);
    }

    public static double fireballVerticalRatio(boolean sealed) {
        return sealed ? 0.57 : 0.87;
    }

    /** Top of the growing column, initially at ground zero, then above the exit. */
    public static double jetTop(double originY, double exitY, double scale, int age) {
        double climb = smooth((age - JET_START_TICK)
                / (double) (JET_REACH_SURFACE_TICK - JET_START_TICK));
        double above = smooth((age - JET_REACH_SURFACE_TICK)
                / (double) (JET_FULL_HEIGHT_TICK - JET_REACH_SURFACE_TICK));
        // Extra upward drift keeps the cooling head travelling rather than
        // appearing to stop dead when the bright central jet reaches full size.
        double drift = smooth((age - JET_FULL_HEIGHT_TICK) / 90.0);
        return originY + (Math.max(exitY, originY) - originY) * climb
                + Math.max(15.0, scale * 2.0) * (above + 0.26 * drift);
    }

    /** A narrowed bottom widening along the excavation throat, then a plume. */
    public static double jetRadius(double originY, double exitY, double y, double scale) {
        double throat = Math.max(1.0, exitY - originY);
        double progress = clamp((y - originY) / throat);
        double bottom = Math.max(1.25, scale * 0.045);
        double surface = Math.max(2.8, scale * 0.17);
        if (y <= exitY) return bottom + (surface - bottom) * smooth(progress);
        double above = (y - exitY) / Math.max(15.0, scale * 2.0);
        return surface * (1.0 + 0.36 * smooth(above));
    }

    /** Fragments peel off the upper head in multiple directions, but there is
     * never a wide disc or mushroom cap. */
    public static double breakup(int age, double heightProgress) {
        double developed = smooth((age - JET_REACH_SURFACE_TICK) / 55.0);
        double nearHead = smooth((heightProgress - 0.66) / 0.34);
        return developed * nearHead;
    }

    /** Side shedding is less intense than the head and never bends the core. */
    public static double fringeAlpha(int age, double heightProgress, boolean top) {
        double body = jetAlpha(age, heightProgress, 0.95);
        return body * (top ? 0.43 : 0.30)
                * (top ? (0.55 + 0.45 * breakup(age, heightProgress)) : 1.0);
    }

    /** Hot lower jet cools first; upper jet remains incandescent briefly. */
    public static double cooling(int age, double y, double originY, double topY) {
        double progress = clamp((y - originY) / Math.max(1.0, topY - originY));
        return smooth((age - JET_COOL_START_TICK - progress * 28.0) / 87.0);
    }

    /** Edges become transparent before the middle; retreat begins at the base. */
    public static double jetAlpha(int age, double heightProgress, double radialProgress) {
        double bottomUp = smooth((age - JET_FADE_START_TICK - heightProgress * 45.0) / 70.0);
        double radialSoftness = smooth((radialProgress - 0.28) / 0.72);
        // Translucent from the very first frame, with a denser central spine.
        double base = 0.86 - 0.49 * radialSoftness;
        double opacity = 1.0 - bottomUp * (0.62 + 0.38 * radialSoftness);
        double head = 1.0 - breakup(age, heightProgress) * 0.22;
        double finalFade = 1.0 - smooth((age - (BREAKOUT_LIFETIME - 32.0)) / 32.0);
        return clamp(base * opacity * head * finalFade);
    }

    public static int mix(int from, int to, double value) {
        double blend = clamp(value);
        int r = (int) Math.round(((from >>> 16) & 255) * (1.0 - blend)
                + ((to >>> 16) & 255) * blend);
        int g = (int) Math.round(((from >>> 8) & 255) * (1.0 - blend)
                + ((to >>> 8) & 255) * blend);
        int b = (int) Math.round((from & 255) * (1.0 - blend)
                + (to & 255) * blend);
        return (r << 16) | (g << 8) | b;
    }

    public static int jetColour(int age, double heightProgress, double cooling) {
        int core = mix(0xFFD45A, 0xBD4E2A, smooth(age / 85.0));
        int smokey = mix(0x563B36, 0x666B68, smooth((age - 125.0) / 65.0));
        return mix(core, smokey, cooling);
    }
}
