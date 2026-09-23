package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager
import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentRegistry
import com.atsuishio.superbwarfare.api.aircraft.AircraftDesignationData
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.registries.ForgeRegistries
import java.nio.file.Files
import java.util.UUID

/** Private-world proof of the authored AGM launch path, with no substitute gun or store fixtures. */
@EventBusSubscriber(modid = Mod.MODID)
object AircraftLaserLaunchTestScenario {
    private data class Case(val aircraft: String, val store: String, val mount: String)
    private val cases = listOf(
        Case("berts_vehicle_pack:su_25", "berts_vehicle_pack:kh29l", "pylon_1"),
        Case("berts_vehicle_pack:su_25", "berts_vehicle_pack:kh25l", "pylon_1"),
        Case("berts_vehicle_pack:fa_18e", "berts_vehicle_pack:fa18e/agm65", "outer"),
    )
    private val identity = UUID.nameUUIDFromBytes("OfflinePlayer:BvpDiagnostics".toByteArray(Charsets.UTF_8))
    private var active: Run? = null

    private fun allowed(player: ServerPlayer): Boolean = player.server.let { server ->
        DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios") &&
            server.isDedicatedServer && !server.usesAuthentication() && server.localIp == "127.0.0.1" &&
            server.port == 25579 && server.playerCount == 1 && player.gameProfile.name == "BvpDiagnostics" &&
            player.uuid == identity && player.abilities.instabuild && !player.isSpectator &&
            player.level().dimension() == Level.OVERWORLD &&
            Files.isRegularFile(server.getWorldPath(LevelResource.ROOT).resolve("sbw-disposable-tests.marker"))
    }

