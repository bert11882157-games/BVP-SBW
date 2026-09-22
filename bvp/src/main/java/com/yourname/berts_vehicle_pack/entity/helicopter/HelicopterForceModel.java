package com.yourname.berts_vehicle_pack.entity.helicopter;

import net.minecraft.world.phys.Vec3;

/**
 * Dimensionally consistent helicopter force authority for the V2 flight model.
 *
 * <p>The model computes acceleration in real units first, then converts the
 * result back to Minecraft motion. Gravity is world-space and rotor thrust is
 * aircraft-space, so pitch and roll change the direction of lift naturally.</p>
 */
public final class HelicopterForceModel {
    public static final double TICKS_PER_SECOND = 20.0D;
    public static final double GRAVITY_MPS2 = 9.80665D;
    private static final double COLLECTIVE_NEUTRAL = 0.50D;
    private static final double COLLECTIVE_ZERO_LIFT = 0.05D;
    private static final double COLLECTIVE_DOWN_EXPONENT = 1.45D;
    private static final double COLLECTIVE_UP_EXPONENT = 1.28D;
    private static final double ACCEL_TO_BLOCKS_PER_TICK_DELTA =
            1.0D / (TICKS_PER_SECOND * TICKS_PER_SECOND);
    private static final Vec3 WORLD_FORWARD = new Vec3(0.0D, 0.0D, 1.0D);
    private static final Vec3 WORLD_RIGHT = new Vec3(1.0D, 0.0D, 0.0D);
    private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
    private static final Vec3 GRAVITY_ACCEL = new Vec3(0.0D, -GRAVITY_MPS2, 0.0D);
    private static final Vec3 ZERO = new Vec3(0.0D, 0.0D, 0.0D);

    private final HelicopterFlightProfile profile;

    public HelicopterForceModel(HelicopterFlightProfile profile) {
        this.profile = profile == null ? HelicopterFlightProfile.mi24v() : profile;
    }

    public Result evaluate(HelicopterFlightController.Input input, HelicopterFlightController.Result controller) {
        if (input == null) {
            return Result.zero();
        }

        boolean hasFlightAuthority = !input.wreck;
        Vec3 forward = safeNormalize(input.forward, WORLD_FORWARD);
        Vec3 right = safeNormalize(input.right, WORLD_RIGHT);
        Vec3 baseMotion = collisionAwareBaseMotion(input.previousMotion, input.requestedMotion);
        Vec3 velocityMps = blocksPerTickToMetersPerSecond(baseMotion);
        double rotorPower = hasFlightAuthority
                ? (controller == null ? clamp(input.rotorLiftPower, 0.0D, 1.0D)
                : clamp(controller.rotorLiftPower, 0.0D, 1.0D))
                : 0.0D;
        double collective = hasFlightAuthority
                ? (controller == null ? clamp(input.enginePower, 0.0D, 1.0D)
                : clamp(controller.collectiveTarget, 0.0D, 1.0D))
                : 0.0D;
        double liftPower = clamp(rotorPower * collectiveLiftScale(collective), 0.0D, 1.0D);

        Vec3 mainRotorAxis = mainRotorAxis(input, forward, right);
        Vec3 tailRotorAxis = tailRotorAxis(input, right);
        double steering = steeringCommand(input);
        Vec3 gravityAccel = GRAVITY_ACCEL;
        double pitchDegrees = pitchDegrees(input);
        Vec3 mainRotorAccel = hasFlightAuthority && input.occupied
                ? scaledMainRotorAcceleration(mainRotorAxis, forward, right, liftPower, pitchDegrees)
                : ZERO;
        Vec3 wingLiftAccel = hasFlightAuthority && input.occupied
                ? wingLiftAcceleration(velocityMps, forward, mainRotorAxis, liftPower, pitchDegrees)
                : ZERO;
        Vec3 tailRotorAccel = hasFlightAuthority && input.occupied
                ? tailRotorAxis.m_82490_(steering * tailRotorAccelerationMagnitude(rotorPower))
                : ZERO;
        Vec3 dragAccel = dragAcceleration(velocityMps, forward, right);
        Vec3 netAccel = gravityAccel.m_82549_(mainRotorAccel).m_82549_(wingLiftAccel)
                .m_82549_(tailRotorAccel).m_82549_(dragAccel);
        Vec3 rawPredictedMotion = baseMotion.m_82549_(netAccel.m_82490_(ACCEL_TO_BLOCKS_PER_TICK_DELTA));
        Vec3 predictedMotion = applyVerticalEnvelope(rawPredictedMotion);
        Vec3 finalNetAccel = predictedMotion.m_82549_(baseMotion.m_82490_(-1.0D))
                .m_82490_(1.0D / ACCEL_TO_BLOCKS_PER_TICK_DELTA);

        double forwardMps = velocityMps.m_82526_(forward);
        double sideMps = velocityMps.m_82526_(right);
        double verticalMps = velocityMps.f_82480_;
        return new Result(
                mainRotorAxis,
                tailRotorAxis,
                velocityMps,
                gravityAccel,
                mainRotorAccel,
                wingLiftAccel,
                tailRotorAccel,
                dragAccel,
                finalNetAccel,
                predictedMotion,
                collective,
                rotorPower,
                pitchDegrees,
                input.rollDegrees,
                forwardMps * 3.6D,
                sideMps * 3.6D,
                verticalMps,
                predictedMotion.m_82526_(forward) * HelicopterFlightController.KMH_PER_BLOCK_PER_TICK,
                predictedMotion.m_82526_(right) * HelicopterFlightController.KMH_PER_BLOCK_PER_TICK,
                predictedMotion.f_82480_ * TICKS_PER_SECOND,
                mainRotorAccel.m_82526_(WORLD_UP),
                finalNetAccel.m_82526_(WORLD_UP),
                this.profile.massKg,
                mainRotorAccel.m_82553_() * this.profile.massKg,
                finalNetAccel.m_82553_() * this.profile.massKg
        );
    }

