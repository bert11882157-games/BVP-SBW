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
        /** Core, flame, tail, diamonds RGB (12 floats). */
        var palette = DEFAULT_PALETTE
        /** Dry-thrust heat haze 0..1, eased; target set each frame from throttle and afterburner. */
        var haze = 0.0
        var hazeTarget = 0.0
        var hazeScale = 1.0
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
               nozzleRadius: Double, active: Boolean, time: Double) =
        submit(entityId, outlet, x, y, z, dx, dy, dz, nozzleRadius, active, time, null, 0.0, 1.0)

    /**
     * As above, with this engine's afterburner [palette] (core, flame, tail, diamonds RGB; null = neutral orange),
     * the accepted [throttle] 0..1 for the dry-thrust heat haze and its strength [hazeScale] (0 = none). The haze
     * fades out as the afterburner lights so it never competes with the flame.
     */
    @JvmStatic
    fun submit(entityId: Int, outlet: Int, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double,
               nozzleRadius: Double, active: Boolean, time: Double, palette: FloatArray?, throttle: Double,
               hazeScale: Double) {
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
        plume.palette = if (palette != null && palette.size >= 12) palette else DEFAULT_PALETTE
        plume.hazeScale = if (hazeScale.isFinite()) hazeScale.coerceIn(0.0, 2.0) else 1.0
        val thrust = if (throttle.isFinite()) throttle.coerceIn(0.0, 1.0) else 0.0
        // Idle engines still shimmer a little; the haze grows with thrust and gives way to the afterburner.
        plume.hazeTarget = (0.25 + 0.75 * thrust) * (1.0 - plume.intensity).pow(2.0)
        plume.haze += (plume.hazeTarget - plume.haze) * min(1.0, step / 8.0)
        plume.seenAt = time
        if (plume.intensity > 0.02) {
            val back = plume.radius * 3.0
            FxLights.sustain(key or 0x4100000000000000L, x + plume.dx * back, y + plume.dy * back, z + plume.dz * back,
                3.0 + 5.5 * plume.intensity, 12.0 * plume.intensity)
        }
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
        HeatHaze.render(mc, event, plumes.values, time)
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
        val length = r * 11.0 * (0.3 + 0.7 * i) * (1.0 + 0.3 * ignition) * flicker

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
                val width = r * BlastSprites.PUFF_FILL * (1.2 - 0.55 * t) * (1.0 + 0.07 * sin(time * 1.9 + k * 2.3 + p.seed))
                val warm = 1.0 - t
                val k0 = 0.8 * i * fade * side
                val pal = p.palette
                val rr = k0 * (pal[6] + (pal[3] - pal[6]) * warm)
                val gg = k0 * (pal[7] + (pal[4] - pal[7]) * warm)
                val bb = k0 * (pal[8] + (pal[5] - pal[8]) * warm)
                val frame = fire[(k + (time * 0.9).toInt()) % fire.size]
                segment(ox, oy, oz, p, s0 - segLength * 0.3, s0 + segLength * 0.7, width, width * 0.9, sx, sy, sz,
                    rr, gg, bb, 0.09 * i * fade * side, frame)
            }
            // Hot core close to the nozzle.
            val coreLength = length * 0.36
            segment(ox, oy, oz, p, -0.05 * r, coreLength, r * 0.6 * flicker, r * 0.25, sx, sy, sz,
                p.palette[0] * i * side, p.palette[1] * i * side, p.palette[2] * i * side, 0.12 * i * side, flash)
            // Shock diamonds: stationary along the jet, breathing and flickering, strongest near the nozzle.
            val ringLevel = ((i - 0.45) / 0.45).coerceIn(0.0, 1.0)
            if (ringLevel > 0.0) {
                val spacing = r * 1.45 * (1.0 + 0.05 * sin(time * 0.8 + p.seed))
                for (n in 0 until 5) {
                    val centre = r * 0.75 + n * spacing + r * 0.06 * sin(time * 3.1 + n * 1.9 + p.seed)
                    if (centre > length * 0.9) break
                    val decay = 1.0 - 0.17 * n
                    val shimmer = 0.85 + 0.15 * sin(time * 4.3 + n * 2.9 + p.seed * 0.7)
                    val k1 = 1.0 * ringLevel * decay * shimmer * side
                    val halfLength = r * 0.42
                    val width = r * 0.72 * (1.0 - 0.1 * n)
                    segment(ox, oy, oz, p, centre - halfLength, centre + halfLength, width, width, sx, sy, sz,
                        k1 * p.palette[9], k1 * p.palette[10], k1 * p.palette[11], 0.05 * k1, flash)
                }
            }
        }
        // Looking up the jet the ribbons turn edge-on, so the flame body and the diamonds are drawn as camera-facing
        // glows stacked along the axis instead (blended in as the ribbons fade out).
        val back = (1.0 - side).coerceIn(0.0, 1.0)
        if (back > 0.02) {
            val glows = 7
            for (k in 0 until glows) {
                val t = (k + 0.5) / glows
                val s0 = t * length * 0.8
                val fade = (1.0 - t).pow(1.1)
                val k2 = 0.55 * i * back * fade
                val pal = p.palette
                val w = 1.0 - t
                billboard(ox + p.dx * s0, oy + p.dy * s0, oz + p.dz * s0, r * (1.25 - 0.55 * t), time * 0.03 + k,
                    k2 * (pal[6] + (pal[3] - pal[6]) * w), k2 * (pal[7] + (pal[4] - pal[7]) * w),
                    k2 * (pal[8] + (pal[5] - pal[8]) * w), 0.05 * k2,
                    fire[(k + (time * 0.9).toInt()) % fire.size], lx, ly, lz, ux, uy, uz)
            }
            val ringLevel = ((i - 0.45) / 0.45).coerceIn(0.0, 1.0)
            if (ringLevel > 0.0) {
                val spacing = r * 1.45
                for (n in 0 until 5) {
                    val centre = r * 0.75 + n * spacing
                    if (centre > length * 0.9) break
                    val k3 = 0.7 * ringLevel * back * (1.0 - 0.17 * n)
                    billboard(ox + p.dx * centre, oy + p.dy * centre, oz + p.dz * centre, r * 0.8 * (1.0 - 0.1 * n),
                        0.0, k3 * p.palette[9], k3 * p.palette[10], k3 * p.palette[11], 0.0, flash, lx, ly, lz, ux, uy, uz)
                }
            }
        }
        // Nozzle seen from behind: a glowing ring with a hot centre; always a little heat glow.
        val rear = (alongView.pow(1.5) * i).coerceIn(0.0, 1.0)
        val ax = ox + p.dx * r * 0.15; val ay = oy + p.dy * r * 0.15; val az = oz + p.dz * r * 0.15
        val pal = p.palette
        billboard(ax, ay, az, r * 1.15, time * 0.02, pal[3] * rear, pal[4] * 0.85 * rear, pal[5] * 0.85 * rear,
            0.1 * rear, ring, lx, ly, lz, ux, uy, uz)
        val hot = i * (0.4 + 0.6 * rear)
        billboard(ax, ay, az, r * (0.7 + 0.5 * rear), 0.0, 0.72 * pal[0] * hot, 0.62 * pal[1] * hot,
            0.55 * pal[2] * hot, 0.0, flash, lx, ly, lz, ux, uy, uz)
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

    /** The previous hard-coded orange: core, flame, tail, diamonds. */
    @JvmField val DEFAULT_PALETTE = floatArrayOf(0.95f, 0.78f, 0.5f, 1f, 0.70f, 0.32f, 1f, 0.36f, 0.1f, 1f, 0.76f, 0.46f)

    /**
     * Dry-thrust heat haze: the scene behind each nozzle shimmers, drawn by refracting a copy of the frame
     * (shader `superbwarfare:heat_haze`) through soft ribbons along the exhaust. Faint at idle, clearer at full
     * dry thrust, gone while the afterburner burns.
     */
    private object HeatHaze {
        private var copy: com.mojang.blaze3d.pipeline.TextureTarget? = null
        private val q = FloatArray(512 * QUAD_FLOATS)
        private var n = 0

        fun render(mc: Minecraft, event: RenderLevelStageEvent, plumes: Collection<Plume>, time: Double) {
            val program = shader ?: return
            n = 0
            val cam = event.camera.position
            val lx = event.camera.leftVector.x().toDouble(); val ly = event.camera.leftVector.y().toDouble()
            val lz = event.camera.leftVector.z().toDouble()
            val ux = event.camera.upVector.x().toDouble(); val uy = event.camera.upVector.y().toDouble()
            val uz = event.camera.upVector.z().toDouble()
            for (p in plumes) {
                val h = p.haze * p.hazeScale
                if (h < 0.02) continue
                val ox = p.x - cam.x; val oy = p.y - cam.y; val oz = p.z - cam.z
                val dist2 = ox * ox + oy * oy + oz * oz
                if (dist2 > 160.0 * 160.0) continue
                val r = p.radius
                val vl = sqrt(dist2).coerceAtLeast(1e-6)
                var sx = p.dy * -oz - p.dz * -oy
                var sy = p.dz * -ox - p.dx * -oz
                var sz = p.dx * -oy - p.dy * -ox
                val sl = sqrt(sx * sx + sy * sy + sz * sz)
                val along = abs((p.dx * -ox + p.dy * -oy + p.dz * -oz) / vl)
                val side = (1.0 - along * along).coerceIn(0.0, 1.0)
                if (sl > 1e-4 * vl && side > 0.02) {
                    sx /= sl; sy /= sl; sz /= sl
                    val length = r * 10.0
                    val pieces = 4
                    for (k in 0 until pieces) {
                        val s0 = length * k / pieces
                        val s1 = length * (k + 1) / pieces
                        val w0 = r * (0.85 + 1.1 * k / pieces)
                        val w1 = r * (0.85 + 1.1 * (k + 1) / pieces)
                        val a0 = h * side * (1.0 - k.toDouble() / pieces)
                        val a1 = h * side * (1.0 - (k + 1).toDouble() / pieces)
                        val ax = ox + p.dx * s0; val ay = oy + p.dy * s0; val az = oz + p.dz * s0
                        val bx = ox + p.dx * s1; val by = oy + p.dy * s1; val bz = oz + p.dz * s1
                        add(ax - sx * w0, ay - sy * w0, az - sz * w0, 0f, k.toFloat() / pieces, a0,
                            ax + sx * w0, ay + sy * w0, az + sz * w0, 1f, k.toFloat() / pieces, a0,
                            bx + sx * w1, by + sy * w1, bz + sz * w1, 1f, (k + 1f) / pieces, a1,
                            bx - sx * w1, by - sy * w1, bz - sz * w1, 0f, (k + 1f) / pieces, a1)
                    }
                }
                val back = 1.0 - side
                if (back > 0.02) {
                    // Looking up the exhaust: a round shimmer over the nozzle.
                    val s = r * 1.6
                    val cx = ox + p.dx * r * 0.5; val cy = oy + p.dy * r * 0.5; val cz = oz + p.dz * r * 0.5
                    val a = h * back
                    add(cx - (lx + ux) * s, cy - (ly + uy) * s, cz - (lz + uz) * s, 0f, 0f, a,
                        cx - (lx - ux) * s, cy - (ly - uy) * s, cz - (lz - uz) * s, 0f, 1f, a,
                        cx + (lx + ux) * s, cy + (ly + uy) * s, cz + (lz + uz) * s, 1f, 1f, a,
                        cx + (lx - ux) * s, cy + (ly - uy) * s, cz + (lz - uz) * s, 1f, 0f, a)
                }
            }
            if (n == 0) return
            val main = mc.mainRenderTarget
            val w = main.width; val hgt = main.height
            val target = copy?.takeIf { it.width == w && it.height == hgt } ?: run {
                copy?.destroyBuffers()
                com.mojang.blaze3d.pipeline.TextureTarget(w, hgt, false, Minecraft.ON_OSX).also {
                    it.setFilterMode(org.lwjgl.opengl.GL11.GL_LINEAR); copy = it }
            }
            // Copy the frame drawn so far, then refract it through the haze ribbons.
            GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, main.frameBufferId)
            GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId)
            GlStateManager._glBlitFrameBuffer(0, 0, w, hgt, 0, 0, w, hgt, org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT,
                org.lwjgl.opengl.GL11.GL_NEAREST)
            main.bindWrite(false)
            val modelView = RenderSystem.getModelViewStack()
            modelView.pushPose()
            modelView.mulPoseMatrix(event.poseStack.last().pose())
            RenderSystem.applyModelViewMatrix()
            RenderSystem.setShader { program }
            RenderSystem.setShaderTexture(0, target.colorTextureId)
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.depthMask(false)
            RenderSystem.enableDepthTest()
            RenderSystem.disableCull()
            val builder = Tesselator.getInstance().builder
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
            for (k in 0 until n) {
                val o = k * 24
                for (v in 0 until 4) {
                    val b = o + v * 6
                    builder.vertex(q[b].toDouble(), q[b + 1].toDouble(), q[b + 2].toDouble()).uv(q[b + 3], q[b + 4])
                        .color(1f, 1f, 1f, q[b + 5].coerceIn(0f, 1f)).endVertex()
                }
            }
            BufferUploader.drawWithShader(builder.end())
            RenderSystem.enableCull()
            RenderSystem.depthMask(true)
            RenderSystem.disableBlend()
            modelView.popPose()
            RenderSystem.applyModelViewMatrix()
        }

        private fun add(x0: Double, y0: Double, z0: Double, u0: Float, v0: Float, a0: Double,
                        x1: Double, y1: Double, z1: Double, u1: Float, v1: Float, a1: Double,
                        x2: Double, y2: Double, z2: Double, u2: Float, v2: Float, a2: Double,
                        x3: Double, y3: Double, z3: Double, u3: Float, v3: Float, a3: Double) {
            if ((n + 1) * 24 > q.size) return
            val o = n * 24
            val xs = doubleArrayOf(x0, x1, x2, x3); val ys = doubleArrayOf(y0, y1, y2, y3)
            val zs = doubleArrayOf(z0, z1, z2, z3); val us = floatArrayOf(u0, u1, u2, u3)
            val vs = floatArrayOf(v0, v1, v2, v3); val al = doubleArrayOf(a0, a1, a2, a3)
            for (v in 0 until 4) {
                val b = o + v * 6
                q[b] = xs[v].toFloat(); q[b + 1] = ys[v].toFloat(); q[b + 2] = zs[v].toFloat()
                q[b + 3] = us[v]; q[b + 4] = vs[v]; q[b + 5] = al[v].toFloat()
            }
            n++
        }
    }

    /** `superbwarfare:heat_haze`, registered by [BlastShaders]. */
    @JvmStatic
    var shader: net.minecraft.client.renderer.ShaderInstance? = null
}
