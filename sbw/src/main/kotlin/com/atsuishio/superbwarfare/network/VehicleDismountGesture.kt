package com.atsuishio.superbwarfare.network

/** Ordered physical edges only: a press or a held/repeated press can never complete a gesture. */
enum class VehicleDismountEdge { PRESS, RELEASE, CANCEL }

enum class VehicleDismountGestureResult { IGNORED, ARMED, RELEASED, COMPLETED }

class VehicleDismountGesture(private val windowNanos: Long = CLIENT_WINDOW_NANOS) {
    companion object {
        const val CLIENT_WINDOW_NANOS = 350_000_000L
        // The client owns the 350 ms gesture; permit bounded packet-delivery jitter server-side.
        const val SERVER_WINDOW_NANOS = 700_000_000L
    }

    private var firstPressNanos: Long? = null
    private var released = false

    fun expired(nowNanos: Long): Boolean = firstPressNanos?.let {
        nowNanos - it !in 0L..windowNanos
    } ?: false

    fun clear() {
        firstPressNanos = null
        released = false
    }

    fun accept(edge: VehicleDismountEdge, nowNanos: Long): VehicleDismountGestureResult {
        if (expired(nowNanos) || edge == VehicleDismountEdge.CANCEL) clear()
        return when (edge) {
            VehicleDismountEdge.CANCEL -> VehicleDismountGestureResult.IGNORED
            VehicleDismountEdge.PRESS -> when {
                firstPressNanos == null -> {
                    firstPressNanos = nowNanos
                    VehicleDismountGestureResult.ARMED
                }
                released -> {
                    clear()
                    VehicleDismountGestureResult.COMPLETED
                }
                else -> VehicleDismountGestureResult.IGNORED
            }
            VehicleDismountEdge.RELEASE -> {
                if (firstPressNanos == null || released) VehicleDismountGestureResult.IGNORED
                else {
                    released = true
                    VehicleDismountGestureResult.RELEASED
                }
            }
        }
    }
}

/** One connection's replay watermark survives gesture/context cancellation and respawn. */
class VehicleDismountAdmission<C> {
    private val gesture = VehicleDismountGesture(VehicleDismountGesture.SERVER_WINDOW_NANOS)
    private var context: C? = null
    private var lastSequence = 0L

    fun clearGesture() {
        context = null
        gesture.clear()
    }

    fun validate(current: C?, nowNanos: Long) {
        if (current == null || current != context || gesture.expired(nowNanos)) clearGesture()
    }

    fun accept(claimed: C, current: C?, sequence: Long, edge: VehicleDismountEdge,
               nowNanos: Long): VehicleDismountGestureResult {
        if (sequence <= 0L || sequence <= lastSequence) return VehicleDismountGestureResult.IGNORED
        if (current == null || claimed != current) {
            clearGesture()
            return VehicleDismountGestureResult.IGNORED
        }
        lastSequence = sequence
        validate(current, nowNanos)
        context = current
        return gesture.accept(edge, nowNanos)
    }
}
