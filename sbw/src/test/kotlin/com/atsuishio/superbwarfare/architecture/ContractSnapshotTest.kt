package com.atsuishio.superbwarfare.architecture

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Locks the identifiers that worlds, servers and the BVP addon depend on, so source reorganisation (moving files
 * between the java and kotlin roots, splitting classes, extracting constants) cannot change them unnoticed:
 * registry IDs, network packet tables, synched entity data order, string keys used with NBT/ForgeData accessors,
 * config keys and diagnostic system properties.
 *
 * The scan reads source text. Entries are keyed by their declaring file's package and name, not its path, so moving
 * a file between source roots leaves the snapshot unchanged.
 *
 * Default mode tolerates additions and fails when a golden entry is removed or changed: a registry ID that disappears
 * or changes its registering helper, a packet whose index or schema changes, a class whose existing synched-data
 * fields no longer lead its list in the same order, an NBT/config/property key that disappears. Set-like sections are
 * compared by value, ignoring the declaring class, so code may move between classes.
 *
 * Strict mode (`-Pcontract.strict=true`) requires the snapshot to equal the golden file exactly, additions included;
 * it is the check for behaviour-neutral refactors. Regenerate the golden files after a reviewed contract change with
 * `-Pcontract.update=true` (or the environment variable CONTRACTS_UPDATE=1). See docs/development/CONTRACT_TESTS.md.
 */
class ContractSnapshotTest {
    @Test
    fun superbWarfareContractsMatchGolden() {
        check("sbw", listOf(Path.of("src/main/java"), Path.of("src/main/kotlin")))
    }

    @Test
    fun bertsVehiclePackContractsMatchGolden() {
        val bvp = Path.of("../bvp/src/main/java")
        assumeTrue(bvp.exists(), "BVP sources are not beside this project")
        check("bvp", listOf(bvp))
    }

    private fun check(name: String, roots: List<Path>) {
        val actual = snapshot(roots)
        val golden = Path.of("src/test/resources/architecture/$name-contracts.golden.txt")
        if (System.getProperty("contract.update") == "true" || System.getenv("CONTRACTS_UPDATE") == "1") {
            Files.createDirectories(golden.parent)
            golden.writeText(actual)
            return
        }
        if (!golden.isRegularFile()) fail("Missing $golden; generate it with -Pcontract.update=true")
        val expected = golden.readText().replace("\r\n", "\n")
        val strict = System.getProperty("contract.strict") == "true"
        val problems = if (strict) strictDifferences(expected, actual) else breakingDifferences(expected, actual)
        if (problems.isEmpty()) return
        val out = Path.of("build/contracts/$name-contracts.actual.txt")
        Files.createDirectories(out.parent)
        out.writeText(actual)
        fail(buildString {
            append("$name contracts ${if (strict) "differ (strict mode)" else "broken"}; actual written to $out")
            problems.take(60).forEach { append("\n  ").append(it) }
            if (problems.size > 60) append("\n  ... ${problems.size - 60} more")
            append("\nIf the change is intended, regenerate the golden file with -Pcontract.update=true and review its diff.")
        })
    }

