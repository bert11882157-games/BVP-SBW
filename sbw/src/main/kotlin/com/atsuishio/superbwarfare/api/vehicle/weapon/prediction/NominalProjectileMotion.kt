package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.tools.VectorTool.isInLiquid
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/** Pure discrete motion shared by live linear-gravity projectiles and advisory prediction. */
object NominalProjectileMotion {
    @JvmStatic
    fun launchSpeed(data: GunData, level: Level, position: Vec3): Double {
        val configured = data.get(GunProp.VELOCITY).toFloat()
        val liveFloat = if (isInLiquid(level, position)) 2f + 0.05f * configured else configured
        return liveFloat.toDouble()
    }

    @JvmStatic
    fun initialMotion(direction: Vec3, launchSpeed: Double, inheritedPlatformMotion: Vec3): Vec3 =
        direction.normalize().scale(launchSpeed).add(inheritedPlatformMotion)

    @JvmStatic
    fun afterStep(currentMotion: Vec3, gravityPerTick: Double): Vec3 =
        currentMotion.add(0.0, -gravityPerTick, 0.0)

    /** Exact air-path ThrowableProjectile update followed by SBW's Float reciprocal cancellation. */
    @JvmStatic
    fun afterFastThrowableAirStep(currentMotion: Vec3, gravityPerTick: Double): Vec3 {
        val drag = 0.99f
        val vanillaResult = currentMotion.scale(drag.toDouble()).add(0.0, -gravityPerTick, 0.0)
        return afterVanillaThrowableStep(vanillaResult, gravityPerTick, drag, gravityPerTick)
    }

    @JvmStatic
    fun afterAirStep(currentMotion: Vec3, gravityPerTick: Double, model: NominalMotionModel): Vec3 =
        when (model) {
            NominalMotionModel.DIRECT_LINEAR_GRAVITY -> afterStep(currentMotion, gravityPerTick)
            NominalMotionModel.FAST_THROWABLE_LINEAR_GRAVITY_AIR ->
                afterFastThrowableAirStep(currentMotion, gravityPerTick)
        }

    /** Re-expresses the existing FastThrowableProjectile cancellation without changing its order. */
    @JvmStatic
    fun afterVanillaThrowableStep(
        vanillaResultMotion: Vec3,
        vanillaGravity: Double,
        vanillaDrag: Float,
        configuredGravity: Double,
    ): Vec3 = vanillaResultMotion
        .add(0.0, vanillaGravity, 0.0)
        .scale((1f / vanillaDrag).toDouble())
        .add(0.0, -configuredGravity, 0.0)
}
