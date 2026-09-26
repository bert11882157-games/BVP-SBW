package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager
import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentRegistry
import com.atsuishio.superbwarfare.api.aircraft.AircraftPylonRacks
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.tools.InventoryTool
import com.google.gson.JsonObject
import net.minecraft.commands.Commands
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.registries.ForgeRegistries
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Candidate-only model/store rendering workload, not a vehicle physics benchmark. */
@EventBusSubscriber(modid = Mod.MODID)
object MixedFleetDiagnostics {
    private var active: Run? = null
    private val ids = listOf("f_14a", "f_14d", "b_1b", "su_35", "a_10", "su_39", "ah_64d", "ch_46e",
        "bmd_1", "btr_zd", "bmp2", "marder_1a2", "cv9040_no_net", "lav25", "zsl_92", "gaz_3937_vodnik_aa")
    private val phases = listOf("empty", "clean", "loaded", "removed", "cleanup")
    private fun privateOperator(p: ServerPlayer): Boolean {
        val remote = p.connection.connection.remoteAddress as? InetSocketAddress ?: return false
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios") &&
            p.server.isDedicatedServer && !p.server.usesAuthentication() && p.server.localIp == "127.0.0.1" &&
            p.server.port == 25579 && p.server.playerCount == 1 && p.level().dimension() == Level.OVERWORLD &&
            p.isAlive && !p.isSpectator && p.abilities.instabuild && remote.address.isLoopbackAddress &&
            p.gameProfile.name == "BvpDiagnostics" && p.uuid == UUID.nameUUIDFromBytes(
                "OfflinePlayer:BvpDiagnostics".toByteArray(StandardCharsets.UTF_8))
    }

