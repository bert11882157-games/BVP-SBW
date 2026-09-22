package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleMuzzleFrame
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.perk.Perk
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.min

object VehicleShotPredictionService {
    const val MAX_PREDICTION_PATH_BLOCKS = 4096.0
    const val MAX_PREDICTION_TICKS = 160
    private const val CLIENT_OWNER_DISTANCE_UNCERTAINTY_BLOCKS = 64.0
    private const val STALE_POSITION_DELTA_SQ = 1.0E-4
    private const val STALE_DIRECTION_DELTA_SQ = 1.0E-8
    private const val STALE_MOTION_DELTA_SQ = 1.0E-6
    private data class ResolvedBallistics(
        val projectileTypeId: ResourceLocation,
        val projectileProfileId: ResourceLocation?,
        val projectileLife: Int,
    )
    private data class BallisticsResolution(
        val ballistics: ResolvedBallistics? = null,
        val diagnostic: NominalShotDiagnostic = NominalShotDiagnostic.NONE,
    )

    /** Client presentation only. Call once at client tick END with partialTicks=1F and cache the result. */
    @JvmStatic
    fun predictClientIfFiredNow(
        vehicle: VehicleEntity,
        controller: Entity,
        partialTicks: Float,
    ): NominalShotResult {
        if (partialTicks != 1F) {
            return NominalShotResult(NominalShotStatus.INVALID_CONTEXT,
                diagnostic = NominalShotDiagnostic.CLIENT_CONTEXT)
        }
        return predictClientSampledIfFiredNow(vehicle, controller, partialTicks)
    }

    /**
     * Latest-only cursor guard for the client CCIP scheduler. The tolerances cover sub-millimeter
     * floating-point churn but invalidate work when the physical launch tuple or any ballistic,
     * platform, owner, or frame semantic changes materially.
     */
    @JvmStatic
    fun isClientNominalSnapshotMateriallyStale(
        pending: NominalShotSnapshot,
        latest: NominalShotSnapshot,
    ): Boolean {
        if (pending.vehicleUuid != latest.vehicleUuid ||
            pending.seatIndex != latest.seatIndex ||
            pending.selectedWeaponIndex != latest.selectedWeaponIndex ||
            pending.weaponName != latest.weaponName ||
            pending.projectileTypeId != latest.projectileTypeId ||
            pending.projectileProfileId != latest.projectileProfileId ||
            pending.configuredLifetimeTicks != latest.configuredLifetimeTicks ||
            pending.horizonTicks != latest.horizonTicks ||
            pending.collisionModel != latest.collisionModel ||
            pending.motionModel != latest.motionModel ||
            frameSemanticsChanged(pending.muzzle.frameReference, latest.muzzle.frameReference) ||
            pending.ownerKinematics == null != (latest.ownerKinematics == null)
        ) return true
        if (vectorChanged(pending.muzzle.position, latest.muzzle.position, STALE_POSITION_DELTA_SQ) ||
            vectorChanged(pending.muzzle.direction, latest.muzzle.direction, STALE_DIRECTION_DELTA_SQ) ||
            vectorChanged(pending.inheritedPlatformMotion, latest.inheritedPlatformMotion, STALE_MOTION_DELTA_SQ) ||
            vectorChanged(pending.initialMotion, latest.initialMotion, STALE_MOTION_DELTA_SQ)
        ) return true
        if (!pending.launchSpeedBlocksPerTick.closeTo(latest.launchSpeedBlocksPerTick) ||
            !pending.gravityPerTick.closeTo(latest.gravityPerTick) ||
            !pending.supportedMaxRangeBlocks.closeTo(latest.supportedMaxRangeBlocks)
        ) return true
        val pendingOwner = pending.ownerKinematics
        val latestOwner = latest.ownerKinematics
        if (pendingOwner != null && latestOwner != null && (
                vectorChanged(pendingOwner.position, latestOwner.position, STALE_POSITION_DELTA_SQ) ||
                    vectorChanged(pendingOwner.motionPerTick, latestOwner.motionPerTick, STALE_MOTION_DELTA_SQ) ||
                    !pendingOwner.maximumDistanceBlocks.closeTo(latestOwner.maximumDistanceBlocks) ||
                    !pendingOwner.uncertaintyMarginBlocks.closeTo(latestOwner.uncertaintyMarginBlocks)
                )
        ) return true
        return false
    }

