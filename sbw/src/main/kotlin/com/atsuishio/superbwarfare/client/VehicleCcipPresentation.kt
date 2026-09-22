package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalShotResult
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalShotDiagnostic
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalShotStatus
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalPredictionCursor
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalPredictionSlice
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalPredictionSliceBudget
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalPredictionSliceStatus
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalShotSnapshot
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.VehicleShotPredictionService
import com.atsuishio.superbwarfare.api.performance.ClientRenderPerformanceDiagnostics
import com.atsuishio.superbwarfare.client.camera.VehicleFreeCameraController
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Bounded client presentation cache for the nominal center trajectory's first geometric block contact.
 * This is not an exact random next-shot oracle: spread RNG is deliberately excluded by the shared predictor.
 */
object VehicleCcipPresentation {
    enum class AdmissionDiagnostic {
        ADMITTED, GUI_OPEN, PAUSED, UNFOCUSED, FREECAM, INVALID_SEAT,
        NO_SELECTED_WEAPON, NO_WEAPON_NAME, NON_FLIGHT_VEHICLE, INVALID_CONTEXT,
    }

    enum class ProjectionDiagnostic {
        NOT_EVALUATED,
        VISIBLE,
        NON_FINITE,
        BEHIND_CAMERA,
        OFFSCREEN,
        MARGIN_CLIPPED,
    }

    /** Internal lifecycle state; no state here grants aim, camera, or firing authority. */
    private enum class EvaluationState {
        IDLE,
        IN_PROGRESS,
        VALID_SOLUTION,
        NO_INTERSECTION,
        OUT_OF_RANGE,
        UNLOADED_CHUNK,
        TEMPORARY_INVALID_CONTEXT,
        BUDGET_EXHAUSTED,
        INVALID_NUMERIC,
    }

    private data class Context(
        val levelIdentity: Int,
        val playerUuid: UUID,
        val vehicleId: Int,
        val vehicleUuid: UUID,
        val seatIndex: Int,
        val selectedWeaponIndex: Int,
        val weaponName: String,
    )

    data class View(
        val vehicleUuid: UUID,
        val seatIndex: Int,
        val selectedWeaponIndex: Int,
        val weaponName: String,
        val result: NominalShotResult,
    )

    private var context: Context? = null
    private var view: View? = null
    private var currentImpact: Vec3? = null
    private var latestSnapshot: NominalShotSnapshot? = null
    private var generation = 0L
    private var activeGeneration = 0L
    private var pendingCursor: NominalPredictionCursor? = null
    private var pendingSnapshot: NominalShotSnapshot? = null
    private var pendingGeneration = Long.MIN_VALUE
    private var evaluationState = EvaluationState.IDLE
    private var lastPredictionContext: Context? = null
    private var lastPredictionStatus: NominalShotStatus? = null
    private var lastPredictionDiagnostic: NominalShotDiagnostic? = null
    private var nextPredictionLogGameTime = Long.MIN_VALUE
    private var lastProjectionContext: Context? = null
    private var lastProjectionDiagnostic: ProjectionDiagnostic? = null
    private var nextProjectionLogGameTime = Long.MIN_VALUE
    private var lastAdmissionVehicleUuid: UUID? = null
    private var lastAdmissionDiagnostic: AdmissionDiagnostic? = null

