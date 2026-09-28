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
 *    (heightmap lookups, one small quad per column, at most [MAX_POOL_QUADS] quads a frame in one draw);
 *  - interiors: a light with blocks overhead (inside a building, under a roof or a bridge) has no heightmap surface
 *    to light, so the solid blocks round it are scanned once into an occupancy grid, and every block face that faces
 *    the light across open air, with nothing solid in between, gets the glow instead (floor, walls and ceiling).
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
    /**
     * Column tops: MOTION_BLOCKING, the heightmap the server sends to clients (MOTION_BLOCKING_NO_LEAVES is not sent;
     * on a client it is an unprimed, partly updated map, which cut whole strips out of the glow in r50). Leaves are
     * stepped through by [top] where they matter.
     */
    private val SURFACE = Heightmap.Types.MOTION_BLOCKING
    private val probe = net.minecraft.core.BlockPos.MutableBlockPos()

    /**
     * The first free y above column (x, z) for effect light: the heightmap top, but a leaf canopy above [aboveY] is
     * looked through (at most 16 leaf blocks), so a tree does not hang glowing walls in the air or roof the light.
     */
    private fun top(world: net.minecraft.world.level.Level, x: Int, z: Int, aboveY: Int): Int {
        var top = world.getHeight(SURFACE, x, z)
        if (top <= aboveY) return top
        var steps = 0
        while (steps++ < 16 && world.getBlockState(probe.set(x, top - 1, z))
                .`is`(net.minecraft.tags.BlockTags.LEAVES)) {
            top--
            while (steps++ < 32 && top > aboveY && world.getBlockState(probe.set(x, top - 1, z)).isAir) top--
        }
        return top
    }

    /** Whether the block topping column (x, z) is a full cube (fences, panes, walls, slabs take no glow of their own). */
    private fun fullTop(world: net.minecraft.world.level.Level, x: Int, top: Int, z: Int): Boolean {
        val state = world.getBlockState(probe.set(x, top - 1, z))
        return state.isSolidRender(world, probe)
    }

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
        // interior light: faces lit (corners xyz x4, normal xyz: 15 floats each), built for the block the light is in
        var faceKey = Long.MIN_VALUE
        var faces = FloatArray(0)
        var faceCount = 0
        var covered = false
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

    /**
     * Multiplier for self-lit effect quads (fire, flash, afterburner flame; shader uniform `GlowBoost`): a little
     * brighter in daylight, and much brighter as the surroundings get dark, so a fireball or a lit afterburner glows at
     * night instead of looking like the same pale sprite.
     */
    @JvmStatic
    fun glowBoost(partialTick: Float): Float {
        val mc = Minecraft.getInstance()
        val world = mc.level ?: return 1.15f
        val pos = net.minecraft.core.BlockPos.containing(mc.gameRenderer.mainCamera.position)
        val sky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos) / 15f *
            world.getSkyDarken(partialTick).coerceIn(0f, 1f)
        val block = world.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos) / 15f * 0.8f
        val dark = 1f - max(sky, block).coerceIn(0f, 1f)
        return 1.15f + 1.35f * dark
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
        l.faceKey = Long.MIN_VALUE; l.faceCount = 0
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
        if (l.key != key) { l.faceKey = Long.MIN_VALUE; l.faceCount = 0 }
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
        try { renderGround(event); renderGlows(event) } finally { renderNanos += System.nanoTime() - started }
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
        var walls = 0
        var builds = 0
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
            // Indoors (blocks overhead): light the faces round the light instead of the heightmap surface.
            val lbx = kotlin.math.floor(l.x).toInt(); val lby = kotlin.math.floor(l.y).toInt(); val lbz = kotlin.math.floor(l.z).toInt()
            val key = net.minecraft.core.BlockPos.asLong(lbx, lby, lbz)
            if (l.faceKey != key) {
                // a blast's flash and fireball light share a block: reuse the faces already found for it
                var twin: Light? = null
                for (j in 0 until count) { val o = lights[j]; if (o !== l && o.faceKey == key) { twin = o; break } }
                if (twin != null) {
                    l.covered = twin.covered; l.faceCount = twin.faceCount
                    if (l.faces.size < twin.faceCount * 15) l.faces = FloatArray(MAX_INTERIOR_FACES * 15)
                    System.arraycopy(twin.faces, 0, l.faces, 0, twin.faceCount * 15)
                } else {
                    if (builds >= MAX_INTERIOR_BUILDS) continue   // next frame; bounds the one-off scan cost
                    l.covered = top(world, lbx, lbz, lby + 1) > lby + 1
                    if (l.covered) { buildInterior(world, l, lbx, lby, lbz); builds++ } else l.faceCount = 0
                }
                l.faceKey = key
            }
            if (l.covered) {
                val ri = min(r, INTERIOR_RADIUS.toDouble())
                val si = (strength * 1.25).coerceAtMost(1.8)
                val f = l.faces
                for (k in 0 until l.faceCount) {
                    if (walls >= MAX_WALL_QUADS) break
                    val o = k * 15
                    val nx = f[o + 12]; val ny = f[o + 13]; val nz = f[o + 14]
                    val w = walls * 16
                    var sum = 0f
                    for (v in 0 until 4) {
                        val vx = f[o + v * 3].toDouble(); val vy = f[o + v * 3 + 1].toDouble(); val vz = f[o + v * 3 + 2].toDouble()
                        val a = interior(l, ri, si, vx, vy, vz, nx, ny, nz)
                        wq[w + v * 4] = (vx - cam.x).toFloat(); wq[w + v * 4 + 1] = (vy - cam.y).toFloat()
                        wq[w + v * 4 + 2] = (vz - cam.z).toFloat(); wq[w + v * 4 + 3] = a
                        sum += a
                    }
                    if (sum < 0.004f) continue
                    wc[walls * 3] = l.r; wc[walls * 3 + 1] = l.g; wc[walls * 3 + 2] = l.b
                    walls++
                }
                continue
            }
            val bx0 = kotlin.math.floor(l.x - r).toInt(); val bx1 = kotlin.math.floor(l.x + r).toInt()
            val bz0 = kotlin.math.floor(l.z - r).toInt(); val bz1 = kotlin.math.floor(l.z + r).toInt()
            // Column tops once per light, with a one-column border for the side faces.
            val gw = bx1 - bx0 + 3
            val gh = bz1 - bz0 + 3
            if (heights.size < gw * gh) heights = IntArray(gw * gh)
            for (gx in 0 until gw) for (gz in 0 until gh)
                heights[gx * gh + gz] = top(world, bx0 - 1 + gx, bz0 - 1 + gz, lby + 1)
            buildHorizon(l, r, bx0, bz0, gw, gh)
            for (bx in bx0..bx1) for (bz in bz0..bz1) {
                if (quads >= MAX_POOL_QUADS) break
                val topI = heights[(bx - bx0 + 1) * gh + (bz - bz0 + 1)]
                val top = topI.toDouble()
                // A thin top block (fence, pane, wall, slab) keeps its place in the horizon but takes no glow: full-width
                // quads on it read as a glowing box round the fence or window.
                if (topI - l.y < r + 1 && l.y - topI < r + 1 && !fullTop(world, bx, topI, bz)) continue
                // Side faces: where a neighbouring column is lower, this column shows a vertical face; light it when it
                // faces the light (block steps, walls, the sides of a crater rim).
                for (d in 0 until 4) {
                    if (walls >= MAX_WALL_QUADS) break
                    val dx = SIDE_X[d]; val dz = SIDE_Z[d]
                    val lower = heights[(bx - bx0 + 1 + dx) * gh + (bz - bz0 + 1 + dz)]
                    if (lower >= topI) continue
                    // the face plane and whether the light is in front of it
                    val fx = if (dx > 0) bx + 1.0 else if (dx < 0) bx.toDouble() else 0.0
                    val fz = if (dz > 0) bz + 1.0 else if (dz < 0) bz.toDouble() else 0.0
                    val facing = if (dx != 0) (l.x - fx) * dx else (l.z - fz) * dz
                    if (facing <= 0.05) continue
                    val y0 = max(lower.toDouble(), l.y - r); val y1 = min(top, l.y + r)
                    if (y1 - y0 < 0.05) continue
                    // edge end points along the face
                    val ax: Double; val az: Double; val cx: Double; val cz: Double
                    if (dx != 0) { ax = fx; cx = fx; az = bz.toDouble(); cz = bz + 1.0 } else { az = fz; cz = fz; ax = bx.toDouble(); cx = bx + 1.0 }
                    // a hair off the face, toward the light
                    val ox = dx * 0.015; val oz = dz * 0.015
                    // One quad per block row, so a tall face (cliff, building) gets its hot spot at the light's height
                    // instead of one corner-lit gradient that went black when all four corners were out of reach; a row
                    // hidden from the light behind higher terrain (the horizon) stays dark.
                    val midX = (ax + cx) * 0.5 + dx * 0.05; val midZ = (az + cz) * 0.5 + dz * 0.05
                    var ya = y0
                    while (ya < y1 - 1e-6 && walls < MAX_WALL_QUADS) {
                        val yb = min(y1, kotlin.math.floor(ya) + 1.0)
                        if (!occluded(l, midX, yb, midZ)) {
                            val w00 = wall(l, r, strength, ax, ya, az); val w01 = wall(l, r, strength, ax, yb, az)
                            val w11 = wall(l, r, strength, cx, yb, cz); val w10 = wall(l, r, strength, cx, ya, cz)
                            if (w00 + w01 + w11 + w10 >= 0.004f) {
                                val w = walls * 16
                                wq[w] = (ax + ox - cam.x).toFloat(); wq[w + 1] = (ya - cam.y).toFloat(); wq[w + 2] = (az + oz - cam.z).toFloat(); wq[w + 3] = w00
                                wq[w + 4] = (ax + ox - cam.x).toFloat(); wq[w + 5] = (yb - cam.y).toFloat(); wq[w + 6] = (az + oz - cam.z).toFloat(); wq[w + 7] = w01
                                wq[w + 8] = (cx + ox - cam.x).toFloat(); wq[w + 9] = (yb - cam.y).toFloat(); wq[w + 10] = (cz + oz - cam.z).toFloat(); wq[w + 11] = w11
                                wq[w + 12] = (cx + ox - cam.x).toFloat(); wq[w + 13] = (ya - cam.y).toFloat(); wq[w + 14] = (cz + oz - cam.z).toFloat(); wq[w + 15] = w10
                                wc[walls * 3] = l.r; wc[walls * 3 + 1] = l.g; wc[walls * 3 + 2] = l.b
                                walls++
                            }
                        }
                        ya = yb
                    }
                }
                val dy = l.y - top
                if (dy < -1.0 || dy > r) continue                 // the surface must be under (or at) the light
                if (occluded(l, bx + 0.5, top, bz + 0.5)) continue   // behind a wall or ridge from the light
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
        lastPoolQuads = quads + walls
        if (quads + walls == 0) return
        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        for (q in 0 until walls) {
            val w = q * 16
            for (v in 0 until 4) {
                val a = wq[w + v * 4 + 3]
                val over = kotlin.math.max(1f, a)
                builder.vertex(pose, wq[w + v * 4], wq[w + v * 4 + 1], wq[w + v * 4 + 2])
                    .color(min(1f, wc[q * 3] * over), min(1f, wc[q * 3 + 1] * over), min(1f, wc[q * 3 + 2] * over), min(1f, a))
                    .endVertex()
            }
        }
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

    // ---------------------------------------------------------------- distant light bursts

    private const val GLOW_NEAR = 48.0
    private const val GLOW_FULL = 96.0
    private const val GLOW_SEGMENTS = 8

    /**
     * Past [GLOW_NEAR] blocks the ground glow shrinks to nothing on screen, and past the loaded chunks there is no
     * terrain to paint at all (the far terrain has no heightmap here), so each light is also drawn as one soft
     * additive disc facing the camera: a blast, a fireball, a burning motor or an afterburner reads as a burst of
     * light across the far view. At most [MAX_LIGHTS] eight-triangle fans in one draw; depth-tested (terrain hides
     * it), never depth-written. Fades in from [GLOW_NEAR] to [GLOW_FULL].
     */
    private fun renderGlows(event: RenderLevelStageEvent) {
        if (count == 0 || !enabled) return
        val camera = event.camera
        val cam = camera.position
        val left = camera.leftVector; val up = camera.upVector
        val pose = event.poseStack.last().pose()
        var builder: com.mojang.blaze3d.vertex.BufferBuilder? = null
        for (i in 0 until count) {
            val l = lights[i]
            val lv = l.current(now)
            if (lv < 3.0) continue
            val dx = l.x - cam.x; val dy = l.y - cam.y; val dz = l.z - cam.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < GLOW_NEAR) continue
            val fade = ((dist - GLOW_NEAR) / (GLOW_FULL - GLOW_NEAR)).coerceIn(0.0, 1.0)
            val a = ((lv / 15.0) * (lv / 15.0) * 0.55 * min(l.boost, 2.0) * fade).toFloat().coerceAtMost(1f)
            if (a < 0.01f) continue
            // a visible disc: a fraction of the light's reach, never smaller than ~0.15 degrees across
            val size = max(l.radius * 0.3 * (lv / 15.0), dist * 0.0013).toFloat()
            if (builder == null) {
                builder = Tesselator.getInstance().builder
                builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)
            }
            val cx = dx.toFloat(); val cy = dy.toFloat(); val cz = dz.toFloat()
            val over = max(1f, a)
            val r = min(1f, l.r * over); val g = min(1f, l.g * over); val b = min(1f, l.b * over)
            for (k in 0 until GLOW_SEGMENTS) {
                val a0 = k * (2.0 * Math.PI / GLOW_SEGMENTS); val a1 = (k + 1) * (2.0 * Math.PI / GLOW_SEGMENTS)
                val c0 = kotlin.math.cos(a0).toFloat() * size; val s0 = kotlin.math.sin(a0).toFloat() * size
                val c1 = kotlin.math.cos(a1).toFloat() * size; val s1 = kotlin.math.sin(a1).toFloat() * size
                builder.vertex(pose, cx, cy, cz).color(r, g, b, a).endVertex()
                builder.vertex(pose, cx + left.x() * c0 + up.x() * s0, cy + left.y() * c0 + up.y() * s0,
                    cz + left.z() * c0 + up.z() * s0).color(r, g, b, 0f).endVertex()
                builder.vertex(pose, cx + left.x() * c1 + up.x() * s1, cy + left.y() * c1 + up.y() * s1,
                    cz + left.z() * c1 + up.z() * s1).color(r, g, b, 0f).endVertex()
            }
        }
        builder ?: return
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

    // ---------------------------------------------------------------- interiors

    private const val INTERIOR_RADIUS = 12
    private const val MAX_INTERIOR_BUILDS = 2
    private const val MAX_INTERIOR_FACES = 2500
    private val DIR = arrayOf(intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
        intArrayOf(0, 0, 1), intArrayOf(0, 0, -1))
    private var occ = java.util.BitSet()
    private val scanPos = net.minecraft.core.BlockPos.MutableBlockPos()

    /**
     * Scans the full solid blocks within [INTERIOR_RADIUS] of the light into an occupancy grid, then keeps every face of
     * a solid block that faces the light across an open cell and has a clear line (grid DDA) back to it. Runs once per
     * light and block position (a flash never moves; a sustained light rebuilds when it enters another block).
     */
    private fun buildInterior(world: net.minecraft.client.multiplayer.ClientLevel, l: Light, lbx: Int, lby: Int, lbz: Int) {
        val rr = INTERIOR_RADIUS
        val n = rr * 2 + 3                                         // one-cell border for the neighbour tests
        val x0 = lbx - rr - 1; val y0 = lby - rr - 1; val z0 = lbz - rr - 1
        occ.clear()
        for (gx in 0 until n) for (gy in 0 until n) {
            val wy = y0 + gy
            if (world.isOutsideBuildHeight(wy)) continue
            for (gz in 0 until n) {
                scanPos.set(x0 + gx, wy, z0 + gz)
                val state = world.getBlockState(scanPos)
                if (!state.isAir && state.isSolidRender(world, scanPos)) occ.set((gx * n + gy) * n + gz)
            }
        }
        // the light may sit inside a block (a blast centre raised into a low ceiling): start from the nearest open cell
        var lx = l.x; var ly = l.y; var lz = l.z
        run {
            var gy = lby - y0
            val gx = lbx - x0; val gz = lbz - z0
            var steps = 0
            while (steps < 3 && gy > 0 && occ[(gx * n + gy) * n + gz]) { gy--; steps++ }
            if (steps > 0) ly = y0 + gy + 0.5
        }
        if (l.faces.size < MAX_INTERIOR_FACES * 15) l.faces = FloatArray(MAX_INTERIOR_FACES * 15)
        val f = l.faces
        var count = 0
        val r2 = (rr + 0.5) * (rr + 0.5)
        outer@ for (gx in 1 until n - 1) for (gy in 1 until n - 1) for (gz in 1 until n - 1) {
            if (!occ[(gx * n + gy) * n + gz]) continue
            val bx = x0 + gx; val by = y0 + gy; val bz = z0 + gz
            val cx = bx + 0.5 - lx; val cy = by + 0.5 - ly; val cz = bz + 0.5 - lz
            if (cx * cx + cy * cy + cz * cz > r2) continue
            for (d in DIR) {
                val ax = gx + d[0]; val ay = gy + d[1]; val az = gz + d[2]
                if (occ[(ax * n + ay) * n + az]) continue
                // face centre, and the light must be on its open side
                val fx = bx + 0.5 + d[0] * 0.5; val fy = by + 0.5 + d[1] * 0.5; val fz = bz + 0.5 + d[2] * 0.5
                if ((lx - fx) * d[0] + (ly - fy) * d[1] + (lz - fz) * d[2] <= 0.05) continue
                if (!clear(n, x0, y0, z0, lx, ly, lz, fx + d[0] * 0.05, fy + d[1] * 0.05, fz + d[2] * 0.05)) continue
                val o = count * 15
                // corners of the face, a hair off it toward the light
                val px = fx + d[0] * 0.015; val py = fy + d[1] * 0.015; val pz = fz + d[2] * 0.015
                val ux: Double; val uy: Double; val uz: Double; val vx: Double; val vy: Double; val vz: Double
                if (d[0] != 0) { ux = 0.0; uy = 0.5; uz = 0.0; vx = 0.0; vy = 0.0; vz = 0.5 }
                else if (d[1] != 0) { ux = 0.5; uy = 0.0; uz = 0.0; vx = 0.0; vy = 0.0; vz = 0.5 }
                else { ux = 0.5; uy = 0.0; uz = 0.0; vx = 0.0; vy = 0.5; vz = 0.0 }
                f[o] = (px - ux - vx).toFloat(); f[o + 1] = (py - uy - vy).toFloat(); f[o + 2] = (pz - uz - vz).toFloat()
                f[o + 3] = (px + ux - vx).toFloat(); f[o + 4] = (py + uy - vy).toFloat(); f[o + 5] = (pz + uz - vz).toFloat()
                f[o + 6] = (px + ux + vx).toFloat(); f[o + 7] = (py + uy + vy).toFloat(); f[o + 8] = (pz + uz + vz).toFloat()
                f[o + 9] = (px - ux + vx).toFloat(); f[o + 10] = (py - uy + vy).toFloat(); f[o + 11] = (pz - uz + vz).toFloat()
                f[o + 12] = d[0].toFloat(); f[o + 13] = d[1].toFloat(); f[o + 14] = d[2].toFloat()
                if (++count >= MAX_INTERIOR_FACES) break@outer
            }
        }
        l.faceCount = count
    }

    /** No solid grid cell on the segment from the light to (tx, ty, tz), the light's own cell excepted (voxel DDA). */
    private fun clear(n: Int, x0: Int, y0: Int, z0: Int, sx: Double, sy: Double, sz: Double,
                      tx: Double, ty: Double, tz: Double): Boolean {
        var ix = kotlin.math.floor(sx).toInt(); var iy = kotlin.math.floor(sy).toInt(); var iz = kotlin.math.floor(sz).toInt()
        val ex = kotlin.math.floor(tx).toInt(); val ey = kotlin.math.floor(ty).toInt(); val ez = kotlin.math.floor(tz).toInt()
        val dx = tx - sx; val dy = ty - sy; val dz = tz - sz
        val stepX = if (dx > 0) 1 else -1; val stepY = if (dy > 0) 1 else -1; val stepZ = if (dz > 0) 1 else -1
        val tdx = if (dx != 0.0) kotlin.math.abs(1.0 / dx) else Double.MAX_VALUE
        val tdy = if (dy != 0.0) kotlin.math.abs(1.0 / dy) else Double.MAX_VALUE
        val tdz = if (dz != 0.0) kotlin.math.abs(1.0 / dz) else Double.MAX_VALUE
        var tmx = if (dx != 0.0) ((if (dx > 0) ix + 1 - sx else sx - ix) * tdx) else Double.MAX_VALUE
        var tmy = if (dy != 0.0) ((if (dy > 0) iy + 1 - sy else sy - iy) * tdy) else Double.MAX_VALUE
        var tmz = if (dz != 0.0) ((if (dz > 0) iz + 1 - sz else sz - iz) * tdz) else Double.MAX_VALUE
        var guard = 0
        while ((ix != ex || iy != ey || iz != ez) && guard++ < n * 3) {
            if (tmx < tmy && tmx < tmz) { ix += stepX; tmx += tdx }
            else if (tmy < tmz) { iy += stepY; tmy += tdy }
            else { iz += stepZ; tmz += tdz }
            val gx = ix - x0; val gy = iy - y0; val gz = iz - z0
            if (gx < 0 || gy < 0 || gz < 0 || gx >= n || gy >= n || gz >= n) return true
            if (occ[(gx * n + gy) * n + gz]) return false
        }
        return true
    }

    /** Light on an interior face corner: 3-D falloff, softer than outdoors (small rooms), times how squarely it faces. */
    private fun interior(l: Light, r: Double, strength: Double, x: Double, y: Double, z: Double,
                         nx: Float, ny: Float, nz: Float): Float {
        val dx = l.x - x; val dy = l.y - y; val dz = l.z - z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        val d = dist / r
        if (d >= 1.0) return 0f
        val fall = (1.0 - d) * (1.0 - d)
        val cos = if (dist > 1e-6) ((dx * nx + dy * ny + dz * nz) / dist).coerceIn(0.0, 1.0) else 1.0
        return (strength * fall * (0.35 + 0.65 * cos)).toFloat()
    }

    private val pq = FloatArray(MAX_POOL_QUADS * 12)
    private val py = FloatArray(MAX_POOL_QUADS)
    private val pc = FloatArray(MAX_POOL_QUADS * 3)
    private const val MAX_WALL_QUADS = 4000
    private val SIDE_X = intArrayOf(1, -1, 0, 0)
    private val SIDE_Z = intArrayOf(0, 0, 1, -1)
    private val wq = FloatArray(MAX_WALL_QUADS * 16)
    private val wc = FloatArray(MAX_WALL_QUADS * 3)
    private var heights = IntArray(64 * 64)

    // ---------------------------------------------------------------- outdoor occlusion (terrain horizon)

    private const val HZ_SECTORS = 64
    private const val HZ_STEP = 0.5
    private const val HZ_MAX_STEPS = 64
    private val horizon = FloatArray(HZ_SECTORS * HZ_MAX_STEPS)
    private var hzSteps = 0
    private var hzEye = 0.0

    /**
     * For each of [HZ_SECTORS] directions round the light, the steepest terrain slope (column top above the light's
     * eye, over horizontal distance) met so far, every [HZ_STEP] blocks out to the light's reach: one march per
     * direction, then [occluded] is a table look-up for every ground column and wall row the light touches.
     */
    private fun buildHorizon(l: Light, r: Double, bx0: Int, bz0: Int, gw: Int, gh: Int) {
        hzSteps = min(HZ_MAX_STEPS, kotlin.math.ceil(r / HZ_STEP).toInt())
        // A light at or just under its own column's surface (a ground burst) sees along the ground, not into it.
        val lx = kotlin.math.floor(l.x).toInt() - bx0 + 1; val lz = kotlin.math.floor(l.z).toInt() - bz0 + 1
        val under = if (lx in 0 until gw && lz in 0 until gh) heights[lx * gh + lz].toDouble() else l.y
        hzEye = max(l.y, under + 0.25)
        for (sct in 0 until HZ_SECTORS) {
            val a = sct * (2.0 * Math.PI / HZ_SECTORS)
            val cx = kotlin.math.cos(a); val cz = kotlin.math.sin(a)
            var best = -1.0e9f
            for (k in 0 until hzSteps) {
                val d = (k + 1) * HZ_STEP
                val gx = kotlin.math.floor(l.x + cx * d).toInt() - bx0 + 1
                val gz = kotlin.math.floor(l.z + cz * d).toInt() - bz0 + 1
                if (gx in 0 until gw && gz in 0 until gh) {
                    val slope = ((heights[gx * gh + gz] - hzEye) / d).toFloat()
                    if (slope > best) best = slope
                }
                horizon[sct * HZ_MAX_STEPS + k] = best
            }
        }
    }

    /** Whether terrain between the light and the point (x, y, z) hides it (the point's own column excluded). */
    private fun occluded(l: Light, x: Double, y: Double, z: Double): Boolean {
        val dx = x - l.x; val dz = z - l.z
        val d = sqrt(dx * dx + dz * dz)
        val k = min(kotlin.math.floor((d - 0.75) / HZ_STEP).toInt() - 1, hzSteps - 1)
        if (k < 0) return false
        val f = (kotlin.math.atan2(dz, dx) / (2.0 * Math.PI) * HZ_SECTORS + HZ_SECTORS) % HZ_SECTORS
        val s0 = kotlin.math.floor(f).toInt() % HZ_SECTORS; val s1 = (s0 + 1) % HZ_SECTORS
        // the lower of the two bounding directions: an edge case stays lit rather than going dark
        val hz = min(horizon[s0 * HZ_MAX_STEPS + k], horizon[s1 * HZ_MAX_STEPS + k])
        return (y - hzEye) / d < hz - 0.05
    }

    /** Light on a vertical face: plain 3-D falloff (the ground pool flattens its reach vertically). */
    private fun wall(l: Light, r: Double, strength: Double, x: Double, y: Double, z: Double): Float {
        val dx = x - l.x; val dy = y - l.y; val dz = z - l.z
        val d = sqrt(dx * dx + dy * dy + dz * dz) / r
        if (d >= 1.0) return 0f
        val f = 1.0 - d
        return (strength * 0.85 * f * f * f).toFloat()
    }

    // ---------------------------------------------------------------- missile and rocket motors

    /**
     * Burning missile and rocket motors light their surroundings, terrain and walls included (radius 8 / level 10
     * passes the terrain gate), from the nozzle, only while the motor burns: an ATGM's ejection charge and its coast
     * after burnout stay dark. Missiles and rockets of any class are found through [MissilePresentation.isMissile]
     * plus the class-name fallback for add-on entities (BVP's Ataka, rocket pods).
     */
    @SubscribeEvent
    fun onClientTick(event: net.minecraftforge.event.TickEvent.ClientTickEvent) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        val world = mc.level ?: return
        val cam = mc.gameRenderer.mainCamera.position
        for (entity in world.entitiesForRendering()) {
            if (entity !is net.minecraft.world.entity.projectile.Projectile) continue
            val missile = com.atsuishio.superbwarfare.api.effect.MissilePresentation.isMissile(entity) ||
                entity.javaClass.simpleName.let { it.contains("Missile") || it.contains("Rocket") }
            if (!missile) continue
            if (entity.distanceToSqr(cam) > 512.0 * 512.0 || entity.deltaMovement.lengthSqr() < 0.09) continue
            if (entity is com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity &&
                entity.hasGuidedPropulsion() &&
                (!entity.isGuidedPropulsionThrusting() || entity.suppressesGuidedPropulsionTrail())) continue
            val nozzle = runCatching {
                com.atsuishio.superbwarfare.api.effect.MissilePresentation.nozzlePosition(entity, 1f)
            }.getOrElse { entity.position().subtract(entity.deltaMovement.normalize().scale(0.6)) }
            motor(0x4D0000000000L or entity.id.toLong(), nozzle.x, nozzle.y, nozzle.z)
        }
    }

    /** One motor's light: a hot yellow-white core that lights the ground and walls round the missile. */
    @JvmStatic
    fun motor(key: Long, x: Double, y: Double, z: Double) =
        sustain(key, x, y, z, 8.0, 10.0, 1.1, 1f, 0.72f, 0.4f)

    private fun pool(l: Light, r: Double, strength: Double, x: Double, y: Double, z: Double): Float {
        val dx = x - l.x; val dy = (y - l.y) * 1.5; val dz = z - l.z
        val d = sqrt(dx * dx + dy * dy + dz * dz) / r
        if (d >= 1.0) return 0f
        val f = 1.0 - d
        return (strength * f * f * f).toFloat()        // falls off fast: a pool round the source, no hard rim
    }
}
