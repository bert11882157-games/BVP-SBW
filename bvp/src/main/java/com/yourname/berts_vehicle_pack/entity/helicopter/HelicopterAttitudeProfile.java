package com.yourname.berts_vehicle_pack.entity.helicopter;

/**
 * Physical rotor-coupled attitude ceilings. Rates are degrees/second at full rotor power and
 * unit EngineInfo authority; response is inverse seconds. Values are engineering calibration,
 * not rates inferred from dimensionless handling modifiers or published aircraft limits.
 */
public record HelicopterAttitudeProfile(
        double pitchRateDegreesPerSecond,
        double yawRateDegreesPerSecond,
        double rollRateDegreesPerSecond,
        double responsePerSecond,
        double maximumPitchDegrees,
        double maximumRollDegrees,
        double fullScaleMouseSample) {
    public HelicopterAttitudeProfile {
        requireRange(pitchRateDegreesPerSecond, 0.0D, 180.0D, "pitch rate");
        requireRange(yawRateDegreesPerSecond, 0.0D, 180.0D, "yaw rate");
        requireRange(rollRateDegreesPerSecond, 0.0D, 180.0D, "roll rate");
        requireRange(responsePerSecond, 0.0D, 20.0D, "response");
        requireRange(maximumPitchDegrees, 0.0D, 85.0D, "pitch limit");
        requireRange(maximumRollDegrees, 0.0D, 85.0D, "roll limit");
        requireRange(fullScaleMouseSample, 0.0D, 512.0D, "mouse scale");
    }

    private static void requireRange(double value, double lowerExclusive,
                                     double upperInclusive, String name) {
        if (!Double.isFinite(value) || value <= lowerExclusive || value > upperInclusive) {
            throw new IllegalArgumentException("Invalid helicopter attitude " + name);
        }
    }
}
