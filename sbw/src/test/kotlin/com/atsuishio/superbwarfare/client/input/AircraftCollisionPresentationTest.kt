package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionRole
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionSnapshot
import com.atsuishio.superbwarfare.client.renderer.special.AircraftCollisionDebugRenderer
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Vector3d
import kotlin.math.abs

/** Actual snapshot, debug vertex emission and UI hit reduction without a Minecraft world. */
object AircraftCollisionPresentationTest {
    private var checks = 0
    private fun expect(value: Boolean, reason: String) { checks++; check(value) { reason } }

    @JvmStatic
    fun main(args: Array<String>) {
        for (yaw in doubleArrayOf(0.0, -35.0, 35.0, 90.0, 179.0)) {
            for (pitch in doubleArrayOf(0.0, 25.0, -50.0)) {
                for (roll in doubleArrayOf(0.0, 45.0, -120.0)) {
                    for (partial in doubleArrayOf(0.0, 0.25, 0.5, 1.0)) {
                        for (gear in floatArrayOf(0F, 0.001F, 0.5F, 1F)) {
                            checkFrame(yaw, pitch, roll, partial, gear)
                        }
                    }
                }
            }
        }
        fixedGearAndReplay()
        pickOrdering()
        uiScopeAndShoulderRay()
        println("PASS $checks physical snapshot/debug/pick checks; no world or GL context")
    }

    private fun definition(retractable: Boolean = true) = AircraftTerrainContact().apply {
        fuselage.minimum = Vec3(-2.0, 0.0, -4.0)
        fuselage.maximum = Vec3(2.0, 2.0, 4.0)
        landingGear.minimum = Vec3(-1.0, -2.0, -3.0)
        landingGear.maximum = Vec3(1.0, -1.0, 3.0)
        retractableGear = retractable
    }

    private fun point(frame: Matrix4d, x: Double, y: Double, z: Double): Vec3 {
        val v = frame.transformPosition(Vector3d(x, y, z))
        return Vec3(v.x, v.y, v.z)
    }

    private fun checkFrame(yaw: Double, pitch: Double, roll: Double, partial: Double, gear: Float) {
        val origin = Vec3(100.0 + partial * 2.0, 300.0 - partial, -70.0 + partial * 3.0)
        val frame = Matrix4d().translation(origin.x, origin.y, origin.z)
            .translate(0.7, 0.2, -0.3).rotateY(Math.toRadians(yaw))
            .rotateX(Math.toRadians(pitch)).rotateZ(Math.toRadians(roll)).translate(-0.7, -0.2, 0.3)
        val snapshot = AircraftCollisionSnapshot.create(definition(), frame, gear)
        val activeCount = if (gear <= 0.001F) 2 else 1
        expect(snapshot.parts.size == 2, "always two definitions, including inactive gear")
        val output = RecordingConsumer()
        AircraftCollisionDebugRenderer.render(snapshot, origin, PoseStack(), output)
        expect(output.vertices.size == activeCount * 24, "exactly twelve edges per active part")
        var offset = 0
        for (part in snapshot.parts) {
            if (!part.active) continue
            val seen = HashSet<Pair<Int, Int>>()
            for (edge in 0 until 12) {
                val from = output.vertices[offset + edge * 2]
                val to = output.vertices[offset + edge * 2 + 1]
                fun corner(vertex: Vertex): Int = part.worldVertices.indexOfFirst {
                    abs(it.x - origin.x - vertex.x) < 0.00002 &&
                        abs(it.y - origin.y - vertex.y) < 0.00002 &&
                        abs(it.z - origin.z - vertex.z) < 0.00002
                }
                val a = corner(from)
                val b = corner(to)
                expect(a >= 0 && b >= 0, "render physical corners in interpolated entity-root space")
                expect(Integer.bitCount(a xor b) == 1 && a < b, "physical edge, not diagonal or AABB")
                expect(seen.add(a to b), "no duplicate edge or old core overlay")
                for (vertex in listOf(from, to)) {
                    val expectedGreen = if (part.role == AircraftCollisionRole.LANDING_GEAR) 255 else 0
                    val expectedBlue = if (part.role == AircraftCollisionRole.FUSELAGE) 255 else 0
                    expect(vertex.red == 0 && vertex.green == expectedGreen && vertex.blue == expectedBlue &&
                        vertex.alpha == 255, "blue fuselage and opaque green active gear only")
                    expect(abs(vertex.nx * vertex.nx + vertex.ny * vertex.ny + vertex.nz * vertex.nz - 1F) <
                        0.00001F, "finite unit line normal")
                }
            }
            offset += 24
        }
        val bodyStart = point(frame, -5.0, 1.0, 0.0)
        val bodyEnd = point(frame, 5.0, 1.0, 0.0)
        val body = snapshot.clip(bodyStart, bodyEnd)
        expect(body != null && body.distanceToSqr(point(frame, -2.0, 1.0, 0.0)) < 1e-12,
            "rotated fuselage selection uses physical face")
        val inside = point(frame, 0.0, 1.0, 0.0)
        expect(snapshot.clip(inside, bodyEnd) == inside, "inside-start selection stays at the eye")
        val gearHit = snapshot.clip(point(frame, -5.0, -1.5, 0.0), point(frame, 5.0, -1.5, 0.0))
        expect((gearHit != null) == (activeCount == 2), "inactive gear is neither drawn nor pickable")
        expect(snapshot.clip(point(frame, -5.0, -0.5, 0.0), point(frame, 5.0, -0.5, 0.0)) == null,
            "empty gap in discovery AABB is not a UI target")
    }

