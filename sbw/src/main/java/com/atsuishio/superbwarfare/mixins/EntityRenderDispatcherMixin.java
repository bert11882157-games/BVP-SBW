package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.renderer.special.AircraftCollisionDebugRenderer;
import com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules;
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;
import com.atsuishio.superbwarfare.client.renderer.special.OBBRenderer;
import com.atsuishio.superbwarfare.config.server.MiscConfig;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModTags;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(method = "renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;F)V",
            at = @At("RETURN"))
    private static void renderHitbox(PoseStack pMatrixStack, VertexConsumer pBuffer, Entity pEntity, float pPartialTicks, CallbackInfo ci) {
        if (!DebugFeaturePolicy.allowsDebugTools()) return;
        if (pEntity instanceof VehicleEntity vehicle && !vehicle.enableAABB()
                && vehicle.getAircraftCollisionSnapshot(pPartialTicks) == null) {
            OBBRenderer.INSTANCE.render(vehicle, vehicle.getOBBs(), pMatrixStack, pBuffer, 0, 1, 0, 1, pPartialTicks);
        }
    }

    @Inject(method = "renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;F)V",
            at = @At("HEAD"), cancellable = true)
    private static void onPreRenderHitbox(PoseStack pMatrixStack, VertexConsumer pBuffer, Entity pEntity, float pPartialTicks, CallbackInfo ci) {
        if (pEntity.getType().is(ModTags.EntityTypes.MINE) && MiscConfig.MINE_HITBOX_INVISIBLE.get()) {
            ci.cancel();
            return;
        }
        // Leave vanilla F3+B drawing intact; omit only our replacement/extra physical boxes.
        if (!DebugFeaturePolicy.allowsDebugTools()) return;
        if (pEntity instanceof VehicleEntity vehicle) {
            var snapshot = vehicle.getAircraftCollisionSnapshot(pPartialTicks);
            if (snapshot != null) {
                AircraftCollisionDebugRenderer.render(snapshot,
                        vehicle.getLegacyInterpolatedPosition(pPartialTicks), pMatrixStack, pBuffer);
                // The physical footprint excludes wing tips and tail fins; show their real
                // projectile volumes separately instead of hiding them with vanilla's AABB.
                OBBRenderer.INSTANCE.render(vehicle, vehicle.getOBBs(), pMatrixStack, pBuffer,
                        1F, 0.65F, 0F, 1F, pPartialTicks);
                var origin = vehicle.getLegacyInterpolatedPosition(pPartialTicks);
                for (var box : AircraftSurfaceModules.debugHitboxes(vehicle, pPartialTicks)) {
                    var center = box.center;
                    var half = box.extents();
                    OBBRenderer.INSTANCE.renderOBB(pMatrixStack, pBuffer,
                            center.x - origin.x, center.y - origin.y, center.z - origin.z,
                            box.rotation(), half.x, half.y, half.z, 1F, 0.25F, 0.25F, 1F);
                }
                ci.cancel();
            }
        }
    }
}
