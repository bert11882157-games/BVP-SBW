package com.yourname.berts_vehicle_pack.entity.helicopter;

import net.minecraft.world.phys.Vec3;

public final class HelicopterFlightController {
    public static final double KMH_PER_BLOCK_PER_TICK = 72.0D;

    private static final double TICKS_PER_SECOND = 20.0D;
    private static final double BASE_FORWARD_CAP_KMH = 50.0D;
    private static final double BASE_BOOST_CAP_KMH = 110.0D;
    private static final double ACCEL_TO_BLOCKS_PER_TICK = 1.0D / (TICKS_PER_SECOND * TICKS_PER_SECOND);
    private static final double SBW_FULL_HELI_POWER = 0.12D;
    private static final double GRAVITY_ACCEL_MPS2 = 9.8D;
    private static final double MAX_ROTOR_ACCEL_MPS2 = 16.8D;
    private static final double GRAVITY_DESCENT_TARGET_MPS = 6.2D;
    private static final double MAX_DESCENT_MPS = 13.5D;
    private static final double MAX_LEVEL_CLIMB_MPS = 4.0D;
    private static final double MAX_FLARE_CLIMB_MPS = 7.0D;
    private static final double MAX_REVERSE_KMH = 28.0D;
    private static final double MAX_SIDE_KMH = 36.0D;
    private static final double LOW_SPEED_FORWARD_ACCEL_KMH_PER_TICK = 0.34D;
    private static final double MID_SPEED_FORWARD_ACCEL_KMH_PER_TICK = 0.11D;
    private static final double HIGH_SPEED_FORWARD_ACCEL_KMH_PER_TICK = 0.035D;
    private static final double VERTICAL_UP_ACCEL_MPS_PER_TICK = 0.025D;
    private static final double VERTICAL_DOWN_ACCEL_MPS_PER_TICK = 0.085D;
    private static final double ROTOR_LIFT_ACCEL_PER_TICK = 0.024D;
    private static final double ROTOR_LIFT_DECAY_PER_TICK = 0.038D;
    private static final double AIR_DRAG_PER_TICK = 0.994D;
    private static final double SIDE_DRAG_KMH_PER_TICK = 0.18D;
    private static final double REVERSE_DRAG_KMH_PER_TICK = 0.16D;
    private static final double OVERSPEED_DECAY_KMH_PER_TICK = 0.35D;
    private static final double OVERSPEED_DIVE_DECAY_KMH_PER_TICK = 0.12D;
    private static final double OVERSPEED_DIVE_START_PITCH_DEGREES = 20.0D;
    private static final double OVERSPEED_DIVE_FULL_PITCH_DEGREES = 42.0D;
    private static final double OVERSPEED_DIVE_START_DESCENT_MPS = 0.4D;
    private static final double OVERSPEED_DIVE_FULL_DESCENT_MPS = 4.0D;
    private static final double FLARE_MIN_FORWARD_KMH = 38.0D;
    private static final double FLARE_FULL_FORWARD_KMH = 76.0D;
    private static final double LOW_FLARE_FORWARD_SPEND_KMH_PER_TICK = 0.26D;
    private static final double FLARE_FORWARD_TO_VERTICAL_EFFICIENCY = 0.42D;
    private static final double MAX_ROLL_THRUST_DEGREES = 125.0D;
    private static final double ROLL_ALTITUDE_COST_START_DEGREES = 45.0D;
    private static final double ROLL_ALTITUDE_COST_STRONG_DEGREES = 60.0D;
    private static final double ROLL_PLUMMET_DEGREES = 95.0D;
    private static final double ROLL_RELIEF_START_PITCH_DEGREES = 12.0D;
    private static final double ROLL_RELIEF_FULL_PITCH_DEGREES = 38.0D;
    private static final Vec3 WORLD_FORWARD = new Vec3(0.0D, 0.0D, 1.0D);
    private static final Vec3 WORLD_RIGHT = new Vec3(1.0D, 0.0D, 0.0D);
    private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
    private static final Vec3 GRAVITY = new Vec3(0.0D, -GRAVITY_ACCEL_MPS2 * ACCEL_TO_BLOCKS_PER_TICK, 0.0D);

    private final HelicopterFlightProfile profile;
    private final HelicopterBankController bankController = new HelicopterBankController();
    private final HelicopterHoverController hoverController = new HelicopterHoverController();

