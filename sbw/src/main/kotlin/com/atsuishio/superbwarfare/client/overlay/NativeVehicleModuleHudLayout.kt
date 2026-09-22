package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.api.vehicle.presentation.*
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** Native damage parts only. BODY boxes do not imply an engine, ammo rack or weapon module. */
internal object NativeVehicleModuleHudLayout {
    fun sample(vehicle: VehicleEntity, partial: Float): List<VehicleModuleHudMarker> {
        val runningGear = when (vehicle.computed().engineType) {
            EngineType.TRACK -> VehicleModuleHudKind.TRACK
            EngineType.WHEEL, EngineType.TOM6, EngineType.WHEELCHAIR -> VehicleModuleHudKind.WHEEL
            else -> null
        }
        return vehicle.obb.mapIndexedNotNull { index, box ->
            val (kind, health) = when (box.part) {
                OBB.Part.MAIN_ENGINE -> VehicleModuleHudKind.ENGINE to VehicleModuleHudHealth(
                    vehicle.mainEngineHealth, vehicle.getEngineMaxHealth(), vehicle.mainEngineDamaged)
                OBB.Part.SUB_ENGINE -> VehicleModuleHudKind.ENGINE to VehicleModuleHudHealth(
                    vehicle.subEngineHealth, vehicle.getEngineMaxHealth(), vehicle.subEngineDamaged)
                OBB.Part.TURRET -> VehicleModuleHudKind.WEAPON to VehicleModuleHudHealth(
                    vehicle.turretHealth, vehicle.getTurretMaxHealth(), vehicle.turretDamaged)
                OBB.Part.WHEEL_LEFT -> (runningGear ?: return@mapIndexedNotNull null) to VehicleModuleHudHealth(
                    vehicle.leftWheelHealth, vehicle.getWheelMaxHealth(), vehicle.leftWheelDamaged)
                OBB.Part.WHEEL_RIGHT -> (runningGear ?: return@mapIndexedNotNull null) to VehicleModuleHudHealth(
                    vehicle.rightWheelHealth, vehicle.getWheelMaxHealth(), vehicle.rightWheelDamaged)
                else -> return@mapIndexedNotNull null
            }
            val p = box.position
            val world = vehicle.transformPosition(vehicle.getTransformFromString(box.transform, partial), p.x, p.y, p.z)
            val local = vehicle.worldToVehicleLocal(Vec3(world.x, world.y, world.z), partial)
            var minZ = -local.z * 16
            var maxZ = minZ
            if (kind == VehicleModuleHudKind.TRACK) {
                val rotation = vehicle.getRotationFromString(box.rotation, partial)
                for (corner in 0..7) {
                    val offset = Vector3d(
                        if (corner and 1 == 0) -box.size.x else box.size.x,
                        if (corner and 2 == 0) -box.size.y else box.size.y,
                        if (corner and 4 == 0) -box.size.z else box.size.z).rotate(rotation)
                    val point = vehicle.worldToVehicleLocal(Vec3(world.x + offset.x,
                        world.y + offset.y, world.z + offset.z), partial)
                    minZ = minOf(minZ, -point.z * 16)
                    maxZ = maxOf(maxZ, -point.z * 16)
                }
            }
            val id = if (kind == VehicleModuleHudKind.TRACK) "native_${box.part}" else "native_${box.part}_$index"
            VehicleModuleHudMarker(id, kind, local.x * 16, -local.z * 16, health, minZ, maxZ)
        }.groupBy { it.id }.values.map { sections ->
            val first = sections.first()
            if (sections.size == 1) first else first.copy(
                modelX = sections.map { it.modelX }.average(),
                modelMinZ = sections.minOf { it.modelMinZ }, modelMaxZ = sections.maxOf { it.modelMaxZ })
        }
    }
}
