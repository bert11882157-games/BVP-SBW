package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy
import com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMotionUtils
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.atsuishio.superbwarfare.init.ModSounds
import net.minecraft.world.phys.Vec3

/**
 * Server-side vehicle-against-vehicle contact (owner 2026-09-29), run once per pair and tick before movement:
 * finds the contact from the physical parts, exchanges momentum along the contact normal, eases overlapping vehicles
 * apart and deals size-aware crash damage ([VehicleImpactModel]).
 *
 * Replaces the vehicle branch of the old strike ("crush") code and the every-4-ticks anti-stacking shove, which both
 * injected velocity from the entity's registered width or its heading and caused vehicles to be catapulted. Clients no
 * longer push vehicles: they receive the result through the normal motion sync.
 */
internal class VehicleCollisionResponseService(private val vehicle: VehicleEntity) {
    /** Other vehicle id to the game tick this pair was last resolved (so the second vehicle of a pair skips it). */
    private val resolvedTick = HashMap<Int, Long>()
    /** Other vehicle id to the tick and closing speed of the last damaging hit. */
    private val lastHit = HashMap<Int, Pair<Long, Double>>()
    /** Tick of the last vehicle contact, so movement does not also charge a wall hit for it. */
    var contactTick = Long.MIN_VALUE
        private set

    fun touchedVehicleThisTick(): Boolean = contactTick == vehicle.level().gameTime

    fun tick() {
        if (vehicle.level().isClientSide || vehicle.isRemoved || vehicle.noPhysics || vehicle.vehicle != null) return
        // Drones keep their own impact handling.
        if (vehicle.vehicleType == VehicleType.DRONE) return
        // Carrier decks are terrain, not a vehicle to collide with (DeckCollisions).
        if (vehicle is com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurfaceEntity) return
        val moving = vehicle.deltaMovement.lengthSqr() > 1.0E-6
        // Parked vehicles are only the second half of someone else's contact; check them for overlap now and then.
        if (!moving && vehicle.tickCount % 4 != 0) return
        val gameTime = vehicle.level().gameTime
        if (vehicle.tickCount % 100 == 0) prune(gameTime)
        val bounds = VehicleMotionUtils.calculateCombinedAABBOptimized(vehicle)
        // Fitted vehicles report a small core as their entity box, so reach past it for large hulls and wings.
        val query = bounds.expandTowards(vehicle.deltaMovement).inflate(QUERY_MARGIN)
        val candidates = vehicle.level().getEntitiesOfClass(VehicleEntity::class.java, query) { other ->
            other !== vehicle && other.isAlive && !other.noPhysics && other.vehicle == null &&
                other.vehicleType != VehicleType.DRONE &&
                other !is com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurfaceEntity &&
                !vehicle.hasPassenger(other) && !other.hasPassenger(vehicle)
        }
        if (candidates.isEmpty()) return
        var own: List<VehicleEntityContacts.Part>? = null
        for (other in candidates) {
            if (vehicle.isRemoved || !vehicle.isAlive) return
            if (resolvedTick[other.id] == gameTime || other.isRemoved) continue
            val otherBounds = VehicleMotionUtils.calculateCombinedAABBOptimized(other)
            if (!otherBounds.expandTowards(other.deltaMovement).intersects(bounds.expandTowards(vehicle.deltaMovement)
                    .inflate(0.05))) continue
            val ownParts = own ?: VehicleEntityContacts.parts(vehicle).also { own = it }
            if (ownParts.isEmpty()) return
            // A partner that already moved this tick stands at its end-of-tick position: only this vehicle's own
            // motion is still to come (counting the partner's again would resolve next tick's contact early).
            val otherMotion = if (other.collisionResponse.movedTick == gameTime) Vec3.ZERO else other.deltaMovement
            val relative = vehicle.deltaMovement.subtract(otherMotion)
            val impact = VehicleEntityContacts.impact(ownParts, VehicleEntityContacts.parts(other), relative) ?: continue
            resolvedTick[other.id] = gameTime
            other.collisionResponse.resolvedTick[vehicle.id] = gameTime
            resolve(other, impact, gameTime)
        }
    }

    /** Game tick this vehicle last moved (server), for the partner look-ahead above. */
    var movedTick = Long.MIN_VALUE
        private set

    fun afterMove() {
        if (!vehicle.level().isClientSide) movedTick = vehicle.level().gameTime
    }

