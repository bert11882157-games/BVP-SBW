package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.misc.MonitorItem
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraftforge.client.settings.IKeyConflictContext
import net.minecraftforge.client.settings.KeyConflictContext

/** Input context for a mounted SBW vehicle or an actively linked remote vehicle monitor. */
object VehicleKeyConflictContext : IKeyConflictContext {
    override fun isActive(): Boolean {
        val minecraft = Minecraft.getInstance()
        if (minecraft.screen != null) return false
        val player = minecraft.player ?: return false
        return player.vehicle is VehicleEntity || controlsLinkedMonitor(player)
    }

    override fun conflicts(other: IKeyConflictContext): Boolean = other === this

    private fun controlsLinkedMonitor(player: Player): Boolean {
        val stack = player.mainHandItem
        val tag = stack.tag ?: return false
        return stack.`is`(ModItems.MONITOR.get())
                && tag.getBoolean(MonitorItem.USING)
                && tag.getBoolean(MonitorItem.LINKED)
    }
}

/** Keeps paired ordinary SBW actions inactive while their vehicle profile owns input. */
object NonVehicleKeyConflictContext : IKeyConflictContext {
    override fun isActive(): Boolean =
        KeyConflictContext.IN_GAME.isActive() && !VehicleKeyConflictContext.isActive()

    override fun conflicts(other: IKeyConflictContext): Boolean =
        other === this || other === KeyConflictContext.IN_GAME
}
