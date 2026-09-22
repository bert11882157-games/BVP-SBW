package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleProfile
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduler
import com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason
import com.atsuishio.superbwarfare.api.weapon.ShotResult
import com.atsuishio.superbwarfare.api.weapon.ShotStatus
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftStoreGroupsTest {
    private fun store(category: String) = JsonObject().apply {
        addProperty("Name", category); addProperty("Category", category)
        if (category == "AIR_TO_AIR") add("Guidance", JsonObject())
    }
    private fun entry(id: String, mount: String, category: String, capacity: Int, ammo: Int,
                      channels: List<String> = emptyList()) = AircraftStoreWeapons.Equipped(
        id, store(category), AircraftStoreWeapons.Member(mount, channels, capacity, ammo))
    private fun accepted(channel: String) = ShotResult(ShotStatus.ACCEPTED, ShotRejectionReason.NONE,
        channel, null, null, null, emptyList())
    private fun trigger(automatic: Boolean) = VehicleWeaponScheduler.RuntimeState(VehicleWeaponScheduleProfile(
        ResourceLocation("test", "group"), 120, 120, 1, 1,
        repeatWhileHeld = automatic, releaseGraceTicks = 0,
    ))

    @Test fun `one cruise group advances individual bay targets only after accepted launch`() {
        val used = IntArray(8)
        val targets = (0..7).associateWith { "target-$it" }.toMutableMap()
        val captured = mutableListOf<String>()
        var lastCruise: Long? = null
        fun group() = AircraftStoreWeapons.collect((0..7).map { bay ->
            entry("test:kh55", "belly_${bay + 1}", "CRUISE_MISSILE", 1, 1 - used[bay])
        }) { null }.single()
        fun launch(tick: Long) {
            val member = group().next ?: throw IllegalArgumentException("Empty")
            val bay = member.mountId.removePrefix("belly_").toInt() - 1
            AircraftStoreLaunchTransaction.execute(used[bay], 1, null, tick, lastCruise,
                launch = { captured += targets[bay] ?: throw IllegalArgumentException("No target") },
                commit = { used[bay]++; targets.remove(bay); lastCruise = tick })
        }
        val id = group().weaponId
        assertEquals(8, group().ammo)
        assertEquals(8, group().capacity)
        val controls = trigger(false)
        controls.tick(0, true)
        assertTrue(controls.canAttempt())
        controls.consumeCredit(); launch(0); controls.recordAccepted(0)
        assertEquals("belly_2", group().next!!.mountId)
        assertEquals(7, group().ammo)
        for (tick in 1..40) {
            controls.tick(tick, true)
            assertFalse(controls.canAttempt(), "same group keeps the semi latch while its next bay changes")
        }
        // A rejected next bay cannot skip to a later bay whose target happens to exist.
        targets.remove(1)
        assertThrows(IllegalArgumentException::class.java) { launch(40) }
        assertEquals("belly_2", group().next!!.mountId)
        assertEquals(7, group().ammo)
        assertEquals(0L, lastCruise)
        assertEquals(listOf("target-0"), captured)
        targets[1] = "target-1"
        controls.releaseEdge(); controls.tick(41, true)
        assertTrue(controls.canAttempt())
        controls.consumeCredit(); launch(41); controls.recordAccepted(41)
        for (bay in 2..7) launch(41L + (bay - 1) * 10L)
        assertEquals((0..7).map { "target-$it" }, captured)
        assertTrue(targets.isEmpty())
        assertEquals(id, group().weaponId)
        assertEquals(0, group().ammo)
        assertEquals(8, group().capacity)
        assertNull(group().next)
    }

    @Test fun `different missile types stay separate and retain groups after depletion`() {
        val groups = AircraftStoreWeapons.collect(listOf(
            entry("test:laser", "left", "LASER_GUIDED", 2, 0),
            entry("test:aam", "tip_left", "AIR_TO_AIR", 1, 1),
            entry("test:laser", "right", "LASER_GUIDED", 2, 2),
            entry("test:aam", "tip_right", "AIR_TO_AIR", 1, 0),
            entry("test:gps", "body", "COORDINATE_MISSILE", 1, 0),
        )) { null }
        assertEquals(listOf("test:laser", "test:aam", "test:gps"), groups.map { it.storeId })
        assertEquals(listOf(2, 1, 0), groups.map { it.ammo })
        assertEquals(listOf(4, 2, 1), groups.map { it.capacity })
        assertEquals("right", groups[0].next!!.mountId)
        assertEquals("tip_left", groups[1].next!!.mountId)
        assertNull(groups[2].next)
        assertTrue(groups.all { it.virtual })
    }

    @Test fun `native types aggregate actual distinct feeds without counting mirrored mounts twice`() {
        for (category in listOf("GUN_POD", "ROCKET_POD", "BOMB")) {
            val groups = AircraftStoreWeapons.collect(listOf(
                entry("test:first", "left", category, 999, 999, listOf("left_feed", "shared")),
                entry("test:first", "right", category, 999, 999, listOf("right_feed", "shared")),
                entry("test:other", "other", category, 999, 999, listOf("other_feed")),
            )) { channel -> when (channel) {
                "left_feed" -> 20 to 0; "right_feed" -> 20 to 7
                "shared" -> 40 to 9; else -> 4 to 3
            } }
            assertEquals(2, groups.size)
            assertEquals(listOf("left_feed", "shared", "right_feed"), groups[0].nativeWeapons)
            assertEquals(80, groups[0].capacity)
            assertEquals(16, groups[0].ammo)
            assertFalse(groups[0].virtual)
            assertEquals(3, groups[1].ammo)
        }
    }

    @Test fun `native dispatch retains rejection and never advances to a second feed on failure`() {
        val ammo = mutableMapOf("left" to 1, "right" to 1)
        val group = AircraftStoreWeapons.collect(listOf(entry("test:bomb", "pair", "BOMB", 2, 2,
            listOf("left", "right")))) { 1 to ammo.getValue(it) }.single()
        val attempted = mutableListOf<String>()
        val failed = AircraftStoreWeapons.launchNative(group, ammo::getValue) {
            attempted += it
            ShotResult.rejected(ShotRejectionReason.PROJECTILE_CREATION_FAILED, it)
        }
        assertFalse(failed.isAccepted())
        assertEquals(ShotRejectionReason.PROJECTILE_CREATION_FAILED, failed.reason)
        assertEquals(listOf("left"), attempted)
        assertEquals(2, ammo.values.sum())
        repeat(2) {
            assertTrue(AircraftStoreWeapons.launchNative(group, ammo::getValue) { channel ->
                attempted += channel; ammo[channel] = ammo.getValue(channel) - 1; accepted(channel)
            }.isAccepted())
        }
        assertEquals(listOf("left", "left", "right"), attempted)
        assertFalse(AircraftStoreWeapons.launchNative(group, ammo::getValue) {
            fail<ShotResult>("empty native group must not dispatch")
        }.isAccepted())
    }

    @Test fun `native feed transitions preserve one click bombs and held rocket cadence`() {
        for (automatic in listOf(false, true)) {
            val ammo = mutableMapOf("left" to 1, "right" to 3)
            val group = AircraftStoreWeapons.collect(listOf(entry("test:store", "pair",
                if (automatic) "ROCKET_POD" else "BOMB", 4, 4, listOf("left", "right")))) {
                (if (it == "left") 1 else 3) to ammo.getValue(it)
            }.single()
            val state = trigger(automatic)
            val events = mutableListOf<Int>()
            fun tick(tick: Int) {
                state.tick(tick, true)
                if (!state.canAttempt()) return
                state.consumeCredit()
                val result = AircraftStoreWeapons.launchNative(group, ammo::getValue) { channel ->
                    events += tick; ammo[channel] = ammo.getValue(channel) - 1; accepted(channel)
                }
                if (result.isAccepted()) state.recordAccepted(tick)
            }
            for (tick in 0..29) tick(tick)
            // Preserve the native credit accumulator's tick rounding across feed changes.
            if (automatic) assertEquals(listOf(0, 11, 21), events)
            else {
                assertEquals(listOf(0), events)
                state.releaseEdge(); tick(30)
                assertEquals(listOf(0, 30), events)
            }
        }
    }

    @Test fun `authored group indices stay reserved through empty loads and refits`() {
        val mounts = JsonParser.parseString("""[
            {"Id":"left","AllowedStores":["test:laser","test:rocket"]},
            {"Id":"right","AllowedStores":["test:laser","test:gps"]},
            {"Id":"unarmed"}
        ]""").asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("gun", "AircraftStore:left", "AircraftStore:right", "AircraftStore:unarmed", "AircraftGunPods",
            "AircraftStoreGroup:test:laser", "AircraftStoreGroup:test:rocket", "AircraftStoreGroup:test:gps"),
            AircraftStoreWeapons.channelIds(mounts, listOf("gun")))
    }

    @Test fun `long store identities remain deterministic within the optional seeker channel bound`() {
        assertEquals("AircraftStoreGroup:test:kh55", AircraftStoreWeapons.groupId("test:kh55"))
        val longId = "test:" + "long_path/".repeat(11) + "missile"
        val first = AircraftStoreWeapons.groupId(longId)
        assertEquals(62, first.length)
        assertEquals(first, AircraftStoreWeapons.groupId(longId))
        assertNotEquals(first, AircraftStoreWeapons.groupId(longId + "2"))
        val group = AircraftStoreWeapons.collect(listOf(entry(longId, "wing", "AIR_TO_AIR", 2, 2))) { null }.single()
        assertEquals(longId, group.storeId)
        assertEquals(first, group.weaponId)
        assertEquals("wing", group.next!!.mountId)
    }
}
