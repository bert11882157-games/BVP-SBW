package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.WeakHashMap

class FarVehicleCopyRegistryTest {
    private class EntityKey(var id: Int) {
        override fun equals(other: Any?): Boolean = other is EntityKey && other.id == id
        override fun hashCode(): Int = id
    }

    private fun pose(z: Double): VehicleChassisPresentation {
        val anchor = Vec3(0.0, 64.0, z)
        return VehicleChassisPresentation(VehiclePoseSnapshot.IDENTITY.withChassisSample(anchor, 0F),
            anchor, 0F, 0.0, 0F, 0, 0, 1, VehicleChassisPresentation.Mode.LEGACY)
    }

    @Test fun `equal numeric IDs reproduce the old alias but never share identity membership`() {
        val copy = EntityKey(55)
        val real = EntityKey(55)
        assertEquals(copy, real)
        assertNotSame(copy, real)
        val oldRegistry = WeakHashMap<EntityKey, String>()
        oldRegistry[copy] = "copy frame"
        assertTrue(oldRegistry.containsKey(real))
        assertEquals("copy frame", oldRegistry[real])

        val registry = FarVehicleCopyRegistry<EntityKey, String>(256)
        registry.register(copy)
        assertTrue(registry.contains(copy))
        assertNull(registry[copy])
        registry.update(copy, "copy frame")
        assertFalse(registry.contains(real))
        assertNull(registry[real])
        assertEquals("copy frame", registry[copy])
    }

    @Test fun `equal-ID replacement and removal operate on the exact registered object`() {
        val oldCopy = EntityKey(55)
        val replacement = EntityKey(55)
        val real = EntityKey(55)
        val registry = FarVehicleCopyRegistry<EntityKey, String>(256)
        registry.register(oldCopy)
        registry.update(oldCopy, "old")
        registry.register(replacement)
        registry.update(replacement, "new")
        assertEquals(2, registry.size())
        registry.remove(real)
        assertEquals(2, registry.size())
        registry.remove(oldCopy)
        assertFalse(registry.contains(oldCopy))
        assertEquals("new", registry[replacement])
        registry.clear()
        assertEquals(0, registry.size())
        assertFalse(registry.contains(replacement))
        assertNull(registry[replacement])
    }

    @Test fun `mutable entity IDs cannot lose keys or acquire another object's frame`() {
        val copy = EntityKey(55)
        val registry = FarVehicleCopyRegistry<EntityKey, String>(256)
        registry.register(copy)
        registry.update(copy, "frame")
        copy.id = 56
        assertTrue(registry.contains(copy))
        assertEquals("frame", registry[copy])
        assertFalse(registry.contains(EntityKey(55)))
        assertFalse(registry.contains(EntityKey(56)))
        registry.remove(copy)
        assertEquals(0, registry.size())
    }

    @Test fun `capacity is hard bounded without eviction and cleanup permits later registration`() {
        val registry = FarVehicleCopyRegistry<EntityKey, String>(FarVehicleStore.MAX_VEHICLES)
        val copies = List(FarVehicleStore.MAX_VEHICLES) { EntityKey(55) }
        for ((index, copy) in copies.withIndex()) {
            registry.register(copy)
            registry.update(copy, "frame $index")
        }
        assertEquals(256, registry.size())
        val excess = EntityKey(55)
        assertThrows(IllegalStateException::class.java) { registry.register(excess) }
        assertFalse(registry.contains(excess))
        assertEquals("frame 0", registry[copies[0]])
        assertEquals("frame 255", registry[copies[255]])
        registry.register(copies[0])
        assertEquals(256, registry.size())
        assertNull(registry[copies[0]])
        registry.remove(copies[0])
        registry.register(excess)
        assertEquals(256, registry.size())
        registry.clear()
        assertEquals(0, registry.size())
        assertThrows(IllegalArgumentException::class.java) { FarVehicleCopyRegistry<EntityKey, String>(0) }
    }

    @Test fun `late updates cannot revive a removed or unregistered equal-ID object`() {
        val registry = FarVehicleCopyRegistry<EntityKey, String>(256)
        val copy = EntityKey(55)
        val real = EntityKey(55)
        registry.register(copy)
        registry.update(copy, "accepted")
        assertThrows(IllegalStateException::class.java) { registry.update(real, "wrong object") }
        assertEquals("accepted", registry[copy])
        registry.remove(copy)
        assertThrows(IllegalStateException::class.java) { registry.update(copy, "late frame") }
        assertEquals(0, registry.size())
    }

    @Test fun `native before far-pass cleanup keeps motion and the existing teleport bound`() {
        assertEquals(8.6, replayInbound(0.0, 10.0, 0.5, 9.3, 1.0, 1.8), 1e-9)
        // An unexplained displacement remains a discontinuity, not a registry-provided hold.
        assertEquals(-10.0, replayInbound(0.0, 10.0, 0.5, 9.3, 1.0, -10.0), 1e-9)
    }

    fun replayInbound(firstTick: Double, firstZ: Double, lastTick: Double, lastZ: Double,
                      nativeTick: Double, nativeZ: Double): Double {
        val copy = EntityKey(55)
        val real = EntityKey(55)
        val registry = FarVehicleCopyRegistry<EntityKey, VehicleChassisPresentation>(256)
        val handoff = FarVehicleHandoff()
        val id = UUID(0, 55)
        registry.register(copy)
        registry.update(copy, pose(firstZ))
        handoff.sample(id, "55:vehicle", registry.contains(copy), firstTick, registry[copy]!!)
        registry.update(copy, pose(lastZ))
        handoff.sample(id, "55:vehicle", registry.contains(copy), lastTick, registry[copy]!!)

        // Normal rendering can run before AFTER_ENTITIES has removed the old cosmetic object.
        assertTrue(registry.contains(copy))
        assertFalse(registry.contains(real))
        assertNull(registry[real])
        val authority = pose(nativeZ)
        val rendered = handoff.sample(id, "55:vehicle", registry.contains(real), nativeTick,
            registry[real] ?: authority)
        assertEquals(nativeZ, authority.anchor.z)
        assertEquals(lastZ, registry[copy]!!.anchor.z)
        registry.remove(copy)
        assertEquals(0, registry.size())
        assertNull(registry[real])
        return rendered.anchor.z
    }
}
