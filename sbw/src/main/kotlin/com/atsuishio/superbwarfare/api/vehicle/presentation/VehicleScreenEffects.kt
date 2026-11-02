package com.atsuishio.superbwarfare.api.vehicle.presentation

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.Entity
import net.minecraftforge.registries.ForgeRegistries
import java.util.concurrent.ConcurrentHashMap

/**
 * Anyone riding a vehicle, in any seat, gets no screen effects: no camera shake from explosions, hits, landings or
 * shock, no hurt tilt, and no screen-distorting status effects (nausea, blindness, darkness and the other mods' ids
 * registered here). The one exception is the shake from the vehicle's own guns firing.
 */
object VehicleScreenEffects {
    private val SCREEN_EFFECT_IDS: MutableSet<ResourceLocation> = ConcurrentHashMap.newKeySet<ResourceLocation>().apply {
        add(ResourceLocation("minecraft", "nausea"))
        add(ResourceLocation("minecraft", "blindness"))
        add(ResourceLocation("minecraft", "darkness"))
    }

    /** Pure rule: a rider is shielded from every shake except one sent by the vehicle they ride. */
    @JvmStatic
    fun blocks(ridingVehicle: Boolean, sentByOwnVehicle: Boolean): Boolean = ridingVehicle && !sentByOwnVehicle

    @JvmStatic
    fun shields(entity: Entity?): Boolean =
        entity != null && (entity.vehicle is VehicleEntity || entity.rootVehicle is VehicleEntity)

    /** True when [player] still gets a shake that [source] sends: not riding, or it is their vehicle's own gun. */
    @JvmStatic
    fun receives(player: Entity, source: Entity?): Boolean {
        val own = source != null && (player.vehicle === source || player.rootVehicle === source.rootVehicle)
        return !blocks(shields(player), own)
    }

    /** Another mod's status effect that blurs, wobbles or darkens the screen; unknown ids are simply never matched. */
    @JvmStatic
    fun registerScreenEffect(id: ResourceLocation) {
        SCREEN_EFFECT_IDS.add(id)
    }

    @JvmStatic
    fun isScreenEffect(effect: MobEffect?): Boolean {
        val id = effect?.let(ForgeRegistries.MOB_EFFECTS::getKey) ?: return false
        return id in SCREEN_EFFECT_IDS
    }
}
