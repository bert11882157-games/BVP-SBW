package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.effect.TransientLights;
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest;
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageResult;
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy;
import com.atsuishio.superbwarfare.api.vehicle.destruction.TurretEjectionPolicy;
import com.atsuishio.superbwarfare.api.vehicle.destruction.VehicleDestructionContext;
import com.yourname.berts_vehicle_pack.ammo.BvpTankShells;
import com.yourname.berts_vehicle_pack.damage.BvpDamageTypes;
import com.yourname.berts_vehicle_pack.effects.BvpProjectileTrailHooks;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

final class AmmoRackService {
    private static final float SUPER_AMMO_RACK_EXPLOSION_RADIUS = 10.0F;
    // Presentation only: small platforms and nearly empty racks do not make a mushroom cloud.
    private static boolean substantialAmmoRack(ArmoredVehicleEntity vehicle) {
        if (vehicle.computed().getLethalDirectCaliberMm() != null) return false;
        int shells = 0;
        for (var stack : vehicle.getItems()) {
            if (BvpTankShells.isCannonShell(stack)) shells += stack.m_41613_();
            if (shells >= 10) return true;
        }
        return false;
    }
    private static final ResourceLocation AMMO_RACK_DESTRUCTION_CAUSE =
            new ResourceLocation("berts_vehicle_pack", "ammo_rack");

    private AmmoRackService() {
    }

    static boolean isSuperAmmoRackOverloaded(ArmorTarget target) {
        return BvpTankShells.exceedsSuperAmmoRackLimit(target.vehicle().getItems());
    }

    static boolean triggerAmmoRackDetonation(ArmorTarget target, Vec3 hitVec, DamageSource source) {
        return triggerAmmoRackDetonation(target, hitVec, source, 12);
    }

    private static boolean triggerAmmoRackDetonation(ArmorTarget target, Vec3 hitVec, DamageSource source,
                                                      int lightTicks) {
        ArmoredVehicleEntity vehicle = target.vehicle();
        ResolvedVehicleDamageResult result = vehicle.applyResolvedDamage(new ResolvedVehicleDamageRequest(
                source,
                Math.max(1.0F, vehicle.getHealth()),
                ResolvedVehicleModulePolicy.SKIP_NATIVE,
                ammoRackDestructionContext(vehicle, hitVec, source),
                true,
                true
        ));
        boolean detonated = result.getAccepted() && result.getDestroyed();
        if (detonated) {
            TransientLights.spawn(vehicle.m_9236_(), hitVec, 15, lightTicks);
        }
        return detonated;
    }

    static boolean triggerSuperAmmoRackDetonation(ArmorTarget target, Vec3 hitVec, DamageSource source) {
        if (!triggerAmmoRackDetonation(target, hitVec, source, 18)) {
            return false;
        }
        Level level = target.level();
        if (!level.f_46443_) {
            killPlayersInSuperAmmoRack(target.vehicle(), hitVec, level, source);
        }
        return true;
    }

    private static void killPlayersInSuperAmmoRack(ArmoredVehicleEntity vehicle, Vec3 hitVec, Level level,
                                                    DamageSource source) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        double radiusSqr = SUPER_AMMO_RACK_EXPLOSION_RADIUS * SUPER_AMMO_RACK_EXPLOSION_RADIUS;
        for (ServerPlayer player : serverLevel.m_6907_()) {
            if (!player.m_21224_()
                    && player.m_20275_(hitVec.f_82479_, hitVec.f_82480_, hitVec.f_82481_) <= radiusSqr) {
                player.m_6469_(BvpDamageTypes.superAmmoRack(level, vehicle, source.m_7639_()), Float.MAX_VALUE);
            }
        }
    }

    private static VehicleDestructionContext ammoRackDestructionContext(ArmoredVehicleEntity vehicle, Vec3 hitVec,
                                                                        DamageSource source) {
        Vec3 launch = vehicle.getUpVec(1.0F)
                .m_82490_(1.25D)
                .m_82549_(vehicle.m_20184_())
                .m_82549_(new Vec3(0.0D, 0.65D, 0.0D));
        ResourceLocation vehicleId = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.m_6095_());
        return VehicleDestructionContext.builder()
                .directSource(vehicle)
                .attacker(source.m_7639_())
                .turretPolicy(TurretEjectionPolicy.FORCE_EJECT)
                .turretImpulse(launch)
                .turretCrushPolicy(TurretWreckImpactHandler.AMMO_RACK_CRUSH_POLICY_ID)
                .explosionCause(AMMO_RACK_DESTRUCTION_CAUSE)
                .explosionProfile(substantialAmmoRack(vehicle)
                        ? BvpProjectileTrailHooks.AMMO_RACK_EXPLOSION_PROFILE_ID : null)
                .wreckVisual(vehicleId)
                .particlePosition(hitVec)
                .build();
    }
}
