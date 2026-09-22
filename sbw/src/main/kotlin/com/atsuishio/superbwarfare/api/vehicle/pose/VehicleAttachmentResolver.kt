package com.atsuishio.superbwarfare.api.vehicle.pose

import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleAgs30PitchFrame
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleRoofCoaxPitchFrame
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.util.Mth
import org.joml.Matrix4d
import kotlin.math.atan2
import kotlin.math.sqrt

/** Stateful per-vehicle resolver for immutable native and data-authored attachment frames. */
internal class VehicleAttachmentResolver {
    private var cachedSnapshot: VehicleAttachmentSnapshot? = null
    private var cachedTick = Int.MIN_VALUE
    private var cachedPartialBits = 0
    private var cachedPresentation: VehicleChassisPresentation? = null
    private var cachedDataOwner: DefaultVehicleData? = null
    private var cachedLegacyAttitudePath = false
    private var cachedXRotOBits = 0
    private var cachedXRotBits = 0
    private var cachedPrevRollBits = 0
    private var cachedRollBits = 0
    private var cachedTurretYRotOBits = 0
    private var cachedTurretYRotBits = 0
    private var cachedTurretXRotOBits = 0
    private var cachedTurretXRotBits = 0
    private var cachedGunYRotOBits = 0
    private var cachedGunYRotBits = 0
    private var cachedGunXRotOBits = 0
    private var cachedGunXRotBits = 0
    private var graphOwner: DefaultVehicleData? = null
    private var graph: VehicleAttachmentGraph? = null
    private var localTransforms: Map<String, Matrix4d> = emptyMap()
    private var cachedChassisSnapshot: VehicleAttachmentSnapshot? = null
    private var cachedChassisPresentation: VehicleChassisPresentation? = null
    private var cachedChassisDataOwner: DefaultVehicleData? = null
    private var cachedChassisRotateOffsetBits = 0L
    private var cachedChassisCustomPitchBits = 0

    fun resolve(vehicle: VehicleEntity, partialTicks: Float): VehicleAttachmentSnapshot {
        val presentation = vehicle.resolveChassisPresentation(partialTicks)
        val pose = presentation.pose
        val partialBits = partialTicks.toRawBits()
        // VehicleData replaces its cached DefaultVehicleData whenever configuration is invalidated,
        // so referential identity is the allocation-free configuration revision for this key.
        val data = vehicle.computed()
        val legacyAttitudePath =
            vehicle.flightStrategyOwnsAttitudeThisTick || vehicle.resolveVehiclePoseProvider() == null
        val xRotOBits = vehicle.xRotO.toRawBits()
        val xRotBits = vehicle.xRot.toRawBits()
        val prevRollBits = vehicle.prevRoll.toRawBits()
        val rollBits = vehicle.roll.toRawBits()
        val turretYRotOBits = vehicle.turretYRotO.toRawBits()
        val turretYRotBits = vehicle.turretYRot.toRawBits()
        val turretXRotOBits = vehicle.turretXRotO.toRawBits()
        val turretXRotBits = vehicle.turretXRot.toRawBits()
        val gunYRotOBits = vehicle.gunYRotO.toRawBits()
        val gunYRotBits = vehicle.gunYRot.toRawBits()
        val gunXRotOBits = vehicle.gunXRotO.toRawBits()
        val gunXRotBits = vehicle.gunXRot.toRawBits()
        cachedSnapshot?.let { cached ->
            if (cachedTick == vehicle.tickCount &&
                cachedPartialBits == partialBits &&
                cachedPresentation == presentation &&
                cachedDataOwner === data &&
                cachedLegacyAttitudePath == legacyAttitudePath &&
                cachedXRotOBits == xRotOBits &&
                cachedXRotBits == xRotBits &&
                cachedPrevRollBits == prevRollBits &&
                cachedRollBits == rollBits &&
                cachedTurretYRotOBits == turretYRotOBits &&
                cachedTurretYRotBits == turretYRotBits &&
                cachedTurretXRotOBits == turretXRotOBits &&
                cachedTurretXRotBits == turretXRotBits &&
                cachedGunYRotOBits == gunYRotOBits &&
                cachedGunYRotBits == gunYRotBits &&
                cachedGunXRotOBits == gunXRotOBits &&
                cachedGunXRotBits == gunXRotBits
            ) {
                return cached
            }
        }

        val matrices = nativeMatrices(vehicle, partialTicks)
        val base = VehicleAttachmentSnapshot.fromOwnedMatrices(pose.sequence, pose.serverTick, matrices)
        val resolved = resolveDataAttachments(base, data)
        cachedSnapshot = resolved
        cachedTick = vehicle.tickCount
        cachedPartialBits = partialBits
        cachedPresentation = presentation
        cachedDataOwner = data
        cachedLegacyAttitudePath = legacyAttitudePath
        cachedXRotOBits = xRotOBits
        cachedXRotBits = xRotBits
        cachedPrevRollBits = prevRollBits
        cachedRollBits = rollBits
        cachedTurretYRotOBits = turretYRotOBits
        cachedTurretYRotBits = turretYRotBits
        cachedTurretXRotOBits = turretXRotOBits
        cachedTurretXRotBits = turretXRotBits
        cachedGunYRotOBits = gunYRotOBits
        cachedGunYRotBits = gunYRotBits
        cachedGunXRotOBits = gunXRotOBits
        cachedGunXRotBits = gunXRotBits
        return resolved
    }

