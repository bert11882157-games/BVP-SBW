package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSlot
import com.atsuishio.superbwarfare.client.VehicleWeaponSlotCycleClient
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.init.ModMobEffects
import com.atsuishio.superbwarfare.network.message.send.SwitchVehicleWeaponMessage
import com.atsuishio.superbwarfare.tools.mc
import com.atsuishio.superbwarfare.tools.notInGame
import com.atsuishio.superbwarfare.tools.sendPacketToServer
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.world.entity.player.Player
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.settings.IKeyConflictContext
import net.minecraftforge.client.settings.KeyModifier
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityMountEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.lwjgl.glfw.GLFW

/** A seat-scoped gesture; gameplay selection is committed only by the existing server packets. */
@Mod.EventBusSubscriber(value = [Dist.CLIENT])
object VehicleWeaponSelectionInput {
    private data class Context(
        val player: Player,
        val vehicle: VehicleEntity,
        val seat: Int,
        val connection: ClientPacketListener,
        val binding: InputConstants.Key,
        val modifier: KeyModifier,
    )
    private data class Binding(val key: InputConstants.Key, val modifier: KeyModifier)

    private val gesture = VehicleWeaponSelectionGesture()
    private var context: Context? = null

    /** Dedicated conflict context avoids claiming inventory keys in seats without weapon controls. */
    @JvmStatic
    fun eligible(): Boolean {
        if (notInGame || mc.isPaused) return false
        val player = mc.player ?: return false
        val vehicle = player.vehicle as? VehicleEntity ?: return false
        val seat = vehicle.getSeatIndex(player)
        return player.isAlive && !player.isRemoved && !player.isSpectator &&
            !player.hasEffect(ModMobEffects.SHOCK.get()) && !vehicle.isRemoved && !vehicle.isWreck &&
            vehicle.level() === player.level() && seat >= 0 &&
            vehicle.getNthEntity(seat) === player && vehicle.banHand(player) && vehicle.hasWeapon(seat)
    }

    private fun valid(active: Context): Boolean = eligible() &&
        mc.player === active.player && mc.connection === active.connection &&
        active.player.vehicle === active.vehicle && mc.level === active.vehicle.level() &&
        active.vehicle.getSeatIndex(active.player) == active.seat &&
        ModKeyMappings.VEHICLE_SWITCH_SECONDARY.key == active.binding &&
        ModKeyMappings.VEHICLE_SWITCH_SECONDARY.keyModifier == active.modifier &&
        ModKeyMappings.VEHICLE_SWITCH_SECONDARY.isConflictContextAndModifierActive() &&
        !seatModifierDown()

    /** Handles both keyboard and mouse edges, including releases while a screen is open. */
    @JvmStatic
    fun handleInput(input: InputConstants.Key, action: Int): Boolean {
        synchronizeBinding()
        val now = System.nanoTime()
        val active = context
        if (active != null && !valid(active)) cancel()
        if (action == GLFW.GLFW_RELEASE) {
            val released = context ?: return false
            if (input != released.binding) return false
            dispatch(released, gesture.release(now))
            context = null
            return true
        }
        if (action != GLFW.GLFW_PRESS || !eligible() || seatModifierDown() ||
            !ModKeyMappings.VEHICLE_SWITCH_SECONDARY.isActiveAndMatches(input)) return false
        if (context != null) return true
        val player = mc.player ?: return false
        val vehicle = player.vehicle as? VehicleEntity ?: return false
        val connection = mc.connection ?: return false
        context = Context(player, vehicle, vehicle.getSeatIndex(player), connection, input,
            ModKeyMappings.VEHICLE_SWITCH_SECONDARY.keyModifier)
        gesture.press(now)
        return true
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        synchronizeBinding()
        val active = context ?: return
        if (!valid(active)) {
            cancel()
            return
        }
        dispatch(active, gesture.advance(System.nanoTime()))
    }

    @SubscribeEvent
    fun mount(event: EntityMountEvent) {
        if (event.entityMounting === mc.player) cancel()
    }

    private fun synchronizeBinding() {
        val mapping = ModKeyMappings.VEHICLE_SWITCH_SECONDARY
        gesture.bind(Binding(mapping.key, mapping.keyModifier))
    }

    /** Read-only HUD fraction; rendering never advances the gesture or sends an input packet. */
    @JvmStatic
    fun holdProgress(vehicle: VehicleEntity, player: Player): Float? {
        val active = context ?: return null
        if (active.vehicle !== vehicle || active.player !== player || !valid(active)) return null
        return gesture.progress(System.nanoTime())
    }

    /** Number-key routing must not select a primary while the same binding owns a pending hold. */
    @JvmStatic
    fun defersPrimary(mapping: KeyMapping): Boolean = eligible() &&
        ModKeyMappings.VEHICLE_SWITCH_SECONDARY.isActiveAndMatches(mapping.key) &&
        !seatModifierDown()

    /** Read the seat modifier physically: calling KeyMapping.isDown from its conflict context recurses. */
    @JvmStatic
    fun seatModifierDown(): Boolean {
        val key = mc.options.keyShift.key
        return when (key.type) {
            InputConstants.Type.KEYSYM -> key != InputConstants.UNKNOWN &&
                InputConstants.isKeyDown(mc.window.window, key.value)
            InputConstants.Type.MOUSE ->
                GLFW.glfwGetMouseButton(mc.window.window, key.value) == GLFW.GLFW_PRESS
            else -> Screen.hasShiftDown()
        }
    }

    private fun dispatch(active: Context, action: VehicleWeaponSelectionGesture.Action) {
        if (!valid(active)) return
        when (action) {
            VehicleWeaponSelectionGesture.Action.SELECT_PRIMARY_TWO ->
                sendPacketToServer(SwitchVehicleWeaponMessage(active.seat, 1.0, false))
            VehicleWeaponSelectionGesture.Action.CYCLE_SECONDARY ->
                VehicleWeaponSlotCycleClient.request(VehicleWeaponSlot.SECONDARY)
            VehicleWeaponSelectionGesture.Action.NONE -> Unit
        }
    }

    private fun cancel() {
        context = null
        gesture.cancel()
    }
}

object VehicleWeaponSelectionKeyContext : IKeyConflictContext {
    override fun isActive(): Boolean = VehicleWeaponSelectionInput.eligible() &&
        !VehicleWeaponSelectionInput.seatModifierDown()
    override fun conflicts(other: IKeyConflictContext): Boolean = other === this
}
