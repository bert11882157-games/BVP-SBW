package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleIndex
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.util.UUID
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/** Explicit disposable-world fixture. No forced chunks or test-only readiness bypass. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object FarTerrainTestScenario {
    private const val X = 4096
    private const val Z = 4096
    private const val Y = 100
    private const val DISTANCE = 160
    private val identity = UUID.nameUUIDFromBytes("OfflinePlayer:BvpDiagnostics".toByteArray(Charsets.UTF_8))

    private class Fixture : SavedData() {
        var near: UUID? = null
        var outside: UUID? = null
        override fun save(tag: CompoundTag): CompoundTag {
            near?.let { tag.putUUID("Near", it) }
            outside?.let { tag.putUUID("Outside", it) }
            return tag
        }
        companion object {
            fun load(tag: CompoundTag) = Fixture().also {
                if (tag.hasUUID("Near")) it.near = tag.getUUID("Near")
                if (tag.hasUUID("Outside")) it.outside = tag.getUUID("Outside")
            }
        }
    }
    private fun fixture(level: ServerLevel) = level.dataStorage.computeIfAbsent(Fixture::load, ::Fixture, "sbw_far_test")

    private fun allowed(player: ServerPlayer): Boolean {
        val server = player.server
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios") &&
            server.isDedicatedServer && !server.usesAuthentication() && server.localIp == "127.0.0.1" &&
            server.port == 25579 && server.playerCount == 1 && player.gameProfile.name == "BvpDiagnostics" &&
            player.uuid == identity && player.abilities.instabuild && !player.isSpectator &&
            player.level().dimension() == Level.OVERWORLD &&
            Files.isRegularFile(server.getWorldPath(LevelResource.ROOT).resolve("sbw-disposable-tests.marker"))
    }

    @SubscribeEvent fun register(event: RegisterCommandsEvent) {
        event.dispatcher.register(Commands.literal("sbw_far_test").requires { it.hasPermission(2) }
            .then(Commands.literal("prepare").then(Commands.argument("entity", ResourceLocationArgument.id()).executes {
                val player = it.source.playerOrException
                if (!allowed(player)) return@executes 0
                val id = ResourceLocationArgument.getId(it, "entity")
                val type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) ?: return@executes 0
                prepare(player, type)
            }))
            .then(Commands.literal("observe").executes { run(it.source.playerOrException, "observe") })
            .then(Commands.literal("away").executes { run(it.source.playerOrException, "away") })
            .then(Commands.literal("wall").executes { run(it.source.playerOrException, "wall") })
            .then(Commands.literal("reveal").executes { run(it.source.playerOrException, "reveal") })
            .then(Commands.literal("status").executes { run(it.source.playerOrException, "status") })
            .then(Commands.literal("cleanup").executes { run(it.source.playerOrException, "cleanup") }))
    }

    private fun prepare(player: ServerPlayer, type: EntityType<*>): Int {
        val level = player.serverLevel()
        // Validate the supplied registry ID before touching fixture terrain.
        val near = type.create(level) as? VehicleEntity ?: return 0
        val outside = type.create(level) as? VehicleEntity ?: return 0
        val state = fixture(level)
        if (state.near != null || state.outside != null) return 0
        for ((entity, distance) in listOf(near to DISTANCE, outside to 256)) {
            level.getChunk(X shr 4, (Z + distance) shr 4)
            for (x in X - 8..X + 8) for (z in Z + distance - 8..Z + distance + 8)
                level.setBlock(BlockPos(x, Y - 1, z), Blocks.STONE.defaultBlockState(), 3)
            entity.moveTo(X + 0.5, Y.toDouble(), Z + distance + 0.5, 0f, 0f)
            entity.setNoGravity(true)
            entity.addTag("sbw_far_test")
            check(level.addFreshEntity(entity))
            EliteDiagnostics.includeServerEntity(entity.uuid)
        }
        state.near = near.uuid; state.outside = outside.uuid; state.setDirty()
        observe(player, false)
        player.sendSystemMessage(Component.literal("FAR_FIXTURE near=${near.uuid} outside=${outside.uuid} origin=$X,$Y,$Z target_distance=$DISTANCE; set client/server view distance to4 for radius192"))
        return 1
    }

    private fun observe(player: ServerPlayer, away: Boolean) {
        player.abilities.flying = true
        player.onUpdateAbilities()
        player.teleportTo(player.serverLevel(), X + 0.5, Y + 2.0, Z + if (away) -512.0 else 0.5, 0f, 0f)
    }

    private fun run(player: ServerPlayer, operation: String): Int {
        if (!allowed(player)) return 0
        val level = player.serverLevel()
        val state = fixture(level)
        when (operation) {
            "observe" -> observe(player, false)
            "away" -> observe(player, true)
            "wall", "reveal" -> wall(level, operation == "wall")
            "cleanup" -> {
                val fixtures = listOf(state.near to DISTANCE, state.outside to 256)
                fixtures.forEach { (_, distance) -> level.getChunk(X shr 4, (Z + distance) shr 4) }
                if (fixtures.any { (id, _) -> id != null && level.getEntity(id) == null &&
                        FarVehicleIndex.get(level).positions.containsKey(id) }) {
                    player.sendSystemMessage(Component.literal("FAR_FIXTURE cleanup pending entity loading; repeat cleanup after the next server ticks. UUIDs retained."))
                    return 0
                }
                for ((id, distance) in fixtures) {
                    // Explicit cleanup may load only the two fixture chunks.
                    level.getChunk(X shr 4, (Z + distance) shr 4)
                    id?.let { level.getEntity(it) }?.takeIf { "sbw_far_test" in it.tags }?.discard()
                    for (x in X - 8..X + 8) for (z in Z + distance - 8..Z + distance + 8)
                        level.setBlock(BlockPos(x, Y - 1, z), Blocks.AIR.defaultBlockState(), 3)
                }
                wall(level, false)
                state.near = null; state.outside = null; state.setDirty()
            }
        }
        val near = state.near?.let { level.getEntity(it) as? VehicleEntity }
        val outside = state.outside?.let { level.getEntity(it) as? VehicleEntity }
        val status = "FAR_FIXTURE operation=$operation near=${state.near} near_loaded=${near != null} near_ready=${near?.let { FarTerrainServer.ready(player, it) }} outside=${state.outside} outside_loaded=${outside != null} radius=${FarTerrainServer.radius(player)} revision=${FarTerrainServer.revision(player)}"
        player.sendSystemMessage(Component.literal(status))
        EliteDiagnostics.record(player, "far_terrain_test", "STATUS", "status", status)
        // getChunkNow observes existing FULL chunks without creating tickets or awaiting loads.
        // Entity accessibility is recorded separately: it can lag terrain availability.
        for ((label, distance) in listOf("near" to DISTANCE, "outside" to 256, "cover" to 108)) {
            val chunkX = X shr 4
            val chunkZ = (Z + distance) shr 4
            val key = ChunkPos.asLong(chunkX, chunkZ)
            val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ)
            val owned = FarTerrainServer.ownsTerrainTicket(level, key)
            val entitiesLoaded = level.areEntitiesLoaded(key)
            player.sendSystemMessage(Component.literal(
                "FAR_CHUNK sample=$label x=$chunkX z=$chunkZ full_present=${chunk != null} " +
                    "entities_loaded=$entitiesLoaded terrain_ticket=$owned"))
            EliteDiagnostics.record(player, "far_terrain_test", "CHUNK_STATE",
                "sample", label, "chunk_x", chunkX, "chunk_z", chunkZ,
                "full_present", chunk != null, "entities_loaded", entitiesLoaded,
                "terrain_ticket", owned)
        }
        return 1
    }

    private fun wall(level: ServerLevel, solid: Boolean) {
        for (x in X - 24..X + 24) for (y in Y - 3..Y + 16) for (z in Z + 108..Z + 109)
            level.setBlock(BlockPos(x, y, z), (if (solid) Blocks.STONE else Blocks.AIR).defaultBlockState(), 3)
    }
}
