package com.atsuishio.superbwarfare.client.weapon

import com.atsuishio.superbwarfare.api.projectile.ProjectileHullDamageClass
import com.atsuishio.superbwarfare.data.vehicle.WeaponSystemKind
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleSelectedRoundMetadataTest {
    @Test fun `ammo cycle changes authored designation while cannon system kind stays stable`() {
        val base = "weapon.berts_vehicle_pack.2a46m_125_mm_smoothbore"
        val selectableOverrides = listOf(
            JsonParser.parseString("""{"Name":"weapon.berts_vehicle_pack.3bm42"}""").asJsonObject,
            JsonParser.parseString("""{"Name":"weapon.berts_vehicle_pack.3bk18m_heat_fs"}""").asJsonObject,
        )
        val types = listOf(ProjectileHullDamageClass.APFSDS, ProjectileHullDamageClass.HEAT_FS)
        val labels = selectableOverrides.mapIndexed { selectedAmmoIndex, selectedOverride ->
            assertEquals(VehicleWeaponHudKind.TANK_CANNON, VehicleWeaponHudMetadata.kind(
                null, types[selectedAmmoIndex], "superbwarfare:cannon_shell", WeaponSystemKind.TANK_CANNON))
            VehicleSelectedRoundMetadata.designationKey(selectedOverride, base)
        }
        assertEquals(listOf("weapon.berts_vehicle_pack.3bm42", "weapon.berts_vehicle_pack.3bk18m_heat_fs"), labels)
        assertEquals("3BM42", VehicleSelectedRoundMetadata.separateDesignation("3BM42 APFSDS", "APFSDS"))
        assertEquals("3BK18M", VehicleSelectedRoundMetadata.separateDesignation("3BK18M HEAT-FS", "HEAT-FS"))
    }

    @Test fun `base weapon missing malformed and blank labels never invent a designation`() {
        val base = "weapon.pack.cannon"
        for (json in listOf("{}", """{"Name":"weapon.pack.cannon"}""", """{"Name":" "}""",
            """{"Name":null}""", """{"Name":14}""", """{"Name":{"fake":"round"}}""")) {
            assertNull(VehicleSelectedRoundMetadata.designationKey(JsonParser.parseString(json).asJsonObject, base))
        }
        assertNull(VehicleSelectedRoundMetadata.designationKey(null, base))
    }

    @Test fun `display formatting removes only a matching standalone type suffix`() {
        assertEquals("3BM42", VehicleSelectedRoundMetadata.separateDesignation("3BM42", "APFSDS"))
        assertEquals("9M117 Bastion", VehicleSelectedRoundMetadata.separateDesignation("9M117 Bastion ATGM", "ATGM"))
        assertEquals("NAMEAP", VehicleSelectedRoundMetadata.separateDesignation("NAMEAP", "AP"))
        assertNull(VehicleSelectedRoundMetadata.separateDesignation("APFSDS", "APFSDS"))
        assertNull(VehicleSelectedRoundMetadata.separateDesignation(null, "APFSDS"))
    }
}