    public HelicopterFlightController(HelicopterFlightProfile profile) {
        this.profile = profile == null ? HelicopterFlightProfile.mi24v() : profile;
    }

    public double maxForwardKmh() {
        return this.profile.maxForwardKmh;
    }

    public double maxBoostForwardKmh() {
        return this.profile.maxBoostForwardKmh;
    }

    public Result apply(Input input) {
        if (input.wreck) {
            this.bankController.reset();
            this.hoverController.reset();
            return Result.inactive(input.requestedMotion, input.rotorLiftPower);
        }

        Vec3 forward = safeNormalize(input.forward, WORLD_FORWARD);
        Vec3 right = safeNormalize(input.right, WORLD_RIGHT);
        Vec3 up = safeNormalize(input.up, WORLD_UP);
        Vec3 rotorAxis = rotorThrustAxis(input, forward, right, up);
        Vec3 baseMotion = input.previousMotion;

        PitchProfile pitch = pitchProfile(input);
        double previousForwardKmh = baseMotion.m_82526_(forward) * KMH_PER_BLOCK_PER_TICK;
        HelicopterHoverController.State hover = this.hoverController.tick(
                input, up.f_82480_, pitch.pitchDegrees, baseMotion.f_82480_ * TICKS_PER_SECOND);
        double collectiveTarget = collectiveTarget(input, hover);
        double rotorLiftPower = nextRotorLiftPower(input, collectiveTarget);

        HelicopterBankController.Result bank = this.bankController.tick(input, previousForwardKmh, rotorLiftPower);
        RollPenalty rollPenalty = rollPenalty(input, pitch);
        double liftEfficiency = liftEfficiency(rotorAxis, rollPenalty.liftPenalty);

        Vec3 gravity = GRAVITY;
        Vec3 thrust = rotorAxis.m_82490_(rotorLiftPower * MAX_ROTOR_ACCEL_MPS2 * ACCEL_TO_BLOCKS_PER_TICK);
        Vec3 motion = baseMotion.m_82549_(gravity).m_82549_(thrust);

        motion = applyAirDrag(input, motion, forward, right);
        motion = governHorizontalMotion(input, baseMotion, motion, forward, right, rotorLiftPower, pitch, bank, hover);
        double rotorVerticalTargetMps = rotorVerticalTargetMps(
                input, rotorAxis, liftEfficiency, rotorLiftPower, pitch, hover, rollPenalty.descentMps);
        motion = governRotorVerticalMotion(input, baseMotion, motion, rotorVerticalTargetMps, pitch, hover);
        motion = applyFlareRecovery(motion, forward, pitch);
        motion = clampVerticalSpeed(motion, forward, pitch, hover);

        return new Result(
                motion,
                collectiveTarget,
                rotorLiftPower,
                rotorLiftPower * MAX_ROTOR_ACCEL_MPS2,
                bank.bankIntensity,
                bank.bankCommand,
                liftEfficiency,
                input.rollDegrees,
                up.f_82480_,
                rotorAxis.f_82480_,
                rotorAxis.m_82526_(right),
                rotorVerticalTargetMps,
                -GRAVITY_ACCEL_MPS2,
                input.requestedMotion.m_82526_(forward) * KMH_PER_BLOCK_PER_TICK,
                input.requestedMotion.m_82526_(right) * KMH_PER_BLOCK_PER_TICK,
                input.requestedMotion.f_82480_ * TICKS_PER_SECOND,
                motion.m_82526_(forward) * KMH_PER_BLOCK_PER_TICK,
                motion.m_82526_(right) * KMH_PER_BLOCK_PER_TICK,
                motion.f_82480_ * TICKS_PER_SECOND,
                hover.active,
                hover.assist
        );
    }

    private double collectiveTarget(Input input, HelicopterHoverController.State hover) {
        if (!input.occupied) {
            return 0.0D;
        }

        double normalizedSbwPower = clamp(input.enginePower / SBW_FULL_HELI_POWER, 0.0D, 1.0D);
        double target = normalizedSbwPower;
        if (input.collectiveUp) {
            target = 1.0D;
        } else if (input.collectiveDown) {
            target = 0.0D;
        } else if (hover.active) {
            target = Math.max(target, hover.rotorPowerFloor);
        }

        return clamp(target, 0.0D, 1.0D);
    }

