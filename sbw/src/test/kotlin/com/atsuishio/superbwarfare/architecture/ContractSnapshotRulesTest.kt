package com.atsuishio.superbwarfare.architecture

import com.atsuishio.superbwarfare.architecture.ContractSnapshotTest.Companion.breakingDifferences
import com.atsuishio.superbwarfare.architecture.ContractSnapshotTest.Companion.strictDifferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The comparison rules of [ContractSnapshotTest]: default mode allows additions only, strict mode nothing. */
class ContractSnapshotRulesTest {
    @TempDir
    lateinit var directory: Path
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

    @Test
    fun movingAKeyLiteralToAQualifiedStringConstantKeepsItsKeyContract() {
        val access = directory.resolve("State.kt")
        Files.writeString(access, "package fixture\nfun restore(tag: Tag) { tag.getBoolean(\"FirstBoarding\") }\n")
        val literal = ContractSnapshotTest.snapshot(listOf(directory)).substringAfter("## keys").substringBefore("## config")
        Files.writeString(directory.resolve("Keys.kt"), "package fixture\nobject Keys { const val BOARDING = \"FirstBoarding\" }\n")
        Files.writeString(access, "package fixture\nfun restore(tag: Tag) { tag.getBoolean(Keys.BOARDING) }\n")
        val constant = ContractSnapshotTest.snapshot(listOf(directory)).substringAfter("## keys").substringBefore("## config")
        assertEquals(literal, constant)
        assertTrue(constant.contains("getBoolean FirstBoarding"))
    }

    @Test
    fun nbtBooleanAccessIsNotASystemProperty() {
        Files.writeString(directory.resolve("Policy.kt"), """
            package fixture
            fun read(tag: Tag) {
                tag.getBoolean("SavedFlag")
                System.getProperty("fixture.name")
                Boolean.getBoolean("fixture.enabled")
                java.lang.Integer.getInteger("fixture.limit")
                System.getenv("FIXTURE_HOME")
            }
        """.trimIndent())
        val properties = ContractSnapshotTest.snapshot(listOf(directory)).substringAfter("## properties")
        assertTrue(!properties.contains("SavedFlag"))
        for (name in listOf("fixture.name", "fixture.enabled", "fixture.limit", "FIXTURE_HOME")) {
            assertTrue(properties.contains(" | $name\n"), name)
        }
    }
}
