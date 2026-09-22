package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve vanilla spawn, profile, motion and removal packets for subscribed distant projectiles. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class FarProjectileTrackingMixin {
    @Shadow @Final Entity entity;

    @Redirect(method = "updatePlayer", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"))
    private int sbw$projectileRange(int entityRange, int viewRange, ServerPlayer player) {
        return FarTerrainServer.projectileTrackingRange(player, entity, Math.min(entityRange, viewRange));
    }
}
