package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.api.aircraft.AircraftMissileLaunchers
import com.atsuishio.superbwarfare.api.aircraft.AircraftTargetingPods

/** Munition groups of the station picker; one tab per group that the station can carry. */
internal enum class AircraftLoadoutTab(val label: String) {
    AAM("AAM"), AGM("AGM"), ARM("ARM"), CRUISE("Cruise"), BOMBS("Bombs"),
    ROCKETS("Rockets"), GUNS("Guns"), PODS("Pods"), OTHER("Other");

    companion object {
        fun of(store: AircraftStoreView): AircraftLoadoutTab = when (store.category) {
            "AIR_TO_AIR" -> AAM
            "AIR_TO_GROUND", "LASER_GUIDED", "COMMAND_GUIDED", AircraftMissileLaunchers.CATEGORY -> AGM
            "ANTI_RADIATION" -> ARM
            "CRUISE" -> CRUISE
            "BOMB" -> BOMBS
            "ROCKET_POD" -> ROCKETS
            "GUN_POD" -> GUNS
            AircraftTargetingPods.CATEGORY -> PODS
            else -> OTHER
        }

        /** Tabs in enum order that hold at least one of [options]. */
        fun present(options: List<AircraftStoreView>): List<AircraftLoadoutTab> =
            options.mapTo(HashSet(), ::of).sorted()

        /**
         * The tab to show: [requested] when the station still offers it, else the tab of the
         * [fitted] store, else the first one. Null only when there are no options at all.
         */
        fun choose(options: List<AircraftStoreView>, requested: AircraftLoadoutTab?,
                   fitted: AircraftStoreView?): AircraftLoadoutTab? {
            val tabs = present(options)
            return requested?.takeIf { it in tabs } ?: fitted?.let(::of)?.takeIf { it in tabs } ?: tabs.firstOrNull()
        }
    }
}
