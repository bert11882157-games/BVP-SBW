package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.projectile.MotionSyncPolicy
import com.atsuishio.superbwarfare.data.projectile.ProjectileTrailMode
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GroundVehicleBlastPolicyTest {
    @Test fun typedBlastOwnershipKeepsAircraftOnTheirExistingDamagePath() {
        val policy = GroundVehicleBlastPolicy(5.0, 14.0, 1600f)
        for (type in VehicleType.entries) {
            assertEquals(type !in setOf(VehicleType.AIRPLANE, VehicleType.HELICOPTER), policy.appliesTo(type))
        }
        assertTrue(policy.appliesTo(null), "Unknown vehicle type retains the prior ground-blast path")
        assertFalse(GroundVehicleBlastPolicy.from(null)?.appliesTo(VehicleType.TANK) == true)
    }
    private fun profile(extensions: String) = ResolvedProjectileProfile(
        ResourceLocation("test", "blast"), null, null, null, MotionSyncPolicy.INHERIT,
        null, 0, ProjectileTrailMode.DEFAULT, 1f, JsonParser.parseString(extensions).asJsonObject)

    @Test fun diagnosticParserSharesNormalAdmissionAndRetainsFailures() {
        val valid = profile("""{"superbwarfare:ground_vehicle_blast_v1":{"InnerRadius":5,"OuterRadius":14,"DamageAtInnerEdge":1600}}""")
        assertEquals(GroundVehicleBlastPolicy(5.0, 14.0, 1600f), GroundVehicleBlastPolicy.parse(valid))
        assertEquals(GroundVehicleBlastPolicy.from(valid), GroundVehicleBlastPolicy.parse(valid))
        assertNull(GroundVehicleBlastPolicy.parse(null))
        assertNull(GroundVehicleBlastPolicy.parse(profile("{}")))
        for (body in listOf("7", "{}", """{"InnerRadius":14,"OuterRadius":5,"DamageAtInnerEdge":1600}""")) {
            val invalid = profile("""{"superbwarfare:ground_vehicle_blast_v1":$body}""")
            assertNull(GroundVehicleBlastPolicy.from(invalid))
            assertThrows(RuntimeException::class.java) { GroundVehicleBlastPolicy.parse(invalid) }
        }
    }

    @Test fun innerBoundaryIsLethalAndOuterDamageFallsToZero() {
        val policy = GroundVehicleBlastPolicy(5.0, 14.0, 1600f)
        assertTrue(policy.lethal(0.0)); assertTrue(policy.lethal(5.0))
        assertFalse(policy.lethal(5.001)); assertFalse(policy.lethal(Double.NaN))
        assertEquals(1600f, policy.damage(5.0))
        assertEquals(800f, policy.damage(9.5))
        assertEquals(0f, policy.damage(14.0)); assertEquals(0f, policy.damage(100.0))
        assertEquals(0f, policy.damage(Double.NaN))
    }
}
