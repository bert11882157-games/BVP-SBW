package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

/** Persisted wall-clock backstop. Native simulated-age expiry still owns normal flight lifetime. */
internal object FarProjectileLifetime {
    const val MAX_TICKS = 140
    const val RESIDENCY_GRACE_TICKS = 0
    private const val KEY = "sbwFarProjectileLifetime"

    fun expired(data: CompoundTag, now: Long, dimension: String, nativeLifetime: Int, age: Int): Boolean {
        if (now < 0) return true
        if (!data.contains(KEY)) {
            val nativeRemaining = (nativeLifetime.coerceIn(1, MAX_TICKS).toLong() - age.coerceAtLeast(0)).coerceAtLeast(0)
            if (nativeRemaining == 0L) return true
            // Seven seconds includes residency waits; no extra lifetime on near/far handoff.
            val remaining = (nativeRemaining + RESIDENCY_GRACE_TICKS).coerceAtMost(MAX_TICKS.toLong())
            if (now > Long.MAX_VALUE - remaining) return true
            data.put(KEY, CompoundTag().also {
                it.putInt("Version", 1); it.putLong("Born", now); it.putLong("Last", now)
                it.putLong("Deadline", now + remaining); it.putString("Dimension", dimension)
            })
        }
        if (!data.contains(KEY, Tag.TAG_COMPOUND.toInt())) return true
        val state = data.getCompound(KEY)
        if (!state.contains("Version", Tag.TAG_INT.toInt()) || state.getInt("Version") != 1 ||
            !state.contains("Born", Tag.TAG_LONG.toInt()) || !state.contains("Last", Tag.TAG_LONG.toInt()) ||
            !state.contains("Deadline", Tag.TAG_LONG.toInt()) || !state.contains("Dimension", Tag.TAG_STRING.toInt())) return true
        val born = state.getLong("Born"); val last = state.getLong("Last"); val deadline = state.getLong("Deadline")
        if (born < 0 || last < born || now < last || deadline <= born || deadline - born > MAX_TICKS ||
            state.getString("Dimension") != dimension || now >= deadline) return true
        state.putLong("Last", now)
        return false
    }
}
