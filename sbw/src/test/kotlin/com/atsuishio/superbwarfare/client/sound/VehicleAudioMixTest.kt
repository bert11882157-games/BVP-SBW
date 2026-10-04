package com.atsuishio.superbwarfare.client.sound

import com.atsuishio.superbwarfare.client.sound.VehicleAudioMix.Category
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VehicleAudioMixTest {
    @BeforeEach
    fun reset() = VehicleAudioMix.resetForTest()

    @Test
    fun soundsWithoutTheirVehicleAreClassifiedById() {
        assertEquals(Category.AIRCRAFT_EFFECTS, VehicleAudioMix.byId(ResourceLocation("superbwarfare", "sonic_boom")))
        assertEquals(Category.AIRCRAFT_EFFECTS, VehicleAudioMix.byId(ResourceLocation("supersonic", "sonic_boom_close")))
        assertEquals(Category.AIRCRAFT_EFFECTS,
            VehicleAudioMix.byId(ResourceLocation("superbwarfare", "aircraft_flare_release")))
        assertEquals(Category.AIRCRAFT_ENGINES, VehicleAudioMix.byId(ResourceLocation("bvp_audio", "air/f16_idle")))
        assertEquals(Category.GROUND_ENGINES, VehicleAudioMix.byId(ResourceLocation("bvp_audio", "engine/t72_idle")))
        assertEquals(Category.GROUND_ENGINES, VehicleAudioMix.byId(ResourceLocation("bvp_audio", "tracks/t72")))
        // the air-burst explosion keeps playing unmixed; only its sonic-boom alias is an aircraft effect
        assertNull(VehicleAudioMix.byId(ResourceLocation("superbwarfare", "explosion_air")))
        assertNull(VehicleAudioMix.byId(ResourceLocation("bvp_audio", "weapon/t72_125_3p")))
        assertNull(VehicleAudioMix.byId(null))
    }

    @Test
    fun gainIsTheShippedTrimTimesThePlayersSlider() {
        assertEquals(0.42f, VehicleAudioMix.gain(Category.GROUND_ENGINES), 1e-6f)
        assertEquals(1f, VehicleAudioMix.gain(Category.GROUND_WEAPONS), 1e-6f)
        VehicleAudioMix.setLevel(Category.GROUND_ENGINES, 0.5f)
        assertEquals(0.21f, VehicleAudioMix.gain(Category.GROUND_ENGINES), 1e-6f)
        VehicleAudioMix.setLevel(Category.AIRCRAFT_EFFECTS, 9f)
        assertEquals(VehicleAudioMix.MAX_LEVEL, VehicleAudioMix.level(Category.AIRCRAFT_EFFECTS), 1e-6f)
        VehicleAudioMix.setLevel(Category.AIRCRAFT_WEAPONS, Float.NaN)
        assertEquals(1f, VehicleAudioMix.level(Category.AIRCRAFT_WEAPONS), 1e-6f)
        VehicleAudioMix.setLevel(Category.AIRCRAFT_ENGINES, 0f)
        assertEquals(0f, VehicleAudioMix.gain(Category.AIRCRAFT_ENGINES), 1e-6f)
    }

    @Test
    fun untaggedUnknownSoundsAreUnchanged() {
        assertEquals(1f, VehicleAudioMix.factor(null), 0f)
    }
}
