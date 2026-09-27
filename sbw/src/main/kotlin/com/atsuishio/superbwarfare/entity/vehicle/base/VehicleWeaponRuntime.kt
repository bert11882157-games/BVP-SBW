package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSlot
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleMuzzleFrame
import com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason
import com.atsuishio.superbwarfare.api.weapon.ShotResult
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ProjectileBeltResolutionStatus
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponShotDiagnostics
import com.atsuishio.superbwarfare.init.ModItems
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Pure server-shot interlock over the current commanded state and normalized retraction fraction. */
internal fun permitsLandingGearShot(required: Boolean, gearUp: Boolean, retraction: Float): Boolean =
    !required || (gearUp && retraction.isFinite() && retraction == 1F)

/**
 * Owns vehicle GunData, deterministic slot policy, and the accepted-shot transaction.
 *
 * Replicated selections, persistence, world effects, aim notifications, and the public ABI remain
 * on [VehicleEntity]. All base-entity shooting overloads enter [fire]; none has its own admission
 * or ammo/effects implementation that can diverge from the primary/secondary scheduler.
 */
internal class VehicleWeaponRuntime(
    private val vehicle: VehicleEntity,
    private val shouldEmitNativeSound: () -> Boolean,
) {
    private val weaponState = VehicleWeaponStateCache<GunData>()
    private var normalizationDataOwner: DefaultVehicleData? = null
    private var normalizationFingerprint = Int.MIN_VALUE
    private data class SelectionCache(val tick: Int, val revision: Int, val owner: DefaultVehicleData,
                                     val nativeAmmo: List<Pair<String, Int>>, val weapons: List<String>,
                                     val indices: List<Int>)
    private val selectionCache = mutableMapOf<Int, SelectionCache>()
    /**
     * Client only: the selectable weapons per seat, kept for one level tick. Rendering asks several times a frame per
     * vehicle (chassis presentation, animations, sounds), and even the validated cache above rebuilt the weapon list,
     * hashed the selection and listed the ammo on each ask (10% of the render thread in a battle). Keyed on the
     * level's game time, which advances even for vehicles the client does not tick.
     */
    private val clientSelection = arrayOfNulls<Pair<Long, List<Int>>>(8)
    /** Null [weaponName] resolves the occupied seat's primary; a name is an explicit channel. */
    fun fire(
        living: LivingEntity?,
        weaponName: String?,
        targetEntityUuid: UUID?,
        targetPos: Vec3?,
        permitsSelectedAttempt: () -> Boolean,
    ): ShotResult = fireInternal(living, weaponName, targetEntityUuid, targetPos, permitsSelectedAttempt, true)

    private fun fireInternal(
        living: LivingEntity?, weaponName: String?, targetEntityUuid: UUID?, targetPos: Vec3?,
        permitsSelectedAttempt: () -> Boolean, routeRocketPods: Boolean,
    ): ShotResult {
        val serverLevel = vehicle.level() as? ServerLevel
        val selectedName = weaponName ?: vehicle.getGunName(vehicle.getSeatIndex(living))
        val rocketCandidates = if (routeRocketPods && selectedName != null)
            com.atsuishio.superbwarfare.api.aircraft.AircraftRocketPodOrder.candidates(vehicle, selectedName)
        else null
        if (rocketCandidates != null) {
            if (weaponName == null && !permitsSelectedAttempt())
                return ShotResult.rejected(ShotRejectionReason.ACTION_BLOCKED, selectedName)
            var rejected = ShotResult.rejected(ShotRejectionReason.CANNOT_SHOOT, selectedName)
            for (candidate in rocketCandidates) {
                val result = fireInternal(living, candidate.name, targetEntityUuid, targetPos, permitsSelectedAttempt, false)
                if (result.isAccepted()) {
                    com.atsuishio.superbwarfare.api.aircraft.AircraftRocketPodOrder.accepted(vehicle, candidate.name, candidate.side)
                    return result
                }
                rejected = result
            }
            return rejected
        }
        if (selectedName != null && com.atsuishio.superbwarfare.api.aircraft.AircraftStoreWeapons.mountId(selectedName) != null) {
            val rejected = when {
                vehicle.isWreck -> ShotRejectionReason.WRECKED
                serverLevel == null -> ShotRejectionReason.NOT_SERVER_AUTHORITY
                !vehicle.isVehicleActionFireAllowed() || weaponName == null && !permitsSelectedAttempt() -> ShotRejectionReason.ACTION_BLOCKED
                else -> null
            }
            if (rejected != null) return ShotResult.rejected(rejected, selectedName)
            return com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager.tryFireStore(vehicle, living, selectedName)
                ?: ShotResult.rejected(ShotRejectionReason.NO_WEAPON, selectedName)
        }
        return VehicleShotTransaction.execute(
            weaponName, vehicle.isWreck, serverLevel != null,
            allowsFire = {
                vehicle.isVehicleActionFireAllowed() && (weaponName != null || permitsSelectedAttempt())
            },
            resolve = {
                val name = weaponName ?: vehicle.getGunName(vehicle.getSeatIndex(living))
                if (name == null || vehicle.getGunData(name) == null) null
                else if (weaponName == null) vehicle.resolveMuzzleFrame(living, 1f)
                else vehicle.resolveMuzzleFrame(name, 1f)
            },
            shoot = { muzzle ->
                var result = ShotResult.rejected(ShotRejectionReason.CANNOT_SHOOT, muzzle.weaponName)
                // Pin the channel before callbacks. Changing selection cannot redirect ammo,
                // belt advancement, recoil, or sound to another weapon during this transaction.
                vehicle.modifyGunData(muzzle.weaponName) { data ->
                    result = performShot(living, serverLevel!!, data, muzzle, targetEntityUuid, targetPos)
                }
                result
            },
            accepted = { muzzle ->
                vehicle.afterShoot(vehicle.getGunData(muzzle.weaponName), muzzle.direction)
                if (shouldEmitNativeSound()) vehicle.playShootSound3p(living, muzzle.weaponName)
            },
        )
    }

    private fun performShot(
        living: LivingEntity?, serverLevel: ServerLevel, data: GunData,
        muzzle: VehicleMuzzleFrame, targetEntityUuid: UUID?, targetPos: Vec3?,
    ): ShotResult {
        if (!com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager.allowsWeapon(vehicle, muzzle.weaponName)) {
            return ShotResult.rejected(ShotRejectionReason.CANNOT_SHOOT, muzzle.weaponName)
        }
        val station = vehicle.computed().passengerWeaponStationBinding
        if (station != null && (muzzle.weaponName == station.weaponId || muzzle.weaponName in station.weaponIds) &&
            !station.permitsFire(-vehicle.gunYRot, -vehicle.gunXRot)) {
            VehicleWeaponShotDiagnostics.recordBoundary(vehicle, living, muzzle.weaponName,
                VehicleWeaponShotDiagnostics.Boundary.CAN_SHOOT, "STATION_FIRE_EXCLUSION")
            return ShotResult.rejected(ShotRejectionReason.CANNOT_SHOOT, muzzle.weaponName)
        }
        if (!permitsLandingGearShot(
                data.get(GunProp.REQUIRES_RETRACTED_LANDING_GEAR),
                vehicle.gearUp, vehicle.synchedGearRot,
            )) {
            VehicleWeaponShotDiagnostics.recordBoundary(
                vehicle, living, muzzle.weaponName, VehicleWeaponShotDiagnostics.Boundary.CAN_SHOOT,
                "LANDING_GEAR_NOT_RETRACTED",
            )
            return ShotResult.rejected(ShotRejectionReason.CANNOT_SHOOT, muzzle.weaponName)
        }
        val belt = data.resolveProjectileBelt()
        if (belt.status == ProjectileBeltResolutionStatus.INVALID) {
            VehicleWeaponShotDiagnostics.recordBoundary(
                vehicle, living, muzzle.weaponName, VehicleWeaponShotDiagnostics.Boundary.BELT,
                belt.failure?.name ?: "INVALID_RESOLUTION",
            )
            return ShotResult.rejected(ShotRejectionReason.PROJECTILE_CREATION_FAILED, muzzle.weaponName)
        }
        val guidance = vehicle.captureVehicleWeaponGuidanceContext(living, muzzle.weaponName, belt.projectileData ?: data)
        val result = data.shootWithResult(ShootParameters(
            vehicle.ammoSupplier, living, serverLevel, muzzle.position, muzzle.direction,
            data, data.get(GunProp.SPREAD), true, targetEntityUuid, targetPos,
            shouldEmitNativeSound(), muzzle.effectPosition, muzzle.effectDirection,
            muzzle.frameReference, guidance, belt.projectileData, belt.round?.tracer,
        )).withWeaponName(muzzle.weaponName)
        if (result.reason == ShotRejectionReason.CANNOT_SHOOT) {
            VehicleWeaponShotDiagnostics.recordCanShootFailure(vehicle, living, muzzle.weaponName, data)
        }
        if (result.isAccepted() && belt.status == ProjectileBeltResolutionStatus.READY) data.advanceProjectileBelt()
        return result
    }

    /** Tick the detached live state; publish immutable copies only for weapons that changed. */
    fun tickWeapons() {
        val live = vehicle.gunDataMap
        val published = vehicle.publishedGunDataSnapshot()
        for ((name, data) in live) {
            if (com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.isPod(vehicle, name) &&
                name !in com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.equipped(vehicle)) continue
            data.vehicleWeaponIdentity = name
            if (data.get(GunProp.BELT_FED)) {
                val capacity = data.get(GunProp.MAGAZINE)
                if (capacity > 0 && data.ammo.get() !in 0..capacity) {
                    data.ammo.set(data.ammo.get().coerceIn(0, capacity))
                }
            }
            data.tick(vehicle, true)
        }
        val snapshot = WeaponSnapshotPublisher.changedSnapshot(live, published, GunData::copy) ?: return
        vehicle.publishWeaponRuntimeSnapshot(snapshot)
        // Associate the live cache with the published snapshot. External packets/assignments
        // fail the owner identity check and rebuild from their authoritative snapshot.
        weaponState.published(snapshot)
    }

    fun resolveGunDataMap(
        rawMap: Map<String, GunData>,
        config: DefaultVehicleData,
    ): Map<String, GunData> {
        return weaponState.resolve(rawMap, config) {
            val newMap = LinkedHashMap<String, GunData>(config.weapons().size)
            for (kv in config.weapons().entries) {
                val oldData = rawMap[kv.key]
                val stack = oldData?.stack?.copy() ?: ItemStack(ModItems.VEHICLE_GUN.get())
                val data = GunData.from(stack) { kv.value }
                // The generic vehicle_gun stack is shared by every channel. Preserve the authored
                // identity so per-weapon heat, reload, diagnostics, and presentation never collide.
                data.vehicleWeaponIdentity = kv.key
                newMap[kv.key] = data
            }
            newMap
        }
    }

    fun invalidateResolvedGunData() {
        weaponState.clear()
        selectionCache.clear()
        clientSelection.fill(null)
    }

    fun invalidateConfiguration() {
        weaponState.invalidateConfiguration()
        selectionCache.clear()
        clientSelection.fill(null)
        normalizationDataOwner = null
        normalizationFingerprint = Int.MIN_VALUE
    }

    fun validWeaponIndices(seatIndex: Int): List<Int> {
        val level = vehicle.level()
        if (level.isClientSide && seatIndex in clientSelection.indices) {
            val time = level.gameTime
            clientSelection[seatIndex]?.let { (at, indices) -> if (at == time) return indices }
            return validWeaponIndicesUncached(seatIndex).also { clientSelection[seatIndex] = time to it }
        }
        return validWeaponIndicesUncached(seatIndex)
    }

    private fun validWeaponIndicesUncached(seatIndex: Int): List<Int> {
        val weapons = vehicle.getWeaponIds(seatIndex)
        val aircraft = com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager.definition(vehicle) != null
        val revision = if (aircraft) com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager.weaponSelectionRevision(vehicle) else 0
        val ammo = if (aircraft) vehicle.gunDataMap.map { (id, gun) -> id to gun.ammo.get() } else emptyList()
        val owner = vehicle.computed()
        val previous = selectionCache[seatIndex]
        if (aircraft && previous != null && previous.tick == vehicle.tickCount && previous.revision == revision &&
            previous.owner === owner && previous.nativeAmmo == ammo && previous.weapons == weapons) return previous.indices
        val indices = weapons.indices.filter { index ->
            val name = weapons.getOrNull(index)
            !name.isNullOrBlank() && vehicle.getGunData(name) != null &&
                com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager.selectableWeapon(vehicle, name)
        }
        if (aircraft && seatIndex in 0 until vehicle.maxPassengers)
            selectionCache[seatIndex] = SelectionCache(vehicle.tickCount, revision, owner, ammo, weapons, indices)
        return indices
    }

    fun resolvedPrimaryIndex(seatIndex: Int, ordered: List<Int>): Int {
        if (ordered.isEmpty()) return -1
        val persisted = vehicle.selectedWeapon.getOrNull(seatIndex)
        return persisted?.takeIf { it in ordered } ?: ordered.first()
    }

    fun resolvedSecondaryIndex(
        seatIndex: Int,
        ordered: List<Int>,
        primaryIndex: Int,
    ): Int? {
        if (ordered.size < 2 || primaryIndex !in ordered) return null
        val persisted = vehicle.secondaryWeapon.getOrNull(seatIndex)
        return persisted?.takeIf { it in ordered && it != primaryIndex }
            ?: ordered.firstOrNull { it != primaryIndex }
    }

    fun selectedWeaponIndex(seatIndex: Int): Int {
        val ordered = validWeaponIndices(seatIndex)
        return resolvedPrimaryIndex(seatIndex, ordered)
    }

    fun secondaryWeaponIndex(seatIndex: Int): Int? {
        val ordered = validWeaponIndices(seatIndex)
        if (ordered.size < 2) return null
        return resolvedSecondaryIndex(seatIndex, ordered, resolvedPrimaryIndex(seatIndex, ordered))
    }

    fun setWeaponSlotIndex(
        seatIndex: Int,
        slot: VehicleWeaponSlot,
        targetWeaponIndex: Int,
    ): Boolean {
        val ordered = validWeaponIndices(seatIndex)
        if (ordered.isEmpty() || targetWeaponIndex !in ordered) return false

        val oldPrimary = resolvedPrimaryIndex(seatIndex, ordered)
        val oldSecondary = resolvedSecondaryIndex(seatIndex, ordered, oldPrimary)
        if (oldPrimary < 0) return false

        val nextPrimary: Int
        val nextSecondary: Int?
        when (slot) {
            VehicleWeaponSlot.PRIMARY -> {
                nextPrimary = targetWeaponIndex
                nextSecondary = if (targetWeaponIndex == oldSecondary) {
                    oldPrimary.takeIf { it >= 0 }
                } else {
                    oldSecondary?.takeIf { it != targetWeaponIndex }
                }
            }

            VehicleWeaponSlot.SECONDARY -> {
                if (targetWeaponIndex == oldPrimary) {
                    nextPrimary = oldSecondary ?: return false
                    nextSecondary = oldPrimary
                } else {
                    nextPrimary = oldPrimary
                    nextSecondary = targetWeaponIndex
                }
            }
        }

        if (nextPrimary == nextSecondary) return false
        return applyWeaponSlotState(seatIndex, nextPrimary, nextSecondary)
    }

    fun cycleWeaponSlot(
        seatIndex: Int,
        slot: VehicleWeaponSlot,
        delta: Int,
    ): Boolean {
        val ordered = validWeaponIndices(seatIndex)
        if (ordered.isEmpty()) return false
        val primary = resolvedPrimaryIndex(seatIndex, ordered)
        val secondary = resolvedSecondaryIndex(seatIndex, ordered, primary)
        val blocked = if (slot == VehicleWeaponSlot.PRIMARY) secondary else primary
        val candidates = ordered.filter { it != blocked }
        if (candidates.size < 2) return false
        val current = when (slot) {
            VehicleWeaponSlot.PRIMARY -> primary
            VehicleWeaponSlot.SECONDARY -> secondary ?: return false
        }
        val ordinal = candidates.indexOf(current)
        if (ordinal < 0) return false
        val nextOrdinal = java.lang.Math.floorMod(
            ordinal.toLong() + delta.toLong(),
            candidates.size.toLong(),
        ).toInt()
        return setWeaponSlotIndex(seatIndex, slot, candidates[nextOrdinal])
    }

    private fun applyWeaponSlotState(
        seatIndex: Int,
        primaryIndex: Int,
        secondaryIndex: Int?,
    ): Boolean {
        val valid = List(vehicle.maxPassengers, ::validWeaponIndices)
        val old = VehicleWeaponSlots.normalize(valid, vehicle.selectedWeapon, vehicle.secondaryWeapon)
        val next = old.select(valid, seatIndex, primaryIndex, secondaryIndex) ?: return false
        if (next == old) return false
        val oldPrimary = old.primary[seatIndex]
        val oldSecondary = old.secondary[seatIndex].takeIf { it >= 0 }

        // Commit the complete state before notifications/withdrawals can inspect it.
        vehicle.selectedWeapon = next.primary
        vehicle.secondaryWeapon = next.secondary
        linkedSetOf<Int>().apply {
            if (oldPrimary != primaryIndex) add(oldPrimary)
            if (oldSecondary != secondaryIndex && oldSecondary != null) add(oldSecondary)
        }
            .filter { it >= 0 }
            .forEach { vehicle.withdrawWeaponOnSelectionChange(seatIndex, it) }

        if (oldPrimary != primaryIndex) {
            vehicle.notifyWeaponContextChanged(seatIndex, oldPrimary, primaryIndex)
        }
        return true
    }

    /** Resize and normalize both persisted arrays before publishing any observer callbacks. */
    fun normalizePersistedWeaponSlots() {
        if (vehicle.level().isClientSide) return
        val data = vehicle.computed()
        val fingerprint = weaponSlotNormalizationFingerprint(data)
        if (normalizationDataOwner === data &&
            normalizationFingerprint == fingerprint
        ) return

        val oldPrimary = vehicle.selectedWeapon
        val oldSecondary = vehicle.secondaryWeapon
        val valid = List(vehicle.maxPassengers, ::validWeaponIndices)
        val next = VehicleWeaponSlots.normalize(valid, oldPrimary, oldSecondary)
        if (oldPrimary != next.primary) vehicle.selectedWeapon = next.primary
        if (oldSecondary != next.secondary) vehicle.secondaryWeapon = next.secondary
        for (seat in next.primary.indices) {
            val previous = oldPrimary.getOrNull(seat) ?: -1
            if (previous != next.primary[seat]) vehicle.notifyWeaponContextChanged(seat, previous, next.primary[seat])
        }
        normalizationDataOwner = data
        normalizationFingerprint = weaponSlotNormalizationFingerprint(data)
    }

    private fun weaponSlotNormalizationFingerprint(data: DefaultVehicleData): Int {
        var result = System.identityHashCode(data)
        result = 31 * result + com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager.weaponSelectionRevision(vehicle)
        result = 31 * result + vehicle.selectedWeapon.hashCode()
        result = 31 * result + vehicle.secondaryWeapon.hashCode()
        return result
    }
}
