package com.yourname.berts_vehicle_pack.entity.helicopter;

/**
 * Allocation-free attitude rate state for an explicitly rotor-coupled helicopter.
 * It calculates one pose only: no entity, velocity, force, gravity, world or camera access.
 * Positive native pitch is nose-down; positive mouse X yaws positive and banks negative.
 */
public final class HelicopterAttitudeController {
    private static final double DT = 1.0D / 20.0D;
    private static final double HOVER_LEVEL_PER_SECOND = 0.6D;
    private long lastServerTick = Long.MIN_VALUE;
    private double yaw, pitch, roll;
    private double yawRate, pitchRate, rollRate;

    public void reset() {
        lastServerTick = Long.MIN_VALUE;
        yawRate = pitchRate = rollRate = 0.0D;
    }

    /**
     * EngineInfo PitchSpeed/YawSpeed/RollSpeed are [0,1] authority fractions for this
     * opt-in path, not native per-tick multipliers. Duplicate ticks and invalid samples
     * do not install a pose. A discontinuity resets rates, never the supplied body pose.
     */
    public boolean step(HelicopterAttitudeProfile profile, long serverTick,
                        double currentYaw, double currentPitch, double currentRoll,
                        double rotorPower, boolean controlsEnabled, boolean grounded,
                        boolean hoverMode, boolean tailRotorDamaged,
                        double mouseX, double mouseY, boolean left, boolean right,
                        double pitchAuthority, double yawAuthority, double rollAuthority) {
        if (serverTick == lastServerTick) return false;
        if (profile == null || !Double.isFinite(currentYaw) || !Double.isFinite(currentPitch)
                || !Double.isFinite(currentRoll) || !Double.isFinite(mouseX)
                || !Double.isFinite(mouseY) || !unit(rotorPower)
                || !unit(pitchAuthority) || !unit(yawAuthority) || !unit(rollAuthority)) {
            reset();
            return false;
        }
        double yawObservationTolerance = Math.max(1.0E-3D, 2.0D * Math.ulp((float) currentYaw));
        if (lastServerTick == Long.MIN_VALUE || serverTick != lastServerTick + 1L
                || Math.abs(wrapDegrees(currentYaw - yaw)) > yawObservationTolerance
                || Math.abs(currentPitch - pitch) > 1.0E-3D
                || Math.abs(wrapDegrees(currentRoll - roll)) > 1.0E-3D) {
            yawRate = pitchRate = rollRate = 0.0D;
        }
        lastServerTick = serverTick;

        double rotor = controlsEnabled ? rotorPower : 0.0D;
        double pitchLimit = profile.pitchRateDegreesPerSecond() * rotor * pitchAuthority;
        double rollLimit = profile.rollRateDegreesPerSecond() * rotor * rollAuthority;
        double yawLimit = profile.yawRateDegreesPerSecond() * rotor * yawAuthority
                * (tailRotorDamaged ? 0.0D : 1.0D) * (grounded ? 0.05D : 1.0D);
        double mx = clamp(mouseX / profile.fullScaleMouseSample(), -1.0D, 1.0D);
        double my = clamp(mouseY / profile.fullScaleMouseSample(), -1.0D, 1.0D);
        double keyboardRoll = left == right ? 0.0D : (right ? 1.0D : -1.0D);
        double pitchTarget = my * pitchLimit;
        double rollTarget = clamp(keyboardRoll - 0.25D * mx, -1.0D, 1.0D) * rollLimit;
        if (hoverMode && !grounded) {
            if (my == 0.0D) pitchTarget = clamp(-currentPitch * HOVER_LEVEL_PER_SECOND,
                    -pitchLimit, pitchLimit);
            if (keyboardRoll == 0.0D && mx == 0.0D) {
                rollTarget = clamp(-currentRoll * HOVER_LEVEL_PER_SECOND, -rollLimit, rollLimit);
            }
        }
        double response = 1.0D - Math.exp(-profile.responsePerSecond() * DT);
        pitchRate = grounded ? 0.0D : nextRate(pitchRate, pitchTarget, response, pitchLimit);
        rollRate = grounded ? 0.0D : nextRate(rollRate, rollTarget, response, rollLimit);
        yawRate = nextRate(yawRate, mx * yawLimit, response, yawLimit);

        yaw = currentYaw + yawRate * DT;
        pitch = boundedAxis(currentPitch, pitchRate * DT, profile.maximumPitchDegrees());
        roll = boundedAxis(currentRoll, rollRate * DT, profile.maximumRollDegrees());
        return true;
    }

    public double yaw() { return yaw; }
    public double pitch() { return pitch; }
    public double roll() { return roll; }
    public double yawRateDegreesPerSecond() { return yawRate; }
    public double pitchRateDegreesPerSecond() { return pitchRate; }
    public double rollRateDegreesPerSecond() { return rollRate; }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= 1.0D;
    }

    private static double nextRate(double current, double target, double response, double limit) {
        return clamp(current + (target - current) * response, -limit, limit);
    }

    private static double boundedAxis(double current, double step, double limit) {
        // An externally displaced body may recover, but is never snapped to a limit.
        if (current > limit) return current + Math.min(step, 0.0D);
        if (current < -limit) return current + Math.max(step, 0.0D);
        return clamp(current + step, -limit, limit);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double wrapDegrees(double value) {
        double wrapped = value % 360.0D;
        if (wrapped >= 180.0D) wrapped -= 360.0D;
        if (wrapped < -180.0D) wrapped += 360.0D;
        return wrapped;
    }
}
