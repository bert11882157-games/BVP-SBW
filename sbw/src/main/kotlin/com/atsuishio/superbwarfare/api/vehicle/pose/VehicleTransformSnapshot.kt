package com.atsuishio.superbwarfare.api.vehicle.pose

import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import kotlin.math.sqrt

/** Immutable local/world transform view. Matrix accessors always return defensive copies. */
class VehicleTransformSnapshot private constructor(
    val name: String,
    val sequence: Int,
    val serverTick: Long,
    localToWorld: Matrix4d,
    copyMatrix: Boolean,
) {
    constructor(
        name: String,
        sequence: Int,
        serverTick: Long,
        localToWorld: Matrix4d,
    ) : this(name, sequence, serverTick, localToWorld, true)

    private val localToWorld = if (copyMatrix) Matrix4d(localToWorld) else localToWorld
    @Volatile
    private var worldToLocal: Matrix4d? = null

    fun matrix(): Matrix4d = Matrix4d(localToWorld)

    fun localToWorld(point: Vec3): Vec3 = transformPoint(localToWorld, point)

    fun worldToLocal(point: Vec3): Vec3 = transformPoint(inverse(), point)

    fun localDirectionToWorld(direction: Vec3): Vec3 = transformDirection(localToWorld, direction)

    fun worldDirectionToLocal(direction: Vec3): Vec3 = transformDirection(inverse(), direction)

    private fun inverse(): Matrix4d {
        worldToLocal?.let { return it }
        return Matrix4d(localToWorld).invert().also { worldToLocal = it }
    }

    private fun transformPoint(matrix: Matrix4d, point: Vec3): Vec3 {
        return Vec3(
            matrix.m00() * point.x + matrix.m10() * point.y + matrix.m20() * point.z + matrix.m30(),
            matrix.m01() * point.x + matrix.m11() * point.y + matrix.m21() * point.z + matrix.m31(),
            matrix.m02() * point.x + matrix.m12() * point.y + matrix.m22() * point.z + matrix.m32(),
        )
    }

    private fun transformDirection(matrix: Matrix4d, direction: Vec3): Vec3 {
        val x = matrix.m00() * direction.x + matrix.m10() * direction.y + matrix.m20() * direction.z
        val y = matrix.m01() * direction.x + matrix.m11() * direction.y + matrix.m21() * direction.z
        val z = matrix.m02() * direction.x + matrix.m12() * direction.y + matrix.m22() * direction.z
        val lengthSquared = x * x + y * y + z * z
        if (lengthSquared <= 1.0E-12) return Vec3(x, y, z)
        val inverseLength = 1.0 / sqrt(lengthSquared)
        return Vec3(x * inverseLength, y * inverseLength, z * inverseLength)
    }

    companion object {
        /** Takes ownership of a newly-created matrix which is never exposed or mutated again. */
        internal fun fromOwnedMatrix(
            name: String,
            sequence: Int,
            serverTick: Long,
            localToWorld: Matrix4d,
        ): VehicleTransformSnapshot = VehicleTransformSnapshot(
            name,
            sequence,
            serverTick,
            localToWorld,
            false,
        )
    }
}
