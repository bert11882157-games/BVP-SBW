package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.world.phys.Vec3
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Stateless guidance math. Propulsion owns speed; guidance owns only the velocity direction. */
object GuidedMissileGuidance {
    const val TICKS_PER_SECOND = 20.0
    private const val MIN_VECTOR_LENGTH_SQUARED = 1.0e-16
    private const val MIN_LOOK_AHEAD_BLOCKS = 2.0
    private val LASER_SEEKER_COSINE = cos(Math.toRadians(45.0))

    /** Remove last tick's wobble before guidance, without undoing propulsion/drag's speed change. */
    fun removeSpinPerturbation(velocity: Vec3, inherited: Vec3, previousOffset: Vec3): Vec3 {
        val relative = velocity.subtract(inherited)
        val clean = relative.subtract(previousOffset)
        return if (finite(clean) && clean.lengthSqr() > MIN_VECTOR_LENGTH_SQUARED)
            clean.normalize().scale(relative.length()).add(inherited) else velocity
    }

    /** Speed-dependent corkscrew with a slower vertical bob; never integrates a random turn bias. */
    fun spinPerturbation(velocity: Vec3, inherited: Vec3, age: Int, maximumSpeed: Double = 6.0): Vec3 {
        val relative = velocity.subtract(inherited)
        val speed = relative.length()
        if (!finite(relative) || speed < 1.0e-8) return velocity
        val forward = relative.scale(1.0 / speed)
        val right = forward.cross(if (kotlin.math.abs(forward.y) < 0.9) Vec3(0.0, 1.0, 0.0)
            else Vec3(1.0, 0.0, 0.0)).normalize()
        val up = right.cross(forward).normalize()
        val fraction = if (maximumSpeed.isFinite() && maximumSpeed > 0) (speed / maximumSpeed).coerceIn(0.0, 1.0) else 1.0
        val amplitude = Math.toRadians(0.22 + (1.0 - fraction) * (1.0 - fraction))
        val phase = age * Math.PI / 10.0
        val bob = sin(age * Math.PI / 17.0) * 0.35
        return forward.add(right.scale(cos(phase) * amplitude))
            .add(up.scale((sin(phase) + bob) * amplitude)).normalize().scale(speed).add(inherited)
    }

    /** A null designation explicitly means coast. The caller must not substitute a camera ray. */
    fun pointDirection(position: Vec3, target: Vec3?): Vec3? {
        if (target == null || !finite(position) || !finite(target)) return null
        return target.subtract(position).takeIf { it.lengthSqr() > MIN_VECTOR_LENGTH_SQUARED }
    }

    /** Aim the total trajectory at the painted point, including momentum inherited at release. */
    fun laserInterceptDirection(position: Vec3, target: Vec3?, relativeSpeed: Double, inherited: Vec3): Vec3? {
        val line = pointDirection(position, target)?.normalize() ?: return null
        if (!finite(inherited) || !relativeSpeed.isFinite() || relativeSpeed <= 1e-8) return null
        val along = inherited.dot(line)
        val crossSquared = (inherited.lengthSqr() - along * along).coerceAtLeast(0.0)
        val available = relativeSpeed * relativeSpeed - crossSquared
        // Low-speed ejection may not yet have enough lateral thrust to cancel carrier momentum.
        val closing = (along + kotlin.math.sqrt(available.coerceAtLeast(0.0))).coerceAtLeast(relativeSpeed * 0.1)
        return line.scale(closing).subtract(inherited).takeIf { finite(it) && it.lengthSqr() > 1e-12 }
    }

    /** The seeker sees a 45-degree cone about the missile body, independent of carrier motion. */
    fun laserSeekerDirection(position: Vec3, facing: Vec3, target: Vec3?,
        relativeSpeed: Double, inherited: Vec3): Vec3? {
        val line = pointDirection(position, target) ?: return null
        if (!finite(facing) || !facing.lengthSqr().isFinite() ||
            facing.lengthSqr() <= MIN_VECTOR_LENGTH_SQUARED || !line.lengthSqr().isFinite()) return null
        if (facing.normalize().dot(line.normalize()) + 1e-12 < LASER_SEEKER_COSINE) return null
        return laserInterceptDirection(position, target, relativeSpeed, inherited)
    }