    /**
     * Client-tick END owner for the bounded CCIP predictor. Render/camera/input consumers only
     * read the immutable result cached here; they never enter collision traversal.
     */
    @JvmStatic
    fun tickClientEnd() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        val vehicle = player?.vehicle as? VehicleEntity
        if (player == null || vehicle == null || minecraft.level == null) {
            clear()
            return
        }
        if (minecraft.screen != null) return reject(vehicle, AdmissionDiagnostic.GUI_OPEN)
        if (minecraft.isPaused) return reject(vehicle, AdmissionDiagnostic.PAUSED)
        if (!minecraft.isWindowActive) return reject(vehicle, AdmissionDiagnostic.UNFOCUSED)
        if (VehicleFreeCameraController.hasPresentation(player, vehicle)) {
            return reject(vehicle, AdmissionDiagnostic.FREECAM)
        }
        val seatIndex = vehicle.getSeatIndex(player)
        if (seatIndex < 0) return reject(vehicle, AdmissionDiagnostic.INVALID_SEAT)
        val selectedWeaponIndex = vehicle.getPrimaryWeaponIndex(seatIndex)
        if (selectedWeaponIndex < 0) return reject(vehicle, AdmissionDiagnostic.NO_SELECTED_WEAPON)
        if (vehicle.getGunName(seatIndex, selectedWeaponIndex) == null) {
            return reject(vehicle, AdmissionDiagnostic.NO_WEAPON_NAME)
        }
        if (!ccipEligible(vehicle, seatIndex, selectedWeaponIndex)) {
            return reject(vehicle, AdmissionDiagnostic.NON_FLIGHT_VEHICLE)
        }
        val current = context(player, vehicle)
            ?: return reject(vehicle, AdmissionDiagnostic.INVALID_CONTEXT)
        if (context != current) resetForContext(current)
        reportAdmissionTransition(vehicle, AdmissionDiagnostic.ADMITTED)

        // Capture exactly one newest immutable launch tuple every END tick. Capture is cheap and
        // side-effect free; collision work is performed only by the one bounded cursor slice
        // below. A material change is a generation edge: the old cursor is cancelled before the
        // newest tuple begins in this same tick, so no obsolete work can sit in a queue.
        val capture = VehicleShotPredictionService.captureClientNominalSnapshotIfFiredNow(
            vehicle, player, 1F,
        )
        val snapshot = capture.snapshot.takeIf { capture.status == NominalShotStatus.READY }
        if (snapshot == null) {
            invalidateForCaptureFailure(vehicle, player, current, capture.status, capture.diagnostic)
            return
        }

        val previous = latestSnapshot
        if (previous == null || VehicleShotPredictionService.isClientNominalSnapshotMateriallyStale(
                previous, snapshot,
            )
        ) {
            latestSnapshot = snapshot
            activeGeneration = nextGeneration()
            clearDisplayedImpact()
            cancelPending()
            beginPendingSnapshot(vehicle, player, current, snapshot, activeGeneration)
            return
        }

