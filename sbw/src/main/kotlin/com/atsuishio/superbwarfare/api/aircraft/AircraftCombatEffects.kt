package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainPolicy
import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.receive.ExplosionBurstMessage
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity

/** Presentation bridge for optional missile integrations; damage remains with the missile. */
object AircraftCombatEffects {
    @JvmStatic fun missileBurst(missile: Entity, target: Entity?): Boolean =
        burst(missile, target as? VehicleEntity, ExplosionBurstMessage.Recipe.AIR_MISSILE)

    @JvmStatic fun aircraftBreakup(vehicle: VehicleEntity): Boolean =
        burst(vehicle, vehicle, ExplosionBurstMessage.Recipe.AIRCRAFT_BREAKUP)

    private fun burst(source: Entity, retained: VehicleEntity?, recipe: ExplosionBurstMessage.Recipe): Boolean {
        val level = source.level() as? ServerLevel ?: return false
        val point = source.boundingBox.center
        if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) return false
        val message = ExplosionBurstMessage(recipe, point, false, level.random.nextLong(), source.stringUUID)
        // These are event recipients, not chunk tickets. Retained targets can be outside acquisition range.
        for (player in level.players()) {
            val radius = FarTerrainServer.radius(player)
            if (player.distanceToSqr(point) <= 512.0 * 512.0 ||
                (radius > 0 && FarTerrainPolicy.inside(player.x, player.z, point.x, point.z, radius)) ||
                (retained != null && retained.level() === level && FarTerrainServer.selected(player, retained))) {
                sendPacketTo(player, message)
            }
        }
        return true
    }
}
