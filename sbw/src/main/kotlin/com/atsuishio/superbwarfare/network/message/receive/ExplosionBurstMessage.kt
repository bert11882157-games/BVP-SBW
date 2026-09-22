package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.api.effect.ExplosionBurstPresentations
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import com.atsuishio.superbwarfare.tools.sendPacketTo
import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.PacketDistributor.TargetPoint

@Serializable
data class ExplosionBurstMessage(
    val recipe: Recipe,
    val position: SerializedVec3,
    val underwater: Boolean,
    val seed: Long,
    val vehicleUuid: String = "",
) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        ExplosionBurstPresentations.emit(this@ExplosionBurstMessage)
    }

    @Serializable
    enum class Recipe {
        LARGE,
        HUGE,
        GIANT,
        FAR_VEHICLE,
        AIR_MISSILE,
        AIRCRAFT_BREAKUP,
    }

    companion object {
        private const val FORCED_PARTICLE_RANGE = 512.0

        /** One small presentation for retained subscribers, even after immediate entity removal. */
        @JvmStatic
        fun sendFarDeath(vehicle: com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity) {
            val level = vehicle.level() as? ServerLevel ?: return
            com.atsuishio.superbwarfare.api.vehicle.render.VehicleDeathEffects.capture(vehicle)
            val message = ExplosionBurstMessage(Recipe.FAR_VEHICLE,
                vehicle.boundingBox.center, false, level.random.nextLong(), vehicle.stringUUID)
            level.players().filter {
                com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer.selected(it, vehicle)
            }.forEach { sendPacketTo(it, message) }
        }

        @JvmStatic
        fun send(level: ServerLevel, recipe: Recipe, position: Vec3, underwater: Boolean) {
            val message = ExplosionBurstMessage(recipe, position, underwater, level.random.nextLong())
            com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer.rememberEffect(level, position, 32.0)
            for (player in level.players()) {
                if (!com.atsuishio.superbwarfare.api.vehicle.render.VehicleDeathEffects.allowsFullFx(player.uuid)) continue
                val far = com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer.radius(player)
                if (com.atsuishio.superbwarfare.api.vehicle.render.VehicleDeathEffects.retainedRecipient(player) ||
                    player.distanceToSqr(position) <= FORCED_PARTICLE_RANGE * FORCED_PARTICLE_RANGE ||
                    (far > 0 && com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainPolicy.inside(
                        player.x, player.z, position.x, position.z, far))) sendPacketTo(player, message)
            }
        }
    }
}
