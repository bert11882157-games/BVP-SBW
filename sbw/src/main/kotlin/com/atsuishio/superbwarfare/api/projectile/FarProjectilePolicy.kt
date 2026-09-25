package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/** Conservative, bounded coverage of the real swept broadphase and possible explosion queries. */
object FarProjectilePolicy {
    const val MAX_SWEEP_CHUNKS = 256
    /** Sustained high-volume fire keeps tens of thousands of rounds in flight; beyond this new rounds are refused. */
    const val MAX_REGISTERED = 65536
    const val MAX_TICKS_PER_SERVER_TICK = MAX_REGISTERED
    const val MAX_RECOVERY_TICKS_PER_SERVER_TICK = 128
    const val MAX_CHUNK_CHECKS_PER_SERVER_TICK = 8192

    @JvmOverloads fun chunks(bounds: AABB, motion: Vec3, radius: Double, lookAheadTicks: Int,
                            collisionPadding: Double = 9.0): Set<Long>? {
        if (!(bounds.minX.isFinite() && bounds.minY.isFinite() && bounds.minZ.isFinite() &&
                bounds.maxX.isFinite() && bounds.maxY.isFinite() && bounds.maxZ.isFinite() &&
                motion.x.isFinite() && motion.y.isFinite() && motion.z.isFinite() &&
                radius.isFinite() && collisionPadding.isFinite()) || radius < 0 ||
            collisionPadding < 1 || lookAheadTicks !in 0..8) return null
        // ProjectileUtil's vanilla +1 broadphase is expanded by another8 for large vehicle OBBs.
        // CustomExplosion queries entities out to2R+1; include a further block for inclusive edges.
        val padding = maxOf(collisionPadding, 2 * radius + 2)
        val swept = bounds.expandTowards(motion.scale(1.0 + lookAheadTicks)).inflate(padding)
        fun chunk(value: Double): Long = floor(value / 16.0).toLong()
        val minX = chunk(swept.minX - 1e-7); val maxX = chunk(swept.maxX + 1e-7)
        val minZ = chunk(swept.minZ - 1e-7); val maxZ = chunk(swept.maxZ + 1e-7)
        if (minX !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || maxX !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            || minZ !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || maxZ !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        val width = maxX - minX + 1; val depth = maxZ - minZ + 1
        if (width !in 1..MAX_SWEEP_CHUNKS || depth !in 1..MAX_SWEEP_CHUNKS || width * depth > MAX_SWEEP_CHUNKS) return null
        return buildSet {
            for (x in minX..maxX) for (z in minZ..maxZ) add(ChunkPos.asLong(x.toInt(), z.toInt()))
        }
    }
}

/** Records whole ServerLevel entity ticks, including a near-to-far transition in the same tick. */
internal class FarProjectileTickGate(private val bornTick: Long = 0, private val bornAge: Int = 0) {
    private var observed = Long.MIN_VALUE
    private var supplemental = Long.MIN_VALUE
    private var recoveryTick = Long.MIN_VALUE
    private var recoverySteps = 0
    /** Game tick of the last passed lifetime check; the check is idempotent within one tick. */
    var lifetimeValidAt = Long.MIN_VALUE

    /**
     * Registered this tick and not stepped yet (e.g. fired during entity iteration). Its first native
     * tick is the next one, so it has no residency wait to publish.
     */
    fun awaitingFirstTick(tick: Long): Boolean = observed == Long.MIN_VALUE && tick == bornTick

    /** Recover only elapsed flight time, never accelerate an on-time projectile. */
    fun hasRecoveryDebt(tick: Long, age: Int): Boolean =
        tick >= bornTick && age.toLong() - bornAge < tick - bornTick

    fun claimRecovery(tick: Long, age: Int, admitted: Boolean, loaded: Boolean): Boolean {
        if (observed != tick || !admitted || !loaded || !hasRecoveryDebt(tick, age)) return false
        if (recoveryTick != tick) { recoveryTick = tick; recoverySteps = 0 }
        if (recoverySteps >= 3) return false
        recoverySteps++
        supplemental = tick
        return true
    }

    /** Ordinary movement across the native boundary also counts as this tick's real advancement. */
    fun advancedAt(tick: Long): Boolean = observed == tick

    fun ordinary(tick: Long): Boolean {
        if (supplemental == tick) return false
        observed = tick
        return true
    }

    fun claim(tick: Long, nativeTicking: Boolean, accessible: Boolean, admitted: Boolean, loaded: Boolean): Boolean {
        if (nativeTicking || !accessible || !admitted || !loaded || observed == tick) return false
        observed = tick
        supplemental = tick
        return true
    }
}
