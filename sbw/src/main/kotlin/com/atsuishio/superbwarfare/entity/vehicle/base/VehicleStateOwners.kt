package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimPresentationFrame
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimProfile
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentationTimeline
import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData
import com.atsuishio.superbwarfare.network.VehicleHelicopterAtgmCameraRayState
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.function.Function

/**
 * Explicit state boundaries for [VehicleEntity]. These objects change ownership only: the
 * entity remains the compatibility facade and the existing NBT keys, data accessors and public
 * property accessors remain unchanged.
 */
internal class VehiclePersistentStateOwner {
    var initialPassengerReloadProgressApplied = false
}

internal class VehicleSynchronizedStateOwner {
    var vehicleInputBits: Short = 0
    var resolvedGunDataRawOwner: Map<String, GunData>? = null
    var resolvedGunDataConfigOwner: DefaultVehicleData? = null
    var resolvedGunDataMap: Map<String, GunData> = emptyMap()
    var weaponSlotNormalizationDataOwner: DefaultVehicleData? = null
    var weaponSlotNormalizationFingerprint: Int = Int.MIN_VALUE
}

internal class VehicleKinematicStateOwner {
    var lastRequestedMovement: Vec3 = Vec3.ZERO
    var lastResolvedMovement: Vec3 = Vec3.ZERO
    var lastCollisionStepCandidateDeltaY: Double = 0.0
    var lastCollisionStepAppliedDeltaY: Double = 0.0
}

internal class VehicleCombatStateOwner {
    val acceptedHelicopterAtgmCameraRays =
        LinkedHashMap<HelicopterAtgmCameraRayKey, VehicleHelicopterAtgmCameraRayState>()
    val genericModuleStates = LinkedHashMap<ResourceLocation, StoredVehicleModuleState>()
    var genericModuleSnapshotCache: String? = null
    var obbCache: MutableList<OBB>? = null
}

internal class VehicleSeatingStateOwner<T : Any>(
    val slots: MutableList<T?>,
) {
    private var resizing = false

    /** Removed occupants detach while their old seat still exists, including reentrant queries. */
    fun ensureSize(target: Int, detach: (T) -> Unit) {
        require(target >= 0) { "Seat count must not be negative" }
        if (resizing || target == slots.size) return
        resizing = true
        try {
            if (target < slots.size) slots.drop(target).filterNotNull().forEach(detach)
            while (slots.size > target) slots.removeLast()
            while (slots.size < target) slots.add(null)
        } finally {
            resizing = false
        }
    }

    fun first(): T? = slots.firstOrNull()

    fun at(index: Int): T? = slots.getOrNull(index)

    fun indexOf(entity: T?): Int = if (entity == null) -1 else slots.indexOf(entity)

    fun insert(entity: T, indexOverride: Function<T, Int>?): Int {
        val existing = slots.indexOf(entity)
        if (existing >= 0) return existing
        val requested = indexOverride?.apply(entity) ?: -1
        val index = if (requested == -1) slots.indexOfFirst { it == null } else requested
        if (index !in slots.indices || slots[index] != null) return -1
        slots[index] = entity
        return index
    }

    fun remove(entity: T): Int {
        val index = slots.indexOf(entity)
        if (index < 0) return -1
        slots[index] = null
        return index
    }

    fun move(entity: T, targetIndex: Int): Boolean {
        if (targetIndex !in slots.indices || slots[targetIndex] != null) return false
        val previousIndex = slots.indexOf(entity)
        if (previousIndex < 0) return false
        slots[previousIndex] = null
        slots[targetIndex] = entity
        return true
    }
}

internal class VehicleLifecycleStateOwner {
    var wasEngineRunning = false
    var wasHornWorking = false
    var wasStuka = false
    var wasHeliCrash = false
    var wasVehicleSkip = false
    var wasFiring = false
}

internal class VehicleClientPresentationStateOwner {
    var vehicleAimProfileDataOwner: DefaultVehicleData? = null
    val vehicleAimProfileCache = HashMap<Long, VehicleAimProfile?>()

    var vehiclePoseSequence = 0
    var vehiclePosePrevious = VehiclePoseSnapshot.IDENTITY
    var vehiclePoseCurrent = VehiclePoseSnapshot.IDENTITY
    var vehiclePosePayload = ""
    var vehiclePoseClientUpdateTick = Int.MIN_VALUE
    var vehiclePoseAbsolutePending: PendingVehiclePoseAbsolute? = null
    val vehicleChassisPresentationTimeline = VehicleChassisPresentationTimeline()
    var chassisPresentationCache: VehicleChassisPresentation? = null
    var chassisPresentationCacheTick = Int.MIN_VALUE
    var chassisPresentationCachePartialBits = 0

    val aimPresentationCache = HashMap<AimPresentationCacheKey, VehicleAimPresentationFrame?>()
    var aimPresentationCacheTick = Int.MIN_VALUE
    var aimPresentationCachePartialBits = 0
    var aimPresentationCacheEpoch = Int.MIN_VALUE
    val aimPresentationContinuity = HashMap<AimPresentationCacheKey, VehicleAimPresentationFrame>()
    val aimPresentationResolvedSequences = HashMap<AimPresentationCacheKey, Int>()
    val aimPresentationTraceCacheHits = HashSet<AimPresentationCacheKey>()
    var aimPresentationContinuityEpoch = Int.MIN_VALUE
    val aimPresentationAdmissionReasons =
        LinkedHashMap<AimPresentationCacheKey, AimPresentationAdmissionReason>()
    var aimPresentationControllerEpochInitialized = false
    var aimPresentationTurretControllerUuid: UUID? = null
    var aimPresentationStationControllerUuid: UUID? = null
}

internal data class HelicopterAtgmCameraRayKey(val controllerUuid: UUID, val seatIndex: Int)

internal data class PendingVehiclePoseAbsolute(
    val target: Vec3,
    val wireYaw: Float,
    val afterSequence: Int,
    val hardDiscontinuity: Boolean,
)

internal data class AimPresentationCacheKey(
    val channel: VehicleAimChannel,
    val seatIndex: Int,
    val weaponIndex: Int,
)

internal enum class AimPresentationAdmissionReason(val metricName: String) {
    ADMIT_EXACT("presentation.aim.admit_exact"),
    ADMIT_HELD("presentation.aim.admit_held"),
    ADMIT_BLEND("presentation.aim.admit_blend"),
    ADMIT_REBOUND("presentation.aim.admit_rebound"),
    REJECT_APPLICABILITY("presentation.aim.reject_applicability"),
    REJECT_WEAPON("presentation.aim.reject_weapon"),
    REJECT_AIM("presentation.aim.reject_aim"),
    REJECT_MODE("presentation.aim.reject_mode"),
    REJECT_CHASSIS("presentation.aim.reject_chassis"),
    REJECT_PARENT("presentation.aim.reject_parent"),
    REJECT_PARENT_TIME("presentation.aim.reject_parent_time"),
    REJECT_ATTACHMENTS("presentation.aim.reject_attachments"),
    REJECT_DIRECTION("presentation.aim.reject_direction"),
    REJECT_MUZZLE("presentation.aim.reject_muzzle"),
}

internal data class StoredVehicleModuleState(
    val maxHealth: Float,
    val health: Float,
    val destroyed: Boolean,
)
