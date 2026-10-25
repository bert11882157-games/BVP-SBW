package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.CompoundTag

/** One durable-field projection for vehicle item encoding and validated restoration. */
internal object VehicleItemDurableState {
    const val INITIAL_BOARDING_APPLIED = "InitialPassengerReloadProgressApplied"

    val keys = setOf(
        "Override",
        "Health",
        "WeaponState",
        INITIAL_BOARDING_APPLIED,
        "TurretHealth",
        "LeftWheelHealth",
        "RightWheelHealth",
        "MainEngineHealth",
        "SubEngineHealth",
        "TurretDamaged",
        "LeftWheelDamaged",
        "RightWheelDamaged",
        "MainEngineDamaged",
        "SubEngineDamaged",
        VehicleModuleStateService.MODULE_STATES_TAG,
        "DogTagIcon",
        "SelectedWeapon",
        "SecondaryWeapon",
        "Energy",
        "Inventory",
        "BvpSpentEraBricks",
    )

    fun validBoardingMarker(state: CompoundTag): Boolean {
        val marker = state.get(INITIAL_BOARDING_APPLIED) ?: return true
        return marker is ByteTag && marker.asByte.toInt() in 0..1
    }

    /** Deep-copy only vehicle-owned durable fields. World authority and occupants never enter an item. */
    fun project(state: CompoundTag): CompoundTag = CompoundTag().also { durable ->
        for (key in keys) state.get(key)?.let { durable.put(key, it.copy()) }
    }

    /**
     * Called only after full item validation. Missing markers on old recovered aircraft must not mint
     * fresh ammunition. A genuinely fresh item has no durable payload and bypasses restoration entirely.
     * An explicit false on a newly encoded, never-boarded aircraft still permits its first boarding fill.
     */
    fun forRestore(validatedState: CompoundTag, armsOnFirstBoarding: Boolean): CompoundTag =
        project(validatedState).also { durable ->
            if (armsOnFirstBoarding && !durable.contains(INITIAL_BOARDING_APPLIED))
                durable.putBoolean(INITIAL_BOARDING_APPLIED, true)
        }
}
