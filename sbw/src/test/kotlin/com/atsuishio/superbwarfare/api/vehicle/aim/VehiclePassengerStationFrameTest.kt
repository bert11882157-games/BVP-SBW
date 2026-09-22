package com.atsuishio.superbwarfare.api.vehicle.aim

import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot
import com.atsuishio.superbwarfare.data.vehicle.subdata.PassengerWeaponStationBinding
import com.atsuishio.superbwarfare.data.vehicle.subdata.PassengerWeaponStationParent
import com.atsuishio.superbwarfare.data.vehicle.subdata.PassengerWeaponStationWeaponKind
import kotlinx.serialization.json.Json
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehiclePassengerStationFrameTest {
    @Test fun `full hull attitude inverse recovers one station-local servo target`() {
        val pivot = Vec3(0.25, 1.75, -8.0)
        var cases = 0
        for (heading in listOf(-179F, -90F, 0F, 35F, 179F)) {
            for (pitch in listOf(-60F, 0F, 55F)) {
                for (roll in listOf(-179F, -90F, 0F, 90F, 179F)) {
                    for (baseYaw in listOf(0F, 180F)) {
                        val hull = Matrix4d().translation(100.0, 200.0, -300.0)
                            .rotateY(Math.toRadians(-heading.toDouble()))
                        VehiclePoseSnapshot.IDENTITY.withBasePose(pitch, roll).applyBaseAttitude(hull)
                        val base = VehiclePassengerStationFrame.base(hull, pivot, baseYaw)
                        for (yaw in listOf(-70F, -35F, 0F, 35F, 70F)) {
                            for (elevation in listOf(-40F, 0F, 60F)) {
                                val physical = Matrix4d(base).rotateY(Math.toRadians(yaw.toDouble()))
                                    .translate(0.0, 0.2, -0.3)
                                    .rotateX(Math.toRadians(elevation.toDouble()))
                                val world = physical.transformDirection(Vector3d(0.0, 0.0, 1.0))
                                val result = VehiclePassengerStationFrame.angles(
                                    base, Vec3(world.x, world.y, world.z),
                                )!!
                                assertEquals(yaw, result.yaw, 1E-4F)
                                assertEquals(elevation, result.pitch, 1E-4F)
                                cases++
                            }
                        }
                    }
                }
            }
        }
        assertEquals(2250, cases)
    }

    @Test fun `rear base yaw is a frame identity not an extra visible neutral rotation`() {
        val base = VehiclePassengerStationFrame.base(Matrix4d(), Vec3(1.0, 2.0, 3.0), 180F)
        val neutral = base.transformDirection(Vector3d(0.0, 0.0, 1.0))
        assertEquals(-1.0, neutral.z, 1E-12)
        assertEquals(0F, VehiclePassengerStationFrame.angles(base, Vec3(0.0, 0.0, -1.0))!!.yaw, 1E-4F)
        assertEquals(1.0, base.m30(), 1E-12)
        assertEquals(2.0, base.m31(), 1E-12)
        assertEquals(3.0, base.m32(), 1E-12)
    }

    @Test fun `invalid direction and singular frame fail closed`() {
        for (direction in listOf(Vec3.ZERO, Vec3(Double.NaN, 0.0, 1.0),
                Vec3(0.0, Double.POSITIVE_INFINITY, 1.0))) {
            assertNull(VehiclePassengerStationFrame.angles(Matrix4d(), direction))
        }
        assertNull(VehiclePassengerStationFrame.angles(Matrix4d().zero(), Vec3(0.0, 0.0, 1.0)))
    }

    @Test fun `legacy direction frame and station identity defaults remain unchanged`() {
        assertEquals(VehicleAimDirectionFrame.LEGACY_WORLD,
            VehicleAimProfile.builder(VehicleAimChannel.TURRET).build().directionFrame)
        assertThrows(IllegalArgumentException::class.java) {
            VehicleAimProfile.builder(VehicleAimChannel.TURRET)
                .directionFrame(VehicleAimDirectionFrame.PASSENGER_STATION_LOCAL).build()
        }
        val profile = VehicleAimProfile.builder(VehicleAimChannel.PASSENGER_WEAPON)
            .directionFrame(VehicleAimDirectionFrame.PASSENGER_STATION_LOCAL).build()
        assertEquals(VehicleAimDirectionFrame.PASSENGER_STATION_LOCAL, profile.directionFrame)
        val binding = Json.decodeFromString<PassengerWeaponStationBinding>(
            """{"WeaponId":"PassengerMachineGun","WeaponKind":"HEAVY_MACHINE_GUN"}""",
        )
        assertTrue(binding.containsWeapon("PassengerMachineGun"))
        assertFalse(binding.containsWeapon("Cannon"))
        assertEquals(0F, binding.baseYawDegrees)
        assertEquals(PassengerWeaponStationParent.TURRET, binding.parent)
    }

    @Test fun `one articulation accepts only its exact bounded weapon bank`() {
        val binding = PassengerWeaponStationBinding().apply {
            parent = PassengerWeaponStationParent.HULL
            weaponKind = PassengerWeaponStationWeaponKind.AUTOCANNON
            weaponId = "RearGunLeft"
            weaponIds = listOf("RearGunLeft", "RearGunRight")
            baseYawDegrees = 180F
        }
        assertTrue(binding.containsWeapon("RearGunLeft"))
        assertTrue(binding.containsWeapon("RearGunRight"))
        assertFalse(binding.containsWeapon("FrontGun"))
        for (bad in listOf(listOf("RearGunRight", "RearGunLeft"),
                listOf("RearGunLeft", "RearGunLeft"), listOf("RearGunLeft", "bad weapon"),
                listOf("RearGunLeft") + (1..8).map { "RearGun$it" })) {
            binding.weaponIds = bad
            assertFalse(binding.hasTypedIdentity(), bad.toString())
        }
        binding.weaponIds = emptyList()
        for (yaw in listOf(Float.NaN, Float.POSITIVE_INFINITY, -181F, 181F)) {
            binding.baseYawDegrees = yaw
            assertFalse(binding.hasTypedIdentity())
        }
    }
}
