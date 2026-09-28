package com.atsuishio.superbwarfare.api.vehicle.aim

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.VehicleRangeBallistics
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.HasFcsGAcquisition
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalBlockCollisionModel
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalMotionModel
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalOwnerKinematics
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileMotion
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalShotSnapshot
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.PitchOnlyDirectionFamily
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.PitchOnlySolveStatus
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.VehicleShotPredictionService
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleTransformSnapshot
import com.atsuishio.superbwarfare.network.AimPresentationTraceSample
import com.atsuishio.superbwarfare.network.NetworkTelemetry
import com.atsuishio.superbwarfare.data.gun.GunProp
import net.minecraft.util.Mth
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Server-authoritative target/actual servo for opted-in player weapon channels. */
class VehicleAimController(private val vehicle: VehicleEntity) {
    private val snapshots = EnumMap<VehicleAimChannel, VehicleAimSnapshot>(VehicleAimChannel::class.java)
    private val clientPresentationTimeline = VehicleAimPresentationTimeline()
    private var snapshotSequence = 0
    private var clientPayload = ""
    private var zoomController: Entity? = null
    private var zoomReceiptTick = -100L
    private var opticalZoom = 1F

    fun acceptOpticalZoom(player: Entity, magnification: Float) {
        if (vehicle.level().isClientSide || controller(VehicleAimChannel.TURRET) !== player) return
        zoomController = player
        zoomReceiptTick = vehicle.level().gameTime
        opticalZoom = if (magnification.isFinite()) magnification.coerceIn(1F, 24F) else 1F
    }
    private var closeAimYawMultiplier = 1F
    private var closeAimPitchMultiplier = 1F
    private var turretGeometricZeroDistanceBlocks =
        VehicleAimProfile.DEFAULT_GEOMETRIC_ZERO_DISTANCE_BLOCKS
    private var turretGeometricZeroController: Entity? = null
    private var turretGeometricZeroSeatIndex = -1
    private var turretGeometricZeroProfile: VehicleAimProfile? = null
    private var fcsZeroState: VehicleFcsZeroState? = null
    private var fcsZeroController: Entity? = null
    private var fcsZeroProfile: VehicleAimProfile? = null
    private var fcsZeroLevel: Level? = null
    private var fcsZeroLastRequestSequence = -1L
    private var fcsZeroRevision = 0L
    private var fcsZeroContextEpoch = 0L

    private data class LiveTurretContext(
        val controller: Entity,
        val seatIndex: Int,
        val weaponIndex: Int,
        val profile: VehicleAimProfile,
    )

    fun profileFor(controller: Entity?, channel: VehicleAimChannel): VehicleAimProfile? {
        controller ?: return null
        val seatIndex = vehicle.getSeatIndex(controller)
        if (seatIndex < 0) return null
        val weaponIndex = vehicle.getSelectedWeapon(seatIndex)
        return resolveProfile(seatIndex, weaponIndex)?.takeIf { it.channel == channel }
    }

    fun handles(controller: Entity?, channel: VehicleAimChannel): Boolean =
        controller is Player && profileFor(controller, channel) != null

    fun capturesMouseInput(controller: Player): Boolean {
        if (vehicle.level().isClientSide) return false
        for (channel in VehicleAimChannel.entries) {
            if (controller(channel) !== controller) continue
            val profile = profileFor(controller, channel) ?: continue
            if (profile.defaultMode == VehicleAimMode.PLAYER_LOOK_AIM) return true
        }
        return false
    }

    fun tickServer() {
        if (vehicle.level().isClientSide) return
        if (vehicle.isWreck) {
            resetTurretGeometricZeroState()
            clearFcsZeroState()
            snapshots.clear()
            vehicle.turretYRotLock = 0F
            vehicle.publishVehicleAimSnapshots(VehicleAimSnapshots.encode(emptyList<VehicleAimSnapshot>()))
            return
        }
        snapshots.clear()
        var turretContextSeen = false
        for (channel in VehicleAimChannel.entries) {
            val controller = controller(channel) ?: continue
            // The profiled player path retains the existing bounded mechanical servo. Mobs retain
            // SBW's established native auto-aim behavior.
            if (controller !is Player) continue
            val seatIndex = vehicle.getSeatIndex(controller)
            if (seatIndex < 0) continue
            val weaponIndex = vehicle.getSelectedWeapon(seatIndex)
            val profile = resolveProfile(seatIndex, weaponIndex) ?: continue
            if (profile.channel != channel) continue
            if (channel == VehicleAimChannel.TURRET) {
                if (profile.defaultMode == VehicleAimMode.PLAYER_LOOK_AIM &&
                    vehicle.resolveVehicleFlightStrategy() == null
                ) {
                    turretContextSeen = true
                    syncTurretGeometricZeroContext(controller, seatIndex, weaponIndex, profile)
                    syncFcsZeroContext(controller, seatIndex, weaponIndex, profile)
                } else {
                    resetTurretGeometricZeroState()
                    clearFcsZeroState()
                }
            }
            snapshots[channel] = updateChannel(controller, seatIndex, weaponIndex, profile)
        }
        if (!turretContextSeen) {
            resetTurretGeometricZeroState()
            clearFcsZeroState()
        }
        // A passenger station is authored beneath the main turret transform. Its immutable client
        // frame therefore needs the authoritative parent axes even when the turret seat is empty.
        // This publishes state only; it never runs a second servo or changes either physical axis.
        ensurePassiveParentTurretSnapshot()
        vehicle.publishVehicleAimSnapshots(VehicleAimSnapshots.encode(snapshots.values))
    }

    fun onPassengerRemoved(passenger: Entity) {
        if (this.controller(VehicleAimChannel.TURRET) === passenger ||
            turretGeometricZeroController === passenger ||
            fcsZeroController === passenger
        ) {
            resetTurretGeometricZeroState()
            clearFcsZeroState()
        }
        publishRemainingContextBaselines()
    }

    fun onControlContextChanged(controller: Entity) {
        if (this.controller(VehicleAimChannel.TURRET) === controller ||
            turretGeometricZeroController === controller ||
            fcsZeroController === controller
        ) {
            resetTurretGeometricZeroState()
            clearFcsZeroState()
        }
        publishContextTransitionBaseline(controller)
    }

    /**
     * Updates the authoritative geometric-zero distance for the live ground TURRET controller.
     * This is deliberately not a packet/input path: callers must validate and replicate the
     * accepted value through their own transport.  Compatible weapon changes retain the value.
     */
    fun setGeometricZeroDistance(controller: Entity?, requestedDistanceBlocks: Int): Boolean {
        if (!VehicleAimProfile.isSupportedGeometricZeroDistance(requestedDistanceBlocks)) return false
        val context = resolveLiveTurretContext(controller) ?: return false
        syncTurretGeometricZeroContext(
            context.controller,
            context.seatIndex,
            context.weaponIndex,
            context.profile,
        )
        // HasFCS profiles use the one-shot G acquisition below; the legacy 50/100/200 cycling
        // value is intentionally dormant and must never become an alternate pitch/aim authority.
        if (VehicleLaserRangefinder.enabled(vehicle, context.seatIndex, context.weaponIndex)) return false
        turretGeometricZeroDistanceBlocks = requestedDistanceBlocks
        return true
    }

