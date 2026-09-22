package com.yourname.berts_vehicle_pack.entity.helicopter;

import com.atsuishio.superbwarfare.api.vehicle.camera.VehicleCameraMode;
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightInputContext;
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightInstrumentSnapshot;
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightStrategy;
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightStrategyProvider;
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightTickResult;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils;
import com.mojang.logging.LogUtils;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public abstract class BvpHelicopterEntity extends ArmoredVehicleEntity implements VehicleFlightStrategyProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FORCE_LOG_INTERVAL_TICKS =
            Math.max(1, Integer.getInteger("bvp.debug.forceLogIntervalTicks", 5));
    private static final boolean FORCE_VECTOR_AUTHORITY =
            Boolean.parseBoolean(System.getProperty("bvp.flight.forceAuthority", "true"));
    private static final double PHYSICAL_COLLECTIVE_NEUTRAL = 0.50D;
    private static final double PHYSICAL_COLLECTIVE_STEP_PER_TICK = 0.05D;
    private static final double PHYSICAL_THRUST_STEP_PER_TICK = 0.33D / 20.0D;
    private static final double PHYSICAL_ROTOR_SPOOL_UP_PER_TICK = 0.030D;
    private static final double PHYSICAL_ROTOR_SPOOL_DOWN_PER_TICK = 0.026D;
    private static final double FORCE_AUTHORITY_MAX_HORIZONTAL_KMH = 240.0D;
    private static final double FORCE_AUTHORITY_MAX_VERTICAL_MPS = 40.0D;
    private static final boolean DEBUG_FORCE_LOGS =
            Boolean.parseBoolean(System.getProperty("bvp.debug.forceLogs", "false"));

    private final HelicopterControlInput controlInput = new HelicopterControlInput();
    private final HelicopterFlightProfile flightProfile;
    private final HelicopterFlightController flightController;
    private final HelicopterForceModel forceModel;
    private final VehicleFlightStrategy nativeFlightStrategy;
    private Vec3 lastHorizontalForward = new Vec3(0.0D, 0.0D, 1.0D);
    private Vec3 lastHorizontalRight = new Vec3(-1.0D, 0.0D, 0.0D);
    private double helicopterPhysicalCollective = PHYSICAL_COLLECTIVE_NEUTRAL;
    private double helicopterThrottleTarget;
    private double helicopterCollectiveTarget;
    private double helicopterRotorLiftPower;
    private double helicopterRotorThrustMps2;
    private double helicopterLastBankIntensity;
    private double helicopterLastBankCommand;
    private double helicopterLastLiftEfficiency = 1.0D;
    private double helicopterLastRollDegrees;
    private double helicopterLastUpY = 1.0D;
    private double helicopterLastThrustAxisY = 1.0D;
    private double helicopterLastThrustAxisSide;
    private double helicopterLastTargetVerticalMps;
    private double helicopterLastGravityMps;
    private double helicopterLastForwardKmh;
    private double helicopterLastSideKmh;
    private double helicopterLastVerticalMps;
    private double helicopterLastRawForwardKmh;
    private double helicopterLastRawSideKmh;
    private double helicopterLastRawVerticalMps;
    private boolean helicopterLastHoverHold;
    private double helicopterLastHoverAssist;
    private HelicopterForceModel.Result helicopterLastForceModel = HelicopterForceModel.Result.zero();

    protected BvpHelicopterEntity(EntityType<? extends BvpHelicopterEntity> type, Level world,
                                  String armorProfileId, HelicopterFlightProfile flightProfile) {
        super(type, world, armorProfileId);
        HelicopterFlightProfile profile = flightProfile == null ? HelicopterFlightProfile.mi24v() : flightProfile;
        this.flightProfile = profile;
        this.flightController = new HelicopterFlightController(profile);
        this.forceModel = new HelicopterForceModel(profile);
        this.nativeFlightStrategy = new VehicleFlightStrategy() {
            @Override
            public void prepareServer(VehicleEntity vehicle) {
                vehicle.applyHelicopterControlLifecycleForFlightStrategy();
            }

            @Override
            public VehicleFlightTickResult tickServer(VehicleEntity vehicle, VehicleFlightInputContext input) {
                return runBvpFlightStrategy(input);
            }

            @Override
            public void onClientInstrumentSnapshot(VehicleEntity vehicle,
                                                   VehicleFlightInstrumentSnapshot snapshot) {
                consumeBvpFlightInstruments(snapshot);
            }
        };
    }

    @Override
    protected Vec3 bvpSeatEyePosition(Entity passenger, float partialTicks, boolean zooming) {
        if (passenger != null && getSeatIndex(passenger) == 0) {
            return VehicleVecUtils.INSTANCE.getCameraPos(this, passenger, partialTicks);
        }
        return super.bvpSeatEyePosition(passenger, partialTicks, zooming);
    }

    @Override
    protected VehicleCameraMode bvpSeatDefaultCameraMode(Entity passenger, int seatIndex,
                                                         int selectedWeaponIndex) {
        if (seatIndex == 0) {
            return VehicleCameraMode.AIRCRAFT_FREELOOK;
        }
        return super.bvpSeatDefaultCameraMode(passenger, seatIndex, selectedWeaponIndex);
    }

    @Override
    public VehicleFlightStrategy createVehicleFlightStrategy(VehicleEntity vehicle) {
        return this.nativeFlightStrategy;
    }

    private VehicleFlightTickResult runBvpFlightStrategy(VehicleFlightInputContext input) {
        this.controlInput.capture(input.getRawInputBits());
        Vec3 motion = applyBvpHelicopterFlight(input);
        logHelicopterForceSample(input.getPreviousMotion(), input.getRequestedMotion(), motion);
        return new VehicleFlightTickResult(
                motion,
                this.helicopterRotorLiftPower,
                this.helicopterCollectiveTarget,
                this.helicopterRotorThrustMps2,
                this.helicopterThrottleTarget,
                m_146908_(),
                m_146909_(),
                getRoll(),
                true
        );
    }

    private void consumeBvpFlightInstruments(VehicleFlightInstrumentSnapshot snapshot) {
        Vec3 motion = snapshot.getMotion();
        updateHelicopterClientTelemetry(motion, snapshot.getRotorLift());
        this.helicopterCollectiveTarget = snapshot.getCollective();
        this.helicopterThrottleTarget = snapshot.getThrottle();
        this.helicopterRotorLiftPower = snapshot.getRotorLift();
        this.helicopterRotorThrustMps2 = snapshot.getThrust();
    }

    protected String bvpHelicopterExtraLog() {
        return "";
    }

    private Vec3 applyBvpHelicopterFlight(VehicleFlightInputContext nativeInput) {
        Vec3 previousMotion = nativeInput.getPreviousMotion();
        Vec3 requestedMotion = nativeInput.getRequestedMotion();
        Vec3 look = nativeInput.getLookDirection();
        Vec3 forward = resolveHorizontalForward(look);
        Vec3 right = this.lastHorizontalRight;
        Vec3 up = nativeInput.getUpDirection();
        boolean occupied = nativeInput.getOccupied();
        boolean wreck = nativeInput.getWreck();
        double inputRotorPower = this.helicopterRotorLiftPower;
        double inputEnginePower = clamp(nativeInput.getEnginePower(), 0.0D, 1.0D);
        if (FORCE_VECTOR_AUTHORITY) {
            tickPhysicalControlState(occupied, wreck);
            inputEnginePower = this.helicopterPhysicalCollective;
            inputRotorPower = nextPhysicalRotorPower(inputRotorPower, physicalRotorTarget());
        }
        HelicopterFlightController.Input input = new HelicopterFlightController.Input(
                previousMotion,
                requestedMotion,
                look,
                forward,
                right,
                up,
                dominantRollDegrees(),
                inputRotorPower,
                inputEnginePower,
                occupied,
                wreck,
                this.controlInput.collectiveUp(),
                this.controlInput.collectiveDown(),
                this.controlInput.steerLeft(),
                this.controlInput.steerRight(),
                nativeInput.getHoverMode()
        );
        Vec3 finalMotion;
        if (FORCE_VECTOR_AUTHORITY) {
            this.helicopterLastForceModel = this.forceModel.evaluate(input, null);
            finalMotion = clampForceAuthorityMotion(this.helicopterLastForceModel.predictedMotion);
            applyPhysicalForceTelemetry(input, requestedMotion, finalMotion, forward, right);
        } else {
            HelicopterFlightController.Result result = this.flightController.apply(input);
            this.helicopterLastForceModel = DEBUG_FORCE_LOGS
                    ? this.forceModel.evaluate(input, result)
                    : HelicopterForceModel.Result.zero();
            applyHelicopterResult(result);
            finalMotion = result.motion;
            if (wreck) {
                finalMotion = finalMotion.m_82520_(0.0D, -nativeInput.getGravityPerTick(), 0.0D);
            }
            applyFinalMotionTelemetry(requestedMotion, finalMotion, forward, right);
        }
        return finalMotion;
    }

    private static double nextPhysicalRotorPower(double current, double target) {
        double step = target > current ? PHYSICAL_ROTOR_SPOOL_UP_PER_TICK : PHYSICAL_ROTOR_SPOOL_DOWN_PER_TICK;
        return approach(current, target, step);
    }

    private void tickPhysicalControlState(boolean occupied, boolean wreck) {
        double collectiveTarget = PHYSICAL_COLLECTIVE_NEUTRAL;
        if (occupied && !wreck) {
            if (this.controlInput.collectiveUp() != this.controlInput.collectiveDown()) {
                collectiveTarget = this.controlInput.collectiveUp() ? 1.0D : 0.0D;
            }
            if (this.controlInput.thrustUp() != this.controlInput.thrustDown()) {
                double direction = this.controlInput.thrustUp() ? 1.0D : -1.0D;
                this.helicopterThrottleTarget = clamp(
                        this.helicopterThrottleTarget + direction * PHYSICAL_THRUST_STEP_PER_TICK,
                        0.0D, 1.0D);
            }
        } else {
            this.helicopterThrottleTarget = approach(this.helicopterThrottleTarget, 0.0D,
                    PHYSICAL_THRUST_STEP_PER_TICK);
        }
        this.helicopterPhysicalCollective = approach(this.helicopterPhysicalCollective,
                collectiveTarget, PHYSICAL_COLLECTIVE_STEP_PER_TICK);
    }

    private double physicalRotorTarget() {
        return clamp(this.helicopterThrottleTarget, 0.0D, 1.0D);
    }

    private Vec3 clampForceAuthorityMotion(Vec3 motion) {
        if (motion == null) {
            return Vec3.f_82478_;
        }
        double horizontal = horizontalSpeed(motion);
        double maxHorizontal = FORCE_AUTHORITY_MAX_HORIZONTAL_KMH / HelicopterFlightController.KMH_PER_BLOCK_PER_TICK;
        double x = motion.f_82479_;
        double z = motion.f_82481_;
        if (horizontal > maxHorizontal && horizontal > 1.0E-6D) {
            double scale = maxHorizontal / horizontal;
            x *= scale;
            z *= scale;
        }
        double maxVertical = FORCE_AUTHORITY_MAX_VERTICAL_MPS / HelicopterForceModel.TICKS_PER_SECOND;
        double y = clamp(motion.f_82480_, -maxVertical, maxVertical);
        if (x == motion.f_82479_ && y == motion.f_82480_ && z == motion.f_82481_) {
            return motion;
        }
        return new Vec3(x, y, z);
    }

    private void applyFinalMotionTelemetry(Vec3 rawMotion, Vec3 finalMotion, Vec3 forward, Vec3 right) {
        this.helicopterLastRawForwardKmh = rawMotion.m_82526_(forward) * HelicopterFlightController.KMH_PER_BLOCK_PER_TICK;
        this.helicopterLastRawSideKmh = rawMotion.m_82526_(right) * HelicopterFlightController.KMH_PER_BLOCK_PER_TICK;
        this.helicopterLastRawVerticalMps = rawMotion.f_82480_ * HelicopterForceModel.TICKS_PER_SECOND;
        this.helicopterLastForwardKmh = finalMotion.m_82526_(forward) * HelicopterFlightController.KMH_PER_BLOCK_PER_TICK;
        this.helicopterLastSideKmh = finalMotion.m_82526_(right) * HelicopterFlightController.KMH_PER_BLOCK_PER_TICK;
        this.helicopterLastVerticalMps = finalMotion.f_82480_ * HelicopterForceModel.TICKS_PER_SECOND;
    }

    private void applyPhysicalForceTelemetry(HelicopterFlightController.Input input, Vec3 rawMotion,
                                             Vec3 finalMotion, Vec3 forward, Vec3 right) {
        HelicopterForceModel.Result force = this.helicopterLastForceModel;
        this.helicopterCollectiveTarget = force.collective;
        this.helicopterRotorLiftPower = force.rotorPower;
        this.helicopterRotorThrustMps2 = force.mainRotorAccelMps2.m_82553_();
        this.helicopterLastBankIntensity = Math.abs(force.mainRotorAxis.m_82526_(right));
        this.helicopterLastBankCommand = (input.steerRight ? 1.0D : 0.0D) - (input.steerLeft ? 1.0D : 0.0D);
        this.helicopterLastLiftEfficiency = clamp(force.mainRotorAxis.f_82480_, 0.0D, 1.0D);
        this.helicopterLastRollDegrees = input.rollDegrees;
        this.helicopterLastUpY = input.up.f_82480_;
        this.helicopterLastThrustAxisY = force.mainRotorAxis.f_82480_;
        this.helicopterLastThrustAxisSide = force.mainRotorAxis.m_82526_(right);
        this.helicopterLastTargetVerticalMps = force.predictedVerticalMps;
        this.helicopterLastGravityMps = -HelicopterForceModel.GRAVITY_MPS2;
        this.helicopterLastHoverHold = false;
        this.helicopterLastHoverAssist = 0.0D;
        applyFinalMotionTelemetry(rawMotion, finalMotion, forward, right);
    }

    private void applyHelicopterResult(HelicopterFlightController.Result result) {
        this.helicopterCollectiveTarget = result.collectiveTarget;
        this.helicopterRotorLiftPower = result.rotorLiftPower;
        this.helicopterRotorThrustMps2 = result.rotorThrustMps2;
        this.helicopterLastBankIntensity = result.bankIntensity;
        this.helicopterLastBankCommand = result.bankCommand;
        this.helicopterLastLiftEfficiency = result.liftEfficiency;
        this.helicopterLastRollDegrees = result.rollDegrees;
        this.helicopterLastUpY = result.upY;
        this.helicopterLastThrustAxisY = result.thrustAxisY;
        this.helicopterLastThrustAxisSide = result.thrustAxisSide;
        this.helicopterLastTargetVerticalMps = result.targetVerticalMps;
        this.helicopterLastGravityMps = result.gravityMps;
        this.helicopterLastRawForwardKmh = result.rawForwardKmh;
        this.helicopterLastRawSideKmh = result.rawSideKmh;
        this.helicopterLastRawVerticalMps = result.rawVerticalMps;
        this.helicopterLastForwardKmh = result.forcedForwardKmh;
        this.helicopterLastSideKmh = result.forcedSideKmh;
        this.helicopterLastVerticalMps = result.forcedVerticalMps;
        this.helicopterLastHoverHold = result.hoverHold;
        this.helicopterLastHoverAssist = result.hoverAssist;
    }

    private void updateHelicopterClientTelemetry(Vec3 motion, double rotorLiftPower) {
        updateHelicopterTelemetryFromMotion(motion, motion, rotorLiftPower);
    }

    private void updateHelicopterTelemetryFromMotion(Vec3 rawMotion, Vec3 forcedMotion, double rotorLiftPower) {
        Vec3 forward = horizontalForwardVector(m_20252_(1.0F));
        if (forward == null) {
            return;
        }
        Vec3 right = horizontalRightVector(forward);
        this.helicopterLastRawForwardKmh = rawMotion.m_82526_(forward) * HelicopterFlightController.KMH_PER_BLOCK_PER_TICK;
        this.helicopterLastRawSideKmh = rawMotion.m_82526_(right) * HelicopterFlightController.KMH_PER_BLOCK_PER_TICK;
        this.helicopterLastRawVerticalMps = rawMotion.f_82480_ * 20.0D;
        this.helicopterLastForwardKmh = forcedMotion.m_82526_(forward) * HelicopterFlightController.KMH_PER_BLOCK_PER_TICK;
        this.helicopterLastSideKmh = forcedMotion.m_82526_(right) * HelicopterFlightController.KMH_PER_BLOCK_PER_TICK;
        this.helicopterLastVerticalMps = forcedMotion.f_82480_ * 20.0D;
        if (!m_9236_().f_46443_) {
            this.helicopterLastBankIntensity = 0.0D;
            this.helicopterLastBankCommand = 0.0D;
        }
        this.helicopterLastLiftEfficiency = 0.0D;
        this.helicopterLastRollDegrees = 0.0D;
        this.helicopterLastUpY = 1.0D;
        this.helicopterLastThrustAxisY = 1.0D;
        this.helicopterLastThrustAxisSide = 0.0D;
        this.helicopterLastTargetVerticalMps = 0.0D;
        this.helicopterLastGravityMps = 0.0D;
        this.helicopterCollectiveTarget = 0.0D;
        this.helicopterThrottleTarget = 0.0D;
        this.helicopterRotorLiftPower = rotorLiftPower;
        this.helicopterRotorThrustMps2 = 0.0D;
        this.helicopterLastHoverHold = false;
        this.helicopterLastHoverAssist = 0.0D;
    }

    public double getBvpRotorLiftPower() {
        VehicleFlightInstrumentSnapshot snapshot = getVehicleFlightInstrumentSnapshot(1.0F);
        return snapshot.getSequence() == 0 ? this.helicopterRotorLiftPower : snapshot.getRotorLift();
    }

    public double getBvpThrottleTarget() {
        VehicleFlightInstrumentSnapshot snapshot = getVehicleFlightInstrumentSnapshot(1.0F);
        return snapshot.getSequence() == 0 ? this.helicopterThrottleTarget : snapshot.getThrottle();
    }

    public double getBvpCollectiveTarget() {
        VehicleFlightInstrumentSnapshot snapshot = getVehicleFlightInstrumentSnapshot(1.0F);
        return snapshot.getSequence() == 0 ? this.helicopterCollectiveTarget : snapshot.getCollective();
    }

    public double getBvpRotorThrustMps2() {
        VehicleFlightInstrumentSnapshot snapshot = getVehicleFlightInstrumentSnapshot(1.0F);
        return snapshot.getSequence() == 0 ? this.helicopterRotorThrustMps2 : snapshot.getThrust();
    }

    public double getBvpLiftEfficiency() {
        return this.helicopterLastLiftEfficiency;
    }

    public double getBvpRollDegrees() {
        return this.helicopterLastRollDegrees;
    }

    public double getBvpUpY() {
        return this.helicopterLastUpY;
    }

    public double getBvpThrustAxisY() {
        return this.helicopterLastThrustAxisY;
    }

    public double getBvpThrustAxisSide() {
        return this.helicopterLastThrustAxisSide;
    }

    public double getBvpTargetVerticalMps() {
        return this.helicopterLastTargetVerticalMps;
    }

    public double getBvpNaturalForwardCapKmh() {
        return this.flightController.maxForwardKmh();
    }

    public double getBvpBoostForwardCapKmh() {
        return this.flightController.maxBoostForwardKmh();
    }

    public double getBvpBankIntensity() {
        return this.helicopterLastBankIntensity;
    }

    public double getBvpForwardSpeedKmh() {
        return this.helicopterLastForwardKmh;
    }

    public double getBvpSideSpeedKmh() {
        return this.helicopterLastSideKmh;
    }

    public double getBvpVerticalSpeedMps() {
        return this.helicopterLastVerticalMps;
    }

    public double getBvpRawForwardSpeedKmh() {
        return this.helicopterLastRawForwardKmh;
    }

    public double getBvpRawSideSpeedKmh() {
        return this.helicopterLastRawSideKmh;
    }

    public double getBvpRawVerticalSpeedMps() {
        return this.helicopterLastRawVerticalMps;
    }

    public int getBvpRawInputBits() {
        return this.controlInput.rawInput() & 0xFFFF;
    }

    public boolean getBvpCollectiveUpInput() {
        return this.controlInput.collectiveUp();
    }

    public boolean getBvpCollectiveDownInput() {
        return this.controlInput.collectiveDown();
    }

    public boolean getBvpHoverAuxInput() {
        return sbwHoverModeEnabled();
    }

    public boolean getBvpHoverHoldActive() {
        return this.helicopterLastHoverHold;
    }

    public double getBvpHoverAssist() {
        return this.helicopterLastHoverAssist;
    }

    public double getBvpForcePitchDegrees() {
        return this.helicopterLastForceModel.pitchDegrees;
    }

    public double getBvpForceMainRotorAccelMps2() {
        return this.helicopterLastForceModel.mainRotorAccelMps2.m_82553_();
    }

    public double getBvpForceVerticalRotorAccelMps2() {
        return this.helicopterLastForceModel.verticalRotorAccelMps2;
    }

    public double getBvpForceVerticalNetAccelMps2() {
        return this.helicopterLastForceModel.verticalNetAccelMps2;
    }

    public double getBvpForcePredictedForwardKmh() {
        return this.helicopterLastForceModel.predictedForwardKmh;
    }

    public double getBvpForcePredictedSideKmh() {
        return this.helicopterLastForceModel.predictedSideKmh;
    }

    public double getBvpForcePredictedVerticalMps() {
        return this.helicopterLastForceModel.predictedVerticalMps;
    }

    public boolean getBvpSbwHoverMode() {
        return sbwHoverModeEnabled();
    }

    private static Vec3 horizontalForwardVector(Vec3 look) {
        if (look == null || !Double.isFinite(look.f_82479_) || !Double.isFinite(look.f_82481_)) {
            return null;
        }
        double length = Math.sqrt(look.f_82479_ * look.f_82479_ + look.f_82481_ * look.f_82481_);
        if (length < 1.0E-6D) {
            return null;
        }
        return new Vec3(look.f_82479_ / length, 0.0D, look.f_82481_ / length);
    }

    private static Vec3 horizontalRightVector(Vec3 forward) {
        return new Vec3(-forward.f_82481_, 0.0D, forward.f_82479_);
    }

    private Vec3 resolveHorizontalForward(Vec3 look) {
        Vec3 forward = horizontalForwardVector(look);
        if (forward != null) {
            this.lastHorizontalForward = forward;
            this.lastHorizontalRight = horizontalRightVector(forward);
        }
        return this.lastHorizontalForward;
    }

    private static double horizontalSpeed(Vec3 motion) {
        return Math.sqrt(motion.f_82479_ * motion.f_82479_ + motion.f_82481_ * motion.f_82481_);
    }

    private double dominantRollDegrees() {
        double rollAngle = getRollAngle();
        double roll = getRoll();
        return Math.abs(rollAngle) >= Math.abs(roll) ? rollAngle : roll;
    }

    private boolean sbwHoverModeEnabled() {
        return getHoverMode();
    }

    private void logHelicopterForceSample(Vec3 previousMotion, Vec3 requestedMotion, Vec3 forcedMotion) {
        // One compact server sample per second makes the shared V2 flight model tunable from exported logs.
        if (!DEBUG_FORCE_LOGS) {
            return;
        }
        if (this.f_19797_ % FORCE_LOG_INTERVAL_TICKS != 0) {
            return;
        }
        HelicopterForceModel.Result force = this.helicopterLastForceModel;
        LOGGER.info("[BVP_HELI_FORCE] side={} id={} profile={} forceAuthority={} previous={} requested={} forced={} rawForwardKmh={} rawSideKmh={} rawVerticalMps={} forcedForwardKmh={} forcedSideKmh={} forcedVerticalMps={} maxForwardKmh={} maxBoostForwardKmh={} bank={} bankCommand={} rollDeg={} upY={} thrustAxisY={} thrustAxisSide={} liftEff={} gravityAccelMps2={} targetVerticalMps={} collectiveTarget={} throttleTarget={} rotorLift={} rotorThrustMps2={} forcePitchDeg={} forceMainAxis={} forceTailAxis={} forceVelocityMps={} forceGravity={} forceMainRotorAccel={} forceWingLiftAccel={} forceTailRotorAccel={} forceDragAccel={} forceNetAccel={} forceVerticalRotorAccel={} forceVerticalNetAccel={} forcePredictedMotion={} forcePredictedForwardKmh={} forcePredictedSideKmh={} forcePredictedVerticalMps={} forceMainRotorN={} forceNetN={} massKg={} enginePowerScale={} wingLiftCoefficient={} tailRotorAuthority={} cyclicAuthority={} pitchResponse={} rollResponse={} yawResponse={} rotationalInertia={} power={} rawInput={} collectiveUp={} collectiveDown={} thrustUp={} thrustDown={} steerL={} steerR={} hoverAux={} hoverHold={} hoverAssist={} sbwHover={} rawUp={} rawDown={} fire={} extra={}",
                this.m_9236_().f_46443_ ? "client" : "server",
                this.m_19879_(),
                getArmorProfileId(),
                FORCE_VECTOR_AUTHORITY,
                formatVec(previousMotion),
                formatVec(requestedMotion),
                formatVec(forcedMotion),
                formatDouble(this.helicopterLastRawForwardKmh),
                formatDouble(this.helicopterLastRawSideKmh),
                formatDouble(this.helicopterLastRawVerticalMps),
                formatDouble(this.helicopterLastForwardKmh),
                formatDouble(this.helicopterLastSideKmh),
                formatDouble(this.helicopterLastVerticalMps),
                formatDouble(this.flightController.maxForwardKmh()),
                formatDouble(this.flightController.maxBoostForwardKmh()),
                formatDouble(this.helicopterLastBankIntensity),
                formatDouble(this.helicopterLastBankCommand),
                formatDouble(this.helicopterLastRollDegrees),
                formatDouble(this.helicopterLastUpY),
                formatDouble(this.helicopterLastThrustAxisY),
                formatDouble(this.helicopterLastThrustAxisSide),
                formatDouble(this.helicopterLastLiftEfficiency),
                formatDouble(this.helicopterLastGravityMps),
                formatDouble(this.helicopterLastTargetVerticalMps),
                formatDouble(this.helicopterCollectiveTarget),
                formatDouble(this.helicopterThrottleTarget),
                formatDouble(this.helicopterRotorLiftPower),
                formatDouble(this.helicopterRotorThrustMps2),
                formatDouble(force.pitchDegrees),
                formatVec(force.mainRotorAxis),
                formatVec(force.tailRotorAxis),
                formatVec(force.velocityMps),
                formatVec(force.gravityAccelMps2),
                formatVec(force.mainRotorAccelMps2),
                formatVec(force.wingLiftAccelMps2),
                formatVec(force.tailRotorAccelMps2),
                formatVec(force.dragAccelMps2),
                formatVec(force.netAccelMps2),
                formatDouble(force.verticalRotorAccelMps2),
                formatDouble(force.verticalNetAccelMps2),
                formatVec(force.predictedMotion),
                formatDouble(force.predictedForwardKmh),
                formatDouble(force.predictedSideKmh),
                formatDouble(force.predictedVerticalMps),
                formatDouble(force.mainRotorForceN),
                formatDouble(force.netForceN),
                formatDouble(force.massKg),
                formatDouble(this.flightProfile.enginePowerScale()),
                formatDouble(this.flightProfile.wingLiftCoefficient()),
                formatDouble(this.flightProfile.tailRotorAuthorityScale()),
                formatDouble(this.flightProfile.cyclicAuthorityScale()),
                formatDouble(this.flightProfile.pitchResponseScale()),
                formatDouble(this.flightProfile.rollResponseScale()),
                formatDouble(this.flightProfile.yawResponseScale()),
                formatDouble(this.flightProfile.rotationalInertiaScale()),
                formatDouble(getPower()),
                this.controlInput.rawInput() & 0xFFFF,
                this.controlInput.collectiveUp(),
                this.controlInput.collectiveDown(),
                this.controlInput.thrustUp(),
                this.controlInput.thrustDown(),
                this.controlInput.steerLeft(),
                this.controlInput.steerRight(),
                getBvpHoverAuxInput(),
                this.helicopterLastHoverHold,
                formatDouble(this.helicopterLastHoverAssist),
                sbwHoverModeEnabled(),
                this.controlInput.up(),
                this.controlInput.down(),
                this.controlInput.fire(),
                bvpHelicopterExtraLog());
    }

    private static String formatVec(Vec3 vec) {
        if (vec == null) {
            return "null";
        }
        return String.format(java.util.Locale.ROOT, "(%.3f,%.3f,%.3f)", vec.f_82479_, vec.f_82480_, vec.f_82481_);
    }

    protected static String formatDouble(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    protected static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double approach(double value, double target, double maxStep) {
        if (value < target) {
            return Math.min(target, value + maxStep);
        }
        return Math.max(target, value - maxStep);
    }
}