    private double nextRotorLiftPower(Input input, double collectiveTarget) {
        double step = collectiveTarget > input.rotorLiftPower ? ROTOR_LIFT_ACCEL_PER_TICK : ROTOR_LIFT_DECAY_PER_TICK;
        return approach(input.rotorLiftPower, collectiveTarget, step);
    }

    private Vec3 applyAirDrag(Input input, Vec3 motion, Vec3 forward, Vec3 right) {
        double forwardComponent = motion.m_82526_(forward);
        double sideComponent = motion.m_82526_(right);
        double verticalComponent = motion.f_82480_;

        if (forwardComponent < 0.0D) {
            forwardComponent = approach(forwardComponent, 0.0D, kmhToBlocksPerTick(REVERSE_DRAG_KMH_PER_TICK));
        }
        sideComponent = approach(sideComponent, 0.0D, kmhToBlocksPerTick(SIDE_DRAG_KMH_PER_TICK));

        Vec3 horizontal = forward.m_82490_(forwardComponent).m_82549_(right.m_82490_(sideComponent));
        Vec3 damped = horizontal.m_82490_(AIR_DRAG_PER_TICK);
        return damped.m_82520_(0.0D, verticalComponent * 0.998D, 0.0D);
    }

    private Vec3 governHorizontalMotion(Input input, Vec3 previousMotion, Vec3 proposedMotion,
                                        Vec3 forward, Vec3 right, double rotorLiftPower,
                                        PitchProfile pitch, HelicopterBankController.Result bank,
                                        HelicopterHoverController.State hover) {
        double previousForwardKmh = previousMotion.m_82526_(forward) * KMH_PER_BLOCK_PER_TICK;
        double proposedForwardKmh = proposedMotion.m_82526_(forward) * KMH_PER_BLOCK_PER_TICK;
        double forwardAccelKmh = pitch.forwardAccelKmhPerTick * rotorLiftPower;
        double profileForwardKmh = previousForwardKmh + forwardAccelKmh;
        if (pitch.forwardBleedKmhPerTick > 0.0D) {
            double bledForwardKmh = Math.max(0.0D, Math.max(0.0D, previousForwardKmh) - pitch.forwardBleedKmhPerTick);
            profileForwardKmh = bledForwardKmh + forwardAccelKmh;
            proposedForwardKmh = Math.max(0.0D, Math.min(proposedForwardKmh, profileForwardKmh));
        } else {
            proposedForwardKmh = Math.max(proposedForwardKmh, profileForwardKmh);
        }
        double overspeedDecayKmh = overspeedDecayFor(pitch, proposedMotion.f_82480_ * TICKS_PER_SECOND);
        double governedForwardKmh = governForwardSpeed(previousForwardKmh, proposedForwardKmh, pitch, overspeedDecayKmh);

        double proposedSideKmh = proposedMotion.m_82526_(right) * KMH_PER_BLOCK_PER_TICK;
        double governedSideKmh = clamp(proposedSideKmh + bank.sideAccelKmhPerTick, -MAX_SIDE_KMH, MAX_SIDE_KMH);
        double sideDrag = Math.max(0.045D, SIDE_DRAG_KMH_PER_TICK * bank.sideDragScale);
        governedSideKmh = approach(governedSideKmh, 0.0D, sideDrag);

        if (governedForwardKmh > 0.0D && bank.forwardBleedKmhPerTick > 0.0D) {
            governedForwardKmh = Math.max(0.0D, governedForwardKmh - bank.forwardBleedKmhPerTick);
        }

        double totalKmh = Math.sqrt(governedForwardKmh * governedForwardKmh + governedSideKmh * governedSideKmh);
        if (totalKmh > this.profile.maxBoostForwardKmh && totalKmh > 1.0E-4D) {
            double scale = this.profile.maxBoostForwardKmh / totalKmh;
            governedForwardKmh *= scale;
            governedSideKmh *= scale;
        }

        Vec3 governed = forward.m_82490_(kmhToBlocksPerTick(governedForwardKmh))
                .m_82549_(right.m_82490_(kmhToBlocksPerTick(governedSideKmh)))
                .m_82520_(0.0D, proposedMotion.f_82480_, 0.0D);
        return hover.dampHorizontal(governed, forward, right);
    }

