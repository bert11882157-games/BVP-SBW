package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import kotlinx.serialization.Serializable

/** One transition event; cone particles are expanded locally instead of sent individually. */
@Serializable
data class SonicBoomMessage(val position: SerializedVec3, val forward: SerializedVec3, val size: Float,
                           val nativeBursts: List<SonicParticleBurst> = emptyList()) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        if (!position.lengthSqr().isFinite() || !forward.lengthSqr().isFinite() || size !in 1f..12f) return
        if (nativeBursts.isNotEmpty()) {
            if (nativeBursts.size > 64 || nativeBursts.any { !it.valid(position) }
                || nativeBursts.sumOf { maxOf(1, it.count) } > 512) return
            com.atsuishio.superbwarfare.client.particle.SonicBoomClient.emitNative(nativeBursts)
        } else com.atsuishio.superbwarfare.client.particle.SonicBoomClient.emit(position, forward, size)
    }
}

@Serializable
data class SonicParticleBurst(val particle: String, val x: Double, val y: Double, val z: Double,
                              val count: Int, val dx: Double, val dy: Double, val dz: Double, val speed: Double) {
    fun valid(center: net.minecraft.world.phys.Vec3) = particle.length <= 128 && count in 0..512 &&
        listOf(x,y,z,dx,dy,dz,speed).all { it.isFinite() } && center.distanceToSqr(x,y,z) < 256 &&
        kotlin.math.abs(dx) <= 4 && kotlin.math.abs(dy) <= 4 && kotlin.math.abs(dz) <= 4 && speed in 0.0..4.0
}
