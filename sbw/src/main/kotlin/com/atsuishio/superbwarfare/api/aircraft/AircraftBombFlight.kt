package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileMotion
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** Bomb release and air motion shared by the live entity and the advisory impact predictor. */
object AircraftBombFlight {
    @JvmStatic fun launchOrigin(vehicle: VehicleEntity, local: Vec3): Vec3 {
        val point = vehicle.getVehicleTransform(1f).transformPosition(Vector3d(local.x, local.y, local.z))
        return Vec3(point.x, point.y, point.z)
    }

    @JvmStatic fun initialMotion(platformMotion: Vec3): Vec3 = platformMotion.add(0.0, -0.04, 0.0)

    /** Normal bombs retain their aircraft's forward speed; a deployed retarder loses it faster. */
    @JvmStatic fun horizontalDragFactor(multiplier: Double): Double =
        (1.0 - 0.0025 * multiplier.coerceIn(0.0, 20.0)).coerceIn(0.8, 1.0)

    @JvmStatic fun applyHorizontalDrag(motion: Vec3, multiplier: Double): Vec3 {
        val factor = horizontalDragFactor(multiplier)
        return Vec3(motion.x * factor, motion.y, motion.z * factor)
    }

    @JvmStatic fun afterPredictedAirStep(motion: Vec3, gravity: Double, multiplier: Double): Vec3 =
        applyHorizontalDrag(NominalProjectileMotion.afterFastThrowableAirStep(motion, gravity), multiplier)

    /**
     * Glide performance of a guided bomb (owner 2026-09-30): lift-to-drag ratio at [bestSpeed] (blocks/tick) and the
     * most lift it can pull there in g ([maxG]); lift falls with the square of speed below it.
     */
    data class Glide(val liftToDrag: Double, val bestSpeed: Double, val maxG: Double) {
        init {
            require(liftToDrag.isFinite() && liftToDrag in 0.5..20.0)
            require(bestSpeed.isFinite() && bestSpeed in 1.0..40.0)
            require(maxG.isFinite() && maxG in 0.5..10.0)
        }

        companion object {
            const val LD_KEY = "BvpBombLiftToDrag"
            const val SPEED_KEY = "BvpBombBestGlideSpeed"
            const val MAX_G_KEY = "BvpBombMaxG"
            /** Store keys in the Bomb block (optional). */
            const val LD_JSON = "LiftToDrag"
            const val SPEED_JSON = "BestGlideSpeed"
            const val MAX_G_JSON = "MaxG"
            /**
             * Paveway / JDAM class: tail kit and small strakes. L/D 3 at 432 km/h, 2.5 g there (owner 2026-10-02:
             * laser bombs "lost like all of their ability to turn": at the old 648 km/h best speed a bomb released at
             * the usual 300-450 km/h could not even hold 1 g, so it never turned onto the spot). It now turns about
             * 1 degree a tick at 324 km/h and its full 2 degrees from about 400 km/h, and still pays for every pull.
             */
            val DEFAULT = Glide(3.0, 6.0, 2.5)
            /**
             * A TV glide bomb without its own figures: L/D 4.5 at 324 km/h, 2.5 g there (it holds 1 g down to about
             * 205 km/h, so a release at the aircraft's usual 300-450 km/h glides instead of falling away).
             */
            val TV_DEFAULT = Glide(4.5, 4.5, 2.5)

            @JvmStatic @JvmOverloads fun of(data: net.minecraft.nbt.CompoundTag, fallback: Glide = DEFAULT): Glide = runCatching {
                Glide(if (data.contains(LD_KEY)) data.getDouble(LD_KEY) else fallback.liftToDrag,
                    if (data.contains(SPEED_KEY)) data.getDouble(SPEED_KEY) else fallback.bestSpeed,
                    if (data.contains(MAX_G_KEY)) data.getDouble(MAX_G_KEY) else fallback.maxG)
            }.getOrDefault(fallback)

            @JvmStatic fun store(data: net.minecraft.nbt.CompoundTag, bomb: com.google.gson.JsonObject) {
                bomb[LD_JSON]?.let { data.putDouble(LD_KEY, it.asDouble) }
                bomb[SPEED_JSON]?.let { data.putDouble(SPEED_KEY, it.asDouble) }
                bomb[MAX_G_JSON]?.let { data.putDouble(MAX_G_KEY, it.asDouble) }
            }
        }
    }

    /**
     * Steepest climb a guided bomb is commanded to: level. It can stretch a glide by holding level while it is fast,
     * but it is never steered upward (owner 2026-09-30: "I should hardly ever be able to climb").
     */
    const val MAX_CLIMB_SINE = 0.0

