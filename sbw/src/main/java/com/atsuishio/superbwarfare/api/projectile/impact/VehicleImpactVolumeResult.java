package com.atsuishio.superbwarfare.api.projectile.impact;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** Immutable set of provider-owned views attached to one projectile impact context. */
public final class VehicleImpactVolumeResult {
    private static final VehicleImpactVolumeResult EMPTY = new VehicleImpactVolumeResult(Map.of());

    private final Map<ResourceLocation, VehicleImpactVolumeView> views;

    private VehicleImpactVolumeResult(Map<ResourceLocation, VehicleImpactVolumeView> views) {
        this.views = views;
    }

    static VehicleImpactVolumeResult of(Map<ResourceLocation, VehicleImpactVolumeView> views) {
        if (views == null || views.isEmpty()) {
            return EMPTY;
        }
        return new VehicleImpactVolumeResult(views);
    }

    public static VehicleImpactVolumeResult empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return views.isEmpty();
    }

    public VehicleImpactVolumeView get(ResourceLocation providerId) {
        return providerId == null ? null : views.get(providerId);
    }

    public <T extends VehicleImpactVolumeView> T get(ResourceLocation providerId, Class<T> viewType) {
        VehicleImpactVolumeView view = get(providerId);
        return viewType != null && viewType.isInstance(view) ? viewType.cast(view) : null;
    }
}
