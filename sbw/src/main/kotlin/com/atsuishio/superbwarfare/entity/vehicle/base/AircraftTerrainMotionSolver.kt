package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingImpactModel
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max

/** Swept movement and impact classification; the caller owns world queries and damage commits. */
internal object AircraftTerrainMotionSolver {
    const val MAX_GEAR_STEP_BLOCKS = 1.0
    private const val SUPPORT_EPSILON = 1e-6
    /** Ground support acts through the deployed gear's lower face, not a steeply diving side. */
    fun acceptsGearContact(normal: Vec3, box: OBB): Boolean {
        if (normal.y <= 0.5) return true
        val up = box.getAxes()[1]
        return normal.x * up.x + normal.y * up.y + normal.z * up.z >= 0.7071067811865476
    }

    /** The inset gear may reach a ledge just after the low nose; look only across that overhang. */
    fun gearSupportReach(body: OBB, gear: OBB, direction: Vec3): Vec3 {
        val horizontal = Vec3(direction.x, 0.0, direction.z)
        if (horizontal.lengthSqr() < 1e-12) return Vec3.ZERO
        val unit = horizontal.normalize()
        fun leading(box: OBB): Double {
            val axes = box.getAxes()
            val extents = doubleArrayOf(box.extents.x, box.extents.y, box.extents.z)
            return box.center.x * unit.x + box.center.z * unit.z + axes.indices.sumOf {
                abs(axes[it].x * unit.x + axes[it].z * unit.z) * extents[it]
            }
        }
        return unit.scale(max(0.0, leading(body) - leading(gear)) + SUPPORT_EPSILON * 4.0)
    }

    data class Query(val contact: FixedWingContactSweep.Contact?, val gear: Boolean, val complete: Boolean)
    data class Result(val movement: Vec3, val velocity: Vec3, val below: Boolean,
        val horizontal: Boolean, val vertical: Boolean, val bodyContact: Boolean,
        val gearContact: Boolean, val normalSpeedSquared: Double, val complete: Boolean,
        val damage: FixedWingImpactModel.Damage, val gearGroundContact: Boolean,
        val gearImpactSpeedBlocksPerTick: Double, val supportAdjustmentBlocks: Double = 0.0,
        val gearStepUsed: Boolean = false)

    fun resolve(requested: Vec3, incoming: Vec3, previouslyGearSupported: Boolean = false,
                gearSupportReach: Vec3 = Vec3.ZERO,
                query: (Vec3, Vec3) -> Query): Result {
        val direct = sweep(requested, incoming, query)
        // Only an existing ground run may follow the runway. Takeoff, airborne approach,
        // retracted gear and a dive must use their actual movement and impact velocity.
        if (!previouslyGearSupported || !direct.complete || requested.y > 0.0 || incoming.y > 0.0)
            return direct
        val horizontal = Vec3(requested.x, 0.0, requested.z)
        if ((direct.horizontal || !direct.gearGroundContact) &&
            horizontal.add(gearSupportReach).lengthSqr() > 1e-12) {
            val rise = Vec3(0.0, MAX_GEAR_STEP_BLOCKS + SUPPORT_EPSILON, 0.0)
            val up = query(rise, Vec3.ZERO)
            if (up.complete && up.contact == null) {
                val supportDestination = horizontal.add(gearSupportReach)
                val across = query(supportDestination, rise)
                // A doorstep can precede a house wall within one movement. Rejecting the
                // entire step here would let the harmless gear stop consume the body impact.
                if (!direct.bodyContact && direct.horizontal && across.complete &&
                    across.contact != null && !across.gear) {
                    partialStepImpact(horizontal, incoming, direct, rise,
                        supportDestination.scale(across.contact.fraction), query)?.let { return it }
                }
                if (across.complete && across.contact == null) {
                    val destination = rise.add(supportDestination)
                    val down = Vec3(0.0, -MAX_GEAR_STEP_BLOCKS * 2.0 - SUPPORT_EPSILON * 2.0, 0.0)
                    val support = query(down, destination)
                    val contact = support.contact
                    if (support.complete && support.gear && contact != null &&
                        contact.normal.y > 0.5 && contact.penetrationDepth <= SUPPORT_EPSILON) {
                        val height = destination.y + down.y * contact.fraction
                        val stepped = Vec3(horizontal.x, height, horizontal.z)
                        val actualDescent = query(Vec3(0.0, height - rise.y, 0.0), rise.add(horizontal))
                        val actualContact = actualDescent.contact
                        if (height in -MAX_GEAR_STEP_BLOCKS..(MAX_GEAR_STEP_BLOCKS + SUPPORT_EPSILON) &&
                            actualDescent.complete && (actualContact == null ||
                                (actualDescent.gear && actualContact.normal.y > 0.5 &&
                                    actualContact.fraction >= 1.0 - SUPPORT_EPSILON)))
                            return supported(stepped, incoming, stepped.y - direct.movement.y,
                                direct.horizontal || height > SUPPORT_EPSILON)
                    }
                }
            }
        }
        // A bounded downward sweep at the actual admitted destination follows small drops
        // and pose changes without losing wheel support or creating a synthetic touchdown.
        if (!direct.bodyContact && !direct.horizontal &&
            (!direct.gearGroundContact || horizontal.lengthSqr() > 1e-12)) {
            val distance = MAX_GEAR_STEP_BLOCKS + direct.movement.y
            if (distance > SUPPORT_EPSILON) {
                val down = Vec3(0.0, -distance, 0.0)
                val support = query(down, direct.movement)
                val contact = support.contact
                if (support.complete && support.gear && contact != null && contact.normal.y > 0.5 &&
                    contact.penetrationDepth <= SUPPORT_EPSILON) {
                    val followed = direct.movement.add(down.scale(contact.fraction))
                    return supported(followed, direct.velocity, followed.y - direct.movement.y)
                }
                // Contact at the start of a sweep cannot keep an aircraft grounded after
                // its wheels have crossed a ledge with no reachable floor at the endpoint.
                if (direct.gearGroundContact)
                    return direct.copy(below = false, gearGroundContact = false)
            }
        }
        return direct
    }

