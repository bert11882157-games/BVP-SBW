package com.atsuishio.superbwarfare.client.renderer.vehicle

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A closed track belt traced around the outer edges of a side's wheels, built at runtime from the wheels themselves
 * (after the track-path system of Superb Modern Combat: circles from the wheel bones, joined by the outer common
 * tangents of neighbouring wheels and arcs around each wheel, with the length measured instead of authored).
 *
 * Coordinates are the model's side plane: `z` forward/back and `y` up, in model pixels. The belt runs clockwise
 * seen with +z to the right and +y up, i.e. along the top from -z to +z, the same sense as the authored paths.
 * `radii` are where the belt's centre line runs (wheel edge plus the link's plate depth).
 *
 * Wheel order: [fromWheels] with `ordered = false` uses the convex hull of the wheels (every wheel the belt can
 * reach from outside; return rollers under a straight top run are skipped). With `ordered = true` the wheels are
 * visited in the given order, so a belt can sag onto rollers between higher wheels.
 */
class TrackBeltPath private constructor(
    private val segZ0: FloatArray, private val segY0: FloatArray,
    private val segZ1: FloatArray, private val segY1: FloatArray,
    private val start: FloatArray,
    val length: Float,
    /** Index into the input wheels for every wheel the belt touches, in belt order. */
    val touched: IntArray,
) {
    val segments: Int get() = segZ0.size

    /** Point on the belt [distance] model pixels from its start (wrapped): writes z, y into [out] at [offset]. */
    fun pointInto(distance: Float, out: FloatArray, offset: Int) {
        var d = distance % length
        if (d < 0F) d += length
        var low = 0
        var high = segZ0.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (start[mid] <= d) low = mid else high = mid - 1
        }
        val segLength = (if (low + 1 < start.size) start[low + 1] else length) - start[low]
        val t = if (segLength > 1.0E-6F) ((d - start[low]) / segLength).coerceIn(0F, 1F) else 0F
        out[offset] = segZ0[low] + (segZ1[low] - segZ0[low]) * t
        out[offset + 1] = segY0[low] + (segY1[low] - segY0[low]) * t
    }

    companion object {
        /** Degrees of arc per straight piece around a wheel. */
        private const val ARC_STEP = PI.toFloat() / 36F

        /**
         * Builds the belt. [z], [y], [radii] per wheel (model pixels). Returns null for fewer than two usable wheels
         * or a degenerate layout.
         */
        @JvmStatic
        fun fromWheels(z: FloatArray, y: FloatArray, radii: FloatArray, ordered: Boolean): TrackBeltPath? {
            val n = z.size
            require(y.size == n && radii.size == n)
            val usable = (0 until n).filter { z[it].isFinite() && y[it].isFinite() && radii[it].isFinite() && radii[it] > 0F }
            if (usable.size < 2) return null
            val loop = if (ordered) orientClockwise(usable, z, y) else hull(usable, z, y, radii)
            if (loop.size < 2) return null
            return build(loop, z, y, radii)
        }

        /** Keeps the given order but runs it clockwise (shoelace sign). */
        private fun orientClockwise(order: List<Int>, z: FloatArray, y: FloatArray): List<Int> {
            var area = 0.0
            for (k in order.indices) {
                val a = order[k]; val b = order[(k + 1) % order.size]
                area += (z[b] - z[a]).toDouble() * (y[b] + y[a])
            }
            // shoelace with this sign is positive for clockwise (z right, y up)
            return if (area >= 0.0) order else order.reversed()
        }

        /** Wheels on the convex hull of all wheel discs, clockwise, starting with the front-most (lowest z). */
        private fun hull(ids: List<Int>, z: FloatArray, y: FloatArray, r: FloatArray): List<Int> {
            val samples = 72
            val points = ArrayList<Triple<Double, Double, Int>>(ids.size * samples)
            for (i in ids) for (k in 0 until samples) {
                val a = 2.0 * PI * k / samples
                points.add(Triple(z[i] + r[i] * cos(a), y[i] + r[i] * sin(a), i))
            }
            points.sortWith(compareBy<Triple<Double, Double, Int>> { it.first }.thenBy { it.second })
            fun cross(o: Triple<Double, Double, Int>, a: Triple<Double, Double, Int>, b: Triple<Double, Double, Int>) =
                (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
            val lower = ArrayList<Triple<Double, Double, Int>>()
            for (p in points) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
                lower.add(p)
            }
            val upper = ArrayList<Triple<Double, Double, Int>>()
            for (p in points.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
                upper.add(p)
            }
            // counter-clockwise hull (lower then upper); reverse for clockwise
            val ccw = lower.dropLast(1) + upper.dropLast(1)
            val order = ArrayList<Int>()
            for (p in ccw.asReversed()) if (order.isEmpty() || order.last() != p.third) order.add(p.third)
            while (order.size > 1 && order.first() == order.last()) order.removeAt(order.size - 1)
            // a wheel can appear twice only in degenerate layouts; keep the first visit
            val seen = HashSet<Int>()
            val unique = order.filter { seen.add(it) }
            val front = unique.indices.minByOrNull { z[unique[it]] } ?: 0
            return unique.drop(front) + unique.take(front)
        }

        private fun build(loop: List<Int>, z: FloatArray, y: FloatArray, r: FloatArray): TrackBeltPath? {
            val m = loop.size
            // Outer common tangent from wheel a to wheel b (clockwise belt: the belt lies on the left of a->b
            // when looking along +z with y up, i.e. the outward normal is the direction rotated +90 degrees).
            val tz0 = FloatArray(m); val ty0 = FloatArray(m); val tz1 = FloatArray(m); val ty1 = FloatArray(m)
            for (k in 0 until m) {
                val a = loop[k]; val b = loop[(k + 1) % m]
                val dz = z[b] - z[a]; val dy = y[b] - y[a]
                val dist = hypot(dz, dy)
                if (dist < 1.0E-4F) return null
                val base = atan2(dy, dz)
                // the tangent's outward normal n satisfies n.(b - a) = r[a] - r[b]
                val off = asin(((r[b] - r[a]) / dist).coerceIn(-1F, 1F))
                val normal = base + off + PI.toFloat() / 2F
                tz0[k] = z[a] + cos(normal) * r[a]; ty0[k] = y[a] + sin(normal) * r[a]
                tz1[k] = z[b] + cos(normal) * r[b]; ty1[k] = y[b] + sin(normal) * r[b]
            }
            val pz = ArrayList<Float>(); val py = ArrayList<Float>()
            fun add(zv: Float, yv: Float) {
                if (pz.isNotEmpty() && hypot(zv - pz.last(), yv - py.last()) < 1.0E-4F) return
                pz.add(zv); py.add(yv)
            }
            for (k in 0 until m) {
                // straight run leaving wheel k, then the arc around wheel k+1 (clockwise: decreasing angle)
                add(tz0[k], ty0[k])
                add(tz1[k], ty1[k])
                val w = loop[(k + 1) % m]
                val from = atan2(ty1[k] - y[w], tz1[k] - z[w])
                val next = (k + 1) % m
                val to = atan2(ty0[next] - y[w], tz0[next] - z[w])
                var sweep = to - from
                while (sweep > 0F) sweep -= 2F * PI.toFloat()
                while (sweep <= -2F * PI.toFloat()) sweep += 2F * PI.toFloat()
                val steps = kotlin.math.max(1, kotlin.math.ceil(abs(sweep) / ARC_STEP).toInt())
                for (s in 1 until steps) {
                    val angle = from + sweep * s / steps
                    add(z[w] + cos(angle) * r[w], y[w] + sin(angle) * r[w])
                }
            }
            if (pz.size >= 2 && hypot(pz.first() - pz.last(), py.first() - py.last()) < 1.0E-4F) {
                pz.removeAt(pz.size - 1); py.removeAt(py.size - 1)
            }
            val count = pz.size
            if (count < 3) return null
            val z0 = FloatArray(count); val y0 = FloatArray(count); val z1 = FloatArray(count); val y1 = FloatArray(count)
            val start = FloatArray(count)
            var total = 0F
            for (i in 0 until count) {
                val j = (i + 1) % count
                z0[i] = pz[i]; y0[i] = py[i]; z1[i] = pz[j]; y1[i] = py[j]
                start[i] = total
                total += hypot(z1[i] - z0[i], y1[i] - y0[i])
            }
            if (!(total > 1.0E-3F)) return null
            return TrackBeltPath(z0, y0, z1, y1, start, total, loop.toIntArray())
        }
    }
}

