package com.atsuishio.superbwarfare.data.vehicle.subdata

import com.atsuishio.superbwarfare.serialization.kserializer.Vec3Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.phys.Vec3

/** Optional aircraft equipment. Positions are hull-local blocks; rates count individual flares. */
@Serializable
data class AircraftCountermeasureDefinition(
    @SerialName("Flares") val flares: Boolean = false,
    @SerialName("Chaff") val chaff: Boolean = false,
    @SerialName("RadarWarningReceiver") val radarWarningReceiver: Boolean = true,
    @SerialName("FlaresPerSecond") val flaresPerSecond: Int = 4,
    @SerialName("FlaresPerBurst") val flaresPerBurst: Int = 12,
    @SerialName("FlareLeftPos") @Serializable(with = Vec3Serializer::class)
    val flareLeftPos: Vec3 = Vec3(-1.0, -0.2, 0.6),
    @SerialName("FlareRightPos") @Serializable(with = Vec3Serializer::class)
    val flareRightPos: Vec3 = Vec3(1.0, -0.2, 0.6),
    @SerialName("FlareEjectionSpeed") val flareEjectionSpeed: Double = 0.7,
) {
    init {
        require(flaresPerSecond in 2..40)
        require(flaresPerBurst in 2..128 && flaresPerBurst % 2 == 0)
        require(flareEjectionSpeed.isFinite() && flareEjectionSpeed in 0.05..3.0)
        require(listOf(flareLeftPos, flareRightPos).all {
            it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.lengthSqr() <= 4096.0
        })
    }
}
