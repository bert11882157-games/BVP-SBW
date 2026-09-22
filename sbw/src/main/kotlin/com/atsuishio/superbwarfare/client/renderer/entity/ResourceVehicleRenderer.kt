package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.client.model.entity.VehicleModel
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider

/**
 * Generic native SBW outer renderer for data-authored vehicle types.
 * GeometryBackend may replace body geometry, while this renderer continues to
 * own culling, LOD/GUI selection, texture policy, dog tags and render layers.
 */
class ResourceVehicleRenderer<T : GeoVehicleEntity>(renderManager: EntityRendererProvider.Context) :
    VehicleRenderer<T>(renderManager, VehicleModel()) {
    init {
        shadowRadius = 1.8f
    }
}
