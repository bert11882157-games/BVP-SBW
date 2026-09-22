package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimController
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimPresentationFrame
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot
import com.atsuishio.superbwarfare.network.NetworkTelemetry
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity

/** Owns immutable chassis-presentation sampling and its per-frame client cache. */
internal class VehicleClientPresentationService(
    private val vehicle: VehicleEntity,
    private val state: VehicleClientPresentationStateOwner,
    private val aimController: VehicleAimController,
) {
    private var chassisCacheStationLocal = false

    fun resolveChassisPresentation(partialTicks: Float): VehicleChassisPresentation = with(vehicle) {
        if (!level().isClientSide) {
            val anchor = state.vehiclePoseCurrent.anchor ?: position()
            val pose = state.vehiclePoseCurrent.copy(anchor = anchor, chassisYawDegrees = yRot)
            return@with VehicleChassisPresentation(
                pose, anchor, yRot, level().gameTime.toDouble(), 1F,
                pose.sequence, pose.sequence, 1,
                VehicleChassisPresentation.Mode.AUTHORITATIVE,
            )
        }

        val partial = partialTicks.coerceIn(0F, 1F)
        val partialBits = partial.toRawBits()
        val stationSeat = passengerWeaponStationControllerIndex
        val localStation = stationSeat >= 0 &&
            isPassengerStationLocalAim(stationSeat, getSelectedWeapon(stationSeat))
        state.chassisPresentationCache?.let { cached ->
            if (state.chassisPresentationCacheTick == tickCount &&
                state.chassisPresentationCachePartialBits == partialBits &&
                chassisCacheStationLocal == localStation
            ) return@with cached
        }

        val legacyAnchor = getLegacyInterpolatedPosition(partial)
        val legacyYaw = if (isFixedWingFlightVehicle()) getYaw(partial)
            else Mth.rotLerp(partial, yRotO, yRot)
        val result = when {
            flightStrategyOwnsAttitudeThisTick || resolveVehiclePoseProvider() == null -> {
                val legacyPose = VehiclePoseSnapshot.IDENTITY.withAuthority(
                    state.vehiclePoseCurrent.sequence,
                    state.vehiclePoseCurrent.serverTick,
                    legacyAnchor,
                    legacyYaw,
                )
                // Legacy ground renderers use this native attitude at the same partial. Keep
                // authored eyes and other immutable attachments on that exact pitched hull.
                // Flight may be selected before its first owned tick; retain its existing path.
                val pose = if (localStation || (!flightStrategyOwnsAttitudeThisTick &&
                    !isFixedWingFlightVehicle() && resolveVehicleFlightStrategy() == null)
                ) {
                    legacyPose.withBasePose(getPitch(partial), getRoll(partial))
                } else {
                    legacyPose
                }
                VehicleChassisPresentation(
                    pose, legacyAnchor, legacyYaw, level().gameTime + partial.toDouble(), partial,
                    pose.sequence, pose.sequence, 0, VehicleChassisPresentation.Mode.LEGACY,
                )
            }

            state.vehiclePoseAbsolutePending?.hardDiscontinuity == true -> {
                val pending = state.vehiclePoseAbsolutePending!!
                val pose = state.vehiclePoseCurrent.copy(
                    anchor = pending.target,
                    chassisYawDegrees = pending.wireYaw,
                )
                VehicleChassisPresentation(
                    pose, pending.target, pending.wireYaw, pose.serverTick.toDouble(), 1F,
                    pose.sequence, pose.sequence, state.vehicleChassisPresentationTimeline.depth,
                    VehicleChassisPresentation.Mode.DISCONTINUITY,
                )
            }

            else -> state.vehicleChassisPresentationTimeline.resolveOwned(
                partial,
                legacyAnchor,
                legacyYaw,
            )
        } ?: run {
            val fallback = state.vehiclePoseCurrent.anchor?.let {
                state.vehiclePoseCurrent.copy(anchor = it)
            } ?: VehiclePoseSnapshot.IDENTITY.withAuthority(
                state.vehiclePoseCurrent.sequence,
                state.vehiclePoseCurrent.serverTick,
                legacyAnchor,
                legacyYaw,
            )
            val anchor = fallback.anchor ?: legacyAnchor
            VehicleChassisPresentation(
                fallback, anchor, fallback.chassisYawDegrees, fallback.serverTick.toDouble(), 1F,
                fallback.sequence, fallback.sequence, state.vehicleChassisPresentationTimeline.depth,
                VehicleChassisPresentation.Mode.REMOTE_BASELINE,
            )
        }
        state.chassisPresentationCache = result
        state.chassisPresentationCacheTick = tickCount
        state.chassisPresentationCachePartialBits = partialBits
        chassisCacheStationLocal = localStation
        result
    }

    fun invalidateChassisCache() {
        state.chassisPresentationCache = null
        state.chassisPresentationCacheTick = Int.MIN_VALUE
    }

    fun resolveAimPresentationFrame(
        controller: Entity?,
        partialTicks: Float,
    ): VehicleAimPresentationFrame? {
        vehicle.refreshClientAimPresentationFromSyncedData(partialTicks)
        val seatIndex = vehicle.getSeatIndex(controller)
        if (seatIndex < 0) return null
        val weaponIndex = vehicle.getSelectedWeapon(seatIndex)
        val channel = vehicle.resolveVehicleAimProfile(seatIndex, weaponIndex)?.channel
            ?: aimController.snapshot(seatIndex, weaponIndex)?.channel
            ?: return null
        return resolveAimPresentationFrame(channel, seatIndex, weaponIndex, partialTicks)
    }

    fun resolveAimPresentationFrame(
        channel: VehicleAimChannel,
        seatIndex: Int,
        weaponIndex: Int,
        partialTicks: Float,
    ): VehicleAimPresentationFrame? = with(vehicle) {
        refreshClientAimPresentationFromSyncedData(partialTicks)
        if (!level().isClientSide || isWreck || (resolveVehicleFlightStrategy() != null &&
            !isPassengerStationLocalAim(seatIndex, weaponIndex)) ||
            seatIndex < 0 || weaponIndex < 0
        ) {
            recordAimPresentationAdmission(
                AimPresentationCacheKey(channel, seatIndex, weaponIndex),
                AimPresentationAdmissionReason.REJECT_APPLICABILITY,
            )
            return@with null
        }
        val partial = partialTicks.coerceIn(0F, 1F)
        val partialBits = partial.toRawBits()
        aimController.rebindCompatibleClientPresentation(
            channel,
            seatIndex,
            weaponIndex,
            getVehicleAimPresentationMode(seatIndex, weaponIndex),
        ) { previousWeaponIndex ->
            sharesClientPhysicalAimSemantics(seatIndex, previousWeaponIndex, weaponIndex)
        }
        val liveEpoch = getAimPresentationEpoch()
        if (state.aimPresentationContinuityEpoch != liveEpoch) {
            state.aimPresentationContinuity.clear()
            state.aimPresentationResolvedSequences.clear()
            state.aimPresentationTraceCacheHits.clear()
            state.aimPresentationAdmissionReasons.clear()
            state.aimPresentationContinuityEpoch = liveEpoch
        }
        if (state.aimPresentationCacheTick != tickCount ||
            state.aimPresentationCachePartialBits != partialBits ||
            state.aimPresentationCacheEpoch != liveEpoch
        ) {
            state.aimPresentationCache.clear()
            state.aimPresentationTraceCacheHits.clear()
            state.aimPresentationCacheTick = tickCount
            state.aimPresentationCachePartialBits = partialBits
            state.aimPresentationCacheEpoch = liveEpoch
        }
        val key = AimPresentationCacheKey(channel, seatIndex, weaponIndex)
        if (state.aimPresentationCache.containsKey(key)) {
            if (state.aimPresentationTraceCacheHits.add(key)) {
                recordAimPresentationFrameTrace(
                    key,
                    state.aimPresentationCache[key],
                    partialBits,
                    liveEpoch,
                    cacheHit = true,
                )
            }
            return@with state.aimPresentationCache[key]
        }
        val direct = buildAimPresentationFrame(key, partial)
        val result = if (direct != null) {
            if (!direct.continuityReprojected &&
                !direct.aim.tailHeld &&
                !direct.aim.contextRebound
            ) state.aimPresentationContinuity[key] = direct
            direct
        } else {
            val continuity = state.aimPresentationContinuity[key]
                ?: state.aimPresentationContinuity.entries.firstOrNull { (candidateKey, candidate) ->
                    candidateKey.channel == channel && candidateKey.seatIndex == seatIndex &&
                        candidate.presentationEpoch == liveEpoch &&
                        sharesClientPhysicalAimSemantics(
                            seatIndex,
                            candidateKey.weaponIndex,
                            weaponIndex,
                        )
                }?.value?.let { previous ->
                    val reboundWeaponName = getGunName(seatIndex, weaponIndex) ?: return@let null
                    previous.copy(
                        selectedWeaponIndex = weaponIndex,
                        weaponName = reboundWeaponName,
                        aim = previous.aim.copy(
                            selectedWeaponIndex = weaponIndex,
                            lockedDiagnostic = false,
                        ),
                    )
                }
            continuity?.let { reprojectAimPresentationContinuity(it, partial) }
        }
        state.aimPresentationCache[key] = result
        result?.let { frame ->
            if (state.aimPresentationResolvedSequences[key] != frame.aim.upperSequence) {
                state.aimPresentationResolvedSequences[key] = frame.aim.upperSequence
                NetworkTelemetry.recordSystemWork("aim.snapshot.resolved")
            }
        }
        recordAimPresentationFrameTrace(key, result, partialBits, liveEpoch, cacheHit = false)
        result
    }
}
