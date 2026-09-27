package com.atsuishio.superbwarfare.tools.blast

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tunables of the Hopkinson-Cranz TNT-equivalent blast model. All distances are metres (1 block = 1 m),
 * charges are TNT-equivalent kilograms. Pure data; the server config builds one per explosion.
 */
data class BlastParameters(
    /** R = k * W^(1/3) for the visible fireball. */
    val fireballK: Double = 0.5,
    /** Severe collapse radius; used as the infantry / soft-vehicle damage zone. */
    val severeK: Double = 1.8,
    /** Moderate damage radius; only the visible shockwave travels this far. */
    val moderateK: Double = 3.5,
    /** Vehicles within this multiple of the fireball radius take true damage (charges >= [vehicleMinKg]). */
    val vehicleRadiusFactor: Double = 1.4,
    /** True damage per kilogram of TNT equivalent. */
    val vehicleDamagePerKg: Double = 3.0,
    /** Smallest charge that deals area damage to vehicles with armor hitboxes. */
    val vehicleMinKg: Double = 25.0,
    /** Smallest charge that produces a visible shockwave. */
    val shockwaveMinKg: Double = 1000.0,
    /** Damage to an unshielded infantry target inside the fireball. */
    val infantryCentreDamage: Double = 40.0,
    /** Fraction of the damage that still arrives behind complete cover. */
    val exposureFloor: Double = 0.1,
    /** Falloff damage magnitude for vehicles without armor hitboxes (aircraft, helicopters, trucks). */
    val softVehicleCentreDamage: Double = 40.0,
    /** Penetrator cylinder radius as a fraction of the fireball radius. */
    val penetratorRadiusFactor: Double = 0.5,
    /** Block-breaking force at the centre per cube-root kilogram, compared with block hardness. */
    val blockForcePerCbrtKg: Double = 8.0,
) {
    init {
        require(listOf(fireballK, severeK, moderateK, vehicleRadiusFactor, penetratorRadiusFactor)
            .all { it.isFinite() && it > 0.0 }) { "Blast radius factors must be finite and positive" }
        require(fireballK <= severeK && severeK <= moderateK) { "Blast radii must be ordered fireball <= severe <= moderate" }
        require(listOf(vehicleDamagePerKg, vehicleMinKg, shockwaveMinKg, infantryCentreDamage,
            softVehicleCentreDamage, blockForcePerCbrtKg).all { it.isFinite() && it >= 0.0 }) {
            "Blast damage parameters must be finite and non-negative"
        }
        require(exposureFloor.isFinite() && exposureFloor in 0.0..1.0) { "Exposure floor must be within 0..1" }
    }

    companion object {
        @JvmField
        val DEFAULT = BlastParameters()
    }
}

/** Radial classification of a point around a detonation. */
enum class BlastZone { FIREBALL, SEVERE, MODERATE, OUTSIDE }

data class BlastRadii(val fireball: Double, val severe: Double, val moderate: Double)

/**
 * A penetrator's fireball volume moved forward along its travel direction: a right circular cylinder
 * that starts at the detonation point ([ox], [oy], [oz]) and extends [length] along the unit axis.
 */