    /** Current authoritative geometric-zero distance; lifecycle reset returns the profile default. */
    fun geometricZeroDistanceBlocks(): Int = turretGeometricZeroDistanceBlocks

    /**
     * Accepts one authoritative laser-rangefinder G edge. The caller must pass a monotonically increasing
     * connection sequence; no client point, distance, or direction is trusted.  A valid edge
     * revokes the previous zero before any muzzle/raycast/solver operation, so a failed capture
     * can never leave the previous correction active.
     */
    /** Compatibility synchronous query restricted to currently loaded terrain. */
    fun requestFcsZero(controller: Entity?, requestSequence: Long): VehicleFcsZeroState =
        beginFcsZero(controller, requestSequence, false).join()

    fun requestFcsZeroAsync(controller: Entity?, requestSequence: Long): java.util.concurrent.CompletableFuture<VehicleFcsZeroState> =
        beginFcsZero(controller, requestSequence, true)

    private fun beginFcsZero(controller: Entity?, requestSequence: Long, savedTerrain: Boolean): java.util.concurrent.CompletableFuture<VehicleFcsZeroState> {
        fun done(state: VehicleFcsZeroState) = java.util.concurrent.CompletableFuture.completedFuture(state)
        val context = resolveLiveTurretContext(controller)
        if (requestSequence < 0L || context == null) {
            return done(VehicleFcsZeroState.inactive(
                vehicle.uuid,
                fcsZeroRevision,
                fcsZeroContextEpoch,
                vehicle.level().gameTime,
                requestSequence,
            ))
        }
        if (requestSequence <= fcsZeroLastRequestSequence) return done(fcsZeroState())

        // Sequence admission and authority revocation happen before every fallible operation.
        fcsZeroLastRequestSequence = requestSequence
        clearFcsZeroState(forceEpoch = true)
        fcsZeroController = context.controller
        fcsZeroProfile = context.profile
        fcsZeroLevel = vehicle.level()
        if (!VehicleLaserRangefinder.enabled(vehicle, context.seatIndex, context.weaponIndex)) {
            return done(publishFcsResult(context, VehicleFcsZeroStatus.UNSUPPORTED))
        }

        val level = vehicle.level() as? ServerLevel
            ?: return done(publishFcsResult(context, VehicleFcsZeroStatus.UNSUPPORTED))
        val ray = vehicle.resolveVehicleAimCameraRay(
            context.controller,
            VehicleAimChannel.TURRET,
            1F,
        ) ?: return done(publishFcsResult(context, VehicleFcsZeroStatus.UNSUPPORTED))
        val muzzle = vehicle.resolveMuzzleFrame(context.controller, 1F)
            ?.takeIf { it.weaponName == vehicle.getGunName(context.seatIndex, context.weaponIndex) }
            ?: return done(publishFcsResult(context, VehicleFcsZeroStatus.UNSUPPORTED))
        if (!finite(muzzle.position) || !finite(muzzle.direction) ||
            muzzle.direction.lengthSqr() <= MIN_DIRECTION_LENGTH_SQR
        ) return done(publishFcsResult(context, VehicleFcsZeroStatus.UNSUPPORTED))
        // Bind a pending result so ordinary ticks preserve this request's context epoch.
        publishFcsResult(context, VehicleFcsZeroStatus.INACTIVE)
        val epoch = fcsZeroContextEpoch
        val base = vehicle.getTurretBaseTransformSnapshot(1F)
        val snapshot = buildFcsNominalSnapshot(context, muzzle, level)
            ?: return done(publishFcsResult(context, VehicleFcsZeroStatus.UNSUPPORTED))
        // The laser follows the gunner's sight line (the centre-screen ray the range is applied to below), not
        // the barrel: once a previous solution has super-elevated the gun, a barrel-axis lase flies over the target.
        val measurement = if (savedTerrain) VehicleLaserRangefinder.measureAsync(level, vehicle,
            ray.origin, ray.direction, MAX_FCS_ZERO_RANGE_BLOCKS)
        else java.util.concurrent.CompletableFuture.completedFuture(VehicleLaserRangefinder.measure(level,
            vehicle, ray.origin, ray.direction, MAX_FCS_ZERO_RANGE_BLOCKS))
        val serverExecutor = java.util.concurrent.Executor { action ->
            if (level.server.isSameThread) action.run() else level.server.execute(action)
        }
        return measurement.thenApplyAsync({ measured ->
            val live = resolveLiveTurretContext(controller)
            if (epoch != fcsZeroContextEpoch || requestSequence != fcsZeroLastRequestSequence ||
                live == null || live.controller !== context.controller || live.profile !== context.profile ||
                live.seatIndex != context.seatIndex || live.weaponIndex != context.weaponIndex ||
                vehicle.level() !== level) {
                VehicleFcsZeroState.inactive(vehicle.uuid, fcsZeroRevision, fcsZeroContextEpoch,
                    level.gameTime, requestSequence)
            } else if (measured == null) publishFcsResult(context, VehicleFcsZeroStatus.NO_BLOCK_HIT)
            else solveFcsRange(context, ray, muzzle, base, snapshot, measured)
        }, serverExecutor)
    }

    private fun solveFcsRange(context: LiveTurretContext, ray: VehicleAimCameraRay,
        muzzle: com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleMuzzleFrame,
        base: VehicleTransformSnapshot, snapshot: NominalShotSnapshot, measuredRange: Double): VehicleFcsZeroState {
        if (!measuredRange.isFinite() || measuredRange <= MIN_DIRECTION_LENGTH_SQR ||
            measuredRange > VehicleShotPredictionService.MAX_PREDICTION_PATH_BLOCKS
        ) return publishFcsResult(context, VehicleFcsZeroStatus.NO_SOLUTION, measuredRange)

        val target = ray.pointAt(measuredRange)
        val solution = solveRangeDirection(context, snapshot, base, target)
            ?: return publishFcsResult(context, VehicleFcsZeroStatus.NO_SOLUTION, measuredRange, source = base)
        val raw = target.subtract(muzzle.position)
        val rawPitch = VehicleAimMath.directionAngles(raw.x, raw.y, raw.z).pitch
        val solvedPitch = VehicleAimMath.directionAngles(solution.x, solution.y, solution.z).pitch
        return publishFcsResult(context, VehicleFcsZeroStatus.SOLUTION, measuredRange,
            (rawPitch - solvedPitch).toDouble(), base)
    }

    private fun solveRangeDirection(context: LiveTurretContext, snapshot: NominalShotSnapshot,
        base: VehicleTransformSnapshot, target: Vec3): Vec3? =
        VehicleRangeBallistics.solve(snapshot, target) { direction ->
            val local = base.worldDirectionToLocal(direction)
            val angles = VehicleAimMath.directionAngles(local.x, local.y, local.z)
            isCommandReachable(context.profile, angles.yaw, angles.pitch) &&
                angles.pitch >= vehicle.resolveVehicleAimMinPitch(VehicleAimChannel.TURRET, context.profile.minPitch) &&
                angles.pitch <= vehicle.resolveVehicleAimMaxPitch(VehicleAimChannel.TURRET, context.profile.maxPitch)
        }?.direction

