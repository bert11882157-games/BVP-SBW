package com.atsuishio.superbwarfare.compat.clothconfig.client

import com.atsuishio.superbwarfare.config.client.ControlConfig
import com.atsuishio.superbwarfare.client.input.FixedWingJoystickSensitivity
import com.atsuishio.superbwarfare.client.input.FixedWingPitchControl
import com.atsuishio.superbwarfare.client.input.VehicleControlBindings
import com.atsuishio.superbwarfare.client.camera.FixedWingDynamicCamera
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder
import net.minecraft.network.chat.Component

object ControlClothConfig {
    fun init(root: ConfigBuilder, entryBuilder: ConfigEntryBuilder) {
        val category = root.getOrCreateCategory(Component.translatable("config.superbwarfare.client.control"))

        category.addEntry(
            entryBuilder
                .startBooleanToggle(
                    Component.translatable("config.superbwarfare.client.control.invert_aircraft_control"),
                    ControlConfig.INVERT_AIRCRAFT_CONTROL.get()
                )
                .setDefaultValue(true)
                .setSaveConsumer { ControlConfig.INVERT_AIRCRAFT_CONTROL.set(it) }
                .setTooltip(Component.translatable("config.superbwarfare.client.control.invert_aircraft_control.des"))
                .build()
        )

        category.addEntry(
            entryBuilder
                .startIntSlider(
                    Component.translatable("config.superbwarfare.client.control.mouse_sensitivity"),
                    ControlConfig.MOUSE_SENSITIVITY.get(),
                    10,
                    200
                )
                .setDefaultValue(100)
                .setSaveConsumer { ControlConfig.MOUSE_SENSITIVITY.set(it) }
                .setTooltip(Component.translatable("config.superbwarfare.client.control.mouse_sensitivity.des")).build()
        )

        root.getOrCreateCategory(Component.translatable(VehicleControlBindings.PLANE_CATEGORY)).addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable("config.superbwarfare.client.plane.invert_mouse_pitch"),
                FixedWingPitchControl.isInverted(),
            ).setDefaultValue(FixedWingPitchControl.DEFAULT_INVERTED)
                .setSaveConsumer { FixedWingPitchControl.setAndSave(it) }
                .setTooltip(Component.translatable("config.superbwarfare.client.plane.invert_mouse_pitch.des"))
                .build()
        )

        root.getOrCreateCategory(Component.translatable(VehicleControlBindings.PLANE_CATEGORY)).addEntry(
            entryBuilder.startIntSlider(
                Component.translatable("config.superbwarfare.client.plane.joystick_sensitivity"),
                (FixedWingJoystickSensitivity.get() * 100).toInt(), 10, 200,
            ).setDefaultValue(100)
                .setSaveConsumer { FixedWingJoystickSensitivity.setAndSave(it / 100.0) }
                .setTooltip(Component.translatable("config.superbwarfare.client.plane.joystick_sensitivity.des"))
                .build()
        )
        root.getOrCreateCategory(Component.translatable(VehicleControlBindings.PLANE_CATEGORY)).addEntry(
            entryBuilder.startIntSlider(
                Component.translatable("config.superbwarfare.client.plane.dynamic_camera"),
                (FixedWingDynamicCamera.getStrength() * 100).toInt(), 0, 100,
            ).setDefaultValue(50)
                .setSaveConsumer { FixedWingDynamicCamera.setStrength(it / 100.0) }
                .setTooltip(Component.translatable("config.superbwarfare.client.plane.dynamic_camera.des"))
                .build()
        )
    }
}
