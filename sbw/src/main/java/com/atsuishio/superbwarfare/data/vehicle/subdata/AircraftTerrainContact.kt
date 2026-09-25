package com.atsuishio.superbwarfare.data.vehicle.subdata

import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2

private const val GEAR_PRESENT_FRACTION = 0.25F
private const val TAIL_CLEARANCE_MARGIN_DEGREES = 1.5
private const val MIN_GROUND_PITCH_DEGREES = 4.0
private const val MAX_GROUND_PITCH_DEGREES = 18.0

/** Physical parts in vehicle-local blocks; projectile/damage OBBs remain independent. */
@Serializable
class AircraftTerrainContact {
    @SerialName("Fuselage") var fuselage: AircraftTerrainBox = AircraftTerrainBox()
    @SerialName("LandingGear") var landingGear: AircraftTerrainBox = AircraftTerrainBox()
    @SerialName("RetractableGear") var retractableGear: Boolean = false
    @SerialName("WheelContacts") var wheelContacts: List<AircraftWheelContact> = emptyList()
    @SerialName("BodyParts") var bodyParts: List<AircraftTerrainBox> = emptyList()
    @SerialName("WreckSections") var wreckSections: List<AircraftTerrainBox> = emptyList()

    fun valid(): Boolean = fuselage.valid() && landingGear.valid() &&
        bodyParts.size <= 16 && bodyParts.all { it.valid() } &&
        (wreckSections.isEmpty() || wreckSections.size == 4 && wreckSections.all { it.valid() }) &&
        (wheelContacts.isEmpty() || validWheelContacts())
    fun bodyVolumes(): List<AircraftTerrainBox> = bodyParts.ifEmpty { listOf(fuselage) }
    fun hasWheelVolumes(): Boolean = validWheelContacts() && wheelContacts.all { it.bounds != null }
    /** Retractable gear carries loads once it is mostly down, so a late extension still lands. */
    fun gearDeployed(fraction: Float): Boolean = !retractableGear ||
        (fraction.isFinite() && fraction >= 0F && fraction <= GEAR_PRESENT_FRACTION)

    /**
     * Nose-up attitude, in degrees, at which a hull part behind the main tyres reaches a level
     * runway while the aircraft pivots on the rear-bottom edge of its main tyres; null without
     * main wheels or a hull part behind them.
     */
    fun tailClearanceDegrees(): Double? {
        val mains = wheelContacts.filter { it.group == AircraftWheelContactGroup.MAIN }
        if (mains.isEmpty()) return null
        val pivotY = mains.minOf { it.bounds?.minimum?.y ?: it.position.y }
        val pivotZ = mains.minOf { it.bounds?.minimum?.z ?: it.position.z }
        return bodyVolumes().filter { it.minimum.z < pivotZ - 1e-6 }
            .minOfOrNull { Math.toDegrees(atan2(it.minimum.y - pivotY, pivotZ - it.minimum.z)) }
    }

    /**
     * Ground rotation limit that keeps the tail clear with a margin. A floor keeps a takeoff
     * attitude available even where low stores leave little clearance.
     */
    fun groundPitchLimitDegrees(): Double = tailClearanceDegrees()
        ?.let { (it - TAIL_CLEARANCE_MARGIN_DEGREES).coerceIn(MIN_GROUND_PITCH_DEGREES, MAX_GROUND_PITCH_DEGREES) }
        ?: MAX_GROUND_PITCH_DEGREES

    fun validWheelContacts(): Boolean = wheelContacts.isNotEmpty() && wheelContacts.size <= 32 &&
        wheelContacts.all { it.valid() } && wheelContacts.map { it.id }.distinct().size == wheelContacts.size &&
        (wheelContacts.none { it.bounds != null } || wheelContacts.all { it.bounds != null }) &&
        wheelContacts.any { it.group == AircraftWheelContactGroup.MAIN }
}

@Serializable
class AircraftWheelContact {
    @SerialName("Id") var id: String = ""
    @SerialName("Group") var group: AircraftWheelContactGroup = AircraftWheelContactGroup.MAIN
    @SerialName("Position") var position: SerializedVec3 = Vec3.ZERO
    @SerialName("Bounds") var bounds: AircraftTerrainBox? = null

    fun valid(): Boolean = id.isNotBlank() && id.length <= 96 &&
        position.x.isFinite() && position.y.isFinite() && position.z.isFinite() &&
        (bounds?.valid() != false)
}

@Serializable
class AircraftTerrainBox {
    @SerialName("Bone") var bone: String = "hull"
    @SerialName("Min") var minimum: SerializedVec3 = Vec3.ZERO
    @SerialName("Max") var maximum: SerializedVec3 = Vec3.ZERO

    fun valid(): Boolean = bone.isNotBlank() && bone.length <= 96 &&
        minimum.x.isFinite() && minimum.y.isFinite() && minimum.z.isFinite() &&
        maximum.x.isFinite() && maximum.y.isFinite() && maximum.z.isFinite() &&
        minimum.x < maximum.x && minimum.y < maximum.y && minimum.z < maximum.z
}
