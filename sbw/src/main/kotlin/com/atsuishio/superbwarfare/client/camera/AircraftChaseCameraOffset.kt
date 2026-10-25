package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import net.minecraft.world.phys.Vec3

/**
 * Default F5 chase framing for aircraft, applied on top of each vehicle's authored offset so every
 * pack inherits it without re-authoring data. Offsets are in blocks, in the vehicle yaw frame:
 * +x left, +y up, +z forward (a chase camera behind the aircraft has negative z).
 *
 * Fixed-wing aircraft keep their authored framing angle and sit further back. Helicopters sit
 * further back, a little higher, and to the pilot's left; the view stays parallel to the heading,
 * so the airframe sits right of centre instead of blocking the flight path.
 * The scroll-wheel distance is still added by the caller after this offset.
 */
object AircraftChaseCameraOffset {
    const val FIXED_WING_DISTANCE_SCALE = 1.4
    const val HELICOPTER_DISTANCE_SCALE = 1.45
    const val HELICOPTER_EXTRA_HEIGHT_BLOCKS = 1.5
    /** Left offset as a fraction of the (scaled) distance behind the aircraft. */
    const val HELICOPTER_LEFT_PER_BACK = 0.2

    fun chase(type: VehicleType?, authored: Vec3): Vec3 = when (type) {
        VehicleType.AIRPLANE -> authored.scale(FIXED_WING_DISTANCE_SCALE)
        VehicleType.HELICOPTER -> {
            val back = -authored.z * HELICOPTER_DISTANCE_SCALE
            Vec3(
                authored.x + HELICOPTER_LEFT_PER_BACK * back.coerceAtLeast(0.0),
                authored.y * HELICOPTER_DISTANCE_SCALE + HELICOPTER_EXTRA_HEIGHT_BLOCKS,
                -back,
            )
        }
        else -> authored
    }

    /**
     * Same policy for seats that use the plain vanilla third-person camera, whose authored
     * `ThirdPersonCameraPos` is (+x left, +y up, +z distance behind).
     */
    fun thirdPerson(type: VehicleType?, authored: Vec3): Vec3 {
        val chase = chase(type, Vec3(authored.x, authored.y, -authored.z))
        return Vec3(chase.x, chase.y, -chase.z)
    }
}
