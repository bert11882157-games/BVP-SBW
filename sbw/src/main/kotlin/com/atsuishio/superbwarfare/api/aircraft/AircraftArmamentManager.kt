package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.item.gun.resolvedProfileId

import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.AircraftArmamentNetwork
import com.atsuishio.superbwarfare.network.message.send.AircraftArmamentRequestMessage
import com.google.gson.*
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
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
    private const val EQUIPMENT = "BvpAircraftArmament"
    private const val PRESETS = "BvpAircraftPresets"
    private data class Lease(val vehicle: VehicleEntity, val epoch: Long, val catalogue: Long,
        var pod: Boolean = false, var seek: JsonObject = JsonObject(), var seekRevision: Long = 0,
        var seekFingerprint: String = "", var seekChannels: Set<String> = emptySet(),
        var designationGeneration: Long = 0, var designating: Boolean = false,
        val last: MutableMap<String, Long> = mutableMapOf())
    private val leases = WeakHashMap<ServerPlayer, Lease>()
    private val sequences = WeakHashMap<Connection, Long>()
    private val watchers = WeakHashMap<VehicleEntity, MutableSet<ServerPlayer>>()
    private data class VisualReceipt(val entityId: Int, val dimension: ResourceLocation,
        val catalogue: Long, val revision: Long)
    private val farReceipts = WeakHashMap<ServerPlayer, LinkedHashMap<UUID, VisualReceipt>>()
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
    else AircraftArmamentRegistry.aircraft[ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type)]

    private fun equipment(vehicle: VehicleEntity): CompoundTag {
        val root = vehicle.persistentData
        if (!root.contains(EQUIPMENT, 10)) root.put(EQUIPMENT, CompoundTag())
        return root.getCompound(EQUIPMENT)
    }
    private fun mounts(def: JsonObject) = AircraftArmamentRegistry.mounts(def)
    private fun selection(vehicle: VehicleEntity): JsonObject {
        if (vehicle.level().isClientSide) return AircraftArmamentClient.getState(vehicle.uuid)
            ?.getAsJsonObject("Selections") ?: JsonObject()
        val nbt = equipment(vehicle).getCompound("Selections")
        val available = definition(vehicle)?.let(::mounts)?.associateBy { it["Id"].asString } ?: emptyMap()
        return JsonObject().also { j -> for (key in nbt.allKeys) {
            val pair = available[key] ?: continue
            if (allowed(pair, nbt.getString(key))) j.addProperty(key, nbt.getString(key))
        } }
    }
    internal fun equippedStore(vehicle: VehicleEntity, mount: String): JsonObject? {
        val id = selection(vehicle)[mount]?.asString ?: return null
        return if (vehicle.level().isClientSide) AircraftArmamentClient.getState(vehicle.uuid)?.getAsJsonObject("Stores")?.getAsJsonObject(id)
            else AircraftArmamentRegistry.stores[ResourceLocation.tryParse(id)]
    }
    internal fun equippedStoreId(vehicle: VehicleEntity, mount: String): String? =
        selection(vehicle)[mount]?.asString
    internal fun mountCapacity(vehicle: VehicleEntity, mount: String): Int {
        val pair = definition(vehicle)?.let(::mounts)?.firstOrNull { it["Id"].asString == mount } ?: return 0
        val store = equippedStore(vehicle, mount) ?: return 0
        return AircraftArmamentRegistry.mountCapacity(pair, store["Capacity"]?.asInt ?: 1)
    }
    internal fun mountRemaining(vehicle: VehicleEntity, mount: String): Int {
        val used = if (vehicle.level().isClientSide)
            AircraftArmamentClient.getState(vehicle.uuid)?.getAsJsonObject("Fired")?.get(mount)?.asInt ?: 0
            else equipment(vehicle).getCompound("Fired").getInt(mount)
        return (mountCapacity(vehicle, mount) - used).coerceAtLeast(0)
    }
    internal fun nativeWeapons(pair: JsonObject, storeId: String?): List<String> {
        val mapped = storeId?.let { pair.getAsJsonObject("NativeWeaponIds")?.get(it) }
        return if (mapped?.isJsonArray == true) mapped.asJsonArray.map { it.asString }
            else listOfNotNull(mapped?.asString ?: pair["WeaponId"]?.asString)
    }
    internal fun gunPodChannels(vehicle: VehicleEntity, equippedOnly: Boolean): List<String> {
        val def = definition(vehicle) ?: return emptyList()
        return mounts(def).flatMap { mount ->
            val ids = if (equippedOnly) listOfNotNull(selection(vehicle)[mount["Id"].asString]?.asString)
                else mount.getAsJsonArray("AllowedStores")?.map { it.asString } ?: emptyList()
            ids.filter { id ->
                val store = if (vehicle.level().isClientSide) AircraftArmamentClient.getState(vehicle.uuid)?.getAsJsonObject("Stores")?.getAsJsonObject(id)
                    else AircraftArmamentRegistry.stores[ResourceLocation.tryParse(id)]
                store?.get("Category")?.asString == "GUN_POD"
            }.flatMap { nativeWeapons(mount, it) }
        }.distinct()
    }
    @JvmStatic fun selectableWeapon(vehicle: VehicleEntity, weapon: String): Boolean {
        if (AircraftStoreWeapons.mountId(weapon) != null || weapon == AircraftGunPodGroups.GROUP) return false
        val definition = definition(vehicle)
        if (definition != null && mounts(definition).any { mount ->
                mount.getAsJsonArray("AllowedStores")?.any { weapon in nativeWeapons(mount, it.asString) } == true
            }) return false
        return allowsWeapon(vehicle, weapon)
    }
    @JvmStatic fun weaponPresentation(vehicle: VehicleEntity, weaponId: String): AircraftWeaponPresentation? {
        AircraftStoreWeapons.group(vehicle, weaponId)?.let { group ->
            val category = when (group.store["Category"]?.asString) {
                "CRUISE_MISSILE", "COORDINATE_MISSILE" -> "MSL"
                "ROCKET_POD" -> "RKT"; "GUN_POD" -> "CNN"; "LASER_GUIDED" -> "AGM"
                "AIR_TO_AIR" -> "AAM"; "BOMB" -> "BMB"; else -> return null
            }
            return AircraftWeaponPresentation(category, group.store["Name"]?.asString ?: group.storeId,
                group.ammo, group.capacity)
        }
        val def = definition(vehicle) ?: return null
        val pair = mounts(def).firstOrNull { AircraftStoreWeapons.PREFIX + it["Id"].asString == weaponId || weaponId in nativeWeapons(it, selection(vehicle)[it["Id"].asString]?.asString) } ?: return null
        val key = pair["Id"].asString
        val store = equippedStore(vehicle, key) ?: return null
        val category = when (store["Category"]?.asString) {
            "CRUISE_MISSILE", "COORDINATE_MISSILE" -> "MSL"
            "ROCKET_POD" -> "RKT"; "GUN_POD" -> "CNN"; "LASER_GUIDED" -> "AGM"; "AIR_TO_AIR" -> "AAM"; else -> return null
        }
        val ammo = if (category in setOf("RKT", "CNN")) vehicle.getGunData(weaponId)?.ammo?.get() ?: 0 else mountRemaining(vehicle, key)
        return AircraftWeaponPresentation(category, AircraftArmamentRegistry.storeWeaponName(pair, store),
            ammo, mountCapacity(vehicle, key))
    }
    @JvmStatic fun onDesignationAccepted(vehicle: VehicleEntity) {
        if (vehicle.level().isClientSide) return
        val def = definition(vehicle) ?: return
        val pair = mounts(def).firstOrNull { val key = it["Id"].asString
            equippedStore(vehicle, key)?.get("Category")?.asString == "LASER_GUIDED" && mountRemaining(vehicle, key) > 0
        } ?: return
        val storeId = equippedStoreId(vehicle, pair["Id"].asString) ?: return
        val index = vehicle.getWeaponIds(0).indexOf(AircraftStoreWeapons.groupId(storeId))
        if (index >= 0) vehicle.setWeaponIndex(0, index)
    }
    private fun selectGuns(vehicle: VehicleEntity) {
        val builtIn = definition(vehicle)?.getAsJsonArray("BuiltInWeapons")?.map { it.asString } ?: return
        val index = vehicle.getWeaponIds(0).indexOfFirst { it in builtIn && vehicle.getGunData(it) != null }
        if (index >= 0) vehicle.setWeaponIndex(0, index)
    }
    @JvmStatic fun tryFireStore(vehicle: VehicleEntity, controller: net.minecraft.world.entity.LivingEntity?, weaponId: String): com.atsuishio.superbwarfare.api.weapon.ShotResult? {
        if (!AircraftStoreWeapons.isChannel(weaponId)) return null
        val group = AircraftStoreWeapons.group(vehicle, weaponId)
            ?: return com.atsuishio.superbwarfare.api.weapon.ShotResult.rejected(com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason.NO_WEAPON, weaponId)
        val player = controller as? ServerPlayer
        if (player == null || !pilot(player, vehicle) || !vehicle.isVehicleActionFireAllowed())
            return com.atsuishio.superbwarfare.api.weapon.ShotResult.rejected(com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason.ACTION_BLOCKED, weaponId)
        return try {
            val selected = listOfNotNull(vehicle.getGunName(0), vehicle.getSecondaryWeaponIndex(0)?.let { vehicle.getGunName(0, it) })
            require(group.weaponId in selected) { "Equip this weapon in a weapon slot first." }
            if (!group.virtual) {
                return AircraftStoreWeapons.launchNative(group,
                    ammunition = { vehicle.gunDataMap[it]?.ammo?.get() ?: 0 },
                    shoot = { vehicle.vehicleShootResult(player, it) })
            }
            val key = group.next?.mountId ?: throw IllegalArgumentException("Weapon is empty; refit on the ground.")
            fire(player, vehicle, JsonObject().apply { addProperty("Pair", key) }, weaponSlotInput = true)
            publish(vehicle)
            com.atsuishio.superbwarfare.api.weapon.ShotResult(com.atsuishio.superbwarfare.api.weapon.ShotStatus.ACCEPTED,
                com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason.NONE, weaponId, null, null, null, emptyList())
        } catch (_: IllegalArgumentException) {
            com.atsuishio.superbwarfare.api.weapon.ShotResult.rejected(com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason.CANNOT_SHOOT, weaponId)
        }
    }
    private fun allowed(pair: JsonObject, id: String) =
        pair.getAsJsonArray("AllowedStores")?.any { it.asString == id } == true &&
            AircraftArmamentRegistry.stores.containsKey(ResourceLocation.tryParse(id))

    @JvmStatic fun allowsWeapon(vehicle: VehicleEntity, weaponName: String): Boolean {
        if (weaponName == AircraftGunPodGroups.GROUP) return AircraftGunPodGroups.loaded(vehicle).isNotEmpty()
        if (AircraftStoreWeapons.isChannel(weaponName)) return AircraftStoreWeapons.available(vehicle, weaponName)
        val def = definition(vehicle) ?: return true
        if (def.getAsJsonArray("SuspendedWeapons")?.none { it.asString == weaponName } != false) return true
        val chosen = selection(vehicle)
        return mounts(def).any { pair -> weaponName in nativeWeapons(pair, chosen[pair["Id"].asString]?.asString) &&
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
                    setOf("LASER_GUIDED", "AIR_TO_AIR", "VISUAL_ONLY", "CRUISE_MISSILE", "COORDINATE_MISSILE") &&
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
        out.add("Selections", selection(vehicle)); out.add("Definition", definition(vehicle)?.deepCopy() ?: JsonObject())
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
        for (name in nbt.allKeys) {
            val choices = nbt.getCompound(name)
            saved.add(name, JsonObject().also { for (pair in choices.allKeys) it.addProperty(pair, choices.getString(pair)) })
        }
        out.add("Presets", saved)
        AircraftArmamentNetwork.send(player, out)
    }
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
                lease.last["open"] = now; reply(player, lease, true)
            }
            return
        }
        if (request.epoch != lease.epoch) { reply(player, lease, message = "Controls refreshed; try again."); return }
        val now = player.serverLevel().gameTime
        val bucket = when(request.operation) { "DESIGNATE", "CLEAR_POINT" -> "point"; "FIRE" -> "fire"; "SEEK" -> "seek"; else -> "edit" }
        val interval = if (bucket == "edit") 5L else 2L
        if (now - (lease.last[bucket] ?: Long.MIN_VALUE / 2) < interval) {
            if (bucket == "edit") reply(player, lease, message = "Please wait briefly before another change.")
            return
        }
        lease.last[bucket] = now
        try {
            when (request.operation) {
                "APPLY" -> { apply(vehicle, body); publish(vehicle); reply(player, lease, message = "Armament equipped.") }
                "SAVE_PRESET" -> {
                    val name = presetName(body); val choices = validateSelections(vehicle, body.getAsJsonObject("Selections"))
                    val saved = presets(player, vehicle); require(saved.contains(name) || saved.allKeys.size < 16) { "Preset limit reached (16)." }
                    val proposed = saved.copy(); proposed.put(name, choices)
                    require(proposed.toString().length <= 8192) { "Preset storage is full; delete an unused preset." }
                    saved.put(name, choices); reply(player, lease, message = "Preset saved.")
                }
                "LOAD_PRESET" -> {
                    val name = presetName(body); val saved = presets(player, vehicle)
                    require(saved.contains(name, 10)) { "Preset not found." }
                    val choices = JsonObject().also { j -> val n = saved.getCompound(name); for (k in n.allKeys) j.addProperty(k, n.getString(k)) }
                    val applyBody = JsonObject().also { it.addProperty("Revision", equipment(vehicle).getLong("Revision")); it.add("Selections", choices) }
                    apply(vehicle, applyBody); publish(vehicle); reply(player, lease, message = "Preset equipped.")
                }
                "DELETE_PRESET" -> { presets(player, vehicle).remove(presetName(body)); reply(player, lease, message = "Preset deleted.") }
                "POD" -> {
                    val active = body["Active"].asBoolean
                    require(!active || definition(vehicle)?.has("Pod") == true) { "This aircraft has no authored targeting pod." }
                    lease.pod = active; lease.designationGeneration++; reply(player, lease)
                }
                "DESIGNATE" -> {
                    designateAsync(player, vehicle, lease, body)
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
        require(name.length in 1..32 && name.none { it.isISOControl() }) { "Use a preset name of 1–32 characters." }
        return name
    }
    private fun validateSelections(vehicle: VehicleEntity, choices: JsonObject?): CompoundTag {
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
        return nbt
    }
    private fun apply(vehicle: VehicleEntity, body: JsonObject) {
        require(vehicle.onGround() && vehicle.deltaMovement.lengthSqr() <= 0.0025) { "Stop the aircraft on the ground before fitting weapons." }
        val state = equipment(vehicle)
        require(body["Revision"]?.asLong == state.getLong("Revision")) { "Loadout changed; reopen the editor." }
        val choices = validateSelections(vehicle, body.getAsJsonObject("Selections"))
        state.put("Selections", choices); state.putLong("Revision", state.getLong("Revision") + 1)
        state.remove("Fired"); state.remove("LastFire"); AircraftMissileLauncher.clear(vehicle); selectGuns(vehicle)
    }
    private fun fire(player: ServerPlayer, vehicle: VehicleEntity, body: JsonObject,
                     weaponSlotInput: Boolean = false) {
        val pair = mounts(definition(vehicle)!!).firstOrNull { it["Id"].asString == body["Pair"]?.asString }
        require(pair != null) { "Choose a hardpoint." }
        val id = selection(vehicle)[pair["Id"].asString]?.asString
        require(id != null && allowed(pair, id)) { "No store equipped." }
        val store = AircraftArmamentRegistry.stores[ResourceLocation(id)]!!
        AircraftStoreControls.requireFireInput(store["Category"]?.asString, weaponSlotInput)
        val airToAir = store["Category"].asString == "AIR_TO_AIR" && store.has("Guidance")
        val coordinate = AircraftCoordinateLauncher.isCoordinate(store["Category"].asString)
        require(store["Category"].asString != "VISUAL_ONLY" && (store["Category"].asString != "AIR_TO_AIR" || airToAir)) { "This store is visual only in this version." }
        val weapon = nativeWeapons(pair, id).singleOrNull()
        if (store["Category"].asString != "LASER_GUIDED" && !airToAir && !coordinate) {
            require(weapon != null) { "This store has no firing implementation yet." }
            require(vehicle.vehicleShootResult(player, weapon).isAccepted()) { "Weapon cannot fire now." }
            return
        }
        val weaponId = AircraftStoreWeapons.groupId(id)
        val equipped = listOfNotNull(vehicle.getGunName(0), vehicle.getSecondaryWeaponIndex(0)?.let { vehicle.getGunName(0, it) })
        require(weaponId in equipped) { "Equip this missile in a weapon slot first." }
        if (airToAir) {
            val lock = AircraftMissileLauncher.update(vehicle, player, weaponId, store)
            require(lock >= 0) { "Compatible Fire From Above missile support is unavailable." }
            require(lock == 2) { "Hold the target in the seeker cone until locked." }
        }
        val state = equipment(vehicle); val key = pair["Id"].asString
        val fired = state.getCompound("Fired"); val used = fired.getInt(key)
        val times = state.getCompound("LastFire"); val now = player.serverLevel().gameTime
        val cruise = store["Category"].asString == "CRUISE_MISSILE"
        // One vehicle-owned cadence spans both weapon controls and every cruise pylon.
        val lastCruise = if (cruise && state.contains("LastCruiseLaunch"))
            state.getLong("LastCruiseLaunch") else null
        AircraftStoreLaunchTransaction.execute(used,
            AircraftArmamentRegistry.mountCapacity(pair, store["Capacity"]?.asInt ?: 1),
            if (times.contains(key)) times.getLong(key) else null, now, lastCruise, launch = {
                val mount = AircraftArmamentRegistry.launchPosition(pair, used)
                if (coordinate) require(AircraftCoordinateLauncher.launch(vehicle, player, key, mount, store)) {
                    "Coordinate missile cannot launch: assign a valid target to this weapon in the vehicle terminal and check missile range and FFA support."
                }
                else if (airToAir) require(AircraftMissileLauncher.launch(vehicle, player, weaponId, mount, store)) { "Missile lock or launch conditions changed." }
                else require(AircraftLaserLauncher.launch(vehicle, player, mount, store)) { "Invalid laser-munition profile." }
            }, commit = {
                fired.putInt(key, used + 1); state.put("Fired", fired)
                times.putLong(key, now); state.put("LastFire", times)
                if (cruise) state.putLong("LastCruiseLaunch", now)
                state.putLong("Revision", state.getLong("Revision") + 1)
            })
    }

    private fun designateAsync(player: ServerPlayer, vehicle: VehicleEntity, lease: Lease, body: JsonObject) {
        require(!lease.designating) { "Laser is measuring terrain; please wait." }
        val podMode = body["Pod"]?.asBoolean == true
        val transform = vehicle.getVehicleTransform(1f)
        val localPosition: Vec3
        val direction: Vec3
        val range: Double
        if (podMode) {
            require(lease.pod) { "Activate the targeting pod first." }
            val pod = definition(vehicle)!!.getAsJsonObject("Pod") ?: throw IllegalArgumentException("No targeting pod.")
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
            localPosition = AircraftArmamentRegistry.vector(pod["Position"])!!
            range = (pod["Range"]?.asDouble ?: 8192.0).coerceIn(1.0, 8192.0)
        } else {
            localPosition = Vec3(0.0, vehicle.bbHeight * 0.5, 1.0)
            val d = transform.transformDirection(Vector3d(0.0, 0.0, 1.0)).normalize()
            direction = Vec3(d.x, d.y, d.z)
            range = 8192.0
        }
        val o = transform.transformPosition(Vector3d(localPosition.x, localPosition.y, localPosition.z))
        val origin = Vec3(o.x, o.y, o.z)
        require(origin.x.isFinite() && origin.y.isFinite() && origin.z.isFinite() && direction.lengthSqr().isFinite())
        val level = player.serverLevel()
        val generation = ++lease.designationGeneration
        val equipmentRevision = equipment(vehicle).getLong("Revision")
        lease.designating = true
        val pending = try {
            com.atsuishio.superbwarfare.api.vehicle.aim.VehicleLaserRangefinder.measureAsync(
                level, vehicle, origin, direction, range, false)
        } catch (error: Exception) { lease.designating = false; throw error }
        pending.whenComplete { distance, error ->
            level.server.execute {
                lease.designating = false
                if (leases[player] !== lease || generation != lease.designationGeneration ||
                    lease.catalogue != AircraftArmamentRegistry.revision || !pilot(player, vehicle) ||
                    vehicle.level() !== level || player.level() !== level ||
                    (podMode && !lease.pod) || equipment(vehicle).getLong("Revision") != equipmentRevision) return@execute
                if (error != null || distance == null || !distance.isFinite() || distance !in 0.0..range) {
                    reply(player, lease, message = "No saved terrain return along this laser ray.")
                    return@execute
                }
                val point = origin.add(direction.scale(distance))
                if (!AircraftDesignationData.get(level).put(vehicle.uuid, point)) {
                    reply(player, lease, message = "Designation storage is full.")
                    return@execute
                }
                onDesignationAccepted(vehicle)
                publishPoint(vehicle)
                reply(player, lease, message = "Target designated.")
            }
        }
    }
    private fun publishPoint(vehicle: VehicleEntity) {
        val out = base(vehicle)
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
            if (point.position == null) { tag.remove("BvpLaserLastPoint"); return null }
            tag.put("BvpLaserLastPoint", CompoundTag().also { n ->
                n.putDouble("X", point.position.x); n.putDouble("Y", point.position.y); n.putDouble("Z", point.position.z)
            })
            return point.position
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
            val group = AircraftStoreWeapons.group(vehicle, weapon) ?: return@mapNotNull null
            val store = group.store
            if (!store.has("Guidance") || group.ammo <= 0) return@mapNotNull null
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
                if (status > 0 && state.hasUUID("TargetUUID")) {
                    addProperty("TargetUUID", state.getUUID("TargetUUID").toString())
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