    /** Immutable state/result snapshot for networking and HUD consumers. */
    fun fcsZeroState(): VehicleFcsZeroState = fcsZeroState ?: VehicleFcsZeroState.inactive(
        vehicle.uuid,
        fcsZeroRevision,
        fcsZeroContextEpoch,
        vehicle.level().gameTime,
        fcsZeroLastRequestSequence,
    )

    /**
     * Weapon selection can change while the controller still owns the same physical aim channel.
     * Rebind a compatible explicit mode and publish the new selected-weapon identity around the
     * unchanged physical target/actual tuple. This controller owns ordinary aim only.
     */
    fun onWeaponContextChanged(
        controller: Entity,
        seatIndex: Int,
        previousWeaponIndex: Int,
        selectedWeaponIndex: Int,
    ) {
        // FCS zero is weapon-specific even when the physical turret/channel is compatible.  A
        // newly selected weapon must acquire a fresh zero and may not revive the old solution.
        clearFcsZeroState()
        val previousProfile = resolveProfile(seatIndex, previousWeaponIndex)
        val selectedProfile = resolveProfile(seatIndex, selectedWeaponIndex)
        if (controller !is Player || vehicle.getSeatIndex(controller) != seatIndex ||
            vehicle.getSelectedWeapon(seatIndex) != selectedWeaponIndex ||
            previousProfile == null || selectedProfile == null ||
            !sharesPhysicalAimSemantics(previousProfile, selectedProfile) ||
            this.controller(selectedProfile.channel) !== controller
        ) {
            onControlContextChanged(controller)
            return
        }

        syncTurretGeometricZeroContext(controller, seatIndex, selectedWeaponIndex, selectedProfile)

        val previousSnapshot = snapshots[selectedProfile.channel]?.takeIf {
            it.seatIndex == seatIndex && it.selectedWeaponIndex == previousWeaponIndex
        }
        publishContextTransitionBaseline(controller, previousSnapshot)
    }

    fun consumeClient(payload: String?, receiptPartialTicks: Float = 0F) {
        if (!vehicle.level().isClientSide || payload == clientPayload) return
        NetworkTelemetry.recordSystemWork("aim.snapshot.received")
        recordClientAimTrace("RECEIVED", reason = "PAYLOAD_RECEIVED")
        clientPayload = payload.orEmpty()
        val decoded = VehicleAimSnapshots.decodeStrict(payload)
        if (decoded == null) {
            // A malformed/partially decoded payload is not an authoritative empty snapshot. Do
            // not let it retire the other physical channel while every contained sample is
            // unknown; preserve the last coherent TURRET/PASSENGER pair instead.
            NetworkTelemetry.recordSystemWork("aim.snapshot.rejected")
            recordClientAimTrace("REJECTED", reason = "MALFORMED")
            return
        }
        NetworkTelemetry.recordSystemWork("aim.snapshot.decoded", items = decoded.size)
        if (decoded.isEmpty()) {
            snapshots.clear()
            clientPresentationTimeline.clear()
            applyClientActuals()
            recordClientAimTrace("REJECTED", reason = "EMPTY_RESET")
            return
        }
        decoded.forEach { recordClientAimTrace("DECODED", it, "DECODED_PAYLOAD") }
        val activeChannels = decoded.mapTo(EnumSet.noneOf(VehicleAimChannel::class.java)) { it.channel }
        // Entity-data payloads are complete snapshots, but transport reordering can deliver an
        // older pre-transition payload after a newer TURRET/PASSENGER pair. Retiring omitted
        // channels before this preflight would clear a valid current pair even though every
        // member of the delayed payload is rejected below. A payload may retire omitted channels
        // only after at least one member proves it is newer for its physical channel.
        if (decoded.none { clientPresentationTimeline.canAccept(it) }) {
            decoded.forEach {
                NetworkTelemetry.recordSystemWork("aim.snapshot.rejected")
                recordClientAimTrace("REJECTED", it, "STALE_OR_REORDERED")
            }
            return
        }
        clientPresentationTimeline.retainChannels(activeChannels)
        VehicleAimChannel.entries.forEach { channel ->
            if (channel !in activeChannels) snapshots.remove(channel)
        }
        decoded.forEach {
            // Preflight before rebind is the critical ordering guard: a delayed old identity may
            // be mechanically compatible, but it must not rekey the newer history it is about to
            // fail against.
            if (!clientPresentationTimeline.canAccept(it)) {
                NetworkTelemetry.recordSystemWork("aim.snapshot.rejected")
                recordClientAimTrace("REJECTED", it, "STALE_OR_REORDERED")
                return@forEach
            }
            clientPresentationTimeline.rebindCompatibleContext(
                it.channel,
                it.seatIndex,
                it.selectedWeaponIndex,
                it.mode,
                vehicle.tickCount,
            ) { previousWeaponIndex ->
                vehicle.sharesClientPhysicalAimSemantics(
                    it.seatIndex,
                    previousWeaponIndex,
                    it.selectedWeaponIndex,
                )
            }
            if (clientPresentationTimeline.offer(it, vehicle.tickCount, receiptPartialTicks)) {
                snapshots[it.channel] = it
                NetworkTelemetry.recordSystemWork("aim.snapshot.admitted")
                recordClientAimTrace("ADMITTED", it, "ADMITTED", clientPresentationEpoch)
            } else {
                NetworkTelemetry.recordSystemWork("aim.snapshot.rejected")
                recordClientAimTrace("REJECTED", it, "TIMELINE_REJECTED")
            }
        }
        applyClientActuals()
    }

    private fun recordClientAimTrace(
        stage: String,
        snapshot: VehicleAimSnapshot? = null,
        reason: String,
        epoch: Int = clientPresentationEpoch,
    ) {
        if (!NetworkTelemetry.isAimPresentationTraceEnabled()) return
        NetworkTelemetry.recordAimPresentationTrace(
            AimPresentationTraceSample(
                stage = stage,
                entityId = vehicle.id,
                clientTick = vehicle.tickCount,
                partialBits = 0,
                channel = snapshot?.channel?.ordinal ?: -1,
                seatIndex = snapshot?.seatIndex ?: -1,
                weaponIndex = snapshot?.selectedWeaponIndex ?: -1,
                sequence = snapshot?.sequence ?: -1,
                upperSequence = snapshot?.sequence ?: -1,
                serverTick = snapshot?.serverTick ?: -1L,
                sourceServerTick = snapshot?.serverTick?.toDouble() ?: -1.0,
                epoch = epoch,
                reason = reason,
                cacheHit = false,
                direct = false,
                continuity = false,
                tailHeld = false,
                alpha = 1F,
                axesValid = snapshot != null,
                actualYaw = snapshot?.actualYaw ?: 0F,
                actualPitch = snapshot?.actualPitch ?: 0F,
                presentedYaw = snapshot?.actualYaw ?: 0F,
                presentedPitch = snapshot?.actualPitch ?: 0F,
            ),
        )
    }

