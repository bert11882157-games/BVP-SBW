package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.item.gun.vehicle.VehicleGun
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity

/** One launch-time damage factor for direct hits and blast. Raw projectile stats stay intact for legacy classifiers. */
object VehicleWeaponDamagePolicy {
    private const val TAG = "SBWVehicleDirectDamageScale"

    @JvmStatic
    fun factor(vehicleMounted: Boolean, munition: String?, caliberMm: Double?, nativeScale: Double = 1.0): Double {
        if (!vehicleMounted) return 1.0
        if (munition != null) return when {
            munition != "bullet" && munition != "autocannon_shell" -> 1.0
            caliberMm == null || !caliberMm.isFinite() -> 1.0
            kotlin.math.abs(caliberMm - 12.7) < 0.001 -> 0.33
            munition == "autocannon_shell" && caliberMm > 0 && caliberMm <= 30.0 -> 0.5
            else -> 1.0
        }
        return nativeScale.takeIf { it == 0.33 || it == 0.5 } ?: 1.0
    }

    @JvmStatic
    fun capture(projectile: Entity, parameters: ShootParameters) {
        val mounted = parameters.data.item is VehicleGun && parameters.ammoSupplier is VehicleEntity
        val combat = ProjectileProfiles.combatDescriptor(projectile)
        val value = factor(mounted, combat?.munitionType?.path, combat?.caliberMm,
            parameters.data.get(GunProp.VEHICLE_DIRECT_DAMAGE_SCALE))
        if (value < 1.0) projectile.persistentData.putDouble(TAG, value)
        else projectile.persistentData.remove(TAG)
    }

    @JvmStatic
    fun scale(projectile: Entity?): Double = projectile?.persistentData?.getDouble(TAG)
        ?.takeIf { it == 0.33 || it == 0.5 } ?: 1.0

    /** One damage-boundary application for both direct and explosion damage sources.
     * Explosion builders retain raw damage/radius; their falloff result enters DamageHandler once.
     */
    @JvmStatic
    fun damage(source: DamageSource, amount: Float): Float = scaleDamage(amount, scale(source.directEntity))

    internal fun scaleDamage(amount: Float, capturedFactor: Double): Float = (amount * capturedFactor).toFloat()
}
