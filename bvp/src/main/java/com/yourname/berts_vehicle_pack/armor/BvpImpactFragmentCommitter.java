package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.*;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDisposition;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult;
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.mojang.logging.LogUtils;
import com.yourname.berts_vehicle_pack.network.BvpNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.Locale;

/** Publishes one visual recipe after an accepted impact; fragment simulation belongs to clients. */
public final class BvpImpactFragmentCommitter {
    private static final ImpactFragmentAdmission<Projectile, Level> ADMISSION = new ImpactFragmentAdmission<>();
    private static long lastFailureNanos;

    private BvpImpactFragmentCommitter() {}

    static void commit(ProjectileImpactContext context, ProjectileImpactResult result) {
        Projectile source = context.getProjectile();
        if (!(source.m_9236_() instanceof ServerLevel level) || !finite(context.getHitVec())) return;
        boolean ricochet = result.getPresentationOutcome() == ProjectileImpactPresentationOutcome.RICOCHET;
        boolean nonPenetration = ricochet
                || result.getPresentationOutcome() == ProjectileImpactPresentationOutcome.NON_PENETRATION;
        if (source instanceof ProjectileEntity fragment && fragment.isImpactShrapnel()) return;
        ResolvedProjectileProfile profile = ProjectileProfiles.resolve(source);
        if (profile == null || profile.getCombat() == null) return;
        ProjectileCombatDescriptor combat = profile.getCombat();
        double caliber = caliber(context, combat);
        boolean heavyWarhead = profile.extension(new net.minecraft.resources.ResourceLocation("superbwarfare", "heavy_warhead_blast_v1")) != null;
        boolean explosiveAutocannon = combat.getHullDamageClass() == ProjectileHullDamageClass.HE
                && caliber >= 20.0D && caliber <= 30.0D;
        // A consumed HE detonation need not report NON_PENETRATION. The old gate silently
        // removed the ZU-23 HE burst before its client-side fragment recipe was selected.
        if ((!nonPenetration && !explosiveAutocannon && !heavyWarhead)
                || result.getDisposition() == ProjectileImpactDisposition.PASS && !ricochet) return;
        boolean collision = switch (context.getKind()) {
            case BLOCK -> context.getBlockPos() != null && context.getBlockState() != null && context.getBlockFace() != null;
            case ENTITY -> context.getTarget() != null && (explosiveAutocannon || heavyWarhead || context.getVehicleImpactVolumes()
                    .get(ArmorImpactHandler.VOLUME_PROVIDER_ID, BvpImpactVolumeQuery.class) != null);
        };
        Vec3 incoming = context.getIncomingVelocity();
        if (!collision || !finite(incoming) || incoming.m_82556_() <= 1.0E-6D) return;
        boolean cyclic = ProjectileProfiles.isKpvtRoundId(combat.getRoundId());
        boolean incendiary = cyclic && combat.getRoundId().m_135815_().equalsIgnoreCase("kpvt_iai");
        String munition = combat.getMunitionType() == null ? ""
                : combat.getMunitionType().toString().toLowerCase(Locale.ROOT);
        int policy = munition.endsWith(":atgm") ? 4 : munition.endsWith(":rocket")
                ? (combat.getCaliberMm() != null && Double.isFinite(combat.getCaliberMm())
                && combat.getCaliberMm() > 80.0D ? 3 : 2) : 0;
        if (heavyWarhead) policy = 6;
        else if (policy == 0 && explosiveAutocannon) policy = 5;
        if (cyclic) {
            if (!incendiary) return;
            policy = 1;
        } else if (policy == 0 && (!Double.isFinite(caliber) || caliber <= 12.7D)) {
            return;
        }
        if (!ADMISSION.claim(source, level, level.m_46467_(), context.getKind(), context.getHitVec())) return;
        try {
            BvpNetwork.sendImpactFragments(level, context.getHitVec(), incoming,
                    impactNormal(context, incoming), (float) caliber, policy);
        } catch (RuntimeException failure) {
            // Optional presentation cannot roll back the already resolved armor transaction.
            long now = System.nanoTime();
            if (lastFailureNanos == 0 || now - lastFailureNanos >= 1_000_000_000L) {
                lastFailureNanos = now;
                LogUtils.getLogger().warn("Impact fragment presentation delivery failed", failure);
            }
        }
    }

