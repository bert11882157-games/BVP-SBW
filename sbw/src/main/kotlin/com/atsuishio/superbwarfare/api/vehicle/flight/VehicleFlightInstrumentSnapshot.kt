package com.atsuishio.superbwarfare.api.vehicle.flight

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/** Immutable synchronized flight instruments. Motion is the strategy's final server motion. */
data class VehicleFlightInstrumentSnapshot(
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
) {
    fun isNewerThan(other: VehicleFlightInstrumentSnapshot): Boolean =
        sequence != other.sequence && Integer.compareUnsigned(sequence, other.sequence) > 0

    fun encode(): String = buildString(160) {
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
    }

    companion object {
        @JvmField
        val EMPTY = VehicleFlightInstrumentSnapshot(
            0, 0L, 0.0, 0.0, 0.0, 0.0, Vec3.ZERO, 0F, 0F, 0F, true
        )

        @JvmStatic
        fun fromResult(sequence: Int, serverTick: Long, result: VehicleFlightTickResult) =
            VehicleFlightInstrumentSnapshot(
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
            )

        @JvmStatic
        fun decode(payload: String?): VehicleFlightInstrumentSnapshot? {
            if (payload.isNullOrBlank()) return null
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

            val gravityEnd = fieldEnd(payload, start, false)
            if (gravityEnd != payload.length || gravityEnd <= start) return null
            val motionIncludesGravity = when {
                payload.regionMatches(start, "1", 0, 1) && gravityEnd == start + 1 -> true
                payload.regionMatches(start, "0", 0, 1) && gravityEnd == start + 1 -> false
                else -> return null
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
            )
            return snapshot.takeIf {
                it.rotorLift.isFinite() && it.collective.isFinite() &&
                        it.thrust.isFinite() && it.throttle.isFinite() &&
                        it.motion.x.isFinite() && it.motion.y.isFinite() && it.motion.z.isFinite() &&
                        it.bodyYaw.isFinite() && it.bodyPitch.isFinite() && it.bodyRoll.isFinite()
            }
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
            val t = Mth.clamp(alpha, 0F, 1F).toDouble()
            // At the upper endpoint every value and provenance field is already exactly the
            // current immutable sample. Reusing it avoids a new Snapshot/Vec3 pair on every
            // settled render frame without changing interpolation or sequence semantics.
            if (t >= 1.0) return current
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
                Mth.rotLerp(t.toFloat(), previous.bodyYaw, current.bodyYaw),
                Mth.rotLerp(t.toFloat(), previous.bodyPitch, current.bodyPitch),
                Mth.rotLerp(t.toFloat(), previous.bodyRoll, current.bodyRoll),
                current.motionIncludesGravity,
            )
        }
    }
}
