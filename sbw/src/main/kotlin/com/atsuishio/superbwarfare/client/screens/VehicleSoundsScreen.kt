package com.atsuishio.superbwarfare.client.screens

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.client.sound.VehicleAudioMix
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.SoundOptionsScreen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import kotlin.math.roundToInt

/**
 * Options > Music & Sounds > Vehicle Sounds (owner 2026-09-29): one slider per part of the vehicle mix
 * ([VehicleAudioMix]), 0-200 % of the shipped balance, applied to sounds already playing. They sit on top of
 * Minecraft's Master and category sliders.
 *
 * Since 2026-10-01 the same sliders also sit directly in Minecraft's Music & Sounds list ([mainListOptions],
 * SoundOptionsScreenMixin), so they are seen without opening this screen; this screen stays for its reset button.
 */
class VehicleSoundsScreen(private val parent: Screen?) : Screen(Component.literal("Vehicle Sounds")) {
    private val sliders = ArrayList<LevelSlider>()

    override fun init() {
        sliders.clear()
        val x = width / 2 - 155
        var y = height / 6 - 12
        for ((i, category) in VehicleAudioMix.Category.entries.withIndex()) {
            val slider = LevelSlider(if (i % 2 == 0) x else x + 160, y, category)
            sliders.add(slider)
            addRenderableWidget(slider)
            if (i % 2 == 1) y += 24
        }
        y += 36
        addRenderableWidget(Button.builder(Component.literal("Reset to balanced")) {
            for (category in VehicleAudioMix.Category.entries) VehicleAudioMix.setLevel(category, 1f)
            for (slider in sliders) slider.refresh()
        }.bounds(width / 2 - 155, y, 150, 20)
            .tooltip(Tooltip.create(Component.literal("Every slider back to 100 % (the mod's own balance)")))
            .build())
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }
            .bounds(width / 2 + 5, y, 150, 20).build())
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics)
        graphics.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF)
        graphics.drawCenteredString(font, Component.literal("100 % is the balanced mix; these multiply Master and " +
            "the Minecraft sound sliders."), width / 2, height / 6 - 30, 0xA0A0A0)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun removed() {
        VehicleAudioMix.save()
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    private class LevelSlider(x: Int, y: Int, private val category: VehicleAudioMix.Category) :
        AbstractSliderButton(x, y, 150, 20, Component.empty(),
            (VehicleAudioMix.level(category) / VehicleAudioMix.MAX_LEVEL).toDouble()) {
        init {
            updateMessage()
        }

        fun refresh() {
            value = (VehicleAudioMix.level(category) / VehicleAudioMix.MAX_LEVEL).toDouble()
            updateMessage()
        }

        override fun updateMessage() {
            val percent = (value * VehicleAudioMix.MAX_LEVEL * 100).roundToInt()
            setMessage(Component.literal("${category.label}: " + if (percent == 0) "OFF" else "$percent%"))
        }

        override fun applyValue() {
            // 5 % steps
            val level = ((value * VehicleAudioMix.MAX_LEVEL * 20).roundToInt() / 20f)
            VehicleAudioMix.setLevel(category, level)
        }
    }

    companion object {
        /** Slider steps: 0..40 = 0..200 % in 5 % steps, the same grid as [LevelSlider]. */
        private const val STEPS_PER_LEVEL = 20

        /**
         * The same five sliders as vanilla [OptionInstance]s, for Minecraft's own Music & Sounds list
         * (SoundOptionsScreenMixin). Built fresh on every init so they show values changed on this screen.
         * Same storage as the screen: [VehicleAudioMix.setLevel], saved to the mix's config file on every change
         * (vanilla saves options.txt on every slider move the same way).
         */
        @JvmStatic
        fun mainListOptions(): Array<OptionInstance<*>> = VehicleAudioMix.Category.entries.map { category ->
            val key = "options.superbwarfare.vehicle_sounds.${category.key}"
            OptionInstance(
                key,
                OptionInstance.cachedConstantTooltip(Component.translatable("$key.tooltip")),
                { caption, steps: Int ->
                    if (steps == 0) Options.genericValueLabel(caption, CommonComponents.OPTION_OFF)
                    else Component.translatable("options.percent_value", caption, steps * 100 / STEPS_PER_LEVEL)
                },
                OptionInstance.IntRange(0, (VehicleAudioMix.MAX_LEVEL * STEPS_PER_LEVEL).roundToInt()),
                (VehicleAudioMix.level(category) * STEPS_PER_LEVEL).roundToInt(),
            ) { steps ->
                VehicleAudioMix.setLevel(category, steps / STEPS_PER_LEVEL.toFloat())
                VehicleAudioMix.save()
            }
        }.toTypedArray()
    }

    /** Adds the "Vehicle Sounds..." button to Minecraft's Music & Sounds screen, left of Done. */
    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
    object Entry {
        @SubscribeEvent
        fun init(event: ScreenEvent.Init.Post) {
            val screen = event.screen as? SoundOptionsScreen ?: return
            val done = event.listenersList.filterIsInstance<Button>()
                .firstOrNull { it.message == CommonComponents.GUI_DONE }
            val y = done?.y ?: (screen.height - 27)
            done?.let {
                it.x = screen.width / 2 + 5
                it.width = 150
            }
            event.addListener(Button.builder(Component.literal("Vehicle Sounds...")) {
                net.minecraft.client.Minecraft.getInstance().setScreen(VehicleSoundsScreen(screen))
            }.bounds(screen.width / 2 - 155, y, 150, 20)
                .tooltip(Tooltip.create(Component.literal(
                    "Ground and aircraft engines, weapons and aircraft effects (Superb Warfare)")))
                .build())
        }
    }
}
