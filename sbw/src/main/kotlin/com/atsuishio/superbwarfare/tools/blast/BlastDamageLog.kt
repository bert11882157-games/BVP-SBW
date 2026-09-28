package com.atsuishio.superbwarfare.tools.blast

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity
import java.util.Locale

/** "[Blast Damage]" line in latest.log for every blast that reaches a classed vehicle (or skips its struck target). */
object BlastDamageLog {
    @JvmStatic
    fun vehicle(vehicle: VehicleEntity, source: Entity?, damageClass: String, rule: String, kg: Double,
                distance: Double, severeRadius: Double, damage: Double, healthBefore: Float) {
        Mod.LOGGER.info("[Blast Damage] vehicle={} class={} source={} rule={} tnt_kg={} distance={} severe_r={} damage={} hp={}->{}/{}",
            net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type), damageClass,
            source?.type?.let { net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(it) },
            rule, f(kg), f(distance), f(severeRadius), f(damage), f(healthBefore.toDouble()), f(vehicle.health.toDouble()),
            f(vehicle.getMaxHealth().toDouble()))
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.2f", v)
}
