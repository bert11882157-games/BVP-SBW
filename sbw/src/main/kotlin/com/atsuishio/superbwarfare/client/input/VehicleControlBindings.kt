package com.atsuishio.superbwarfare.client.input

import net.minecraft.client.KeyMapping
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.mojang.blaze3d.platform.InputConstants
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/** Registry and runtime priority policy for the dedicated in-vehicle control profile. */
object VehicleControlBindings {
    const val BVP_CATEGORY = "key.categories.berts_vehicle_pack"
    const val LAND_CATEGORY = "key.categories.berts_vehicle_pack.land"
    const val PLANE_CATEGORY = "key.categories.berts_vehicle_pack.plane"
    const val HELICOPTER_CATEGORY = "key.categories.berts_vehicle_pack.helicopter"
    const val DRONE_CATEGORY = "key.categories.berts_vehicle_pack.drone"
    const val WATERCRAFT_CATEGORY = "key.categories.berts_vehicle_pack.watercraft"
    const val TACZ_CATEGORY = "key.category.tacz"
    const val LEGACY_SBW_CATEGORY = "key.categories.superbwarfare"

    private val mappings = mutableListOf<VehicleKeyMapping>()

    @JvmStatic
    fun register(mapping: VehicleKeyMapping): VehicleKeyMapping {
        mappings += mapping
        return mapping
    }

    @JvmStatic
    fun isActive(): Boolean = VehicleKeyConflictContext.isActive()

    /** Poll held vehicle actions by their saved binding even when another mapping shares the key. */
    @JvmStatic
    fun isPhysicallyHeld(mapping: VehicleKeyMapping): Boolean {
        if (!mapping.isConflictContextAndModifierActive()) return false
        if (com.atsuishio.superbwarfare.diagnostics.DiagnosticHeldKeys.isHeld(mapping)) return true
        val key = mapping.key
        if (key == InputConstants.UNKNOWN) return false
        val window = Minecraft.getInstance().window.window
        return when (key.type) {
            InputConstants.Type.KEYSYM -> InputConstants.isKeyDown(window, key.value)
            InputConstants.Type.MOUSE -> GLFW.glfwGetMouseButton(window, key.value) == GLFW.GLFW_PRESS
            else -> mapping.isDown
        }
    }

    @JvmStatic
    fun isVehicleMapping(mapping: KeyMapping): Boolean = mapping is VehicleKeyMapping

    /** The legacy SBW controls remain registered and saved, but are intentionally hidden. */
    @JvmStatic
    fun isVisibleInControls(mapping: KeyMapping): Boolean =
        mapping.category != LEGACY_SBW_CATEGORY

    @JvmStatic
    fun isManagedVehicleCategory(category: String): Boolean = when (category) {
        BVP_CATEGORY, LAND_CATEGORY, PLANE_CATEGORY, HELICOPTER_CATEGORY,
        DRONE_CATEGORY, WATERCRAFT_CATEGORY -> true
        else -> false
    }

    @JvmStatic
    fun isSameManagedCategory(first: KeyMapping, second: KeyMapping): Boolean =
        first.category == second.category && isManagedVehicleCategory(first.category)

    /** Controls duplicates are visible even when the two mappings use different runtime contexts. */
    @JvmStatic
    fun hasSameGroupKeyConflict(first: KeyMapping, second: KeyMapping): Boolean =
        isSameManagedCategory(first, second) && first.key != InputConstants.UNKNOWN &&
            first.key == second.key && first.keyModifier == second.keyModifier

    /** Only duplicates inside the same visible group participate in vehicle conflict warnings. */
    @JvmStatic
    fun shouldSuppressConflict(first: KeyMapping, second: KeyMapping): Boolean {
        if (first.category == LEGACY_SBW_CATEGORY || second.category == LEGACY_SBW_CATEGORY) return true
        return (isManagedVehicleCategory(first.category) || isManagedVehicleCategory(second.category)) &&
            first.category != second.category
    }

    class MovementMappings(
        val left: KeyMapping,
        val right: KeyMapping,
        val forward: KeyMapping,
        val backward: KeyMapping,
        val down: KeyMapping,
        val auxiliary: KeyMapping?,
    )

    private val land by lazy {
        MovementMappings(ModKeyMappings.MOVE_LEFT, ModKeyMappings.MOVE_RIGHT,
            ModKeyMappings.MOVE_FORWARD, ModKeyMappings.MOVE_BACKWARD,
            ModKeyMappings.MOVE_SHIFT, ModKeyMappings.MOVE_CTRL)
    }
    private val plane by lazy {
        MovementMappings(ModKeyMappings.FIXED_WING_ROLL_LEFT, ModKeyMappings.FIXED_WING_ROLL_RIGHT,
            ModKeyMappings.FLIGHT_THROTTLE_UP, ModKeyMappings.FLIGHT_THROTTLE_DOWN,
            ModKeyMappings.FLIGHT_THROTTLE_DOWN, null)
    }
    private val helicopter by lazy {
        MovementMappings(ModKeyMappings.HELICOPTER_ROLL_LEFT, ModKeyMappings.HELICOPTER_ROLL_RIGHT,
            ModKeyMappings.HELICOPTER_COLLECTIVE_UP, ModKeyMappings.HELICOPTER_COLLECTIVE_DOWN,
            ModKeyMappings.HELICOPTER_THRUST_UP, ModKeyMappings.HELICOPTER_THRUST_DOWN)
    }
    private val fixedWing by lazy {
        MovementMappings(ModKeyMappings.FIXED_WING_ROLL_LEFT, ModKeyMappings.FIXED_WING_ROLL_RIGHT,
            ModKeyMappings.FLIGHT_THROTTLE_UP, ModKeyMappings.FLIGHT_THROTTLE_DOWN,
            ModKeyMappings.FLIGHT_THROTTLE_DOWN, null)
    }
    private val drone by lazy {
        MovementMappings(ModKeyMappings.DRONE_LEFT, ModKeyMappings.DRONE_RIGHT,
            ModKeyMappings.DRONE_FORWARD, ModKeyMappings.DRONE_BACKWARD,
            ModKeyMappings.DRONE_DESCEND, null)
    }

    @JvmStatic
    fun movementMappings(profile: VehicleControlProfile): MovementMappings = when (profile) {
        VehicleControlProfile.LAND -> land
        VehicleControlProfile.PLANE ->
            if ((Minecraft.getInstance().player?.vehicle as? VehicleEntity)?.isFixedWingFlightVehicle() == true)
                fixedWing else plane
        VehicleControlProfile.HELICOPTER -> helicopter
        VehicleControlProfile.DRONE -> drone
    }

    /** Ordinary KeyMapping consumers yield only when an active vehicle binding owns this key. */
    @JvmStatic
    fun shouldSuppress(mapping: KeyMapping): Boolean {
        if (mapping is VehicleKeyMapping || !VehicleKeyConflictContext.isActive()) return false
        return mappings.any { it.claims(mapping.key) }
    }
}
