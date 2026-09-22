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
) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        ExplosionBurstPresentations.emit(this@ExplosionBurstMessage)
    }

    @Serializable
    enum class Recipe {
        LARGE,
        HUGE,
        GIANT,
    }

    companion object {
        private const val FORCED_PARTICLE_RANGE = 512.0

        @JvmStatic
        fun send(level: ServerLevel, recipe: Recipe, position: Vec3, underwater: Boolean) {
            val target = PacketDistributor.NEAR.with {
                TargetPoint(
                    position.x,
                    position.y,
                    position.z,
                    FORCED_PARTICLE_RANGE,
                    level.dimension(),
                )
            }
            sendPacketTo(target, ExplosionBurstMessage(recipe, position, underwater, level.random.nextLong()))
        }
    }
}
