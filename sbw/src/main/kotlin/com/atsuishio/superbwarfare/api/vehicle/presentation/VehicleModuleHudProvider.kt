package com.atsuishio.superbwarfare.api.vehicle.presentation

/** Actual independently tracked modules; null means the vehicle does not expose that module. */
interface VehicleModuleHudProvider {
    fun vehicleModuleHudState(): VehicleModuleHudState
}

data class VehicleModuleHudHealth(val health: Float, val maximum: Float, val destroyed: Boolean)
data class VehicleModuleHudState(
    val engine: VehicleModuleHudHealth?,
    val leftTrack: VehicleModuleHudHealth?,
    val rightTrack: VehicleModuleHudHealth?,
    val weapon: VehicleModuleHudHealth?,
)

/** Intervals are [0,33), [33,66), [66,100); zero health/destruction is separate. */
enum class VehicleModuleHudDamage(val fill: Int, val outline: Int) {
    UNKNOWN(0xFF7D8994.toInt(), 0xFF7D8994.toInt()),
    YELLOW(0xFFFFD95A.toInt(), 0xFFFFD95A.toInt()),
    ORANGE(0xFFFF962E.toInt(), 0xFFFF962E.toInt()),
    RED(0xFFFF4141.toInt(), 0xFFFF4141.toInt()),
    DESTROYED(0xFF080A0C.toInt(), 0xFFFF3030.toInt());

    companion object {
        fun from(module: VehicleModuleHudHealth?): VehicleModuleHudDamage {
            if (module == null) return UNKNOWN
            if (module.destroyed) return DESTROYED
            if (!module.health.isFinite() || !module.maximum.isFinite() || module.maximum <= 0F) return UNKNOWN
            if (module.health <= 0F) return DESTROYED
            // Synced health is float: 40.2/60 must still mean exactly 33% damage.
            // Cross-multiply with a 0.0001 percentage-point serialization tolerance.
            val health = module.health.toDouble() * 100.0
            val tolerance = module.maximum.toDouble() * 0.0001
            return when { health > module.maximum.toDouble() * 67.0 + tolerance -> YELLOW
                health > module.maximum.toDouble() * 34.0 + tolerance -> ORANGE
                else -> RED }
        }
    }
}