    private fun fixedGearAndReplay() {
        for (gear in floatArrayOf(0F, 1F, Float.NaN)) {
            val snapshot = AircraftCollisionSnapshot.create(definition(false), Matrix4d(), gear)
            val first = RecordingConsumer()
            AircraftCollisionDebugRenderer.render(snapshot, Vec3.ZERO, PoseStack(), first)
            expect(first.vertices.size == 48, "fixed gear always contributes the second physical box")
            repeat(20) {
                val replay = RecordingConsumer()
                AircraftCollisionDebugRenderer.render(snapshot, Vec3.ZERO, PoseStack(), replay)
                expect(replay.vertices == first.vertices, "render replay neither mutates nor accumulates geometry")
            }
        }
    }

    private fun pickOrdering() {
        val start = Vec3.ZERO
        fun hit(distance: Double) = EntityHitResult(null, Vec3(distance, 0.0, 0.0))
        val near = hit(1.0)
        val far = hit(2.0)
        expect(AircraftCollisionPicking.nearer(start, null, near, 9.0) === near,
            "returns exact physical hit/parent unchanged")
        expect(AircraftCollisionPicking.nearer(start, far, near, 9.0) === near, "nearest aircraft wins")
        expect(AircraftCollisionPicking.nearer(start, near, far, 9.0) === near, "nearer native entity retained")
        expect(AircraftCollisionPicking.nearer(start, near, null, 9.0) === near, "physical miss preserves other entities")
        expect(AircraftCollisionPicking.nearer(start, null, far, 1.0) == null, "block/reach limit retained")
        expect(AircraftCollisionPicking.nearer(start, near, hit(1.0), 9.0) === near, "ties preserve existing target")
        expect(AircraftCollisionPicking.nearer(start, null, hit(Double.NaN), 9.0) == null, "numeric failure rejected")
        expect(AircraftCollisionPicking.nearer(start, null, hit(Double.POSITIVE_INFINITY), 9.0) == null,
            "infinite hit rejected")
        expect(AircraftCollisionPicking.nearer(start, null, hit(0.0), 0.0) != null, "zero-distance inside hit admitted")
    }

