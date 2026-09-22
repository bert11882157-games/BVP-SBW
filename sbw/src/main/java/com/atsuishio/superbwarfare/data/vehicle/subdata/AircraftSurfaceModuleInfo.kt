package com.atsuishio.superbwarfare.data.vehicle.subdata

import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.phys.Vec3

@Serializable
class AircraftSurfaceBox {
    @SerialName("Bone") var bone: String = "hull"
    /** Vehicle-local bind-pose blocks, before the named bone's delta articulation. */
    @SerialName("Min") var min: SerializedVec3 = Vec3.ZERO
    @SerialName("Max") var max: SerializedVec3 = Vec3.ZERO
}

@Serializable
class AircraftSurfaceModuleInfo {
    @SerialName("Id") var id: String = ""
    @SerialName("MaxHealthFraction") var maxHealthFraction: Double = 0.0
    @SerialName("Hitboxes") var hitboxes: List<AircraftSurfaceBox> = emptyList()
}

/** Common-side copy of the validated source rig. Axes and pivots are vehicle-local. */
@Serializable
class AircraftSurfaceTransform {
    @SerialName("NativeChannel") var nativeChannel: String? = null
    @SerialName("Parent") var parent: String = "hull"
    @SerialName("Pivot") var pivot: SerializedVec3 = Vec3.ZERO
    @SerialName("Axis") var axis: SerializedVec3 = Vec3(1.0, 0.0, 0.0)
    @SerialName("ControlWeights") var controlWeights: SerializedVec3 = Vec3.ZERO
    @SerialName("MaxDeflectionDegrees") var maxDeflectionDegrees: Double = 0.0
    @SerialName("SpeedSchedule") var speedSchedule: List<List<Double>> = emptyList()
    @SerialName("AngleSign") var angleSign: Double = 1.0
}
