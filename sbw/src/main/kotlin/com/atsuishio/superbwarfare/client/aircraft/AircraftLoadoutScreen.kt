package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.api.aircraft.AircraftGuidanceLabels
import com.atsuishio.superbwarfare.client.camera.AircraftCameraPivot
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.worldToScreen
import com.google.gson.JsonObject
import net.minecraft.ChatFormatting
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import net.minecraftforge.registries.ForgeRegistries
import org.joml.Vector3d
import org.lwjgl.glfw.GLFW
import kotlin.math.*

/** Server-acknowledged live equipment editor. Closing does not discard equipment changes. */
class AircraftLoadoutScreen(private var state: AircraftArmamentSnapshot) : Screen(Component.literal("Aircraft loadout")) {
    private data class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: () -> Unit) {
        fun contains(px: Double, py: Double) = px >= x && py >= y && px < x+w && py < y+h
    }
    private val hits = ArrayList<Hit>()
    private val oldView = Minecraft.getInstance().options.cameraType
    private val heading = (Minecraft.getInstance().player?.vehicle as? VehicleEntity)?.getYaw(1f) ?: 0f
    private var selectedMount: String? = null
    private var page = 0
    private var presetsOpen = false
    private var nameInput: EditBox? = null
    private var pending = false
    private var pendingTicks = 0
    private var hovered: List<Component>? = null
    private data class Station(val mount: AircraftMountView, val position: Vec3, val number: Int, val index: Int)
    private fun stations(): List<Station> = state.definition.mounts.filterNot { it.internal }
        .flatMap { mount -> mount.positions.map { mount to it } }
        .sortedWith(compareBy<Pair<AircraftMountView, Vec3>> { it.second.x }
            .thenBy { it.second.z }.thenBy { it.first.id })
        .mapIndexed { index, (mount, position) -> Station(mount, position, index + 1, mount.positions.indexOf(position)) }
    override fun isPauseScreen() = false
    override fun init() {
        Minecraft.getInstance().options.cameraType = CameraType.THIRD_PERSON_BACK
        nameInput?.let { it.x=width/2-80; it.y=64; addRenderableWidget(it) }
    }
    override fun removed() { Minecraft.getInstance().options.cameraType=oldView; super.removed() }
    fun accept(next: AircraftArmamentSnapshot) {
        if(next.vehicle!=state.vehicle) return
        state=next; pending=false
    }
    fun cameraRotation(vehicle: VehicleEntity, partial: Float): Vec2? =
        cameraFit(vehicle,partial)?.let { Vec2(heading,it.pitch) }
    fun cameraPosition(vehicle: VehicleEntity, partial: Float): Vec3? = cameraFit(vehicle,partial)?.position
    // Position and rotation are read separately per frame; both must come from one fit.
    private var cachedFit: AircraftLoadoutFraming.Fit? = null
    private var cachedFitKey: List<Any>? = null
    private fun cameraFit(vehicle: VehicleEntity, partial: Float): AircraftLoadoutFraming.Fit? {
        if(vehicle.uuid!=state.vehicle || !partial.isFinite()) return null
        val fov=Minecraft.getInstance().options.fov().get().toDouble()
        val key=listOf(vehicle.tickCount,partial,width,height,fov,state.revision)
        if(key==cachedFitKey) return cachedFit
        val speed=AircraftMountPresentation.speed(vehicle,partial)
        val points=state.definition.mounts.filterNot { it.internal }.flatMap { mount -> mount.positions.indices.map { mount.position(it,speed) } } + vehicle.computed().aircraftTerrainContact
            ?.bodyVolumes().orEmpty().flatMap { box -> (0..7).map { bits -> Vec3(
                if(bits and 1==0) box.minimum.x else box.maximum.x,
                if(bits and 2==0) box.minimum.y else box.maximum.y,
                if(bits and 4==0) box.minimum.z else box.maximum.z) } }
        val transform=vehicle.getVehicleTransform(partial)
        val world=points.map { local -> transform.transformPosition(Vector3d(local.x,local.y,local.z)).let { Vec3(it.x,it.y,it.z) } }
        val center=AircraftCameraPivot.center(vehicle,partial)
        val bayRows=(state.definition.mounts.count { it.internal }+3)/4
        // Equipment is only editable on the ground, so the entity's feet are the ground plane.
        var groundY=Mth.lerp(partial.toDouble(),vehicle.yo,vehicle.y)
        var fit=AircraftLoadoutFraming.fitFromGround(center,world,heading,width,height,fov,bayRows,groundY)
        // Rising terrain around the aircraft lifts the eye onto the obstructing surface.
        for(lift in 1..MAX_TERRAIN_LIFTS) {
            groundY=obstructionTop(vehicle,fit.position) ?: break
            fit=AircraftLoadoutFraming.fitFromGround(center,world,heading,width,height,fov,bayRows,groundY)
        }
        cachedFit=fit; cachedFitKey=key
        return fit
    }
    /** World Y of the first open block above a solid block containing [eye], or null when open. */
    private fun obstructionTop(vehicle: VehicleEntity, eye: Vec3): Double? {
        val level=vehicle.level()
        fun solid(pos: BlockPos)=!level.getBlockState(pos).getCollisionShape(level,pos).isEmpty
        var pos=BlockPos.containing(eye.x,eye.y,eye.z)
        if(!solid(pos)) return null
        var climbed=0
        while(climbed++<MAX_TERRAIN_CLIMB_BLOCKS && solid(pos)) pos=pos.above()
        return pos.y.toDouble()
    }
    private companion object {
        const val MAX_TERRAIN_LIFTS = 3
        const val MAX_TERRAIN_CLIMB_BLOCKS = 8
        val BUTTON_TEXT = 0xFFEDE7DC.toInt()
        /** Matches the green of the store tooltip lines (ChatFormatting.GREEN). */
        val OCCUPIED_STATION_TEXT = 0xFF55FF55.toInt()
    }
    private fun editable(): Boolean {
        val vehicle=Minecraft.getInstance().player?.vehicle as? VehicleEntity ?: return false
        return !pending && vehicle.onGround() && vehicle.deltaMovement.lengthSqr()<=.0025
    }
    private fun submit(operation: String, choices: Map<String,String> = state.selections,
                       counts: Map<String,Int> = state.counts, name: String? = null) {
        if(!editable()) return
        val body=JsonObject().apply {
            addProperty("Revision",state.revision); name?.let { addProperty("Name",it) }
            if(operation=="APPLY" || operation=="SAVE_PRESET") {
                add("Selections",JsonObject().apply { choices.forEach { (k,v)->addProperty(k,v) } })
                add("Counts",JsonObject().apply { counts.filterKeys { it in choices }.forEach { (k,v)->addProperty(k,v) } })
            }
        }
        pending=AircraftArmamentClient.request(operation,body); pendingTicks=0
    }
    private fun fits(mount: AircraftMountView, store: AircraftStoreView, count: Int,
                     choices: Map<String,String>, counts: Map<String,Int>): Boolean =
        count in 1..state.maxCopies(mount,store) && store.massKg*(store.capacity?:1)*count+store.rackMassKg<=mount.maxPylonMassKg+1e-6 &&
            state.payloadKg(choices,counts)<=state.definition.maxPayloadKg+1e-6
    private fun choose(mount: AircraftMountView, store: AircraftStoreView?) {
        val choices=state.selections.toMutableMap(); val counts=state.counts.toMutableMap()
        if(store==null) { choices.remove(mount.id); counts.remove(mount.id) }
        else { choices[mount.id]=store.id; counts[mount.id]=selectionCount(mount,store) }
        submit("APPLY",choices,counts)
    }
    private fun selectionCount(mount: AircraftMountView, store: AircraftStoreView): Int =
        if(!mount.quantitySelectable && state.selections[mount.id]==store.id) state.counts[mount.id] ?: store.fixedRackCount ?: 1
        else if(mount.internal) 1 else store.fixedRackCount ?: 1
    private fun changeBayCount(mount: AircraftMountView, delta: Int) {
        if(!mount.internal || !mount.quantitySelectable) return
        val store=state.stores[state.selections[mount.id]]?:return
        val count=(state.counts[mount.id]?:1)+delta; val counts=state.counts+(mount.id to count)
        if(fits(mount,store,count,state.selections,counts)) submit("APPLY",counts=counts)
    }
    private fun savePrompt() {
        presetsOpen=false; selectedMount=null
        if(nameInput!=null) return
        val next=generateSequence(1) { it+1 }.first { it.toString() !in state.presets }
        nameInput=addRenderableWidget(EditBox(font,width/2-80,64,160,20,Component.literal("Preset name"))).also {
            it.setMaxLength(32); it.value=next.toString(); setFocused(it)
        }
    }
    private fun saveNamed() {
        val input=nameInput?:return; val name=input.value.trim()
        if(name.isNotBlank() && editable()) {
            submit("SAVE_PRESET",name=name); removeWidget(input); nameInput=null
        }
    }
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if(keyCode==GLFW.GLFW_KEY_ESCAPE || nameInput==null && AircraftArmamentKeys.OPEN.matches(keyCode,scanCode)) { onClose(); return true }
        if(nameInput!=null && (keyCode==GLFW.GLFW_KEY_ENTER || keyCode==GLFW.GLFW_KEY_KP_ENTER)) { saveNamed(); return true }
        return super.keyPressed(keyCode,scanCode,modifiers)
    }
    override fun mouseClicked(x: Double,y: Double,button: Int): Boolean {
        if(super.mouseClicked(x,y,button)) return true
        if(button!=0) return false
        hits.asReversed().firstOrNull { it.contains(x,y) }?.let { it.action(); return true }
        return false
    }
    override fun tick() {
        val vehicle=Minecraft.getInstance().player?.vehicle as? VehicleEntity
        if(vehicle==null || vehicle.uuid!=state.vehicle || vehicle.isRemoved || vehicle.isWreck) { onClose(); return }
        // Re-read an acknowledgement, never replay a loadout mutation.
        if(pending && ++pendingTicks==100) AircraftArmamentClient.request("OPEN")
        nameInput?.tick()
    }
    private fun button(g: GuiGraphics,text: String,x: Int,y: Int,w: Int,mx: Int,my: Int,
                       enabled: Boolean=true,textColor: Int=BUTTON_TEXT,action: ()->Unit) {
        val over=mx in x until x+w && my in y until y+20
        g.fill(x,y,x+w,y+20,if(over && enabled) 0xDD465E6E.toInt() else 0xCC17212B.toInt())
        g.drawCenteredString(font,font.plainSubstrByWidth(text,w-4),x+w/2,y+6,
            if(enabled) textColor else 0xFF77818B.toInt())
        if(enabled) hits+=Hit(x,y,w,20,action)
    }
    private fun inventoryPanel(g: GuiGraphics,x: Int,y: Int,w: Int,h: Int) {
        g.fill(x,y,x+w,y+h,0xFF373737.toInt())
        g.fill(x+1,y+1,x+w-1,y+h-1,0xFF555555.toInt())
        g.fill(x+1,y+1,x+w-2,y+h-2,0xFFFFFFFF.toInt())
        g.fill(x+3,y+3,x+w-3,y+h-3,0xFFC6C6C6.toInt())
    }
    private fun inventorySlot(g: GuiGraphics,x: Int,y: Int,selected: Boolean) {
        g.fill(x,y,x+18,y+18,0xFFFFFFFF.toInt())
        g.fill(x,y,x+17,y+17,0xFF373737.toInt())
        g.fill(x+1,y+1,x+17,y+17,if(selected) 0xFFA89864.toInt() else 0xFF8B8B8B.toInt())
    }
    override fun render(g: GuiGraphics,mx: Int,my: Int,partial: Float) {
        hits.clear(); hovered=null
        val vehicle=Minecraft.getInstance().player?.vehicle as? VehicleEntity?:return
        g.fill(0,0,width,48,0xA5101923.toInt())
        g.drawString(font,state.definition.name,10,7,0xFFE2B66D.toInt())
        val weight="${state.payloadKg().toInt()} / ${state.definition.maxPayloadKg.toInt()} kg"
        g.drawString(font,weight,width-font.width(weight)-10,7,-1)
        button(g,"Save preset",10,23,90,mx,my,!pending) { savePrompt() }
        button(g,"Load preset",104,23,90,mx,my,!pending) { presetsOpen=!presetsOpen; selectedMount=null }
        button(g,"Close",width-60,23,50,mx,my) { onClose() }
        val status=when { pending->"Fitting equipment…"; !editable()->"Stop on the ground to change equipment"
            state.message.isNotEmpty()->state.message; else->"Select a station · Equipment is fitted immediately" }
        g.drawCenteredString(font,font.plainSubstrByWidth(status,width-16),width/2,height-13,0xFFE2B66D.toInt())
        var bay=0; val transform=vehicle.getVehicleTransform(partial)
        val stations=stations()
        // Station numbers whose physical position currently carries a store (fired rounds excluded).
        val occupiedStations=stations.filter { state.storePresent(it.mount,it.index) }.mapTo(HashSet()) { it.number.toString() }
        val anchors=ArrayList<AircraftLoadoutLayout.Anchor>()
        // Modal pickers suppress the projected controls visually as well as their click targets.
        if(selectedMount==null && !presetsOpen && nameInput==null) {
        for(mount in state.definition.mounts) {
            if(mount.internal) {
                val col=bay%4; val row=bay++/4
                button(g,mount.name,10+col*64,height-65-row*23,60,mx,my) { selectedMount=mount.id; page=0; presetsOpen=false }
            }
        }
        for(station in stations) {
                val mount=station.mount; val local=mount.position(station.index,AircraftMountPresentation.speed(vehicle,partial))
                val world=transform.transformPosition(Vector3d(local.x,local.y,local.z))
                val p=Vec3(world.x,world.y,world.z).worldToScreen()
                if(p.z<=0 || !p.x.isFinite() || !p.y.isFinite()) continue
                anchors+=AircraftLoadoutLayout.Anchor(mount.id,station.number.toString(),p.x.toInt(),p.y.toInt())
        }
        for(placement in AircraftLoadoutLayout.place(anchors,width,height,(bay+3)/4)) {
            val anchor=placement.anchor
            val x=placement.x; val y=placement.y
            g.vLine(anchor.x,min(anchor.y,y),max(anchor.y,y),0x997EB3C5.toInt())
            val center=x+AircraftLoadoutLayout.BUTTON_WIDTH/2
            g.hLine(min(anchor.x,center),max(anchor.x,center),y,0x997EB3C5.toInt())
            button(g,anchor.name,x,y,AircraftLoadoutLayout.BUTTON_WIDTH,mx,my,
                textColor=if(anchor.name in occupiedStations) OCCUPIED_STATION_TEXT else BUTTON_TEXT) { selectedMount=anchor.id; page=0; presetsOpen=false }
        }
        }
        if(presetsOpen) {
            while(hits.size>3) hits.removeAt(hits.lastIndex)
            val names=state.presets.keys.sorted()
            if(names.isEmpty()) g.drawString(font,"No saved presets",104,53,-1)
            val visible=((height-90)/21).coerceAtLeast(1)
            page=page.coerceIn(0,(names.size-1).coerceAtLeast(0)/visible)
            names.drop(page*visible).take(visible).forEachIndexed { i,name ->
                button(g,name,104.coerceAtMost(width-160),50+i*21,150,mx,my,editable()) { submit("LOAD_PRESET",name=name); presetsOpen=false }
            }
            button(g,"<",104.coerceAtMost(width-160),height-35,25,mx,my,page>0) { page-- }
            button(g,">",134.coerceAtMost(width-130),height-35,25,mx,my,(page+1)*visible<names.size) { page++ }
        }
        state.definition.mounts.firstOrNull { it.id==selectedMount }?.let { mount ->
            while(hits.size>3) hits.removeAt(hits.lastIndex)
            val pw=min(176,width-16); val x=(width-pw)/2; val y=56
            val bayControls=mount.internal && mount.quantitySelectable
            val columns=((pw-14)/18).coerceIn(1,9)
            val rows=((height-y-(if(bayControls) 94 else 70))/18).coerceIn(1,6); val perPage=columns*rows
            val options=mount.allowed.mapNotNull { state.stores[it] }
            page=page.coerceIn(0,(options.size-1).coerceAtLeast(0)/perPage)
            inventoryPanel(g,x,y,pw,rows*18+if(bayControls) 82 else 58)
            val stationTitle=if(mount.internal) mount.name else stations.filter { it.mount.id==mount.id }
                .joinToString(" / ") { it.number.toString() } + if(mount.positions.size>1) " · paired stations" else ""
            g.drawString(font,font.plainSubstrByWidth(stationTitle,pw-12),x+6,y+6,0xFF404040.toInt(),false)
            repeat(perPage) { i -> inventorySlot(g,x+7+(i%columns)*18,y+19+(i/columns)*18,false) }
            options.drop(page*perPage).take(perPage).forEachIndexed { i,store ->
                val ix=x+7+(i%columns)*18; val iy=y+19+(i/columns)*18
                val count=selectionCount(mount,store)
                val fits=fits(mount,store,count,state.selections+(mount.id to store.id),state.counts+(mount.id to count))
                inventorySlot(g,ix,iy,state.selections[mount.id]==store.id)
                val podAmmo = if(store.id in setOf("berts_vehicle_pack:gsh23_pod",
                    "berts_vehicle_pack:gun_pod/f111_m61_bay", "berts_vehicle_pack:gun_pod/f4c_gau4"))
                    net.minecraft.resources.ResourceLocation("superbwarfare:small_autocannon_shell") else null
                val item=(store.ammoItem ?: podAmmo ?: store.item)?.let { ForgeRegistries.ITEMS.getValue(it) }?:Items.PAPER
                g.renderItem(ItemStack(item),ix+1,iy+1)
                if(!fits) g.fill(ix+1,iy+1,ix+17,iy+17,0x99202020.toInt())
                if(mx in ix until ix+18 && my in iy until iy+18) {
                    g.fill(ix+1,iy+1,ix+17,iy+17,0x60FFFFFF)
                    hovered=listOf(Component.literal(store.name),
                    Component.literal(store.categoryLabel).withStyle(ChatFormatting.GREEN),
                    Component.literal(AircraftGuidanceLabels.description(store.guidanceMode)).withStyle(ChatFormatting.GREEN),
                    Component.literal("${(store.massKg*(store.capacity?:1)*count+store.rackMassKg).toInt()} kg${if(count>1) " · $count munitions" else ""}").withStyle(ChatFormatting.GREEN)) +
                    if(fits) emptyList() else listOf(Component.literal("Payload or station limit exceeded").withStyle(ChatFormatting.RED))
                }
                if(editable() && fits) hits+=Hit(ix,iy,18,18) { choose(mount,store) }
            }
            val bottom=y+18*rows+25
            button(g,"Empty",x+6,bottom,48,mx,my,editable()) { choose(mount,null) }
            button(g,"<",x+58,bottom,20,mx,my,page>0) { page-- }
            button(g,">",x+82,bottom,20,mx,my,(page+1)*perPage<options.size) { page++ }
            button(g,"Done",x+pw-48,bottom,42,mx,my) { selectedMount=null }
            if(mount.internal && mount.quantitySelectable) {
                val store=state.stores[state.selections[mount.id]]; val count=state.counts[mount.id]?:1
                button(g,"−",x+6,bottom+24,20,mx,my,editable() && store!=null && count>1) { changeBayCount(mount,-1) }
                g.drawString(font,"${if(store==null) 0 else count*(store.capacity?:1)} in bay slot",x+31,bottom+30,0xFF404040.toInt(),false)
                button(g,"+",x+pw-26,bottom+24,20,mx,my,editable() && store!=null &&
                    fits(mount,store,count+1,state.selections,state.counts+(mount.id to count+1))) { changeBayCount(mount,1) }
            }
        }
        nameInput?.let {
            hits.clear()
            g.fill(width/2-88,51,width/2+88,112,0xF01B2633.toInt()); g.drawString(font,"Preset name",width/2-80,53,-1)
            button(g,"Save",width/2-30,88,60,mx,my,editable()) { saveNamed() }
        }
        super.render(g,mx,my,partial)
        hovered?.let { g.renderComponentTooltip(font,it,mx,my) }
    }
}
