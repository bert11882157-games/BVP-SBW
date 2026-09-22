package com.atsuishio.superbwarfare.api.vehicle.flight

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/** Immutable synchronized flight instruments. Motion is the strategy's final server motion. */
data class VehicleFlightInstrumentSnapshot @JvmOverloads constructor(
    val sequence: Int,
    val serverTick: Long,
    val rotorLift: Double,
    val collective: Double,
    val thrust: Double,
    val throttle: Double,
    val motion: Vec3,
    val bodyYaw: Float,
    val bodyPitch: Float,
    val bodyRoll: Float,
    val motionIncludesGravity: Boolean,
    val controlSurfaces: FixedWingControlSurfaceSnapshot? = null,
    val fixedWingSignedLiftG: Double? = null,
) {
    init {
        require(fixedWingSignedLiftG == null || fixedWingSignedLiftG.isFinite()) { "lift load must be finite" }
    }

    fun isNewerThan(other: VehicleFlightInstrumentSnapshot): Boolean =
        sequence - other.sequence > 0 && serverTick >= other.serverTick

    fun encode(): String = buildString(160) {
        require(fixedWingSignedLiftG == null || controlSurfaces != null) { "Lift load requires fixed-wing controls" }
        append(sequence).append(';')
        append(serverTick).append(';')
        append(rotorLift).append(';')
        append(collective).append(';')
        append(thrust).append(';')
        append(throttle).append(';')
        append(motion.x).append(';')
        append(motion.y).append(';')
        append(motion.z).append(';')
        append(bodyYaw).append(';')
        append(bodyPitch).append(';')
        append(bodyRoll).append(';')
        append(if (motionIncludesGravity) 1 else 0)
        controlSurfaces?.let { surfaces ->
            require(surfaces.serverTick == serverTick) { "Flight controls must share the body tick" }
            val schema = when {
                surfaces.wheelBrakeActive -> WHEEL_BRAKE_SCHEMA
                fixedWingSignedLiftG != null -> FIXED_WING_INSTRUMENT_SCHEMA
                else -> CONTROL_SURFACE_SCHEMA
            }
            append(';').append(schema)
            append(';').append(surfaces.elevator)
            append(';').append(surfaces.aileron)
            append(';').append(surfaces.rudder)
            append(';').append(surfaces.airbrake)
            append(';').append(surfaces.throttle)
            append(';').append(if (surfaces.afterburnerActive) 1 else 0)
            if (schema == WHEEL_BRAKE_SCHEMA) {
                // Empty load preserves unavailable instruments rather than inventing zero lift.
                append(';').append(fixedWingSignedLiftG?.toString().orEmpty())
                append(';').append(if (surfaces.wheelBrakeActive) 1 else 0)
            } else {
                fixedWingSignedLiftG?.let { append(';').append(it) }
            }
        }
    }

    companion object {
        private const val CONTROL_SURFACE_SCHEMA = 1
        private const val FIXED_WING_INSTRUMENT_SCHEMA = 2
        private const val WHEEL_BRAKE_SCHEMA = 3
        private const val MAX_PAYLOAD_CHARS = 512

        @JvmField
        val EMPTY = VehicleFlightInstrumentSnapshot(
            0, 0L, 0.0, 0.0, 0.0, 0.0, Vec3.ZERO, 0F, 0F, 0F, true
        )

        @JvmStatic
        @JvmOverloads
        fun fromResult(
            sequence: Int,
            serverTick: Long,
            result: VehicleFlightTickResult,
            controlSurfaces: FixedWingControlSurfaceSnapshot? = null,
        ): VehicleFlightInstrumentSnapshot {
            val acceptedControls = controlSurfaces?.takeIf { it.serverTick == serverTick }
            return VehicleFlightInstrumentSnapshot(
                sequence,
                serverTick,
                result.rotorLift,
                result.collective,
                result.thrust,
                result.throttle,
                result.motion,
                result.bodyYaw,
                result.bodyPitch,
                result.bodyRoll,
                result.motionIncludesGravity,
                acceptedControls,
                result.fixedWingSignedLiftG?.takeIf { acceptedControls != null },
            )
        }

        @JvmStatic
        fun decode(payload: String?): VehicleFlightInstrumentSnapshot? {
            if (payload.isNullOrBlank() || payload.length > MAX_PAYLOAD_CHARS) return null
            // This payload is changed-only synchronized at the flight tick cadence and is read
            // from both gameplay and presentation boundaries.  Do not split it into a temporary
            // list of strings on every packet: that allocation burst was large enough to produce
            // periodic integrated-server/client stalls with several helicopters tracking.
            var start = 0
            val sequenceEnd = fieldEnd(payload, start, true)
            if (sequenceEnd < 0) return null
            val sequenceLong = parseLong(payload, start, sequenceEnd) ?: return null
            if (sequenceLong < Int.MIN_VALUE || sequenceLong > Int.MAX_VALUE) return null
            start = sequenceEnd + 1

            val serverTickEnd = fieldEnd(payload, start, true)
            if (serverTickEnd < 0) return null
            val serverTick = parseLong(payload, start, serverTickEnd) ?: return null
            if (serverTick < 0L) return null
            start = serverTickEnd + 1

            val rotorLiftEnd = fieldEnd(payload, start, true)
            if (rotorLiftEnd < 0) return null
            val rotorLift = parseDouble(payload, start, rotorLiftEnd) ?: return null
            start = rotorLiftEnd + 1

            val collectiveEnd = fieldEnd(payload, start, true)
            if (collectiveEnd < 0) return null
            val collective = parseDouble(payload, start, collectiveEnd) ?: return null
            start = collectiveEnd + 1

            val thrustEnd = fieldEnd(payload, start, true)
            if (thrustEnd < 0) return null
            val thrust = parseDouble(payload, start, thrustEnd) ?: return null
            start = thrustEnd + 1

            val throttleEnd = fieldEnd(payload, start, true)
            if (throttleEnd < 0) return null
            val throttle = parseDouble(payload, start, throttleEnd) ?: return null
            start = throttleEnd + 1

            val motionXEnd = fieldEnd(payload, start, true)
            if (motionXEnd < 0) return null
            val motionX = parseDouble(payload, start, motionXEnd) ?: return null
            start = motionXEnd + 1

            val motionYEnd = fieldEnd(payload, start, true)
            if (motionYEnd < 0) return null
            val motionY = parseDouble(payload, start, motionYEnd) ?: return null
            start = motionYEnd + 1

            val motionZEnd = fieldEnd(payload, start, true)
            if (motionZEnd < 0) return null
            val motionZ = parseDouble(payload, start, motionZEnd) ?: return null
            start = motionZEnd + 1

            val bodyYawEnd = fieldEnd(payload, start, true)
            if (bodyYawEnd < 0) return null
            val bodyYaw = parseDouble(payload, start, bodyYawEnd)?.toFloat() ?: return null
            start = bodyYawEnd + 1

            val bodyPitchEnd = fieldEnd(payload, start, true)
            if (bodyPitchEnd < 0) return null
            val bodyPitch = parseDouble(payload, start, bodyPitchEnd)?.toFloat() ?: return null
            start = bodyPitchEnd + 1

            val bodyRollEnd = fieldEnd(payload, start, true)
            if (bodyRollEnd < 0) return null
            val bodyRoll = parseDouble(payload, start, bodyRollEnd)?.toFloat() ?: return null
            start = bodyRollEnd + 1

            // Legacy helicopter payloads end at gravity. Only fixed-wing payloads append
            // a versioned surface tuple; its source tick is the enclosing body tick.
            val extensionStart = payload.indexOf(';', start)
            val gravityEnd = if (extensionStart < 0) payload.length else extensionStart
            val motionIncludesGravity = parseFlag(payload, start, gravityEnd) ?: return null
            var controlSurfaces: FixedWingControlSurfaceSnapshot? = null
            var fixedWingSignedLiftG: Double? = null
            if (extensionStart >= 0) {
                start = extensionStart + 1
                val schemaEnd = fieldEnd(payload, start, true)
                if (schemaEnd < 0) return null
                val schema = parseLong(payload, start, schemaEnd) ?: return null
                if (schema != CONTROL_SURFACE_SCHEMA.toLong() &&
                    schema != FIXED_WING_INSTRUMENT_SCHEMA.toLong() &&
                    schema != WHEEL_BRAKE_SCHEMA.toLong()) return null
                start = schemaEnd + 1
                val elevatorEnd = fieldEnd(payload, start, true)
                if (elevatorEnd < 0) return null
                val elevator = parseControl(payload, start, elevatorEnd, -1.0) ?: return null
                start = elevatorEnd + 1
                val aileronEnd = fieldEnd(payload, start, true)
                if (aileronEnd < 0) return null
                val aileron = parseControl(payload, start, aileronEnd, -1.0) ?: return null
                start = aileronEnd + 1
                val rudderEnd = fieldEnd(payload, start, true)
                if (rudderEnd < 0) return null
                val rudder = parseControl(payload, start, rudderEnd, -1.0) ?: return null
                start = rudderEnd + 1
                val airbrakeEnd = fieldEnd(payload, start, true)
                if (airbrakeEnd < 0) return null
                val airbrake = parseControl(payload, start, airbrakeEnd, 0.0) ?: return null
                start = airbrakeEnd + 1
                val throttleControlEnd = fieldEnd(payload, start, true)
                if (throttleControlEnd < 0) return null
                val throttleControl = parseControl(payload, start, throttleControlEnd, 0.0) ?: return null
                start = throttleControlEnd + 1
                val brakingSchema = schema == WHEEL_BRAKE_SCHEMA.toLong()
                val extended = schema >= FIXED_WING_INSTRUMENT_SCHEMA
                val afterburnerEnd = fieldEnd(payload, start, extended)
                if (afterburnerEnd < 0) return null
                val afterburner = parseFlag(payload, start, afterburnerEnd) ?: return null
                var wheelBrakeActive = false
                if (extended) {
                    start = afterburnerEnd + 1
                    val loadEnd = fieldEnd(payload, start, brakingSchema)
                    if (loadEnd < 0) return null
                    if (loadEnd != start || !brakingSchema) {
                        fixedWingSignedLiftG = parseDouble(payload, start, loadEnd) ?: return null
                    }
                    if (brakingSchema) {
                        start = loadEnd + 1
                        val brakeEnd = fieldEnd(payload, start, false)
                        if (brakeEnd != payload.length) return null
                        wheelBrakeActive = parseFlag(payload, start, brakeEnd) ?: return null
                    } else if (loadEnd != payload.length) return null
                } else if (afterburnerEnd != payload.length) return null
                controlSurfaces = FixedWingControlSurfaceSnapshot(
                    serverTick, elevator, aileron, rudder, airbrake, throttleControl, afterburner, wheelBrakeActive,
                )
            }

            val snapshot = VehicleFlightInstrumentSnapshot(
                sequenceLong.toInt(),
                serverTick,
                rotorLift,
                collective,
                thrust,
                throttle,
                Vec3(motionX, motionY, motionZ),
                bodyYaw,
                bodyPitch,
                bodyRoll,
                motionIncludesGravity,
                controlSurfaces,
                fixedWingSignedLiftG,
            )
            return snapshot.takeIf {
                it.rotorLift.isFinite() && it.collective.isFinite() &&
                        it.thrust.isFinite() && it.throttle.isFinite() &&
                        it.motion.x.isFinite() && it.motion.y.isFinite() && it.motion.z.isFinite() &&
                        it.bodyYaw.isFinite() && it.bodyPitch.isFinite() && it.bodyRoll.isFinite()
            }
        }

        private fun parseFlag(payload: String, start: Int, end: Int): Boolean? {
            if (end != start + 1) return null
            return when (payload[start]) {
                '1' -> true
                '0' -> false
                else -> null
            }
        }

        private fun parseControl(payload: String, start: Int, end: Int, minimum: Double): Float? {
            val value = parseDouble(payload, start, end) ?: return null
            return value.takeIf { it in minimum..1.0 }?.toFloat()
        }

        /** End index for one delimited field; the final field must have no delimiter. */
        private fun fieldEnd(payload: String, start: Int, delimited: Boolean): Int {
            if (start < 0 || start > payload.length) return -1
            val end = payload.indexOf(';', start)
            return if (delimited) end else if (end < 0) payload.length else -1
        }

        private fun parseLong(payload: String, start: Int, end: Int): Long? {
            if (start >= end) return null
            var index = start
            var negative = false
            when (payload[index]) {
                '-' -> { negative = true; index++ }
                '+' -> index++
            }
            if (index >= end) return null
            var value = 0L
            val limit = if (negative) Long.MIN_VALUE else -Long.MAX_VALUE
            val multMin = limit / 10L
            while (index < end) {
                val digit = payload[index] - '0'
                if (digit !in 0..9 || value < multMin) return null
                value *= 10L
                if (value < limit + digit) return null
                value -= digit
                index++
            }
            return if (negative) value else -value
        }

        private fun parseDouble(payload: String, start: Int, end: Int): Double? {
            if (start >= end) return null
            var index = start
            var sign = 1.0
            when (payload[index]) {
                '-' -> { sign = -1.0; index++ }
                '+' -> index++
            }
            if (index >= end) return null
            var value = 0.0
            var digits = 0
            while (index < end && payload[index] in '0'..'9') {
                value = value * 10.0 + (payload[index] - '0')
                digits++
                index++
            }
            if (index < end && payload[index] == '.') {
                index++
                var scale = 0.1
                while (index < end && payload[index] in '0'..'9') {
                    value += (payload[index] - '0') * scale
                    scale *= 0.1
                    digits++
                    index++
                }
            }
            if (digits == 0) return null
            if (index < end && (payload[index] == 'e' || payload[index] == 'E')) {
                index++
                var exponentNegative = false
                if (index < end && (payload[index] == '-' || payload[index] == '+')) {
                    exponentNegative = payload[index] == '-'
                    index++
                }
                if (index >= end) return null
                var exponent = 0
                while (index < end && payload[index] in '0'..'9') {
                    exponent = (exponent * 10) + (payload[index] - '0')
                    // Keep the finite subnormal range accepted by Double.parseDouble; positive
                    // overflow is rejected by the final isFinite guard below.
                    if (exponent > 1024) return null
                    index++
                }
                if (exponentNegative) exponent = -exponent
                value *= Math.pow(10.0, exponent.toDouble())
            }
            if (index != end) return null
            val result = sign * value
            return result.takeIf { it.isFinite() }
        }

        @JvmStatic
        fun interpolate(
            previous: VehicleFlightInstrumentSnapshot,
            current: VehicleFlightInstrumentSnapshot,
            alpha: Float,
        ): VehicleFlightInstrumentSnapshot {
            if (!alpha.isFinite()) return current.copy(controlSurfaces = null, fixedWingSignedLiftG = null)
            val t = Mth.clamp(alpha, 0F, 1F).toDouble()
            // At the upper endpoint every value and provenance field is already exactly the
            // current immutable sample. Reusing it avoids a new Snapshot/Vec3 pair on every
            // settled render frame without changing interpolation or sequence semantics.
            if (t >= 1.0) return current
            val previousLiftG = previous.fixedWingSignedLiftG
            val currentLiftG = current.fixedWingSignedLiftG
            val liftG = if (previousLiftG != null && currentLiftG != null)
                ((1.0 - t) * previousLiftG + t * currentLiftG).takeIf(Double::isFinite)
            else null
            val attitude = if (previous.controlSurfaces != null && current.controlSurfaces != null)
                VehicleFlightAttitude.interpolate(previous.bodyYaw, previous.bodyPitch, previous.bodyRoll,
                    current.bodyYaw, current.bodyPitch, current.bodyRoll, t.toFloat())
            else null
            return VehicleFlightInstrumentSnapshot(
                current.sequence,
                current.serverTick,
                Mth.lerp(t, previous.rotorLift, current.rotorLift),
                Mth.lerp(t, previous.collective, current.collective),
                Mth.lerp(t, previous.thrust, current.thrust),
                Mth.lerp(t, previous.throttle, current.throttle),
                Vec3(
                    Mth.lerp(t, previous.motion.x, current.motion.x),
                    Mth.lerp(t, previous.motion.y, current.motion.y),
                    Mth.lerp(t, previous.motion.z, current.motion.z),
                ),
                attitude?.yaw ?: Mth.rotLerp(t.toFloat(), previous.bodyYaw, current.bodyYaw),
                attitude?.pitch ?: Mth.rotLerp(t.toFloat(), previous.bodyPitch, current.bodyPitch),
                attitude?.roll ?: Mth.rotLerp(t.toFloat(), previous.bodyRoll, current.bodyRoll),
                current.motionIncludesGravity,
                interpolateControlSurfaces(previous.controlSurfaces, current.controlSurfaces, t),
                liftG,
            )
        }

        private fun interpolateControlSurfaces(
            previous: FixedWingControlSurfaceSnapshot?,
            current: FixedWingControlSurfaceSnapshot?,
            alpha: Double,
        ): FixedWingControlSurfaceSnapshot? {
            if (current == null || previous == null) return current
            return FixedWingControlSurfaceSnapshot(
                current.serverTick,
                Mth.lerp(alpha, previous.elevator.toDouble(), current.elevator.toDouble()).toFloat(),
                Mth.lerp(alpha, previous.aileron.toDouble(), current.aileron.toDouble()).toFloat(),
                Mth.lerp(alpha, previous.rudder.toDouble(), current.rudder.toDouble()).toFloat(),
                Mth.lerp(alpha, previous.airbrake.toDouble(), current.airbrake.toDouble()).toFloat(),
                Mth.lerp(alpha, previous.throttle.toDouble(), current.throttle.toDouble()).toFloat(),
                // Discrete switches follow the accepted state. Rebased blends may never reach
                // alpha=1 under continuous updates, so retaining the old flag can latch it forever.
                current.afterburnerActive,
                current.wheelBrakeActive,
            )
        }
    }
}
