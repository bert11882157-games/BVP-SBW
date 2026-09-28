package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity
import java.util.Locale

/**
 * "[Aircraft Damage]" lines in latest.log for every aircraft hit that matters (gun rounds below 20 mm only while
 * diagnostics run), and the same values on the diagnostics stream (category "aircraft_damage_model").
 */
object AircraftDamageLog {
    private const val ALWAYS_FROM_MM = 20.0

    @JvmStatic
    fun hit(vehicle: VehicleEntity, projectile: Entity?, kind: String, calibre: Double?, damage: Double,
            healthBefore: Float, accepted: Boolean) {
        emit(vehicle, "HIT", kind == AircraftHitRules.Kind.GUN.name && (calibre ?: 0.0) < ALWAYS_FROM_MM,
            String.format(Locale.ROOT, "kind=%s cal=%s damage=%.1f hp=%.1f->%.1f/%.0f%s", kind,
                calibre?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-", damage, healthBefore, vehicle.health,
                vehicle.getMaxHealth(), if (accepted) "" else " REJECTED"),
            "projectile", projectile?.type, "kind", kind, "calibre", calibre, "damage", damage,
            "health_before", healthBefore, "health_after", vehicle.health, "accepted", accepted)
    }

    @JvmStatic
    fun event(vehicle: VehicleEntity, event: String, detail: String) =
        emit(vehicle, event, false, detail, "detail", detail)

    private fun emit(vehicle: VehicleEntity, event: String, quiet: Boolean, detail: String, vararg fields: Any?) {
        val diagnostics = EliteDiagnostics.isEnabled(vehicle.level())
        if (diagnostics) EliteDiagnostics.record(vehicle, "aircraft_damage_model", event, *fields)
        if (diagnostics || !quiet) Mod.LOGGER.info("[Aircraft Damage] {} aircraft={} {}", event,
            net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type), detail)
    }
}
