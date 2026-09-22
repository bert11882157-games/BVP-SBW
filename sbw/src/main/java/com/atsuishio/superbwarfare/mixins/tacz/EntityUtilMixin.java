package com.atsuishio.superbwarfare.mixins.tacz;

import com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.entity.OBBEntity;
import com.atsuishio.superbwarfare.init.ModParticleTypes;
import com.atsuishio.superbwarfare.init.ModSounds;
import com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection;
import com.atsuishio.superbwarfare.diagnostics.ProjectileHitDiagnostics;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.EntityUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.atsuishio.superbwarfare.tools.ParticleTool.sendParticle;

@Mixin(EntityUtil.class)
public class EntityUtilMixin {

    @Inject(method = "getHitResult(Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lcom/tacz/guns/entity/EntityKineticBullet$EntityResult;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void getHitResult(Projectile bulletEntity, Entity entity, Vec3 startVec, Vec3 endVec, CallbackInfoReturnable<EntityKineticBullet.EntityResult> cir) {
        if (!entity.level().isClientSide && entity instanceof ProjectileCollisionTarget detailed
                && detailed.usesDetailedProjectileCollision()) {
            var hit = ProjectileHitDiagnostics.query(bulletEntity, entity, "tacz_detailed", startVec, endVec,
                    detailed.clipProjectile(startVec, endVec));
            // Empty hull space is a final miss, including for optional handheld AT impacts.
            cir.setReturnValue(hit == null ? null : new EntityKineticBullet.EntityResult(entity, hit.point(), false));
            return;
        }
        if (entity instanceof OBBEntity obbEntity && !obbEntity.enableAABB()) {
            var hit = ProjectileHitDiagnostics.query(bulletEntity, entity, "tacz_obb", startVec, endVec,
                    ProjectileHitSelection.nearestObb(obbEntity.getOBBs(), startVec, endVec, 0.0D));
            // An OBB miss is final; the native AABB is only a broadphase bound for this target.
            cir.setReturnValue(hit == null ? null : new EntityKineticBullet.EntityResult(entity, hit.point(), false));
            if (hit != null) {
                if (ProjectileProfiles.profileId(bulletEntity) == null
                        && bulletEntity.level() instanceof ServerLevel serverLevel && bulletEntity.getDeltaMovement().lengthSqr() > 0.01) {
                    Vec3 hitPos = hit.point();
                    bulletEntity.level().playSound(null, BlockPos.containing(hitPos), ModSounds.HIT.get(), SoundSource.PLAYERS, 1, 1);
                    sendParticle(serverLevel, ModParticleTypes.FIRE_STAR.get(), hitPos.x, hitPos.y, hitPos.z, 2, 0, 0, 0, 0.2, false);
                    sendParticle(serverLevel, ParticleTypes.SMOKE, hitPos.x, hitPos.y, hitPos.z, 2, 0, 0, 0, 0.01, false);
                }
            }
        }
    }
}
