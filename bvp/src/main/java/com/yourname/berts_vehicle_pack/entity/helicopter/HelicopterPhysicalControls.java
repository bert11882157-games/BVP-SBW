package com.yourname.berts_vehicle_pack.entity.helicopter;

/** Per-airframe physical input rates in inverse seconds and horizontal speed in km/h. */
public record HelicopterPhysicalControls(
        double throttlePerSecond,
        double collectivePerSecond,
        double rotorSpoolUpPerSecond,
        double rotorSpoolDownPerSecond,
        double maxHorizontalKmh,
        boolean mainEngineDamageCutsDrive,
        HelicopterAttitudeProfile attitudeProfile) {
    /** Existing profiles retain the native attitude lifecycle. */
    public HelicopterPhysicalControls(double throttlePerSecond, double collectivePerSecond,
                                     double rotorSpoolUpPerSecond, double rotorSpoolDownPerSecond,
                                     double maxHorizontalKmh, boolean mainEngineDamageCutsDrive) {
        this(throttlePerSecond, collectivePerSecond, rotorSpoolUpPerSecond,
                rotorSpoolDownPerSecond, maxHorizontalKmh, mainEngineDamageCutsDrive, null);
    }

    public HelicopterPhysicalControls {
        requirePositive(throttlePerSecond, "throttle rate");
        requirePositive(collectivePerSecond, "collective rate");
        requirePositive(rotorSpoolUpPerSecond, "rotor spool-up rate");
        requirePositive(rotorSpoolDownPerSecond, "rotor spool-down rate");
        requirePositive(maxHorizontalKmh, "horizontal speed limit");
    }

    public double nextRotorPower(double current, double target) {
        double step = (target > current ? rotorSpoolUpPerSecond : rotorSpoolDownPerSecond) / 20.0D;
        return current < target ? Math.min(target, current + step) : Math.max(target, current - step);
    }

    public double rotorTarget(double throttle, boolean mainEngineDamaged) {
        return mainEngineDamageCutsDrive && mainEngineDamaged ? 0.0D
                : Math.max(0.0D, Math.min(1.0D, throttle));
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException("Helicopter " + name + " must be finite and positive");
        }
    }
}
