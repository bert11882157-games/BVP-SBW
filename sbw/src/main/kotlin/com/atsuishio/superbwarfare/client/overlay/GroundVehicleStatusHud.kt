package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.api.vehicle.presentation.*
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.util.Mth
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlin.math.floor
import kotlin.math.ceil

object GroundVehicleStatusHud {
    private var lastDiagnosticVehicle = -1
    private var lastDiagnosticTick = Long.MIN_VALUE
    @JvmStatic fun auxiliaryY(height: Int, row: Int): Int = height - 58 + row * 12
    @JvmStatic fun textWidth(width: Int): Int = minOf(104,width-24)

    fun render(g: GuiGraphics, v: VehicleEntity, partial: Float, width: Int, height: Int) {
        val font=Minecraft.getInstance().font
        val size=(height*0.18F).toInt().coerceIn(52,84)
        val cx=12+size/2;val cy=height-68-size/2;val radius=size/2
        // A translucent circular orientation field, rather than an aggregate-health icon.
        for(y in -radius..radius) {
            val dx=sqrt((radius*radius-y*y).toDouble()).toInt()
            g.fill(cx-dx,cy+y,cx+dx+1,cy+y+1,0x70202B39)
        }
        val pose=g.pose()
        pose.pushPose()
        pose.translate(cx.toDouble(),cy.toDouble(),0.0)
        val scale=size/40F
        pose.scale(scale,scale,1F)
        pose.pushPose()
        pose.mulPose(Axis.ZP.rotationDegrees(Mth.rotLerp(partial,v.turretYRotO,v.turretYRot)))
        val hull = GroundVehicleHullHud.draw(g,v)
        val labels = ArrayList<Triple<String, Double, Double>>()
        if (hull != null) {
            val markers = (v as? VehicleModuleHudLayoutProvider)?.vehicleModuleHudLayout(partial)
                ?: NativeVehicleModuleHudLayout.sample(v, partial)
            val tick = v.level().gameTime
            val capture = EliteDiagnostics.isClientEnabled() &&
                (lastDiagnosticVehicle != v.id || tick - lastDiagnosticTick >= 20 || tick < lastDiagnosticTick)
            if (capture) {
                lastDiagnosticVehicle = v.id
                lastDiagnosticTick = tick
                EliteDiagnostics.record(v, "ground_hud", "module_layout", "count", markers.size)
            }
            // True-size outlines first, largest underneath; glyphs only where no outline is known.
            val outlined = markers.filter { VehicleModuleFootprints.hasFootprint(it) }
                .sortedByDescending { VehicleModuleFootprints.area(it, hull) }
            for (marker in outlined) {
                val state = VehicleModuleHudDamage.from(marker.health)
                VehicleModuleFootprints.draw(g, marker, hull, state.fill, state.outline,
                    state == VehicleModuleHudDamage.DESTROYED)
                val (lx, ly) = VehicleModuleFootprints.centre(marker, hull)
                labels.add(Triple(VehicleModuleFootprints.label(marker), lx, ly))
                if (capture) EliteDiagnostics.record(v, "ground_hud", "module_outline",
                    "module", marker.id, "kind", marker.kind, "polygons", marker.footprints.size,
                    "label_x", lx, "label_y", ly, "color", state)
            }
            for (marker in markers) {
                if (VehicleModuleFootprints.hasFootprint(marker)) continue
                if (!marker.modelX.isFinite() || !marker.modelZ.isFinite()) continue
                val shape = VehicleSystemGlyphs.module(marker.kind)
                val x = hull.hudX(marker.modelX).roundToInt() - shape[0].length / 2
                val track = marker.kind == VehicleModuleHudKind.TRACK
                if (track && (!marker.modelMinZ.isFinite() || !marker.modelMaxZ.isFinite()
                    || marker.modelMaxZ <= marker.modelMinZ)) continue
                val y = if (track) floor(hull.hudY(marker.modelMinZ)).toInt()
                    else hull.hudY(marker.modelZ).roundToInt() - shape.size / 2
                val bottom = if (track) ceil(hull.hudY(marker.modelMaxZ)).toInt() else y + shape.size
                val state = VehicleModuleHudDamage.from(marker.health)
                if (track) VehicleSystemGlyphs.drawTrack(g, x, y, bottom, state.fill, state.outline)
                else VehicleSystemGlyphs.drawModule(g, shape, x, y, state.fill, state.outline)
                if (capture) EliteDiagnostics.record(v, "ground_hud", "module_marker",
                    "module", marker.id, "kind", marker.kind, "model_x", marker.modelX,
                    "model_z", marker.modelZ, "hud_x", x, "hud_y", y,
                    "model_min_z", marker.modelMinZ, "model_max_z", marker.modelMaxZ, "hud_bottom", bottom,
                    "health", marker.health.health, "maximum", marker.health.maximum,
                    "destroyed", marker.health.destroyed, "color", state)
            }
        }
        pose.popPose()
        // Labels stay upright: place each at its module's rotated centre, skipping any that would overlap.
        if (labels.isNotEmpty()) {
            val turn = Math.toRadians(Mth.rotLerp(partial,v.turretYRotO,v.turretYRot).toDouble())
            val c = kotlin.math.cos(turn); val s = kotlin.math.sin(turn)
            val textScale = 0.42F
            val placed = ArrayList<FloatArray>()
            for ((text, x, y) in labels) {
                val rx = (x * c - y * s).toFloat(); val ry = (x * s + y * c).toFloat()
                val w = font.width(text) * textScale; val h = 8 * textScale
                val box = floatArrayOf(rx - w / 2 - 0.5F, ry - h / 2 - 0.5F, rx + w / 2 + 0.5F, ry + h / 2 + 0.5F)
                if (placed.any { it[0] < box[2] && box[0] < it[2] && it[1] < box[3] && box[1] < it[3] }) continue
                placed.add(box)
                pose.pushPose()
                pose.translate(rx.toDouble(), ry.toDouble(), 0.0)
                pose.scale(textScale, textScale, 1F)
                g.drawString(font, text, -font.width(text) / 2, -4, 0xFFF2F2F2.toInt(), true)
                pose.popPose()
            }
        }
        // Neutral aim tick is orientation, not a fabricated weapon-health module.
        if (v.hasTurret()) g.fill(0,-20,1,-16,0xFFCAD2DB.toInt())
        pose.popPose()
        val speed=(Mth.lerp(partial.toDouble(),v.absoluteSpeedO,v.absoluteSpeed)*72.0).coerceAtLeast(0.0)
        g.drawString(font,"SPD  ${speed.toInt()} km/h",12,auxiliaryY(height,0),0xFFF2F2F2.toInt(),true)
    }
}