    /**
     * Builds a presentation-only attachment graph from one immutable chassis/actual-aim tuple.
     * No live entity axis or gameplay transform is read after the tuple enters this method.
     */
    fun resolveAimPresentation(
        vehicle: VehicleEntity,
        presentation: VehicleChassisPresentation,
        turretYaw: Float,
        turretPitch: Float,
        stationYaw: Float? = null,
        stationPitch: Float? = null,
    ): VehicleAttachmentSnapshot? {
        if (!turretYaw.isFinite() || !turretPitch.isFinite() ||
            stationYaw?.isFinite() == false || stationPitch?.isFinite() == false
        ) return null
        val pose = presentation.pose
        val anchor = presentation.anchor
        if (!anchor.x.isFinite() || !anchor.y.isFinite() || !anchor.z.isFinite() ||
            !presentation.chassisYawDegrees.isFinite()
        ) return null
        val data = vehicle.computed()

        val yawOffset = Matrix4d()
            .translation(anchor.x, anchor.y + vehicle.rotateOffsetHeight, anchor.z)
            .rotateY(Math.toRadians(-presentation.chassisYawDegrees.toDouble()))
        val yOffset = pose.applyBaseAttitude(Matrix4d(yawOffset))
        val vehicleTransform = Matrix4d(yOffset)
            .translate(0.0, -vehicle.rotateOffsetHeight, 0.0)
        pose.applyExtension(vehicleTransform, vehicle.rotateOffsetHeight)
        val customPitch = Matrix4d(yOffset)
            .translate(0.0, -vehicle.rotateOffsetHeight, 0.0)
            .rotateX(Math.toRadians(vehicle.turretCustomPitch.toDouble()))
        pose.applyExtension(customPitch, vehicle.rotateOffsetHeight)
        val flat = Matrix4d()
            .translation(anchor.x, anchor.y, anchor.z)
            .rotateY(Math.toRadians(-presentation.chassisYawDegrees.toDouble()))

        val matrices = LinkedHashMap<String, Matrix4d>()
        matrices["Default"] = Matrix4d(vehicleTransform)
        matrices["Vehicle"] = Matrix4d(vehicleTransform)
        matrices["VehicleCustomPitch"] = Matrix4d(customPitch)
        matrices["VehicleFlat"] = flat

        val turret = vehicle.turretPos?.let { position ->
            Matrix4d(customPitch)
                .translate(position.x, position.y, position.z)
                .rotateY(Math.toRadians(turretYaw.toDouble()))
        }
        if (turret != null) {
            matrices["Turret"] = Matrix4d(turret)
            vehicle.barrelPosition?.let { position ->
                matrices["Barrel"] = Matrix4d(turret)
                    .translate(position.x, position.y, position.z)
                    .rotateX(Math.toRadians(turretPitch.toDouble()))
            }
            roofCoaxPitchMatrix(data.roofCoaxPitch, turret, turretPitch)?.let { matrix ->
                matrices[VehicleRoofCoaxPitchFrame.NATIVE_FRAME] = matrix
            }
            ags30PitchMatrix(data.ags30Pitch, turret, turretPitch)?.let { matrix ->
                matrices[VehicleAgs30PitchFrame.NATIVE_FRAME] = matrix
            }
        }
        if (stationYaw != null && stationPitch != null && vehicle.hasPassengerWeaponStation()) {
            val stationParent = if (vehicle.isHullParentedPassengerWeaponStation()) {
                vehicleTransform
            } else {
                turret
            }
            if (stationParent != null) {
                vehicle.passengerWeaponStationPosition?.let { position ->
                    val stationYawOffset = if (vehicle.isHullParentedPassengerWeaponStation()) {
                        stationYaw
                    } else {
                        Mth.wrapDegrees(stationYaw - turretYaw)
                    }
                    val station = Matrix4d(stationParent)
                        .translate(position.x, position.y, position.z)
                        .rotateY(Math.toRadians(stationYawOffset.toDouble()))
                    matrices["WeaponStation"] = Matrix4d(station)
                    vehicle.passengerWeaponStationBarrelPosition?.let { barrelPosition ->
                        matrices["WeaponStationBarrel"] = Matrix4d(station)
                            .translate(barrelPosition.x, barrelPosition.y, barrelPosition.z)
                            .rotateX(Math.toRadians(stationPitch.toDouble()))
                    }
                }
            }
        }
        val base = VehicleAttachmentSnapshot.fromOwnedMatrices(
            pose.sequence,
            pose.serverTick,
            matrices,
        )
        return resolveDataAttachments(base, data)
    }

