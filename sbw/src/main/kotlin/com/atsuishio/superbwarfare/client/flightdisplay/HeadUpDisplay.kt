package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.IncomingMissileWarning
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource.HeadUpDisplayResource
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3f
import org.lwjgl.opengl.GL11
import java.util.IdentityHashMap
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The pilot's head-up display: symbology ([HudPainter]) projected onto the combiner glass, drawn into the world, so
 * it shows from the cockpit and from outside (third person, other players) alike.
 *
 * The image is light added onto the glass. Each point of the combiner takes the part of the picture that lies in its
 * direction from the design eye, so from the pilot's head the symbols sit over the world they describe, as a
 * collimated HUD's do. The aircraft the local player rides shows live symbology, redrawn every frame; other aircraft
 * show a still picture.
 *
 * Placement comes from the vehicle's client resource ("HeadUpDisplay", written by tools/cockpit_models).
 */
object HeadUpDisplay {
    private val LOGGER = Mod.LOGGER
    private val LIVE_TEXTURE = Mod.loc("dynamic/head_up_display_live")
    private const val GRID = 8
    private const val LIFT = 0.0015f          // the picture sits this far in front of the glass, blocks
    private const val MAX_STILLS = 16
    /** The live picture's resolution over [HudPainter.SIZE]: sharp when the pilot zooms in (the stills stay at 1). */
    private const val LIVE_SCALE = 3
    /** The live picture follows the pilot's camera within this distance of the design eye, blocks... */
    private const val MAX_EYE_SHIFT = 0.6
    /** ...refitted once the camera has moved this far. */
    private const val REFIT_DISTANCE = 0.005
    private const val DEFAULT_RGB = 0x4CFF6A
    private const val SIGHT_RGB = 0xFF9A3C          // a gunsight's reticle lamp: amber

    /** A combiner fitted from a resource: its corners in the model frame and the picture's mapping onto it. */
    private class Combiner(val points: Array<Array<Vector3f>>, val uvs: Array<Array<FloatArray>>, val frame: HudPainter.Frame,
                           val rgb: Int, val style: String)

    private val combiners = IdentityHashMap<HeadUpDisplayResource, Combiner?>()
    private val stills = IdentityHashMap<HeadUpDisplayResource, Pair<RenderTarget, ResourceLocation>>()
    private val stillRequested = LinkedHashSet<HeadUpDisplayResource>()
    private var live: RenderTarget? = null
    private var liveFor: HeadUpDisplayResource? = null
    private var liveVehicleId = -1
    private var failed = false
    private var logged = false

    private fun resource(vehicle: VehicleEntity): HeadUpDisplayResource? =
        VehicleResource.getDefault(vehicle).headUpDisplay?.takeIf { it.frame == "VEHICLE_LOCAL_BLOCKS" }

    private fun combiner(r: HeadUpDisplayResource): Combiner? = combiners.getOrPut(r) { fit(r) }

    private var liveFit: Combiner? = null
    private var liveFitFor: HeadUpDisplayResource? = null
    private val liveFitEye = Vector3d()
    /**
     * The camera in the vehicle frame, measured in the world pass where the camera and the vehicle's pose belong to
     * the same frame. The frame's start (where the picture is painted) still holds the last frame's camera, which
     * against this frame's pose put the eye off by the distance flown per frame: the picture refitted every frame and
     * the symbols slid about, and flipped back to the design eye once that passed [MAX_EYE_SHIFT].
     */
    private val measuredEye = Vector3d(Double.NaN, Double.NaN, Double.NaN)
    private var measuredFor = -1

