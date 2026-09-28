package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot
import net.minecraft.util.Mth
import java.util.UUID

/** Bounded full-frame assembly. An incomplete frame never removes a previously visible vehicle. */
class FarVehicleStore {
    data class Entry @JvmOverloads constructor(
        val previous: FarVehicleSnapshot,
        val current: FarVehicleSnapshot,
        val previousPose: VehiclePoseSnapshot,
        val currentPose: VehiclePoseSnapshot,
        val receivedTick: Long,
        val interval: Int,
        val sourceTick: Long = 0L,
        val startTick: Double = receivedTick.toDouble(),
        val endTick: Double = receivedTick + interval.toDouble(),
        internal val clockOffset: Double = 0.0,
        internal val earlierSegments: List<Entry> = emptyList(),
    ) {
        val previousFixedWingControls: FarFixedWingVisualState? = if (previous.wreck) null else
            FarFixedWingVisualState.decode(previous.visualData[FarFixedWingVisualState.VISUAL_KEY])
        val currentFixedWingControls: FarFixedWingVisualState? = if (current.wreck) null else
            FarFixedWingVisualState.decode(current.visualData[FarFixedWingVisualState.VISUAL_KEY])
    }

    private data class Pending(
        val sequence: Long,
        val partCount: Int,
        val startedTick: Long,
        val serverTick: Long,
        val interval: Int,
        val range: Int,
        val parts: MutableMap<Int, List<FarVehicleSnapshot>> = HashMap(),
    )

    private val entries = LinkedHashMap<Int, Entry>()
    private var pending: Pending? = null
    private var session: String? = null
    private var sequence = -1L
    var rangeBlocks: Int = 2048
        private set

    fun values(): Collection<Entry> = entries.values
    /** Changes whenever the entries do (for per-frame memos over them). */
    var revision = 0L
        private set
    fun contains(id: Int): Boolean = entries.containsKey(id)
    fun get(id: Int): Entry? = entries[id]

    fun clear() {
        entries.clear()
        revision++
        pending = null
        session = null
        sequence = -1L
    }

    fun expire(now: Long) {
        // Network stalls freeze the last authoritative pose. Only a complete membership frame
        // or world/connection lifecycle can remove a vehicle; interpolation still expires below.
        if (pending?.let { now - it.startedTick > EXPIRY_TICKS } == true) pending = null
    }

