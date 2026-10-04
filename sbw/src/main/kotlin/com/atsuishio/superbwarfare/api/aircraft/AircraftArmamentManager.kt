package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.item.gun.resolvedProfileId

import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.AircraftArmamentNetwork
import com.atsuishio.superbwarfare.network.message.send.AircraftArmamentRequestMessage
import com.atsuishio.superbwarfare.tools.InventoryTool
import com.google.gson.*
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityMountEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.registries.ForgeRegistries
import org.joml.Vector3d
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.*

/** Server authority for equipment and designations. Rendering and editor drafts never mutate this state. */
@EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID)
object AircraftArmamentManager {
    /** The entity persistent-data compound holding the fitted armament (selections, counts, fired, paid). */
    const val EQUIPMENT = "BvpAircraftArmament"
    private const val PRESETS = "BvpAircraftPresets"
    /** Per hardpoint: munitions on the rack that the pilot paid for (the only refundable ones). */
    private const val PAID = "Paid"
    private data class Lease(val vehicle: VehicleEntity, val epoch: Long, val catalogue: Long,
        var pod: Boolean = false, var seek: JsonObject = JsonObject(), var seekRevision: Long = 0,
        var seekFingerprint: String = "", var seekChannels: Set<String> = emptySet(),
        var designationGeneration: Long = 0, var designating: Boolean = false,
        var stabilizeGeneration: Long = 0, var stabilizing: Boolean = false,
        val last: MutableMap<String, Long> = mutableMapOf())
    private val leases = WeakHashMap<ServerPlayer, Lease>()
    private val sequences = WeakHashMap<Connection, Long>()
    private val watchers = WeakHashMap<VehicleEntity, MutableSet<ServerPlayer>>()
    private data class VisualReceipt(val entityId: Int, val dimension: ResourceLocation,
        val catalogue: Long, val revision: Long)
    private val farReceipts = WeakHashMap<ServerPlayer, LinkedHashMap<UUID, VisualReceipt>>()
    private data class GroupCache(val tick: Int, val revision: Long, val catalogue: Long,
                                  val definition: JsonObject, val groups: List<AircraftWeaponGroups.Group>)
    private val groupCache = WeakHashMap<VehicleEntity, GroupCache>()
    private var nextEpoch = 1L
    private var nextSeekRevision = 1L

    internal fun diagnosticRequest(player: ServerPlayer, vehicle: VehicleEntity, operation: String,
        body: JsonObject, staleEpoch: Boolean = false, wrongDimension: Boolean = false) {
        check(java.lang.Boolean.getBoolean("bvp.diagnostics.scenarios"))
        val connection = player.connection.connection
        val previousSequence = sequences[connection]
        try {
            handle(player, AircraftArmamentRequestMessage(vehicle.uuid,
                if (wrongDimension) ResourceLocation("minecraft:the_nether") else vehicle.level().dimension().location(),
                if (staleEpoch) -1 else leases[player]?.epoch ?: 0,
                (previousSequence ?: 0) + 1, operation, body.toString()), body)
        } finally {
            // The fixture exercises admission on the server thread without consuming client sequence numbers.
            if (previousSequence == null) sequences.remove(connection) else sequences[connection] = previousSequence
        }
    }

