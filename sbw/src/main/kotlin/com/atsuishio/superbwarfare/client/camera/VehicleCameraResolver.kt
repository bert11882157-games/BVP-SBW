package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMode
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimOpticalCameraPolicy
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimPresentationFrame
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimReticleProfileProvider
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimReticleRole
import com.atsuishio.superbwarfare.api.vehicle.camera.VehicleCameraMode
import com.atsuishio.superbwarfare.api.vehicle.camera.VehicleSeatPoseSnapshot
import com.atsuishio.superbwarfare.api.vehicle.camera.VehicleSeatPoseResolver
import com.atsuishio.superbwarfare.api.vehicle.camera.controlsPlayerLookAim
import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils.getXRotFromVector
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils.getYRotFromVector
import com.atsuishio.superbwarfare.event.ClientMouseHandler
import com.atsuishio.superbwarfare.tools.maxZoom
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import java.util.LinkedHashMap
import java.util.UUID

internal data class VehicleCameraRequest(
    val vehicle: VehicleEntity,
    val player: Player,
    val partialTicks: Float,
    val zoom: Boolean,
    val firstPerson: Boolean,
)

internal object VehicleCameraResolver {
    private data class CameraSessionKey(
        val level: Level,
        val vehicleUuid: UUID,
        val controllerUuid: UUID,
        val firstPerson: Boolean,
        val zooming: Boolean,
        val opticalPolicy: VehicleAimOpticalCameraPolicy,
        val presentationEpoch: Int,
    )

    private data class WeaponViewKey(
        val level: Level,
        val vehicleUuid: UUID,
        val controllerUuid: UUID,
        val seatIndex: Int,
        val selectedWeaponIndex: Int,
        val zooming: Boolean,
        val presentationEpoch: Int,
        val positionAttachment: String,
        val directionAttachment: String,
    )

    private data class WeaponViewPose(val position: Vec3, val rotation: Vec2)
    private data class HeldWeaponView(
        val key: WeaponViewKey,
        val authoritativeFrame: VehicleAimPresentationFrame,
        val capturedGameTime: Long,
    )
    private data class SeatPoseKey(
        val level: Level,
        val vehicleUuid: UUID,
        val controllerUuid: UUID,
        val seatIndex: Int,
        val selectedWeaponIndex: Int,
        val zooming: Boolean,
        val presentationEpoch: Int,
        val gameTime: Long,
        val partialBits: Int,
    )
    private data class CachedSeatPose(val key: SeatPoseKey, val pose: VehicleSeatPoseSnapshot?)

    private val heldWeaponViews = LinkedHashMap<UUID, HeldWeaponView>(32, 0.75f, true)
    private val cachedSeatPoses = LinkedHashMap<UUID, CachedSeatPose>(32, 0.75f, true)
    private val cameraSessions = LinkedHashMap<UUID, CameraSessionKey>(32, 0.75f, true)
    private val freeCameraSessions = LinkedHashMap<UUID, Boolean>(32, 0.75f, true)

    private class ThirdPersonFlightView(
        val vehicle: VehicleEntity,
        val level: Level,
        val seat: Int,
        val view: CameraType,
        var tick: Int,
        val angles: FixedWingThirdPersonAngles = FixedWingThirdPersonAngles(),
    )
    private val thirdPersonFlightViews = LinkedHashMap<UUID, ThirdPersonFlightView>(32, 0.75f, true)

