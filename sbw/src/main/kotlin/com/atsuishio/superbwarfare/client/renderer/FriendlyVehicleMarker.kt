package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import java.util.UUID

/** World geometry uses the ordinary depth buffer; it is never a through-wall HUD icon. */
object FriendlyVehicleMarker {
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
            poses.translate(0.0, (vehicle.boundingBox.maxY - vehicle.y).coerceIn(0.7, 16.0) + size + 0.25, 0.0)
            poses.mulPose(mc.gameRenderer.mainCamera.rotation())
            poses.scale(size, size, size)
            val pose = poses.last()
            val consumer = buffers.getBuffer(RenderType.lines())
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                val dx = x2 - x1; val dy = y2 - y1
                val norm = kotlin.math.sqrt(dx * dx + dy * dy)
                consumer.vertex(pose.pose(), x1, y1, 0F).color(70, 255, 95, 255)
                    .normal(pose.normal(), dx / norm, dy / norm, 0F).endVertex()
                consumer.vertex(pose.pose(), x2, y2, 0F).color(70, 255, 95, 255)
                    .normal(pose.normal(), dx / norm, dy / norm, 0F).endVertex()
            }
            line(0F, 0.5F, 0F, -0.5F)
            line(-0.45F, 0F, 0F, -0.5F)
            line(0.45F, 0F, 0F, -0.5F)
        } finally { poses.popPose() }
    }
}
