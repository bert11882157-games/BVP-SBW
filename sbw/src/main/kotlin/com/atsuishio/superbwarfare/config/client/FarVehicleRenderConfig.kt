package com.atsuishio.superbwarfare.config.client

import com.atsuishio.superbwarfare.config.buildClientConfig

object FarVehicleRenderConfig {
    @JvmField val ENABLED = buildClientConfig {
        push("far_vehicle_render")
        comment("Opt in to distant vehicle retention. Uses ordinary Minecraft/Voxy terrain for occlusion; does not stream terrain cover.")
        define("enabled", false)
    }
    @JvmField val RANGE = buildClientConfig {
        comment("Legacy setting retained for config compatibility. Acquisition uses five times effective view distance; retained vehicles have no distance cutoff.")
        defineInRange("range_blocks", 2048, 64, 8192)
    }
    @JvmField val MAX_VEHICLES = buildClientConfig {
        comment("Maximum vehicles drawn by the additional render pass per frame.")
        defineInRange("max_vehicles", 128, 1, 256).also { pop() }
    }
}
