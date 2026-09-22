package com.atsuishio.superbwarfare.api.projectile.impact;

import com.atsuishio.superbwarfare.Mod;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

/** Server-only registry queried after native collision has already produced an entity impact. */
public final class VehicleImpactVolumes {
    private static final Map<String, RegisteredProvider> PROVIDERS = new ConcurrentSkipListMap<>();

    private VehicleImpactVolumes() {
    }

    public static void register(ResourceLocation id, VehicleImpactVolumeProvider provider) {
        ResourceLocation checkedId = Objects.requireNonNull(id, "id");
        PROVIDERS.put(checkedId.toString(),
                new RegisteredProvider(checkedId, Objects.requireNonNull(provider, "provider")));
    }

    public static void unregister(ResourceLocation id) {
        if (id != null) {
            PROVIDERS.remove(id.toString());
        }
    }

    public static VehicleImpactVolumeResult query(ProjectileImpactContext context) {
        Objects.requireNonNull(context, "context");
        if (context.getKind() != ProjectileImpactContext.Kind.ENTITY
                || context.getProjectile().level().isClientSide) {
            return VehicleImpactVolumeResult.empty();
        }

        Map<ResourceLocation, VehicleImpactVolumeView> views = null;
        for (RegisteredProvider registered : PROVIDERS.values()) {
            try {
                VehicleImpactVolumeView view = registered.provider.query(context);
                if (view == null) {
                    continue;
                }
                if (views == null) {
                    views = new LinkedHashMap<>();
                }
                views.put(registered.id, view);
            } catch (RuntimeException exception) {
                Mod.LOGGER.warn("Vehicle impact-volume provider {} failed for target {}",
                        registered.id, context.getTarget(), exception);
            }
        }
        return VehicleImpactVolumeResult.of(views);
    }

    static ProjectileImpactContext attach(ProjectileImpactContext context) {
        VehicleImpactVolumeResult result = query(context);
        return result.isEmpty() ? context : context.withVehicleImpactVolumes(result);
    }

    private record RegisteredProvider(ResourceLocation id, VehicleImpactVolumeProvider provider) {
    }
}
