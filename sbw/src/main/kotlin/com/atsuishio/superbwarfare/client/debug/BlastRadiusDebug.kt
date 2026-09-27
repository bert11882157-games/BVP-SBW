package com.atsuishio.superbwarfare.client.debug

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.tools.blast.BlastParameters
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterClientCommandsEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import kotlin.math.cos
import kotlin.math.sin

/**
 * `/blastradius on|off` (client command): every TNT-equivalent detonation near the player shows its inner blast
 * radius (the fireball: blocks break, vehicles take true damage) as a red wire sphere and the infantry
 * (severe-collapse) radius as a faint orange one for [SHOW_TICKS], and prints both radii in chat.
 */
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object BlastRadiusDebug {
    private const val SHOW_TICKS = 160
    private const val SEGMENTS = 48
    private class Blast(val position: Vec3, val fireball: Double, val severe: Double, val until: Long)

    @Volatile
    var enabled = false
        private set
    private val blasts = ArrayList<Blast>()

    @SubscribeEvent
    fun onRegisterClientCommands(event: RegisterClientCommandsEvent) {
        event.dispatcher.register(Commands.literal("blastradius")
            .executes { toggle(!enabled); 1 }
            .then(Commands.argument("state", StringArgumentType.word()).suggests { _, b -> b.suggest("on").suggest("off").buildFuture() }
                .executes { ctx -> toggle(StringArgumentType.getString(ctx, "state").equals("on", true)); 1 }))
    }

    private fun toggle(on: Boolean) {
        enabled = on
        if (!on) synchronized(blasts) { blasts.clear() }
        Minecraft.getInstance().player?.displayClientMessage(Component.literal(
            if (on) "Blast radius display ON: detonations show the inner (fireball) radius in red, infantry radius in orange"
            else "Blast radius display OFF"), false)
    }

    /** Called for each fireball the server announces (radius = fireball radius, 0.5 * W^(1/3) m by default). */
    @JvmStatic
    fun record(position: Vec3, fireballRadius: Double) {
        if (!enabled) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val p = BlastParameters.DEFAULT
        val severe = fireballRadius * p.severeK / p.fireballK
        val kg = com.atsuishio.superbwarfare.tools.blast.BlastModel.chargeForRadius(fireballRadius, p.fireballK)
        synchronized(blasts) { blasts.add(Blast(position, fireballRadius, severe, level.gameTime + SHOW_TICKS)) }
        val player = mc.player ?: return
        if (player.position().distanceTo(position) < 512)
            player.displayClientMessage(Component.literal(String.format(
                "Blast %.1f kg TNT: inner radius %.1f m, infantry radius %.1f m (%.0f m away)",
                kg, fireballRadius, severe, player.position().distanceTo(position))), false)
    }

    @SubscribeEvent
    fun onRender(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !enabled) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val now = level.gameTime
        val list = synchronized(blasts) { blasts.removeIf { it.until < now }; blasts.toList() }
        if (list.isEmpty()) return
        val camera = event.camera.position
        val pose: PoseStack = event.poseStack
        val buffers = mc.renderBuffers().bufferSource()
        val lines = buffers.getBuffer(RenderType.lines())
        for (b in list) {
            pose.pushPose()
            pose.translate(b.position.x - camera.x, b.position.y - camera.y, b.position.z - camera.z)
            sphere(lines, pose, b.fireball.toFloat(), 1f, 0.15f, 0.1f, 1f)
            sphere(lines, pose, b.severe.toFloat(), 1f, 0.6f, 0.1f, 0.45f)
            pose.popPose()
        }
        buffers.endBatch(RenderType.lines())
    }

    /** Three great circles plus four latitude rings. */
    private fun sphere(c: VertexConsumer, pose: PoseStack, r: Float, red: Float, green: Float, blue: Float, alpha: Float) {
        val m = pose.last().pose(); val n = pose.last().normal()
        fun ring(point: (Double) -> Triple<Float, Float, Float>) {
            for (i in 0 until SEGMENTS) {
                val a = point(2 * Math.PI * i / SEGMENTS); val b = point(2 * Math.PI * (i + 1) / SEGMENTS)
                val dx = b.first - a.first; val dy = b.second - a.second; val dz = b.third - a.third
                val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
                c.vertex(m, a.first, a.second, a.third).color(red, green, blue, alpha).normal(n, dx / len, dy / len, dz / len).endVertex()
                c.vertex(m, b.first, b.second, b.third).color(red, green, blue, alpha).normal(n, dx / len, dy / len, dz / len).endVertex()
            }
        }
        ring { t -> Triple((r * cos(t)).toFloat(), 0f, (r * sin(t)).toFloat()) }
        ring { t -> Triple((r * cos(t)).toFloat(), (r * sin(t)).toFloat(), 0f) }
        ring { t -> Triple(0f, (r * cos(t)).toFloat(), (r * sin(t)).toFloat()) }
        for (lat in doubleArrayOf(-0.6, -0.3, 0.3, 0.6)) {
            val y = (r * sin(lat * Math.PI / 2 * 1.0)).toFloat(); val rr = (r * cos(lat * Math.PI / 2)).toFloat()
            ring { t -> Triple((rr * cos(t)).toFloat(), y, (rr * sin(t)).toFloat()) }
        }
    }
}
