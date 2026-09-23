package com.atsuishio.superbwarfare.api.aircraft

import net.minecraft.world.entity.Entity

/** Optional FFA diagnostics bridge; debug logging never controls a spawn or its damage. */
object AircraftMunitionDebug {
    private val method by lazy {
        runCatching { Class.forName("dev.ballistics.InterceptorDebug")
            .getMethod("munition", Entity::class.java, String::class.java) }.getOrNull()
    }

    @JvmStatic fun log(entity: Entity, action: String) {
        if (entity.level().isClientSide) return
        runCatching { method?.invoke(null, entity, action.take(160)) }
    }
}
