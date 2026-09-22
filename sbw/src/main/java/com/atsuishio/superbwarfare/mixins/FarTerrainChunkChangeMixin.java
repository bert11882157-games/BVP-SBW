package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class FarTerrainChunkChangeMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void sbw$terrainChanged(BlockPos pos, BlockState state, boolean moving,
                                   CallbackInfoReturnable<BlockState> result) {
        if (result.getReturnValue() != null) {
            LevelChunk chunk = (LevelChunk) (Object) this;
            FarTerrainServer.changed(chunk.getLevel(), chunk.getPos().toLong());
        }
    }
}