    private fun resolve(other: VehicleEntity, impact: VehicleEntityContacts.Impact, gameTime: Long) {
        val ownAircraft = isAircraft(vehicle)
        val otherAircraft = isAircraft(other)
        var normal = impact.normal
        var ownVelocity = vehicle.deltaMovement
        var otherVelocity = other.deltaMovement
        if (!ownAircraft && !otherAircraft) {
            // Ground vehicles meet side to side. One resting on another (shallow vertical overlap) is the support
            // code's business; two merged into each other (spawned or placed so) are eased apart horizontally.
            val flat = Vec3(normal.x, 0.0, normal.z)
            if (flat.lengthSqr() >= FLAT_NORMAL_MIN * FLAT_NORMAL_MIN) {
                normal = flat.normalize()
            } else if (impact.depth > MERGED_DEPTH) {
                val apart = vehicle.position().subtract(other.position()).multiply(1.0, 0.0, 1.0)
                normal = if (apart.lengthSqr() > 1.0E-6) apart.normalize()
                else if (vehicle.id < other.id) Vec3(1.0, 0.0, 0.0) else Vec3(-1.0, 0.0, 0.0)
                ownVelocity = Vec3.ZERO
                otherVelocity = Vec3.ZERO
            } else return
        }
        // Movement only reports a horizontal collision against vehicles that are solid to it; only then may a
        // contact stand in for that wall hit.
        if (solidTo(other)) contactTick = gameTime
        if (solidTo(vehicle)) other.collisionResponse.contactTick = gameTime
        val outcome = VehicleImpactModel.resolve(
            VehicleImpactModel.Body(vehicle.mass.toDouble(), ownVelocity, ownAircraft, impact.ownZones),
            VehicleImpactModel.Body(other.mass.toDouble(), otherVelocity, otherAircraft, impact.otherZones),
            normal, impact.depth)
        applyDelta(vehicle, outcome.deltaA)
        applyDelta(other, outcome.deltaB)
        if (!outcome.damages) return

        // One damaging hit per pair per [DAMAGE_COOLDOWN] ticks, unless the new one is clearly harder.
        val previous = lastHit[other.id]
        if (previous != null && gameTime - previous.first < DAMAGE_COOLDOWN &&
            outcome.closingSpeed < previous.second * 1.5) return
        lastHit[other.id] = gameTime to outcome.closingSpeed
        other.collisionResponse.lastHit[vehicle.id] = gameTime to outcome.closingSpeed

        vehicle.level().playSound(null, vehicle, ModSounds.VEHICLE_STRIKE.get(), vehicle.soundSource, 1f, 1f)
        val incoming = vehicle.deltaMovement.subtract(outcome.deltaA)
        val otherIncoming = other.deltaMovement.subtract(outcome.deltaB)
        damage(vehicle, other, outcome.damageA, outcome.lethalA, outcome.shearA, outcome.protectedA, impact.ownZones,
            incoming)
        damage(other, vehicle, outcome.damageB, outcome.lethalB, outcome.shearB, outcome.protectedB, impact.otherZones,
            otherIncoming)
        if (EliteDiagnostics.isEnabled(vehicle.level())) {
            EliteDiagnostics.record(vehicle, "vehicle_impact", "HIT", "other", other.stringUUID,
                "closing_blocks_per_tick", outcome.closingSpeed, "mass", vehicle.mass, "other_mass", other.mass,
                "damage_fraction", outcome.damageA, "other_damage_fraction", outcome.damageB,
                "zones", impact.ownZones.joinToString(), "other_zones", impact.otherZones.joinToString())
        }
    }

    private fun damage(
        target: VehicleEntity, striker: VehicleEntity, fraction: Double, lethal: Boolean, shear: Boolean,
        protected: Boolean, zones: Set<VehicleImpactModel.Zone>, incoming: Vec3,
    ) {
        if (target.isWreck || target.isRemoved || fraction <= 0.0) return
        if (shear && VehicleImpactModel.Zone.BODY !in zones) {
            // The wing that struck comes off: which one follows from where the other vehicle is.
            val side = wingSide(target, striker)
            if (side != 0) AircraftWreckBreakup.detach(target, side, incoming)
        }
        val source = ModDamageTypes.causeVehicleStrikeDamage(target.level().registryAccess(), striker,
            striker.lastDriver ?: striker)
        var amount = (target.getMaxHealth() * fraction).toFloat()
        // Much heavier than what hit it: never destroyed by this collision, however damaged it already was.
        if (protected) amount = kotlin.math.min(amount, target.health - 1f)
        if (!(amount > 0f)) return
        val result = target.applyResolvedDamage(ResolvedVehicleDamageRequest(source, amount,
            modulePolicy = ResolvedVehicleModulePolicy.SKIP_NATIVE, lethal = lethal))
        if (result.accepted && target.health <= 0f && isAircraft(target)) AircraftWreckBreakup.detach(target, 3, incoming)
    }

    /** 1 = left, 2 = right wing (by the authored wing parts nearest the other vehicle); 0 when unknown. */
    private fun wingSide(target: VehicleEntity, striker: VehicleEntity): Int {
        val snapshot = target.getAircraftCollisionSnapshot(1F) ?: return 0
        val point = striker.position()
        var best = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (part in snapshot.parts) {
            if (!part.active || part.wingSide == 0) continue
            val center = part.worldBounds.center
            val distance = center.distanceToSqr(point)
            if (distance < bestDistance) {
                bestDistance = distance
                best = part.wingSide
            }
        }
        return best
    }

    private fun applyDelta(target: VehicleEntity, delta: Vec3) {
        if (delta.lengthSqr() < 1.0E-10) return
        target.setDeltaMovement(target.deltaMovement.add(delta))
        target.hasImpulse = true
    }

    /** Whether [partner]'s parts stop this vehicle's movement (entity-collidable box or physical aircraft parts). */
    private fun solidTo(partner: VehicleEntity): Boolean =
        partner.canBeCollidedWith() || partner.usesAircraftPhysicalCollision()

    private fun prune(gameTime: Long) {
        resolvedTick.values.removeIf { gameTime - it > 2 }
        lastHit.values.removeIf { gameTime - it.first > DAMAGE_COOLDOWN }
    }

    companion object {
        /** Reach beyond the query box for vehicles whose parts extend past their entity box (blocks). */
        const val QUERY_MARGIN = 12.0
        /** Ground pairs: a contact normal flatter than this (horizontal share) is treated as resting on top. */
        const val FLAT_NORMAL_MIN = 0.35
        const val DAMAGE_COOLDOWN = 10L
        /** Ground pairs: vertical overlap deeper than this (blocks) means merged, not resting on top. */
        const val MERGED_DEPTH = 0.3

        fun isAircraft(vehicle: VehicleEntity): Boolean =
            vehicle.vehicleType == VehicleType.AIRPLANE || vehicle.vehicleType == VehicleType.HELICOPTER
    }
}
