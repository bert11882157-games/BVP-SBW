package com.atsuishio.superbwarfare.tools.blast

import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Owner 2026-09-29, "munitions underperforming": blasts measure to the hull boxes, and a direct hit never costs damage. */
class BlastDistanceTest {
    private val hull = Vector3d(0.0, 1.0, 0.0)
    private val half = Vector3d(1.9, 1.0, 4.0)   // an 8 x 3.8 m tank hull, long axis z

    @Test fun `inside and on the surface is zero`() {
        assertEquals(0.0, BlastDistance.toBox(hull, half, Quaterniond(), 0.0, 1.0, 0.0), 1e-9)
        assertEquals(0.0, BlastDistance.toBox(hull, half, Quaterniond(), 1.9, 1.0, 3.9), 1e-9)
    }

    @Test fun `a charge off the nose is measured from the nose`() {
        // 1 m in front of the glacis: a 3 x 3 m core entity box would put it at 2.5 m
        assertEquals(1.0, BlastDistance.toBox(hull, half, Quaterniond(), 0.0, 1.0, 5.0), 1e-9)
        // off a corner: diagonal to the edge
        assertEquals(Math.sqrt(2.0), BlastDistance.toBox(hull, half, Quaterniond(), 2.9, 1.0, 5.0), 1e-9)
    }

    @Test fun `the box rotation is honoured`() {
        // hull turned 90 degrees about y: its long axis now lies along x
        val turned = Quaterniond().rotateY(Math.toRadians(90.0))
        assertEquals(1.0, BlastDistance.toBox(hull, half, turned, 5.0, 1.0, 0.0), 1e-9)
        assertEquals(1.1, BlastDistance.toBox(hull, half, turned, 0.0, 1.0, 3.0), 1e-9)
    }

    @Test fun `a direct hit only removes its own damage from the blast`() {
        assertEquals(353.0, StruckVehicles.remainingBlast(353.0, null), 1e-9)
        // an AGM that penetrates for 120 still gets the rest of a 559 blast
        assertEquals(439.0, StruckVehicles.remainingBlast(559.0, 120.0), 1e-9)
        // a 2 % bounce does not cancel the blast
        assertEquals(170.0, StruckVehicles.remainingBlast(174.0, 4.0), 1e-9)
        // a hit already worth more than the blast: nothing more
        assertEquals(0.0, StruckVehicles.remainingBlast(31.0, 180.0), 1e-9)
        assertEquals(0.0, StruckVehicles.remainingBlast(500.0, Double.POSITIVE_INFINITY), 1e-9)
    }
}
