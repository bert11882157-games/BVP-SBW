package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.api.vehicle.deck.DeckCollisions;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Deck columns join the obstacle list of every movement collision: vanilla Entity.collide (players, mobs, items,
 * step-up probes), SBW's aircraft entity-collision path and VehicleCollisionEnvironmentService (ground vehicles) all
 * resolve through this one static method, so a carrier deck stops, supports and steps them exactly like blocks.
 */
@Mixin(Entity.class)
public abstract class DeckCollisionMixin {
    private static final ThreadLocal<Boolean> SBW$DECK_ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"), cancellable = true)
    private static void sbw$deckColumns(@Nullable Entity entity, Vec3 movement, AABB box, Level level,
                                        List<VoxelShape> hits, CallbackInfoReturnable<Vec3> cir) {
        if (DeckRegistry.isEmpty(level) || SBW$DECK_ACTIVE.get()) return;
        List<VoxelShape> deck = DeckCollisions.shapes(level, box.expandTowards(movement), entity);
        if (deck.isEmpty()) return;
        List<VoxelShape> all = new ArrayList<>(hits.size() + deck.size());
        all.addAll(hits);
        all.addAll(deck);
        SBW$DECK_ACTIVE.set(Boolean.TRUE);
        try {
            cir.setReturnValue(Entity.collideBoundingBox(entity, movement, box, level, all));
        } finally {
            SBW$DECK_ACTIVE.set(Boolean.FALSE);
        }
    }
}
