package com.atsuishio.superbwarfare.config.server

import com.atsuishio.superbwarfare.config.buildServerConfig

object FarRenderConfig {
    @JvmField val ENABLED = buildServerConfig {
        push("far_render")
        comment("Send render-only vehicle snapshots beyond normal tracking distance.")
        define("enabled", true)
    }
    @JvmField val RANGE = buildServerConfig {
        comment("Legacy setting retained for config compatibility. Acquisition uses five times effective view distance; acquired vehicles remain subscribed until removed or disconnected.")
        defineInRange("range_blocks", 2048, 64, 8192)
    }
    @JvmField val MAX_VEHICLES = buildServerConfig {
        comment("Maximum acquired vehicles per player. Existing subscriptions keep their slots until removal or disconnect.")
        defineInRange("max_vehicles_per_player", 128, 1, 256)
    }
    @JvmField val INTERVAL = buildServerConfig {
        comment("Ticks between visual snapshots. Client interpolation covers the interval.")
        defineInRange("update_interval_ticks", 3, 2, 20).also { pop() }
    }
}