    /** Retained binary entry point. Impact fragments no longer have a server gameplay representation. */
    @Deprecated
    public static void spawnLegacy(Level level, Projectile source, Vec3 position, Vec3 incoming,
                                   Vec3 normal, int count, float scale, long seed) {
    }

    static double caliber(ProjectileImpactContext context, ProjectileCombatDescriptor combat) {
        if (combat.getCaliberMm() != null && Double.isFinite(combat.getCaliberMm()) && combat.getCaliberMm() > 0.0D) {
            return combat.getCaliberMm();
        }
        BvpImpactVolumeQuery volumes = context.getVehicleImpactVolumes()
                .get(ArmorImpactHandler.VOLUME_PROVIDER_ID, BvpImpactVolumeQuery.class);
        ProjectileArmorEffect shot = volumes == null
                ? ArmorShotClassifier.classifyBvpImpact(context.getProjectile(), context.getOwner(), context.getHitVec())
                : ArmorShotClassifier.classify(context.getProjectile(), context.getOwner(), volumes.profile(), context.getHitVec());
        if (volumes != null && shot == null) {
            shot = ArmorShotClassifier.classifyUnmodeledBvpImpact(context.getProjectile(), context.getOwner());
        }
        if (shot == null || shot.impactVisual == null) return 0.0D;
        return switch (shot.impactVisual) {
            case BULLET -> 7.62D;
            case HMG -> 12.7D;
            case AUTOCANNON_AP, AUTOCANNON_HE -> 30.0D;
            case APFSDS, HEAT_FS, HE, ATGM -> 100.0D;
            case NONE -> 0.0D;
        };
    }

    static Vec3 impactNormal(ProjectileImpactContext context, Vec3 incomingDirection) {
        if (context.getBlockFace() != null) {
            var normal = context.getBlockFace().m_122436_();
            return new Vec3(normal.m_123341_(), normal.m_123342_(), normal.m_123343_());
        }
        BvpImpactVolumeQuery volumes = context.getVehicleImpactVolumes()
                .get(ArmorImpactHandler.VOLUME_PROVIDER_ID, BvpImpactVolumeQuery.class);
        if (volumes != null) {
            ArmorProfiles.ArmorHit armorHit = volumes.armorHit(volumes.initialTrace());
            if (armorHit != null && armorHit.plate != null && armorHit.localImpact != null) {
                ArmorProfiles.Vec localNormal = ArmorHitResolver.normalToHullFrame(volumes.target(),
                        armorHit.plate, armorHit.frameNormal());
                if (localNormal != null) {
                    Vec3 origin = volumes.target().armorLocalPointToWorld(ArmorProfiles.Vec.ZERO);
                    Vec3 tip = volumes.target().armorLocalPointToWorld(localNormal);
                    Vec3 worldNormal = tip.m_82546_(origin);
                    if (finite(worldNormal) && worldNormal.m_82556_() > 1.0E-6D) {
                        return worldNormal.m_82541_();
                    }
                }
            }
        }
        // Unboxed/entity fallback has no authored plate normal; use the accepted incoming
        // direction as the deterministic outward normal rather than inventing a surface.
        return finite(incomingDirection) && incomingDirection.m_82556_() > 1.0E-6D
                ? incomingDirection.m_82490_(-1.0D).m_82541_()
                : new Vec3(0.0D, 1.0D, 0.0D);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.f_82479_)
                && Double.isFinite(value.f_82480_) && Double.isFinite(value.f_82481_);
    }
}
