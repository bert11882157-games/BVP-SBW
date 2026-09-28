package com.atsuishio.superbwarfare.data

import com.atsuishio.superbwarfare.Mod
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.serialization.json.JsonElement as KxElement
import kotlinx.serialization.json.JsonObject as KxObject
import kotlinx.serialization.json.JsonArray as KxArray
import kotlinx.serialization.json.JsonNull as KxNull
import kotlinx.serialization.json.JsonPrimitive as KxPrimitive

/** Converts the current override tree directly, retaining mutable-input and numeric semantics. */
private fun propertyJson(value: JsonElement): KxElement = when (value) {
    is JsonNull -> KxNull
    is JsonObject -> KxObject(buildMap(value.size()) {
        for ((key, child) in value.entrySet()) {
            // Match the existing Gson default: omit null object members.
            if (!child.isJsonNull) put(key, propertyJson(child))
        }
    })
    is JsonArray -> KxArray(List(value.size()) { propertyJson(value[it]) })
    is JsonPrimitive -> when {
        value.isString -> KxPrimitive(value.asString)
        value.isBoolean -> KxPrimitive(value.asBoolean)
        else -> KxPrimitive(value.asNumber)
    }
    else -> error("Unsupported JSON element")
}

// TODO 取代StringPropModifier
class JsonPropertyModifier<DATA : DefaultDataSupplier<DEFAULT_DATA>, DEFAULT_DATA>(
    // TODO 实现VehicleProp后禁止该项为空
    val props: List<Prop<DATA, DEFAULT_DATA, *, *, *>>? = null
) : OldPropertyModifier<DATA, DEFAULT_DATA>, PropertyModifier<DATA, DEFAULT_DATA> {
    private var obj: JsonObject? = null
    private var str: String? = null

    fun update(`object`: JsonObject?) {
        if (`object` !== this.obj) resolved = null
        this.obj = `object`
        this.str = null
    }

    /**
     * The override converted and deserialized once per override object. Property maps are rebuilt whenever a gun's
     * state tag changes (every shot, every heat or reload step), and each rebuild converted the whole override tree
     * and deserialized every property again. Immutable values (numbers, strings, booleans, enums) are reused;
     * anything else is deserialized fresh each time, as before, so no rebuild can see another's mutable value.
     */
    private class Resolved(val source: JsonObject, val entries: List<Pair<Prop<*, *, *, *, *>, KxElement>>,
                           val immutable: Array<Any?>)
    private var resolved: Resolved? = null

    fun update(string: String?) {
        if (string.isNullOrEmpty() || string == this.str) return
        this.str = string

        try {
            update(DataLoader.GSON.fromJson(string, JsonObject::class.java))
        } catch (exception: Exception) {
            Mod.LOGGER.error("Failed to parse string prop modifier: {}", string, exception)
        }
    }

    override fun computeProperties(data: DATA, rawData: DEFAULT_DATA): DEFAULT_DATA {
        if (obj == null || obj!!.size() == 0) return rawData

        val dataJson = DataLoader.GSON.toJsonTree(rawData).getAsJsonObject()
        for (entry in obj!!.entrySet()) {
            dataJson.add(entry.key, entry.value)
        }

        return DataLoader.GSON.fromJson(dataJson, rawData!!.javaClass)
    }

    private val propsMap by lazy {
        props?.associateBy { it.serializationName } ?: emptyMap()
    }

    override fun modifyProperty(modifier: PMC<DATA, DEFAULT_DATA>) {
        val source = obj ?: return
        var cache = resolved
        if (cache == null || cache.source !== source) {
            val element = propertyJson(source) as KxObject
            val entries = ArrayList<Pair<Prop<*, *, *, *, *>, KxElement>>(element.size)
            for ((key, value) in element) entries.add((propsMap[key] ?: continue) to value)
            cache = Resolved(source, entries, arrayOfNulls(entries.size))
            resolved = cache
        }

        for ((index, entry) in cache.entries.withIndex()) {
            val (prop, value) = entry
            val deserialized = cache.immutable[index] ?: try {
                prop.deserialize(value)!!.also {
                    if (it is Number || it is String || it is Boolean || it is Enum<*>) cache.immutable[index] = it
                }
            } catch (exception: Exception) {
                Mod.LOGGER.error("Failed to deserialize prop: {}", value, exception)
                continue
            }
            @Suppress("UNCHECKED_CAST")
            modifier[prop as Prop<DATA, DEFAULT_DATA, *, Any, *>] = deserialized
        }
    }
}
