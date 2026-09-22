package com.atsuishio.superbwarfare.client.input

/** Monotonic-time tap/hold arbitration. Repeats cannot restart or complete a press twice. */
class VehicleWeaponSelectionGesture {
    enum class Action { NONE, SELECT_PRIMARY_TWO, CYCLE_SECONDARY }

    private var startedAt: Long? = null
    private var completed = false
    private var binding: Any = Unit

    /** Binding identity includes device, key and modifier. A remap abandons the old press. */
    fun bind(identity: Any) {
        if (binding == identity) return
        cancel()
        binding = identity
    }

    fun press(nowNanos: Long) {
        if (startedAt != null) return
        startedAt = nowNanos
        completed = false
    }

    fun advance(nowNanos: Long): Action {
        val start = startedAt ?: return Action.NONE
        if (nowNanos - start < 0L) {
            cancel()
            return Action.NONE
        }
        if (completed || nowNanos - start < HOLD_NANOS) return Action.NONE
        completed = true
        return Action.CYCLE_SECONDARY
    }

    fun release(nowNanos: Long): Action {
        if (startedAt == null) return Action.NONE
        val thresholdAction = advance(nowNanos)
        val result = when {
            thresholdAction != Action.NONE -> thresholdAction
            startedAt == null || completed -> Action.NONE
            else -> Action.SELECT_PRIMARY_TWO
        }
        cancel()
        return result
    }

    /** Full hold fraction over [HOLD_MILLIS]; null while idle, read-only even at completion. */
    fun progress(nowNanos: Long): Float? = startedAt?.let {
        ((nowNanos - it).toDouble() / HOLD_NANOS).coerceIn(0.0, 1.0).toFloat()
    }

    fun cancel() {
        startedAt = null
        completed = false
    }

    companion object {
        const val HOLD_MILLIS = 400L
        const val FEEDBACK_DELAY_MILLIS = 100L
        const val HOLD_NANOS = HOLD_MILLIS * 1_000_000L
        const val FEEDBACK_DELAY_NANOS = FEEDBACK_DELAY_MILLIS * 1_000_000L
        private const val FEEDBACK_START_PROGRESS = FEEDBACK_DELAY_MILLIS / (HOLD_MILLIS * 1f)

        /** HUD-only reveal fraction: hidden before 100 ms, then 0..1 through completion. */
        @JvmStatic
        fun feedbackProgress(fullHoldProgress: Float?): Float? {
            val progress = fullHoldProgress?.takeIf { it.isFinite() && it >= FEEDBACK_START_PROGRESS }
                ?: return null
            return ((progress - FEEDBACK_START_PROGRESS) / (1f - FEEDBACK_START_PROGRESS))
                .coerceIn(0f, 1f)
        }
    }
}
