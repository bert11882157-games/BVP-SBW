package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo
import com.atsuishio.superbwarfare.tools.OBB

/** Per-entity selection cache, invalidated by an authored-list replacement or gear endpoint. */
internal class VehicleCollisionBoxes {
    private var source: List<OBBInfo>? = null
    private var gearRetracted = false
    private var tracksOmitted = false
    private var selected: MutableList<OBB>? = null

    @JvmOverloads
    fun select(boxes: List<OBBInfo>, hasLandingGear: Boolean, gearFraction: Float,
               omitTracks: Boolean = false): MutableList<OBB> {
        // Partial, invalid, or unavailable gear remains solid, matching its visible extended mesh.
        val retracted = hasLandingGear && gearFraction == 1F
        if (source !== boxes || gearRetracted != retracted || tracksOmitted != omitTracks || selected == null) {
            selected = boxes.asSequence().filter { !retracted || !it.landingGear }
                .filter { !omitTracks || it.part != OBB.Part.WHEEL_LEFT && it.part != OBB.Part.WHEEL_RIGHT }
                .map { it.getOBB() }.toMutableList()
            source = boxes
            gearRetracted = retracted
            tracksOmitted = omitTracks
        }
        return selected!!
    }
}
