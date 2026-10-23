package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy

import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager
import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreWeapons
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.google.gson.*
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.items.ItemHandlerHelper
import net.minecraftforge.registries.ForgeRegistries
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opt-in private-server check that every aircraft gun fires through the same path as a held fire click (owner
 * 2026-10-23: "some aircraft autocannons were not firing when i would click fire"). Each crewed seat's native
 * weapons are selected in turn and held through the weapon scheduler, first as spawned (no magazine fill, no
 * creative ammo box, as a creative player first climbs in) and then with a filled magazine and a creative ammo box.
 * Every attempt records what the client gates on, the accepted shot count and, when nothing fired, each admission
 * the shot needs. The report (aircraft-gun-fire.json) lists every silent gun.
 */
@EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID)
object AircraftGunFireDiagnostics {
    private var run: Run? = null
    @SubscribeEvent fun commands(event: RegisterCommandsEvent) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
        event.dispatcher.register(Commands.literal("bvp_aircraft_gun_test")
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
        event.dispatcher.register(Commands.literal("bvp_aircraft_gun_stop")
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

    private const val HOLD_TICKS = 14
    private const val SLOT_TICKS = 24

    private data class Case(val seat: Int, val index: Int, val weapon: String, val supplied: Boolean)

    private class Run(val player: ServerPlayer) {
        val level = player.serverLevel()
        val oldPosition = player.position()
        val oldYaw = player.yRot; val oldPitch = player.xRot
        val oldInventory = player.inventory.items.map { it.copy() }
        val results = JsonArray()
        val silent = JsonArray()
        val origin = Vec3(oldPosition.x + 24, oldPosition.y + 80, oldPosition.z + 24)
        val queue = ArrayDeque<ResourceLocation>()
        var vehicle: VehicleEntity? = null
        var report = JsonObject()
        val cases = ArrayDeque<Case>()
        var case: Case? = null
        var attempt = JsonObject()
        var sequence = 0L
        var ammoBefore = 0
        var age = 0
        var tested = 0

        fun prepare() {
            for (id in ForgeRegistries.ENTITY_TYPES.keys.filter { it.namespace != "minecraft" }.sortedBy { it.toString() }) {
                val probe = runCatching { ForgeRegistries.ENTITY_TYPES.getValue(id)?.create(level) }.getOrNull()
                if (probe is VehicleEntity && (probe.vehicleType == VehicleType.AIRPLANE ||
                        probe.vehicleType == VehicleType.HELICOPTER || probe.isFixedWingFlightVehicle())) queue.addLast(id)
                probe?.discard()
            }
            check(queue.isNotEmpty()) { "No aircraft is registered" }
        }

        fun start(id: ResourceLocation) {
            val created = ForgeRegistries.ENTITY_TYPES.getValue(id)?.create(level) as? VehicleEntity
                ?: error("$id unavailable")
            vehicle = created
            report = JsonObject().also { it.addProperty("aircraft", id.toString()); it.add("guns", JsonArray()) }
            results.add(report)
            created.moveTo(origin.x, origin.y, origin.z, 0f, 0f)
            created.setNoGravity(true)
            level.addFreshEntity(created)
            player.startRiding(created, true)
            cases.clear()
            for (supplied in listOf(false, true)) for (seat in 0 until created.maxPassengers) {
                created.getWeaponIds(seat).forEachIndexed { index, name ->
                    // store channels are fitted equipment, covered by the armament acceptance runs
                    if (AircraftStoreWeapons.mountId(name) == null) cases.addLast(Case(seat, index, name, supplied))
                }
            }
        }

        fun end() {
            val current = vehicle ?: return
            release()
            if (player.vehicle === current) player.stopRiding()
            level.getEntities(current, AABB(origin, origin).inflate(256.0)) { it is Projectile }.forEach(Entity::discard)
            current.discard()
            vehicle = null
        }

        fun release() {
            val current = vehicle ?: return
            if (case != null) current.updateScheduledWeaponTrigger(player, false, null, null)
        }

        fun supply(current: VehicleEntity) {
            val box = ItemStack(ModItems.CREATIVE_AMMO_BOX.get())
            if (!player.inventory.contains(box)) player.inventory.add(box.copy())
            current.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent { ItemHandlerHelper.insertItem(it, box.copy(), false) }
        }

        fun begin(next: Case) {
            val current = vehicle!!
            case = next
            if (next.supplied) supply(current)
            val seated = current.getSeatIndex(player) == next.seat || current.changeSeat(player, next.seat)
            current.setWeaponIndex(next.seat, next.index)
            val data = current.getGunData(next.weapon)
            if (next.supplied) current.modifyGunData(next.weapon) { it.ammo.set(it.get(GunProp.MAGAZINE)) }
            sequence = current.getWeaponScheduleSnapshot(next.seat, next.index)?.acceptedSequence ?: 0L
            ammoBefore = data?.let { current.getAmmo(it) } ?: 0
            attempt = JsonObject().also {
                it.addProperty("seat", next.seat); it.addProperty("weapon", next.weapon)
                it.addProperty("pass", if (next.supplied) "filled magazine + creative ammo box" else "as spawned")
                it.addProperty("seated", seated)
                it.addProperty("selected", current.getGunName(next.seat) == next.weapon)
                it.addProperty("hasGunData", data != null)
                // what the client checks before it sends the fire click at all
                it.addProperty("clientSendsClick", current.banHand(player) && current.hasWeapon(next.seat) &&
                    current.getGunData(next.seat, next.index) != null)
                it.addProperty("ammoBefore", ammoBefore)
                if (data != null) it.addProperty("backpackAmmo", data.useBackpackAmmo())
                it.addProperty("triggerAccepted", current.updateScheduledWeaponTrigger(player, true, null, null))
            }
        }

        fun conclude() {
            val current = vehicle!!
            val now = case!!
            val snapshot = current.getWeaponScheduleSnapshot(now.seat, now.index)
            val shots = (snapshot?.acceptedSequence ?: 0L) - sequence
            current.updateScheduledWeaponTrigger(player, false, null, null)
            val data = current.getGunData(now.weapon)
            attempt.addProperty("shots", shots)
            attempt.addProperty("ammoAfter", data?.let { current.getAmmo(it) } ?: 0)
            attempt.addProperty("lastDecision", snapshot?.lastDecision ?: "no schedule")
            if (shots <= 0) {
                // the direct path, then every admission it needs
                val direct = current.vehicleShootResult(player, now.weapon)
                attempt.addProperty("directShot", direct.reason.name)
                attempt.add("causes", JsonObject().also { c ->
                    c.addProperty("armamentAllows", AircraftArmamentManager.allowsWeapon(current, now.weapon))
                    c.addProperty("fireAllowed", current.isVehicleActionFireAllowed())
                    if (data != null) {
                        c.addProperty("overheat", data.overHeat.get())
                        c.addProperty("heat", data.heat.get()); c.addProperty("heatPerShot", data.get(GunProp.HEAT_PER_SHOOT))
                        c.addProperty("reloading", data.reloading()); c.addProperty("charging", data.charging())
                        c.addProperty("boltNeeded", data.bolt.needed.get())
                        c.addProperty("ammo", current.getAmmo(data)); c.addProperty("ammoPerShot", data.get(GunProp.AMMO_COST_PER_SHOOT))
                        c.addProperty("magazine", data.get(GunProp.MAGAZINE))
                    }
                })
                silent.add(JsonObject().also {
                    it.addProperty("aircraft", report["aircraft"].asString); it.addProperty("seat", now.seat)
                    it.addProperty("weapon", now.weapon); it.addProperty("pass", attempt["pass"].asString)
                    it.addProperty("lastDecision", attempt["lastDecision"].asString)
                    it.addProperty("directShot", attempt["directShot"].asString)
                })
            }
            report.getAsJsonArray("guns").add(attempt)
            tested++
            case = null
        }

        /** Per aircraft: settle 20 ticks aloft, then one gun every [SLOT_TICKS]: hold [HOLD_TICKS], release, cool. */
        fun tick(): Boolean {
            if (vehicle == null) {
                val next = queue.removeFirstOrNull() ?: return true
                start(next); age = 0
                return false
            }
            val current = vehicle!!
            age++
            current.deltaMovement = Vec3.ZERO
            current.setPos(origin.x, origin.y, origin.z)
            if (age < 20) return false
            val slotAge = (age - 20) % SLOT_TICKS
            when {
                slotAge == 0 -> {
                    val next = cases.removeFirstOrNull()
                    if (next == null) end() else begin(next)
                }
                slotAge == HOLD_TICKS && case != null -> conclude()
            }
            return false
        }

        fun finish(error: Exception?) {
            if (error != null) results.add(JsonObject().also { it.addProperty("error", error.toString()) })
            try {
                if (case != null) runCatching { conclude() }
                end()
                player.inventory.items.indices.forEach { player.inventory.items[it] = oldInventory[it] }
                player.teleportTo(level, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch)
            } finally {
                val status = if (error == null && silent.size() == 0) "PASS" else "FAIL"
                val output = JsonObject().also {
                    it.addProperty("status", status); it.addProperty("gunsTested", tested)
                    it.add("silent", silent); it.add("aircraft", results)
                }
                Files.writeString(Path.of("aircraft-gun-fire.json"), GsonBuilder().setPrettyPrinting().create().toJson(output))
                player.sendSystemMessage(Component.literal("Aircraft gun fire: $status, ${silent.size()} silent of $tested"))
            }
        }
    }
}
