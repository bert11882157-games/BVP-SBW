package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarProjectileLifetimeTest {
    @Test fun `bomb keeps authored flight beyond seven seconds without renewing at a handoff`() {
        val data = CompoundTag()
        fun bomb(tag: CompoundTag, now: Long, age: Int = 0) =
            FarProjectileLifetime.expired(tag, now, "minecraft:overworld", 601, age, 2400)
        assertFalse(bomb(data, 100))
        assertFalse(bomb(data, 240, 140))
        val saved = data.copy()
        assertFalse(bomb(saved, 700, 600))
        assertTrue(bomb(saved, 701))
        assertTrue(bomb(data.copy(), 701))
        assertTrue(FarProjectileLifetime.expired(CompoundTag(), 100, "minecraft:overworld", 601, 0, 2401))
    }

    private fun expired(tag: CompoundTag, now: Long, life: Int = 2400, age: Int = 0, dimension: String = "minecraft:overworld") =
        FarProjectileLifetime.expired(tag, now, dimension, life, age)

    @Test fun `denied and unloaded time expires without any simulated age`() {
        val tag = CompoundTag()
        assertFalse(expired(tag, 100))
        assertFalse(expired(tag, 239))
        assertTrue(expired(tag, 240))
    }

    @Test fun `short native flight gets no additional residency allowance or revival`() {
        val tag = CompoundTag()
        assertFalse(expired(tag, 100, life = 51, age = 10))
        assertFalse(expired(tag, 140, life = 51, age = 10))
        assertTrue(expired(tag, 141, life = 51, age = 10))
        assertTrue(expired(CompoundTag(), 100, life = 41, age = 41))
    }

    @Test fun `handoff and persistence cannot renew expiry`() {
        val original = CompoundTag()
        assertFalse(expired(original, 100, life = 51))
        assertFalse(expired(original, 120, life = 51, age = 20))
        val reloaded = original.copy()
        assertFalse(expired(reloaded, 150, life = 2400, age = 0))
        assertTrue(expired(reloaded, 151, life = 2400, age = 0))
    }

    @Test fun `malformed future reversed and dimension clocks expire safely`() {
        val original = CompoundTag()
        assertFalse(expired(original, 100))
        assertFalse(expired(original, 120))
        assertTrue(expired(original.copy(), 119))
        assertTrue(expired(original.copy(), 130, dimension = "minecraft:the_nether"))
        assertTrue(expired(original.copy(), -1))
        for (key in listOf("Born", "Last", "Deadline", "Version", "Dimension")) {
            val bad = original.copy()
            bad.getCompound("sbwFarProjectileLifetime").remove(key)
            assertTrue(expired(bad, 130), key)
        }
        val bad = CompoundTag().also { it.putString("sbwFarProjectileLifetime", "bad") }
        assertTrue(expired(bad, 100))
        val future = original.copy()
        future.getCompound("sbwFarProjectileLifetime").putLong("Born", 200)
        assertTrue(expired(future, 130))
        val excessive = original.copy()
        excessive.getCompound("sbwFarProjectileLifetime").putLong("Deadline", Long.MAX_VALUE)
        assertTrue(expired(excessive, 130))
    }

    @Test fun `excessive configured lifetime capped and overflow never wraps`() {
        val tag = CompoundTag()
        assertFalse(expired(tag, 100, life = Int.MAX_VALUE))
        assertTrue(expired(tag, 240, life = Int.MAX_VALUE))
        assertTrue(expired(CompoundTag(), Long.MAX_VALUE - 2))
    }

    @Test fun `late registration subtracts existing flight age from seven second cap`() {
        val tag = CompoundTag()
        assertFalse(expired(tag, 100, life = 401, age = 100))
        assertFalse(expired(tag, 139, life = 401, age = 100))
        assertTrue(expired(tag, 140, life = 401, age = 100))
        assertTrue(expired(CompoundTag(), 100, life = 401, age = 140))
    }
}
