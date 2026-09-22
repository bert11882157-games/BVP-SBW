package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.module.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.*

class VehicleModuleStateServiceTest {
    private val module = ResourceLocation("fixture", "rack")

    @Test fun `legacy module identifiers retain their serialized namespace and paths`() {
        assertEquals(listOf("superbwarfare:turret", "superbwarfare:running_gear_left",
            "superbwarfare:running_gear_right", "superbwarfare:engine_main", "superbwarfare:engine_sub"),
            listOf(VehicleModuleIds.TURRET, VehicleModuleIds.RUNNING_GEAR_LEFT,
                VehicleModuleIds.RUNNING_GEAR_RIGHT, VehicleModuleIds.ENGINE_MAIN,
                VehicleModuleIds.ENGINE_SUB).map { it.toString() })
    }

    private class Access(override val isClientSide: Boolean = false) : VehicleModuleStateAccess {
        val definitions = linkedMapOf<ResourceLocation, VehicleModuleDefinition>()
        val legacy = VehicleModuleAdapter.entries.associateWith {
            LegacyVehicleModuleState(200f, false)
        }.toMutableMap()
        val publications = mutableListOf<String>()
        var payload = ""
        var overriddenState: VehicleModuleState? = null
        var lastSet: VehicleModuleState? = null
        val service = VehicleModuleStateService(this)
        override fun providedDefinition(id: ResourceLocation) = definitions[id]
        override fun definition(id: ResourceLocation) = service.getDefinition(id)
        override fun state(id: ResourceLocation) = overriddenState?.takeIf { it.id == id }
            ?: service.getState(id)
        override fun setState(id: ResourceLocation, health: Double, destroyed: Boolean): VehicleModuleState? {
            if (overriddenState?.id == id) {
                return overriddenState!!.copy(health = health.toFloat(), destroyed = destroyed)
                    .also { lastSet = it }
            }
            return service.setState(id, health, destroyed)
        }
        override fun legacyMaximum(adapter: VehicleModuleAdapter) = 200f
        override fun legacyState(adapter: VehicleModuleAdapter) = legacy.getValue(adapter)
        override fun writeLegacyState(adapter: VehicleModuleAdapter, health: Float, destroyed: Boolean) {
            legacy[adapter] = LegacyVehicleModuleState(health, destroyed)
        }
        override fun snapshot() = payload
        override fun publishSnapshot(payload: String) {
            this.payload = payload
            publications += payload
        }
    }

    private fun access(client: Boolean = false) = Access(client).apply {
        definitions[module] = VehicleModuleDefinition(module, 100f)
    }

    @Test fun `generic damage and repair retain destruction latch until full health`() {
        val access = access()
        val service = access.service
        assertEquals(VehicleModuleState(module, 100f, 100f, false), service.getState(module))
        assertEquals(60f, service.damage(module, 40.0)!!.health)
        assertFalse(service.getState(module)!!.destroyed)
        assertTrue(service.damage(module, 1000.0)!!.destroyed)
        assertTrue(service.setHealth(module, 50.0)!!.destroyed)
        assertFalse(service.setHealth(module, 100.0)!!.destroyed)
        assertEquals("fixture:rack,100.0,100.0,0", access.payload)
        assertNull(service.damage(ResourceLocation("fixture", "unknown"), 1.0))
    }

    @Test fun `client queries consume snapshots without writing authoritative health`() {
        val access = access(true)
        access.payload = "fixture:rack,100.0,70.0,0"
        assertEquals(70f, access.service.getState(module)!!.health)
        assertEquals(70f, access.service.damage(module, 40.0)!!.health)
        assertEquals(70f, access.service.setHealth(module, 10.0)!!.health)
        assertEquals(70f, access.service.setState(module, 0.0, true)!!.health)
        assertTrue(access.publications.isEmpty())
        access.payload = "fixture:rack,100.0,0.0,0;INVALID,9,8,0;fixture:bad,NaN,8,0"
        assertTrue(access.service.getState(module)!!.destroyed)
        assertNull(access.service.getState(ResourceLocation("fixture", "bad")))
        access.payload = ""
        access.service.invalidateClientSnapshot()
        assertEquals(100f, access.service.getState(module)!!.health)
    }

    @Test fun `all legacy adapters retain native to logical conversion and write-through`() {
        for (adapter in VehicleModuleAdapter.entries.filter { it != VehicleModuleAdapter.GENERIC }) {
            val access = access()
            access.definitions[module] = VehicleModuleDefinition(module, 100f, adapter)
            access.legacy[adapter] = LegacyVehicleModuleState(100f, false)
            assertEquals(50f, access.service.getState(module)!!.health, adapter.name)
            assertEquals(25f, access.service.setHealth(module, 25.0)!!.health)
            assertEquals(LegacyVehicleModuleState(50f, false), access.legacy[adapter])
            access.service.setState(module, 0.0, false)
            assertTrue(access.legacy.getValue(adapter).destroyed)
            assertTrue(access.publications.isEmpty(), "Legacy state uses its existing synchronized fields")
        }
    }

