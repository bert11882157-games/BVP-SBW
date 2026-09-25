package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.aircraft.AircraftLoadoutCost.Rack
import com.google.gson.JsonParser
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.items.ItemStackHandler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AircraftLoadoutCostTest {
    companion object {
        @BeforeAll @JvmStatic fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
        const val AAM = "superbwarfare:small_air_to_air_missile"
        const val BOMB = "superbwarfare:small_freefall_bomb"
    }
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun plan(old: Map<String, Rack>, new: Map<String, Rack>, exempt: Boolean = false) =
        AircraftLoadoutCost.plan(old, new, exempt)
    private val names = mapOf<Item, String>(Items.ARROW to "Arrow", Items.STICK to "Stick")

    @Test fun `only munitions released from the rack are bought`() {
        val ammo = """"AmmoItem":"$AAM""""
        assertEquals(AAM, AircraftLoadoutCost.ammoId(json("""{"Category":"LASER_GUIDED",$ammo}""")))
        assertEquals(AAM, AircraftLoadoutCost.ammoId(json("""{"Category":"COMMAND_GUIDED",$ammo}""")))
        assertEquals(AAM, AircraftLoadoutCost.ammoId(json("""{"Category":"AIR_TO_AIR","Guidance":{},$ammo}""")))
        assertEquals(AAM, AircraftLoadoutCost.ammoId(json("""{"Category":"BOMB","Bomb":{},$ammo}""")))
        assertEquals(AAM, AircraftLoadoutCost.ammoId(json("""{"Category":"CRUISE","Flight":{},$ammo}""")))
        // Visual modeled missiles, pods (native reload) and fixtures without AmmoItem stay free.
        assertNull(AircraftLoadoutCost.ammoId(json("""{"Category":"AIR_TO_AIR",$ammo}""")))
        assertNull(AircraftLoadoutCost.ammoId(json("""{"Category":"BOMB",$ammo}""")))
        assertNull(AircraftLoadoutCost.ammoId(json("""{"Category":"ROCKET_POD",$ammo}""")))
        assertNull(AircraftLoadoutCost.ammoId(json("""{"Category":"GUN_POD"}""")))
        assertNull(AircraftLoadoutCost.ammoId(json("""{"Category":"VISUAL_ONLY",$ammo}""")))
        assertNull(AircraftLoadoutCost.ammoId(json("""{"Category":"LASER_GUIDED"}""")))
    }

    @Test fun `rack sizes count pairs singles fixed racks and bay copies`() {
        val pair = json("""{"Id":"pair","Left":[-3,0,0],"Right":[3,0,0]}""")
        val single = json("""{"Id":"centre","Position":[0,-1,0]}""")
        assertEquals(2, AircraftLoadoutCost.rounds(1, 2, 1))
        assertEquals(1, AircraftLoadoutCost.rounds(1, 1, 1))
        assertEquals(AircraftArmamentRegistry.mountCapacity(pair, 1) * 3, AircraftLoadoutCost.rounds(1, 2, 3))
        assertEquals(AircraftArmamentRegistry.mountCapacity(single, 2) * 4, AircraftLoadoutCost.rounds(2, 1, 4))
        assertEquals(6, AircraftLoadoutCost.rounds(1, 2, 3))
    }

    @Test fun `swapping stores refunds the old rack and pays for the new one`() {
        val result = plan(mapOf("outer" to Rack("aim9", AAM, 2)), mapOf("outer" to Rack("mk82", BOMB, 2)))
        assertEquals(mapOf(AAM to -2, BOMB to 2), result.net)
        assertEquals(mapOf(BOMB to 2), result.charges)
        assertEquals(mapOf(AAM to 2), result.refunds)
        assertEquals(2, result.paid("outer"))
    }

    @Test fun `refitting the same store pays only for fired munitions`() {
        val partial = plan(mapOf("outer" to Rack("aim9", AAM, 1)), mapOf("outer" to Rack("aim9", AAM, 2)))
        assertEquals(mapOf(AAM to 1), partial.net)
        assertEquals(2, partial.paid("outer"))
        val full = plan(mapOf("outer" to Rack("aim9", AAM, 2)), mapOf("outer" to Rack("aim9", AAM, 2)))
        assertTrue(full.net.isEmpty())
        assertEquals(2, full.paid("outer"))
        val empty = plan(mapOf("outer" to Rack("aim9", AAM, 0)), mapOf("outer" to Rack("aim9", AAM, 2)))
        assertEquals(mapOf(AAM to 2), empty.net)
    }

    @Test fun `emptying a hardpoint refunds what is still on the rack`() {
        val result = plan(mapOf("bay" to Rack("mk82", BOMB, 3)), emptyMap())
        assertEquals(mapOf(BOMB to -3), result.net)
        assertEquals(0, result.paid("bay"))
    }

    @Test fun `bay quantity changes pay or refund the difference`() {
        val more = plan(mapOf("bay" to Rack("mk82", BOMB, 4)), mapOf("bay" to Rack("mk82", BOMB, 6)))
        assertEquals(mapOf(BOMB to 2), more.net)
        assertEquals(6, more.paid("bay"))
        val fewer = plan(mapOf("bay" to Rack("mk82", BOMB, 4)), mapOf("bay" to Rack("mk82", BOMB, 2)))
        assertEquals(mapOf(BOMB to -2), fewer.net)
        assertEquals(2, fewer.paid("bay"))
    }

    @Test fun `moving a munition between hardpoints costs nothing and touches every station`() {
        val result = plan(mapOf("left" to Rack("aim9", AAM, 2), "wing" to Rack("mk82", BOMB, 1)),
            mapOf("right" to Rack("aim9", AAM, 2), "wing" to Rack("mk82", BOMB, 2)))
        // AAMs net out; the untouched but fired bomb station is rearmed and pays for its missing round.
        assertEquals(mapOf(BOMB to 1), result.net)
        assertEquals(2, result.paid("right"))
        assertEquals(0, result.paid("left"))
    }

    @Test fun `detached stations are neither refunded nor charged`() {
        val pair = json("""{"Id":"pair","Left":[-3,0,0],"Right":[3,0,0]}""")
        val store = json("""{"Category":"AIR_TO_GROUND","Capacity":1,"RackSpacing":[0.5,0.4,0]}""")
        val capacity = AircraftArmamentRegistry.mountCapacity(pair, 1) * 2
        val leftWingLost = { round: Int -> AircraftPylonRacks.launchPosition(pair, store, 2, round).x < 0 }
        assertEquals(4, AircraftLoadoutCost.attached(capacity, 0) { false })
        assertEquals(2, AircraftLoadoutCost.attached(capacity, 0, leftWingLost))
        assertEquals(1, AircraftLoadoutCost.attached(capacity, 2, leftWingLost))
        assertEquals(0, AircraftLoadoutCost.attached(capacity, 9, leftWingLost))
        val live = AircraftLoadoutCost.attached(capacity, 0, leftWingLost)
        assertEquals(mapOf(AAM to -2), plan(mapOf("pair" to Rack("agm", AAM, live)), emptyMap()).net)
        assertTrue(plan(mapOf("pair" to Rack("agm", AAM, live)), mapOf("pair" to Rack("agm", AAM, live))).net.isEmpty())
    }

    @Test fun `unpaid munitions from old or creative fits are never refunded`() {
        assertTrue(plan(mapOf("outer" to Rack("aim9", AAM, 2, paid = 0)), emptyMap()).net.isEmpty())
        val topUp = plan(mapOf("outer" to Rack("aim9", AAM, 1, paid = 0)), mapOf("outer" to Rack("aim9", AAM, 2)))
        assertEquals(mapOf(AAM to 1), topUp.net)
        assertEquals(1, topUp.paid("outer"))
        // A smaller bay load takes unpaid munitions off first.
        val fewer = plan(mapOf("bay" to Rack("mk82", BOMB, 4, paid = 1)), mapOf("bay" to Rack("mk82", BOMB, 2)))
        assertTrue(fewer.net.isEmpty())
        assertEquals(1, fewer.paid("bay"))
        val lessStill = plan(mapOf("bay" to Rack("mk82", BOMB, 4, paid = 3)), mapOf("bay" to Rack("mk82", BOMB, 1)))
        assertEquals(mapOf(BOMB to -2), lessStill.net)
        assertEquals(1, lessStill.paid("bay"))
        assertThrows(IllegalArgumentException::class.java) { Rack("aim9", AAM, 1, paid = 2) }
    }

    @Test fun `creative mode or a creative ammo box fits without paying or refunding`() {
        assertTrue(AircraftLoadoutCost.exempt(true, false, false))
        assertTrue(AircraftLoadoutCost.exempt(false, true, false))
        assertTrue(AircraftLoadoutCost.exempt(false, false, true))
        assertFalse(AircraftLoadoutCost.exempt(false, false, false))
        val swap = plan(mapOf("outer" to Rack("aim9", AAM, 2)), mapOf("outer" to Rack("mk82", BOMB, 2)), exempt = true)
        assertTrue(swap.net.isEmpty())
        assertEquals(0, swap.paid("outer"))
        val refill = plan(mapOf("outer" to Rack("aim9", AAM, 1, paid = 1)), mapOf("outer" to Rack("aim9", AAM, 2)), exempt = true)
        assertTrue(refill.net.isEmpty())
        assertEquals(1, refill.paid("outer"))
    }

    @Test fun `free stores cost nothing`() {
        val result = plan(mapOf("outer" to Rack("pod", null, 20)), mapOf("outer" to Rack("gsh23", null, 200)))
        assertTrue(result.net.isEmpty())
        assertEquals(0, result.paid("outer"))
    }

    @Test fun `settlement is all or nothing and names what is missing`() {
        val inventory = ItemStackHandler(3)
        inventory.setStackInSlot(0, ItemStack(Items.ARROW, 1))
        inventory.setStackInSlot(1, ItemStack(Items.STICK, 5))
        val error = assertThrows(IllegalArgumentException::class.java) {
            AircraftLoadoutCost.settle(inventory, mapOf(Items.STICK to 2, Items.ARROW to 2), { names.getValue(it) }) { _, _ ->
                fail("Nothing may be refunded when the edit is rejected")
            }
        }
        assertEquals("Need 2 × Arrow (have 1)", error.message)
        assertEquals(1, inventory.getStackInSlot(0).count)
        assertEquals(5, inventory.getStackInSlot(1).count)
        assertEquals(listOf(AircraftLoadoutCost.Shortfall(AAM, 2, 1)),
            AircraftLoadoutCost.shortfalls(mapOf(AAM to 2, BOMB to -4)) { 1 })
    }

    @Test fun `settlement takes charges across stacks and hands refunds back`() {
        val inventory = ItemStackHandler(3)
        inventory.setStackInSlot(0, ItemStack(Items.ARROW, 1))
        inventory.setStackInSlot(2, ItemStack(Items.ARROW, 4))
        val given = mutableListOf<Pair<Item, Int>>()
        AircraftLoadoutCost.settle(inventory, mapOf(Items.ARROW to 3, Items.STICK to -2), { names.getValue(it) }) { item, count ->
            given += item to count
        }
        assertEquals(2, AircraftLoadoutCost.count(inventory, Items.ARROW))
        assertEquals(listOf(Items.STICK to 2), given)
    }

    @Test fun `settlement restores what it took when the inventory refuses a counted stack`() {
        val inventory = object : ItemStackHandler(2) {
            override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack =
                if (slot == 1) ItemStack.EMPTY else super.extractItem(slot, amount, simulate)
        }
        inventory.setStackInSlot(0, ItemStack(Items.STICK, 1))
        inventory.setStackInSlot(1, ItemStack(Items.ARROW, 2))
        val given = mutableListOf<Pair<Item, Int>>()
        assertThrows(IllegalArgumentException::class.java) {
            AircraftLoadoutCost.settle(inventory, linkedMapOf(Items.STICK to 1, Items.ARROW to 2), { names.getValue(it) }) { item, count ->
                given += item to count
            }
        }
        assertEquals(listOf(Items.STICK to 1), given)
    }

    @Test fun `status text matches the pylon menu wording`() {
        val name = { id: String -> if (id == AAM) "Small Air-to-Air Missile" else "Small Aerial Bomb" }
        assertEquals("Need 2 × Small Air-to-Air Missile (have 1)",
            AircraftLoadoutCost.describe(listOf(AircraftLoadoutCost.Shortfall(AAM, 2, 1)), name))
        assertEquals("Cost 2 × Small Air-to-Air Missile · Refund 1 × Small Aerial Bomb",
            AircraftLoadoutCost.summary(mapOf(AAM to 2, BOMB to -1), name))
        assertEquals("No ammunition change", AircraftLoadoutCost.summary(emptyMap(), name))
    }
}
