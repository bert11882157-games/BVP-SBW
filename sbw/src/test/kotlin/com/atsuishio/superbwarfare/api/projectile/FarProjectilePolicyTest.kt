package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarProjectilePolicyTest {
    private val box = AABB(7.9, 64.0, 7.9, 8.1, 64.2, 8.1)

    @Test fun `bullet corridor matches its smaller entity query without narrowing explosion coverage`() {
        val motion = Vec3(57.5, 6.0, -2.18)
        val bullet = FarProjectilePolicy.chunks(box, motion, 0.0, 0, 3.0)!!
        val shell = FarProjectilePolicy.chunks(box, motion, 0.0, 0)!!
        assertTrue(shell.containsAll(bullet))
        assertTrue(bullet.size < shell.size)
        val explosiveBullet = FarProjectilePolicy.chunks(box, motion, 12.0, 0, 3.0)!!
        assertEquals(FarProjectilePolicy.chunks(box, motion, 12.0, 0), explosiveBullet)
        assertTrue(FarProjectilePolicy.MAX_TICKS_PER_SERVER_TICK >= FarProjectilePolicy.MAX_REGISTERED,
            "Registration must not admit more on-time shots than can receive one simulation step")
    }

    @Test fun `cold residency delay recovers with bounded collision checked substeps`() {
        val gate = FarProjectileTickGate(100, 0)
        assertTrue(gate.claim(110, false, true, true, true))
        assertFalse(gate.claimRecovery(110, 1, false, true))
        assertFalse(gate.claimRecovery(110, 1, true, false))
        for (age in 1..3) assertTrue(gate.claimRecovery(110, age, true, true))
        assertFalse(gate.claimRecovery(110, 4, true, true), "At most three recovery steps per server tick")
        assertFalse(gate.ordinary(110), "Recovery cannot also receive a native tick")
        assertTrue(gate.claim(111, false, true, true, true))
        assertTrue(gate.claimRecovery(111, 5, true, true))
        assertFalse(gate.claimRecovery(111, 11, true, true), "Never outrun elapsed flight time")
    }

    @Test fun `normal flight and restored old age never acquire artificial recovery debt`() {
        val gate = FarProjectileTickGate(100, 200)
        assertFalse(gate.hasRecoveryDebt(99, 200))
        assertFalse(gate.hasRecoveryDebt(100, 200))
        assertTrue(gate.ordinary(101))
        assertFalse(gate.claimRecovery(101, 201, true, true))
        assertTrue(gate.hasRecoveryDebt(110, 202))
    }

    @Test fun `resumed native flight repays only its existing residency delay`() {
        val gate = FarProjectileTickGate(100, 0)
        assertTrue(gate.ordinary(105))
        assertTrue(gate.advancedAt(105))
        assertTrue(gate.hasRecoveryDebt(105, 2))
        for (age in 2..4) assertTrue(gate.claimRecovery(105, age, true, true))
        assertFalse(gate.claimRecovery(105, 5, true, true))
        assertFalse(gate.ordinary(105))
        assertTrue(gate.ordinary(106))
        assertFalse(gate.hasRecoveryDebt(106, 6))
    }

    @Test fun `fast sweep includes all intermediate terrain and broadphase neighbors`() {
        val chunks = FarProjectilePolicy.chunks(box, Vec3(160.0, 0.0, 0.0), 0.0, 0)!!
        for (x in -1..11) for (z in -1..1) assertTrue(ChunkPos.asLong(x, z) in chunks)
        assertFalse(setOf(ChunkPos.asLong(0, 0), ChunkPos.asLong(10, 0)).containsAll(chunks))
    }

    @Test fun `negative diagonal sweep includes corners of queried broadphase`() {
        val chunks = FarProjectilePolicy.chunks(box, Vec3(-64.0, -2.0, -64.0), 0.0, 0)!!
        assertTrue(ChunkPos.asLong(-5, 1) in chunks)
        assertTrue(ChunkPos.asLong(1, -5) in chunks)
        assertTrue(ChunkPos.asLong(-2, -2) in chunks)
    }

    @Test fun `explosion and cluster lookahead expand required terrain instead of loading it`() {
        val ordinary = FarProjectilePolicy.chunks(box, Vec3(20.0, 0.0, 0.0), 0.0, 0)!!
        val explosive = FarProjectilePolicy.chunks(box, Vec3(20.0, 0.0, 0.0), 12.0, 0)!!
        val cluster = FarProjectilePolicy.chunks(box, Vec3(20.0, 0.0, 0.0), 0.0, 8)!!
        assertTrue(explosive.containsAll(ordinary))
        assertTrue(ChunkPos.asLong(0, 2) in explosive)
        assertTrue(ChunkPos.asLong(12, 0) in cluster)
        assertFalse(ordinary.containsAll(cluster))
    }

    @Test fun `invalid and excessive envelopes fail closed before enumeration`() {
        assertNull(FarProjectilePolicy.chunks(box, Vec3(Double.NaN, 0.0, 0.0), 0.0, 0))
        assertNull(FarProjectilePolicy.chunks(box, Vec3.ZERO, -1.0, 0))
        assertNull(FarProjectilePolicy.chunks(box, Vec3.ZERO, Double.POSITIVE_INFINITY, 0))
        assertNull(FarProjectilePolicy.chunks(box, Vec3(1e12, 0.0, 1e12), 0.0, 0))
        assertNull(FarProjectilePolicy.chunks(box, Vec3.ZERO, 4096.0, 0))
        assertNull(FarProjectilePolicy.chunks(box, Vec3.ZERO, 0.0, 9))
    }

    @Test fun `round fired during entity iteration is not paused before its first native tick`() {
        val gate = FarProjectileTickGate(200, 0)
        assertTrue(gate.awaitingFirstTick(200), "Registered this tick and not yet stepped")
        assertFalse(gate.awaitingFirstTick(201), "A later skipped tick is a real wait")
        assertTrue(gate.ordinary(201))
        assertFalse(gate.awaitingFirstTick(201))
        val claimed = FarProjectileTickGate(300, 0)
        assertTrue(claimed.claim(300, false, true, true, true))
        assertFalse(claimed.awaitingFirstTick(300), "A far step in the spawn tick publishes normally")
    }

    @Test fun `registry holds tens of thousands of rounds and always offers each one a step`() {
        assertTrue(FarProjectilePolicy.MAX_REGISTERED >= 32768)
        assertTrue(FarProjectilePolicy.MAX_TICKS_PER_SERVER_TICK >= FarProjectilePolicy.MAX_REGISTERED)
    }

    @Test fun `ordinary movement into full corridor is not ticked twice`() {
        val gate = FarProjectileTickGate()
        assertTrue(gate.ordinary(40))
        assertFalse(gate.claim(40, false, true, true, true))
        assertTrue(gate.claim(41, false, true, true, true))
        assertFalse(gate.claim(41, false, true, true, true))
        assertFalse(gate.ordinary(41))
        assertTrue(gate.ordinary(42))
    }

    @Test fun `missing admission terrain or entities and native ticking never claim far step`() {
        val gate = FarProjectileTickGate()
        assertFalse(gate.claim(1, true, true, true, true))
        assertFalse(gate.claim(1, false, false, true, true))
        assertFalse(gate.claim(1, false, true, false, true))
        assertFalse(gate.claim(1, false, true, true, false))
        // A denied preflight does not consume the step when its real prerequisites become ready.
        assertTrue(gate.claim(1, false, true, true, true))
    }

    @Test fun `prediction remains active across ordinary far handoff but pauses on denied or skipped tick`() {
        val gate = FarProjectileTickGate()
        assertFalse(gate.advancedAt(40))
        assertTrue(gate.ordinary(40))
        assertFalse(gate.claim(40, false, true, true, true))
        assertTrue(gate.advancedAt(40), "Ordinary step crossing out of native range must not cause a false pause")
        assertTrue(gate.claim(41, false, true, true, true))
        assertTrue(gate.advancedAt(41))
        assertFalse(gate.claim(42, false, true, false, true))
        assertFalse(gate.advancedAt(42), "Denied corridor cannot keep predicting from a prior tick")
        assertFalse(gate.advancedAt(43), "A work-budget skip has no advancement claim")
        assertFalse(gate.claim(44, false, true, true, false))
        assertFalse(gate.advancedAt(44), "Waiting for resident terrain must pause")
        assertTrue(gate.ordinary(45))
        assertTrue(gate.advancedAt(45), "Native reentry resumes prediction")
    }
}
