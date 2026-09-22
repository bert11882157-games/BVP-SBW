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

/**
 * Owns vehicle GunData, deterministic slot policy, and the accepted-shot transaction.
 *
 * Replicated selections, persistence, world effects, aim notifications, and the public ABI remain
 * on [VehicleEntity]. All base-entity shooting overloads enter [fire]; none has its own admission
 * or ammo/effects implementation that can diverge from the primary/secondary scheduler.
 */
internal class VehicleWeaponRuntime(
    private val vehicle: VehicleEntity,
    private val stateOwner: VehicleSynchronizedStateOwner,
    private val shouldEmitNativeSound: () -> Boolean,
) {
    /** Null [weaponName] resolves the occupied seat's primary; a name is an explicit channel. */
    fun fire(
        living: LivingEntity?,
        weaponName: String?,
        targetEntityUuid: UUID?,
        targetPos: Vec3?,
        permitsSelectedAttempt: () -> Boolean,
    ): ShotResult {
        val serverLevel = vehicle.level() as? ServerLevel
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
            data.vehicleWeaponIdentity = name
            data.tick(vehicle, true)
        }
        val snapshot = WeaponSnapshotPublisher.changedSnapshot(live, published, GunData::copy) ?: return
        vehicle.publishWeaponRuntimeSnapshot(snapshot)
        // Keep the live owner warm after our own publication. External packets/assignments
        // still fail the owner identity check and rebuild from their authoritative snapshot.
        stateOwner.resolvedGunDataRawOwner = snapshot
    }

    fun resolveGunDataMap(
        rawMap: Map<String, GunData>,
        config: DefaultVehicleData,
    ): Map<String, GunData> {
        if (stateOwner.resolvedGunDataRawOwner === rawMap &&
            stateOwner.resolvedGunDataConfigOwner === config
        ) {
            return stateOwner.resolvedGunDataMap
        }
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

        stateOwner.resolvedGunDataRawOwner = rawMap
        stateOwner.resolvedGunDataConfigOwner = config
        stateOwner.resolvedGunDataMap = newMap
        return newMap
    }

    fun invalidateResolvedGunData() {
        stateOwner.resolvedGunDataRawOwner = null
        stateOwner.resolvedGunDataConfigOwner = null
        stateOwner.resolvedGunDataMap = emptyMap()
    }

    fun invalidateConfiguration() {
        stateOwner.resolvedGunDataConfigOwner = null
        stateOwner.weaponSlotNormalizationDataOwner = null
        stateOwner.weaponSlotNormalizationFingerprint = Int.MIN_VALUE
    }

    fun validWeaponIndices(seatIndex: Int): List<Int> {
        val weapons = vehicle.getSeat(seatIndex)?.weapons() ?: return emptyList()
        return weapons.indices.filter { index ->
            val name = weapons.getOrNull(index)
            !name.isNullOrBlank() && vehicle.getGunData(name) != null
        }
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
        if (stateOwner.weaponSlotNormalizationDataOwner === data &&
            stateOwner.weaponSlotNormalizationFingerprint == fingerprint
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
        stateOwner.weaponSlotNormalizationDataOwner = data
        stateOwner.weaponSlotNormalizationFingerprint = weaponSlotNormalizationFingerprint(data)
    }

    private fun weaponSlotNormalizationFingerprint(data: DefaultVehicleData): Int {
        var result = System.identityHashCode(data)
        result = 31 * result + vehicle.selectedWeapon.hashCode()
        result = 31 * result + vehicle.secondaryWeapon.hashCode()
        return result
    }
}
