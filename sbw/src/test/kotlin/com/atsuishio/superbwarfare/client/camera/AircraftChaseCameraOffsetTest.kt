package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftChaseCameraOffsetTest {
    @Test fun fixedWingMovesBackAlongTheAuthoredFramingAngle() {
        val chase = AircraftChaseCameraOffset.chase(VehicleType.AIRPLANE, Vec3(0.0, 3.2, -16.0))
        assertEquals(0.0, chase.x, 1e-9)
        assertEquals(3.2 * 1.4, chase.y, 1e-9)
        assertEquals(-16.0 * 1.4, chase.z, 1e-9)
    }

    @Test fun helicopterSitsFurtherBackHigherAndToTheLeft() {
        val authored = Vec3(0.0, 6.06, -17.94)
        val chase = AircraftChaseCameraOffset.chase(VehicleType.HELICOPTER, authored)
        assertTrue(chase.z < authored.z, "further behind")
        assertTrue(chase.y > authored.y * 1.45, "above the scaled height")
        assertTrue(chase.x > 0.0, "+x is the aircraft's left")
        assertEquals(0.2 * -chase.z, chase.x, 1e-9)
    }

    @Test fun groundVehiclesKeepAuthoredOffsets() {
        val authored = Vec3(0.0, 2.2, -6.6)
        assertSame(authored, AircraftChaseCameraOffset.chase(VehicleType.TANK, authored))
        assertSame(authored, AircraftChaseCameraOffset.chase(null, authored))
    }

    @Test fun vanillaThirdPersonFrameUsesPositiveDistance() {
        val chase = AircraftChaseCameraOffset.thirdPerson(VehicleType.HELICOPTER, Vec3(0.0, 5.0, 10.0))
        assertEquals(14.5, chase.z, 1e-9)
        assertEquals(0.2 * 14.5, chase.x, 1e-9)
        assertEquals(5.0 * 1.45 + 1.5, chase.y, 1e-9)
    }
}
