package com.atsuishio.superbwarfare.api.projectile.impact;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

/** Ordered extension registry used before native Superb Warfare impact behavior begins. */
public final class ProjectileImpactResolver {
    private static final Map<String, ProjectileImpactHandler> HANDLERS = new ConcurrentSkipListMap<>();

    private ProjectileImpactResolver() {
    }

    public static void register(ResourceLocation id, ProjectileImpactHandler handler) {
        HANDLERS.put(Objects.requireNonNull(id, "id").toString(),
                Objects.requireNonNull(handler, "handler"));
    }

    public static void unregister(ResourceLocation id) {
        if (id != null) {
            HANDLERS.remove(id.toString());
        }
    }

    public static ProjectileImpactResult resolve(ProjectileImpactContext context) {
        Objects.requireNonNull(context, "context");
        if (context.getProjectile().level().isClientSide()) {
            return ProjectileImpactResult.defaultResult();
        }
        ProjectileImpactContext resolvedContext = VehicleImpactVolumes.attach(context);
        ProjectileImpactResult resolvedResult = ProjectileImpactResult.defaultResult();
        for (ProjectileImpactHandler handler : HANDLERS.values()) {
            ProjectileImpactResult result = handler.resolve(resolvedContext);
            if (result != null && result.hasOverrides()) {
                resolvedResult = result;
                break;
            }
        }
        // Handler-owned gameplay and sound calls complete before post-resolution presentation.
        ProjectileImpactPresentations.dispatch(resolvedContext, resolvedResult);
        return resolvedResult;
    }
}