    /** [direction] (unit) with its climb limited to [MAX_CLIMB_SINE]. */
    @JvmStatic fun limitClimb(direction: Vec3): Vec3 {
        if (direction.y <= MAX_CLIMB_SINE) return direction
        val horizontal = kotlin.math.sqrt(direction.x * direction.x + direction.z * direction.z)
        if (horizontal < 1.0E-6) return Vec3(0.0, MAX_CLIMB_SINE, kotlin.math.sqrt(1 - MAX_CLIMB_SINE * MAX_CLIMB_SINE))
        val scale = kotlin.math.sqrt(1 - MAX_CLIMB_SINE * MAX_CLIMB_SINE) / horizontal
        return Vec3(direction.x * scale, MAX_CLIMB_SINE, direction.z * scale)
    }

    /**
     * TV glide bombs fly no faster than 400 km/h (blocks/tick) unless released faster, and then bleed the excess
     * ([TV_OVERSPEED_RETAIN] of it kept per tick, half gone in about a second).
     */
    const val TV_SPEED_CAP = 400.0 / 72.0
    const val TV_OVERSPEED_RETAIN = 0.97
    /** How much steeper than the best glide angle (sine) a TV bomb noses down per unit of missing speed (share of v*). */
    const val TV_SPEED_HOLD_GAIN = 0.6
    /** Steepest descent (sine, about 44 degrees) a TV bomb noses down to only to regain speed. */
    const val TV_MAX_RECOVERY_SINE = 0.7
    /** TV bombs stay in the air this long (ticks) before they self-destruct: a long glide from altitude. */
    const val TV_LIFETIME_TICKS = 1800

    /**
     * Where a TV glide bomb steers (owner 2026-10-02: "tv guided bombs should at least try to glide"). It heads for
     * [aim] (or, without one, keeps its present heading) on its glide slope: the best glide angle 1 : L/D, steeper
     * when it is slower than its best glide speed (to regain speed, never past [TV_MAX_RECOVERY_SINE]) and shallower,
     * down to level, when it is faster (stretching the glide with the spare energy). When the aim point lies at or
     * below that slope it can be reached, and the bomb flies straight down the line at it. It is never commanded
     * above level. Returns a unit vector, or null when it has no horizontal heading at all.
     */
    @JvmStatic fun tvGlideDirection(position: Vec3, velocity: Vec3, aim: Vec3?, glide: Glide): Vec3? {
        val speed = velocity.length()
        if (!speed.isFinite()) return null
        var heading = aim?.subtract(position)?.let { Vec3(it.x, 0.0, it.z) }
        if (heading == null || heading.lengthSqr() < 1.0E-6) heading = Vec3(velocity.x, 0.0, velocity.z)
        if (heading.lengthSqr() < 1.0E-10) return null
        heading = heading.normalize()
        val bestSine = 1.0 / kotlin.math.sqrt(1.0 + glide.liftToDrag * glide.liftToDrag)
        val descent = (bestSine + TV_SPEED_HOLD_GAIN * (glide.bestSpeed - speed) / glide.bestSpeed)
            .coerceIn(0.0, maxOf(bestSine, TV_MAX_RECOVERY_SINE))
        if (aim != null) {
            val line = aim.subtract(position)
            if (line.lengthSqr() > 1.0E-6) {
                val unit = line.normalize()
                if (-unit.y >= descent) return unit
            }
        }
        val level = kotlin.math.sqrt(1.0 - descent * descent)
        return Vec3(heading.x * level, -descent, heading.z * level)
    }

    /**
     * Where a laser or GPS bomb steers toward [target]. While the target lies below its glide slope (1 : L/D) it can
     * reach it and flies straight down the line at it. Farther out it keeps a shallow lofted approach, aiming above
     * the target (never above level) to stretch the glide. (The loft used to stay on to the end, so every bomb passed
     * over the spot and landed 10-20 blocks long.) Null when it is on top of the target.
     */
    @JvmStatic fun guidedDirection(position: Vec3, target: Vec3, glide: Glide): Vec3? {
        val line = target.subtract(position)
        if (line.lengthSqr() <= 1.0) return null
        val unit = line.normalize()
        val bestSine = 1.0 / kotlin.math.sqrt(1.0 + glide.liftToDrag * glide.liftToDrag)
        if (-unit.y >= bestSine) return unit
        val horizontalRange = kotlin.math.hypot(line.x, line.z)
        val height = (-line.y).coerceAtLeast(0.0)
        val overhead = minOf(35.0, height * 0.5, horizontalRange * 0.8)
        val aim = line.add(0.0, overhead, 0.0)
        if (aim.lengthSqr() <= 1.0) return null
        return limitClimb(aim.normalize())
    }

