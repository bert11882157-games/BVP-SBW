package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import org.joml.Matrix4f
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small blue dot above friendly vehicles. World geometry uses the ordinary depth buffer; it is never a
 * through-wall HUD icon.
 */
object FriendlyVehicleMarker {
    private const val SEGMENTS = 16
    /** Dot radius and rim width, in marker units (the marker scales with distance, so it keeps a steady screen size). */
    private const val RADIUS = 0.3F
    private const val RIM = 0.07F
    private var level: Any? = null
    private var expires = 0L
    private var friendly: Set<UUID> = emptySet()
    fun clear() { level = null; expires = 0; friendly = emptySet() }

    fun receive(dimension: String, ids: List<String>) {
        val current = Minecraft.getInstance().level ?: return
        if (current.dimension().location().toString() != dimension) return
        friendly = ids.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
        level = current
        expires = current.gameTime + 30
    }

    fun render(vehicle: VehicleEntity, poses: PoseStack, buffers: MultiBufferSource) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || level !== mc.level || vehicle.level() !== level ||
            vehicle.level().gameTime > expires || vehicle.uuid !in friendly || vehicle.isRemoved ||
            vehicle.isWreck || vehicle.health <= 0 || vehicle.isInvisible || mc.player?.vehicle === vehicle ||
            buffers !== mc.renderBuffers().bufferSource()) return
        val distance = mc.gameRenderer.mainCamera.position.distanceTo(vehicle.position())
        val size = (distance * 0.008).coerceIn(0.35, 8.0).toFloat()
        poses.pushPose()
        try {
            poses.translate(0.0, (vehicle.boundingBox.maxY - vehicle.y).coerceIn(0.7, 16.0) + size * RADIUS + 0.25, 0.0)
            poses.mulPose(mc.gameRenderer.mainCamera.rotation())
            poses.scale(size, size, size)
            val matrix = poses.last().pose()
            val consumer = buffers.getBuffer(RenderType.debugQuads())
            // bright blue core inside a dark blue ring, so the dot reads against sky and terrain alike
            ring(consumer, matrix, 0F, RADIUS, 60, 150, 255, 255)
            ring(consumer, matrix, RADIUS, RADIUS + RIM, 20, 50, 120, 230)
        } finally { poses.popPose() }
    }

    /** A filled ring (inner 0 = disc) in the marker plane, as quads (debug quads are not back-face culled). */
    private fun ring(consumer: VertexConsumer, matrix: Matrix4f, inner: Float, outer: Float,
                     r: Int, g: Int, b: Int, a: Int) {
        for (i in 0 until SEGMENTS) {
            val c0 = cos(2.0 * Math.PI * i / SEGMENTS).toFloat(); val s0 = sin(2.0 * Math.PI * i / SEGMENTS).toFloat()
            val c1 = cos(2.0 * Math.PI * (i + 1) / SEGMENTS).toFloat(); val s1 = sin(2.0 * Math.PI * (i + 1) / SEGMENTS).toFloat()
            consumer.vertex(matrix, c0 * inner, s0 * inner, 0F).color(r, g, b, a).endVertex()
            consumer.vertex(matrix, c0 * outer, s0 * outer, 0F).color(r, g, b, a).endVertex()
            consumer.vertex(matrix, c1 * outer, s1 * outer, 0F).color(r, g, b, a).endVertex()
            consumer.vertex(matrix, c1 * inner, s1 * inner, 0F).color(r, g, b, a).endVertex()
        }
    }
}
