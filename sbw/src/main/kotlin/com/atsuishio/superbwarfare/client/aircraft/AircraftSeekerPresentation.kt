package com.atsuishio.superbwarfare.client.aircraft

import kotlin.math.exp

/** Presentation follows authoritative acquisition; interpolation can never grant a lock. */
class AircraftSeekerPresentation {
    enum class Tone { SILENT, SEARCH, ACQUIRE, LOCK }
    companion object {
        fun tone(seek: AircraftSeekView?): Tone = when {
            seek?.activeAam != true -> Tone.SILENT
            seek.ready -> Tone.LOCK
            else -> Tone.SILENT
        }
    }
    private var identity = ""
    private var lastTime = Double.NaN
    var convergence = 0.0; private set
    data class Circle(val x: Double, val y: Double, val radius: Double) {
        fun point(angle: Double): Pair<Double, Double> =
            Pair(x + kotlin.math.cos(angle) * radius, y - kotlin.math.sin(angle) * radius)
    }
    private var circle: Circle? = null
    private var circleTime = Double.NaN

    /** Only a screen-space center and scalar radius interpolate; there is no rotating vertex basis. */
    fun circle(seek: AircraftSeekView, seconds: Double, searchX: Double, searchY: Double,
               searchRadius: Double, target: Pair<Double, Double>?): Circle {
        val progress = sample(seek, seconds)
        val blend = if (target == null) 0.0 else progress * progress * (3 - 2 * progress)
        val desired = Circle(searchX + ((target?.first ?: searchX) - searchX) * blend,
            searchY + ((target?.second ?: searchY) - searchY) * blend,
            searchRadius + (8.0 - searchRadius) * blend)
        val previous = circle
        val alpha = if (!circleTime.isFinite() || seconds < circleTime) 1.0 else
            1 - exp(-(seconds - circleTime).coerceIn(0.0, 0.2) * 24)
        val result = if (previous == null) desired else Circle(
            previous.x + (desired.x - previous.x) * alpha,
            previous.y + (desired.y - previous.y) * alpha,
            previous.radius + (desired.radius - previous.radius) * alpha)
        circle = result; circleTime = seconds
        return result
    }
    fun sample(seek: AircraftSeekView, seconds: Double): Double {
        val next = "${seek.weaponId}:${seek.target}"
        if (identity != next || !seconds.isFinite() || !lastTime.isFinite() || seconds < lastTime) {
            identity = next; lastTime = seconds; convergence = 0.0
        }
        val dt = (seconds - lastTime).coerceIn(0.0, 0.2)
        lastTime = seconds
        val goal = if (seek.target == null) 0.0 else seek.progress
        convergence = if (seek.ready) 1.0 else convergence + (goal - convergence) * (1 - exp(-dt * 18))
        return convergence
    }
    fun reset() { identity = ""; lastTime = Double.NaN; convergence = 0.0; circle = null; circleTime = Double.NaN }
}

/** One confirmation per stable lock; transient ready-bit jitter cannot restart the sound. */
class AircraftLockConfirmation {
    private var lastIdentity = ""
    private var lastPlayed = Long.MIN_VALUE
    private var missingSince: Long? = null
    fun update(seek: AircraftSeekView?, tick: Long): Boolean {
        if (seek?.activeAam != true || !seek.ready || seek.target == null) {
            if (missingSince == null) missingSince = tick
            return false
        }
        val identity = "${seek.weaponId}:${seek.target}"
        val rearmed = missingSince?.let { tick - it >= 10 } == true
        missingSince = null
        val cooled = lastPlayed == Long.MIN_VALUE || tick - lastPlayed >= 20
        if (!cooled || (identity == lastIdentity && !rearmed)) return false
        lastIdentity = identity; lastPlayed = tick
        return true
    }
    fun reset() { lastIdentity = ""; lastPlayed = Long.MIN_VALUE; missingSince = null }
}
