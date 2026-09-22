package com.atsuishio.superbwarfare.api.weapon

/**
 * Converts a resolved reload clip duration into the countdown observed by GunEventHandler.
 * Reload completion occurs at raw timer 2 before decrement, irrespective of bolt setup offset.
 * Measured clips align to that boundary; the setup offset only bounds the available window.
 *
 * VehicleReloadSoundTime is retained as a legacy countdown for already-authored resources;
 * new typed TaP/BVP events should provide VehicleReloadClipDurationTicks instead.
 */
object VehicleReloadSoundTiming {
    /** GunEventHandler completes after decrementing raw timer 2 to 1. */
    @JvmStatic
    fun ticksUntilCompletion(rawTimer: Int): Int = (rawTimer.toLong() - 2L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    @JvmStatic
    fun countdownFor(
        reloadDurationTicks: Int,
        clipDurationTicks: Int,
        legacyCountdown: Int,
        timerOffsetTicks: Int,
    ): Int {
        if (reloadDurationTicks <= 0) return 0
        val offset = timerOffsetTicks.coerceAtLeast(0)
        val maxCountdown = reloadDurationTicks.toLong() + offset.toLong()
        val boundedMax = maxCountdown.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (clipDurationTicks <= 0 && legacyCountdown > 0) {
            return legacyCountdown.coerceIn(1, boundedMax)
        }
        // A typed event without an explicit duration is conservatively treated as a full reload
        // clip, so it starts immediately rather than at the final timer tick. Author the measured
        // duration to align the clip end exactly with reload completion.
        val clip = if (clipDurationTicks > 0) {
            clipDurationTicks.coerceAtMost(reloadDurationTicks)
        } else {
            reloadDurationTicks
        }
        val startElapsed = (reloadDurationTicks - clip).coerceAtLeast(0)
        val countdown = reloadDurationTicks.toLong() - startElapsed.toLong() + 2L
        return countdown.coerceIn(1L, maxCountdown).toInt()
    }
}
