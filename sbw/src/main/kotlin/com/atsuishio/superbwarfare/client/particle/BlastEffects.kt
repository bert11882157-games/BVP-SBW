package com.atsuishio.superbwarfare.client.particle

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.network.message.receive.FireballMessage
import com.atsuishio.superbwarfare.network.message.receive.ShockwaveMessage
import com.atsuishio.superbwarfare.tools.blast.BlastModel
import com.atsuishio.superbwarfare.tools.blast.BlastVisuals
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.ParticleStatus
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Client presentation of TNT blasts, drawn by this renderer instead of the vanilla particle engine so overlapping
 * blasts can never evict each other (the engine keeps at most 16384 particles per render type and silently drops
 * the oldest, which made stacked explosions vanish) and so every puff is depth-sorted and interpolated per frame.
 *
 * One [Blast] per detonation: a flash, a fireball that starts at the impact point and expands to its radius
 * ([BlastVisuals.expansionAt]), burns out and leaves smoke in the central region; a ground dust ring and tumbling
 * terrain chunks for ground bursts; a mushroom cloud for [BlastVisuals.MUSHROOM_KG] and more; and the shockwave
 * shell sent separately for heavy charges. Glow and smoke share one premultiplied-alpha pass (shader
 * `superbwarfare:blast`), chunks are drawn as small textured cubes of the ground block.
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object BlastEffects {
    private const val MAX_BLASTS = 64
    private const val MAX_QUADS = 16000
    private const val MAX_SHOCKWAVES = 16
    private const val GRAVITY = 0.04
    private const val LOG_INTERVAL_MS = 5000L

    @JvmStatic
    var shader: ShaderInstance? = null

    private val blasts = ArrayList<Blast>()
    private val shockwaves = ArrayList<Shockwave>()
    private var trackedLevel: ClientLevel? = null
    private var lastLogAt = 0L
    private val quads = QuadList(MAX_QUADS)

    // ---------------------------------------------------------------- input

    @JvmStatic
    fun onFireball(message: FireballMessage) {
        val level = level() ?: return
        if (!message.valid()) return
        if (blasts.size >= MAX_BLASTS) blasts.removeAt(0)
        val quality = when (Minecraft.getInstance().options.particles().get()) {
            ParticleStatus.ALL -> 1.0
            ParticleStatus.DECREASED -> 0.6
            else -> 0.35
        }
        val p = message.position
        val blast = Blast(level, p.x, p.y, p.z, message.radius.toDouble(), message.seed, level.gameTime, quality)
        blasts.add(blast)
        val clock = System.currentTimeMillis()
        if (clock - lastLogAt >= LOG_INTERVAL_MS) {
            lastLogAt = clock
            Mod.LOGGER.info("TNT blast presented: {} kg, fireball {} m, expansion {} ticks, {} fire / {} smoke / {} chunks{}{}",
                "%.1f".format(blast.kg), "%.2f".format(blast.radius), "%.1f".format(BlastVisuals.expansionTicks(blast.kg)),
                blast.fire.size, blast.smoke.size, blast.chunks.size, if (blast.groundBurst) ", ground burst" else "",
                if (blast.mushroom) ", mushroom cloud" else "")
        }
    }

    @JvmStatic
    fun onShockwave(message: ShockwaveMessage) {
        val level = level() ?: return
        if (!message.valid()) return
        if (shockwaves.size >= MAX_SHOCKWAVES) shockwaves.removeAt(0)
        val p = message.position
        shockwaves.add(Shockwave(p.x, p.y, p.z, message.fromRadius.toDouble(), message.toRadius.toDouble(),
            message.durationTicks, level.gameTime, message.seed))
        Mod.LOGGER.info("TNT shockwave presented: {} -> {} m, {} ticks",
            "%.1f".format(message.fromRadius), "%.1f".format(message.toRadius), message.durationTicks)
    }

    private fun level(): ClientLevel? {
        val level = Minecraft.getInstance().level
        if (level !== trackedLevel) {
            trackedLevel = level
            blasts.clear()
            shockwaves.clear()
        }
        return level
    }

    // ---------------------------------------------------------------- tick

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val level = level() ?: return
        if (Minecraft.getInstance().isPaused) return
        val now = level.gameTime
        blasts.removeIf { !it.tick(level, now) }
        shockwaves.removeIf { now - it.start > it.duration + 1 }
    }

    // ---------------------------------------------------------------- render

    @SubscribeEvent
    fun onRender(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        val level = level() ?: return
        if (blasts.isEmpty() && shockwaves.isEmpty()) return
        val mc = Minecraft.getInstance()
        val camera = event.camera
        val cam = camera.position
        val time = level.gameTime + event.partialTick.toDouble()

        renderChunks(mc, level, event.poseStack, cam.x, cam.y, cam.z, time, event.partialTick)

        val fire = BlastSprites.fireballFrames() ?: return
        val smoke = BlastSprites.sootFrames() ?: return
        val glow = BlastSprites.glowSprites() ?: return
        val sprites = Sprites(fire, smoke, glow[0], glow[1])
        quads.clear()
        quads.puffs = sprites.puffs
        for (blast in blasts) blast.emit(quads, sprites, level, time, cam.x, cam.y, cam.z)
        for (wave in shockwaves) wave.emit(quads, sprites, time, cam.x, cam.y, cam.z)
        if (quads.size == 0) return
        drawQuads(mc, event.poseStack, camera)
    }

    private fun drawQuads(mc: Minecraft, poseStack: PoseStack, camera: Camera) {
        val program = shader ?: return
        quads.sortBackToFront()
        val left = camera.leftVector
        val up = camera.upVector
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushPose()
        modelView.mulPoseMatrix(poseStack.last().pose())
        RenderSystem.applyModelViewMatrix()
        mc.gameRenderer.lightTexture().turnOnLightLayer()
        RenderSystem.setShader { program }
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES)
        RenderSystem.enableBlend()
        RenderSystem.blendFuncSeparate(
            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
        )
        RenderSystem.depthMask(false)
        RenderSystem.enableDepthTest()
        RenderSystem.disableCull()
        val builder: BufferBuilder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE)
        quads.write(builder, left.x().toDouble(), left.y().toDouble(), left.z().toDouble(),
            up.x().toDouble(), up.y().toDouble(), up.z().toDouble())
        BufferUploader.drawWithShader(builder.end())
        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
        mc.gameRenderer.lightTexture().turnOffLightLayer()
        modelView.popPose()
        RenderSystem.applyModelViewMatrix()
    }

    private fun renderChunks(mc: Minecraft, level: ClientLevel, poseStack: PoseStack, cx: Double, cy: Double, cz: Double,
                             time: Double, partial: Float) {
        if (blasts.none { it.chunks.isNotEmpty() }) return
        val buffers = mc.renderBuffers().bufferSource()
        val type = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS)
        val consumer = buffers.getBuffer(type)
        val rotation = Quaternionf()
        val corner = Vector3f()
        val normal = Vector3f()
        for (blast in blasts) {
            val sprite = blast.chunkSprite ?: continue
            for (chunk in blast.chunks) {
                val scale = chunk.scale(time)
                if (scale <= 0.0) continue
                val x = chunk.px + (chunk.x - chunk.px) * partial
                val y = chunk.py + (chunk.y - chunk.py) * partial
                val z = chunk.pz + (chunk.z - chunk.pz) * partial
                val angle = chunk.angle + chunk.spin * partial
                rotation.identity().rotateAxis(angle.toFloat(), chunk.ax, chunk.ay, chunk.az)
                val light = LevelRenderer.getLightColor(level, BlockPos.containing(x, y + 0.2, z))
                val half = (chunk.size * scale * 0.5).toFloat()
                poseStack.pushPose()
                poseStack.translate(x - cx, y - cy, z - cz)
                poseStack.mulPose(rotation)
                val pose = poseStack.last()
                for (face in 0 until 6) {
                    val n = CUBE_NORMALS[face]
                    normal.set(n[0], n[1], n[2])
                    val shade = FACE_SHADE[face]
                    for (v in 0 until 4) {
                        val c = CUBE_FACES[face][v]
                        corner.set(c[0] * half, c[1] * half, c[2] * half)
                        val u = sprite.getU((chunk.u0 + (if (v == 1 || v == 2) chunk.uvSpan else 0.0)) * 16.0)
                        val vv = sprite.getV((chunk.v0 + (if (v >= 2) chunk.uvSpan else 0.0)) * 16.0)
                        consumer.vertex(pose.pose(), corner.x, corner.y, corner.z)
                            .color(blast.chunkRed * shade, blast.chunkGreen * shade, blast.chunkBlue * shade, 1f)
                            .uv(u, vv).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                            .normal(pose.normal(), normal.x, normal.y, normal.z).endVertex()
                    }
                }
                poseStack.popPose()
            }
        }
        buffers.endBatch(type)
    }

    private val FACE_SHADE = floatArrayOf(0.8f, 0.8f, 1f, 0.55f, 0.9f, 0.9f)
    private val CUBE_NORMALS = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f))
    /** Unit-cube face corners (half extent 1), counter-clockwise seen from outside. */
    private val CUBE_FACES = arrayOf(
        arrayOf(floatArrayOf(1f, -1f, -1f), floatArrayOf(1f, 1f, -1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, -1f, 1f)),
        arrayOf(floatArrayOf(-1f, -1f, 1f), floatArrayOf(-1f, 1f, 1f), floatArrayOf(-1f, 1f, -1f), floatArrayOf(-1f, -1f, -1f)),
        arrayOf(floatArrayOf(-1f, 1f, -1f), floatArrayOf(-1f, 1f, 1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 1f, -1f)),
        arrayOf(floatArrayOf(-1f, -1f, 1f), floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f), floatArrayOf(1f, -1f, 1f)),
        arrayOf(floatArrayOf(1f, -1f, 1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f), floatArrayOf(-1f, -1f, 1f)),
        arrayOf(floatArrayOf(-1f, -1f, -1f), floatArrayOf(-1f, 1f, -1f), floatArrayOf(1f, 1f, -1f), floatArrayOf(1f, -1f, -1f)),
    )

    // ---------------------------------------------------------------- data

    class Sprites(val fire: Array<TextureAtlasSprite>, val smoke: Array<TextureAtlasSprite>,
                  val flash: TextureAtlasSprite, val ring: TextureAtlasSprite) {
        val puffs: Set<TextureAtlasSprite> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TextureAtlasSprite, Boolean>())
            .also { it.addAll(fire); it.addAll(smoke) }
    }

    /** Camera-relative billboards: centre, half size, roll, premultiplied colour, sprite UVs, light. */
    class QuadList(private val capacity: Int) {
        private val data = DoubleArray(capacity * STRIDE)
        private val lights = IntArray(capacity)
        private var order = IntArray(capacity)
        private val depth = DoubleArray(capacity)
        var size = 0
            private set
        /** Fire and smoke sprites: their puff body ends well inside the square, so their quads are drawn larger. */
        var puffs: Set<TextureAtlasSprite> = emptySet()

        fun clear() { size = 0 }

        /** [r],[g],[b] are the emitted/lit colour, [cover] the opacity (0 = pure glow). */
        fun add(x: Double, y: Double, z: Double, half: Double, roll: Double, r: Double, g: Double, b: Double,
                cover: Double, sprite: TextureAtlasSprite, light: Int) {
            if (size >= capacity || half <= 0.0) return
            val a = cover.coerceIn(0.0, 1.0)
            if (r + g + b + a < 0.004) return
            val o = size * STRIDE
            data[o] = x; data[o + 1] = y; data[o + 2] = z; data[o + 4] = roll
            data[o + 3] = if (sprite in puffs) half * BlastSprites.PUFF_FILL else half
            data[o + 5] = max(0.0, r); data[o + 6] = max(0.0, g); data[o + 7] = max(0.0, b); data[o + 8] = a
            data[o + 9] = sprite.u0.toDouble(); data[o + 10] = sprite.u1.toDouble()
            data[o + 11] = sprite.v0.toDouble(); data[o + 12] = sprite.v1.toDouble()
            depth[size] = x * x + y * y + z * z
            lights[size] = light
            size++
        }

        fun sortBackToFront() {
            if (order.size < size) order = IntArray(capacity)
            val boxed = (0 until size).sortedByDescending { depth[it] }
            for (i in 0 until size) order[i] = boxed[i]
        }

        fun write(builder: BufferBuilder, lx: Double, ly: Double, lz: Double, ux: Double, uy: Double, uz: Double) {
            for (i in 0 until size) {
                val q = order[i]
                val o = q * STRIDE
                val x = data[o]; val y = data[o + 1]; val z = data[o + 2]; val h = data[o + 3]
                val c = cos(data[o + 4]) * h
                val s = sin(data[o + 4]) * h
                // Rotated basis in the view plane.
                val ax = lx * c + ux * s; val ay = ly * c + uy * s; val az = lz * c + uz * s
                val bx = ux * c - lx * s; val by = uy * c - ly * s; val bz = uz * c - lz * s
                val r = data[o + 5].toFloat().coerceAtMost(4f); val g = data[o + 6].toFloat().coerceAtMost(4f)
                val bl = data[o + 7].toFloat().coerceAtMost(4f); val a = data[o + 8].toFloat()
                val u0 = data[o + 9].toFloat(); val u1 = data[o + 10].toFloat()
                val v0 = data[o + 11].toFloat(); val v1 = data[o + 12].toFloat()
                val light = lights[q]
                builder.vertex(x - ax - bx, y - ay - by, z - az - bz).uv(u1, v1).color(r.coerceAtMost(1f), g.coerceAtMost(1f), bl.coerceAtMost(1f), a).uv2(light).endVertex()
                builder.vertex(x - ax + bx, y - ay + by, z - az + bz).uv(u1, v0).color(r.coerceAtMost(1f), g.coerceAtMost(1f), bl.coerceAtMost(1f), a).uv2(light).endVertex()
                builder.vertex(x + ax + bx, y + ay + by, z + az + bz).uv(u0, v0).color(r.coerceAtMost(1f), g.coerceAtMost(1f), bl.coerceAtMost(1f), a).uv2(light).endVertex()
                builder.vertex(x + ax - bx, y + ay - by, z + az - bz).uv(u0, v1).color(r.coerceAtMost(1f), g.coerceAtMost(1f), bl.coerceAtMost(1f), a).uv2(light).endVertex()
            }
        }

        companion object { private const val STRIDE = 13 }
    }

    private const val FULL_BRIGHT = 0xF000F0

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** Fire colour temperature: 0 = white-hot, 1 = orange, 2 = dark red. Writes premultiplied glow into [out]. */
    private fun fireColor(temperature: Double, intensity: Double, out: DoubleArray) {
        val t = temperature.coerceIn(0.0, 2.0)
        if (t <= 1.0) {
            out[0] = 1.0; out[1] = 0.95 - 0.42 * t; out[2] = 0.75 - 0.62 * t
        } else {
            val k = t - 1.0
            out[0] = 1.0 - 0.45 * k; out[1] = 0.53 - 0.41 * k; out[2] = 0.13 - 0.1 * k
        }
        out[0] *= intensity; out[1] *= intensity; out[2] *= intensity
    }

    // ---------------------------------------------------------------- one blast

    class Chunk(var x: Double, var y: Double, var z: Double, var vx: Double, var vy: Double, var vz: Double,
                val size: Double, val ax: Float, val ay: Float, val az: Float, var spin: Double,
                val u0: Double, val v0: Double, val uvSpan: Double) {
        var px = x; var py = y; var pz = z
        var angle = 0.0
        var landedAt = -1.0
        var restTicks = 60.0

        fun scale(time: Double): Double {
            if (landedAt < 0.0) return 1.0
            val since = time - landedAt
            return if (since < restTicks) 1.0 else (1.0 - (since - restTicks) / 20.0).coerceIn(0.0, 1.0)
        }
    }

    class Blast(level: ClientLevel, val x: Double, val y: Double, val z: Double, fireballRadius: Double, seed: Long,
                val start: Long, quality: Double) {
        val kg = BlastVisuals.chargeFromFireballRadius(fireballRadius)
        val radius = BlastVisuals.visualRadius(fireballRadius)
        private val scale = BlastVisuals.scale(kg)
        val mushroom = BlastVisuals.producesMushroom(kg)
        val groundY: Double
        val groundBurst: Boolean
        private val groundState: BlockState?
        val chunkSprite: TextureAtlasSprite?
        var chunkRed = 1f; var chunkGreen = 1f; var chunkBlue = 1f
        private val dustR: Double; private val dustG: Double; private val dustB: Double
        private val light: Int
        private val end = start + BlastVisuals.totalTicks(kg)

        // Per-puff parameters, flattened: fire (ox, oy, oz, size, lag, roll, spin, frame)
        val fire: DoubleArray
        val smoke: DoubleArray
        private val dust: DoubleArray
        private val cap: DoubleArray
        private val stem: DoubleArray
        val chunks = ArrayList<Chunk>()
        private val color = DoubleArray(3)

        init {
            val random = Random(seed)
            var surface = Double.NaN
            var state: BlockState? = null
            val scanDepth = (radius * 1.2 + 2.0).toInt()
            val cursor = BlockPos.MutableBlockPos()
            for (dy in 0..scanDepth) {
                cursor.set(x, y - dy - 0.01, z)
                val candidate = level.getBlockState(cursor)
                val shape = candidate.getCollisionShape(level, cursor)
                if (!candidate.isAir && !shape.isEmpty) {
                    surface = cursor.y + shape.max(net.minecraft.core.Direction.Axis.Y)
                    state = candidate
                    break
                }
                if (!candidate.fluidState.isEmpty) break
            }
            groundY = if (surface.isNaN()) y else surface
            groundBurst = !surface.isNaN() && y - surface <= max(1.0, 0.6 * radius)
            groundState = if (groundBurst) state else null
            light = LevelRenderer.getLightColor(level, BlockPos.containing(x, y + 0.5, z))

            val mc = Minecraft.getInstance()
            chunkSprite = groundState?.let { mc.blockRenderer.blockModelShaper.getParticleIcon(it) }
            if (groundState != null) {
                val tint = mc.blockColors.getColor(groundState, level, BlockPos.containing(x, groundY - 0.5, z), 0)
                if (tint != -1) {
                    chunkRed = ((tint shr 16) and 0xFF) / 255f; chunkGreen = ((tint shr 8) and 0xFF) / 255f
                    chunkBlue = (tint and 0xFF) / 255f
                }
                val map = groundState.getMapColor(level, BlockPos.containing(x, groundY - 0.5, z)).col
                val mr = ((map shr 16) and 0xFF) / 255.0; val mg = ((map shr 8) and 0xFF) / 255.0
                val mb = (map and 0xFF) / 255.0
                dustR = 0.55 * mr + 0.45 * 0.55; dustG = 0.55 * mg + 0.45 * 0.5; dustB = 0.55 * mb + 0.45 * 0.45
            } else {
                dustR = 0.5; dustG = 0.47; dustB = 0.43
            }

            val nFire = (BlastVisuals.firePuffCount(radius) * quality).toInt().coerceAtLeast(6)
            fire = DoubleArray(nFire * 8)
            for (i in 0 until nFire) {
                val o = i * 8
                val (ox, oy, oz) = ballPoint(random, groundBurst)
                val reach = 0.25 + 0.75 * cbrt(random.nextDouble())
                fire[o] = ox * reach; fire[o + 1] = oy * reach; fire[o + 2] = oz * reach
                fire[o + 3] = 0.42 + 0.28 * random.nextDouble()
                fire[o + 4] = random.nextDouble() * 0.35 * BlastVisuals.expansionTicks(kg)
                fire[o + 5] = random.nextDouble() * 2 * PI
                fire[o + 6] = (random.nextDouble() - 0.5) * 0.08
                fire[o + 7] = random.nextInt(8).toDouble()
            }
            val nSmoke = (BlastVisuals.smokePuffCount(radius) * quality).toInt().coerceAtLeast(6)
            smoke = DoubleArray(nSmoke * 8)
            for (i in 0 until nSmoke) {
                val o = i * 8
                val (ox, oy, oz) = ballPoint(random, groundBurst)
                val reach = 0.55 * cbrt(random.nextDouble())
                smoke[o] = ox * reach; smoke[o + 1] = abs(oy) * reach * (if (groundBurst) 1.0 else 0.8)
                smoke[o + 2] = oz * reach
                smoke[o + 3] = 0.5 + 0.35 * random.nextDouble()
                smoke[o + 4] = BlastVisuals.expansionTicks(kg) + random.nextDouble() * BlastVisuals.fireHoldTicks(kg) * 1.5
                smoke[o + 5] = random.nextDouble() * 2 * PI
                smoke[o + 6] = 0.7 + 0.6 * random.nextDouble()
                smoke[o + 7] = random.nextInt(6).toDouble()
            }
            val nDust = if (groundBurst) (BlastVisuals.dustPuffCount(radius) * quality).toInt().coerceAtLeast(6) else 0
            dust = DoubleArray(nDust * 4)
            for (i in 0 until nDust) {
                val o = i * 4
                dust[o] = 2 * PI * (i + random.nextDouble() * 0.6) / nDust
                dust[o + 1] = 0.8 + 0.5 * random.nextDouble()
                dust[o + 2] = random.nextDouble() * 2 * PI
                dust[o + 3] = random.nextInt(6).toDouble()
            }
            if (mushroom) {
                val nCap = (min(120.0, 40.0 + 3.0 * scale) * quality).toInt().coerceAtLeast(24)
                cap = DoubleArray(nCap * 5)
                for (i in 0 until nCap) {
                    val o = i * 5
                    cap[o] = 2 * PI * random.nextDouble()                  // toroidal angle
                    cap[o + 1] = 2 * PI * random.nextDouble()              // poloidal angle at start
                    cap[o + 2] = if (i % 5 == 0) 0.45 else 1.0             // a few fill the core
                    cap[o + 3] = random.nextDouble() * 2 * PI
                    cap[o + 4] = random.nextInt(6).toDouble()
                }
                val nStem = (min(70.0, 20.0 + 2.0 * scale) * quality).toInt().coerceAtLeast(12)
                stem = DoubleArray(nStem * 4)
                for (i in 0 until nStem) {
                    val o = i * 4
                    stem[o] = (i + random.nextDouble()) / nStem
                    stem[o + 1] = 2 * PI * random.nextDouble()
                    stem[o + 2] = random.nextDouble() * 2 * PI
                    stem[o + 3] = random.nextInt(6).toDouble()
                }
            } else {
                cap = DoubleArray(0)
                stem = DoubleArray(0)
            }
            if (groundState != null && chunkSprite != null) {
                val count = (BlastVisuals.chunkCount(kg) * max(0.5, quality)).toInt()
                val speed = BlastVisuals.chunkSpeed(kg) / 20.0
                val size = BlastVisuals.chunkSize(kg)
                repeat(count) {
                    val azimuth = random.nextDouble() * 2 * PI
                    val elevation = Math.toRadians(30.0 + 50.0 * random.nextDouble())
                    val v = speed * (0.55 + 0.65 * random.nextDouble())
                    val spread = radius * 0.3 * random.nextDouble()
                    val axis = Vector3f(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f)
                    if (axis.lengthSquared() < 1e-4f) axis.set(0f, 1f, 0f)
                    axis.normalize()
                    val edge = size * (0.6 + 0.8 * random.nextDouble())
                    val span = (0.25 + 0.35 * random.nextDouble()).coerceAtMost(1.0)
                    chunks.add(Chunk(x + cos(azimuth) * spread, groundY + 0.05, z + sin(azimuth) * spread,
                        cos(azimuth) * cos(elevation) * v, sin(elevation) * v, sin(azimuth) * cos(elevation) * v,
                        edge, axis.x, axis.y, axis.z, (random.nextDouble() - 0.5) * 1.2,
                        random.nextDouble() * (1.0 - span), random.nextDouble() * (1.0 - span), span).also {
                        it.restTicks = 50.0 + 60.0 * random.nextDouble()
                    })
                }
            }
        }

        private fun ballPoint(random: Random, upper: Boolean): Triple<Double, Double, Double> {
            while (true) {
                val px = random.nextDouble() * 2 - 1
                val py = random.nextDouble() * 2 - 1
                val pz = random.nextDouble() * 2 - 1
                val d = px * px + py * py + pz * pz
                if (d > 1.0 || d < 1e-6) continue
                val n = sqrt(d)
                return Triple(px / n, if (upper) abs(py) / n else py / n, pz / n)
            }
        }

        /** Advances chunk physics; false once the whole presentation is over. */
        fun tick(level: ClientLevel, now: Long): Boolean {
            val cursor = BlockPos.MutableBlockPos()
            for (chunk in chunks) {
                chunk.px = chunk.x; chunk.py = chunk.y; chunk.pz = chunk.z
                if (chunk.landedAt >= 0.0) { chunk.spin = 0.0; continue }
                chunk.angle += chunk.spin
                chunk.vy -= GRAVITY
                chunk.vx *= 0.985; chunk.vy *= 0.985; chunk.vz *= 0.985
                val nx = chunk.x + chunk.vx
                val ny = chunk.y + chunk.vy
                val nz = chunk.z + chunk.vz
                cursor.set(nx, ny - chunk.size * 0.4, nz)
                val state = level.getBlockState(cursor)
                val shape = state.getCollisionShape(level, cursor)
                if (chunk.vy < 0.0 && !shape.isEmpty) {
                    chunk.x = nx; chunk.z = nz
                    chunk.y = cursor.y + shape.max(net.minecraft.core.Direction.Axis.Y) + chunk.size * 0.4
                    chunk.landedAt = now.toDouble()
                } else if (now - start > 200) {
                    chunk.landedAt = now.toDouble()
                } else {
                    chunk.x = nx; chunk.y = ny; chunk.z = nz
                }
            }
            return now <= end
        }

        fun emit(out: QuadList, sprites: Sprites, level: ClientLevel, time: Double, cx: Double, cy: Double, cz: Double) {
            val age = time - start
            if (age < 0.0) return
            val ox = x - cx
            val oy = y - cy
            val oz = z - cz
            val expansion = BlastVisuals.expansionTicks(kg)
            val hold = BlastVisuals.fireHoldTicks(kg)
            val fade = BlastVisuals.fireFadeTicks(kg)

            // Flash: an instant, bright glow at the impact point.
            val flash = BlastVisuals.flashTicks(kg)
            if (age < flash) {
                val k = 1.0 - age / flash
                out.add(ox, oy + 0.1 * radius, oz, radius * 2.4 * (0.7 + 0.3 * k), 0.0,
                    1.6 * k, 1.45 * k, 1.1 * k, 0.0, sprites.flash, FULL_BRIGHT)
            }

            // Fireball: expands from the impact point, holds, then cools and burns out.
            val fireEnd = expansion + hold + fade
            if (age < fireEnd + 2) {
                val burn = ((age - expansion - hold) / fade).coerceIn(0.0, 1.0)
                val intensity = if (age < expansion + hold) 1.0 else (1.0 - burn).pow(1.3)
                val temperature = if (age < expansion) 0.35 * age / expansion
                    else if (age < expansion + hold) 0.35 + 0.65 * (age - expansion) / hold
                    else 1.0 + burn
                val rise = if (age > expansion) 0.05 * radius * (age - expansion) / 20.0 * (1.0 + scale * 0.1) else 0.0
                val count = fire.size / 8
                for (i in 0 until count) {
                    val o = i * 8
                    val e = BlastVisuals.expansionAt(age - fire[o + 4], kg)
                    if (e <= 0.0) continue
                    val grow = 0.35 + 0.65 * e
                    val px = ox + fire[o] * radius * e
                    val py = oy + fire[o + 1] * radius * e + rise * (0.6 + 0.4 * fire[o + 1])
                    val pz = oz + fire[o + 2] * radius * e
                    val half = fire[o + 3] * radius * grow * (1.0 + 0.3 * burn)
                    fireColor(temperature + 0.25 * (1.0 - abs(fire[o + 1])) * burn, 1.15 * intensity, color)
                    // Dense fire partly hides what is behind it while it is young; then it is pure glow.
                    val cover = 0.35 * intensity * (1.0 - burn)
                    val frame = ((fire[o + 7] + burn * 3).toInt()) % 8
                    out.add(px, py, pz, half, fire[o + 5] + fire[o + 6] * age, color[0], color[1], color[2], cover,
                        sprites.fire[frame], FULL_BRIGHT)
                }
            }

            // Central smoke: rises out of the burnt-out fireball and lingers.
            val smokeLife = BlastVisuals.smokeTicks(kg)
            run {
                val count = smoke.size / 8
                for (i in 0 until count) {
                    val o = i * 8
                    val t = age - smoke[o + 4]
                    if (t <= 0.0 || t >= smokeLife) continue
                    val p = t / smokeLife
                    val fadeIn = smoothstep(0.0, 12.0, t)
                    val fadeOut = 1.0 - smoothstep(0.55, 1.0, p)
                    val alpha = 0.72 * fadeIn * fadeOut
                    if (alpha <= 0.01) continue
                    val seconds = t / 20.0
                    val riseSpeed = (0.35 + 0.06 * radius) * smoke[o + 6]
                    val drift = 1.0 + 0.35 * (1.0 - exp(-seconds / 3.0))
                    val px = ox + smoke[o] * radius * drift
                    val py = oy + smoke[o + 1] * radius + riseSpeed * seconds * (1.0 - 0.4 * p)
                    val pz = oz + smoke[o + 2] * radius * drift
                    val half = smoke[o + 3] * radius * (0.9 + 0.9 * (1.0 - exp(-seconds / 4.0)))
                    val grey = 0.16 + 0.16 * p
                    val lit = alpha
                    out.add(px, py, pz, half, smoke[o + 5] + 0.02 * seconds, grey * lit, grey * 0.95 * lit,
                        grey * 0.9 * lit, alpha, sprites.smoke[smoke[o + 7].toInt()], light)
                }
            }

            // Ground dust ring: races outward along the ground and settles.
            if (dust.isNotEmpty()) {
                val life = BlastVisuals.dustTicks(kg)
                if (age < life) {
                    val p = age / life
                    val outward = 1.0 - (1.0 - smoothstep(0.0, 0.35, p)).pow(2.0)
                    val alpha = 0.55 * smoothstep(0.0, 0.04, p) * (1.0 - smoothstep(0.45, 1.0, p))
                    val count = dust.size / 4
                    for (i in 0 until count) {
                        val o = i * 4
                        val reach = radius * (0.6 + 1.8 * outward) * dust[o + 1]
                        val px = ox + cos(dust[o]) * reach
                        val pz = oz + sin(dust[o]) * reach
                        val py = groundY - cy + radius * 0.25 * (0.6 + outward)
                        val half = radius * (0.35 + 0.5 * outward)
                        out.add(px, py, pz, half, dust[o + 2], dustR * alpha, dustG * alpha, dustB * alpha, alpha,
                            sprites.smoke[dust[o + 3].toInt()], light)
                    }
                }
            }

            // Mushroom cloud: the fireball becomes a rolling, rising cap on a dusty stem.
            if (mushroom && age > expansion) {
                val t = age - expansion
                val life = BlastVisuals.mushroomTicks(kg)
                if (t < life) {
                    val rise = BlastVisuals.mushroomRiseAt(t, kg)
                    val fadeOut = 1.0 - smoothstep(0.65, 1.0, t / life)
                    val fadeIn = smoothstep(0.0, 6.0, t)
                    val capHeight = radius * (0.6 + 5.0 * rise)
                    val major = radius * (0.8 + 0.8 * rise)
                    val minor = radius * 0.55 * (1.0 + 0.45 * rise)
                    val roll = t / 20.0 * 0.6
                    val glow = (1.0 - smoothstep(0.0, 40.0 + 2.0 * scale, t))
                    val count = cap.size / 5
                    val baseY = groundY - cy
                    for (i in 0 until count) {
                        val o = i * 5
                        val phi = cap[o]
                        val theta = cap[o + 1] + roll
                        val ring = major * cap[o + 2] + minor * cos(theta)
                        val px = ox + cos(phi) * ring
                        val pz = oz + sin(phi) * ring
                        val py = baseY + capHeight + minor * sin(theta)
                        val half = minor * (0.8 + 0.25 * cap[o + 2])
                        val top = 0.5 + 0.5 * sin(theta)
                        val alpha = 0.8 * fadeIn * fadeOut
                        val grey = (0.14 + 0.14 * top) * alpha
                        fireColor(1.0 + (1.0 - glow), glow * 0.9, color)
                        out.add(px, py, pz, half, cap[o + 3] + 0.01 * t, grey + color[0] * 0.6, grey * 0.93 + color[1] * 0.6,
                            grey * 0.86 + color[2] * 0.6, alpha, sprites.smoke[cap[o + 4].toInt()], light)
                    }
                    val stemTop = capHeight - minor * 0.4
                    val stemCount = stem.size / 4
                    for (i in 0 until stemCount) {
                        val o = i * 4
                        val f = stem[o]
                        val h = stemTop * f * rise.coerceAtLeast(0.15)
                        val width = radius * (0.3 + 0.4 * (1.0 - f) * (1.0 - f))
                        val a = stem[o + 1] + t / 20.0 * 0.25
                        val px = ox + cos(a) * width * 0.6
                        val pz = oz + sin(a) * width * 0.6
                        val alpha = 0.7 * fadeIn * fadeOut * smoothstep(0.0, 0.08, f + 0.05)
                        val tone = alpha * (0.85 + 0.15 * f)
                        out.add(px, baseY + h, pz, width, stem[o + 2], dustR * tone * 0.75, dustG * tone * 0.75,
                            dustB * tone * 0.75, alpha, sprites.smoke[stem[o + 3].toInt()], light)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- shockwave

    class Shockwave(val x: Double, val y: Double, val z: Double, val from: Double, val to: Double, val duration: Int,
                    val start: Long, seed: Long) {
        private val phase = Random(seed).nextDouble() * 2 * PI

        fun emit(out: QuadList, sprites: Sprites, time: Double, cx: Double, cy: Double, cz: Double) {
            val age = time - start
            if (age < 0.0 || age > duration) return
            val p = age / duration
            val r = BlastModel.shockwaveRadiusAt(p, from, to)
            val alpha = 0.22 * (1.0 - p).pow(1.4)
            // A thin, translucent condensation shell drawn as a ring of overlapping glows along the front...
            val segments = 40
            for (ring in 0 until 5) {
                val elevation = ring * (PI / 2) / 5.0
                val rr = r * cos(elevation)
                val yy = r * sin(elevation)
                val n = max(6, (segments * cos(elevation)).toInt())
                for (i in 0 until n) {
                    val a = phase + 2 * PI * i / n
                    out.add(x - cx + cos(a) * rr, y - cy + yy, z - cz + sin(a) * rr, r * 0.22, a,
                        0.8 * alpha, 0.8 * alpha, 0.82 * alpha, alpha * 0.9, sprites.smoke[i % sprites.smoke.size],
                        FULL_BRIGHT)
                }
            }
            // ...and a ground-hugging dust wall at the front.
            val dustAlpha = 0.45 * (1.0 - p).pow(1.2)
            for (i in 0 until segments) {
                val a = phase + 2 * PI * (i + 0.5) / segments
                out.add(x - cx + cos(a) * r, y - cy + r * 0.04, z - cz + sin(a) * r, r * 0.12, a,
                    0.45 * dustAlpha, 0.41 * dustAlpha, 0.37 * dustAlpha, dustAlpha, sprites.smoke[i % sprites.smoke.size],
                    FULL_BRIGHT)
            }
        }
    }
}
