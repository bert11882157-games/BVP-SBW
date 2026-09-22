package com.atsuishio.superbwarfare.api.vehicle.pose

import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import java.util.LinkedHashMap

/** Immutable resolved named-frame set for one authoritative tick or one interpolated render view. */
class VehicleAttachmentSnapshot private constructor(
    val sequence: Int,
    val serverTick: Long,
    transforms: Map<String, VehicleTransformSnapshot>
) {
    // Factories hand over fresh maps and no map view escapes this class.
    private val transforms = transforms

    fun transform(name: String): VehicleTransformSnapshot? = transforms[name]

    fun point(name: String, localPoint: Vec3): Vec3? = transforms[name]?.localToWorld(localPoint)

    fun direction(name: String, localDirection: Vec3): Vec3? =
        transforms[name]?.localDirectionToWorld(localDirection)

    internal val frameCount: Int
        get() = transforms.size

    internal fun copyFramesTo(destination: MutableMap<String, VehicleTransformSnapshot>) {
        destination.putAll(transforms)
    }

    companion object {
        internal fun fromSnapshots(
            sequence: Int,
            serverTick: Long,
            snapshots: Map<String, VehicleTransformSnapshot>,
        ): VehicleAttachmentSnapshot = VehicleAttachmentSnapshot(sequence, serverTick, snapshots)

        internal fun fromOwnedMatrices(
            sequence: Int,
            serverTick: Long,
            matrices: Map<String, Matrix4d>,
        ): VehicleAttachmentSnapshot {
            val snapshots = LinkedHashMap<String, VehicleTransformSnapshot>(matrices.size)
            for ((name, matrix) in matrices) {
                require(name.isNotBlank()) { "Attachment frame name cannot be blank" }
                require(!snapshots.containsKey(name)) { "Duplicate attachment frame: $name" }
                snapshots[name] = VehicleTransformSnapshot.fromOwnedMatrix(name, sequence, serverTick, matrix)
            }
            return VehicleAttachmentSnapshot(sequence, serverTick, snapshots)
        }

        @JvmStatic
        fun fromMatrices(
            sequence: Int,
            serverTick: Long,
            matrices: Map<String, Matrix4d>
        ): VehicleAttachmentSnapshot {
            val snapshots = LinkedHashMap<String, VehicleTransformSnapshot>()
            for ((name, matrix) in matrices) {
                require(name.isNotBlank()) { "Attachment frame name cannot be blank" }
                require(!snapshots.containsKey(name)) { "Duplicate attachment frame: $name" }
                snapshots[name] = VehicleTransformSnapshot(name, sequence, serverTick, matrix)
            }
            return VehicleAttachmentSnapshot(sequence, serverTick, snapshots)
        }
    }
}
