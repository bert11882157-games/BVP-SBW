package com.atsuishio.superbwarfare.api.projectile

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Test view of the BVP resource pack: generated fallback, then source-owned overlay. */
internal class EffectiveDamageResources(private val bvp: File) {
    companion object {
        fun invariant(path: String) = path.replace('\\', '/')
        /** Explicit zero means no TNT in TntEquivalents; field presence alone is not an explosive charge. */
        fun noCharge(data: JsonObject): Boolean = listOf("TntEquivalentKg", "ExplosionDamage").all { key ->
            val value = data.get(key)
            value == null || value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                value.asDouble.isFinite() && value.asDouble == 0.0
        }
        fun overlay(generated: Map<String, File>, main: Map<String, File>): Map<String, File> = generated + main
    }
    private val roots = listOf("src/generated/resources/data/berts_vehicle_pack", "src/main/resources/data/berts_vehicle_pack")
    val files: Map<String, File> by lazy {
        roots.fold(emptyMap<String, File>()) { accumulated, dir ->
            val root = File(bvp, dir)
            overlay(accumulated, root.walkTopDown().filter { it.isFile && it.extension == "json" }
                .associateBy { invariant(it.relativeTo(root).path) })
        }
    }
    fun json(path: String): JsonObject = files.getValue(path).reader().use { JsonParser.parseReader(it).asJsonObject }
    val registered: Set<String> by lazy {
        val source = File(bvp, "src/main/java/com/yourname/berts_vehicle_pack/init/ModEntities.java").readText()
        Regex("""ENTITIES\.register\("([^"]+)",\s*\(\)\s*->\s*vehicle\(""")
            .findAll(source).map { it.groupValues[1] }.toSet().also { check(it.size >= 100) { "Vehicle registry scope unexpectedly empty/small: ${it.size}" } }
    }
    val vehicles: Map<String, JsonObject> by lazy { registered.associateWith { json("sbw/vehicles/$it.json") } }
    val orphanVehicles: Set<String> get() = files.keys.filter { it.startsWith("sbw/vehicles/") }
        .map { it.removePrefix("sbw/vehicles/").removeSuffix(".json") }.toSet() - registered
    /** Traverse real resource references, not directory-name guesses about which old profiles remain live. */
    val reachable: Set<String> by lazy {
        reachableFrom(registered.flatMap { id ->
            listOf("sbw/vehicles/$id.json") +
                listOf("sbw/aircraft_armaments/$id.json").filter { it in files }
        })
    }

    internal fun reachableFrom(roots: Collection<String>): Set<String> {
        val queue = ArrayDeque(roots)
        val visited = linkedSetOf<String>()
        fun walk(e: JsonElement, where: String, parent: String = "") {
            when {
                e.isJsonObject -> e.asJsonObject.entrySet().forEach { (key, value) ->
                    if (key in setOf("ProjectileProfile", "BombletProfile", "SensorProjectileProfile") ||
                        key == "Profile" && parent == "Projectile") {
                        check(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$where/$key: invalid profile reference" }
                        val id = value.asString
                        if (id.startsWith("berts_vehicle_pack:")) {
                            val target = "sbw/projectile_profiles/${id.substringAfter(':')}.json"
                            check(target in files) { "$where/$key: missing referenced projectile profile $target" }
                        }
                    }
                    walk(value, "$where/$key", key)
                }
                e.isJsonArray -> e.asJsonArray.forEachIndexed { i, value -> walk(value, "$where[$i]", parent) }
                e.isJsonPrimitive && e.asJsonPrimitive.isString -> {
                    val value = e.asString
                    if (value.startsWith("berts_vehicle_pack:")) {
                        val id = value.substringAfter(':')
                        for (category in listOf("projectile_profiles", "aircraft_stores", "guns")) {
                            val path = "sbw/$category/$id.json"
                            if (path in files && path !in visited) queue.add(path)
                        }
                    }
                }
            }
        }
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (visited.add(path)) walk(json(path), path)
        }
        return visited
    }