    /**
     * The pilot's picture, collimated for where their eye actually is: in first person the camera's position in the
     * vehicle frame (a zoom or seat camera can sit off the design eye, and the symbols would then slide off the
     * boresight), refitted when it moves; otherwise, or when the camera is not near the seat, the design eye.
     */
    private fun liveCombiner(vehicle: VehicleEntity, r: HeadUpDisplayResource): Combiner? {
        val mc = Minecraft.getInstance()
        val design = vec(r.eye) ?: return combiner(r)
        var eye = design
        if (mc.options.cameraType.isFirstPerson && measuredFor == vehicle.id && measuredEye.isFinite
            && measuredEye.distance(design) < MAX_EYE_SHIFT) eye = Vector3d(measuredEye)
        if (eye === design) return combiner(r)
        if (liveFitFor !== r || liveFit == null || liveFitEye.distance(eye) > REFIT_DISTANCE) {
            liveFit = fit(r, eye) ?: return combiner(r)
            if (liveFitFor !== r) LOGGER.info("Head-up display eye refit: camera {} blocks off the design eye", eye.distance(design))
            liveFitFor = r
            liveFitEye.set(eye)
        }
        return liveFit
    }

    /** Called from [FlightDisplays.onRenderTick] at the start of every frame, outside the world pass. */
    fun renderTick(partialTick: Float) {
        if (failed) return
        try {
            for (r in stillRequested.toList()) {
                if (stills.containsKey(r) || stills.size >= MAX_STILLS) continue
                val c = combiner(r) ?: continue
                val location = Mod.loc("dynamic/head_up_display_still_" + stills.size)
                val target = create(location)
                paint(target, FlightDisplayState.STILL, c, HudPainter.Alerts(locked = false, launch = false))
                stills[r] = target to location
            }
            stillRequested.clear()
            val vehicle = Minecraft.getInstance().player?.vehicle as? VehicleEntity ?: run { liveVehicleId = -1; return }
            val r = resource(vehicle) ?: run { liveVehicleId = -1; return }
            // live only for the pilot at the HUD's own seat (first or third person); passengers see the still picture
            if (vehicle.isWreck || FlightDisplayState.crewSeat(vehicle) != r.seat) { liveVehicleId = -1; return }
            val c = liveCombiner(vehicle, r) ?: run { liveVehicleId = -1; return }
            val state = FlightDisplayState.sample(vehicle, partialTick) ?: return
            val flags = vehicle.getIncomingMissileWarning()
            val alerts = HudPainter.Alerts(locked = vehicle.getAircraftThreatLevel() > 0,
                launch = flags and IncomingMissileWarning.INCOMING != 0)
            val target = live ?: create(LIVE_TEXTURE, LIVE_SCALE).also { live = it }
            paint(target, state, c, alerts)
            liveFor = r
            liveVehicleId = vehicle.id
            if (!logged) {
                logged = true
                LOGGER.info("Head-up display active for {}: {} deg/texel field", VehicleResource.getDefault(vehicle).id,
                    1f / c.frame.pxPerDeg)
            }
        } catch (e: Throwable) {
            failed = true
            LOGGER.error("Head-up display failed; disabled for this session", e)
        }
    }

    /** Draws [vehicle]'s HUD picture in the vehicle model frame (see [FlightDisplays.render]). */
    fun render(vehicle: VehicleEntity, poseStack: PoseStack, buffers: MultiBufferSource) {
        if (failed) return
        val r = resource(vehicle) ?: return
        val mc = Minecraft.getInstance()
        if (mc.player?.vehicle === vehicle && FlightDisplayState.crewSeat(vehicle) == r.seat) {
            val cam = vehicle.worldToVehicleLocal(mc.gameRenderer.mainCamera.position, mc.frameTime)
            measuredEye.set(cam.x, cam.y, cam.z)
            measuredFor = vehicle.id
        }
        val isLive = liveVehicleId == vehicle.id && liveFor === r && mc.player?.vehicle === vehicle
        val c = (if (isLive && liveFitFor === r) liveFit else null) ?: combiner(r) ?: return
        val texture = if (isLive) LIVE_TEXTURE else stills[r]?.second ?: run { stillRequested.add(r); return }
        val matrix = poseStack.last().pose()
        // Additive: black adds nothing, the symbols glow on the glass. A soft halo first, then the sharp picture.
        val glow = buffers.getBuffer(RenderType.eyes(texture))
        val halo = 0.0035f
        for ((dx, dy) in arrayOf(halo to 0f, -halo to 0f, 0f to halo, 0f to -halo)) emit(glow, matrix, c, 40, dx, dy)
        emit(glow, matrix, c, 255, 0f, 0f)
    }