    /**
     * Pure client-presentation sample for bounded render-cadence CCIP evaluation.
     *
     * The caller owns cadence and context resets and must invoke this only from Minecraft's
     * client/render thread. Each call freezes one selected physical muzzle tuple, then runs the
     * same side-effect-free nominal kernel as the server service or reuses its exact same-tick
     * collision result. Platform motion is inherited exactly once only when live fire requests it.
     * Flight vehicles use the freshest complete sequenced server instrument sample: inherited
     * launch velocity must model the accepted server shot, not the deliberately delayed
     * body-presentation interpolation. Every other client context remains fail-closed.
     */
    /**
     * Client capture-only seam for resumable CCIP. This freezes selected weapon, muzzle,
     * descriptor, motion, and owner context but never traverses collision or touches the cache.
     */
    @JvmStatic
    fun captureClientNominalSnapshotIfFiredNow(
        vehicle: VehicleEntity,
        controller: Entity,
        partialTicks: Float,
    ): NominalShotCapture {
        if (!vehicle.level().isClientSide || controller.vehicle !== vehicle) {
            return NominalShotCapture(NominalShotStatus.INVALID_CONTEXT,
                diagnostic = NominalShotDiagnostic.CLIENT_CONTEXT)
        }
        if (!partialTicks.isFinite() || partialTicks !in 0F..1F) {
            return NominalShotCapture(NominalShotStatus.INVALID_CONTEXT,
                diagnostic = NominalShotDiagnostic.CLIENT_SAMPLE_TIME_INVALID)
        }
        val seatIndex = vehicle.getSeatIndex(controller)
        val weaponIndex = vehicle.getSelectedWeapon(seatIndex)
        val profile = vehicle.resolveVehicleAimProfile(seatIndex, weaponIndex)
        val flightStrategy = vehicle.resolveVehicleFlightStrategy()
        val flightMotion = if (flightStrategy != null) {
            vehicle.getVehicleFlightInstrumentSnapshot(1F)
                .takeIf { it.sequence != 0 && valid(it.motion) }
                ?.motion
        } else null
        val muzzle = if (profile != null && flightStrategy == null) {
            vehicle.resolveAimPresentationFrame(controller, partialTicks)?.muzzle
        } else {
            // Flight vehicles have no provider-backed aim presentation frame. Freeze exactly one
            // current selected-weapon transform at the caller's bounded client sample time.
            vehicle.resolveMuzzleFrame(controller, partialTicks)
        }
        return captureSelected(vehicle, controller, muzzle, flightMotion)
    }

    @JvmStatic
    fun predictClientSampledIfFiredNow(
        vehicle: VehicleEntity,
        controller: Entity,
        partialTicks: Float,
    ): NominalShotResult {
        val capture = captureClientNominalSnapshotIfFiredNow(vehicle, controller, partialTicks)
        val snapshot = capture.snapshot ?: run {
            ClientNominalCollisionResultCache.invalidate()
            return NominalShotResult(capture.status, diagnostic = capture.diagnostic)
        }
        val gunData = vehicle.getGunData(snapshot.seatIndex, snapshot.selectedWeaponIndex) ?: run {
            ClientNominalCollisionResultCache.invalidate()
            return NominalShotResult(
                NominalShotStatus.INVALID_CONTEXT,
                diagnostic = NominalShotDiagnostic.GUN_DATA_UNAVAILABLE,
            )
        }
        val gunState = ClientNominalCollisionResultCache.captureGunState(gunData)
        ClientNominalCollisionResultCache.lookup(vehicle.level(), snapshot, gunState)?.let {
            return it
        }
        return predictFirstBlock(snapshot, vehicle.level()).also {
            ClientNominalCollisionResultCache.store(vehicle.level(), snapshot, gunState, it)
        }
    }