    internal fun resolveClientPresentationAt(
        channel: VehicleAimChannel,
        seatIndex: Int,
        weaponIndex: Int,
        serverTick: Double,
    ): VehicleAimPresentationSample? = clientPresentationTimeline.resolveAt(
        channel,
        seatIndex,
        weaponIndex,
        serverTick,
    )

    internal fun resolveLatestClientPresentation(
        channel: VehicleAimChannel,
        seatIndex: Int,
        weaponIndex: Int,
        partialTicks: Float,
        presentationServerTick: Double,
    ): VehicleAimPresentationSample? = clientPresentationTimeline.resolveLatest(
        channel,
        seatIndex,
        weaponIndex,
        partialTicks,
        vehicle.tickCount,
        presentationServerTick,
    )

    internal fun resolveClientChannelPresentationAt(
        channel: VehicleAimChannel,
        serverTick: Double,
    ): VehicleAimPresentationSample? = clientPresentationTimeline.resolveChannelAt(channel, serverTick)

    internal fun resolveLatestClientChannelPresentation(
        channel: VehicleAimChannel,
        partialTicks: Float,
        presentationServerTick: Double,
    ): VehicleAimPresentationSample? = clientPresentationTimeline.resolveLatestChannel(
        channel,
        partialTicks,
        vehicle.tickCount,
        presentationServerTick,
    )

    internal fun rebindCompatibleClientPresentation(
        channel: VehicleAimChannel,
        seatIndex: Int,
        selectedWeaponIndex: Int,
        mode: VehicleAimMode,
        compatible: (previousWeaponIndex: Int) -> Boolean,
    ): Boolean = clientPresentationTimeline.rebindCompatibleContext(
        channel,
        seatIndex,
        selectedWeaponIndex,
        mode,
        vehicle.tickCount,
        compatible,
    )

    internal val clientPresentationEpoch: Int
        get() = clientPresentationTimeline.epoch

    internal fun clearClientPresentation() {
        clientPayload = ""
        clientPresentationTimeline.clear()
    }

    /** Controller identity is not on the wire; retain only bounded physical actual, never lock. */
    internal fun beginClientControllerEpoch(changedChannels: Set<VehicleAimChannel>) {
        clientPresentationTimeline.beginControllerEpoch(changedChannels)
    }

    fun snapshot(seatIndex: Int, weaponIndex: Int): VehicleAimSnapshot? =
        snapshots.values.firstOrNull {
            it.seatIndex == seatIndex && it.selectedWeaponIndex == weaponIndex
        }

    fun snapshot(channel: VehicleAimChannel): VehicleAimSnapshot? = snapshots[channel]

    private fun updateChannel(
        controller: Entity,
        seatIndex: Int,
        weaponIndex: Int,
        profile: VehicleAimProfile,
    ): VehicleAimSnapshot {
        val mode = profile.defaultMode
        if (mode == VehicleAimMode.INACTIVE) {
            if (profile.snapToNeutralWhenInactive) {
                setInactiveNeutral(profile)
            }
            val parentedToTurret = usesTurretParentYawFrame(profile)
            val parentYaw = if (parentedToTurret) Mth.wrapDegrees(vehicle.turretYRot) else 0F
            val neutralLocalYaw = VehicleAimMath.applyYawRange(profile.neutralYaw, profile.minYaw, profile.maxYaw)
            val neutralWorldYaw = toPassengerWorldYaw(neutralLocalYaw, parentedToTurret, parentYaw)
            val actualYaw = actualYaw(profile.channel)
            val actualPitch = actualPitch(profile.channel)
            return snapshot(
                profile.channel,
                seatIndex,
                weaponIndex,
                mode,
                neutralWorldYaw,
                profile.neutralPitch,
                actualYaw,
                actualPitch,
                abs(Mth.wrapDegrees(actualYaw - neutralWorldYaw)) <= profile.lockToleranceDegrees &&
                        abs(actualPitch - profile.neutralPitch) <= profile.lockToleranceDegrees,
            )
        }

        val rawDesired = controller.getViewVector(1F)
        val hasFcs = profile.channel == VehicleAimChannel.TURRET &&
                VehicleLaserRangefinder.enabled(vehicle, seatIndex, weaponIndex) &&
                vehicle.resolveVehicleFlightStrategy() == null
        // HasFCS uses only the latest live command plus the scalar vertical zero acquired by G.
        // The legacy geometric-zero target remains available for profiles without HasFCS.
        val fcsDesired = if (hasFcs) resolveFcsDesiredDirection(
            controller,
            seatIndex,
            weaponIndex,
            profile,
            rawDesired,
        ) else null
        val zeroedDesired = if (!hasFcs) resolveGeometricZeroDirection(controller, profile) else null
        val desired = fcsDesired ?: zeroedDesired ?: rawDesired
        val stationLocal = profile.directionFrame == VehicleAimDirectionFrame.PASSENGER_STATION_LOCAL
        val stationAngles = if (stationLocal) vehicle.getPassengerStationAimBase(1F)
            ?.let { VehiclePassengerStationFrame.angles(it, desired) } else null
        if (stationLocal && stationAngles == null) {
            val yaw = actualYaw(profile.channel)
            val pitch = actualPitch(profile.channel)
            return snapshot(profile.channel, seatIndex, weaponIndex, mode, yaw, pitch, yaw, pitch, false)
        }
        val currentVector = when (profile.channel) {
            VehicleAimChannel.TURRET -> vehicle.getBarrelVector(1F)
            VehicleAimChannel.PASSENGER_WEAPON -> vehicle.getPassengerWeaponStationVector(1F)
        }
        val parentedToTurret = usesTurretParentYawFrame(profile)
        val parentYaw = if (parentedToTurret) Mth.wrapDegrees(vehicle.turretYRot) else 0F
        val previousWorldYaw = actualYaw(profile.channel)
        val previousYaw = toPassengerLocalYaw(previousWorldYaw, parentedToTurret, parentYaw)
        val previousPitch = actualPitch(profile.channel)
        // Solve in the physical turret base. World Euler differences mix elevation and traverse
        // on a rolled/pitched chassis, leaving a residual error even with a stationary command.
        val turretBase = if (profile.channel == VehicleAimChannel.TURRET)
            vehicle.getTurretBaseTransformSnapshot(1F) else null
        fun turretAngles(direction: Vec3): VehicleAimMath.DirectionAngles? = turretBase?.let {
            val local = it.worldDirectionToLocal(direction)
            VehicleAimMath.directionAngles(local.x, local.y, local.z)
        }
        val turretDesiredAngles = turretAngles(desired)
        val worldYawDelta = Mth.wrapDegrees(
            -VehicleVecUtils.getYRotFromVector(desired) + VehicleVecUtils.getYRotFromVector(currentVector)
        ).toFloat()
        val pitchDelta = (turretDesiredAngles ?: stationAngles)?.let { it.pitch - previousPitch } ?: Mth.wrapDegrees(
            -VehicleVecUtils.getXRotFromVector(desired) + VehicleVecUtils.getXRotFromVector(currentVector)
        ).toFloat()
        val targetWorldYaw = previousWorldYaw - worldYawDelta
        val targetYaw = (turretDesiredAngles ?: stationAngles)?.yaw
            ?: toPassengerLocalYaw(targetWorldYaw, parentedToTurret, parentYaw)
        val yawDelta = Mth.wrapDegrees(previousYaw - targetYaw)
        val pitchTarget = previousPitch + pitchDelta
        val closeAimDesired = desired
        val closeAimReachable = isCommandReachable(profile, targetYaw, pitchTarget) &&
            (!hasFcs || fcsZeroState?.hasSolution != true || fcsDesired != null)
        updateCloseAimState(
            profile,
            currentVector,
            closeAimDesired,
            closeAimReachable,
        )
        val yawStep = vehicle.resolveVehicleAimYawRateDegreesPerSecond(
            profile.channel,
            profile.yawRateDegreesPerSecond,
        ) * closeAimYawMultiplier / TICKS_PER_SECOND
        val pitchStep = vehicle.resolveVehicleAimPitchRateDegreesPerSecond(
            profile.channel,
            profile.pitchRateDegreesPerSecond,
        ) * closeAimPitchMultiplier / TICKS_PER_SECOND
        val zoom = if (zoomController === controller && vehicle.level().gameTime - zoomReceiptTick in 0..40)
            opticalZoom else 1F
        val snap = profile.channel == VehicleAimChannel.TURRET && closeAimReachable &&
            yawStep > 0F && pitchStep > 0F && VehicleAimMath.shouldSnap(yawDelta, pitchDelta, zoom)
        val appliedYaw = if (snap) yawDelta else Mth.clamp(yawDelta, -yawStep, yawStep)
        val appliedPitch = if (snap) pitchDelta else Mth.clamp(pitchDelta, -pitchStep, pitchStep)
        val yawChange = if (snap) -appliedYaw else VehicleAimMath.softenDeltaNearRange(
            previousYaw,
            -appliedYaw,
            profile.minYaw,
            profile.maxYaw,
            profile.softYawLimitDegrees,
        )
        val pitchChange = if (snap) appliedPitch else VehicleAimMath.softenDeltaNearRange(
            previousPitch,
            appliedPitch,
            profile.minPitch,
            profile.maxPitch,
            profile.softPitchLimitDegrees,
        )
        val nextYaw = VehicleAimMath.applyYawRange(previousYaw + yawChange, profile.minYaw, profile.maxYaw)
        val nextPitch = Mth.clamp(previousPitch + pitchChange, profile.minPitch, profile.maxPitch)
        val actualAppliedYaw = actualAppliedYaw(previousYaw, nextYaw, profile.minYaw, profile.maxYaw)
        val actualAppliedPitch = nextPitch - previousPitch
        val lockYawDelta = yawDelta
        val lockPitchDelta = pitchDelta
        val locked = abs(lockYawDelta - actualAppliedYaw) <= profile.lockToleranceDegrees &&
                abs(lockPitchDelta - actualAppliedPitch) <= profile.lockToleranceDegrees

        val nextWorldYaw = toPassengerWorldYaw(nextYaw, parentedToTurret, parentYaw)
        val boundedTargetYaw = VehicleAimMath.applyYawRange(targetYaw, profile.minYaw, profile.maxYaw)
        val boundedTargetWorldYaw = toPassengerWorldYaw(boundedTargetYaw, parentedToTurret, parentYaw)
        setActual(profile.channel, nextWorldYaw, nextPitch, profile.minYaw, profile.maxYaw)
        return snapshot(
            profile.channel,
            seatIndex,
            weaponIndex,
            mode,
            boundedTargetWorldYaw,
            Mth.clamp(previousPitch + pitchDelta, profile.minPitch, profile.maxPitch),
            nextWorldYaw,
            nextPitch,
            locked,
        )
    }

