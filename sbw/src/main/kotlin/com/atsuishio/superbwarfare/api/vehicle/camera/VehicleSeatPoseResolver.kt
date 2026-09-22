package com.atsuishio.superbwarfare.api.vehicle.camera

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimPresentationFrame
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleAttachmentSnapshot
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.LinkedHashMap
import java.util.UUID

/** Resolves one coherent rider/camera pose while retaining legacy body placement fallback. */
internal object VehicleSeatPoseResolver {
    private data class CoherentKey(
        val level: Level,
        val vehicleUuid: UUID,
        val passengerUuid: UUID,
        val seatIndex: Int,
        val selectedWeaponIndex: Int,
        val zooming: Boolean,
        val presentationEpoch: Int,
    )

    private data class HoldSlot(val passengerUuid: UUID, val zooming: Boolean)

    private data class SafeKey(
        val level: Level,
        val vehicleUuid: UUID,
        val passengerUuid: UUID,
        val seatIndex: Int,
        val zooming: Boolean,
    )

    private data class HeldPose(
        val key: CoherentKey,
        val authoritativeFrame: VehicleAimPresentationFrame,
        val capturedGameTime: Long,
    )

    /** Camera/body placement only; this carries no authoritative aim or lock state. */
    private data class ChassisSafePose(
        val key: SafeKey,
        val template: VehicleSeatPoseSnapshot,
        val bodyLocal: Vec3,
        val eyeLocal: Vec3,
        val directionLocal: Vec3?,
    )

    private val heldBySlot = LinkedHashMap<HoldSlot, HeldPose>(32, 0.75f, true)
    private val safeBySlot = LinkedHashMap<HoldSlot, ChassisSafePose>(32, 0.75f, true)

    fun resolve(
        vehicle: VehicleEntity,
        passenger: Entity,
        partialTicks: Float,
        zooming: Boolean,
    ): VehicleSeatPoseSnapshot? {
        val seatIndex = vehicle.getSeatIndex(passenger)
        if (seatIndex < 0) return null
        val selectedWeaponIndex = vehicle.getSelectedWeapon(seatIndex)
        val coherentClientAim = vehicle.level().isClientSide &&
                (vehicle.resolveVehicleFlightStrategy() == null ||
                    vehicle.isPassengerStationLocalAimController(passenger)) && passenger is Player &&
                vehicle.resolveVehicleAimProfile(seatIndex, selectedWeaponIndex) != null
        if (!coherentClientAim) {
            (vehicle as? VehicleSeatPoseProvider)?.createVehicleSeatPose(
                passenger,
                seatIndex,
                selectedWeaponIndex,
                partialTicks,
                zooming,
            )?.let { return it }
        }

        val key = if (coherentClientAim) CoherentKey(
            vehicle.level(), vehicle.uuid, passenger.uuid, seatIndex,
            selectedWeaponIndex, zooming, vehicle.getAimPresentationEpoch(),
        ) else null
        val safeKey = key?.let {
            SafeKey(it.level, it.vehicleUuid, it.passengerUuid, it.seatIndex,
                it.zooming)
        }
        if (key != null) invalidateMismatched(HoldSlot(passenger.uuid, zooming), key, safeKey!!)

        val seat = vehicle.computed().seats().getOrNull(seatIndex) ?: return null
        val camera = seat.cameraPos ?: return null
        val eyeAnchor = (if (zooming) camera.zoomEyeAttachment else null)
            ?.takeIf { it.isNotBlank() }
            ?: camera.eyeAttachment?.takeIf { it.isNotBlank() }
            ?: if (coherentClientAim) {
                return chassisSafeOrNull(safeKey, vehicle, passenger, partialTicks)
            } else return null
        val directionAnchor = (if (zooming) camera.zoomDirectionAttachment else null)
            ?.takeIf { it.isNotBlank() }
            ?: camera.directionAttachment?.takeIf { it.isNotBlank() }
            ?: eyeAnchor
        val authoritativeFrame = if (coherentClientAim) {
            vehicle.resolveAimPresentationFrame(passenger, partialTicks)
                ?.takeIf { it.presentationEpoch == key!!.presentationEpoch }
        } else null
        val presentationFrame = if (coherentClientAim) {
            authoritativeFrame ?: heldFrameOrNull(key, vehicle)
                ?.let { vehicle.reprojectAimPresentationContinuity(it, partialTicks) }
                ?: return chassisSafeOrNull(safeKey, vehicle, passenger, partialTicks)
        } else null
        val attachments = if (coherentClientAim) {
            presentationFrame!!.attachments
        } else {
            vehicle.getVehicleAttachmentSnapshot(partialTicks)
        }
        val bodyAnchor = seat.bodyAttachment?.takeIf { it.isNotBlank() }
            ?: seat.transform?.takeIf { it.isNotBlank() }
            ?: "Vehicle"
        val body = attachments.point(bodyAnchor, seat.position) ?: if (coherentClientAim) {
            return chassisSafeOrNull(safeKey, vehicle, passenger, partialTicks)
        } else run {
            val legacy = vehicle.transformPosition(
                vehicle.getTransformFromString(seat.transform, partialTicks),
                seat.position.x,
                seat.position.y,
                seat.position.z,
            )
            Vec3(legacy.x, legacy.y, legacy.z)
        }
        val eyeOffset = if (zooming) camera.zoomPosition ?: camera.position else camera.position
        val eye = attachments.point(eyeAnchor, eyeOffset) ?: if (coherentClientAim) {
            return chassisSafeOrNull(safeKey, vehicle, passenger, partialTicks)
        } else return null
        val direction = attachments.direction(directionAnchor, Vec3(0.0, 0.0, 1.0))
            ?: if (coherentClientAim) {
                return chassisSafeOrNull(safeKey, vehicle, passenger, partialTicks)
            } else return null
        val defaultMode = camera.cameraMode ?: if (camera.useAircraftCamera) {
            VehicleCameraMode.AIRCRAFT_FREELOOK
        } else {
            VehicleCameraMode.FIXED_ATTACHMENT
        }
        val resolved = VehicleSeatPoseSnapshot(
            seatIndex,
            selectedWeaponIndex,
            bodyAnchor,
            eyeAnchor,
            directionAnchor,
            body,
            eye,
            direction,
            defaultMode,
            camera.aimCameraMode ?: defaultMode,
        )
        if (key != null && authoritativeFrame != null && !authoritativeFrame.continuityReprojected) {
            remember(key, authoritativeFrame, vehicle.level().gameTime)
        }
        if (safeKey != null) rememberChassisSafe(safeKey, resolved, attachments)
        return resolved
    }

