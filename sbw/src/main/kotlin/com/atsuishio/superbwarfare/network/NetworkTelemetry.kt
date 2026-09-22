package com.atsuishio.superbwarfare.network

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder

enum class PacketRejectReason {
    MALFORMED,
    RATE_LIMIT,
    QUEUE_LIMIT,
    WRONG_DIRECTION,
    MISSING_SENDER,
}

data class ChassisTraceSample(
    val entityId: Int,
    val serverTick: Long,
    val sequence: Int,
    val requestedMovementY: Double,
    val resolvedMovementY: Double,
    val collisionStepCandidateDeltaY: Double,
    val collisionStepAppliedDeltaY: Double,
    val anchorX: Double,
    val anchorY: Double,
    val anchorZ: Double,
    val chassisYawDegrees: Float,
    val groundBias: Double,
    val collisionStepOffset: Double,
    val collisionStepVelocity: Double,
    val resolvedChassisWorldY: Double,
)

/**
 * One bounded, opt-in aim sample correlation event.  It deliberately contains only numeric
 * entity/channel/seat/weapon identities and angles; UUIDs, names, coordinates, and payload text
 * never enter the trace.  [stage] and [reason] are fixed source labels, not packet-derived data.
 */
data class AimPresentationTraceSample(
    val stage: String,
    val entityId: Int,
    val clientTick: Int,
    val partialBits: Int,
    val channel: Int,
    val seatIndex: Int,
    val weaponIndex: Int,
    val sequence: Int,
    val upperSequence: Int,
    val serverTick: Long,
    val sourceServerTick: Double,
    val epoch: Int,
    val reason: String,
    val cacheHit: Boolean,
    val direct: Boolean,
    val continuity: Boolean,
    val tailHeld: Boolean,
    val alpha: Float,
    val axesValid: Boolean,
    val actualYaw: Float,
    val actualPitch: Float,
    val presentedYaw: Float,
    val presentedPitch: Float,
)

private class PacketMetric {
    val encodedPackets = LongAdder()
    val decodedPackets = LongAdder()
    val encodedBytes = LongAdder()
    val decodedBytes = LongAdder()
    val encodeNanos = LongAdder()
    val decodeNanos = LongAdder()
    val handlerNanos = LongAdder()
    val queueDelayNanos = LongAdder()
    val rejects = LongAdder()
}

private class EntityDataMetric {
    val dirtyWrites = LongAdder()
    val payloadBytes = LongAdder()
    val unchangedSuppressions = LongAdder()
}

private class SystemMetric {
    val invocations = LongAdder()
    val items = LongAdder()
    val recipients = LongAdder()
    val serializedTags = LongAdder()
    val losChecks = LongAdder()
    val workNanos = LongAdder()
    val highWater = AtomicLong()
}

private data class AimTraceDedupKey(
    val entityId: Int,
    val channel: Int,
    val stage: String,
    val clientTick: Int,
)

private class TelemetryState {
    val packets = ConcurrentHashMap<Int, PacketMetric>()
    val entityData = ConcurrentHashMap<String, EntityDataMetric>()
    val systems = ConcurrentHashMap<String, SystemMetric>()
    val rejectReasons = ConcurrentHashMap<PacketRejectReason, LongAdder>()
    val queueHighWater = AtomicInteger()
    val chassisTrace = ConcurrentLinkedQueue<ChassisTraceSample>()
    val chassisTraceCount = AtomicInteger()
    val chassisTraceDrops = LongAdder()
    val aimPresentationTrace = ConcurrentLinkedQueue<AimPresentationTraceSample>()
    val aimPresentationTraceCount = AtomicInteger()
    val aimPresentationTraceDrops = LongAdder()
    val aimPresentationTraceDedup = ConcurrentHashMap<AimTraceDedupKey, Boolean>()
}

