package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/** Native lifecycle, authority, synchronization, and client interpolation for an opt-in strategy. */
class VehicleFlightController(private val vehicle: VehicleEntity) {
    private val wreckStrategy = AircraftWreckFlightStrategy()
    private var activeStrategy: VehicleFlightStrategy? = null
    private var sequence = 0
    private var payload = ""
    private var previous = VehicleFlightInstrumentSnapshot.EMPTY
    private var current = VehicleFlightInstrumentSnapshot.EMPTY
    private var hasSnapshot = false
    private var acceptedClientSequence: Int? = null
    private var acceptedClientServerTick = -1L
    private var clientUpdateTick = Int.MIN_VALUE
    private var clientPositionInterpolationTicks = 1
    private var deliveredClientSequence: Int? = null
    private var interpolatedTick = Int.MIN_VALUE
    private var interpolatedPartialBits = 0
    private var interpolatedPreviousSequence = 0
    private var interpolatedCurrentSequence = 0
    private var interpolatedDurationTicks = 1
    private var interpolated = VehicleFlightInstrumentSnapshot.EMPTY

    var motionIncludesGravityThisTick: Boolean = false
        private set

    /** True only for a tick in which a selected strategy owns the vehicle's final body attitude. */
    var strategyOwnsAttitudeThisTick: Boolean = false
        private set

    fun beginTick() {
        motionIncludesGravityThisTick = false
        strategyOwnsAttitudeThisTick = false
    }

    /** Returns true when the strategy owns this tick and legacy EngineInfo.work must be skipped. */
    fun apply(strategy: VehicleFlightStrategy?): Boolean {
        val aircraftWreck = vehicle.isWreck && (vehicle.vehicleType ==
            com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE || vehicle.vehicleType ==
            com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.HELICOPTER)
        transition(if (aircraftWreck || AircraftWreckBreakup.mask(vehicle) != 0) wreckStrategy else
            com.atsuishio.superbwarfare.diagnostics.AamTestTargets.flightStrategy(vehicle) ?: strategy)
        val selected = activeStrategy ?: return false
        strategyOwnsAttitudeThisTick = true

        if (vehicle.level().isClientSide) {
            consumeClient(vehicle.readVehicleFlightInstrumentPayload())
            val applied = interpolateClientSnapshot(0F, clientPositionInterpolationTicks)
            motionIncludesGravityThisTick = applied.motionIncludesGravity
            if (hasSnapshot) {
                vehicle.applyVehicleFlightMotion(applied.motion, true)
                vehicle.applyVehicleFlightAttitude(applied.bodyYaw, applied.bodyPitch, applied.bodyRoll)
                deliverClientSnapshot(selected)
            }
            return true
        }

        selected.prepareServer(vehicle)
        val result = selected.tickServer(vehicle, vehicle.createVehicleFlightInputContext())
        motionIncludesGravityThisTick = result.motionIncludesGravity
        vehicle.applyVehicleFlightMotion(result.motion, false)
        vehicle.applyVehicleFlightAttitude(result.bodyYaw, result.bodyPitch, result.bodyRoll)
        val serverTick = vehicle.level().gameTime
        val controlSurfaces = (selected as? FixedWingFlightStrategy)?.controlSurfaceSnapshot()
            ?.takeIf { it.serverTick == serverTick }
        if (!hasPublishableChange(result, controlSurfaces)) {
            return true
        }
        sequence += 1
        previous = current
        current = VehicleFlightInstrumentSnapshot.fromResult(sequence, serverTick, result, controlSurfaces)
        hasSnapshot = true
        payload = current.encode()
        vehicle.publishVehicleFlightInstrumentPayload(payload)
        return true
    }

    fun snapshot(partialTicks: Float): VehicleFlightInstrumentSnapshot {
        if (!vehicle.level().isClientSide) return current
        consumeClient(vehicle.readVehicleFlightInstrumentPayload())
        // Gameplay/predictor diagnostics retain the current authoritative receipt clock. Only
        // the client-applied body presentation is stretched to the entity position interval.
        return interpolateClientSnapshot(partialTicks, 1)
    }

