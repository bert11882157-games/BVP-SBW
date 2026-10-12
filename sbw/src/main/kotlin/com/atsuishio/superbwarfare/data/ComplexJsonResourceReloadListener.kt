package com.atsuishio.superbwarfare.data

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.event.LoadingDataEvent
import com.atsuishio.superbwarfare.api.event.LoadingJsonEvent
import com.atsuishio.superbwarfare.data.gun.DefaultGunData
import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData
import com.atsuishio.superbwarfare.data.vehicle.VehicleAttachmentDataValidator
import com.atsuishio.superbwarfare.tools.postEvent
import kotlinx.serialization.serializer
import net.minecraft.resources.FileToIdConverter
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller

class ComplexJsonResourceReloadListener(private val data: MutableMap<String, DataLoader.GeneralData<*>>) :
    SimplePreparableReloadListener<List<ComplexJsonResourceReloadListener.PreparedData>>() {

    override fun prepare(resourceManager: ResourceManager, profiler: ProfilerFiller): List<PreparedData> {
        return this.data.map { (name, value) ->
            val map = HashMap<String, Any>()

            val converter = FileToIdConverter.json(name)
            for (entry in converter.listMatchingResources(resourceManager).entries) {
                val location = entry.key
                val pathLocation = converter.fileToId(location)

                try {
                    entry.value.openAsReader().use { reader ->
                        val id = pathLocation.toString()

                        var jsonStr = reader.lineSequence().joinToString("\n")
                        val jsonEvent = LoadingJsonEvent(id, jsonStr)
                        if (!postEvent(jsonEvent)) {
                            jsonStr = jsonEvent.jsonStr
                        }

                        var data = if (value.isKtData) {
                            DataLoader.JSON.decodeFromString(serializer(value.type), jsonStr)
                        } else {
                            DataLoader.GSON.fromJson(jsonStr, value.type)
                        }

                        if (data is IDBasedData<*>) {
                            data.id = id
                        }

                        if (data is DefaultGunData) {
                            val event = LoadingDataEvent.Gun(id, data)
                            if (!postEvent(event)) {
                                data = event.data
                            }
                        }

                        if (data is DefaultVehicleData) {
                            val event = LoadingDataEvent.Vehicle(id, data)
                            if (!postEvent(event)) {
                                data = event.data
                            }
                            VehicleAttachmentDataValidator.validate(id, data)
                        }

                        map.put(id, data)
                    }
                } catch (exception: Exception) {
                    Mod.LOGGER.error("Couldn't parse data file {} from {}", pathLocation, location, exception)
                }
            }

            PreparedData(value, map)
        }
    }

    override fun apply(prepared: List<PreparedData>, resourceManager: ResourceManager, profiler: ProfilerFiller) {
        for ((data, map) in prepared) {
            data.dataMap = map
            data.onReload?.accept(map)
        }
    }

    data class PreparedData(val data: DataLoader.GeneralData<*>, val map: HashMap<String, Any>)
}
