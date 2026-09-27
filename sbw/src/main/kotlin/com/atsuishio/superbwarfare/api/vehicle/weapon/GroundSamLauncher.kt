package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftMissileLauncher
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraftforge.event.AddReloadListenerEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.registries.ForgeRegistries
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Surface-to-air missiles of ground vehicles, guided by Fire From Above (FFA).
 *
 * `data/<ns>/sbw/ground_sams/<vehicle>.json` names the vehicle (by entity type id) and, per native weapon, the FFA
 * guidance and flight profile. While the weapon is selected the gunner's launcher bore searches FFA's airborne
 * contacts every tick; firing with a lock launches an FFA interceptor from the weapon's muzzle and spends the
 * native round. Without a lock a weapon either fires its native projectile (`"WithoutLock": "NATIVE"`, e.g. the
 * Tunguska's optical command guidance) or refuses (`"REFUSE"`, IR missiles). Without FFA nothing changes.
 * An optional `Radar` block registers the vehicle as an FFA mobile radar (search/track coverage and its HUD).
 */
@EventBusSubscriber(modid = Mod.MODID)
object GroundSamLauncher {
    const val CHANNEL_PREFIX = "ground_sam:"

    data class Definition(val radarRange: Int?, val weapons: Map<String, JsonObject>)

    @Volatile private var definitions: Map<ResourceLocation, Definition> = emptyMap()
    private data class Engagement(val vehicle: VehicleEntity, val channel: String)
    private val active = WeakHashMap<ServerPlayer, Engagement>()
    private val lastStatus = WeakHashMap<ServerPlayer, Int>()

    fun definition(vehicle: VehicleEntity): Definition? =
        ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type)?.let { definitions[it] }

    /** FFA mobile-radar range for this vehicle, or null when it carries no SAM radar. */
    @JvmStatic fun radarRange(vehicle: VehicleEntity): Int? = definition(vehicle)?.radarRange

    private fun store(vehicle: VehicleEntity, weapon: String?): JsonObject? =
        weapon?.let { definition(vehicle)?.weapons?.get(it) }

    private fun refuses(store: JsonObject) = store["WithoutLock"]?.asString == "REFUSE"

    /**
     * Called for each projectile of a server shot. Null leaves the shot to the native projectile; true means FFA
     * launched the missile (the native transaction then spends the round); false rejects the shot.
     */
    @JvmStatic fun tryLaunch(parameters: ShootParameters): Boolean? {
        val vehicle = parameters.ammoSupplier as? VehicleEntity ?: return null
        if (vehicle.level().isClientSide) return null
        val weapon = parameters.data.vehicleWeaponIdentity ?: return null
        val store = store(vehicle, weapon) ?: return null
        if (!AircraftMissileLauncher.available()) return null
        val player = parameters.shooter as? ServerPlayer ?: return null
        val channel = CHANNEL_PREFIX + weapon
        val status = AircraftMissileLauncher.updateToward(vehicle, player, channel, parameters.shootDirection, store)
        if (status == 2 && AircraftMissileLauncher.launchAt(vehicle, player, channel, parameters.shootPosition,
                parameters.shootDirection, store)) return true
        if (!refuses(store)) return null
        player.displayClientMessage(Component.literal("${label(store)}: no lock").withStyle(ChatFormatting.RED), true)
        return false
    }

    private fun label(store: JsonObject) = store["Name"]?.asString ?: "SAM"

    private fun release(player: ServerPlayer) {
        active.remove(player)?.let { AircraftMissileLauncher.clear(it.vehicle, it.channel) }
        lastStatus.remove(player)
    }

    @SubscribeEvent fun tick(event: TickEvent.PlayerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val player = event.player as? ServerPlayer ?: return
        val vehicle = player.vehicle as? VehicleEntity
        if (vehicle == null || vehicle.isWreck || definitions.isEmpty()) { if (player in active) release(player); return }
        val weapon = vehicle.getGunName(vehicle.getSeatIndex(player))
        val store = store(vehicle, weapon)
        if (store == null || weapon == null) { if (player in active) release(player); return }
        val channel = CHANNEL_PREFIX + weapon
        val engagement = Engagement(vehicle, channel)
        val previous = active.put(player, engagement)
        if (previous != null && previous != engagement) AircraftMissileLauncher.clear(previous.vehicle, previous.channel)
        val status = AircraftMissileLauncher.updateToward(vehicle, player, channel, vehicle.getShootVec(weapon, 1f), store)
        if (status < 0) return
        hud(player, vehicle, store, channel, status)
    }

    private fun hud(player: ServerPlayer, vehicle: VehicleEntity, store: JsonObject, channel: String, status: Int) {
        val now = player.serverLevel().gameTime
        val changed = lastStatus.put(player, status) != status
        val manual = !refuses(store)
        val text = when {
            // FFA admits only the vehicle's first passenger as the launching operator.
            vehicle.passengers.firstOrNull() !== player ->
                Component.literal("${label(store)}: lock needs the vehicle's first crew member").withStyle(ChatFormatting.GRAY)
            status == 2 -> {
                val state = AircraftMissileLauncher.state(vehicle, channel)
                val distance = vehicle.position().distanceTo(net.minecraft.world.phys.Vec3(
                    state.getDouble("TargetX"), state.getDouble("TargetY"), state.getDouble("TargetZ")))
                Component.literal("${label(store)}: LOCK ${distance.roundToInt()} m — FIRE").withStyle(ChatFormatting.GREEN)
            }
            status == 1 -> {
                val progress = AircraftMissileLauncher.state(vehicle, channel).getDouble("Progress").coerceIn(0.0, 1.0)
                Component.literal("${label(store)}: ACQUIRING ${(progress * 100).roundToInt()}%").withStyle(ChatFormatting.YELLOW)
            }
            else -> Component.literal("${label(store)}: SEARCH" + if (manual) " · manual guidance" else "")
                .withStyle(ChatFormatting.GRAY)
        }
        if (changed || now % 10L == 0L) player.displayClientMessage(text, true)
        val tone = when (status) { 1 -> if (now % 6L == 0L) "LockingSound" else null; 2 -> if (changed || now % 12L == 0L) "LockedSound" else null; else -> null }
        tone?.let { key ->
            val id = store[key]?.asString ?: if (key == "LockingSound") "superbwarfare:javelin_locking" else "superbwarfare:javelin_locked"
            ResourceLocation.tryParse(id)?.let { player.playNotifySound(SoundEvent.createVariableRangeEvent(it), SoundSource.PLAYERS, 1f, 1f) }
        }
    }

    @SubscribeEvent fun loggedOut(event: PlayerEvent.PlayerLoggedOutEvent) { (event.entity as? ServerPlayer)?.let(::release) }
    @SubscribeEvent fun stopped(event: ServerStoppedEvent) { active.clear(); lastStatus.clear() }

    internal fun parse(obj: JsonObject): Definition {
        require(obj["Schema"]?.asInt == 1) { "Schema must be 1" }
        val radar = obj.getAsJsonObject("Radar")?.get("Range")?.asInt
        require(radar == null || radar in 16..4096) { "Radar.Range must be 16..4096" }
        val weapons = linkedMapOf<String, JsonObject>()
        for ((name, value) in obj.getAsJsonObject("Weapons").entrySet()) {
            val store = value.asJsonObject
            requireNotNull(AircraftMissileLauncher.guidance(store)) { "$name has no Guidance" }
            require(store["WithoutLock"]?.asString in setOf(null, "NATIVE", "REFUSE")) { "$name WithoutLock must be NATIVE or REFUSE" }
            weapons[name] = store.deepCopy()
        }
        require(weapons.isNotEmpty()) { "No weapons" }
        return Definition(radar, weapons)
    }

    private class Listener : SimpleJsonResourceReloadListener(Gson(), "sbw/ground_sams") {
        override fun apply(input: Map<ResourceLocation, JsonElement>, resources: ResourceManager, profiler: ProfilerFiller) {
            val loaded = linkedMapOf<ResourceLocation, Definition>()
            for ((id, value) in input) try {
                loaded[id] = parse(value.asJsonObject)
            } catch (e: Exception) {
                Mod.LOGGER.error("Invalid ground SAM {}: {}", id, e.message)
            }
            definitions = loaded
        }
    }

    @SubscribeEvent fun reload(event: AddReloadListenerEvent) { event.addListener(Listener()) }
}
