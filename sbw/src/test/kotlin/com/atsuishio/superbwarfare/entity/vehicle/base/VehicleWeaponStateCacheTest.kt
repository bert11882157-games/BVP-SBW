package com.atsuishio.superbwarfare.entity.vehicle.base

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleWeaponStateCacheTest {
    @Test fun `identical owners reuse live state and equal replacement snapshots rebuild it`() {
        val cache = VehicleWeaponStateCache<String>()
        val snapshot = mapOf("cannon" to "saved")
        val configuration = Any()
        val live = cache.resolve(snapshot, configuration) { mapOf("cannon" to "live") }
        assertSame(live, cache.resolve(snapshot, configuration) { error("must reuse live state") })
        val replacement = LinkedHashMap(snapshot)
        assertEquals(snapshot, replacement)
        assertNotSame(live, cache.resolve(replacement, configuration) { mapOf("cannon" to "reloaded") })
    }

    @Test fun `publication retains live state while configuration replacement invalidates it`() {
        val cache = VehicleWeaponStateCache<String>()
        val configuration = Any()
        val live = cache.resolve(emptyMap(), configuration) { mapOf("coax" to "live") }
        val published = mapOf("coax" to "copy")
        cache.published(published)
        assertSame(live, cache.resolve(published, configuration) { error("publication is not a reload") })
        val updated = cache.resolve(published, Any()) { mapOf("coax" to "new configuration") }
        assertNotSame(live, updated)
    }

    @Test fun `failed materialization cannot partially replace the previous binding`() {
        val cache = VehicleWeaponStateCache<String>()
        val configuration = Any()
        val snapshot = mapOf("coax" to "saved")
        val live = cache.resolve(snapshot, configuration) { mapOf("coax" to "live") }
        assertThrows(IllegalStateException::class.java) {
            cache.resolve(mapOf("other" to "state"), Any()) { error("invalid configuration") }
        }
        assertSame(live, cache.resolve(snapshot, configuration) { error("prior binding must survive") })
    }

    @Test fun `explicit invalidation and clearing rebuild even unchanged owner identities`() {
        val cache = VehicleWeaponStateCache<String>()
        val configuration = Any()
        val snapshot = mapOf("coax" to "saved")
        var builds = 0
        fun resolve() = cache.resolve(snapshot, configuration) { builds++; snapshot }
        resolve()
        cache.invalidateConfiguration()
        resolve()
        cache.clear()
        resolve()
        assertEquals(3, builds)
    }
}
