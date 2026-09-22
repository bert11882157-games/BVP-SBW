package com.atsuishio.superbwarfare.api.vehicle.render

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.floor

class FarTerrainVisibilityTest {
    @Test fun `all sampled rays including interior cover are meshed`() {
        for (camera in listOf(Vec3(0.0, 75.0, 0.0), Vec3(-97.1, 250.0, -63.5),
            Vec3(128.0, -30.0, 400.0), Vec3(0.0, 1000.0, 0.0))) {
            val bounds = AABB(20.0, 64.0, 400.0, 45.0, 72.0, 440.0)
            val envelope = bounds.inflate(FarTerrainPolicy.RENDER_PADDING_BLOCKS)
            val needed = FarTerrainVisibility.sections(camera, bounds, -4, 20)
            for (ix in 0..4) for (iy in 0..4) for (iz in 0..4) {
                val end = Vec3(envelope.minX + envelope.xsize * ix / 4,
                    envelope.minY + envelope.ysize * iy / 4, envelope.minZ + envelope.zsize * iz / 4)
                for (step in 0..200) {
                    val point = camera.lerp(end, step / 200.0)
                    val y = floor(point.y / 16).toInt()
                    if (y !in -4..19) continue
                    val section = FarTerrainVisibility.Section(FarTerrainPolicy.key(
                        FarTerrainPolicy.chunk(point.x), FarTerrainPolicy.chunk(point.z)), y)
                    assertTrue(section in needed, "Missing sightline section $section from $camera")
                }
            }
        }
    }

    @Test fun `underground columns do not block ground vehicle readiness`() {
        val needed = FarTerrainVisibility.sections(Vec3(0.5, 70.0, 0.5),
            AABB(0.0, 64.0, 500.0, 5.0, 70.0, 506.0), -4, 20)
        assertTrue(needed.isNotEmpty())
        assertTrue(needed.all { it.y in 2..5 })
        assertTrue(needed.size < needed.map { it.chunk }.distinct().size * 5)
    }

    @Test fun `camera motion changes required cover and keeps wall section required`() {
        val box = AABB(0.0, 64.0, 500.0, 5.0, 70.0, 506.0)
        val before = FarTerrainVisibility.sections(Vec3(0.5, 70.0, 0.5), box, -4, 20)
        val after = FarTerrainVisibility.sections(Vec3(64.5, 70.0, 0.5), box, -4, 20)
        assertNotEquals(before, after)
        assertTrue(FarTerrainVisibility.Section(FarTerrainPolicy.key(2, 15), 4) in after)
        assertFalse(after.all { it in before }, "New camera coverage cannot reuse an unrelated ready proof")
    }
}
