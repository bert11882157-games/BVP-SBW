package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResolver;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult;
import com.atsuishio.superbwarfare.api.projectile.impact.VehicleImpactVolumes;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;
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
        BvpDroneRpgImpactBridge.register();
        VehicleImpactVolumes.register(VOLUME_PROVIDER_ID, BvpImpactVolumeQuery::create);
        ProjectileImpactResolver.register(RESOLVER_ID, INSTANCE::resolve);
    }

    private ProjectileImpactResult resolve(ProjectileImpactContext context) {
        ProjectileImpactResult result = resolveArmor(context);
        BvpImpactFragmentCommitter.commit(context, result);
        return result;
    }

    private ProjectileImpactResult resolveArmor(ProjectileImpactContext context) {
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
        // Classification and required combat metadata admit gameplay. Optional effect data
        // cannot veto a classified pack round. Legacy visual-only classification must not
        // conceal a declared profile whose required combat metadata failed validation.
        BvpBlockImpactAdmission.CombatState combat = BvpBlockImpactAdmission.combatState(
                ProjectileProfiles.profileId(projectile), ProjectileProfiles.resolve(projectile));
        if (!BvpBlockImpactAdmission.admits(shot != null,
                ProjectileArmorEffects.hasImpactVisual(shot), combat)) {
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