    private Vec3 mainRotorAxis(HelicopterFlightController.Input input, Vec3 forward, Vec3 right) {
        Vec3 aircraftUp = safeNormalize(input.up, null);
        if (aircraftUp != null) {
            return aircraftUp;
        }
        double pitchRadians = Math.toRadians(clamp(pitchDegrees(input), -75.0D, 75.0D));
        double rollRadians = Math.toRadians(clamp(input.rollDegrees, -135.0D, 135.0D));
        double pitchVertical = Math.cos(pitchRadians);
        double forwardTilt = Math.sin(pitchRadians);
        double rollSideTilt = Math.sin(rollRadians) * Math.max(0.0D, pitchVertical);
        double verticalTilt = Math.cos(rollRadians) * Math.max(0.0D, pitchVertical);
        Vec3 fallbackAxis = forward.m_82490_(forwardTilt)
                .m_82549_(right.m_82490_(rollSideTilt))
                .m_82549_(WORLD_UP.m_82490_(verticalTilt));
        return safeNormalize(fallbackAxis, WORLD_UP);
    }

    private static Vec3 tailRotorAxis(HelicopterFlightController.Input input, Vec3 right) {
        Vec3 fallback = right == null ? WORLD_RIGHT : right;
        return safeNormalize(fallback, WORLD_RIGHT);
    }

    private Vec3 scaledMainRotorAcceleration(Vec3 mainRotorAxis, Vec3 forward, Vec3 right,
                                             double rotorPower, double pitchDegrees) {
        double baseAccel = mainRotorAccelerationMagnitude(rotorPower);
        if (baseAccel <= 0.0D) {
            return ZERO;
        }

        double forwardComponent = mainRotorAxis.m_82526_(forward);
        double sideComponent = mainRotorAxis.m_82526_(right);
        double verticalComponent = mainRotorAxis.f_82480_;
        double pitchAuthority = cyclicAuthority() * this.profile.handlingProfile.pitchResponseScale;
        double rollAuthority = cyclicAuthority() * this.profile.handlingProfile.rollResponseScale;
        double verticalLiftEfficiency = noseDownLiftEfficiency(pitchDegrees);

        return forward.m_82490_(forwardComponent * baseAccel * pitchAuthority)
                .m_82549_(right.m_82490_(sideComponent * baseAccel * rollAuthority))
                .m_82549_(WORLD_UP.m_82490_(verticalComponent * baseAccel * verticalLiftEfficiency));
    }

