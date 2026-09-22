package com.atsuishio.superbwarfare.tools

import net.minecraft.world.level.Level

/**
 * Optional source-aware explosion presentation handler.
 *
 * Return true only after fully handling or intentionally suppressing the effect. Returning false delegates to the
 * next registered handler and ultimately to Superb Warfare's unchanged particle implementation.
 */
fun interface ExplosionFxHandler {
    fun handle(level: Level, context: ExplosionFxContext): Boolean
}