    /**
     * Limited turret-parented passenger stations store an absolute gunYRot but author their
     * limits around the current turret. Full-yaw and hull-parented stations intentionally retain
     * the historical world/hull frame semantics. These scalar helpers avoid per-tick frame
     * allocations in the authoritative servo.
     */
    private fun usesTurretParentYawFrame(profile: VehicleAimProfile): Boolean =
        profile.channel == VehicleAimChannel.PASSENGER_WEAPON &&
                !vehicle.isHullParentedPassengerWeaponStation() &&
                !VehicleAimMath.isFullYawRange(profile.minYaw, profile.maxYaw)

    private fun toPassengerLocalYaw(worldYaw: Float, parentedToTurret: Boolean, parentYaw: Float): Float =
        if (parentedToTurret) Mth.wrapDegrees(worldYaw - parentYaw) else worldYaw

    private fun toPassengerWorldYaw(localYaw: Float, parentedToTurret: Boolean, parentYaw: Float): Float =
        if (parentedToTurret) Mth.wrapDegrees(parentYaw + localYaw) else localYaw

    private fun resolveGeometricZeroDirection(
        controller: Entity,
        profile: VehicleAimProfile,
    ): net.minecraft.world.phys.Vec3? {
        if (profile.channel != VehicleAimChannel.TURRET) return null
        val ray = vehicle.resolveVehicleAimCameraRay(controller, profile.channel, 1F) ?: return null
        val muzzle = vehicle.getShootPos(controller, 1F)
        val target = ray.pointAt(turretGeometricZeroDistanceBlocks.toDouble())
        val desired = muzzle.vectorTo(target)
        val lengthSqr = desired.lengthSqr()
        return if (lengthSqr.isFinite() && lengthSqr > MIN_DIRECTION_LENGTH_SQR) {
            desired.normalize()
        } else {
            null
        }
    }

    private fun resolveFcsDesiredDirection(
        controller: Entity,
        seatIndex: Int,
        weaponIndex: Int,
        profile: VehicleAimProfile,
        rawDesired: Vec3,
    ): Vec3? {
        val state = fcsZeroState ?: return null
        if (!state.hasSolution || fcsZeroController !== controller ||
            state.operatorUuid != controller.uuid || state.seatIndex != seatIndex ||
            state.selectedWeaponIndex != weaponIndex || state.weaponName != vehicle.getGunName(seatIndex, weaponIndex) ||
            state.status != VehicleFcsZeroStatus.SOLUTION
        ) return null
        val range = state.measuredRangeBlocks ?: return null
        val level = vehicle.level() as? ServerLevel ?: return null
        val muzzle = vehicle.resolveMuzzleFrame(controller, 1F) ?: return null
        val context = LiveTurretContext(controller, seatIndex, weaponIndex, profile)
        val snapshot = buildFcsNominalSnapshot(context, muzzle, level) ?: return null
        val ray = vehicle.resolveVehicleAimCameraRay(controller, profile.channel, 1F) ?: return null
        // Recompute the firing direction for the retained range, not a retained world target.
        // Both axes may change on a tilted hull; camera/muzzle parallax and vehicle motion count.
        return solveRangeDirection(context, snapshot, vehicle.getTurretBaseTransformSnapshot(1F), ray.pointAt(range))
    }

