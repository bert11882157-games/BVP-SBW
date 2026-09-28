package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.effect.TransientLights;
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest;
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;

final class VehicleDamageService {
    private static final ResourceLocation ARMOR_DESTRUCTION_CAUSE =
            new ResourceLocation("berts_vehicle_pack", "armor_damage");

    private VehicleDamageService() {
    }

    static void applyVehicleDamage(ArmorTarget target, DamageSource source, double damageAmount) {
        ArmoredVehicleEntity vehicle = target.vehicle();
        if (!Double.isFinite(damageAmount) || damageAmount <= 0.0D) {
            return;
        }
        applyResolvedDamage(vehicle, source, (float) damageAmount);
    }

    private static void applyResolvedDamage(ArmoredVehicleEntity vehicle, DamageSource source, float damage) {
        if (source == null || !Float.isFinite(damage) || damage <= 0.0F) {
            return;
        }
        boolean wasWreck = vehicle.isWreck();
        vehicle.applyResolvedDamage(new ResolvedVehicleDamageRequest(
                source,
                damage,
                ResolvedVehicleModulePolicy.SKIP_NATIVE,
                vehicle.defaultDestructionContext().toBuilder()
                        .attacker(source.m_7639_())
                        .explosionCause(ARMOR_DESTRUCTION_CAUSE)
                        .build(),
                true,
                false
        ));
        if (!wasWreck && vehicle.isWreck()) {
            // No SBW white burst for a helicopter wreck: the destruction lifecycle owns the TNT fireball.
            TransientLights.spawn(vehicle.m_9236_(), vehicle.m_20182_(), 15, 10);
        }
    }
}
