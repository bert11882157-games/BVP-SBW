package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ProjectileBeltResolutionStatus
import com.atsuishio.superbwarfare.data.StringOrVec3
import net.minecraft.world.phys.Vec3

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

    /**
     * A bank member must be listed exactly once on the bank's own seat. The same physical gun may also be listed on
     * other seats (a two-seat attack helicopter whose pilot can fire the gunner's turret gun): its GunData, ammunition
     * and muzzles are shared by name, and the scheduler keeps one cadence and heat state per gun name whichever seats
     * hold its trigger, so a second listing can neither double its cadence nor claim its muzzles twice.
     */
    internal fun ownsMember(seatWeapons: List<List<String>>, bankSeat: Int, name: String): Boolean {
        val own = seatWeapons.getOrNull(bankSeat) ?: return false
        return own.count { it == name } == 1
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

    /**
     * Existing banks expand primary; aircraft pod aliases expand either held slot. Null when the bank cannot fire;
     * [reject] then receives the reason (the scheduler's INVALID_FIXED_GUN_BANK boundary and the fire diagnostics).
     */
    fun expand(
        anchor: ResolvedVehicleWeaponSchedule,
        primary: Boolean,
        reject: ((String) -> Unit)? = null,
    ): List<ResolvedVehicleWeaponSchedule>? {
        fun refuse(reason: String): List<ResolvedVehicleWeaponSchedule>? {
            reject?.invoke(reason)
            return null
        }
        val selection = anchor.selection
        val vehicle = selection.vehicle
        val seat = vehicle.getSeat(selection.seatIndex) ?: return refuse("no seat ${selection.seatIndex}")
        val podBank = com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.bank(selection)
        val bank = podBank ?: seat.fixedGunBank
        if (podBank == null && (bank.isEmpty() || selection.weaponName !in bank)) return listOf(anchor)
        if (selection.controller.vehicle !== vehicle || !selection.controller.isAlive) return refuse("controller not aboard")
        val indices = memberIndices(bank, vehicle.getWeaponIds(selection.seatIndex),
            if (podBank != null) com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.MAX_GROUP_MEMBERS else MAX_GUNS)
            ?: return refuse("bank $bank is not listed once each on seat ${selection.seatIndex}")
        val result = ArrayList<ResolvedVehicleWeaponSchedule>(bank.size)
        val muzzleOwners = HashSet<String>()

        for (index in indices) {
            val resolved = if (index == selection.weaponIndex) anchor else
                VehicleWeaponScheduleProviders.resolve(vehicle, selection.controller, index)
                    ?: return refuse("member $index has no schedule")
            val member = resolved.selection
            if (!ownsMember(vehicle.computed().seats().map { it.weapons() }, selection.seatIndex, member.weaponName)) {
                return refuse("${member.weaponName} is not owned by seat ${selection.seatIndex}")
            }
            if (podBank != null && com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.isPod(vehicle, member.weaponName) && member.gunData.ammo.get() <= 0) continue
            var invalid = ""
            if (!validGun(member, member.gunData) { invalid = it }) return refuse("${member.weaponName}: $invalid")

            val profile = resolved.profile
            val required = eventCapacity(profile.eventRpm)
                ?: return refuse("${member.weaponName}: event rate ${profile.eventRpm} out of range")
            if (profile.projectilesPerEvent != 1 ||
                !matchesAuthoredRate(profile, member.gunData.get(GunProp.RPM)) ||
                profile.maxCatchUpEvents > MAX_EVENTS_PER_TICK
            ) return refuse("${member.weaponName}: profile ${profile.id} (${profile.projectilesPerEvent}/event, " +
                "${profile.bulletRpm}/${profile.eventRpm} rpm, catch-up ${profile.maxCatchUpEvents}) does not match " +
                "${member.gunData.get(GunProp.RPM)} rpm")

            for (muzzle in member.gunData.get(GunProp.SHOOT_POS).muzzleAttachments) {
                if (!muzzleOwners.add(muzzle)) return refuse("${member.weaponName}: muzzle $muzzle is shared")
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

    /**
     * A muzzle frame's direction against the gun's authored one. The authored value is a StringOrVec3, which has no
     * value equality: comparing it to the frame's Vec3 directly was always unequal, so every station-barrel bank
     * (AH-64D M230, AH-1F M197, Mi-24 gunner guns, rear and tail guns) failed and never fired on a held click.
     */
    internal fun sameDirection(frame: Vec3, authored: StringOrVec3): Boolean = authored.vec3 == frame

    private fun supportedProjectile(data: GunData): Boolean {
        val projectile = data.get(GunProp.PROJECTILE)
        return projectile.itemId in projectileTypes &&
            (projectile.data == null || projectile.data!!.entrySet().isEmpty())
    }

    private fun validGun(
        selection: VehicleWeaponSelection,
        data: GunData,
        refuse: (String) -> Unit = {},
    ): Boolean {
        fun invalid(reason: String): Boolean {
            refuse(reason)
            return false
        }
        if (data.get(GunProp.PROJECTILE_AMOUNT) != 1 ||
            data.get(GunProp.AMMO_COST_PER_SHOOT) != 1 ||
            data.get(GunProp.MAGAZINE) <= 0
        ) return invalid("not a one-round magazine gun")
        if (!supportedProjectile(data)) return invalid("projectile ${data.get(GunProp.PROJECTILE).itemId} is not a gun round")

        val shootPos = data.get(GunProp.SHOOT_POS)
        val count = shootPos.positions.size
        val station = selection.vehicle.isHullParentedPassengerWeaponStation() &&
            selection.vehicle.isPassengerWeaponStationWeapon(selection.seatIndex, selection.weaponIndex)
        val parent = when (shootPos.transform) {
            "Vehicle" -> "Vehicle"
            "WeaponStationBarrel" -> if (station) "WeaponStationBarrel" else
                return invalid("station barrel gun on seat ${selection.seatIndex}, which does not aim the station")
            else -> return invalid("transform ${shootPos.transform} is not bankable")
        }
        if (
            shootPos.boundUpWithAmmoAmount ||
            count !in 1..MAX_MUZZLES ||
            shootPos.directions.size != count ||
            shootPos.muzzleAttachments.size != count ||
            shootPos.muzzleDirectionAttachments != shootPos.muzzleAttachments
        ) return invalid("shoot positions, directions and muzzle attachments do not line up")

        val attachments = selection.vehicle.computed().attachments
        for (index in 0 until count) {
            val frame = attachments[shootPos.muzzleAttachments[index]]
                ?: return invalid("no attachment ${shootPos.muzzleAttachments[index]}")
            val position = shootPos.positions[index]
            val direction = frame.direction
            if (frame.parent != parent || frame.position != position ||
                (parent == "WeaponStationBarrel" && !sameDirection(frame.direction, shootPos.directions[index])) ||
                !position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite() ||
                !direction.x.isFinite() || !direction.y.isFinite() || !direction.z.isFinite() ||
                !direction.lengthSqr().isFinite() || direction.lengthSqr() <= 1.0E-12
            ) return invalid("muzzle ${shootPos.muzzleAttachments[index]} differs from its attachment frame")
        }
        return true
    }
}
