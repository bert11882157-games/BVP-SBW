package com.atsuishio.superbwarfare.api.weapon

/** Separates a delayed data update from an ended or superseded reload cycle. */
object ReloadPlaybackWindow {
    enum class Decision { WAIT, PLAY, STOP }

    fun evaluate(expectedRevision: Int, currentRevision: Int, reloading: Boolean,
                 countdown: Int, remainingBudget: Int, sourceAvailable: Boolean): Decision {
        if (!sourceAvailable || remainingBudget <= 0 || currentRevision > expectedRevision) return Decision.STOP
        if (currentRevision != expectedRevision) return Decision.WAIT
        return if (reloading && countdown > 1) Decision.PLAY else Decision.STOP
    }
}