    @JvmStatic
    fun predictFirstBlock(snapshot: NominalShotSnapshot, level: Level): NominalShotResult {
        if (!valid(snapshot.muzzle.position) || !valid(snapshot.initialMotion) ||
            snapshot.ownerKinematics?.let { !valid(it.position) || !valid(it.motionPerTick) } == true
        ) {
            return NominalShotResult(NominalShotStatus.NON_FINITE,
                diagnostic = NominalShotDiagnostic.LAUNCH_STATE_NON_FINITE)
        }
        val cursor = ClientNominalPredictionEngine.begin(snapshot, level)
        val completed = ClientNominalPredictionEngine.advance(
            cursor,
            snapshot,
            level,
            NominalPredictionSliceBudget(
                maxSegments = Int.MAX_VALUE,
                maxVoxelVisits = Int.MAX_VALUE,
                maxNanos = 0L,
            ),
        )
        return completed.result ?: NominalShotResult(
            NominalShotStatus.INVALID_CONTEXT,
            diagnostic = NominalShotDiagnostic.PREDICTION_CANCELLED,
        )
    }

    /**
     * Captures an immutable client collision cursor. Relative trajectory preparation is bounded
     * by the existing 160-tick horizon; no world collision traversal is performed here.
     */
    @JvmStatic
    fun beginClientNominalPrediction(
        snapshot: NominalShotSnapshot,
        level: Level,
    ): NominalPredictionCursor = ClientNominalPredictionEngine.begin(snapshot, level)

    /**
     * Advances the cursor by bounded scheduler work. Segment/voxel budgets are hard; the optional
     * nanosecond budget is checked around each bounded DDA advance and is therefore advisory for
     * that one call. IN_PROGRESS is not a ballistic failure: retain the returned cursor and resume
     * it. Snapshot or Level identity changes cancel work.
     */
    @JvmStatic
    fun advanceClientNominalPrediction(
        cursor: NominalPredictionCursor,
        snapshot: NominalShotSnapshot,
        level: Level,
        budget: NominalPredictionSliceBudget,
    ): NominalPredictionSlice = ClientNominalPredictionEngine.advance(cursor, snapshot, level, budget)

    /** Explicit lifecycle cancellation for level/vehicle/seat/weapon context teardown. */
    @JvmStatic
    fun cancelClientNominalPrediction(): NominalPredictionSlice = NominalPredictionSlice(
        NominalPredictionSliceStatus.CANCELLED,
        result = NominalShotResult(
            NominalShotStatus.INVALID_CONTEXT,
            diagnostic = NominalShotDiagnostic.PREDICTION_CANCELLED,
        ),
    )

    /**
     * Server-only entry point for the bounded fixed-yaw pitch solve. The caller must supply the
     * opaque HasFcsGAcquisition token. This method delegates to the pure predictor without
     * acquiring client, world, or firing authority.
     */
    @JvmStatic
    fun solveFreeAirPitchOnly(
        snapshot: NominalShotSnapshot,
        targetPoint: Vec3,
        family: PitchOnlyDirectionFamily,
        minElevationDegrees: Double,
        maxElevationDegrees: Double,
        acquisition: HasFcsGAcquisition?,
    ): PitchOnlyFireControlResult = PitchOnlyFireControl.solveFreeAirPitchOnly(
        snapshot,
        targetPoint,
        family,
        minElevationDegrees,
        maxElevationDegrees,
        acquisition,
    )

    @JvmStatic
    fun solveFreeAirPitchOnly(
        snapshot: NominalShotSnapshot,
        targetPoint: Vec3,
        targetRangeBlocks: Double,
        family: PitchOnlyDirectionFamily,
        minElevationDegrees: Double,
        maxElevationDegrees: Double,
        acquisition: HasFcsGAcquisition?,
    ): PitchOnlyFireControlResult = PitchOnlyFireControl.solveFreeAirPitchOnly(
        snapshot,
        targetPoint,
        targetRangeBlocks,
        family,
        minElevationDegrees,
        maxElevationDegrees,
        acquisition,
    )

