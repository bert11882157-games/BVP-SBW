package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.data.DataLoader
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType
import com.atsuishio.superbwarfare.tools.toKxJson
import com.google.gson.JsonObject

/** Configuration-bound derived engine. This owner never changes velocity, fuel or occupants. */
internal class VehicleEngineRuntime(private val onFailure: (Exception) -> Unit) {
    private var configurationOwner: Any? = null
    var engine: EngineInfo? = null
        private set

    /** Returns true only when a new configuration was bound; invalid data is logged once. */
    fun bind(owner: Any, type: EngineType, source: JsonObject, suppliedEngine: EngineInfo?): Boolean {
        if (configurationOwner === owner) return false
        val firstBinding = configurationOwner == null
        configurationOwner = owner
        engine = if (firstBinding && suppliedEngine != null) suppliedEngine else try {
            val serializer = when (type) {
                EngineType.WHEEL -> EngineInfo.Wheel.serializer()
                EngineType.TRACK -> EngineInfo.Track.serializer()
                EngineType.HELICOPTER -> EngineInfo.Helicopter.serializer()
                EngineType.SHIP -> EngineInfo.Ship.serializer()
                EngineType.AIRCRAFT -> EngineInfo.Aircraft.serializer()
                EngineType.WHEELCHAIR -> EngineInfo.WheelChair.serializer()
                EngineType.TOM6 -> EngineInfo.Tom6.serializer()
                else -> null
            }
            serializer?.let { DataLoader.JSON.decodeFromJsonElement(it, source.toKxJson()) }
        } catch (error: Exception) {
            onFailure(error)
            null
        }
        return true
    }
}
