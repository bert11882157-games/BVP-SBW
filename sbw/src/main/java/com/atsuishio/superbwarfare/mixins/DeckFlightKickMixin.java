package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.api.vehicle.deck.DeckCollisions;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckRegistry;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Standing on a carrier deck over open water is standing on ground: the "Flying is not enabled" kick only looks for
 * blocks. A one-block margin covers the client/server phase of a moving deck.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class DeckFlightKickMixin {
    @Inject(method = "noBlocksAround(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void sbw$deckIsGround(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (DeckRegistry.isEmpty(entity.level())) return;
        if (DeckCollisions.intersects(entity.level(),
                entity.getBoundingBox().inflate(1.0).expandTowards(0.0, -0.55, 0.0), entity)) {
            cir.setReturnValue(false);
        }
    }
}
