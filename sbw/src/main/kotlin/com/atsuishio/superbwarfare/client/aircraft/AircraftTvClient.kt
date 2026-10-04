package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentRegistry
import com.atsuishio.superbwarfare.api.aircraft.AircraftTvGuidance
import com.atsuishio.superbwarfare.client.camera.FixedWingDynamicCamera
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.client.event.RenderHandEvent
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The pilot's view through a released TV munition's seeker (AircraftTvGuidance). The view is world-stabilised: the
 * mouse moves the line of sight, the munition turns to fly down it, and the picture does not swing as it turns. The
 * seeker is drawn a little ahead of the munition's nose so its own body stays out of the picture. The pod key leaves
 * the view (the seeker stays locked on the last point); losing the munition ends it.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AircraftTvClient {
    /** Seeker eye ahead of the munition's tail origin: past the nose of the longest TV munition (KD-88, 4.8 m). */
    private const val EYE_AHEAD = 5.5
    private const val MAX_ZOOM = 8.0
    private const val LOST_TICKS = 10

    private var munitionId = -1
    private var owner: VehicleEntity? = null
    private var level: Any? = null
    private var yaw = 0.0
    private var pitch = 0.0
    private var zoom = 1.0
    private var cursorX = Double.NaN
    private var cursorY = Double.NaN
    private var previousView: CameraType? = null
    private var missing = 0
    private var lastEye: Vec3? = null
    /** Flight-path direction at the last two client ticks, interpolated for the eye (it moved in 20 Hz steps). */
    private var previousHeading: Vec3? = null
    private var currentHeading: Vec3? = null

    @JvmStatic fun active(): Boolean = munitionId >= 0

    /** True while [vehicle]'s pilot is looking through a TV seeker. */
    @JvmStatic fun activeFor(vehicle: VehicleEntity?): Boolean = active() && vehicle != null && owner === vehicle

    @JvmStatic fun magnification(): Double? = if (active()) zoom else null

    /** Server: [json] carries "TvMunition" (and maybe the first aim point "TvPoint"). */
    fun begin(json: JsonObject, vehicle: VehicleEntity) {
        val mc = Minecraft.getInstance()
        val id = json["TvMunition"]?.asInt ?: return
        if (!active()) previousView = mc.options.cameraType
        munitionId = id; owner = vehicle; level = mc.level; missing = 0; zoom = 1.0
        cursorX = Double.NaN; cursorY = Double.NaN
        previousHeading = null; currentHeading = null
        // First line of sight: at the aim point the seeker starts on, else where the pilot was looking.
        val munition = mc.level?.getEntity(id)
        val start = munition?.position() ?: mc.gameRenderer.mainCamera.position
        val point = AircraftArmamentRegistry.vector(json["TvPoint"])
        val look = point?.subtract(start)?.takeIf { it.lengthSqr() > 1.0 }?.normalize()
            ?: mc.gameRenderer.mainCamera.lookVector.let { Vec3(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) }
        yaw = Math.toDegrees(atan2(-look.x, look.z)); pitch = Math.toDegrees(atan2(-look.y, hypot(look.x, look.z)))
        mc.options.setCameraType(CameraType.FIRST_PERSON)
        FixedWingDynamicCamera.reset()
    }

    fun end() {
        if (!active()) return
        val mc = Minecraft.getInstance()
        munitionId = -1; owner = null; lastEye = null
        if (mc.options.cameraType == CameraType.FIRST_PERSON) previousView?.let(mc.options::setCameraType)
        previousView = null
        FixedWingDynamicCamera.reset()
    }

    private fun munition(): Entity? = Minecraft.getInstance().level?.getEntity(munitionId)?.takeIf { !it.isRemoved }

    /**
     * Flight path of the munition: its velocity (smoothed munitions carry the server's), else its own movement. Not
     * the raw position step, which carries the blended network corrections.
     */
    private fun rawHeading(munition: Entity): Vec3 =
        munition.deltaMovement.takeIf { it.lengthSqr() > 1.0E-4 }?.normalize()
            ?: Vec3(munition.x - munition.xo, munition.y - munition.yo, munition.z - munition.zo)
                .takeIf { it.lengthSqr() > 1.0E-4 }?.normalize() ?: munition.lookAngle

    private fun heading(munition: Entity): Vec3 = currentHeading ?: rawHeading(munition)

    /** The heading between the last two ticks at [partial]. */
    private fun heading(munition: Entity, partial: Float): Vec3 {
        val from = previousHeading ?: return heading(munition)
        val to = currentHeading ?: return from
        val mixed = from.lerp(to, partial.toDouble().coerceIn(0.0, 1.0))
        return if (mixed.lengthSqr() > 1.0E-6) mixed.normalize() else to
    }

    /** Degrees of seeker turn per pixel of mouse travel: the player's own mouse sensitivity, as vanilla aiming. */
    private fun degreesPerPixel(): Double {
        val mc = Minecraft.getInstance()
        val s = mc.options.sensitivity().get() * 0.6 + 0.2
        return s * s * s * 8.0 * 0.15 / zoom
    }

    fun line(): Vec3 {
        val p = Math.toRadians(pitch); val y = Math.toRadians(yaw)
        return Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    /** Keeps the line of sight inside the seeker gimbal around the munition's flight path. */
    private fun clampToGimbal(munition: Entity) {
        val axis = heading(munition)
        val los = line()
        val limit = Math.toRadians(AircraftTvGuidance.GIMBAL_DEGREES)
        val angle = kotlin.math.acos(los.dot(axis).coerceIn(-1.0, 1.0))
        if (angle <= limit) return
        // slerp from the axis toward the line of sight, stopping on the gimbal edge
        val sinAngle = sin(angle)
        if (sinAngle < 1.0E-6) return
        val a = sin(angle - limit) / sinAngle; val b = sin(limit) / sinAngle
        val clamped = axis.scale(a).add(los.scale(b)).normalize()
        yaw = Math.toDegrees(atan2(-clamped.x, clamped.z)); pitch = Math.toDegrees(atan2(-clamped.y, hypot(clamped.x, clamped.z)))
    }

    @JvmStatic fun cameraPosition(vehicle: VehicleEntity, partial: Float): Vec3? {
        if (!activeFor(vehicle)) return null
        val munition = munition() ?: return lastEye
        val eye = munition.getPosition(partial).add(heading(munition, partial).scale(EYE_AHEAD))
        lastEye = eye
        return eye
    }

    @JvmStatic fun cameraRotation(vehicle: VehicleEntity): Vec2? =
        if (activeFor(vehicle)) Vec2(yaw.toFloat(), pitch.toFloat()) else null

    @SubscribeEvent fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END || !active()) return
        val mc = Minecraft.getInstance()
        val player = mc.player
        val vehicle = owner
        if (player == null || vehicle == null || mc.level !== level || player.vehicle !== vehicle || vehicle.isRemoved ||
            vehicle.isWreck || !player.isAlive) { end(); return }
        val munition = munition()
        if (munition == null) { if (++missing >= LOST_TICKS) end(); return }
        missing = 0
        previousHeading = currentHeading ?: rawHeading(munition)
        currentHeading = rawHeading(munition)
        clampToGimbal(munition)
        // every tick: the server keeps the newest line of sight (it used to arrive at 10 Hz and sometimes 5)
        if (mc.isPaused) return
        val eye = munition.position().add(heading(munition).scale(EYE_AHEAD))
        val los = line()
        AircraftArmamentClient.request("TV", JsonObject().apply {
            addProperty("Entity", munitionId)
            add("Origin", JsonArray().apply { add(eye.x); add(eye.y); add(eye.z) })
            add("Direction", JsonArray().apply { add(los.x); add(los.y); add(los.z) })
        })
    }

    @SubscribeEvent fun renderTick(event: TickEvent.RenderTickEvent) {
        if (event.phase != TickEvent.Phase.START || !active()) return
        val mc = Minecraft.getInstance()
        if (mc.screen != null || !mc.isWindowActive || !mc.mouseHandler.isMouseGrabbed) {
            cursorX = Double.NaN; cursorY = Double.NaN; return
        }
        val x = mc.mouseHandler.xpos(); val y = mc.mouseHandler.ypos()
        if (cursorX.isFinite() && cursorY.isFinite()) {
            // the player's own sensitivity and invert-Y, as when aiming on foot (owner 2026-09-30)
            val scale = degreesPerPixel()
            val invert = if (mc.options.invertYMouse().get()) -1.0 else 1.0
            yaw += (x - cursorX).coerceIn(-256.0, 256.0) * scale
            pitch = (pitch + invert * (y - cursorY).coerceIn(-256.0, 256.0) * scale).coerceIn(-89.9, 89.9)
            munition()?.let(::clampToGimbal)
        }
        cursorX = x; cursorY = y
    }

    /** Scroll zooms the seeker; returns true when it used the scroll. */
    fun scroll(delta: Double): Boolean {
        if (!active()) return false
        zoom = (zoom * Math.pow(1.25, delta)).coerceIn(1.0, MAX_ZOOM)
        return true
    }

    @SubscribeEvent fun hand(event: RenderHandEvent) { if (active()) event.isCanceled = true }

    /** The seeker picture shows only the seeker symbology, not the cockpit's HUD. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    fun overlays(event: RenderGuiOverlayEvent.Pre) {
        if (!active()) return
        val id = event.overlay.id
        if (id == VanillaGuiOverlay.CROSSHAIR.id() || (id.namespace == Mod.MODID && id.path != "aircraft_armament"))
            event.isCanceled = true
    }

    fun renderHud(graphics: GuiGraphics, width: Int, height: Int) {
        if (!active()) return
        val mc = Minecraft.getInstance()
        val color = 0xFFE8E8E8.toInt()
        val cx = width / 2; val cy = height / 2
        // open cross with a centre gap, and a box at the gimbal-corner scale
        graphics.fill(cx - 22, cy, cx - 6, cy + 1, color); graphics.fill(cx + 7, cy, cx + 23, cy + 1, color)
        graphics.fill(cx, cy - 22, cx + 1, cy - 6, color); graphics.fill(cx, cy + 7, cx + 1, cy + 23, color)
        graphics.fill(cx - 40, cy - 30, cx - 30, cy - 29, color); graphics.fill(cx + 31, cy - 30, cx + 41, cy - 29, color)
        graphics.fill(cx - 40, cy + 30, cx - 30, cy + 31, color); graphics.fill(cx + 31, cy + 30, cx + 41, cy + 31, color)
        val munition = munition()
        val status = if (munition == null) "LINK LOST" else "TV  ${String.format(java.util.Locale.ROOT, "%.1fx", zoom)}"
        graphics.drawCenteredString(mc.font, status, cx, 24, color)
        graphics.drawCenteredString(mc.font,
            "Mouse: steer  |  Scroll: zoom  |  [${AircraftArmamentKeys.POD.translatedKeyMessage.string}] Leave (seeker stays locked)",
            cx, 36, color)
        munition?.let {
            val range = it.position().distanceTo(mc.player?.position() ?: it.position())
            val speed = it.deltaMovement.length() * 20.0 * 3.6
            graphics.drawString(mc.font, String.format(java.util.Locale.ROOT, "%.0f km/h", speed), cx - 120, cy + 50, color)
            graphics.drawString(mc.font, String.format(java.util.Locale.ROOT, "%.0f m from you", range), cx + 60, cy + 50, color)
        }
    }
}