    private double governForwardSpeed(double previousKmh, double proposedKmh, PitchProfile pitch,
                                      double overspeedDecayKmhPerTick) {
        double forwardCap = pitch.forwardCapKmh;
        if (previousKmh > forwardCap) {
            double decayed = Math.max(forwardCap, previousKmh - overspeedDecayKmhPerTick);
            proposedKmh = Math.min(proposedKmh, decayed);
        } else if (proposedKmh > previousKmh) {
            proposedKmh = Math.min(proposedKmh,
                    previousKmh + Math.max(forwardAccelFor(previousKmh), pitch.forwardAccelKmhPerTick));
        }

        if (proposedKmh > forwardCap && previousKmh <= forwardCap) {
            proposedKmh = forwardCap;
        }
        if (proposedKmh < previousKmh) {
            return Math.max(-MAX_REVERSE_KMH, proposedKmh);
        }
        return proposedKmh;
    }

    private Vec3 governRotorVerticalMotion(Input input, Vec3 previousMotion, Vec3 proposedMotion,
                                           double targetMps, PitchProfile pitch, HelicopterHoverController.State hover) {
        double currentMps = previousMotion.f_82480_ * TICKS_PER_SECOND;
        double recoveryStep = VERTICAL_UP_ACCEL_MPS_PER_TICK + pitch.flareStrength * 0.085D;
        if (hover.active) {
            recoveryStep = Math.max(recoveryStep, hover.verticalStepMpsPerTick);
        }
        double step = hover.active ? recoveryStep
                : (targetMps > currentMps ? recoveryStep : VERTICAL_DOWN_ACCEL_MPS_PER_TICK);
        double governedMps = approach(currentMps, targetMps, step);
        return new Vec3(proposedMotion.f_82479_, metersPerSecondToBlocksPerTick(governedMps), proposedMotion.f_82481_);
    }

    private Vec3 applyFlareRecovery(Vec3 motion, Vec3 forward, PitchProfile pitch) {
        double forwardSpeed = motion.m_82526_(forward);
        double forwardKmh = forwardSpeed * KMH_PER_BLOCK_PER_TICK;
        if (pitch.flareStrength <= 1.0E-4D || forwardKmh <= FLARE_MIN_FORWARD_KMH) {
            return motion;
        }

        double verticalMps = motion.f_82480_ * TICKS_PER_SECOND;
        double verticalRoom = Math.max(0.0D, MAX_FLARE_CLIMB_MPS - verticalMps);
        double speedFactor = clamp((forwardKmh - FLARE_MIN_FORWARD_KMH)
                / (FLARE_FULL_FORWARD_KMH - FLARE_MIN_FORWARD_KMH), 0.0D, 1.0D);
        double spendKmhPerTick = LOW_FLARE_FORWARD_SPEND_KMH_PER_TICK * pitch.flareStrength;
        double efficiency = FLARE_FORWARD_TO_VERTICAL_EFFICIENCY;
        double spend = Math.min(
                kmhToBlocksPerTick(spendKmhPerTick) * speedFactor,
                Math.min(forwardSpeed - kmhToBlocksPerTick(FLARE_MIN_FORWARD_KMH),
                        metersPerSecondToBlocksPerTick(verticalRoom) / efficiency)
        );
        if (spend <= 1.0E-6D) {
            return motion;
        }
        return motion
                .m_82549_(forward.m_82490_(-spend))
                .m_82520_(0.0D, spend * TICKS_PER_SECOND * efficiency, 0.0D);
    }

    private Vec3 clampVerticalSpeed(Vec3 motion, Vec3 forward, PitchProfile pitch,
                                    HelicopterHoverController.State hover) {
        double verticalMps = motion.f_82480_ * TICKS_PER_SECOND;
        if (hover.active && !canKeepFlareClimb(motion, forward, pitch)) {
            double governedMps = clamp(verticalMps, -hover.maxVerticalMps, hover.maxVerticalMps);
            return new Vec3(motion.f_82479_, metersPerSecondToBlocksPerTick(governedMps), motion.f_82481_);
        }
        double maxClimb = canKeepFlareClimb(motion, forward, pitch) ? MAX_FLARE_CLIMB_MPS : MAX_LEVEL_CLIMB_MPS;
        if (verticalMps > maxClimb) {
            return new Vec3(motion.f_82479_, metersPerSecondToBlocksPerTick(maxClimb), motion.f_82481_);
        }
        double descentCap = pitch.descentCapMps;
        if (verticalMps < -descentCap) {
            return new Vec3(motion.f_82479_, metersPerSecondToBlocksPerTick(-descentCap), motion.f_82481_);
        }
        return motion;
    }