    @SubscribeEvent fun register(event: RegisterCommandsEvent) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
        event.dispatcher.register(Commands.literal("sbw_aircraft_laser_launch_test")
            .requires { it.hasPermission(2) }
            .executes { context ->
                val player = context.source.playerOrException
                if (!allowed(player) || active != null || EliteDiagnostics.isServerEnabled()) return@executes 0
                val run = Run(player)
                active = run
                try { run.prepare(); 1 } catch (error: Exception) {
                    run.finish("PREPARE_FAILED: ${error.message}"); active = null; 0
                }
            })
        event.dispatcher.register(Commands.literal("sbw_aircraft_laser_launch_cancel")
            .requires { it.hasPermission(2) }
            .executes { context ->
                val run = active ?: return@executes 0
                if (run.player !== context.source.playerOrException) return@executes 0
                run.finish("CANCELLED"); active = null; 1
            })
    }

    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val run = active ?: return
        try {
            if (run.tick()) { run.finish(null); active = null }
        } catch (error: Exception) {
            run.finish("FAILED: ${error.message}"); active = null
        }
    }

    @SubscribeEvent fun stopping(@Suppress("UNUSED_PARAMETER") event: ServerStoppingEvent) {
        active?.finish("SERVER_STOPPING")
        active = null
    }

    private class Run(val player: ServerPlayer) {
        private val level = player.serverLevel()
        private val oldPosition = player.position()
        private val oldYaw = player.yRot
        private val oldPitch = player.xRot
        private val oldVehicle = player.vehicle
        private val oldInstabuild = player.abilities.instabuild
        private val oldFlying = player.abilities.flying
        private val oldPresets = player.persistentData.getCompound("BvpAircraftPresets").copy()
        private val output = JsonArray()
        private var index = 0
        private var age = 0
        private var aircraft: VehicleEntity? = null
        private var missile: WireGuideMissileEntity? = null
        private var row: JsonObject? = null
        private var finished = false

        fun prepare() {
            player.teleportTo(level, 4380.5, 260.0, 7460.5, 0f, 0f)
            player.abilities.instabuild = false
            player.abilities.flying = false
            player.onUpdateAbilities()
            check(!player.isCreative) { "Ammunition consumption requires non-creative admission" }
            begin()
        }

        private fun ammoCount(vehicle: VehicleEntity, item: net.minecraft.world.item.Item): Int =
            (0 until vehicle.inventory.slots).sumOf { slot ->
                vehicle.inventory.getStackInSlot(slot).takeIf { it.`is`(item) }?.count ?: 0
            }

        private fun begin() {
            if (index >= cases.size) return
            val case = cases[index]
            age = 0
            val result = JsonObject().also {
                it.addProperty("Aircraft", case.aircraft)
                it.addProperty("Store", case.store)
                it.addProperty("Mount", case.mount)
                output.add(it)
            }
            row = result
            try {
                val store = requireNotNull(AircraftArmamentRegistry.stores[ResourceLocation(case.store)]) {
                    "Authored store unavailable"
                }
                check(store["Category"]?.asString == "LASER_GUIDED") { "Store is not laser guided" }
                val gunId = store["LaunchGunProfile"]?.asString ?: error("No authored launch gun")
                val profileId = ResourceLocation.tryParse(store["ProjectileProfile"]?.asString ?: "")
                    ?: error("No authored projectile profile")
                result.addProperty("LaunchGunPresent", CustomData.GUN_DATA.containsKey(gunId))
                result.addProperty("ProjectileProfilePresent", ProjectileProfiles.resolve(profileId) != null)
                check(CustomData.GUN_DATA.containsKey(gunId) && ProjectileProfiles.resolve(profileId) != null) {
                    "Authored launch gun or projectile profile missing from candidate"
                }
                val type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation(case.aircraft))
                    ?: error("Authored aircraft entity unavailable")
                val vehicle = type.create(level) as? VehicleEntity ?: error("Aircraft entity cannot spawn")
                aircraft = vehicle
                vehicle.moveTo(4380.5, 260.0, 7460.5, 0f, 0f)
                vehicle.setNoGravity(true)
                vehicle.addTag("sbw_aircraft_laser_launch_test")
                check(level.addFreshEntity(vehicle)) { "Aircraft insertion failed" }
                check(AircraftArmamentManager.definition(vehicle) != null) { "Authored aircraft definition unavailable" }
                check(player.startRiding(vehicle, true)) { "Pilot seat unavailable" }
                vehicle.deltaMovement = Vec3.ZERO
                vehicle.setOnGround(true)
                val ammoId = ResourceLocation.tryParse(store["AmmoItem"]?.asString ?: "")
                    ?: error("No authored ammunition item")
                val ammo = ForgeRegistries.ITEMS.getValue(ammoId) ?: error("Authored ammunition item unavailable")
                var remaining = ItemStack(ammo, 1)
                for (slotIndex in 0 until vehicle.inventory.slots) {
                    if (remaining.isEmpty) break
                    remaining = vehicle.inventory.insertItem(slotIndex, remaining, false)
                }
                check(remaining.isEmpty) {
                    "Aircraft inventory rejected authored ammunition"
                }
                val before = ammoCount(vehicle, ammo)
                result.addProperty("AmmoBefore", before)
                check(before >= 1) { "Aircraft ammunition inventory did not contain one round" }

                AircraftArmamentManager.diagnosticRequest(player, vehicle, "OPEN", JsonObject())
                val apply = JsonObject().also { body ->
                    body.addProperty("Revision", AircraftArmamentManager.snapshot(vehicle)["Revision"].asLong)
                    body.add("Selections", JsonObject().also { it.addProperty(case.mount, case.store) })
                    body.add("Counts", JsonObject().also { it.addProperty(case.mount, 1) })
                }
                AircraftArmamentManager.diagnosticRequest(player, vehicle, "APPLY", apply)
                val selected = AircraftArmamentManager.snapshot(vehicle)
                    .getAsJsonObject("Selections")?.get(case.mount)?.asString == case.store
                result.addProperty("LoadoutApplied", selected)
                check(selected) { "Real authored APPLY did not equip the store" }
                val weapon = "AircraftStore:${case.mount}"
                val slot = vehicle.getWeaponIds(0).indexOf(weapon)
                check(slot >= 0) { "Store command slot unavailable" }
                vehicle.setWeaponIndex(0, slot)
                check(vehicle.getGunName(0) == weapon) { "Store command slot not selected" }
                vehicle.setPos(vehicle.x, vehicle.y + 60.0, vehicle.z)
                vehicle.setOnGround(false)
                result.addProperty("NoDesignationAtLaunch",
                    AircraftDesignationData.get(level).get(vehicle.uuid)?.position == null)
                val fire = JsonObject().also { it.addProperty("Pair", case.mount) }
                AircraftArmamentManager.diagnosticRequest(player, vehicle, "FIRE", fire)
                val after = AircraftArmamentManager.snapshot(vehicle)
                val fired = after.getAsJsonObject("Fired")?.get(case.mount)?.asInt ?: 0
                result.addProperty("FiredAfter", fired)
                result.addProperty("AmmoAfter", ammoCount(vehicle, ammo))
                missile = level.allEntities.filterIsInstance<WireGuideMissileEntity>().firstOrNull {
                    it.persistentData.hasUUID("BvpLaserAircraft") &&
                        it.persistentData.getUUID("BvpLaserAircraft") == vehicle.uuid
                }
                result.addProperty("MissileSpawned", missile != null)
                result.addProperty("ProfileMatched", missile?.let { ProjectileProfiles.profileId(it) == profileId } == true)
                result.addProperty("ShotAccepted", fired == 1 && ammoCount(vehicle, ammo) == before - 1)
            } catch (error: Exception) {
                result.addProperty("Error", error.message ?: error.javaClass.simpleName)
            }
        }

        fun tick(): Boolean {
            if (index >= cases.size) return true
            val result = row ?: return true
            age++
            if (age == 2 || age == 5) {
                val present = missile?.let { level.getEntity(it.uuid)?.isAlive == true } == true
                result.addProperty(if (age == 2) "AliveAfterTwoTicks" else "AliveAfterFiveTicks", present)
            }
            if (age < 5) return false
            result.addProperty("Pass", listOf("LoadoutApplied", "NoDesignationAtLaunch", "MissileSpawned",
                "ProfileMatched", "ShotAccepted", "AliveAfterTwoTicks", "AliveAfterFiveTicks")
                .all { result[it]?.asBoolean == true })
            cleanupCase()
            index++
            if (index < cases.size) begin()
            return index >= cases.size
        }

        private fun cleanupCase() {
            if (player.vehicle === aircraft) player.stopRiding()
            missile?.discard()
            aircraft?.discard()
            missile = null
            aircraft = null
        }

        fun finish(reason: String?) {
            if (finished) return
            finished = true
            try {
                cleanupCase()
                player.persistentData.put("BvpAircraftPresets", oldPresets)
                player.teleportTo(level, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch)
                if (oldVehicle?.isAlive == true) player.startRiding(oldVehicle, true)
                player.abilities.instabuild = oldInstabuild
                player.abilities.flying = oldFlying
                player.onUpdateAbilities()
            } finally {
                val passed = reason == null && output.size() == cases.size &&
                    output.all { it.asJsonObject["Pass"]?.asBoolean == true }
                val report = JsonObject().also {
                    it.addProperty("Status", if (passed) "PASS" else "FAIL")
                    reason?.let { message -> it.addProperty("Reason", message) }
                    it.add("Cases", output)
                }
                Files.writeString(player.server.getWorldPath(LevelResource.ROOT)
                    .resolve("sbw-aircraft-laser-launch-acceptance.json"),
                    GsonBuilder().setPrettyPrinting().create().toJson(report))
                player.sendSystemMessage(Component.literal("Aircraft laser launch: ${report["Status"].asString}"))
            }
        }
    }
}
