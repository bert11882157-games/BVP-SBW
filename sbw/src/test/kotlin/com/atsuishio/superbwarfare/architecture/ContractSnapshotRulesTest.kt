package com.atsuishio.superbwarfare.architecture

import com.atsuishio.superbwarfare.architecture.ContractSnapshotTest.Companion.breakingDifferences
import com.atsuishio.superbwarfare.architecture.ContractSnapshotTest.Companion.strictDifferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The comparison rules of [ContractSnapshotTest]: default mode allows additions only, strict mode nothing. */
class ContractSnapshotRulesTest {
    private val golden = """
        ## registry (2)
        a.Items.kt | registerItem rifle
        a.Items.kt | registerItem scope
        ## packets (1)
        a.Net.kt | Ping clientSchema(0, owner = "x")
        ## synched-data (2)
        a.Car.kt | HEALTH Car FLOAT
        a.Car.kt | FUEL Car INT
    """.trimIndent() + "\n"

    @Test
    fun additionsPassDefaultButNotStrict() {
        val added = golden.replace("a.Items.kt | registerItem scope\n", "a.Items.kt | registerItem scope\na.Items.kt | registerItem stock\n")
            .replace("a.Car.kt | FUEL Car INT\n", "a.Car.kt | FUEL Car INT\na.Car.kt | GEAR Car BOOLEAN\n")
        assertEquals(emptyList<String>(), breakingDifferences(golden, added))
        assertTrue(strictDifferences(golden, added).isNotEmpty())
    }

    @Test
    fun movingAnEntryToAnotherClassIsNotABreak() {
        val moved = golden.replace("a.Items.kt | registerItem scope", "b.Optics.kt | registerItem scope")
        assertEquals(emptyList<String>(), breakingDifferences(golden, moved))
    }

    @Test
    fun removalsIndexChangesAndReordersBreak() {
        assertEquals(1, breakingDifferences(golden, golden.replace("a.Items.kt | registerItem rifle\n", "")).size)
        assertEquals(1, breakingDifferences(golden, golden.replace("registerItem rifle", "registerBlock rifle")).size)
        assertEquals(1, breakingDifferences(golden, golden.replace("clientSchema(0,", "clientSchema(1,")).size)
        val swapped = golden.replace("a.Car.kt | HEALTH Car FLOAT\na.Car.kt | FUEL Car INT", "a.Car.kt | FUEL Car INT\na.Car.kt | HEALTH Car FLOAT")
        assertEquals(1, breakingDifferences(golden, swapped).size)
    }

    @Test
    fun identicalSnapshotsPassBothModes() {
        assertEquals(emptyList<String>(), breakingDifferences(golden, golden))
        assertEquals(emptyList<String>(), strictDifferences(golden, golden))
    }
}
