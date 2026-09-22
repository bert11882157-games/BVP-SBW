package com.atsuishio.superbwarfare.init

import com.mojang.blaze3d.platform.InputConstants
import com.atsuishio.superbwarfare.client.input.NonVehicleKeyConflictContext
import com.atsuishio.superbwarfare.client.input.VehicleControlBindings
import com.atsuishio.superbwarfare.client.input.VehicleKeyMapping
import net.minecraft.client.KeyMapping
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.settings.KeyConflictContext
import net.minecraftforge.client.settings.IKeyConflictContext
import net.minecraftforge.client.settings.KeyModifier
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.lwjgl.glfw.GLFW

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object ModKeyMappings {
    const val CATEGORY = "key.categories.superbwarfare"
    const val VEHICLE_CATEGORY = VehicleControlBindings.BVP_CATEGORY
    private val KEYS = mutableListOf<KeyMapping>()

    @JvmField
    val MOVE_FORWARD = registerVehicleKey("move_forward", GLFW.GLFW_KEY_W)

    @JvmField
    val MOVE_BACKWARD = registerVehicleKey("move_backward", GLFW.GLFW_KEY_S)

    @JvmField
    val MOVE_LEFT = registerVehicleKey("move_left", GLFW.GLFW_KEY_A)

    @JvmField
    val MOVE_RIGHT = registerVehicleKey("move_right", GLFW.GLFW_KEY_D)

    @JvmField
    /** Space is reserved for the server-owned secondary weapon trigger while mounted. */
    val VEHICLE_FIRE_SECONDARY = registerVehicleKey("vehicle_fire_secondary", GLFW.GLFW_KEY_SPACE)

    @JvmField
    val VEHICLE_SWITCH_PRIMARY = registerVehicleKey("vehicle_switch_primary", GLFW.GLFW_KEY_Z)

    @JvmField
    val VEHICLE_SWITCH_SECONDARY = registerVehicleKey("vehicle_switch_secondary", GLFW.GLFW_KEY_Q)

    @JvmField
    val VEHICLE_CYCLE_SIGHT_ZERO = registerVehicleKey("vehicle_cycle_sight_zero", GLFW.GLFW_KEY_G)

    @JvmField
    val MOVE_SHIFT = registerVehicleKey("move_shift", GLFW.GLFW_KEY_LEFT_SHIFT)

    @JvmField
    val MOVE_CTRL = registerVehicleKey("move_ctrl", GLFW.GLFW_KEY_LEFT_CONTROL)

    @JvmField
    val RELOAD = registerKey("reload", GLFW.GLFW_KEY_R, NonVehicleKeyConflictContext)

    @JvmField
    val VEHICLE_RELOAD = registerVehicleKey("vehicle_reload", GLFW.GLFW_KEY_R)

    @JvmField
    val FIRE_MODE = registerKey("fire_mode", GLFW.GLFW_KEY_N, NonVehicleKeyConflictContext)

    @JvmField
    val VEHICLE_CYCLE_AMMO = registerVehicleKey("vehicle_cycle_ammo", GLFW.GLFW_KEY_N)

    @JvmField
    val SENSITIVITY_INCREASE = registerKey("sensitivity_increase", GLFW.GLFW_KEY_PAGE_UP)

    @JvmField
    val SENSITIVITY_REDUCE = registerKey("sensitivity_reduce", GLFW.GLFW_KEY_PAGE_DOWN)

    @JvmField
    val INTERACT = registerKey("interact", GLFW.GLFW_KEY_X)

    @JvmField
    val DISMOUNT = registerVehicleKey("dismount", GLFW.GLFW_KEY_LEFT_ALT)

    @JvmField
    val BREATH = registerKey("breath", GLFW.GLFW_KEY_LEFT_CONTROL)

    @JvmField
    val CONFIG = registerKey(
        "config",
        GLFW.GLFW_KEY_O,
        KeyConflictContext.IN_GAME,
        KeyModifier.ALT
    )

    @JvmField
    val EDIT_MODE = registerKey("edit_mode", GLFW.GLFW_KEY_H)

    @JvmField
    val CHANGE_AMMO_FORWARD = registerKey(
        "change_ammo_forward",
        GLFW.GLFW_KEY_LEFT,
        NonVehicleKeyConflictContext
    )

    @JvmField
    val VEHICLE_CHANGE_AMMO_FORWARD = registerVehicleKey(
        "vehicle_change_ammo_forward",
        GLFW.GLFW_KEY_LEFT
    )

    @JvmField
    val CHANGE_AMMO_BACKWARD = registerKey(
        "change_ammo_backward",
        GLFW.GLFW_KEY_RIGHT,
        NonVehicleKeyConflictContext
    )

    @JvmField
    val VEHICLE_CHANGE_AMMO_BACKWARD = registerVehicleKey(
        "vehicle_change_ammo_backward",
        GLFW.GLFW_KEY_RIGHT
    )

    @JvmField
    val CHANGE_FIRE_MODE_FORWARD = registerKey(
        "change_fire_mode_forward",
        GLFW.GLFW_KEY_UP,
        NonVehicleKeyConflictContext
    )

    @JvmField
    val CHANGE_FIRE_MODE_BACKWARD = registerKey(
        "change_fire_mode_backward",
        GLFW.GLFW_KEY_DOWN,
        NonVehicleKeyConflictContext
    )

    @JvmField
    val UNLOAD = registerKey("unload", InputConstants.UNKNOWN.value)

    @JvmField
    val FIRE = registerKey(
        "fire",
        GLFW.GLFW_MOUSE_BUTTON_LEFT,
        NonVehicleKeyConflictContext,
        type = InputConstants.Type.MOUSE
    )

    @JvmField
    val VEHICLE_FIRE = registerVehicleKey(
        "vehicle_fire",
        GLFW.GLFW_MOUSE_BUTTON_LEFT,
        type = InputConstants.Type.MOUSE
    )

    @JvmField
    val HOLD_ZOOM = registerKey(
        "hold_zoom",
        GLFW.GLFW_MOUSE_BUTTON_RIGHT,
        NonVehicleKeyConflictContext,
        type = InputConstants.Type.MOUSE
    )

    @JvmField
    val VEHICLE_HOLD_ZOOM = registerVehicleKey(
        "vehicle_hold_zoom",
        GLFW.GLFW_MOUSE_BUTTON_RIGHT,
        type = InputConstants.Type.MOUSE
    )

    @JvmField
    val SWITCH_ZOOM = registerKey(
        "switch_zoom",
        GLFW.GLFW_KEY_UNKNOWN,
        NonVehicleKeyConflictContext
    )

    @JvmField
    val VEHICLE_SWITCH_ZOOM = registerVehicleKey("vehicle_switch_zoom", GLFW.GLFW_KEY_UNKNOWN)

    @JvmField
    val RELEASE_DECOY = registerVehicleKey("release_decoy", GLFW.GLFW_KEY_V)

    @JvmField
    val FREE_CAMERA = registerVehicleKey("free_camera", GLFW.GLFW_KEY_C)

    @JvmField
    val MELEE = registerKey("melee", GLFW.GLFW_KEY_V)

    @JvmField
    val VEHICLE_SEEK = registerVehicleKey("vehicle_seek", GLFW.GLFW_KEY_X)

    @JvmField
    val MARK = registerKey("mark", GLFW.GLFW_MOUSE_BUTTON_MIDDLE, type = InputConstants.Type.MOUSE)

    @JvmField
    val ACTIVE_THERMAL_IMAGING = registerKey(
        "active_thermal_imaging",
        GLFW.GLFW_KEY_K,
        NonVehicleKeyConflictContext
    )

    @JvmField
    val VEHICLE_ACTIVE_THERMAL_IMAGING = registerVehicleKey(
        "vehicle_active_thermal_imaging",
        GLFW.GLFW_KEY_K
    )

    private fun registerKey(
        name: String,
        code: Int,
        conflictContext: IKeyConflictContext = KeyConflictContext.IN_GAME,
        modifier: KeyModifier = KeyModifier.NONE,
        type: InputConstants.Type = InputConstants.Type.KEYSYM
    ): KeyMapping {
        val key = KeyMapping(
            "key.superbwarfare.$name",
            conflictContext,
            modifier,
            type,
            code,
            CATEGORY
        )
        KEYS.add(key)
        return key
    }

    private fun registerVehicleKey(
        name: String,
        code: Int,
        modifier: KeyModifier = KeyModifier.NONE,
        type: InputConstants.Type = InputConstants.Type.KEYSYM
    ): VehicleKeyMapping {
        val key = VehicleControlBindings.register(
            VehicleKeyMapping(
                "key.superbwarfare.$name",
                modifier,
                type,
                code,
                VEHICLE_CATEGORY
            )
        )
        KEYS.add(key)
        return key
    }

    @SubscribeEvent
    fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
        KEYS.forEach { event.register(it) }
    }
}