    @SubscribeEvent fun commands(event: RegisterCommandsEvent) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
        event.dispatcher.register(Commands.literal("bvp_mixed_fleet_test").requires { it.hasPermission(2) }
            .executes { context ->
                val player = context.source.playerOrException
                if (active != null || !privateOperator(player) || player.vehicle != null || EliteDiagnostics.isServerEnabled())
                    return@executes 0
                val run = Run(player)
                active = run
                try { run.prepare() } catch (failure: Exception) { run.finish("ERROR", failure.toString()) }
                if (active != null) 1 else 0
            })
        event.dispatcher.register(Commands.literal("bvp_mixed_fleet_stop").requires { it.hasPermission(2) }
            .executes { active?.finish("STOPPED", "Operator request"); 1 })
    }

    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        val run = active ?: return
        if (event.server != run.player.server) return
        try {
            if (event.phase == TickEvent.Phase.START) run.hold() else run.tick()
        } catch (failure: Exception) { run.finish("ERROR", failure.toString()) }
    }

    @SubscribeEvent fun stopping(event: ServerStoppingEvent) {
        if (active?.player?.server === event.server) active?.finish("STOPPED", "Server stopping")
    }

    private class Run(val player: ServerPlayer) {
        private val level = player.serverLevel()
        private val saved = player.position()
        private val yaw = player.yRot
        private val pitch = player.xRot
        private val flying = player.abilities.flying
        private val origin = Vec3(kotlin.math.floor(saved.x), saved.y + 4, kotlin.math.floor(saved.z))
        private val observer = origin.add(72.0, 40.0, -145.0)
        private val fleet = linkedMapOf<VehicleEntity, Vec3>()
        private var age = 0
        private var phase = "setup"
        private var capture = false
        /** Loadout munitions are bought when fitted; the workload fits real stores without spending the operator's. */
        private var grantedAmmoBox = false

        fun record(event: String, vararg values: Pair<String, Any>) {
            val fields = (listOf("age" to age, "phase" to phase) + values).flatMap { listOf(it.first, it.second) }
            EliteDiagnostics.record(player, "mixed_fleet", event, *fields.toTypedArray())
        }

        fun prepare() {
            check(EliteDiagnostics.start(player.server).startsWith("Elite diagnostics enabled"))
            capture = true
            player.abilities.flying = true; player.onUpdateAbilities()
            if (!player.isCreative && !InventoryTool.hasCreativeAmmoBox(player)) {
                check(player.inventory.add(ItemStack(ModItems.CREATIVE_AMMO_BOX.get()))) { "No room for a creative ammo box" }
                grantedAmmoBox = true
            }
            observe()
            record("START", "vehicles" to 16, "ids" to ids, "phases" to phases,
                "warmup_ticks_per_phase" to 200, "measure_ticks_per_phase" to 600,
                "required_client_view_chunks" to 24,
                "anchored_render_fixture" to true, "observer" to observer.toString())
        }

        private fun observe() {
            player.stopRiding()
            player.teleportTo(level, observer.x, observer.y, observer.z, 0f, 10.5f)
            player.deltaMovement = Vec3.ZERO
        }

        fun hold() {
            check(privateOperator(player) && player.vehicle == null) { "Private observer context changed" }
            for ((vehicle, position) in fleet) {
                check(!vehicle.isRemoved && !vehicle.isWreck) { "Fixture vehicle lost" }
                vehicle.setPos(position); vehicle.deltaMovement = Vec3.ZERO; vehicle.setOnGround(true)
            }
        }

        private fun spawn() {
            for (row in 0..3) for (column in 0..3) {
                val id = ids[row * 4 + column]
                val type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation("berts_vehicle_pack", id))
                val vehicle = type?.create(level) as? VehicleEntity ?: error("Missing fixture $id")
                val point = origin.add(column * 48.0, 0.0, row * 48.0)
                level.getChunk(kotlin.math.floor(point.x).toInt() shr 4, kotlin.math.floor(point.z).toInt() shr 4)
                vehicle.load(CompoundTag()); vehicle.moveTo(point.x, point.y, point.z, 180f, 0f)
                vehicle.setNoGravity(true); vehicle.setOnGround(true)
                check(level.addFreshEntity(vehicle)) { "Fixture insertion failed $id" }
                fleet[vehicle] = point
                record("SPAWN", "vehicle" to vehicle.uuid.toString(), "type" to id, "position" to point.toString())
            }
        }

        private fun fit(loaded: Boolean) {
            for (vehicle in fleet.keys) {
                val definition = AircraftArmamentManager.definition(vehicle) ?: continue
                if (AircraftArmamentRegistry.mounts(definition).isEmpty()) continue
                val choices = JsonObject()
                val selected = linkedMapOf<String, JsonObject>()
                if (loaded) for (mount in AircraftArmamentRegistry.mounts(definition)) {
                    val key = mount["Id"].asString
                    val candidates = mount.getAsJsonArray("AllowedStores").mapNotNull { value ->
                        val id = value.asString
                        AircraftArmamentRegistry.stores[ResourceLocation(id)]?.let { id to it }
                    }.sortedBy { (_, store) -> (store["MassKg"]?.asDouble ?: 0.0) * (store["Capacity"]?.asInt ?: 1) }
                    val chosen = candidates.firstOrNull { (_, store) ->
                        val mass = (store["MassKg"]?.asDouble ?: 0.0) * (store["Capacity"]?.asInt ?: 1)
                        mass > 0 && AircraftPylonRacks.maxCopies(definition, mount, store) >= 1 &&
                            mass <= (mount["MaxPylonMassKg"]?.asDouble ?: definition["MaxPylonMassKg"]?.asDouble ?: Double.MAX_VALUE) &&
                            AircraftArmamentRegistry.loadoutMassKg(definition, selected + (key to store)) <= definition["MaxPayloadKg"].asDouble
                    } ?: continue
                    choices.addProperty(key, chosen.first); selected[key] = chosen.second
                }
                if (loaded) check(choices.size() > 0) { "No eligible stores on ${vehicle.type}" }
                check(player.startRiding(vehicle, true)) { "Cannot operate fixture aircraft" }
                vehicle.deltaMovement = Vec3.ZERO; vehicle.setOnGround(true)
                val body = JsonObject().also {
                    it.addProperty("Revision", AircraftArmamentManager.snapshot(vehicle)["Revision"].asLong)
                    it.add("Selections", choices)
                }
                // First request acquires a lease if needed; the next retries the same unmodified body.
                AircraftArmamentManager.diagnosticRequest(player, vehicle, "APPLY", body)
                if (AircraftArmamentManager.snapshot(vehicle).getAsJsonObject("Selections") != choices)
                    AircraftArmamentManager.diagnosticRequest(player, vehicle, "APPLY", body)
                val result = AircraftArmamentManager.snapshot(vehicle)
                check(result.getAsJsonObject("Selections") == choices) { "Actual loadout admission failed" }
                val receipt = JsonObject().also { compact ->
                    for (key in listOf("Revision", "Selections", "Counts")) compact.add(key, result[key])
                }
                check(receipt.toString().length <= 4096) { "Loadout receipt exceeds diagnostic scalar limit" }
                record("LOADOUT", "vehicle" to vehicle.uuid.toString(), "loaded" to loaded,
                    "selections" to choices.toString(), "snapshot" to receipt.toString())
                player.stopRiding()
            }
            observe()
        }

        fun tick() {
            val frame = age % 800
            if (frame == 0) {
                phase = phases[age / 800]
                when (phase) {
                    "clean" -> spawn()
                    "loaded" -> fit(true)
                    "removed" -> fit(false)
                    "cleanup" -> { fleet.keys.forEach { it.discard() }; fleet.clear() }
                }
                record("PHASE_START", "live_vehicles" to fleet.size)
            }
            if (frame == 200) record("WINDOW_START", "live_vehicles" to fleet.size)
            if (frame == 799) record("WINDOW_END", "live_vehicles" to fleet.size)
            age++
            if (age == 4000) finish("PASS", "All real loadout transitions admitted; rendering/performance awaits client evidence")
        }

        fun finish(status: String, reason: String) {
            try {
                player.stopRiding(); fleet.keys.forEach { it.discard() }; fleet.clear()
                record("COMPLETE", "status" to status, "reason" to reason, "owned_live_vehicles" to 0)
                player.teleportTo(level, saved.x, saved.y, saved.z, yaw, pitch)
                player.abilities.flying = flying; player.onUpdateAbilities()
                if (grantedAmmoBox) {
                    grantedAmmoBox = false
                    (0 until player.inventory.containerSize).firstOrNull {
                        player.inventory.getItem(it).`is`(ModItems.CREATIVE_AMMO_BOX.get())
                    }?.let { player.inventory.removeItem(it, 1) }
                }
            } finally {
                if (capture) EliteDiagnostics.stop(player.server)
                active = null
            }
        }
    }
}
