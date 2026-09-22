package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderPartSnapshot
import com.atsuishio.superbwarfare.network.PacketLimitProfiles
import com.atsuishio.superbwarfare.network.message.receive.FarVehicleFrameMessage
import com.atsuishio.superbwarfare.serialization.ByteBufDecoder
import com.atsuishio.superbwarfare.serialization.ByteBufEncoder
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.joml.Matrix4f
import com.atsuishio.superbwarfare.client.renderer.FarVehicleRenderer

class FarVehicleStoreTest {
    private val session = UUID(0, 1).toString()
    private fun snapshot(id: Int = 1, x: Double = 0.0, yaw: Float = 0F) = FarVehicleSnapshot(
        id, UUID(0, id.toLong()).toString(), "berts_vehicle_pack:tank", x, 64.0, 0.0, yaw, 0F, 0F, 20,
        VehiclePoseSnapshot.IDENTITY.withChassisSample(Vec3(x, 64.0, 0.0), yaw).encode(),
        VehicleRenderPartSnapshot(yaw, 0F, 0F, yaw, 2F, 0F, 2F, 10F, 10F, 3F, 0.05F,
            4F, 0.1F, 2, 1F, true, true, false),
        RunningGearRenderState(1F, 2F, 3F, 4F, 5F, 20), 1F, 1.0, 0.1, 0.0, 0.2,
        30F, 40F, List(7) { it.toFloat() }, listOf(0, 1), 100F, false, false, false, true,
        "", mapOf("bvp.rotor_active" to "true", "bvp.spent_era" to "left_1,right_2"))

    private fun accept(store: FarVehicleStore, frame: Long, values: List<FarVehicleSnapshot>,
                       index: Int = 0, count: Int = 1, tick: Long = frame) =
        store.accept(session, frame, frame * 3, 3, 2048, index, count, values, tick)

    @Test fun `96 ground vehicles commit atomically in reordered parts and remove only at completion`() {
        val store = FarVehicleStore()
        val parts = (1..96).map { snapshot(it) }.chunked(16)
        for (index in listOf(5, 2, 4, 0, 1)) {
            assertFalse(accept(store, 1, parts[index], index, 6))
            assertTrue(store.values().isEmpty())
        }
        assertTrue(accept(store, 1, parts[3], 3, 6))
        assertEquals(96, store.values().size)
        assertFalse(accept(store, 2, listOf(snapshot(1)), 0, 2))
        assertEquals(96, store.values().size)
        assertTrue(accept(store, 2, emptyList(), 1, 2))
        assertEquals(listOf(1), store.values().map { it.current.id })
        assertTrue(accept(store, 3, emptyList()))
        assertTrue(store.values().isEmpty())
    }

    @Test fun `duplicates old frames and inconsistent frame metadata cannot replace live state`() {
        val store = FarVehicleStore()
        assertTrue(accept(store, 1, listOf(snapshot())))
        assertFalse(accept(store, 1, emptyList()))
        assertFalse(accept(store, 2, listOf(snapshot()), 0, 2))
        assertFalse(accept(store, 2, emptyList(), 0, 2))
        assertFalse(store.accept(session, 2, 99, 3, 2048, 1, 2, emptyList(), 2))
        assertFalse(accept(store, 2, listOf(snapshot()), 1, 2))
        assertEquals(1, store.values().size)
        assertTrue(accept(store, 3, listOf(snapshot(2))))
        assertEquals(2, store.values().single().current.id)
    }

    @Test fun `network silence retains visual state until authoritative removal or lifecycle clear`() {
        val store = FarVehicleStore()
        assertTrue(accept(store, 1, listOf(snapshot()), tick = 10))
        assertFalse(store.accept(UUID(0, 2).toString(), 2, 2, 3, 2048, 0, 1, emptyList(), 11))
        store.expire(70)
        assertEquals(1, store.values().size)
        store.expire(71)
        assertEquals(1, store.values().size)
        store.expire(12000)
        assertEquals(1, store.values().size, "A server stall must not unload a stationary vehicle")
        assertFalse(accept(store, 1, listOf(snapshot()), tick = 72))
        store.clear()
        assertTrue(store.accept(UUID(0, 2).toString(), 0, 0, 3, 2048, 0, 1, listOf(snapshot()), 0))
    }

