package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.api.vehicle.deck.DeckCollisions;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckRegistry;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sneaking stops at a deck's edge like at a block's. Vanilla's back-off only sees blocks, so over open water it
 * would refuse every sneaking step on a deck; when a deck supports the player the back-off is computed with the
 * deck columns and the blocks together instead.
 */
@Mixin(Player.class)
public abstract class DeckSneakEdgeMixin {
    @Inject(method = "maybeBackOffFromEdge(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/MoverType;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"), cancellable = true)
    private void sbw$deckEdge(Vec3 movement, MoverType type, CallbackInfoReturnable<Vec3> cir) {
        Player self = (Player) (Object) this;
        if (DeckRegistry.isEmpty(self.level())) return;
        if (self.getAbilities().flying || movement.y > 0.0 || (type != MoverType.SELF && type != MoverType.PLAYER)
                || !self.isShiftKeyDown() || !self.onGround()) return;
        Vec3 backedOff = DeckCollisions.backOffFromEdge(self, movement, self.maxUpStep());
        if (backedOff != null) cir.setReturnValue(backedOff);
    }
}
