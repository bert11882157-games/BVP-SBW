package com.atsuishio.superbwarfare.client.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftLoadoutTabTest {
    private fun store(id: String, category: String) = AircraftStoreView(id, id, category, null, null, null, 1.0, 1)

    @Test fun everyStoreCategoryLandsOnOneTab() {
        val expected = mapOf("AIR_TO_AIR" to AircraftLoadoutTab.AAM, "AIR_TO_GROUND" to AircraftLoadoutTab.AGM,
            "LASER_GUIDED" to AircraftLoadoutTab.AGM, "COMMAND_GUIDED" to AircraftLoadoutTab.AGM,
            "MISSILE_LAUNCHER" to AircraftLoadoutTab.AGM, "ANTI_RADIATION" to AircraftLoadoutTab.ARM,
            "CRUISE" to AircraftLoadoutTab.CRUISE, "BOMB" to AircraftLoadoutTab.BOMBS,
            "ROCKET_POD" to AircraftLoadoutTab.ROCKETS, "GUN_POD" to AircraftLoadoutTab.GUNS,
            "TARGETING_POD" to AircraftLoadoutTab.PODS, "VISUAL_ONLY" to AircraftLoadoutTab.OTHER)
        for ((category, tab) in expected) assertEquals(tab, AircraftLoadoutTab.of(store(category, category)), category)
    }

    @Test fun onlyTabsWithOptionsAreShownInFixedOrder() {
        val options = listOf(store("b", "BOMB"), store("a", "AIR_TO_AIR"), store("b2", "BOMB"), store("g", "GUN_POD"))
        assertEquals(listOf(AircraftLoadoutTab.AAM, AircraftLoadoutTab.BOMBS, AircraftLoadoutTab.GUNS),
            AircraftLoadoutTab.present(options))
    }

    @Test fun chosenTabFallsBackToTheFittedStoreThenTheFirstTab() {
        val bomb = store("b", "BOMB"); val aam = store("a", "AIR_TO_AIR")
        val options = listOf(bomb, aam)
        assertEquals(AircraftLoadoutTab.BOMBS, AircraftLoadoutTab.choose(options, AircraftLoadoutTab.BOMBS, aam))
        assertEquals(AircraftLoadoutTab.AAM, AircraftLoadoutTab.choose(options, AircraftLoadoutTab.ROCKETS, aam))
        assertEquals(AircraftLoadoutTab.AAM, AircraftLoadoutTab.choose(options, null, null))
        assertEquals(AircraftLoadoutTab.AAM, AircraftLoadoutTab.choose(options, null, store("r", "ROCKET_POD")))
        assertNull(AircraftLoadoutTab.choose(emptyList(), AircraftLoadoutTab.AAM, aam))
    }
}
