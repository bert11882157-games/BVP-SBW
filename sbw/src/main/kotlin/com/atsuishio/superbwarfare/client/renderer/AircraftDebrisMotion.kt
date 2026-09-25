package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.api.vehicle.flight.WreckDebrisPhysics
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Client-only inertial debris. Collision is supplied by a loaded-terrain sweep, never chunk loading. */
class AircraftDebrisMotion(position: Vec3, velocity: Vec3, orientation: Quaternionf,
                           private val spin: Vec3, private val phase: Double,
                           private val gravity: Double = 9.80665 / 400.0,
                           private val bounceLimit: Int = 0,
                           private val halfExtents: Vec3? = null,
                           private val rest: WreckDebrisPhysics.Rest = WreckDebrisPhysics.Rest.ANY,
                           private val friction: Double = WreckDebrisPhysics.FUSELAGE_FRICTION) {
    var previousPosition = position; private set
    var position = position; private set
    var velocity = velocity; private set
    var previousOrientation = Quaternionf(orientation); private set
    var orientation = Quaternionf(orientation); private set
    var grounded = false; private set
    var age = 0; private set
    var groundedTicks = 0; private set
    var bounces = 0; private set
    var impacted = false; private set
    /** World point where the piece scrapes the terrain this tick, null while airborne or unknown. */
    var scrapePoint: Vec3? = null; private set
    var previousScrapePoint: Vec3? = null; private set
    /** Horizontal radius of the load-bearing contact patch. */
    var scrapeRadius = 0.0; private set
    /** Probe measurements: ground travel, deepest measured overlap, ticks a slide was held in place, rest age. */
    var slideDistance = 0.0; private set
    var maxPenetration = 0.0; private set
    var pinnedTicks = 0; private set
    var restAge = -1; private set
    private var tipRate = 0f
    private var rolling = false
    private var lastLean: Vec3? = null
    private var quietTicks = 0
    val expired: Boolean get() = groundedTicks >= WreckDebrisPhysics.FRAGMENT_GROUND_TICKS || age >= 1200
    data class Contact(val position: Vec3, val normal: Vec3, val point: Vec3? = null)

    /** Downward terrain query used for support, step-up and depenetration. */
    fun interface Surface {
        /** Top of the first upward face within [depth] below [from]; [BURIED] when [from] is in a solid. */
        fun below(from: Vec3, depth: Double): Double?
    }

    /** Local support samples: box corners and edge midpoints, so long edges cannot straddle a ridge unseen. */
    val supports: List<Vec3> = halfExtents?.let { samples(it, 2) } ?: listOf(Vec3.ZERO)
    /** Center and corners for the translation sweep. */
    val sweepSamples: List<Vec3> = listOf(Vec3.ZERO) + (halfExtents?.let { samples(it, 3) } ?: emptyList())

    fun offset(local: Vec3, rotation: Quaternionf = orientation): Vec3 {
        val turned = rotation.transform(Vector3f(local.x.toFloat(), local.y.toFloat(), local.z.toFloat()))
        return Vec3(turned.x.toDouble(), turned.y.toDouble(), turned.z.toDouble())
    }

    /** Nearest terrain contact of any sweep sample moving with the current attitude; [cast] works on world points. */
    fun sweep(from: Vec3, to: Vec3, cast: (Vec3, Vec3) -> Contact?): Contact? = sweepSamples.mapNotNull { local ->
        val offset = offset(local)
        cast(from.add(offset), to.add(offset))?.let { Contact(it.position.subtract(offset), it.normal, it.point ?: it.position) }
    }.minByOrNull { it.position.distanceToSqr(from) }

    /** Pairwise cosmetic contact never feeds back into the authoritative vehicle. */
    fun separate(offset: Vec3, normal: Vec3) {
        position = position.add(offset)
        val into = velocity.dot(normal)
        if (into < 0) velocity = velocity.subtract(normal.scale(into))
        quietTicks = 0
    }

    /** Initial placement: lift a piece captured inside terrain, climbing out of fully buried columns. */
    fun placeOn(surface: Surface, climb: Int = 4) {
        depenetrate(surface, climb)
        previousPosition = position
    }

    fun tick(collision: (Vec3, Vec3) -> Contact?, surface: Surface? = null) {
        previousPosition = position
        previousOrientation.set(orientation)
        previousScrapePoint = scrapePoint
        scrapePoint = null
        age++
        if (impacted) groundedTicks++
        // Contact is transient: a wall strike or leaving a ledge must not suspend debris in air.
        val wasGrounded = grounded
        grounded = false
        velocity = velocity.scale(WreckDebrisPhysics.AIR_DRAG)
        // Coulomb friction: constant deceleration keeps real inertia instead of an exponential stall.
        if (wasGrounded) velocity = WreckDebrisPhysics.slide(velocity, friction, gravity)
        velocity = velocity.add(0.0, -gravity, 0.0)
        val start = position
        val requested = velocity.horizontalDistance()
        var groundNormal: Vec3? = null
        var remaining = velocity
        for (sweep in 0..2) {
            val target = position.add(remaining)
            val contact = collision(position, target)
            if (contact == null) { position = target; break }
            impacted = true
            val normal = contact.normal.normalize()
            val distance = remaining.length()
            val fraction = if (distance > 1e-9) (position.distanceTo(contact.position) / distance).coerceIn(0.0, 1.0) else 0.0
            val floor = normal.y > .5
            if (!floor && abs(normal.y) < .5 && surface != null && (wasGrounded || grounded)) {
                val rise = stepRise(contact, normal, surface)
                if (rise != null) {
                    // Ride up a low obstacle instead of dumping the slide into it.
                    position = contact.position.add(normal.scale(.003)).add(0.0, rise, 0.0)
                    velocity = velocity.scale(WreckDebrisPhysics.STEP_RETENTION)
                    remaining = velocity.scale(1 - fraction)
                    if (remaining.lengthSqr() < 1e-10) break
                    continue
                }
            }
            val into = velocity.dot(normal)
            position = contact.position.add(normal.scale(if (floor) -WreckDebrisPhysics.CONTACT_SLOP else .003))
            if (into < 0) velocity = if (floor && (wasGrounded || grounded))
                velocity.subtract(normal.scale(into))
                else WreckDebrisPhysics.deflect(velocity, normal)
            if (floor) {
                grounded = true
                groundNormal = normal
                // Remaining time is spent scraping, not repeating a positive restitution impulse.
                velocity = velocity.subtract(normal.scale(velocity.dot(normal)))
                if (velocity.lengthSqr() < .000025) velocity = Vec3.ZERO
            }
            remaining = velocity.scale(1 - fraction)
            if (remaining.lengthSqr() < 1e-10) break
        }
        val moved = position.subtract(start).horizontalDistance()
        if (grounded) {
            slideDistance += moved
            if (requested > .05 && moved < requested * .2) pinnedTicks++
        }
        var turned = false
        if (grounded && groundNormal != null) {
            if (surface == null) {
                // Terrain-less fallback: settle about the lowest support.
                val before = supports.minOf { offset(it).y }
                orientation.set(WreckDebrisPhysics.settle(orientation, groundNormal, .12f, halfExtents, rest))
                position = position.add(0.0, before - supports.minOf { offset(it).y }, 0.0)
                scrapePoint = position.add(0.0, before + WreckDebrisPhysics.CONTACT_SLOP, 0.0)
            } else {
                quietTicks = if (velocity.horizontalDistanceSqr() < 1e-8 && tipRate == 0f) quietTicks + 1 else 0
                if (quietTicks > 4 && restAge < 0) restAge = age - 4
                // A settled piece is re-examined a few times per second, not every tick.
                if (quietTicks <= 4 || age % 8 == 0) turned = restOnSupports(surface)
            }
        } else {
            tipRate = 0f
            rolling = false
            lastLean = null
            quietTicks = 0
            // Smooth changing torque gives a tumbling broken panel without per-frame randomness.
            val wobble = sin(age * .071 + phase)
            orientation.rotateXYZ((spin.x * (1 + wobble * .45)).toFloat(),
                (spin.y * (1 + sin(age * .093 + phase) * .35)).toFloat(),
                (spin.z * (1 - wobble * .35)).toFloat()).normalize()
            turned = true
        }
        // Rotation is never swept; lift anything it pushed below the surface.
        if (surface != null && (turned || moved > 1e-4 || !grounded) && (impacted || nearTerrain(surface)))
            depenetrate(surface)
    }

    /** One center ray decides whether a falling, tumbling piece is low enough to need the full pass. */
    private fun nearTerrain(surface: Surface): Boolean {
        val reach = (halfExtents?.length() ?: 0.0) + 1.0
        return surface.below(position.add(0.0, reach, 0.0), reach * 2 + 1.0) != null
    }

    /** Height a grounded piece must rise to clear a low obstacle, or null for a real wall. */
    private fun stepRise(contact: Contact, normal: Vec3, surface: Surface): Double? {
        val point = contact.point ?: return null
        val lowest = contact.position.y + supports.minOf { offset(it).y }
        val column = point.subtract(normal.scale(.08))
        val top = surface.below(Vec3(column.x, lowest + WreckDebrisPhysics.CONTACT_SLOP + WreckDebrisPhysics.STEP_HEIGHT + .05,
            column.z), WreckDebrisPhysics.STEP_HEIGHT + .4) ?: return null
        if (top.isInfinite()) return null
        val rise = top - WreckDebrisPhysics.CONTACT_SLOP - lowest
        return if (rise > WreckDebrisPhysics.STEP_HEIGHT || rise < -.05) null else rise.coerceAtLeast(.01)
    }

    /** Support-based rest: topple over an overhanging edge, and roll off a face the piece may not rest on. */
    private fun restOnSupports(surface: Surface): Boolean {
        val offsets = supports.map { offset(it) }
        val low = offsets.minOf { it.y }
        val top = position.y + offsets.maxOf { it.y } + .3
        val depth = top - (position.y + low) + WreckDebrisPhysics.STEP_HEIGHT
        val contacts = ArrayList<Vec3>()
        var patchX = 0.0; var patchZ = 0.0; var patchY = Double.NEGATIVE_INFINITY
        for (o in offsets) {
            if (o.y > low + WreckDebrisPhysics.STEP_HEIGHT) continue
            val height = surface.below(Vec3(position.x + o.x, top, position.z + o.z), depth) ?: continue
            if (height.isInfinite() || position.y + o.y - height > WreckDebrisPhysics.SUPPORT_BAND) continue
            contacts += o
            patchX += o.x; patchZ += o.z; patchY = max(patchY, height)
        }
        if (contacts.isEmpty()) {
            scrapePoint = position.add(0.0, low + WreckDebrisPhysics.CONTACT_SLOP, 0.0)
            return false
        }
        val centerX = patchX / contacts.size
        val centerZ = patchZ / contacts.size
        scrapePoint = Vec3(position.x + centerX, patchY + .04, position.z + centerZ)
        scrapeRadius = contacts.maxOf { Math.hypot(it.x - centerX, it.z - centerZ) }.coerceAtMost(3.0)
        val up = Vec3(0.0, 1.0, 0.0)
        if (!rolling && WreckDebrisPhysics.verticalAxis(orientation, up) in rest.axes) {
            val hint = if (velocity.horizontalDistanceSqr() > 1e-8) velocity else Vec3(Math.cos(phase), 0.0, Math.sin(phase))
            val overhang = WreckDebrisPhysics.overhang(contacts, WreckDebrisPhysics.SUPPORT_MARGIN, hint)
            if (overhang == null) { tipRate = 0f; lastLean = null; return false }
            // Energy is lost on each rocking contact: reversing lean restarts the topple from rest.
            if (lastLean?.let { it.dot(overhang.lean) < 0 } == true) tipRate = 0f
            lastLean = overhang.lean
            tipRate = min(tipRate + WreckDebrisPhysics.TIP_ACCEL, WreckDebrisPhysics.TIP_MAX)
            topple(position.add(overhang.pivot), up.cross(overhang.lean).normalize(), tipRate)
            return true
        }
        lastLean = null
        // On a cut end or a thin edge: roll over the support edge until an allowed face is down, even
        // while the edge would still hold it upright. The roll finishes; it never rocks back mid-way.
        rolling = true
        val delta = WreckDebrisPhysics.restTarget(orientation, up, halfExtents, rest)
            .mul(Quaternionf(orientation).conjugate())
        if (delta.w < 0) delta.set(-delta.x, -delta.y, -delta.z, -delta.w)
        val angle = 2f * acos(delta.w.coerceIn(-1f, 1f))
        val sinHalf = kotlin.math.sqrt((1f - delta.w * delta.w).coerceAtLeast(0f))
        if (angle < 1e-3f || sinHalf < 1e-6f) { rolling = false; tipRate = 0f; return false }
        val axis = Vec3((delta.x / sinHalf).toDouble(), (delta.y / sinHalf).toDouble(), (delta.z / sinHalf).toDouble())
        val fall = axis.cross(up)
        val pivot = contacts.maxBy { it.x * fall.x + it.z * fall.z }
        tipRate = min(tipRate + WreckDebrisPhysics.TIP_ACCEL, WreckDebrisPhysics.TIP_MAX)
        val turn = min(angle, tipRate)
        rotateAbout(position.add(pivot), Quaternionf().rotationAxis(turn, axis.x.toFloat(), axis.y.toFloat(), axis.z.toFloat()))
        if (turn >= angle) { rolling = false; tipRate = 0f }
        return true
    }

    /** Rotate about a support edge; landing flat on an allowed face ends the topple instead of rocking past it. */
    private fun topple(pivot: Vec3, axis: Vec3, rate: Float) {
        val up = Vec3(0.0, 1.0, 0.0)
        val step = Quaternionf().rotationAxis(rate, axis.x.toFloat(), axis.y.toFloat(), axis.z.toFloat())
        val face = WreckDebrisPhysics.verticalAxis(orientation, up)
        val local = Vector3f(if (face == 0) 1f else 0f, if (face == 1) 1f else 0f, if (face == 2) 1f else 0f)
        val world = Quaternionf(orientation).transform(Vector3f(local))
        if (world.y < 0) world.negate()
        val before = acos(world.y.coerceIn(-1f, 1f))
        val nudge = Quaternionf().rotationAxis(rate * .05f, axis.x.toFloat(), axis.y.toFloat(), axis.z.toFloat())
        val approaching = Quaternionf(nudge).mul(orientation).transform(Vector3f(local))
            .let { acos(abs(it.y).coerceIn(0f, 1f)) } < before
        if (face in rest.axes && approaching && before <= rate) {
            rotateAbout(pivot, Quaternionf().rotationTo(world, Vector3f(0f, 1f, 0f)))
            tipRate = 0f
            lastLean = null
            return
        }
        rotateAbout(pivot, step)
    }

    private fun rotateAbout(pivot: Vec3, rotation: Quaternionf) {
        val arm = position.subtract(pivot)
        val turned = Quaternionf(rotation).transform(Vector3f(arm.x.toFloat(), arm.y.toFloat(), arm.z.toFloat()))
        position = pivot.add(turned.x.toDouble(), turned.y.toDouble(), turned.z.toDouble())
        orientation.set(Quaternionf(rotation).mul(orientation)).normalize()
    }

    /**
     * Cast down from above the piece at every low support sample and lift the center until the deepest
     * one is at most [WreckDebrisPhysics.CONTACT_SLOP] below its surface. Samples inside a solid column
     * are skipped; a lift beyond a step is treated as wall clipping unless [climb] allows digging out.
     */
    fun depenetrate(surface: Surface, climb: Int = 0): Double {
        val offsets = supports.map { offset(it) }
        val low = offsets.minOf { it.y }
        val high = offsets.maxOf { it.y }
        val limit = if (climb > 0) high - low + climb + WreckDebrisPhysics.STEP_HEIGHT
            else WreckDebrisPhysics.STEP_HEIGHT + WreckDebrisPhysics.CONTACT_SLOP
        val heights = DoubleArray(offsets.size) { Double.NaN }
        var lift = 0.0
        for ((index, o) in offsets.withIndex()) {
            if (o.y > low + WreckDebrisPhysics.STEP_HEIGHT) continue
            var height: Double? = null
            for (raise in 0..climb.coerceAtLeast(0)) {
                height = surface.below(Vec3(position.x + o.x, position.y + high + .3 + raise, position.z + o.z),
                    high - low + 2.0 + raise)
                if (height == null || !height.isInfinite()) break
            }
            if (height == null || height.isInfinite()) continue
            heights[index] = height
            val needed = height - WreckDebrisPhysics.CONTACT_SLOP - (position.y + o.y)
            if (needed > lift && needed <= limit) lift = needed
        }
        if (lift > 0) {
            position = position.add(0.0, lift, 0.0)
            if (velocity.y < 0) velocity = Vec3(velocity.x, 0.0, velocity.z)
        }
        for (index in offsets.indices) if (!heights[index].isNaN())
            maxPenetration = max(maxPenetration, heights[index] - (position.y + offsets[index].y))
        return lift
    }

    companion object {
        /** [Surface.below] result for a query that starts inside a solid block. */
        const val BURIED = Double.NEGATIVE_INFINITY

        private fun samples(half: Vec3, nonZero: Int): List<Vec3> = buildList {
            for (i in -1..1) for (j in -1..1) for (k in -1..1) {
                val count = (if (i != 0) 1 else 0) + (if (j != 0) 1 else 0) + (if (k != 0) 1 else 0)
                if (count >= nonZero) add(Vec3(half.x * i, half.y * j, half.z * k))
            }
        }
    }
}