    fun accept(
        sessionId: String, frame: Long, serverTick: Long, interval: Int, range: Int,
        index: Int, count: Int, snapshots: List<FarVehicleSnapshot>, now: Long,
    ): Boolean {
        if (sessionId.length != 36 || frame < 0 || serverTick < 0 || interval !in 2..20 ||
            range !in 64..8192 || count !in 1..MAX_PARTS || index !in 0 until count ||
            snapshots.size > PER_PACKET || snapshots.any { !it.valid() }) return false
        if (runCatching { UUID.fromString(sessionId).toString() }.getOrNull() != sessionId) return false
        if (snapshots.map { it.id }.toSet().size != snapshots.size) return false
        if (snapshots.map { it.uuid }.toSet().size != snapshots.size) return false
        // A session change is admitted only after a connection/world lifecycle clear.
        if (session != null && session != sessionId) return false
        if (frame <= sequence) return false
        session = sessionId
        val oldPending = pending
        if (oldPending != null && frame < oldPending.sequence) return false
        val p = if (oldPending?.sequence == frame) oldPending else
            Pending(frame, count, now, serverTick, interval, range).also { pending = it }
        if (p.partCount != count || p.serverTick != serverTick || p.interval != interval || p.range != range) return false
        if (p.parts.containsKey(index)) return false
        p.parts[index] = snapshots.map { it.copy(flaps = it.flaps.toList(),
            selectedWeapons = it.selectedWeapons.toList(), visualData = it.visualData.toMap()) }
        if (p.parts.size != count) return false
        val complete = (0 until count).flatMap { p.parts.getValue(it) }
        if (complete.size > MAX_VEHICLES || complete.map { it.id }.toSet().size != complete.size ||
            complete.map { it.uuid }.toSet().size != complete.size) {
            pending = null
            return false
        }
        val next = LinkedHashMap<Int, Entry>()
        for (snapshot in complete) {
            val old = entries[snapshot.id]
            val discontinuity = old == null || !old.current.sameIdentity(snapshot) ||
                snapshot.distanceSquared(old.current.x, old.current.y, old.current.z) > 64.0 * 64.0 ||
                old.interval != interval || serverTick <= old.sourceTick ||
                serverTick - old.sourceTick > EXPIRY_TICKS || now - old.receivedTick > EXPIRY_TICKS
            val previous = if (discontinuity) snapshot else old!!.current
            val delay = BUFFER_INTERVALS * interval.toDouble()
            val depleted = !discontinuity && old!!.endTick < now
            val observedOffset = now.toDouble() - serverTick
            // Keep the least-delayed accepted clock observation. A late startup receipt cannot
            // permanently strand playback behind its retained history; isolated late arrivals
            // do not move the clock backwards. A depleted buffer explicitly starts recovery.
            val offset = if (discontinuity || depleted) observedOffset else
                minOf(old!!.clockOffset, observedOffset)
            val start = if (discontinuity) now + delay else maxOf(old!!.endTick, now.toDouble())
            val span = if (discontinuity) 0.0 else (serverTick - old!!.sourceTick).toDouble()
            val expectedEnd = start + span
            val correction = if (discontinuity || depleted) 0.0 else
                (serverTick + offset + delay - expectedEnd)
                    .coerceIn(-span * MAX_CLOCK_ADJUSTMENT, span * MAX_CLOCK_ADJUSTMENT)
            // Only unplayed new segments change duration. Existing segment endpoints remain
            // contiguous and immutable, so receipt/clock adjustment cannot jump an active pose.
            val earlier = ArrayList<Entry>(MAX_SEGMENTS)
            var segmentPrevious = previous
            var segmentStart = start
            if (!discontinuity) {
                for (entry in old!!.earlierSegments) if (entry.endTick > now) earlier.add(entry)
                if (old.endTick > now) earlier.add(old.copy(earlierSegments = emptyList()))
                if (earlier.size == MAX_SEGMENTS) {
                    // Merge the latest future segment with this arrival. The retained brackets
                    // cover now through now + 1, including any already-rendered partial tick.
                    val future = earlier.removeAt(earlier.lastIndex)
                    segmentPrevious = future.previous
                    segmentStart = future.startTick
                }
            }
            next[snapshot.id] = Entry(segmentPrevious, snapshot,
                VehiclePoseSnapshot.decode(segmentPrevious.pose)!!, VehiclePoseSnapshot.decode(snapshot.pose)!!,
                now, interval, serverTick, segmentStart, expectedEnd + correction, offset, earlier.toList())
        }
        entries.clear()
        entries.putAll(next)
        revision++
        rangeBlocks = range
        sequence = frame
        pending = null
        return true
    }

