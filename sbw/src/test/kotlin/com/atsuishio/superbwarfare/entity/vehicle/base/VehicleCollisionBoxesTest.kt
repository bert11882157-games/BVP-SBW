package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo
import com.atsuishio.superbwarfare.tools.OBB
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleCollisionBoxesTest {
    @Test
    fun `tracked mode omits both track contacts and preserves hull engine and wheeled contacts`() {
        val hull = OBBInfo()
        val engine = OBBInfo().apply { part = OBB.Part.MAIN_ENGINE }
        val left = OBBInfo().apply { part = OBB.Part.WHEEL_LEFT }
        val right = OBBInfo().apply { part = OBB.Part.WHEEL_RIGHT }
        val boxes = listOf(hull, engine, left, right)
        val cache = VehicleCollisionBoxes()
        assertEquals(boxes.map { it.getOBB() }, cache.select(boxes, false, 0F))
        val tracked = cache.select(boxes, false, 0F, true)
        assertEquals(listOf(hull.getOBB(), engine.getOBB()), tracked)
        assertSame(tracked, cache.select(boxes, false, 1F, true))
        assertEquals(boxes.map { it.getOBB() }, cache.select(boxes, false, 0F, false))
    }

    @Test
    fun `gear flag defaults off and round trips through the actual serializer`() {
        assertFalse(Json.decodeFromString(OBBInfo.serializer(), "{}").landingGear)
        val explicit = Json.decodeFromString(OBBInfo.serializer(), """{"LandingGear":true}""")
        assertTrue(explicit.landingGear)
        assertTrue(Json.decodeFromString(OBBInfo.serializer(),
            Json.encodeToString(OBBInfo.serializer(), explicit)).landingGear)
        assertThrows(Exception::class.java) {
            Json.decodeFromString(OBBInfo.serializer(), """{"LandingGear":2}""")
        }
    }

    @Test
    fun `partial and invalid gear stay solid and legacy geometry is unchanged`() {
        val hull = OBBInfo()
        val gear = OBBInfo().apply { landingGear = true }
        val boxes = listOf(hull, gear)
        val cache = VehicleCollisionBoxes()
        for (fraction in listOf(-1F, 0F, 0.05F, 0.5F, Math.nextDown(1F), 1F,
            Math.nextUp(1F), Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(listOf(hull.getOBB(), gear.getOBB()), cache.select(boxes, false, fraction))
            val expected = if (fraction == 1F) listOf(hull.getOBB())
                else listOf(hull.getOBB(), gear.getOBB())
            assertEquals(expected, cache.select(boxes, true, fraction))
        }
    }

    @Test
    fun `selection restores identical volumes and tracks current pose without reallocating`() {
        val hull = OBBInfo()
        val gear = OBBInfo().apply { landingGear = true }
        val source = listOf(hull, gear)
        val cache = VehicleCollisionBoxes()
        val extended = cache.select(source, true, 0F)
        assertSame(extended, cache.select(source, true, 0.9F))
        val hidden = cache.select(source, true, 1F)
        assertSame(hidden, cache.select(source, true, 1F))
        hull.getOBB().center.set(5.0, 10.0, -5.0)
        assertEquals(10.0, hidden.single().center.y)
        val restored = cache.select(source, true, 0.95F)
        assertEquals(extended, restored)
        assertSame(gear.getOBB(), restored[1])
        assertEquals(extended, cache.select(source, false, 1F))
        val replacement = OBBInfo()
        assertEquals(listOf(replacement.getOBB()), cache.select(listOf(replacement), true, 1F))
        assertTrue(cache.select(emptyList(), true, 0F).isEmpty())
    }
}