    private fun captureSelected(
        vehicle: VehicleEntity,
        controller: Entity,
        muzzle: VehicleMuzzleFrame?,
        clientFlightMotion: Vec3? = null,
    ): NominalShotCaptureResult {
        val seatIndex = vehicle.getSeatIndex(controller)
        if (seatIndex < 0) return NominalShotCaptureResult(NominalShotStatus.INVALID_CONTEXT,
            diagnostic = NominalShotDiagnostic.SEAT_OR_WEAPON_CONTEXT)
        val weaponIndex = vehicle.getSelectedWeapon(seatIndex)
        val weaponName = vehicle.getGunName(seatIndex, weaponIndex)
            ?: return NominalShotCaptureResult(NominalShotStatus.INVALID_CONTEXT,
                diagnostic = NominalShotDiagnostic.SEAT_OR_WEAPON_CONTEXT)
        val data = vehicle.getGunData(seatIndex, weaponIndex)
            ?: return NominalShotCaptureResult(NominalShotStatus.INVALID_CONTEXT,
                diagnostic = NominalShotDiagnostic.GUN_DATA_UNAVAILABLE)
        if (muzzle == null) {
            return NominalShotCaptureResult(NominalShotStatus.INVALID_CONTEXT,
                diagnostic = NominalShotDiagnostic.MUZZLE_FRAME_UNAVAILABLE)
        }
        if (muzzle.weaponName != weaponName || !validMuzzle(muzzle)) {
            return NominalShotCaptureResult(NominalShotStatus.INVALID_CONTEXT,
                diagnostic = NominalShotDiagnostic.MUZZLE_FRAME_MISMATCH)
        }
        if (Perk.Type.entries.any { data.perk.getInstances(it).isNotEmpty() }) {
            return NominalShotCaptureResult(NominalShotStatus.UNSUPPORTED,
                diagnostic = NominalShotDiagnostic.ACTIVE_PROJECTILE_PERK)
        }
        val resolution = resolveClientBallistics(data)
        val ballistics = resolution.ballistics
            ?: return NominalShotCaptureResult(NominalShotStatus.UNSUPPORTED,
                diagnostic = resolution.diagnostic)
        val model = NominalProjectileModels.model(ballistics.projectileTypeId)
            ?: return NominalShotCaptureResult(NominalShotStatus.UNSUPPORTED,
                diagnostic = NominalShotDiagnostic.PROJECTILE_MODEL_UNREGISTERED)
        if (com.atsuishio.superbwarfare.tools.VectorTool.isInLiquid(vehicle.level(), muzzle.position)) {
            return NominalShotCaptureResult(NominalShotStatus.UNSUPPORTED,
                diagnostic = NominalShotDiagnostic.MUZZLE_IN_LIQUID)
        }
        val addsPlatformMotion = data.get(GunProp.ADD_SHOOTER_DELTA_MOVEMENT)
        if (addsPlatformMotion && vehicle.level().isClientSide && clientFlightMotion == null) {
            return NominalShotCaptureResult(NominalShotStatus.UNSUPPORTED,
                diagnostic = NominalShotDiagnostic.CLIENT_PLATFORM_MOTION_UNAVAILABLE)
        }
        val inheritedMotion = when {
            !addsPlatformMotion -> Vec3.ZERO
            vehicle.level().isClientSide -> requireNotNull(clientFlightMotion)
            else -> controller.rootVehicle.deltaMovement
        }
        if (!valid(inheritedMotion)) return NominalShotCaptureResult(NominalShotStatus.NON_FINITE,
            diagnostic = NominalShotDiagnostic.LAUNCH_STATE_NON_FINITE)
        val launchSpeed = NominalProjectileMotion.launchSpeed(data, vehicle.level(), muzzle.position)
        val gravity = data.get(GunProp.GRAVITY).toFloat().toDouble()
        val lifetime = ballistics.projectileLife
        if (!launchSpeed.isFinite() || launchSpeed <= 0.0 || !gravity.isFinite() || gravity < 0.0 || lifetime < 0) {
            return NominalShotCaptureResult(NominalShotStatus.NON_FINITE,
                diagnostic = NominalShotDiagnostic.LAUNCH_STATE_NON_FINITE)
        }
        val initialMotion = NominalProjectileMotion.initialMotion(muzzle.direction, launchSpeed, inheritedMotion)
        if (!valid(initialMotion)) return NominalShotCaptureResult(NominalShotStatus.NON_FINITE,
            diagnostic = NominalShotDiagnostic.LAUNCH_STATE_NON_FINITE)
        val horizon = min(lifetime.toLong() + 1L, MAX_PREDICTION_TICKS.toLong()).toInt()
        val ownerKinematics = model.ownerDistanceLimitBlocks?.let { limit ->
            val ownerPosition = controller.position()
            val ownerMotion = controller.rootVehicle.deltaMovement
            if (!valid(ownerPosition) || !valid(ownerMotion)) {
                return NominalShotCaptureResult(NominalShotStatus.NON_FINITE,
                    diagnostic = NominalShotDiagnostic.OWNER_STATE_NON_FINITE)
            }
            NominalOwnerKinematics(
                ownerPosition,
                ownerMotion,
                limit,
                if (vehicle.level().isClientSide) CLIENT_OWNER_DISTANCE_UNCERTAINTY_BLOCKS else 0.0,
            )
        }
        val preliminarySnapshot = NominalShotSnapshot(
            vehicle.uuid,
            seatIndex,
            weaponIndex,
            weaponName,
            ballistics.projectileTypeId,
            ballistics.projectileProfileId,
            muzzle,
            launchSpeed,
            inheritedMotion,
            initialMotion,
            gravity,
            lifetime,
            horizon,
            MAX_PREDICTION_PATH_BLOCKS,
            model.collision,
            model.motion,
            ownerKinematics,
        )
        val supportedRange = NominalRelativeTrajectoryCache.get(preliminarySnapshot).supportedRangeBlocks
        if (!supportedRange.isFinite() || supportedRange <= 0.0) {
            return NominalShotCaptureResult(NominalShotStatus.NON_FINITE,
                diagnostic = NominalShotDiagnostic.LAUNCH_STATE_NON_FINITE)
        }
        val snapshot = preliminarySnapshot.copy(supportedMaxRangeBlocks = supportedRange)
        return NominalShotCaptureResult(NominalShotStatus.READY, snapshot)
    }