    /**
     * A gimballed television seeker: steers at [target] while it lies within [gimbalDegrees] of the missile's
     * facing (the operator's crosshair cannot leave the seeker's view, so this only drops a point left far behind).
     */
    fun tvSeekerDirection(position: Vec3, facing: Vec3, target: Vec3?, relativeSpeed: Double, inherited: Vec3,
                          gimbalDegrees: Double): Vec3? {
        val line = pointDirection(position, target) ?: return null
        if (!finite(facing) || facing.lengthSqr() <= MIN_VECTOR_LENGTH_SQUARED || !line.lengthSqr().isFinite()) return null
        if (facing.normalize().dot(line.normalize()) + 1e-12 < cos(Math.toRadians(gimbalDegrees))) return null
        return laserInterceptDirection(position, target, relativeSpeed, inherited)
    }

    /**
     * Direction [fraction] of the way from [current] toward [desired] (both any length; the result is a unit vector),
     * so a steering command closes a share of the remaining angle each tick instead of snapping onto it.
     */
    @JvmStatic
    fun easeToward(current: Vec3, desired: Vec3, fraction: Double): Vec3 {
        if (!finite(current) || !finite(desired) || current.lengthSqr() <= MIN_VECTOR_LENGTH_SQUARED) return desired
        if (desired.lengthSqr() <= MIN_VECTOR_LENGTH_SQUARED) return current
        val a = current.normalize()
        val b = desired.normalize()
        val f = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 1.0
        val mixed = a.scale(1.0 - f).add(b.scale(f))
        return if (mixed.lengthSqr() > MIN_VECTOR_LENGTH_SQUARED) mixed.normalize() else b
    }

    /**
     * TV missiles never fly faster than 400 km/h (owner 2026-10-02: "it should accelerate decently with pitchdown,
     * but not extremely fast, cap out at 400 km/h"), in blocks/tick (1 block = 1 m, 20 ticks/s).
     */
    const val TV_SPEED_CAP = 400.0 / 72.0
    /** Level cruise is this much faster than the carrier was flying at release (blocks/tick)... */
    const val TV_CRUISE_MARGIN = 1.25
    /** ...but at least this fast (about 300 km/h; slow carriers such as helicopters)... */
    const val TV_CRUISE_MIN_SPEED = 4.2
    /** ...and, unless the carrier itself was faster, at most this fast (360 km/h): a dive adds the rest to the cap. */
    const val TV_CRUISE_MAX_SPEED = 5.0
    /** Most extra motor push while below cruise speed (blocks/tick², about 12 m/s²). */
    const val TV_CRUISE_ACCELERATION = 0.03
    /** The extra push fades out over the last this-many blocks/tick below cruise, so a climb costs some speed. */
    const val TV_CRUISE_PUSH_BAND = 1.0
    /** Gravity along the flight path (blocks/tick², about 14 m/s²): a dive gains speed, a climb loses it. */
    const val TV_PATH_GRAVITY = 0.035
    /** Share of the excess over [TV_SPEED_CAP] a missile released faster keeps per tick (gone in about a second). */
    const val TV_OVERSPEED_RETAIN = 0.85
    /** Slowest a TV missile flies (blocks/tick); it never hangs in the air. */
    const val TV_MIN_SPEED = 1.0
    /** The motor sustains cruise for at least this long after ignition (ticks), whatever its boost burn time. */
    const val TV_SUSTAIN_TICKS = 600

