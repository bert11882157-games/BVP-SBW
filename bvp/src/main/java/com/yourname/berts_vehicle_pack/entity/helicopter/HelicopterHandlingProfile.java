package com.yourname.berts_vehicle_pack.entity.helicopter;

public final class HelicopterHandlingProfile {
    public static final HelicopterHandlingProfile DEFAULT =
            new HelicopterHandlingProfile(1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D,
                    1.0D, 1.0D, 1.0D);
    private static final HelicopterHandlingProfile MI24V = new HelicopterHandlingProfile(
            0.95D, 0.90D, 0.75D, 0.90D, 0.72D, 1.15D,
            1.0D, 1.0D, 1.0D);
    private static final HelicopterHandlingProfile MI28N = new HelicopterHandlingProfile(
            1.10D, 1.10D, 1.10D, 1.12D, 1.12D, 0.92D,
            1.0D, 1.0D, 1.0D);
    private static final HelicopterHandlingProfile KA50 = new HelicopterHandlingProfile(
            1.10D, 1.10D, 1.10D, 1.12D, 1.12D, 0.92D,
            1.0D, 1.0D, 1.0D);
    private static final HelicopterHandlingProfile AH6J = new HelicopterHandlingProfile(
            1.08D, 1.12D, 1.08D, 1.10D, 1.15D, 0.80D,
            2.5D, 1.15D, 1.15D);
    private static final HelicopterHandlingProfile AH1G_COBRA = new HelicopterHandlingProfile(
            1.15D, 1.05D, 1.10D, 1.08D, 1.15D, 0.90D,
            2.5D, 1.15D, 1.15D);

    final double tailRotorAuthorityScale;
    final double cyclicAuthorityScale;
    final double pitchResponseScale;
    final double rollResponseScale;
    final double yawResponseScale;
    final double rotationalInertiaScale;
    /** TaP-compatible input metadata; the BVP flight solver consumes normalized input. */
    final double maxThrottle;
    final double turnLeftModifier;
    final double turnRightModifier;

    private HelicopterHandlingProfile(double tailRotorAuthorityScale,
                                      double cyclicAuthorityScale,
                                      double pitchResponseScale,
                                      double rollResponseScale,
                                      double yawResponseScale,
                                      double rotationalInertiaScale,
                                      double maxThrottle,
                                      double turnLeftModifier,
                                      double turnRightModifier) {
        this.tailRotorAuthorityScale = sanitizeScale(tailRotorAuthorityScale);
        this.cyclicAuthorityScale = sanitizeScale(cyclicAuthorityScale);
        this.pitchResponseScale = sanitizeScale(pitchResponseScale);
        this.rollResponseScale = sanitizeScale(rollResponseScale);
        this.yawResponseScale = sanitizeScale(yawResponseScale);
        this.rotationalInertiaScale = sanitizeScale(rotationalInertiaScale);
        this.maxThrottle = Math.max(0.0D, maxThrottle);
        this.turnLeftModifier = sanitizeScale(turnLeftModifier);
        this.turnRightModifier = sanitizeScale(turnRightModifier);
    }

    public static HelicopterHandlingProfile mi24v() {
        return MI24V;
    }

    public static HelicopterHandlingProfile mi28n() {
        return MI28N;
    }

    public static HelicopterHandlingProfile ka50() {
        return KA50;
    }

    public static HelicopterHandlingProfile ah6j() {
        return AH6J;
    }

    public static HelicopterHandlingProfile ah1gCobra() {
        return AH1G_COBRA;
    }

    public static HelicopterHandlingProfile of(double tailRotorAuthorityScale,
                                               double cyclicAuthorityScale,
                                               double pitchResponseScale,
                                               double rollResponseScale,
                                               double yawResponseScale,
                                               double rotationalInertiaScale) {
        return new HelicopterHandlingProfile(tailRotorAuthorityScale, cyclicAuthorityScale,
                pitchResponseScale, rollResponseScale, yawResponseScale, rotationalInertiaScale,
                1.0D, 1.0D, 1.0D);
    }

    public double maxThrottle() {
        return maxThrottle;
    }

    public double turnLeftModifier() {
        return turnLeftModifier;
    }

    public double turnRightModifier() {
        return turnRightModifier;
    }

    private static double sanitizeScale(double value) {
        return Math.max(0.05D, value);
    }
}
