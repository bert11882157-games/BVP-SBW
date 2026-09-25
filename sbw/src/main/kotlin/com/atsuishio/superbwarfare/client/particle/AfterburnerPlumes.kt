package com.atsuishio.superbwarfare.client.particle

import com.atsuishio.superbwarfare.Mod
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Afterburner plumes drawn as geometry every frame (not particles): a translucent flame body flowing aft from
 * the nozzle, a hot core, a train of shock diamonds that breathe and flicker, and a glowing nozzle ring when seen
 * from behind. Each nozzle lights up with a short ignition pop and plume overshoot, and on shutdown the diamonds
 * go first while the flame shrinks back into the nozzle.
 *
 * Aircraft renderers call [submit] once per frame and nozzle with the world-space nozzle pose at that frame's
 * partial tick; plumes that stop being submitted (aircraft out of view) are dropped.
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AfterburnerPlumes {
    private const val STARTUP_TICKS = 6.0
    private const val SHUTDOWN_TICKS = 9.0
    private const val IGNITION_TICKS = 6.0
    private const val MAX_PLUMES = 128
    private const val MAX_QUADS = 6000
    private const val FULL_BRIGHT = 0xF000F0

    private class Plume {
        var x = 0.0; var y = 0.0; var z = 0.0
        var dx = 0.0; var dy = 0.0; var dz = -1.0
        var radius = 0.4
        var active = false
        var intensity = 0.0
        var clock = Double.NaN
        var ignitedAt = -1e9
        var seenAt = 0.0
        val seed = Math.random() * 1000.0
    }

    private val plumes = HashMap<Long, Plume>()
    private var level: Any? = null
    private val quads = FloatArray(MAX_QUADS * QUAD_FLOATS)
    private var quadCount = 0

    /**
     * One nozzle for this frame. [time] is the client clock (game time + partial tick); position and direction are
     * world space, direction pointing aft (along the exhaust).
     */
    @JvmStatic
    fun submit(entityId: Int, outlet: Int, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double,
               nozzleRadius: Double, active: Boolean, time: Double) {
        val mc = Minecraft.getInstance()
        if (mc.level !== level) { plumes.clear(); level = mc.level }
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (!(length > 1e-6) || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val key = (entityId.toLong() shl 8) or (outlet.toLong() and 0xFF)
        val plume = plumes[key] ?: run {
            if (plumes.size >= MAX_PLUMES) return
            Plume().also { plumes[key] = it }
        }
        plume.x = x; plume.y = y; plume.z = z
        plume.dx = dx / length; plume.dy = dy / length; plume.dz = dz / length
        plume.radius = nozzleRadius.coerceIn(0.1, 1.5)
        if (active && !plume.active && plume.intensity < 0.35) plume.ignitedAt = time
        plume.active = active
        val step = if (plume.clock.isNaN()) 0.0 else (time - plume.clock).coerceIn(0.0, 4.0)
        plume.clock = time
        plume.intensity = if (active) min(1.0, plume.intensity + step / STARTUP_TICKS)
            else max(0.0, plume.intensity - step / SHUTDOWN_TICKS)
        plume.seenAt = time
    }

    @SubscribeEvent
    fun onRender(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        val mc = Minecraft.getInstance()
        val world = mc.level ?: return
        if (world !== level) { plumes.clear(); level = world; return }
        if (plumes.isEmpty()) return
        val time = world.gameTime + event.partialTick.toDouble()
        plumes.values.removeIf { time - it.seenAt > 3.0 }
        val fire = BlastSprites.fireballFrames() ?: return
        val glow = BlastSprites.glowSprites() ?: return
        val cam = event.camera.position
        quadCount = 0
        val ordered = plumes.values.filter { it.intensity > 0.005 || time - it.ignitedAt < IGNITION_TICKS }
            .sortedByDescending { (it.x - cam.x).pow(2) + (it.y - cam.y).pow(2) + (it.z - cam.z).pow(2) }
        for (plume in ordered) emit(plume, time, cam.x, cam.y, cam.z, fire, glow[0], glow[1], event.camera.leftVector.x().toDouble(),
            event.camera.leftVector.y().toDouble(), event.camera.leftVector.z().toDouble(), event.camera.upVector.x().toDouble(),
            event.camera.upVector.y().toDouble(), event.camera.upVector.z().toDouble())
        if (quadCount == 0) return
        draw(mc, event)
    }

    private fun emit(p: Plume, time: Double, cx: Double, cy: Double, cz: Double, fire: Array<TextureAtlasSprite>,
                     flash: TextureAtlasSprite, ring: TextureAtlasSprite,
                     lx: Double, ly: Double, lz: Double, ux: Double, uy: Double, uz: Double) {
        val r = p.radius
        val i = p.intensity
        val ox = p.x - cx; val oy = p.y - cy; val oz = p.z - cz
        // Ribbon side vector: perpendicular to the plume axis and the view ray.
        val vx = -ox; val vy = -oy; val vz = -oz
        val vl = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1e-6)
        var sx = p.dy * vz - p.dz * vy
        var sy = p.dz * vx - p.dx * vz
        var sz = p.dx * vy - p.dy * vx
        val sl = sqrt(sx * sx + sy * sy + sz * sz)
        val alongView = abs((p.dx * vx + p.dy * vy + p.dz * vz) / vl) // 1 = looking straight up the jet
        val ribbon = sl > 1e-4 * vl
        if (ribbon) { sx /= sl; sy /= sl; sz /= sl }
        val side = (1.0 - alongView * alongView).coerceIn(0.0, 1.0)

        val sinceIgnition = time - p.ignitedAt
        val ignition = if (sinceIgnition in 0.0..IGNITION_TICKS) 1.0 - sinceIgnition / IGNITION_TICKS else 0.0
        val flicker = 1.0 + 0.05 * sin(time * 2.7 + p.seed) + 0.04 * sin(time * 5.3 + p.seed * 1.7)
        val length = r * 9.5 * (0.3 + 0.7 * i) * (1.0 + 0.3 * ignition) * flicker

        // Ignition pop.
        if (ignition > 0.0) {
            billboard(ox + p.dx * r, oy + p.dy * r, oz + p.dz * r, r * (1.2 + 1.8 * (1.0 - ignition)), 0.0,
                1.6 * ignition, 1.1 * ignition, 0.55 * ignition, 0.0, flash, lx, ly, lz, ux, uy, uz)
        }
        if (i <= 0.005) return

        if (ribbon && side > 0.02) {
            // Flame body: overlapping segments flowing aft, fading and cooling toward the tail.
            val segments = 12
            val flow = (time / 5.0 + p.seed) % 1.0
            for (k in 0 until segments) {
                val phase = ((k.toDouble() / segments) + flow) % 1.0
                val s0 = phase * length
                val segLength = length / segments * 2.6 * BlastSprites.PUFF_FILL
                val t = phase
                val fade = (1.0 - t).pow(1.25) * min(1.0, phase * 8.0)
                val width = r * BlastSprites.PUFF_FILL * (1.05 - 0.5 * t) * (1.0 + 0.07 * sin(time * 1.9 + k * 2.3 + p.seed))
                val warm = 1.0 - t
                val k0 = 0.55 * i * fade * side
                val rr = k0 * 1.0; val gg = k0 * (0.36 + 0.34 * warm); val bb = k0 * (0.1 + 0.22 * warm)
                val frame = fire[(k + (time * 0.9).toInt()) % fire.size]
                segment(ox, oy, oz, p, s0 - segLength * 0.3, s0 + segLength * 0.7, width, width * 0.9, sx, sy, sz,
                    rr, gg, bb, 0.06 * i * fade * side, frame)
            }
            // Hot core close to the nozzle.
            val coreLength = length * 0.36
            segment(ox, oy, oz, p, -0.05 * r, coreLength, r * 0.6 * flicker, r * 0.25, sx, sy, sz,
                0.95 * i * side, 0.78 * i * side, 0.5 * i * side, 0.12 * i * side, flash)
            // Shock diamonds: stationary along the jet, breathing and flickering, strongest near the nozzle.
            val ringLevel = ((i - 0.45) / 0.45).coerceIn(0.0, 1.0)
            if (ringLevel > 0.0) {
                val spacing = r * 1.45 * (1.0 + 0.05 * sin(time * 0.8 + p.seed))
                for (n in 0 until 5) {
                    val centre = r * 0.75 + n * spacing + r * 0.06 * sin(time * 3.1 + n * 1.9 + p.seed)
                    if (centre > length * 0.9) break
                    val decay = 1.0 - 0.17 * n
                    val shimmer = 0.85 + 0.15 * sin(time * 4.3 + n * 2.9 + p.seed * 0.7)
                    val k1 = 0.85 * ringLevel * decay * shimmer * side
                    val halfLength = r * 0.42
                    val width = r * 0.72 * (1.0 - 0.1 * n)
                    segment(ox, oy, oz, p, centre - halfLength, centre + halfLength, width, width, sx, sy, sz,
                        k1, k1 * 0.76, k1 * 0.46, 0.05 * k1, flash)
                }
            }
        }
        // Nozzle seen from behind: a glowing ring with a hot centre; always a little heat glow.
        val rear = (alongView.pow(1.5) * i).coerceIn(0.0, 1.0)
        val ax = ox + p.dx * r * 0.15; val ay = oy + p.dy * r * 0.15; val az = oz + p.dz * r * 0.15
        billboard(ax, ay, az, r * 1.15, time * 0.02, 1.0 * rear, 0.62 * rear, 0.3 * rear, 0.1 * rear, ring,
            lx, ly, lz, ux, uy, uz)
        billboard(ax, ay, az, r * (0.7 + 0.5 * rear), 0.0, 0.7 * i * (0.4 + 0.6 * rear), 0.5 * i * (0.4 + 0.6 * rear),
            0.28 * i * (0.4 + 0.6 * rear), 0.0, flash, lx, ly, lz, ux, uy, uz)
    }

    /** Camera-facing ribbon along the plume axis from distance [s0] to [s1] aft of the nozzle. */
    private fun segment(ox: Double, oy: Double, oz: Double, p: Plume, s0: Double, s1: Double, w0: Double, w1: Double,
                        sx: Double, sy: Double, sz: Double, r: Double, g: Double, b: Double, cover: Double,
                        sprite: TextureAtlasSprite) {
        val ax = ox + p.dx * s0; val ay = oy + p.dy * s0; val az = oz + p.dz * s0
        val bx = ox + p.dx * s1; val by = oy + p.dy * s1; val bz = oz + p.dz * s1
        quad(ax - sx * w0, ay - sy * w0, az - sz * w0, ax + sx * w0, ay + sy * w0, az + sz * w0,
            bx + sx * w1, by + sy * w1, bz + sz * w1, bx - sx * w1, by - sy * w1, bz - sz * w1,
            r, g, b, cover, sprite)
    }

    private fun billboard(x: Double, y: Double, z: Double, half: Double, roll: Double, r: Double, g: Double, b: Double,
                          cover: Double, sprite: TextureAtlasSprite,
                          lx: Double, ly: Double, lz: Double, ux: Double, uy: Double, uz: Double) {
        val c = kotlin.math.cos(roll) * half
        val s = sin(roll) * half
        val ax = lx * c + ux * s; val ay = ly * c + uy * s; val az = lz * c + uz * s
        val bx = ux * c - lx * s; val by = uy * c - ly * s; val bz = uz * c - lz * s
        quad(x - ax - bx, y - ay - by, z - az - bz, x - ax + bx, y - ay + by, z - az + bz,
            x + ax + bx, y + ay + by, z + az + bz, x + ax - bx, y + ay - by, z + az - bz, r, g, b, cover, sprite)
    }

    private fun quad(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
                     x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double,
                     r: Double, g: Double, b: Double, cover: Double, sprite: TextureAtlasSprite) {
        if (quadCount >= MAX_QUADS) return
        if (r + g + b + cover < 0.004) return
        val o = quadCount * QUAD_FLOATS
        val q = quads
        q[o] = x0.toFloat(); q[o + 1] = y0.toFloat(); q[o + 2] = z0.toFloat()
        q[o + 3] = x1.toFloat(); q[o + 4] = y1.toFloat(); q[o + 5] = z1.toFloat()
        q[o + 6] = x2.toFloat(); q[o + 7] = y2.toFloat(); q[o + 8] = z2.toFloat()
        q[o + 9] = x3.toFloat(); q[o + 10] = y3.toFloat(); q[o + 11] = z3.toFloat()
        q[o + 12] = r.toFloat().coerceIn(0f, 1f); q[o + 13] = g.toFloat().coerceIn(0f, 1f)
        q[o + 14] = b.toFloat().coerceIn(0f, 1f); q[o + 15] = cover.toFloat().coerceIn(0f, 1f)
        q[o + 16] = sprite.u0; q[o + 17] = sprite.u1; q[o + 18] = sprite.v0; q[o + 19] = sprite.v1
        quadCount++
    }

    private fun draw(mc: Minecraft, event: RenderLevelStageEvent) {
        val program = BlastEffects.shader ?: return
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushPose()
        modelView.mulPoseMatrix(event.poseStack.last().pose())
        RenderSystem.applyModelViewMatrix()
        mc.gameRenderer.lightTexture().turnOnLightLayer()
        RenderSystem.setShader { program }
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES)
        RenderSystem.enableBlend()
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA)
        RenderSystem.depthMask(false)
        RenderSystem.enableDepthTest()
        RenderSystem.disableCull()
        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE)
        val q = quads
        for (n in 0 until quadCount) {
            val o = n * QUAD_FLOATS
            val r = q[o + 12]; val g = q[o + 13]; val b = q[o + 14]; val a = q[o + 15]
            val u0 = q[o + 16]; val u1 = q[o + 17]; val v0 = q[o + 18]; val v1 = q[o + 19]
            builder.vertex(q[o].toDouble(), q[o + 1].toDouble(), q[o + 2].toDouble()).uv(u1, v1).color(r, g, b, a).uv2(FULL_BRIGHT).endVertex()
            builder.vertex(q[o + 3].toDouble(), q[o + 4].toDouble(), q[o + 5].toDouble()).uv(u1, v0).color(r, g, b, a).uv2(FULL_BRIGHT).endVertex()
            builder.vertex(q[o + 6].toDouble(), q[o + 7].toDouble(), q[o + 8].toDouble()).uv(u0, v0).color(r, g, b, a).uv2(FULL_BRIGHT).endVertex()
            builder.vertex(q[o + 9].toDouble(), q[o + 10].toDouble(), q[o + 11].toDouble()).uv(u0, v1).color(r, g, b, a).uv2(FULL_BRIGHT).endVertex()
        }
        BufferUploader.drawWithShader(builder.end())
        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
        mc.gameRenderer.lightTexture().turnOffLightLayer()
        modelView.popPose()
        RenderSystem.applyModelViewMatrix()
    }

    private const val QUAD_FLOATS = 20
}
