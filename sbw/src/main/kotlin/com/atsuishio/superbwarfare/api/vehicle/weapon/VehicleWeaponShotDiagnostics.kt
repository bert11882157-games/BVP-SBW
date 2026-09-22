package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.api.weapon.ShotResult
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.LivingEntity
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Opt-in, bounded diagnostics for rejected vehicle shot attempts.
 *
 * This is deliberately observational: it never changes admission, scheduler state, ammo,
 * heat, projectile creation, or any other gameplay state. Controlled solely by Elite diagnostics.
 */
object VehicleWeaponShotDiagnostics {
    private const val MAX_KEYS = 256
    private const val REPEAT_INTERVAL_TICKS = 20L

    /**
     * The firing stage that rejected a vehicle shot or could not validate its requirements.
     * These values are diagnostic labels only: they never participate in admission or fallback
     * selection.  Keep the set small so a live opt-in trace remains actionable and bounded.
     */
    enum class Boundary {
        INPUT,
        SCHEDULER,
        SELECTION,
        CAN_SHOOT,
        BELT,
        PROJECTILE_ENTITY,
        PROJECTILE_PROFILE,
        INSERTION,
    }

    private val enabled: Boolean get() = EliteDiagnostics.isServerEnabled()

    private data class AttemptKey(
        val vehicleUuid: UUID,
        val dimension: String,
        val controllerUuid: UUID?,
        val seatIndex: Int,
        val weaponName: String,
    )

    private data class LastAttempt(val tick: Long, val reason: String)

    private val attempts = object : LinkedHashMap<AttemptKey, LastAttempt>(MAX_KEYS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AttemptKey, LastAttempt>?): Boolean {
            return size > MAX_KEYS
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean = enabled

    /**
     * Emits one firing-stage detail when Elite diagnostics is enabled. The
     * identity key is the same bounded vehicle/controller/seat/weapon scope as [record], while
     * the stage/detail code is debounced independently through the stored reason.
     */
    @JvmStatic
    fun recordBoundary(
        vehicle: VehicleEntity,
        controller: LivingEntity?,
        weaponName: String?,
        boundary: Boundary,
        detail: String,
    ) {
        if (!enabled) return
        emit(
            vehicle,
            controller,
            weaponName,
            "${boundary.name}:${detail.trim().take(96).ifBlank { "UNSPECIFIED" }}",
        )
    }

    /**
     * Records the standard VehicleGun admission predicates without invoking canShoot a second
     * time.  This is called only after the authoritative result is CANNOT_SHOOT, so custom gun
     * implementations that reject for another reason are reported as UNCLASSIFIED rather than
     * being mistaken for one of the native predicates.
     */
    @JvmStatic
    fun recordCanShootFailure(
        vehicle: VehicleEntity,
        controller: LivingEntity?,
        weaponName: String?,
        data: GunData,
    ) {
        if (!enabled) return

        val failures = ArrayList<String>(6)
        val projectileAmount = runCatching { data.get(GunProp.PROJECTILE_AMOUNT) }.getOrNull()
        when {
            projectileAmount == null -> failures += "PROJECTILE_COUNT_UNREADABLE"
            projectileAmount <= 0 -> failures += "PROJECTILE_COUNT"
        }

        val overHeat = runCatching { data.overHeat.get() }.getOrNull()
        if (overHeat == null) failures += "OVERHEAT_UNREADABLE"
        else if (overHeat) failures += "OVERHEAT"

        val heatPerShoot = runCatching { data.get(GunProp.HEAT_PER_SHOOT) }.getOrNull()
        val heat = runCatching { data.heat.get() }.getOrNull()
        if (heatPerShoot == null || heat == null) {
            failures += "HEAT_UNREADABLE"
        } else if (!(heatPerShoot <= (100.0 + heatPerShoot - heat))) {
            failures += "HEAT"
        }

        runCatching { data.reloading() }.fold(
            onSuccess = { if (it) failures += "RELOAD" },
            onFailure = { failures += "RELOAD_UNREADABLE" },
        )
        runCatching { data.charging() }.fold(
            onSuccess = { if (it) failures += "CHARGE" },
            onFailure = { failures += "CHARGE_UNREADABLE" },
        )
        runCatching { data.bolt.needed.get() }.fold(
            onSuccess = { if (it) failures += "BOLT" },
            onFailure = { failures += "BOLT_UNREADABLE" },
        )

        val ammoCost = runCatching { data.get(GunProp.AMMO_COST_PER_SHOOT) }.getOrNull()
        val ammo = runCatching { vehicle.getAmmo(data) }.getOrNull()
        when {
            ammoCost == null || ammo == null -> failures += "AMMO_UNREADABLE"
            ammo < ammoCost -> failures += "AMMO"
        }

        if (failures.isEmpty()) failures += "CUSTOM_OR_UNCLASSIFIED"
        recordBoundary(vehicle, controller, weaponName, Boundary.CAN_SHOOT, failures.joinToString("+"))
    }

    /** Records a ProjectileFactory boundary using the immutable pre-shot frame identity. */
    @JvmStatic
    fun recordProjectileBoundary(
        parameters: ShootParameters,
        boundary: Boundary,
        detail: String,
    ) {
        if (!enabled) return
        val vehicle = parameters.ammoSupplier as? VehicleEntity
            ?: (parameters.shooter?.vehicle as? VehicleEntity)
            ?: return
        recordBoundary(
            vehicle,
            parameters.shooter as? LivingEntity,
            parameters.frameReference?.weaponName ?: "<unknown>",
            boundary,
            detail,
        )
    }

    /** Records one rejected result, subject to the opt-in/debounce/LRU limits above. */
    @JvmStatic
    fun record(
        vehicle: VehicleEntity,
        controller: LivingEntity?,
        weaponName: String?,
        result: ShotResult,
    ) {
        if (!enabled || result.isAccepted()) return

        emit(vehicle, controller, weaponName, "RESULT:${result.reason.name}")
    }

    private fun emit(
        vehicle: VehicleEntity,
        controller: LivingEntity?,
        weaponName: String?,
        reason: String,
    ) {
        if (!enabled) return

        val key = AttemptKey(
            vehicleUuid = vehicle.uuid,
            dimension = vehicle.level().dimension().location().toString(),
            controllerUuid = controller?.uuid,
            seatIndex = vehicle.getSeatIndex(controller),
            weaponName = weaponName?.takeIf { it.isNotBlank() } ?: "<selected>",
        )
        val tick = vehicle.tickCount.toLong()
        val shouldEmit = synchronized(attempts) {
            val previous = attempts[key]
            val elapsed = previous?.let { tick - it.tick } ?: Long.MAX_VALUE
            val emit = previous == null || previous.reason != reason || elapsed >= REPEAT_INTERVAL_TICKS || elapsed < 0L
            // Retain the last emitted tick.  Updating on suppressed attempts would prevent a
            // continuously failing trigger from ever reaching the bounded repeat interval.
            if (emit) attempts[key] = LastAttempt(tick, reason)
            emit
        }
        if (!shouldEmit) return

        EliteDiagnostics.record(vehicle, "weapon", "rejected", "controller", controller?.uuid,
            "seat", key.seatIndex, "weapon", key.weaponName, "reason", reason)
    }
}