    fun rotation(request: VehicleCameraRequest): Vec2? = with(request) {
        val thirdPersonFlight = thirdPersonFlightAngles(request)
        AircraftArmamentClient.cameraRotation(vehicle, partialTicks)?.let { return it }
        val seatIndex = vehicle.getSeatIndex(player)
        val selectedWeaponIndex = if (seatIndex >= 0) vehicle.getPrimaryWeaponIndex(seatIndex) else -1
        val reticleRole = (vehicle as? VehicleAimReticleProfileProvider)
            ?.getVehicleAimReticleRole(seatIndex, selectedWeaponIndex)
            ?: VehicleAimReticleRole.OTHER
        trackCameraSession(request, reticleRole.opticalCameraPolicy)
        val weaponAttachmentZoom = zoom && firstPerson &&
                reticleRole.opticalCameraPolicy == VehicleAimOpticalCameraPolicy.WEAPON_ATTACHMENT
        val seatZoom = zoom && firstPerson &&
                reticleRole.opticalCameraPolicy != VehicleAimOpticalCameraPolicy.FOV_ONLY
        val aimMode = if (seatIndex >= 0) {
            vehicle.getVehicleAimPresentationMode(seatIndex, selectedWeaponIndex)
        } else {
            VehicleAimMode.INACTIVE
        }
        // Fixed-wing pilot views use the interpolated body plus render-owned target chase. This guard
        // precedes generic armored aim-profile metadata, so a jet's gun profile cannot
        // silently turn the cockpit into a tank/player-look camera.  It does not alter the
        // authored eye/direction attachments or any Crosshair/weapon direction.
        if (firstPerson && vehicle.isFixedWingFlightVehicle() &&
            !vehicle.isPassengerStationLocalAimController(player)) {
            // The authored eye and rendered hull use this wrapped, frame-latched yaw already.
            // Linear interpolation of the raw endpoints would turn through zero at +/-180.
            return aircraftRotation(vehicle, partialTicks, vehicle.getResolvedChassisYaw(partialTicks))
        }
        if (firstPerson && vehicle.controlsPlayerLookAim(player, seatIndex, selectedWeaponIndex, aimMode)) {
            return Vec2(player.yRot, player.xRot)
        }

        val pose = if (seatIndex >= 0 && firstPerson) {
            resolveSeatPose(vehicle, player, partialTicks, seatIndex, selectedWeaponIndex, seatZoom)
        } else {
            null
        }
        val poseCameraMode = pose?.cameraMode(aimMode)
        if (firstPerson && poseCameraMode == VehicleCameraMode.PLAYER_LOOK_AIM) {
            return Vec2(player.yRot, player.xRot)
        }

        val gunData = if (seatIndex >= 0) vehicle.getGunData(player) else null
        if (weaponAttachmentZoom && gunData != null) {
            val view = gunData.get(GunProp.SHOOT_POS)
            if (!view.viewDirectionAttachment.isNullOrBlank() || view.viewDirection != null) {
                if (usesCoherentCameraAttachments(vehicle, player, firstPerson)) {
                    return resolveWeaponViewPose(vehicle, player, partialTicks, seatIndex,
                        selectedWeaponIndex, view.viewAttachment, view.viewDirectionAttachment)?.rotation
                }
                rotationFromDirection(vehicle.getViewVec(player, partialTicks))?.let { return it }
            }
        }
        if (pose != null && poseCameraMode != null &&
            (firstPerson || poseCameraMode != VehicleCameraMode.PLAYER_LOOK_AIM)
        ) {
            return when (poseCameraMode) {
                VehicleCameraMode.PLAYER_LOOK_AIM -> Vec2(player.yRot, player.xRot)
                VehicleCameraMode.AIRCRAFT_FREELOOK -> aircraftRotation(vehicle, partialTicks)
                VehicleCameraMode.FIXED_ATTACHMENT -> rotationFromDirection(pose.direction)
            }
        }

        vehicle.computed().seats().getOrNull(seatIndex)?.cameraPos ?: return null
        if (vehicle.useAircraftCamera(seatIndex)) {
            return if (thirdPersonFlight != null) aircraftRotation(vehicle, partialTicks,
                thirdPersonFlight.yaw(), thirdPersonFlight.pitch())
            else aircraftRotation(vehicle, partialTicks)
        }
        if (!weaponAttachmentZoom && !firstPerson) return null
        if (usesCoherentCameraAttachments(vehicle, player, firstPerson)) return null
        val direction = vehicle.cameraDirection(player, partialTicks)
        return Vec2(
            -getYRotFromVector(direction).toFloat(),
            -getXRotFromVector(direction).toFloat(),
        )
    }

