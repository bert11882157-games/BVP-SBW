package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity

/**
 * DISABLED ([ENABLED] = false): high-rate aircraft guns used to fire half as many rounds, each standing for
 * [WEIGHT] rounds.
 *
 * The weight is stamped on the projectile at launch (server persistent data, and copied into the
 * client entity from its spawn data) and read wherever a hit is resolved, so a consolidated round
 * deals the damage (and blast) of two rounds and tracer cadence keeps the same number of visible
 * tracers per second.
 *
 * Flow: the schedule profile carries the weight ([VehicleWeaponScheduleProfile.roundWeight]) and
 * fires at 1/weight of the authored event rate; the scheduler exposes it for one trigger event via
 * [withRequestedWeight]; the gun clamps it to the ammo it can pay for and exposes the result to the
 * projectile factory via [withLaunchWeight]; the factory [mark]s each projectile before insertion.
 */
object AircraftRoundConsolidation {
    /**
     * Off (owner 2026-09-29: "combine two shots into one" removed). Every round is fired as itself; the weight
     * plumbing stays so rounds stamped by an older build still resolve, and always reads 1 for new rounds.
     */
    const val ENABLED = false
    /** Guns strictly faster than this many rounds per minute were consolidated on aircraft while [ENABLED]. */
    const val MIN_RPM = 700
    const val WEIGHT = 2
    private const val TAG = "SBWRoundWeight"

    /** Projectile entity types fired by guns; rockets, missiles and stores are never consolidated. */
    @JvmField
    val GUN_ROUND_TYPES: Set<String> = setOf(
        "superbwarfare:projectile",
        "superbwarfare:small_cannon_shell",
    )

    private val requestedSlot: ThreadLocal<Int> = ThreadLocal.withInitial { 1 }
    private val launchSlot: ThreadLocal<Int> = ThreadLocal.withInitial { 1 }

    @JvmStatic
    fun applies(aircraft: Boolean, rpm: Int): Boolean = ENABLED && aircraft && rpm > MIN_RPM

    @JvmStatic
    fun mark(projectile: Entity, weight: Int) {
        if (weight > 1) projectile.persistentData.putInt(TAG, weight)
        else projectile.persistentData.remove(TAG)
    }

    /** 1 for an ordinary round, [WEIGHT] for a consolidated round. */
    @JvmStatic
    fun weight(projectile: Entity?): Int {
        val value = projectile?.persistentData?.getInt(TAG) ?: return 1
        return if (value in 2..WEIGHT) value else 1
    }

    /**
     * Whether the fired round with [sequence] shows a tracer when every [everyNth] authored round is a
     * tracer. A consolidated round covers virtual rounds `sequence*weight until (sequence+1)*weight`.
     */
    @JvmStatic
    fun tracerRound(sequence: Long, everyNth: Int, weight: Int): Boolean {
        if (everyNth <= 1) return true
        val span = weight.coerceIn(1, WEIGHT).toLong()
        val first = sequence * span
        for (index in 0 until span) if (Math.floorMod(first + index, everyNth.toLong()) == 0L) return true
        return false
    }

    /** Fixed-wing and rotary-wing platforms. Drones and ground vehicles are excluded. */
    @JvmStatic
    fun isAircraftType(type: VehicleType?): Boolean =
        type == VehicleType.AIRPLANE || type == VehicleType.HELICOPTER

    @JvmStatic
    fun isAircraft(vehicle: VehicleEntity): Boolean =
        isAircraftType(vehicle.vehicleType) || vehicle.isFixedWingFlightVehicle()

    @JvmStatic
    fun isGunRound(projectileItemId: String?): Boolean =
        projectileItemId != null && projectileItemId.trim().lowercase() in GUN_ROUND_TYPES

    /**
     * Round weight for a schedule: [WEIGHT] for an automatic aircraft gun firing faster than
     * [MIN_RPM] rounds per minute, otherwise 1.
     */
    @JvmStatic
    fun scheduleWeight(aircraft: Boolean, rpm: Int, automatic: Boolean, gunRound: Boolean): Int =
        if (automatic && gunRound && applies(aircraft, rpm)) WEIGHT else 1

    /** Event rate at which [weight]-round events deliver [rpm] rounds per minute (odd rates round up). */
    @JvmStatic
    fun eventRpm(rpm: Int, weight: Int): Int {
        val span = weight.coerceIn(1, WEIGHT)
        if (span == 1 || rpm <= 0) return rpm
        return ((rpm.toLong() + span - 1) / span).toInt()
    }

    /**
     * Weight the next event can pay for: a consolidated round costs [WEIGHT] rounds of ammunition,
     * and the last round of a magazine that cannot cover it fires as an ordinary round.
     */
    @JvmStatic
    fun affordableWeight(requested: Int, ammoCostPerRound: Int, availableAmmo: Int): Int {
        val span = requested.coerceIn(1, WEIGHT)
        if (span == 1 || ammoCostPerRound <= 0) return span
        return (availableAmmo / ammoCostPerRound).coerceIn(1, span)
    }

    /** Ammunition beyond the normal per-shot cost that a [weight]-round event consumes. */
    @JvmStatic
    fun extraAmmo(weight: Int, ammoCostPerRound: Int): Int =
        ammoCostPerRound.coerceAtLeast(0) * (weight.coerceIn(1, WEIGHT) - 1)

    /** Runs one scheduled trigger event whose rounds should each stand for [weight] rounds. */
    fun <T> withRequestedWeight(weight: Int, action: () -> T): T =
        withWeight(requestedSlot, weight, action)

    /** Weight requested by the scheduled trigger event on this thread; 1 outside one. */
    @JvmStatic
    fun requestedWeight(): Int = requestedSlot.get()

    /** Runs projectile creation for rounds that each stand for [weight] rounds. */
    fun <T> withLaunchWeight(weight: Int, action: () -> T): T =
        withWeight(launchSlot, weight, action)

    /** Weight to stamp on projectiles created on this thread; 1 outside a launch. */
    @JvmStatic
    fun launchWeight(): Int = launchSlot.get()

    private fun <T> withWeight(slot: ThreadLocal<Int>, weight: Int, action: () -> T): T {
        val previous = slot.get()
        slot.set(weight.coerceIn(1, WEIGHT))
        try {
            return action()
        } finally {
            slot.set(previous)
        }
    }
}
