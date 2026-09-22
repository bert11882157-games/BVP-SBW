package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightStrategy
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.registries.ForgeRegistries
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID

/** Operator-created, bounded test entities. This never runs a test or creates a target automatically. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object AamTestTargets {
    private const val MARKER = "SbwAamTestTarget"
    private val ticket = TicketType.create<UUID>("sbw_aam_test", Comparator { a, b -> a.compareTo(b) }, 40)
    private class Run(val owner: ServerPlayer, val vehicle: VehicleEntity, val heading: Float) {
        val level = owner.serverLevel()
        val expires = level.gameTime + AamTargetFlight.LIFETIME_TICKS
        val flight = AamTargetFlight(heading)
        val tickets = mutableSetOf<ChunkPos>()
        var wreckAt: Long? = null
    }
    private val owners = linkedMapOf<UUID, Run>()
    private val vehicles = IdentityHashMap<VehicleEntity, Run>()
    private val outcomes = linkedMapOf<UUID, String>()

    @JvmStatic fun flightStrategy(vehicle: VehicleEntity): VehicleFlightStrategy? =
        if (vehicle.level().isClientSide || vehicle.isRemoved || vehicle.isWreck || vehicle.health <= 0) null
        else vehicles[vehicle]?.flight

    fun spawn(player: ServerPlayer, distance: Int): String {
        require(distance in AamTargetFlight.MIN_DISTANCE..AamTargetFlight.MAX_DISTANCE) { "Target distance must be 64..512 blocks." }
        require(owners.containsKey(player.uuid) || owners.size < AamTargetFlight.MAX_TARGETS) {
            "Four AAM targets are already active; clear one or wait for cleanup."
        }
        val level = player.serverLevel()
        val heading = (player.vehicle as? VehicleEntity)?.yRot ?: player.yRot
        val origin = player.eyePosition
        val ahead = origin.add(Vec3.directionFromRotation(0F, heading).scale(distance.toDouble()))
        val block = BlockPos.containing(ahead)
        require(level.worldBorder.isWithinBounds(block)) { "Target would be outside the world border." }
        // One explicit command may load its spawn chunk; subsequent rolling tickets are bounded.
        val terrain = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, block.x, block.z)
        val position = AamTargetFlight.spawnPosition(origin, heading, distance, terrain, level.maxBuildHeight)
        val type = listOf("berts_vehicle_pack:mig_15bis", "superbwarfare:a_10a")
            .firstNotNullOfOrNull { ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation(it)) }
            ?: throw IllegalArgumentException("No supported aircraft is registered.")
        val vehicle = type.create(level) as? VehicleEntity
            ?: throw IllegalArgumentException("Registered target is not an SBW vehicle.")
        require(vehicle.vehicleType == VehicleType.AIRPLANE) { "Target must be an aircraft." }
        vehicle.moveTo(position.x, position.y, position.z, heading, 0F)
        vehicle.applyVehicleFlightAttitude(heading, 0F, 0F)
        vehicle.deltaMovement = AamTargetFlight.result(heading).motion
        vehicle.setOnGround(false)
        vehicle.lastDriverUUID = ""
        vehicle.isInvulnerable = false
        vehicle.customName = Component.literal("AAM TEST TARGET")
        vehicle.isCustomNameVisible = true
        vehicle.persistentData.putBoolean(MARKER, true)
        val run = Run(player, vehicle, heading)
        val previous = owners.put(player.uuid, run)
        vehicles[vehicle] = run
        try {
            updateTickets(run)
            require(level.addFreshEntity(vehicle)) { "The server rejected the target spawn." }
            vehicle.health = vehicle.getMaxHealth()
        } catch (failure: Exception) {
            owners.remove(player.uuid)
            if (previous != null) owners[player.uuid] = previous
            remove(run)
            throw failure
        }
        previous?.let(::remove)
        outcomes.remove(player.uuid)
        return "AAM target spawned ${distance}m ahead, flying straight at 108 km/h. " +
            "It expires in 60s. /sbw test status; /sbw test clear."
    }

    fun status(player: ServerPlayer): String {
        val run = owners[player.uuid] ?: return outcomes[player.uuid] ?: "No AAM target. Use /sbw test aircraft [64..512]."
        val v = run.vehicle
        val distance = if (player.level() === run.level) "${player.distanceTo(v).toInt()}m" else "other dimension"
        return String.format(Locale.ROOT, "AAM target %s: HP %.0f/%.0f, %s, %ds left; %s.",
            v.uuid.toString().take(8), v.health.coerceAtLeast(0F), v.getMaxHealth(), distance,
            ((run.expires - run.level.gameTime).coerceAtLeast(0) + 19) / 20,
            if (v.isWreck || v.health <= 0) "destroyed" else if (v.onGround()) "grounded" else "airborne")
    }

    fun clear(player: ServerPlayer): String {
        val run = owners.remove(player.uuid) ?: return "No active AAM target to clear."
        remove(run)
        return "AAM target cleared.".also { recordOutcome(player.uuid, it) }
    }

    private fun recordOutcome(owner: UUID, message: String) {
        outcomes[owner] = message
        while (outcomes.size > 16) outcomes.remove(outcomes.keys.first())
    }

    private fun updateTickets(run: Run) {
        val next = run.vehicle.position().add(AamTargetFlight.result(run.heading).motion.scale(8.0))
        val needed = setOf(run.vehicle.chunkPosition(), ChunkPos(BlockPos.containing(next)))
        for (old in run.tickets - needed) run.level.chunkSource.removeRegionTicket(ticket, old, 2, run.vehicle.uuid)
        for (pos in needed) if (pos !in run.tickets || run.level.gameTime % 20L == 0L)
            run.level.chunkSource.addRegionTicket(ticket, pos, 2, run.vehicle.uuid)
        run.tickets.clear(); run.tickets.addAll(needed)
    }

    private fun remove(run: Run) {
        vehicles.remove(run.vehicle)
        for (pos in run.tickets) run.level.chunkSource.removeRegionTicket(ticket, pos, 2, run.vehicle.uuid)
        run.tickets.clear()
        if (!run.vehicle.isRemoved) run.vehicle.discard()
    }

    private fun finish(run: Run, reason: String) {
        if (owners[run.owner.uuid] !== run) return
        owners.remove(run.owner.uuid)
        val message = "$reason Final target HP ${run.vehicle.health.coerceAtLeast(0F).toInt()}/${run.vehicle.getMaxHealth().toInt()}."
        recordOutcome(run.owner.uuid, message)
        if (run.owner.connection.connection.isConnected) run.owner.sendSystemMessage(Component.literal(message))
        remove(run)
    }

    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        for (run in owners.values.toList()) {
            val vehicle = run.vehicle
            if (!run.owner.connection.connection.isConnected || run.owner.level() !== run.level) {
                finish(run, "AAM target cleaned up after owner left."); continue
            }
            if (vehicle.isRemoved) { finish(run, "AAM target removed."); continue }
            if (vehicle.isWreck || vehicle.health <= 0) {
                val deadAt = run.wreckAt ?: run.level.gameTime.also { run.wreckAt = it }
                if (vehicle.sympatheticDetonated && run.level.gameTime - deadAt >= 100) {
                    finish(run, "AAM target destroyed."); continue
                }
            }
            if (run.level.gameTime >= run.expires) { finish(run, "AAM target expired."); continue }
            if (vehicle.isVehicle) { finish(run, "AAM target test ended because it was occupied."); continue }
            updateTickets(run)
        }
    }

    @SubscribeEvent fun joined(event: EntityJoinLevelEvent) {
        if (event.level is ServerLevel && event.entity.persistentData.getBoolean(MARKER) &&
            !vehicles.containsKey(event.entity)) {
            event.isCanceled = true
            event.entity.discard()
        }
    }

    @SubscribeEvent fun logout(event: PlayerEvent.PlayerLoggedOutEvent) {
        owners.remove(event.entity.uuid)?.let(::remove)
        outcomes.remove(event.entity.uuid)
    }

    @SubscribeEvent fun stopping(event: ServerStoppingEvent) {
        owners.values.toList().forEach(::remove)
        owners.clear(); vehicles.clear(); outcomes.clear()
    }
}
