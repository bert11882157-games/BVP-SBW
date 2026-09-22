package com.atsuishio.superbwarfare.client.input

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.atsuishio.superbwarfare.client.input.VehicleWeaponSelectionGesture.Action.*

class VehicleWeaponSelectionGestureTest {
    private val start = 10_000_000_000L
    private val threshold = VehicleWeaponSelectionGesture.HOLD_NANOS

    @Test fun `tap before threshold selects primary two only on release`() {
        val gesture = VehicleWeaponSelectionGesture()
        gesture.press(start)
        assertEquals(NONE, gesture.advance(start + threshold - 1))
        assertEquals(SELECT_PRIMARY_TWO, gesture.release(start + threshold - 1))
        assertNull(gesture.progress(start + threshold))
        assertEquals(NONE, gesture.release(start + threshold))
    }

    @Test fun `exact 400 millisecond boundary cycles secondary once and never selects primary`() {
        val gesture = VehicleWeaponSelectionGesture()
        gesture.press(start)
        assertEquals(NONE, gesture.advance(start + 399_000_000L))
        assertEquals(CYCLE_SECONDARY, gesture.advance(start + 400_000_000L))
        assertEquals(1f, gesture.progress(start + threshold))
        assertEquals(NONE, gesture.advance(start + 10 * threshold))
        assertEquals(NONE, gesture.release(start + 10 * threshold))
    }

    @Test fun `100 millisecond feedback boundary does not delay the 400 millisecond hold`() {
        val gesture = VehicleWeaponSelectionGesture()
        assertEquals(400L, VehicleWeaponSelectionGesture.HOLD_MILLIS)
        assertEquals(100L, VehicleWeaponSelectionGesture.FEEDBACK_DELAY_MILLIS)
        assertEquals(400_000_000L, threshold)
        assertEquals(100_000_000L, VehicleWeaponSelectionGesture.FEEDBACK_DELAY_NANOS)
        assertNull(VehicleWeaponSelectionGesture.feedbackProgress(gesture.progress(start)))
        gesture.press(start)
        assertNull(VehicleWeaponSelectionGesture.feedbackProgress(gesture.progress(start + 99_000_000L)))
        assertEquals(NONE, gesture.advance(start + 100_000_000L))
        assertEquals(0.25f, gesture.progress(start + 100_000_000L))
        assertEquals(0f, VehicleWeaponSelectionGesture.feedbackProgress(gesture.progress(start + 100_000_000L)))
        assertEquals(299f / 300f,
            VehicleWeaponSelectionGesture.feedbackProgress(gesture.progress(start + 399_000_000L))!!, 0.000001f)
        // A HUD read reaching1 is not a selection event; the input owner still commits it once.
        assertEquals(1f, VehicleWeaponSelectionGesture.feedbackProgress(gesture.progress(start + threshold)))
        assertEquals(CYCLE_SECONDARY, gesture.advance(start + threshold))
    }

    @Test fun `release at 100 or 399 milliseconds remains a primary tap`() {
        for (elapsed in listOf(100_000_000L, 399_000_000L)) {
            val gesture = VehicleWeaponSelectionGesture()
            gesture.press(start)
            assertEquals(SELECT_PRIMARY_TWO, gesture.release(start + elapsed))
            assertNull(gesture.progress(start + threshold))
            assertEquals(NONE, gesture.advance(start + threshold))
        }
    }

    @Test fun `feedback handles invalid values and disappears when a visible hold is canceled`() {
        assertNull(VehicleWeaponSelectionGesture.feedbackProgress(Float.NaN))
        assertNull(VehicleWeaponSelectionGesture.feedbackProgress(Float.POSITIVE_INFINITY))
        assertNull(VehicleWeaponSelectionGesture.feedbackProgress(-1f))
        val gesture = VehicleWeaponSelectionGesture()
        gesture.press(start)
        assertNotNull(VehicleWeaponSelectionGesture.feedbackProgress(gesture.progress(start + 100_000_000L)))
        gesture.cancel()
        assertNull(VehicleWeaponSelectionGesture.feedbackProgress(gesture.progress(start + 399_000_000L)))
        assertEquals(NONE, gesture.release(start + threshold))
    }

    @Test fun `release at boundary catches a hold between ticks`() {
        val gesture = VehicleWeaponSelectionGesture()
        gesture.press(start)
        assertEquals(CYCLE_SECONDARY, gesture.release(start + threshold))
        assertEquals(NONE, gesture.advance(start + threshold + 1))
    }

    @Test fun `repeat press cannot restart timer or rearm a completed hold`() {
        val gesture = VehicleWeaponSelectionGesture()
        gesture.press(start)
        gesture.press(start + threshold / 2)
        assertEquals(CYCLE_SECONDARY, gesture.advance(start + threshold))
        gesture.press(start + 2 * threshold)
        assertEquals(NONE, gesture.advance(start + 3 * threshold))
        assertEquals(NONE, gesture.release(start + 3 * threshold))
        gesture.press(start + 4 * threshold)
        assertEquals(CYCLE_SECONDARY, gesture.advance(start + 5 * threshold))
    }

    @Test fun `cancel abandons pending tap and completed hold`() {
        for (completed in listOf(false, true)) {
            val gesture = VehicleWeaponSelectionGesture()
            gesture.press(start)
            if (completed) gesture.advance(start + threshold)
            gesture.cancel()
            assertNull(gesture.progress(start + 2 * threshold))
            assertEquals(NONE, gesture.release(start + 2 * threshold))
            assertEquals(NONE, gesture.advance(start + 3 * threshold))
        }
    }

    @Test fun `progress is read only and clamps at completion until release`() {
        val gesture = VehicleWeaponSelectionGesture()
        assertNull(gesture.progress(start))
        gesture.press(start)
        assertEquals(0f, gesture.progress(start))
        assertEquals(0.5f, gesture.progress(start + threshold / 2))
        assertEquals(1f, gesture.progress(start + threshold * 2))
        assertEquals(CYCLE_SECONDARY, gesture.advance(start + threshold * 2))
    }

    @Test fun `backward clock cancels without selecting either slot`() {
        val gesture = VehicleWeaponSelectionGesture()
        gesture.press(start)
        assertEquals(NONE, gesture.release(start - 1))
        assertNull(gesture.progress(start))
    }

    @Test fun `remapping keyboard mouse or modifier cancels old press and new binding can arm`() {
        data class Binding(val device: String, val code: Int, val modifier: String)
        val original = Binding("KEYSYM", 50, "NONE")
        for (remapped in listOf(Binding("KEYSYM", 71, "NONE"),
            Binding("MOUSE", 4, "NONE"), Binding("SCANCODE", 50, "NONE"),
            Binding("KEYSYM", 50, "CONTROL"))) {
            val gesture = VehicleWeaponSelectionGesture()
            gesture.bind(original)
            gesture.press(start)
            gesture.bind(original.copy())
            assertEquals(0.5f, gesture.progress(start + threshold / 2))
            gesture.bind(remapped)
            assertNull(gesture.progress(start + threshold))
            assertEquals(NONE, gesture.release(start + threshold))
            gesture.press(start + threshold)
            assertEquals(CYCLE_SECONDARY, gesture.advance(start + 2 * threshold))
        }
    }
}