    private boolean canKeepFlareClimb(Vec3 motion, Vec3 forward, PitchProfile pitch) {
        double forwardKmh = motion.m_82526_(forward) * KMH_PER_BLOCK_PER_TICK;
        return pitch.flareStrength > 0.35D
                && forwardKmh > FLARE_MIN_FORWARD_KMH
                && motion.f_82480_ * TICKS_PER_SECOND <= MAX_FLARE_CLIMB_MPS;
    }

    private double rotorVerticalTargetMps(Input input, Vec3 up, double liftEfficiency,
                                                 double rotorLiftPower, PitchProfile pitch,
                                                 HelicopterHoverController.State hover,
                                                 double rollDescentMps) {
        if (!input.occupied) {
            return -GRAVITY_DESCENT_TARGET_MPS;
        }
        double descentCap = Math.max(pitch.descentCapMps, rollDescentMps);
        if (liftEfficiency < 0.08D || up.f_82480_ <= 0.0D) {
            return -descentCap;
        }

        double fullPowerTarget = pitch.verticalTargetMps * liftEfficiency + (-descentCap) * (1.0D - liftEfficiency);
        double target = -descentCap + rotorLiftPower * (fullPowerTarget + descentCap);
        if (hover.active) {
            target = hover.verticalTargetMps;
        } else if (input.collectiveDown) {
            target = Math.min(target, -GRAVITY_DESCENT_TARGET_MPS);
        }
        target -= rollDescentMps;
        return clamp(target, -descentCap, MAX_LEVEL_CLIMB_MPS);
    }

    private static double liftEfficiency(Vec3 up, double bankLiftPenalty) {
        double verticalEfficiency = clamp((up.f_82480_ + 0.05D) / 1.05D, 0.0D, 1.0D);
        return clamp(verticalEfficiency * (1.0D - clamp(bankLiftPenalty, 0.0D, 1.0D)), 0.0D, 1.0D);
    }

    private static Vec3 rotorThrustAxis(Input input, Vec3 forward, Vec3 right, Vec3 up) {
        double rollDegrees = clamp(input.rollDegrees, -MAX_ROLL_THRUST_DEGREES, MAX_ROLL_THRUST_DEGREES);
        double rollRadians = Math.toRadians(rollDegrees);
        double forwardTilt = clamp(up.m_82526_(forward), -0.65D, 0.65D);
        double pitchRemainingLift = Math.sqrt(Math.max(0.0D, 1.0D - forwardTilt * forwardTilt));
        double sideTilt = Math.sin(rollRadians) * pitchRemainingLift;
        double verticalTilt = Math.cos(rollRadians) * pitchRemainingLift;

        Vec3 axis = forward.m_82490_(forwardTilt)
                .m_82549_(right.m_82490_(sideTilt))
                .m_82520_(0.0D, verticalTilt, 0.0D);
        return safeNormalize(axis, up);
    }

    private RollPenalty rollPenalty(Input input, PitchProfile pitch) {
        if (!input.occupied || input.wreck) {
            return RollPenalty.ZERO;
        }
        double roll = Math.abs(input.rollDegrees);
        if (roll <= ROLL_ALTITUDE_COST_START_DEGREES) {
            return RollPenalty.ZERO;
        }

        double relief = clamp((pitch.pitchDegrees - ROLL_RELIEF_START_PITCH_DEGREES)
                / (ROLL_RELIEF_FULL_PITCH_DEGREES - ROLL_RELIEF_START_PITCH_DEGREES), 0.0D, 0.58D);
        double reliefScale = roll >= ROLL_PLUMMET_DEGREES
                ? 1.0D - relief * 0.25D
                : 1.0D - relief;
        double severity;
        double descentMps;
        if (roll <= ROLL_ALTITUDE_COST_STRONG_DEGREES) {
            double t = invLerp(ROLL_ALTITUDE_COST_START_DEGREES, ROLL_ALTITUDE_COST_STRONG_DEGREES, roll);
            severity = lerp(0.0D, 0.24D, t);
            descentMps = lerp(0.0D, 1.7D, t);
        } else if (roll <= ROLL_PLUMMET_DEGREES) {
            double t = invLerp(ROLL_ALTITUDE_COST_STRONG_DEGREES, ROLL_PLUMMET_DEGREES, roll);
            severity = lerp(0.24D, 0.78D, t);
            descentMps = lerp(1.7D, 9.8D, t);
        } else {
            double t = invLerp(ROLL_PLUMMET_DEGREES, MAX_ROLL_THRUST_DEGREES, Math.min(roll, MAX_ROLL_THRUST_DEGREES));
            severity = lerp(0.78D, 1.0D, t);
            descentMps = lerp(9.8D, MAX_DESCENT_MPS, t);
        }
        return new RollPenalty(
                clamp(severity * reliefScale, 0.0D, 1.0D),
                clamp(descentMps * reliefScale, 0.0D, MAX_DESCENT_MPS)
        );
    }

