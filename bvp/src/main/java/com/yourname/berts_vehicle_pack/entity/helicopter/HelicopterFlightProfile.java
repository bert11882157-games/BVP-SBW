package com.yourname.berts_vehicle_pack.entity.helicopter;

public final class HelicopterFlightProfile {
    private static final double DEFAULT_MASS_KG = 11500.0D;
    private static final double DEFAULT_MAIN_ROTOR_ACCEL_MPS2 = 14.2D;
    private static final double DEFAULT_TAIL_ROTOR_ACCEL_MPS2 = 1.9D;
    private static final double DEFAULT_MAIN_ROTOR_FORCE_N =
            DEFAULT_MASS_KG * DEFAULT_MAIN_ROTOR_ACCEL_MPS2;
    private static final double DEFAULT_TAIL_ROTOR_FORCE_N =
            DEFAULT_MASS_KG * DEFAULT_TAIL_ROTOR_ACCEL_MPS2;
    private static final double MI24_MAIN_ROTOR_FORCE_N = 110000.0D;
    private static final double MI24_ENGINE_POWER_SCALE =
            MI24_MAIN_ROTOR_FORCE_N / DEFAULT_MAIN_ROTOR_FORCE_N;
    private static final double MI28_MAIN_ROTOR_FORCE_N = 115000.0D;
    private static final double MI28_ENGINE_POWER_SCALE =
            MI28_MAIN_ROTOR_FORCE_N / DEFAULT_MAIN_ROTOR_FORCE_N;
    private static final double KA50_MAIN_ROTOR_FORCE_N = 115000.0D;
    private static final double KA50_ENGINE_POWER_SCALE =
            KA50_MAIN_ROTOR_FORCE_N / DEFAULT_MAIN_ROTOR_FORCE_N;
    private static final double AH6J_ENGINE_POWER_SCALE = 0.14D;
    private static final double AH1G_ENGINE_POWER_SCALE = 0.30D;
    private static final double DEFAULT_FORWARD_LINEAR_DRAG = 0.050D;
    private static final double DEFAULT_FORWARD_QUADRATIC_DRAG = 0.0052D;
    private static final double DEFAULT_SIDE_LINEAR_DRAG = 0.135D;
    private static final double DEFAULT_SIDE_QUADRATIC_DRAG = 0.0125D;
    private static final double DEFAULT_VERTICAL_LINEAR_DRAG = 0.055D;
    private static final double DEFAULT_VERTICAL_QUADRATIC_DRAG = 0.0080D;
    private static final HelicopterFlightProfile MI24V = new HelicopterFlightProfile(72.0D, 94.0D,
            9000.0D,
            MI24_ENGINE_POWER_SCALE, 0.20D,
            HelicopterHandlingProfile.mi24v(),
            DEFAULT_FORWARD_LINEAR_DRAG, DEFAULT_FORWARD_QUADRATIC_DRAG,
            DEFAULT_SIDE_LINEAR_DRAG, DEFAULT_SIDE_QUADRATIC_DRAG,
            DEFAULT_VERTICAL_LINEAR_DRAG, DEFAULT_VERTICAL_QUADRATIC_DRAG,
            2.8D, 4.3D);
    private static final HelicopterFlightProfile MI28N = new HelicopterFlightProfile(89.0D, 112.0D,
            8250.0D,
            MI28_ENGINE_POWER_SCALE, 0.25D,
            HelicopterHandlingProfile.mi28n(),
            0.038D, 0.0030D,
            0.105D, 0.0075D,
            0.050D, 0.0070D,
            4.0D, 6.6D);
    private static final HelicopterFlightProfile KA50 = new HelicopterFlightProfile(89.0D, 112.0D,
            8250.0D,
            KA50_ENGINE_POWER_SCALE, 0.25D,
            HelicopterHandlingProfile.ka50(),
            0.038D, 0.0030D,
            0.105D, 0.0075D,
            0.050D, 0.0070D,
            4.0D, 6.6D);
    private static final HelicopterFlightProfile AH6J = new HelicopterFlightProfile(175.0D, 205.0D,
            1600.0D,
            AH6J_ENGINE_POWER_SCALE, 0.08D,
            HelicopterHandlingProfile.ah6j(),
            0.050D, 0.0052D,
            0.135D, 0.0125D,
            0.055D, 0.0080D,
            4.0D, 7.0D);
    private static final HelicopterFlightProfile AH1G_COBRA = new HelicopterFlightProfile(210.0D, 235.0D,
            4300.0D,
            AH1G_ENGINE_POWER_SCALE, 0.15D,
            HelicopterHandlingProfile.ah1gCobra(),
            0.050D, 0.0052D,
            0.135D, 0.0125D,
            0.055D, 0.0080D,
            4.0D, 7.0D);

