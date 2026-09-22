package com.atsuishio.superbwarfare.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.atsuishio.superbwarfare.network.message.send.AircraftArmamentRequestMessage
import com.atsuishio.superbwarfare.network.message.receive.AircraftArmamentStateMessage
import com.atsuishio.superbwarfare.tools.sendPacketTo
import com.atsuishio.superbwarfare.tools.sendPacketToServer
import net.minecraft.client.Minecraft
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

object AircraftArmamentNetwork {
    private val epochs = mutableMapOf<UUID, Long>()
    private var connection: Any? = null
    private var sequence = 0L
    @JvmStatic fun resetClient() { epochs.clear(); connection = null; sequence = 0 }
    @JvmStatic fun request(operation: String, vehicle: UUID, payload: JsonObject) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val current = minecraft.connection ?: return
        if (connection !== current) { resetClient(); connection = current }
        val json = payload.toString()
        if (json.length > 4096) return
        sendPacketToServer(AircraftArmamentRequestMessage(vehicle, level.dimension().location(),
            epochs[vehicle] ?: 0, ++sequence, operation, json))
    }
    @JvmStatic fun receive(payload: JsonObject) {
        val id = runCatching { UUID.fromString(payload.get("Vehicle").asString) }.getOrNull() ?: return
        val minecraft = Minecraft.getInstance()
        val current = minecraft.connection ?: return
        if (connection !== current) { resetClient(); connection = current }
        val level = minecraft.level ?: return
        if (payload.get("Dimension")?.asString != level.dimension().location().toString()) return
        payload.get("Epoch")?.let { epochs[id] = it.asLong }
        com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient.receive(payload)
    }
    @JvmStatic fun send(player: ServerPlayer, payload: JsonObject) {
        val text = payload.toString()
        require(text.length <= 65536) { "Aircraft snapshot exceeds schema limit" }
        sendPacketTo(player, AircraftArmamentStateMessage(text))
    }
    fun parseRequest(text: String): JsonObject? {
        if (text.length > 4096) return null
        var depth = 0; var quoted = false; var escaped = false
        for (char in text) {
            if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
            else when (char) { '"' -> quoted = true; '{', '[' -> { if (++depth > 8) return null }; '}', ']' -> depth-- }
        }
        return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
    }
}