/**
 * Link placement on a [TrackBeltPath]: `count` evenly spaced links (the count follows from the belt length and the
 * link pitch unless capped by the links the model provides), each centred on the chord between the points half a
 * spacing before and after it, turned to that chord and stretched along it so neighbouring links meet; the outside
 * of a bend is covered by the plate's half thickness as in [RunningGearTrackEvaluator].
 */
class TrackBeltLinks(val path: TrackBeltPath, val count: Int, private val pitch: Float, private val halfThickness: Float) {
    val spacing: Float = path.length / count
    private val scratch = FloatArray(8)

    /**
     * Pose of link [index] when the belt has travelled [travel] model pixels: writes centre z, centre y, rotation
     * about x in degrees (same convention as the authored paths) and the longitudinal scale into [out].
     */
    fun linkInto(index: Int, travel: Float, out: FloatArray, offset: Int) {
        val s = index * spacing + travel
        val h = spacing * 0.5F
        path.pointInto(s - h, scratch, 0)
        path.pointInto(s + h, scratch, 2)
        path.pointInto(s - 3F * h, scratch, 4)
        path.pointInto(s + 3F * h, scratch, 6)
        val sz = scratch[0]; val sy = scratch[1]; val ez = scratch[2]; val ey = scratch[3]
        val dz = ez - sz; val dy = ey - sy
        val chord = hypot(dz, dy)
        val rotation = chordDegrees(dy, dz)
        var startExt = 0F
        var endExt = 0F
        if (halfThickness > 0F && chord > 1.0E-6F) {
            startExt = joint(chordDegrees(sy - scratch[5], sz - scratch[4]), rotation)
            endExt = joint(rotation, chordDegrees(scratch[7] - ey, scratch[6] - ez))
        }
        val shift = if (chord > 1.0E-6F) (endExt - startExt) / (2F * chord) else 0F
        out[offset] = (sz + ez) * 0.5F + dz * shift
        out[offset + 1] = (sy + ey) * 0.5F + dy * shift
        out[offset + 2] = rotation
        out[offset + 3] = if (pitch > 1.0E-6F) (chord + startExt + endExt) / pitch else 1F
    }

    private fun joint(fromDegrees: Float, toDegrees: Float): Float {
        var delta = (toDegrees - fromDegrees) % 360F
        if (delta > 180F) delta -= 360F
        if (delta < -180F) delta += 360F
        val half = Math.toRadians(abs(delta) * 0.5)
        return (halfThickness * kotlin.math.tan(half).coerceAtMost(4.0)).toFloat()
    }

    private fun chordDegrees(deltaY: Float, deltaZ: Float): Float =
        Math.toDegrees(atan2(-deltaY.toDouble(), deltaZ.toDouble())).toFloat()

    companion object {
        /** Links for a belt of [length] with link [pitch], at most [available] (the model's link bones). */
        @JvmStatic
        fun countFor(length: Float, pitch: Float, available: Int): Int {
            if (!(pitch > 0F) || available <= 0) return available.coerceAtLeast(0)
            return Math.round(length / pitch).coerceIn(3, available)
        }
    }
}