    private data class FcsNominalModel(
        val collision: NominalBlockCollisionModel,
        val motion: NominalMotionModel,
        val ownerDistanceLimitBlocks: Double? = null,
    )

    private fun buildFcsNominalSnapshot(
        context: LiveTurretContext,
        muzzle: com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleMuzzleFrame,
        level: ServerLevel,
    ): NominalShotSnapshot? {
        val data = vehicle.getGunData(context.seatIndex, context.weaponIndex) ?: return null
        if (com.atsuishio.superbwarfare.perk.Perk.Type.entries.any { data.perk.getInstances(it).isNotEmpty() }) return null
        val descriptor = data.get(GunProp.NOMINAL_BALLISTICS) ?: return null
        if (!descriptor.supported || descriptor.projectileLife < 0) return null
        val projectileType = ResourceLocation.tryParse(descriptor.projectileType.trim()) ?: return null
        val model = resolveFcsNominalModel(projectileType) ?: return null
        val projectileProfile = descriptor.projectileProfile?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { ResourceLocation.tryParse(it) ?: return null }
        if (com.atsuishio.superbwarfare.tools.VectorTool.isInLiquid(level, muzzle.position)) return null
        val inheritedMotion = if (data.get(GunProp.ADD_SHOOTER_DELTA_MOVEMENT)) {
            context.controller.rootVehicle.deltaMovement
        } else {
            Vec3.ZERO
        }
        if (!finite(inheritedMotion)) return null
        val launchSpeed = NominalProjectileMotion.launchSpeed(data, level, muzzle.position)
        val gravity = data.get(GunProp.GRAVITY).toFloat().toDouble()
        if (!launchSpeed.isFinite() || launchSpeed <= 0.0 || !gravity.isFinite() || gravity < 0.0) return null
        val initialMotion = NominalProjectileMotion.initialMotion(
            muzzle.direction.normalize(),
            launchSpeed,
            inheritedMotion,
        )
        if (!finite(initialMotion)) return null
        val ownerKinematics = model.ownerDistanceLimitBlocks?.let { limit ->
            val ownerPosition = context.controller.position()
            val ownerMotion = context.controller.rootVehicle.deltaMovement
            if (!finite(ownerPosition) || !finite(ownerMotion)) return null
            NominalOwnerKinematics(ownerPosition, ownerMotion, limit, 0.0)
        }
        val lifetime = descriptor.projectileLife
        val horizon = min(lifetime.toLong() + 1L, VehicleShotPredictionService.MAX_PREDICTION_TICKS.toLong()).toInt()
        if (horizon !in 1..VehicleShotPredictionService.MAX_PREDICTION_TICKS) return null
        return NominalShotSnapshot(
            vehicle.uuid,
            context.seatIndex,
            context.weaponIndex,
            muzzle.weaponName,
            projectileType,
            projectileProfile,
            muzzle,
            launchSpeed,
            inheritedMotion,
            initialMotion,
            gravity,
            lifetime,
            horizon,
            VehicleShotPredictionService.MAX_PREDICTION_PATH_BLOCKS,
            model.collision,
            model.motion,
            ownerKinematics,
        )
    }

    private fun resolveFcsNominalModel(type: ResourceLocation): FcsNominalModel? = when (type.toString()) {
        "superbwarfare:projectile" -> FcsNominalModel(
            NominalBlockCollisionModel.SBW_PROJECTILE,
            NominalMotionModel.DIRECT_LINEAR_GRAVITY,
        )
        "superbwarfare:small_rocket",
        "superbwarfare:medium_rocket",
        "superbwarfare:cannon_shell" -> FcsNominalModel(
            NominalBlockCollisionModel.STANDARD_PROJECTILE,
            NominalMotionModel.FAST_THROWABLE_LINEAR_GRAVITY_AIR,
        )
        "superbwarfare:small_cannon_shell" -> FcsNominalModel(
            NominalBlockCollisionModel.STANDARD_PROJECTILE,
            NominalMotionModel.FAST_THROWABLE_LINEAR_GRAVITY_AIR,
            1024.0,
        )
        else -> null
    }

    private fun publishFcsResult(
        context: LiveTurretContext,
        status: VehicleFcsZeroStatus,
        measuredRangeBlocks: Double? = null,
        elevationOffsetDegrees: Double? = null,
        source: VehicleTransformSnapshot? = null,
    ): VehicleFcsZeroState {
        fcsZeroRevision += 1L
        val sourceSequence = source?.sequence ?: -1
        val sourceServerTick = source?.serverTick ?: -1L
        return VehicleFcsZeroState(
            vehicle.uuid,
            context.controller.uuid,
            context.seatIndex,
            context.weaponIndex,
            vehicle.getGunName(context.seatIndex, context.weaponIndex),
            fcsZeroLastRequestSequence,
            fcsZeroRevision,
            fcsZeroContextEpoch,
            vehicle.level().gameTime,
            status,
            measuredRangeBlocks?.takeIf { it.isFinite() && it >= 0.0 },
            elevationOffsetDegrees?.takeIf { it.isFinite() },
            sourceSequence,
            sourceServerTick,
        ).also { fcsZeroState = it }
    }

    private fun syncFcsZeroContext(
        controller: Entity,
        seatIndex: Int,
        weaponIndex: Int,
        profile: VehicleAimProfile,
    ) {
        val state = fcsZeroState ?: return
        // A lifecycle clear leaves an explicit INACTIVE state with no bound controller.  Keep
        // that terminal baseline stable on later ticks; otherwise every ordinary tick after a
        // clear would manufacture another epoch/revision and churn presentation/transport
        // consumers without any new context event.
        if (state.status == VehicleFcsZeroStatus.INACTIVE && fcsZeroController == null) return
        val contextMatches = fcsZeroController === controller &&
                fcsZeroLevel === vehicle.level() && fcsZeroProfile === profile &&
                state.operatorUuid == controller.uuid && state.seatIndex == seatIndex &&
                state.selectedWeaponIndex == weaponIndex &&
                state.weaponName == vehicle.getGunName(seatIndex, weaponIndex) &&
                profile.channel == VehicleAimChannel.TURRET &&
                profile.defaultMode == VehicleAimMode.PLAYER_LOOK_AIM
        if (!contextMatches ||
            (state.status != VehicleFcsZeroStatus.INACTIVE && !VehicleLaserRangefinder.enabled(vehicle, seatIndex, weaponIndex))
        ) {
            clearFcsZeroState()
        }
    }

    private fun clearFcsZeroState(forceEpoch: Boolean = false) {
        if (!forceEpoch && fcsZeroState == null && fcsZeroController == null) return
        fcsZeroContextEpoch += 1L
        fcsZeroRevision += 1L
        fcsZeroController = null
        fcsZeroProfile = null
        fcsZeroLevel = null
        fcsZeroState = VehicleFcsZeroState.inactive(
            vehicle.uuid,
            fcsZeroRevision,
            fcsZeroContextEpoch,
            vehicle.level().gameTime,
            fcsZeroLastRequestSequence,
        )
    }

