package com.atsuishio.superbwarfare.mixins;

import net.minecraft.server.level.ServerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface FarProjectileTrackedEntityAccessor {
    @Accessor("serverEntity") ServerEntity sbw$serverEntity();
    @Accessor("seenBy") java.util.Set<net.minecraft.server.network.ServerPlayerConnection> sbw$seenBy();
}