    // ProjectileProfiles.resolveUncached permits combat=null for presentation profiles. These exact
    // reviewed resources carry bomb visuals; new omissions require review rather than silently dropping data.
    private val visualOnly = setOf(
        "aircraft_bombs/agm154a.json",
        "aircraft_bombs/blu1.json",
        "aircraft_bombs/blu109.json",
        "aircraft_bombs/blu109_jdam.json",
        "aircraft_bombs/blu27.json",
        "aircraft_bombs/cbu87.json",
        "aircraft_bombs/cbu97.json",
        "aircraft_bombs/cbu99_rockeye_ii.json",
        "aircraft_bombs/fab_100.json",
        "aircraft_bombs/fab_1500.json",
        "aircraft_bombs/fab_250.json",
        "aircraft_bombs/fab_3000.json",
        "aircraft_bombs/fab_500.json",
        "aircraft_bombs/fab_5000.json",
        "aircraft_bombs/gbu10.json",
        "aircraft_bombs/gbu12.json",
        "aircraft_bombs/gbu15.json",
        "aircraft_bombs/gbu16.json",
        "aircraft_bombs/gbu24.json",
        "aircraft_bombs/gbu27.json",
        "aircraft_bombs/gbu28.json",
        "aircraft_bombs/gbu31.json",
        "aircraft_bombs/gbu32.json",
        "aircraft_bombs/gbu38.json",
        "aircraft_bombs/gbu39.json",
        "aircraft_bombs/gbu53.json",
        "aircraft_bombs/gbu54.json",
        "aircraft_bombs/gbu62.json",
        "aircraft_bombs/gbu62l.json",
        "aircraft_bombs/gbu64.json",
        "aircraft_bombs/gbu8.json",
        "aircraft_bombs/kab1500l.json",
        "aircraft_bombs/kab250.json",
        "aircraft_bombs/kab500od.json",
        "aircraft_bombs/ls6_500.json",
        "aircraft_bombs/m117.json",
        "aircraft_bombs/m118.json",
        "aircraft_bombs/mc1000.json",
        "aircraft_bombs/mk77.json",
        "aircraft_bombs/mk81.json",
        "aircraft_bombs/mk82.json",
        "aircraft_bombs/mk82_air.json",
        "aircraft_bombs/mk82_snakeeye.json",
        "aircraft_bombs/mk83.json",
        "aircraft_bombs/mk83_air.json",
        "aircraft_bombs/mk84.json",
        "aircraft_bombs/mk84_air.json",
        "aircraft_bombs/odab250.json",
        "aircraft_bombs/odab500pm.json",
        "aircraft_bombs/rbk_250.json",
        "aircraft_bombs/samp21.json",
        "aircraft_bombs/samp21_200.json",
        "aircraft_bombs/samp25.json",
        "aircraft_bombs/samp25_200.json",
        "aircraft_bombs/walleye.json",
        "aircraft_bombs/walleye2.json",
        "aircraft_bombs/zb500.json",
        "cluster_bomblet.json",
    )
    internal fun combat(path: String): JsonObject? {
        val profile = json("sbw/projectile_profiles/$path")
        if (profile.has("Combat")) {
            check(profile.get("Combat").isJsonObject) { "$path: malformed Combat" }
            return profile.getAsJsonObject("Combat")
        }
        check(path in visualOnly) { "$path: missing Combat on a referenced damage profile" }
        check(profile.keySet().all { it in setOf("VisualProfile", "MotionSync", "TrailMode", "RenderScale", "Extensions") }
                && profile.get("VisualProfile")?.isJsonPrimitive == true) { "$path: unreviewed visual-only profile shape" }
        return null
    }
    fun combats(): List<Pair<String, JsonObject>> = reachable.filter { it.startsWith("sbw/projectile_profiles/") }
        .sorted().mapNotNull { full ->
            val path = full.removePrefix("sbw/projectile_profiles/")
            combat(path)?.let { path to it }
        }
}
