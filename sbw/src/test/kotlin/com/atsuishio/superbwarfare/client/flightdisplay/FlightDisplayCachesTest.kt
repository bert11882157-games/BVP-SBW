package com.atsuishio.superbwarfare.client.flightdisplay

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FlightDisplayCachesTest {
    private data class Key(val value: Int)
    private data class Target(val key: Key, val slot: Int, val serial: Int)

    @Test fun fitsMemoizeRejectionsAndUseIdentityUntilReset() {
        var calls = 0
        val cache = IdentityFitCache<Key, Int> { calls++; if (it.value < 0) null else it.value }
        val valid = Key(3); val invalid = Key(-1)
        assertEquals(3, cache.get(valid)); assertEquals(3, cache.get(valid))
        assertNull(cache.get(invalid)); assertNull(cache.get(invalid))
        assertEquals(2, calls)
        assertEquals(3, cache.get(Key(3))) // equal value, distinct resource identity
        assertEquals(3, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get(invalid)); assertEquals(4, calls)
    }

    @Test fun seventeenthSequentialPictureReusesStableSlotAndCanReturn() {
        var serial = 0
        val destroyed = mutableListOf<Target>()
        val cache = HudStillSlots<Key, Target>(16, { k, slot -> Target(k, slot, serial++) }, destroyed::add)
        val keys = List(17) { Key(it) }
        cache.beginFrame()
        val originals = keys.take(16).map { checkNotNull(cache.prepare(it)) }
        assertNull(cache.prepare(keys[16])) // all current preparation leases protected
        cache.endPreparation()
        originals.forEach { assertSame(it, cache.lease(it.key)) }
        cache.beginFrame()
        val newest = checkNotNull(cache.prepare(keys[16]))
        cache.endPreparation()
        assertEquals(16, cache.size)
        assertEquals(listOf(originals[0]), destroyed)
        assertEquals(originals[0].slot, newest.slot)
        assertSame(newest, cache.lease(keys[16]))
        cache.beginFrame()
        val returned = checkNotNull(cache.prepare(keys[0]))
        cache.endPreparation()
        assertNotEquals(originals[0].serial, returned.serial)
        assertEquals(16, cache.size)
        assertEquals(16, (keys.mapNotNull { cache.lease(it) }.map { it.slot }).toSet().size)
        cache.clear(); cache.clear()
        assertEquals(serial, destroyed.size)
        assertEquals(serial, destroyed.map { it.serial }.toSet().size)
    }

    @Test fun worldEmissionCannotEvictLeasedTextures() {
        val destroyed = mutableListOf<Target>()
        val cache = HudStillSlots<Key, Target>(1, { k, slot -> Target(k, slot, 0) }, destroyed::add)
        val first = Key(1); val other = Key(2)
        cache.beginFrame(); val target = cache.prepare(first); cache.endPreparation()
        assertSame(target, cache.lease(first))
        assertThrows(IllegalStateException::class.java) { cache.prepare(other) }
        assertTrue(destroyed.isEmpty())
        cache.beginFrame()
        assertSame(target, cache.lease(first))
        assertNull(cache.prepare(other))
        cache.endPreparation()
        assertTrue(destroyed.isEmpty())
    }

    @Test fun failedCreationLeavesSlotReusableAndResetIsIdempotent() {
        var fail = true
        val destroyed = mutableListOf<Target>()
        val cache = HudStillSlots<Key, Target>(1, { k, slot ->
            if (fail) error("paint failed")
            Target(k, slot, 0)
        }, destroyed::add)
        cache.beginFrame()
        assertThrows(IllegalStateException::class.java) { cache.prepare(Key(1)) }
        assertEquals(0, cache.size)
        fail = false
        assertNotNull(cache.prepare(Key(2)))
        cache.endPreparation()
        cache.clear(); cache.clear()
        assertEquals(1, destroyed.size)
        assertEquals(0, cache.size)
    }
}