    private double mainRotorAccelerationMagnitude(double rotorPower) {
        return rotorPower * this.profile.maxMainRotorForceN / this.profile.massKg;
    }

    private double collectiveLiftScale(double collective) {
        collective = clamp(collective, 0.0D, 1.0D);
        double neutralLift = neutralCollectiveLiftScale();
        if (collective <= COLLECTIVE_NEUTRAL) {
            double factor = collective / COLLECTIVE_NEUTRAL;
            factor = 1.0D - Math.pow(1.0D - factor, COLLECTIVE_DOWN_EXPONENT);
            return COLLECTIVE_ZERO_LIFT + (neutralLift - COLLECTIVE_ZERO_LIFT) * factor;
        }
        double factor = (collective - COLLECTIVE_NEUTRAL) / (1.0D - COLLECTIVE_NEUTRAL);
        factor = Math.pow(factor, COLLECTIVE_UP_EXPONENT);
        return neutralLift + (1.0D - neutralLift) * factor;
    }

    private double neutralCollectiveLiftScale() {
        double hoverScale = GRAVITY_MPS2 / Math.max(1.0D, this.profile.maxMainRotorAccelMps2);
        return clamp(hoverScale, 0.52D, 0.88D);
    }

    private double tailRotorAccelerationMagnitude(double rotorPower) {
        double yawAuthority = this.profile.handlingProfile.tailRotorAuthorityScale
                * this.profile.handlingProfile.yawResponseScale
                / this.profile.handlingProfile.rotationalInertiaScale;
        return rotorPower * this.profile.maxTailRotorForceN / this.profile.massKg * yawAuthority;
    }

    private double cyclicAuthority() {
        return this.profile.handlingProfile.cyclicAuthorityScale
                / this.profile.handlingProfile.rotationalInertiaScale;
    }

    private Vec3 wingLiftAcceleration(Vec3 velocityMps, Vec3 forward, Vec3 mainRotorAxis,
                                      double rotorPower, double pitchDegrees) {
        if (this.profile.wingLiftCoefficient <= 0.0D || rotorPower <= 0.0D) {
            return ZERO;
        }
        double forwardMps = Math.max(0.0D, velocityMps.m_82526_(forward));
        if (forwardMps <= 0.0D) {
            return ZERO;
        }
        double naturalForwardMps = Math.max(1.0D, kmhToMps(this.profile.maxForwardKmh));
        double speedFactor = clamp(forwardMps / naturalForwardMps, 0.0D, 1.35D);
        double attitudeEfficiency = clamp(mainRotorAxis.f_82480_, 0.0D, 1.0D)
                * Math.pow(noseDownLiftEfficiency(pitchDegrees), 1.75D);
        double maxWingSupportAccel = GRAVITY_MPS2 * this.profile.wingLiftCoefficient;
        double liftAccel = maxWingSupportAccel * speedFactor * speedFactor
                * attitudeEfficiency * rotorPower;
        double cap = Math.min(maxWingSupportAccel * 1.15D, this.profile.maxMainRotorAccelMps2 * 0.22D);
        return WORLD_UP.m_82490_(clamp(liftAccel, 0.0D, cap));
    }

    private Vec3 dragAcceleration(Vec3 velocityMps, Vec3 forward, Vec3 right) {
        double forwardMps = velocityMps.m_82526_(forward);
        double sideMps = velocityMps.m_82526_(right);
        double verticalMps = velocityMps.f_82480_;
        double forwardDrag = componentDrag(forwardMps,
                this.profile.forwardLinearDrag, this.profile.forwardQuadraticDrag);
        double sideDrag = componentDrag(sideMps,
                this.profile.sideLinearDrag, this.profile.sideQuadraticDrag);
        double verticalDrag = componentDrag(verticalMps,
                this.profile.verticalLinearDrag, this.profile.verticalQuadraticDrag);
        forwardDrag += forwardEnvelopeDrag(forwardMps);
        sideDrag += sideEnvelopeDrag(sideMps);
        verticalDrag += verticalEnvelopeDrag(verticalMps);
        return forward.m_82490_(forwardDrag)
                .m_82549_(right.m_82490_(sideDrag))
                .m_82549_(WORLD_UP.m_82490_(verticalDrag));
    }

