package com.atsuishio.superbwarfare.api.projectile.impact;

import com.atsuishio.superbwarfare.api.aircraft.AircraftProjectileDamage;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.mixin.OBBHitter;
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
        if (EliteDiagnostics.isEnabled(context.getProjectile().level())) {
            var projectile = context.getProjectile();
            var subject = context.getTarget() == null ? projectile : context.getTarget();
            EliteDiagnostics.record(subject, "hitreg", "impact_dispatch",
                    "projectile", projectile.getUUID(), "owner", context.getOwner() == null ? null : context.getOwner().getUUID(),
                    "kind", context.getKind(), "point", context.getHitVec(), "incoming_velocity", context.getIncomingVelocity(),
                    "contact", OBBHitter.getInstance(projectile).sbw$getProjectileContact(),
                    "block", context.getBlockPos());
        }
        ProjectileImpactResult lightPlatform = com.atsuishio.superbwarfare.api.vehicle.damage.LightPlatformProjectileDamage.resolve(context);
        if (lightPlatform != null) return lightPlatform;
        ProjectileImpactResult aircraft = AircraftProjectileDamage.resolve(context);
        if (aircraft != null) {
            // Aircraft HP is resolved before armor volume queries or pack penetration handlers.
            // Native effects/disposal remain active; the struck aircraft rejects duplicate damage.
            return aircraft;
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
