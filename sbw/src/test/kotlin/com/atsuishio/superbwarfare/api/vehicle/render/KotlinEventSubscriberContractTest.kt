package com.atsuishio.superbwarfare.api.vehicle.render

import net.minecraftforge.eventbus.api.SubscribeEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class KotlinEventSubscriberContractTest {
    @Test
    fun `singleton listeners use instance methods for Kotlin for Forge registration`() {
        val classes = listOf(
            "com.atsuishio.superbwarfare.api.vehicle.render.FarVehiclePublisher",
            "com.atsuishio.superbwarfare.client.FarVehicleClient",
            "com.atsuishio.superbwarfare.client.FarVehicleReloadListener",
            "com.atsuishio.superbwarfare.client.renderer.FarVehicleRenderer",
            "com.atsuishio.superbwarfare.api.weapon.VehicleReloadAudio",
            "com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics",
            "com.atsuishio.superbwarfare.diagnostics.EliteVehicleDiagnostics",
            "com.atsuishio.superbwarfare.diagnostics.EliteAudioPlayback",
        )
        for (name in classes) {
            val type = Class.forName(name, false, javaClass.classLoader)
            val listeners = type.declaredMethods.filter { it.isAnnotationPresent(SubscribeEvent::class.java) }
            assertTrue(listeners.isNotEmpty(), "$name has no event listeners")
            listeners.forEach { method ->
                assertFalse(Modifier.isStatic(method.modifiers), "$name.${method.name} is ignored by instance registration")
            }
        }
    }
}