    fun position(request: VehicleCameraRequest): Vec3? = with(request) {
        val thirdPersonFlight = thirdPersonFlightAngles(request)
        AircraftArmamentClient.cameraPosition(vehicle, partialTicks)?.let { return it }
        val seatIndex = vehicle.getSeatIndex(player)
        val selectedWeaponIndex = if (seatIndex >= 0) vehicle.getPrimaryWeaponIndex(seatIndex) else -1
        val reticleRole = (vehicle as? VehicleAimReticleProfileProvider)
            ?.getVehicleAimReticleRole(seatIndex, selectedWeaponIndex)
            ?: VehicleAimReticleRole.OTHER
        trackCameraSession(request, reticleRole.opticalCameraPolicy)
        val weaponAttachmentZoom = zoom && firstPerson &&
                reticleRole.opticalCameraPolicy == VehicleAimOpticalCameraPolicy.WEAPON_ATTACHMENT
        val seatZoom = zoom && firstPerson &&
                reticleRole.opticalCameraPolicy != VehicleAimOpticalCameraPolicy.FOV_ONLY
        val gunData = if (seatIndex >= 0) vehicle.getGunData(player) else null
        if (weaponAttachmentZoom && gunData != null) {
            val view = gunData.get(GunProp.SHOOT_POS)
            if (!view.viewAttachment.isNullOrBlank() || view.viewPosition != null) {
                if (usesCoherentCameraAttachments(vehicle, player, firstPerson)) {
                    return resolveWeaponViewPose(vehicle, player, partialTicks, seatIndex,
                        selectedWeaponIndex, view.viewAttachment, view.viewDirectionAttachment)?.position
                }
                vehicle.getViewPos(player, partialTicks)?.let { return it }
            }
        }
        if (seatIndex >= 0 && firstPerson) {
            resolveSeatPose(vehicle, player, partialTicks, seatIndex, selectedWeaponIndex, seatZoom)
                ?.let { return it.eyePosition }
        }
        val data = vehicle.computed().seats().getOrNull(seatIndex)?.cameraPos ?: return null
        if (firstPerson) {
            if (usesCoherentCameraAttachments(vehicle, player, firstPerson)) return null
            return if (seatZoom) {
                if (gunData != null && gunData.get(GunProp.SHOOT_POS).viewPosition != null) {
                    if (weaponAttachmentZoom) vehicle.getViewPos(player, partialTicks)
                    else vehicle.getZoomPos(player, partialTicks)
                } else {
                    vehicle.getZoomPos(player, partialTicks)
                }
            } else {
                vehicle.getCameraPos(player, partialTicks)
            }
        }
        if (!vehicle.useAircraftCamera(seatIndex)) return null

        val transform = vehicle.getClientVehicleTransform(partialTicks)
        thirdPersonFlight?.applyOrbitRotation(transform,
            ClientMouseHandler.freeCameraYaw, ClientMouseHandler.freeCameraPitch)
        val maxCameraPosition = vehicle.transformPosition(
            transform,
            data.aircraftCameraPos.x,
            data.aircraftCameraPos.y + 0.1 * ClientMouseHandler.custom3pDistanceLerp,
            data.aircraftCameraPos.z - ClientMouseHandler.custom3pDistanceLerp,
        )
        if (vehicle.isFixedWingFlightVehicle()) {
            val offset = FixedWingDynamicCamera.offset(vehicle, partialTicks)
            val pitchOffset = FixedWingCameraOrbit.pitchOffsetForBranch(vehicle.getResolvedChassisYaw(partialTicks),
                thirdPersonFlight?.yaw() ?: vehicle.getResolvedChassisYaw(partialTicks), offset.y)
            if (!FixedWingCameraOrbit.apply(maxCameraPosition,
                    transform.m30(), transform.m31(), transform.m32(),
                    ((thirdPersonFlight?.yaw() ?: vehicle.getResolvedChassisYaw(partialTicks)) -
                        ClientMouseHandler.freeCameraYaw).toFloat(),
                    offset.x, pitchOffset)) return null
        }
        // Collision remains the same aircraft-pivot-to-camera trace, after the external orbit.
        return maxCameraPosition.maxZoom(transform)
    }

    private fun aircraftRotation(
        vehicle: VehicleEntity,
        partialTicks: Float,
        bodyYaw: Float = if (vehicle.isFixedWingFlightVehicle()) vehicle.getResolvedChassisYaw(partialTicks)
            else vehicle.getYaw(partialTicks),
        bodyPitch: Float = vehicle.getPitch(partialTicks),
    ): Vec2 {
        val baseYaw = bodyYaw - ClientMouseHandler.freeCameraYaw
        val basePitch = bodyPitch + ClientMouseHandler.freeCameraPitch
        val offset = FixedWingDynamicCamera.offset(vehicle, partialTicks)
        val pitchOffset = FixedWingCameraOrbit.pitchOffsetForBranch(vehicle.getResolvedChassisYaw(partialTicks),
            bodyYaw, offset.y)
        return Vec2(
            (baseYaw + offset.x).toFloat(),
            (basePitch + pitchOffset).toFloat(),
        )
    }

