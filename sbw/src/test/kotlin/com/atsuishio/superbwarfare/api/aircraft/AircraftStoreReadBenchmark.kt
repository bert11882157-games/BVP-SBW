package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** Isolated loadout-read measurement, not a renderer benchmark or an FPS prediction. */
object AircraftStoreReadBenchmark {
    @Volatile private var sink = 0

    @JvmStatic fun main(args: Array<String>) {
        val definition = JsonParser.parseString(Files.readString(Path.of(args[0]))).asJsonObject
        val store = JsonParser.parseString(Files.readString(Path.of(args[1]))).asJsonObject
        val storeId = "berts_vehicle_pack:kh55"
        val mounts = AircraftArmamentRegistry.mounts(definition)
        require(mounts.size == 8)
        val selection = mounts.associate { it["Id"].asString to storeId }
        fun entries() = AircraftArmamentRegistry.mounts(definition).map { mount ->
            val id = mount["Id"].asString
            // Equivalent to the prior mountCapacity and mountRemaining reads on the client.
            fun capacity(): Int {
                val match = AircraftArmamentRegistry.mounts(definition).first { it["Id"].asString == id }
                return AircraftArmamentRegistry.mountCapacity(match, store["Capacity"]?.asInt ?: 1)
            }
            AircraftStoreWeapons.Equipped(selection.getValue(id), store,
                AircraftStoreWeapons.Member(id, AircraftArmamentManager.nativeWeapons(mount, storeId),
                    capacity(), capacity()))
        }
        val state = AircraftStoreWeaponState().layout(definition, 1)
        val load = { entries() }
        val native: (String) -> Pair<Int, Int>? = { null }
        val uncached = {
            val groups = AircraftStoreWeapons.collect(entries(), native)
            // The old virtual GunData cache serialized the definition on every read.
            sink = groups.single().ammo + (store.toString() + "|" + groups.single().capacity).length
        }
        val cached = {
            state.layout(definition, 1)
            sink = state.groups(1, load, native).single().ammo
        }
        repeat(5000) { uncached(); cached() }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val iterations = 25000
        fun measure(read: () -> Unit): Pair<Double, Double> {
            val beforeBytes = bean.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            repeat(iterations) { read() }
            return (System.nanoTime() - start).toDouble() / iterations to
                (bean.getThreadAllocatedBytes(thread) - beforeBytes).toDouble() / iterations
        }
        val before = mutableListOf<Pair<Double, Double>>()
        val after = mutableListOf<Pair<Double, Double>>()
        repeat(7) { round ->
            if (round % 2 == 0) { before += measure(uncached); after += measure(cached) }
            else { after += measure(cached); before += measure(uncached) }
        }
        require(state.groups(1, load, native).single().ammo == 8)
        fun summary(rows: List<Pair<Double, Double>>) = JsonObject().apply {
            addProperty("medianNanosecondsPerRead", rows.map { it.first }.sorted()[3])
            addProperty("medianAllocatedBytesPerRead", rows.map { it.second }.sorted()[3])
        }
        println(JsonObject().apply {
            addProperty("scope", "Eight-bay TU-95 loadout construction and virtual cache-key work only; no game or renderer")
            addProperty("iterationsPerRound", iterations); addProperty("rounds", 7)
            add("uncachedEquivalent", summary(before)); add("cachedProductionState", summary(after))
        })
    }
}
