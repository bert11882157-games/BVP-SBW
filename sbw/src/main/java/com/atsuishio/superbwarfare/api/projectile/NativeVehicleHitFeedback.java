package com.atsuishio.superbwarfare.api.projectile;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BiConsumer;

/** Server-only cosmetic callback after a native projectile's accepted vehicle contact. */
public final class NativeVehicleHitFeedback {
    private static final ConcurrentSkipListMap<String, BiConsumer<VehicleEntity, Double>> PROVIDERS =
            new ConcurrentSkipListMap<>();

    private NativeVehicleHitFeedback() { }

    public static void register(ResourceLocation id, BiConsumer<VehicleEntity, Double> provider) {
        if (PROVIDERS.putIfAbsent(id.toString(), provider) != null) {
            throw new IllegalStateException("Duplicate native vehicle-hit feedback provider: " + id);
        }
    }

    public static void accepted(Projectile projectile, VehicleEntity vehicle) {
        if (projectile.level().isClientSide || vehicle.isRemoved()) return;
        Double caliber = ProjectileCalibers.resolveMillimetres(projectile);
        if (caliber == null || !Double.isFinite(caliber) || caliber <= 0) return;
        for (var provider : PROVIDERS.values()) provider.accept(vehicle, caliber);
    }
}
