package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import net.minecraft.world.phys.Vec3
import kotlin.math.acos

/**
 * Helmet-mounted sight cueing for air-to-air missile seekers.
 *
 * Both sides must opt in, because only some aircraft carry a helmet sight and only some seekers can be slaved to one:
 * - the aircraft armament definition names its sight: `"HelmetSight": {"Name": "Shchel-3UM", "MaxOffBoresightDegrees": 60}`;
 * - the store's `Guidance` gives its seeker's off-boresight limit: `"HelmetOffBoresightDegrees": 45`.
 * The cue reaches the smaller of the two angles.
 *
 * Behaviour (server-owned, see [line]):
 * - While the pilot freelooks more than [ENGAGE_DEGREES] away from the nose, and inside the limit, the seeker is slaved to
 *   the pilot's line of sight with a narrow [ACQUIRE_CONE_DEGREES] cone: the aircraft under the helmet circle is
 *   acquired and dwells to a lock as usual.
 * - Once the seeker holds a target it tracks that target (the line to it, with the store's own cone) for as long as the
 *   target stays inside the limit, wherever the pilot looks. Looking at a different aircraft (more than
 *   [TRACK_CONE_DEGREES] from the held one) re-cues the seeker onto it.
 * - Looking within [ENGAGE_DEGREES] of the nose with nothing held leaves the ordinary boresight seeker unchanged.
 *
 * The client only reports where the pilot looks, in the aircraft's own frame; the server validates the angle and the
 * opt-ins itself, so a client cannot grant a lock or widen a seeker.
 */
object AircraftHelmetSight {
    /** Aircraft armament definition key. */
    const val SIGHT = "HelmetSight"
    /** Key inside a store's Guidance object. */
    const val STORE_LIMIT = "HelmetOffBoresightDegrees"
    /**
     * The pilot must freelook at least this far off the nose (the forward crosshair) before the helmet circle appears and
     * takes the seeker (owner 2026-11-02: 2 degrees).
     */
    const val ENGAGE_DEGREES = 2.0
    /** Half-angle of the helmet circle: what the seeker searches while slaved to the pilot's eyes. */
    const val ACQUIRE_CONE_DEGREES = 4.0
    /** A held target this close to the helmet line stays held while the pilot keeps looking at it. */
    const val TRACK_CONE_DEGREES = 8.0
    /** A helmet report older than this (server ticks) no longer steers the seeker. */
    const val STALE_TICKS = 10L
    /** The client reports at most this often (ticks) while cueing. */
    const val REPORT_INTERVAL_TICKS = 2L

    data class Sight(val name: String, val maxOffBoresightDegrees: Double)
    /** A seeker search line in world space and its half-angle cone, as FFA's lock update takes them. */
    data class Line(val forward: Vec3, val coneDegrees: Double)

    @JvmStatic fun sight(definition: JsonObject?): Sight? = runCatching {
        val raw = definition?.getAsJsonObject(SIGHT) ?: return null
        Sight(raw["Name"].asString, raw["MaxOffBoresightDegrees"].asDouble)
    }.getOrNull()

    /** The store seeker's off-boresight limit when it can be slaved to a helmet sight; null when it cannot. */
    @JvmStatic fun missileLimit(store: JsonObject?): Double? = runCatching {
        if (store?.get("Category")?.asString != "AIR_TO_AIR") return null
        store.getAsJsonObject("Guidance")?.get(STORE_LIMIT)?.asDouble
    }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    /** The usable off-boresight angle for [store] on an aircraft with [definition]; null when either lacks the feature. */
    @JvmStatic fun limit(definition: JsonObject?, store: JsonObject?): Double? {
        val aircraft = sight(definition)?.maxOffBoresightDegrees ?: return null
        val missile = missileLimit(store) ?: return null
        return minOf(aircraft, missile)
    }

    fun validateSight(definition: JsonObject) {
        val raw = definition[SIGHT] ?: return
        require(raw.isJsonObject) { "$SIGHT must be an object" }
        val sight = raw.asJsonObject
        require(sight["Name"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.length in 1..32) {
            "$SIGHT.Name must be 1-32 characters"
        }
        val limit = sight["MaxOffBoresightDegrees"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        require(limit != null && limit.isFinite() && limit in 15.0..120.0) { "$SIGHT.MaxOffBoresightDegrees must be 15-120" }
    }

    fun validateStore(store: JsonObject) {
        val guidance = store.getAsJsonObject("Guidance") ?: return
        val raw = guidance[STORE_LIMIT] ?: return
        require(raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber) { "$STORE_LIMIT must be a number" }
        require(raw.asDouble.isFinite() && raw.asDouble in 15.0..90.0) { "$STORE_LIMIT must be 15-90" }
        require(store["Category"]?.asString == "AIR_TO_AIR") { "Only air-to-air seekers take a helmet cue" }
    }

    /** Degrees between two directions (any length). */
    @JvmStatic fun angleDegrees(a: Vec3, b: Vec3): Double {
        val la = a.length(); val lb = b.length()
        if (la < 1.0e-9 || lb < 1.0e-9) return 180.0
        return Math.toDegrees(acos((a.dot(b) / (la * lb)).coerceIn(-1.0, 1.0)))
    }

    /** Whether the pilot's [view] is far enough off the [nose] to cue, and inside [limit]. */
    @JvmStatic fun cueing(nose: Vec3, view: Vec3, limit: Double): Boolean =
        angleDegrees(nose, view) in ENGAGE_DEGREES..limit

    /**
     * The seeker line for one tick, or null to keep the ordinary boresight seeker.
     *
     * [nose], [view] (the pilot's line of sight, null when not reported) and [toTarget] (towards the target the seeker
     * holds, null when none) are world directions. [storeCone] is the store's own seeker half-angle.
     */
    @JvmStatic fun line(nose: Vec3, view: Vec3?, toTarget: Vec3?, limit: Double, storeCone: Double): Line? {
        val cue = view?.takeIf { cueing(nose, it, limit) }?.normalize()
        val held = toTarget?.takeIf { it.lengthSqr() > 1.0e-12 && angleDegrees(nose, it) <= limit }?.normalize()
        if (held != null && (cue == null || angleDegrees(cue, held) <= TRACK_CONE_DEGREES))
            return Line(held, storeCone.coerceIn(1.0, 60.0))
        if (cue != null) return Line(cue, ACQUIRE_CONE_DEGREES)
        return null
    }
}