    @JvmStatic fun definition(vehicle: VehicleEntity): JsonObject? = if (vehicle.level().isClientSide)
        AircraftArmamentClient.getState(vehicle.uuid)?.getAsJsonObject("Definition")
    else AircraftArmamentRegistry.aircraft[ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type)]?.let {
        // Store targeting pods are offered on every external pylon of a fixed-wing aircraft (AircraftTargetingPods).
        if (fixedWing(vehicle)) AircraftTargetingPods.withUniversalStores(it, AircraftArmamentRegistry.stores) else it
    }
    private fun fixedWing(vehicle: VehicleEntity) = vehicle.isFixedWingFlightVehicle() ||
        vehicle.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE

    /**
     * The aircraft's targeting pod as an aircraft `Pod` object, or null: its authored pod, else a fitted pod store
     * still on the airframe (AircraftTargetingPods.effective). The client reads the one its last snapshot carried.
     */
    @JvmStatic fun pod(vehicle: VehicleEntity): JsonObject? {
        val def = definition(vehicle) ?: return null
        if (vehicle.level().isClientSide) return def.getAsJsonObject("Pod")
        val chosen = selection(vehicle)
        return AircraftTargetingPods.effective(def, { chosen[it]?.asString },
            { AircraftArmamentRegistry.stores[ResourceLocation.tryParse(it)] }) {
            com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.detachedAt(vehicle, it)
        }
    }

    private fun equipment(vehicle: VehicleEntity): CompoundTag {
        val root = vehicle.persistentData
        if (!root.contains(EQUIPMENT, 10)) root.put(EQUIPMENT, CompoundTag())
        return root.getCompound(EQUIPMENT)
    }
    private fun mounts(def: JsonObject) = AircraftArmamentRegistry.mounts(def)
    /** The last validated selection per aircraft (server), reused while its inputs are unchanged. */
    private class SelectionMemo(val tag: CompoundTag, val hash: Int, val catalogue: Long, val definition: JsonObject?,
                                val json: JsonObject)
    private val selectionMemo = java.util.WeakHashMap<VehicleEntity, SelectionMemo>()

    /**
     * The equipped store per mount, validated against the aircraft's definition. Many per-tick paths (weapon
     * selection, store lookups, gun and rocket pod channels, capacity) ask for it several times a tick, so the
     * server keeps the result until the Selections tag (identity or content), the catalogue or the definition
     * changes. Callers only read it; one that hands it on (the snapshot) copies it.
     */
    private fun selection(vehicle: VehicleEntity): JsonObject {
        if (vehicle.level().isClientSide) return AircraftArmamentClient.getState(vehicle.uuid)
            ?.getAsJsonObject("Selections") ?: JsonObject()
        val state = equipment(vehicle)
        // no fitted stores (every ground vehicle): nothing to validate or remember
        if (!state.contains("Selections", 10)) return JsonObject()
        val nbt = state.getCompound("Selections")
        val definition = definition(vehicle)
        val hash = nbt.hashCode()
        selectionMemo[vehicle]?.let { memo ->
            if (memo.tag === nbt && memo.hash == hash && memo.catalogue == AircraftArmamentRegistry.revision &&
                memo.definition === definition) return memo.json
        }
        return computeSelection(nbt, definition).also {
            selectionMemo[vehicle] = SelectionMemo(nbt, hash, AircraftArmamentRegistry.revision, definition, it)
        }
    }

    private fun computeSelection(nbt: CompoundTag, definition: JsonObject?): JsonObject {
        val available = definition?.let(::mounts)?.associateBy { it["Id"].asString } ?: emptyMap()
        return JsonObject().also { j -> for (key in nbt.allKeys) {
            val pair = available[key] ?: continue
            if (allowed(pair, nbt.getString(key))) j.addProperty(key, nbt.getString(key))
        } }
    }
    internal fun equippedStoreId(vehicle: VehicleEntity, mount: String): String? = selection(vehicle)[mount]?.asString
    internal fun equippedStore(vehicle: VehicleEntity, mount: String): JsonObject? {
        val id = selection(vehicle)[mount]?.asString ?: return null
        return if (vehicle.level().isClientSide) AircraftArmamentClient.getState(vehicle.uuid)?.getAsJsonObject("Stores")?.getAsJsonObject(id)
            else AircraftArmamentRegistry.stores[ResourceLocation.tryParse(id)]
    }
    private fun rackCount(vehicle: VehicleEntity, mount: String): Int {
        val def = definition(vehicle) ?: return 1
        val hardpoint = mounts(def).firstOrNull { it["Id"].asString == mount } ?: return 1
        val store = equippedStore(vehicle, mount) ?: return 1
        val count = if (vehicle.level().isClientSide) AircraftArmamentClient.getState(vehicle.uuid)
            ?.getAsJsonObject("Counts")?.get(mount)?.asInt ?: 1
        else equipment(vehicle).getCompound("Counts").getInt(mount).coerceAtLeast(1)
        return count.coerceIn(1, AircraftPylonRacks.maxCopies(def, hardpoint, store))
    }
    internal fun mountCapacity(vehicle: VehicleEntity, mount: String): Int {
        val pair = definition(vehicle)?.let(::mounts)?.firstOrNull { it["Id"].asString == mount } ?: return 0
        val store = equippedStore(vehicle, mount) ?: return 0
        return AircraftArmamentRegistry.mountCapacity(pair, store["Capacity"]?.asInt ?: 1) * rackCount(vehicle, mount)
    }
    internal fun mountRemaining(vehicle: VehicleEntity, mount: String): Int {
        val used = if (vehicle.level().isClientSide)
            AircraftArmamentClient.getState(vehicle.uuid)?.getAsJsonObject("Fired")?.get(mount)?.asInt ?: 0
            else equipment(vehicle).getCompound("Fired").getInt(mount)
        return (mountCapacity(vehicle, mount) - used).coerceAtLeast(0)
    }

    // Loadout ammunition: munitions are bought from the pilot's inventory when fitted (AircraftLoadoutCost).
    private fun ammoExempt(player: ServerPlayer, vehicle: VehicleEntity) = AircraftLoadoutCost.exempt(player.isCreative,
        InventoryTool.hasCreativeAmmoBox(player), InventoryTool.hasCreativeAmmoBoxForVehicle(vehicle))
    /** Munitions from [fired] onward that sit on a station still attached to the airframe. */
    private fun liveRounds(vehicle: VehicleEntity, pair: JsonObject, store: JsonObject, copies: Int, fired: Int): Int {
        val capacity = AircraftArmamentRegistry.mountCapacity(pair, store["Capacity"]?.asInt ?: 1) * copies
        if (com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.mask(vehicle) == 0)
            return AircraftLoadoutCost.attached(capacity, fired) { false }
        return AircraftLoadoutCost.attached(capacity, fired) {
            com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.detachedAt(vehicle,
                AircraftPylonRacks.launchPosition(pair, store, copies, it))
        }
    }
    /** What every hardpoint holds now; detached stations and unpaid munitions are never refunded. */
    private fun fittedRacks(vehicle: VehicleEntity): Map<String, AircraftLoadoutCost.Rack> {
        val def = definition(vehicle) ?: return emptyMap()
        val chosen = selection(vehicle); val state = equipment(vehicle)
        val fired = state.getCompound("Fired"); val paid = state.getCompound(PAID)
        return mounts(def).mapNotNull { pair ->
            val key = pair["Id"].asString
            val id = chosen[key]?.asString ?: return@mapNotNull null
            val store = AircraftArmamentRegistry.stores[ResourceLocation.tryParse(id)] ?: return@mapNotNull null
            val rounds = liveRounds(vehicle, pair, store, rackCount(vehicle, key), fired.getInt(key))
            key to AircraftLoadoutCost.Rack(id, AircraftLoadoutCost.ammoId(store), rounds, paid.getInt(key).coerceIn(0, rounds))
        }.toMap()
    }
    /** Every hardpoint of a validated loadout, refitted full. */
    private fun plannedRacks(vehicle: VehicleEntity, validated: CompoundTag): Map<String, AircraftLoadoutCost.Rack> {
        val available = mounts(definition(vehicle)!!).associateBy { it["Id"].asString }
        val chosen = validated.getCompound("Selections"); val counts = validated.getCompound("Counts")
        return chosen.allKeys.associateWith { key ->
            val id = chosen.getString(key)
            val store = AircraftArmamentRegistry.stores.getValue(ResourceLocation(id))
            AircraftLoadoutCost.Rack(id, AircraftLoadoutCost.ammoId(store),
                liveRounds(vehicle, available.getValue(key), store, counts.getInt(key).coerceAtLeast(1), 0))
        }
    }
    private fun loadoutPlan(player: ServerPlayer, vehicle: VehicleEntity, validated: CompoundTag,
                            fitted: Map<String, AircraftLoadoutCost.Rack> = fittedRacks(vehicle),
                            exempt: Boolean = ammoExempt(player, vehicle)) =
        AircraftLoadoutCost.plan(fitted, plannedRacks(vehicle, validated), exempt)
    private fun ammoName(id: String): String {
        val item = ResourceLocation.tryParse(id)?.takeIf(ForgeRegistries.ITEMS::containsKey)
            ?.let(ForgeRegistries.ITEMS::getValue) ?: return id
        val key = item.descriptionId
        // A dedicated server has no mod language files; fall back to the item path in title case.
        return if (net.minecraft.locale.Language.getInstance().has(key)) net.minecraft.locale.Language.getInstance().getOrDefault(key)
            else id.substringAfter(':').split('_', '/').filter { it.isNotEmpty() }
                .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }
    /** Takes the plan's cost from the pilot all-or-nothing and gives refunds back (overflow drops). */
    private fun settle(player: ServerPlayer, plan: AircraftLoadoutCost.Plan) {
        if (plan.net.isEmpty()) return
        val items = LinkedHashMap<net.minecraft.world.item.Item, Int>()
        for ((id, delta) in plan.net) {
            val item = ResourceLocation.tryParse(id)?.takeIf(ForgeRegistries.ITEMS::containsKey)
                ?.let(ForgeRegistries.ITEMS::getValue)?.takeIf { it != net.minecraft.world.item.Items.AIR }
            // Unknown ammunition cannot be charged; a refund of it is simply dropped.
            if (item == null) { require(delta < 0) { "Unknown store ammunition: $id" }; continue }
            items.merge(item, delta, Int::plus)
        }
        val inventory = net.minecraftforge.items.wrapper.CombinedInvWrapper(
            net.minecraftforge.items.wrapper.PlayerMainInvWrapper(player.inventory),
            net.minecraftforge.items.wrapper.PlayerOffhandInvWrapper(player.inventory))
        AircraftLoadoutCost.settle(inventory, items, { ammoName(ForgeRegistries.ITEMS.getKey(it).toString()) }) { item, count ->
            InventoryTool.insertItem(player, ItemStack(item), count)
        }
    }
    internal fun nativeWeapons(pair: JsonObject, storeId: String?): List<String> {
        val mapped = storeId?.let { pair.getAsJsonObject("NativeWeaponIds")?.get(it) }
        return if (mapped?.isJsonArray == true) mapped.asJsonArray.map { it.asString }
            else listOfNotNull(mapped?.asString ?: pair["WeaponId"]?.asString)
    }
    private fun fittedGroups(vehicle: VehicleEntity): List<AircraftWeaponGroups.Group> {
        val def = definition(vehicle) ?: return emptyList()
        val revision = if (vehicle.level().isClientSide)
            AircraftArmamentClient.getState(vehicle.uuid)?.get("Revision")?.asLong ?: 0L
            else equipment(vehicle).getLong("Revision")
        val previous = groupCache[vehicle]
        if (previous?.tick == vehicle.tickCount && previous.revision == revision &&
            previous.catalogue == AircraftArmamentRegistry.revision && previous.definition === def)
            return previous.groups
        val chosen = selection(vehicle)
        val clientStores = if (vehicle.level().isClientSide)
            AircraftArmamentClient.getState(vehicle.uuid)?.getAsJsonObject("Stores") else null
        val fitted = mounts(def).mapNotNull { mount ->
            val key = mount["Id"].asString
            val storeId = chosen[key]?.asString ?: return@mapNotNull null
            val store = (if (vehicle.level().isClientSide) clientStores?.getAsJsonObject(storeId)
                else AircraftArmamentRegistry.stores[ResourceLocation.tryParse(storeId)])
                ?: return@mapNotNull null
            val category = store.get("Category")?.asString ?: return@mapNotNull null
            if (category in setOf("AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION", "LASER_GUIDED", "COMMAND_GUIDED", "CRUISE", "BOMB") &&
                !AircraftStoreWeapons.launchable(store)) return@mapNotNull null
            AircraftWeaponGroups.Mount(key, storeId, category, nativeWeapons(mount, storeId))
        }
        val result = AircraftWeaponGroups.groups(fitted)
        groupCache[vehicle] = GroupCache(vehicle.tickCount, revision,
            AircraftArmamentRegistry.revision, def, result)
        return result
    }
    internal fun groupFor(vehicle: VehicleEntity, weapon: String): AircraftWeaponGroups.Group? =
        fittedGroups(vehicle).firstOrNull { weapon == it.representative || weapon in it.members }

    /** Physical feeds/mounts behind a selectable aircraft row; null means an ordinary native gun. */
    @JvmStatic fun groupMembers(vehicle: VehicleEntity, representative: String): List<String>? =
        fittedGroups(vehicle).firstOrNull { it.representative == representative }?.members

    internal fun gunPodAliases(vehicle: VehicleEntity): List<String> = fittedGroups(vehicle)
        .filter { it.category == "GUN_POD" }.map { it.representative }
    /** All gun-pod channels of a definition (equippedOnly = false) for the store catalogue they were read from. */
    private class PodChannelMemo(val stores: Any?, val channels: List<String>)
    private val podChannelMemo: MutableMap<JsonObject, PodChannelMemo> =
        com.google.common.collect.MapMaker().weakKeys().makeMap()

    internal fun gunPodChannels(vehicle: VehicleEntity, equippedOnly: Boolean): List<String> {
        val def = definition(vehicle) ?: return emptyList()
        val chosen = if (equippedOnly) selection(vehicle) else null
        val clientStores = if (vehicle.level().isClientSide)
            AircraftArmamentClient.getState(vehicle.uuid)?.getAsJsonObject("Stores") else null
        // Every vehicle weapon tick asks whether each weapon is a pod; without a selection the answer depends only on
        // the definition and the store catalogue (both replaced, not edited, when they change), so it is kept per pair.
        val source: Any? = if (vehicle.level().isClientSide) clientStores else AircraftArmamentRegistry.stores
        if (!equippedOnly) podChannelMemo[def]?.takeIf { it.stores === source }?.let { return it.channels }
        return mounts(def).flatMap { mount ->
            val ids = if (equippedOnly) listOfNotNull(chosen?.get(mount["Id"].asString)?.asString)
                else mount.getAsJsonArray("AllowedStores")?.map { it.asString } ?: emptyList()
            ids.filter { id ->
                val store = if (vehicle.level().isClientSide) clientStores?.getAsJsonObject(id)
                    else AircraftArmamentRegistry.stores[ResourceLocation.tryParse(id)]
                store?.get("Category")?.asString == "GUN_POD"
            }.flatMap { nativeWeapons(mount, it) }
        }.distinct().also { if (!equippedOnly) podChannelMemo[def] = PodChannelMemo(source, it) }
    }
    internal fun rocketPodChannels(vehicle: VehicleEntity): List<String> {
        val def = definition(vehicle) ?: return emptyList()
        val chosen = selection(vehicle)
        return mounts(def).flatMap { mount ->
            val id = chosen[mount["Id"].asString]?.asString ?: return@flatMap emptyList()
            val store = if (vehicle.level().isClientSide)
                AircraftArmamentClient.getState(vehicle.uuid)?.getAsJsonObject("Stores")?.getAsJsonObject(id)
            else AircraftArmamentRegistry.stores[ResourceLocation.tryParse(id)]
            if (store?.get("Category")?.asString == "ROCKET_POD") nativeWeapons(mount, id)
            else emptyList()
        }.distinct()
    }
    @JvmStatic fun selectableWeapon(vehicle: VehicleEntity, weapon: String): Boolean {
        if (definition(vehicle) == null) return allowsWeapon(vehicle, weapon)
        val group = groupFor(vehicle, weapon)
        if (group != null) return group.representative == weapon
        if (AircraftGunPodGroups.isAlias(weapon) || AircraftStoreWeapons.mountId(weapon) != null) return false
        return allowsWeapon(vehicle, weapon)
    }
    @JvmStatic fun weaponPresentation(vehicle: VehicleEntity, weaponId: String): AircraftWeaponPresentation? {
        val group = fittedGroups(vehicle).firstOrNull { it.representative == weaponId } ?: return null
        if (group.category == "GUN_POD") return AircraftGunPodGroups.presentation(vehicle, weaponId)
        val key = group.mounts.first()
        val store = equippedStore(vehicle, key) ?: return null
        val category = when (store["Category"]?.asString) {
            "ROCKET_POD" -> "RKT"; "GUN_POD" -> "CNN"; "LASER_GUIDED", "COMMAND_GUIDED", "AIR_TO_GROUND", "ANTI_RADIATION" -> "AGM"
            "AIR_TO_AIR" -> "AAM"; "BOMB" -> "BMB"; "CRUISE" -> "AGM"; else -> return null
        }
        val ammo = if (category == "RKT") group.members.sumOf { vehicle.gunDataMap[it]?.ammo?.get() ?: 0 }
            else group.mounts.sumOf { mountRemaining(vehicle, it) }
        val capacity = if (category == "RKT") group.members.sumOf { vehicle.gunDataMap[it]?.get(com.atsuishio.superbwarfare.data.gun.GunProp.MAGAZINE) ?: 0 }
            else group.mounts.sumOf { mountCapacity(vehicle, it) }
        return AircraftWeaponPresentation(category, store["Name"].asString, ammo, capacity,
            AircraftGuidanceLabels.short(AircraftGuidanceLabels.mode(store)))
    }
    private fun selectGuns(vehicle: VehicleEntity) {
        val builtIn = definition(vehicle)?.getAsJsonArray("BuiltInWeapons")?.map { it.asString } ?: return
        val index = vehicle.getWeaponIds(0).indexOfFirst { it in builtIn && vehicle.getGunData(it) != null }
        if (index >= 0) vehicle.setWeaponIndex(0, index)
    }
    @JvmStatic fun tryFireStore(vehicle: VehicleEntity, controller: net.minecraft.world.entity.LivingEntity?, weaponId: String): com.atsuishio.superbwarfare.api.weapon.ShotResult? {
        val key = AircraftStoreWeapons.mountId(weaponId) ?: return null
        val player = controller as? ServerPlayer
        if (player == null || !pilot(player, vehicle) || !vehicle.isVehicleActionFireAllowed())
            return com.atsuishio.superbwarfare.api.weapon.ShotResult.rejected(com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason.ACTION_BLOCKED, weaponId)
        val group = groupFor(vehicle, weaponId)?.takeIf { it.representative == weaponId }
        val mounts = group?.mounts ?: listOf(key)
        val cursors = equipment(vehicle).getCompound("GroupCursor")
        val live = AircraftWeaponGroups.orderedLiveMounts(mounts, cursors.getInt(weaponId)) {
            mountRemaining(vehicle, it)
        }
        for (mount in live) {
            try {
                fire(player, vehicle, JsonObject().apply { addProperty("Pair", mount) })
                if (group != null) {
                    cursors.putInt(weaponId, (mounts.indexOf(mount) + 1) % mounts.size)
                    equipment(vehicle).put("GroupCursor", cursors)
                }
                publish(vehicle)
                return com.atsuishio.superbwarfare.api.weapon.ShotResult(com.atsuishio.superbwarfare.api.weapon.ShotStatus.ACCEPTED,
                    com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason.NONE, weaponId, null, null, null, emptyList())
            } catch (_: IllegalArgumentException) {
                // A cooling or exhausted physical mount cannot consume the group's accepted shot.
            }
        }
        return com.atsuishio.superbwarfare.api.weapon.ShotResult.rejected(com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason.CANNOT_SHOOT, weaponId)
    }
    private fun allowed(pair: JsonObject, id: String) =
        pair.getAsJsonArray("AllowedStores")?.any { it.asString == id } == true &&
            AircraftArmamentRegistry.stores.containsKey(ResourceLocation.tryParse(id))

    @JvmStatic fun allowsWeapon(vehicle: VehicleEntity, weaponName: String): Boolean {
        if (AircraftGunPodGroups.isAlias(weaponName)) return AircraftGunPodGroups.loaded(vehicle, weaponName).isNotEmpty()
        if (AircraftStoreWeapons.mountId(weaponName) != null) {
            val members = groupMembers(vehicle, weaponName) ?: return false
            return members.any { AircraftStoreWeapons.available(vehicle, it) }
        }
        val def = definition(vehicle) ?: return true
        if (def.getAsJsonArray("SuspendedWeapons")?.none { it.asString == weaponName } != false) return true
        val chosen = selection(vehicle)
        return mounts(def).any { pair -> weaponName in nativeWeapons(pair, chosen[pair["Id"].asString]?.asString) &&
            AircraftArmamentRegistry.mountPositions(pair).none {
                com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.detachedAt(vehicle, it) } &&
            chosen[pair["Id"].asString]?.asString?.let { id ->
                val store = if (vehicle.level().isClientSide) AircraftArmamentClient.getState(vehicle.uuid)
                    ?.getAsJsonObject("Stores")?.getAsJsonObject(id)
                    else AircraftArmamentRegistry.stores[ResourceLocation.tryParse(id)]
                val compatible = if (vehicle.level().isClientSide)
                    pair.getAsJsonArray("AllowedStores")?.any { it.asString == id } == true else allowed(pair, id)
                val gunProfile = store?.get("GunProfile")?.asString
                val expectedProjectiles = gunProfile?.let {
                    com.atsuishio.superbwarfare.data.CustomData.GUN_DATA[it]
                }?.let { profile -> buildSet {
                    profile.projectile.value.resolvedProfileId()?.let(::add)
                    for (entry in profile.ammoConsumers) {
                        entry.value.projectile?.value?.resolvedProfileId()?.let(::add)
                        entry.value.override?.getAsJsonObject("Projectile")?.get("Profile")?.asString
                            ?.let(ResourceLocation::tryParse)?.let(::add)
                    }
                } } ?: emptySet()
                val actualProjectile = vehicle.getGunData(weaponName)
                    ?.get(com.atsuishio.superbwarfare.data.gun.GunProp.PROJECTILE)?.resolvedProfileId()
                compatible && store != null && store["Category"]?.asString !in
                    setOf("LASER_GUIDED", "COMMAND_GUIDED", "AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION", "BOMB", "CRUISE", "VISUAL_ONLY",
                        AircraftTargetingPods.CATEGORY) &&
                    (vehicle.level().isClientSide || gunProfile == null || actualProjectile != null && actualProjectile in expectedProjectiles)
            } == true }
    }

    @JvmStatic fun weaponSelectionRevision(vehicle: VehicleEntity): Int =
        if (definition(vehicle) == null) 0 else selection(vehicle).hashCode() + AircraftArmamentRegistry.revision.toInt() +
            (if (vehicle.level().isClientSide) AircraftArmamentClient.getState(vehicle.uuid)?.get("Revision")?.asLong ?: 0 else equipment(vehicle).getLong("Revision")).toInt()

    private fun base(vehicle: VehicleEntity) = JsonObject().also {
        it.addProperty("Vehicle", vehicle.uuid.toString()); it.addProperty("EntityId", vehicle.id)
        it.addProperty("Dimension", vehicle.level().dimension().location().toString())
    }
    private fun pointJson(p: Vec3) = JsonArray().also { it.add(p.x); it.add(p.y); it.add(p.z) }
    @JvmStatic fun snapshot(vehicle: VehicleEntity): JsonObject = base(vehicle).also { out ->
        out.addProperty("Revision", equipment(vehicle).getLong("Revision"))
        out.addProperty("CatalogueRevision", AircraftArmamentRegistry.revision)
        out.add("Selections", selection(vehicle).deepCopy())
        // A fitted pod store gives the client its pod exactly like an authored one (AircraftTargetingPods).
        out.add("Definition", (definition(vehicle)?.deepCopy() ?: JsonObject()).also { def ->
            if (!def.has("Pod")) pod(vehicle)?.let { def.add("Pod", it) }
        })
        out.add("Counts", JsonObject().also { counts ->
            for ((key, _) in selection(vehicle).entrySet()) counts.addProperty(key, rackCount(vehicle, key))
        })
        val used = equipment(vehicle).getCompound("Fired")
        out.add("Fired", JsonObject().also { counts ->
            definition(vehicle)?.let { def -> mounts(def).forEach { mount ->
                val key = mount["Id"].asString
                if (used.contains(key)) counts.addProperty(key, used.getInt(key).coerceIn(0, 20000))
            } }
        })
        val stores = JsonObject()
        definition(vehicle)?.let { def -> mounts(def).forEach { pair ->
            pair.getAsJsonArray("AllowedStores")?.forEach { id ->
                AircraftArmamentRegistry.stores[ResourceLocation.tryParse(id.asString)]?.let { stores.add(id.asString, it.deepCopy()) }
            }
        } }
        out.add("Stores", stores)
        val p = (vehicle.level() as? ServerLevel)?.let { AircraftDesignationData.get(it).get(vehicle.uuid) }
        p?.position?.let { out.add("Point", pointJson(it)) } ?: out.addProperty("ClearPoint", true)
        out.addProperty("PointRevision", p?.revision ?: 0)
        out.addProperty("Open", false)
    }

    private fun presets(player: ServerPlayer, vehicle: VehicleEntity): CompoundTag {
        val root = player.persistentData
        if (!root.contains(PRESETS, 10)) root.put(PRESETS, CompoundTag())
        val all = root.getCompound(PRESETS)
        val type = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type).toString()
        if (!all.contains(type, 10)) all.put(type, CompoundTag())
        return all.getCompound(type)
    }
    private fun reply(player: ServerPlayer, lease: Lease, open: Boolean = false, message: String = "") {
        val out = snapshot(lease.vehicle)
        out.addProperty("Epoch", lease.epoch); out.addProperty("PodActive", lease.pod)
        out.addProperty("Open", open); out.addProperty("Message", message)
        if (!lease.seek.has("Revision")) updateSeeker(player, lease, false)
        out.add("Seek", lease.seek.deepCopy())
        val saved = JsonObject()
        val nbt = presets(player, lease.vehicle)
        val available = definition(lease.vehicle)?.let(::mounts)?.associateBy { it["Id"].asString }.orEmpty()
        var oldPreset = false
        for (name in nbt.allKeys) {
            val preset = nbt.getCompound(name)
            val choices = if (preset.contains("Selections", 10)) preset.getCompound("Selections") else preset
            saved.add(name, JsonObject().also { preview -> for (pair in choices.allKeys) {
                val mount = available[pair]
                if (mount != null && allowed(mount, choices.getString(pair))) preview.addProperty(pair, choices.getString(pair))
                else oldPreset = true
            } })
        }
        // A stored preset from an older station layout must not invalidate the entire client
        // snapshot. Keep its saved bytes; LOAD still validates them before changing equipment.
        if (oldPreset && message.isBlank()) out.addProperty("Message", "Some saved presets need refitting after station changes.")
        out.add("Presets", saved)
        // Pilot-private loadout ammunition: exemption, paid munitions per hardpoint and server-priced presets.
        val exempt = ammoExempt(player, lease.vehicle)
        out.addProperty("AmmoExempt", exempt)
        val paid = equipment(lease.vehicle).getCompound(PAID)
        out.add("Paid", JsonObject().also { j -> for (key in paid.allKeys) if (key in available) j.addProperty(key, paid.getInt(key)) })
        if (!exempt && available.isNotEmpty()) {
            val fitted = fittedRacks(lease.vehicle)
            out.add("PresetCosts", JsonObject().also { costs -> for (name in nbt.allKeys) {
                val (choices, counts) = presetRequest(nbt.getCompound(name))
                val plan = try {
                    loadoutPlan(player, lease.vehicle, validateSelections(lease.vehicle, choices, counts, savedPreset = true), fitted, false)
                } catch (_: RuntimeException) { continue }
                costs.add(name, JsonObject().also { j -> plan.net.forEach { (ammo, delta) -> j.addProperty(ammo, delta) } })
            } })
        }
        AircraftArmamentNetwork.send(player, out)
    }
    /** A saved preset's selections and rack counts, in APPLY request form. */
    private fun presetRequest(stored: CompoundTag): Pair<JsonObject, JsonObject> {
        val n = if (stored.contains("Selections", 10)) stored.getCompound("Selections") else stored
        val choices = JsonObject().also { j -> for (k in n.allKeys) j.addProperty(k, n.getString(k)) }
        val counts = JsonObject().also { j -> val c = stored.getCompound("Counts")
            for (k in c.allKeys) j.addProperty(k, c.getInt(k)) }
        return choices to counts
    }
    /** Success text naming what the refit cost and returned; bounded for the snapshot Message field. */
    private fun fittedMessage(done: String, plan: AircraftLoadoutCost.Plan): String =
        if (plan.net.isEmpty()) "$done." else "$done · ${AircraftLoadoutCost.summary(plan.net, ::ammoName)}".take(240)
    private fun publish(vehicle: VehicleEntity) {
        val out = snapshot(vehicle)
        for (player in recipients(vehicle)) {
            val lease = leases[player]
            if (lease?.vehicle === vehicle) reply(player, lease) else AircraftArmamentNetwork.send(player, out)
        }
    }
    private fun recipients(vehicle: VehicleEntity): Set<ServerPlayer> = buildSet {
        addAll(watchers[vehicle] ?: emptySet())
        vehicle.passengers.filterIsInstance<ServerPlayer>().forEach(::add)
        (vehicle.level() as? ServerLevel)?.players()?.filterTo(this) {
            com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer.selected(it, vehicle)
        }
    }.filterTo(mutableSetOf()) { it.level() === vehicle.level() && it.connection.connection.isConnected }

    /** Bootstrap distant observers once per equipment revision, never a full receipt each frame. */
    fun publishFarIfChanged(player: ServerPlayer, vehicle: VehicleEntity) {
        if (definition(vehicle) == null) return
        val stamp = VisualReceipt(vehicle.id, vehicle.level().dimension().location(),
            AircraftArmamentRegistry.revision, equipment(vehicle).getLong("Revision"))
        val sent = farReceipts.getOrPut(player) { linkedMapOf() }
        if (sent[vehicle.uuid] == stamp) return
        val lease = leases[player]
        if (lease?.vehicle === vehicle) reply(player, lease)
        else AircraftArmamentNetwork.send(player, snapshot(vehicle))
        sent[vehicle.uuid] = stamp
        while (sent.size > 256) sent.remove(sent.keys.first())
    }
    private fun pilot(player: ServerPlayer, vehicle: VehicleEntity) = player.vehicle === vehicle &&
        vehicle.getSeatIndex(player) == 0 && player.isAlive && !player.isSpectator && vehicle.isAlive && !vehicle.isWreck

    @JvmStatic fun handle(player: ServerPlayer, request: AircraftArmamentRequestMessage, body: JsonObject) {
        if (request.sequence <= (sequences[player.connection.connection] ?: 0)) return
        sequences[player.connection.connection] = request.sequence
        if (request.dimension != player.level().dimension().location()) return
        val vehicle = player.serverLevel().getEntity(request.vehicle) as? VehicleEntity ?: return
        if (!pilot(player, vehicle) || definition(vehicle) == null) return
        var lease = leases[player]
        if (lease == null || lease.vehicle !== vehicle || lease.catalogue != AircraftArmamentRegistry.revision) {
            lease = Lease(vehicle, nextEpoch++, AircraftArmamentRegistry.revision); leases[player] = lease
        }
        if (request.operation == "OPEN") {
            val now = player.serverLevel().gameTime
            if (now - (lease.last["open"] ?: Long.MIN_VALUE / 2) >= 10) {
                lease.pod = false
                lease.last["open"] = now; reply(player, lease, true)
            }
            return
        }
        if (request.epoch != lease.epoch) { reply(player, lease, message = "Controls refreshed; try again."); return }
        val now = player.serverLevel().gameTime
        val bucket = when(request.operation) { "DESIGNATE", "CLEAR_POINT" -> "point"; "STABILIZE" -> "stabilize"; "TV" -> "tv"; "FIRE" -> "fire"; "SEEK" -> "seek"; "COMMAND" -> "command"; else -> "edit" }
        // TV steering arrives every tick and the newest line of sight always wins (owner 2026-09-30: the seeker
        // stepped when jittered packets were dropped).
        val interval = when (bucket) { "edit" -> 5L; "tv" -> 0L; else -> 2L }
        if (now - (lease.last[bucket] ?: Long.MIN_VALUE / 2) < interval) {
            if (bucket == "edit") reply(player, lease, message = "Please wait briefly before another change.")
            return
        }
        lease.last[bucket] = now
        try {
            when (request.operation) {
                "COMMAND" -> AircraftManualCommand.accept(vehicle,player,body,now)
                "TV" -> AircraftTvGuidance.command(player, vehicle, body)
                "APPLY" -> {
                    val plan = apply(player, vehicle, body); if (pod(vehicle) == null) lease.pod = false; publish(vehicle)
                    reply(player, lease, message = fittedMessage("Armament equipped", plan))
                }
                "SAVE_PRESET" -> {
                    val name = presetName(body); val choices = validateSelections(vehicle, body.getAsJsonObject("Selections"), body.getAsJsonObject("Counts"))
                    val saved = presets(player, vehicle); require(saved.contains(name) || saved.allKeys.size < 16) { "Preset limit reached (16)." }
                    val proposed = saved.copy(); proposed.put(name, choices)
                    require(proposed.toString().length <= 8192) { "Preset storage is full; delete an unused preset." }
                    saved.put(name, choices); reply(player, lease, message = "Preset saved.")
                }
                "LOAD_PRESET" -> {
                    val name = presetName(body); val saved = presets(player, vehicle)
                    require(saved.contains(name, 10)) { "Preset not found." }
                    val (choices, counts) = presetRequest(saved.getCompound(name))
                    val applyBody = JsonObject().also { it.addProperty("Revision", equipment(vehicle).getLong("Revision"))
                        it.add("Selections", choices); it.add("Counts", counts) }
                    val plan = apply(player, vehicle, applyBody, savedPreset = true); if (pod(vehicle) == null) lease.pod = false
                    publish(vehicle)
                    reply(player, lease, message = fittedMessage("Preset equipped", plan))
                }
                "DELETE_PRESET" -> { presets(player, vehicle).remove(presetName(body)); reply(player, lease, message = "Preset deleted.") }
                "POD" -> {
                    val active = body["Active"].asBoolean
                    require(!active || pod(vehicle) != null) { "No targeting pod: fit one on a pylon." }
                    lease.pod = active; lease.designationGeneration++; lease.stabilizeGeneration++; reply(player, lease)
                }
                "DESIGNATE" -> {
                    designateAsync(player, vehicle, lease, body)
                }
                "STABILIZE" -> {
                    require(lease.pod && body["Pod"]?.asBoolean == true) { "Activate the targeting pod first." }
                    designateAsync(player, vehicle, lease, body, stabilizeOnly = true)
                }
                "CLEAR_POINT" -> {
                    lease.designationGeneration++
                    require(AircraftDesignationData.get(player.serverLevel()).put(vehicle.uuid, null)) { "Designation storage is full." }
                    publishPoint(vehicle)
                }
                "FIRE" -> { fire(player, vehicle, body); publish(vehicle) }
            }
        } catch (e: IllegalArgumentException) {
            reply(player, lease, message = e.message?.take(160) ?: "Invalid armament request.")
        } catch (_: Exception) {
            reply(player, lease, message = "Invalid armament request.")
        }
    }

    private fun presetName(body: JsonObject): String {
        val name = body["Name"]?.asString?.trim() ?: ""
        require(name.length in 1..32 && name.none { it.isISOControl() }) { "Use a preset name of 1â€“32 characters." }
        return name
    }
    private fun validateSelections(vehicle: VehicleEntity, choices: JsonObject?, rawCounts: JsonObject?,
                                   savedPreset: Boolean = false): CompoundTag {
        require(choices != null && choices.size() <= 16) { "Invalid loadout." }
        val available = mounts(definition(vehicle)!!).associateBy { it["Id"].asString }
        val nbt = CompoundTag()
        for ((key, entry) in choices.entrySet()) {
            val pair = available[key]; require(pair != null) { "Unknown hardpoint: $key" }
            val id = entry.asString
            if (id.isEmpty()) continue
            require(allowed(pair, id)) { "Store is not approved for $key." }
            nbt.putString(key, id)
        }
        val definition = definition(vehicle)!!
        val currentlySelected = selection(vehicle)
        AircraftArmamentRegistry.validateMountConflicts(definition, nbt.allKeys)
        val counts = CompoundTag()
        require(rawCounts == null || rawCounts.entrySet().all { it.key in nbt.allKeys }) { "Count without equipped store." }
        for (key in nbt.allKeys) {
            val store = AircraftArmamentRegistry.stores[ResourceLocation(nbt.getString(key))]!!
            val requestedCount = rawCounts?.get(key)?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "Invalid rack quantity." }
                it.asBigDecimal.intValueExact()
            }
            val authoredCount = store["FixedRackCount"]?.asInt?.takeUnless {
                available.getValue(key)["Internal"]?.asBoolean == true
            }
            val count = if (savedPreset) (requestedCount ?: authoredCount ?: 1).also {
                require(authoredCount == null || it == authoredCount) { "Saved rack no longer matches its fixed store." }
            } else AircraftPylonRacks.selectionCopies(currentlySelected[key]?.asString,
                nbt.getString(key), rackCount(vehicle, key), requestedCount, authoredCount,
                available.getValue(key)["Internal"]?.asBoolean == true &&
                    available.getValue(key)["QuantitySelectable"]?.asBoolean != false)
            require(count in 1..AircraftPylonRacks.maxCopies(definition, available.getValue(key), store)) { "Rack limit exceeded on $key." }
            val pylonMass = (store["MassKg"]?.asDouble ?: 0.0) * (store["Capacity"]?.asInt ?: 1) * count +
                (store["RackMassKg"]?.asDouble ?: 0.0)
            require(pylonMass <= (available.getValue(key)["MaxPylonMassKg"]?.asDouble ?:
                definition["MaxPylonMassKg"]?.asDouble ?: Double.POSITIVE_INFINITY) + 1e-6) {
                "Pylon mass limit exceeded on $key."
            }
            counts.putInt(key, count)
        }
        val validated = CompoundTag().also { it.put("Selections", nbt); it.put("Counts", counts) }
        if (available.isEmpty()) return validated
        val limit = definition["MaxPayloadKg"]?.asDouble
        require(limit != null && limit > 0.0) { "Aircraft payload limit is not authored." }
        val selected = nbt.allKeys.associateWith { key ->
            AircraftArmamentRegistry.stores[ResourceLocation(nbt.getString(key))]!!
        }
        require(selected.values.all { it["MassKg"]?.asDouble?.let { mass -> mass > 0.0 && mass.isFinite() } == true }) {
            "Store mass is not authored."
        }
        val mass = AircraftArmamentRegistry.loadoutMassKg(definition, selected, counts.allKeys.associateWith(counts::getInt))
        require(mass <= limit + 1.0e-6) { "Payload ${mass.toInt()} kg exceeds maximum ${limit.toInt()} kg." }
        return validated
    }
    private fun apply(player: ServerPlayer, vehicle: VehicleEntity, body: JsonObject,
                      savedPreset: Boolean = false): AircraftLoadoutCost.Plan {
        require(vehicle.onGround() && vehicle.deltaMovement.lengthSqr() <= 0.0025) { "Stop the aircraft on the ground before fitting weapons." }
        val state = equipment(vehicle)
        require(body["Revision"]?.asLong == state.getLong("Revision")) { "Loadout changed; reopen the editor." }
        val choices = validateSelections(vehicle, body.getAsJsonObject("Selections"), body.getAsJsonObject("Counts"), savedPreset)
        // Every hardpoint is refitted full: pay for what is missing, get back what comes off. Nothing
        // below may change the loadout unless the pilot's inventory settled the whole price.
        val plan = loadoutPlan(player, vehicle, choices)
        settle(player, plan)
        state.put("Selections", choices.getCompound("Selections")); state.put("Counts", choices.getCompound("Counts")); state.putLong("Revision", state.getLong("Revision") + 1)
        state.put(PAID, CompoundTag().also { paid -> for (key in choices.getCompound("Selections").allKeys)
            plan.paid(key).takeIf { it > 0 }?.let { paid.putInt(key, it) } })
        state.remove("Fired"); state.remove("LastFire"); state.remove("GroupCursor"); AircraftMissileLauncher.clear(vehicle)
        AircraftRocketPodOrder.clear(vehicle); selectGuns(vehicle)
        return plan
    }
    private fun fire(player: ServerPlayer, vehicle: VehicleEntity, body: JsonObject) {
        val pair = mounts(definition(vehicle)!!).firstOrNull { it["Id"].asString == body["Pair"]?.asString }
        require(pair != null) { "Choose a hardpoint." }
        val id = selection(vehicle)[pair["Id"].asString]?.asString
        require(id != null && allowed(pair, id)) { "No store equipped." }
        val store = AircraftArmamentRegistry.stores[ResourceLocation(id)]!!
        val category = store["Category"].asString
        val guidedMissile = category in setOf("AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION", "CRUISE") && store.has("Guidance")
        val bomb = category == "BOMB" && store.has("Bomb")
        val cruise = category == "CRUISE" && store.has("Flight")
        require(category != AircraftTargetingPods.CATEGORY) { "A targeting pod is a sensor, not a weapon." }
        require(category != "VISUAL_ONLY" && (category !in setOf("AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION", "BOMB", "CRUISE") || guidedMissile || bomb || cruise)) { "This store is visual only in this version." }
        require(com.atsuishio.superbwarfare.entity.vehicle.base.permitsLandingGearShot(
            AircraftMountGearInterlock.required(pair), vehicle.gearUp, vehicle.synchedGearRot)) {
            "Retract the landing gear to release from ${pair["Name"].asString}."
        }
        val weapon = nativeWeapons(pair, id).singleOrNull()
        if (category !in setOf("LASER_GUIDED", "COMMAND_GUIDED") && !guidedMissile && !bomb && !cruise) {
            require(weapon != null) { "This store has no firing implementation yet." }
            require(vehicle.vehicleShootResult(player, weapon).isAccepted()) { "Weapon cannot fire now." }
            return
        }
        val weaponId = AircraftStoreWeapons.PREFIX + pair["Id"].asString
        val equipped = listOfNotNull(vehicle.getGunName(0), vehicle.getSecondaryWeaponIndex(0)?.let { vehicle.getGunName(0, it) })
        val selectedGroup = groupFor(vehicle, weaponId)?.representative ?: weaponId
        require(selectedGroup in equipped) { "Select this store in a weapon slot first." }
        // A TV bomb needs no lock to release: its seeker view opens after release (AircraftTvGuidance).
        if (guidedMissile) {
            val lock = AircraftMissileLauncher.update(vehicle, player, selectedGroup, store)
            require(lock >= 0) { "Compatible Fire From Above missile support is unavailable." }
            require(lock == 2) { "Hold the target in the seeker cone until locked." }
        }
        val state = equipment(vehicle); val key = pair["Id"].asString
        val fired = state.getCompound("Fired"); var used = fired.getInt(key)
        while (used >= 0 && used < mountCapacity(vehicle, key) &&
            com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.detachedAt(vehicle,
                AircraftPylonRacks.launchPosition(pair, store, rackCount(vehicle, key), used))) used++
        require(used >= 0 && used < mountCapacity(vehicle, key)) {
            "Hardpoint is empty; refit on the ground."
        }
        val times = state.getCompound("LastFire"); val now = player.serverLevel().gameTime
        require(!times.contains(key) || now - times.getLong(key) >= AircraftStoreWeapons.RELEASE_INTERVAL_TICKS) {
            "Launcher is cycling."
        }
        val mount = AircraftMountSweep.position(pair, used % AircraftArmamentRegistry.mountPositions(pair).size,
            AircraftPylonRacks.launchPosition(pair, store, rackCount(vehicle, key), used), vehicle.deltaMovement.length())
        require(!com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.detachedAt(vehicle, mount)) {
            "This weapon station was detached with the wing."
        }
        // Launch from where the round is drawn: its placement on the station (AircraftStoreAttachment).
        val offset = AircraftPylonRacks.launchOffset(pair, store, rackCount(vehicle, key), used, id)
        // The munition was bought when it was fitted (AircraftLoadoutCost); releasing it spends only the rack round.
        require(when {
            guidedMissile -> AircraftMissileLauncher.launch(vehicle, player, selectedGroup, mount, store, offset)
            bomb -> AircraftBombLauncher.launch(vehicle, player, mount, store, selectedGroup, offset)
            cruise -> AircraftCruiseLauncher.launch(vehicle, player, mount, store, offset)
            else -> AircraftLaserLauncher.launch(vehicle, player, mount, store, selectedGroup, offset)
        }) { "Munition release failed or launch conditions changed." }
        fired.putInt(key, used + 1); state.put("Fired", fired); times.putLong(key, now); state.put("LastFire", times)
        state.putLong("Revision", state.getLong("Revision") + 1)
        if (!guidedMissile && !bomb && !cruise) selectGuns(vehicle)
    }

    private fun designateAsync(player: ServerPlayer, vehicle: VehicleEntity, lease: Lease, body: JsonObject, stabilizeOnly: Boolean = false) {
        // Stabilization (the pod holding its aim on the ground) runs on its own channel, so it never delays or
        // cancels a designation, and a busy stabilizer silently skips a request (the client asks again).
        if (stabilizeOnly && lease.stabilizing) return
        require(stabilizeOnly || !lease.designating) { "Laser is measuring terrain; please wait." }
        val stabilizeRequest = if (stabilizeOnly) requireNotNull(body["StabilizeRequest"]) {
            "Missing stabilization request."
        }.asLong else -1L
        val podMode = body["Pod"]?.asBoolean == true
        val transform = vehicle.getVehicleTransform(1f)
        val localPosition: Vec3
        val direction: Vec3
        val range: Double
        if (podMode) {
            require(lease.pod) { "Activate the targeting pod first." }
            val pod = pod(vehicle) ?: throw IllegalArgumentException("No targeting pod.")
            val raw = AircraftArmamentRegistry.vector(body["Direction"])
                ?: throw IllegalArgumentException("Invalid pod direction.")
            require(raw.lengthSqr() in 0.99..1.01) { "Invalid pod direction." }
            direction = raw.normalize()
            val local = org.joml.Matrix4d(transform).invert().transformDirection(Vector3d(direction.x, direction.y, direction.z))
            val yaw = Math.toDegrees(atan2(-local.x, local.z))
            val pitch = Math.toDegrees(atan2(-local.y, hypot(local.x, local.z)))
            // Small tolerance covers motion between the rendered frame and its server receipt.
            require(abs(yaw) <= pod["YawLimit"].asDouble + 5.0 &&
                pitch in (pod["PitchMin"].asDouble - 5.0)..(pod["PitchMax"].asDouble + 5.0)) { "Target is outside pod limits." }
            // A pod on a swept pylon looks from where its wing has carried it.
            localPosition = AircraftTargetingPods.sweptPosition(definition(vehicle)!!, pod, vehicle.deltaMovement.length())!!
            range = (pod["Range"]?.asDouble ?: 8192.0).coerceIn(1.0, 8192.0)
        } else {
            localPosition = Vec3(0.0, vehicle.bbHeight * 0.5, 1.0)
            val d = transform.transformDirection(Vector3d(0.0, 0.0, 1.0)).normalize()
            range = 8192.0
            // Without the pod the laser follows the pilot's crosshair: the centre of the first-person view on the
            // frame the key was pressed on. The hull nose is the fallback when the client sent no view.
            val view = if (body["Camera"]?.asBoolean == true) AircraftArmamentRegistry.vector(body["Direction"]) else null
            direction = view?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() &&
                it.lengthSqr() in 0.99..1.01 }?.normalize() ?: Vec3(d.x, d.y, d.z)
        }
        val o = transform.transformPosition(Vector3d(localPosition.x, localPosition.y, localPosition.z))
        val serverOrigin = Vec3(o.x, o.y, o.z)
        // The pilot aimed from the pod (or cockpit) position of the frame on screen. A fast aircraft moves several
        // blocks per tick, so the same direction from the server's (newer) position lands off the aimed spot, badly
        // so at long range. Use the client's ray origin when it is plausibly this aircraft's.
        val clientOrigin = if (podMode || body["Camera"]?.asBoolean == true) AircraftArmamentRegistry.vector(body["Origin"]) else null
        val slack = 6.0 + vehicle.deltaMovement.length() * 3.0
        val origin = clientOrigin?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() &&
            (if (podMode) it.distanceToSqr(serverOrigin) else vehicle.boundingBox.distanceToSqr(it)) <= slack * slack }
            ?: serverOrigin
        require(origin.x.isFinite() && origin.y.isFinite() && origin.z.isFinite() && direction.lengthSqr().isFinite())
        val level = player.serverLevel()
        val generation = if (stabilizeOnly) ++lease.stabilizeGeneration else ++lease.designationGeneration
        val equipmentRevision = equipment(vehicle).getLong("Revision")
        if (stabilizeOnly) lease.stabilizing = true else lease.designating = true
        // Designation lases vehicles too: a painted vehicle keeps the spot on it while it moves. Stabilization only
        // anchors the pod view and stays on terrain.
        val pending = try {
            com.atsuishio.superbwarfare.api.vehicle.aim.VehicleLaserRangefinder.measureReturnAsync(
                level, vehicle, origin, direction, range, !stabilizeOnly)
        } catch (error: Exception) {
            if (stabilizeOnly) lease.stabilizing = false else lease.designating = false
            throw error
        }
        pending.whenComplete { laser, error ->
            val distance = laser?.distance
            level.server.execute {
                if (stabilizeOnly) lease.stabilizing = false else lease.designating = false
                val current = if (stabilizeOnly) lease.stabilizeGeneration else lease.designationGeneration
                if (leases[player] !== lease || generation != current ||
                    lease.catalogue != AircraftArmamentRegistry.revision || !pilot(player, vehicle) ||
                    vehicle.level() !== level || player.level() !== level ||
                    (podMode && !lease.pod) || equipment(vehicle).getLong("Revision") != equipmentRevision) return@execute
                if (error != null || distance == null || !distance.isFinite() || distance !in 0.0..range) {
                    if (!stabilizeOnly) reply(player, lease, message = "No saved terrain return along this laser ray.")
                    return@execute
                }
                val point = origin.add(direction.scale(distance))
                if (stabilizeOnly) {
                    AircraftArmamentNetwork.send(player, base(vehicle).also {
                        it.add("StabilizePoint", pointJson(point))
                        it.addProperty("StabilizeRequest", stabilizeRequest)
                    })
                    return@execute
                }
                val painted = laser?.vehicle?.takeIf { !it.isRemoved && it.isAlive && it.level() === level }
                if (!AircraftDesignationData.get(level).put(vehicle.uuid, point, painted)) {
                    reply(player, lease, message = "Designation storage is full.")
                    return@execute
                }
                publishPoint(vehicle)
                reply(player, lease, message = if (painted != null) "Target designated (tracking vehicle)." else "Target designated.")
            }
        }
    }
    private fun publishPoint(vehicle: VehicleEntity, track: Boolean = false) {
        val out = base(vehicle)
        if (track) out.addProperty("Track", true)
        val p = AircraftDesignationData.get(vehicle.level() as ServerLevel).get(vehicle.uuid)
        out.addProperty("PointRevision", p?.revision ?: 0)
        p?.position?.let { out.add("Point", pointJson(it)) } ?: out.addProperty("ClearPoint", true)
        for (player in recipients(vehicle)) AircraftArmamentNetwork.send(player, out)
    }
    @JvmStatic fun laserTarget(missile: Entity): Vec3? {
        val tag = missile.persistentData
        if (!tag.hasUUID("BvpLaserAircraft")) return null
        val level = missile.level() as? ServerLevel ?: return null
        if (tag.getString("BvpLaserDimension") != level.dimension().location().toString()) return null
        val point = AircraftDesignationData.get(level).get(tag.getUUID("BvpLaserAircraft"))
        if (point != null) {
            tag.putLong("BvpLaserRevision", point.revision)
            // On a painted vehicle the spot is where the vehicle is this tick, not where it was designated.
            val live = point.live(level)
            if (live == null) { tag.remove("BvpLaserLastPoint"); return null }
            tag.put("BvpLaserLastPoint", CompoundTag().also { n ->
                n.putDouble("X", live.x); n.putDouble("Y", live.y); n.putDouble("Z", live.z)
            })
            return live
        }
        if (!tag.contains("BvpLaserLastPoint", 10)) return null
        val last = tag.getCompound("BvpLaserLastPoint")
        return Vec3(last.getDouble("X"), last.getDouble("Y"), last.getDouble("Z"))
    }

    /** Each equipped slot owns continuous dwell; editor selection and client packets grant no lock time. */
    private fun updateSeeker(player: ServerPlayer, lease: Lease, send: Boolean = true) {
        val vehicle = lease.vehicle
        val equipped = listOfNotNull(vehicle.getGunName(0),
            vehicle.getSecondaryWeaponIndex(0)?.let { vehicle.getGunName(0, it) }).distinct()
        val active = equipped.mapNotNull { weapon ->
            val mount = AircraftStoreWeapons.mountId(weapon) ?: return@mapNotNull null
            val store = equippedStore(vehicle, mount) ?: return@mapNotNull null
            val members = groupFor(vehicle, weapon)?.takeIf { it.representative == weapon }?.mounts ?: listOf(mount)
            if (!store.has("Guidance") || members.none { mountRemaining(vehicle, it) > 0 }) return@mapNotNull null
            weapon to store
        }
        val channels = active.map { it.first }.toSet()
        (lease.seekChannels - channels).forEach { AircraftMissileLauncher.clear(vehicle, it) }
        lease.seekChannels = channels
        val primaryWeapon = vehicle.getGunName(0)
        val displayChannel = AircraftSeekerDisplayPolicy.choose(active.map { (weapon, store) ->
            AircraftSeekerDisplayPolicy.Channel(weapon, store["Category"]?.asString ?: "",
                store.getAsJsonObject("Guidance")?.get("Mode")?.asString ?: "",
                if (weapon == primaryWeapon) "PRIMARY" else "SECONDARY")
        })
        var display = JsonObject().apply {
            addProperty("WeaponId", ""); addProperty("Progress", 0); addProperty("Ready", false); addProperty("Status", "NO_TARGET")
            addProperty("Category", ""); addProperty("GuidanceMode", ""); addProperty("Slot", "")
            addProperty("ConeDegrees", 0); addProperty("Range", 0); addProperty("LockTicks", 0)
        }
        for ((weapon, store) in active) {
            val status = AircraftMissileLauncher.update(vehicle, player, weapon, store)
            val selectedDisplay = displayChannel ?: continue
            if (weapon != selectedDisplay.weaponId) continue
            val state = AircraftMissileLauncher.state(vehicle, weapon)
            val tuning = runCatching { AircraftMissileLauncher.guidance(store) }.getOrNull()
            display = JsonObject().apply {
                addProperty("WeaponId", weapon)
                addProperty("Category", selectedDisplay.category)
                addProperty("GuidanceMode", tuning?.mode ?: "")
                addProperty("Slot", selectedDisplay.slot)
                addProperty("ConeDegrees", tuning?.cone ?: 0.0)
                addProperty("Range", tuning?.range ?: 0.0)
                addProperty("LockTicks", tuning?.lockTicks ?: 0)
                addProperty("Progress", state.getDouble("Progress").coerceIn(0.0, 1.0))
                addProperty("Ready", status == 2)
                addProperty("Status", when (status) { -1 -> "UNAVAILABLE"; 1 -> "ACQUIRING"; 2 -> "READY"; else -> "NO_TARGET" })
                if (status > 0 && (state.hasUUID("TargetUUID") || state.getString("TargetType") == "BLOCK")) {
                    if (state.hasUUID("TargetUUID")) addProperty("TargetUUID", state.getUUID("TargetUUID").toString())
                    if (state.getString("TargetType") == "BLOCK") {
                        addProperty("TargetType", "BLOCK")
                        addProperty("TargetBlock", state.getLong("TargetBlock"))
                    }
                    add("TargetPosition", pointJson(Vec3(state.getDouble("TargetX"), state.getDouble("TargetY"), state.getDouble("TargetZ"))))
                }
            }
        }
        // Keep the private full receipt current, but stream only changed thin receipts at 10 Hz.
        val fingerprint = display.toString()
        if (fingerprint != lease.seekFingerprint && (!send || player.serverLevel().gameTime % 2L == 0L)) {
            lease.seekFingerprint = fingerprint
            lease.seekRevision = nextSeekRevision++
            display.addProperty("Revision", lease.seekRevision)
            lease.seek = display
            if (send) AircraftArmamentNetwork.send(player, base(vehicle).apply { add("Seek", display.deepCopy()) })
        }
    }

    private const val TRACK_PUBLISH_TICKS = 5
    private const val TRACK_PUBLISH_MOVE_SQR = 0.5 * 0.5

    /** Keeps the pilot's designation marker (and pod) on a painted vehicle that has moved. */
    private fun followPaintedVehicle(aircraft: VehicleEntity) {
        val level = aircraft.level() as? ServerLevel ?: return
        val data = AircraftDesignationData.get(level)
        val point = data.get(aircraft.uuid)?.takeIf { it.target != null } ?: return
        val live = point.live(level) ?: return
        if (point.position != null && point.position.distanceToSqr(live) < TRACK_PUBLISH_MOVE_SQR) return
        data.follow(aircraft.uuid, live) ?: return
        publishPoint(aircraft, track = true)
    }

    @SubscribeEvent fun tick(event: TickEvent.PlayerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val player = event.player as? ServerPlayer ?: return
        val vehicle = player.vehicle as? VehicleEntity
        if (vehicle == null || !pilot(player, vehicle) || definition(vehicle) == null) {
            leases.remove(player)?.let { AircraftMissileLauncher.clear(it.vehicle) }; return
        }
        val old = leases[player]
        if (old?.vehicle === vehicle && old.catalogue == AircraftArmamentRegistry.revision) {
            updateSeeker(player, old)
            if (player.tickCount % TRACK_PUBLISH_TICKS == 0) followPaintedVehicle(vehicle)
            return
        }
        old?.let { AircraftMissileLauncher.clear(it.vehicle) }
        val lease = Lease(vehicle, nextEpoch++, AircraftArmamentRegistry.revision)
        leases[player] = lease; updateSeeker(player, lease, false); reply(player, lease)
    }
    @SubscribeEvent fun mounted(event: EntityMountEvent) {
        (event.entityMounting as? ServerPlayer)?.let { leases.remove(it)?.let { lease -> AircraftMissileLauncher.clear(lease.vehicle) } }
    }
    @SubscribeEvent fun tracking(event: PlayerEvent.StartTracking) {
        val vehicle = event.target as? VehicleEntity ?: return
        val player = event.entity as? ServerPlayer ?: return
        if (definition(vehicle) != null) {
            watchers.getOrPut(vehicle) { mutableSetOf() }.add(player)
            AircraftArmamentNetwork.send(player, snapshot(vehicle))
        }
    }
    @SubscribeEvent fun untracking(event: PlayerEvent.StopTracking) {
        val vehicle = event.target as? VehicleEntity ?: return
        watchers[vehicle]?.remove(event.entity)
    }
    @SubscribeEvent fun logout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        leases.remove(player)?.let { AircraftMissileLauncher.clear(it.vehicle) }; sequences.remove(player.connection.connection)
        farReceipts.remove(player)
        watchers.values.forEach { it.remove(player) }
    }
    @SubscribeEvent fun dimensionChanged(event: PlayerEvent.PlayerChangedDimensionEvent) {
        (event.entity as? ServerPlayer)?.let { farReceipts.remove(it) }
    }
    @SubscribeEvent fun clone(event: PlayerEvent.Clone) {
        if (event.original.persistentData.contains(PRESETS))
            event.entity.persistentData.put(PRESETS, event.original.persistentData.getCompound(PRESETS).copy())
    }
    @SubscribeEvent fun stopped(event: ServerStoppedEvent) {
        leases.values.forEach { AircraftMissileLauncher.clear(it.vehicle) }; leases.clear(); sequences.clear(); watchers.clear(); farReceipts.clear(); AircraftStoreWeapons.clear()
    }
}