        if (pendingCursor != null && pendingGeneration == activeGeneration &&
            evaluationState == EvaluationState.IN_PROGRESS
        ) {
            advancePending(vehicle, player, current, activeGeneration)
        } else {
            // There is no trustworthy world-mutation epoch. Refresh an equivalent launch tuple
            // on the normal next END tick so a pillar/world edit cannot pin a terminal endpoint;
            // retain the prior valid endpoint while this same-tuple generation recomputes.
            latestSnapshot = snapshot
            activeGeneration = nextGeneration()
            cancelPending()
            beginPendingSnapshot(vehicle, player, current, snapshot, activeGeneration)
        }
    }

    @JvmStatic
    fun activeView(player: Player, partialTicks: Float): View? =
        activeView(player, partialTicks, 0L)

    /** Render-only read path. The predictor is intentionally absent from this method. */
    @Suppress("UNUSED_PARAMETER")
    internal fun activeView(player: Player, partialTicks: Float, nowNanos: Long): View? {
        val vehicle = player.vehicle as? VehicleEntity ?: return null
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null || minecraft.screen != null || minecraft.isPaused ||
            !minecraft.isWindowActive || VehicleFreeCameraController.hasPresentation(player, vehicle)
        ) return null
        val seatIndex = vehicle.getSeatIndex(player)
        val selectedWeaponIndex = vehicle.getPrimaryWeaponIndex(seatIndex)
        if (!ccipEligible(vehicle, seatIndex, selectedWeaponIndex)) {
            return null
        }
        val current = context(player, vehicle) ?: return null
        if (context != current) return null
        val presentationValid = evaluationState == EvaluationState.VALID_SOLUTION ||
            (evaluationState == EvaluationState.IN_PROGRESS &&
                currentImpact != null && view?.result?.status in VISIBLE_IMPACT_STATUSES)
        return view.takeIf { presentationValid }
    }

    @JvmStatic
    fun presentationPoint(): Vec3? {
        val presentationValid = evaluationState == EvaluationState.VALID_SOLUTION ||
            (evaluationState == EvaluationState.IN_PROGRESS && currentImpact != null)
        return currentImpact.takeIf { presentationValid }
    }

    @JvmStatic
    fun clear() {
        context = null
        view = null
        currentImpact = null
        cancelPending()
        latestSnapshot = null
        activeGeneration = nextGeneration()
        evaluationState = EvaluationState.IDLE
        lastPredictionContext = null
        lastPredictionStatus = null
        lastPredictionDiagnostic = null
        nextPredictionLogGameTime = Long.MIN_VALUE
        lastProjectionContext = null
        lastProjectionDiagnostic = null
        nextProjectionLogGameTime = Long.MIN_VALUE
        lastAdmissionVehicleUuid = null
        lastAdmissionDiagnostic = null
    }

    private fun reject(vehicle: VehicleEntity, diagnostic: AdmissionDiagnostic) {
        context = null
        view = null
        currentImpact = null
        cancelPending()
        latestSnapshot = null
        activeGeneration = nextGeneration()
        evaluationState = EvaluationState.TEMPORARY_INVALID_CONTEXT
        lastPredictionContext = null
        lastPredictionStatus = null
        lastPredictionDiagnostic = null
        nextPredictionLogGameTime = Long.MIN_VALUE
        lastProjectionContext = null
        lastProjectionDiagnostic = null
        nextProjectionLogGameTime = Long.MIN_VALUE
        reportAdmissionTransition(vehicle, diagnostic)
    }

    private fun reportAdmissionTransition(vehicle: VehicleEntity, diagnostic: AdmissionDiagnostic) {
        if (lastAdmissionVehicleUuid == vehicle.uuid && lastAdmissionDiagnostic == diagnostic) return
        lastAdmissionVehicleUuid = vehicle.uuid
        lastAdmissionDiagnostic = diagnostic
        Mod.LOGGER.info("CCIP admission transition vehicle={} diagnostic={}", vehicle.uuid, diagnostic)
    }

    @JvmStatic
    fun reportProjection(player: Player, diagnostic: ProjectionDiagnostic) {
        val vehicle = player.vehicle as? VehicleEntity ?: return
        val current = context(player, vehicle) ?: return
        if (lastProjectionContext == current && lastProjectionDiagnostic == diagnostic) return
        val gameTime = player.level().gameTime
        if (lastProjectionContext == current && gameTime < nextProjectionLogGameTime) return
        lastProjectionContext = current
        lastProjectionDiagnostic = diagnostic
        nextProjectionLogGameTime = gameTime + PROJECTION_LOG_INTERVAL_TICKS
        Mod.LOGGER.info(
            "CCIP projection transition vehicle={} seat={} weapon={} diagnostic={}",
            current.vehicleUuid, current.seatIndex, current.weaponName, diagnostic,
        )
    }

    private fun reportPredictionTransition(current: Context, result: NominalShotResult, gameTime: Long) {
        if (lastPredictionContext == current && lastPredictionStatus == result.status &&
            lastPredictionDiagnostic == result.diagnostic
        ) return
        if (gameTime < nextPredictionLogGameTime) return
        lastPredictionContext = current
        lastPredictionStatus = result.status
        lastPredictionDiagnostic = result.diagnostic
        nextPredictionLogGameTime = gameTime + PREDICTION_LOG_INTERVAL_TICKS
        Mod.LOGGER.info(
            "CCIP prediction transition vehicle={} seat={} weapon={} status={} diagnostic={}",
            current.vehicleUuid, current.seatIndex, current.weaponName, result.status, result.diagnostic,
        )
    }

    private fun resetForContext(current: Context) {
        cancelPending()
        context = current
        view = null
        currentImpact = null
        latestSnapshot = null
        activeGeneration = nextGeneration()
        evaluationState = EvaluationState.IDLE
        lastPredictionContext = null
        lastPredictionStatus = null
        lastPredictionDiagnostic = null
        nextPredictionLogGameTime = Long.MIN_VALUE
        lastProjectionContext = null
        lastProjectionDiagnostic = null
        nextProjectionLogGameTime = Long.MIN_VALUE
    }

    /** Starts one immutable snapshot/cursor and advances exactly one bounded slice this tick. */
    private fun beginPendingSnapshot(
        vehicle: VehicleEntity,
        player: Player,
        current: Context,
        snapshot: NominalShotSnapshot,
        token: Long,
    ) {
        pendingSnapshot = snapshot
        pendingGeneration = token
        pendingCursor = VehicleShotPredictionService.beginClientNominalPrediction(snapshot, vehicle.level())
        evaluationState = EvaluationState.IN_PROGRESS
        advancePending(vehicle, player, current, token)
    }

    /** Advances at most one bounded scheduler slice. Render/HUD never enters this path. */
    private fun advancePending(
        vehicle: VehicleEntity,
        player: Player,
        current: Context,
        token: Long,
    ) {
        if (token != activeGeneration || token != pendingGeneration) {
            cancelPending()
            return
        }
        if (evaluationState != EvaluationState.IN_PROGRESS) {
            cancelPending()
            return
        }
        val cursor = pendingCursor ?: return
        val snapshot = pendingSnapshot ?: run {
            cancelPending()
            evaluationState = EvaluationState.TEMPORARY_INVALID_CONTEXT
            return
        }
        if (snapshot.vehicleUuid != current.vehicleUuid ||
            snapshot.seatIndex != current.seatIndex ||
            snapshot.selectedWeaponIndex != current.selectedWeaponIndex ||
            snapshot.weaponName != current.weaponName ||
            vehicle.level() !== player.level()
        ) {
            clearDisplayedImpact()
            cancelPending()
            evaluationState = EvaluationState.TEMPORARY_INVALID_CONTEXT
            return
        }
        val performanceStarted = ClientRenderPerformanceDiagnostics.startTimer()
        var slice: NominalPredictionSlice? = null
        try {
            slice = VehicleShotPredictionService.advanceClientNominalPrediction(
                cursor,
                snapshot,
                vehicle.level(),
                SLICE_BUDGET,
            )
        } finally {
            ClientRenderPerformanceDiagnostics.recordCcipSample(
                performanceStarted,
                slice?.voxelVisits ?: 0,
            )
        }
        val completed = slice ?: return
        when (completed.status) {
            NominalPredictionSliceStatus.IN_PROGRESS -> {
                pendingCursor = completed.cursor ?: run {
                    clearDisplayedImpact()
                    cancelPending()
                    evaluationState = EvaluationState.TEMPORARY_INVALID_CONTEXT
                    return
                }
                evaluationState = EvaluationState.IN_PROGRESS
            }
            NominalPredictionSliceStatus.CANCELLED -> {
                clearDisplayedImpact()
                cancelPending()
                evaluationState = EvaluationState.TEMPORARY_INVALID_CONTEXT
            }
            NominalPredictionSliceStatus.COMPLETE -> {
                val result = completed.result ?: run {
                    clearDisplayedImpact()
                    cancelPending()
                    evaluationState = EvaluationState.INVALID_NUMERIC
                    return
                }
                cancelPending()
                if (token != activeGeneration || latestSnapshot == null ||
                    VehicleShotPredictionService.isClientNominalSnapshotMateriallyStale(
                        snapshot, latestSnapshot!!,
                    )
                ) {
                    // A late completion can never revive an older generation. The newest tuple
                    // will already have started or will start on its own capture edge.
                    clearDisplayedImpact()
                    return
                }
                consumeResult(vehicle, player, current, result)
            }
        }
    }

    private fun cancelPending() {
        if (pendingCursor != null || pendingSnapshot != null) {
            VehicleShotPredictionService.cancelClientNominalPrediction()
        }
        pendingCursor = null
        pendingSnapshot = null
    }

    private fun clearDisplayedImpact() {
        view = null
        currentImpact = null
    }

    private fun consumeResult(
        vehicle: VehicleEntity,
        player: Player,
        current: Context,
        result: NominalShotResult,
    ) {
        evaluationState = evaluationState(result)
        val point = result.point?.takeIf {
            result.status in VISIBLE_IMPACT_STATUSES &&
                it.x.isFinite() && it.y.isFinite() && it.z.isFinite()
        }
        currentImpact = point
        view = point?.let {
            View(vehicle.uuid, current.seatIndex, current.selectedWeaponIndex, current.weaponName, result)
        }
        if (point == null) {
            // Non-impact, unloaded, budget, and invalid outcomes are explicit terminal states;
            // they never keep a previous endpoint alive.
            currentImpact = null
        }
        reportPredictionTransition(current, result, player.level().gameTime)
    }

    private fun invalidateForCaptureFailure(
        vehicle: VehicleEntity,
        player: Player,
        current: Context,
        status: NominalShotStatus,
        diagnostic: NominalShotDiagnostic,
    ) {
        val result = NominalShotResult(status, diagnostic = diagnostic)
        latestSnapshot = null
        activeGeneration = nextGeneration()
        clearDisplayedImpact()
        cancelPending()
        evaluationState = evaluationState(result)
        reportPredictionTransition(current, result, player.level().gameTime)
    }

    private fun nextGeneration(): Long {
        generation += 1L
        return generation
    }

    private fun evaluationState(result: NominalShotResult): EvaluationState = when {
        result.status in VISIBLE_IMPACT_STATUSES -> EvaluationState.VALID_SOLUTION
        result.diagnostic == NominalShotDiagnostic.OWNER_DISTANCE_EXPIRED ->
            EvaluationState.OUT_OF_RANGE
        result.status == NominalShotStatus.NO_IMPACT -> EvaluationState.NO_INTERSECTION
        result.status == NominalShotStatus.UNLOADED_TERRAIN -> EvaluationState.UNLOADED_CHUNK
        result.status == NominalShotStatus.BUDGET_EXCEEDED -> EvaluationState.BUDGET_EXHAUSTED
        result.status == NominalShotStatus.NON_FINITE -> EvaluationState.INVALID_NUMERIC
        else -> EvaluationState.TEMPORARY_INVALID_CONTEXT
    }

    private fun context(player: Player, vehicle: VehicleEntity): Context? {
        if (!player.isAlive || player.isSpectator || vehicle.isRemoved || vehicle.isWreck ||
            vehicle.level() !== player.level()
        ) return null
        val seatIndex = vehicle.getSeatIndex(player)
        if (seatIndex < 0) return null
        val selectedWeaponIndex = vehicle.getPrimaryWeaponIndex(seatIndex)
        val weaponName = vehicle.getGunName(seatIndex, selectedWeaponIndex) ?: return null
        val levelIdentity = System.identityHashCode(player.level())
        context?.takeIf {
            it.levelIdentity == levelIdentity &&
                    it.playerUuid == player.uuid &&
                    it.vehicleId == vehicle.id &&
                    it.vehicleUuid == vehicle.uuid &&
                    it.seatIndex == seatIndex &&
                    it.selectedWeaponIndex == selectedWeaponIndex &&
                    it.weaponName == weaponName
        }?.let { return it }
        return Context(
            levelIdentity,
            player.uuid,
            vehicle.id,
            vehicle.uuid,
            seatIndex,
            selectedWeaponIndex,
            weaponName,
        )
    }

    /** Aircraft use the single body-forward cue; retain CCIP for other flight strategies. */
    private fun ccipEligible(vehicle: VehicleEntity, seatIndex: Int, selectedWeaponIndex: Int): Boolean {
        return seatIndex >= 0 && selectedWeaponIndex >= 0 &&
            !vehicle.isFixedWingFlightVehicle() && vehicle.vehicleType !=
                com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE &&
            vehicle.resolveVehicleFlightStrategy() != null
    }

    private const val PROJECTION_LOG_INTERVAL_TICKS = 10L
    private const val PREDICTION_LOG_INTERVAL_TICKS = 10L
    /** Finite hard ceiling; the 1 ms/512-voxel limits decide how much clear-air work fits. */
    private const val MAX_CURSOR_SEGMENTS = 160
    private val SLICE_BUDGET = NominalPredictionSliceBudget(
        maxSegments = MAX_CURSOR_SEGMENTS,
        maxVoxelVisits = 512,
        maxNanos = 1_000_000L,
    )
    private val VISIBLE_IMPACT_STATUSES = setOf(
        NominalShotStatus.IMPACT,
        NominalShotStatus.WATER_SURFACE_IMPACT,
    )
}
