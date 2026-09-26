package com.atsuishio.superbwarfare.client.particle

import com.atsuishio.superbwarfare.Mod
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterShadersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber

/** Registers `superbwarfare:blast`, the premultiplied-alpha sprite shader used by [BlastEffects] and afterburners. */
@EventBusSubscriber(modid = Mod.MODID, bus = EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object BlastShaders {
    @SubscribeEvent
    fun register(event: RegisterShadersEvent) {
        event.registerShader(ShaderInstance(event.resourceProvider, ResourceLocation(Mod.MODID, "blast"),
            DefaultVertexFormat.PARTICLE)) { BlastEffects.shader = it }
        event.registerShader(ShaderInstance(event.resourceProvider, ResourceLocation(Mod.MODID, "heat_haze"),
            DefaultVertexFormat.POSITION_TEX_COLOR)) { AfterburnerPlumes.shader = it }
    }
}
