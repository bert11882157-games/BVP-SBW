package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.concurrent.CompletableFuture

/** Small resource-generation cache: CPU geometry work off-thread, texture ownership on render thread. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid=Mod.MODID,
    bus=net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD,value=[Dist.CLIENT])
object GroundVehicleHullHud {
    private val backendModels=HashMap<ResourceLocation,ResourceLocation>()
    private data class Entry(val future:CompletableFuture<VehicleHullRaster.Image?>,
                             var texture:ResourceLocation?=null)
    private val cache=LinkedHashMap<ResourceLocation,Entry>(16,0.75F,true)

    /** Backends identify their real source geometry instead of exposing the native fallback boxes. */
    @JvmStatic fun registerModel(backend:ResourceLocation,model:ResourceLocation) {
        if (backendModels.size<512 || backendModels.containsKey(backend)) backendModels[backend]=model
    }

    @SubscribeEvent fun reload(event:RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ResourceManagerReloadListener {
            Minecraft.getInstance().execute {
                for (entry in cache.values) close(entry)
                cache.clear()
            }
        })
    }

    private fun close(entry:Entry) {
        entry.future.cancel(false)
        entry.texture?.let { Minecraft.getInstance().textureManager.release(it) }
    }

    fun draw(g:GuiGraphics,vehicle:VehicleEntity):VehicleHullRaster.Image? {
        val model=VehicleResource.getDefault(vehicle).model
        val source=if(model.geometryBackend!=null) backendModels[model.geometryBackend] else model.model
        if(source==null) return null
        val minecraft=Minecraft.getInstance()
        val entry=cache.getOrPut(source) {
            if(cache.size>=64) {
                val oldest=cache.entries.iterator(); val value=oldest.next(); close(value.value); oldest.remove()
            }
            val resources=minecraft.resourceManager
            Entry(CompletableFuture.supplyAsync({
                try {
                    resources.getResource(source).orElseThrow().open().use { input ->
                        val bytes=input.readNBytes(64*1024*1024+1)
                        require(bytes.size<=64*1024*1024) { "Hull source exceeds size bound" }
                        VehicleHullRaster.create(JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject)
                    }
                } catch (failure:Exception) {
                    Mod.LOGGER.warn("Cannot load vehicle HUD hull {}",source,failure)
                    null
                }
            },Util.backgroundExecutor()))
        }
        val raster=entry.future.getNow(null) ?: return null
        if(entry.texture==null) {
            val image=NativeImage(raster.size,raster.size,false)
            for(y in 0 until raster.size) for(x in 0 until raster.size)
                image.setPixelRGBA(x,y,((raster.alpha[y*raster.size+x].toInt() and 255) shl 24) or 0xFFFFFF)
            val texture=DynamicTexture(image)
            texture.setFilter(true,false)
            entry.texture=minecraft.textureManager.register("vehicle_hud_hull",texture)
        }
        g.flush()
        RenderSystem.enableBlend()
        RenderSystem.setShaderColor(0.60F,0.66F,0.72F,0.85F)
        try { g.blit(entry.texture!!,-18,-18,0F,0F,36,36,36,36) }
        finally { RenderSystem.setShaderColor(1F,1F,1F,1F) }
        if (EliteDiagnostics.isClientEnabled()) EliteDiagnostics.recordClient(
            vehicle.level().gameTime, "ground_hud", "hull_draw", "vehicle", vehicle.uuid,
            "model", source, "texture", entry.texture, "pixels", raster.size,
            "polygons", raster.polygonCount)
        return raster
    }
}
