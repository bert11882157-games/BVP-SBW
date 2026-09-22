package com.yourname.berts_vehicle_pack.entity.helicopter;

import net.minecraft.world.phys.Vec3;

final class HelicopterHoverController {
    private static final double MIN_UP_AXIS_Y = 0.08D;
    private static final double MAX_ASSIST_PITCH_DEGREES = 65.0D;
    private static final double ASSIST_IN_PER_TICK = 0.055D;
    private static final double ASSIST_OUT_PER_TICK = 0.140D;
    private static final double ROTOR_POWER_FLOOR = 0.84D;
    private static final double VERTICAL_TARGET_DECAY_MPS_PER_TICK = 0.145D;
    private static final double VERTICAL_BASE_STEP_MPS_PER_TICK = 0.115D;
    private static final double VERTICAL_RESPONSE_MPS_PER_TICK = 0.245D;
    private static final double VERTICAL_EMERGENCY_STEP_MPS_PER_TICK = 0.520D;
    private static final double VERTICAL_COMMAND_UP_MPS = 1.35D;
    private static final double VERTICAL_COMMAND_DOWN_MPS = -1.75D;
    private static final double MAX_HOVER_VERTICAL_MPS = 4.80D;
    private static final double HORIZONTAL_BRAKE_KMH_PER_TICK = 0.34D;
    private static final double HORIZONTAL_DAMPING = 0.965D;
    private static final double MAX_HOVER_HORIZONTAL_KMH = 42.0D;
    private static final double KMH_PER_BLOCK_PER_TICK = HelicopterFlightController.KMH_PER_BLOCK_PER_TICK;

    private double assist;
    private double verticalTargetMps;
    private boolean wasActive;

    State tick(HelicopterFlightController.Input input, double upY, double pitchDegrees, double currentVerticalMps) {
        boolean active = input.occupied
                && input.hoverAux
                && !input.wreck
                && upY >= MIN_UP_AXIS_Y
                && pitchDegrees <= MAX_ASSIST_PITCH_DEGREES;

        if (!active) {
            this.assist = approach(this.assist, 0.0D, ASSIST_OUT_PER_TICK);
            if (this.assist <= 1.0E-4D) {
                this.wasActive = false;
                this.verticalTargetMps = currentVerticalMps;
            }
            return State.inactive();
        }

        if (!this.wasActive) {
            this.verticalTargetMps = clamp(currentVerticalMps, -MAX_HOVER_VERTICAL_MPS, MAX_HOVER_VERTICAL_MPS);
            this.wasActive = true;
        }

        this.assist = approach(this.assist, 1.0D, ASSIST_IN_PER_TICK);
        if (input.collectiveUp) {
            this.verticalTargetMps = approach(this.verticalTargetMps, VERTICAL_COMMAND_UP_MPS,
                    VERTICAL_TARGET_DECAY_MPS_PER_TICK * 1.6D);
        } else if (input.collectiveDown) {
            this.verticalTargetMps = approach(this.verticalTargetMps, VERTICAL_COMMAND_DOWN_MPS,
                    VERTICAL_TARGET_DECAY_MPS_PER_TICK * 1.8D);
        } else {
            this.verticalTargetMps = approach(this.verticalTargetMps, 0.0D,
                    VERTICAL_TARGET_DECAY_MPS_PER_TICK * (0.45D + this.assist * 0.75D));
        }

        double error = Math.abs(currentVerticalMps - this.verticalTargetMps);
        double step = VERTICAL_BASE_STEP_MPS_PER_TICK + VERTICAL_RESPONSE_MPS_PER_TICK * this.assist;
        if (error > 4.0D) {
            step = Math.max(step, VERTICAL_EMERGENCY_STEP_MPS_PER_TICK);
        }

        return new State(
                true,
                this.assist,
                ROTOR_POWER_FLOOR,
                this.verticalTargetMps,
                step,
                MAX_HOVER_VERTICAL_MPS,
                HORIZONTAL_BRAKE_KMH_PER_TICK * this.assist,
                lerp(1.0D, HORIZONTAL_DAMPING, this.assist),
                MAX_HOVER_HORIZONTAL_KMH);
    }

    void reset() {
        this.assist = 0.0D;
        this.verticalTargetMps = 0.0D;
        this.wasActive = false;
    }

    private static double approach(double value, double target, double maxStep) {
        if (value < target) {
            return Math.min(target, value + maxStep);
        }
        return Math.max(target, value - maxStep);
    }

    private static double lerp(double min, double max, double factor) {
        return min + (max - min) * clamp(factor, 0.0D, 1.0D);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class State {
        static final State INACTIVE = new State(false, 0.0D, 0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 1.0D, 0.0D);

        final boolean active;
        final double assist;
        final double rotorPowerFloor;
        final double verticalTargetMps;
        final double verticalStepMpsPerTick;
        final double maxVerticalMps;
        final double horizontalBrakeKmhPerTick;
        final double horizontalDamping;
        final double maxHorizontalKmh;

        private State(boolean active, double assist, double rotorPowerFloor, double verticalTargetMps,
                      double verticalStepMpsPerTick, double maxVerticalMps, double horizontalBrakeKmhPerTick,
                      double horizontalDamping, double maxHorizontalKmh) {
            this.active = active;
            this.assist = assist;
            this.rotorPowerFloor = rotorPowerFloor;
            this.verticalTargetMps = verticalTargetMps;
            this.verticalStepMpsPerTick = verticalStepMpsPerTick;
            this.maxVerticalMps = maxVerticalMps;
            this.horizontalBrakeKmhPerTick = horizontalBrakeKmhPerTick;
            this.horizontalDamping = horizontalDamping;
            this.maxHorizontalKmh = maxHorizontalKmh;
        }

        static State inactive() {
            return INACTIVE;
        }

        Vec3 dampHorizontal(Vec3 motion, Vec3 forward, Vec3 right) {
            if (!this.active || this.assist <= 1.0E-4D) {
                return motion;
            }
            double forwardKmh = motion.m_82526_(forward) * KMH_PER_BLOCK_PER_TICK;
            double sideKmh = motion.m_82526_(right) * KMH_PER_BLOCK_PER_TICK;
            forwardKmh = approach(forwardKmh, 0.0D, this.horizontalBrakeKmhPerTick);
            sideKmh = approach(sideKmh, 0.0D, this.horizontalBrakeKmhPerTick);
            forwardKmh *= this.horizontalDamping;
            sideKmh *= this.horizontalDamping;

            double totalKmh = Math.sqrt(forwardKmh * forwardKmh + sideKmh * sideKmh);
            if (totalKmh > this.maxHorizontalKmh && totalKmh > 1.0E-4D) {
                double scale = this.maxHorizontalKmh / totalKmh;
                forwardKmh *= scale;
                sideKmh *= scale;
            }
            return forward.m_82490_(forwardKmh / KMH_PER_BLOCK_PER_TICK)
                    .m_82549_(right.m_82490_(sideKmh / KMH_PER_BLOCK_PER_TICK))
                    .m_82520_(0.0D, motion.f_82480_, 0.0D);
        }
    }
}

