package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.api.vehicle.weapon.*
import com.atsuishio.superbwarfare.data.gun.ProjectileBeltFamily
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleWeaponCadencePoliciesTest {
    @Test fun `no policy preserves the original schedule object`() {
        val profile = VehicleWeaponScheduleProfile(ResourceLocation("fixture", "native"), 850, 850, 1, 1)
        assertSame(profile, VehicleWeaponCadencePolicies.apply(VehicleWeaponCadenceInput(null, null), profile))
    }

    @Test fun `rate policy preserves heat audio repetition and event multiplicity`() {
        val id = ResourceLocation("fixture", "rate")
        val profile = VehicleWeaponScheduleProfile.fixedWindow(id, 1000, 500, 2, 7, 30, 80, 80, false)
        VehicleWeaponCadencePolicies.register(id) { if (it.family == ProjectileBeltFamily.KPVT) 600 else null }
        try {
            val result = VehicleWeaponCadencePolicies.apply(VehicleWeaponCadenceInput(ProjectileBeltFamily.KPVT, null), profile)
            assertEquals(profile.copy(bulletRpm = 1200, eventRpm = 600), result)
            assertSame(profile, VehicleWeaponCadencePolicies.apply(VehicleWeaponCadenceInput(null, null), profile))
        } finally { VehicleWeaponCadencePolicies.unregister(id) }
    }

    @Test fun `rate multiplication saturates without integer overflow`() {
        val id = ResourceLocation("fixture", "large_rate")
        val profile = VehicleWeaponScheduleProfile(id, 1, 1, 4, 4)
        VehicleWeaponCadencePolicies.register(id) { Int.MAX_VALUE }
        try {
            assertEquals(Int.MAX_VALUE, VehicleWeaponCadencePolicies.apply(
                VehicleWeaponCadenceInput(null, null), profile).bulletRpm)
        } finally { VehicleWeaponCadencePolicies.unregister(id) }
    }
}
