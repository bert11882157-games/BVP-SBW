package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.entity.effect.TransientLightEntity
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu

class TransientLightRenderer(context: EntityRendererProvider.Context) :
    EntityRenderer<TransientLightEntity>(context) {
    override fun getTextureLocation(entity: TransientLightEntity): ResourceLocation = InventoryMenu.BLOCK_ATLAS

    override fun shouldShowName(entity: TransientLightEntity): Boolean = false
}