    private PitchProfile pitchProfile(Input input) {
        double rawPitch = noseDownPitchDegrees(input);
        double pitch = Math.max(0.0D, rawPitch);
        double pullBack = clamp(-rawPitch / 12.0D, 0.0D, 1.0D);

        if (pitch <= 10.0D) {
            double t = pitch / 10.0D;
            return new PitchProfile(
                    pitch,
                    0.0D,
                    lerp(0.34D, 0.12D, t) + pullBack * 0.10D,
                    lerp(4.0D, 2.1D, t) + pullBack * 1.8D,
                    this.profile.maxForwardKmh,
                    GRAVITY_DESCENT_TARGET_MPS,
                    clamp(1.0D - t + pullBack, 0.0D, 1.0D));
        }
        if (pitch <= 20.0D) {
            double t = invLerp(10.0D, 20.0D, pitch);
            return new PitchProfile(
                    pitch,
                    lerp(0.02D, 0.16D, t),
                    0.0D,
                    lerp(2.1D, 0.8D, t),
                    this.profile.maxForwardKmh,
                    GRAVITY_DESCENT_TARGET_MPS,
                    0.0D);
        }
        if (pitch <= 24.0D) {
            double t = invLerp(20.0D, 24.0D, pitch);
            return new PitchProfile(
                    pitch,
                    lerp(0.16D, 0.12D, t),
                    0.0D,
                    lerp(0.8D, 0.15D, t),
                    this.profile.maxForwardKmh,
                    GRAVITY_DESCENT_TARGET_MPS,
                    0.0D);
        }
        if (pitch <= 27.0D) {
            return new PitchProfile(
                    pitch,
                    0.10D,
                    0.0D,
                    0.0D,
                    this.profile.maxForwardKmh,
                    GRAVITY_DESCENT_TARGET_MPS,
                    0.0D);
        }
        if (pitch <= 34.0D) {
            double t = invLerp(27.0D, 34.0D, pitch);
            return new PitchProfile(
                    pitch,
                    lerp(0.16D, 0.26D, t),
                    0.0D,
                    lerp(0.0D, -1.2D, t),
                    lerp(this.profile.maxForwardKmh, scaledCap(60.0D), t),
                    lerp(GRAVITY_DESCENT_TARGET_MPS, 7.4D, t),
                    0.0D);
        }
        if (pitch <= 42.0D) {
            double t = invLerp(34.0D, 42.0D, pitch);
            return new PitchProfile(
                    pitch,
                    lerp(0.26D, 0.38D, t),
                    0.0D,
                    lerp(-1.2D, -3.4D, t),
                    lerp(scaledCap(60.0D), scaledCap(70.0D), t),
                    lerp(7.4D, 9.2D, t),
                    0.0D);
        }
        if (pitch <= 52.0D) {
            double t = invLerp(42.0D, 52.0D, pitch);
            return new PitchProfile(
                    pitch,
                    lerp(0.38D, 0.50D, t),
                    0.0D,
                    lerp(-3.4D, -6.6D, t),
                    lerp(scaledCap(70.0D), this.profile.maxBoostForwardKmh, t),
                    lerp(9.2D, 11.6D, t),
                    0.0D);
        }

        double t = clamp((pitch - 52.0D) / 18.0D, 0.0D, 1.0D);
        return new PitchProfile(
                pitch,
                lerp(0.50D, 0.62D, t),
                0.0D,
                lerp(-6.6D, -11.5D, t),
                this.profile.maxBoostForwardKmh,
                lerp(11.6D, MAX_DESCENT_MPS, t),
                0.0D);
    }

