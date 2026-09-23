package com.atsuishio.superbwarfare.api.aircraft

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.items.ItemStackHandler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AircraftStoreAmmunitionTest {
    companion object {
        @BeforeAll @JvmStatic fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
    private fun magazine(count: Int) = ItemStackHandler(2).also {
        it.setStackInSlot(0, ItemStack(Items.ARROW, count))
    }
    @Test fun `accepted releases spend exactly one item and empty magazines never launch`() {
        val inventory = magazine(2)
        var launches = 0
        repeat(2) {
            assertTrue(AircraftStoreAmmunition.fire(inventory, Items.ARROW, false,
                { fail("Unexpected refund") }) { launches++; true })
        }
        assertTrue(inventory.getStackInSlot(0).isEmpty)
        assertThrows(IllegalArgumentException::class.java) {
            AircraftStoreAmmunition.fire(inventory, Items.ARROW, false, {}) { launches++; true }
        }
        assertEquals(2, launches)
    }
    @Test fun `rejected and exceptional releases restore inventory`() {
        val inventory = magazine(3)
        assertFalse(AircraftStoreAmmunition.fire(inventory, Items.ARROW, false, {}) { false })
        assertEquals(3, inventory.getStackInSlot(0).count)
        assertThrows(IllegalStateException::class.java) {
            AircraftStoreAmmunition.fire(inventory, Items.ARROW, false, {}) { error("Rejected spawn") }
        }
        assertEquals(3, inventory.getStackInSlot(0).count)
    }
    @Test fun `refund survives a launch hook replacing the source slot`() {
        val inventory = magazine(1)
        assertFalse(AircraftStoreAmmunition.fire(inventory, Items.ARROW, false,
            { fail("Second slot should accept refund") }) {
            inventory.setStackInSlot(0, ItemStack(Items.STONE, 64)); false
        })
        assertTrue(inventory.getStackInSlot(1).`is`(Items.ARROW))
        assertEquals(1, inventory.getStackInSlot(1).count)
    }
    @Test fun `creative and legacy stores preserve existing inventory exemption`() {
        val inventory = magazine(0)
        assertTrue(AircraftStoreAmmunition.fire(inventory, Items.ARROW, true, {}) { true })
        assertTrue(AircraftStoreAmmunition.fire(inventory, null, false, {}) { true })
    }
}