    /** Replace the pre-movement receipt with the accepted terrain pose in the same server tick. */
    fun acceptGroundContactPitch(pitch: Float) {
        if (vehicle.level().isClientSide || !hasSnapshot) return
        val strategy = activeStrategy ?: return
        (strategy as? FixedWingFlightStrategy)?.acceptGroundPitch(pitch.toDouble())
        sequence += 1
        val serverTick = vehicle.level().gameTime
        val surfaces = (strategy as? FixedWingFlightStrategy)?.controlSurfaceSnapshot()?.takeIf { it.serverTick == serverTick }
        current = current.copy(sequence = sequence, serverTick = serverTick, bodyPitch = pitch,
            motion = vehicle.deltaMovement, controlSurfaces = surfaces,
            fixedWingSignedLiftG = current.fixedWingSignedLiftG.takeIf { surfaces != null })
        payload = current.encode()
        vehicle.publishVehicleFlightInstrumentPayload(payload)
    }

    /**
     * Immutable client sample aligned with the entity's previous-to-current rendered body pose.
     * [apply] installs interpolation alpha zero at packet receipt; entity rendering presents that
     * applied interval one tick later, so the shared snapshot interpolation is shifted by one tick.
     */
    fun presentationSnapshot(partialTicks: Float): VehicleFlightInstrumentSnapshot {
        if (!vehicle.level().isClientSide) return current
        consumeClient(vehicle.readVehicleFlightInstrumentPayload())
        return interpolateClientSnapshot(partialTicks - 1F, clientPositionInterpolationTicks)
    }

    /** Read-only accepted surfaces on the same interpolation clock as the rendered body. */
    fun controlSurfacePresentationSnapshot(partialTicks: Float): FixedWingControlSurfaceSnapshot? {
        if (!partialTicks.isFinite() || vehicle.resolveVehicleFlightStrategy() !is FixedWingFlightStrategy) {
            return null
        }
        return presentationSnapshot(partialTicks).controlSurfaces
    }

    private fun interpolateClientSnapshot(
        partialTicks: Float,
        durationTicks: Int,
    ): VehicleFlightInstrumentSnapshot {
        if (!partialTicks.isFinite()) {
            return current.copy(controlSurfaces = null, fixedWingSignedLiftG = null)
        }
        if (previous.sequence == current.sequence) return current
        val duration = durationTicks.coerceAtLeast(1)
        val partialBits = partialTicks.toRawBits()
        if (interpolatedTick == vehicle.tickCount &&
            interpolatedPartialBits == partialBits &&
            interpolatedPreviousSequence == previous.sequence &&
            interpolatedCurrentSequence == current.sequence &&
            interpolatedDurationTicks == duration
        ) {
            return interpolated
        }
        val alpha = ((vehicle.tickCount - clientUpdateTick).toFloat() + partialTicks) /
            duration.toFloat()
        interpolated = VehicleFlightInstrumentSnapshot.interpolate(previous, current, alpha)
        interpolatedTick = vehicle.tickCount
        interpolatedPartialBits = partialBits
        interpolatedPreviousSequence = previous.sequence
        interpolatedCurrentSequence = current.sequence
        interpolatedDurationTicks = duration
        return interpolated
    }

    /**
     * Binds the client-applied flight body interval to the same vanilla move packet interval.
     * Packet order is harmless: updating the duration invalidates the per-frame interpolation
     * cache, while a later instrument payload retains this exact duration.
     */
    fun beginClientPositionInterpolation(interpolationTicks: Int) {
        if (!vehicle.level().isClientSide) return
        clientPositionInterpolationTicks = interpolationTicks.coerceAtLeast(1)
        interpolatedTick = Int.MIN_VALUE
    }

    /**
     * Preserve the pre-migration changed-only synchronization contract. Motion and instruments
     * use their former integer resolutions; attitude uses millidegrees so the strategy remains
     * authoritative without dirtying entity data for numerically insignificant changes.
     */
    private fun hasPublishableChange(
        result: VehicleFlightTickResult,
        controlSurfaces: FixedWingControlSurfaceSnapshot?,
    ): Boolean {
        if (!hasSnapshot) return true
        val nextFixedWingSignedLiftG = result.fixedWingSignedLiftG?.takeIf { controlSurfaces != null }
        return current.fixedWingSignedLiftG != nextFixedWingSignedLiftG ||
            !sameControlSurfaces(current.controlSurfaces, controlSurfaces) ||
            quantize(current.rotorLift, 1_000) != quantize(result.rotorLift, 1_000) ||
            quantize(current.collective, 1_000) != quantize(result.collective, 1_000) ||
            quantize(current.thrust, 1_000) != quantize(result.thrust, 1_000) ||
            quantize(current.throttle, 1_000) != quantize(result.throttle, 1_000) ||
            quantize(current.motion.x, 10_000) != quantize(result.motion.x, 10_000) ||
            quantize(current.motion.y, 10_000) != quantize(result.motion.y, 10_000) ||
            quantize(current.motion.z, 10_000) != quantize(result.motion.z, 10_000) ||
            quantize(current.bodyYaw.toDouble(), 1_000) != quantize(result.bodyYaw.toDouble(), 1_000) ||
            quantize(current.bodyPitch.toDouble(), 1_000) != quantize(result.bodyPitch.toDouble(), 1_000) ||
            quantize(current.bodyRoll.toDouble(), 1_000) != quantize(result.bodyRoll.toDouble(), 1_000) ||
            current.motionIncludesGravity != result.motionIncludesGravity
    }