    private fun invalidateMismatched(slot: HoldSlot, current: CoherentKey, safeCurrent: SafeKey) {
        val held = heldBySlot[slot]
        if (held != null && held.key != current) heldBySlot.remove(slot)
        val safe = safeBySlot[slot]
        if (safe != null && safe.key != safeCurrent) safeBySlot.remove(slot)
    }

    private fun heldFrameOrNull(key: CoherentKey?, vehicle: VehicleEntity): VehicleAimPresentationFrame? {
        key ?: return null
        val slot = HoldSlot(key.passengerUuid, key.zooming)
        if (vehicle.isRemoved || vehicle.isWreck) {
            invalidatePassenger(key.passengerUuid)
            return null
        }
        val held = heldBySlot[slot] ?: return null
        val now = vehicle.level().gameTime
        if (held.key != key || now - held.capturedGameTime !in 0..HOLD_TICKS) {
            heldBySlot.remove(slot)
            return null
        }
        return held.authoritativeFrame
    }

    private fun remember(key: CoherentKey, frame: VehicleAimPresentationFrame, gameTime: Long) {
        heldBySlot[HoldSlot(key.passengerUuid, key.zooming)] = HeldPose(key, frame, gameTime)
        while (heldBySlot.size > MAX_HELD_POSES) {
            val iterator = heldBySlot.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun rememberChassisSafe(
        key: SafeKey,
        pose: VehicleSeatPoseSnapshot,
        attachments: VehicleAttachmentSnapshot,
    ) {
        val vehicle = attachments.transform("Vehicle") ?: return
        val safe = ChassisSafePose(
            key,
            pose,
            vehicle.worldToLocal(pose.bodyPosition),
            vehicle.worldToLocal(pose.eyePosition),
            pose.direction?.let(vehicle::worldDirectionToLocal),
        )
        safeBySlot[HoldSlot(key.passengerUuid, key.zooming)] = safe
        trimSafeCache()
    }

    private fun chassisSafeOrNull(
        key: SafeKey?,
        vehicle: VehicleEntity,
        passenger: Entity,
        partialTicks: Float,
    ): VehicleSeatPoseSnapshot? {
        key ?: return null
        if (vehicle.isRemoved || vehicle.isWreck) return null
        val safe = safeBySlot[HoldSlot(key.passengerUuid, key.zooming)]
            ?: return coldStartChassisSafe(key, vehicle, passenger, partialTicks)
        if (safe.key != key) return coldStartChassisSafe(key, vehicle, passenger, partialTicks)
        val currentVehicle = vehicle.resolveChassisCameraAttachmentSnapshot(passenger, partialTicks)
            ?.transform("Vehicle") ?: return null
        return VehicleSeatPoseSnapshot(
            safe.template.seatIndex,
            vehicle.getSelectedWeapon(key.seatIndex),
            safe.template.bodyAnchor,
            safe.template.eyeAnchor,
            safe.template.directionAnchor,
            currentVehicle.localToWorld(safe.bodyLocal),
            currentVehicle.localToWorld(safe.eyeLocal),
            safe.directionLocal?.let(currentVehicle::localDirectionToWorld),
            safe.template.defaultCameraMode,
            safe.template.aimCameraMode,
        )
    }

    private fun coldStartChassisSafe(
        key: SafeKey,
        vehicle: VehicleEntity,
        passenger: Entity,
        partialTicks: Float,
    ): VehicleSeatPoseSnapshot? {
        val seat = vehicle.computed().seats().getOrNull(key.seatIndex) ?: return null
        val camera = seat.cameraPos ?: return null
        val attachments = vehicle.resolveChassisCameraAttachmentSnapshot(passenger, partialTicks) ?: return null
        val vehicleTransform = attachments.transform("Vehicle") ?: return null
        val authoredEye = if (key.zooming) camera.zoomPosition ?: camera.position else camera.position
        // Cold start must not instantiate any aim-bearing graph at invented axes.
        // Use only authored chassis-local seat/camera values until one real frame seeds the exact
        // Vehicle-local camera tuple. Player look remains the temporary rotation authority.
        val safeEyeLocal = Vec3(
            seat.position.x + authoredEye.x,
            maxOf(seat.position.y + authoredEye.y, vehicle.bbHeight.toDouble() + COLD_START_CLEARANCE),
            seat.position.z + authoredEye.z,
        )
        val safeBodyLocal = safeEyeLocal.subtract(0.0, passenger.eyeHeight.toDouble(), 0.0)
        val body = vehicleTransform.localToWorld(safeBodyLocal)
        val eye = vehicleTransform.localToWorld(safeEyeLocal)
        val direction = Vec3.directionFromRotation(passenger.xRot, passenger.yRot)
        val defaultMode = camera.cameraMode ?: if (camera.useAircraftCamera) {
            VehicleCameraMode.AIRCRAFT_FREELOOK
        } else {
            VehicleCameraMode.FIXED_ATTACHMENT
        }
        val pose = VehicleSeatPoseSnapshot(
            key.seatIndex,
            vehicle.getSelectedWeapon(key.seatIndex),
            "Vehicle",
            "Vehicle",
            "Vehicle",
            body,
            eye,
            direction,
            defaultMode,
            camera.aimCameraMode ?: defaultMode,
        )
        rememberChassisSafe(key, pose, attachments)
        return pose
    }

    private fun trimSafeCache() {
        while (safeBySlot.size > MAX_HELD_POSES) {
            val iterator = safeBySlot.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    fun invalidatePassenger(passengerUuid: UUID) {
        heldBySlot.keys.removeIf { it.passengerUuid == passengerUuid }
        safeBySlot.keys.removeIf { it.passengerUuid == passengerUuid }
    }

    /** Aim epoch reset only: chassis-local safe camera placement remains context-valid. */
    fun invalidateAuthoritativePassenger(passengerUuid: UUID) {
        heldBySlot.keys.removeIf { it.passengerUuid == passengerUuid }
    }

    fun invalidateZoomPose(passengerUuid: UUID) {
        heldBySlot.keys.removeIf { it.passengerUuid == passengerUuid && it.zooming }
        safeBySlot.keys.removeIf { it.passengerUuid == passengerUuid && it.zooming }
    }

    fun invalidateVehicle(vehicleUuid: UUID) {
        heldBySlot.entries.removeIf { it.value.key.vehicleUuid == vehicleUuid }
        safeBySlot.entries.removeIf { it.value.key.vehicleUuid == vehicleUuid }
    }

    private const val MAX_HELD_POSES = 64
    private const val HOLD_TICKS = 3L
    private const val COLD_START_CLEARANCE = 0.25
}
