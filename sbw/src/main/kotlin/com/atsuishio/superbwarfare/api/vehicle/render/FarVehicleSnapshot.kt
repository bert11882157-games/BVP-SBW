package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderPartSnapshot
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.abs

/** Render-only state. Positions are world blocks; rotations retain the vehicle renderer's units. */
@Serializable
data class FarVehicleSnapshot(
    val id: Int,
    val uuid: String,
    val type: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val age: Int,
    val pose: String,
    val parts: VehicleRenderPartSnapshot,
    val runningGear: RunningGearRenderState,
    val power: Float,
    val targetSpeed: Double,
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double,
    val propeller: Float,
    val gear: Float,
    val flaps: List<Float>,
    val selectedWeapons: List<Int>,
    val health: Float,
    val wreck: Boolean,
    val turretEjected: Boolean,
    val turretBurned: Boolean,
    val occupied: Boolean,
    val overrideData: String,
    val visualData: Map<String, String>,
    val aircraftWreckStart: Long = -1L,
    val aircraftWreckWings: Int = -1,
    val aircraftWreckMotionX: Float = 0F,
    val aircraftWreckMotionY: Float = 0F,
    val aircraftWreckMotionZ: Float = 0F,

) {
    fun sameIdentity(other: FarVehicleSnapshot): Boolean =
        id == other.id && uuid == other.uuid && type == other.type && overrideData == other.overrideData

    fun distanceSquared(px: Double, py: Double, pz: Double): Double =
        (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz)

    fun valid(): Boolean {
        if (id < 0 || age < 0 || aircraftWreckStart < -1L || uuid.length != 36 || !RESOURCE_ID.matches(type)) return false
        if (aircraftWreckWings !in -1..3) return false
        if (!listOf(aircraftWreckMotionX, aircraftWreckMotionY, aircraftWreckMotionZ).all { it.isFinite() && abs(it) < 100F }) return false
        if (runCatching { UUID.fromString(uuid).toString() }.getOrNull() != uuid) return false
        if (!listOf(x, y, z).all { it.isFinite() && abs(it) <= 30_000_000.0 }) return false
        if (!listOf(motionX, motionY, motionZ, targetSpeed).all { it.isFinite() && abs(it) <= 100_000 }) return false
        if (!listOf(yaw, pitch, roll, power, propeller, gear, health).all(Float::isFinite)) return false
        if (selectedWeapons.size > 16 || selectedWeapons.any { it !in -1..1024 }) return false
        if (flaps.size != 7 || flaps.any { !it.isFinite() }) return false
        if (pose.length > 1024 || VehiclePoseSnapshot.decode(pose) == null) return false
        if (visualData.size > 8 || visualData.keys.any { !VISUAL_KEY.matches(it) }) return false
        if (overrideData.length > 8192 || visualData.values.any { it.length > 8192 }) return false
        if (visualData.containsKey(FarFixedWingVisualState.VISUAL_KEY) &&
            FarFixedWingVisualState.decode(visualData[FarFixedWingVisualState.VISUAL_KEY]) == null) return false
        val textBytes = overrideData.toByteArray(Charsets.UTF_8).size +
            visualData.entries.sumOf { it.key.toByteArray().size + it.value.toByteArray().size }
        if (textBytes > 8192) return false
        val p = parts
        if (!listOf(p.interpolatedHullYawDegrees, p.hullPitchDegrees, p.hullRollDegrees,
                p.turretWorldYawDegrees, p.turretPitchDegrees, p.turretYawFromRenderedHullDegrees,
                p.barrelPitchDegrees, p.stationYawRelativeToTurretDegrees, p.stationYawFromRenderedHullDegrees,
                p.stationPitchDegrees, p.stationPitchRadians, p.recoilShake, p.cannonRecoilForce)
                .all(Float::isFinite) || p.ags30PitchDegrees?.isFinite() == false) return false
        if (p.cannonRecoilTime !in 0..100_000) return false
        val g = runningGear
        return listOf(g.leftWheelRotation, g.rightWheelRotation, g.leftTrackPhase,
            g.rightTrackPhase, g.rudderRotation).all(Float::isFinite) && g.trackAnimationLength in 1..100_000
    }

    companion object {
        private val RESOURCE_ID = Regex("[a-z0-9_.-]{1,64}:[a-z0-9/._-]{1,192}")
        private val VISUAL_KEY = Regex("[a-zA-Z0-9_.-]{1,64}")
    }
}

/** Addon-owned cosmetic fields. Implementations must not read or write inventory/combat state. */
interface FarVehicleVisualExtension {
    fun captureFarRenderVisuals(): Map<String, String>
    /** Called only on an unregistered client render copy with validated, bounded fields. */
    fun applyFarRenderVisuals(values: Map<String, String>)
}
