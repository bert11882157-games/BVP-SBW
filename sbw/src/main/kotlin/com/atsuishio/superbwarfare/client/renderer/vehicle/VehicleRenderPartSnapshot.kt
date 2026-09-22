package com.atsuishio.superbwarfare.client.renderer.vehicle

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimPresentationFrame
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.AimPresentationTraceSample
import com.atsuishio.superbwarfare.network.NetworkTelemetry
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import java.util.WeakHashMap

/**
 * Immutable, renderer-neutral vehicle-part state for one rendered frame.
 *
 * Both the raw interpolated world angles used by SBW's Gecko models and the
 * angles resolved against the dispatcher-provided hull yaw are retained. The
 * latter lets non-Gecko backends preserve their existing interpolation basis
 * without reconstructing the same formulas independently.
 */
data class VehicleRenderPartSnapshot(
    val interpolatedHullYawDegrees: Float,
    val hullPitchDegrees: Float,
    val hullRollDegrees: Float,
    val turretWorldYawDegrees: Float,
    val turretPitchDegrees: Float,
    val turretYawFromRenderedHullDegrees: Float,
    val barrelPitchDegrees: Float,
    val stationYawRelativeToTurretDegrees: Float,
    val stationYawFromRenderedHullDegrees: Float,
    val stationPitchDegrees: Float,
    val stationPitchRadians: Float,
    /**
     * The selected authored AGS-30 turret-channel pitch, or null unless the exact
     * GrenadeLauncher profile/frame is active.  This is presentation-only: the
     * native turret aim frame remains the sole authority and no muzzle/ballistic
     * state is reconstructed here.
     */
    val ags30PitchDegrees: Float?,
    val recoilShake: Float,
    val cannonRecoilTime: Int,
    val cannonRecoilForce: Float,
    val aimPresentationValid: Boolean,
    val stationPresentationValid: Boolean,
    val turretHiddenByVehicleState: Boolean,
) {
    companion object {
        private data class HeldTurretPose(
            val epoch: Int,
            val seat: Int,
            val weapon: Int,
            val yaw: Float,
            val pitch: Float,
            val capturedGameTime: Long,
        )

        private data class HeldStationPose(
            val epoch: Int,
            val seat: Int,
            val weapon: Int,
            val yaw: Float,
            val pitch: Float,
            val capturedGameTime: Long,
        )

        private val HELD_TURRET_POSES = WeakHashMap<VehicleEntity, HeldTurretPose>()
        private val HELD_STATION_POSES = WeakHashMap<VehicleEntity, HeldStationPose>()

        @JvmStatic
        fun capture(
            vehicle: VehicleEntity,
            renderedHullYawDegrees: Float,
            partialTick: Float,
        ): VehicleRenderPartSnapshot {
            val turretSeat = vehicle.turretControllerIndex
            val turretWeapon = turretSeat.takeIf { it >= 0 }?.let(vehicle::getSelectedWeapon) ?: -1
            val stationSeat = vehicle.passengerWeaponStationControllerIndex
            val stationWeapon = stationSeat.takeIf { it >= 0 }?.let(vehicle::getSelectedWeapon) ?: -1
            val coherentBase = vehicle.level().isClientSide && !vehicle.isWreck &&
                    vehicle.resolveVehicleFlightStrategy() == null
            val coherentTurret = coherentBase && turretSeat >= 0 && turretWeapon >= 0 &&
                    vehicle.getNthEntity(turretSeat) is Player &&
                    vehicle.resolveVehicleAimProfile(turretSeat, turretWeapon) != null
            val coherentStation = coherentBase && stationSeat >= 0 && stationWeapon >= 0 &&
                    vehicle.getNthEntity(stationSeat) is Player &&
                    vehicle.resolveVehicleAimProfile(stationSeat, stationWeapon) != null
            val coherentAimPresentation = coherentTurret || coherentStation
            // getAimPresentationEpoch is the atomic preparation barrier: it consumes and
            // compatibly rebinds the complete payload before returning the final epoch.
            val presentationEpoch = if (coherentAimPresentation) vehicle.getAimPresentationEpoch() else Int.MIN_VALUE
            // Passenger-station presentation is authored beneath the same physical turret.
            // Resolve its explicit passive TURRET parent even when the main seat is empty. Missing
            // motion authority falls through to the bounded/neutral topology-visible policy below.
            val resolvedTurret = if (coherentTurret || coherentStation) {
                vehicle.resolveAimPresentationFrame(
                    VehicleAimChannel.TURRET,
                    turretSeat,
                    turretWeapon,
                    partialTick,
                )?.takeIf { it.presentationEpoch == presentationEpoch }
            } else null

            val resolvedStation = if (coherentStation) {
                vehicle.resolveAimPresentationFrame(
                    VehicleAimChannel.PASSENGER_WEAPON,
                    stationSeat,
                    stationWeapon,
                    partialTick,
                )?.takeIf { it.presentationEpoch == presentationEpoch }
            } else null
            // The central resolver already returns a current-chassis reprojection for bounded
            // same-epoch gaps. Never add a second cache of hull-relative local axes here.
            val turretFrame = resolvedTurret ?: resolvedStation
            val stationFrame = resolvedStation
            val now = vehicle.level().gameTime
            if (vehicle.isRemoved || vehicle.isWreck) {
                HELD_TURRET_POSES.remove(vehicle)
                HELD_STATION_POSES.remove(vehicle)
            }
            if (coherentAimPresentation && turretFrame != null && !turretFrame.continuityReprojected) {
                HELD_TURRET_POSES[vehicle] = HeldTurretPose(
                    presentationEpoch, turretSeat, turretWeapon,
                    turretFrame.presentedTurretYaw, turretFrame.presentedTurretPitch, now,
                )
            }
            if (coherentStation && stationFrame != null && !stationFrame.continuityReprojected &&
                stationFrame.presentedStationYaw != null && stationFrame.presentedStationPitch != null
            ) {
                HELD_STATION_POSES[vehicle] = HeldStationPose(
                    presentationEpoch, stationSeat, stationWeapon,
                    stationFrame.presentedStationYaw!!, stationFrame.presentedStationPitch!!, now,
                )
            }
            val heldTurret = HELD_TURRET_POSES[vehicle]?.takeIf {
                it.epoch == presentationEpoch && it.seat == turretSeat && it.weapon == turretWeapon &&
                        now - it.capturedGameTime in 0..POSE_HOLD_TICKS
            }
            val heldStation = HELD_STATION_POSES[vehicle]?.takeIf {
                it.epoch == presentationEpoch && it.seat == stationSeat && it.weapon == stationWeapon &&
                        now - it.capturedGameTime in 0..POSE_HOLD_TICKS
            }
            val synchronizedTurretYaw = vehicle.turretYRot.takeIf(Float::isFinite)
                ?: Mth.rotLerp(partialTick, vehicle.turretYRotO, vehicle.turretYRot)
                    .takeIf(Float::isFinite) ?: 0F
            val synchronizedTurretPitch = vehicle.turretXRot.takeIf(Float::isFinite)
                ?: Mth.lerp(partialTick, vehicle.turretXRotO, vehicle.turretXRot)
                    .takeIf(Float::isFinite) ?: 0F
            val synchronizedStationYaw = vehicle.gunYRot.takeIf(Float::isFinite)
                ?: Mth.rotLerp(partialTick, vehicle.gunYRotO, vehicle.gunYRot)
                    .takeIf(Float::isFinite) ?: 0F
            val synchronizedStationPitch = vehicle.gunXRot.takeIf(Float::isFinite)
                ?: Mth.lerp(partialTick, vehicle.gunXRotO, vehicle.gunXRot)
                    .takeIf(Float::isFinite) ?: 0F
            // Presentation loss revokes motion authority, not intact model topology. A current
            // frame wins, then a short same-context physical pose hold, then a finite neutral
            // topology-visible baseline. Never reuse mutable entity turret/gun axes for an
            // eligible coherent channel: those fields can be one hull-relative tick stale and
            // make the model follow the hull before snapping back when the frame returns.
            val aimPresentationValid = true
            val stationPresentationValid = true
            val turretWorldYaw = if (coherentAimPresentation) {
                turretFrame?.presentedTurretYaw?.takeIf(Float::isFinite)
                    ?: heldTurret?.yaw?.takeIf(Float::isFinite)
                    ?: 0F
            }
            else synchronizedTurretYaw
            val turretPitch = if (coherentAimPresentation) {
                turretFrame?.presentedTurretPitch?.takeIf(Float::isFinite)
                    ?: heldTurret?.pitch?.takeIf(Float::isFinite)
                    ?: 0F
            }
            else synchronizedTurretPitch
            val turretYawFromRenderedHull = if (coherentAimPresentation) turretWorldYaw else Mth.wrapDegrees(
                renderedHullYawDegrees - vehicle.getBarrelYRot(partialTick)
            )
            val stationWorldYaw = if (coherentStation) {
                stationFrame?.presentedStationYaw?.takeIf(Float::isFinite)
                    ?: heldStation?.yaw?.takeIf(Float::isFinite)
                    ?: 0F
            } else
                synchronizedStationYaw
            val stationPitch = if (coherentStation) {
                (stationFrame?.presentedStationPitch?.takeIf(Float::isFinite)
                    ?: heldStation?.pitch?.takeIf(Float::isFinite)
                    ?: 0F).let {
                    Mth.clamp(-it, vehicle.passengerWeaponMinPitch, vehicle.passengerWeaponMaxPitch)
                }
            } else {
                Mth.clamp(-synchronizedStationPitch, vehicle.passengerWeaponMinPitch, vehicle.passengerWeaponMaxPitch)
            }
            val stationYawRelativeToTurret = if (coherentStation) {
                stationWorldYaw?.let { Mth.wrapDegrees(it - turretWorldYaw) } ?: 0F
            } else {
                Mth.wrapDegrees((stationWorldYaw ?: 0F) - turretWorldYaw)
            }

            // BMP-2M's authored AGS-30 subtree is a second turret child, not a
            // passenger station.  Admit it only for the exact typed weapon
            // channel and an eligible coherent turret context.  The pitch comes
            // from the same current/held presentation frame that drives the
            // authoritative turret; absent context fails closed (null), so a
            // stale or unsupported frame can never invent a pose.
            val ags30PitchDegrees = if (coherentTurret &&
                vehicle.getGunName(turretSeat, turretWeapon) == "GrenadeLauncher" &&
                vehicle.resolveVehicleAimProfile(turretSeat, turretWeapon)?.channel == VehicleAimChannel.TURRET
            ) {
                (turretFrame?.presentedTurretPitch ?: heldTurret?.pitch)
                    ?.takeIf(Float::isFinite)
                    ?.let { Mth.clamp(-it, vehicle.turretMinPitch, vehicle.turretMaxPitch) }
            } else null

            // Capture only the renderer's numeric consumption decision. This is telemetry-only:
            // it does not feed the frame resolver or alter the bounded hold/neutral policy.
            if (coherentTurret || coherentStation) {
                recordRendererAimTrace(
                    vehicle,
                    turretFrame?.channel ?: VehicleAimChannel.TURRET,
                    turretFrame,
                    turretWorldYaw,
                    turretPitch,
                    heldTurret != null,
                    presentationEpoch,
                    partialTick,
                )
                if (coherentStation) {
                    recordRendererAimTrace(
                        vehicle,
                        VehicleAimChannel.PASSENGER_WEAPON,
                        stationFrame,
                        stationWorldYaw ?: 0F,
                        stationPitch,
                        heldStation != null,
                        presentationEpoch,
                        partialTick,
                    )
                }
            }

            return VehicleRenderPartSnapshot(
                interpolatedHullYawDegrees = vehicle.getYaw(partialTick),
                hullPitchDegrees = vehicle.getPitch(partialTick),
                hullRollDegrees = vehicle.getRoll(partialTick),
                turretWorldYawDegrees = turretWorldYaw,
                turretPitchDegrees = turretPitch,
                turretYawFromRenderedHullDegrees = turretYawFromRenderedHull,
                barrelPitchDegrees = Mth.clamp(
                    -turretPitch,
                    vehicle.turretMinPitch,
                    vehicle.turretMaxPitch,
                ),
                stationYawRelativeToTurretDegrees = stationYawRelativeToTurret,
                stationYawFromRenderedHullDegrees = if (coherentStation) stationYawRelativeToTurret else Mth.wrapDegrees(
                    renderedHullYawDegrees - vehicle.getGunYRot(partialTick) - turretYawFromRenderedHull
                ),
                stationPitchDegrees = stationPitch,
                stationPitchRadians = stationPitch * Mth.DEG_TO_RAD,
                ags30PitchDegrees = ags30PitchDegrees,
                recoilShake = Mth.lerp(
                    partialTick,
                    vehicle.recoilShakeO.toFloat(),
                    vehicle.recoilShake.toFloat(),
                ),
                cannonRecoilTime = vehicle.cannonRecoilTime,
                cannonRecoilForce = vehicle.cannonRecoilForce,
                aimPresentationValid = aimPresentationValid,
                stationPresentationValid = stationPresentationValid,
                turretHiddenByVehicleState =
                    vehicle.isWreck && vehicle.hasTurret() && vehicle.sympatheticDetonated,
            )
        }

        @JvmStatic
        fun capture(vehicle: VehicleEntity, partialTick: Float): VehicleRenderPartSnapshot {
            return capture(vehicle, vehicle.getYaw(partialTick), partialTick)
        }

        private fun recordRendererAimTrace(
            vehicle: VehicleEntity,
            channel: VehicleAimChannel,
            frame: VehicleAimPresentationFrame?,
            consumedYaw: Float,
            consumedPitch: Float,
            heldFallback: Boolean,
            epoch: Int,
            partialTick: Float,
        ) {
            if (!NetworkTelemetry.isAimPresentationTraceEnabled()) return
            val aim = frame?.aim
            val continuity = frame?.continuityReprojected == true
            val reason = when {
                frame != null && continuity -> "CONTINUITY"
                frame != null && (aim?.tailHeld == true || aim?.contextRebound == true) -> "HELD_FRAME"
                frame != null -> "FRAME"
                heldFallback -> "HELD_RENDER"
                else -> "NEUTRAL_RENDER"
            }
            NetworkTelemetry.recordAimPresentationTrace(
                AimPresentationTraceSample(
                    stage = "RENDERED",
                    entityId = vehicle.id,
                    clientTick = vehicle.tickCount,
                    partialBits = partialTick.coerceIn(0F, 1F).toRawBits(),
                    channel = channel.ordinal,
                    seatIndex = aim?.seatIndex ?: -1,
                    weaponIndex = aim?.selectedWeaponIndex ?: -1,
                    sequence = aim?.upperSequence ?: -1,
                    upperSequence = aim?.upperSequence ?: -1,
                    serverTick = aim?.sourceServerTick?.takeIf { it.isFinite() }?.toLong() ?: -1L,
                    sourceServerTick = aim?.sourceServerTick?.takeIf { it.isFinite() } ?: -1.0,
                    epoch = epoch,
                    reason = reason,
                    cacheHit = false,
                    direct = frame != null && !continuity && aim?.tailHeld != true && aim?.contextRebound != true,
                    continuity = continuity,
                    tailHeld = aim?.tailHeld == true || heldFallback,
                    alpha = aim?.alpha ?: 1F,
                    axesValid = consumedYaw.isFinite() && consumedPitch.isFinite(),
                    actualYaw = aim?.actualYaw ?: consumedYaw,
                    actualPitch = aim?.actualPitch ?: consumedPitch,
                    presentedYaw = consumedYaw,
                    presentedPitch = consumedPitch,
                ),
            )
        }

        private const val POSE_HOLD_TICKS = 3L
    }
}
