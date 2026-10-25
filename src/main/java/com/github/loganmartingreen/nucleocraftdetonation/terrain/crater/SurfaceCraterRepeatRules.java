package com.github.loganmartingreen.nucleocraftdetonation.terrain.crater;

/** Pure matching rule shared by server-side crater history and regression tests. */
public final class SurfaceCraterRepeatRules {
    private SurfaceCraterRepeatRules() { }

    /** The column was part of an older same-size-or-larger crater footprint.
     * The new crater may cut fresh ground outside this area, but it must not
     * drill into the old basin again by using its lowered surface as a datum.
     */
    public static boolean protectsColumn(int priorX, int priorZ, int priorRadius,
                                         int newRadius, int columnX, int columnZ) {
        if (priorRadius <= 0 || newRadius <= 0 || priorRadius < newRadius) {
            return false;
        }
        double dx = columnX - priorX + 0.5D;
        double dz = columnZ - priorZ + 0.5D;
        // The thin outer edge is unaffected by crater excavation anyway;
        // matching the full radius avoids a dangerous unprotected overlap.
        return dx * dx + dz * dz <= (double) priorRadius * priorRadius;
    }

    public static boolean sameSite(int priorX, int priorZ, int priorRadius,
                                   int newX, int newZ, int newRadius) {
        if (priorRadius < newRadius || newRadius <= 0) return false;
        // A crater's broad central basin is the same site even when the
        // player aims at a different block on its newly exposed floor.
        double tolerance = Math.max(4.0D, Math.min(priorRadius, newRadius) * 0.55D);
        return Math.hypot((double) priorX - newX, (double) priorZ - newZ)
                <= tolerance;
    }
}
