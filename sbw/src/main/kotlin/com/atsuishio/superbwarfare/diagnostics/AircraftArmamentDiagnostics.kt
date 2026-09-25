package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy

import com.atsuishio.superbwarfare.api.aircraft.*
import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.data.gun.DefaultGunData
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.gun.resolvedProfileId
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.*
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.registries.ForgeRegistries
import java.nio.file.Files
import java.nio.file.Path

/** Opt-in private-server acceptance of real equipment admission and persistent laser ownership. */
@EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID)
object AircraftArmamentDiagnostics {
    private var run: Run? = null
    @SubscribeEvent fun commands(event: RegisterCommandsEvent) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
        for ((command, preview) in listOf("bvp_aircraft_armament_test" to false,
            "bvp_aircraft_armament_preview" to true)) {
            event.dispatcher.register(Commands.literal(command)
                .requires { it.hasPermission(2) }.executes { context ->
                val player = context.source.playerOrException
                val server = player.server
                if (run != null || !server.isDedicatedServer || server.usesAuthentication() ||
                    server.localIp != "127.0.0.1" || server.port != 25579 || server.playerCount != 1 ||
                    player.gameProfile.name != "BvpDiagnostics" || !player.abilities.instabuild) return@executes 0
                val candidate = Run(player, preview)
                run = candidate
                try { candidate.prepare() } catch (e: Exception) { candidate.finish(e); run = null }
                1
            })
        }
        event.dispatcher.register(Commands.literal("bvp_aircraft_armament_stop")
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

    private class Run(val player: ServerPlayer, val preview: Boolean) {
        val level = player.serverLevel()
        val oldPosition = player.position()
        val oldVehicle = player.vehicle
        val oldYaw = player.yRot; val oldPitch = player.xRot
        val oldPresets = player.persistentData.getCompound("BvpAircraftPresets").copy()
        val results = JsonArray()
        val blocks = linkedMapOf<BlockPos, net.minecraft.world.level.block.state.BlockState>()
        lateinit var aircraft: VehicleEntity
        var restore: (() -> Unit)? = null
        var previousGun: DefaultGunData? = null
        var gunInstalled = false
        var age = 0
        var pointA: Vec3? = null
        var launched: List<Entity> = emptyList()
        fun json(text: String) = JsonParser.parseString(text).asJsonObject
        fun checkCase(name: String, good: Boolean) {
            results.add(JsonObject().also { it.addProperty("case", name); it.addProperty("pass", good) })
            check(good) { name }
        }
        fun prepare() {
            val id = ResourceLocation("berts_vehicle_pack:eurofighter_typhoon")
            // Fixture stores declare no AmmoItem, so fitting them is free (AircraftLoadoutCost) and these
            // cases exercise admission only; paid fitting is covered by AircraftLaserLaunchTestScenario.
            val stores = mapOf(
                ResourceLocation("bvp_diagnostics:aam") to json("""{"Schema":1,"Name":"TEST AAM — visual only","Category":"AIR_TO_AIR","Item":"minecraft:firework_rocket","Scale":0.5}"""),
                ResourceLocation("bvp_diagnostics:laser") to json("""{"Schema":1,"Name":"TEST laser missile","Category":"LASER_GUIDED","Item":"minecraft:arrow","Capacity":2,"ProjectileProfile":"berts_vehicle_pack:qn_506model/micromissile/ammo_00_qn201dd_atgm"}"""))
            val nativeGun = CustomData.VEHICLE_DATA["berts_vehicle_pack:qn_506model"]?.weapons()?.get("MicroMissile")
                ?: error("Native QN506 diagnostic launch profile unavailable")
            previousGun = CustomData.GUN_DATA.put("bvp_diagnostics:laser", nativeGun)
            gunInstalled = true
            val launchData = GunData.from(ItemStack(ModItems.VEHICLE_GUN.get())) { nativeGun }
            stores[ResourceLocation("bvp_diagnostics:laser")]!!.also {
                it.addProperty("LaunchGunProfile", "bvp_diagnostics:laser")
                it.addProperty("ProjectileProfile", launchData.get(GunProp.PROJECTILE).resolvedProfileId().toString())
            }
            val definition = json("""{"Schema":1,"Name":"PRIVATE ARMAMENT FIXTURE","BuiltInWeapons":["Cannon"],"SuspendedWeapons":["FixtureRocket"],"Pairs":[{"Id":"outer","Name":"Outer wing pair","Left":[-3,1,0],"Right":[3,1,0],"AllowedStores":["bvp_diagnostics:aam","bvp_diagnostics:laser"]}],"Pod":{"Position":[0,1,3.5],"YawLimit":170,"PitchMin":-20,"PitchMax":90,"Source":"PRIVATE TEST FIXTURE — no public compatibility"}}""")
            restore = AircraftArmamentRegistry.installDiagnosticFixture(id, definition, stores)
            aircraft = ForgeRegistries.ENTITY_TYPES.getValue(id)?.create(level) as? VehicleEntity ?: error("Fixture aircraft unavailable")
            val x = oldPosition.x + 24; val z = oldPosition.z + 24
            val y = oldPosition.y + 2
            for (dx in -12..12) for (dz in -12..12) {
                val pos = BlockPos.containing(x + dx, y - 1, z + dz)
                blocks[pos] = level.getBlockState(pos); level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3)
            }
            aircraft.moveTo(x, y, z, 0f, 0f); aircraft.setNoGravity(true)
            level.addFreshEntity(aircraft); player.startRiding(aircraft, true)
        }
        fun request(op: String, body: JsonObject = JsonObject(), stale: Boolean = false, wrongDimension: Boolean = false) =
            AircraftArmamentManager.diagnosticRequest(player, aircraft, op, body, stale, wrongDimension)
        fun selections() = AircraftArmamentManager.snapshot(aircraft).getAsJsonObject("Selections")
        fun apply(id: String? = null, stale: Boolean = false, wrongDimension: Boolean = false) {
            val body = JsonObject()
            body.addProperty("Revision", AircraftArmamentManager.snapshot(aircraft)["Revision"].asLong)
            body.add("Selections", JsonObject().also { if (id != null) it.addProperty("outer", id) })
            request("APPLY", body, stale, wrongDimension)
        }
        fun tick(): Boolean {
            age++
            if (age < 190) { aircraft.deltaMovement = Vec3.ZERO; aircraft.setOnGround(true) }
            if (preview && age > 20) return age >= 2400
            when(age) {
                20 -> {
                    checkCase("neutral has no suspended selection", selections().size() == 0)
                    checkCase("built-in gun remains admitted", AircraftArmamentManager.allowsWeapon(aircraft,"Cannon"))
                    checkCase("unequipped suspended channel denied", !AircraftArmamentManager.allowsWeapon(aircraft,"FixtureRocket"))
                    request("OPEN")
                }
                32 -> {
                    request("APPLY", json("""{"Revision":0,"Selections":{"outer_left":"bvp_diagnostics:aam"}}"""))
                    checkCase("asymmetric key rejected atomically", selections().size() == 0)
                }
                44 -> { apply("bvp_diagnostics:aam"); checkCase("one pair selection equips both sides", selections()["outer"]?.asString == "bvp_diagnostics:aam") }
                56 -> {
                    request("SAVE_PRESET", json("""{"Name":"Acceptance","Selections":{"outer":"bvp_diagnostics:aam"}}"""))
                    checkCase("preset persisted on player", player.persistentData.getCompound("BvpAircraftPresets")
                        .getCompound("berts_vehicle_pack:eurofighter_typhoon").contains("Acceptance"))
                }
                68 -> { apply(); checkCase("clean loadout removes stores", selections().size() == 0) }
                80 -> { request("LOAD_PRESET", json("""{"Name":"Acceptance"}""")); checkCase("preset restores pair", selections().size() == 1) }
                92 -> { apply(stale=true); checkCase("stale writer lease rejected", selections().size() == 1) }
                104 -> { apply(wrongDimension=true); checkCase("wrong dimension rejected", selections().size() == 1) }
                116 -> {
                    aircraft.setOnGround(false); apply()
                    checkCase("airborne refit rejected", selections().size() == 1)
                }
                128 -> {
                    val before = level.allEntities.count()
                    request("FIRE", json("""{"Pair":"outer"}"""))
                    checkCase("visual AAM has no projectile side effect", level.allEntities.count() == before)
                }
                140 -> { apply("bvp_diagnostics:laser"); checkCase("laser pair equipped", selections()["outer"]?.asString == "bvp_diagnostics:laser") }
                152 -> request("POD", json("""{"Active":true}"""))
                164 -> {
                    aircraft.setPos(aircraft.x, aircraft.y + 80, aircraft.z)
                    aircraft.setOnGround(false)
                    request("DESIGNATE", json("""{"Pod":true,"Yaw":0,"Pitch":80}"""))
                    pointA = AircraftDesignationData.get(level).get(aircraft.uuid)?.position
                    checkCase("server pod ray paints terrain", pointA != null)
                }
                176 -> {
                    request("FIRE", json("""{"Pair":"outer"}"""))
                    launched = level.allEntities.filter { it.persistentData.hasUUID("BvpLaserAircraft") &&
                        it.persistentData.getUUID("BvpLaserAircraft") == aircraft.uuid }.toList()
                    checkCase("laser launch spawns a tagged missile", launched.size == 1)
                    checkCase("missile sees designated point", AircraftArmamentManager.laserTarget(launched[0]) == pointA)
                }
                178 -> {
                    request("DESIGNATE", json("""{"Pod":true,"Yaw":60,"Pitch":80}"""))
                    val pointB = AircraftDesignationData.get(level).get(aircraft.uuid)?.position
                    checkCase("repaint changes point", pointB != null && pointB.distanceTo(pointA!!) > 2)
                    checkCase("in-flight missile follows new designation", AircraftArmamentManager.laserTarget(launched[0]) == pointB)
                }
                181 -> {
                    request("CLEAR_POINT")
                    checkCase("explicit clear stops point guidance", AircraftArmamentManager.laserTarget(launched[0]) == null)
                }
                184 -> {
                    request("DESIGNATE", json("""{"Pod":true,"Yaw":-60,"Pitch":80}"""))
                    checkCase("new designation resumes guidance", AircraftArmamentManager.laserTarget(launched[0]) != null)
                }
                187 -> {
                    player.stopRiding()
                    checkCase("uncrewed carrier retains missile target", AircraftArmamentManager.laserTarget(launched[0]) != null)
                    aircraft.discard()
                    checkCase("removed carrier retains missile target", AircraftArmamentManager.laserTarget(launched[0]) != null)
                    return true
                }
            }
            return false
        }
        fun finish(error: Exception?) {
            if (error != null) results.add(JsonObject().also { it.addProperty("error", error.toString()); it.addProperty("pass", false) })
            try {
                if (::aircraft.isInitialized) {
                    level.allEntities.filter { it.persistentData.hasUUID("BvpLaserAircraft") &&
                        it.persistentData.getUUID("BvpLaserAircraft") == aircraft.uuid }.toList().forEach(Entity::discard)
                    if (player.vehicle === aircraft) player.stopRiding()
                    aircraft.discard()
                }
                restore?.invoke()
                if (gunInstalled) {
                    previousGun?.let { CustomData.GUN_DATA["bvp_diagnostics:laser"] = it }
                        ?: CustomData.GUN_DATA.remove("bvp_diagnostics:laser")
                }
                player.persistentData.put("BvpAircraftPresets", oldPresets)
                player.teleportTo(level, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch)
                if (oldVehicle?.isAlive == true) player.startRiding(oldVehicle, true)
                for ((pos, state) in blocks) level.setBlock(pos, state, 3)
            } finally {
                val output = JsonObject().also { it.addProperty("status", if(error != null) "FAIL" else if(preview) "PREVIEW_CLOSED" else "PASS"); it.add("cases", results) }
                val path = Path.of(if(preview) "aircraft-armament-preview.json" else "aircraft-armament-acceptance.json")
                Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(output))
                player.sendSystemMessage(Component.literal("Aircraft armament acceptance: ${output["status"].asString}"))
            }
        }
    }
}
