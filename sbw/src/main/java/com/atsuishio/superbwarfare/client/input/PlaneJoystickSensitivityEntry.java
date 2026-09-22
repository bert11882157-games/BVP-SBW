package com.atsuishio.superbwarfare.client.input;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.controls.KeyBindsList;
import net.minecraft.network.chat.Component;
import com.atsuishio.superbwarfare.client.camera.FixedWingDynamicCamera;

import java.util.List;
import java.util.Locale;

/** A real persisted setting row inside Plane Settings; it does not register a key or action. */
public final class PlaneJoystickSensitivityEntry extends KeyBindsList.CategoryEntry {
    private final SensitivitySlider slider;
    private final Button reset;
    private final boolean cameraMotion;

    public PlaneJoystickSensitivityEntry(KeyBindsList owner) {
        this(owner, false);
    }

    public PlaneJoystickSensitivityEntry(KeyBindsList owner, boolean cameraMotion) {
        owner.super(Component.empty());
        this.cameraMotion = cameraMotion;
        slider = new SensitivitySlider();
        reset = Button.builder(Component.translatable("controls.reset"), button -> {
            if (cameraMotion) FixedWingDynamicCamera.setStrength(0.5);
            else FixedWingJoystickSensitivity.setAndSave(FixedWingJoystickSensitivity.DEFAULT);
            slider.refresh();
        }).bounds(0, 0, 70, 20).build();
    }

    @Override
    public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                       int mouseX, int mouseY, boolean hovered, float partialTick) {
        slider.setX(left + 6);
        slider.setY(top);
        slider.setWidth(Math.max(120, width - 86));
        reset.setX(left + width - 74);
        reset.setY(top);
        reset.active = cameraMotion ? FixedWingDynamicCamera.getStrength() != 0.5
                : FixedWingJoystickSensitivity.get() != FixedWingJoystickSensitivity.DEFAULT;
        slider.render(graphics, mouseX, mouseY, partialTick);
        reset.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public List<? extends GuiEventListener> children() { return List.of(slider, reset); }

    @Override
    public List<? extends NarratableEntry> narratables() { return List.of(slider, reset); }

    @Override
    public ComponentPath nextFocusPath(FocusNavigationEvent event) {
        List<? extends GuiEventListener> controls = event instanceof FocusNavigationEvent.TabNavigation tab
                && !tab.forward() ? List.of(reset, slider) : children();
        for (GuiEventListener control : controls) {
            ComponentPath path = control.nextFocusPath(event);
            if (path != null) return ComponentPath.path(this, path);
        }
        return null;
    }

    private final class SensitivitySlider extends AbstractSliderButton {
        private SensitivitySlider() {
            super(0, 0, 200, 20, Component.empty(), 0.0);
            setTooltip(Tooltip.create(Component.translatable(
                    cameraMotion ? "config.superbwarfare.client.plane.dynamic_camera.des"
                            : "config.superbwarfare.client.plane.joystick_sensitivity.des")));
            refresh();
        }

        private void refresh() {
            value = cameraMotion ? FixedWingDynamicCamera.getStrength()
                    : (FixedWingJoystickSensitivity.get() - FixedWingJoystickSensitivity.MINIMUM)
                    / (FixedWingJoystickSensitivity.MAXIMUM - FixedWingJoystickSensitivity.MINIMUM);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(cameraMotion ? Component.translatable("config.superbwarfare.client.plane.dynamic_camera.value",
                    Math.round(FixedWingDynamicCamera.getStrength() * 100))
                    : Component.translatable("config.superbwarfare.client.plane.joystick_sensitivity.value",
                    String.format(Locale.ROOT, "%.2f", FixedWingJoystickSensitivity.get())));
        }

        @Override
        protected void applyValue() {
            if (cameraMotion) {
                FixedWingDynamicCamera.setStrength(Math.round(value * 100.0) / 100.0);
                return;
            }
            double multiplier = FixedWingJoystickSensitivity.MINIMUM
                    + value * (FixedWingJoystickSensitivity.MAXIMUM - FixedWingJoystickSensitivity.MINIMUM);
            FixedWingJoystickSensitivity.setAndSave(Math.round(multiplier * 100.0) / 100.0);
        }
    }
}