    private fun resolveClientBallistics(data: GunData): BallisticsResolution {
        val descriptor = data.get(GunProp.NOMINAL_BALLISTICS)
            ?: return BallisticsResolution(diagnostic = NominalShotDiagnostic.CLIENT_DESCRIPTOR_MISSING)
        if (!descriptor.supported) return BallisticsResolution(
            diagnostic = NominalShotDiagnostic.CLIENT_DESCRIPTOR_DISABLED)
        val type = ResourceLocation.tryParse(descriptor.projectileType.trim())
            ?: return BallisticsResolution(diagnostic = NominalShotDiagnostic.CLIENT_DESCRIPTOR_INVALID_TYPE)
        val rawProfile = descriptor.projectileProfile?.trim()?.takeIf(String::isNotEmpty)
        val profile = if (rawProfile == null) null else ResourceLocation.tryParse(rawProfile)
            ?: return BallisticsResolution(diagnostic = NominalShotDiagnostic.CLIENT_DESCRIPTOR_INVALID_PROFILE)
        if (descriptor.projectileLife < 0) return BallisticsResolution(
            diagnostic = NominalShotDiagnostic.CLIENT_DESCRIPTOR_INVALID_LIFETIME)
        return BallisticsResolution(ResolvedBallistics(type, profile, descriptor.projectileLife))
    }

    private fun validMuzzle(muzzle: VehicleMuzzleFrame): Boolean =
        valid(muzzle.position) && valid(muzzle.direction) && muzzle.direction.lengthSqr() > 1.0E-12

    private fun vectorChanged(first: Vec3, second: Vec3, toleranceSquared: Double): Boolean =
        !valid(first) || !valid(second) || first.distanceToSqr(second) > toleranceSquared

    private fun frameSemanticsChanged(
        first: com.atsuishio.superbwarfare.api.weapon.ShotFrameReference,
        second: com.atsuishio.superbwarfare.api.weapon.ShotFrameReference,
    ): Boolean = first.weaponName != second.weaponName ||
        first.positionSlot != second.positionSlot ||
        first.directionSlot != second.directionSlot ||
        first.muzzlePositionAttachment != second.muzzlePositionAttachment ||
        first.muzzleDirectionAttachment != second.muzzleDirectionAttachment ||
        first.effectPositionAttachment != second.effectPositionAttachment ||
        first.effectDirectionAttachment != second.effectDirectionAttachment

    private fun Double.closeTo(other: Double): Boolean =
        isFinite() && other.isFinite() && kotlin.math.abs(this - other) <= 1.0E-9

    private fun valid(vector: Vec3): Boolean = vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()
}