    @Test fun `terrain churn and temporary native untracking cannot become vehicle removal frames`() {
        val publication = FarVehiclePublication<FarVehicleSnapshot> { UUID.fromString(it.uuid) }
        val terrain = FarTerrainHandshake("s", "d")
        val store = FarVehicleStore()
        val vehicle = snapshot()
        val selected = setOf(UUID.fromString(vehicle.uuid))
        terrain.update(576, setOf(1, 2), emptySet())
        terrain.delivered(terrain.revision, 1)
        terrain.delivered(terrain.revision, 2)
        terrain.acknowledge("s", "d", terrain.revision)
        assertTrue(accept(store, 1, publication.reconcile(selected, listOf(vehicle))))
        for (frame in 2L..120L) {
            terrain.update(576, setOf(1, 2), setOf(2))
            assertFalse(terrain.readyFor(setOf(1, 2)))
            val updates = if (frame % 3L == 0L) emptyList() else listOf(vehicle)
            assertTrue(accept(store, frame, publication.reconcile(selected, updates)))
            assertTrue(store.contains(vehicle.id), "Vehicle disappeared during terrain refresh $frame")
        }
        assertTrue(accept(store, 121, publication.reconcile(emptySet(), emptyList())))
        assertFalse(store.contains(vehicle.id), "Leaving the subscription must remove the retained vehicle")
        assertTrue(publication.reconcile(selected, emptyList()).isEmpty(), "Re-entry requires a fresh capture")
    }

    @Test fun `interpolation wraps yaw and moves chassis parts and running gear together`() {
        val store = FarVehicleStore()
        val first = snapshot(yaw = 179F)
        val second = snapshot(x = 6.0, yaw = -179F).copy(
            parts = first.parts.copy(turretWorldYawDegrees = -179F, barrelPitchDegrees = 6F),
            runningGear = first.runningGear.copy(leftWheelRotation = 7F))
        assertTrue(accept(store, 1, listOf(first), tick = 0))
        assertTrue(accept(store, 2, listOf(second), tick = 3))
        val entry = store.values().single()
        val mid = FarVehicleStore.interpolate(entry, FarVehicleStore.alpha(entry, 7.5))
        assertEquals(3.0, mid.x, 1e-9)
        assertEquals(180F, mid.yaw, 0.001F)
        assertEquals(180F, mid.parts.turretWorldYawDegrees, 0.001F)
        assertEquals(4F, mid.parts.barrelPitchDegrees, 0.001F)
        assertEquals(4F, mid.runningGear.leftWheelRotation, 0.001F)
        assertEquals(1F, FarVehicleStore.alpha(entry, 100.0))
    }

    @Test fun `teleports and reused identities snap instead of blending across unrelated vehicles`() {
        for (changed in listOf(snapshot(x = 65.0), snapshot(x = 2.0).copy(uuid = UUID(0, 99).toString()),
                snapshot(x = 2.0).copy(overrideData = "variant"),
                snapshot(x = 2.0).copy(type = "berts_vehicle_pack:helicopter"))) {
            val store = FarVehicleStore()
            assertTrue(accept(store, 1, listOf(snapshot())))
            assertTrue(accept(store, 2, listOf(changed)))
            assertEquals(changed.x, FarVehicleStore.interpolate(store.values().single(), 0F).x)
        }
    }

