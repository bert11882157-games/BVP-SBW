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
import kotlin.math.sin

/**
 * Missile approach warning for the crew of a vehicle a missile homes on (see [IncomingMissileWarning]): a compact
 * strip near the top of the screen with a gently pulsing MISSILE tag and the vehicle's decoy buttons with their keys.
 * The button that breaks the lock carries a steady red outline; nothing flashes.
 */
@OnlyIn(Dist.CLIENT)
object IncomingMissileOverlay : CommonOverlay("incoming_missile") {
    private const val RED = 0xFFFF4A4A.toInt()
    private const val WHITE = 0xFFEAEFF4.toInt()
    private const val GREY = 0xFF7D8590.toInt()
    private const val AMBER = 0xFFFFCF65.toInt()
    private const val PANEL = 0x8C1B2430.toInt()
    private const val PAD = 3
    private const val GAP = 4
    /** Top of the strip as a fraction of the screen height (clear of the crosshair and the flight HUD). */
    private const val TOP_FRACTION = 0.1f

    private class Button(val text: String, val status: String, val ready: Boolean, val effective: Boolean)

    override fun RenderContext.render() {
        val vehicle = player.vehicle as? VehicleEntity ?: return
        val flags = vehicle.getIncomingMissileWarning()
        if (flags and IncomingMissileWarning.INCOMING == 0) return
        val font = mc.font
        val g = guiGraphics

        val needsFlare = flags and (IncomingMissileWarning.DECOY or IncomingMissileWarning.FLARE) != 0
        val needsChaff = flags and IncomingMissileWarning.CHAFF != 0
        val decoyKey = ModKeyMappings.RELEASE_DECOY.key.displayName.string
        val chaffKey = ModKeyMappings.RELEASE_CHAFF.key.displayName.string
        val buttons = ArrayList<Button>(2)
        val missing = ArrayList<String>(2)
        val equipment = AircraftCountermeasures.definition(vehicle)
        if (equipment != null) {
            // the same supply rule as the flight HUD: a creative ammo box never runs dry
            val unlimited = AircraftCountermeasures.hasUnlimitedSupply(vehicle, player)
            if (equipment.flares) {
                val count = if (unlimited) Int.MAX_VALUE else stock(vehicle, ModItems.FLARE_AMMUNITION.get())
                val cooldown = vehicle.getFlareCooldownTicks()
                buttons += Button("FLARE [$decoyKey]", status(count < 2, cooldown), count >= 2 && cooldown == 0, needsFlare)
            } else if (needsFlare) missing += "NO FLARES"
            if (equipment.chaff) {
                val count = if (unlimited) Int.MAX_VALUE else stock(vehicle, ModItems.CHAFF_AMMUNITION.get())
                val cooldown = vehicle.getChaffCooldownTicks()
                buttons += Button("CHAFF [$chaffKey]",
                    if (vehicle.isChaffEmitting()) "ACTIVE" else status(count < 1, cooldown),
                    count >= 1 && cooldown == 0, needsChaff)
            } else if (needsChaff) missing += "NO CHAFF"
        } else {
            // Ground vehicles: the decoy key fires smoke, which diverts native seekers only.
            val needsSmoke = flags and IncomingMissileWarning.DECOY != 0
            if (vehicle.hasDecoy()) buttons += Button("SMOKE [$decoyKey]",
                if (vehicle.decoyReady) "READY" else "RELOAD", vehicle.decoyReady, needsSmoke)
            else if (needsSmoke) missing += "NO SMOKE"
            if (flags and IncomingMissileWarning.FLARE != 0) missing += "NO FLARES"
            if (needsChaff) missing += "NO CHAFF"
        }
        if (missing.isEmpty() && buttons.none { it.effective }) missing += "DECOYS INEFFECTIVE"

        // One line: [MISSILE] [FLARE [X] READY] [CHAFF [C] READY]  NO SMOKE
        val title = "MISSILE"
        val cells = ArrayList<Pair<Int, Button?>>()          // width, button (null = title)
        cells += font.width(title) + PAD * 2 to null
        for (button in buttons) cells += font.width("${button.text} ${button.status}") + PAD * 2 to button
        val note = missing.joinToString("  ")
        val total = cells.sumOf { it.first } + GAP * (cells.size - 1) +
            if (note.isEmpty()) 0 else GAP + font.width(note)
        val height = font.lineHeight + PAD * 2 - 1
        val top = (h * TOP_FRACTION).toInt()
        var x = w / 2 - total / 2
        // A slow, soft pulse (about once a second) on the tag only.
        val pulse = 0.5f + 0.5f * sin(System.currentTimeMillis() / 1000.0 * Math.PI * 2.0).toFloat()
        for ((width, button) in cells) {
            g.fill(x, top, x + width, top + height, PANEL)
            if (button == null) {
                outline(g, x, top, width, height, blend(RED, 0xFF8A2A2A.toInt(), 1f - pulse))
                g.drawString(font, title, x + PAD, top + PAD, RED, false)
            } else {
                outline(g, x, top, width, height, if (button.effective) RED else GREY)
                g.drawString(font, button.text, x + PAD, top + PAD, if (button.effective) WHITE else GREY, false)
                g.drawString(font, button.status, x + PAD + font.width(button.text + " "), top + PAD,
                    if (button.ready) WHITE else AMBER, false)
            }
            x += width + GAP
        }
        if (note.isNotEmpty()) g.drawString(font, note, x, top + PAD, RED, true)
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

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val ca = (a ushr shift) and 0xFF
            val cb = (b ushr shift) and 0xFF
            return (ca + (cb - ca) * t).toInt().coerceIn(0, 255) shl shift
        }
        return channel(24) or channel(16) or channel(8) or channel(0)
    }
}

/** The crew's missile alarm: a soft beep about once a second while [IncomingMissileWarning] flags their vehicle. */
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object IncomingMissileAlarm {
    private const val BEEP_TICKS = 20
    private const val VOLUME = 0.35F
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
            mc.soundManager.play(SimpleSoundInstance.forUI(ModSounds.MISSILE_WARNING.get(), 1.15F, VOLUME))
        }
        ticks++
    }
}
