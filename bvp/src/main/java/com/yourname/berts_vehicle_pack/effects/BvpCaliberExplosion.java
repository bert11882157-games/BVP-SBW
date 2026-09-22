package com.yourname.berts_vehicle_pack.effects;

/** Caliber scales presentation only; rocket and missile callers retain their existing effects. */
public final class BvpCaliberExplosion {
    private BvpCaliberExplosion() {}
    public static float diameter(double caliberMm) {
        if (!Double.isFinite(caliberMm) || caliberMm < 14.5) return 0F;
        // Keep the tiny 14.5 mm onset and the 100 mm / two-block anchor, with more
        // visible bursts for autocannons between them. Particle count stays unchanged.
        if (caliberMm <= 30.0) {
            double smallestDiameter = 2.0 * Math.pow(14.5 / 100.0, 1.5);
            double fraction = (caliberMm - 14.5) / (30.0 - 14.5);
            return (float) (smallestDiameter + fraction * (0.65 - smallestDiameter));
        }
        if (caliberMm < 100.0) {
            return (float) (0.65 + (caliberMm - 30.0) / 70.0 * (2.0 - 0.65));
        }
        return (float) Math.min(32.0, 2.0 * Math.pow(caliberMm / 100.0, 1.5));
    }
}
