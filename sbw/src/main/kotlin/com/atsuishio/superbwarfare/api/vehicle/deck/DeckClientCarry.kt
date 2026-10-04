package com.atsuishio.superbwarfare.api.vehicle.deck

import com.atsuishio.superbwarfare.Mod
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent

/**
 * Client half of [DeckCarry]: the local player moves itself, so its own client carries it with the deck.
 * Stepping off a moving deck keeps the deck's velocity; stepping on sheds it, so a run along the deck is relative
 * to the deck either way.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(
    modid = Mod.MODID, bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object DeckClientCarry {
    private var ridingOwner = -1
    private var ridingTick = Int.MIN_VALUE
    private var lastDeckVelocity = Vec3.ZERO

    @SubscribeEvent
    fun setup(event: FMLClientSetupEvent) {
        DeckCarry.localPlayerHook = DeckCarry.LocalCarry { owner, from, to, surface -> carry(owner, from, to, surface) }
    }

    private fun carry(owner: Entity, from: DeckPose, to: DeckPose, surface: DeckSurface) {
        val player = Minecraft.getInstance().player ?: return
        if (player.level() !== owner.level() || player.vehicle != null || player.isSpectator) return
        val tick = player.tickCount
        val wasRiding = ridingOwner == owner.id && tick - ridingTick <= 1
        if (DeckCarry.isOnDeck(player, from, surface)) {
            val moved = DeckCarry.move(player, from, to)
            if (!wasRiding) player.deltaMovement = player.deltaMovement.subtract(moved.x, 0.0, moved.z)
            ridingOwner = owner.id
            ridingTick = tick
            lastDeckVelocity = moved
        } else if (wasRiding) {
            player.deltaMovement = player.deltaMovement.add(lastDeckVelocity.x, 0.0, lastDeckVelocity.z)
            ridingOwner = -1
        }
    }
}
