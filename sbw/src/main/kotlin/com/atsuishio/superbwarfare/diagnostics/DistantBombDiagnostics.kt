package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentRegistry
import com.atsuishio.superbwarfare.api.aircraft.AircraftBombLauncher
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import com.atsuishio.superbwarfare.entity.projectile.AerialBombEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.ProjectileImpactEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.registries.ForgeRegistries
import java.nio.file.Files
import java.nio.file.Path

/** Real authored dumb bomb, launched above normal flight height to cross the former 7-second cutoff. */
@EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID)
object DistantBombDiagnostics {
    private var active: Run? = null
    @SubscribeEvent fun commands(event: RegisterCommandsEvent) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
        event.dispatcher.register(Commands.literal("bvp_distant_bomb_test").requires { it.hasPermission(2) }.executes {
            val p = it.source.playerOrException
            val s = p.server
            if (active != null || p.isPassenger || !s.isDedicatedServer || s.usesAuthentication() ||
                s.localIp != "127.0.0.1" || s.port != 25579 || s.playerCount != 1 ||
                p.gameProfile.name != "BvpDiagnostics" || !p.abilities.instabuild ||
                p.level().dimension() != Level.OVERWORLD ||
                !Files.isRegularFile(s.getWorldPath(LevelResource.ROOT).resolve("sbw-disposable-tests.marker"))) return@executes 0
            active = Run(p)
            try { active!!.prepare() } catch (error: Exception) { finish(error) }
            1
        })
    }
    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val run = active ?: return
        try { if (run.tick()) finish(null) } catch (error: Exception) { finish(error) }
    }
    @SubscribeEvent fun impact(event: ProjectileImpactEvent) {
        val run = active ?: return
        if (event.entity === run.bomb && event.rayTraceResult.type == HitResult.Type.BLOCK)
            run.impact = event.rayTraceResult.location
    }
    @SubscribeEvent fun stop(event: ServerStoppingEvent) {
        if (active != null) finish(IllegalStateException("Server stopped"))
    }
    private fun finish(error: Exception?) { val run = active; active = null; run?.finish(error) }
    private class Run(val player: ServerPlayer) {
        val level = player.serverLevel()
        val previous = player.position(); val yaw = player.yRot; val pitch = player.xRot
        val origin = Vec3(14336.5, 1600.0, 14336.5)
        val cases = JsonArray()
        var carrier: VehicleEntity? = null
        var bomb: AerialBombEntity? = null
        var impact: Vec3? = null
        var age = 0
        fun checkCase(name: String, pass: Boolean) {
            cases.add(JsonObject().also { it.addProperty("case", name); it.addProperty("pass", pass); it.addProperty("tick", age) })
            check(pass) { name }
        }
        fun prepare() {
            val store = AircraftArmamentRegistry.stores[ResourceLocation("berts_vehicle_pack:fab_250")]
                ?: error("Real authored bomb missing")
            player.teleportTo(level, origin.x, origin.y, origin.z, 0f, 85f)
            val plane = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation("berts_vehicle_pack:su_25"))?.create(level)
                as? VehicleEntity ?: error("Carrier unavailable")
            carrier = plane
            plane.setPos(origin); plane.deltaMovement = Vec3(0.0, 0.0, 8.0)
            checkCase("real carrier accepted", level.addFreshEntity(plane))
            checkCase("authored dumb bomb launched", AircraftBombLauncher.launch(plane, player, Vec3(0.0, -2.0, 0.0), store))
            bomb = level.allEntities.filterIsInstance<AerialBombEntity>().single {
                it.persistentData.hasUUID("BvpBombAircraft") && it.persistentData.getUUID("BvpBombAircraft") == plane.uuid
            }
            plane.discard()
            checkCase("bomb has authored 600-tick native life", bomb!!.getLife() == 600)
        }
        fun tick(): Boolean {
            age++
            val shot = bomb ?: error("No bomb")
            if (age == 145) {
                checkCase("bomb survives old seven-second limit", !shot.isRemoved)
                checkCase("bomb advances beyond ordinary terrain loading", shot.z - origin.z > 450)
            }
            if (shot.isRemoved) {
                checkCase("real block collision observed before removal", impact != null)
                checkCase("impact occurs after seven seconds", age > 140)
                checkCase("impact more than 512 blocks away", impact!!.subtract(origin).horizontalDistance() > 512)
                return true
            }
            check(age < 580) { "Bomb stalled or missed its real impact before authored lifetime" }
            return false
        }
        fun finish(error: Exception?) {
            bomb?.takeUnless(Entity::isRemoved)?.discard(); carrier?.takeUnless(Entity::isRemoved)?.discard()
            player.teleportTo(level, previous.x, previous.y, previous.z, yaw, pitch)
            val out = JsonObject().also {
                it.addProperty("status", if (error == null) "PASS" else "FAIL"); it.add("cases", cases)
                error?.let { error -> it.addProperty("error", error.toString()) }
                impact?.let { point -> it.addProperty("impact", point.toString()) }
            }
            Files.writeString(Path.of("distant-bomb-reliability.json"), GsonBuilder().setPrettyPrinting().create().toJson(out))
            player.sendSystemMessage(Component.literal("Distant bomb reliability: ${out["status"].asString}"))
        }
    }
}
