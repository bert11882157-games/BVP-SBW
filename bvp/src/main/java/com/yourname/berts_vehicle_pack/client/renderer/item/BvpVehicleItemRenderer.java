package com.yourname.berts_vehicle_pack.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.item.BvpVehicleItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight BVP vehicle item icon.
 *
 * <p>Inventory, HUD, hand, ground, and item-frame draws use the imported TaP icon and never load,
 * upload, cache, or render a second copy of the complete vehicle mesh. The real entity renderer is
 * therefore the sole owner of vehicle geometry.</p>
 */
public final class BvpVehicleItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final float GUI_SCALE = 1.0F;
    private static final float WORLD_SCALE = 0.72F;
    private static final Map<ResourceLocation, ResourceLocation> ICON_LOCATIONS =
            new ConcurrentHashMap<>();

    public BvpVehicleItemRenderer() {
        super(
                Minecraft.m_91087_().m_167982_(),
                Minecraft.m_91087_().m_167973_()
        );
    }

    @Override
    public void m_108829_(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                          MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (displayContext == null || poseStack == null || bufferSource == null) {
            return;
        }

        ResourceLocation typeId = BvpVehicleItem.getEntityTypeId(stack).orElse(null);
        if (typeId == null || !BertsVehiclePack.MODID.equals(typeId.m_135827_())) {
            return;
        }

        ResourceLocation icon = ICON_LOCATIONS.computeIfAbsent(typeId, id ->
                new ResourceLocation(BertsVehiclePack.MODID,
                        "textures/item/vehicle_icons/" + id.m_135815_() + ".png"));
        VertexConsumer consumer = bufferSource.m_6299_(RenderType.m_110458_(icon));
        boolean gui = displayContext == ItemDisplayContext.GUI;
        float scale = gui ? GUI_SCALE : WORLD_SCALE;

        poseStack.m_85836_();
        poseStack.m_85837_(0.5D, 0.5D, 0.5D);
        poseStack.m_85841_(scale, scale, scale);
        PoseStack.Pose pose = poseStack.m_85850_();
        Matrix4f matrix = pose.m_252922_();
        Matrix3f normal = pose.m_252943_();
        vertex(consumer, matrix, normal, -0.5F, -0.5F, 0.0F, 0.0F, 1.0F,
                packedLight, packedOverlay);
        vertex(consumer, matrix, normal, 0.5F, -0.5F, 0.0F, 1.0F, 1.0F,
                packedLight, packedOverlay);
        vertex(consumer, matrix, normal, 0.5F, 0.5F, 0.0F, 1.0F, 0.0F,
                packedLight, packedOverlay);
        vertex(consumer, matrix, normal, -0.5F, 0.5F, 0.0F, 0.0F, 0.0F,
                packedLight, packedOverlay);
        poseStack.m_85849_();
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                               float x, float y, float z, float u, float v,
                               int packedLight, int packedOverlay) {
        consumer.m_252986_(matrix, x, y, z)
                .m_85950_(1.0F, 1.0F, 1.0F, 1.0F)
                .m_7421_(u, v)
                .m_86008_(packedOverlay)
                .m_85969_(packedLight)
                .m_252939_(normal, 0.0F, 0.0F, 1.0F)
                .m_5752_();
    }
}
