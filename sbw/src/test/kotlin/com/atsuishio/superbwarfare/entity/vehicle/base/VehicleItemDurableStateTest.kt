package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.nbt.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Exercises the production encode/restore projection; does not simulate boarding or claim game execution. */
class VehicleItemDurableStateTest {
    private val marker = VehicleItemDurableState.INITIAL_BOARDING_APPLIED
    private fun savedWeapons() = CompoundTag().apply {
        put("WeaponState", CompoundTag().apply {
            put("Cannon", CompoundTag().apply { putInt("Ammo", 73); putInt("ReloadTick", 19) })
        })
        putFloat("Health", 120F)
        putIntArray("SelectedWeapon", intArrayOf(0, -1))
        putString("UUID", "must not enter item")
        putString("Unknown", "must not enter item")
    }

    @Test fun `spent weapon state and marker survive repeated recovery with detached copies`() {
        val world = savedWeapons().apply { putBoolean(marker, true) }
        val before = world.copy()
        var item = VehicleItemDurableState.project(world)
        repeat(3) {
            val restored = VehicleItemDurableState.forRestore(item, true)
            assertTrue(restored.getBoolean(marker))
            assertEquals(before.get("WeaponState"), restored.get("WeaponState"))
            assertArrayEquals(intArrayOf(0, -1), restored.getIntArray("SelectedWeapon"))
            assertFalse(restored.contains("UUID")); assertFalse(restored.contains("Unknown"))
            item = VehicleItemDurableState.project(restored)
        }
        item.getCompound("WeaponState").getCompound("Cannon").putInt("Ammo", 0)
        assertEquals(before, world)
    }

    @Test fun `old recovered aircraft suppress initial fill without touching ammo or reload`() {
        val legacy = savedWeapons()
        val before = legacy.copy()
        val restored = VehicleItemDurableState.forRestore(legacy, true)
        assertTrue(restored.getBoolean(marker))
        assertEquals(before.get("WeaponState"), restored.get("WeaponState"))
        assertEquals(before, legacy)
        assertEquals(restored, VehicleItemDurableState.forRestore(VehicleItemDurableState.project(restored), true))
    }

    @Test fun `missing weapon compound on an old durable payload cannot request fresh aircraft ammo`() {
        val oldItem = savedWeapons().apply { remove("WeaponState") }
        val restored = VehicleItemDurableState.forRestore(oldItem, true)
        assertTrue(restored.getBoolean(marker))
        assertFalse(restored.contains("WeaponState"))
    }

    @Test fun `explicit never boarded state survives while ground compatibility stays unchanged`() {
        val neverBoarded = savedWeapons().apply { putBoolean(marker, false) }
        val restored = VehicleItemDurableState.forRestore(VehicleItemDurableState.project(neverBoarded), true)
        assertTrue(restored.contains(marker)); assertFalse(restored.getBoolean(marker))
        val ground = savedWeapons()
        assertFalse(VehicleItemDurableState.forRestore(ground, false).contains(marker))
        assertEquals(ground.get("WeaponState"), VehicleItemDurableState.forRestore(ground, false).get("WeaponState"))
    }

    @Test fun `boarding marker accepts only absent or canonical byte booleans`() {
        assertTrue(VehicleItemDurableState.validBoardingMarker(CompoundTag()))
        for (value in listOf(false, true)) {
            assertTrue(VehicleItemDurableState.validBoardingMarker(CompoundTag().apply { putBoolean(marker, value) }))
        }
        for (bad in listOf<Tag>(IntTag.valueOf(1), StringTag.valueOf("true"), CompoundTag(),
                ByteTag.valueOf((-1).toByte()), ByteTag.valueOf(2.toByte()), IntArrayTag(intArrayOf(1)))) {
            assertFalse(VehicleItemDurableState.validBoardingMarker(CompoundTag().apply { put(marker, bad) }))
        }
    }
}
