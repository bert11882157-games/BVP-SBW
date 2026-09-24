package com.yourname.berts_vehicle_pack.effects;

/** Uses the same caliber scale as authored vehicle ShootShake, with a restrained aircraft cap. */
public final class BvpImpactShakeScale {
    private BvpImpactShakeScale() { }

    public static double amplitude(double caliberMm, boolean aircraft) {
        if (!Double.isFinite(caliberMm) || caliberMm <= 0) return 0;
        // Small-arms recoil is absent in vehicle guns, but a received hit still needs feedback.
        double ground = caliberMm < 12.7 ? 3 : caliberMm == 12.7 ? 6
                : 60 * Math.max(.20, 1 + (caliberMm - 75) * .02);
        return aircraft ? Math.min(12, ground * .18) : ground;
    }
}
