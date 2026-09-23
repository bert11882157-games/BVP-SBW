package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.client.sound.AttributedVehicleSound
import com.atsuishio.superbwarfare.api.performance.ClientRenderPerformanceDiagnostics
import com.atsuishio.superbwarfare.network.NetworkTelemetry
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundManager
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.IdentityHashMap
import java.util.UUID

/** Sound-engine observation belongs to the actual instance, even after the player changes seats. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object EliteAudioPlayback {
    private data class Playback(val id: UUID, val startTick: Long, val startedNs: Long,
                                var active: Boolean = false, val preexisting: Boolean = false)
    private val playing = IdentityHashMap<SoundInstance, Playback>()
    private var observedSession: UUID? = null
    private var seededSession: UUID? = null
    private var lastPerformanceTick = Long.MIN_VALUE

    /** Includes engine loops already playing when capture is enabled; this does not replay them. */
    @JvmStatic fun observeExisting(instances: Collection<SoundInstance>) {
        val session = EliteDiagnostics.clientSessionId() ?: return
        prepareSession(session)
        if (seededSession == session) return
        seededSession = session
        val tick = Minecraft.getInstance().level?.gameTime ?: 0L
        instances.asSequence().filterNot(playing::containsKey).take((2048 - playing.size).coerceAtLeast(0)).forEach { instance ->
            val playback = Playback(UUID.randomUUID(), tick, System.nanoTime(), true, true)
            playing[instance] = playback
            record(instance, playback, "already_active_at_capture_start", tick)
        }
    }

    @JvmStatic fun resolved(instance: SoundInstance) {
        val session = EliteDiagnostics.clientSessionId() ?: return
        prepareSession(session)
        val tick = Minecraft.getInstance().level?.gameTime ?: 0L
        if (playing.size >= 2048) {
            val oldest = playing.entries.first()
            record(oldest.key, oldest.value, "trace_capacity_evicted", tick)
            playing.remove(oldest.key)
        }
        val playback = Playback(UUID.randomUUID(), tick, System.nanoTime())
        playing[instance] = playback
        record(instance, playback, "resolved_play_request", tick)
    }

    private fun prepareSession(session: UUID) {
        if (observedSession == session) return
        playing.clear()
        observedSession = session
        seededSession = null
        lastPerformanceTick = Long.MIN_VALUE
    }

    @JvmStatic fun stopped(instance: SoundInstance, reason: String) {
        val playback = playing.remove(instance) ?: return
        if (!EliteDiagnostics.isClientEnabled()) return
        record(instance, playback, reason, Minecraft.getInstance().level?.gameTime ?: 0L)
    }

    @SubscribeEvent fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        if (!EliteDiagnostics.isClientEnabled()) {
            playing.clear(); observedSession = null; seededSession = null; lastPerformanceTick = Long.MIN_VALUE
            ClientRenderPerformanceDiagnostics.setEnabled(false)
            return
        }
        NetworkTelemetry.tickClient()
        val client = Minecraft.getInstance()
        val now = client.level?.gameTime ?: 0L
        if (now % 20L == 0L && lastPerformanceTick != now) {
            lastPerformanceTick = now
            val s = ClientRenderPerformanceDiagnostics.snapshot()
            val runtime = Runtime.getRuntime()
            EliteDiagnostics.recordClient(now, "performance", "render_cumulative",
                "heap_used_bytes", runtime.totalMemory() - runtime.freeMemory(),
                "heap_committed_bytes", runtime.totalMemory(),
                "frames", s.frameIntervals(), "frame_ns", s.frameIntervalNanos(),
                "max_frame_ns", s.maxFrameIntervalNanos(), "vehicle_renders", s.vehicleRenders(),
                "vehicle_render_ns", s.vehicleRenderNanos(), "model_loads", s.vehicleModelLoads(),
                "model_failures", s.vehicleModelLoadFailures(), "model_load_ns", s.vehicleModelLoadNanos(),
                "vbo_hits", s.polyMeshVboHits(), "vbo_misses", s.polyMeshVboMisses(),
                "uploads", s.polyMeshUploads(), "upload_bytes", s.polyMeshUploadBytes(),
                "draw_calls", s.polyMeshDrawCalls(), "track_rebuilds", s.linksTransformRebuilds(),
                "track_links", s.linksEvaluated(), "track_ns", s.linksTransformNanos(),
                "tracer_candidates", s.tracerCandidatesVisited(), "tracer_discovery_ns", s.tracerDiscoveryNanos(),
                "tracer_render_ns", s.tracerRenderNanos(), "tracer_beams", s.tracerBeamsDrawn(),
                "ccip_samples", s.ccipSamples(), "ccip_steps", s.ccipCollisionSteps(), "ccip_ns", s.ccipSampleNanos())
        }
        val iterator = playing.entries.iterator()
        while (iterator.hasNext()) {
            val (instance, playback) = iterator.next()
            val active = client.soundManager.isActive(instance)
            if (active && !playback.active) {
                playback.active = true
                record(instance, playback, "engine_active", now)
            } else if (!active && (playback.active || now - playback.startTick >= 10)) {
                record(instance, playback, if (playback.active) "engine_finished" else "not_started_or_short_clip", now)
                iterator.remove()
            } else if (active && instance.isLooping && now % 20L == 0L) {
                record(instance, playback, "loop_sample", now)
            }
        }
    }

    @SubscribeEvent fun logout(event: ClientPlayerNetworkEvent.LoggingOut) {
        playing.clear()
        EliteDiagnostics.setClientSession(UUID(0, 0), false)
    }

    private fun record(instance: SoundInstance, playback: Playback, event: String, tick: Long) {
        val source = instance as? AttributedVehicleSound
        val resolved = instance.sound?.takeUnless { it == SoundManager.EMPTY_SOUND }?.path
        EliteDiagnostics.recordClient(tick, "audio", event,
            "playback", playback.id, "source_entity", source?.eliteSourceEntity(),
            "attribution", if (source == null) "position_only" else "instance_origin",
            "weapon", source?.eliteWeapon(), "channel", source?.eliteChannel(),
            "reload_cycle", source?.eliteCycle(), "reload_revision", source?.eliteReloadRevision(),
            "event_id", instance.location, "ogg", resolved, "sound_source", instance.source.name,
            "volume", instance.volume, "pitch", instance.pitch, "looping", instance.isLooping,
            "x", instance.x, "y", instance.y, "z", instance.z,
            "elapsed_ticks", tick - playback.startTick,
            "elapsed_scope", if (playback.preexisting) "capture_observation" else "play_request",
            "elapsed_ms", (System.nanoTime() - playback.startedNs) / 1_000_000.0)
    }
}
