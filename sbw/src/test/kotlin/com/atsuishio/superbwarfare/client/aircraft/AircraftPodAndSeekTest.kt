package com.atsuishio.superbwarfare.client.aircraft

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.atan2

class AircraftPodAndSeekTest {
    private val pod = AircraftPodView(Vec3.ZERO, 180F, -10F, 90F, "test", 24.0, 8192.0)
    private fun degrees(v: Vec3) = Math.toDegrees(atan2(-v.x, v.z))

    @Test fun `designated point survives aircraft roll yaw and translation inside gimbal`() {
        val aim = AircraftPodStabilizer()
        val target = Vec3(0.0, -2000.0, 4000.0)
        aim.designate(target)
        for (roll in listOf(-0.15, 0.0, 0.15)) for (yaw in listOf(-0.3, 0.0, 0.3)) {
            val origin = Vec3(100.0, 20.0, 80.0)
            val hull = Matrix4d().rotateY(yaw).rotateZ(roll)
            assertTrue(aim.direction(pod, hull, origin)!!.distanceTo(target.subtract(origin).normalize()) < 1e-10)
        }
    }

    @Test fun `pod sees directly down but stops at upward gimbal boundary`() {
        val aim = AircraftPodStabilizer()
        aim.designate(Vec3(0.0, -200.0, 0.0))
        assertEquals(-1.0, aim.direction(pod, Matrix4d(), Vec3.ZERO)!!.y, 1e-10)
        aim.designate(Vec3(0.0, 200.0, 0.0))
        val up = aim.direction(pod, Matrix4d(), Vec3.ZERO)!!
        assertEquals(kotlin.math.sin(Math.toRadians(10.0)), up.y, 1e-10)
    }

    @Test fun `zoomed mouse retains subpixel precision and is independent of frame rate`() {
        for (fps in listOf(30, 60, 144)) {
            val aim = AircraftPodStabilizer()
            aim.begin(pod, Matrix4d(), Vec3.ZERO)
            for (frame in 0..fps) aim.sample(frame * 12.0 / fps, 0.0, 0.5, 24.0, pod, Matrix4d(), Vec3.ZERO)
            assertEquals(0.25, degrees(aim.direction(pod, Matrix4d(), Vec3.ZERO)!!), 1e-9)
            aim.resetCursor()
            aim.sample(10000.0, 10000.0, 0.5, 24.0, pod, Matrix4d(), Vec3.ZERO)
            assertEquals(0.25, degrees(aim.direction(pod, Matrix4d(), Vec3.ZERO)!!), 1e-9)
        }
    }

    private val vehicle = UUID(0, 1)
    private val target = UUID(0, 2)
    private fun full() = JsonObject().apply {
        addProperty("Vehicle", vehicle.toString()); addProperty("EntityId", 42); addProperty("Revision", 1)
        add("Definition", JsonObject().apply { addProperty("Schema", 1); addProperty("Name", "Test") })
        add("Stores", JsonObject())
    }
    private fun seek(revision: Long, ready: Boolean) = JsonObject().apply {
        addProperty("Revision", revision); addProperty("WeaponId", "AircraftStore:5")
        addProperty("TargetUUID", target.toString())
        add("TargetPosition", JsonArray().apply { add(0); add(100); add(200) })
        addProperty("Progress", if(ready) 1.0 else 0.5); addProperty("Ready", ready)
        addProperty("Status", if(ready) "READY" else "ACQUIRING")
    }

    @Test fun `thin seeker updates are monotonic independently of equipment and designation`() {
        val cache = AircraftArmamentStateCache()
        assertNotNull(cache.receive(full()))
        val thin = JsonObject().apply {
            addProperty("Vehicle", vehicle.toString()); addProperty("EntityId", 42); add("Seek", seek(2, true))
        }
        assertTrue(cache.receive(thin)!!.snapshot.seek!!.ready)
        assertTrue(cache.receive(full())!!.snapshot.seek!!.ready)
        thin.add("Seek", seek(1, false))
        assertNull(cache.receive(thin))
        thin.add("Seek", JsonObject().apply {
            addProperty("Revision", 3); addProperty("WeaponId", "AircraftStore:5")
            addProperty("Status", "NO_TARGET"); addProperty("Progress", 0); addProperty("Ready", false)
        })
        assertNull(cache.receive(thin)!!.snapshot.seek!!.target)
        assertFalse(cache.get(vehicle)!!.snapshot.seek!!.ready)
    }

    @Test fun `invalid ready state cannot advertise permission to launch`() {
        assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentSnapshot.decodeSeek(seek(1, true).apply { addProperty("Progress", 0.5) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentSnapshot.decodeSeek(seek(1, true).apply { remove("TargetPosition") })
        }
    }

    @Test fun `public loadout refresh cannot close private targeting pod`() {
        val cache = AircraftArmamentStateCache()
        val state = full().apply {
            getAsJsonObject("Definition").add("Pod", JsonObject().apply {
                addProperty("Source", "test")
                add("Position", JsonArray().apply { add(0); add(0); add(0) })
            })
            addProperty("PodActive", true)
        }
        assertTrue(cache.receive(state)!!.snapshot.podActive)
        state.remove("PodActive")
        assertTrue(cache.receive(state)!!.snapshot.podActive)
        state.addProperty("PodActive", false)
        assertFalse(cache.receive(state)!!.snapshot.podActive)
    }
}
