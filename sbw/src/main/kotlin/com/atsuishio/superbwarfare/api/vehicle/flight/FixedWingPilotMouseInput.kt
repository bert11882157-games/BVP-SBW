package com.atsuishio.superbwarfare.api.vehicle.flight

import java.util.UUID

/** Server-thread pilot deltas. A sample is consumed once; a departed pilot cannot retain input. */
class FixedWingPilotMouseInput {
    private var owner: UUID? = null
    private var pendingX = 0.0
    private var pendingY = 0.0

    var sampledX = 0.0
        private set
    var sampledY = 0.0
        private set

    fun offer(controller: UUID, x: Double, y: Double): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        if (owner != controller) {
            clear()
            owner = controller
        }
        pendingX = (pendingX + x.coerceIn(-512.0, 512.0)).coerceIn(-512.0, 512.0)
        pendingY = (pendingY + y.coerceIn(-512.0, 512.0)).coerceIn(-512.0, 512.0)
        return true
    }

    fun consume(controller: UUID?) {
        if (controller == null || controller != owner) {
            clear()
            return
        }
        sampledX = pendingX
        sampledY = pendingY
        pendingX = 0.0
        pendingY = 0.0
    }

    fun clear() {
        owner = null
        pendingX = 0.0
        pendingY = 0.0
        sampledX = 0.0
        sampledY = 0.0
    }
}
