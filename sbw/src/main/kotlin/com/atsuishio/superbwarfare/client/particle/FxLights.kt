package com.atsuishio.superbwarfare.client.particle

import com.atsuishio.superbwarfare.Mod
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Light cast by effects (fireballs, muzzle flashes, afterburners, missile motors), kept cheap on purpose: no block
 * relighting (no chunk rebuilds). A small, capped set of point lights, each a position, a reach and a block-light
 * level that fades out, is consulted in two places:
 *
 *  - entities and vehicles: their packed block light is raised to the effect light at their centre (one mixin on
 *    `EntityRenderer.getBlockLightLevel`, a loop over at most [MAX_LIGHTS] lights per rendered entity);
 *  - the ground: a warm, additive glow laid on the top surface of the block columns round each strong light
 *    (heightmap lookups, one small quad per column, at most [MAX_POOL_QUADS] quads a frame in one draw).
 *
 * Sustained lights (afterburners, motors) are refreshed by their owner every frame and vanish two ticks after the
 * owner stops; flashes decay on their own.
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object FxLights {
    private const val MAX_LIGHTS = 48
    private const val MAX_POOL_LIGHTS = 16
    private const val MAX_POOL_QUADS = 6000
    private const val MAX_POOL_RADIUS = 24
    private const val MAX_RADIUS = 48.0

    private class Light {
        var key = 0L
        var x = 0.0; var y = 0.0; var z = 0.0
        var radius = 0.0
        var level = 0.0          // block light 0..15 at the centre
        var born = 0.0           // client time (ticks) it was set
        var life = 0.0           // ticks for a flash to fade out; 0 = sustained
        var seen = 0.0           // sustained: last refresh
        var boost = 1.0          // ground-glow strength multiplier (a blast's first flash burns far brighter than 15)
        var r = 1f; var g = 0.5f; var b = 0.22f   // ground-glow colour
        fun current(now: Double): Double = if (life > 0) level * max(0.0, 1.0 - (now - born) / life)
            else if (now - seen <= 2.0) level else 0.0
    }

    private val lights = Array(MAX_LIGHTS) { Light() }
    private var count = 0
    private var level: Any? = null

    /** Client clock in ticks (game time + partial tick of the frame being drawn). */
    @Volatile private var now = 0.0

    /** Diagnostics (perf probe A/B): false switches effect lighting off entirely. */
    @JvmStatic @Volatile var enabled = true
    /** Diagnostics: render-thread time spent on effect lighting (ground glow), cumulative. */
    @JvmStatic var renderNanos = 0L; private set

    /** Diagnostics: lights alive and pool quads drawn last frame. */
    @JvmStatic var lastLights = 0; private set
    @JvmStatic var lastPoolQuads = 0; private set

    private fun clock(): Double {
        val mc = Minecraft.getInstance()
        val world = mc.level ?: return now
        if (world !== level) { level = world; count = 0 }
        return max(now, world.gameTime.toDouble())
    }

    private fun slot(key: Long, t: Double): Light? {
        if (key != 0L) for (i in 0 until count) if (lights[i].key == key) return lights[i]
        if (count < MAX_LIGHTS) return lights[count++]
        // replace the weakest
        var weakest: Light? = null; var w = Double.MAX_VALUE
        for (i in 0 until count) { val c = lights[i].current(t) * lights[i].radius; if (c < w) { w = c; weakest = lights[i] } }
        return weakest
    }

    /** A flash: [level] block light at the centre, reaching [radius] blocks, fading to nothing over [ticks]. */
    @JvmStatic
    fun flash(x: Double, y: Double, z: Double, radius: Double, level: Double, ticks: Double) =
        flash(x, y, z, radius, level, ticks, 1.0, 1f, 0.5f, 0.22f)

    /**
     * A flash with a ground-glow [boost] (1 = normal) and colour. Separate flashes are never merged with ones of a
     * different boost, so a blast's white-hot first flash and its lingering orange fireball light stay distinct.
     */
    @JvmStatic
    fun flash(x: Double, y: Double, z: Double, radius: Double, level: Double, ticks: Double,
              boost: Double, r: Float, g: Float, b: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || radius <= 0 || level <= 0) return
        val t = clock()
        // several particles of one burst (or rapid fire from one muzzle) share one light
        for (i in 0 until count) {
            val o = lights[i]
            if (o.key != 0L || o.life <= 0 || o.boost != boost) continue
            val dx = o.x - x; val dy = o.y - y; val dz = o.z - z
            if (dx * dx + dy * dy + dz * dz < 2.25) {
                o.radius = max(o.current(t) / max(o.level, 1e-3) * o.radius, min(radius, MAX_RADIUS))
                o.level = max(o.current(t), min(level, 15.0)); o.born = t; o.life = max(ticks, 0.5)
                return
            }
        }
        val l = slot(0L, t) ?: return
        l.key = 0L; l.x = x; l.y = y; l.z = z; l.radius = min(radius, MAX_RADIUS); l.level = min(level, 15.0)
        l.born = t; l.life = max(ticks, 0.5); l.seen = t
        l.boost = boost; l.r = r; l.g = g; l.b = b
    }

    /** A sustained light owned by [key] (refresh it every frame while it burns). */
    @JvmStatic
    fun sustain(key: Long, x: Double, y: Double, z: Double, radius: Double, level: Double) =
        sustain(key, x, y, z, radius, level, 1.0, 1f, 0.5f, 0.22f)

    /** A sustained light with a ground-glow [boost] and colour (afterburners burn brilliant and yellow-white). */
    @JvmStatic
    fun sustain(key: Long, x: Double, y: Double, z: Double, radius: Double, level: Double,
                boost: Double, r: Float, g: Float, b: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || radius <= 0 || level <= 0.05) return
        val t = clock()
        val l = slot(key, t) ?: return
        l.key = key; l.x = x; l.y = y; l.z = z; l.radius = min(radius, MAX_RADIUS); l.level = min(level, 15.0)
        l.life = 0.0; l.seen = t
        l.boost = boost; l.r = r; l.g = g; l.b = b
    }

    /** Effect block light (0..15) at a point: the brightest light reaching it, falling off smoothly with distance. */
    @JvmStatic
    fun levelAt(x: Double, y: Double, z: Double): Int {
        if (count == 0 || !enabled) return 0
        val t = now
        var best = 0.0
        for (i in 0 until count) {
            val l = lights[i]
            val dx = x - l.x; val dy = y - l.y; val dz = z - l.z
            val r2 = l.radius * l.radius
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 >= r2) continue
            val f = 1.0 - sqrt(d2 / r2)
            val v = l.current(t) * f * (2.0 - f)       // ease: bright core, soft edge
            if (v > best) best = v
        }
        return best.toInt().coerceIn(0, 15)
    }

    // ---------------------------------------------------------------- ground glow

    @SubscribeEvent
    fun onRender(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val started = System.nanoTime()
        try { renderGround(event) } finally { renderNanos += System.nanoTime() - started }
    }

    private fun renderGround(event: RenderLevelStageEvent) {
        val mc = Minecraft.getInstance()
        val world = mc.level ?: return
        now = world.gameTime + event.partialTick.toDouble()
        // drop dead lights
        var n = 0
        for (i in 0 until count) {
            if (lights[i].current(now) > 0.05) { if (n != i) { val tmp = lights[n]; lights[n] = lights[i]; lights[i] = tmp }; n++ }
        }
        count = n
        lastLights = count
        lastPoolQuads = 0
        if (count == 0 || !enabled) return
        val cam = event.camera.position
        // strongest lights first
        val order = (0 until count).sortedByDescending { lights[it].current(now) * lights[it].radius * lights[it].boost }
            .take(MAX_POOL_LIGHTS)
        var quads = 0
        val pose = event.poseStack.last().pose()
        for (i in order) {
            val l = lights[i]
            val lv = l.current(now)
            // small flashes (machine guns, autocannons) light the vehicles round them but lay no ground pool
            if (lv < 6.0 || l.radius < 8.0) continue
            val r = min(l.radius, MAX_POOL_RADIUS.toDouble())
            // a soft warm tint on the ground, not a painted patch: strong only right under a big fireball, and far
            // brighter for a boosted light (a blast's first flash, an afterburner)
            val strength = ((lv / 15.0) * (lv / 15.0) * 0.32 * l.boost).coerceAtMost(1.6)
            val bx0 = kotlin.math.floor(l.x - r).toInt(); val bx1 = kotlin.math.floor(l.x + r).toInt()
            val bz0 = kotlin.math.floor(l.z - r).toInt(); val bz1 = kotlin.math.floor(l.z + r).toInt()
            for (bx in bx0..bx1) for (bz in bz0..bz1) {
                if (quads >= MAX_POOL_QUADS) break
                val top = world.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz).toDouble()
                val dy = l.y - top
                if (dy < -1.0 || dy > r) continue                 // the surface must be under (or at) the light
                val o = quads * 12
                pc[quads * 3] = l.r; pc[quads * 3 + 1] = l.g; pc[quads * 3 + 2] = l.b
                val a00 = pool(l, r, strength, bx.toDouble(), top, bz.toDouble())
                val a10 = pool(l, r, strength, bx + 1.0, top, bz.toDouble())
                val a11 = pool(l, r, strength, bx + 1.0, top, bz + 1.0)
                val a01 = pool(l, r, strength, bx.toDouble(), top, bz + 1.0)
                if (a00 + a10 + a11 + a01 < 0.004f) continue
                val yf = (top + 0.015 - cam.y).toFloat()
                val x0 = (bx - cam.x).toFloat(); val x1 = (bx + 1 - cam.x).toFloat()
                val z0 = (bz - cam.z).toFloat(); val z1 = (bz + 1 - cam.z).toFloat()
                pq[o] = x0; pq[o + 1] = z0; pq[o + 2] = a00
                pq[o + 3] = x0; pq[o + 4] = z1; pq[o + 5] = a01
                pq[o + 6] = x1; pq[o + 7] = z1; pq[o + 8] = a11
                pq[o + 9] = x1; pq[o + 10] = z0; pq[o + 11] = a10
                py[quads] = yf
                quads++
            }
        }
        lastPoolQuads = quads
        if (quads == 0) return
        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        for (q in 0 until quads) {
            val o = q * 12
            for (v in 0 until 4) {
                // alpha above 1 is split into brighter colour: additive blending, so a boosted pool reads as more light
                val a = pq[o + v * 3 + 2]
                val over = kotlin.math.max(1f, a)
                builder.vertex(pose, pq[o + v * 3], py[q], pq[o + v * 3 + 1])
                    .color(min(1f, pc[q * 3] * over), min(1f, pc[q * 3 + 1] * over), min(1f, pc[q * 3 + 2] * over), min(1f, a))
                    .endVertex()
            }
        }
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }
        RenderSystem.enableBlend()
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
        RenderSystem.depthMask(false)
        RenderSystem.enableDepthTest()
        RenderSystem.disableCull()
        BufferUploader.drawWithShader(builder.end())
        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
    }

    private val pq = FloatArray(MAX_POOL_QUADS * 12)
    private val py = FloatArray(MAX_POOL_QUADS)
    private val pc = FloatArray(MAX_POOL_QUADS * 3)

    // ---------------------------------------------------------------- missile and rocket motors

    @SubscribeEvent
    fun onClientTick(event: net.minecraftforge.event.TickEvent.ClientTickEvent) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        val world = mc.level ?: return
        val cam = mc.gameRenderer.mainCamera.position
        for (entity in world.entitiesForRendering()) {
            if (entity !is net.minecraft.world.entity.projectile.Projectile) continue
            val name = entity.javaClass.simpleName
            if (!(name.contains("Missile") || name.contains("Rocket"))) continue
            if (entity.distanceToSqr(cam) > 160.0 * 160.0 || entity.deltaMovement.lengthSqr() < 0.09) continue
            val back = entity.deltaMovement.normalize().scale(-0.6)
            sustain(0x4D0000000000L or entity.id.toLong(), entity.x + back.x, entity.y + back.y, entity.z + back.z, 6.0, 12.0)
        }
    }

    private fun pool(l: Light, r: Double, strength: Double, x: Double, y: Double, z: Double): Float {
        val dx = x - l.x; val dy = (y - l.y) * 1.5; val dz = z - l.z
        val d = sqrt(dx * dx + dy * dy + dz * dz) / r
        if (d >= 1.0) return 0f
        val f = 1.0 - d
        return (strength * f * f * f).toFloat()        // falls off fast: a pool round the source, no hard rim
    }
}
