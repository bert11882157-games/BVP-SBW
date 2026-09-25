package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.data.gun.FireMode
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity

object VehicleWeaponScheduleProviders {
    private const val LEGACY_DEFAULT_RPM = 240
    private const val ATGM_DEFAULT_RPM = 40
    private const val AUTOMATIC_RELEASE_GRACE_TICKS = 4
    private val nativeProviderId = Mod.loc("native_weapon_cadence")
    private val nativeProvider = object : VehicleWeaponScheduleProvider {
        override fun resolve(selection: VehicleWeaponSelection): VehicleWeaponScheduleProfile {
            val atgm = isAtgm(selection)
            val eventRpm = selection.gunData.get(GunProp.RPM).takeIf { it > 0 }
                ?: when {
                    atgm -> ATGM_DEFAULT_RPM
                    else -> LEGACY_DEFAULT_RPM
                }
            val projectiles = selection.gunData.get(GunProp.PROJECTILE_AMOUNT).coerceAtLeast(1)
            val repeatWhileHeld = selection.gunData.selectedFireModeInfo().mode != FireMode.SEMI
            return VehicleWeaponScheduleProfile(
                id = nativeProviderId,
                bulletRpm = bulletRpm(eventRpm, projectiles),
                eventRpm = eventRpm,
                projectilesPerEvent = projectiles,
                soundIntervalProjectiles = projectiles,
                repeatWhileHeld = repeatWhileHeld,
                preserveAcceptedCadenceAcrossPresses = atgm,
                releaseGraceTicks = if (repeatWhileHeld) AUTOMATIC_RELEASE_GRACE_TICKS else 0,
            )
        }
    }
    private val providers = OrderedProviderRegistry<ResourceLocation, VehicleWeaponScheduleProvider>()

    @JvmStatic
    fun register(id: ResourceLocation, provider: VehicleWeaponScheduleProvider) = providers.register(id, provider)

    @JvmStatic
    fun unregister(id: ResourceLocation): Boolean = providers.unregister(id)

    internal fun resolve(vehicle: VehicleEntity, controller: LivingEntity): ResolvedVehicleWeaponSchedule? {
        return resolve(vehicle, controller, vehicle.getSelectedWeapon(vehicle.getSeatIndex(controller)))
    }

    /** Resolves an exact weapon index for paired secondary scheduling without changing selection. */
    internal fun resolve(
        vehicle: VehicleEntity,
        controller: LivingEntity,
        weaponIndex: Int,
    ): ResolvedVehicleWeaponSchedule? {
        val seatIndex = vehicle.getSeatIndex(controller)
        if (seatIndex < 0) return null
        if (weaponIndex < 0) return null
        val weaponName = vehicle.getGunName(seatIndex, weaponIndex) ?: return null
        val gunData = vehicle.getGunData(seatIndex, weaponIndex) ?: return null
        val selection = VehicleWeaponSelection(
            vehicle,
            controller,
            seatIndex,
            weaponIndex,
            weaponName,
            gunData,
        )

        for ((providerId, provider) in providers.snapshot()) {
            try {
                val profile = provider.resolve(selection) ?: continue
                return ResolvedVehicleWeaponSchedule(
                    providerId,
                    provider,
                    selection,
                    applyCadencePolicy(selection, profile),
                )
            } catch (exception: RuntimeException) {
                Mod.LOGGER.warn(
                    "Vehicle weapon schedule provider {} failed for entity {} seat {} weapon {}",
                    providerId,
                    vehicle.id,
                    seatIndex,
                    weaponIndex,
                    exception,
                )
            }
        }
        return ResolvedVehicleWeaponSchedule(
            nativeProviderId,
            nativeProvider,
            selection,
            applyCadencePolicy(selection, nativeProvider.resolve(selection)),
        )
    }

    @JvmStatic
    fun hasSchedule(vehicle: VehicleEntity, controller: LivingEntity): Boolean {
        return resolve(vehicle, controller) != null
    }

    private fun bulletRpm(eventRpm: Int, projectiles: Int): Int {
        return (eventRpm.toLong() * projectiles).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun isAtgm(selection: VehicleWeaponSelection): Boolean {
        // Belt-backed launchers must classify the effective selected round, not only the root
        // fallback projectile. Guidance and cadence now share the same fail-closed resolver.
        return VehicleWeaponGuidance.isAtgm(selection.gunData)
    }

    private fun applyCadencePolicy(
        selection: VehicleWeaponSelection,
        profile: VehicleWeaponScheduleProfile,
    ): VehicleWeaponScheduleProfile {
        val belt = selection.gunData.get(GunProp.PROJECTILE_BELT)
        val projectile = selection.gunData.get(GunProp.PROJECTILE)
        val profileId = projectile.profile
            ?: CustomData.LAUNCHABLE_ENTITY[projectile.itemId.trim()]?.profile
        val cadence = VehicleWeaponCadencePolicies.apply(VehicleWeaponCadenceInput(
            belt?.family, ProjectileProfiles.resolve(profileId)?.combat?.roundId), profile)
        // High-rate single mounts need the same bounded event budget as fixed gun banks.
        val resolved = cadence.copy(
            maxCatchUpEvents = maxOf(cadence.maxCatchUpEvents,
                VehicleFixedGunBanks.eventCapacity(cadence.eventRpm) ?: 2),
            heatPolicy = cadence.heatPolicy.takeIf { selection.gunData.get(GunProp.OVERHEAT_ENABLED) },
        )
        if (!isAtgm(selection)) return consolidate(selection, resolved, projectile.itemId)
        val rate = minOf(60, resolved.eventRpm)
        return resolved.copy(eventRpm = rate, bulletRpm = bulletRpm(rate, resolved.projectilesPerEvent),
            preserveAcceptedCadenceAcrossPresses = true, maxCatchUpEvents = 1)
    }

    /** Aircraft guns above [AircraftRoundConsolidation.MIN_RPM] fire half the rounds, each counting twice. */
    private fun consolidate(
        selection: VehicleWeaponSelection,
        profile: VehicleWeaponScheduleProfile,
        projectileItemId: String,
    ): VehicleWeaponScheduleProfile {
        val weight = AircraftRoundConsolidation.scheduleWeight(
            AircraftRoundConsolidation.isAircraft(selection.vehicle),
            profile.eventRpm,
            profile.repeatWhileHeld && selection.gunData.selectedFireModeInfo().mode != FireMode.BURST,
            AircraftRoundConsolidation.isGunRound(projectileItemId),
        )
        return profile.consolidated(weight)
    }
}

internal data class ResolvedVehicleWeaponSchedule(
    val providerId: ResourceLocation,
    val provider: VehicleWeaponScheduleProvider,
    val selection: VehicleWeaponSelection,
    val profile: VehicleWeaponScheduleProfile,
)
