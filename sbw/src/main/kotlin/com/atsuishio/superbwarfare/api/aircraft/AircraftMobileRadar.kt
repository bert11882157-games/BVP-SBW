package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import java.util.WeakHashMap

/** Loaded emitters only. Position is read by FFA when it rebuilds its normal coverage cache. */
@EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID)
object AircraftMobileRadar {
    private val vehicles = WeakHashMap<VehicleEntity, Boolean>()
    private val api by lazy { runCatching {
        val cls = Class.forName("dev.ballistics.MobileRadarHooks")
        cls.getMethod("setRadar", Entity::class.java, Int::class.javaPrimitiveType) to cls.getMethod("clearRadar", Entity::class.java)
    }.getOrNull() }
    private fun update(vehicle: VehicleEntity) {
        val radar = AircraftArmamentManager.definition(vehicle)?.getAsJsonObject("Radar")
        // Ground SAM vehicles (GroundSamLauncher) carry their search/track radar in their SAM definition.
        val groundRange = if (radar == null) com.atsuishio.superbwarfare.api.vehicle.weapon.GroundSamLauncher.radarRange(vehicle) else null
        val alive = !vehicle.isRemoved && !vehicle.isWreck && vehicle.health > 0
        val active = alive && (radar?.get("Enabled")?.asBoolean == true || groundRange != null)
        runCatching {
            if (active) api?.first?.invoke(null, vehicle, groundRange ?: radar?.get("Range")?.asInt ?: 1000)
            else api?.second?.invoke(null, vehicle)
        }
    }
    @SubscribeEvent fun joined(event: EntityJoinLevelEvent) {
        if (event.level.isClientSide) return
        (event.entity as? VehicleEntity)?.let { vehicles[it] = true; update(it) }
    }
    @SubscribeEvent fun left(event: EntityLeaveLevelEvent) {
        (event.entity as? VehicleEntity)?.let { if (vehicles.remove(it) != null) runCatching { api?.second?.invoke(null, it) } }
    }
    @SubscribeEvent fun tick(event: TickEvent.LevelTickEvent) {
        if (event.phase != TickEvent.Phase.END || event.level.isClientSide) return
        // Refresh permissions and data-pack configuration; movement itself needs no re-registration.
        for (vehicle in vehicles.keys.toList()) if (vehicle.level() === event.level) update(vehicle)
    }
    @SubscribeEvent fun stopped(event: ServerStoppedEvent) { vehicles.clear() }
}
