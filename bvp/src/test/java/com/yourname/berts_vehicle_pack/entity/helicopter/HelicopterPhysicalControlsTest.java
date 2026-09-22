package com.yourname.berts_vehicle_pack.entity.helicopter;

/** Focused lifecycle checks independent of a game world. */
public final class HelicopterPhysicalControlsTest {
    public static void main(String[] args) {
        HelicopterPhysicalControls controls = new HelicopterPhysicalControls(.25, 1, .3, .22, 83.75, true);
        double rotor = 0;
        for (int i = 0; i < 20; i++) rotor = controls.nextRotorPower(rotor, 1);
        near(rotor, .3, "one second spool up");
        double failedTarget = controls.rotorTarget(1, true);
        near(failedTarget, 0, "main engine failure cuts the target");
        rotor = controls.nextRotorPower(rotor, failedTarget);
        near(rotor, .289, "rotor decays without instant removal");
        for (int i = 0; i < 200; i++) rotor = controls.nextRotorPower(rotor, failedTarget);
        near(rotor, 0, "spool cannot cross zero");
        for (int i = 0; i < 200; i++) rotor = controls.nextRotorPower(rotor, 1);
        near(rotor, 1, "spool cannot cross target");
        near(controls.rotorTarget(-1, false), 0, "negative throttle");
        near(controls.rotorTarget(2, false), 1, "throttle upper bound");
        for (HelicopterFlightProfile profile : new HelicopterFlightProfile[] {
                HelicopterFlightProfile.mi24v(), HelicopterFlightProfile.mi28n(),
                HelicopterFlightProfile.ka50(), HelicopterFlightProfile.ah6j(),
                HelicopterFlightProfile.ah1gCobra()}) {
            if (profile.physicalControls() != null) throw new AssertionError("Legacy controls changed");
        }
        for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            try {
                new HelicopterPhysicalControls(invalid, 1, .3, .22, 83.75, true);
                throw new AssertionError("Invalid control rate accepted");
            } catch (IllegalArgumentException expected) {
                // Invalid source rates fail before a controller can consume them.
            }
        }
        System.out.println("PASS helicopter physical controls lifecycle and legacy opt-out");
    }

    private static void near(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > 1e-12) {
            throw new AssertionError(message + ": " + actual + " != " + expected);
        }
    }
}
