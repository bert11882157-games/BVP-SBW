package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResolver;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult;
import com.atsuishio.superbwarfare.api.projectile.impact.VehicleImpactVolumes;
import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;
import com.yourname.berts_vehicle_pack.effects.BvpProjectileEffectDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.Projectile;

public final class ArmorImpactHandler {
    private static final ResourceLocation RESOLVER_ID =
            new ResourceLocation("berts_vehicle_pack", "armor");
    static final ResourceLocation VOLUME_PROVIDER_ID =
            new ResourceLocation("berts_vehicle_pack", "armor_volumes");
    private static final ArmorImpactHandler INSTANCE = new ArmorImpactHandler();

    private final ArmorImpactService impactService = new ArmorImpactService();

    private ArmorImpactHandler() {
    }

    public static void register() {
        VehicleImpactVolumes.register(VOLUME_PROVIDER_ID, BvpImpactVolumeQuery::create);
        ProjectileImpactResolver.register(RESOLVER_ID, INSTANCE::resolve);
    }

    private ProjectileImpactResult resolve(ProjectileImpactContext context) {
        Projectile projectile = context.getProjectile();
        if (projectile.m_9236_().f_46443_) {
            return ProjectileImpactResult.defaultResult();
        }

        if (context.getKind() == ProjectileImpactContext.Kind.BLOCK) {
            ProjectileImpactResult result = resolveBlockImpact(context);
            applyRocketEra(context);
            return result;
        }

        BvpImpactVolumeQuery volumes = context.getVehicleImpactVolumes()
                .get(VOLUME_PROVIDER_ID, BvpImpactVolumeQuery.class);
        if (volumes != null) {
            ProjectileImpactResult result = impactService.handle(context, volumes);
            applyRocketEra(context);
            return result;
        }

        ProjectileArmorEffect shot = ArmorShotClassifier.classifyBvpImpact(
                projectile, context.getOwner(), context.getHitVec());
        if (!ProjectileArmorEffects.hasImpactVisual(shot)) {
            applyRocketEra(context);
            return ProjectileImpactResult.defaultResult();
        }
        ProjectileImpactResult result = ProjectileArmorMutationService.continueImpact(
                true, ProjectileImpactPresentationOutcome.NON_PENETRATION);
        applyRocketEra(context);
        return result;
    }

    private static void applyRocketEra(ProjectileImpactContext context) {
        Projectile projectile = context.getProjectile();
        ProjectileArmorEffect shot = ArmorShotClassifier.classifyBvpImpact(
                projectile, context.getOwner(), context.getHitVec());
        RocketEraImpactService.apply(projectile.m_9236_(), context.getHitVec(), projectile, shot);
    }

    private static ProjectileImpactResult resolveBlockImpact(ProjectileImpactContext context) {
        Projectile projectile = context.getProjectile();
        ProjectileArmorEffect shot = ArmorShotClassifier.classifyBvpImpact(
                projectile, context.getOwner(), context.getHitVec());
        // A typed BVP profile is itself sufficient admission for the accepted block-impact
        // presentation.  PG-9/OG-9 intentionally have no ArmorEffect ImpactVisual enum value;
        // their validated projectile_effect_v1 profile supplies the HE fallback in the
        // presentation provider.  Requiring the enum here silently dropped those real block
        // impacts and left the provider with no chance to spawn their intended shrapnel.
        if (!ProjectileArmorEffects.hasImpactVisual(shot)
                && BvpProjectileEffectDefinition.forEntity(projectile) == null) {
            return ProjectileImpactResult.defaultResult();
        }

        // A typed projectile profile is not proof that classification succeeded.  In
        // particular, an admitted AP/APFSDS shell can have no ArmourEffect when its
        // round/profile mapping is absent or malformed.  Preserve the native safe impact
        // result in that case; never dereference the nullable classification or invent a
        // presentation/damage path.
        if (shot == null) {
            return ProjectileImpactResult.defaultResult();
        }

        if (projectile instanceof CannonShellEntity shell
                && shell.getShellType() == CannonShellEntity.Type.AP
                && shot.impactVisual == ProjectileArmorEffect.ImpactVisual.APFSDS) {
            return ProjectileArmorMutationService.blockApfsdsBlockImpact();
        }
        if (projectile instanceof CannonShellEntity
                && shot.impactVisual == ProjectileArmorEffect.ImpactVisual.APFSDS) {
            return ProjectileArmorMutationService.blockImpact(
                    true, ProjectileImpactPresentationOutcome.NON_PENETRATION);
        }
        return ProjectileArmorMutationService.continueImpact(
                true, ProjectileImpactPresentationOutcome.NON_PENETRATION);
    }
}
