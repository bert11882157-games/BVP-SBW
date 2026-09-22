package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.data.gun.DefaultGunData
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.event.GunEventHandler
import com.atsuishio.superbwarfare.item.gun.vehicle.VehicleGun
import com.atsuishio.superbwarfare.perk.Perk
import net.minecraft.world.item.ItemStack
import net.minecraftforge.registries.ForgeRegistry
import net.minecraftforge.registries.GameData
import net.minecraft.world.item.Item
import net.minecraftforge.registries.ForgeRegistries
import net.minecraft.resources.ResourceLocation
import com.atsuishio.superbwarfare.data.gun.subdata.Reload
import com.atsuishio.superbwarfare.data.gun.value.ReloadState
import kotlinx.serialization.json.Json
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftBeltReloadTest {
    @Test fun `saved infinite and zero-cost overrides cannot defeat finite vehicle installation`() {
        val gun=fixtureGun
        val definition=DefaultGunData().apply { magazine=250;beltFed=true;normalReloadTime=400;emptyReloadTime=400;overheatEnabled=false }
        val stack=ItemStack(gun)
        val perks=CompoundTag()
        for(type in Perk.Type.entries)perks.put(type.typeName,ListTag())
        stack.orCreateTag.put("Perks",perks)
        val data=GunData.from(stack){definition}
        data.propertyOverrideString.set("""{"Magazine":0,"BeltFed":false,"AmmoCostPerShoot":0,"ProjectileAmount":2,"NormalReloadTime":0,"EmptyReloadTime":0,"OverheatEnabled":true,"HeatPerShoot":100}""")
        data.ammo.set(3)
        val restored=GunData.from(data.stack.copy()){definition}
        assertFalse(restored.useBackpackAmmo())
        assertEquals(3,restored.currentAvailableShots(null))
        assertEquals(250,restored.get(com.atsuishio.superbwarfare.data.gun.GunProp.MAGAZINE))
        assertEquals(400,restored.get(com.atsuishio.superbwarfare.data.gun.GunProp.NORMAL_RELOAD_TIME))
        assertEquals(400,restored.get(com.atsuishio.superbwarfare.data.gun.GunProp.EMPTY_RELOAD_TIME))
        assertEquals(1,restored.get(com.atsuishio.superbwarfare.data.gun.GunProp.PROJECTILE_AMOUNT))
        assertFalse(restored.get(com.atsuishio.superbwarfare.data.gun.GunProp.OVERHEAT_ENABLED))
        restored.ammo.set(0)
        assertFalse(restored.hasEnoughAmmoToShoot(null))
    }
    companion object {
        private lateinit var fixtureGun: VehicleGun
        @JvmStatic @BeforeAll fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            GameData.unfreezeData()
            fixtureGun=VehicleGun()
            ForgeRegistries.ITEMS.register(ResourceLocation("test","aircraft_service_fixture"),fixtureGun)
            (ForgeRegistries.ITEMS as ForgeRegistry<Item>).freeze()
        }
    }
    @Test fun `belt reload opt in and durations survive native data serialization`() {
        assertFalse(DefaultGunData().beltFed)
        val authored = Json.decodeFromString(DefaultGunData.serializer(),
            """{"BeltFed":true,"NormalReloadTime":400,"EmptyReloadTime":400,"Magazine":120,"AutoReload":true,"OverheatEnabled":false}""")
        val restored = Json.decodeFromString(DefaultGunData.serializer(),
            Json.encodeToString(DefaultGunData.serializer(), authored))
        assertTrue(restored.beltFed)
        assertEquals(400, restored.normalReloadTime)
        assertEquals(400, restored.emptyReloadTime)
        assertTrue(DefaultGunData().overheatEnabled)
        assertFalse(restored.overheatEnabled)
        assertEquals(120, restored.magazine)
    }

    @Test fun `native normal and empty belt timers complete on update 400 and retain NBT progress`() {
        for (state in listOf(ReloadState.NORMAL_RELOADING, ReloadState.EMPTY_RELOADING)) {
            var tag = CompoundTag()
            var reload = Reload(tag)
            reload.setState(state)
            // The native open-bolt start adds one before the same-tick reduce/completion path.
            reload.setTime(400 + 1)
            for (update in 1..400) {
                reload.reduce()
                assertEquals(update == 400, reload.countdownFinished(), "$state update=$update")
                if (update == 147) {
                    tag = tag.copy()
                    reload = Reload(tag)
                    assertEquals(state, reload.state())
                    assertEquals(254, reload.time())
                }
            }
            reload.setState(ReloadState.NOT_RELOADING)
            reload.setTime(0)
            assertFalse(reload.countdownFinished())
        }
    }

    @Test fun `heatless definition clears saved heat while ordinary guns keep their cooldown`() {
        // All tests use one registered item and independent stacks/definitions.
        val gun = fixtureGun
        for (enabled in listOf(false, true)) {
            val definition = DefaultGunData().apply { overheatEnabled = enabled; naturalCooldown = 0.25 }
            val stack = ItemStack(gun)
            // Empty modern perk lists avoid resolving legacy names against unloaded mod registries.
            val perks = CompoundTag()
            for (type in Perk.Type.entries) perks.put(type.typeName, ListTag())
            stack.orCreateTag.put("Perks", perks)
            val data = GunData.from(stack) { definition }
            data.heat.set(100.0)
            data.overHeat.set(true)
            val restored = GunData.from(data.stack.copy()) { definition }
            GunEventHandler.handleCooldown(null, restored)
            assertEquals(if (enabled) 99.75 else 0.0, restored.heat.get())
            assertEquals(enabled, restored.overHeat.get())
        }
    }
}