    @Test fun `allocation and numeric limits reject bad data before cache admission`() {
        val valid = snapshot()
        assertTrue(valid.valid())
        for (invalid in listOf(valid.copy(x = Double.NaN), valid.copy(roll = Float.POSITIVE_INFINITY),
                valid.copy(uuid = "x".repeat(36)), valid.copy(flaps = emptyList()),
                valid.copy(pose = "bad"), valid.copy(overrideData = "x".repeat(8193)),
                valid.copy(visualData = mapOf("bad key" to "value")),
                valid.copy(runningGear = valid.runningGear.copy(trackAnimationLength = 0)),
                valid.copy(parts = valid.parts.copy(cannonRecoilTime = -1)))) {
            assertFalse(invalid.valid())
            assertFalse(accept(FarVehicleStore(), 1, listOf(invalid)))
        }
        assertFalse(accept(FarVehicleStore(), 1, (1..17).map { snapshot(it) }))
        assertFalse(accept(FarVehicleStore(), 1, listOf(valid), 0, 17))
        assertFalse(accept(FarVehicleStore(), 1, listOf(valid, valid)))
        assertFalse(accept(FarVehicleStore(), 1, listOf(valid, valid.copy(id = 2))))
        assertTrue(valid.copy(health = -10F, wreck = true, turretEjected = true).valid())
    }

    @Test fun `full capacity and defensive copies remain bounded`() {
        val store = FarVehicleStore()
        (1..256).map { snapshot(it) }.chunked(16).forEachIndexed { i, part ->
            assertEquals(i == 15, accept(store, 1, part, i, 16))
        }
        assertEquals(256, store.values().size)
        val visual = mutableMapOf("bvp.rotor_active" to "true")
        assertTrue(accept(store, 2, listOf(snapshot().copy(visualData = visual))))
        visual.clear()
        assertEquals("true", store.values().single().current.visualData["bvp.rotor_active"])
    }

