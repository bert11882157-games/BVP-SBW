package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ProjectileBeltResolutionStatus

/** Opt-in grouping only. Cadence and accepted-shot mutation remain scheduler-owned. */
internal object VehicleFixedGunBanks {
    const val MAX_GUNS = 8
    const val MAX_EVENTS_PER_TICK = 8
    private const val MAX_MUZZLES = 32
    private val projectileTypes = setOf(
        "superbwarfare:projectile",
        "superbwarfare:small_cannon_shell",
    )

    internal fun memberIndices(bank: List<String>, owned: List<String>, maximum: Int = MAX_GUNS): IntArray? {
        if (bank.size !in 1..maximum) return null
        val indices = IntArray(bank.size)
        for ((ordinal, name) in bank.withIndex()) {
            if (name.isBlank() || name.length > 96 || name.any { it.isISOControl() }) return null
            if (bank.indexOf(name) != ordinal) return null
            val index = owned.indexOf(name)
            if (index < 0 || owned.lastIndexOf(name) != index) return null
            indices[ordinal] = index
        }
        return indices
    }

    internal fun eventCapacity(rpm: Int): Int? {
        if (rpm !in 1..(1200 * MAX_EVENTS_PER_TICK)) return null
        return (rpm + 1199) / 1200
    }

    /**
     * Whether [profile] fires the authored [rpm] one round per event, or as consolidated
     * aircraft events at 1/roundWeight of that rate while still presenting the authored rate.
     */
    internal fun matchesAuthoredRate(profile: VehicleWeaponScheduleProfile, rpm: Int): Boolean =
        profile.bulletRpm == rpm &&
            profile.eventRpm == AircraftRoundConsolidation.eventRpm(rpm, profile.roundWeight)

    fun bankSnapshot(selection: VehicleWeaponSelection): List<String>? =
        com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.bank(selection)
            ?: selection.vehicle.getSeat(selection.seatIndex)?.fixedGunBank?.takeIf { selection.weaponName in it }?.toList()

    /** Existing banks expand primary; aircraft pod aliases expand either held slot. */
    fun expand(
        anchor: ResolvedVehicleWeaponSchedule,
        primary: Boolean,
    ): List<ResolvedVehicleWeaponSchedule>? {
        val selection = anchor.selection
        val vehicle = selection.vehicle
        val seat = vehicle.getSeat(selection.seatIndex) ?: return null
        val podBank = com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.bank(selection)
        val bank = podBank ?: seat.fixedGunBank
        if (podBank == null && (bank.isEmpty() || selection.weaponName !in bank)) return listOf(anchor)
        if (selection.controller.vehicle !== vehicle || !selection.controller.isAlive) return null
        val indices = memberIndices(bank, vehicle.getWeaponIds(selection.seatIndex),
            if (podBank != null) com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.MAX_GROUP_MEMBERS else MAX_GUNS) ?: return null
        val result = ArrayList<ResolvedVehicleWeaponSchedule>(bank.size)
        val muzzleOwners = HashSet<String>()

        for (index in indices) {
            val resolved = if (index == selection.weaponIndex) anchor else
                VehicleWeaponScheduleProviders.resolve(vehicle, selection.controller, index)
                    ?: return null
            val member = resolved.selection
            if (vehicle.computed().seats().count { member.weaponName in it.weapons() } != 1) {
                return null
            }
            if (podBank != null && com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.isPod(vehicle, member.weaponName) && member.gunData.ammo.get() <= 0) continue
            if (!validGun(member, member.gunData)) return null

            val profile = resolved.profile
            val required = eventCapacity(profile.eventRpm) ?: return null
            if (profile.projectilesPerEvent != 1 ||
                !matchesAuthoredRate(profile, member.gunData.get(GunProp.RPM)) ||
                profile.maxCatchUpEvents > MAX_EVENTS_PER_TICK
            ) return null

            for (muzzle in member.gunData.get(GunProp.SHOOT_POS).muzzleAttachments) {
                if (!muzzleOwners.add(muzzle)) return null
            }

            if (podBank != null || primary || index == selection.weaponIndex) {
                result.add(resolved.copy(profile = profile.copy(
                    maxCatchUpEvents = maxOf(profile.maxCatchUpEvents, required),
                    preserveAcceptedCadenceAcrossPresses = true,
                )))
            }
        }
        return result
    }

    /** Recheck the live channel before each event; do not retain mutable GunData copies. */
    fun permitsEvent(
        selection: VehicleWeaponSelection,
        profile: VehicleWeaponScheduleProfile,
    ): Boolean {
        val data = selection.vehicle.getGunData(selection.seatIndex, selection.weaponIndex)
            ?: return false
        if (com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.isPod(selection.vehicle, selection.weaponName) &&
            (data.ammo.get() <= 0 || selection.weaponName !in com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.equipped(selection.vehicle))) return false
        if (!validGun(selection, data) || !matchesAuthoredRate(profile, data.get(GunProp.RPM))) return false
        val belt = data.resolveProjectileBelt()
        // Retain the normal transaction's precise invalid-belt rejection and diagnostics.
        if (belt.status == ProjectileBeltResolutionStatus.INVALID) return true
        return supportedProjectile(belt.projectileData ?: data)
    }

    private fun supportedProjectile(data: GunData): Boolean {
        val projectile = data.get(GunProp.PROJECTILE)
        return projectile.itemId in projectileTypes &&
            (projectile.data == null || projectile.data!!.entrySet().isEmpty())
    }

    private fun validGun(selection: VehicleWeaponSelection, data: GunData): Boolean {
        if (data.get(GunProp.PROJECTILE_AMOUNT) != 1 ||
            data.get(GunProp.AMMO_COST_PER_SHOOT) != 1 ||
            data.get(GunProp.MAGAZINE) <= 0 ||
            !supportedProjectile(data)
        ) return false

        val shootPos = data.get(GunProp.SHOOT_POS)
        val count = shootPos.positions.size
        val station = selection.vehicle.isHullParentedPassengerWeaponStation() &&
            selection.vehicle.isPassengerWeaponStationWeapon(selection.seatIndex, selection.weaponIndex)
        val parent = when (shootPos.transform) {
            "Vehicle" -> "Vehicle"
            "WeaponStationBarrel" -> if (station) "WeaponStationBarrel" else return false
            else -> return false
        }
        if (
            shootPos.boundUpWithAmmoAmount ||
            count !in 1..MAX_MUZZLES ||
            shootPos.directions.size != count ||
            shootPos.muzzleAttachments.size != count ||
            shootPos.muzzleDirectionAttachments != shootPos.muzzleAttachments
        ) return false

        val attachments = selection.vehicle.computed().attachments
        for (index in 0 until count) {
            val frame = attachments[shootPos.muzzleAttachments[index]] ?: return false
            val position = shootPos.positions[index]
            val direction = frame.direction
            if (frame.parent != parent || frame.position != position ||
                (parent == "WeaponStationBarrel" && frame.direction != shootPos.directions[index]) ||
                !position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite() ||
                !direction.x.isFinite() || !direction.y.isFinite() || !direction.z.isFinite() ||
                !direction.lengthSqr().isFinite() || direction.lengthSqr() <= 1.0E-12
            ) return false
        }
        return true
    }
}
