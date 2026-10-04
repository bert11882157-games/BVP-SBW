package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

/**
 * Targeting pods carried as pylon stores (owner 2026-10-01, the Palantir test pod: "mountable on any jet with a
 * pylon ... for aircraft that dont have a targeting pod, you attach this to the pylon, and it will then have a
 * targeting pod").
 *
 * A store of category [CATEGORY] carries a [POD] block: the sensor `Eye` (model-file blocks in the
 * [AircraftStoreMountAnchor] frame, the front of the sensor window) and the gimbal of an authored aircraft `Pod`
 * (`YawLimit`, `PitchMin`, `PitchMax`, optional `MaxZoom`, `Range`). Its optional [ANY_PYLON] block offers it on every
 * external mount of every fixed-wing aircraft except the mount ids in `ExceptMounts`, without editing any aircraft
 * definition ([withUniversalStores], applied per fixed-wing vehicle by `AircraftArmamentManager.definition`).
 *
 * The aircraft's pod ([effective]) is its authored built-in `Pod` when it has one (always available, so it always
 * wins); otherwise the first fitted pod store, in mount order, whose station is still on the airframe. That pod is
 * an aircraft `Pod` object whose `Position` is the eye where the store is drawn on its pylon ([storePod]), so the
 * server laser, the client camera and every pod rule use it exactly like an authored pod. `Mount` and
 * `MountPosition` name the station so a swept pylon's eye follows the wing ([sweptPosition]) and the pod view can
 * leave its own housing undrawn.
 */
object AircraftTargetingPods {
    const val CATEGORY = "TARGETING_POD"
    const val POD = "TargetingPod"
    const val ANY_PYLON = "AnyPylon"
    const val EXCEPT_MOUNTS = "ExceptMounts"
    const val MOUNT = "Mount"
    const val MOUNT_POSITION = "MountPosition"
    private val MOUNT_ID = Regex("[a-zA-Z0-9_.-]{1,48}")

    fun validateStore(store: JsonObject) {
        val category = store["Category"]?.asString
        require((category == CATEGORY) == store.has(POD)) { "A $CATEGORY store needs a $POD block, and only it has one" }
        store[ANY_PYLON]?.let { raw ->
            require(category == CATEGORY && raw.isJsonObject) { "$ANY_PYLON is an object on a $CATEGORY store" }
            val block = raw.asJsonObject
            require(block.keySet().all { it == EXCEPT_MOUNTS }) { "Unknown $ANY_PYLON key" }
            block[EXCEPT_MOUNTS]?.let { except ->
                require(except.isJsonArray && except.asJsonArray.size() <= 32 && except.asJsonArray.all {
                    it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.matches(MOUNT_ID)
                }) { "$EXCEPT_MOUNTS lists up to 32 mount ids" }
            }
        }
        val pod = store[POD] ?: return
        require(pod.isJsonObject && store.has("Model")) { "$POD describes the eye of a modelled pod" }
        val block = pod.asJsonObject
        require(block.keySet().all { it in setOf("Eye", "YawLimit", "PitchMin", "PitchMax", "MaxZoom", "Range") }) {
            "Unknown $POD key"
        }
        val eye = requireNotNull(AircraftArmamentRegistry.vector(block["Eye"])) { "$POD Eye must be a finite [x, y, z]" }
        require(eye.length() <= AircraftStoreMountAnchor.MAX_LENGTH_BLOCKS) { "$POD Eye is outside the store model" }
        validateGimbal(block)
    }

    /** The gimbal rules of an authored aircraft `Pod` (AircraftArmamentRegistry). */
    fun validateGimbal(pod: JsonObject) {
        fun number(key: String): Double = requireNotNull(pod[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber })
            { "$key must be a number" }.asDouble
        require(number("YawLimit") in 0.0..180.0)
        require(number("PitchMin") in -90.0..90.0)
        require(number("PitchMax") in pod["PitchMin"].asDouble..90.0)
        pod["MaxZoom"]?.let { require(number("MaxZoom").let { z -> z.isFinite() && z in 1.0..24.0 }) }
        pod["Range"]?.let { require(number("Range").let { r -> r.isFinite() && r in 1.0..8192.0 }) }
    }

    fun isPod(store: JsonObject?): Boolean = store?.get("Category")?.asString == CATEGORY && store.has(POD)

    /** Whether [store] offers itself on [mount] of a fixed-wing aircraft: every external pylon but the excepted ids. */
    fun offeredOn(store: JsonObject, mount: JsonObject): Boolean {
        val block = store.getAsJsonObject(ANY_PYLON) ?: return false
        if (!isPod(store) || mount["Internal"]?.asBoolean == true) return false
        val id = mount["Id"]?.asString ?: return false
        return block.getAsJsonArray(EXCEPT_MOUNTS)?.none { it.asString == id } ?: true
    }

