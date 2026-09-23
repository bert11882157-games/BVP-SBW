package com.atsuishio.superbwarfare.client.weapon

import com.atsuishio.superbwarfare.api.projectile.ProjectileHullDamageClass
import com.atsuishio.superbwarfare.data.vehicle.subdata.PassengerWeaponStationWeaponKind
import com.atsuishio.superbwarfare.data.vehicle.WeaponSystemKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleWeaponHudMetadataTest {
    @Test fun `group reload stays hidden while another feed is ready`() {
        assertEquals(0, VehicleWeaponHudMetadata.groupReloadTicks(listOf(0 to 300, 25 to 0)))
        assertEquals(80, VehicleWeaponHudMetadata.groupReloadTicks(listOf(0 to 300, 0 to 80)))
        assertEquals(80, VehicleWeaponHudMetadata.groupReloadTicks(listOf(25 to 80, 0 to 0)))
        assertEquals(0, VehicleWeaponHudMetadata.groupReloadTicks(listOf(0 to 0)))
        assertEquals(0, VehicleWeaponHudMetadata.groupReloadTicks(emptyList()))
    }

    @Test fun `all authored kinds propagate without consulting names ammunition or station guesses`() {
        for (kind in WeaponSystemKind.entries) {
            assertEquals(kind.name, VehicleWeaponHudMetadata.kind(
                PassengerWeaponStationWeaponKind.HEAVY_MACHINE_GUN,
                ProjectileHullDamageClass.ATGM, "superbwarfare:cannon_shell", kind).name)
        }
    }

    @Test fun `explicit unknown blocks inferred kind while absent metadata preserves native fallback`() {
        assertEquals(VehicleWeaponHudKind.UNKNOWN, VehicleWeaponHudMetadata.kind(
            null, null, "superbwarfare:cannon_shell", WeaponSystemKind.UNKNOWN))
        assertEquals(VehicleWeaponHudKind.TANK_CANNON, VehicleWeaponHudMetadata.kind(
            null, null, "superbwarfare:cannon_shell", null))
    }

    @Test fun `equipped systems retain seat indices and filter unavailable loadout entries`() {
        val names = listOf("Cannon", "", "UnequippedPod", "MissingWeapon", "Coax")
        assertEquals(listOf(0, 4), VehicleWeaponHudMetadata.equippedIndices(names) {
            it in setOf("Cannon", "Coax")
        })
    }

    @Test fun `native launcher identities and explicit station kinds choose truthful icons`() {
        assertEquals(VehicleWeaponHudKind.TANK_CANNON,
            VehicleWeaponHudMetadata.kind(null, null, "superbwarfare:cannon_shell"))
        assertEquals(VehicleWeaponHudKind.AUTOCANNON,
            VehicleWeaponHudMetadata.kind(null, null, "superbwarfare:small_cannon_shell"))
        assertEquals(VehicleWeaponHudKind.ATGM,
            VehicleWeaponHudMetadata.kind(null, ProjectileHullDamageClass.ATGM, "superbwarfare:missile"))
        assertEquals(VehicleWeaponHudKind.HMG,
            VehicleWeaponHudMetadata.kind(PassengerWeaponStationWeaponKind.HEAVY_MACHINE_GUN,
                null, "superbwarfare:projectile"))
    }

    @Test fun `cannon with an ATGM round remains a cannon system`() {
        assertEquals(VehicleWeaponHudKind.TANK_CANNON,
            VehicleWeaponHudMetadata.kind(null, ProjectileHullDamageClass.ATGM, "superbwarfare:cannon_shell"))
    }

    @Test fun `ambiguous bullets and lookalike ids remain unknown`() {
        for (id in listOf(null, "superbwarfare:projectile", "addon:cannon_shell", "addon:hmg",
            "addon:gun_grenade")) {
            assertEquals(VehicleWeaponHudKind.UNKNOWN, VehicleWeaponHudMetadata.kind(null, null, id))
        }
        assertEquals(VehicleWeaponHudKind.UNKNOWN,
            VehicleWeaponHudMetadata.kind(PassengerWeaponStationWeaponKind.ROCKET_POD, null, null))
    }

    @Test fun `typed grenade launchers and rifle caliber bullets have useful labels`() {
        assertEquals(VehicleWeaponHudKind.GRENADE_LAUNCHER,
            VehicleWeaponHudMetadata.kind(null, null, "superbwarfare:gun_grenade"))
        assertEquals(VehicleWeaponHudKind.LMG,
            VehicleWeaponHudMetadata.kind(null, null, "superbwarfare:projectile", caliberMm = 7.62))
        assertEquals(VehicleWeaponHudKind.HMG,
            VehicleWeaponHudMetadata.kind(null, null, "superbwarfare:projectile", caliberMm = 12.7))
    }

    @Test fun `ammo cycle capability counts selectable consumers only`() {
        assertFalse(VehicleWeaponHudMetadata.supportsAmmoCycle(0))
        assertFalse(VehicleWeaponHudMetadata.supportsAmmoCycle(1))
        assertTrue(VehicleWeaponHudMetadata.supportsAmmoCycle(2))
    }

    @Test fun `ammo distinguishes empty magazine direct reserve and unavailable counts`() {
        assertEquals(VehicleWeaponHudAmmo(0, 45, false), VehicleWeaponHudAmmo.from(100, 0, 45, false))
        assertEquals(VehicleWeaponHudAmmo(null, 45, false), VehicleWeaponHudAmmo.from(0, 999, 45, false))
        assertEquals(VehicleWeaponHudAmmo(null, null, false), VehicleWeaponHudAmmo.from(10, -1, -1, false))
        assertEquals(VehicleWeaponHudAmmo(10, null, true),
            VehicleWeaponHudAmmo.from(10, 10, Int.MAX_VALUE, false))
        assertEquals(VehicleWeaponHudAmmo(null, null, true), VehicleWeaponHudAmmo.from(0, 0, 0, true))
    }
}
