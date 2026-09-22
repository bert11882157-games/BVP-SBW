package com.atsuishio.superbwarfare.api.vehicle.render

import net.minecraft.core.BlockPos
import net.minecraft.world.level.chunk.DataLayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FarTerrainLightTest {
    @Test fun `missing open sky is bright but explicit covered darkness remains dark`() {
        val roof = DataLayer()
        assertEquals(15, FarTerrainLight.sky(true, mapOf(4 to roof), BlockPos(-1, 90, -1)))
        assertEquals(0, FarTerrainLight.sky(true, mapOf(4 to roof), BlockPos(-1, 70, -1)))
        assertEquals(0, FarTerrainLight.sky(false, emptyMap(), BlockPos(0, 500, 0)))
    }

    @Test fun `sparse sections inherit the nearest upper column without aliasing negative positions`() {
        val upper = DataLayer().also { it.set(15, 0, 15, 7) }
        val distant = DataLayer().also { it.set(15, 0, 15, 12) }
        val layers = mapOf(6 to upper, 10 to distant)
        assertEquals(7, FarTerrainLight.sky(true, layers, BlockPos(-1, 70, -17)))
        assertEquals(12, FarTerrainLight.sky(true, layers, BlockPos(-1, 130, -17)))
    }
}