/**
 * Low-cardinality counters and bounded traces owned by Elite diagnostics.
 * Packet admission remains active even when recording is disabled.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object NetworkTelemetry {
    private const val MAX_PENDING_HANDLERS = 4_096
    private const val MAX_CHASSIS_TRACE_SAMPLES = 4_096
    private const val MAX_AIM_PRESENTATION_TRACE_SAMPLES = 8_192
    private val state = AtomicReference(TelemetryState())
    private val pendingHandlers = AtomicInteger()
    private var lastFlushNanos = 0L

    fun isEnabled(): Boolean = EliteDiagnostics.isServerEnabled() || EliteDiagnostics.isClientEnabled()
    fun isAimPresentationTraceAuthorized(): Boolean = true
    fun isAimPresentationTraceEnabled(): Boolean = isEnabled()

    /** Called only by the central session owner. Never reset the live admission queue. */
    fun resetElite() { state.set(TelemetryState()); lastFlushNanos = 0L }

    fun tryEnqueue(registered: RegisteredPacketSchema): Boolean {
        val depth = pendingHandlers.incrementAndGet()
        if (depth > MAX_PENDING_HANDLERS) {
            pendingHandlers.decrementAndGet()
            recordReject(registered, PacketRejectReason.QUEUE_LIMIT)
            return false
        }
        if (isEnabled()) {
            state.get().queueHighWater.updateAndGet { previous -> maxOf(previous, depth) }
        }
        return true
    }

    fun recordHandled(registered: RegisteredPacketSchema, queuedAtNanos: Long, startedAtNanos: Long) {
        pendingHandlers.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
        if (!isEnabled()) return
        val now = System.nanoTime()
        metric(registered).queueDelayNanos.add((startedAtNanos - queuedAtNanos).coerceAtLeast(0L))
        metric(registered).handlerNanos.add((now - startedAtNanos).coerceAtLeast(0L))
    }

    fun recordEncode(registered: RegisteredPacketSchema, bytes: Int, nanos: Long) {
        if (!isEnabled()) return
        metric(registered).apply {
            encodedPackets.increment()
            encodedBytes.add(bytes.toLong().coerceAtLeast(0L))
            encodeNanos.add(nanos.coerceAtLeast(0L))
        }
    }

    fun recordDecode(registered: RegisteredPacketSchema, bytes: Int, nanos: Long) {
        if (!isEnabled()) return
        metric(registered).apply {
            decodedPackets.increment()
            decodedBytes.add(bytes.toLong().coerceAtLeast(0L))
            decodeNanos.add(nanos.coerceAtLeast(0L))
        }
    }

    fun recordReject(registered: RegisteredPacketSchema, reason: PacketRejectReason) {
        if (!isEnabled()) return
        metric(registered).rejects.increment()
        state.get().rejectReasons.computeIfAbsent(reason) { LongAdder() }.increment()
    }

    fun recordEntityDataDirty(accessor: String, payloadBytes: Int) {
        if (!isEnabled()) return
        state.get().entityData.computeIfAbsent(accessor) { EntityDataMetric() }.apply {
            dirtyWrites.increment()
            this.payloadBytes.add(payloadBytes.toLong().coerceAtLeast(0L))
        }
    }

    fun recordEntityDataSuppressed(accessor: String) {
        if (!isEnabled()) return
        state.get().entityData.computeIfAbsent(accessor) { EntityDataMetric() }.unchangedSuppressions.increment()
    }

    /** Names must be fixed source constants; payload-derived labels are deliberately forbidden. */
    fun recordSystemWork(
        name: String,
        items: Int = 0,
        recipients: Int = 0,
        serializedTags: Int = 0,
        losChecks: Int = 0,
        workNanos: Long = 0L,
    ) {
        if (!isEnabled()) return
        require(SYSTEM_NAME.matches(name)) { "Invalid low-cardinality telemetry name: $name" }
        state.get().systems.computeIfAbsent(name) { SystemMetric() }.apply {
            invocations.increment()
            this.items.add(items.toLong().coerceAtLeast(0L))
            this.recipients.add(recipients.toLong().coerceAtLeast(0L))
            this.serializedTags.add(serializedTags.toLong().coerceAtLeast(0L))
            this.losChecks.add(losChecks.toLong().coerceAtLeast(0L))
            this.workNanos.add(workNanos.coerceAtLeast(0L))
        }
    }

    fun recordSystemGauge(name: String, current: Long) {
        if (!isEnabled()) return
        require(SYSTEM_NAME.matches(name)) { "Invalid low-cardinality telemetry name: $name" }
        val nonNegative = current.coerceAtLeast(0L)
        state.get().systems.computeIfAbsent(name) { SystemMetric() }.apply {
            invocations.increment()
            highWater.updateAndGet { previous -> maxOf(previous, nonNegative) }
        }
    }

    fun recordChassisTrace(
        entityId: Int,
        serverTick: Long,
        sequence: Int,
        requestedMovementY: Double,
        resolvedMovementY: Double,
        collisionStepCandidateDeltaY: Double,
        collisionStepAppliedDeltaY: Double,
        anchor: Vec3,
        chassisYawDegrees: Float,
        groundBias: Double,
        collisionStepOffset: Double,
        collisionStepVelocity: Double,
        resolvedChassisWorldY: Double,
    ) {
        if (!isEnabled()) return
        if (!requestedMovementY.isFinite() ||
            !resolvedMovementY.isFinite() ||
            !collisionStepCandidateDeltaY.isFinite() ||
            !collisionStepAppliedDeltaY.isFinite() ||
            !anchor.x.isFinite() ||
            !anchor.y.isFinite() ||
            !anchor.z.isFinite() ||
            !chassisYawDegrees.isFinite() ||
            !groundBias.isFinite() ||
            !collisionStepOffset.isFinite() ||
            !collisionStepVelocity.isFinite() ||
            !resolvedChassisWorldY.isFinite()
        ) {
            return
        }

        val captureState = state.get()
        val sampleIndex = captureState.chassisTraceCount.incrementAndGet()
        if (sampleIndex > MAX_CHASSIS_TRACE_SAMPLES) {
            captureState.chassisTraceCount.decrementAndGet()
            captureState.chassisTraceDrops.increment()
            return
        }
        captureState.chassisTrace.add(
            ChassisTraceSample(
                entityId,
                serverTick,
                sequence,
                requestedMovementY,
                resolvedMovementY,
                collisionStepCandidateDeltaY,
                collisionStepAppliedDeltaY,
                anchor.x,
                anchor.y,
                anchor.z,
                chassisYawDegrees,
                groundBias,
                collisionStepOffset,
                collisionStepVelocity,
                resolvedChassisWorldY,
            )
        )
    }

    /** Records one finite correlation event only while the explicit aim trace capture is active. */
    fun recordAimPresentationTrace(sample: AimPresentationTraceSample) {
        if (!isAimPresentationTraceEnabled()) return
        if (!TRACE_STAGE.matches(sample.stage) || !TRACE_REASON.matches(sample.reason)) return
        if (sample.axesValid && (!sample.actualYaw.isFinite() || !sample.actualPitch.isFinite() ||
                !sample.presentedYaw.isFinite() || !sample.presentedPitch.isFinite())) return
        if (!sample.sourceServerTick.isFinite() || !sample.alpha.isFinite()) return

        val captureState = state.get()
        if (sample.stage == "RESOLVED" || sample.stage == "RENDERED") {
            val dedupKey = AimTraceDedupKey(sample.entityId, sample.channel, sample.stage, sample.clientTick)
            if (captureState.aimPresentationTraceDedup.size >= MAX_AIM_PRESENTATION_TRACE_SAMPLES &&
                !captureState.aimPresentationTraceDedup.containsKey(dedupKey)
            ) return
            if (captureState.aimPresentationTraceDedup.putIfAbsent(dedupKey, true) != null) return
        }
        val sampleIndex = captureState.aimPresentationTraceCount.incrementAndGet()
        if (sampleIndex > MAX_AIM_PRESENTATION_TRACE_SAMPLES) {
            captureState.aimPresentationTraceCount.decrementAndGet()
            captureState.aimPresentationTraceDrops.increment()
            return
        }
        captureState.aimPresentationTrace.add(sample)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.END) flushElite()
    }

    fun tickClient() = flushElite()

    /** At most once a second per JVM; integrated client/server share the process counters. */
    @Synchronized fun flushElite(force: Boolean = false) {
        if (!isEnabled()) return
        val now = System.nanoTime()
        if (!force && now - lastFlushNanos < 1_000_000_000L) return
        lastFlushNanos = now
        val captured = state.get()
        EliteDiagnostics.recordProcess("network", "queue",
            "pending" to pendingHandlers.get(), "high_water" to captured.queueHighWater.get(),
            "chassis_drops" to captured.chassisTraceDrops.sum(),
            "aim_drops" to captured.aimPresentationTraceDrops.sum())
        NetworkPacketManifest.entries().forEach { registered ->
            val m = captured.packets[registered.schema.id] ?: return@forEach
            EliteDiagnostics.recordProcess("network", "packet_cumulative",
                "id" to registered.schema.id, "type" to registered.type.simpleName,
                "direction" to registered.schema.direction.name,
                "encoded" to m.encodedPackets.sum(), "decoded" to m.decodedPackets.sum(),
                "encoded_bytes" to m.encodedBytes.sum(), "decoded_bytes" to m.decodedBytes.sum(),
                "encode_ns" to m.encodeNanos.sum(), "decode_ns" to m.decodeNanos.sum(),
                "handler_ns" to m.handlerNanos.sum(), "queue_ns" to m.queueDelayNanos.sum(),
                "rejects" to m.rejects.sum())
        }
        captured.systems.forEach { (name, m) ->
            EliteDiagnostics.recordProcess("network", "system_cumulative",
                "name" to name, "invocations" to m.invocations.sum(), "items" to m.items.sum(),
                "recipients" to m.recipients.sum(), "serialized_tags" to m.serializedTags.sum(),
                "los_checks" to m.losChecks.sum(), "work_ns" to m.workNanos.sum(),
                "high_water" to m.highWater.get())
        }
        captured.entityData.forEach { (name, m) ->
            EliteDiagnostics.recordProcess("network", "entity_data_cumulative",
                "accessor" to name, "writes" to m.dirtyWrites.sum(), "bytes" to m.payloadBytes.sum(),
                "suppressed" to m.unchangedSuppressions.sum())
        }
        captured.rejectReasons.forEach { (reason, count) ->
            EliteDiagnostics.recordProcess("network", "reject_cumulative", "reason" to reason.name,
                "count" to count.sum())
        }
        while (true) {
            val s = captured.chassisTrace.poll() ?: break
            captured.chassisTraceCount.decrementAndGet()
            EliteDiagnostics.recordProcess("network", "chassis",
                "entity_id" to s.entityId, "server_tick" to s.serverTick, "sequence" to s.sequence,
                "requested_y" to s.requestedMovementY, "resolved_y" to s.resolvedMovementY,
                "candidate_step_y" to s.collisionStepCandidateDeltaY,
                "applied_step_y" to s.collisionStepAppliedDeltaY, "anchor_x" to s.anchorX,
                "anchor_y" to s.anchorY, "anchor_z" to s.anchorZ, "yaw" to s.chassisYawDegrees,
                "ground_bias" to s.groundBias, "step_offset" to s.collisionStepOffset,
                "step_velocity" to s.collisionStepVelocity, "world_y" to s.resolvedChassisWorldY)
        }
        while (true) {
            val s = captured.aimPresentationTrace.poll() ?: break
            captured.aimPresentationTraceCount.decrementAndGet()
            EliteDiagnostics.recordProcess("network", "aim",
                "stage" to s.stage, "entity_id" to s.entityId, "client_tick" to s.clientTick,
                "partial_bits" to s.partialBits, "channel" to s.channel, "seat" to s.seatIndex,
                "weapon" to s.weaponIndex, "sequence" to s.sequence, "upper_sequence" to s.upperSequence,
                "server_tick" to s.serverTick, "source_server_tick" to s.sourceServerTick,
                "epoch" to s.epoch, "reason" to s.reason, "cache_hit" to s.cacheHit,
                "direct" to s.direct, "continuity" to s.continuity, "tail_held" to s.tailHeld,
                "alpha" to s.alpha, "axes_valid" to s.axesValid, "actual_yaw" to s.actualYaw,
                "actual_pitch" to s.actualPitch, "presented_yaw" to s.presentedYaw,
                "presented_pitch" to s.presentedPitch)
        }
        captured.aimPresentationTraceDedup.clear()
    }

    private fun metric(registered: RegisteredPacketSchema): PacketMetric =
        state.get().packets.computeIfAbsent(registered.schema.id) { PacketMetric() }

    private val SYSTEM_NAME = Regex("[a-z0-9_.-]{1,64}")
    private val TRACE_STAGE = Regex("[A-Z_]{1,32}")
    private val TRACE_REASON = Regex("[A-Z0-9_]{1,40}")
}
