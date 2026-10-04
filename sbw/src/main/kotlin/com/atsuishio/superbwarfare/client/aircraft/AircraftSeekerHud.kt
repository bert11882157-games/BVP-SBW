package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.worldToScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import kotlin.math.*

object AircraftSeekerHud {
    private val presentation = AircraftSeekerPresentation()
    fun reset() { presentation.reset(); AircraftSeekerSounds.reset() }

    fun render(graphics: GuiGraphics, vehicle: VehicleEntity, seek: AircraftSeekView?, target: Vec3?,
               partial: Float, width: Int, height: Int) {
        if (seek?.activeAam != true) { presentation.reset(); return }
        val mc = Minecraft.getInstance()
        val transform = vehicle.getVehicleTransform(partial)
        fun axis(x: Double, y: Double, z: Double): Vec3 {
            val v = transform.transformDirection(Vector3d(x, y, z)).normalize()
            return Vec3(v.x, v.y, v.z)
        }
        val forward = axis(0.0, 0.0, 1.0)
        val origin = mc.gameRenderer.mainCamera.position
        val centre = origin.add(forward.scale(200.0))
        val projectedCentre = centre.worldToScreen()
        if (projectedCentre.z <= 0.01 || !projectedCentre.x.isFinite() || !projectedCentre.y.isFinite()) return
        val fittedRadius = min(width, height) * 0.38 * (seek.coneDegrees / 50.0).coerceIn(0.1, 1.0)
        val acquired = target?.worldToScreen()?.takeIf { it.z > 0.01 && it.x.isFinite() && it.y.isFinite() }
        // A monotonic client clock: on a dedicated server the level time is corrected by the server's time packet
        // (every second), which can step it back a tick and used to restart the seeker circle.
        val circle = presentation.circle(seek, net.minecraft.Util.getNanos() / 1.0E9,
            projectedCentre.x, projectedCentre.y, fittedRadius, acquired?.let { Pair(it.x, it.y) })
        val color = if (seek.ready) 0xFF80FF94.toInt() else 0xDFFFF1B0.toInt()
        var previous: Vec3? = null
        graphics.enableScissor(0, 0, width, height)
        try {
            if (seek.guidanceMode in setOf("ANTI_RADIATION", "GROUND_INFRARED")) {
                val r = circle.radius
                val points = listOf(Vec3(circle.x-r,circle.y-r,1.0), Vec3(circle.x+r,circle.y-r,1.0),
                    Vec3(circle.x+r,circle.y+r,1.0), Vec3(circle.x-r,circle.y+r,1.0))
                for (i in points.indices) line(graphics, points[i], points[(i+1)%4], width, height, color)
            } else for (i in 0..96) {
                val angle = i * Math.PI * 2 / 96
                val coordinates = circle.point(angle)
                val point = Vec3(coordinates.first, coordinates.second, 1.0)
                if (previous != null) line(graphics, previous, point, width, height, color)
                previous = point
            }
            if (acquired != null && seek.ready && acquired.x in 0.0..width.toDouble() && acquired.y in 0.0..height.toDouble())
                graphics.drawCenteredString(mc.font, "LOCK", acquired.x.toInt(), acquired.y.toInt() + 13, color)
        } finally { graphics.disableScissor() }
    }

    /** Clip before rasterizing so off-screen cone arcs cannot create unbounded GUI work. */
    private fun line(g: GuiGraphics, a: Vec3, b: Vec3, width: Int, height: Int, color: Int) {
        val dx = b.x - a.x; val dy = b.y - a.y
        var low = 0.0; var high = 1.0
        fun clip(p: Double, q: Double): Boolean {
            if (abs(p) < 1e-9) return q >= 0
            val r = q / p
            if (p < 0) { if (r > high) return false; low = max(low, r) }
            else { if (r < low) return false; high = min(high, r) }
            return true
        }
        if (!clip(-dx, a.x) || !clip(dx, width - a.x) || !clip(-dy, a.y) || !clip(dy, height - a.y)) return
        val x = a.x + low * dx; val y = a.y + low * dy
        val sx = (high - low) * dx; val sy = (high - low) * dy
        val length = hypot(sx, sy)
        if (length < 0.01) return
        val ox = -sy / length * 0.3; val oy = sx / length * 0.3
        val buffer = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui())
        val matrix = g.pose().last().pose()
        fun vertex(px: Double, py: Double) {
            buffer.vertex(matrix, px.toFloat(), py.toFloat(), 0F)
                .color((color ushr 16) and 255, (color ushr 8) and 255, color and 255, (color ushr 24) and 255).endVertex()
        }
        vertex(x + ox, y + oy); vertex(x + sx + ox, y + sy + oy)
        vertex(x + sx - ox, y + sy - oy); vertex(x - ox, y - oy)
    }
}
