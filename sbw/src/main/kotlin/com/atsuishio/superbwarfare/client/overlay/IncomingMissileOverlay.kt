package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftCountermeasures
import com.atsuishio.superbwarfare.api.aircraft.IncomingMissileWarning
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.init.ModSounds
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.world.item.Item
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import kotlin.math.ceil

/**
 * Missile approach warning for the crew of a vehicle a missile homes on (see [IncomingMissileWarning]): a flashing
 * INCOMING, the vehicle's decoy buttons with their keys, and a flashing arrow on each one that breaks the lock.
 */
@OnlyIn(Dist.CLIENT)
object IncomingMissileOverlay : CommonOverlay("incoming_missile") {
    private const val RED = 0xFFFF3030.toInt()
    private const val DARK_RED = 0xFF8A1A1A.toInt()
    private const val WHITE = 0xFFEAEFF4.toInt()
    private const val GREY = 0xFF7D8590.toInt()
    private const val AMBER = 0xFFFFCF65.toInt()
    private const val PANEL = 0xA51B2430.toInt()
    private const val BUTTON_HEIGHT = 22
    private const val BUTTON_GAP = 10
    private const val ARROW_HEIGHT = 8

    private class Button(val label: String, val key: String, val status: String, val ready: Boolean,
                         val effective: Boolean)

    override fun RenderContext.render() {
        val vehicle = player.vehicle as? VehicleEntity ?: return
        val flags = vehicle.getIncomingMissileWarning()
        if (flags and IncomingMissileWarning.INCOMING == 0) return
        val font = mc.font
        val g = guiGraphics
        // Four flashes a second, shared by the text, the arrows and the highlighted buttons.
        val on = (System.currentTimeMillis() / 125L) % 2L == 0L

        val top = (h * 0.2f).toInt()
        val pose = g.pose()
        pose.pushPose()
        pose.translate(w / 2f, top.toFloat(), 0f)
        pose.scale(2f, 2f, 1f)
        val title = "INCOMING"
        g.drawString(font, title, -font.width(title) / 2, 0, if (on) RED else DARK_RED, true)
        pose.popPose()

        val needsFlare = flags and (IncomingMissileWarning.DECOY or IncomingMissileWarning.FLARE) != 0
        val needsChaff = flags and IncomingMissileWarning.CHAFF != 0
        val decoyKey = ModKeyMappings.RELEASE_DECOY.key.displayName.string
        val chaffKey = ModKeyMappings.RELEASE_CHAFF.key.displayName.string
        val buttons = ArrayList<Button>(2)
        val missing = ArrayList<String>(2)
        val equipment = AircraftCountermeasures.definition(vehicle)
        if (equipment != null) {
            if (equipment.flares) {
                val count = stock(vehicle, ModItems.FLARE_AMMUNITION.get())
                val cooldown = vehicle.getFlareCooldownTicks()
                buttons += Button("FLARE", decoyKey, status(count < 2, cooldown), count >= 2 && cooldown == 0, needsFlare)
            } else if (needsFlare) missing += "NO FLARES"
            if (equipment.chaff) {
                val count = stock(vehicle, ModItems.CHAFF_AMMUNITION.get())
                val cooldown = vehicle.getChaffCooldownTicks()
                buttons += Button("CHAFF", chaffKey,
                    if (vehicle.isChaffEmitting()) "ACTIVE" else status(count < 1, cooldown),
                    count >= 1 && cooldown == 0, needsChaff)
            } else if (needsChaff) missing += "NO CHAFF"
        } else {
            // Ground vehicles: the decoy key fires smoke, which diverts native seekers only.
            val needsSmoke = flags and IncomingMissileWarning.DECOY != 0
            if (vehicle.hasDecoy()) buttons += Button("SMOKE", decoyKey,
                if (vehicle.decoyReady) "READY" else "RELOAD", vehicle.decoyReady, needsSmoke)
            else if (needsSmoke) missing += "NO SMOKE"
            if (flags and IncomingMissileWarning.FLARE != 0) missing += "NO FLARES"
            if (needsChaff) missing += "NO CHAFF"
        }
        if (missing.isEmpty() && buttons.none { it.effective }) missing += "DECOYS INEFFECTIVE"

        var y = top + 22 + ARROW_HEIGHT + 4
        if (buttons.isNotEmpty()) {
            val width = buttons.maxOf { maxOf(font.width("${it.label} [${it.key}]"), font.width(it.status)) } + 12
            var x = w / 2 - (buttons.size * width + (buttons.size - 1) * BUTTON_GAP) / 2
            for (button in buttons) {
                g.fill(x, y, x + width, y + BUTTON_HEIGHT, PANEL)
                outline(g, x, y, width, BUTTON_HEIGHT, when {
                    !button.effective -> GREY
                    on -> RED
                    else -> WHITE
                })
                g.drawCenteredString(font, "${button.label} [${button.key}]", x + width / 2, y + 3,
                    if (button.effective) WHITE else GREY)
                g.drawCenteredString(font, button.status, x + width / 2, y + 12,
                    if (button.ready) WHITE else AMBER)
                if (button.effective && on) arrow(g, x + width / 2, y - ARROW_HEIGHT - 2)
                x += width + BUTTON_GAP
            }
            y += BUTTON_HEIGHT + 4
        }
        if (missing.isNotEmpty()) {
            g.drawCenteredString(font, missing.joinToString("   "), w / 2, y, RED)
        }
    }

    private fun status(empty: Boolean, cooldown: Int): String = when {
        empty -> "EMPTY"
        cooldown > 0 -> "${ceil(cooldown / 20.0).toInt()}s"
        else -> "READY"
    }

    private fun stock(vehicle: VehicleEntity, item: Item): Int =
        (0 until vehicle.inventory.slots).sumOf { slot ->
            vehicle.inventory.getStackInSlot(slot).takeIf { it.`is`(item) }?.count ?: 0
        }

    private fun outline(g: GuiGraphics, x: Int, y: Int, width: Int, height: Int, color: Int) {
        g.fill(x, y, x + width, y + 1, color)
        g.fill(x, y + height - 1, x + width, y + height, color)
        g.fill(x, y, x + 1, y + height, color)
        g.fill(x + width - 1, y, x + width, y + height, color)
    }

    /** A downward arrow whose tip sits [ARROW_HEIGHT] pixels below [top], centred on [centerX]. */
    private fun arrow(g: GuiGraphics, centerX: Int, top: Int) {
        for (row in 0 until ARROW_HEIGHT) {
            val half = ARROW_HEIGHT - 1 - row
            g.fill(centerX - half, top + row, centerX + half + 1, top + row + 1, RED)
        }
    }
}

/** The crew's missile alarm: a fast two-tone beep while [IncomingMissileWarning] flags the vehicle they ride. */
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object IncomingMissileAlarm {
    private const val BEEP_TICKS = 5
    private var ticks = 0

    @SubscribeEvent
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        val player = mc.player
        val vehicle = player?.vehicle as? VehicleEntity
        if (mc.isPaused || player == null || vehicle == null || !player.isAlive ||
            vehicle.getIncomingMissileWarning() and IncomingMissileWarning.INCOMING == 0) {
            ticks = 0
            return
        }
        if (ticks % BEEP_TICKS == 0) {
            val high = (ticks / BEEP_TICKS) % 2 == 0
            mc.soundManager.play(SimpleSoundInstance.forUI(ModSounds.MISSILE_WARNING.get(), if (high) 1.3F else 1.0F, 0.7F))
        }
        ticks++
    }
}
