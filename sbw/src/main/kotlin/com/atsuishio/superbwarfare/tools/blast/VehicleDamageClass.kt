package com.atsuishio.superbwarfare.tools.blast

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/**
 * Hull class of a vehicle (vehicle data `DamageClass`, written by tools/damage/balance.py) and how hard an area
 * blast hits it: vehicle blast damage = 3 x kg x falloff x [blastMultiplier] (owner direction 2026-09-28).
 * A 125 mm HE shell bursting against the hull: MBT 16, IFV 31, car 94; a Mk 82 at the hull: MBT 353.
 */
enum class VehicleDamageClass(val blastMultiplier: Double) {
    MBT(1.0),
    MBT_CHASSIS(1.5),
    IFV(2.0),
    WHEELED(2.5),
    LIGHT(3.0),
    CAR(6.0),
    STATIC(6.0),
    AIRPLANE(4.0),
    HELICOPTER(4.0),
    /** Warships (BVP's carriers): sunk by bombs and missiles, the blast falloff alone keeps them tough. */
    SHIP(1.0);

    val aircraft: Boolean get() = this == AIRPLANE || this == HELICOPTER

    companion object {
        /** The authored class, or null for a vehicle without one (SBW's own vehicles keep their legacy rules). */
        @JvmStatic
        fun of(vehicle: VehicleEntity): VehicleDamageClass? {
            val authored = vehicle.computed().damageClass ?: return null
            return runCatching { valueOf(authored) }.getOrNull()
        }

        @JvmStatic
        fun isAircraftType(type: VehicleType?): Boolean = type == VehicleType.AIRPLANE || type == VehicleType.HELICOPTER
    }
}
