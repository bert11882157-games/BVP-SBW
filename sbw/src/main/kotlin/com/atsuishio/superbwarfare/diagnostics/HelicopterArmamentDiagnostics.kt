package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy

import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager
import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentRegistry
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.*
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.registries.ForgeRegistries
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Opt-in private-server acceptance of every helicopter's real loadout path (owner 2026-10-23: "helicopter weapons
 * cannot be equipped"). For each helicopter with an armament definition: does it settle landed and still enough
 * for the refit check, does each station take its first allowed store through the real APPLY request, and which
 * weapons each crew seat is admitted to and can fire. Nothing is asserted mid-run: every case is recorded and the
 * report (helicopter-armament-acceptance.json) says PASS only when every equip and landing case passed.
 */
@EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID)
object HelicopterArmamentDiagnostics {
    private var run: Run? = null
    @SubscribeEvent fun commands(event: RegisterCommandsEvent) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
        event.dispatcher.register(Commands.literal("bvp_helicopter_armament_test")
            .requires { it.hasPermission(2) }.executes { context ->
                val player = context.source.playerOrException
                val server = player.server
                if (run != null || !server.isDedicatedServer || server.usesAuthentication() ||
                    server.localIp != "127.0.0.1" || server.port != 25579 || server.playerCount != 1 ||
                    player.gameProfile.name != "BvpDiagnostics" || !player.abilities.instabuild) return@executes 0
                val candidate = Run(player)
                run = candidate
                try { candidate.prepare() } catch (e: Exception) { candidate.finish(e); run = null }
                1
            })
        event.dispatcher.register(Commands.literal("bvp_helicopter_armament_stop")
            .requires { it.hasPermission(2) }.executes {
                run?.finish(null); run = null; 1
            })
    }
    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val active = run ?: return
        try { if (active.tick()) { active.finish(null); run = null } }
        catch (e: Exception) { active.finish(e); run = null }
    }
    @SubscribeEvent fun stop(event: ServerStoppingEvent) {
        run?.finish(IllegalStateException("Server stopped during test")); run = null
    }

    private class Run(val player: ServerPlayer) {
        val level = player.serverLevel()
        val oldPosition = player.position()
        val oldYaw = player.yRot; val oldPitch = player.xRot
        val results = JsonArray()
        val blocks = linkedMapOf<BlockPos, BlockState>()
        val pad = Vec3(oldPosition.x + 24, oldPosition.y + 2, oldPosition.z + 24)
        val queue = ArrayDeque<ResourceLocation>()
        var heli: VehicleEntity? = null
        var report = JsonObject()
        var mounts = listOf<JsonObject>()
        val fitted = linkedMapOf<String, String>()
        val projectiles = mutableListOf<UUID>()
        var failed = false
        var age = 0

        fun json(text: String) = JsonParser.parseString(text).asJsonObject
        fun record(case: String, pass: Boolean, detail: JsonObject.() -> Unit = {}) {
            if (!pass) failed = true
            report.getAsJsonArray("cases").add(JsonObject().also {
                it.addProperty("case", case); it.addProperty("pass", pass); it.detail()
            })
        }

        fun prepare() {
            for (id in AircraftArmamentRegistry.aircraft.keys.sortedBy { it.toString() }) {
                val probe = ForgeRegistries.ENTITY_TYPES.getValue(id)?.create(level) as? VehicleEntity ?: continue
                if (probe.vehicleType == VehicleType.HELICOPTER) queue.addLast(id)
                probe.discard()
            }
            check(queue.isNotEmpty()) { "No helicopter has an armament definition" }
            for (dx in -14..14) for (dz in -14..14) {
                val pos = BlockPos.containing(pad.x + dx, pad.y - 1, pad.z + dz)
                blocks[pos] = level.getBlockState(pos); level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3)
                for (dy in 0..8) {
                    val air = pos.above(1 + dy)
                    if (!blocks.containsKey(air)) { blocks[air] = level.getBlockState(air) }
                    level.setBlock(air, Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }

        fun request(op: String, body: JsonObject = JsonObject()) =
            AircraftArmamentManager.diagnosticRequest(player, heli!!, op, body)
        fun selections(): JsonObject = AircraftArmamentManager.snapshot(heli!!).getAsJsonObject("Selections")
        fun apply(choices: Map<String, String>) {
            val body = JsonObject()
            body.addProperty("Revision", AircraftArmamentManager.snapshot(heli!!)["Revision"].asLong)
            body.add("Selections", JsonObject().also { s -> choices.forEach { (k, v) -> s.addProperty(k, v) } })
            request("APPLY", body)
        }
        /** Fires every weapon listed for [seat] once, after filling each gun's magazine. */
        fun fireSeat(seat: Int) {
            val vehicle = heli!!
            for (name in vehicle.getWeaponIds(seat)) {
                vehicle.modifyGunData(name) { it.ammo.set(it.get(GunProp.MAGAZINE)) }
                val admitted = AircraftArmamentManager.allowsWeapon(vehicle, name)
                val shot = vehicle.vehicleShootResult(player, name)
                projectiles.addAll(shot.spawnedProjectileIds)
                report.getAsJsonArray("fire").add(JsonObject().also {
                    it.addProperty("seat", seat); it.addProperty("weapon", name); it.addProperty("admitted", admitted)
                    it.addProperty("accepted", shot.isAccepted()); it.addProperty("reason", shot.reason.name)
                    it.addProperty("projectiles", shot.spawnedProjectileIds.size)
                })
            }
        }

        fun start(id: ResourceLocation) {
            val vehicle = ForgeRegistries.ENTITY_TYPES.getValue(id)?.create(level) as? VehicleEntity
                ?: error("$id unavailable")
            heli = vehicle
            report = JsonObject().also { it.addProperty("helicopter", id.toString()); it.add("cases", JsonArray()); it.add("fire", JsonArray()) }
            results.add(report)
            mounts = AircraftArmamentRegistry.mounts(AircraftArmamentManager.definition(vehicle) ?: JsonObject())
            fitted.clear()
            vehicle.moveTo(pad.x, pad.y + 0.5, pad.z, 0f, 0f)
            level.addFreshEntity(vehicle)
            player.startRiding(vehicle, true)
        }

        fun end() {
            val vehicle = heli ?: return
            for (uuid in projectiles) level.getEntity(uuid)?.discard()
            projectiles.clear()
            if (player.vehicle === vehicle) player.stopRiding()
            vehicle.discard()
            heli = null
        }

        /** Per helicopter: settle 60 ticks, open, fit one station every 6 ticks (the edit interval is 5), then fire. */
        fun tick(): Boolean {
            if (heli == null) {
                val next = queue.removeFirstOrNull() ?: return true
                start(next); age = 0
                return false
            }
            val vehicle = heli!!
            age++
            val fireAt = 66 + 6 * mounts.size + 6
            if (age in 61 until fireAt) { vehicle.deltaMovement = Vec3.ZERO; vehicle.setOnGround(true) }
            when {
                age == 60 -> {
                    val speed = vehicle.deltaMovement.lengthSqr()
                    record("landed and still enough to refit", vehicle.onGround() && speed <= 0.0025) {
                        addProperty("onGround", vehicle.onGround()); addProperty("speedSqr", speed)
                    }
                    record("pilot holds seat 0", vehicle.getSeatIndex(player) == 0)
                    record("armament definition loaded", AircraftArmamentManager.definition(vehicle) != null) {
                        addProperty("stations", mounts.size)
                    }
                    request("OPEN")
                }
                age >= 66 && age < fireAt - 6 && (age - 66) % 6 == 0 -> {
                    val mount = mounts[(age - 66) / 6]
                    val id = mount["Id"].asString
                    val store = mount.getAsJsonArray("AllowedStores")?.firstOrNull()?.asString
                    if (store == null) { record("station $id offers a store", false); return false }
                    apply(fitted + (id to store))
                    val fits = selections()[id]?.asString == store
                    if (fits) fitted[id] = store
                    record("station $id fits $store", fits)
                }
                age == fireAt -> {
                    record("every station fitted together", selections().size() == mounts.size) {
                        addProperty("fitted", selections().size())
                    }
                    // fire in the air, clear of the pad and of the airframe's own blast
                    vehicle.setNoGravity(true); vehicle.setPos(vehicle.x, vehicle.y + 60, vehicle.z)
                    fireSeat(0)
                }
                age == fireAt + 20 -> {
                    if (vehicle.maxPassengers > 1 && vehicle.getWeaponIds(1).isNotEmpty()) {
                        val moved = vehicle.changeSeat(player, 1)
                        record("gunner seat taken", moved)
                        if (moved) fireSeat(1)
                    }
                }
                age >= fireAt + 40 -> end()
            }
            return false
        }

        fun finish(error: Exception?) {
            if (error != null) { failed = true; results.add(JsonObject().also { it.addProperty("error", error.toString()) }) }
            try {
                end()
                player.teleportTo(level, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch)
                for ((pos, state) in blocks) level.setBlock(pos, state, 3)
            } finally {
                val output = JsonObject().also { it.addProperty("status", if (failed) "FAIL" else "PASS"); it.add("helicopters", results) }
                Files.writeString(Path.of("helicopter-armament-acceptance.json"), GsonBuilder().setPrettyPrinting().create().toJson(output))
                player.sendSystemMessage(Component.literal("Helicopter armament acceptance: ${output["status"].asString}"))
            }
        }
    }
}
