package com.atsuishio.superbwarfare.init

import com.mojang.blaze3d.platform.InputConstants
import com.atsuishio.superbwarfare.client.input.*
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
    val MOVE_FORWARD = registerVehicleKey("move_forward", GLFW.GLFW_KEY_W,
        context = LandVehicleKeyConflictContext, category = VehicleControlBindings.LAND_CATEGORY)

    @JvmField
    val MOVE_BACKWARD = registerVehicleKey("move_backward", GLFW.GLFW_KEY_S,
        context = LandVehicleKeyConflictContext, category = VehicleControlBindings.LAND_CATEGORY)

    @JvmField
    val MOVE_LEFT = registerVehicleKey("move_left", GLFW.GLFW_KEY_A,
        context = LandVehicleKeyConflictContext, category = VehicleControlBindings.LAND_CATEGORY)

    @JvmField
    val MOVE_RIGHT = registerVehicleKey("move_right", GLFW.GLFW_KEY_D,
        context = LandVehicleKeyConflictContext, category = VehicleControlBindings.LAND_CATEGORY)

    @JvmField
    /** Space is reserved for the server-owned secondary weapon trigger while mounted. */
    val VEHICLE_FIRE_SECONDARY = registerVehicleKey("vehicle_fire_secondary", GLFW.GLFW_KEY_SPACE)

    @JvmField
    val FLIGHT_RECENTER = registerVehicleKey("flight_recenter", GLFW.GLFW_KEY_HOME,
        context = FixedWingPilotKeyConflictContext, category = VehicleControlBindings.PLANE_CATEGORY)

    @JvmField
    val FLIGHT_THROTTLE_UP = registerVehicleKey(
        "flight_throttle_up",
        GLFW.GLFW_KEY_LEFT_SHIFT,
        context = PlanePilotKeyConflictContext,
        category = VehicleControlBindings.PLANE_CATEGORY,
    )

    @JvmField
    val FLIGHT_THROTTLE_DOWN = registerVehicleKey(
        "flight_throttle_down",
        GLFW.GLFW_KEY_LEFT_CONTROL,
        context = PlanePilotKeyConflictContext,
        category = VehicleControlBindings.PLANE_CATEGORY,
    )

    @JvmField
    val FLIGHT_AIRBRAKE = KeyMapping("key.superbwarfare.flight_airbrake", GLFW.GLFW_KEY_UNKNOWN, VehicleControlBindings.PLANE_CATEGORY)

    @JvmField
    val FLIGHT_RUDDER_LEFT = KeyMapping("key.superbwarfare.flight_rudder_left", GLFW.GLFW_KEY_UNKNOWN, VehicleControlBindings.PLANE_CATEGORY)

    @JvmField
    val FLIGHT_RUDDER_RIGHT = KeyMapping("key.superbwarfare.flight_rudder_right", GLFW.GLFW_KEY_UNKNOWN, VehicleControlBindings.PLANE_CATEGORY)

    @JvmField
    val FIXED_WING_PITCH_DOWN = registerVehicleKey("fixed_wing_pitch_down", GLFW.GLFW_KEY_W,
        context = FixedWingPilotKeyConflictContext, category = VehicleControlBindings.PLANE_CATEGORY)

    @JvmField
    val FIXED_WING_PITCH_UP = registerVehicleKey("fixed_wing_pitch_up", GLFW.GLFW_KEY_S,
        context = FixedWingPilotKeyConflictContext, category = VehicleControlBindings.PLANE_CATEGORY)

    @JvmField
    val FIXED_WING_ROLL_LEFT = registerVehicleKey("fixed_wing_roll_left", GLFW.GLFW_KEY_A,
        context = FixedWingPilotKeyConflictContext, category = VehicleControlBindings.PLANE_CATEGORY)

    @JvmField
    val FIXED_WING_ROLL_RIGHT = registerVehicleKey("fixed_wing_roll_right", GLFW.GLFW_KEY_D,
        context = FixedWingPilotKeyConflictContext, category = VehicleControlBindings.PLANE_CATEGORY)

    @JvmField
    // Airbrakes share the held throttle-down binding at zero thrust.
    val FIXED_WING_AIRBRAKE = KeyMapping("key.superbwarfare.fixed_wing_airbrake", GLFW.GLFW_KEY_UNKNOWN,
        VehicleControlBindings.PLANE_CATEGORY)

    @JvmField
    val FIXED_WING_LANDING_GEAR = registerVehicleKey("fixed_wing_landing_gear", GLFW.GLFW_KEY_G,
        context = FixedWingLandingGearKeyConflictContext, category = VehicleControlBindings.PLANE_CATEGORY)

    @JvmField
    val FLIGHT_LEGACY_BOOST = KeyMapping("key.superbwarfare.flight_legacy_boost", GLFW.GLFW_KEY_UNKNOWN, VehicleControlBindings.PLANE_CATEGORY)

    @JvmField
    val HELICOPTER_COLLECTIVE_UP = registerVehicleKey("helicopter_collective_up", GLFW.GLFW_KEY_W,
        context = HelicopterPilotKeyConflictContext, category = VehicleControlBindings.HELICOPTER_CATEGORY)

    @JvmField
    val HELICOPTER_COLLECTIVE_DOWN = registerVehicleKey("helicopter_collective_down", GLFW.GLFW_KEY_S,
        context = HelicopterPilotKeyConflictContext, category = VehicleControlBindings.HELICOPTER_CATEGORY)

    @JvmField
    val HELICOPTER_ROLL_LEFT = registerVehicleKey("helicopter_roll_left", GLFW.GLFW_KEY_A,
        context = HelicopterPilotKeyConflictContext, category = VehicleControlBindings.HELICOPTER_CATEGORY)

    @JvmField
    val HELICOPTER_ROLL_RIGHT = registerVehicleKey("helicopter_roll_right", GLFW.GLFW_KEY_D,
        context = HelicopterPilotKeyConflictContext, category = VehicleControlBindings.HELICOPTER_CATEGORY)

    @JvmField
    val HELICOPTER_THRUST_UP = registerVehicleKey("helicopter_thrust_up", GLFW.GLFW_KEY_LEFT_SHIFT,
        context = HelicopterPilotKeyConflictContext, category = VehicleControlBindings.HELICOPTER_CATEGORY)

    @JvmField
    val HELICOPTER_THRUST_DOWN = registerVehicleKey("helicopter_thrust_down", GLFW.GLFW_KEY_LEFT_CONTROL,
        context = HelicopterPilotKeyConflictContext, category = VehicleControlBindings.HELICOPTER_CATEGORY)

    @JvmField
    val DRONE_FORWARD = registerVehicleKey("drone_forward", GLFW.GLFW_KEY_W,
        context = DroneKeyConflictContext, category = VehicleControlBindings.DRONE_CATEGORY)

    @JvmField
    val DRONE_BACKWARD = registerVehicleKey("drone_backward", GLFW.GLFW_KEY_S,
        context = DroneKeyConflictContext, category = VehicleControlBindings.DRONE_CATEGORY)

    @JvmField
    val DRONE_LEFT = registerVehicleKey("drone_left", GLFW.GLFW_KEY_A,
        context = DroneKeyConflictContext, category = VehicleControlBindings.DRONE_CATEGORY)

    @JvmField
    val DRONE_RIGHT = registerVehicleKey("drone_right", GLFW.GLFW_KEY_D,
        context = DroneKeyConflictContext, category = VehicleControlBindings.DRONE_CATEGORY)

    @JvmField
    val DRONE_ASCEND = registerVehicleKey("drone_ascend", GLFW.GLFW_KEY_SPACE,
        context = DroneKeyConflictContext, category = VehicleControlBindings.DRONE_CATEGORY)

    @JvmField
    val DRONE_DESCEND = registerVehicleKey("drone_descend", GLFW.GLFW_KEY_LEFT_SHIFT,
        context = DroneKeyConflictContext, category = VehicleControlBindings.DRONE_CATEGORY)

    @JvmField
    val VEHICLE_SWITCH_PRIMARY = registerVehicleKey("vehicle_switch_primary", GLFW.GLFW_KEY_Z)

    @JvmField
    val VEHICLE_SWITCH_SECONDARY = registerVehicleKey("vehicle_cycle_secondary", GLFW.GLFW_KEY_UNKNOWN,
        context = VehicleWeaponSelectionKeyContext)

    @JvmField
    val VEHICLE_CYCLE_SIGHT_ZERO = registerVehicleKey("vehicle_cycle_sight_zero", GLFW.GLFW_KEY_G)

    @JvmField
    val MOVE_SHIFT = registerVehicleKey("move_shift", GLFW.GLFW_KEY_LEFT_SHIFT,
        context = LandVehicleKeyConflictContext, category = VehicleControlBindings.LAND_CATEGORY)

    @JvmField
    val MOVE_CTRL = registerVehicleKey("move_ctrl", GLFW.GLFW_KEY_LEFT_CONTROL,
        context = LandVehicleKeyConflictContext, category = VehicleControlBindings.LAND_CATEGORY)

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
    val RELEASE_CHAFF = registerVehicleKey("release_chaff", GLFW.GLFW_KEY_B)

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
        type: InputConstants.Type = InputConstants.Type.KEYSYM,
        context: IKeyConflictContext = VehicleKeyConflictContext,
        category: String = VEHICLE_CATEGORY,
    ): VehicleKeyMapping {
        val key = VehicleControlBindings.register(
            VehicleKeyMapping(
                "key.superbwarfare.$name",
                modifier,
                type,
                code,
                category,
                context,
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