    private fun directionFromAngles(yawDegrees: Double, pitchDegrees: Double): Vec3 {
        val yaw = Math.toRadians(yawDegrees)
        val pitch = Math.toRadians(pitchDegrees)
        val horizontal = kotlin.math.cos(pitch)
        return Vec3(
            kotlin.math.sin(yaw) * horizontal,
            -kotlin.math.sin(pitch),
            kotlin.math.cos(yaw) * horizontal,
        )
    }

    private fun finite(vector: Vec3): Boolean =
        vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()

    private fun isCommandReachable(
        profile: VehicleAimProfile,
        targetYaw: Float,
        targetPitch: Float,
    ): Boolean {
        val yawReachable = VehicleAimMath.isFullYawRange(profile.minYaw, profile.maxYaw) ||
                targetYaw >= profile.minYaw && targetYaw <= profile.maxYaw
        val pitchReachable = targetPitch >= profile.minPitch && targetPitch <= profile.maxPitch
        return yawReachable && pitchReachable
    }

    /** Applies one shared 3-D close-error policy to the physical TURRET. */
    private fun updateCloseAimState(
        profile: VehicleAimProfile,
        currentVector: net.minecraft.world.phys.Vec3,
        desired: net.minecraft.world.phys.Vec3,
        commandReachable: Boolean,
    ) {
        closeAimYawMultiplier = 1F
        closeAimPitchMultiplier = 1F
        if (profile.channel != VehicleAimChannel.TURRET) return

        if (!commandReachable) return
        val currentLengthSqr = currentVector.lengthSqr()
        val desiredLengthSqr = desired.lengthSqr()
        if (!currentLengthSqr.isFinite() || !desiredLengthSqr.isFinite() ||
            currentLengthSqr <= MIN_DIRECTION_LENGTH_SQR || desiredLengthSqr <= MIN_DIRECTION_LENGTH_SQR
        ) return
        val dot = (currentVector.dot(desired) / sqrt(currentLengthSqr * desiredLengthSqr))
            .coerceIn(-1.0, 1.0)
        val gain = VehicleAimMath.closeAimMultiplier(Math.toDegrees(acos(dot)))
        closeAimYawMultiplier = gain
        closeAimPitchMultiplier = gain
    }

    private fun snapshot(
        channel: VehicleAimChannel,
        seatIndex: Int,
        weaponIndex: Int,
        mode: VehicleAimMode,
        targetYaw: Float,
        targetPitch: Float,
        actualYaw: Float,
        actualPitch: Float,
        locked: Boolean,
    ): VehicleAimSnapshot {
        snapshotSequence += 1
        return VehicleAimSnapshot(
            snapshotSequence,
            vehicle.level().gameTime,
            channel,
            seatIndex,
            weaponIndex,
            mode,
            targetYaw,
            targetPitch,
            actualYaw,
            actualPitch,
            locked,
        )
    }

    private fun setActual(
        channel: VehicleAimChannel,
        yaw: Float,
        pitch: Float,
        minYaw: Float = -180F,
        maxYaw: Float = 180F,
    ) {
        when (channel) {
            VehicleAimChannel.TURRET -> {
                val previousYaw = vehicle.turretYRot
                vehicle.turretYRotO = VehicleAimMath.previousYawForInterpolation(previousYaw, yaw, minYaw, maxYaw)
                vehicle.turretXRotO = vehicle.turretXRot
                vehicle.turretYRot = yaw
                vehicle.turretXRot = pitch
                vehicle.turretYRotLock = Mth.wrapDegrees(yaw - vehicle.turretYRotO)
            }
            VehicleAimChannel.PASSENGER_WEAPON -> {
                val previousYaw = vehicle.gunYRot
                vehicle.gunYRotO = VehicleAimMath.previousYawForInterpolation(previousYaw, yaw, minYaw, maxYaw)
                vehicle.gunXRotO = vehicle.gunXRot
                vehicle.gunYRot = yaw
                vehicle.gunXRot = pitch
            }
        }
    }

    private fun setInactiveNeutral(profile: VehicleAimProfile) {
        val parentedToTurret = usesTurretParentYawFrame(profile)
        val parentYaw = if (parentedToTurret) Mth.wrapDegrees(vehicle.turretYRot) else 0F
        val yaw = toPassengerWorldYaw(
            VehicleAimMath.applyYawRange(profile.neutralYaw, profile.minYaw, profile.maxYaw),
            parentedToTurret,
            parentYaw,
        )
        val pitch = Mth.clamp(profile.neutralPitch, profile.minPitch, profile.maxPitch)
        when (profile.channel) {
            VehicleAimChannel.TURRET -> {
                vehicle.turretYRotO = yaw
                vehicle.turretXRotO = pitch
                vehicle.turretYRot = yaw
                vehicle.turretXRot = pitch
                vehicle.turretYRotLock = 0F
            }
            VehicleAimChannel.PASSENGER_WEAPON -> {
                vehicle.gunYRotO = yaw
                vehicle.gunXRotO = pitch
                vehicle.gunYRot = yaw
                vehicle.gunXRot = pitch
            }
        }
    }

    private fun actualYaw(channel: VehicleAimChannel): Float = when (channel) {
        VehicleAimChannel.TURRET -> vehicle.turretYRot
        VehicleAimChannel.PASSENGER_WEAPON -> vehicle.gunYRot
    }

    private fun actualPitch(channel: VehicleAimChannel): Float = when (channel) {
        VehicleAimChannel.TURRET -> vehicle.turretXRot
        VehicleAimChannel.PASSENGER_WEAPON -> vehicle.gunXRot
    }

    private fun applyClientActuals() {
        for (snapshot in snapshots.values) {
            if (!vehicle.shouldApplyClientAimChannel(snapshot.channel)) continue
            setActual(snapshot.channel, snapshot.actualYaw, snapshot.actualPitch)
        }
    }

    /**
     * Publishes one physical-axis baseline for an accepted seat/weapon context transition. The
     * normal server tick remains the sole servo writer; this only closes the interval between the
     * synchronized selected-weapon change and the next authored aim tick. Without it, coherent
     * presentation correctly rejects the old weapon sample and can hide the shared turret while
     * the vehicle is stationary.
     */
    private fun publishContextTransitionBaseline(
        controller: Entity,
        preservedPhysicalState: VehicleAimSnapshot? = null,
    ) {
        if (!writeContextTransitionBaseline(controller, preservedPhysicalState)) return
        ensurePassiveParentTurretSnapshot()
        vehicle.publishVehicleAimSnapshots(VehicleAimSnapshots.encode(snapshots.values))
    }

    private fun writeContextTransitionBaseline(
        controller: Entity,
        preservedPhysicalState: VehicleAimSnapshot? = null,
    ): Boolean {
        if (vehicle.level().isClientSide || vehicle.isWreck || controller !is Player) return false
        val seatIndex = vehicle.getSeatIndex(controller)
        if (seatIndex < 0) return false
        val weaponIndex = vehicle.getSelectedWeapon(seatIndex)
        val profile = resolveProfile(seatIndex, weaponIndex) ?: return false
        if (this.controller(profile.channel) !== controller) return false
        val yaw = actualYaw(profile.channel)
        val pitch = actualPitch(profile.channel)
        val preserved = preservedPhysicalState?.takeIf { it.channel == profile.channel }
        snapshots[profile.channel] = snapshot(
            profile.channel,
            seatIndex,
            weaponIndex,
            profile.defaultMode,
            preserved?.targetYaw ?: yaw,
            preserved?.targetPitch ?: pitch,
            yaw,
            pitch,
            false,
        )
        return true
    }

