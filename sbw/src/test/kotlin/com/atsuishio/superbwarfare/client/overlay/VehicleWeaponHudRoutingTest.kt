package com.atsuishio.superbwarfare.client.overlay

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Compiled call-site contracts plus the production admission policy; no client/world bootstrap. */
class VehicleWeaponHudRoutingTest {
    private data class Call(val owner: String, val name: String)
    private fun calls(type: String, method: String): List<Call> {
        val result = mutableListOf<Call>()
        val path = "com/atsuishio/superbwarfare/$type.class"
        javaClass.classLoader.getResourceAsStream(path).use { stream ->
            requireNotNull(stream) { path }
            ClassReader(stream).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?,
                                         exceptions: Array<out String>?): MethodVisitor? {
                    if (name != method) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String,
                                                     descriptor: String, isInterface: Boolean) {
                            result += Call(owner, name)
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        assertTrue(result.isNotEmpty(), "Missing compiled method $type.$method")
        return result
    }
    private fun List<Call>.countNamed(name: String) = count { it.name == name }
    private fun List<Call>.panelCalls() = count {
        it.owner.endsWith("/VehicleSystemsHud") && it.name.startsWith("ground")
    }

    @Test fun capabilityAssignsExactlyOnePanelOwnerAndKeepsBanHand() {
        // Same answer for pilot, gunner and door stations; identity/armament guards are in the snapshot.
        repeat(3) {
            assertTrue(VehicleWeaponHudRouting.independentPanel(true, true))
            assertFalse(VehicleWeaponHudRouting.aircraftPanel(true))
        }
        assertFalse(VehicleWeaponHudRouting.independentPanel(true, false))
        assertFalse(VehicleWeaponHudRouting.independentPanel(false, true))
        assertTrue(VehicleWeaponHudRouting.aircraftPanel(false))
        for (scheme in listOf(false, true)) {
            val owners = (if (VehicleWeaponHudRouting.independentPanel(scheme, true)) 1 else 0) +
                (if (VehicleWeaponHudRouting.aircraftPanel(scheme)) 1 else 0)
            assertEquals(1, owners)
        }
    }

    @Test fun independentCompiledRouteUsesCapabilityNotHudTypePilotOrSelectedGun() {
        val route = calls("client/overlay/VehicleHudOverlay", "renderWeaponInfo")
        assertEquals(1, route.countNamed("usesGroundVehicleWeaponScheme"))
        assertEquals(1, route.countNamed("banHand"))
        assertEquals(1, route.countNamed("independentPanel"))
        assertEquals(1, route.panelCalls())
        for (forbidden in listOf("getFirstPassenger", "getGunData", "getHudType"))
            assertEquals(0, route.countNamed(forbidden), forbidden)
        // The actual enclosing independent overlay owns one call, rather than the pilot-only flight HUD.
        assertEquals(1, calls("client/overlay/VehicleHudOverlay", "render").countNamed("renderWeaponInfo"))
    }

    @Test fun fixedWingCompiledRouteRetainsItsPanelWithExclusiveOwnership() {
        val route = calls("client/overlay/weapon/AircraftHud", "render")
        assertEquals(1, route.panelCalls())
        assertEquals(1, route.countNamed("aircraftPanel"))
        assertTrue(route.countNamed("usesGroundVehicleWeaponScheme") >= 1)
        assertEquals(0, calls("client/overlay/weapon/HelicopterHud", "render").panelCalls())
    }

    @Test fun legacyAmmoHelpersConsultCapabilityAndSnapshotRetainsSeatAndGroupContracts() {
        for (method in listOf("renderWeaponInfoFirst", "renderWeaponInfoThird", "renderWeaponInfoThirdAir")) {
            assertEquals(1, calls("client/overlay/VehicleMainWeaponHudOverlay", method)
                .countNamed("usesGroundVehicleWeaponScheme"), method)
        }
        for (type in listOf("AircraftHud", "HelicopterHud")) {
            val route = calls("client/overlay/weapon/$type", "render")
            assertTrue(route.countNamed("usesGroundVehicleWeaponScheme") >=
                route.countNamed("firstPersonAmmoComponent") + route.countNamed("thirdPersonAmmoComponent"))
        }
        val snapshot = calls("client/weapon/VehicleWeaponHudSnapshot\$Companion", "capture")
        for (contract in listOf("getSeatIndex", "getNthEntity", "getWeaponIds", "getPrimaryWeaponIndex",
            "getSecondaryWeaponIndex", "weaponPresentation", "groupReloadTicks"))
            assertTrue(snapshot.countNamed(contract) > 0, contract)
    }
}