    /**
     * Level cruise speed for a TV missile released at [platformSpeed] (blocks/tick): [TV_CRUISE_MARGIN] faster than
     * the carrier within [TV_CRUISE_MIN_SPEED]..[TV_CRUISE_MAX_SPEED], never slower than the carrier was, never above
     * [TV_SPEED_CAP] or the carrier speed plus the profile's MaxSpeed.
     */
    @JvmStatic
    fun tvCruiseSpeed(platformSpeed: Double, profileMaxSpeed: Double): Double {
        val platform = if (platformSpeed.isFinite()) platformSpeed.coerceAtLeast(0.0) else 0.0
        val ceiling = if (profileMaxSpeed.isFinite() && profileMaxSpeed > 0.0) platform + profileMaxSpeed else Double.MAX_VALUE
        val level = (platform + TV_CRUISE_MARGIN).coerceIn(TV_CRUISE_MIN_SPEED, TV_CRUISE_MAX_SPEED)
        return maxOf(level, platform).coerceAtMost(ceiling).coerceAtMost(TV_SPEED_CAP)
            .coerceAtMost(GuidedPropulsionProfile.MAX_SPEED_BLOCKS_PER_TICK)
    }

    /** Air drag on a TV missile at its cruise speed (blocks/tick², about 3 m/s²), growing with v². */
    const val TV_COAST_DRAG = 0.008

    /**
     * One tick of TV-missile speed (blocks/tick). [climbSine] is the sine of the flight path angle (negative in a
     * dive). Gravity along the path [TV_PATH_GRAVITY] speeds a dive and slows a climb; drag d (v/target)² slows it;
     * while [burning] the motor cancels the drag at cruise and pushes up to [TV_CRUISE_ACCELERATION] more while it is
     * below [target] (fading over [TV_CRUISE_PUSH_BAND]). Never above [TV_SPEED_CAP]: a missile released faster bleeds
     * the excess quickly ([TV_OVERSPEED_RETAIN]).
     */
    @JvmStatic
    @JvmOverloads
    fun tvCruiseStep(speed: Double, target: Double, burning: Boolean, climbSine: Double = 0.0): Double {
        val v = if (speed.isFinite()) speed.coerceAtLeast(0.0) else 0.0
        if (!(target > 0.0) || !target.isFinite()) return v
        val sine = if (climbSine.isFinite()) climbSine.coerceIn(-1.0, 1.0) else 0.0
        val ratio = v / target
        val drag = TV_COAST_DRAG * ratio * ratio
        val motor = if (burning) TV_COAST_DRAG +
            TV_CRUISE_ACCELERATION * ((target - v) / TV_CRUISE_PUSH_BAND).coerceIn(0.0, 1.0) else 0.0
        val next = v - TV_PATH_GRAVITY * sine + motor - drag
        val ceiling = maxOf(TV_SPEED_CAP, TV_SPEED_CAP + (v - TV_SPEED_CAP) * TV_OVERSPEED_RETAIN)
        return next.coerceAtMost(ceiling).coerceAtLeast(minOf(TV_MIN_SPEED, ceiling))
            .coerceAtMost(GuidedPropulsionProfile.MAX_SPEED_BLOCKS_PER_TICK)
    }

    /** A bounded loft, tapering continuously into the stored target rather than chasing the operator. */
    fun topAttackDirection(position: Vec3, target: Vec3): Vec3? {
        if (!finite(position) || !finite(target)) return null
        val range = position.subtract(target).horizontalDistance()
        val loft = ((range - 25.0).coerceAtLeast(0.0) * 0.25).coerceAtMost(40.0)
        return target.add(0.0, loft, 0.0).subtract(position)
            .takeIf { it.lengthSqr() > MIN_VECTOR_LENGTH_SQUARED }
    }

    /**
     * Rotate the actual relative velocity by one bounded 3D angle, not independent Euler clamps.
     * This preserves speed in both thrust and coast, including diagonal and vertical turns.
     */
    @JvmStatic
    fun steer(
        velocity: Vec3,
        inheritedMotion: Vec3,
        targetDirection: Vec3,
        maxTurnRateDegreesPerSecond: Double,
    ): Vec3 = steerBounded(velocity, inheritedMotion, targetDirection, maxTurnRateDegreesPerSecond,
        GuidedPropulsionProfile.MAX_TURN_RATE_DEGREES_PER_SECOND)

