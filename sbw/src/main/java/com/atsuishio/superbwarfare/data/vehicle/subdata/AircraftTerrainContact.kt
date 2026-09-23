package com.atsuishio.superbwarfare.data.vehicle.subdata

import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.phys.Vec3

/** Physical parts in vehicle-local blocks; projectile/damage OBBs remain independent. */
@Serializable
class AircraftTerrainContact {
    @SerialName("Fuselage") var fuselage: AircraftTerrainBox = AircraftTerrainBox()
    @SerialName("LandingGear") var landingGear: AircraftTerrainBox = AircraftTerrainBox()
    @SerialName("RetractableGear") var retractableGear: Boolean = false
    @SerialName("WheelContacts") var wheelContacts: List<AircraftWheelContact> = emptyList()
    @SerialName("BodyParts") var bodyParts: List<AircraftTerrainBox> = emptyList()

    fun valid(): Boolean = fuselage.valid() && landingGear.valid() &&
        bodyParts.size <= 16 && bodyParts.all { it.valid() } &&
        (wheelContacts.isEmpty() || validWheelContacts())
    fun bodyVolumes(): List<AircraftTerrainBox> = bodyParts.ifEmpty { listOf(fuselage) }
    fun hasWheelVolumes(): Boolean = validWheelContacts() && wheelContacts.all { it.bounds != null }
    fun gearDeployed(fraction: Float): Boolean = !retractableGear ||
        (fraction.isFinite() && fraction >= 0F && fraction <= 0.001F)

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
    @SerialName("Min") var minimum: SerializedVec3 = Vec3.ZERO
    @SerialName("Max") var maximum: SerializedVec3 = Vec3.ZERO

    fun valid(): Boolean = minimum.x.isFinite() && minimum.y.isFinite() && minimum.z.isFinite() &&
        maximum.x.isFinite() && maximum.y.isFinite() && maximum.z.isFinite() &&
        minimum.x < maximum.x && minimum.y < maximum.y && minimum.z < maximum.z
}
