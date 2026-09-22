package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.data.gun.value.ReloadState

enum class ReloadTransitionCause {
    DIRECT_REQUEST,
    AMMO_CONSUMER_CHANGE,
    STATUS_RESET,
}

enum class ReloadTransitionMode {
    NO_CHANGE,
    RESET,
    START_RELOAD,
}

/** One policy decision for a reload lifecycle transition. */
data class ReloadTransitionAction @JvmOverloads constructor(
    val mode: ReloadTransitionMode,
    val progressPercent: Int = 0,
) {
    init {
        require(progressPercent in 0..99) { "progressPercent must be between 0 and 99" }
        require(mode == ReloadTransitionMode.START_RELOAD || progressPercent == 0) {
            "progressPercent is only valid when starting a reload"
        }
    }

    companion object {
        @JvmField
        val RESET = ReloadTransitionAction(ReloadTransitionMode.RESET)

        @JvmField
        val START_FULL = ReloadTransitionAction(ReloadTransitionMode.START_RELOAD)

        @JvmStatic
        fun startAtProgress(progressPercent: Int) =
            ReloadTransitionAction(ReloadTransitionMode.START_RELOAD, progressPercent)
    }
}

/**
 * Selects the reload action for an ammunition-consumer switch.
 * The loaded-idle shortcut is deliberately separate from an active/empty switch so callers
 * cannot accidentally carry partial progress through an in-flight reload.
 */
data class ReloadTransitionPolicy @JvmOverloads constructor(
    val idleLoadedConsumerSwitch: ReloadTransitionAction = ReloadTransitionAction.RESET,
    val activeOrEmptyConsumerSwitch: ReloadTransitionAction = ReloadTransitionAction.RESET,
) {
    fun forConsumerSwitch(wasReloading: Boolean, hadMagazineAmmo: Boolean): ReloadTransitionAction {
        return if (!wasReloading && hadMagazineAmmo) {
            idleLoadedConsumerSwitch
        } else {
            activeOrEmptyConsumerSwitch
        }
    }

    companion object {
        const val VEHICLE_PARTIAL_RELOAD_PROGRESS_PERCENT = 66

        /** Existing GunData behavior: reset a switched consumer without starting a reload. */
        @JvmField
        val LEGACY = ReloadTransitionPolicy()

        /** Existing BVP vehicle timing: 66% credit for an idle loaded-consumer switch. */
        @JvmField
        val VEHICLE_DEFAULT = ReloadTransitionPolicy(
            idleLoadedConsumerSwitch = ReloadTransitionAction.startAtProgress(
                VEHICLE_PARTIAL_RELOAD_PROGRESS_PERCENT
            ),
            activeOrEmptyConsumerSwitch = ReloadTransitionAction.START_FULL,
        )
    }
}

/** Immutable receipt for a reload transition request. */
data class ReloadTransitionResult(
    val cause: ReloadTransitionCause,
    val mode: ReloadTransitionMode,
    val previousState: ReloadState,
    val previousRemainingTicks: Int,
    val progressPercent: Int,
    val previousAmmoConsumerIndex: Int? = null,
    val selectedAmmoConsumerIndex: Int? = null,
) {
    fun changed(): Boolean = mode != ReloadTransitionMode.NO_CHANGE

    fun reloadRequested(): Boolean = mode == ReloadTransitionMode.START_RELOAD
}
