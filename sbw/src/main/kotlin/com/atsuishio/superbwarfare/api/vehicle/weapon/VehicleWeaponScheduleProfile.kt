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
 *
 * [roundWeight] > 1 marks a consolidated schedule: each event fires rounds that each stand for
 * [roundWeight] rounds, [eventRpm] is already divided accordingly and [bulletRpm] keeps the real
 * rate for presentation. Providers author ordinary profiles; SBW applies [consolidated].
 */
data class VehicleWeaponScheduleProfile @JvmOverloads constructor(
    val id: ResourceLocation,
    val bulletRpm: Int,
    val eventRpm: Int,
    val projectilesPerEvent: Int,
    val soundIntervalProjectiles: Int,
    val heatPolicy: VehicleWeaponHeatPolicy? = null,
    val repeatWhileHeld: Boolean = true,
    /** Compatibility field; server cadence now always survives release/repress and selection. */
    val preserveAcceptedCadenceAcrossPresses: Boolean = false,
    val releaseGraceTicks: Int = 4,
    val maxCatchUpEvents: Int = 2,
    val halfHeatPitch: Float = 0.84f,
    val threeQuarterHeatPitch: Float = 0.68f,
    val nineTenthsHeatPitch: Float = 0.50f,
    val emitNativeSound: Boolean = true,
    val roundWeight: Int = 1,
) {
    init {
        require(bulletRpm > 0) { "bulletRpm must be positive" }
        require(eventRpm > 0) { "eventRpm must be positive" }
        require(projectilesPerEvent > 0) { "projectilesPerEvent must be positive" }
        require(soundIntervalProjectiles > 0) { "soundIntervalProjectiles must be positive" }
        require(releaseGraceTicks >= 0) { "releaseGraceTicks cannot be negative" }
        require(maxCatchUpEvents > 0) { "maxCatchUpEvents must be positive" }
        require(roundWeight in 1..AircraftRoundConsolidation.WEIGHT) { "roundWeight out of range" }
    }

    /** Rounds one accepted event represents, for heat, sound cadence and ammunition. */
    val roundsPerEvent: Int get() = projectilesPerEvent * roundWeight

    /**
     * The same weapon firing [weight]-round events: the event rate and catch-up budget shrink by
     * [weight] while [bulletRpm], heat per second and sound cadence stay those of the real gun.
     */
    fun consolidated(weight: Int): VehicleWeaponScheduleProfile {
        val span = weight.coerceIn(1, AircraftRoundConsolidation.WEIGHT)
        if (span == 1 || roundWeight != 1) return this
        val rate = AircraftRoundConsolidation.eventRpm(eventRpm, span)
        val capacity = ((rate.toLong() + 1199) / 1200).toInt()
        return copy(
            eventRpm = rate,
            roundWeight = span,
            maxCatchUpEvents = maxOf((maxCatchUpEvents + span - 1) / span, capacity, 1),
        )
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
