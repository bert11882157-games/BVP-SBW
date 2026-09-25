package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Server integration adapter for the independent aircraft model.
 * Reference aircraft specifications remain separate from the game-scale handling profile.
 */
open class FixedWingFlightStrategy @JvmOverloads constructor(
    val profile: FixedWingFlightProfile,
    val handling: FixedWingHandlingProfile = FixedWingHandlingProfile.GAME_JET,
) : VehicleFlightStrategy() {
    override val strategyKind = VehicleFlightStrategyKind.FIXED_WING

    private val model = FixedWingFlightModel(handling)
    private val mouseAim = FixedWingMouseAimController(handling)
    private val pilotIntent = FixedWingPilotIntentState()
    private var initialized = false
    private var lastServerTick = Long.MIN_VALUE
    private var controllerUuid: UUID? = null
    private var afterburnerRemaining = profile.afterburnerFuelSeconds ?: 0.0
    private val limitedAfterburner = profile.afterburnerFuelSeconds != null
    private var surfaceSnapshot: FixedWingControlSurfaceSnapshot? = null
    private val afterburnerControl = FixedWingAfterburnerControl()
    private val sonicCrossing = FixedWingSonicCrossing()
    private val afterburnerFuelCostPerTick =
        profile.afterburnerConsumptionPerSecond * FixedWingFlightModel.DT
    private var groundPitchSource: AircraftTerrainContact? = null
    private var groundPitchLimit = FixedWingFlightModel.MAX_GROUND_PITCH_DEGREES

    /** Observes admitted edges without advancing dynamics, including two edges in one tick. */
    fun observePilotThrottleInput(vehicle: VehicleEntity, throttleAxis: Double) {
        if (vehicle.level().isClientSide) return
        val pilot = vehicle.getNthEntity(0) as? Player
        val controlsEnabled = pilot != null && pilot.vehicle === vehicle &&
            pilot.isAlive && !pilot.isSpectator && pilot.uuid == controllerUuid
        afterburnerControl.update(
            model.throttle,
            throttleAxis,
            afterburnerEligible(vehicle, controlsEnabled, engineAvailability(vehicle)),
        )
    }

    private fun afterburnerEligible(
        vehicle: VehicleEntity,
        controlsEnabled: Boolean,
        engineAvailability: Double,
    ): Boolean =
        controlsEnabled && !vehicle.isWreck && !vehicle.isInFluidType &&
            profile.afterburnerEnabled && vehicle.computed().afterburner && engineAvailability > 0.0 &&
            vehicle.hasOperationalPower(handling.afterburnerOperationalEnergyPerTick) &&
            (!limitedAfterburner ||
                (afterburnerRemaining > 0.0 && afterburnerRemaining >= afterburnerFuelCostPerTick))

    override fun onActivated(vehicle: VehicleEntity) {
        resetFromPhysicalPose(vehicle)
        afterburnerRemaining = profile.afterburnerFuelSeconds ?: 0.0
    }

    override fun onDeactivated(vehicle: VehicleEntity) {
        sonicCrossing.reset()
        clearPilotControls()
        initialized = false
        lastServerTick = Long.MIN_VALUE
    }

    /** May be called immediately on an accepted pilot lifecycle transition. */
    fun clearPilotControls() {
        controllerUuid = null
        pilotIntent.clear()
        mouseAim.reset()
        model.resetControls(preserveThrottle = true)
        afterburnerControl.reset()
        surfaceSnapshot = null
    }

    override fun tickServer(
        vehicle: VehicleEntity,
        input: VehicleFlightInputContext,
    ): VehicleFlightTickResult {
        check(!vehicle.level().isClientSide) { "Fixed-wing dynamics are server-authoritative" }
        if (!initialized ||
            (lastServerTick != Long.MIN_VALUE && input.serverTick - lastServerTick != 1L) ||
            attitudeDiscontinuous(input)
        ) {
            resetFromPhysicalPose(vehicle)
        }

        val pilot = synchronizePilot(vehicle)
        val controlsEnabled = pilot != null && input.occupied && !input.wreck

        val energyCost = handling.operationalEnergyPerTick
        val propulsionAvailable = !input.wreck && !input.inFluid &&
            vehicle.hasOperationalPower(energyCost)
        val engineAvailability = if (propulsionAvailable) engineAvailability(vehicle) else 0.0
        val requestedAfterburner = afterburnerControl.update(
            model.throttle,
            input.fixedWingThrottleAxis,
            afterburnerEligible(vehicle, controlsEnabled, engineAvailability),
        )

        val referenceAltitude =
            (vehicle.y - vehicle.level().seaLevel) / handling.simulationLengthScale
        val density = FixedWingAtmosphere.densityRatio(referenceAltitude)
        val temperature = FixedWingAtmosphere.temperatureKelvin(referenceAltitude)
        val vx = input.airVelocity.x * TICKS_PER_SECOND
        val vy = input.airVelocity.y * TICKS_PER_SECOND
        val vz = input.airVelocity.z * TICKS_PER_SECOND
        mouseAim.update(model, pilotIntent.sample(input.serverTick), controlsEnabled,
            input.onGround, vx, vy, vz, density)
        model.groundPitchLimitDegrees = groundPitchLimit(vehicle)
        val validStep = model.step(
            serverTick = input.serverTick,
            inputVelocityX = vx,
            inputVelocityY = vy,
            inputVelocityZ = vz,
            grounded = input.onGround,
            controlsEnabled = controlsEnabled,
            throttleAxis = input.fixedWingThrottleAxis,
            airbrakeRequested = input.fixedWingAirbrakeRequested,
            afterburnerRequested = requestedAfterburner,
            engineAvailability = engineAvailability,
            airDensityRatio = density,
            airTemperatureKelvin = temperature,
            surfaces = mouseAim,
            surfaceDamage = FixedWingSurfaceDamage.from(vehicle),
            gearDeployment = if (vehicle.hasFixedWingLandingGear()) 1.0 - vehicle.synchedGearRot else 0.0,
            // Fixed tyres also support runway acceleration; retractable-gear drag stays separate.
            wheelsDeployed = vehicle.computed().aircraftTerrainContact?.let {
                it.validWheelContacts() && it.gearDeployed(vehicle.synchedGearRot)
            } == true,
        )
        if (!validStep) afterburnerControl.reset()
        if (input.serverTick % 2L == 0L && pilot != null &&
            com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.isServerEnabled()) {
            com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.record(vehicle, "fixed_wing", "SPEED_STEP",
                "input_kmh", input.airVelocity.length() * 72.0, "output_kmh", model.speedMps * 3.6,
                "grounded", input.onGround, "throttle", model.throttle, "engine", engineAvailability,
                "afterburner", model.afterburnerActive, "thrust", model.thrustAccelerationMps2,
                "wheel_contact_admitted", model.runwayContactAdmitted,
                "runway_launch_multiplier", model.runwayLaunchMultiplier,
                "runway_base_horizontal_thrust_mps2", model.runwayBaseHorizontalThrustMps2,
                "drag", model.dragAccelerationMps2, "maneuver_drag", model.maneuverDragAccelerationMps2,
                "valid", validStep)
        }
        if (!validStep || input.onGround || input.wreck) sonicCrossing.reset()
        else if (sonicCrossing.update(model.speedMps * 3.6, input.serverTick)) {
            FixedWingSonicBoom.emit(vehicle)
            com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.record(vehicle, "fixed_wing", "SONIC_BOOM",
                "speed_kmh", model.speedMps * 3.6, "threshold_kmh", 350)
        }
        if (model.thrustAccelerationMps2 > 0.0) {
            vehicle.consumeOperationalPower(
                if (model.afterburnerActive) handling.afterburnerOperationalEnergyPerTick else energyCost,
            )
        }
        if (model.afterburnerActive && limitedAfterburner) {
            afterburnerRemaining = max(
                0.0,
                afterburnerRemaining -
                    afterburnerFuelCostPerTick,
            )
        }
        lastServerTick = input.serverTick
        initialized = true
        surfaceSnapshot = null

        return VehicleFlightTickResult(
            motion = Vec3(
                model.velocityX / TICKS_PER_SECOND,
                model.velocityY / TICKS_PER_SECOND,
                model.velocityZ / TICKS_PER_SECOND,
            ),
            rotorLift = model.normalizedLift,
            collective = model.angleOfAttackDegrees,
            thrust = model.thrustAccelerationMps2 * profile.massKg,
            throttle = model.throttle,
            bodyYaw = model.yawDegrees.toFloat(),
            bodyPitch = model.pitchDegrees.toFloat(),
            bodyRoll = model.rollDegrees.toFloat(),
            motionIncludesGravity = true,
            fixedWingSignedLiftG = if (validStep)
                (model.liftAccelerationMps2 / handling.gravityMps2).takeIf(Double::isFinite)
            else null,
        )
    }

    /**
     * On-demand authoritative deflections. A client must use the synchronized counterpart,
     * never run this model or infer a control surface from camera/player view.
     */
    fun controlSurfaceSnapshot(): FixedWingControlSurfaceSnapshot? {
        if (lastServerTick == Long.MIN_VALUE) return null
        surfaceSnapshot?.let { return it }
        return FixedWingControlSurfaceSnapshot(
            serverTick = lastServerTick,
            elevator = model.elevator.toFloat(),
            aileron = model.aileron.toFloat(),
            rudder = model.rudder.toFloat(),
            airbrake = model.airbrake.toFloat(),
            throttle = model.throttle.toFloat(),
            afterburnerActive = model.afterburnerActive,
            wheelBrakeActive = model.wheelBrakeActive,
        ).also { surfaceSnapshot = it }
    }

    fun stateSnapshot(): FixedWingFlightState {
        val referenceSpeed = model.speedMps / handling.simulationLengthScale
        val dynamicPressure = 0.5 * AIR_DENSITY_KG_PER_M3 *
            model.sampledDensityRatio * referenceSpeed * referenceSpeed
        val referenceForce = dynamicPressure * profile.referenceWingAreaM2
        val liftForce = model.liftAccelerationMps2 / handling.simulationLengthScale * profile.massKg
        val dragForce = model.dragAccelerationMps2 / handling.simulationLengthScale * profile.massKg
        return FixedWingFlightState(
            serverTick = if (lastServerTick == Long.MIN_VALUE) 0L else lastServerTick,
            speedMps = model.speedMps,
            angleOfAttackDegrees = model.angleOfAttackDegrees,
            throttle = model.throttle,
            afterburnerActive = model.afterburnerActive,
            stallActive = model.stallActive,
            bodyYawDegrees = model.yawDegrees,
            bodyPitchDegrees = model.pitchDegrees,
            bodyRollDegrees = model.rollDegrees,
            forwardVelocityMps = model.forwardVelocityMps,
            lateralVelocityMps = model.lateralVelocityMps,
            verticalVelocityMps = model.verticalVelocityMps,
            dynamicPressurePa = dynamicPressure,
            liftCoefficient = if (referenceForce > 1.0E-9) liftForce / referenceForce else 0.0,
            dragCoefficient = if (referenceForce > 1.0E-9) dragForce / referenceForce else 0.0,
            liftForceNewtons = liftForce,
            dragForceNewtons = dragForce,
            thrustForceNewtons =
                model.thrustAccelerationMps2 / handling.simulationLengthScale * profile.massKg,
            gravityAccelerationMps2 = handling.gravityMps2,
            controlEffectiveness = model.controlEffectiveness,
            stallSeverity = model.stallSeverity,
            yawRateDegPerSecond = model.yawRateDegreesPerSecond,
            pitchRateDegPerSecond = model.pitchRateDegreesPerSecond,
            rollRateDegPerSecond = model.rollRateDegreesPerSecond,
            profileId = profile.id,
            massKg = profile.massKg,
            maxStructuralSpeedMps = handling.maximumIndicatedSpeedMps,
            maxEngineSpeedMps = handling.maximumSpeedMps,
            stallSpeedMps = handling.liftReferenceSpeedMps,
            stallAoADegrees = handling.stallAngleDegrees,
            worldSpeedEnvelopeMps = handling.maximumSpeedMps,
            worldSpeedEnvelopeResponsePerSecond = 0.0,
            joystickSensitivity = handling.stickSensitivity,
            joystickDeadzone = handling.stickDeadzone,
            joystickSmoothingPerSecond = handling.stickResponsePerSecond,
            joystickReturnPerSecond = handling.automaticReturnPerSecond,
            quaternionX = model.quaternionX,
            quaternionY = model.quaternionY,
            quaternionZ = model.quaternionZ,
            quaternionW = model.quaternionW,
            virtualPitchTarget = model.virtualPitchTarget,
            virtualRollTarget = model.virtualRollTarget,
            airflowAuthority = model.airflowAuthority,
            sideslipDegrees = model.sideslipDegrees,
            signedLiftAccelerationMps2 = model.liftAccelerationMps2,
            dragAccelerationMps2 = model.dragAccelerationMps2,
            sideDragAccelerationMps2 = model.sideDragAccelerationMps2,
            overspeedDragAccelerationMps2 = model.overspeedDragAccelerationMps2,
            thrustAccelerationMps2 = model.thrustAccelerationMps2,
            preStepKineticEnergyPerKg = model.preStepKineticEnergyPerKg,
            postStepKineticEnergyPerKg = model.postStepKineticEnergyPerKg,
            stepThrustWorkPerKg = model.stepThrustWorkPerKg,
            stepGravityWorkPerKg = model.stepGravityWorkPerKg,
            stepDragWorkPerKg = model.stepDragWorkPerKg,
            stepSideWorkPerKg = model.stepSideWorkPerKg,
            stepLiftWorkPerKg = model.stepLiftWorkPerKg,
            stepGroundResistanceWorkPerKg = model.stepGroundResistanceWorkPerKg,
        )
    }

    /** Server-only direction intent; the current seat-0 pilot is the sole admission authority. */
    @JvmOverloads
    fun acceptPilotIntent(
        vehicle: VehicleEntity, controller: Player, controlEpoch: Long, sequence: Long,
        worldX: Double, worldY: Double, worldZ: Double, manualMask: Int, centerAim: Boolean,
        screenRollInput: Float? = null, firstPerson: Boolean = false,
        inversionRequested: Boolean = false,
    ): Boolean {
        val directionSquared = worldX * worldX + worldY * worldY + worldZ * worldZ
        if (!directionSquared.isFinite() || directionSquared !in 0.998001..1.002001 ||
            (manualMask and FixedWingPilotIntent.VALID_MASK) != manualMask) return false
        if (screenRollInput != null && (!screenRollInput.isFinite() || screenRollInput !in -1f..1f)) return false
        if (inversionRequested && screenRollInput == null) return false
        if (vehicle.level().isClientSide || controller.vehicle !== vehicle ||
            controller.level() !== vehicle.level() || vehicle.getNthEntity(0) !== controller ||
            !controller.isAlive || controller.isSpectator || vehicle.isWreck) return false
        if (synchronizePilot(vehicle) !== controller) return false
        val qx = model.quaternionX
        val qy = model.quaternionY
        val qz = model.quaternionZ
        val qw = model.quaternionW
        val accepted = pilotIntent.offer(controller.uuid, controlEpoch, sequence, vehicle.level().gameTime,
            if (centerAim) 2.0 * (qx * qz + qy * qw) else worldX,
            if (centerAim) 2.0 * (qy * qz - qx * qw) else worldY,
            if (centerAim) 1.0 - 2.0 * (qx * qx + qy * qy) else worldZ,
            manualMask, if (centerAim) null else screenRollInput, firstPerson,
            !centerAim && inversionRequested)
        if (accepted && centerAim) mouseAim.resetGuidance()
        return accepted
    }

    /** Returns a lease/accepted target for the mounted pilot; clients never call the model. */
    fun pilotIntentSnapshot(vehicle: VehicleEntity, controller: Player): FixedWingPilotIntentSnapshot? {
        if (vehicle.level().isClientSide || controller.vehicle !== vehicle ||
            controller.level() !== vehicle.level() || vehicle.getNthEntity(0) !== controller ||
            !controller.isAlive || controller.isSpectator || vehicle.isWreck) return null
        if (synchronizePilot(vehicle) !== controller) return null
        return pilotIntent.snapshot(vehicle.level().gameTime)
    }

    private fun synchronizePilot(vehicle: VehicleEntity): Player? {
        if (!initialized) resetFromPhysicalPose(vehicle)
        val pilot = (vehicle.getNthEntity(0) as? Player)?.takeIf {
            it.vehicle === vehicle && it.isAlive && !it.isSpectator && !vehicle.isWreck
        }
        if (pilot?.uuid != controllerUuid) {
            model.resetControls(preserveThrottle = true)
            mouseAim.reset()
            afterburnerControl.reset()
            controllerUuid = pilot?.uuid
            val qx = model.quaternionX
            val qy = model.quaternionY
            val qz = model.quaternionZ
            val qw = model.quaternionW
            pilotIntent.bind(controllerUuid, 2.0 * (qx * qz + qy * qw),
                2.0 * (qy * qz - qx * qw), 1.0 - 2.0 * (qx * qx + qy * qy))
        }
        return pilot
    }

    private fun engineAvailability(vehicle: VehicleEntity): Double {
        if (vehicle.mainEngineDamaged || vehicle.subEngineDamaged) return 0.0
        val maximum = vehicle.getEngineMaxHealth().toDouble()
        val main = vehicle.mainEngineHealth.toDouble()
        val sub = vehicle.subEngineHealth.toDouble()
        if (!maximum.isFinite() || maximum <= 0.0 || !main.isFinite() || !sub.isFinite()) {
            return 0.0
        }
        return (min(main, sub) / maximum).coerceIn(0.0, 1.0)
    }

    private fun resetFromPhysicalPose(vehicle: VehicleEntity) {
        sonicCrossing.reset()
        model.reset(vehicle.yRot.toDouble(), vehicle.xRot.toDouble(), vehicle.roll.toDouble())
        pilotIntent.clear()
        mouseAim.reset()
        afterburnerControl.reset()
        controllerUuid = null
        surfaceSnapshot = null
        initialized = true
        lastServerTick = Long.MIN_VALUE
    }

    internal fun groundGearSettleWeight(speedMps: Double): Double {
        val reference = handling.takeoffHandling?.referenceSpeedMps ?: handling.liftReferenceSpeedMps
        return FixedWingGroundAttitude.settleWeight(speedMps, reference)
    }

    internal fun acceptGroundPitch(pitchDegrees: Double) = model.acceptGroundPitch(pitchDegrees)

    /** Tail clearance is fixed airframe geometry; measure it once per terrain definition. */
    private fun groundPitchLimit(vehicle: VehicleEntity): Double {
        val terrain = vehicle.computed().aircraftTerrainContact
        if (terrain !== groundPitchSource) {
            groundPitchSource = terrain
            groundPitchLimit = terrain?.takeIf { it.valid() }?.groundPitchLimitDegrees()
                ?: FixedWingFlightModel.MAX_GROUND_PITCH_DEGREES
        }
        return groundPitchLimit
    }

    private fun attitudeDiscontinuous(input: VehicleFlightInputContext): Boolean =
        VehicleFlightAttitude.separationDegrees(
            input.bodyYawDegrees.toFloat(), input.bodyPitchDegrees.toFloat(), input.bodyRollDegrees.toFloat(),
            model.yawDegrees.toFloat(), model.pitchDegrees.toFloat(), model.rollDegrees.toFloat(),
        ) > 45.0

    companion object {
        private const val TICKS_PER_SECOND = 20.0
        private const val AIR_DENSITY_KG_PER_M3 = 1.225
    }
}
