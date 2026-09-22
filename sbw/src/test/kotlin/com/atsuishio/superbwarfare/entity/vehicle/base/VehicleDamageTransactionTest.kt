package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRejection
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageResult
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleDamageTransactionTest {
    private data class Source(val name: String)
    private data class Destruction(val name: String)
    private data class Commit(
        val source: Source,
        val amount: Float,
        val policy: ResolvedVehicleModulePolicy,
        val feedback: Boolean,
    )

    private class Access : VehicleDamageAccess<Source, Destruction> {
        override var isServerAuthority = true
        override var isWreck = false
        override var isAlive = true
        override var health = 100f
        override var maxHealth = 100f
        val events = mutableListOf<String>()
        val commits = mutableListOf<Commit>()
        val destroyed = mutableListOf<Destruction>()
        var admission = true
        var modifier: (Float) -> Float = { it * 0.5f }
        var vanillaResult = false
        var onAdmission: () -> Unit = {}
        var onDebug: () -> Unit = {}
        var onCommit: (Float) -> Unit = { health -= it }
        var onDestroy: () -> Unit = { isWreck = true }

        override fun acceptsSource(source: Source): Boolean {
            events += "admit:${source.name}"
            onAdmission()
            return admission
        }

        override fun reportDebug(source: Source, amount: Float) {
            events += "debug:${source.name}:$amount"
            onDebug()
        }

        override fun computeAfterModifiers(source: Source, amount: Float): Float {
            events += "modify:${source.name}:$amount"
            return modifier(amount)
        }

        override fun commit(
            source: Source,
            amount: Float,
            modulePolicy: ResolvedVehicleModulePolicy,
            feedback: Boolean,
        ) {
            events += "commit:${source.name}:$amount"
            commits += Commit(source, amount, modulePolicy, feedback)
            onCommit(amount)
        }

        override fun invokeVanillaHurt(source: Source, amount: Float): Boolean {
            events += "vanilla:${source.name}:$amount"
            return vanillaResult
        }

        override fun defaultDestructionContext(): Destruction {
            events += "default-destruction"
            return Destruction("default")
        }

        override fun destroy(context: Destruction) {
            events += "destroy:${context.name}"
            destroyed += context
            onDestroy()
        }

        fun resolved(
            amount: Float = 10f,
            lethal: Boolean = false,
            policy: ResolvedVehicleModulePolicy = ResolvedVehicleModulePolicy.APPLY_NATIVE,
            feedback: Boolean = true,
            context: Destruction? = null,
            source: Source = Source("resolved"),
        ): ResolvedVehicleDamageResult = VehicleDamageTransaction(this).applyResolved(
            source, amount, lethal, policy, feedback, context,
        )
    }

    @Test
    fun `client rejection wins before malformed amount or source inspection`() {
        val access = Access().apply { isServerAuthority = false; isWreck = true }
        assertEquals(
            ResolvedVehicleDamageResult.rejected(ResolvedVehicleDamageRejection.NOT_SERVER_AUTHORITY),
            access.resolved(Float.NaN),
        )
        assertTrue(access.events.isEmpty())
        assertEquals(100f, access.health)
    }

    @Test
    fun `invalid resolved amounts reject before state mutation including signed zero`() {
        for (amount in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f, -0f)) {
            val access = Access().apply { isWreck = true }
            assertEquals(ResolvedVehicleDamageRejection.INVALID_AMOUNT, access.resolved(amount).rejection)
            assertTrue(access.events.isEmpty(), "Amount: $amount")
            assertEquals(100f, access.health)
        }
    }

    @Test
    fun `wreck zero health negative health and removed vehicles reject without source effects`() {
        for (state in listOf<(Access) -> Unit>(
            { it.isWreck = true }, { it.health = 0f }, { it.health = -1f }, { it.isAlive = false },
        )) {
            val access = Access().also(state)
            assertEquals(ResolvedVehicleDamageRejection.ALREADY_DESTROYED, access.resolved().rejection)
            assertTrue(access.events.isEmpty())
        }
    }

    @Test
    fun `source rejection has no debug commit destruction or vanilla side effects`() {
        val access = Access().apply { admission = false }
        assertEquals(ResolvedVehicleDamageRejection.SOURCE_REJECTED, access.resolved().rejection)
        assertEquals(listOf("admit:resolved"), access.events)
        assertTrue(access.commits.isEmpty())
        assertTrue(access.destroyed.isEmpty())
        assertEquals(100f, access.health)
    }

    @Test
    fun `resolved damage preserves source module policy and feedback without repeating modifiers`() {
        for (policy in ResolvedVehicleModulePolicy.entries) {
            for (feedback in listOf(false, true)) {
                val source = Source("armor")
                val access = Access().apply { modifier = { fail("Already-resolved damage must bypass modifiers") } }
                val result = access.resolved(17.25f, policy = policy, feedback = feedback, source = source)
                assertEquals(ResolvedVehicleDamageResult(true, 17.25f, false, ResolvedVehicleDamageRejection.NONE), result)
                assertEquals(listOf(Commit(source, 17.25f, policy, feedback)), access.commits)
                assertSame(source, access.commits.single().source)
                assertEquals(listOf("admit:armor", "debug:armor:17.25", "commit:armor:17.25"), access.events)
            }
        }
    }

    @Test
    fun `lethal damage retains the current health floor and maximum plus one cap`() {
        data class Case(val health: Float, val amount: Float, val lethal: Boolean, val expected: Float)
        for ((health, amount, lethal, expected) in listOf(
            Case(80f, 1f, true, 80f),
            Case(80f, 90f, true, 90f),
            Case(140f, 1f, true, 101f),
            Case(80f, Float.MAX_VALUE, false, 101f),
            Case(80f, 10f, false, 10f),
        )) {
            val access = Access().apply { this.health = health }
            access.resolved(amount, lethal = lethal)
            assertEquals(expected, access.commits.single().amount)
        }
    }

    @Test
    fun `diagnostic callback changes are observed before lethal clamp and applied damage sampling`() {
        val access = Access().apply {
            onAdmission = { health = 80f }
            onDebug = { health = 60f; maxHealth = 40f }
        }
        val result = access.resolved(1f, lethal = true)
        assertEquals(41f, access.commits.single().amount)
        assertEquals(41f, result.appliedDamage)
        assertEquals(19f, access.health)
        assertFalse(result.destroyed)
    }

    @Test
    fun `applied amount follows accepted hull state rather than the requested amount`() {
        val access = Access().apply { onCommit = { health -= 3f } }
        val result = access.resolved(20f)
        assertEquals(20f, access.commits.single().amount)
        assertEquals(3f, result.appliedDamage)
        assertEquals(97f, access.health)
    }

    @Test
    fun `health restoring callbacks produce a valid accepted zero damage result`() {
        val access = Access().apply { onCommit = { health += 3f } }
        val result = access.resolved()
        assertTrue(result.accepted)
        assertEquals(0f, result.appliedDamage)
        assertFalse(result.destroyed)
    }

    @Test
    fun `supplied destruction context survives exactly and default context is lazy`() {
        val supplied = Destruction("caller")
        val access = Access()
        val result = access.resolved(100f, context = supplied)
        assertTrue(result.destroyed)
        assertSame(supplied, access.destroyed.single())
        assertFalse(access.events.contains("default-destruction"))
        assertEquals("destroy:caller", access.events.last())

        val surviving = Access()
        surviving.resolved(1f)
        assertTrue(surviving.destroyed.isEmpty())
        assertFalse(surviving.events.contains("default-destruction"))
    }

    @Test
    fun `default context is resolved only after hull commit and subsequent damage rejects`() {
        val access = Access()
        val result = access.resolved(100f)
        assertTrue(result.destroyed)
        assertEquals(listOf(
            "admit:resolved", "debug:resolved:100.0", "commit:resolved:100.0",
            "default-destruction", "destroy:default",
        ), access.events)
        val before = access.events.toList()
        assertEquals(ResolvedVehicleDamageRejection.ALREADY_DESTROYED, access.resolved().rejection)
        assertEquals(before, access.events)
        assertEquals(1, access.destroyed.size)
    }

    @Test
    fun `commit callback that already wrecked the vehicle does not request destruction twice`() {
        val access = Access().apply { onCommit = { health = 0f; isWreck = true } }
        val result = access.resolved()
        assertEquals(100f, result.appliedDamage)
        assertFalse(result.destroyed)
        assertTrue(access.destroyed.isEmpty())
    }

    @Test
    fun `raw hurt retains source modifier commit vanilla ordering and vanilla return value`() {
        for (vanilla in listOf(false, true)) {
            val access = Access().apply { vanillaResult = vanilla }
            assertEquals(vanilla, VehicleDamageTransaction(access).hurt(Source("raw"), 20f))
            assertEquals(listOf(
                "admit:raw", "debug:raw:20.0", "modify:raw:20.0", "commit:raw:10.0", "vanilla:raw:10.0",
            ), access.events)
            assertEquals(ResolvedVehicleModulePolicy.APPLY_NATIVE, access.commits.single().policy)
            assertTrue(access.commits.single().feedback)
            assertTrue(access.destroyed.isEmpty())
        }
    }

    @Test
    fun `raw rejection returns false without consulting modifiers or vanilla`() {
        val access = Access().apply { admission = false }
        assertFalse(VehicleDamageTransaction(access).hurt(Source("raw"), 10f))
        assertEquals(listOf("admit:raw"), access.events)
    }

    @Test
    fun `legacy raw path does not acquire resolved damage admission or eager destruction`() {
        val access = Access().apply { isServerAuthority = false; isWreck = true; health = 0f }
        VehicleDamageTransaction(access).hurt(Source("raw"), 0f)
        assertEquals(1, access.commits.size)
        assertEquals(0f, access.commits.single().amount)
        assertEquals("vanilla:raw:0.0", access.events.last())
        assertTrue(access.destroyed.isEmpty())
    }

    @Test
    fun `exception before commit leaves health untouched and does not continue the transaction`() {
        for (phase in listOf("admission", "debug", "modifier")) {
            val failure = IllegalStateException(phase)
            val access = Access().apply {
                when (phase) {
                    "admission" -> onAdmission = { throw failure }
                    "debug" -> onDebug = { throw failure }
                    "modifier" -> modifier = { throw failure }
                }
            }
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                VehicleDamageTransaction(access).hurt(Source("raw"), 10f)
            })
            assertEquals(100f, access.health)
            assertTrue(access.commits.isEmpty())
            assertTrue(access.events.none { it.startsWith("vanilla:") })
        }
    }

    @Test
    fun `commit failure propagates without reporting success invoking vanilla or starting destruction`() {
        for (resolved in listOf(false, true)) {
            val failure = IllegalStateException("commit")
            val access = Access().apply { onCommit = { health = 0f; throw failure } }
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                if (resolved) access.resolved() else VehicleDamageTransaction(access).hurt(Source("raw"), 10f)
            })
            assertEquals(0f, access.health, "External effects are not rolled back")
            assertTrue(access.events.none { it.startsWith("vanilla:") || it.startsWith("destroy:") })
            assertTrue(access.destroyed.isEmpty())
        }
    }
}
