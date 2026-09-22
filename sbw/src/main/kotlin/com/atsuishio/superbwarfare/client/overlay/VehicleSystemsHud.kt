package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.client.weapon.*
import com.atsuishio.superbwarfare.client.input.VehicleWeaponSelectionGesture
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.entity.player.Player
import kotlin.math.ceil

object VehicleSystemsHud {
    private const val PRIMARY=0xFF8FE3FF.toInt()
    private const val SECONDARY=0xFFFFCF65.toInt()
    private const val WHITE=0xFFEAEFF4.toInt()

    private fun ammo(s:VehicleWeaponHudSystem):String = when {
        s.loadedAmmo!=null -> "${s.loadedAmmo}" + (if(s.infiniteAmmo) " / ∞" else s.reserveAmmo?.let { " / $it" } ?: "")
        s.infiniteAmmo -> "∞"
        else -> s.reserveAmmo?.toString() ?: "—"
    }

    fun ground(g:GuiGraphics,v:VehicleEntity,p:Player,w:Int,h:Int,aircraft:Boolean=false) {
        val snapshot=VehicleWeaponHudSnapshot.capture(v,p) ?: return
        if(snapshot.systems.isEmpty()) return
        val font=Minecraft.getInstance().font
        val layout=GroundHudLayout(w,h,snapshot.systems.size)
        if (!aircraft) com.atsuishio.superbwarfare.client.overlay.weapon.VehicleShellTypeHud.render(
            g,p,v,w,h,layout.amberLineY)
        for((i,system) in snapshot.systems.withIndex()) {
            val x=layout.slotLeft(i)
            val y=layout.slotTop(i)
            val color=when {system.primary->PRIMARY;system.secondary->SECONDARY;else->WHITE}
            g.fill(x,y,x+layout.slotWidth,y+layout.slotHeight,0xA51B2430.toInt())
            if(system.primary || system.secondary) {
                g.fill(x,y,x+40,y+1,color);g.fill(x,y+layout.slotHeight-1,x+40,y+layout.slotHeight,color)
                g.fill(x,y,x+1,y+layout.slotHeight,color);g.fill(x+39,y,x+40,y+layout.slotHeight,color)
            }
            val assignment=when {system.primary && system.secondary->"1 / 2";system.primary->"1";system.secondary->"2";else->""}
            g.drawCenteredString(font,assignment,x+20,y-11,color)
            val progress=snapshot.secondaryHoldProgress
            if(VehicleWeaponSelectionGesture.feedbackProgress(progress)!=null && progress!=null &&
                (system.secondary || (snapshot.systems.none {it.secondary} && system.primary))) {
                val remaining=ceil((1F-progress)*VehicleWeaponSelectionGesture.HOLD_MILLIS).toInt().coerceAtLeast(0)
                val text=if(remaining==0) "SELECTED" else "HOLD ${remaining}ms"
                val textX=(x+20-font.width(text)/2).coerceIn(124,(w-12-font.width(text)).coerceAtLeast(124))
                g.drawString(font,text,textX,y-23,SECONDARY,true)
            }
            val label=system.categoryLabel ?: when(system.kind) {
                VehicleWeaponHudKind.TANK_CANNON,VehicleWeaponHudKind.AUTOCANNON->"CNN"
                VehicleWeaponHudKind.HMG->"HMG"
                VehicleWeaponHudKind.LMG->"LMG"
                VehicleWeaponHudKind.ATGM->"MSL"
                VehicleWeaponHudKind.GRENADE_LAUNCHER->"GRN"
                else->"WPN"
            }
            g.drawCenteredString(font,"${system.slotIndex+1} $label",x+20,y+4,color)
            val countText=ammo(system)
            val shown=font.plainSubstrByWidth(countText,34)
            g.drawString(font,shown,x+37-font.width(shown),y+16,WHITE,true)
            if(system.reloadRemainingTicks>0) {
                val reload=String.format(java.util.Locale.ROOT,"%.1fs",system.reloadRemainingTicks/20.0)
                g.drawCenteredString(font,reload,x+20,y+27,SECONDARY)
            }
            if(system.primary && system.supportsAmmoCycle) {
                val key="[${snapshot.ammoCycleKeyLabel.string}]"
                val shownKey=font.plainSubstrByWidth(key,40)
                g.drawCenteredString(font,shownKey,x+20,y+layout.slotHeight+3,PRIMARY)
            }
        }
    }

    fun aircraft(g:GuiGraphics,v:VehicleEntity,p:Player,w:Int,throttle:Double?,speed:Double?) {
        val font=Minecraft.getInstance().font
        val width=minOf(260,(w*0.45).toInt());val x=w-width-10
        fun line(label:String,value:String,detail:String,y:Int,color:Int=WHITE) {
            g.drawString(font,label,x,y,color,true)
            g.drawString(font,value,x+34,y,color,true)
            g.drawString(font,font.plainSubstrByWidth(detail,(width-92).coerceAtLeast(1)),x+92,y,color,true)
        }
        line("THR",throttle?.takeIf {it.isFinite()}?.let {"${(it*100).toInt()} %"} ?: "—","",12)
        line("SPD",speed?.takeIf {it.isFinite()}?.let {
            com.atsuishio.superbwarfare.tools.FormatTool.format0D(it)
        } ?: "—","km/h",24)
        val altitude=v.getResolvedChassisWorldY(Minecraft.getInstance().frameTime)
        line("ALT",altitude.takeIf { it.isFinite() }?.let {
            com.atsuishio.superbwarfare.tools.FormatTool.format0D(it)
        } ?: "—","m",36)
        val equipment=com.atsuishio.superbwarfare.api.aircraft.AircraftCountermeasures.definition(v)
        if(equipment!=null) {
            var y=54
            fun status(cooldown:Int)=if(cooldown>0) "CD ${ceil(cooldown/20.0).toInt()}s" else "READY"
            if(equipment.flares) {
                val key=com.atsuishio.superbwarfare.init.ModKeyMappings.RELEASE_DECOY.key.displayName.string
                line("FLR",v.getFlareLevel().toString(),"[$key] ${status(v.getFlareCooldownTicks())}",y)
                y+=12
            }
            if(equipment.chaff) {
                val key=com.atsuishio.superbwarfare.init.ModKeyMappings.RELEASE_CHAFF.key.displayName.string
                line("CHF",v.getChaffLevel().toString(),
                    "[$key] ${if(v.isChaffEmitting()) "ACTIVE" else status(v.getChaffCooldownTicks())}",y)
                y+=12
            }
            if(v.getAircraftThreatLevel()>0) line("RWR","",
                if(v.getAircraftThreatLevel()==2) "MISSILE" else "RADAR",y,SECONDARY)
        }
    }
}
