package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.entity.mixin.OBBHitter;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.AircraftEntityCollisionService;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.atsuishio.superbwarfare.item.gun.launcher.SuperStarShooterItem;
import com.atsuishio.superbwarfare.tools.OBB;
import com.atsuishio.superbwarfare.world.phys.ProjectileContact;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Math;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

@Mixin(Entity.class)
public abstract class EntityMixin implements OBBHitter {

    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"), cancellable = true)
    private void sbw$collideAircraftParts(Vec3 requested, CallbackInfoReturnable<Vec3> cir) {
        Vec3 admitted = AircraftEntityCollisionService.tryCollide((Entity) (Object) this, requested);
        if (admitted != null) cir.setReturnValue(admitted);
    }

    @Shadow
    @Nullable
    public abstract Entity getVehicle();

    @Shadow
    private AABB bb;
    @Shadow
    private float eyeHeight;

    @Shadow
    public abstract Vec3 position();

    @Shadow
    private Vec3 position;
    @Unique
    public OBB.Part sbw$currentHitPart;
    @Unique
    private ProjectileContact sbw$projectileContact;

    @Override
    public OBB.Part sbw$getCurrentHitPart() {
        return this.sbw$currentHitPart;
    }

    @Override
    public void sbw$setCurrentHitPart(OBB.Part part) {
        this.sbw$currentHitPart = part;
        this.sbw$projectileContact = null;
    }

    @Override
    public ProjectileContact sbw$getProjectileContact() {
        return this.sbw$projectileContact;
    }

    @Override
    public void sbw$setProjectileContact(ProjectileContact contact) {
        this.sbw$projectileContact = contact;
        this.sbw$currentHitPart = contact == null ? OBB.Part.EMPTY : contact.part();
    }

    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
    public void turn(double pYRot, double pXRot, CallbackInfo ci) {
        var entity = (Entity) (Object) this;
        if (entity instanceof Player player && player.getMainHandItem().getItem() instanceof GunItem && player.getPose() == Pose.SWIMMING && !player.isSwimming()) {
            ci.cancel();
            float f = (float) pXRot * 0.15F;
            float f1 = (float) pYRot * 0.15F;
            player.setXRot(player.getXRot() + f);
            player.setYRot(player.getYRot() + f1);
            Vec3 forward = new Vec3(player.getLookAngle().x, 0, player.getLookAngle().z).normalize();
            if (player.level().getBlockState(BlockPos.containing(player.getX() + 0.25 * forward.x, player.getY() - 0.1, player.getZ() + 0.25 * forward.z)).canOcclude()) {
                player.setXRot(Mth.clamp(player.getXRot(), -45F, 30F));
            } else {
                player.setXRot(Mth.clamp(player.getXRot(), -45F, 89F));
            }
            player.xRotO += f;
            player.yRotO += f1;
            player.xRotO = Mth.clamp(player.xRotO, -90F, 90F);

            float diffY = Math.clamp(-90f, 90f, Mth.wrapDegrees(player.getYHeadRot() - player.yBodyRot));
            player.setYBodyRot(player.yBodyRot + 0.5f * diffY);

            if (player.getVehicle() != null) {
                player.getVehicle().onPassengerTurned(player);
            }
        }
        if (entity instanceof Player player && player.getMainHandItem().getItem() instanceof SuperStarShooterItem) {
            ci.cancel();
            float f = (float) pXRot * 0.15F;
            float f1 = (float) pYRot * 0.15F;
            player.setXRot(player.getXRot() + f);
            player.setYRot(player.getYRot() + f1);
            player.setXRot(Mth.clamp(player.getXRot(), -90.0F, 90.0F));
            player.xRotO += f;
            player.yRotO += f1;
            player.xRotO = Mth.clamp(player.xRotO, -90.0F, 90.0F);
            if (player.getVehicle() != null) {
                player.getVehicle().onPassengerTurned(player);
            }
            float diffY = Math.clamp(-90f, 90f, Mth.wrapDegrees(player.getYHeadRot() - player.yBodyRot));
            player.setYBodyRot(player.yBodyRot + 0.5f * diffY);
        }
    }

    @Inject(method = "getBoundingBox()Lnet/minecraft/world/phys/AABB;",
            at = @At("RETURN"), cancellable = true)
    private void getBoundingBox(CallbackInfoReturnable<AABB> cir) {
        if (this.getVehicle() instanceof VehicleEntity vehicle) {
            cir.cancel();
            var s = vehicle.getPassengerRenderScale();
            var x = bb.getXsize() - bb.getXsize() * s;
            var y = bb.getYsize() - bb.getYsize() * s;
            var z = bb.getZsize() - bb.getZsize() * s;
            cir.setReturnValue(bb.deflate(x, y, z));
        }
    }

    @Inject(method = "getEyeY()D",
            at = @At("RETURN"), cancellable = true)
    private void getEyeY(CallbackInfoReturnable<Double> cir) {
        if (this.getVehicle() instanceof VehicleEntity vehicle) {
            cir.cancel();
            var s = vehicle.getPassengerRenderScale();
            cir.setReturnValue(this.position.y + (double) this.eyeHeight * s);
        }
    }

    @Inject(method = "getEyeHeight()F",
            at = @At("RETURN"), cancellable = true)
    private void getEyeHeight(CallbackInfoReturnable<Float> cir) {
        if (this.getVehicle() instanceof VehicleEntity vehicle) {
            cir.cancel();
            var s = vehicle.getPassengerRenderScale();
            cir.setReturnValue(this.eyeHeight * s);
        }
    }

    @Inject(method = "getEyeHeight(Lnet/minecraft/world/entity/Pose;Lnet/minecraft/world/entity/EntityDimensions;)F",
            at = @At("RETURN"), cancellable = true)
    private void getEyeHeightDimensions(Pose pPose, EntityDimensions pDimensions, CallbackInfoReturnable<Float> cir) {
        if (this.getVehicle() instanceof VehicleEntity vehicle) {
            cir.cancel();
            var s = vehicle.getPassengerRenderScale();
            cir.setReturnValue(pDimensions.height * 0.85f * s);
        }
    }
}
