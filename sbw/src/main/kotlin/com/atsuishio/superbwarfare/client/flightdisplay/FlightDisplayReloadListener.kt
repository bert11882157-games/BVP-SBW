package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.Mod
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber

/** The resource reload apply executor is the client/render thread, before world rendering resumes. */
@EventBusSubscriber(modid = Mod.MODID, bus = EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object FlightDisplayReloadListener {
    @SubscribeEvent
    fun register(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ResourceManagerReloadListener {
            RenderSystem.assertOnRenderThread()
            CanopyGlass.resetResources()
            HeadUpDisplay.resetResources()
        })
    }
}
