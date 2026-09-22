package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.effect.TransientLights;
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest;
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy;
import com.atsuishio.superbwarfare.tools.ParticleTool;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.helicopter.BvpHelicopterEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;

final class VehicleDamageService {
    private static final double CHEMICAL_DAMAGE_MULTIPLIER = 0.80D;
    private static final ResourceLocation ARMOR_DESTRUCTION_CAUSE =
            new ResourceLocation("berts_vehicle_pack", "armor_damage");

    private VehicleDamageService() {
    }

    static void applyChemicalVehicleDamage(ArmorTarget target, DamageSource source, boolean criticalHit) {
        applyResolvedDamage(target.vehicle(), source, chemicalVehicleDamageBasis(target, criticalHit));
    }

    static float chemicalVehicleDamageBasis(ArmorTarget target, boolean criticalHit) {
        float maxHealth = Math.max(1.0F, target.vehicle().getMaxHealth());
        return maxHealth * (float) CHEMICAL_DAMAGE_MULTIPLIER * (criticalHit ? 2.0F : 1.0F);
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
            // VehicleEntity intentionally omits vehicleExplosion for AIRPLANE/HELICOPTER
            // destruction. BVP helicopter damage still reaches this one wreck transition,
            // so restore presentation only here; gameplay damage/destruction remains native.
            if (vehicle instanceof BvpHelicopterEntity) {
                ParticleTool.spawnExplosionParticles(
                        vehicle.computed().getDestroyInfo().getParticleType(),
                        vehicle.m_9236_(),
                        vehicle.m_20182_());
            }
            TransientLights.spawn(vehicle.m_9236_(), vehicle.m_20182_(), 15, 10);
        }
    }
}
