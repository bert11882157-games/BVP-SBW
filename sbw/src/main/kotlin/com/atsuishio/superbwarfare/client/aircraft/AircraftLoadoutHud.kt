package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.Mod
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderGuiEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber

/** Keep the live aircraft visible while the equipment editor owns the screen's controls. */
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AircraftLoadoutHud {
    @SubscribeEvent fun beforeHud(event: RenderGuiEvent.Pre) {
        if (Minecraft.getInstance().screen is AircraftLoadoutScreen) event.isCanceled = true
    }
}
