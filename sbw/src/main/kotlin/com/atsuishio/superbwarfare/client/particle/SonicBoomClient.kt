package com.atsuishio.superbwarfare.client.particle

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import com.atsuishio.superbwarfare.network.message.receive.SonicParticleBurst
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.registries.ForgeRegistries
import kotlin.math.*

object SonicBoomClient {
    private var lastLevel: Any? = null
    private var lastTick = Long.MIN_VALUE
    private var cones = 0
    fun emitNative(bursts: List<SonicParticleBurst>) {
        val level = Minecraft.getInstance().level ?: return
        if (lastLevel !== level || lastTick != level.gameTime) { lastLevel = level; lastTick = level.gameTime; cones = 0 }
        if (cones++ >= 3) return
        for (burst in bursts) {
            val id = ResourceLocation.tryParse(burst.particle) ?: continue
            val type = ForgeRegistries.PARTICLE_TYPES.getValue(id) as? SimpleParticleType ?: continue
            if (burst.count == 0) {
                level.addAlwaysVisibleParticle(type, true, burst.x, burst.y, burst.z,
                    burst.dx * burst.speed, burst.dy * burst.speed, burst.dz * burst.speed)
            } else repeat(burst.count) {
                // Match vanilla's server particle packet distribution, including directional count-zero bursts.
                level.addAlwaysVisibleParticle(type, true,
                    burst.x + level.random.nextGaussian() * burst.dx,
                    burst.y + level.random.nextGaussian() * burst.dy,
                    burst.z + level.random.nextGaussian() * burst.dz,
                    level.random.nextGaussian() * burst.speed,
                    level.random.nextGaussian() * burst.speed,
                    level.random.nextGaussian() * burst.speed)
            }
        }
    }
    fun emit(center: Vec3, direction: Vec3, size: Float) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (lastLevel !== level || lastTick != level.gameTime) { lastLevel = level; lastTick = level.gameTime; cones = 0 }
        if (cones++ >= 3 || direction.lengthSqr() < 1e-8) return
        val forward = direction.normalize()
        val side = forward.cross(if (abs(forward.y) < .95) Vec3(0.0,1.0,0.0) else Vec3(1.0,0.0,0.0)).normalize()
        val up = side.cross(forward).normalize()
        val smoke = CustomCloudOption(.92f,.94f,.98f,12,size*.22f,0f,false,false)
        // Thin expanding vapor skirt with a narrow nose, bounded even during mass flybys.
        for (ring in 1..3) for (i in 0 until 24) {
            val angle = (i + ring*.35)*2*PI/24
            val radial = side.scale(cos(angle)).add(up.scale(sin(angle)))
            val point = center.subtract(forward.scale(ring*size*.32)).add(radial.scale(ring*size*.24))
            level.addAlwaysVisibleParticle(smoke,true,point.x,point.y,point.z,
                radial.x*3,radial.y*3,radial.z*3)
        }
    }
}
