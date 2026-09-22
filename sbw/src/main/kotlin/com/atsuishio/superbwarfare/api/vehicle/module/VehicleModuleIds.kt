package com.atsuishio.superbwarfare.api.vehicle.module

import com.atsuishio.superbwarfare.Mod

/** Stable semantic IDs backed by SBW's five legacy part fields. */
object VehicleModuleIds {
    @JvmField val TURRET = Mod.loc("turret")
    @JvmField val RUNNING_GEAR_LEFT = Mod.loc("running_gear_left")
    @JvmField val RUNNING_GEAR_RIGHT = Mod.loc("running_gear_right")
    @JvmField val ENGINE_MAIN = Mod.loc("engine_main")
    @JvmField val ENGINE_SUB = Mod.loc("engine_sub")
}