    private class Universal(val stores: Map<ResourceLocation, JsonObject>, val definition: JsonObject)
    private val universal: MutableMap<JsonObject, Universal> = com.google.common.collect.MapMaker().weakKeys().makeMap()

    /**
     * [definition] with every [ANY_PYLON] store appended to the `AllowedStores` of each mount it is offered on. The
     * registry definition is never edited: a changed copy is kept per (definition, catalogue) pair, so callers that
     * compare definitions by identity see one object until either is reloaded; with nothing to add it is [definition].
     */
    fun withUniversalStores(definition: JsonObject, stores: Map<ResourceLocation, JsonObject>): JsonObject {
        universal[definition]?.takeIf { it.stores === stores }?.let { return it.definition }
        val offered = stores.entries.filter { (_, store) -> store.has(ANY_PYLON) && isPod(store) }
        var result = definition
        if (offered.isNotEmpty()) {
            val copy = definition.deepCopy()
            var changed = false
            for (mount in AircraftArmamentRegistry.mounts(copy)) for ((id, store) in offered) {
                if (!offeredOn(store, mount)) continue
                val allowed = mount.getAsJsonArray("AllowedStores") ?: JsonArray().also { mount.add("AllowedStores", it) }
                if (allowed.none { it.asString == id.toString() }) { allowed.add(id.toString()); changed = true }
            }
            if (changed) result = copy
        }
        universal[definition] = Universal(stores, result)
        return result
    }

    /** Hull point of the eye of pod [store] fitted on physical position [position] of [mount] (unswept). */
    fun eye(mount: JsonObject, store: JsonObject, storeId: String?, position: Int): Vec3 {
        val eye = requireNotNull(AircraftArmamentRegistry.vector(store.getAsJsonObject(POD)?.get("Eye")))
        val anchors = AircraftStoreAttachment.anchors(store)
        // One pod per physical position: copy-major placements of a single copy are the positions in order.
        val placement = AircraftPylonRacks.layout(mount, store, 1, storeId).placements[position]
        return placement.point.add(anchors.hullDelta(eye.subtract(placement.anchor)))
    }

    /** An aircraft `Pod` object for pod [store] on [mount] position [position], eye included. */
    fun storePod(mount: JsonObject, store: JsonObject, storeId: String?, position: Int): JsonObject {
        val gimbal = store.getAsJsonObject(POD)
        val eye = eye(mount, store, storeId, position)
        return JsonObject().apply {
            add("Position", JsonArray().apply { add(eye.x); add(eye.y); add(eye.z) })
            for (key in listOf("YawLimit", "PitchMin", "PitchMax", "MaxZoom", "Range"))
                gimbal[key]?.let { add(key, it.deepCopy()) }
            addProperty("Source", "${store["Name"]?.asString ?: "Targeting pod"} on ${mount["Name"]?.asString ?: mount["Id"].asString}")
            add(MOUNT, JsonPrimitive(mount["Id"].asString)); addProperty(MOUNT_POSITION, position)
        }
    }

    /**
     * The pod the aircraft has now: its authored `Pod`, else the first fitted pod store (mount order, left before
     * right) whose station [detached] does not report lost. [selections] maps mount id to the fitted store id.
     */
    fun effective(definition: JsonObject, selections: (String) -> String?, store: (String) -> JsonObject?,
                  detached: (Vec3) -> Boolean): JsonObject? {
        definition.getAsJsonObject("Pod")?.let { return it }
        for (mount in AircraftArmamentRegistry.mounts(definition)) {
            if (mount["Internal"]?.asBoolean == true) continue
            val id = selections(mount["Id"].asString) ?: continue
            if (mount.getAsJsonArray("AllowedStores")?.none { it.asString == id } != false) continue
            val pod = store(id)?.takeIf(::isPod) ?: continue
            val positions = AircraftArmamentRegistry.mountPositions(mount).size
            for (position in 0 until positions) {
                val point = AircraftPylonRacks.launchPosition(mount, pod, 1, position, id)
                if (!detached(point)) return storePod(mount, pod, id, position)
            }
        }
        return null
    }

    /** [pod]'s `Position`, moved with its swept pylon at [speed] (blocks per tick); unchanged for any other pod. */
    fun sweptPosition(definition: JsonObject, pod: JsonObject, speed: Double): Vec3? {
        val base = AircraftArmamentRegistry.vector(pod["Position"]) ?: return null
        val mountId = pod[MOUNT]?.asString ?: return base
        val mount = AircraftArmamentRegistry.mounts(definition).firstOrNull { it["Id"].asString == mountId } ?: return base
        val index = pod[MOUNT_POSITION]?.asInt ?: return base
        if (index !in AircraftArmamentRegistry.mountPositions(mount).indices) return base
        return AircraftMountSweep.position(mount, index, base, speed)
    }
}