    private static final HelicopterFlightProfile MI24A = new HelicopterFlightProfile(
            75.375D, 83.75D, 10320.0D, 130000.0D / 163300.0D, 0.20D,
            HelicopterHandlingProfile.of(0.95D, 0.90D, 0.75D, 0.90D, 0.72D, 1.15D),
            0.030D, 0.0007007756330102947D, 0.135D, 0.0125D,
            0.055D, 0.0080D, 2.38D, 2.80D,
            new HelicopterPhysicalControls(.25D, 1.00D, .30D, .22D, 83.75D, true,
                    new HelicopterAttitudeProfile(18.0D, 16.0D, 28.0D, 3.0D, 60.0D, 75.0D, 8.0D)));
    private static final HelicopterFlightProfile MI24D = new HelicopterFlightProfile(
            75.375D, 83.75D, 10690.0D, 130000.0D / 163300.0D, 0.20D,
            HelicopterHandlingProfile.of(0.97D, 0.92D, 0.78D, 0.92D, 0.76D, 1.18D),
            0.030D, 0.0007727918703541040D, 0.145D, 0.0130D,
            0.060D, 0.0090D, 2.38D, 2.80D,
            new HelicopterPhysicalControls(.24D, .95D, .28D, .20D, 83.75D, true,
                    new HelicopterAttitudeProfile(20.0D, 18.0D, 30.0D, 3.2D, 60.0D, 75.0D, 8.0D)));
    private static final HelicopterFlightProfile AH1F = new HelicopterFlightProfile(
            62.325D, 69.25D, 3990.0D, 52000.0D / 163300.0D, 0.12D,
            HelicopterHandlingProfile.of(1.05D, 1.05D, 0.98D, 1.04D, 1.08D, 0.98D),
            0.040D, 0.004229106199018914D, 0.130D, 0.0110D,
            0.050D, 0.0075D, 1.7425D, 2.05D,
            new HelicopterPhysicalControls(.33D, 1.10D, .40D, .32D, 69.25D, true,
                    new HelicopterAttitudeProfile(28.0D, 28.0D, 42.0D, 4.0D, 65.0D, 80.0D, 8.0D)));
    private static final HelicopterFlightProfile MI26 = new HelicopterFlightProfile(
            63.75D, 67.50D, 56000.0D, 650000.0D / 163300.0D, 0.0D,
            HelicopterHandlingProfile.of(0.65D, 0.82D, 0.62D, 0.68D, 0.55D, 1.85D),
            0.015D, 0.001130561929995938D, 0.120D, 0.0160D,
            0.070D, 0.0120D, 1.25D, 1.75D,
            new HelicopterPhysicalControls(.12D, .40D, .10D, .08D, 67.50D, true,
                    new HelicopterAttitudeProfile(8.0D, 8.0D, 12.0D, 1.5D, 35.0D, 50.0D, 8.0D)));

    final double maxForwardKmh;
    final double maxBoostForwardKmh;
    final double massKg;
    final double enginePowerScale;
    final double wingLiftCoefficient;
    final HelicopterHandlingProfile handlingProfile;
    final double maxMainRotorForceN;
    final double maxTailRotorForceN;
    final double maxMainRotorAccelMps2;
    final double maxTailRotorAccelMps2;
    final double climbSoftCapMps;
    final double climbHardCapMps;
    final double forwardLinearDrag;
    final double forwardQuadraticDrag;
    final double sideLinearDrag;
    final double sideQuadraticDrag;
    final double verticalLinearDrag;
    final double verticalQuadraticDrag;
    private final HelicopterPhysicalControls physicalControls;

    private HelicopterFlightProfile(double maxForwardKmh, double maxBoostForwardKmh,
                                    double massKg, double enginePowerScale,
                                    double wingLiftCoefficient, HelicopterHandlingProfile handlingProfile,
                                    double forwardLinearDrag,
                                    double forwardQuadraticDrag, double sideLinearDrag,
                                    double sideQuadraticDrag, double verticalLinearDrag,
                                    double verticalQuadraticDrag,
                                    double climbSoftCapMps, double climbHardCapMps) {
        this(maxForwardKmh, maxBoostForwardKmh, massKg, enginePowerScale, wingLiftCoefficient,
                handlingProfile, forwardLinearDrag, forwardQuadraticDrag, sideLinearDrag,
                sideQuadraticDrag, verticalLinearDrag, verticalQuadraticDrag,
                climbSoftCapMps, climbHardCapMps, null);
    }

