package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.network.PacketLimitProfiles
import com.atsuishio.superbwarfare.network.message.receive.VehicleReloadSoundMessage
import com.atsuishio.superbwarfare.serialization.ByteBufDecoder
import com.atsuishio.superbwarfare.serialization.ByteBufEncoder
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class VehicleReloadAudioTest {
    @Test fun `client playback requires exact active reload and cannot survive completion`() {
        fun decide(revision: Int = 17, loading: Boolean = true, countdown: Int = 50,
                   budget: Int = 48, source: Boolean = true) =
            ReloadPlaybackWindow.evaluate(17, revision, loading, countdown, budget, source)
        assertEquals(ReloadPlaybackWindow.Decision.WAIT, decide(revision = 16, loading = false))
        assertEquals(ReloadPlaybackWindow.Decision.PLAY, decide())
        assertEquals(ReloadPlaybackWindow.Decision.STOP, decide(countdown = 1))
        assertEquals(ReloadPlaybackWindow.Decision.STOP, decide(countdown = 0))
        assertEquals(ReloadPlaybackWindow.Decision.STOP, decide(loading = false))
        assertEquals(ReloadPlaybackWindow.Decision.STOP, decide(revision = 18))
        assertEquals(ReloadPlaybackWindow.Decision.STOP, decide(budget = 0))
        assertEquals(ReloadPlaybackWindow.Decision.STOP, decide(source = false))
    }

    @Test fun `independent playback clock may retire entries during a tick snapshot`() {
        val stopped = mutableListOf<String>()
        val registry = AudioPlaybackRegistry<String>(stopped::add)
        val ids = List(3) { UUID.randomUUID() }
        ids.forEachIndexed { index, id -> registry.start(id) { index.toString() } }
        registry.forEachActive { registry.stop(ids[it.toInt()]) }
        assertEquals(listOf("0", "1", "2"), stopped)
        assertEquals(0, registry.activeCount())
        ids.forEach { assertFalse(registry.start(it) { "late buffer" }) }
    }

    @Test fun `authoritative cycle can claim once and finished revision never restarts`() {
        val cycle = VehicleReloadAudio.VehicleReloadSoundCycle(10, lastRemainingTicks = 120)
        assertTrue(cycle.claim())
        assertFalse(cycle.claim())
        cycle.retire()
        assertFalse(cycle.claim())
        assertNull(cycle.observe(120, true, 11))
        assertTrue(cycle.retired)
        val nextRevision = VehicleReloadAudio.VehicleReloadSoundCycle(12, lastRemainingTicks = 120)
        assertNotEquals(cycle.playbackId, nextRevision.playbackId)
        assertTrue(nextRevision.claim())
    }

    @Test fun `normal countdown remains owned but reload timer resets retire playback`() {
        val cycle = VehicleReloadAudio.VehicleReloadSoundCycle(10, lastRemainingTicks = 120)
        for (remaining in 120 downTo 2) assertNull(cycle.observe(remaining, true, 130L - remaining))
        assertFalse(cycle.retired)
        assertEquals("reload_timer_discontinuity", cycle.observe(120, true, 131))
        assertTrue(cycle.retired)
        assertFalse(cycle.claim())
    }

    @Test fun `timer jumps and cancellations cannot recover a partial clip in the same cycle`() {
        for ((remaining, reloading, reason) in listOf(
            Triple(100, true, "reload_timer_discontinuity"),
            Triple(0, false, "reload_cancelled_or_reset"))) {
            val cycle = VehicleReloadAudio.VehicleReloadSoundCycle(10, lastRemainingTicks = 120)
            assertEquals(reason, cycle.observe(remaining, reloading, 11))
            assertFalse(cycle.claim())
        }
    }

    @Test fun `wire round trip preserves the originating weapon and exact playback token`() {
        val source = VehicleReloadSoundMessage(
            UUID.randomUUID(), ResourceLocation("minecraft", "overworld"), 42, UUID.randomUUID(),
            "Cannon", 17, ResourceLocation("berts_vehicle_pack", "soviet_125mm_autoloader"),
            130, false, false,
        )
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            ByteBufEncoder(buffer, PacketLimitProfiles.TINY)
                .encodeSerializableValue(VehicleReloadSoundMessage.serializer(), source)
            assertTrue(buffer.readableBytes() < PacketLimitProfiles.TINY.maxWireBytes)
            val restored = ByteBufDecoder(buffer, PacketLimitProfiles.TINY)
                .decodeSerializableValue(VehicleReloadSoundMessage.serializer())
            assertEquals(source, restored)
        } finally { buffer.release() }
    }

    @Test fun `same sound event on two vehicles stops only its own instance`() {
        val stopped = mutableListOf<String>()
        val registry = AudioPlaybackRegistry<String>(stopped::add)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertTrue(registry.start(a) { "tank-a-reload" })
        assertTrue(registry.start(b) { "tank-b-reload" })
        registry.stop(a)
        assertEquals(listOf("tank-a-reload"), stopped)
        assertEquals(1, registry.activeCount())
        registry.stop(b)
        assertEquals(listOf("tank-a-reload", "tank-b-reload"), stopped)
    }

    @Test fun `duplicate start and late packet cannot replay a completed cycle`() {
        val registry = AudioPlaybackRegistry<String> { }
        val id = UUID.randomUUID()
        var starts = 0
        assertTrue(registry.start(id) { starts++; "clip" })
        assertFalse(registry.start(id) { starts++; "clip" })
        registry.retire(id)
        assertFalse(registry.start(id) { starts++; "clip" })
        assertEquals(1, starts)
    }

    @Test fun `stop before delayed start keeps the cycle cancelled`() {
        val registry = AudioPlaybackRegistry<String> { fail("nothing was playing") }
        val id = UUID.randomUUID()
        registry.stop(id)
        assertFalse(registry.start(id) { error("must not create playback") })
    }

    @Test fun `dismount can stop cab sound without stopping world reloads`() {
        val stopped = mutableListOf<String>()
        val registry = AudioPlaybackRegistry<String>(stopped::add)
        registry.start(UUID.randomUUID()) { "cab" }
        registry.start(UUID.randomUUID()) { "world" }
        registry.stopMatching { it == "cab" }
        assertEquals(listOf("cab"), stopped)
        assertEquals(1, registry.activeCount())
        registry.clear()
        assertEquals(listOf("cab", "world"), stopped)
        assertEquals(0, registry.activeCount())
    }

    @Test fun `measured clip finishes on the same tick as loading for either bolt offset`() {
        for (offset in 1..2) for (reload in listOf(100, 120, 130, 160)) {
            val clip = 87
            val countdown = VehicleReloadSoundTiming.countdownFor(reload, clip, 1, offset)
            var startedAt: Int? = null
            var finishedAt: Int? = null
            var timer = reload + offset
            var tick = 0
            while (timer > 1) {
                if (timer == countdown) startedAt = tick
                timer--
                if (timer == 1) finishedAt = tick
                tick++
            }
            assertEquals(clip, finishedAt!! - startedAt!!, "offset=$offset reload=$reload")
        }
    }

    @Test fun `long clip is bounded by actual remaining reload not a setup offset`() {
        for (offset in 1..2) {
            val countdown = VehicleReloadSoundTiming.countdownFor(120, 133, 1, offset)
            assertEquals(120 + offset, countdown)
            assertEquals(118 + offset, VehicleReloadSoundTiming.ticksUntilCompletion(countdown))
        }
        assertEquals(0, VehicleReloadSoundTiming.ticksUntilCompletion(Int.MIN_VALUE))
    }
}
