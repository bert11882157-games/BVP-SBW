package com.atsuishio.superbwarfare.api.aircraft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AircraftRocketPodOrderTest {
    private val left1 = AircraftRocketPodOrder.Candidate("pylon_1", AircraftRocketPodOrder.Side.LEFT)
    private val right1 = AircraftRocketPodOrder.Candidate("pylon_1", AircraftRocketPodOrder.Side.RIGHT)
    private val left2 = AircraftRocketPodOrder.Candidate("pylon_2", AircraftRocketPodOrder.Side.LEFT)
    private val right2 = AircraftRocketPodOrder.Candidate("pylon_2", AircraftRocketPodOrder.Side.RIGHT)

    @Test fun `accepted side preference alternates across multiple mounted channels`() {
        assertEquals(listOf(left1, left2, right1, right2),
            AircraftRocketPodOrder.ordered(listOf(left1, right1, left2, right2), null, 0))
        assertEquals(listOf(right2, right1, left1, left2),
            AircraftRocketPodOrder.ordered(listOf(left1, right1, left2, right2),
                AircraftRocketPodOrder.Side.LEFT, 3))
    }

    @Test fun `empty preferred side falls back in stable channel order`() {
        assertEquals(listOf(left2, left1),
            AircraftRocketPodOrder.ordered(listOf(left1, left2), AircraftRocketPodOrder.Side.LEFT, 1))
        assertEquals(emptyList<AircraftRocketPodOrder.Candidate>(),
            AircraftRocketPodOrder.ordered(emptyList(), AircraftRocketPodOrder.Side.RIGHT, 9))
    }
}