    /** Only the pre-turret frame is needed to reproject a world aim ray onto the rendered hull. */
    fun resolveAimBaseTransform(
        vehicle: VehicleEntity,
        presentation: VehicleChassisPresentation,
    ): VehicleTransformSnapshot? {
        val pose = presentation.pose
        val anchor = presentation.anchor
        if (!anchor.x.isFinite() || !anchor.y.isFinite() || !anchor.z.isFinite() ||
            !presentation.chassisYawDegrees.isFinite()
        ) return null
        val rotateOffset = vehicle.rotateOffsetHeight
        val yawOffset = Matrix4d()
            .translation(anchor.x, anchor.y + rotateOffset, anchor.z)
            .rotateY(Math.toRadians(-presentation.chassisYawDegrees.toDouble()))
        val customPitch = pose.applyBaseAttitude(yawOffset)
            .translate(0.0, -rotateOffset, 0.0)
            .rotateX(Math.toRadians(vehicle.turretCustomPitch.toDouble()))
        pose.applyExtension(customPitch, rotateOffset)
        return VehicleTransformSnapshot.fromOwnedMatrix(
            "VehicleCustomPitch",
            pose.sequence,
            pose.serverTick,
            customPitch,
        )
    }

    /**
     * Chassis-only fallback for a provider camera before a coherent aim frame exists. Aim-bearing
     * native parents are deliberately absent, so authored Turret/Barrel/WeaponStation descendants
     * cannot silently fall back to stale mutable client axes.
     */
    fun resolveChassisPresentation(
        vehicle: VehicleEntity,
        presentation: VehicleChassisPresentation,
    ): VehicleAttachmentSnapshot? {
        val pose = presentation.pose
        val anchor = presentation.anchor
        if (!anchor.x.isFinite() || !anchor.y.isFinite() || !anchor.z.isFinite() ||
            !presentation.chassisYawDegrees.isFinite()
        ) return null
        val data = vehicle.computed()
        val rotateOffset = vehicle.rotateOffsetHeight
        val customPitchDegrees = vehicle.turretCustomPitch
        cachedChassisSnapshot?.let { cached ->
            if (cachedChassisPresentation === presentation && cachedChassisDataOwner === data &&
                cachedChassisRotateOffsetBits == rotateOffset.toRawBits() &&
                cachedChassisCustomPitchBits == customPitchDegrees.toRawBits()
            ) return cached
        }
        val yawOffset = Matrix4d()
            .translation(anchor.x, anchor.y + rotateOffset, anchor.z)
            .rotateY(Math.toRadians(-presentation.chassisYawDegrees.toDouble()))
        val yOffset = pose.applyBaseAttitude(Matrix4d(yawOffset))
        val vehicleTransform = Matrix4d(yOffset)
            .translate(0.0, -rotateOffset, 0.0)
        pose.applyExtension(vehicleTransform, rotateOffset)
        val customPitch = Matrix4d(yOffset)
            .translate(0.0, -rotateOffset, 0.0)
            .rotateX(Math.toRadians(customPitchDegrees.toDouble()))
        pose.applyExtension(customPitch, rotateOffset)
        val matrices = linkedMapOf(
            "Default" to Matrix4d(vehicleTransform),
            "Vehicle" to Matrix4d(vehicleTransform),
            "VehicleCustomPitch" to Matrix4d(customPitch),
            "VehicleFlat" to Matrix4d()
                .translation(anchor.x, anchor.y, anchor.z)
                .rotateY(Math.toRadians(-presentation.chassisYawDegrees.toDouble())),
        )
        val resolved = resolveDataAttachments(
            VehicleAttachmentSnapshot.fromOwnedMatrices(pose.sequence, pose.serverTick, matrices),
            data,
        )
        cachedChassisSnapshot = resolved
        cachedChassisPresentation = presentation
        cachedChassisDataOwner = data
        cachedChassisRotateOffsetBits = rotateOffset.toRawBits()
        cachedChassisCustomPitchBits = customPitchDegrees.toRawBits()
        return resolved
    }

