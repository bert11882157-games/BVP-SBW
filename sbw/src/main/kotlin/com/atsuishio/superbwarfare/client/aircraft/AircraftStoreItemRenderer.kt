package com.atsuishio.superbwarfare.client.aircraft

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.registries.ForgeRegistries

/** Static standard baked items only: never dispatch a custom live-entity item renderer. */
object AircraftStoreItemRenderer {
    private data class Asset(val stack: ItemStack, val model: BakedModel)
    private val assets = LinkedHashMap<ResourceLocation, Asset?>()
    @JvmStatic fun resourceExists(id: ResourceLocation): Boolean =
        Minecraft.getInstance().resourceManager.getResource(id).isPresent
    @JvmStatic fun clear() = assets.clear()

    @JvmStatic fun render(id: ResourceLocation, pose: PoseStack, buffers: MultiBufferSource, light: Int): Boolean {
        val mc = Minecraft.getInstance()
        if (!assets.containsKey(id)) {
            if (assets.size >= 64) return false
            val item = ForgeRegistries.ITEMS.getValue(id)
            val stack = if (item == null || item === Items.AIR) ItemStack.EMPTY else ItemStack(item)
            val model = if (stack.isEmpty) null else mc.itemRenderer.getModel(stack, mc.level, null, 0)
            assets[id] = if (model == null || model.isCustomRenderer) null else Asset(stack, model)
        }
        val asset = assets[id] ?: return false
        mc.itemRenderer.render(asset.stack, ItemDisplayContext.FIXED, false, pose, buffers,
            light, OverlayTexture.NO_OVERLAY, asset.model)
        return true
    }
}
