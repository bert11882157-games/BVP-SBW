package com.atsuishio.superbwarfare.api.vehicle.pose

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import kotlin.math.abs

/**
 * Immutable, server-authored chassis sample used when a pose provider owns attitude. Position,
 * base attitude, provider extension, ground bias, and collision-step compensation share one
 * sequence/server tick and therefore one client interpolation alpha. A selected flight strategy
 * suppresses this snapshot for that tick.
 *
 * Angles use the logical JOML vehicle frame: positive pitch rotates around local +X and positive
 * roll rotates around local +Z. [verticalOffset] is applied in the already yawed vehicle frame.
 */
data class VehiclePoseSnapshot(
    val sequence: Int,
    val serverTick: Long,
    val chassisYawDegrees: Float,
    val basePitchDegrees: Float,
    val baseRollDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val groundBias: Double,
    val collisionStepOffset: Double,
    val collisionStepVelocity: Double,
    val anchor: Vec3?,
) {
    val verticalOffset: Double
        get() = groundBias + collisionStepOffset

    fun isBaseIdentity(): Boolean =
        abs(basePitchDegrees) < ANGLE_EPSILON && abs(baseRollDegrees) < ANGLE_EPSILON

    fun isExtensionIdentity(): Boolean =
        abs(pitchDegrees) < ANGLE_EPSILON &&
                abs(rollDegrees) < ANGLE_EPSILON &&
                abs(verticalOffset) < OFFSET_EPSILON

    fun isIdentity(): Boolean =
        isBaseIdentity() && isExtensionIdentity()

    fun hasSamePose(other: VehiclePoseSnapshot): Boolean =
        abs(Mth.wrapDegrees(chassisYawDegrees - other.chassisYawDegrees)) < ANGLE_EPSILON &&
                abs(basePitchDegrees - other.basePitchDegrees) < ANGLE_EPSILON &&
                abs(baseRollDegrees - other.baseRollDegrees) < ANGLE_EPSILON &&
                abs(pitchDegrees - other.pitchDegrees) < ANGLE_EPSILON &&
                abs(rollDegrees - other.rollDegrees) < ANGLE_EPSILON &&
                abs(groundBias - other.groundBias) < OFFSET_EPSILON &&
                abs(collisionStepOffset - other.collisionStepOffset) < OFFSET_EPSILON &&
                abs(collisionStepVelocity - other.collisionStepVelocity) < OFFSET_EPSILON

    fun hasSameChassisSample(other: VehiclePoseSnapshot): Boolean =
        hasSamePose(other) && anchorsMatch(anchor, other.anchor)

    /** RFC-1982-style serial comparison; remains correct across the signed Int wrap boundary. */
    fun isNewerThan(other: VehiclePoseSnapshot): Boolean =
        sequence != other.sequence && sequence - other.sequence > 0

    fun withAuthority(sequence: Int, serverTick: Long): VehiclePoseSnapshot =
        copy(sequence = sequence, serverTick = serverTick)

    fun withAuthority(
        sequence: Int,
        serverTick: Long,
        anchor: Vec3,
        chassisYawDegrees: Float,
    ): VehiclePoseSnapshot {
        requireFiniteAnchor(anchor)
        require(chassisYawDegrees.isFinite()) { "chassisYawDegrees must be finite" }
        return copy(
            sequence = sequence,
            serverTick = serverTick,
            chassisYawDegrees = chassisYawDegrees,
            anchor = anchor,
        )
    }

    fun withChassisSample(anchor: Vec3, chassisYawDegrees: Float): VehiclePoseSnapshot {
        requireFiniteAnchor(anchor)
        require(chassisYawDegrees.isFinite()) { "chassisYawDegrees must be finite" }
        return copy(anchor = anchor, chassisYawDegrees = chassisYawDegrees)
    }

    fun withBasePose(basePitchDegrees: Float, baseRollDegrees: Float): VehiclePoseSnapshot {
        require(basePitchDegrees.isFinite()) { "basePitchDegrees must be finite" }
        require(baseRollDegrees.isFinite()) { "baseRollDegrees must be finite" }
        return copy(basePitchDegrees = basePitchDegrees, baseRollDegrees = baseRollDegrees)
    }

    fun resolvedChassisWorldY(): Double? = anchor?.let { it.y + verticalOffset }

    /** Applies native pitch then native roll while [transform] is positioned at the body pivot. */
    fun applyBaseAttitude(transform: Matrix4d): Matrix4d {
        if (isBaseIdentity()) return transform

        transform.rotateX(Math.toRadians(basePitchDegrees.toDouble()))
        transform.rotateZ(Math.toRadians(baseRollDegrees.toDouble()))
        return transform
    }

    /** Applies the provider extension in the exact order used by BVP's legacy suspension. */
    fun applyExtension(transform: Matrix4d, pivotY: Double): Matrix4d {
        if (isExtensionIdentity()) return transform

        transform.translate(0.0, verticalOffset, 0.0)
        transform.translate(0.0, pivotY, 0.0)
        transform.rotateX(Math.toRadians(pitchDegrees.toDouble()))
        transform.rotateZ(Math.toRadians(rollDegrees.toDouble()))
        transform.translate(0.0, -pivotY, 0.0)
        return transform
    }

    /** Compact schema-2 entity-data representation. */
    fun encode(): String {
        val chassisAnchor = requireNotNull(anchor) { "Authoritative vehicle pose is missing its chassis anchor" }
        return buildString(224) {
            append(CURRENT_SCHEMA).append(';')
            append(sequence).append(';')
            append(serverTick).append(';')
            append(chassisYawDegrees).append(';')
            append(basePitchDegrees).append(';')
            append(baseRollDegrees).append(';')
            append(pitchDegrees).append(';')
            append(rollDegrees).append(';')
            append(groundBias).append(';')
            append(collisionStepOffset).append(';')
            append(collisionStepVelocity).append(';')
            append(chassisAnchor.x).append(';')
            append(chassisAnchor.y).append(';')
            append(chassisAnchor.z)
        }
    }

    companion object {
        private const val CURRENT_SCHEMA = 2
        private const val ANGLE_EPSILON = 1.0E-5F
        // Final pre-migration hull transforms ignored pitch, roll, and vertical bias below 1e-5.
        private const val OFFSET_EPSILON = 1.0E-5
        private const val POSITION_EPSILON = 1.0E-6

        @JvmField
        val IDENTITY = VehiclePoseSnapshot(0, 0L, 0F, 0F, 0F, 0F, 0F, 0.0, 0.0, 0.0, null)

        @JvmStatic
        fun createComponents(
            pitchDegrees: Float,
            rollDegrees: Float,
            groundBias: Double,
            collisionStepOffset: Double,
            collisionStepVelocity: Double,
        ): VehiclePoseSnapshot {
            require(pitchDegrees.isFinite()) { "pitchDegrees must be finite" }
            require(rollDegrees.isFinite()) { "rollDegrees must be finite" }
            require(groundBias.isFinite()) { "groundBias must be finite" }
            require(collisionStepOffset.isFinite()) { "collisionStepOffset must be finite" }
            require(collisionStepVelocity.isFinite()) { "collisionStepVelocity must be finite" }
            return VehiclePoseSnapshot(
                0,
                0L,
                0F,
                0F,
                0F,
                pitchDegrees,
                rollDegrees,
                groundBias,
                collisionStepOffset,
                collisionStepVelocity,
                null,
            )
        }

        @JvmStatic
        fun decode(payload: String?): VehiclePoseSnapshot? {
            if (payload.isNullOrEmpty()) return null
            val fields = payload.split(';', limit = 15)
            if (fields.size != 14 || fields[0].toIntOrNull() != CURRENT_SCHEMA) return null
            val snapshot = VehiclePoseSnapshot(
                fields[1].toIntOrNull() ?: return null,
                fields[2].toLongOrNull() ?: return null,
                fields[3].toFloatOrNull() ?: return null,
                fields[4].toFloatOrNull() ?: return null,
                fields[5].toFloatOrNull() ?: return null,
                fields[6].toFloatOrNull() ?: return null,
                fields[7].toFloatOrNull() ?: return null,
                fields[8].toDoubleOrNull() ?: return null,
                fields[9].toDoubleOrNull() ?: return null,
                fields[10].toDoubleOrNull() ?: return null,
                Vec3(
                    fields[11].toDoubleOrNull() ?: return null,
                    fields[12].toDoubleOrNull() ?: return null,
                    fields[13].toDoubleOrNull() ?: return null,
                ),
            )
            return snapshot.takeIf {
                it.chassisYawDegrees.isFinite() &&
                        it.basePitchDegrees.isFinite() && it.baseRollDegrees.isFinite() &&
                        it.pitchDegrees.isFinite() && it.rollDegrees.isFinite() &&
                        it.groundBias.isFinite() && it.collisionStepOffset.isFinite() &&
                        it.collisionStepVelocity.isFinite() &&
                        (it.anchor == null || isFiniteAnchor(it.anchor))
            }
        }

        @JvmStatic
        fun interpolate(
            previous: VehiclePoseSnapshot,
            current: VehiclePoseSnapshot,
            alpha: Float,
        ): VehiclePoseSnapshot {
            val clamped = Mth.clamp(alpha, 0F, 1F)
            if ((previous.anchor == null) != (current.anchor == null)) return current
            val interpolatedAnchor = when {
                previous.anchor != null && current.anchor != null -> Vec3(
                    Mth.lerp(clamped.toDouble(), previous.anchor.x, current.anchor.x),
                    Mth.lerp(clamped.toDouble(), previous.anchor.y, current.anchor.y),
                    Mth.lerp(clamped.toDouble(), previous.anchor.z, current.anchor.z),
                )

                else -> current.anchor ?: previous.anchor
            }
            return VehiclePoseSnapshot(
                current.sequence,
                current.serverTick,
                Mth.rotLerp(clamped, previous.chassisYawDegrees, current.chassisYawDegrees),
                Mth.lerp(clamped, previous.basePitchDegrees, current.basePitchDegrees),
                Mth.lerp(clamped, previous.baseRollDegrees, current.baseRollDegrees),
                Mth.lerp(clamped, previous.pitchDegrees, current.pitchDegrees),
                Mth.lerp(clamped, previous.rollDegrees, current.rollDegrees),
                Mth.lerp(clamped.toDouble(), previous.groundBias, current.groundBias),
                Mth.lerp(clamped.toDouble(), previous.collisionStepOffset, current.collisionStepOffset),
                Mth.lerp(clamped.toDouble(), previous.collisionStepVelocity, current.collisionStepVelocity),
                interpolatedAnchor,
            )
        }

        private fun anchorsMatch(first: Vec3?, second: Vec3?): Boolean {
            if (first == null || second == null) return first == second
            return abs(first.x - second.x) < POSITION_EPSILON &&
                    abs(first.y - second.y) < POSITION_EPSILON &&
                    abs(first.z - second.z) < POSITION_EPSILON
        }

        private fun requireFiniteAnchor(anchor: Vec3) {
            require(isFiniteAnchor(anchor)) { "Chassis anchor must be finite" }
        }

        private fun isFiniteAnchor(anchor: Vec3): Boolean =
            anchor.x.isFinite() && anchor.y.isFinite() && anchor.z.isFinite()
    }
}
