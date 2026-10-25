package com.atsuishio.superbwarfare.api.vehicle.weapon

import java.util.LinkedHashMap
import java.util.UUID

/**
 * Retained, seat-bound trigger edges. Queries resolve the current selection without preparing a shot or consuming
 * cadence. Only refresh prunes stale entries; releases are visible immediately, without presentation release grace.
 * The scheduler supplies the same live-seat and selection resolvers for preparation and pre-aim reads.
 */
internal class VehicleWeaponTriggerIntents<T : Any, R : Any>(
    private val currentSeat: (UUID) -> Int?,
    private val resolve: (UUID, VehicleWeaponSlot) -> R?,
) {
    private data class Held<T>(val seatIndex: Int, val payload: T)
    private val primary = LinkedHashMap<UUID, Held<T>>()
    private val secondary = LinkedHashMap<UUID, Held<T>>()

    private fun entries(slot: VehicleWeaponSlot) = when (slot) {
        VehicleWeaponSlot.PRIMARY -> primary
        VehicleWeaponSlot.SECONDARY -> secondary
    }

    fun retain(slot: VehicleWeaponSlot, controller: UUID, seatIndex: Int, payload: T) {
        require(seatIndex >= 0)
        entries(slot)[controller] = Held(seatIndex, payload)
    }

    fun release(slot: VehicleWeaponSlot, controller: UUID) {
        entries(slot).remove(controller)
    }

    /** No cached tick result: a press, release or selection change in the same tick changes this answer. */
    fun holds(controller: UUID, seatIndex: Int, matches: (R, VehicleWeaponSlot) -> Boolean): Boolean {
        if (seatIndex < 0 || currentSeat(controller) != seatIndex) return false
        for (slot in VehicleWeaponSlot.entries) {
            val held = entries(slot)[controller] ?: continue
            if (held.seatIndex != seatIndex) continue
            val selection = resolve(controller, slot) ?: continue
            if (matches(selection, slot)) return true
        }
        return false
    }

    /** Execution preparation retains insertion order and gives primary its existing precedence over secondary. */
    fun refresh(
        slot: VehicleWeaponSlot,
        unresolved: (UUID) -> Unit,
        activate: (R, T, VehicleWeaponSlot) -> Boolean,
    ) {
        val iterator = entries(slot).iterator()
        while (iterator.hasNext()) {
            val (controller, held) = iterator.next()
            if (currentSeat(controller) != held.seatIndex) {
                iterator.remove()
                continue
            }
            val selection = resolve(controller, slot)
            if (selection == null) {
                iterator.remove()
                unresolved(controller)
                continue
            }
            if (!activate(selection, held.payload, slot)) iterator.remove()
        }
    }
}