    @Test fun `maximum cosmetic payload round trips below wire ceiling without NBT`() {
        val values = (1..16).map { snapshot(it).copy(overrideData = "", visualData =
            mapOf("bvp.spent_era" to "x".repeat(8192 - "bvp.spent_era".length))) }
        assertTrue(values.all { it.valid() })
        val message = FarVehicleFrameMessage(session, "minecraft:overworld", 1, 1, 3, 2048, 0, 1, values)
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            ByteBufEncoder(buffer, PacketLimitProfiles.FAR_RENDER)
                .encodeSerializableValue(FarVehicleFrameMessage.serializer(), message)
            assertTrue(buffer.readableBytes() < PacketLimitProfiles.FAR_RENDER.maxWireBytes)
            val result = ByteBufDecoder(buffer, PacketLimitProfiles.FAR_RENDER)
                .decodeSerializableValue(FarVehicleFrameMessage.serializer())
            assertEquals(message, result)
            assertEquals(0, buffer.readableBytes())
            assertEquals(0, PacketLimitProfiles.FAR_RENDER.maxNbtBytes)
        } finally { buffer.release() }
    }

    @Test fun `decoder rejects excessive allocation before reading collection elements`() {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeVarInt(257)
            assertThrows(RuntimeException::class.java) {
                ByteBufDecoder(buffer, PacketLimitProfiles.FAR_RENDER)
                    .decodeSerializableValue(ListSerializer(Int.serializer()))
            }
        } finally { buffer.release() }
    }

    @Test fun `extended projection preserves near plane and original matrix`() {
        val original = Matrix4f().perspective(1F, 1.5F, 0.05F, 512F)
        val retained = Matrix4f(original)
        val extended = FarVehicleRenderer.extendedProjection(original, 2560F)!!
        assertEquals(retained, original)
        assertEquals(original.m00(), extended.m00())
        assertEquals(original.m11(), extended.m11())
        assertEquals(0.05F, extended.m32() / (extended.m22() - 1F), 0.00001F)
        assertTrue(extended.m32() / (extended.m22() + 1F) > 2500F)
        assertNull(FarVehicleRenderer.extendedProjection(original, Float.NaN))
    }

    @Test fun `fixed wing accepted tuple is paired to the same chassis entry clock`() {
        val store = FarVehicleStore()
        val key = FarFixedWingVisualState.VISUAL_KEY
        val first = FarFixedWingVisualState.create(1, 10, -1F, 0.5F, 0F, 0.2)!!
        val next = FarFixedWingVisualState.create(2, 13, 1F, -0.5F, 1F, 0.8)!!
        assertTrue(accept(store, 1, listOf(snapshot().copy(visualData = mapOf(key to first.encode()))), tick = 0))
        assertTrue(accept(store, 2, listOf(snapshot(x = 6.0).copy(visualData = mapOf(key to next.encode()))), tick = 3))
        val entry = store.values().single()
        val alpha = FarVehicleStore.alpha(entry, 7.5)
        val controls = FarFixedWingVisualState.interpolate(entry.previousFixedWingControls,
            entry.currentFixedWingControls, alpha)!!
        assertEquals(3.0, FarVehicleStore.interpolate(entry, alpha).x, 1e-9)
        assertEquals(0F, controls.elevator)
        assertEquals(0F, controls.aileron)
        assertEquals(0.5F, controls.rudder)
        assertEquals(0.5, controls.throttle, 1e-9)
        assertFalse(accept(store, 1, listOf(snapshot()), tick = 5))
        assertEquals(next, store.values().single().currentFixedWingControls)
    }

    @Test fun `early and late receipts retain continuous source phase at 30 60 and 120 FPS`() {
        for (fps in listOf(30, 60, 120)) {
            val store = FarVehicleStore()
            val receipts = (0..30).map { n -> n * 3.0 + if (n % 4 == 2) 1.9 else 0.4 }
            var next = 0
            var previousX: Double? = null
            val step = 20.0 / fps
            for (i in 0..(80 / step).toInt()) {
                val tick = i * step
                while (next < receipts.size && receipts[next] <= tick) {
                    assertTrue(accept(store, next + 1L, listOf(snapshot(x = next * 4.2)),
                        tick = receipts[next].toLong()))
                    next++
                }
                val entry = store.get(1) ?: continue
                val segment = FarVehicleStore.segment(entry, tick)
                val value = FarVehicleStore.interpolate(segment, FarVehicleStore.alpha(segment, tick), entry.current)
                if (tick >= 10) {
                    assertEquals((tick - 6) * 1.4, value.x, 0.00002)
                    previousX?.let { assertEquals(step * 1.4, value.x - it, 0.00002) }
                    previousX = value.x
                }
                assertTrue(entry.earlierSegments.size < FarVehicleStore.MAX_SEGMENTS)
                assertTrue(entry.earlierSegments.all { it.earlierSegments.isEmpty() })
            }
        }
    }

    @Test fun `receipt replacement does not jump at the same render time and all axes stay paired`() {
        val store = FarVehicleStore()
        fun acceptMotion(n: Int, now: Long) {
            val value = snapshot(x = n * 4.2, yaw = n * 10F).copy(
                y = n * 2.0, z = -n * 3.0,
                parts = snapshot().parts.copy(barrelPitchDegrees = n * 6F),
                runningGear = snapshot().runningGear.copy(leftTrackPhase = n * 3F))
            assertTrue(accept(store, n + 1L, listOf(value), tick = now))
        }
        for (n in 0..3) acceptMotion(n, n * 3L)
        fun sample(tick: Double): FarVehicleSnapshot {
            val entry = store.get(1)!!
            val segment = FarVehicleStore.segment(entry, tick)
            return FarVehicleStore.interpolate(segment, FarVehicleStore.alpha(segment, tick), entry.current)
        }
        val before = sample(12.78)
        acceptMotion(4, 12)
        val after = sample(12.78)
        assertEquals(before.x, after.x, 0.000001)
        assertEquals(before.y, after.y, 0.000001)
        assertEquals(before.z, after.z, 0.000001)
        assertEquals(before.yaw, after.yaw, 0.000001F)
        assertEquals(before.parts.barrelPitchDegrees, after.parts.barrelPitchDegrees, 0.000001F)
        assertEquals(before.runningGear.leftTrackPhase, after.runningGear.leftTrackPhase, 0.000001F)
    }

    @Test fun `buffer delays geometry but never delays discrete retirement and controls omission`() {
        val store = FarVehicleStore()
        for (n in 0..3) assertTrue(accept(store, n + 1L, listOf(snapshot(x = n * 4.2)), tick = n * 3L))
        val latest = snapshot(x = 16.8).copy(occupied = false, wreck = true, health = 0F,
            selectedWeapons = listOf(7), visualData = emptyMap())
        assertTrue(accept(store, 5, listOf(latest), tick = 12))
        val entry = store.get(1)!!
        val segment = FarVehicleStore.segment(entry, 12.2)
        assertNotSame(entry, segment)
        val value = FarVehicleStore.interpolate(segment, FarVehicleStore.alpha(segment, 12.2), entry.current)
        assertTrue(value.x < latest.x)
        assertTrue(value.wreck)
        assertFalse(value.occupied)
        assertEquals(0F, value.health)
        assertEquals(listOf(7), value.selectedWeapons)
        assertTrue(value.visualData.isEmpty())
        assertNull(entry.currentFixedWingControls)
        assertTrue(accept(store, 6, emptyList(), tick = 15))
        assertTrue(store.values().isEmpty())
    }

    @Test fun `depletion holds then resumes boundedly and lifecycle cuts all history`() {
        val store = FarVehicleStore()
        assertTrue(accept(store, 1, listOf(snapshot()), tick = 0))
        assertTrue(accept(store, 2, listOf(snapshot(x = 4.2)), tick = 3))
        val old = store.get(1)!!
        assertEquals(4.2, FarVehicleStore.interpolate(old, FarVehicleStore.alpha(old, 20.0)).x)
        assertTrue(accept(store, 3, listOf(snapshot(x = 8.4)), tick = 20))
        val resumed = store.get(1)!!
        assertEquals(4.2, FarVehicleStore.interpolate(resumed, FarVehicleStore.alpha(resumed, 20.0)).x)
        assertEquals(6.3, FarVehicleStore.interpolate(resumed, FarVehicleStore.alpha(resumed, 21.5)).x, 1e-6)
        assertEquals(8.4, FarVehicleStore.interpolate(resumed, FarVehicleStore.alpha(resumed, 30.0)).x)
        assertTrue(accept(store, 4, listOf(snapshot(x = 90.0)), tick = 23))
        assertTrue(store.get(1)!!.earlierSegments.isEmpty())
        assertEquals(90.0, FarVehicleStore.interpolate(store.get(1)!!, 0F).x)
        store.clear()
        assertTrue(store.values().isEmpty())
        assertTrue(accept(store, 1, listOf(snapshot(x = 1.0)), tick = 0))
        assertTrue(store.get(1)!!.earlierSegments.isEmpty())
    }

    @Test fun `buffered fixed wing controls reject a retired source and pause does not advance samples`() {
        val store = FarVehicleStore()
        val key = FarFixedWingVisualState.VISUAL_KEY
        for (n in 0..3) {
            val controls = FarFixedWingVisualState.create(100 + n, 1000L + n * 3, n / 3F, 0F, 0F, 1.0)!!
            assertTrue(accept(store, n + 1L,
                listOf(snapshot(x = n * 4.2).copy(visualData = mapOf(key to controls.encode()))), tick = n * 3L))
        }
        val reset = FarFixedWingVisualState.create(1, 10L, -1F, 0F, 0F, 0.0)!!
        assertTrue(accept(store, 5, listOf(snapshot(x = 16.8).copy(visualData = mapOf(key to reset.encode()))), tick = 12))
        val entry = store.get(1)!!
        val segment = FarVehicleStore.segment(entry, 12.3)
        val alpha = FarVehicleStore.alpha(segment, 12.3)
        assertNotSame(entry, segment)
        assertEquals(reset, FarVehicleStore.fixedWingControls(entry, segment, alpha))
        val paused = FarVehicleStore.interpolate(segment, alpha, entry.current)
        repeat(120) {
            val same = FarVehicleStore.segment(entry, 12.3)
            assertSame(segment, same)
            assertEquals(paused, FarVehicleStore.interpolate(same, FarVehicleStore.alpha(same, 12.3), entry.current))
        }
        assertTrue(store.accept(session, 6, 18, 6, 2048, 0, 1, listOf(snapshot(x = 18.0)), 15))
        assertTrue(store.get(1)!!.earlierSegments.isEmpty())
        assertTrue(store.accept(session, 7, 1, 6, 2048, 0, 1, listOf(snapshot(x = 1.0)), 18))
        assertTrue(store.get(1)!!.earlierSegments.isEmpty())
    }

    @Test fun `existing entry constructor and two argument interpolation remain callable`() {
        assertNotNull(FarVehicleStore.Entry::class.java.getConstructor(
            FarVehicleSnapshot::class.java, FarVehicleSnapshot::class.java,
            VehiclePoseSnapshot::class.java, VehiclePoseSnapshot::class.java,
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        assertNotNull(FarVehicleStore.Companion::class.java.getMethod("interpolate",
            FarVehicleStore.Entry::class.java, Float::class.javaPrimitiveType))
    }

    @Test fun `four tick startup bias converges continuously at bounded source speed`() {
        for (fps in listOf(30, 60, 120)) {
            val store = FarVehicleStore()
            val receipts = (0..40).map { n -> if (n == 0) 4.0 else maxOf(4.0, n * 3.0) }
            var next = 0
            var previous: Double? = null
            val step = 20.0 / fps
            for (i in 0..(100 / step).toInt()) {
                val tick = i * step
                while (next < receipts.size && receipts[next] <= tick) {
                    assertTrue(store.accept(session, next.toLong(), next * 3L, 3, 2048,
                        0, 1, listOf(snapshot(x = next * 4.2)), receipts[next].toLong()))
                    next++
                    val entry = store.get(1)!!
                    val segments = entry.earlierSegments + entry
                    assertTrue(segments.size <= FarVehicleStore.MAX_SEGMENTS)
                    assertTrue(segments.all { it.endTick >= it.startTick })
                    for ((a, b) in segments.zipWithNext()) assertEquals(a.endTick, b.startTick)
                }
                val entry = store.get(1) ?: continue
                val segment = FarVehicleStore.segment(entry, tick)
                val x = FarVehicleStore.interpolate(segment, FarVehicleStore.alpha(segment, tick)).x
                if (tick >= 11) {
                    previous?.let {
                        val speed = (x - it) / step
                        assertTrue(speed > 1.39998 && speed <= 1.4 / 0.875 + 0.00002, "$speed")
                    }
                    previous = x
                }
                if (tick >= 50) {
                    assertEquals(0.0, entry.clockOffset)
                    assertEquals((tick - 6) * 1.4, x, 0.00002)
                }
            }
        }
    }

    @Test fun `burst pressure preserves every current partial tick and coalesces only future work`() {
        val store = FarVehicleStore()
        fun put(source: Int, now: Long) {
            val value = snapshot(x = source * source * 0.01).copy(health = source.toFloat())
            assertTrue(store.accept(session, source.toLong(), source.toLong(), 2, 2048,
                0, 1, listOf(value), now))
        }
        fun at(tick: Double): Double {
            val entry = store.get(1)!!
            val segment = FarVehicleStore.segment(entry, tick)
            return FarVehicleStore.interpolate(segment, FarVehicleStore.alpha(segment, tick), entry.current).x
        }
        for (source in 0..6) put(source, source.toLong())
        val before = (0..100).map { at(7.0 + it / 100.0) }
        for (source in 7..50) {
            put(source, 7)
            for (partial in 0..100) assertEquals(before[partial], at(7.0 + partial / 100.0), 1e-9)
            val entry = store.get(1)!!
            assertEquals(source.toFloat(), entry.current.health)
            assertTrue(entry.earlierSegments.size < FarVehicleStore.MAX_SEGMENTS)
            assertTrue(entry.earlierSegments.all { it.earlierSegments.isEmpty() })
            val segments = entry.earlierSegments + entry
            if (source >= 10) assertEquals(FarVehicleStore.MAX_SEGMENTS, segments.size)
            for ((a, b) in segments.zipWithNext()) {
                assertEquals(a.endTick, b.startTick)
                assertEquals(a.current.x, b.previous.x)
            }
            assertEquals(entry.current.x, at(1000.0), 1e-9)
        }
    }

    @Test fun `late jitter cannot restore a corrected startup offset or evict a visible bracket`() {
        val store = FarVehicleStore()
        for (n in 0..35) {
            val receipt = if (n == 0) 4L else maxOf(4L, n * 3L + if (n % 4 == 2) 1 else 0)
            val old = store.get(1)
            val before = old?.let {
                val segment = FarVehicleStore.segment(it, receipt + 0.9)
                FarVehicleStore.interpolate(segment, FarVehicleStore.alpha(segment, receipt + 0.9)).x
            }
            assertTrue(store.accept(session, n.toLong(), n * 3L, 3, 2048,
                0, 1, listOf(snapshot(x = n * 4.2)), receipt))
            val entry = store.get(1)!!
            if (before != null && old!!.endTick >= receipt + 1) {
                val segment = FarVehicleStore.segment(entry, receipt + 0.9)
                assertEquals(before, FarVehicleStore.interpolate(segment,
                    FarVehicleStore.alpha(segment, receipt + 0.9)).x, 1e-9)
            }
            if (n >= 3) assertEquals(0.0, entry.clockOffset)
            if (n >= 16) assertEquals(n * 3.0 + 6.0, entry.endTick)
        }
    }

    @Test fun `fixed wing omission wreck identity change and lifecycle clear retire old tuple`() {
        val store = FarVehicleStore()
        val key = FarFixedWingVisualState.VISUAL_KEY
        val controls = FarFixedWingVisualState.create(1, 10, 1F, 0F, 0F, 1.0)!!
        val initial = snapshot().copy(visualData = mapOf(key to controls.encode()))
        assertTrue(accept(store, 1, listOf(initial)))
        assertTrue(accept(store, 2, listOf(snapshot())))
        assertNull(store.values().single().currentFixedWingControls)
        assertTrue(accept(store, 3, listOf(initial.copy(wreck = true))))
        assertNull(store.values().single().currentFixedWingControls)
        val fresh = initial.copy(uuid = UUID(0, 99).toString())
        assertTrue(accept(store, 4, listOf(fresh)))
        val entry = store.values().single()
        assertEquals(entry.currentFixedWingControls, entry.previousFixedWingControls)
        assertEquals(controls, FarFixedWingVisualState.interpolate(entry.previousFixedWingControls,
            entry.currentFixedWingControls, 0F))
        for (value in listOf("bad", "1;1;1;2;0;0;1", "x".repeat(193))) {
            val invalid = initial.copy(visualData = mapOf(key to value))
            assertFalse(invalid.valid())
            assertFalse(accept(store, 5, listOf(invalid)))
            assertEquals(fresh.uuid, store.values().single().current.uuid)
        }
        store.clear()
        assertTrue(store.values().isEmpty())
        assertTrue(accept(store, 0, listOf(snapshot())))
        assertNull(store.values().single().currentFixedWingControls)
        assertTrue(accept(store, 1, listOf(initial)))
        store.expire(62)
        assertEquals(controls, store.values().single().currentFixedWingControls)
        assertTrue(accept(store, 2, emptyList(), tick = 63))
        assertTrue(store.values().isEmpty())
    }

    @Test fun `fixed wing scalar extension round trips through the existing far packet codec`() {
        val controls = FarFixedWingVisualState.create(Int.MIN_VALUE, 13, 0.3F, -0.8F, 1F, 0.42)!!
        val value = snapshot().copy(visualData = mapOf(
            FarFixedWingVisualState.VISUAL_KEY to controls.encode(), "bvp.rotor_active" to "true"))
        val message = FarVehicleFrameMessage(session, "minecraft:overworld", 1, 13, 3, 2048, 0, 1, listOf(value))
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            ByteBufEncoder(buffer, PacketLimitProfiles.FAR_RENDER)
                .encodeSerializableValue(FarVehicleFrameMessage.serializer(), message)
            assertTrue(buffer.readableBytes() < PacketLimitProfiles.FAR_RENDER.maxWireBytes)
            val decoded = ByteBufDecoder(buffer, PacketLimitProfiles.FAR_RENDER)
                .decodeSerializableValue(FarVehicleFrameMessage.serializer())
            assertEquals(message, decoded)
            assertEquals(controls, FarFixedWingVisualState.decode(
                decoded.vehicles.single().visualData[FarFixedWingVisualState.VISUAL_KEY]))
            assertEquals(0, buffer.readableBytes())
        } finally { buffer.release() }
    }
}
