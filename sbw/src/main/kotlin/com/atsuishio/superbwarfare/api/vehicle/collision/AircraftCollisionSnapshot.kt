package com.atsuishio.superbwarfare.api.vehicle.collision

import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainBox
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.Collections

enum class AircraftCollisionRole { FUSELAGE, LANDING_GEAR }

/** One immutable physical part at a sampled pose; vertices use local X/Y/Z sign bits 0/1/2. */
class AircraftCollisionPart internal constructor(
    val role: AircraftCollisionRole,
    val active: Boolean,
    private val center: Vec3,
    private val halfExtents: Vec3,
    rotation: Quaterniond,
) {
    private val orientation = Quaterniond(rotation)
    val worldVertices: List<Vec3> = Collections.unmodifiableList((0..7).map { bits ->
        val local = Vector3d(
            if (bits and 1 == 0) -halfExtents.x else halfExtents.x,
            if (bits and 2 == 0) -halfExtents.y else halfExtents.y,
            if (bits and 4 == 0) -halfExtents.z else halfExtents.z)
        orientation.transform(local)
        Vec3(local.x + center.x, local.y + center.y, local.z + center.z)
    })
    val worldBounds = AABB(worldVertices.minOf { it.x }, worldVertices.minOf { it.y },
        worldVertices.minOf { it.z }, worldVertices.maxOf { it.x }, worldVertices.maxOf { it.y },
        worldVertices.maxOf { it.z })

    internal fun toObb() = OBB(Vector3d(center.x, center.y, center.z),
        Vector3d(halfExtents.x, halfExtents.y, halfExtents.z), Quaterniond(orientation), OBB.Part.BODY)

    internal fun toTerrainInfo() = OBBInfo().apply {
        size = halfExtents
        landingGear = role == AircraftCollisionRole.LANDING_GEAR
        getOBB().center.set(center.x, center.y, center.z)
        getOBB().updateRotation(orientation)
    }

    internal fun clip(start: Vec3, end: Vec3): Vec3? {
        val box = toObb()
        if (box.contains(start)) return start
        return box.clip(Vector3d(start.x, start.y, start.z), Vector3d(end.x, end.y, end.z))
            .map { Vec3(it.x, it.y, it.z) }.orElse(null)
    }
}

/** The two authored physical parts and their nonsolid, tightly enclosing discovery AABB. */
class AircraftCollisionSnapshot private constructor(parts: List<AircraftCollisionPart>) {
    val parts: List<AircraftCollisionPart> = Collections.unmodifiableList(parts)
    val queryBounds: AABB = parts.filter { it.active }.map { it.worldBounds }.reduce(AABB::minmax)

    internal fun activeObbs(): List<OBB> = parts.filter { it.active }.map { it.toObb() }
    internal fun terrainInfos(): List<OBBInfo> = parts.filter { it.active }.map { it.toTerrainInfo() }

    fun clip(start: Vec3, end: Vec3): Vec3? = parts.asSequence().filter { it.active }
        .mapNotNull { it.clip(start, end) }.minByOrNull { start.distanceToSqr(it) }

    companion object {
        @JvmStatic
        fun create(definition: AircraftTerrainContact, frame: Matrix4d,
                   gearFraction: Float): AircraftCollisionSnapshot {
            require(definition.valid())
            val orientation = frame.getNormalizedRotation(Quaterniond())
            fun part(role: AircraftCollisionRole, source: AircraftTerrainBox, active: Boolean): AircraftCollisionPart {
                val localCenter = source.minimum.add(source.maximum).scale(0.5)
                val world = frame.transformPosition(Vector3d(localCenter.x, localCenter.y, localCenter.z))
                return AircraftCollisionPart(role, active, Vec3(world.x, world.y, world.z),
                    source.maximum.subtract(source.minimum).scale(0.5), orientation)
            }
            return AircraftCollisionSnapshot(listOf(
                part(AircraftCollisionRole.FUSELAGE, definition.fuselage, true),
                part(AircraftCollisionRole.LANDING_GEAR, definition.landingGear,
                    definition.gearDeployed(gearFraction))))
        }
    }
}
