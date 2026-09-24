package com.atsuishio.superbwarfare.client.weapon

import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager
import com.atsuishio.superbwarfare.client.input.VehicleWeaponSelectionInput
import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.data.gun.AmmoConsumer
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.vehicle.WeaponSystemMetadata
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModKeyMappings
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.registries.ForgeRegistries

data class VehicleWeaponHudSystem(
    val slotIndex: Int,
    val weaponId: String,
    val displayName: Component,
    val kind: VehicleWeaponHudKind,
    val loadedAmmo: Int?,
    val reserveAmmo: Int?,
    val infiniteAmmo: Boolean,
    val primary: Boolean,
    val secondary: Boolean,
    val supportsAmmoCycle: Boolean,
    val reloadRemainingTicks: Int = 0,
    val categoryLabel: String? = null,
    val displayNumber: Int = slotIndex + 1,
    val guidance: String = "",
)

/** A read-only view of the occupied seat's systems, never a row for each ammunition choice. */
@OnlyIn(Dist.CLIENT)
data class VehicleWeaponHudSnapshot(
    val seatIndex: Int,
    val systems: List<VehicleWeaponHudSystem>,
    val secondaryHoldProgress: Float?,
    /** Translated, remap-aware binding including any modifier; renderer supplies brackets. */
    val ammoCycleKeyLabel: Component,
) {
    companion object {
        @JvmStatic
        fun capture(vehicle: VehicleEntity, player: Player): VehicleWeaponHudSnapshot? {
            val seat = vehicle.getSeatIndex(player)
            if (!vehicle.level().isClientSide || player.vehicle !== vehicle ||
                player.level() !== vehicle.level() || !player.isAlive || player.isSpectator ||
                player.isRemoved || vehicle.isRemoved || vehicle.isWreck || seat < 0 ||
                vehicle.getNthEntity(seat) !== player) return null
            val names = vehicle.getWeaponIds(seat)
            val groupedAircraft = AircraftArmamentManager.definition(vehicle) != null
            val indices = VehicleWeaponHudMetadata.equippedIndices(names) { name ->
                vehicle.getGunData(name) != null && AircraftArmamentManager.selectableWeapon(vehicle, name)
            }
            val primary = vehicle.getPrimaryWeaponIndex(seat)
            val secondary = vehicle.getSecondaryWeaponIndex(seat)
            val station = vehicle.computed().passengerWeaponStationBinding
            val vehicleTypeId = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type)?.toString()
            val rows = indices.mapIndexedNotNull { ordinal, index ->
                val name = names[index]
                val gun = vehicle.getGunData(name) ?: return@mapIndexedNotNull null
                val displayNumber = if (groupedAircraft) ordinal + 1 else index + 1
                val store = AircraftArmamentManager.weaponPresentation(vehicle, name)
                val reloadTicks = AircraftArmamentManager.groupMembers(vehicle, name)?.let { members ->
                    VehicleWeaponHudMetadata.groupReloadTicks(members.mapNotNull { member ->
                        vehicle.getGunData(member)?.let { it.ammo.get() to it.reload.time() }
                    })
                } ?: gun.reload.time().coerceAtLeast(0)
                if (store != null) return@mapIndexedNotNull VehicleWeaponHudSystem(
                    index, name, Component.literal(store.name), VehicleWeaponHudKind.UNKNOWN,
                    store.ammo, null, false, index == primary, index == secondary, false,
                    reloadTicks, store.category, displayNumber, store.guidance)
                val base = gun.getDefault()
                // Use the base system descriptor, so selecting an ATGM round in a tank cannon
                // cannot turn that cannon row into a separate missile launcher or change its name.
                val nominal = base.nominalBallistics
                val combat = nominal?.projectileProfile?.let { CustomData.PROJECTILE_PROFILE[it]?.combat }
                val authoredKind = WeaponSystemMetadata.resolve(vehicleTypeId, name,
                    combat?.weaponId?.toString()) { CustomData.WEAPON_SYSTEM_METADATA[it] }
                val stationKind = station?.takeIf { it.containsWeapon(name) }?.weaponKind
                val ammo = VehicleWeaponHudAmmo.from(
                    gun.get(GunProp.MAGAZINE), gun.ammo.get(), gun.backupAmmoCount.get(),
                    gun.selectedAmmoConsumer().type == AmmoConsumer.AmmoConsumeType.INFINITE,
                )
                VehicleWeaponHudSystem(
                    index, name,
                    base.name?.takeIf { it.isNotBlank() }?.let { Component.translatable(it, "") }
                        ?: Component.literal(name),
                    VehicleWeaponHudMetadata.kind(stationKind, combat?.hullDamageClass,
                        nominal?.projectileType, authoredKind, combat?.caliberMm),
                    ammo.loaded, ammo.reserve, ammo.infinite, index == primary, index == secondary,
                    VehicleWeaponHudMetadata.supportsAmmoCycle(gun.get(GunProp.AMMO_CONSUMER).size),
                    reloadTicks, displayNumber = displayNumber,
                )
            }
            return VehicleWeaponHudSnapshot(seat, rows,
                VehicleWeaponSelectionInput.holdProgress(vehicle, player),
                ModKeyMappings.VEHICLE_CYCLE_AMMO.translatedKeyMessage.copy())
        }
    }
}