data class BlastCylinder(
    val ox: Double, val oy: Double, val oz: Double,
    val dx: Double, val dy: Double, val dz: Double,
    val radius: Double, val length: Double,
) {
    init {
        require(listOf(ox, oy, oz, dx, dy, dz, radius, length).all { it.isFinite() })
        require(radius > 0.0 && length > 0.0)
        require(abs(dx * dx + dy * dy + dz * dz - 1.0) < 1e-6) { "Cylinder axis must be a unit vector" }
    }

    val volume: Double get() = PI * radius * radius * length

    /** Axial parameter of the point's projection; not clamped. */
    fun axial(x: Double, y: Double, z: Double): Double = (x - ox) * dx + (y - oy) * dy + (z - oz) * dz

    fun contains(x: Double, y: Double, z: Double): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        val t = axial(x, y, z)
        if (t < 0.0 || t > length) return false
        val px = x - ox - dx * t
        val py = y - oy - dy * t
        val pz = z - oz - dz * t
        return px * px + py * py + pz * pz <= radius * radius
    }

    /** Smallest distance between the axis segment and an axis-aligned box (0 when they overlap). */
    fun axisDistanceToBox(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): Double {
        // The distance from a point moving linearly to a convex set is convex in the parameter.
        var lo = 0.0
        var hi = length
        repeat(60) {
            val m1 = lo + (hi - lo) / 3.0
            val m2 = hi - (hi - lo) / 3.0
            if (pointBoxDistance(m1, minX, minY, minZ, maxX, maxY, maxZ) <=
                pointBoxDistance(m2, minX, minY, minZ, maxX, maxY, maxZ)) hi = m2 else lo = m1
        }
        return pointBoxDistance((lo + hi) * 0.5, minX, minY, minZ, maxX, maxY, maxZ)
    }

    /**
     * Conservative box test: the box's axial extent must overlap the flat-capped segment and the box must
     * come within [radius] of the axis segment. Near the cap rims this can include a box a capsule would.
     */
    fun intersectsBox(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): Boolean {
        if (!listOf(minX, minY, minZ, maxX, maxY, maxZ).all { it.isFinite() }) return false
        // Axial extent of the box: the projection of an AABB onto a direction is centre +/- half-extent.
        val centre = axial((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5)
        val half = abs(dx) * (maxX - minX) * 0.5 + abs(dy) * (maxY - minY) * 0.5 + abs(dz) * (maxZ - minZ) * 0.5
        if (centre + half < 0.0 || centre - half > length) return false
        return axisDistanceToBox(minX, minY, minZ, maxX, maxY, maxZ) <= radius
    }

    /** Axis-aligned bounds of the whole cylinder: [minX, minY, minZ, maxX, maxY, maxZ]. */
    fun bounds(): DoubleArray {
        val ex = radius * sqrt(max(0.0, 1.0 - dx * dx))
        val ey = radius * sqrt(max(0.0, 1.0 - dy * dy))
        val ez = radius * sqrt(max(0.0, 1.0 - dz * dz))
        val endX = ox + dx * length
        val endY = oy + dy * length
        val endZ = oz + dz * length
        return doubleArrayOf(
            min(ox, endX) - ex, min(oy, endY) - ey, min(oz, endZ) - ez,
            max(ox, endX) + ex, max(oy, endY) + ey, max(oz, endZ) + ez,
        )
    }

    /** Farthest distance from the detonation point that any part of the cylinder reaches. */
    val reach: Double get() = sqrt(length * length + radius * radius)

    private fun pointBoxDistance(t: Double, minX: Double, minY: Double, minZ: Double,
                                 maxX: Double, maxY: Double, maxZ: Double): Double {
        val x = ox + dx * t
        val y = oy + dy * t
        val z = oz + dz * t
        val cx = x.coerceIn(minX, maxX) - x
        val cy = y.coerceIn(minY, maxY) - y
        val cz = z.coerceIn(minZ, maxZ) - z
        return sqrt(cx * cx + cy * cy + cz * cz)
    }
}

/**
 * Hopkinson-Cranz scaled distance: R = k * W^(1/3). Pure functions only; callers supply distances,
 * visibility fractions and random numbers so every rule is deterministic and unit-testable.
 */
object BlastModel {
    /**
     * Presentation radius thresholds (m) between the six native explosion recipes, MINI..GIANT. These are the
     * legacy authored-radius thresholds, so a TNT blast never looks smaller than the munition did before.
     */
    private val PRESENTATION_TIER_LIMITS = doubleArrayOf(2.0, 4.0, 7.0, 10.0, 20.0)
    private const val SHOCKWAVE_PARTICLES_PER_SQUARE_METRE = 0.5
    private const val SHOCKWAVE_MIN_PARTICLES = 64
    private val GOLDEN_ANGLE = PI * (3.0 - sqrt(5.0))
    private const val FIREBALL_MIN_PUFFS = 3
    private const val FIREBALL_MAX_PUFFS = 48
    private const val FIREBALL_PUFFS_PER_METRE = 5.0
    const val FIREBALL_SPRITE_EDGE = 0.8
    private const val FIREBALL_MIN_TICKS = 10
    private const val FIREBALL_MAX_TICKS = 32
    private const val FIREBALL_GROWTH_FRACTION = 0.2
    private const val FIREBALL_GLOW_FRACTION = 0.72
    private const val FIREBALL_MIN_AUDIENCE = 96.0
    private const val FIREBALL_MAX_AUDIENCE = 512.0
    /** progress, r, g, b, alpha */
    private val FIREBALL_COLOR_KEYS = arrayOf(
        doubleArrayOf(0.00, 1.00, 0.98, 0.88, 1.00),
        doubleArrayOf(0.12, 1.00, 0.84, 0.42, 1.00),
        doubleArrayOf(0.35, 1.00, 0.52, 0.12, 0.95),
        doubleArrayOf(0.60, 0.82, 0.26, 0.06, 0.85),
        doubleArrayOf(0.80, 0.34, 0.14, 0.08, 0.55),
        doubleArrayOf(1.00, 0.15, 0.12, 0.11, 0.00),
    )