    companion object {
        private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        // register(...) and the helpers built on it (registerItem, registerGun, registerAmmo, modItemTag, ...)
        private val REGISTRY = Regex("""\b(register\w*|mod\w*Tag)\(\s*"([a-z0-9_./\-]+)"""")
        private val SCHEMA_PACKET = Regex("""\bplay(?:ToClient|ToServer)<\s*(\w+)\s*>\s*\(""")
        private val FORGE_PACKET = Regex("""registerMessage\(\s*(\w+)\s*,\s*([\w.]+)\.class""")
        private val CONSTANT = Regex("""\b(?:const val|static final (?:int|String))\s+(\w*(?:PROTOCOL|_ID)\w*)\s*(?::\s*\w+)?\s*=\s*([^;\n]+)""")
        private val DEFINE_ID = Regex("""(\w+)\s*(?::[^=\n]+)?=\s*SynchedEntityData\s*\.\s*defineId\(\s*([\w.:]+?)(?:::class\.java|\.class)\s*,\s*([\w.]+)""")
        private val KEYED = Regex("""\.(put\w*|get\w*|contains\w*|remove)\(\s*"([^"\n]+)"""")
        private val CONSTANT_KEYED = Regex("""\.(put\w*|get\w*|contains\w*|remove)\(\s*([\w.]+)\s*[,)]""")
        private val STRING_CONSTANT = Regex("""\b(?:const\s+val|static\s+final\s+String)\s+(\w+)\s*(?::\s*String\s*)?=\s*"([^"\n]+)"""")
        private val CONFIG = Regex("""\b(define\w*)\(\s*"([^"\n]+)"""")
        private val PROPERTY = Regex("""(?:(?:java\.lang\.)?System\.(?:getProperty|getenv)|(?:java\.lang\.)?(?:Boolean\.getBoolean|Integer\.getInteger|Long\.getLong))\(\s*"([\w.\-]+)"""")

        /** Sections whose entries are ordered per declaring class (entity data IDs follow declaration order). */
        private val ORDERED = setOf("synched-data")

        /** The text inside the parentheses opening at [open], nesting respected (no parentheses in strings expected). */
        private fun balanced(text: String, open: Int): String {
            var depth = 0
            for (i in open until text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return text.substring(open + 1, i)
                }
            }
            error("Unbalanced call at $open")
        }

        private fun sections(snapshot: String): Map<String, List<Pair<String, String>>> {
            val out = linkedMapOf<String, MutableList<Pair<String, String>>>()
            var current: MutableList<Pair<String, String>>? = null
            for (line in snapshot.lines()) {
                if (line.startsWith("## ")) {
                    current = out.getOrPut(line.removePrefix("## ").substringBefore(" (")) { mutableListOf() }
                } else if (line.isNotBlank()) {
                    current?.add(line.substringBefore(" | ") to line.substringAfter(" | "))
                }
            }
            return out
        }

        /** Exact comparison: every removed, added or reordered line. */
        fun strictDifferences(expected: String, actual: String): List<String> {
            if (expected == actual) return emptyList()
            val exp = expected.lines().toSet()
            val act = actual.lines().toSet()
            val removed = expected.lines().filter { it.isNotBlank() && it !in act }.map { "- $it" }
            val added = actual.lines().filter { it.isNotBlank() && it !in exp }.map { "+ $it" }
            return (removed + added).ifEmpty { listOf("same entries, different order") }
        }

        /** Golden entries that the actual snapshot no longer carries unchanged; additions are allowed. */
        fun breakingDifferences(expected: String, actual: String): List<String> {
            val act = sections(actual)
            val problems = mutableListOf<String>()
            for ((section, entries) in sections(expected)) {
                val now = act[section].orEmpty()
                if (section in ORDERED) {
                    // each class's existing fields must still lead its list, in the same order
                    val before = entries.groupBy({ it.first }, { it.second })
                    val after = now.groupBy({ it.first }, { it.second })
                    for ((owner, fields) in before) {
                        val current = after[owner].orEmpty()
                        if (current.size < fields.size || current.subList(0, fields.size) != fields) {
                            problems += "[$section] $owner: was ${fields.joinToString()}; now ${current.joinToString().ifEmpty { "(none)" }}"
                        }
                    }
                } else {
                    // compared by value, so moving a registration or key between classes is not a break
                    val remaining = now.groupingBy { it.second }.eachCount().toMutableMap()
                    for ((owner, value) in entries) {
                        val left = remaining[value] ?: 0
                        if (left > 0) remaining[value] = left - 1 else problems += "[$section] removed or changed: $value (was in $owner)"
                    }
                }
            }
            return problems
        }

        fun snapshot(roots: List<Path>): String {
            val files = sortedMapOf<String, String>()
            for (root in roots) {
                if (!root.exists()) continue
                Files.walk(root).use { stream ->
                    stream.filter { it.isRegularFile() && (it.extension == "kt" || it.extension == "java") }.forEach {
                        val text = it.readText().replace("\r\n", "\n")
                        val pkg = PACKAGE.find(text)?.groupValues?.get(1) ?: ""
                        // a .kt and a .java file may share a name (network.NetworkRegistry); the extension keeps them apart
                        val key = (if (pkg.isEmpty()) "" else "$pkg.") + "${it.nameWithoutExtension}.${it.extension}"
                        check(files.put(key, text) == null) { "Two sources declare $key" }
                    }
                }
            }
            val sections = linkedMapOf<String, MutableList<String>>(
                "registry" to mutableListOf(), "packets" to mutableListOf(), "network-constants" to mutableListOf(),
                "synched-data" to mutableListOf(), "keys" to mutableListOf(), "config" to mutableListOf(),
                "properties" to mutableListOf())
            // A moved string constant must retain the same keyed-access contract as its literal.
            // Resolve only unambiguous, declared string constants; dynamic arguments are not guessed.
            val constants = mutableMapOf<String, MutableSet<String>>()
            for ((owner, text) in files) {
                val type = owner.removeSuffix(".kt").removeSuffix(".java")
                STRING_CONSTANT.findAll(text).forEach {
                    val name = it.groupValues[1]
                    val value = it.groupValues[2]
                    for (alias in setOf("$type.$name", "${type.substringAfterLast('.')}.$name")) {
                        constants.getOrPut(alias) { mutableSetOf() }.add(value)
                    }
                }
            }
            for ((owner, text) in files) {
                // set-like contracts: sorted per owner; ordered contracts (packet and synched-data IDs): source order
                REGISTRY.findAll(text).map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toSortedSet()
                    .forEach { sections["registry"]!! += "$owner | $it" }
                SCHEMA_PACKET.findAll(text).forEach {
                    // the schema arguments may run over several lines: take the balanced parentheses
                    val args = balanced(text, it.range.last)
                    sections["packets"]!! += "$owner | ${it.groupValues[1]} ${args.replace(Regex("\\s+"), " ").trim()}"
                }
                FORGE_PACKET.findAll(text).forEach { sections["packets"]!! += "$owner | ${it.groupValues[1]} ${it.groupValues[2]}" }
                CONSTANT.findAll(text).forEach {
                    sections["network-constants"]!! += "$owner | ${it.groupValues[1]} = ${it.groupValues[2].trim()}"
                }
                DEFINE_ID.findAll(text).forEach {
                    sections["synched-data"]!! += "$owner | ${it.groupValues[1]} ${it.groupValues[2]} ${it.groupValues[3]}"
                }
                val type = owner.removeSuffix(".kt").removeSuffix(".java")
                val constantKeys = CONSTANT_KEYED.findAll(text).mapNotNull {
                    val token = it.groupValues[2]
                    val alias = if ('.' in token) token else "$type.$token"
                    constants[alias]?.singleOrNull()?.let { value -> "${it.groupValues[1]} $value" }
                }
                (KEYED.findAll(text).map { "${it.groupValues[1]} ${it.groupValues[2]}" } + constantKeys).toSortedSet()
                    .forEach { sections["keys"]!! += "$owner | $it" }
                CONFIG.findAll(text).map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toSortedSet()
                    .forEach { sections["config"]!! += "$owner | $it" }
                PROPERTY.findAll(text).map { it.groupValues[1] }.toSortedSet()
                    .forEach { sections["properties"]!! += "$owner | $it" }
            }
            return buildString {
                for ((section, lines) in sections) {
                    append("## ").append(section).append(" (").append(lines.size).append(")\n")
                    lines.forEach { append(it).append('\n') }
                }
            }
        }
    }
}
