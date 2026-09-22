package com.atsuishio.superbwarfare.client.input;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.controls.KeyBindsList;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Persistent pitch preference in Plane Settings, independent of every key binding. */
public final class PlanePitchInversionEntry extends KeyBindsList.CategoryEntry {
    private final Button toggle;
    private final Button reset;

    public PlanePitchInversionEntry(KeyBindsList owner) {
        owner.super(Component.empty());
        toggle = Button.builder(Component.empty(), button -> {
            FixedWingPitchControl.setAndSave(!FixedWingPitchControl.isInverted());
            refresh();
        }).bounds(0, 0, 200, 20).build();
        toggle.setTooltip(Tooltip.create(Component.translatable(
                "config.superbwarfare.client.plane.invert_mouse_pitch.des")));
        reset = Button.builder(Component.translatable("controls.reset"), button -> {
            FixedWingPitchControl.setAndSave(FixedWingPitchControl.DEFAULT_INVERTED);
            refresh();
        }).bounds(0, 0, 70, 20).build();
        refresh();
    }

    private void refresh() {
        toggle.setMessage(Component.translatable("config.superbwarfare.client.plane.invert_mouse_pitch.value",
                Component.translatable(FixedWingPitchControl.isInverted() ? "options.on" : "options.off")));
        reset.active = FixedWingPitchControl.isInverted() != FixedWingPitchControl.DEFAULT_INVERTED;
    }

    @Override
    public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                       int mouseX, int mouseY, boolean hovered, float partialTick) {
        toggle.setX(left + 6);
        toggle.setY(top);
        toggle.setWidth(Math.max(120, width - 86));
        reset.setX(left + width - 74);
        reset.setY(top);
        refresh();
        toggle.render(graphics, mouseX, mouseY, partialTick);
        reset.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public List<? extends GuiEventListener> children() { return List.of(toggle, reset); }

    @Override
    public List<? extends NarratableEntry> narratables() { return List.of(toggle, reset); }

    @Override
    public ComponentPath nextFocusPath(FocusNavigationEvent event) {
        List<? extends GuiEventListener> controls = event instanceof FocusNavigationEvent.TabNavigation tab
                && !tab.forward() ? List.of(reset, toggle) : children();
        for (GuiEventListener control : controls) {
            ComponentPath path = control.nextFocusPath(event);
            if (path != null) return ComponentPath.path(this, path);
        }
        return null;
    }
}