    @JvmStatic
    fun valid(kg: Double): Boolean = kg.isFinite() && kg > 0.0

    /**
     * Gameplay size boost on top of Hopkinson-Cranz scaling, by charge: 20 kg and more +25 %, 100 kg and more +50 %,
     * 500 kg and more +100 %. It multiplies every blast radius (fireball, infantry/severe, moderate/shockwave), and
     * with them the vehicle true-damage sphere, the penetrator cylinder and the block-force reach, which all derive
     * from the fireball radius. Damage amounts per kg are unchanged.
     */
    @JvmField val RADIUS_BOOST_TIERS: List<Pair<Double, Double>> = listOf(500.0 to 2.0, 100.0 to 1.5, 20.0 to 1.25)

    @JvmStatic
    fun radiusBoost(kg: Double): Double {
        if (!valid(kg)) return 1.0
        for ((minKg, factor) in RADIUS_BOOST_TIERS) if (kg >= minKg) return factor
        return 1.0
    }

    /** Hopkinson-Cranz radius k * W^(1/3), without the gameplay boost. */
    @JvmStatic
    fun baseRadius(k: Double, kg: Double): Double = if (valid(kg) && k.isFinite() && k > 0.0) k * cbrt(kg) else 0.0

    @JvmStatic
    fun radius(k: Double, kg: Double): Double = baseRadius(k, kg) * radiusBoost(kg)

    /** Charge (kg) whose boosted radius with constant [k] is [radius]; the inverse of [radius]. 0 when invalid. */
    @JvmStatic
    fun chargeForRadius(radius: Double, k: Double): Double {
        if (!(radius > 0.0) || !radius.isFinite() || !(k > 0.0)) return 0.0
        val s = radius / k
        // Tiers from the heaviest down; the boost steps leave gaps between tiers, so exactly one tier fits.
        var upper = Double.POSITIVE_INFINITY
        for ((minKg, factor) in RADIUS_BOOST_TIERS) {
            val kg = (s / factor).let { it * it * it }
            if (kg >= minKg * (1.0 - 1e-9) && kg < upper * (1.0 + 1e-9)) return kg
            upper = minKg
        }
        return s * s * s
    }

    @JvmStatic
    fun radii(kg: Double, parameters: BlastParameters = BlastParameters.DEFAULT): BlastRadii = BlastRadii(
        radius(parameters.fireballK, kg), radius(parameters.severeK, kg), radius(parameters.moderateK, kg))

    @JvmStatic
    fun classify(distance: Double, radii: BlastRadii): BlastZone = when {
        !distance.isFinite() || distance < 0.0 -> BlastZone.OUTSIDE
        distance <= radii.fireball -> BlastZone.FIREBALL
        distance <= radii.severe -> BlastZone.SEVERE
        distance <= radii.moderate -> BlastZone.MODERATE
        else -> BlastZone.OUTSIDE
    }

    /**
     * 1 inside the fireball, falling to 0 at the severe-collapse edge. Uses SBW's legacy
     * (p^2 + p) / 2 curve so damage stays heavy near the centre and light near the edge.
     */
    @JvmStatic
    fun severeFalloff(distance: Double, radii: BlastRadii): Double {
        if (!distance.isFinite() || distance < 0.0 || radii.severe <= 0.0) return 0.0
        if (distance <= radii.fireball) return 1.0
        if (distance >= radii.severe) return 0.0
        val span = radii.severe - radii.fireball
        if (span <= 0.0) return 0.0
        val p = 1.0 - (distance - radii.fireball) / span
        return (p * p + p) * 0.5
    }

    /** Visible fraction with a floor: complete cover still lets [floor] of the blast through. */
    @JvmStatic
    fun exposure(seenFraction: Double, floor: Double): Double {
        val boundedFloor = if (floor.isFinite()) floor.coerceIn(0.0, 1.0) else 0.0
        val seen = if (seenFraction.isFinite()) seenFraction.coerceIn(0.0, 1.0) else 0.0
        return max(seen, boundedFloor)
    }

