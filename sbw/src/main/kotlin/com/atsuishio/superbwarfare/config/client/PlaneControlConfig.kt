package com.atsuishio.superbwarfare.config.client

import com.atsuishio.superbwarfare.config.buildClientConfig

/** Registered before legacy config groups so this path is independent of their builder scopes. */
object PlaneControlConfig {
    @JvmField
    val ROLL_BINDINGS_CORRECTED = buildClientConfig {
        push("control")
        push("plane")
        comment("One-time correction of the reversed fixed-wing A/D defaults; custom bindings are retained")
        define("roll_bindings_corrected", false).also { pop(); pop() }
    }
    @JvmField
    val DYNAMIC_CAMERA_STRENGTH = buildClientConfig {
        push("control")
        push("plane")
        comment("Camera chase movement magnitude in first/third person; 0 disables chase, 1 gives full movement; cockpit position is unaffected")
        defineInRange("dynamic_camera_strength", 0.5, 0.0, 1.0).also { pop(); pop() }
    }
    @JvmField
    val INVERT_POINTING_PITCH = buildClientConfig {
        push("control")
        push("plane")
        comment("Invert world-direction mouse aiming; false means mouse up moves the aim marker up")
        define("invert_pointing_pitch", false).also { pop(); pop() }
    }

    @JvmField
    val DIRECTIONAL_BINDINGS_MIGRATED = buildClientConfig {
        push("control")
        push("plane")
        comment("One-time migration of the legacy airbrake binding to fixed-wing directional controls")
        define("directional_bindings_migrated", false).also { pop(); pop() }
    }

    @JvmField
    val INVERT_MOUSE_PITCH = buildClientConfig {
        push("control")
        push("plane")
        comment("Invert fixed-wing mouse pitch: forward/up lowers the nose; back/down raises it")
        define("invert_mouse_pitch", true).also {
            pop()
            pop()
        }
    }

    @JvmField
    val JOYSTICK_SENSITIVITY = buildClientConfig {
        push("control")
        push("plane")
        comment("Fixed-wing mouse-aim multiplier; does not change camera or other vehicle sensitivity")
        defineInRange("joystick_sensitivity", 1.0, 0.1, 2.0).also {
            pop()
            pop()
        }
    }
}