    /** Only the typed maneuver extension can opt into its separately bounded angular ceiling. */
    fun steerManeuver(velocity: Vec3, inheritedMotion: Vec3, targetDirection: Vec3,
        configuredTurnRate: Double, policy: GuidedManeuverPolicy): Vec3 =
        steerBounded(velocity, inheritedMotion, targetDirection,
            policy.effectiveTurnRate(velocity.subtract(inheritedMotion).length(), configuredTurnRate), policy.bodyTurnCeiling)

    private fun steerBounded(velocity: Vec3, inheritedMotion: Vec3, targetDirection: Vec3,
        maxTurnRateDegreesPerSecond: Double, ceiling: Double): Vec3 {
        if (!finite(velocity) || !finite(inheritedMotion) || !finite(targetDirection) ||
            !maxTurnRateDegreesPerSecond.isFinite() || maxTurnRateDegreesPerSecond <= 0.0
        ) return velocity
        val relative = velocity.subtract(inheritedMotion)
        val speed = relative.length()
        val targetLengthSquared = targetDirection.lengthSqr()
        if (!speed.isFinite() || speed * speed <= MIN_VECTOR_LENGTH_SQUARED ||
            !targetLengthSquared.isFinite() || targetLengthSquared <= MIN_VECTOR_LENGTH_SQUARED
        ) return velocity

        val forward = relative.scale(1.0 / speed)
        val target = targetDirection.normalize()
        val angle = acos(forward.dot(target).coerceIn(-1.0, 1.0))
        val limit = Math.toRadians(maxTurnRateDegreesPerSecond.coerceAtMost(ceiling) / TICKS_PER_SECOND)
        if (angle <= limit) return target.scale(speed).add(inheritedMotion)

        var axis = forward.cross(target)
        if (axis.lengthSqr() <= MIN_VECTOR_LENGTH_SQUARED) {
            // An exact reversal has infinitely many valid axes; pick a stable perpendicular.
            axis = forward.cross(if (kotlin.math.abs(forward.y) < 0.9) Vec3(0.0, 1.0, 0.0)
                else Vec3(1.0, 0.0, 0.0))
        }
        axis = axis.normalize()
        val direction = forward.scale(cos(limit)).add(axis.cross(forward).scale(sin(limit))).normalize()
        val result = direction.scale(speed).add(inheritedMotion)
        return if (finite(result)) result else velocity
    }

    /** A time-based look-ahead remains ahead of fast missiles instead of chasing a 1.6-block point. */
    @JvmStatic
    fun targetOnRay(
        position: Vec3,
        rayOrigin: Vec3,
        rayDirection: Vec3,
        relativeSpeed: Double,
        lookAheadTicks: Int,
    ): Vec3? {
        if (!finite(position) || !finite(rayOrigin) || !finite(rayDirection) ||
            !rayDirection.lengthSqr().isFinite() || rayDirection.lengthSqr() <= MIN_VECTOR_LENGTH_SQUARED ||
            !relativeSpeed.isFinite() || relativeSpeed < 0.0 || lookAheadTicks <= 0
        ) return null
        val direction = rayDirection.normalize()
        val ahead = max(MIN_LOOK_AHEAD_BLOCKS,
            relativeSpeed * lookAheadTicks.coerceAtMost(GuidedPropulsionProfile.MAX_GUIDANCE_LOOK_AHEAD_TICKS))
        val along = position.subtract(rayOrigin).dot(direction)
        val target = rayOrigin.add(direction.scale(max(ahead, along + ahead)))
        val steering = target.subtract(position)
        return steering.takeIf { finite(it) && it.lengthSqr() > MIN_VECTOR_LENGTH_SQUARED }
    }

    private fun finite(vector: Vec3): Boolean =
        vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()
}