    private fun uiScopeAndShoulderRay() {
        val scope = AircraftCollisionPicking.UiPickScope()
        expect(!scope.isActive, "ordinary weapon/projectile calls begin outside UI scope")
        for (partial in floatArrayOf(0F, 0.25F, 0.75F, 1F)) {
            scope.begin(partial)
            repeat(3) {
                expect(scope.isActive, "multiple camera-mod queries may share one UI pick")
                expect(scope.enter() == partial, "same render partial reaches physical collision")
                expect(!scope.isActive, "native delegate is not intercepted recursively")
                scope.leave(true)
                expect(scope.isActive, "successful native delegate restores only the current UI scope")
            }
            scope.end()
            expect(!scope.isActive, "return closes scope before any later weapon query")
        }
        scope.begin(0.5F)
        scope.enter()
        scope.leave(false)
        expect(!scope.isActive, "failed resolver closes rather than leaking UI ownership")
        scope.begin(Float.NaN)
        expect(!scope.isActive, "nonfinite render partial cannot arm UI scope")

        AircraftCollisionPicking.beginUiPick(0.5F)
        var otherThreadActive = true
        val thread = Thread { otherThreadActive = AircraftCollisionPicking.isUiPickActive() }
        thread.start(); thread.join()
        expect(!otherThreadActive && AircraftCollisionPicking.isUiPickActive(), "UI scope is thread confined")
        try {
            AircraftCollisionPicking.pickInUiScope(null, Vec3.ZERO, Vec3(1.0, 0.0, 0.0),
                net.minecraft.world.phys.AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), { true }, 1.0)
            error("null viewer must fail")
        } catch (_: NullPointerException) {
            expect(!AircraftCollisionPicking.isUiPickActive(), "actual resolver finally closes on failure")
        } finally {
            AircraftCollisionPicking.endUiPick()
        }

        val snapshot = AircraftCollisionSnapshot.create(definition(), Matrix4d(), 0F)
        val shoulderStart = Vec3(0.0, 1.0, -8.0)
        val shoulderEnd = Vec3(0.0, 1.0, 8.0)
        val playerEye = Vec3(5.0, 1.0, -8.0)
        val playerEnd = Vec3(5.0, 1.0, 8.0)
        expect(snapshot.clip(playerEye, playerEnd) == null, "unoffset player ray misses control")
        val physical = snapshot.clip(shoulderStart, shoulderEnd)
        expect(physical != null && physical.distanceToSqr(Vec3(0.0, 1.0, -4.0)) < 1e-12,
            "supplied shoulder ray hits physical part without eye/camera reconstruction")
        expect(snapshot.clip(Vec3(0.0, -0.5, -8.0), Vec3(0.0, -0.5, 8.0)) == null,
            "shoulder ray through empty lower union inset remains a miss")
        val exactHit = EntityHitResult(null, physical!!)
        expect(AircraftCollisionPicking.nearer(shoulderStart, null, exactHit, 9.0) == null,
            "camera-mod distance limit still occludes the physical hit")
    }

    private data class Vertex(val x: Double, val y: Double, val z: Double,
                              val red: Int, val green: Int, val blue: Int, val alpha: Int,
                              val nx: Float, val ny: Float, val nz: Float)

    private class RecordingConsumer : VertexConsumer {
        val vertices = ArrayList<Vertex>()
        private var x = 0.0; private var y = 0.0; private var z = 0.0
        private var red = 0; private var green = 0; private var blue = 0; private var alpha = 0
        private var nx = 0F; private var ny = 0F; private var nz = 0F
        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
            this.x = x; this.y = y; this.z = z; return this
        }
        override fun color(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
            this.red = red; this.green = green; this.blue = blue; this.alpha = alpha; return this
        }
        override fun normal(x: Float, y: Float, z: Float): VertexConsumer {
            nx = x; ny = y; nz = z; return this
        }
        override fun endVertex() { vertices.add(Vertex(x, y, z, red, green, blue, alpha, nx, ny, nz)) }
        override fun uv(u: Float, v: Float): VertexConsumer = this
        override fun overlayCoords(u: Int, v: Int): VertexConsumer = this
        override fun uv2(u: Int, v: Int): VertexConsumer = this
        override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int) = error("unexpected default color")
        override fun unsetDefaultColor() = error("unexpected default color")
    }
}
