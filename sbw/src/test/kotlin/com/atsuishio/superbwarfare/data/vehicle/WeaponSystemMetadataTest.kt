package com.atsuishio.superbwarfare.data.vehicle

import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.data.DataLoader
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WeaponSystemMetadataTest {
    private val vehicleId = "berts_vehicle_pack:t90a"
    private val gunId = "berts_vehicle_pack:aircraft_guns/ubs"
    private val records = mapOf(
        vehicleId to WeaponSystemMetadata(systems = mapOf(
            "Cannon" to WeaponSystemKind.TANK_CANNON,
            "MachineGun" to WeaponSystemKind.LMG,
            "Unclassified" to WeaponSystemKind.UNKNOWN,
        )),
        gunId to WeaponSystemMetadata(kind = WeaponSystemKind.HMG),
        "berts_vehicle_pack:guided_launcher" to WeaponSystemMetadata(kind = WeaponSystemKind.ATGM),
    )

    @Test fun `registered dataset uses the existing server to client map codec without losing kinds`() {
        // Registration is the production path. Serializing this GeneralData map is exactly what
        // OnDatapackSyncEvent calls; decoding uses DataSyncMessage's runtime map serializer.
        val proxy = CustomData.WEAPON_SYSTEM_METADATA
        val registration = DataLoader.LOADED_DATA.getValue("sbw/weapon_system_metadata")
        assertTrue(registration.synced)
        assertTrue(registration.isKtData)
        assertEquals(WeaponSystemMetadata::class.java, registration.type)
        val sample = DataLoader.GeneralData(WeaponSystemMetadata::class.java, proxy,
            HashMap<String, Any>(records), true, true, null)
        val encoded = sample.serializeToString()
        @Suppress("UNCHECKED_CAST")
        val decoded = DataLoader.JSON.decodeFromString(serializer(sample.mapType.type), encoded)
            as Map<String, WeaponSystemMetadata>
        assertEquals(records, decoded)
        assertEquals(WeaponSystemKind.LMG, decoded.getValue(vehicleId).systems["MachineGun"])
    }

    @Test fun `vehicle channel identity overrides shared weapon family and ammo classes`() {
        assertEquals(WeaponSystemKind.TANK_CANNON, WeaponSystemMetadata.resolve(
            vehicleId, "Cannon", "berts_vehicle_pack:guided_launcher", records::get))
        assertEquals(WeaponSystemKind.LMG,
            WeaponSystemMetadata.resolve(vehicleId, "MachineGun", gunId, records::get))
        assertEquals(WeaponSystemKind.HMG,
            WeaponSystemMetadata.resolve("berts_vehicle_pack:yak_3", "MachineGunLeft", gunId, records::get))
    }

    @Test fun `lookup is exact for namespace channel casing and weapon id`() {
        assertNull(WeaponSystemMetadata.resolve("addon:t90a", "Cannon", null, records::get))
        assertNull(WeaponSystemMetadata.resolve(vehicleId, "cannon", null, records::get))
        assertNull(WeaponSystemMetadata.resolve(null, "MachineGun", "addon:aircraft_guns/ubs", records::get))
        assertNull(WeaponSystemMetadata.resolve(null, "12.7 mm UBS", null, records::get))
    }

    @Test fun `unknown override and reload removal cannot reuse an old guessed category`() {
        assertEquals(WeaponSystemKind.UNKNOWN,
            WeaponSystemMetadata.resolve(vehicleId, "Unclassified", gunId, records::get))
        val reloaded = emptyMap<String, WeaponSystemMetadata>()
        assertNull(WeaponSystemMetadata.resolve(vehicleId, "Cannon", gunId, reloaded::get))
    }

    @Test fun `missing metadata remains absent and invalid kinds are rejected`() {
        assertEquals(WeaponSystemMetadata(), DataLoader.JSON.decodeFromString(
            WeaponSystemMetadata.serializer(), "{}"))
        assertThrows(IllegalArgumentException::class.java) {
            DataLoader.JSON.decodeFromString(WeaponSystemMetadata.serializer(), """{"Kind":"not_a_kind"}""")
        }
        val codec = MapSerializer(String.serializer(), WeaponSystemMetadata.serializer())
        assertEquals(emptyMap<String, WeaponSystemMetadata>(), DataLoader.JSON.decodeFromString(codec, "{}"))
    }

    @Test fun `ambiguous record and malformed or excessive channels are rejected`() {
        for (json in listOf(
            """{"Kind":"HMG","Systems":{"Cannon":"TANK_CANNON"}}""",
            """{"Systems":{"@Template":"LMG"}}""",
            """{"Systems":{"":"LMG"}}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                DataLoader.JSON.decodeFromString(WeaponSystemMetadata.serializer(), json)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WeaponSystemMetadata(systems = (1..65).associate { "Gun$it" to WeaponSystemKind.LMG })
        }
    }
}
