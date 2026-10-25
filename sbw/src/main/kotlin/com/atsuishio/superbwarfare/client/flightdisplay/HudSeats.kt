package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource.HeadUpDisplayResource

/**
 * Which of a vehicle's HUDs and sights exist and which one goes live for a crewman: the client resource's
 * "HeadUpDisplay" (the one most vehicles carry) and any more crew stations' in "HeadUpDisplays", one per seat (a
 * helicopter's gunner beside its pilot). Only VEHICLE_LOCAL_BLOCKS entries count. Kept free of the renderer so the
 * selection is testable.
 */
object HudSeats {
    /** Every usable HUD of [resource], "HeadUpDisplay" first. */
    @JvmStatic fun of(resource: DefaultVehicleResource): List<HeadUpDisplayResource> =
        of(resource.headUpDisplay, resource.headUpDisplays)

    @JvmStatic fun of(one: HeadUpDisplayResource?, more: Array<HeadUpDisplayResource?>?): List<HeadUpDisplayResource> {
        if (more == null) return if (one != null && usable(one)) listOf(one) else emptyList()
        val all = ArrayList<HeadUpDisplayResource>(more.size + 1)
        if (one != null) all.add(one)
        for (r in more) if (r != null) all.add(r)
        return all.filter(::usable)
    }

    /** The HUD drawn live for the crewman in [seat] (-1: not aboard as crew), or null: each goes live only in its own seat. */
    @JvmStatic fun liveFor(seat: Int, all: List<HeadUpDisplayResource>): HeadUpDisplayResource? =
        if (seat < 0) null else all.firstOrNull { it.seat == seat }

    private fun usable(r: HeadUpDisplayResource) = r.frame == "VEHICLE_LOCAL_BLOCKS"
}