    private double forwardEnvelopeDrag(double forwardMps) {
        if (forwardMps >= 0.0D) {
            return envelopeDrag(forwardMps,
                    kmhToMps(this.profile.maxForwardKmh),
                    kmhToMps(this.profile.maxBoostForwardKmh),
                    0.040D, 0.180D, 20.0D);
        }
        return envelopeDrag(forwardMps,
                kmhToMps(reverseSoftCapKmh()),
                kmhToMps(reverseHardCapKmh()),
                0.060D, 0.220D, 16.0D);
    }

    private double sideEnvelopeDrag(double sideMps) {
        return envelopeDrag(sideMps,
                kmhToMps(sideSoftCapKmh()),
                kmhToMps(sideHardCapKmh()),
                0.050D, 0.180D, 16.0D);
    }

    private double verticalEnvelopeDrag(double verticalMps) {
        if (verticalMps >= 0.0D) {
            return envelopeDrag(verticalMps,
                    climbSoftCapMps(),
                    climbHardCapMps(),
                    0.065D, 0.200D, 14.0D);
        }
        return envelopeDrag(verticalMps,
                descentSoftCapMps(),
                descentHardCapMps(),
                0.025D, 0.090D, 10.0D);
    }

    private static double componentDrag(double componentMps, double linear, double quadratic) {
        return -componentMps * linear - componentMps * Math.abs(componentMps) * quadratic;
    }

    private static double envelopeDrag(double componentMps, double softCapMps, double hardCapMps,
                                       double softCoefficient, double hardCoefficient, double maxAccelMps2) {
        double speed = Math.abs(componentMps);
        if (speed <= softCapMps) {
            return 0.0D;
        }
        double overSoft = speed - softCapMps;
        double overHard = Math.max(0.0D, speed - hardCapMps);
        double accel = overSoft * overSoft * softCoefficient
                + overHard * overHard * hardCoefficient;
        return -Math.signum(componentMps) * clamp(accel, 0.0D, maxAccelMps2);
    }

    private Vec3 applyVerticalEnvelope(Vec3 motion) {
        double verticalMps = motion.f_82480_ * TICKS_PER_SECOND;
        if (verticalMps > climbHardCapMps()) {
            return new Vec3(motion.f_82479_, climbHardCapMps() / TICKS_PER_SECOND, motion.f_82481_);
        }
        if (verticalMps < -descentHardCapMps()) {
            return new Vec3(motion.f_82479_, -descentHardCapMps() / TICKS_PER_SECOND, motion.f_82481_);
        }
        return motion;
    }

    private double reverseSoftCapKmh() {
        return Math.max(24.0D, this.profile.maxForwardKmh * 0.55D);
    }

    private double reverseHardCapKmh() {
        return Math.max(30.0D, this.profile.maxForwardKmh * 0.75D);
    }

    private double sideSoftCapKmh() {
        return Math.max(30.0D, this.profile.maxForwardKmh * 0.70D);
    }

    private double sideHardCapKmh() {
        return Math.max(40.0D, this.profile.maxBoostForwardKmh * 0.80D);
    }

    private double climbSoftCapMps() {
        return this.profile.climbSoftCapMps;
    }

    private double climbHardCapMps() {
        return this.profile.climbHardCapMps;
    }

    private static double noseDownLiftEfficiency(double pitchDegrees) {
        double noseDownDegrees = Math.max(0.0D, pitchDegrees);
        double t = smoothStep((noseDownDegrees - 18.0D) / 34.0D);
        return 1.0D - 0.78D * t;
    }