    fun invalidate() {
        cachedSnapshot = null
        cachedTick = Int.MIN_VALUE
        cachedPresentation = null
        cachedDataOwner = null
        cachedChassisSnapshot = null
        cachedChassisPresentation = null
        cachedChassisDataOwner = null
    }

    private fun nativeMatrices(vehicle: VehicleEntity, partialTicks: Float): LinkedHashMap<String, Matrix4d> {
        val data = vehicle.computed()
        val matrices = LinkedHashMap<String, Matrix4d>()
        val vehicleTransform = vehicle.getVehicleTransform(partialTicks)
        matrices["Default"] = vehicleTransform
        matrices["Vehicle"] = vehicleTransform
        matrices["VehicleCustomPitch"] = vehicle.getVehicleTransformWithCustomPitch(partialTicks)
        matrices["VehicleFlat"] = vehicle.getVehicleFlatTransform(partialTicks)
        if (vehicle.hasTurret()) {
            val turret = vehicle.getTurretTransform(partialTicks)
            matrices["Turret"] = turret
            if (vehicle.barrelPosition != null) {
                matrices["Barrel"] = vehicle.getBarrelTransform(partialTicks)
            }
            val actualPitch = Mth.lerp(partialTicks, vehicle.turretXRotO, vehicle.turretXRot)
            roofCoaxPitchMatrix(data.roofCoaxPitch, turret, actualPitch)?.let { matrix ->
                matrices[VehicleRoofCoaxPitchFrame.NATIVE_FRAME] = matrix
            }
            ags30PitchMatrix(data.ags30Pitch, turret, actualPitch)?.let { matrix ->
                matrices[VehicleAgs30PitchFrame.NATIVE_FRAME] = matrix
            }
        }
        if (vehicle.hasPassengerWeaponStation()) {
            matrices["WeaponStation"] = vehicle.getGunTransform(partialTicks)
            if (vehicle.passengerWeaponStationBarrelPosition != null) {
                matrices["WeaponStationBarrel"] = vehicle.getPassengerWeaponStationBarrelTransform(partialTicks)
            }
        }
        return matrices
    }

    /**
     * T(turret) · T(authored pivot) · Rx(actual pitch). The Turret matrix already contains
     * chassis pose and actual turret yaw, so this frame adds no second yaw or servo.
     */
    private fun roofCoaxPitchMatrix(
        frame: VehicleRoofCoaxPitchFrame?,
        turret: Matrix4d,
        actualPitch: Float,
    ): Matrix4d? {
        if (frame == null || !frame.hasTypedIdentity() || !actualPitch.isFinite()) return null
        val pivot = frame.pivot ?: return null
        return Matrix4d(turret)
            .translate(pivot.x, pivot.y, pivot.z)
            .rotateX(Math.toRadians(actualPitch.toDouble()))
    }

    /** T(turret) · T(authored AGS pivot) · Rx(actual selected-turret pitch). */
    private fun ags30PitchMatrix(
        frame: VehicleAgs30PitchFrame?,
        turret: Matrix4d,
        actualPitch: Float,
    ): Matrix4d? {
        if (frame == null || !frame.hasTypedIdentity() || !actualPitch.isFinite()) return null
        val pivot = frame.pivot ?: return null
        return Matrix4d(turret)
            .translate(pivot.x, pivot.y, pivot.z)
            .rotateX(Math.toRadians(actualPitch.toDouble()))
    }

    private fun resolveDataAttachments(
        base: VehicleAttachmentSnapshot,
        data: DefaultVehicleData,
    ): VehicleAttachmentSnapshot {
        if (data.attachments.isEmpty()) return base
        if (graphOwner !== data) {
            graphOwner = data
            val nodes = ArrayList<VehicleAttachmentNode>(data.attachments.size)
            val transforms = LinkedHashMap<String, Matrix4d>(data.attachments.size)
            for ((name, info) in data.attachments) {
                nodes.add(VehicleAttachmentNode(name, info.parent))
                val direction = info.direction.normalize()
                val horizontal = sqrt(direction.x * direction.x + direction.z * direction.z)
                transforms[name] = Matrix4d()
                    .translation(info.position.x, info.position.y, info.position.z)
                    .rotateY(atan2(direction.x, direction.z))
                    .rotateX(atan2(-direction.y, horizontal))
            }
            graph = VehicleAttachmentGraph(nodes, VehicleAttachmentGraph.NATIVE_BASE_FRAMES)
            localTransforms = transforms
        }
        return graph?.resolve(base, localTransforms) ?: base
    }
}