    @JvmStatic
    fun infantryDamage(distance: Double, seenFraction: Double, radii: BlastRadii,
                       parameters: BlastParameters = BlastParameters.DEFAULT): Double =
        parameters.infantryCentreDamage * severeFalloff(distance, radii) * exposure(seenFraction, parameters.exposureFloor)

    @JvmStatic
    fun softVehicleDamage(distance: Double, seenFraction: Double, radii: BlastRadii,
                          parameters: BlastParameters = BlastParameters.DEFAULT): Double =
        parameters.softVehicleCentreDamage * severeFalloff(distance, radii) * exposure(seenFraction, parameters.exposureFloor)

    /** Radius of the vehicle true-damage sphere (1.4 x fireball by default). */
    @JvmStatic
    fun vehicleTrueDamageRadius(radii: BlastRadii, parameters: BlastParameters = BlastParameters.DEFAULT): Double =
        parameters.vehicleRadiusFactor * radii.fireball

    /** Whether this charge is large enough to deal area damage to vehicles, including armored ones. */
    @JvmStatic
    fun damagesVehicles(kg: Double, parameters: BlastParameters = BlastParameters.DEFAULT): Boolean =
        valid(kg) && kg >= parameters.vehicleMinKg

    /** True damage (bypassing armor and damage modifiers) for a vehicle at [distance] from the centre. */
    @JvmStatic
    fun vehicleTrueDamage(kg: Double, distance: Double, radii: BlastRadii,
                          parameters: BlastParameters = BlastParameters.DEFAULT): Double {
        if (!damagesVehicles(kg, parameters) || !distance.isFinite() || distance < 0.0) return 0.0
        return if (distance <= vehicleTrueDamageRadius(radii, parameters)) parameters.vehicleDamagePerKg * kg else 0.0
    }

    /** True damage for a vehicle inside a penetrator cylinder (no occlusion, no distance falloff). */
    @JvmStatic
    fun vehicleTrueDamageInCylinder(kg: Double, parameters: BlastParameters = BlastParameters.DEFAULT): Double =
        if (damagesVehicles(kg, parameters)) parameters.vehicleDamagePerKg * kg else 0.0

    @JvmStatic
    fun producesShockwave(kg: Double, parameters: BlastParameters = BlastParameters.DEFAULT): Boolean =
        valid(kg) && kg >= parameters.shockwaveMinKg

    @JvmStatic
    fun sphereVolume(radius: Double): Double = 4.0 / 3.0 * PI * radius * radius * radius

    /**
     * The penetrator cylinder holding the fireball's volume: radius r = factor * R_f and
     * length L = (4/3 pi R_f^3) / (pi r^2) (about 5.33 R_f for the default factor 0.5).
     * Returns null when the direction or fireball is degenerate.
     */
    @JvmStatic
    fun penetratorCylinder(ox: Double, oy: Double, oz: Double, dirX: Double, dirY: Double, dirZ: Double,
                           fireballRadius: Double,
                           parameters: BlastParameters = BlastParameters.DEFAULT): BlastCylinder? {
        val lengthSquared = dirX * dirX + dirY * dirY + dirZ * dirZ
        if (!lengthSquared.isFinite() || lengthSquared < 1e-12 || !fireballRadius.isFinite() || fireballRadius <= 0.0) return null
        if (!ox.isFinite() || !oy.isFinite() || !oz.isFinite()) return null
        val inverse = 1.0 / sqrt(lengthSquared)
        val radius = parameters.penetratorRadiusFactor * fireballRadius
        val length = sphereVolume(fireballRadius) / (PI * radius * radius)
        return BlastCylinder(ox, oy, oz, dirX * inverse, dirY * inverse, dirZ * inverse, radius, length)
    }

