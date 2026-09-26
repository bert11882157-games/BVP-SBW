package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Canopy glass: the dome tools/canopy_glass/glass.py fitted over each aircraft's canopy, drawn as a faint blue-grey
 * tint that thickens toward grazing angles (a cheap Fresnel), from inside and outside.
 *
 * The glass must not hide anything behind it, so it never writes depth and is drawn last: while the level renders,
 * each vehicle's glass is transformed into view space and collected; after particles it is drawn in one batch,
 * depth-tested against everything already drawn (terrain, vehicles, smoke) but never occluding any of it.
 */
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object CanopyGlass {
    private const val BASE_ALPHA = 0.07f
    private const val EDGE_ALPHA = 0.30f
    private const val MAX_ALPHA = 0.42f
    private const val TINT_R = 205; private const val TINT_G = 228; private const val TINT_B = 240
    private const val VERTEX_FLOATS = 7            // x y z (view space), r g b a
    private const val MAX_VERTICES = 300_000

    /** Model-frame triangles (x, y, z per corner) with a smooth normal per corner. */
    private class Mesh(val points: FloatArray, val normals: FloatArray)

    private val cache = HashMap<DefaultVehicleResource.CanopyGlassResource, Mesh?>()
    private var collecting = false
    private var batch = FloatArray(VERTEX_FLOATS * 4096)
    private var vertexCount = 0

    private fun mesh(resource: DefaultVehicleResource.CanopyGlassResource): Mesh? = cache.getOrPut(resource) {
        if (resource.frame != "VEHICLE_LOCAL_BLOCKS") return@getOrPut null
        val local = when (resource.schema) {
            1 -> resource.triangles?.takeIf { it.size >= 9 && it.size % 9 == 0 }
            2 -> dome(resource)
            else -> null
        } ?: return@getOrPut null
        if (!local.all(Double::isFinite)) return@getOrPut null
        // Vehicle-local (+X left, +Z forward) to the model frame: rotate 180 degrees about Y.
        val p = FloatArray(local.size) { i -> (if (i % 3 == 1) local[i] else -local[i]).toFloat() }
        Mesh(p, smoothNormals(p))
    }

    /** Triangles of a Schema 2 dome: a fan round the apex, then quads between neighbouring spokes. */
    private fun dome(r: DefaultVehicleResource.CanopyGlassResource): DoubleArray? {
        val apex = r.apex?.takeIf { it.size == 3 } ?: return null
        val spokes = r.spokes ?: return null
        val n = r.rings
        if (n < 1 || spokes.size < 3 || spokes.any { it.size != n * 3 }) return null
        val out = ArrayList<Double>(spokes.size * (6 * n - 3) * 3)
        fun add(s: Int, k: Int) {                      // k = -1 is the apex
            if (k < 0) { out.add(apex[0]); out.add(apex[1]); out.add(apex[2]) }
            else { val a = spokes[s]; out.add(a[k * 3]); out.add(a[k * 3 + 1]); out.add(a[k * 3 + 2]) }
        }
        for (i in spokes.indices) {
            val j = (i + 1) % spokes.size
            add(i, -1); add(i, 0); add(j, 0)
            for (k in 0 until n - 1) {
                add(i, k); add(j, k); add(j, k + 1)
                add(i, k); add(j, k + 1); add(i, k + 1)
            }
        }
        return out.toDoubleArray()
    }

    /** Per-corner normals averaged over every triangle sharing that point, so the tint shades smoothly. */
    private fun smoothNormals(p: FloatArray): FloatArray {
        val sums = HashMap<Long, Vector3f>()
        fun key(i: Int): Long {
            val x = Math.round(p[i] * 2048.0); val y = Math.round(p[i + 1] * 2048.0); val z = Math.round(p[i + 2] * 2048.0)
            return (x and 0x1FFFFFL) or ((y and 0x1FFFFFL) shl 21) or ((z and 0x1FFFFFL) shl 42)
        }
        val faces = p.size / 9
        for (t in 0 until faces) {
            val o = t * 9
            val e1 = Vector3f(p[o + 3] - p[o], p[o + 4] - p[o + 1], p[o + 5] - p[o + 2])
            val e2 = Vector3f(p[o + 6] - p[o], p[o + 7] - p[o + 1], p[o + 8] - p[o + 2])
            val n = e1.cross(e2)                        // area-weighted
            for (c in 0 until 3) sums.getOrPut(key(o + c * 3)) { Vector3f() }.add(n)
        }
        val out = FloatArray(p.size)
        for (v in 0 until p.size / 3) {
            val n = Vector3f(sums[key(v * 3)] ?: Vector3f(0f, 1f, 0f))
            if (n.lengthSquared() > 1e-12f) n.normalize() else n.set(0f, 1f, 0f)
            out[v * 3] = n.x; out[v * 3 + 1] = n.y; out[v * 3 + 2] = n.z
        }
        return out
    }

    /** Collects [vehicle]'s glass in the vehicle model frame (see [FlightDisplays.render]); drawn after particles. */
    fun render(vehicle: VehicleEntity, poseStack: PoseStack, @Suppress("UNUSED_PARAMETER") buffers: net.minecraft.client.renderer.MultiBufferSource, packedLight: Int) {
        if (!collecting) return                          // item/GUI renders outside the level pass
        val resource = VehicleResource.getDefault(vehicle).canopyGlass ?: return
        val mesh = mesh(resource) ?: return
        val corners = mesh.points.size / 3
        if (vertexCount + corners > MAX_VERTICES) return
        ensure(vertexCount + corners)
        val pose = poseStack.last()
        val matrix = pose.pose()
        val normalMatrix = pose.normal()
        val light = brightness(packedLight)
        val r = TINT_R / 255f * light; val g = TINT_G / 255f * light; val b = TINT_B / 255f * light
        val p = mesh.points; val nm = mesh.normals
        val pos = Vector4f(); val nrm = Vector3f()
        var o = vertexCount * VERTEX_FLOATS
        for (v in 0 until corners) {
            pos.set(p[v * 3], p[v * 3 + 1], p[v * 3 + 2], 1f)
            matrix.transform(pos)
            normalMatrix.transform(nrm.set(nm[v * 3], nm[v * 3 + 1], nm[v * 3 + 2]))
            // View space: the camera is at the origin, so the view vector is -pos.
            val len = sqrt(pos.x * pos.x + pos.y * pos.y + pos.z * pos.z).coerceAtLeast(1e-4f)
            val nl = nrm.length().coerceAtLeast(1e-4f)
            val facing = abs((pos.x * nrm.x + pos.y * nrm.y + pos.z * nrm.z) / (len * nl))
            val alpha = (BASE_ALPHA + EDGE_ALPHA * (1f - facing).pow(3)).coerceIn(0f, MAX_ALPHA)
            batch[o] = pos.x; batch[o + 1] = pos.y; batch[o + 2] = pos.z
            batch[o + 3] = r; batch[o + 4] = g; batch[o + 5] = b; batch[o + 6] = alpha
            o += VERTEX_FLOATS
        }
        vertexCount += corners
    }

    private fun brightness(packedLight: Int): Float {
        val level = Minecraft.getInstance().level
        val sky = LightTexture.sky(packedLight) * (if (level == null) 1f else 1f - level.skyDarken / 11f)
        val block = LightTexture.block(packedLight).toFloat()
        return 0.3f + 0.7f * (max(sky, block) / 15f).coerceIn(0f, 1f)
    }

    private fun ensure(vertices: Int) {
        if (vertices * VERTEX_FLOATS <= batch.size) return
        batch = batch.copyOf(max(vertices * VERTEX_FLOATS, batch.size * 2))
    }

    @SubscribeEvent
    fun onStage(event: RenderLevelStageEvent) {
        when (event.stage) {
            RenderLevelStageEvent.Stage.AFTER_SKY -> { collecting = true; vertexCount = 0 }
            RenderLevelStageEvent.Stage.AFTER_PARTICLES -> {
                collecting = false
                if (vertexCount > 0) draw()
                vertexCount = 0
            }
            else -> {}
        }
    }

    private fun draw() {
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushPose()
        modelView.setIdentity()                          // vertices are already in view space
        RenderSystem.applyModelViewMatrix()
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()
        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)
        val q = batch
        for (v in 0 until vertexCount) {
            val o = v * VERTEX_FLOATS
            builder.vertex(q[o].toDouble(), q[o + 1].toDouble(), q[o + 2].toDouble())
                .color(q[o + 3], q[o + 4], q[o + 5], q[o + 6]).endVertex()
        }
        BufferUploader.drawWithShader(builder.end())
        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.disableBlend()
        modelView.popPose()
        RenderSystem.applyModelViewMatrix()
    }
}