    /** One bounded continuity owner shared by rotation and orbit, never by flight/aim authority. */
    private fun thirdPersonFlightAngles(request: VehicleCameraRequest): FixedWingThirdPersonAngles? = with(request) {
        val seat = vehicle.getSeatIndex(player)
        if (firstPerson || !partialTicks.isFinite() || !vehicle.isFixedWingFlightVehicle() ||
            player.vehicle !== vehicle || vehicle.isRemoved || vehicle.isWreck ||
            seat < 0 || !vehicle.useAircraftCamera(seat) ||
            vehicle.isPassengerStationLocalAimController(player) || AircraftArmamentClient.isPodActive(vehicle)) {
            thirdPersonFlightViews.remove(player.uuid)
            return null
        }
        val type = Minecraft.getInstance().options.cameraType
        var cached = thirdPersonFlightViews[player.uuid]
        if (cached == null || cached.vehicle !== vehicle || cached.level !== vehicle.level() ||
            cached.seat != seat || cached.view != type || vehicle.tickCount < cached.tick) {
            cached = ThirdPersonFlightView(vehicle, vehicle.level(), seat, type, vehicle.tickCount)
            thirdPersonFlightViews[player.uuid] = cached
            while (thirdPersonFlightViews.size > MAX_CAMERA_SESSIONS) {
                val iterator = thirdPersonFlightViews.entries.iterator()
                iterator.next()
                iterator.remove()
            }
        }
        cached.tick = vehicle.tickCount
        if (!cached.angles.update(vehicle.getResolvedChassisYaw(partialTicks), vehicle.getPitch(partialTicks))) {
            thirdPersonFlightViews.remove(player.uuid)
            return null
        }
        // Keep observing the moving body during held position queries. The held world-view
        // override remains sole presentation owner; release resumes this current branch.
        cached.angles.presentation(VehicleFreeCameraController.hasPresentation(player, vehicle))
    }

    private fun rotationFromDirection(direction: Vec3?): Vec2? = direction
        ?.takeIf { it.lengthSqr() > 1.0E-8 }
        ?.let {
            Vec2(
                -getYRotFromVector(it).toFloat(),
                -getXRotFromVector(it).toFloat(),
            )
        }

    private fun usesCoherentCameraAttachments(
        vehicle: VehicleEntity,
        player: Player,
        firstPerson: Boolean,
    ): Boolean {
        val seat = vehicle.getSeatIndex(player)
        val weapon = if (seat >= 0) vehicle.getPrimaryWeaponIndex(seat) else -1
        return firstPerson && vehicle.level().isClientSide &&
                (vehicle.resolveVehicleFlightStrategy() == null ||
                    vehicle.isPassengerStationLocalAimController(player)) && seat >= 0 && weapon >= 0 &&
                vehicle.resolveVehicleAimProfile(seat, weapon) != null
    }

    private fun resolveWeaponViewPose(
        vehicle: VehicleEntity,
        player: Player,
        partialTicks: Float,
        seatIndex: Int,
        selectedWeaponIndex: Int,
        positionAttachment: String?,
        directionAttachment: String?,
    ): WeaponViewPose? {
        val positionName = positionAttachment?.takeIf { it.isNotBlank() } ?: return null
        val directionName = directionAttachment?.takeIf { it.isNotBlank() } ?: return null
        val key = WeaponViewKey(
            vehicle.level(), vehicle.uuid, player.uuid, seatIndex, selectedWeaponIndex, true,
            vehicle.getAimPresentationEpoch(), positionName, directionName,
        )
        val existing = heldWeaponViews[player.uuid]
        if (existing != null && existing.key != key) heldWeaponViews.remove(player.uuid)

        val authoritativeFrame = vehicle.resolveAimPresentationFrame(player, partialTicks)
            ?.takeIf { it.presentationEpoch == key.presentationEpoch }
        val frame = authoritativeFrame ?: run {
            val held = heldWeaponViews[player.uuid] ?: return null
            val age = vehicle.level().gameTime - held.capturedGameTime
            if (held.key != key || age !in 0..WEAPON_VIEW_HOLD_TICKS || vehicle.isRemoved || vehicle.isWreck) {
                heldWeaponViews.remove(player.uuid)
                return null
            }
            vehicle.reprojectAimPresentationContinuity(held.authoritativeFrame, partialTicks)
                ?: return null
        }
        val position = frame.attachments.point(positionName, Vec3.ZERO)
        val rotation = rotationFromDirection(
            frame.attachments.direction(directionName, Vec3(0.0, 0.0, 1.0)),
        )
        if (position == null || rotation == null || !position.x.isFinite() ||
            !position.y.isFinite() || !position.z.isFinite()
        ) return null
        val pose = WeaponViewPose(position, rotation)
        if (authoritativeFrame != null && !authoritativeFrame.continuityReprojected) {
            heldWeaponViews[player.uuid] = HeldWeaponView(key, authoritativeFrame, vehicle.level().gameTime)
            trimWeaponViewCache()
        }
        return pose
    }