    private HelicopterFlightProfile(double maxForwardKmh, double maxBoostForwardKmh,
                                    double massKg, double enginePowerScale,
                                    double wingLiftCoefficient, HelicopterHandlingProfile handlingProfile,
                                    double forwardLinearDrag,
                                    double forwardQuadraticDrag, double sideLinearDrag,
                                    double sideQuadraticDrag, double verticalLinearDrag,
                                    double verticalQuadraticDrag,
                                    double climbSoftCapMps, double climbHardCapMps,
                                    HelicopterPhysicalControls physicalControls) {
        double safeMassKg = Math.max(1000.0D, massKg);
        double safeEnginePowerScale = Math.max(0.05D, enginePowerScale);
        this.maxForwardKmh = maxForwardKmh;
        this.maxBoostForwardKmh = maxBoostForwardKmh;
        this.massKg = safeMassKg;
        this.enginePowerScale = safeEnginePowerScale;
        this.wingLiftCoefficient = Math.max(0.0D, wingLiftCoefficient);
        this.handlingProfile = handlingProfile == null ? HelicopterHandlingProfile.DEFAULT : handlingProfile;
        this.maxMainRotorForceN = DEFAULT_MAIN_ROTOR_FORCE_N * safeEnginePowerScale;
        this.maxTailRotorForceN = DEFAULT_TAIL_ROTOR_FORCE_N * safeEnginePowerScale;
        this.maxMainRotorAccelMps2 = this.maxMainRotorForceN / safeMassKg;
        this.maxTailRotorAccelMps2 = this.maxTailRotorForceN / safeMassKg;
        this.climbSoftCapMps = climbSoftCapMps;
        this.climbHardCapMps = climbHardCapMps;
        this.forwardLinearDrag = forwardLinearDrag;
        this.forwardQuadraticDrag = forwardQuadraticDrag;
        this.sideLinearDrag = sideLinearDrag;
        this.sideQuadraticDrag = sideQuadraticDrag;
        this.verticalLinearDrag = verticalLinearDrag;
        this.verticalQuadraticDrag = verticalQuadraticDrag;
        this.physicalControls = physicalControls;
    }

    /** Null preserves the legacy per-tick constants without a rate conversion. */
    public HelicopterPhysicalControls physicalControls() {
        return physicalControls;
    }

    public static HelicopterFlightProfile mi24v() {
        return MI24V;
    }

    public static HelicopterFlightProfile mi28n() {
        return MI28N;
    }

    public static HelicopterFlightProfile ka50() {
        return KA50;
    }

    public static HelicopterFlightProfile ah6j() {
        return AH6J;
    }

    public static HelicopterFlightProfile ah1gCobra() {
        return AH1G_COBRA;
    }

    public static HelicopterFlightProfile mi24a() {
        return MI24A;
    }

    public static HelicopterFlightProfile mi24d() {
        return MI24D;
    }

    public static HelicopterFlightProfile ah1f() {
        return AH1F;
    }

    public static HelicopterFlightProfile mi26() {
        return MI26;
    }

    public double maxForwardKmh() {
        return maxForwardKmh;
    }

    public double maxBoostForwardKmh() {
        return maxBoostForwardKmh;
    }

    public double massKg() {
        return massKg;
    }

    public double enginePowerScale() {
        return enginePowerScale;
    }

    public double wingLiftCoefficient() {
        return wingLiftCoefficient;
    }

    public double tailRotorAuthorityScale() {
        return handlingProfile.tailRotorAuthorityScale;
    }

    public double cyclicAuthorityScale() {
        return handlingProfile.cyclicAuthorityScale;
    }

    public double pitchResponseScale() {
        return handlingProfile.pitchResponseScale;
    }

    public double rollResponseScale() {
        return handlingProfile.rollResponseScale;
    }

    public double yawResponseScale() {
        return handlingProfile.yawResponseScale;
    }

    public double rotationalInertiaScale() {
        return handlingProfile.rotationalInertiaScale;
    }

    public double maxThrottle() {
        return handlingProfile.maxThrottle;
    }

    public double turnLeftModifier() {
        return handlingProfile.turnLeftModifier;
    }

    public double turnRightModifier() {
        return handlingProfile.turnRightModifier;
    }

    public double maxMainRotorForceN() {
        return maxMainRotorForceN;
    }

    public double maxTailRotorForceN() {
        return maxTailRotorForceN;
    }

    public double maxMainRotorAccelMps2() {
        return maxMainRotorAccelMps2;
    }

    public double maxTailRotorAccelMps2() {
        return maxTailRotorAccelMps2;
    }

    public double climbSoftCapMps() {
        return climbSoftCapMps;
    }

    public double climbHardCapMps() {
        return climbHardCapMps;
    }
}
