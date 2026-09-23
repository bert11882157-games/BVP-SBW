package com.atsuishio.superbwarfare.api.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftWeaponGroupsTest {
    private fun mount(id: String, store: String, category: String, vararg channels: String) =
        AircraftWeaponGroups.Mount(id, store, category, channels.toList())

    @Test fun `identical equipped pods and missiles have one representative per munition identity`() {
        val groups = AircraftWeaponGroups.groups(listOf(
            mount("rocket_left", "fixture:s8", "ROCKET_POD", "RocketLeft"),
            mount("rocket_right", "fixture:s8", "ROCKET_POD", "RocketRight"),
            mount("other_rocket", "fixture:s13", "ROCKET_POD", "HeavyRocket"),
            mount("gun_left", "berts_vehicle_pack:gsh23_pod", "GUN_POD", "GShLeft"),
            mount("gun_right", "berts_vehicle_pack:gsh23_pod", "GUN_POD", "GShRight"),
            mount("aam_left", "fixture:r60", "AIR_TO_AIR"),
            mount("aam_right", "fixture:r60", "AIR_TO_AIR"),
            mount("agm_left", "fixture:kh23", "LASER_GUIDED"),
            mount("agm_right", "fixture:kh23", "LASER_GUIDED"),
        ))
        assertEquals(listOf("RocketLeft", "HeavyRocket", AircraftGunPodGroups.GROUP,
            "AircraftStore:aam_left", "AircraftStore:agm_left"), groups.map { it.representative })
        assertEquals(listOf("RocketLeft", "RocketRight"), groups[0].members)
        assertEquals(listOf("GShLeft", "GShRight"), groups[2].members)
        assertEquals(listOf("AircraftStore:aam_left", "AircraftStore:aam_right"), groups[3].members)
        assertEquals(listOf("aam_left", "aam_right"), groups[3].mounts)
        assertEquals(listOf("AircraftStore:agm_left", "AircraftStore:agm_right"), groups[4].members)
    }

    @Test fun `different gunpod types never share a physical feed or selector`() {
        val groups = AircraftWeaponGroups.groups(listOf(
            mount("left", "berts_vehicle_pack:gsh23_pod", "GUN_POD", "GShLeft"),
            mount("right", "fixture:m134", "GUN_POD", "M134Right"),
        ))
        assertEquals(listOf("AircraftGunPods", "AircraftGunPods:fixture:m134"),
            groups.map { it.representative })
        assertEquals(listOf(listOf("GShLeft"), listOf("M134Right")), groups.map { it.members })
    }

    @Test fun `depleted first guided mount leaves second live and cursor advances only after acceptance`() {
        val mounts = listOf("left", "right")
        assertEquals(listOf("right"), AircraftWeaponGroups.orderedLiveMounts(mounts, 0) {
            if (it == "right") 1 else 0
        })
        assertEquals(listOf("right"), AircraftWeaponGroups.orderedLiveMounts(mounts, 1) {
            if (it == "right") 1 else 0
        })
        assertTrue(AircraftWeaponGroups.orderedLiveMounts(mounts, 0) { 0 }.isEmpty())
        assertEquals(listOf("right", "left"), AircraftWeaponGroups.orderedLiveMounts(mounts, 1) { 1 })
    }
}
