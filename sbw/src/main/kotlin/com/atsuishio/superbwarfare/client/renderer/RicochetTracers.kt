package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.Mod.Companion.loc
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.network.message.receive.RicochetTracerMessage
import com.atsuishio.superbwarfare.resource.BedrockModelLoader
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Cosmetic ricochet tracers (owner 2026-09-30): the armor ate the round on the server; this client-only streak leaves
 * the plate along the reflected path at half speed, falls with the round's gravity, hits nothing and vanishes on
 * terrain or after [LIFE_TICKS]. Drawn like SBW's projectile streak (same model and glow).
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object RicochetTracers {
    private class Tracer(var position: Vec3, var velocity: Vec3, val red: Float, val green: Float, val blue: Float,
                         val width: Float, val gravity: Double) {
        var previous: Vec3 = position
        var age = 0
        var dead = false
    }

    const val LIFE_TICKS = 40
    private const val MAX_TRACERS = 256
    private val tracers = ArrayList<Tracer>()
    private var world: Any? = null
    private val TEXTURE = loc("textures/bedrock/projectile/projectile.png")
    private val RENDER_TYPE: RenderType by lazy { RenderType.energySwirl(TEXTURE, 15.0f, 15.0f) }

    @JvmStatic
    fun spawn(message: RicochetTracerMessage) {
        syncWorld()
        if (tracers.size >= MAX_TRACERS) tracers.removeAt(0)
        tracers += Tracer(message.position, message.velocity, message.red, message.green, message.blue,
            message.width, message.gravity.toDouble())
    }

    @JvmStatic fun count(): Int = tracers.size

    private fun syncWorld() {
        val level = Minecraft.getInstance().level
        if (world !== level) { tracers.clear(); world = level }
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        syncWorld()
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (mc.isPaused || tracers.isEmpty()) return
        for (t in tracers) {
            t.previous = t.position
            val next = t.position.add(t.velocity)
            val hit = level.clip(ClipContext(t.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                mc.player))
            if (hit.type != HitResult.Type.MISS) {
                t.position = hit.location
                t.dead = true
            } else {
                t.position = next
            }
            t.velocity = t.velocity.add(0.0, -t.gravity, 0.0)
            t.age++
        }
        tracers.removeIf { (it.dead && it.age > 1) || it.age >= LIFE_TICKS || it.position.y < level.minBuildHeight - 32 }
    }

    @SubscribeEvent
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES || tracers.isEmpty()) return
        val model = BedrockModelLoader.getModel(BedrockModelLoader.PROJECTILE_MODEL) ?: return
        val mc = Minecraft.getInstance()
        val camera = event.camera.position
        val buffers = mc.renderBuffers().bufferSource()
        val pose = event.poseStack
        val consumer = buffers.getBuffer(RENDER_TYPE)
        for (t in tracers) {
            val position = t.previous.lerp(t.position, event.partialTick.toDouble())
            if (position.distanceToSqr(camera) > 512.0 * 512.0) continue
            if (!event.frustum.isVisible(AABB(position, position).inflate(2.0))) continue
            val speed = t.velocity.length()
            if (speed < 1.0E-4) continue
            pose.pushPose()
            try {
                pose.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z)
                pose.mulPose(Axis.YP.rotationDegrees(VehicleVecUtils.getYRotFromVector(t.velocity).toFloat()))
                pose.mulPose(Axis.XP.rotationDegrees(-VehicleVecUtils.getXRotFromVector(t.velocity).toFloat()))
                pose.scale(t.width, t.width, (0.7 * speed).toFloat())
                model.renderToBuffer(pose, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    t.red, t.green, t.blue, 1.0f)
            } finally {
                pose.popPose()
            }
        }
        buffers.endBatch(RENDER_TYPE)
    }
}