    @Test fun `damage and health operations preserve addon virtual state and setter dispatch`() {
        val access = access()
        access.overriddenState = VehicleModuleState(module, 500f, 200f, false)
        assertEquals(150f, access.service.damage(module, 50.0)!!.health)
        assertEquals(500f, access.lastSet!!.maxHealth)
        assertEquals(400f, access.service.setHealth(module, 400.0)!!.health)
        assertTrue(access.publications.isEmpty(), "The addon setter owns this transition")
    }

    @Test fun `persistence round trip retains unknown damaged modules and omits healthy ones`() {
        val access = access()
        val second = ResourceLocation("fixture", "engine")
        access.definitions[second] = VehicleModuleDefinition(second, 50f)
        access.service.setHealth(module, 25.0)
        access.service.setHealth(second, 50.0)
        val saved = CompoundTag()
        access.service.writeAdditionalSaveData(saved)
        val states = saved.getCompound(VehicleModuleStateService.MODULE_STATES_TAG)
        assertTrue(states.contains(module.toString()))
        assertFalse(states.contains(second.toString()))
        val restored = Access()
        restored.service.readAdditionalSaveData(saved)
        assertEquals(VehicleModuleState(module, 100f, 25f, false), restored.service.getState(module))
        assertEquals("fixture:rack,100.0,25.0,0", restored.payload)
        restored.service.readAdditionalSaveData(CompoundTag())
        assertNull(restored.service.getState(module))
    }

    @Test fun `invalid numeric inputs cannot contaminate module state or saved reloads`() {
        val access = access()
        assertEquals(100f, access.service.damage(module, -10.0)!!.health)
        assertEquals(0f, access.service.damage(module, Double.NaN)!!.health)
        access.service.setHealth(module, 100.0)
        assertEquals(0f, access.service.setHealth(module, Double.POSITIVE_INFINITY)!!.health)
        val saved = CompoundTag()
        access.service.writeAdditionalSaveData(saved)
        saved.getCompound(VehicleModuleStateService.MODULE_STATES_TAG)
            .getCompound(module.toString()).putFloat(VehicleModuleStateService.MODULE_HEALTH_TAG, Float.NaN)
        access.service.readAdditionalSaveData(saved)
        assertEquals(0f, access.service.getState(module)!!.health)
        assertTrue(access.service.getState(module)!!.destroyed)
    }

    @Test fun `fractional maxima retain the established health and persistence compatibility`() {
        val access = access()
        access.definitions[module] = VehicleModuleDefinition(module, 0.5f)
        assertEquals(0.5f, access.service.getState(module)!!.health)
        // Native mutation storage has a minimum maximum of one, but setHealth first clamps to
        // the addon's current logical maximum. Do not widen that admission during extraction.
        assertEquals(0.5f, access.service.setHealth(module, 0.8)!!.health)
        val state = CompoundTag().apply {
            putFloat(VehicleModuleStateService.MODULE_MAX_HEALTH_TAG, 0.5f)
            putFloat(VehicleModuleStateService.MODULE_HEALTH_TAG, 0.25f)
        }
        val saved = CompoundTag().apply {
            put(VehicleModuleStateService.MODULE_STATES_TAG, CompoundTag().apply { put(module.toString(), state) })
        }
        access.service.readAdditionalSaveData(saved)
        assertEquals(VehicleModuleState(module, 0.5f, 0.25f, false), access.service.getState(module))
    }

    @Test fun `snapshot encoding is deterministic and returned state lists cannot mutate storage`() {
        val access = access()
        val first = ResourceLocation("fixture", "a")
        access.definitions[first] = VehicleModuleDefinition(first, 20f)
        access.service.setHealth(module, 25.0)
        access.service.setHealth(first, 10.0)
        assertEquals("fixture:a,20.0,10.0,0;fixture:rack,100.0,25.0,0", access.payload)
        val snapshot = access.service.getStates()
        (snapshot as MutableList).clear()
        assertEquals(25f, access.service.getState(module)!!.health)
    }

    @Test fun `domain component cannot reach the full entity or shared mutable state owner`() {
        val forbidden = listOf("VehicleEntity", "VehicleCombatStateOwner", "net/minecraft/world/entity/")
        val references = mutableListOf<String>()
        javaClass.classLoader.getResourceAsStream(
            "com/atsuishio/superbwarfare/entity/vehicle/base/VehicleModuleStateService.class")!!.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(access: Int, name: String, descriptor: String,
                    signature: String?, value: Any?): FieldVisitor? {
                    references += descriptor
                    return null
                }
                override fun visitMethod(access: Int, name: String, descriptor: String,
                    signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    references += descriptor
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String,
                            descriptor: String, isInterface: Boolean) { references += owner + descriptor }
                        override fun visitFieldInsn(opcode: Int, owner: String, name: String,
                            descriptor: String) { references += owner + descriptor }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        assertTrue(references.none { value -> forbidden.any(value::contains) }, references.toString())
    }
}
