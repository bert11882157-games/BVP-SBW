package com.atsuishio.superbwarfare.api.vehicle.aim

import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleAttachmentSnapshot
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleMuzzleFrame
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** One immutable, presentation-only actual-aim and chassis tuple for a rendered frame. */
data class VehicleAimPresentationFrame(
    val vehicleUuid: UUID,
    val presentationEpoch: Int,
    val channel: VehicleAimChannel,
    val seatIndex: Int,
    val selectedWeaponIndex: Int,
    val weaponName: String,
    val source: Source,
    val aim: VehicleAimPresentationSample,
    val sourceChassis: VehicleChassisPresentation,
    val presentedChassis: VehicleChassisPresentation,
    val presentedTurretYaw: Float,
    val presentedTurretPitch: Float,
    val presentedStationYaw: Float?,
    val presentedStationPitch: Float?,
    val authoritativeWorldDirection: Vec3,
    val attachments: VehicleAttachmentSnapshot,
    val muzzle: VehicleMuzzleFrame,
    /** Client tick of the last complete authoritative frame; continuity never refreshes it. */
    val capturedClientTick: Int,
    /** True only for a bounded same-epoch hold reprojected onto a newer presented chassis. */
    val continuityReprojected: Boolean = false,
) {
    enum class Source {
        AUTHORITATIVE_REMOTE,
        AUTHORITATIVE_LOCAL_REPROJECTED,
        /** Authoritative aim composed once with the vehicle's existing vanilla chassis presentation. */
        AUTHORITATIVE_LEGACY_CHASSIS,
    }
}
