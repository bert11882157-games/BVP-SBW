package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/** Client-only smoke at damaged wing geometry; rendering observes, client ticks emit. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AircraftSurfaceSmoke {
    private val seen = LinkedHashMap<VehicleEntity, Long>()
    private var level: Any? = null
    private var tick = 0L
    private var nextObserveDiagnostic = 0L
    private var nextEmitDiagnostic = 0L

    private fun damaged(vehicle: VehicleEntity, id: net.minecraft.resources.ResourceLocation): Boolean {
        if (!FarVehicleCopies.isCopy(vehicle)) return AircraftSurfaceModules.damaged(vehicle, id)
        val bits = FarVehicleCopies.frame(vehicle)?.snapshot?.visualData
            ?.get(FarVehicleCopies.SURFACE_DAMAGE_KEY)?.toIntOrNull() ?: return false
        val index = AircraftSurfaceModules.ids.indexOf(id)
        return index >= 0 && bits and (1 shl index) != 0
    }

    fun observe(vehicle: VehicleEntity) {
        val gameTime = Minecraft.getInstance().level?.gameTime ?: return
        if (vehicle.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE &&
            EliteDiagnostics.isClientEnabled() && gameTime >= nextObserveDiagnostic) {
            nextObserveDiagnostic=gameTime+20
            EliteDiagnostics.recordClient(gameTime,"aircraft_surface_smoke","OBSERVED",
                "vehicle",vehicle.uuid,"same_level",vehicle.level() === Minecraft.getInstance().level,
                "fixed_wing",vehicle.isFixedWingFlightVehicle(),"copy",FarVehicleCopies.isCopy(vehicle),
                "left",damaged(vehicle,AircraftSurfaceModules.WING_LEFT),
                "right",damaged(vehicle,AircraftSurfaceModules.WING_RIGHT),
                "module_count",vehicle.computed().aircraftSurfaceModules.size,
                "module_snapshot",vehicle.entityData.get(VehicleEntity.MODULE_STATE_SNAPSHOT),"client_clock",tick)
        }
        if (vehicle.level() !== Minecraft.getInstance().level || !vehicle.isFixedWingFlightVehicle()) return
        if (!damaged(vehicle, AircraftSurfaceModules.WING_LEFT) &&
            !damaged(vehicle, AircraftSurfaceModules.WING_RIGHT)) return
        if (seen.size >= 64 && vehicle !in seen) seen.remove(seen.keys.first())
        seen[vehicle] = tick
    }

    @SubscribeEvent fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        if (level !== mc.level) {
            seen.clear(); level = mc.level; tick = 0; nextObserveDiagnostic=0; nextEmitDiagnostic=0
        }
        if (mc.level == null || mc.isPaused) return
        tick++
        if (tick % 20L == 0L && EliteDiagnostics.isClientEnabled())
            EliteDiagnostics.recordClient(mc.level!!.gameTime,"aircraft_surface_smoke","TICK",
                "seen",seen.size,"client_clock",tick)
        seen.entries.removeIf { it.key.isRemoved || it.key.isWreck || tick - it.value > 20 }
        if (tick % 4L != 0L) return
        var remaining = 32
        for (vehicle in seen.keys) {
            for (id in listOf(AircraftSurfaceModules.WING_LEFT, AircraftSurfaceModules.WING_RIGHT)) {
                if (remaining <= 0) return
                if (!damaged(vehicle, id)) continue
                val point = AircraftSurfaceModules.smokeEmissionPosition(vehicle, id, 1F)
                val particle = point?.let {
                    mc.particleEngine.createParticle(ParticleTypes.LARGE_SMOKE, it.x, it.y, it.z, 0.0, 0.035, 0.0)
                }
                if (EliteDiagnostics.isClientEnabled() && mc.level!!.gameTime >= nextEmitDiagnostic) {
                    nextEmitDiagnostic=mc.level!!.gameTime+20
                    EliteDiagnostics.recordClient(mc.level!!.gameTime,"aircraft_surface_smoke","EMIT",
                        "vehicle",vehicle.uuid,"surface",id,"point",point,"created",particle != null)
                }
                if (point == null) continue
                remaining--
            }
        }
    }
}
