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
import java.util.WeakHashMap;

/**
 * BVP's accepted-impact presentation boundary. The resolver has already committed the parent
 * gameplay result before this provider runs. Client fragment recipes are dispatched separately.
 * This callback owns only
 * particles, lights, audio, and replacement-visual admission; it never inserts a projectile.
 */
public final class BvpImpactPresentationProvider {
    private static final ResourceLocation PROVIDER_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "impact_presentation");
    private static final float KPVT_HE_BASELINE_SCALE = 0.5F;
    private static final String TAP_RICOCHET_SOUND = "berts_vehicle_pack:ricochet_nonpenetration";
    private static final int MAX_IMPACT_KEYS = 256;
    private static final double SAME_IMPACT_DISTANCE_BLOCKS = 0.0625D;
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
        if (definition == null && impactOnly == null && !BvpMaterialImpactSounds.acceptsCombatProfile(profile)) {
            return false;
        }
        ProjectileArmorEffect.ImpactVisual visual = resolveImpactVisual(context);
        if (visual == null || visual == ProjectileArmorEffect.ImpactVisual.NONE) {
            visual = fallbackEffectVisual(definition != null ? definition.impact() : impactOnly);
        }
        if (visual == null || visual == ProjectileArmorEffect.ImpactVisual.NONE) {
            return false;
        }
        boolean explosion = emitsExplosion(visual);
        // SBW may retain a detonated missile for three ticks to finish block destruction.
        // Further block contacts are not additional explosions; preserve that gameplay latch
        // for replacement FX as well instead of flashing again at every contact.
        if (explosion && projectile instanceof com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile shell
                && shell.getExploded()) {
            return true;
        }
        boolean solidImpactBurst = penetration && emitsSolidImpactBurst(visual);
        // Small-caliber impacts still own material audio even when their policy emits no fragments.
        if (!explosion && !nonPenetration && !solidImpactBurst) {
            return false;
        }
        if (!claimImpact(projectile, context.getKind(), context.getHitVec())) {
            return true;
        }

        BvpMaterialImpactSounds.play(context);

        if (EliteDiagnostics.isEnabled(projectile.m_9236_())) {
            EliteDiagnostics.record(projectile, "impact", "bvp_presentation",
                    "profile", ProjectileProfiles.profileId(projectile),
                    "owner", context.getOwner() == null ? null : context.getOwner().m_20148_(),
                    "kind", context.getKind(), "outcome", result.getPresentationOutcome(),
                    "position", context.getHitVec(), "visual", visual,
                    "explosion", explosion, "solid_impact_burst", solidImpactBurst,
                    "nonpenetration_sound", ricochet ? TAP_RICOCHET_SOUND : null);
        }

        if (ricochet) {
            // A true bounce may add its distinct ring to the surface impact.
            ArmorSoundService.play(projectile.m_9236_(), context.getHitVec(), TAP_RICOCHET_SOUND,
                    0.45F, 1.0F);
        }
        com.yourname.berts_vehicle_pack.effects.BvpBallisticImpactEffects.impact(context,
                BvpImpactFragmentCommitter.impactNormal(context, context.getIncomingVelocity()),
                visual == ProjectileArmorEffect.ImpactVisual.HE
                        || visual == ProjectileArmorEffect.ImpactVisual.AUTOCANNON_HE);

        var combat = ProjectileProfiles.combatDescriptor(projectile);
        boolean heavyKinetic = !explosion && combat != null && combat.getCaliberMm() != null
                && combat.getCaliberMm() > 80;
        if (solidImpactBurst || heavyKinetic) {
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
        if (!BvpMaterialImpactSounds.acceptsCombatProfile(profile)) {
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

    private static boolean isKpvtIai(Projectile projectile) {
        var descriptor = ProjectileProfiles.combatDescriptor(projectile);
        return descriptor != null && ProjectileProfiles.isKpvtRoundId(descriptor.getRoundId())
                && descriptor.getRoundId().m_135815_().equalsIgnoreCase("kpvt_iai");
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
                    && previous.position.m_82554_(position) <= SAME_IMPACT_DISTANCE_BLOCKS) {
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

    private record ImpactKey(ProjectileImpactContext.Kind kind, Level level,
                             long gameTime, Vec3 position) {
    }
}
