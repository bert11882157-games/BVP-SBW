package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.util.UUID

/**
 * Owns the temporary vehicle-HUD scale override without persisting or overwriting a player's
 * setting after they change it themselves.  The option is changed only at session edges; it is
 * never touched by a render-frame layout calculation.
 */
@OnlyIn(Dist.CLIENT)
object VehicleHudScaleController {
    private var owned = false
    private var manualOverride = false
    private var forcedScale = 2
    private var sessionPlayer: UUID? = null
    private var sessionVehicle: UUID? = null
    private var sessionLevel: Any? = null
    private var sessionEntryWasOne = false

    fun tick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        if (player == null) {
            reset()
        } else {
            sync(player)
        }
    }

    fun sync(player: Player?) {
        val minecraft = Minecraft.getInstance()
        val vehicle = player?.vehicle as? VehicleEntity
        val eligible = player != null && !player.isSpectator && !player.isDeadOrDying && vehicle != null
        if (!eligible) {
            reset()
            return
        }

        val level = player!!.level()
        val contextChanged = sessionPlayer != null &&
            (sessionPlayer != player.uuid || sessionVehicle != vehicle!!.uuid || sessionLevel !== level)
        if (contextChanged) {
            release(minecraft)
            manualOverride = false
            sessionPlayer = null
            sessionVehicle = null
            sessionLevel = null
            sessionEntryWasOne = false
        }

        if (sessionPlayer == null) {
            // Capture the entry value once.  A session that began at any non-one scale never
            // claims the option later, even if the player changes it to one while mounted.
            sessionPlayer = player.uuid
            sessionVehicle = vehicle!!.uuid
            sessionLevel = level
            sessionEntryWasOne = minecraft.options.guiScale().get() == 1
        }

        if (owned) {
            // A changed value is a player's explicit choice.  Relinquish ownership and never
            // write it back during this mounted session.
            if (minecraft.options.guiScale().get() != forcedScale) {
                owned = false
                manualOverride = true
            }
            return
        }

        if (!sessionEntryWasOne || manualOverride) return
        if (minecraft.options.guiScale().get() != 1) {
            // The player changed the value before we could claim it; respect that choice for
            // the remainder of this mounted session.
            manualOverride = true
            return
        }

        forcedScale = 2
        minecraft.options.guiScale().set(forcedScale)
        owned = true
    }

    fun reset() {
        release(Minecraft.getInstance())
        owned = false
        manualOverride = false
        sessionPlayer = null
        sessionVehicle = null
        sessionLevel = null
        sessionEntryWasOne = false
    }

    private fun release(minecraft: Minecraft) {
        if (owned && minecraft.options.guiScale().get() == forcedScale) {
            // The only value we own is the temporary 2x override, and entry was exactly 1.
            minecraft.options.guiScale().set(1)
        }
        owned = false
    }
}