    companion object {
        const val PER_PACKET = 16
        const val MAX_VEHICLES = 256
        const val MAX_PARTS = MAX_VEHICLES / PER_PACKET
        const val EXPIRY_TICKS = 60L
        // Four shallow segments retain at most five endpoints. With a minimum source span of
        // one tick and 12.5% retiming, the last starts beyond now + 1 even under burst pressure.
        // Coalescing it cannot change a bracket sampled in the current render tick.
        const val MAX_SEGMENTS = 4
        const val BUFFER_INTERVALS = 2
        private const val MAX_CLOCK_ADJUSTMENT = 0.125

        fun segment(entry: Entry, tick: Double): Entry {
            var index = 0
            while (index < entry.earlierSegments.size) {
                val earlier = entry.earlierSegments[index++]
                if (tick <= earlier.endTick) return earlier
            }
            return entry
        }

        fun alpha(entry: Entry, tick: Double): Float {
            if (!tick.isFinite() || entry.endTick <= entry.startTick) return 1F
            return ((tick - entry.startTick) / (entry.endTick - entry.startTick))
                .toFloat().coerceIn(0F, 1F)
        }

        fun fixedWingControls(entry: Entry, segment: Entry, alpha: Float): FarFixedWingVisualState? {
            val latest = entry.currentFixedWingControls ?: return null
            val sampled = segment.currentFixedWingControls ?: return null
            if (latest.serverTick < sampled.serverTick || latest.sequence - sampled.sequence < 0) {
                return latest
            }
            return FarFixedWingVisualState.interpolate(segment.previousFixedWingControls, sampled, alpha)
        }

        @JvmOverloads
        fun interpolate(entry: Entry, alpha: Float,
                        latest: FarVehicleSnapshot = entry.current): FarVehicleSnapshot {
            val a = alpha.coerceIn(0F, 1F)
            val old = entry.previous
            val value = entry.current
            fun linear(x: Float, y: Float) = Mth.lerp(a, x, y)
            fun angle(x: Float, y: Float) = Mth.rotLerp(a, x, y)
            val p = old.parts
            val q = value.parts
            val g = old.runningGear
            val h = value.runningGear
            // Occupancy, wreck, health, weapon identity and extension presence stay current;
            // only continuous visual channels use the buffered source pair.
            return latest.copy(
                x = Mth.lerp(a.toDouble(), old.x, value.x),
                y = Mth.lerp(a.toDouble(), old.y, value.y),
                z = Mth.lerp(a.toDouble(), old.z, value.z),
                yaw = angle(old.yaw, value.yaw), pitch = linear(old.pitch, value.pitch),
                roll = angle(old.roll, value.roll),
                propeller = linear(old.propeller, value.propeller), gear = linear(old.gear, value.gear),
                flaps = value.flaps.mapIndexed { index, f -> linear(old.flaps[index], f) },
                parts = latest.parts.copy(
                    interpolatedHullYawDegrees = angle(p.interpolatedHullYawDegrees, q.interpolatedHullYawDegrees),
                    hullPitchDegrees = linear(p.hullPitchDegrees, q.hullPitchDegrees),
                    hullRollDegrees = angle(p.hullRollDegrees, q.hullRollDegrees),
                    turretWorldYawDegrees = angle(p.turretWorldYawDegrees, q.turretWorldYawDegrees),
                    turretPitchDegrees = linear(p.turretPitchDegrees, q.turretPitchDegrees),
                    turretYawFromRenderedHullDegrees = angle(p.turretYawFromRenderedHullDegrees, q.turretYawFromRenderedHullDegrees),
                    barrelPitchDegrees = linear(p.barrelPitchDegrees, q.barrelPitchDegrees),
                    stationYawRelativeToTurretDegrees = angle(p.stationYawRelativeToTurretDegrees, q.stationYawRelativeToTurretDegrees),
                    stationYawFromRenderedHullDegrees = angle(p.stationYawFromRenderedHullDegrees, q.stationYawFromRenderedHullDegrees),
                    stationPitchDegrees = linear(p.stationPitchDegrees, q.stationPitchDegrees),
                    stationPitchRadians = linear(p.stationPitchRadians, q.stationPitchRadians),
                    ags30PitchDegrees = if (latest.parts.ags30PitchDegrees == null) null else
                        if (p.ags30PitchDegrees != null && q.ags30PitchDegrees != null)
                        linear(p.ags30PitchDegrees, q.ags30PitchDegrees) else q.ags30PitchDegrees,
                    recoilShake = linear(p.recoilShake, q.recoilShake),
                ),
                runningGear = latest.runningGear.copy(
                    leftWheelRotation = linear(g.leftWheelRotation, h.leftWheelRotation),
                    rightWheelRotation = linear(g.rightWheelRotation, h.rightWheelRotation),
                    leftTrackPhase = linear(g.leftTrackPhase, h.leftTrackPhase),
                    rightTrackPhase = linear(g.rightTrackPhase, h.rightTrackPhase),
                    rudderRotation = linear(g.rudderRotation, h.rudderRotation),
                ),
            )
        }
    }
}
