package com.yourname.berts_vehicle_pack.entity.helicopter;

final class HelicopterBankController {
    private static final double MAX_ROLL_FOR_BANK_DEGREES = 65.0D;
    private static final double BANK_IN_PER_TICK = 0.075D;
    private static final double BANK_RETURN_PER_TICK = 0.11D;
    private static final double BASE_SIDE_ACCEL_KMH_PER_TICK = 0.22D;
    private static final double HOVER_SIDE_AUTHORITY = 0.28D;
    private static final double FULL_SIDE_AUTHORITY_SPEED_KMH = 36.0D;
    private static final double FORWARD_BLEED_KMH_PER_TICK = 0.055D;

    private double smoothedBank;

    Result tick(HelicopterFlightController.Input input, double forwardKmh, double rotorLiftPower) {
        if (!input.occupied || input.wreck) {
            reset();
            return Result.ZERO;
        }

        double command = commandedBank(input);
        double target = clamp(command, -1.0D, 1.0D);
        double step = Math.abs(target) > Math.abs(this.smoothedBank) ? BANK_IN_PER_TICK : BANK_RETURN_PER_TICK;
        this.smoothedBank = clamp(approach(this.smoothedBank, target, step), -1.0D, 1.0D);

        double bankAbs = Math.abs(this.smoothedBank);
        double speedAuthority = Math.max(HOVER_SIDE_AUTHORITY,
                clamp(Math.abs(forwardKmh) / FULL_SIDE_AUTHORITY_SPEED_KMH, 0.0D, 1.0D));
        double bankRadians = Math.toRadians(this.smoothedBank * MAX_ROLL_FOR_BANK_DEGREES);
        double sideAccel = Math.sin(bankRadians)
                * BASE_SIDE_ACCEL_KMH_PER_TICK
                * clamp(rotorLiftPower, 0.0D, 1.0D)
                * speedAuthority;
        double forwardBleed = bankAbs * bankAbs * FORWARD_BLEED_KMH_PER_TICK * speedAuthority
                + Math.abs(sideAccel) * 0.08D;
        double sideDragScale = 1.0D - bankAbs * 0.24D;

        return new Result(bankAbs, command, sideAccel, forwardBleed, sideDragScale);
    }

    void reset() {
        this.smoothedBank = 0.0D;
    }

    private static double commandedBank(HelicopterFlightController.Input input) {
        double rollCommand = clamp(input.rollDegrees / MAX_ROLL_FOR_BANK_DEGREES, -1.0D, 1.0D);
        double steerCommand = steerSign(input) * 0.35D;
        return Math.abs(rollCommand) >= Math.abs(steerCommand) ? rollCommand : steerCommand;
    }

    private static double steerSign(HelicopterFlightController.Input input) {
        if (input.steerLeft == input.steerRight) {
            return 0.0D;
        }
        return input.steerRight ? 1.0D : -1.0D;
    }

    private static double approach(double value, double target, double maxStep) {
        if (value < target) {
            return Math.min(target, value + maxStep);
        }
        return Math.max(target, value - maxStep);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class Result {
        static final Result ZERO = new Result(0.0D, 0.0D, 0.0D, 0.0D, 1.0D);

        final double bankIntensity;
        final double bankCommand;
        final double sideAccelKmhPerTick;
        final double forwardBleedKmhPerTick;
        final double sideDragScale;

        Result(double bankIntensity, double bankCommand, double sideAccelKmhPerTick,
               double forwardBleedKmhPerTick, double sideDragScale) {
            this.bankIntensity = bankIntensity;
            this.bankCommand = bankCommand;
            this.sideAccelKmhPerTick = sideAccelKmhPerTick;
            this.forwardBleedKmhPerTick = forwardBleedKmhPerTick;
            this.sideDragScale = sideDragScale;
        }
    }
}

