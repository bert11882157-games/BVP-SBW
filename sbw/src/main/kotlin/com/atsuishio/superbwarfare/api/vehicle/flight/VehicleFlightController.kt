package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/** Native lifecycle, authority, synchronization, and client interpolation for an opt-in strategy. */
class VehicleFlightController(private val vehicle: VehicleEntity) {
    private var activeStrategy: VehicleFlightStrategy? = null
    private var sequence = 0
    private var payload = ""
    private var previous = VehicleFlightInstrumentSnapshot.EMPTY
    private var current = VehicleFlightInstrumentSnapshot.EMPTY
    private var clientUpdateTick = Int.MIN_VALUE
    private var clientPositionInterpolationTicks = 1
    private var deliveredClientSequence = 0
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
        transition(strategy)
        val selected = activeStrategy ?: return false
        strategyOwnsAttitudeThisTick = true

        if (vehicle.level().isClientSide) {
            consumeClient(vehicle.readVehicleFlightInstrumentPayload())
            val applied = interpolateClientSnapshot(0F, clientPositionInterpolationTicks)
            motionIncludesGravityThisTick = applied.motionIncludesGravity
            if (applied.sequence != 0) {
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
        if (!hasPublishableChange(result)) {
            return true
        }
        sequence += 1
        previous = current
        current = VehicleFlightInstrumentSnapshot.fromResult(sequence, vehicle.level().gameTime, result)
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

    private fun interpolateClientSnapshot(
        partialTicks: Float,
        durationTicks: Int,
    ): VehicleFlightInstrumentSnapshot {
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
    private fun hasPublishableChange(result: VehicleFlightTickResult): Boolean {
        if (current.sequence == 0) return true
        return quantize(current.rotorLift, 1_000) != quantize(result.rotorLift, 1_000) ||
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

    private fun quantize(value: Double, scale: Int): Long = Math.round(value * scale)

    /** True only after the client has decoded a usable authoritative flight baseline. */
    fun hasClientInstrumentSnapshot(): Boolean {
        if (!vehicle.level().isClientSide) return false
        consumeClient(vehicle.readVehicleFlightInstrumentPayload())
        return current.sequence != 0
    }

    fun consumeClient(incomingPayload: String?) {
        if (!vehicle.level().isClientSide) return
        val incomingText = incomingPayload.orEmpty()
        if (incomingText == payload) return
        payload = incomingText
        if (incomingText.isBlank()) {
            previous = VehicleFlightInstrumentSnapshot.EMPTY
            current = VehicleFlightInstrumentSnapshot.EMPTY
            clientPositionInterpolationTicks = 1
            deliveredClientSequence = 0
            interpolatedTick = Int.MIN_VALUE
            return
        }

        val incoming = VehicleFlightInstrumentSnapshot.decode(incomingText) ?: return
        if (current.sequence == 0 && previous.sequence == 0) {
            previous = incoming
            current = incoming
        } else {
            if (!incoming.isNewerThan(current)) return
            previous = current
            current = incoming
        }
        clientUpdateTick = vehicle.tickCount
        interpolatedTick = Int.MIN_VALUE
    }

    fun close() {
        transition(null)
    }

    private fun transition(next: VehicleFlightStrategy?) {
        if (activeStrategy === next) return
        activeStrategy?.onDeactivated(vehicle)
        activeStrategy = next
        deliveredClientSequence = 0
        next?.onActivated(vehicle)
        if (next == null && !vehicle.level().isClientSide && payload.isNotEmpty()) {
            payload = ""
            previous = VehicleFlightInstrumentSnapshot.EMPTY
            current = VehicleFlightInstrumentSnapshot.EMPTY
            interpolatedTick = Int.MIN_VALUE
            vehicle.publishVehicleFlightInstrumentPayload("")
        }
    }

    private fun deliverClientSnapshot(strategy: VehicleFlightStrategy) {
        if (current.sequence == 0 || current.sequence == deliveredClientSequence) return
        strategy.onClientInstrumentSnapshot(vehicle, current)
        deliveredClientSequence = current.sequence
    }
}
