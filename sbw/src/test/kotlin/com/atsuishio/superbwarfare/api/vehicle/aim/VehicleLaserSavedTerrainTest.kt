package com.atsuishio.superbwarfare.api.vehicle.aim

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class VehicleLaserSavedTerrainTest {
    companion object {
        @JvmStatic @BeforeAll fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private fun saved(block: String): CompoundTag = CompoundTag().apply {
        putInt("xPos", 200); putInt("zPos", 0); putString("Status", "minecraft:full")
        put("sections", ListTag().apply {
            add(CompoundTag().apply {
                putByte("Y", 4)
                put("block_states", CompoundTag().apply {
                    put("palette", ListTag().apply {
                        add(CompoundTag().apply { putString("Name", block) })
                    })
                })
            })
        })
    }

    @Test fun `saved terrain returns a block 3200 metres away without a live chunk`() {
        val view = VehicleLaserSavedTerrain.decode(saved("minecraft:stone"), ChunkPos(200, 0), -64, 384)!!
        val start = Vec3(0.5, 64.25, 0.5)
        val cells = (3200..3215).map { BlockPos(it, 64, 0) }
        val hit = VehicleLaserSavedTerrain.firstHit(view, cells, start, Vec3(4096.5, 64.25, 0.5))!!
        assertEquals(3200.0, hit.x, 1e-6)
        assertEquals(3199.5, start.distanceTo(hit), 1e-6)
    }

    @Test fun `saved slab uses collision shape instead of a whole cube`() {
        val view = VehicleLaserSavedTerrain.decode(saved("minecraft:stone_slab"), ChunkPos(200, 0), -64, 384)!!
        val cells = (3200..3215).map { BlockPos(it, 64, 0) }
        assertNotNull(VehicleLaserSavedTerrain.firstHit(view, cells,
            Vec3(0.5, 64.25, 0.5), Vec3(4096.5, 64.25, 0.5)))
        assertNull(VehicleLaserSavedTerrain.firstHit(view, cells,
            Vec3(0.5, 64.75, 0.5), Vec3(4096.5, 64.75, 0.5)))
    }

    @Test fun `wrong chunk and incomplete generation never count as empty terrain`() {
        assertNull(VehicleLaserSavedTerrain.decode(saved("minecraft:stone"), ChunkPos(201, 0), -64, 384))
        val partial = saved("minecraft:stone").apply { putString("Status", "minecraft:noise") }
        assertNull(VehicleLaserSavedTerrain.decode(partial, ChunkPos(200, 0), -64, 384))
        assertNull(VehicleLaserSavedTerrain.decode(CompoundTag(), ChunkPos(200, 0), -64, 384))
    }
}
