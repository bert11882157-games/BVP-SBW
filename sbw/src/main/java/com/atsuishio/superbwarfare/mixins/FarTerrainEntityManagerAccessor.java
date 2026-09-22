package com.atsuishio.superbwarfare.mixins;

import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PersistentEntitySectionManager.class)
public interface FarTerrainEntityManagerAccessor {
    @Accessor("permanentStorage")
    EntityPersistentStorage<?> sbw$getStorage();
}