    /**
     * The fastest a bomb with [glide] can turn at [speed] (blocks/tick) while it holds its path against [gravity]:
     * radians per tick, from the lift it has left over after holding 1 g.
     */
    @JvmStatic fun envelopeTurn(speed: Double, gravity: Double, glide: Glide): Double {
        if (!(speed > 1.0E-6) || !speed.isFinite()) return 0.0
        val g = if (gravity.isFinite()) gravity.coerceAtLeast(0.0) else 0.0
        val ratio = speed / glide.bestSpeed
        val liftMax = glide.maxG * g * ratio * ratio
        val spare = liftMax * liftMax - g * g
        return if (spare > 0.0) kotlin.math.sqrt(spare) / speed else 0.0
    }

    /**
     * Owner 2026-10-02 ("TV guided missiles/bombs need to turn WAY slower, max turn rate of whatever the missile/bomb
     * is originally based off of"): a TV bomb turns no faster than the laser-kit bomb of its class ([Glide.DEFAULT])
     * at the same speed, and never faster than its own [maxTurn] (radians per tick). Its wings still give it its long
     * glide; they do not make it more agile than a Paveway.
     */
    @JvmStatic fun tvMaxTurn(speed: Double, gravity: Double, maxTurn: Double): Double =
        minOf(maxTurn, envelopeTurn(speed, gravity, Glide.DEFAULT))

    /** [velocity] (this tick's) held to the TV speed cap, given the speed at the start of the tick. */
    @JvmStatic fun limitTvSpeed(velocity: Vec3, previousSpeed: Double): Vec3 {
        val speed = velocity.length()
        val previous = if (previousSpeed.isFinite()) previousSpeed else 0.0
        val ceiling = maxOf(TV_SPEED_CAP, TV_SPEED_CAP + (previous - TV_SPEED_CAP) * TV_OVERSPEED_RETAIN)
        return if (speed > ceiling && speed.isFinite()) velocity.scale(ceiling / speed) else velocity
    }

    /**
     * One tick of guided glide. [start] is the velocity at the start of the tick (blocks/tick, before this tick's
     * gravity), [gravity] blocks/tick². The fins ask for a turn toward [desired] of at most [maxTurn] radians; the
     * lift that turn and holding the path against gravity need is capped at maxG x g x (v / bestSpeed)²; gravity
     * along the path speeds a dive and slows a climb; parasite drag k v² and induced drag K L² / v² slow it
     * (k = g / (2 L/D v*²), K = v*² / (2 L/D g): a steady glide at v* sinks at 1 : L/D).
     */
    @JvmStatic fun glideStep(start: Vec3, gravity: Double, desired: Vec3?, maxTurn: Double, glide: Glide): Vec3 {
        val speed0 = start.length()
        val g = if (gravity.isFinite()) gravity.coerceAtLeast(0.0) else 0.0
        if (!(speed0 > 1.0E-6) || !speed0.isFinite()) return start.add(0.0, -g, 0.0)
        val dir = start.scale(1.0 / speed0)
        // gravity along the path
        var speed = (speed0 - g * dir.y).coerceAtLeast(0.0)
        // gravity across the path, which lift must cancel to hold it
        val down = Vec3(0.0, -g, 0.0)
        val across = down.subtract(dir.scale(down.dot(dir)))
        var turned = dir
        if (desired != null && maxTurn > 0.0) {
            val angle = kotlin.math.acos(dir.dot(desired).coerceIn(-1.0, 1.0))
            if (angle > 1.0E-6) {
                val blend = (maxTurn / angle).coerceAtMost(1.0)
                turned = dir.scale(1.0 - blend).add(desired.scale(blend)).normalize()
            }
        }
        val wanted = turned.subtract(dir).scale(speed)
        val liftRequired = wanted.subtract(across)
        val ratio = speed / glide.bestSpeed
        val liftMax = glide.maxG * g * ratio * ratio
        val required = liftRequired.length()
        val lift = if (required > liftMax && required > 1.0E-12) liftRequired.scale(liftMax / required) else liftRequired
        val newDirection = dir.scale(speed).add(across).add(lift)
        if (newDirection.lengthSqr() < 1.0E-12) return start.add(0.0, -g, 0.0)
        val k = g / (2.0 * glide.liftToDrag * glide.bestSpeed * glide.bestSpeed)
        val bigK = glide.bestSpeed * glide.bestSpeed / (2.0 * glide.liftToDrag * kotlin.math.max(g, 1.0E-9))
        val a = lift.length()
        val drag = k * speed * speed + bigK * a * a / kotlin.math.max(speed * speed, 1.0E-4)
        speed = (speed - drag).coerceAtLeast(0.0)
        return newDirection.normalize().scale(speed)
    }
}