    private fun publishRemainingContextBaselines() {
        if (vehicle.level().isClientSide || vehicle.isWreck) return
        var wroteBaseline = false
        var removedStaleChannel = false
        for (channel in VehicleAimChannel.entries) {
            val remainingController = controller(channel)
            if (remainingController == null) {
                removedStaleChannel = snapshots.remove(channel) != null || removedStaleChannel
                continue
            }
            wroteBaseline = writeContextTransitionBaseline(remainingController) || wroteBaseline
        }
        if (!wroteBaseline && !removedStaleChannel) return
        ensurePassiveParentTurretSnapshot()
        vehicle.publishVehicleAimSnapshots(VehicleAimSnapshots.encode(snapshots.values))
    }

    private fun sharesPhysicalAimSemantics(
        previous: VehicleAimProfile,
        selected: VehicleAimProfile,
    ): Boolean = previous.channel == selected.channel &&
            previous.directionFrame == selected.directionFrame &&
            previous.yawRateDegreesPerSecond == selected.yawRateDegreesPerSecond &&
            previous.pitchRateDegreesPerSecond == selected.pitchRateDegreesPerSecond &&
            previous.minYaw == selected.minYaw && previous.maxYaw == selected.maxYaw &&
            previous.minPitch == selected.minPitch && previous.maxPitch == selected.maxPitch &&
            previous.softYawLimitDegrees == selected.softYawLimitDegrees &&
            previous.softPitchLimitDegrees == selected.softPitchLimitDegrees &&
            previous.defaultMode == selected.defaultMode &&
            previous.snapToNeutralWhenInactive == selected.snapToNeutralWhenInactive &&
            previous.neutralYaw == selected.neutralYaw && previous.neutralPitch == selected.neutralPitch &&
            previous.geometricZeroDistanceBlocks == selected.geometricZeroDistanceBlocks

    private fun resolveLiveTurretContext(controller: Entity?): LiveTurretContext? {
        if (vehicle.level().isClientSide || controller !is Player ||
            controller.vehicle !== vehicle || controller.level() !== vehicle.level() ||
            !controller.isAlive || vehicle.isRemoved || vehicle.isWreck ||
            vehicle.resolveVehicleFlightStrategy() != null ||
            this.controller(VehicleAimChannel.TURRET) !== controller
        ) return null
        val seatIndex = vehicle.getSeatIndex(controller)
        if (seatIndex < 0) return null
        val weaponIndex = vehicle.getSelectedWeapon(seatIndex)
        val profile = resolveProfile(seatIndex, weaponIndex) ?: return null
        if (profile.channel != VehicleAimChannel.TURRET ||
            profile.defaultMode != VehicleAimMode.PLAYER_LOOK_AIM
        ) return null
        return LiveTurretContext(controller, seatIndex, weaponIndex, profile)
    }

    private fun syncTurretGeometricZeroContext(
        controller: Entity,
        seatIndex: Int,
        weaponIndex: Int,
        profile: VehicleAimProfile,
    ) {
        if (profile.channel != VehicleAimChannel.TURRET ||
            profile.defaultMode != VehicleAimMode.PLAYER_LOOK_AIM ||
            vehicle.resolveVehicleFlightStrategy() != null
        ) {
            resetTurretGeometricZeroState()
            return
        }
        val previousProfile = turretGeometricZeroProfile
        val compatible = turretGeometricZeroController === controller &&
                turretGeometricZeroSeatIndex == seatIndex &&
                previousProfile != null && sharesPhysicalAimSemantics(previousProfile, profile)
        if (!compatible) turretGeometricZeroDistanceBlocks = profile.geometricZeroDistanceBlocks
        turretGeometricZeroController = controller
        turretGeometricZeroSeatIndex = seatIndex
        turretGeometricZeroProfile = profile
    }

    private fun resetTurretGeometricZeroState() {
        turretGeometricZeroDistanceBlocks = VehicleAimProfile.DEFAULT_GEOMETRIC_ZERO_DISTANCE_BLOCKS
        turretGeometricZeroController = null
        turretGeometricZeroSeatIndex = -1
        turretGeometricZeroProfile = null
    }

    /** Passenger-station attachments are authored below the shared physical turret parent. */
    private fun ensurePassiveParentTurretSnapshot() {
        if (!snapshots.containsKey(VehicleAimChannel.PASSENGER_WEAPON) || !vehicle.hasTurret()) return
        val currentTick = vehicle.level().gameTime
        if (snapshots[VehicleAimChannel.TURRET]?.serverTick == currentTick) return
        val seatIndex = vehicle.turretControllerIndex
        val weaponIndex = vehicle.getSelectedWeapon(seatIndex)
        if (seatIndex < 0 || weaponIndex < 0 || vehicle.getGunName(seatIndex, weaponIndex) == null) return
        val yaw = actualYaw(VehicleAimChannel.TURRET)
        val pitch = actualPitch(VehicleAimChannel.TURRET)
        snapshots[VehicleAimChannel.TURRET] = snapshot(
            VehicleAimChannel.TURRET,
            seatIndex,
            weaponIndex,
            VehicleAimMode.INACTIVE,
            yaw,
            pitch,
            yaw,
            pitch,
            false,
        )
    }

    private fun resolveProfile(seatIndex: Int, weaponIndex: Int): VehicleAimProfile? =
        vehicle.resolveVehicleAimProfile(seatIndex, weaponIndex)
            ?.takeIf { it.directionFrame != VehicleAimDirectionFrame.PASSENGER_STATION_LOCAL ||
                vehicle.isPassengerStationLocalAim(seatIndex, weaponIndex) }

    private fun controller(channel: VehicleAimChannel): Entity? = when (channel) {
        VehicleAimChannel.TURRET -> vehicle.getNthEntity(vehicle.turretControllerIndex)
        VehicleAimChannel.PASSENGER_WEAPON -> vehicle.getNthEntity(vehicle.passengerWeaponStationControllerIndex)
    }

    private fun actualAppliedYaw(previousYaw: Float, nextYaw: Float, minYaw: Float, maxYaw: Float): Float =
        if (VehicleAimMath.isFullYawRange(minYaw, maxYaw)) Mth.wrapDegrees(previousYaw - nextYaw)
        else previousYaw - nextYaw

    companion object {
        private const val TICKS_PER_SECOND = 20F
        private const val MIN_DIRECTION_LENGTH_SQR = 1.0E-12
        private const val MAX_FCS_ZERO_RANGE_BLOCKS = VehicleShotPredictionService.MAX_PREDICTION_PATH_BLOCKS
        private const val LOCAL_YAW_TOLERANCE_DEGREES = 1.0E-3
        private const val PITCH_LIMIT_EPSILON = 1.0E-4F
    }
}
