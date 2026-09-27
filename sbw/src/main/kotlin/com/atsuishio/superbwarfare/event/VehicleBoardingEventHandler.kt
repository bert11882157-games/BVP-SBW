package com.atsuishio.superbwarfare.event

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/** Hidden crew still have vanilla interaction boxes. Route those clicks to their vehicle. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object VehicleBoardingEventHandler {
    @SubscribeEvent
    fun interact(event: PlayerInteractEvent.EntityInteract) = forward(event, event.target)

    @SubscribeEvent
    fun interactAt(event: PlayerInteractEvent.EntityInteractSpecific) = forward(event, event.target)

    private fun forward(event: PlayerInteractEvent, target: net.minecraft.world.entity.Entity) {
        val player = event.entity
        if (player.isPassenger || player.isShiftKeyDown) return
        val vehicle = target.vehicle as? VehicleEntity ?: return
        if (!vehicle.hidePassenger(target)) return
        // Reuse capacity, wreck, team and item policy; never force-mount or replace an occupant.
        event.cancellationResult = vehicle.interact(player, event.hand)
        event.isCanceled = true
    }
}