    /**
     * Block-breaking force at [distance], compared with a block's hardness (metal hardness x3).
     * [randomUnit] in 0..1 adds the legacy +/-15% jitter; force is zero at the fireball edge.
     */
    @JvmStatic
    fun blockForce(kg: Double, distance: Double, fireballRadius: Double, randomUnit: Double,
                   parameters: BlastParameters = BlastParameters.DEFAULT): Double {
        if (!valid(kg) || !distance.isFinite() || distance < 0.0 || fireballRadius <= 0.0 || distance > fireballRadius) return 0.0
        val jitter = 0.85 + 0.3 * (if (randomUnit.isFinite()) randomUnit.coerceIn(0.0, 1.0) else 0.5)
        val ratio = distance / fireballRadius
        return parameters.blockForcePerCbrtKg * cbrt(kg) * jitter * (1.0 - ratio * ratio)
    }

    /** Uniform (unattenuated) block-breaking force inside a penetrator cylinder. */
    @JvmStatic
    fun cylinderBlockForce(kg: Double, parameters: BlastParameters = BlastParameters.DEFAULT): Double =
        if (valid(kg)) parameters.blockForcePerCbrtKg * cbrt(kg) else 0.0

    /** Entity query reach around the detonation point for every damaging rule. */
    @JvmStatic
    fun queryRadius(radii: BlastRadii, parameters: BlastParameters = BlastParameters.DEFAULT,
                    cylinder: BlastCylinder? = null): Double =
        max(max(radii.severe, vehicleTrueDamageRadius(radii, parameters)), cylinder?.reach ?: 0.0)

    /** 0..5 = MINI, SMALL, MEDIUM, LARGE, HUGE, GIANT native recipes, chosen by visible fireball size. */
    @JvmStatic
    fun presentationTier(radius: Double): Int {
        if (!radius.isFinite() || radius <= 0.0) return 0
        var tier = 0
        for (limit in PRESENTATION_TIER_LIMITS) if (radius >= limit) tier++
        return tier
    }

    /**
     * Radius (m) the explosion presentation (dust, flash, sound layers) is sized to: the damaging reach of the charge
     * (severe collapse radius), never less than the munition's authored legacy radius. The fireball itself is drawn
     * separately at exactly its own radius.
     */
    @JvmStatic
    fun presentationRadius(authoredRadius: Double, radii: BlastRadii): Double {
        val authored = if (authoredRadius.isFinite() && authoredRadius > 0.0) authoredRadius else 0.0
        return max(authored, radii.severe)
    }

    /** Particle count for an expanding shell reaching [moderateRadius], capped by [budget]. */
    @JvmStatic
    fun shockwaveParticleCount(moderateRadius: Double, budget: Int): Int {
        if (budget <= 0 || !moderateRadius.isFinite() || moderateRadius <= 0.0) return 0
        val area = 2.0 * PI * moderateRadius * moderateRadius
        val wanted = ceil(area * SHOCKWAVE_PARTICLES_PER_SQUARE_METRE)
        val floor = min(SHOCKWAVE_MIN_PARTICLES, budget)
        return wanted.coerceIn(floor.toDouble(), budget.toDouble()).toInt()
    }

    /** Front radius at [progress] (0..1): fast at first, decelerating as it approaches [to]. */
    @JvmStatic
    fun shockwaveRadiusAt(progress: Double, from: Double, to: Double): Double {
        val t = if (progress.isFinite()) progress.coerceIn(0.0, 1.0) else 1.0
        val eased = 1.0 - (1.0 - t) * (1.0 - t)
        return from + (to - from) * eased
    }

    // ---- Visible fireball (presentation of radii.fireball) ----

    /** Puffs that make up one visible fireball of [radius] m: cheap for shells, dense for heavy bombs. */
    @JvmStatic
    fun fireballPuffCount(radius: Double, budget: Int): Int {
        if (budget <= 0 || !radius.isFinite() || radius <= 0.0) return 0
        val wanted = ceil(FIREBALL_MIN_PUFFS + FIREBALL_PUFFS_PER_METRE * radius)
            .coerceAtMost(FIREBALL_MAX_PUFFS.toDouble()).toInt()
        return min(wanted, budget)
    }

    /** Half-size of one puff quad (m). Few puffs overlap more so a small fireball still reads as one ball. */
    @JvmStatic
    fun fireballPuffHalfSize(radius: Double, count: Int): Double {
        if (!radius.isFinite() || radius <= 0.0 || count <= 0) return 0.0
        val share = if (count <= FIREBALL_MIN_PUFFS + 1) 0.62 else 0.5
        return radius * share
    }

