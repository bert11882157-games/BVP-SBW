package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.math.abs
import kotlin.math.pow

/**
 * Canopy glass: the triangles tools/canopy_glass/glass.py fitted over each aircraft's canopy openings, drawn as a
 * faint blue-grey tint that thickens toward grazing angles (a cheap Fresnel), both from inside and outside.
 */
object CanopyGlass {
    private val TEXTURE = Mod.loc("textures/misc/canopy_glass.png")
    private const val BASE_ALPHA = 0.08f
    private const val EDGE_ALPHA = 0.32f

    private class Mesh(val points: FloatArray, val normals: FloatArray)

    private val cache = HashMap<DefaultVehicleResource.CanopyGlassResource, Mesh?>()

    private fun mesh(resource: DefaultVehicleResource.CanopyGlassResource): Mesh? = cache.getOrPut(resource) {
        val t = resource.triangles ?: return@getOrPut null
        if (resource.frame != "VEHICLE_LOCAL_BLOCKS" || t.size < 9 || t.size % 9 != 0 || !t.all(Double::isFinite)) return@getOrPut null
        // Vehicle-local (+X left, +Z forward) to the model frame: rotate 180 degrees about Y.
        val p = FloatArray(t.size) { i -> (if (i % 3 == 1) t[i] else -t[i]).toFloat() }
        val n = FloatArray(t.size / 3)
        for (k in 0 until t.size / 9) {
            val o = k * 9
            val a = Vector3f(p[o], p[o + 1], p[o + 2])
            val e1 = Vector3f(p[o + 3], p[o + 4], p[o + 5]).sub(a)
            val e2 = Vector3f(p[o + 6], p[o + 7], p[o + 8]).sub(a)
            val nn = e1.cross(e2)
            if (nn.lengthSquared() > 1e-12f) nn.normalize()
            n[k * 3] = nn.x; n[k * 3 + 1] = nn.y; n[k * 3 + 2] = nn.z
        }
        Mesh(p, n)
    }

    /** Draws [vehicle]'s canopy glass in the vehicle model frame (see [FlightDisplays.render]). */
    fun render(vehicle: VehicleEntity, poseStack: PoseStack, buffers: MultiBufferSource, packedLight: Int) {
        val resource = VehicleResource.getDefault(vehicle).canopyGlass ?: return
        val mesh = mesh(resource) ?: return
        val pose = poseStack.last()
        val matrix = pose.pose()
        // Camera in the model frame: the pose maps model space to camera-relative view space.
        val cam = Matrix4f(matrix).invert().transform(Vector4f(0f, 0f, 0f, 1f))
        val consumer = buffers.getBuffer(RenderType.entityTranslucent(TEXTURE))
        val p = mesh.points
        for (k in 0 until p.size / 9) {
            val o = k * 9
            val cx = (p[o] + p[o + 3] + p[o + 6]) / 3f
            val cy = (p[o + 1] + p[o + 4] + p[o + 7]) / 3f
            val cz = (p[o + 2] + p[o + 5] + p[o + 8]) / 3f
            val v = Vector3f(cam.x - cx, cam.y - cy, cam.z - cz)
            if (v.lengthSquared() > 1e-8f) v.normalize()
            val nx = mesh.normals[k * 3]; val ny = mesh.normals[k * 3 + 1]; val nz = mesh.normals[k * 3 + 2]
            val facing = abs(v.x * nx + v.y * ny + v.z * nz)
            val alpha = (BASE_ALPHA + EDGE_ALPHA * (1f - facing).pow(3)).coerceIn(0f, 0.45f)
            val a = (alpha * 255).toInt()
            val n = pose.normal().transform(Vector3f(nx, ny, nz))
            // Triangles go out as degenerate quads (the entity render types draw quads).
            for (i in intArrayOf(0, 1, 2, 2)) {
                consumer.vertex(matrix, p[o + i * 3], p[o + i * 3 + 1], p[o + i * 3 + 2])
                    .color(205, 228, 240, a).uv(0.5f, 0.5f).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(packedLight).normal(n.x, n.y, n.z).endVertex()
            }
        }
    }
}
