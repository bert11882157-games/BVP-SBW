package com.atsuishio.superbwarfare.client.overlay.weapon

import com.atsuishio.superbwarfare.api.projectile.ProjectileHullDamageClass
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.client.overlay.GroundHudLayout
import com.atsuishio.superbwarfare.client.weapon.VehicleWeaponHudSnapshot
import com.atsuishio.superbwarfare.client.weapon.VehicleSelectedRoundSnapshot
import com.atsuishio.superbwarfare.client.weapon.VehicleSelectedRoundMetadata
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player

/** Read-only selected ammunition classification; never resolves or advances a firing belt. */
object VehicleShellTypeHud {
    fun render(graphics: GuiGraphics, player: Player, vehicle: VehicleEntity,
               width: Int, height: Int, amberLineY: Int) {
        val seat = vehicle.getSeatIndex(player)
        if (seat < 0 || !vehicle.banHand(player)) return
        val weapon = vehicle.getPrimaryWeaponIndex(seat)
        val data = vehicle.getGunData(seat, weapon) ?: return
        val selected = VehicleSelectedRoundSnapshot.capture(data) ?: return
        val type = typeKey(selected.type)?.let { Component.translatable(it).string }
        val designation = VehicleSelectedRoundMetadata.separateDesignation(selected.designation?.string, type)
        val text = listOfNotNull(type, designation).joinToString(" | ").takeIf { it.isNotBlank() } ?: return
        val font = Minecraft.getInstance().font
        val count = VehicleWeaponHudSnapshot.capture(vehicle,player)?.systems?.size ?: 0
        val layout = GroundHudLayout(width,height,count)
        val y = labelTop(amberLineY,0,layout.firstRowTop-23,height,font.lineHeight) ?: return
        val available = layout.shellWidth
        val first = font.plainSubstrByWidth(text, available)
        val rest = text.substring(first.length).trim()
        val lines = if(rest.isBlank()) listOf(first) else listOf(first,
            if(font.width(rest)<=available) rest else font.plainSubstrByWidth(rest,available-font.width("…"))+"…")
        val textWidth = lines.maxOf { font.width(it) }
        val right = layout.panelRight
        val left = right - textWidth
        // Fixed GUI font size and contrasting backing keep type/designation readable in bright terrain.
        graphics.fill(left,amberLineY,right,amberLineY+1,0xFFFFAD40.toInt())
        graphics.fill(left-2,y-2,right+2,y+lines.size*font.lineHeight+1,0x90202A36.toInt())
        for((row,line) in lines.withIndex())
            graphics.drawString(font,line,left+(textWidth-font.width(line))/2,
                y+row*font.lineHeight,0xFFF1F4F7.toInt(),true)
    }

    internal fun selectedTypeKey(data: GunData): String? {
        val consumers = data.get(GunProp.AMMO_CONSUMER)
        if (data.selectedAmmoType.get() !in consumers.indices) return null
        // Projectile/ShellType are server-only; the nominal identity follows client ammo overrides.
        val profileName = data.get(GunProp.NOMINAL_BALLISTICS)?.projectileProfile ?: return null
        val profileId = ResourceLocation.tryParse(profileName) ?: return null
        val profile = ProjectileProfiles.resolve(profileId) ?: return null
        return typeKey(profile.combat?.hullDamageClass)
    }

    internal fun typeKey(type: ProjectileHullDamageClass?): String? = when (type) {
        ProjectileHullDamageClass.ATGM -> "hud.superbwarfare.shell_type.atgm"
        ProjectileHullDamageClass.HEAT -> "hud.superbwarfare.shell_type.heat"
        ProjectileHullDamageClass.HEAT_FS -> "hud.superbwarfare.shell_type.heat_fs"
        ProjectileHullDamageClass.HE -> "hud.superbwarfare.shell_type.he"
        ProjectileHullDamageClass.APHE -> "hud.superbwarfare.shell_type.aphe"
        ProjectileHullDamageClass.APCR -> "hud.superbwarfare.shell_type.apcr"
        ProjectileHullDamageClass.APDS -> "hud.superbwarfare.shell_type.apds"
        ProjectileHullDamageClass.APFSDS -> "hud.superbwarfare.shell_type.apfsds"
        ProjectileHullDamageClass.DEFAULT, null -> null
    }

    /** Fixed font size below the existing separator, above the hotbar, or no label if it cannot fit. */
    internal fun labelTop(lineY: Int, zoneTop: Int, zoneBottom: Int,
                          screenHeight: Int, fontHeight: Int): Int? {
        val top = lineY + 4
        return top.takeIf { fontHeight > 0 && it >= zoneTop &&
            it + fontHeight <= minOf(zoneBottom, screenHeight - 24) }
    }
}
