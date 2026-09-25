package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ProjectileCombatDescriptor;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-only deterministic ricochet gate for typed per-round WT curves. */
final class ArmorRicochetService {
    private static final double EPSILON = 1.0E-9D;
    private static final double MAX_RICOCHET_SPEED = 64.0D;
    private static final double POST_IMPACT_OFFSET = 0.06D;
    private static final Map<Projectile, String> LAST_DEFLECTION =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ArmorRicochetService() {
    }

    static Decision evaluate(Projectile projectile, ArmorTarget target, ArmorHit armorHit,
                             ArmorHitResolver.ShotTrace trace) {
        if (projectile == null || target == null || armorHit == null || armorHit.plate == null || trace == null) {
            return Decision.NONE;
        }
        if (armorHit.localImpact == null || !finite(armorHit.localImpact)
                || !finite(trace.hitVec) || !finite(trace.hullShotDirection)
                || trace.hullShotDirection.length() < EPSILON) {
            return Decision.NONE;
        }
        ProjectileCombatDescriptor descriptor = ProjectileProfiles.combatDescriptor(projectile);
        // A typed round identity and an authored curve are both mandatory.  This prevents a
        // caliber/global fallback from silently changing unmodeled rounds.
        if (descriptor == null || descriptor.getRoundId() == null || descriptor.getRicochetCurve() == null) {
            return Decision.NONE;
        }
        String plateKey = target.vehicle().m_20148_() + ":" + armorHit.plate.name;
        if (plateKey.equals(LAST_DEFLECTION.get(projectile))) {
            return new Decision(false, true, Double.NaN, 0.0D, 0.0D);
        }
        Vec localDirection = ArmorHitResolver.directionToBoxFrame(
                target, armorHit.plate, trace.hullShotDirection).normalize();
        Vec normal = armorHit.frameNormal().normalize();
        if (!finite(localDirection) || !finite(normal)
                || localDirection.length() < EPSILON || normal.length() < EPSILON) {
            return Decision.NONE;
        }
        // WT incidence is measured from the outward plate normal: 0° is a perpendicular
        // front-face hit and 90° is grazing.  A valid front-face contact approaches the
        // outward normal, hence dot(direction, normal) must be strictly negative. Using the
        // absolute dot product would also admit back-face exits as front impacts.
        double cosine = -localDirection.dot(normal);
        if (!Double.isFinite(cosine) || cosine <= EPSILON || cosine > 1.0D + EPSILON) {
            return Decision.NONE;
        }
        cosine = Math.max(0.0D, Math.min(1.0D, cosine));
        double angle = Math.toDegrees(Math.acos(cosine));
        if (!Double.isFinite(angle) || angle < 0.0D || angle > 90.0D) {
            return Decision.NONE;
        }
        Double probability = descriptor.ricochetProbabilityAtIncidence(angle);
        if (probability == null || !Double.isFinite(probability)
                || probability <= EPSILON) {
            return new Decision(false, false, angle, probability == null ? 0.0D : probability, 0.0D);
        }
        double sample = deterministicUnit(projectile.m_20148_(), target.vehicle().m_20148_(),
                armorHit.plate.name, trace.hitVec);
        return new Decision(sample < probability, false, angle, probability, sample);
    }

    /** Applies the accepted deterministic reflection once, with no unsupported roughness term,
     * and preserves the projectile's existing lifetime (never resetting or extending it). */
    static boolean applyDeflection(Projectile projectile, ArmorTarget target, ArmorHit armorHit,
                                   ArmorHitResolver.ShotTrace trace) {
        if (projectile == null || target == null || armorHit == null || armorHit.plate == null
                || armorHit.localImpact == null || trace == null || !finite(trace.hitVec)
                || !finite(armorHit.localImpact)) {
            return false;
        }
        // Frame-local normal (turret volumes are authored at the rest pose) back into hull
        // coordinates: barrel volumes through the barrel frame, turret volumes by the turret yaw.
        Vec localNormal = ArmorHitResolver.normalToHullFrame(target, armorHit.plate,
                armorHit.frameNormal().normalize());
        if (localNormal == null) return false;
        Vec3 origin = target.armorLocalPointToWorld(ArmorProfiles.Vec.ZERO);
        Vec3 tip = target.armorLocalPointToWorld(localNormal);
        Vec3 normal = tip.m_82546_(origin);
        if (!finite(normal) || normal.m_82556_() < EPSILON) return false;
        normal = normal.m_82541_();

        Vec3 incoming = projectile.m_20184_();
        double speed = incoming.m_82553_();
        if (!finite(incoming) || !Double.isFinite(speed) || speed < EPSILON) return false;
        // ArmorBox normals are authored in the plate frame.  Orient the world normal away
        // from the incoming projectile so the offset is guaranteed to leave the plate; the
        // reflected vector itself is invariant under a normal sign flip.
        if (incoming.m_82526_(normal) > 0.0D) {
            normal = normal.m_82490_(-1.0D);
        }
        Vec3 reflected = incoming.m_82546_(normal.m_82490_(2.0D * incoming.m_82526_(normal)));
        double reflectedLength = reflected.m_82553_();
        if (!Double.isFinite(reflectedLength) || reflectedLength < EPSILON) return false;
        reflected = reflected.m_82490_(Math.min(speed, MAX_RICOCHET_SPEED) / reflectedLength);
        projectile.m_20256_(reflected);

        // ProjectileEntity applies its pre-impact movement after onHit; pre-position it so that
        // the same tick lands just outside the plate. FastThrowable already moved before onHit.
        Vec3 launchPoint = trace.hitVec.m_82549_(normal.m_82490_(POST_IMPACT_OFFSET));
        if (!finite(launchPoint)) {
            return false;
        }
        if (projectile instanceof com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity) {
            launchPoint = launchPoint.m_82546_(incoming);
        }
        if (!finite(launchPoint)) {
            return false;
        }
        projectile.m_6034_(launchPoint.f_82479_, launchPoint.f_82480_, launchPoint.f_82481_);
        LAST_DEFLECTION.put(projectile, target.vehicle().m_20148_() + ":" + armorHit.plate.name);
        return true;
    }

    private static double deterministicUnit(UUID projectileUuid, UUID targetUuid,
                                            String plateName, net.minecraft.world.phys.Vec3 hit) {
        long value = projectileUuid == null ? 0L : projectileUuid.getMostSignificantBits()
                ^ Long.rotateLeft(projectileUuid.getLeastSignificantBits(), 17);
        if (targetUuid != null) {
            value ^= Long.rotateLeft(targetUuid.getMostSignificantBits(), 23)
                    ^ Long.rotateLeft(targetUuid.getLeastSignificantBits(), 41);
        }
        value ^= plateName == null ? 0L : plateName.hashCode() * 0x9E3779B97F4A7C15L;
        if (hit != null) {
            value ^= Double.doubleToLongBits(hit.f_82479_);
            value = Long.rotateLeft(value, 19) ^ Double.doubleToLongBits(hit.f_82480_);
            value = Long.rotateLeft(value, 19) ^ Double.doubleToLongBits(hit.f_82481_);
        }
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    record Decision(boolean ricochet, boolean repeated, double incidenceAngleDegrees,
                    double probability, double sample) {
        static final Decision NONE = new Decision(false, false, Double.NaN, 0.0D, 0.0D);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.f_82479_) && Double.isFinite(value.f_82480_)
                && Double.isFinite(value.f_82481_);
    }

    private static boolean finite(Vec sampleVec) {
        return sampleVec != null && Double.isFinite(sampleVec.x) && Double.isFinite(sampleVec.y)
                && Double.isFinite(sampleVec.z);
    }
}
