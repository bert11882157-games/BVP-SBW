package com.atsuishio.superbwarfare.mixins;

import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityStorage.class)
public interface FarTerrainEntityStorageAccessor {
    @Accessor("worker")
    IOWorker sbw$getWorker();
}
