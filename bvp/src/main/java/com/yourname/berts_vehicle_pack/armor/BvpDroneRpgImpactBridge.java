package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.impact.DronePayloadImpacts;
import com.atsuishio.superbwarfare.api.projectile.impact.DronePayloadImpacts.Decision;
import com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;
import com.atsuishio.superbwarfare.entity.projectile.RpgRocketStandardEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.EntityHitResult;

/** Standard RPG drone payloads use the exact handheld shaped-charge armor contract. */
public final class BvpDroneRpgImpactBridge {
    private BvpDroneRpgImpactBridge() { }
    public static void register() {
        DronePayloadImpacts.register(new ResourceLocation("berts_vehicle_pack", "drone_rpg"),
                BvpDroneRpgImpactBridge::resolve);
        if (DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) BvpDroneRpgScenario.register();
    }
    private static Decision resolve(Entity drone, Entity payload, Entity owner, Entity target,
                                   Vec3 point, Vec3 velocity) {
        if (!(payload instanceof RpgRocketStandardEntity rocket) || !(target instanceof VehicleEntity vehicle)
                || target == drone) return Decision.UNCLAIMED;
        if (!Double.isFinite(velocity.m_82556_()) || velocity.m_82556_() < 1.0E-8D) return Decision.MISS;
        // Only the incoming tick segment plus the drone's 0.35-block forward contact skin.
        Vec3 start = point.m_82546_(velocity);
        Vec3 end = point.m_82549_(velocity.m_82541_().m_82490_(0.35D));
        ArmorTarget armorTarget = ArmorTargetAdapters.resolve(target);
        var contact = armorTarget == null
                ? ProjectileHitSelection.nearestObb(vehicle.getOBBs(), start, end, 0.0D)
                : BvpProjectileCollision.clip(armorTarget, ArmorProfiles.get(armorTarget.armorProfileId()), start, end);
        if (contact == null) {
            EliteDiagnostics.record(drone, "drone_rpg", "CONTACT_MISS", "target", target.m_20148_(),
                    "start", start, "end", end);
            return Decision.MISS;
        }
        rocket.m_5602_(owner);
        rocket.m_146884_(contact.point());
        rocket.m_20256_(velocity);
        ProjectileProfiles.assign(rocket, BvpHandheldAtPolicy.PROFILE);
        ProjectileProfiles.recordServerLaunchPosition(rocket, point.m_82546_(velocity));
        var combat = ProjectileProfiles.combatDescriptor(rocket);
        boolean valid = combat != null && combat.getPenetrationMm() == 400.0D
                && new ResourceLocation("berts_vehicle_pack", "chemical").equals(combat.getDamageType());
        EliteDiagnostics.record(drone, "drone_rpg", "PROFILE_RESOLVED", "profile",
                ProjectileProfiles.profileId(rocket), "valid", valid, "target", target.m_20148_(),
                "penetration_mm", combat == null ? null : combat.getPenetrationMm());
        // A missing retained profile must never revert to the old 340 raw-damage bypass.
        if (!valid) return Decision.MISS;
        float before = vehicle.getHealth();
        rocket.impactFromCarrier(new EntityHitResult(target, contact.point()));
        if (rocket.m_213877_()) {
            drone.getPersistentData().m_128405_("BvpRpgImpactCount", drone.getPersistentData().m_128451_("BvpRpgImpactCount") + 1);
            drone.getPersistentData().m_128347_("BvpRpgPenetrationMm", combat.getPenetrationMm());
        }
        EliteDiagnostics.record(drone, "drone_rpg", "ARMOR_RESOLVED", "target", target.m_20148_(),
                "contact", contact.point(), "hull_before", before, "hull_after", vehicle.getHealth(),
                "consumed", rocket.m_213877_());
        return rocket.m_213877_() ? Decision.CONSUMED : Decision.MISS;
    }
}
