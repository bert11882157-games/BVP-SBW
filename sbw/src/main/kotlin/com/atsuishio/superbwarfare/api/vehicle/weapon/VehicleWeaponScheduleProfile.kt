package com.atsuishio.superbwarfare.api.vehicle.weapon

import net.minecraft.resources.ResourceLocation

data class VehicleWeaponHeatPolicy(
    val projectileLimit: Int,
    val windowTicks: Int,
    val cooldownTicks: Int,
    val releaseDecayTicks: Int = 2,
) {
    init {
        require(projectileLimit > 0) { "projectileLimit must be positive" }
        require(windowTicks > 0) { "windowTicks must be positive" }
        require(cooldownTicks > 0) { "cooldownTicks must be positive" }
        require(releaseDecayTicks > 0) { "releaseDecayTicks must be positive" }
    }
}

/**
 * Data-only cadence and heat policy for a server-scheduled vehicle weapon.
 * Addons own the values; SBW owns the state machine that consumes them.
 */
data class VehicleWeaponScheduleProfile(
    val id: ResourceLocation,
    val bulletRpm: Int,
    val eventRpm: Int,
    val projectilesPerEvent: Int,
    val soundIntervalProjectiles: Int,
    val heatPolicy: VehicleWeaponHeatPolicy? = null,
    val repeatWhileHeld: Boolean = true,
    /** Accepted events retain their cadence across release/repress and weapon switching. */
    val preserveAcceptedCadenceAcrossPresses: Boolean = false,
    val releaseGraceTicks: Int = 4,
    val maxCatchUpEvents: Int = 2,
    val halfHeatPitch: Float = 0.84f,
    val threeQuarterHeatPitch: Float = 0.68f,
    val nineTenthsHeatPitch: Float = 0.50f,
    val emitNativeSound: Boolean = true,
) {
    init {
        require(bulletRpm > 0) { "bulletRpm must be positive" }
        require(eventRpm > 0) { "eventRpm must be positive" }
        require(projectilesPerEvent > 0) { "projectilesPerEvent must be positive" }
        require(soundIntervalProjectiles > 0) { "soundIntervalProjectiles must be positive" }
        require(releaseGraceTicks >= 0) { "releaseGraceTicks cannot be negative" }
        require(maxCatchUpEvents > 0) { "maxCatchUpEvents must be positive" }
    }

    fun soundPitch(heatFraction: Double): Float = when {
        heatFraction >= 0.90 -> nineTenthsHeatPitch
        heatFraction >= 0.75 -> threeQuarterHeatPitch
        heatFraction >= 0.50 -> halfHeatPitch
        else -> 1.0f
    }

    companion object {
        /** Java-friendly factory for the common fixed-window automatic-weapon policy. */
        @JvmStatic
        fun fixedWindow(
            id: ResourceLocation,
            bulletRpm: Int,
            eventRpm: Int,
            projectilesPerEvent: Int,
            soundIntervalProjectiles: Int,
            overheatProjectiles: Int,
            overheatWindowTicks: Int,
            cooldownTicks: Int,
            emitNativeSound: Boolean,
        ) = VehicleWeaponScheduleProfile(
            id = id,
            bulletRpm = bulletRpm,
            eventRpm = eventRpm,
            projectilesPerEvent = projectilesPerEvent,
            soundIntervalProjectiles = soundIntervalProjectiles,
            heatPolicy = VehicleWeaponHeatPolicy(
                overheatProjectiles,
                overheatWindowTicks,
                cooldownTicks,
            ),
            emitNativeSound = emitNativeSound,
        )
    }
}