    private static double smoothStep(double value) {
        double t = clamp(value, 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double descentSoftCapMps() {
        return 8.5D;
    }

    private static double descentHardCapMps() {
        return 13.5D;
    }

    private static double kmhToMps(double kmh) {
        return kmh / 3.6D;
    }

    private static Vec3 collisionAwareBaseMotion(Vec3 previousMotion, Vec3 requestedMotion) {
        // The flight strategy samples current deltaMovement after the vanilla lifecycle and
        // runs no legacy movement forces before this evaluation. Collision response and any
        // intervening external impulse belong to the current sample, at every magnitude.
        // The older sample is a fallback for callers without a current sample, not a filter.
        return requestedMotion != null ? requestedMotion : previousMotion != null ? previousMotion : ZERO;
    }

    private static Vec3 blocksPerTickToMetersPerSecond(Vec3 motion) {
        if (motion == null) {
            return ZERO;
        }
        return motion.m_82490_(TICKS_PER_SECOND);
    }

    private static double steeringCommand(HelicopterFlightController.Input input) {
        if (input.steerLeft == input.steerRight) {
            return 0.0D;
        }
        return input.steerRight ? 1.0D : -1.0D;
    }

    private static double pitchDegrees(HelicopterFlightController.Input input) {
        double verticalLook = clamp(-input.look.f_82480_, -1.0D, 1.0D);
        return Math.toDegrees(Math.asin(verticalLook));
    }

    private static Vec3 safeNormalize(Vec3 value, Vec3 fallback) {
        if (value == null || value.m_82553_() < 1.0E-6D) {
            return fallback;
        }
        return value.m_82541_();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class Result {
        private static final Result ZERO_RESULT = new Result(
                ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO,
                0.0D, 0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D,
                0.0D, 0.0D);

        public final Vec3 mainRotorAxis;
        public final Vec3 tailRotorAxis;
        public final Vec3 velocityMps;
        public final Vec3 gravityAccelMps2;
        public final Vec3 mainRotorAccelMps2;
        public final Vec3 wingLiftAccelMps2;
        public final Vec3 tailRotorAccelMps2;
        public final Vec3 dragAccelMps2;
        public final Vec3 netAccelMps2;
        public final Vec3 predictedMotion;
        public final double collective;
        public final double rotorPower;
        public final double pitchDegrees;
        public final double rollDegrees;
        public final double forwardKmh;
        public final double sideKmh;
        public final double verticalMps;
        public final double predictedForwardKmh;
        public final double predictedSideKmh;
        public final double predictedVerticalMps;
        public final double verticalRotorAccelMps2;
        public final double verticalNetAccelMps2;
        public final double massKg;
        public final double mainRotorForceN;
        public final double netForceN;

        private Result(Vec3 mainRotorAxis, Vec3 tailRotorAxis, Vec3 velocityMps,
                       Vec3 gravityAccelMps2, Vec3 mainRotorAccelMps2,
                       Vec3 wingLiftAccelMps2, Vec3 tailRotorAccelMps2,
                       Vec3 dragAccelMps2, Vec3 netAccelMps2,
                       Vec3 predictedMotion, double collective, double rotorPower,
                       double pitchDegrees, double rollDegrees,
                       double forwardKmh, double sideKmh, double verticalMps,
                       double predictedForwardKmh, double predictedSideKmh,
                       double predictedVerticalMps, double verticalRotorAccelMps2,
                       double verticalNetAccelMps2, double massKg,
                       double mainRotorForceN, double netForceN) {
            this.mainRotorAxis = mainRotorAxis;
            this.tailRotorAxis = tailRotorAxis;
            this.velocityMps = velocityMps;
            this.gravityAccelMps2 = gravityAccelMps2;
            this.mainRotorAccelMps2 = mainRotorAccelMps2;
            this.wingLiftAccelMps2 = wingLiftAccelMps2;
            this.tailRotorAccelMps2 = tailRotorAccelMps2;
            this.dragAccelMps2 = dragAccelMps2;
            this.netAccelMps2 = netAccelMps2;
            this.predictedMotion = predictedMotion;
            this.collective = collective;
            this.rotorPower = rotorPower;
            this.pitchDegrees = pitchDegrees;
            this.rollDegrees = rollDegrees;
            this.forwardKmh = forwardKmh;
            this.sideKmh = sideKmh;
            this.verticalMps = verticalMps;
            this.predictedForwardKmh = predictedForwardKmh;
            this.predictedSideKmh = predictedSideKmh;
            this.predictedVerticalMps = predictedVerticalMps;
            this.verticalRotorAccelMps2 = verticalRotorAccelMps2;
            this.verticalNetAccelMps2 = verticalNetAccelMps2;
            this.massKg = massKg;
            this.mainRotorForceN = mainRotorForceN;
            this.netForceN = netForceN;
        }

        public static Result zero() { return ZERO_RESULT; }
    }
}
