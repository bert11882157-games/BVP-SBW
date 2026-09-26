package com.atsuishio.superbwarfare.api.vehicle.presentation

/** Read-only client presentation. An empty layout means no located damage modules exist. */
interface VehicleModuleHudLayoutProvider {
    fun vehicleModuleHudLayout(partialTick: Float): List<VehicleModuleHudMarker>
}

enum class VehicleModuleHudKind { ENGINE, TRACK, WHEEL, WEAPON, AMMO, MODULE }

/** Position is in the hull's Bedrock geometry coordinates, in model pixels (16 per block). */
data class VehicleModuleHudMarker @JvmOverloads constructor(
    val id: String,
    val kind: VehicleModuleHudKind,
    val modelX: Double,
    val modelZ: Double,
    val health: VehicleModuleHudHealth,
    /** Authored longitudinal bounds; grouped track sections retain their complete span. */
    val modelMinZ: Double = modelZ,
    val modelMaxZ: Double = modelZ,
    /**
     * Top-down outlines of the module's hit volumes, each a convex polygon as flat [x0, z0, x1, z1, ...] in the
     * same model-pixel frame. Empty: only the position is known and a glyph is drawn.
     */
    val footprints: List<DoubleArray> = emptyList(),
)