    private fun emit(consumer: VertexConsumer, matrix: Matrix4f, c: Combiner, level: Int, du: Float, dv: Float) {
        for (i in 0 until GRID) for (j in 0 until GRID) {
            val corners = arrayOf(intArrayOf(i, j), intArrayOf(i, j + 1), intArrayOf(i + 1, j + 1), intArrayOf(i + 1, j))
            // both windings: the picture shows from the pilot's side and through the glass from outside
            for (order in arrayOf(intArrayOf(0, 1, 2, 3), intArrayOf(3, 2, 1, 0))) for (k in order) {
                val (a, b) = corners[k].let { it[0] to it[1] }
                val p = c.points[a][b]; val uv = c.uvs[a][b]
                consumer.vertex(matrix, p.x, p.y, p.z).color(level, level, level, 255).uv(uv[0] + du, uv[1] + dv)
                    .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0f, 1f, 0f).endVertex()
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Fits the picture to the combiner: the field of view the glass covers from the design eye (degrees off the nose,
     * square so both axes share one scale), and for each grid point of the glass the texture point in its direction.
     */
    private fun fit(r: HeadUpDisplayResource, eyeAt: Vector3d? = null): Combiner? {
        val centre = vec(r.center) ?: return null
        val eye = eyeAt ?: vec(r.eye) ?: return null
        val n = vec(r.normal)?.takeIf { it.lengthSquared() > 1e-9 }?.normalize() ?: return null
        val u0 = vec(r.up) ?: return null
        val up = Vector3d(u0).sub(Vector3d(n).mul(u0.dot(n))).takeIf { it.lengthSquared() > 1e-9 }?.normalize() ?: return null
        if (!(r.width > 0.01 && r.width < 1.0 && r.height > 0.01 && r.height < 1.0)) return null
        // From the pilot's side (normal toward them), right = up x normal: vehicle-local -X, the pilot's right.
        val right = Vector3d(up).cross(n).normalize()
        val local = Array(GRID + 1) { i ->
            Array(GRID + 1) { j ->
                Vector3d(centre).add(Vector3d(right).mul((i.toDouble() / GRID - 0.5) * r.width))
                    .add(Vector3d(up).mul((j.toDouble() / GRID - 0.5) * r.height))
            }
        }
        // direction of every grid point from the eye: degrees right and up of the nose (vehicle-local +Z)
        val angles = Array(GRID + 1) { i ->
            Array(GRID + 1) { j ->
                val d = Vector3d(local[i][j]).sub(eye)
                if (d.z <= 1e-3) return null
                doubleArrayOf(Math.toDegrees(atan2(-d.x, d.z)), Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z))))
            }
        }
        val all = angles.flatten()
        val azMin = all.minOf { it[0] }; val azMax = all.maxOf { it[0] }
        val elMin = all.minOf { it[1] }; val elMax = all.maxOf { it[1] }
        val field = maxOf(azMax - azMin, elMax - elMin) * 1.02
        if (!(field > 0.5 && field < 120.0)) return null
        val azC = (azMin + azMax) / 2; val elC = (elMin + elMax) / 2
        val size = HudPainter.SIZE.toFloat()
        // texture: u right, v up (render targets store their top row at v = 1); painter y runs down
        val uvs = Array(GRID + 1) { i ->
            Array(GRID + 1) { j ->
                val a = angles[i][j]
                floatArrayOf((0.5 + (a[0] - azC) / field).toFloat(), (0.5 + (a[1] - elC) / field).toFloat())
            }
        }
        val px = size / field.toFloat()
        val frame = HudPainter.Frame(
            pxPerDeg = px,
            boreX = (size * (0.5 - azC / field)).toFloat(),
            boreY = (size * (0.5 + elC / field)).toFloat(),
            left = (size * (0.5 + (azMin - azC) / field)).toFloat(),
            top = (size * (0.5 - (elMax - elC) / field)).toFloat(),
            right = (size * (0.5 + (azMax - azC) / field)).toFloat(),
            bottom = (size * (0.5 - (elMin - elC) / field)).toFloat(),
        )
        // model frame: vehicle-local turned 180 degrees about Y; the picture stands just in front of the glass
        val points = Array(GRID + 1) { i ->
            Array(GRID + 1) { j ->
                val p = Vector3d(local[i][j]).add(Vector3d(n).mul(LIFT.toDouble()))
                Vector3f(-p.x.toFloat(), p.y.toFloat(), -p.z.toFloat())
            }
        }
        val style = r.style?.uppercase() ?: "HUD"
        return Combiner(points, uvs, frame, r.color ?: (if (style == "HUD") DEFAULT_RGB else SIGHT_RGB), style)
    }

    private fun vec(a: DoubleArray?): Vector3d? =
        a?.takeIf { it.size == 3 && it.all(Double::isFinite) }?.let { Vector3d(it[0], it[1], it[2]) }

    // ------------------------------------------------------------------ painting

    /**
     * A target [scale] times [HudPainter.SIZE] texels across; the painter keeps drawing in its SIZE units (the ortho
     * projection below maps them over the whole target), so a larger target only adds sharpness.
     */
    private fun create(location: ResourceLocation, scale: Int = 1): RenderTarget {
        val target = TextureTarget(HudPainter.SIZE * scale, HudPainter.SIZE * scale, false, Minecraft.ON_OSX)
        target.setClearColor(0f, 0f, 0f, 1f)
        target.setFilterMode(GL11.GL_LINEAR)
        Minecraft.getInstance().textureManager.register(location, TargetTexture(target))
        return target
    }

    private fun paint(target: RenderTarget, state: FlightDisplayState, c: Combiner, alerts: HudPainter.Alerts) {
        val mc = Minecraft.getInstance()
        RenderSystem.assertOnRenderThread()
        val size = HudPainter.SIZE.toFloat()
        RenderSystem.backupProjectionMatrix()
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushPose()
        val previousScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        try {
            target.clear(Minecraft.ON_OSX)
            target.bindWrite(true)
            RenderSystem.setProjectionMatrix(Matrix4f().setOrtho(0f, size, size, 0f, 1000f, 3000f), VertexSorting.ORTHOGRAPHIC_Z)
            modelView.setIdentity()
            modelView.translate(0f, 0f, -2000f)
            RenderSystem.applyModelViewMatrix()
            RenderSystem.disableDepthTest()
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            val buffers = MultiBufferSource.immediate(Tesselator.getInstance().builder)
            val graphics = GuiGraphics(mc, buffers)
            when (c.style) {
                "GYRO", "REFLECTOR" -> GunsightPainter.paint(graphics, state, c.frame, c.rgb, c.style == "GYRO",
                    state !== FlightDisplayState.STILL)
                else -> HudPainter.paint(graphics, state, c.frame, alerts, c.rgb)
            }
            graphics.flush()
        } finally {
            RenderSystem.disableScissor()
            if (previousScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST)
            modelView.popPose()
            RenderSystem.applyModelViewMatrix()
            RenderSystem.restoreProjectionMatrix()
            RenderSystem.enableDepthTest()
            mc.mainRenderTarget.bindWrite(true)
        }
    }

    /** Exposes a render target's colour attachment to render types, always linearly filtered. */
    private class TargetTexture(private val target: RenderTarget) : AbstractTexture() {
        override fun getId(): Int = target.colorTextureId
        override fun load(manager: ResourceManager) {}
        override fun releaseId() {}
        override fun setFilter(blur: Boolean, mipmap: Boolean) {
            GlStateManager._bindTexture(getId())
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        }
    }
}
