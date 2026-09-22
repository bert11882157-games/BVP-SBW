package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile;
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDisposition;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentations;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.effects.BvpLeanImpactEffects;
import com.yourname.berts_vehicle_pack.effects.BvpProjectileTrailHooks;
import com.yourname.berts_vehicle_pack.effects.BvpProjectileEffectDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * BVP's accepted-impact presentation boundary. The resolver has already committed the parent
 * gameplay result before this provider runs. The parent is never mutated; the only gameplay-like
 * work here is a bounded, marked child bullet fan whose own normal SBW damage is explicit and
 * cannot recurse into another impact fan.
 */
public final class BvpImpactPresentationProvider {
    private static final ResourceLocation PROVIDER_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "impact_presentation");
    private static final double BASE_CALIBER_MM = 125.0D;
    // The existing 125 mm presentation is the preserved baseline after the previous
    // one-third rebalance.  Every other caliber is derived from this one typed baseline.
    private static final float BASE_SHRAPNEL_SCALE = 2.5F / 3.0F;
    private static final int BASE_SHRAPNEL_MIN_COUNT = 4;
    private static final int BASE_SHRAPNEL_MAX_COUNT = 6;
    private static final double BELOW_STEP_FACTOR = 0.935D;
    private static final double ABOVE_STEP_FACTOR = 1.065D;
    private static final double MIN_BASELINE_FACTOR = 0.15D;
    private static final float SMALL_CALIBER_SCALE = BASE_SHRAPNEL_SCALE * 0.15F;
    private static final float KPVT_HE_BASELINE_SCALE = 0.5F;
    private static final String TAP_RICOCHET_SOUND = "berts_vehicle_pack:ricochet_nonpenetration";
    private static final int MAX_IMPACT_KEYS = 256;
    private static final double SAME_IMPACT_DISTANCE_SQR = 0.0625D;
    private static final Map<Projectile, ImpactKey> EMITTED_IMPACTS = new WeakHashMap<>();

    private BvpImpactPresentationProvider() {
    }

    public static void register() {
        ProjectileImpactPresentations.register(PROVIDER_ID, BvpImpactPresentationProvider::present);
    }

    private static boolean present(ProjectileImpactContext context, ProjectileImpactResult result) {
        boolean ricochet = result.getPresentationOutcome()
                == com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome.RICOCHET;
        if (result.getDisposition() == ProjectileImpactDisposition.PASS && !ricochet) {
            return false;
        }
        // The presentation registry is dispatched only after a resolver call, but a resolver
        // result alone is not sufficient proof that this was a collision owned by BVP.  Entity
        // contexts must carry the authoritative BVP armor-volume view; block contexts must carry
        // the native block hit tuple.  This prevents a generic/non-BVP entity or lifecycle path
        // from manufacturing a shrapnel fan at a free-flight position.
        if (!isAcceptedCollisionContext(context)) {
            return false;
        }
        boolean nonPenetration = ricochet || result.getPresentationOutcome()
                == com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome.NON_PENETRATION;
        boolean penetration = result.getPresentationOutcome()
                == com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome.PENETRATION;
        // Effects are admitted only after the authoritative armor branch has classified the
        // collision.  A DEFAULT result is not allowed to invent a penetration/non-penetration
        // effect classification.
        if (!nonPenetration && !penetration) {
            return false;
        }
        Projectile projectile = context.getProjectile();
        // Impact fragments are ordinary damaging SBW bullets, but their single generation must
        // never recurse into another fan or explosion presentation.
        if (projectile instanceof ProjectileEntity fragment && fragment.isImpactShrapnel()) {
            return false;
        }
        if (!finite(context.getHitVec())) {
            return false;
        }
        BvpProjectileEffectDefinition definition = BvpProjectileEffectDefinition.forEntity(projectile);
        ResolvedProjectileProfile profile = ProjectileProfiles.resolve(projectile);
        BvpProjectileEffectDefinition.Impact impactOnly =
                BvpProjectileEffectDefinition.impactOnly(profile);
        if (definition == null && impactOnly == null) {
            return false;
        }
        ProjectileArmorEffect.ImpactVisual visual = resolveImpactVisual(context);
        if (visual == null || visual == ProjectileArmorEffect.ImpactVisual.NONE) {
            visual = fallbackEffectVisual(definition != null ? definition.impact() : impactOnly);
        }
        if (visual == null || visual == ProjectileArmorEffect.ImpactVisual.NONE) {
            return false;
        }
        long seed = impactSeed(context);
        ImpactStreakSpec streak = nonPenetration ? shrapnelSpec(projectile, visual, seed) : null;
        boolean explosion = emitsExplosion(visual);
        boolean solidImpactBurst = penetration && emitsSolidImpactBurst(visual);
        // Even a <=12.7 mm non-penetration has an authoritative TaP ricochet sound.  It may
        // have no shrapnel by policy, but it still belongs to this once-only accepted event.
        if (streak == null && !explosion && !nonPenetration && !solidImpactBurst) {
            return false;
        }
        if (!claimImpact(projectile, context.getKind(), context.getHitVec())) {
            return true;
        }

        if (EliteDiagnostics.isEnabled(projectile.m_9236_())) {
            EliteDiagnostics.record(projectile, "impact", "bvp_presentation",
                    "profile", ProjectileProfiles.profileId(projectile),
                    "owner", context.getOwner() == null ? null : context.getOwner().m_20148_(),
                    "kind", context.getKind(), "outcome", result.getPresentationOutcome(),
                    "position", context.getHitVec(), "visual", visual,
                    "shrapnel_requested", streak == null ? 0 : streak.count(),
                    "shrapnel_scale", streak == null ? 0 : streak.scale(),
                    "explosion", explosion, "solid_impact_burst", solidImpactBurst,
                    "nonpenetration_sound", nonPenetration ? TAP_RICOCHET_SOUND : null);
        }

        if (nonPenetration) {
            // BVP owns the copied TaP non-penetration/ricochet event.
            ArmorSoundService.play(projectile.m_9236_(), context.getHitVec(), TAP_RICOCHET_SOUND,
                    1.0F, 1.0F);
            if (streak != null) {
                BvpLeanImpactEffects.spawnImpactShrapnel(
                        projectile.m_9236_(),
                        projectile,
                        context.getHitVec(),
                        projectile.m_20184_(),
                        impactNormal(context, projectile.m_20184_()),
                        streak.count(),
                        streak.scale(),
                        streak.seed());
            }
        }

        if (solidImpactBurst) {
            // Kinetic penetration gets only a tiny presentation burst.  This is particles/light,
            // not an explosive gameplay event, and never creates shrapnel.
            BvpLeanImpactEffects.spawnTinyExplosion(
                    projectile.m_9236_(), context.getHitVec(), projectile);
        }
        if (explosion) {
            emitExplosion(projectile.m_9236_(), context.getHitVec(), visual, projectile);
        }
        // Native gameplay remains authoritative, but every accepted BVP visual either replaces
        // or intentionally suppresses the later SBW explosion-particle route.  This is what keeps
        // solid AP/APDS/APFSDS presentation explosion-free without touching damage/collision.
        BvpProjectileTrailHooks.deferExplosionPresentation(
                projectile, projectile.m_9236_(), context.getHitVec());
        return true;
    }

    private static ProjectileArmorEffect.ImpactVisual fallbackEffectVisual(
            BvpProjectileEffectDefinition.Impact impact) {
        if (impact == null) {
            return null;
        }
        return switch (impact.explosion()) {
            case "medium" -> ProjectileArmorEffect.ImpactVisual.HE;
            case "large" -> ProjectileArmorEffect.ImpactVisual.ATGM;
            default -> null;
        };
    }

    private static boolean emitsExplosion(ProjectileArmorEffect.ImpactVisual visual) {
        return switch (visual) {
            // AP/APDS/APFSDS never publish a normal explosive detonation.  Penetrating kinetic
            // hits use the separate tiny presentation burst above; shrapnel is non-penetration-only.
            case AUTOCANNON_HE, HEAT_FS, HE, ATGM -> true;
            case AUTOCANNON_AP, APFSDS -> false;
            case BULLET, HMG, NONE -> false;
        };
    }

    private static boolean emitsSolidImpactBurst(ProjectileArmorEffect.ImpactVisual visual) {
        return switch (visual) {
            case BULLET, HMG, AUTOCANNON_AP, APFSDS -> true;
            case AUTOCANNON_HE, HEAT_FS, HE, ATGM, NONE -> false;
        };
    }

    private static ProjectileArmorEffect.ImpactVisual resolveImpactVisual(ProjectileImpactContext context) {
        Projectile projectile = context.getProjectile();
        ResolvedProjectileProfile profile = ProjectileProfiles.resolve(projectile);
        if (BvpProjectileEffectDefinition.from(profile) == null
                && BvpProjectileEffectDefinition.impactOnly(profile) == null) {
            return null;
        }

        // KPVT component profiles carry the authoritative lean-impact visual.  Prefer this
        // immutable typed identity over the generic SmallCannonShell shooter fallback, which
        // otherwise has no legacy 20/23/30 mm classifier for a 14.5 mm round.
        if (ProjectileProfiles.isKpvtProjectile(projectile)) {
            ProjectileArmorEffect.ImpactVisual typed = typedImpactVisual(profile);
            if (typed != null) {
                return typed;
            }
        }

        BvpImpactVolumeQuery volumes = context.getVehicleImpactVolumes()
                .get(ArmorImpactHandler.VOLUME_PROVIDER_ID, BvpImpactVolumeQuery.class);
        ProjectileArmorEffect shot;
        if (volumes != null) {
            shot = ArmorShotClassifier.classify(
                    projectile, context.getOwner(), volumes.profile(), context.getHitVec());
            if (shot == null) {
                shot = ArmorShotClassifier.classifyUnmodeledBvpImpact(projectile, context.getOwner());
            }
        } else {
            shot = ArmorShotClassifier.classifyBvpImpact(
                    projectile, context.getOwner(), context.getHitVec());
        }
        return shot == null ? null : shot.impactVisual;
    }

    private static ProjectileArmorEffect.ImpactVisual typedImpactVisual(ResolvedProjectileProfile profile) {
        ResourceLocation id = profile == null ? null : profile.getImpactVisualProfileId();
        if (id == null) {
            return null;
        }
        return switch (id.m_135815_().toLowerCase(Locale.ROOT)) {
            case "autocannon_ap", "ap", "apds" -> ProjectileArmorEffect.ImpactVisual.AUTOCANNON_AP;
            case "autocannon_he", "he" -> ProjectileArmorEffect.ImpactVisual.AUTOCANNON_HE;
            default -> null;
        };
    }

    private static void emitExplosion(Level level, Vec3 hitVec,
                                      ProjectileArmorEffect.ImpactVisual visual,
                                      Projectile source) {
        switch (visual) {
            case AUTOCANNON_AP, APFSDS -> { /* solid-shot impacts have no explosion presentation */ }
            case AUTOCANNON_HE -> {
                if (isKpvtIai(source)) {
                    BvpLeanImpactEffects.spawnMediumExplosion(
                            level, hitVec, source, KPVT_HE_BASELINE_SCALE);
                } else {
                    BvpLeanImpactEffects.spawnMediumExplosion(level, hitVec, source);
                }
            }
            case HEAT_FS, HE -> BvpLeanImpactEffects.spawnMediumExplosion(level, hitVec, source);
            case ATGM -> BvpLeanImpactEffects.spawnLargeExplosion(level, hitVec, source);
            case BULLET, HMG, NONE -> { /* native small-arms decal/spark remains authoritative */ }
        }
    }

    private static ImpactStreakSpec shrapnelSpec(Projectile projectile,
                                                 ProjectileArmorEffect.ImpactVisual visual,
                                                 long seed) {
        if (ProjectileProfiles.isKpvtProjectile(projectile)) {
            // KPVT kinetic components are explicitly spark-free.  Only typed IAI emits the
            // half-sized/half-quantity explosive shrapnel fan.
            if (!isKpvtIai(projectile)) {
                return null;
            }
            ImpactStreakSpec thirtyMillimetre = genericCaliberSpec(30.0D, seed);
            return new ImpactStreakSpec(
                    Math.max(1, (int) Math.ceil(thirtyMillimetre.count() * KPVT_HE_BASELINE_SCALE)),
                    thirtyMillimetre.scale() * KPVT_HE_BASELINE_SCALE,
                    seed);
        }
        double caliber = caliberMm(projectile, visual);
        int rocketCount = typedRocketCount(projectile);
        if (rocketCount > 0) {
            // Typed rocket effect classes replace only the generic caliber count. Their
            // presentation scale, lifetime, speed, damage, and trajectory remain unchanged.
            return new ImpactStreakSpec(rocketCount, shrapnelScale(caliber), seed);
        }
        if (!Double.isFinite(caliber) || caliber <= 12.7D) {
            return null;
        }
        if (caliber <= 23.0D) {
            long choice = mixImpactSeed(seed);
            // Low two bits provide an unbiased quarter gate; the next bit chooses 1 or 2.
            if ((choice & 3L) != 0L) {
                return null;
            }
            int count = 1 + (int) ((choice >>> 2) & 1L);
            return new ImpactStreakSpec(count, SMALL_CALIBER_SCALE, seed);
        }
        return genericCaliberSpec(caliber, seed);
    }

    private static ImpactStreakSpec genericCaliberSpec(double caliber, long seed) {
        double factor = caliberFactor(caliber);
        Random random = new Random(seed);
        int baselineCount = BASE_SHRAPNEL_MIN_COUNT
                + random.nextInt(BASE_SHRAPNEL_MAX_COUNT - BASE_SHRAPNEL_MIN_COUNT + 1);
        int count = Math.max(1, (int) Math.ceil(baselineCount * factor));
        return new ImpactStreakSpec(count, shrapnelScale(caliber), seed);
    }

    private static boolean isKpvtIai(Projectile projectile) {
        var descriptor = ProjectileProfiles.combatDescriptor(projectile);
        return descriptor != null && ProjectileProfiles.isKpvtRoundId(descriptor.getRoundId())
                && descriptor.getRoundId().m_135815_().equalsIgnoreCase("kpvt_iai");
    }

    private static int typedRocketCount(Projectile projectile) {
        BvpProjectileEffectDefinition definition = BvpProjectileEffectDefinition.forEntity(projectile);
        if (definition == null) {
            return 0;
        }
        var descriptor = ProjectileProfiles.combatDescriptor(projectile);
        String munition = descriptor == null || descriptor.getMunitionType() == null
                ? "" : descriptor.getMunitionType().toString().toLowerCase(Locale.ROOT);
        if (munition.endsWith(":atgm") || munition.equals("atgm")) return 24;
        if (munition.endsWith(":rocket") || munition.equals("rocket")) {
            double caliber = descriptor.getCaliberMm() == null ? 0.0D : descriptor.getCaliberMm();
            return Double.isFinite(caliber) && caliber > 80.0D ? 18 : 12;
        }
        return 0;
    }

    private static float shrapnelScale(double caliber) {
        if (!Double.isFinite(caliber)) {
            return BASE_SHRAPNEL_SCALE * (float) MIN_BASELINE_FACTOR;
        }
        return (float) Math.max(
                BASE_SHRAPNEL_SCALE * MIN_BASELINE_FACTOR,
                BASE_SHRAPNEL_SCALE * caliberFactor(caliber));
    }

    private static double caliberFactor(double caliber) {
        double steps = (caliber - BASE_CALIBER_MM) / 10.0D;
        double raw = steps < 0.0D
                ? Math.pow(BELOW_STEP_FACTOR, -steps)
                : Math.pow(ABOVE_STEP_FACTOR, steps);
        return Math.max(MIN_BASELINE_FACTOR, raw);
    }

    private static long mixImpactSeed(long seed) {
        long value = seed ^ (seed >>> 30);
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static double caliberMm(Projectile projectile, ProjectileArmorEffect.ImpactVisual visual) {
        var descriptor = ProjectileProfiles.combatDescriptor(projectile);
        if (descriptor != null && descriptor.getCaliberMm() != null
                && Double.isFinite(descriptor.getCaliberMm()) && descriptor.getCaliberMm() > 0.0D) {
            return descriptor.getCaliberMm();
        }
        return switch (visual) {
            case BULLET -> 7.62D;
            case HMG -> 12.7D;
            case AUTOCANNON_AP, AUTOCANNON_HE -> 30.0D;
            case APFSDS, HEAT_FS, HE, ATGM -> 100.0D;
            case NONE -> 0.0D;
        };
    }

    private static Vec3 impactNormal(ProjectileImpactContext context, Vec3 incomingDirection) {
        if (context.getBlockFace() != null) {
            var normal = context.getBlockFace().m_122436_();
            return new Vec3(normal.m_123341_(), normal.m_123342_(), normal.m_123343_());
        }
        BvpImpactVolumeQuery volumes = context.getVehicleImpactVolumes()
                .get(ArmorImpactHandler.VOLUME_PROVIDER_ID, BvpImpactVolumeQuery.class);
        if (volumes != null) {
            ArmorProfiles.ArmorHit armorHit = volumes.armorHit(volumes.initialTrace());
            if (armorHit != null && armorHit.plate != null && armorHit.localImpact != null) {
                ArmorProfiles.Vec localNormal = armorHit.plate.normalAt(armorHit.localImpact);
                if (armorHit.plate.isBarrelFrame() && volumes.target().barrelFrame() != null) {
                    localNormal = volumes.target().barrelFrame().toHullDirection(localNormal);
                } else if (armorHit.plate.isTurretFrame()) {
                    localNormal = localNormal.rotateY(volumes.target().turretFrameYaw());
                }
                Vec3 origin = volumes.target().armorLocalPointToWorld(ArmorProfiles.Vec.ZERO);
                Vec3 tip = volumes.target().armorLocalPointToWorld(localNormal);
                Vec3 worldNormal = tip.m_82546_(origin);
                if (finite(worldNormal) && worldNormal.m_82556_() > 1.0E-6D) {
                    return worldNormal.m_82541_();
                }
            }
        }
        // Unboxed/entity fallback has no authored plate normal; use the accepted incoming
        // direction as the deterministic outward normal rather than inventing a surface.
        return finite(incomingDirection) && incomingDirection.m_82556_() > 1.0E-6D
                ? incomingDirection.m_82490_(-1.0D).m_82541_()
                : new Vec3(0.0D, 1.0D, 0.0D);
    }

    private static long impactSeed(ProjectileImpactContext context) {
        UUID uuid = context.getProjectile().m_20148_();
        Vec3 hit = context.getHitVec();
        long seed = uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 17);
        seed ^= Double.doubleToLongBits(hit.f_82479_);
        seed = Long.rotateLeft(seed, 21) ^ Double.doubleToLongBits(hit.f_82480_);
        seed = Long.rotateLeft(seed, 21) ^ Double.doubleToLongBits(hit.f_82481_);
        seed ^= context.getBlockFace() == null
                ? 0L
                : context.getBlockFace().ordinal() * 0x9E3779B97F4A7C15L;
        return seed;
    }

    private static boolean finite(Vec3 value) {
        return value != null
                && Double.isFinite(value.f_82479_)
                && Double.isFinite(value.f_82480_)
                && Double.isFinite(value.f_82481_);
    }

    private static boolean isAcceptedCollisionContext(ProjectileImpactContext context) {
        if (context == null || !finite(context.getHitVec()) || context.getKind() == null) {
            return false;
        }
        return switch (context.getKind()) {
            case BLOCK -> context.getBlockPos() != null
                    && context.getBlockState() != null
                    && context.getBlockFace() != null;
            case ENTITY -> context.getTarget() != null
                    && context.getVehicleImpactVolumes()
                    .get(ArmorImpactHandler.VOLUME_PROVIDER_ID, BvpImpactVolumeQuery.class) != null;
        };
    }

    private static boolean claimImpact(Projectile projectile, ProjectileImpactContext.Kind kind,
                                      Vec3 position) {
        if (projectile == null || kind == null || position == null) {
            return false;
        }
        Level level = projectile.m_9236_();
        long gameTime = level.m_46467_();
        synchronized (EMITTED_IMPACTS) {
            ImpactKey previous = EMITTED_IMPACTS.get(projectile);
            if (previous != null && previous.kind == kind && previous.level == level
                    && previous.gameTime == gameTime
                    && previous.position.m_82554_(position) <= SAME_IMPACT_DISTANCE_SQR) {
                return false;
            }
            while (EMITTED_IMPACTS.size() >= MAX_IMPACT_KEYS) {
                Iterator<Projectile> iterator = EMITTED_IMPACTS.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
            EMITTED_IMPACTS.put(projectile, new ImpactKey(kind, level, gameTime, position));
            return true;
        }
    }

    private record ImpactStreakSpec(int count, float scale, long seed) {
    }

    private record ImpactKey(ProjectileImpactContext.Kind kind, Level level,
                             long gameTime, Vec3 position) {
    }
}
