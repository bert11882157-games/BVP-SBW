package com.atsuishio.superbwarfare.mixins.tacz;

import com.atsuishio.superbwarfare.api.aircraft.AircraftProjectileDamage;
import com.atsuishio.superbwarfare.api.aircraft.AircraftProjectileDamageOverride;
import com.atsuishio.superbwarfare.api.projectile.ProfiledProjectile;
import com.atsuishio.superbwarfare.api.projectile.FarProjectileAccess;
import com.atsuishio.superbwarfare.api.projectile.NativeVehicleHitFeedback;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResolver;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult;
import com.atsuishio.superbwarfare.compat.tacz.TaczAircraftDamage;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget;
import com.atsuishio.superbwarfare.entity.OBBEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.mixin.OBBHitter;
import com.atsuishio.superbwarfare.tools.OBB;
import com.atsuishio.superbwarfare.world.phys.ProjectileContact;
import com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Aircraft physical-hit policy and opt-in ground armor bridge; native projectile effects remain. */
@Mixin(targets = "com.tacz.guns.entity.EntityKineticBullet", remap = false)
public abstract class EntityKineticBulletMixin extends Projectile
        implements ProfiledProjectile, AircraftProjectileDamageOverride, FarProjectileAccess {
    @Shadow private boolean explosion;
    @Shadow private float explosionDamage;
    @Shadow private float explosionRadius;
    @Unique private ResourceLocation sbw$profileId;
    @Unique private ResolvedProjectileProfile sbw$profile;
    @Unique private ProjectileImpactResult sbw$impact;

    @Shadow public abstract ResourceLocation getAmmoId();

    @Override public double farProjectileExplosionRadius() { return explosion ? explosionRadius : 0.0; }

    protected EntityKineticBulletMixin(EntityType<? extends Projectile> type, Level level) { super(type, level); }

    @Override public float aircraftDirectHitDamage(float targetMaxHealth) {
        return TaczAircraftDamage.damageForAmmo(getAmmoId(), targetMaxHealth);
    }

    @Override public ResourceLocation getProjectileProfileId() { return sbw$profileId; }
    @Override public ResolvedProjectileProfile getResolvedProjectileProfile() { return sbw$profile; }
    @Override public void setProjectileProfileId(ResourceLocation id) {
        sbw$profileId = id;
        sbw$profile = ProjectileProfiles.resolve(id);
    }
    @Override public void copyProjectileProfileFrom(ProfiledProjectile source) {
        sbw$profileId = source.getProjectileProfileId();
        sbw$profile = source.getResolvedProjectileProfile();
    }

    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true, remap = false)
    private void sbw$entityImpact(@Coerce EntityHitResult hit, Vec3 start, Vec3 end, CallbackInfo callback) {
        if (level().isClientSide) return;
        // TacZ's result has no part field. Resolve only the dispatched target, never publish
        // metadata while TacZ is still collecting and sorting broadphase candidates.
        ProjectileCollisionTarget.Hit contact = null;
        if (hit.getEntity() instanceof ProjectileCollisionTarget detailed && detailed.usesDetailedProjectileCollision()) {
            contact = detailed.clipProjectile(start, end);
        } else if (hit.getEntity() instanceof OBBEntity obb && !obb.enableAABB()) {
            contact = ProjectileHitSelection.nearestObb(obb.getOBBs(), start, end, 0.0D);
        }
        OBBHitter.getInstance(this).sbw$setProjectileContact(new ProjectileContact(
                hit.getEntity().getUUID(), level().getGameTime(), contact == null ? OBB.Part.EMPTY : contact.part()));
        if (EliteDiagnostics.isEnabled(level())) {
            EliteDiagnostics.record(hit.getEntity(), "hitreg", "tacz_dispatch", "projectile", getUUID(),
                    "point", hit.getLocation(), "start", start, "end", end,
                    "part", contact == null ? OBB.Part.EMPTY : contact.part(), "profile", sbw$profileId);
        }
        if (sbw$profileId == null && !isRemoved() && hit.getEntity() instanceof VehicleEntity vehicle) {
            NativeVehicleHitFeedback.accepted(this, vehicle);
        }
        if (AircraftProjectileDamage.isAircraft(hit.getEntity())) {
            if (isRemoved()) { callback.cancel(); return; }
            ProjectileImpactResult aircraft = AircraftProjectileDamage.resolve(
                    ProjectileImpactContext.entity(getOwner(), this, hit.getEntity(), hit.getLocation()));
            if (aircraft != null && !aircraft.continuesDefaultPipeline()) {
                if (aircraft.consumesProjectile()) discard();
                callback.cancel();
            }
            // Ordinary unprofiled bullets are included. The shared target receipt suppresses
            // subsequent native direct/blast HP, not TacZ impact effects or projectile disposal.
            return;
        }
        if (sbw$profileId == null) return;
        if (isRemoved()) { callback.cancel(); return; }
        sbw$apply(ProjectileImpactContext.entity(getOwner(), this, hit.getEntity(), hit.getLocation()), callback);
    }

    @Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private void sbw$blockImpact(BlockHitResult hit, Vec3 start, Vec3 end, CallbackInfo callback) {
        OBBHitter.getInstance(this).sbw$setProjectileContact(null);
        if (sbw$profileId == null || level().isClientSide || hit.getType() == HitResult.Type.MISS) return;
        if (isRemoved()) { callback.cancel(); return; }
        sbw$apply(ProjectileImpactContext.block(getOwner(), this, hit.getLocation(), hit.getBlockPos(),
                level().getBlockState(hit.getBlockPos()), hit.getDirection()), callback);
    }

    @Unique private void sbw$apply(ProjectileImpactContext context, CallbackInfo callback) {
        if (sbw$profile == null) {
            EliteDiagnostics.record(this, "tacz_at", "PROFILE_UNAVAILABLE", "profile", sbw$profileId);
            discard();
            callback.cancel();
            return;
        }
        ProjectileImpactResult result = ProjectileImpactResolver.resolve(context);
        EliteDiagnostics.record(this, "tacz_at", "IMPACT_RESOLVED", "kind", context.getKind(),
                "position", context.getHitVec(), "profile", sbw$profileId,
                "disposition", result.getDisposition(), "outcome", result.getPresentationOutcome());
        if (!result.continuesDefaultPipeline()) {
            if (result.consumesProjectile()) discard();
            callback.cancel();
            return;
        }
        sbw$impact = result;
        if (result.suppressesDefaultExplosion()) explosion = false;
        if (result.getResidualExplosionDamage() != null) explosionDamage = result.getResidualExplosionDamage();
        if (result.getResidualExplosionRadius() != null) explosionRadius = result.getResidualExplosionRadius();
    }

    @Inject(method = "getDamage", at = @At("HEAD"), cancellable = true, remap = false)
    private void sbw$residualDamage(Vec3 position, CallbackInfoReturnable<Float> callback) {
        if (sbw$impact != null && sbw$impact.getResidualDamage() != null) {
            callback.setReturnValue(sbw$impact.getResidualDamage());
        }
    }

    @Inject(method = {"onHitEntity", "onHitBlock"}, at = @At("RETURN"), remap = false)
    private void sbw$impactFinished(CallbackInfo callback) { sbw$impact = null; }
}
