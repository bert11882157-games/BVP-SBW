package com.atsuishio.superbwarfare.api.vehicle.pose

import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleSnapshot
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderPartSnapshot
import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData
import com.atsuishio.superbwarfare.data.vehicle.VehicleAttachmentDataValidator
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleAttachmentInfo
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleAttachmentRotationChannel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class VehicleAttachmentPitchTest {
    private val forward = Vec3(0.0, 0.0, 1.0)
    private val pivot = Vec3(-0.75, 0.4, 0.2)
    private val muzzle = Vec3(0.12, -0.08, 2.4)
    private val nodes = listOf(
        VehicleAttachmentNode("pitch", "Turret", VehicleAttachmentRotationChannel.TURRET_PITCH),
        VehicleAttachmentNode("muzzle", "pitch"),
        VehicleAttachmentNode("legacy", "Turret"),
    )
    private val local = mapOf(
        "pitch" to Matrix4d().translation(pivot.x, pivot.y, pivot.z),
        "muzzle" to Matrix4d().translation(muzzle.x, muzzle.y, muzzle.z).rotateY(0.13),
        "legacy" to Matrix4d().translation(0.2, 0.3, 0.4).rotateY(-0.2),
    )

    @Test fun `independent pivots apply actual pitch once after full hull and turret yaw`() {
        val graph = VehicleAttachmentGraph(nodes, VehicleAttachmentGraph.NATIVE_BASE_FRAMES)
        assertTrue(graph.followsTurretPitch("pitch"))
        assertTrue(graph.followsTurretPitch("muzzle"))
        assertFalse(graph.followsTurretPitch("legacy"))
        assertFalse(graph.followsTurretPitch("missing"))
        for (heading in listOf(-180.0, -35.0, 0.0, 90.0)) {
            for (yaw in listOf(-180.0, -35.0, 0.0, 35.0, 90.0, 180.0)) {
                for (pitch in listOf(-40F, 0F, 25F, 60F)) {
                    val turret = Matrix4d().translation(100.0, 50.0, -20.0)
                        .rotateY(Math.toRadians(-heading)).rotateX(0.15).rotateZ(-0.2)
                        .translate(0.0, 1.2, 0.4).rotateY(Math.toRadians(yaw))
                    val base = VehicleAttachmentSnapshot.fromMatrices(13, 24, mapOf("Turret" to turret))
                    val actual = graph.resolve(base, local, pitch)
                    val expected = VehicleTransformSnapshot("expected", 13, 24, Matrix4d(turret)
                        .translate(pivot.x, pivot.y, pivot.z).rotateX(Math.toRadians(pitch.toDouble()))
                        .mul(local.getValue("muzzle")))
                    close(expected.localToWorld(Vec3.ZERO), actual.point("muzzle", Vec3.ZERO)!!)
                    close(expected.localDirectionToWorld(forward), actual.direction("muzzle", forward)!!)
                    assertEquals(13, actual.transform("muzzle")!!.sequence)
                    assertEquals(24L, actual.transform("muzzle")!!.serverTick)
                    val legacy = VehicleTransformSnapshot("legacy", 13, 24,
                        Matrix4d(turret).mul(local.getValue("legacy")))
                    close(legacy.localToWorld(Vec3.ZERO), actual.point("legacy", Vec3.ZERO)!!)
                }
            }
        }
    }

    @Test fun `absent or nonfinite pitch omits only opted node and descendants`() {
        val graph = VehicleAttachmentGraph(nodes, VehicleAttachmentGraph.NATIVE_BASE_FRAMES)
        val base = VehicleAttachmentSnapshot.fromMatrices(1, 1, mapOf("Turret" to Matrix4d()))
        for (pitch in listOf(null, Float.NaN, Float.POSITIVE_INFINITY)) {
            val result = graph.resolve(base, local, pitch)
            assertNull(result.transform("pitch"))
            assertNull(result.transform("muzzle"))
            assertNotNull(result.transform("legacy"))
        }
        val chassisOnly = VehicleAttachmentSnapshot.fromMatrices(1, 1, mapOf("Vehicle" to Matrix4d()))
        assertNull(graph.resolve(chassisOnly, local, 15F).transform("muzzle"))
        val prior = graph.resolve(base, local, 20F)
        val point = prior.point("muzzle", Vec3.ZERO)!!
        graph.resolve(base, local, -25F)
        assertEquals(point, prior.point("muzzle", Vec3.ZERO))
    }

    @Test fun `authored muzzle direction is not reinterpreted as an extra servo rotation`() {
        val graph = VehicleAttachmentGraph(nodes, VehicleAttachmentGraph.NATIVE_BASE_FRAMES)
        val base = VehicleAttachmentSnapshot.fromMatrices(1, 1, mapOf("Turret" to Matrix4d()))
        val accepted = graph.resolve(base, local, 25F)
        val direction = accepted.direction("muzzle", forward)!!
        val folded = com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMath.directionAngles(
            direction.x, direction.y, direction.z,
        )
        val wrongBase = VehicleAttachmentSnapshot.fromMatrices(1, 1,
            mapOf("Turret" to Matrix4d().rotateY(Math.toRadians(folded.yaw.toDouble()))))
        val doubled = graph.resolve(wrongBase, local, folded.pitch).direction("muzzle", forward)!!
        assertTrue(direction.dot(doubled) < 0.999, "folding the child direction would double articulation")
        assertTrue(graph.followsTurretPitch("muzzle"))
        close(direction, graph.resolve(base, local, 25F).direction("muzzle", forward)!!)
    }

    @Test fun `strict authored channel rejects wrong parent and oriented pitch roots`() {
        val info = Json.decodeFromString<VehicleAttachmentInfo>(
            """{"Parent":"Turret","Position":[0,1,2],"RotationChannel":"TURRET_PITCH"}""",
        )
        val data = DefaultVehicleData().apply { attachments["pitch"] = info }
        VehicleAttachmentDataValidator.validate("fixture", data)
        for (parent in listOf("Barrel", "WeaponStation", "Vehicle", "other_pitch")) {
            info.parent = parent
            assertThrows(IllegalArgumentException::class.java) {
                VehicleAttachmentDataValidator.validate("fixture", data)
            }
        }
        info.parent = "Turret"
        info.direction = Vec3(0.1, 0.0, 1.0)
        assertThrows(IllegalArgumentException::class.java) {
            VehicleAttachmentDataValidator.validate("fixture", data)
        }
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.decodeFromString<VehicleAttachmentInfo>("""{"RotationChannel":"PLAYER_LOOK"}""")
        }
        assertNull(Json.decodeFromString<VehicleAttachmentInfo>("{}").rotationChannel)
    }

    @Test fun `accepted near and serialized far parts preserve native pitch and station signs`() {
        val graph = VehicleAttachmentGraph(nodes, VehicleAttachmentGraph.NATIVE_BASE_FRAMES)
        for (pitch in listOf(-40F, 0F, 35F, 60F)) {
            val near = VehicleRenderPartSnapshot(25F, 12F, -18F, 35F, pitch, 35F, -pitch,
                -45F, -45F, -pitch, -pitch * (Math.PI / 180).toFloat(), null,
                0F, 0, 0F, true, true, false)
            val pose = VehiclePoseSnapshot.IDENTITY.withBasePose(12F, -18F)
                .withAuthority(5, 100, Vec3(10.0, 20.0, 30.0), 25F)
            val snapshot = FarVehicleSnapshot(1, "00000000-0000-0000-0000-000000000001",
                "fixture:vehicle", 10.0, 20.0, 30.0, 25F, 12F, -18F, 100, pose.encode(), near,
                RunningGearRenderState(0F, 0F, 0F, 0F, 0F, 1), 1F, 0.0, 0.0, 0.0, 0.0,
                0F, 0F, List(7) { 0F }, listOf(0, 0), 100F, false, false, false, true, "", emptyMap())
            val far = Json.decodeFromString<FarVehicleSnapshot>(Json.encodeToString(snapshot))
            assertTrue(far.valid())
            assertEquals(near, far.parts)
            val turret = pose.applyBaseAttitude(Matrix4d().translation(10.0, 20.0, 30.0)
                .rotateY(Math.toRadians(-25.0))).rotateY(Math.toRadians(35.0))
            val base = VehicleAttachmentSnapshot.fromMatrices(5, 100, mapOf("Turret" to turret))
            val nearFrames = graph.resolve(base, local, near.turretPitchDegrees)
            val farFrames = graph.resolve(base, local, far.parts.turretPitchDegrees)
            close(nearFrames.point("muzzle", Vec3.ZERO)!!, farFrames.point("muzzle", Vec3.ZERO)!!)
            close(nearFrames.direction("muzzle", forward)!!, farFrames.direction("muzzle", forward)!!)
            assertEquals(pitch, -far.parts.stationPitchDegrees)
        }
    }

    private fun close(expected: Vec3, actual: Vec3) {
        assertTrue(expected.distanceTo(actual) < 1E-9, "$expected != $actual")
    }

    companion object {
        @JvmStatic @BeforeAll fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