    /**
     * Farthest a puff centre may sit from the blast centre. The soft sprite's visible edge is about
     * [FIREBALL_SPRITE_EDGE] of its half-size, so centre + edge never passes the fireball radius.
     */
    @JvmStatic
    fun fireballPuffReach(radius: Double, halfSize: Double): Double =
        max(0.0, radius - FIREBALL_SPRITE_EDGE * halfSize)

    /** Visible fireball lifetime in ticks: a 30 mm shell flashes, a FAB-5000 burns for over a second. */
    @JvmStatic
    fun fireballLifetimeTicks(radius: Double): Int {
        if (!radius.isFinite() || radius <= 0.0) return FIREBALL_MIN_TICKS
        return (9.0 + 6.0 * sqrt(radius)).toInt().coerceIn(FIREBALL_MIN_TICKS, FIREBALL_MAX_TICKS)
    }

    /** Fraction (0..1) of full size at [progress]: the ball reaches its radius within the first fifth of its life. */
    @JvmStatic
    fun fireballExpansionAt(progress: Double): Double {
        val t = if (progress.isFinite()) (progress / FIREBALL_GROWTH_FRACTION).coerceIn(0.0, 1.0) else 1.0
        val inverse = 1.0 - t
        return 1.0 - inverse * inverse * inverse
    }

    /** White-hot, orange, deep red, then soot: writes r, g, b, alpha for [progress] (0..1) into [out]. */
    @JvmStatic
    fun fireballColorAt(progress: Double, out: FloatArray) {
        require(out.size >= 4)
        val p = if (progress.isFinite()) progress.coerceIn(0.0, 1.0) else 1.0
        var index = 0
        while (index < FIREBALL_COLOR_KEYS.size - 2 && p > FIREBALL_COLOR_KEYS[index + 1][0]) index++
        val a = FIREBALL_COLOR_KEYS[index]
        val b = FIREBALL_COLOR_KEYS[index + 1]
        val span = b[0] - a[0]
        val f = if (span <= 0.0) 1.0 else ((p - a[0]) / span).coerceIn(0.0, 1.0)
        for (channel in 0 until 4) out[channel] = (a[channel + 1] + (b[channel + 1] - a[channel + 1]) * f).toFloat()
    }

    /** True while the fireball glows (full-bright); afterwards it is lit like smoke. */
    @JvmStatic
    fun fireballGlowing(progress: Double): Boolean = progress.isFinite() && progress < FIREBALL_GLOW_FRACTION

    /**
     * Uniform point inside the unit ball from three uniform randoms in [0, 1): direction from [u1], [u2], distance
     * cbrt([u3]). With [upperOnly] the point is mirrored into y >= 0 (a ground burst's hemisphere).
     */
    @JvmStatic
    fun fireballPuffOffset(u1: Double, u2: Double, u3: Double, upperOnly: Boolean, out: DoubleArray) {
        require(out.size >= 3)
        val z = 2.0 * u1.coerceIn(0.0, 1.0) - 1.0
        val angle = 2.0 * PI * u2.coerceIn(0.0, 1.0)
        val horizontal = sqrt(max(0.0, 1.0 - z * z))
        val distance = cbrt(u3.coerceIn(0.0, 1.0))
        val y = horizontal * sin(angle) * distance
        out[0] = horizontal * cos(angle) * distance
        out[1] = if (upperOnly) abs(y) else y
        out[2] = z * distance
    }

    /** Players farther than this (m) never see a fireball of [radius]; small shells stay local. */
    @JvmStatic
    fun fireballAudienceRange(radius: Double): Double {
        if (!radius.isFinite() || radius <= 0.0) return FIREBALL_MIN_AUDIENCE
        return (FIREBALL_MIN_AUDIENCE + 48.0 * radius).coerceAtMost(FIREBALL_MAX_AUDIENCE)
    }

    /**
     * Unit direction [index] of [count] spread evenly over the upper hemisphere (y >= 0), rotated by [phase].
     * Writes x, y, z into [out] to avoid per-particle allocation.
     */
    @JvmStatic
    fun hemisphereDirection(index: Int, count: Int, phase: Double, out: DoubleArray) {
        require(count > 0 && index in 0 until count && out.size >= 3)
        val y = (index + 0.5) / count
        val horizontal = sqrt(max(0.0, 1.0 - y * y))
        val angle = phase + GOLDEN_ANGLE * index
        out[0] = horizontal * cos(angle)
        out[1] = y
        out[2] = horizontal * sin(angle)
    }
}