    /** Timestamp advancement alone must not dirty a stationary changed-only payload. */
    private fun sameControlSurfaces(
        previous: FixedWingControlSurfaceSnapshot?,
        next: FixedWingControlSurfaceSnapshot?,
    ): Boolean {
        if (previous == null || next == null) return previous === next
        return previous.elevator == next.elevator && previous.aileron == next.aileron &&
            previous.rudder == next.rudder && previous.airbrake == next.airbrake &&
            previous.throttle == next.throttle && previous.afterburnerActive == next.afterburnerActive &&
            previous.wheelBrakeActive == next.wheelBrakeActive
    }

    private fun quantize(value: Double, scale: Int): Long = Math.round(value * scale)

    /** True only after the client has decoded a usable authoritative flight baseline. */
    fun hasClientInstrumentSnapshot(): Boolean {
        if (!vehicle.level().isClientSide) return false
        consumeClient(vehicle.readVehicleFlightInstrumentPayload())
        return hasSnapshot
    }

    fun consumeClient(incomingPayload: String?) {
        if (!vehicle.level().isClientSide) return
        val incomingText = incomingPayload.orEmpty()
        if (incomingText == payload) return
        payload = incomingText
        if (incomingText.isBlank()) {
            clearSnapshots()
            return
        }

        val incoming = VehicleFlightInstrumentSnapshot.decode(incomingText)
        if (incoming == null) {
            // Keep the accepted body and replay watermark; clear unavailable extended instruments.
            previous = previous.copy(controlSurfaces = null, fixedWingSignedLiftG = null)
            current = current.copy(controlSurfaces = null, fixedWingSignedLiftG = null)
            interpolatedTick = Int.MIN_VALUE
            return
        }
        // A clear hides the old tuple; it does not authorize delayed packets to seed it again.
        val acceptedSequence = acceptedClientSequence
        if (acceptedSequence != null && (incoming.sequence - acceptedSequence <= 0 ||
                incoming.serverTick < acceptedClientServerTick)
        ) return
        if (!hasSnapshot) {
            previous = incoming
            current = incoming
        } else {
            if (!incoming.isNewerThan(current)) return
            // A delayed batch may replace a target before its blend has finished. Continue from
            // the currently presented state, not the unreached target (which causes a visible jump).
            previous = interpolateClientSnapshot(0F, clientPositionInterpolationTicks)
            current = incoming
        }
        hasSnapshot = true
        acceptedClientSequence = incoming.sequence
        acceptedClientServerTick = incoming.serverTick
        clientUpdateTick = vehicle.tickCount
        interpolatedTick = Int.MIN_VALUE
    }

    fun close() {
        transition(null)
        clearSnapshots()
    }

    private fun transition(next: VehicleFlightStrategy?) {
        if (activeStrategy === next) return
        val replacingStrategy = activeStrategy != null
        activeStrategy?.onDeactivated(vehicle)
        activeStrategy = next
        deliveredClientSequence = null
        if (replacingStrategy) clearSnapshots()
        next?.onActivated(vehicle)
        if (next == null && !vehicle.level().isClientSide && payload.isNotEmpty()) {
            payload = ""
            clearSnapshots()
            vehicle.publishVehicleFlightInstrumentPayload("")
        }
    }

    private fun clearSnapshots() {
        previous = VehicleFlightInstrumentSnapshot.EMPTY
        current = VehicleFlightInstrumentSnapshot.EMPTY
        hasSnapshot = false
        clientUpdateTick = Int.MIN_VALUE
        clientPositionInterpolationTicks = 1
        deliveredClientSequence = null
        interpolatedTick = Int.MIN_VALUE
    }

    private fun deliverClientSnapshot(strategy: VehicleFlightStrategy) {
        if (!hasSnapshot || current.sequence == deliveredClientSequence) return
        strategy.onClientInstrumentSnapshot(vehicle, current)
        deliveredClientSequence = current.sequence
    }
}