    private double scaledCap(double defaultKmh) {
        double factor = (defaultKmh - BASE_FORWARD_CAP_KMH) / (BASE_BOOST_CAP_KMH - BASE_FORWARD_CAP_KMH);
        return lerp(this.profile.maxForwardKmh, this.profile.maxBoostForwardKmh, factor);
    }

    private static double noseDownPitchDegrees(Input input) {
        double verticalLook = clamp(-input.look.f_82480_, -1.0D, 1.0D);
        return Math.toDegrees(Math.asin(verticalLook));
    }

    private double forwardAccelFor(double currentKmh) {
        double speed = Math.max(0.0D, currentKmh);
        if (speed < 20.0D) {
            return LOW_SPEED_FORWARD_ACCEL_KMH_PER_TICK;
        }
        if (speed < 40.0D) {
            return MID_SPEED_FORWARD_ACCEL_KMH_PER_TICK;
        }
        return HIGH_SPEED_FORWARD_ACCEL_KMH_PER_TICK;
    }

    private static double overspeedDecayFor(PitchProfile pitch, double verticalMps) {
        if (verticalMps >= -OVERSPEED_DIVE_START_DESCENT_MPS) {
            return OVERSPEED_DECAY_KMH_PER_TICK;
        }

        double pitchFactor = clamp((pitch.pitchDegrees - OVERSPEED_DIVE_START_PITCH_DEGREES)
                / (OVERSPEED_DIVE_FULL_PITCH_DEGREES - OVERSPEED_DIVE_START_PITCH_DEGREES), 0.0D, 1.0D);
        double descentFactor = clamp((-verticalMps - OVERSPEED_DIVE_START_DESCENT_MPS)
                / (OVERSPEED_DIVE_FULL_DESCENT_MPS - OVERSPEED_DIVE_START_DESCENT_MPS), 0.0D, 1.0D);
        return lerp(OVERSPEED_DECAY_KMH_PER_TICK, OVERSPEED_DIVE_DECAY_KMH_PER_TICK,
                pitchFactor * descentFactor);
    }

    private static Vec3 safeNormalize(Vec3 value, Vec3 fallback) {
        if (value == null || value.m_82556_() < 1.0E-8D) {
            return fallback;
        }
        return value.m_82541_();
    }

    private static double kmhToBlocksPerTick(double kmh) {
        return kmh / KMH_PER_BLOCK_PER_TICK;
    }

    private static double metersPerSecondToBlocksPerTick(double metersPerSecond) {
        return metersPerSecond / TICKS_PER_SECOND;
    }

    private static double approach(double value, double target, double maxStep) {
        if (value < target) {
            return Math.min(target, value + maxStep);
        }
        return Math.max(target, value - maxStep);
    }

    private static double invLerp(double min, double max, double value) {
        return clamp((value - min) / (max - min), 0.0D, 1.0D);
    }

