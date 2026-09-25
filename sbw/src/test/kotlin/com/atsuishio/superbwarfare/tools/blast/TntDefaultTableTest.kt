package com.atsuishio.superbwarfare.tools.blast

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class TntDefaultTableTest {
    @Test fun `table keeps positive charges and drops null or zero entries`() {
        val parsed = TntDefaultTable.parse(JsonParser.parseString("""
            {"_comment": "x", "entries": {"superbwarfare:c4": 0.57, "superbwarfare:mk_82": 89,
             "superbwarfare:perk/firefly": null, "superbwarfare:edd": 0}}
        """.trimIndent()))
        assertEquals(mapOf("superbwarfare:c4" to 0.57, "superbwarfare:mk_82" to 89.0), parsed)
        assertEquals(emptyMap<String, Double>(), TntDefaultTable.parse(JsonParser.parseString("{}")))
    }

    @Test fun `malformed tables are rejected`() {
        for (bad in listOf("[]", """{"entries": []}""", """{"entries": {"a": "1"}}""",
                """{"entries": {"a": -1}}""", """{"entries": {"a": 1e9}}""", """{"entries": {"": 1}}""")) {
            assertThrows(IllegalArgumentException::class.java, { TntDefaultTable.parse(JsonParser.parseString(bad)) }, bad)
        }
    }

    @Test fun `server config overrides win and minus one defers to the table`() {
        assertEquals(2.5, TntDefaultTable.configuredOrTable(2.5, 0.6))
        assertEquals(0.0, TntDefaultTable.configuredOrTable(0.0, 0.6), "0 in the config forces the legacy blast")
        assertEquals(0.6, TntDefaultTable.configuredOrTable(-1.0, 0.6))
        assertEquals(0.0, TntDefaultTable.configuredOrTable(-1.0, null))
        assertEquals(0.6, TntDefaultTable.configuredOrTable(Double.NaN, 0.6))
    }

    @Test fun `shipped defaults table parses`() {
        val file = listOf("src/main/resources/data/superbwarfare/blast/tnt_defaults.json",
            "sbw/src/main/resources/data/superbwarfare/blast/tnt_defaults.json").map(::File).firstOrNull { it.isFile }
        assertNotNull(file, "tnt_defaults.json is packaged with SBW")
        assertDoesNotThrow { TntDefaultTable.parse(JsonParser.parseString(file!!.readText())) }
    }
}
