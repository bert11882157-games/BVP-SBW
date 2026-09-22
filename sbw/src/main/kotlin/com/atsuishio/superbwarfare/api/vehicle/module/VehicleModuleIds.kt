package com.atsuishio.superbwarfare.api.vehicle.module

import com.atsuishio.superbwarfare.Mod
import net.minecraft.resources.ResourceLocation

/** Stable part IDs. The constant namespace does not require initializing the mod entry point. */
object VehicleModuleIds {
    @JvmField val TURRET = ResourceLocation(Mod.MODID, "turret")
    @JvmField val RUNNING_GEAR_LEFT = ResourceLocation(Mod.MODID, "running_gear_left")
    @JvmField val RUNNING_GEAR_RIGHT = ResourceLocation(Mod.MODID, "running_gear_right")
    @JvmField val ENGINE_MAIN = ResourceLocation(Mod.MODID, "engine_main")
    @JvmField val ENGINE_SUB = ResourceLocation(Mod.MODID, "engine_sub")
}
