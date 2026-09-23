package com.atsuishio.superbwarfare.client.aircraft

import com.google.gson.JsonObject
import java.util.UUID

/** Bounded, atomic full/thin receipt handling; no GUI or network effects. */
internal class AircraftArmamentStateCache {
    companion object { const val MAX_ENTRIES = 512 } // 256 far proxies plus native/pilot receipts.
    data class Entry(val snapshot: AircraftArmamentSnapshot, val json: JsonObject,
                     val allBones: Set<String>, val visibleBones: Set<String>,
                     val catalogueRevision: Long, val pointRevision: Long)
    private val entries = LinkedHashMap<UUID, Entry>()
    val size: Int get() = entries.size
    fun get(id: UUID): Entry? = entries[id]
    fun clear() = entries.clear()

    fun receive(json: JsonObject): Entry? {
        return try {
            val id = UUID.fromString(json.get("Vehicle").asString)
            val previous = entries[id]
            val result = if (json.has("Definition")) {
                var snapshot = AircraftArmamentSnapshot.decode(json) ?: return null
                val catalogue = revision(json, "CatalogueRevision") ?: 0L
                var pointRevision = revision(json, "PointRevision") ?: 0L
                if (previous != null && (catalogue < previous.catalogueRevision ||
                        (catalogue == previous.catalogueRevision && snapshot.revision < previous.snapshot.revision))) return null
                val full = json.deepCopy()
                if (!full.has("Epoch") && previous?.json?.has("Epoch") == true) {
                    full.add("Epoch", previous.json.get("Epoch").deepCopy())
                }
                if (!full.has("PodActive") && previous != null && snapshot.definition.pod != null) {
                    snapshot = snapshot.copy(podActive = previous.snapshot.podActive)
                    full.addProperty("PodActive", snapshot.podActive)
                }
                if (previous != null && pointRevision < previous.pointRevision) {
                    snapshot = snapshot.copy(point = previous.snapshot.point)
                    pointRevision = previous.pointRevision
                    if (previous.json.has("Point")) full.add("Point", previous.json.get("Point").deepCopy())
                    else full.remove("Point")
                    full.addProperty("PointRevision", pointRevision)
                }
                if (previous?.snapshot?.seek != null &&
                    (snapshot.seek?.revision ?: -1L) < previous.snapshot.seek.revision) {
                    snapshot = snapshot.copy(seek = previous.snapshot.seek)
                    full.add("Seek", previous.json.get("Seek").deepCopy())
                }
                Entry(snapshot, full, snapshot.allStoreBones(), snapshot.visibleBones(), catalogue, pointRevision)
            } else {
                previous ?: return null
                require(revision(json, "EntityId") == previous.snapshot.entityId.toLong())
                if (json.has("Seek")) {
                    val seek = AircraftArmamentSnapshot.decodeSeek(json.getAsJsonObject("Seek"))
                    if (seek.revision <= (previous.snapshot.seek?.revision ?: -1L)) return null
                    val merged = copyHeader(previous.json)
                    merged.add("Seek", json.get("Seek").deepCopy())
                    val result = previous.copy(snapshot = previous.snapshot.copy(seek = seek), json = merged)
                    entries[id] = result
                    return result
                }
                val pointRevision = revision(json, "PointRevision") ?: return null
                if (pointRevision <= previous.pointRevision) return null
                val clear = json.get("ClearPoint")?.let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean)
                    it.asBoolean
                } ?: false
                if (!clear && !json.has("Point")) return null
                val point = if (clear) null else AircraftArmamentSnapshot.vector(json.get("Point"), 30_000_000.0)
                val merged = copyHeader(previous.json)
                if (clear) merged.remove("Point") else merged.add("Point", json.get("Point").deepCopy())
                merged.addProperty("PointRevision", pointRevision)
                previous.copy(snapshot = previous.snapshot.copy(point = point), json = merged, pointRevision = pointRevision)
            }
            entries[id] = result
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
            result
        } catch (_: RuntimeException) { null }
    }

    /** Full receipts own immutable catalogue subtrees. Thin updates replace root fields only:
     * copying every pylon/store definition at seeker frequency wastes CPU and invalidates
     * store identity caches even though no equipment changed. */
    private fun copyHeader(source: JsonObject) = JsonObject().also { copy ->
        for ((key, value) in source.entrySet()) copy.add(key, value)
    }

    private fun revision(json: JsonObject, key: String): Long? = json.get(key)?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
        it.asBigDecimal.longValueExact().also { value -> require(value >= 0) }
    }
}