    private static double lerp(double min, double max, double factor) {
        return min + (max - min) * clamp(factor, 0.0D, 1.0D);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class RollPenalty {
        static final RollPenalty ZERO = new RollPenalty(0.0D, 0.0D);

        final double liftPenalty;
        final double descentMps;

        private RollPenalty(double liftPenalty, double descentMps) {
            this.liftPenalty = liftPenalty;
            this.descentMps = descentMps;
        }
    }

    public static final class Input {
        public final Vec3 previousMotion;
        public final Vec3 requestedMotion;
        public final Vec3 look;
        public final Vec3 forward;
        public final Vec3 right;
        public final Vec3 up;
        public final double rollDegrees;
        public final double rotorLiftPower;
        public final double enginePower;
        public final boolean occupied;
        public final boolean wreck;
        public final boolean collectiveUp;
        public final boolean collectiveDown;
        public final boolean steerLeft;
        public final boolean steerRight;
        public final boolean hoverAux;

        public Input(Vec3 previousMotion, Vec3 requestedMotion, Vec3 look, Vec3 forward, Vec3 right, Vec3 up,
                     double rollDegrees, double rotorLiftPower, double enginePower,
                     boolean occupied, boolean wreck, boolean collectiveUp, boolean collectiveDown,
                     boolean steerLeft, boolean steerRight, boolean hoverAux) {
            this.previousMotion = previousMotion;
            this.requestedMotion = requestedMotion;
            this.look = look;
            this.forward = forward;
            this.right = right;
            this.up = up;
            this.rollDegrees = rollDegrees;
            this.rotorLiftPower = rotorLiftPower;
            this.enginePower = enginePower;
            this.occupied = occupied;
            this.wreck = wreck;
            this.collectiveUp = collectiveUp;
            this.collectiveDown = collectiveDown;
            this.steerLeft = steerLeft;
            this.steerRight = steerRight;
            this.hoverAux = hoverAux;
        }
    }

    private final class PitchProfile {
        final double pitchDegrees;
        final double forwardAccelKmhPerTick;
        final double forwardBleedKmhPerTick;
        final double verticalTargetMps;
        final double forwardCapKmh;
        final double descentCapMps;
        final double flareStrength;

        PitchProfile(double pitchDegrees, double forwardAccelKmhPerTick, double forwardBleedKmhPerTick,
                     double verticalTargetMps, double forwardCapKmh, double descentCapMps,
                     double flareStrength) {
            this.pitchDegrees = pitchDegrees;
            this.forwardAccelKmhPerTick = forwardAccelKmhPerTick;
            this.forwardBleedKmhPerTick = forwardBleedKmhPerTick;
            this.verticalTargetMps = verticalTargetMps;
            this.forwardCapKmh = forwardCapKmh;
            this.descentCapMps = descentCapMps;
            this.flareStrength = flareStrength;
        }
    }

    public static final class Result {
        public final Vec3 motion;
        public final double collectiveTarget;
        public final double rotorLiftPower;
        public final double rotorThrustMps2;
        public final double bankIntensity;
        public final double bankCommand;
        public final double liftEfficiency;
        public final double rollDegrees;
        public final double upY;
        public final double thrustAxisY;
        public final double thrustAxisSide;
        public final double targetVerticalMps;
        public final double gravityMps;
        public final double rawForwardKmh;
        public final double rawSideKmh;
        public final double rawVerticalMps;
        public final double forcedForwardKmh;
        public final double forcedSideKmh;
        public final double forcedVerticalMps;
        public final boolean hoverHold;
        public final double hoverAssist;

        private Result(Vec3 motion, double collectiveTarget, double rotorLiftPower, double rotorThrustMps2,
                       double bankIntensity, double bankCommand, double liftEfficiency,
                       double rollDegrees, double upY, double thrustAxisY, double thrustAxisSide,
                       double targetVerticalMps, double gravityMps, double rawForwardKmh, double rawSideKmh,
                       double rawVerticalMps, double forcedForwardKmh, double forcedSideKmh,
                       double forcedVerticalMps, boolean hoverHold, double hoverAssist) {
            this.motion = motion;
            this.collectiveTarget = collectiveTarget;
            this.rotorLiftPower = rotorLiftPower;
            this.rotorThrustMps2 = rotorThrustMps2;
            this.bankIntensity = bankIntensity;
            this.bankCommand = bankCommand;
            this.liftEfficiency = liftEfficiency;
            this.rollDegrees = rollDegrees;
            this.upY = upY;
            this.thrustAxisY = thrustAxisY;
            this.thrustAxisSide = thrustAxisSide;
            this.targetVerticalMps = targetVerticalMps;
            this.gravityMps = gravityMps;
            this.rawForwardKmh = rawForwardKmh;
            this.rawSideKmh = rawSideKmh;
            this.rawVerticalMps = rawVerticalMps;
            this.forcedForwardKmh = forcedForwardKmh;
            this.forcedSideKmh = forcedSideKmh;
            this.forcedVerticalMps = forcedVerticalMps;
            this.hoverHold = hoverHold;
            this.hoverAssist = hoverAssist;
        }

        public static Result inactive(Vec3 motion, double rotorLiftPower) {
            return new Result(motion, 0.0D, rotorLiftPower, rotorLiftPower * MAX_ROTOR_ACCEL_MPS2,
                    0.0D, 0.0D, 0.0D,
                    0.0D, 1.0D, 1.0D, 0.0D,
                    0.0D, -GRAVITY_ACCEL_MPS2,
                    0.0D, 0.0D, motion.f_82480_ * TICKS_PER_SECOND,
                    0.0D, 0.0D, motion.f_82480_ * TICKS_PER_SECOND,
                    false, 0.0D);
        }
    }
}