    private fun partialStepImpact(horizontal: Vec3, incoming: Vec3, direct: Result,
                                  rise: Vec3, raisedContact: Vec3,
                                  query: (Vec3, Vec3) -> Query): Result? {
        val down = Vec3(0.0, -MAX_GEAR_STEP_BLOCKS * 2.0 - SUPPORT_EPSILON * 2.0, 0.0)
        fun supportHeight(destination: Vec3): Double? {
            val sample = query(down, rise.add(destination.x, 0.0, destination.z))
            val contact = sample.contact ?: return null
            if (!sample.complete || !sample.gear || contact.normal.y <= 0.5 ||
                contact.penetrationDepth > SUPPORT_EPSILON) return null
            return (rise.y + down.y * contact.fraction).takeIf {
                it > SUPPORT_EPSILON && it <= MAX_GEAR_STEP_BLOCKS + SUPPORT_EPSILON
            }
        }
        val height = supportHeight(raisedContact) ?: return null
        // The maximum-rise probe only locates support. Measure the real body collision at
        // that support height so a speculative high path cannot create crash damage.
        val offset = Vec3(0.0, height + SUPPORT_EPSILON, 0.0)
        val stepped = sweep(horizontal, incoming) { movement, admitted ->
            query(movement, offset.add(admitted))
        }
        if (!stepped.complete ||
            (!stepped.bodyContact && stepped.movement.subtract(horizontal).lengthSqr() > 1e-12) ||
            stepped.movement.dot(horizontal) <= direct.movement.dot(horizontal) + SUPPORT_EPSILON)
            return null
        val finalHeight = supportHeight(stepped.movement) ?: return null
        if (abs(finalHeight - height) > SUPPORT_EPSILON) return null
        return stepped.copy(movement = stepped.movement.add(0.0, height, 0.0),
            velocity = Vec3(stepped.velocity.x, 0.0, stepped.velocity.z), below = true,
            vertical = true, gearContact = true, gearGroundContact = true,
            gearImpactSpeedBlocksPerTick = 0.0,
            supportAdjustmentBlocks = height - direct.movement.y, gearStepUsed = true)
    }

    private fun supported(movement: Vec3, incoming: Vec3, adjustment: Double, stepped: Boolean = false) = Result(
        movement, Vec3(incoming.x, 0.0, incoming.z), true, false, true, false, true,
        0.0, true, FixedWingImpactModel.Damage(0.0, false), true, 0.0, adjustment, stepped)

    private fun sweep(requested: Vec3, incoming: Vec3, query: (Vec3, Vec3) -> Query): Result {
        var velocity = incoming
        var admitted = Vec3.ZERO
        var remaining = requested
        var below = false
        var horizontal = false
        var vertical = false
        var bodyContact = false
        var gearContact = false
        var gearGroundContact = false
        var gearImpactSpeedBlocksPerTick = 0.0
        var normalSpeedSquared = 0.0
        var complete = true
        for (iteration in 0 until 4) {
            val sample = query(remaining, admitted)
            if (!sample.complete) { complete = false; break }
            val contact = sample.contact
            if (contact == null) { admitted = admitted.add(remaining); break }
            val normal = contact.normal
            val into = remaining.dot(normal)
            if (contact.initiallyOverlapping && contact.penetrationDepth > 1e-7) {
                admitted = admitted.add(normal.scale((contact.penetrationDepth + 1e-7).coerceAtMost(0.5)))
            }
            admitted = admitted.add(remaining.scale(contact.fraction))
            remaining = remaining.scale(1.0 - contact.fraction)
            if (into < 0.0) remaining = remaining.subtract(normal.scale(remaining.dot(normal)))
            val velocityInto = velocity.dot(normal)
            if (velocityInto < 0.0) velocity = velocity.subtract(normal.scale(velocityInto))
            below = below || normal.y > 0.5
            horizontal = horizontal || abs(normal.x) > 0.5 || abs(normal.z) > 0.5
            vertical = vertical || abs(normal.y) > 0.5
            if (sample.gear) {
                gearContact = true
                if (normal.y > 0.5) {
                    gearGroundContact = true
                    gearImpactSpeedBlocksPerTick = max(gearImpactSpeedBlocksPerTick,
                        max(0.0, -incoming.dot(normal)))
                }
            } else {
                bodyContact = true
                val speed = max(0.0, -incoming.dot(normal)) * 20.0
                normalSpeedSquared = max(normalSpeedSquared, speed * speed)
            }
            if (remaining.lengthSqr() < 1e-14 && contact.penetrationDepth <= 1e-7) break
        }
        val totalSquared = incoming.lengthSqr() * 400.0
        val damage = if (bodyContact) FixedWingImpactModel.evaluate(
            normalSpeedSquared.coerceAtMost(totalSquared), totalSquared, false)
            else FixedWingImpactModel.Damage(0.0, false)
        return Result(admitted, if (complete) velocity else Vec3.ZERO, below, horizontal, vertical,
            bodyContact, gearContact, normalSpeedSquared, complete, damage,
            gearGroundContact, gearImpactSpeedBlocksPerTick)
    }
}