    fun invalidateController(controllerUuid: UUID) {
        clearCameraHolds(controllerUuid)
        freeCameraSessions.remove(controllerUuid)
    }

    fun invalidateVehicle(vehicleUuid: UUID) {
        thirdPersonFlightViews.entries.removeIf { it.value.vehicle.uuid == vehicleUuid }
        heldWeaponViews.entries.removeIf { it.value.key.vehicleUuid == vehicleUuid }
        cachedSeatPoses.entries.removeIf { it.value.key.vehicleUuid == vehicleUuid }
        cameraSessions.entries.removeIf { it.value.vehicleUuid == vehicleUuid }
    }

    @JvmStatic
    fun updateFreeCameraSession(controllerUuid: UUID, active: Boolean) {
        val previous = freeCameraSessions[controllerUuid]
        if (previous == null && !active) return
        freeCameraSessions[controllerUuid] = active
        if (previous == null || previous != active) clearCameraHolds(controllerUuid, preserveFlightView = true)
        trimBooleanSessionMap(freeCameraSessions)
    }

    private fun clearCameraHolds(controllerUuid: UUID, preserveFlightView: Boolean = false) {
        if (!preserveFlightView) thirdPersonFlightViews.remove(controllerUuid)
        heldWeaponViews.remove(controllerUuid)
        cachedSeatPoses.remove(controllerUuid)
        cameraSessions.remove(controllerUuid)
        VehicleSeatPoseResolver.invalidatePassenger(controllerUuid)
    }

    private fun resolveSeatPose(
        vehicle: VehicleEntity,
        player: Player,
        partialTicks: Float,
        seatIndex: Int,
        selectedWeaponIndex: Int,
        zooming: Boolean,
    ): VehicleSeatPoseSnapshot? {
        val key = SeatPoseKey(
            vehicle.level(), vehicle.uuid, player.uuid, seatIndex, selectedWeaponIndex, zooming,
            vehicle.getAimPresentationEpoch(), vehicle.level().gameTime, partialTicks.toRawBits(),
        )
        cachedSeatPoses[player.uuid]?.takeIf { it.key == key }?.let { return it.pose }
        val pose = vehicle.resolveVehicleSeatPose(player, partialTicks, zooming)
        cachedSeatPoses[player.uuid] = CachedSeatPose(key, pose)
        while (cachedSeatPoses.size > MAX_CAMERA_SESSIONS) {
            val iterator = cachedSeatPoses.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        return pose
    }

    private fun trimBooleanSessionMap(map: LinkedHashMap<UUID, Boolean>) {
        while (map.size > MAX_CAMERA_SESSIONS) {
            val iterator = map.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun trackCameraSession(
        request: VehicleCameraRequest,
        opticalPolicy: VehicleAimOpticalCameraPolicy,
    ) = with(request) {
        val current = CameraSessionKey(
            vehicle.level(), vehicle.uuid, player.uuid, firstPerson, zoom,
            opticalPolicy, vehicle.getAimPresentationEpoch(),
        )
        val previous = cameraSessions[player.uuid]
        if (previous != null && previous != current) {
            heldWeaponViews.remove(player.uuid)
            val epochOnly = previous.copy(presentationEpoch = current.presentationEpoch) == current
            if (epochOnly) VehicleSeatPoseResolver.invalidateAuthoritativePassenger(player.uuid)
            else VehicleSeatPoseResolver.invalidatePassenger(player.uuid)
        }
        cameraSessions[player.uuid] = current
        while (cameraSessions.size > MAX_CAMERA_SESSIONS) {
            val iterator = cameraSessions.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun trimWeaponViewCache() {
        while (heldWeaponViews.size > MAX_HELD_WEAPON_VIEWS) {
            val iterator = heldWeaponViews.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private const val MAX_HELD_WEAPON_VIEWS = 64
    private const val MAX_CAMERA_SESSIONS = 64
    private const val WEAPON_VIEW_HOLD_TICKS = 3L
}
