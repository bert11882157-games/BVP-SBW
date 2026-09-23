package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftBombPredictor
import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager
import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreWeapons
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.worldToScreen
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderGuiEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import kotlin.math.*

/** The expensive ballistic prediction runs at 4 Hz; the projected marker follows every frame. */
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AircraftBombHud {
    private var vehicle: VehicleEntity? = null
    private var weapon: String? = null
    private var point: Vec3? = null
    private fun selectedBomb(current: VehicleEntity): String? = sequenceOf(
        current.getGunName(0),
        current.getSecondaryWeaponIndex(0)?.let { current.getGunName(0, it) }
    ).filterNotNull().firstOrNull { id ->
        AircraftStoreWeapons.mountId(id)?.let { mount ->
            AircraftArmamentManager.equippedStore(current, mount)?.get("Category")?.asString == "BOMB"
        } == true
    }
    @SubscribeEvent fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        val current = mc.player?.vehicle as? VehicleEntity
        val selected = current?.let(::selectedBomb)
        if (current !== vehicle || selected != weapon) { point = null; vehicle = current; weapon = selected }
        if (current == null || selected == null || mc.player?.let(current::getSeatIndex) != 0) { point = null; return }
        if (!mc.isPaused && current.level().gameTime % 5 == 0L)
            point = AircraftBombPredictor.predictImpact(current, selected)
    }
    @SubscribeEvent fun render(event: RenderGuiEvent.Post) {
        val mc = Minecraft.getInstance()
        val current = mc.player?.vehicle as? VehicleEntity ?: return
        if (current !== vehicle || selectedBomb(current) != weapon || mc.options.hideGui || mc.screen != null) return
        val center = point?.worldToScreen() ?: return
        val width = mc.window.guiScaledWidth; val height = mc.window.guiScaledHeight
        if (center.z <= 0 || !center.x.isFinite() || !center.y.isFinite() || center.x !in 0.0..width.toDouble() || center.y !in 0.0..height.toDouble()) return
        val g = event.guiGraphics; val buffer = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui())
        val matrix = g.pose().last().pose()
        fun vertex(x: Double, y: Double) { buffer.vertex(matrix, x.toFloat(), y.toFloat(), 0f).color(255,255,255,230).endVertex() }
        for (i in 0 until 48) {
            val a=i*Math.PI/24; val b=(i+1)*Math.PI/24
            vertex(center.x+cos(a)*8.5,center.y+sin(a)*8.5);vertex(center.x+cos(b)*8.5,center.y+sin(b)*8.5)
            vertex(center.x+cos(b)*7.7,center.y+sin(b)*7.7);vertex(center.x+cos(a)*7.7,center.y+sin(a)*7.7)
        }
    }
}
