package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager
import com.atsuishio.superbwarfare.api.aircraft.AircraftHelmetSight
import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreWeapons
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.worldToScreen
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Matrix4d
import org.joml.Vector3d
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Client half of [AircraftHelmetSight]: works out where the pilot looks, reports it to the server while it cues the
 * seeker, and draws the green helmet circle. The server decides the lock; nothing here can grant one.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AircraftHelmetSightClient {
    /** [view] is the pilot's world line of sight, [local] the same in the aircraft frame. */
    data class Cue(val vehicle: VehicleEntity, val view: Vec3, val local: Vec3, val offDegrees: Double, val limit: Double) {
        val within: Boolean get() = offDegrees <= limit
    }

    private const val GREEN = 0xFF3CFF64.toInt()
    private const val DIM = 0xB0A0A0A0.toInt()
    /** Report again while cueing after the line moved this far, or as a keep-alive at this tick interval. */
    private const val RESEND_DEGREES = 0.25
    private const val KEEPALIVE_TICKS = 6

    /** The newest cue sampled at render time, where the camera and the aircraft pose share one partial tick. */
    private var latest: Cue? = null
    /** Client ticks since [latest] was sampled: a HUD that stopped drawing (F1, a TV seeker) stops the reports. */
    private var latestAge = Int.MAX_VALUE / 2
    private var lastSent: Vec3? = null
    private var ticksSinceSend = Int.MAX_VALUE / 2
    private var reporting: VehicleEntity? = null

    /** The off-boresight limit for the store behind the displayed seeker, or null when helmet lock does not apply. */
    private fun limit(vehicle: VehicleEntity, seek: AircraftSeekView?): Double? {
        if (seek?.activeAam != true || seek.category != "AIR_TO_AIR") return null
        val mount = AircraftStoreWeapons.mountId(seek.weaponId) ?: return null
        val store = AircraftArmamentManager.equippedStore(vehicle, mount) ?: return null
        return AircraftHelmetSight.limit(AircraftArmamentManager.definition(vehicle), store)
    }

    /** Helmet cueing applies only to the operator's own first-person view, not the pod, a TV seeker or a free orbit. */
    private fun sample(vehicle: VehicleEntity, seek: AircraftSeekView?, partial: Float): Cue? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        if (mc.options.cameraType != CameraType.FIRST_PERSON || mc.cameraEntity !== player) return null
        if (AircraftArmamentClient.isPodActive(vehicle) || AircraftTvClient.activeFor(vehicle)) return null
        val limit = limit(vehicle, seek) ?: return null
        val look = mc.gameRenderer.mainCamera.lookVector
        val view = Vec3(look)
        if (!view.x.isFinite() || !view.y.isFinite() || !view.z.isFinite() || view.lengthSqr() < 0.5) return null
        val transform = vehicle.getVehicleTransform(partial)
        val n = transform.transformDirection(Vector3d(0.0, 0.0, 1.0)).normalize()
        val off = AircraftHelmetSight.angleDegrees(Vec3(n.x, n.y, n.z), view)
        if (off < AircraftHelmetSight.ENGAGE_DEGREES) return null
        val l = Matrix4d(transform).invert().transformDirection(Vector3d(view.x, view.y, view.z)).normalize()
        return Cue(vehicle, view.normalize(), Vec3(l.x, l.y, l.z), off, limit)
    }

    @SubscribeEvent fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        val vehicle = AircraftArmamentClient.operatedVehicle()
        val cue = latest?.takeIf { vehicle != null && it.vehicle === vehicle && it.within && latestAge <= 2 &&
            mc.screen == null && !mc.isPaused }
        ticksSinceSend++
        latestAge++
        if (reporting != null && reporting !== vehicle) { reporting = null; lastSent = null }
        if (vehicle == null) { latest = null; return }
        if (cue != null) {
            val moved = lastSent?.let { AircraftHelmetSight.angleDegrees(it, cue.local) > RESEND_DEGREES } ?: true
            if (ticksSinceSend >= AircraftHelmetSight.REPORT_INTERVAL_TICKS && (moved || ticksSinceSend >= KEEPALIVE_TICKS)) {
                if (AircraftArmamentClient.request("HELMET", JsonObject().apply {
                        add("Direction", JsonArray().apply { add(cue.local.x); add(cue.local.y); add(cue.local.z) })
                    })) {
                    lastSent = cue.local; ticksSinceSend = 0; reporting = vehicle
                }
            }
        } else if (reporting === vehicle) {
            // Looking back at the nose (or past the limit) hands the seeker back at once instead of waiting for staleness.
            AircraftArmamentClient.request("HELMET")
            reporting = null; lastSent = null; ticksSinceSend = 0
        }
    }

    /**
     * Samples this frame's cue and draws the helmet circle. Returns the circle's screen centre and radius while the
     * helmet slaves the seeker, so the seeker circle can start from where the pilot looks instead of the nose.
     */
    fun render(graphics: GuiGraphics, vehicle: VehicleEntity, seek: AircraftSeekView?, partial: Float,
               width: Int, height: Int): AircraftSeekerHud.Search? {
        val cue = sample(vehicle, seek, partial)
        latest = cue; latestAge = 0
        cue ?: return null
        val mc = Minecraft.getInstance()
        val camera = mc.gameRenderer.mainCamera
        val forward = Vec3(camera.lookVector)
        val up = Vec3(camera.upVector)
        val centre = camera.position.add(forward.scale(100.0)).worldToScreen()
        if (centre.z <= 0.01 || !centre.x.isFinite() || !centre.y.isFinite()) return null
        val a = Math.toRadians(AircraftHelmetSight.ACQUIRE_CONE_DEGREES)
        val edge = camera.position.add(forward.scale(cos(a) * 100.0)).add(up.scale(sin(a) * 100.0)).worldToScreen()
        val radius = hypot(edge.x - centre.x, edge.y - centre.y).takeIf { it.isFinite() }?.coerceIn(4.0, height * 0.25)
            ?: return null
        val color = if (cue.within) GREEN else DIM
        graphics.enableScissor(0, 0, width, height)
        try {
            var previous: Vec3? = null
            for (i in 0..64) {
                val angle = i * Math.PI * 2 / 64
                val point = Vec3(centre.x + cos(angle) * radius, centre.y - sin(angle) * radius, 1.0)
                previous?.let { AircraftSeekerHud.line(graphics, it, point, width, height, color) }
                previous = point
            }
            graphics.fill(centre.x.toInt(), centre.y.toInt(), centre.x.toInt() + 1, centre.y.toInt() + 1, color)
            val label = if (cue.within) "HMS" else "HMS LIMIT"
            graphics.drawCenteredString(mc.font, label, centre.x.toInt(), (centre.y + radius).toInt() + 4, color)
        } finally { graphics.disableScissor() }
        return if (cue.within) AircraftSeekerHud.Search(centre.x, centre.y, radius) else null
    }

    fun reset() { latest = null; latestAge = Int.MAX_VALUE / 2; lastSent = null; reporting = null; ticksSinceSend = Int.MAX_VALUE / 2 }
}
