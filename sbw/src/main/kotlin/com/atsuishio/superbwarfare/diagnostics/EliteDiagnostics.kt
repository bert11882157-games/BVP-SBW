package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import com.atsuishio.superbwarfare.api.performance.ClientRenderPerformanceDiagnostics
import com.atsuishio.superbwarfare.network.NetworkTelemetry
import com.atsuishio.superbwarfare.network.message.receive.EliteDiagnosticsStateMessage
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.loading.FMLPaths
import java.util.UUID

/** The sole diagnostics switch and sink for vehicle, weapon, flight, audio, combat and FX probes. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object EliteDiagnostics {
    @Volatile private var serverSink: EliteDiagnosticSink? = null
    @Volatile private var clientSink: EliteDiagnosticSink? = null
    @Volatile private var serverEntityScope: Set<UUID>? = null
    private var startedServerTick = 0
    private var lastOutcome = "Elite diagnostics disabled"
    private const val MAX_CAPTURE_TICKS = 20 * 300
    /** Sustained render benchmarks need counters without per-entity trace traffic. */
    private val clientCountersOnly = java.lang.Boolean.getBoolean("bvp.diagnostics.countersOnly")
    private fun clientCategoryEnabled(category: String) = !clientCountersOnly || category == "performance"

    @JvmStatic fun isServerEnabled(): Boolean = DebugFeaturePolicy.allowsDebugTools() && serverSink?.accepting == true
    @JvmStatic fun isClientEnabled(): Boolean = DebugFeaturePolicy.allowsDebugTools() && clientSink?.accepting == true
    @JvmStatic fun clientSessionId(): UUID? = clientSink?.takeIf { it.accepting }?.session
    @JvmStatic fun isEnabled(level: Level): Boolean = if (level.isClientSide) isClientEnabled() else isServerEnabled()

    fun start(server: MinecraftServer): String = startWithScope(server, null)

    /** A bounded server-only entity selection; client capture and process counters retain their scope. */
    fun startForEntities(server: MinecraftServer, entityIds: Set<UUID>): String {
        require(entityIds.isNotEmpty() && entityIds.size <= 256) { "Expected 1 to 256 diagnostic entities" }
        return startWithScope(server, entityIds.toSet())
    }

    /** Add an owned fixture entity to an active selection before recording its lifecycle. */
    @JvmStatic fun includeServerEntity(entityId: UUID) {
        val scope = serverEntityScope ?: return
        if (entityId in scope) return
        require(scope.size < 256) { "Diagnostic entity selection is full" }
        serverEntityScope = scope + entityId
        serverSink?.offer(-1, "session", "entity_scope_added", mapOf("entity_uuid" to entityId.toString()))
    }

    private fun startWithScope(server: MinecraftServer, entityIds: Set<UUID>?): String {
        if (!DebugFeaturePolicy.allowsDebugTools()) return "Elite diagnostics are disabled in this playtest artifact"
        stop(server)
        NetworkTelemetry.resetElite()
        val session = UUID.randomUUID()
        serverEntityScope = entityIds
        serverSink = open(session, "server", entityIds)
        startedServerTick = server.tickCount
        server.playerList.players.forEach { sendPacketTo(it, EliteDiagnosticsStateMessage(session, true)) }
        return "Elite diagnostics enabled (all categories, maximum 5 minutes). Output: ${serverSink!!.path}"
    }

    fun stop(server: MinecraftServer): String {
        NetworkTelemetry.flushElite(force = true)
        val previous = serverSink
        serverSink = null
        serverEntityScope = null
        previous?.close()
        server.playerList.players.forEach {
            sendPacketTo(it, EliteDiagnosticsStateMessage(previous?.session ?: UUID(0, 0), false))
        }
        lastOutcome = "Elite diagnostics disabled" + (previous?.let {
            "; output: ${it.path}; dropped=${it.dropped.get()}" + (it.failure?.let { failure -> "; error=$failure" } ?: "")
        } ?: "")
        return lastOutcome
    }

    fun status(): String = serverSink?.let {
        "Elite diagnostics ${if (it.accepting) "enabled" else "stopped"}; dropped=${it.dropped.get()}; " +
            "output=${it.path}" + (it.failure?.let { failure -> "; error=$failure" } ?: "")
    } ?: lastOutcome

    /** Called only by the server-owned diagnostics state packet; distinct sinks in integrated play. */
    @JvmStatic fun setClientSession(session: UUID, enabled: Boolean) {
        if (!DebugFeaturePolicy.allowsDebugTools()) {
            ClientRenderPerformanceDiagnostics.setEnabled(false)
            return
        }
        if (enabled && clientSink?.session == session && isClientEnabled()) return
        if (!isServerEnabled() && !isClientEnabled() && enabled) NetworkTelemetry.resetElite()
        if (!enabled) NetworkTelemetry.flushElite(force = true)
        clientSink?.close()
        clientSink = if (enabled) open(session, "client") else null
        ClientRenderPerformanceDiagnostics.setEnabled(enabled)
    }

    private fun open(session: UUID, side: String, entityIds: Set<UUID>? = null): EliteDiagnosticSink {
        val versions = ModList.get().mods.filter { it.modId in setOf(Mod.MODID, "berts_vehicle_pack", "ballistics", "tacz", "clowder_modern") }
            .associate { it.modId to it.version.toString() }
        val artifacts = versions.keys.associateWith { ModList.get().getModFileById(it).file.filePath.toString() }
        return EliteDiagnosticSink(FMLPaths.GAMEDIR.get().resolve("logs/elite-diagnostics/$session-$side.jsonl"),
            session, side, mapOf("mods" to versions, "code_source" to
                EliteDiagnostics::class.java.protectionDomain.codeSource?.location?.toString(),
                "artifacts" to artifacts, "max_seconds" to 300,
                "client_probe_mode" to if (side == "client" && clientCountersOnly) "performance_counters_only" else "full",
                "scope" to if (entityIds == null) "loaded vehicles and projectiles; no chat/input content"
                    else "selected server entities; process counters remain aggregate; no chat/input content",
                "selected_entity_uuids" to entityIds?.map(UUID::toString)?.sorted()),
            onFailure = { Mod.LOGGER.error("Elite diagnostics {} writer failed: {}", side, it) })
    }

    /** Java-friendly pairs are snapshotted as scalar values; entities are never sent to the writer. */
    @JvmStatic fun record(entity: Entity, category: String, event: String, vararg fields: Any?) {
        if (!isEnabled(entity.level())) return
        if (entity.level().isClientSide && !clientCategoryEnabled(category)) return
        if (!entity.level().isClientSide && serverEntityScope?.contains(entity.uuid) == false) return
        val data = pairs(fields)
        data["entity_uuid"] = entity.uuid.toString()
        data["entity_id"] = entity.id
        data["entity_type"] = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.type).toString()
        data["dimension"] = entity.level().dimension().location().toString()
        emit(entity.level().isClientSide, entity.level().gameTime, category, event, data)
    }

    @JvmStatic fun recordClient(tick: Long, category: String, event: String, vararg fields: Any?) {
        if (!isClientEnabled() || !clientCategoryEnabled(category)) return
        emit(true, tick, category, event, pairs(fields))
    }

    /** Process-wide counters include Netty and both sides of an integrated server, explicitly. */
    internal fun recordProcess(category: String, event: String, vararg fields: Pair<String, Any?>) {
        val sink = serverSink?.takeIf { it.accepting } ?: clientSink?.takeIf { it.accepting } ?: return
        if (sink === clientSink && !clientCategoryEnabled(category)) return
        sink.offer(-1, category, event, fields.toMap() + ("scope" to "process_aggregate"))
    }

    private fun emit(client: Boolean, tick: Long, category: String, event: String, data: Map<String, Any?>) {
        (if (client) clientSink else serverSink)?.offer(tick, category, event, data)
    }

    private fun pairs(fields: Array<out Any?>): MutableMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>(fields.size / 2)
        for (index in 0 until fields.size - 1 step 2) {
            val value = fields[index + 1]
            result[fields[index].toString().take(96)] = when (value) {
                null, is Boolean -> value
                is Number -> if (value.toDouble().isFinite()) value else null
                else -> value.toString().take(4096)
            }
        }
        return result
    }

    @SubscribeEvent fun login(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val sink = serverSink?.takeIf { it.accepting } ?: return
        sendPacketTo(player, EliteDiagnosticsStateMessage(sink.session, true))
    }

    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || serverSink == null) return
        if (!isServerEnabled() || event.server.tickCount - startedServerTick >= MAX_CAPTURE_TICKS) stop(event.server)
    }

    @SubscribeEvent fun shutdown(event: ServerStoppingEvent) { stop(event.server) }
}
