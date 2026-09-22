package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleMuzzleFrame
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import java.util.UUID

enum class NominalShotStatus {
    READY,
    IMPACT,
    WATER_SURFACE_IMPACT,
    NO_IMPACT,
    UNSUPPORTED,
    INVALID_CONTEXT,
    NON_FINITE,
    UNLOADED_TERRAIN,
    BUDGET_EXCEEDED,
}

/** Non-serialized, presentation-safe reason for a fail-closed nominal evaluation. */
enum class NominalShotDiagnostic {
    NONE,
    CLIENT_CONTEXT,
    CLIENT_SAMPLE_TIME_INVALID,
    SEAT_OR_WEAPON_CONTEXT,
    GUN_DATA_UNAVAILABLE,
    MUZZLE_FRAME_UNAVAILABLE,
    MUZZLE_FRAME_MISMATCH,
    ACTIVE_PROJECTILE_PERK,
    CLIENT_DESCRIPTOR_MISSING,
    CLIENT_DESCRIPTOR_DISABLED,
    CLIENT_DESCRIPTOR_INVALID_TYPE,
    CLIENT_DESCRIPTOR_INVALID_PROFILE,
    CLIENT_DESCRIPTOR_INVALID_LIFETIME,
    LIVE_PROJECTILE_BEHAVIOR_UNSUPPORTED,
    PROJECTILE_MODEL_UNREGISTERED,
    MUZZLE_IN_LIQUID,
    CLIENT_PLATFORM_MOTION_UNAVAILABLE,
    LAUNCH_STATE_NON_FINITE,
    OWNER_STATE_NON_FINITE,
    TRACE_LIQUID,
    FIRST_WATER_SURFACE_CONTACT,
    TRACE_RESULT_NON_FINITE,
    TRACE_CONTEXT_SENSITIVE,
    TRACE_UNLOADED,
    VEHICLE_CONTEXT_UNAVAILABLE,
    FIRST_VEHICLE_COLLISION,
    OWNER_DISTANCE_EXPIRED,
    NO_BLOCK_WITHIN_LIFETIME,
    PREDICTION_BUDGET_EXHAUSTED,
    PREDICTION_CANCELLED,
}

enum class NominalBlockCollisionModel {
    SBW_PROJECTILE,
    STANDARD_PROJECTILE,
}

enum class NominalMotionModel {
    DIRECT_LINEAR_GRAVITY,
    FAST_THROWABLE_LINEAR_GRAVITY_AIR,
}

data class NominalOwnerKinematics(
    val position: Vec3,
    val motionPerTick: Vec3,
    val maximumDistanceBlocks: Double,
    val uncertaintyMarginBlocks: Double,
)

data class NominalShotSnapshot(
    val vehicleUuid: UUID,
    val seatIndex: Int,
    val selectedWeaponIndex: Int,
    val weaponName: String,
    val projectileTypeId: ResourceLocation,
    val projectileProfileId: ResourceLocation?,
    val muzzle: VehicleMuzzleFrame,
    val launchSpeedBlocksPerTick: Double,
    val inheritedPlatformMotion: Vec3,
    val initialMotion: Vec3,
    val gravityPerTick: Double,
    val configuredLifetimeTicks: Int,
    val horizonTicks: Int,
    val supportedMaxRangeBlocks: Double,
    val collisionModel: NominalBlockCollisionModel,
    val motionModel: NominalMotionModel,
    val ownerKinematics: NominalOwnerKinematics?,
)

data class NominalShotCaptureResult(
    val status: NominalShotStatus,
    val snapshot: NominalShotSnapshot? = null,
    val diagnostic: NominalShotDiagnostic = NominalShotDiagnostic.NONE,
)

/** Client-facing name for the capture-only result; no collision work is implied. */
typealias NominalShotCapture = NominalShotCaptureResult

data class NominalShotResult(
    val status: NominalShotStatus,
    val point: Vec3? = null,
    val direction: Vec3? = null,
    val timeOfFlightTicks: Double? = null,
    val travelledDistanceBlocks: Double = 0.0,
    val steps: Int = 0,
    val diagnostic: NominalShotDiagnostic = NominalShotDiagnostic.NONE,
)
