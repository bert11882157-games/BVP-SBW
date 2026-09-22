package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.gun.ProjectileInfo
import kotlinx.serialization.json.Json
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectileCalibersTest {
    @Test fun `native caliber survives definition and fired projectile persistence`() {
        val definition = Json.decodeFromString(ProjectileInfo.serializer(),
            """{"Type":"superbwarfare:projectile","CaliberMm":7.62}""")
        val restored = Json.decodeFromString(ProjectileInfo.serializer(),
            Json.encodeToString(ProjectileInfo.serializer(), definition))
        val fired = CompoundTag()
        ProjectileCalibers.capture(fired, restored.caliberMm)
        definition.caliberMm = 125.0
        assertEquals(7.62, ProjectileCalibers.authored(fired.copy()))
        assertEquals("superbwarfare:projectile", restored.itemId)
    }

    @Test fun `unknown string definitions stay unknown and invalid metadata fails closed`() {
        val legacy = ProjectileInfo.ProjectileInfoInstanceBuilder.fromString("superbwarfare:cannon_shell")
        assertNull(legacy.caliberMm)
        val fired = CompoundTag()
        ProjectileCalibers.capture(fired, 125.0)
        ProjectileCalibers.capture(fired, legacy.caliberMm)
        assertNull(ProjectileCalibers.authored(fired))
        for (invalid in listOf(0.0, -1.0, 1001.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            ProjectileCalibers.capture(fired, invalid)
            assertTrue(ProjectileCalibers.authored(fired.copy())!!.isNaN())
        }
    }
}
