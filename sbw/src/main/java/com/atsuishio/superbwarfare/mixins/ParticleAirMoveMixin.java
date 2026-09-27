package com.atsuishio.superbwarfare.mixins;

import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Particles with physics run the vanilla block-collision sweep every tick. Long-lived smoke (rocket and missile trails,
 * wreck smoke) spends most of its life in open air, where the sweep always comes back empty, and in a battle there
 * are over a thousand of them (7% of the render thread in the r28 war). When every chunk section the swept box can
 * touch holds only air, no block can collide, so the move is returned as is. The check covers the same one-block
 * margin the vanilla sweep uses for oversized shapes (fences, walls); anything else takes the vanilla path.
 */
@Mixin(Particle.class)
public abstract class ParticleAirMoveMixin {
    @Redirect(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 sbw$collideUnlessOpenAir(Entity entity, Vec3 movement, AABB box, Level level, List<VoxelShape> hits) {
        if (entity == null && hits.isEmpty() && sbw$onlyAir(level, box.expandTowards(movement))) return movement;
        return Entity.collideBoundingBox(entity, movement, box, level, hits);
    }

    private static boolean sbw$onlyAir(Level level, AABB swept) {
        int x0 = (int) Math.floor(swept.minX - 1.0) >> 4, x1 = (int) Math.floor(swept.maxX + 1.0) >> 4;
        int y0 = (int) Math.floor(swept.minY - 1.0) >> 4, y1 = (int) Math.floor(swept.maxY + 1.0) >> 4;
        int z0 = (int) Math.floor(swept.minZ - 1.0) >> 4, z1 = (int) Math.floor(swept.maxZ + 1.0) >> 4;
        if (x1 - x0 > 2 || y1 - y0 > 2 || z1 - z0 > 2) return false;
        int sections = level.getSectionsCount();
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                // An unloaded chunk has no collisions in the vanilla sweep either.
                ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (int sy = y0; sy <= y1; sy++) {
                    int index = level.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= sections) continue;
                    LevelChunkSection section = chunk.getSection(index);
                    if (!section.hasOnlyAir()) return false;
                }
            }
        }
        return true;
    }
}
