package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.entity.projectile.BasicGeoProjectileEntity;
import com.atsuishio.superbwarfare.client.ClientRenderHandler;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils;
import com.example.sbwmeshloader.core.PolyMeshLoader;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class BvpSpinningProjectileRenderer<T extends Entity> extends EntityRenderer<T> {
    private final ResourceLocation modelLocation;
    private final ResourceLocation textureLocation;
    private final float spinDegreesPerTick;
    private final float modelForwardYawDegrees;
    private final Map<ResourceLocation, PolyMeshModel> models = new HashMap<>();
    private final Set<ResourceLocation> failedModels = new HashSet<>();

    public BvpSpinningProjectileRenderer(EntityRendererProvider.Context context, ResourceLocation modelLocation,
                                         ResourceLocation textureLocation, float spinDegreesPerTick) {
        this(context, modelLocation, textureLocation, spinDegreesPerTick, 0.0F);
    }

    public BvpSpinningProjectileRenderer(EntityRendererProvider.Context context, ResourceLocation modelLocation,
                                         ResourceLocation textureLocation, float spinDegreesPerTick,
                                         float modelForwardYawDegrees) {
        super(context);
        this.modelLocation = modelLocation;
        this.textureLocation = textureLocation;
        this.spinDegreesPerTick = spinDegreesPerTick;
        this.modelForwardYawDegrees = modelForwardYawDegrees;
    }

    @Override
    public ResourceLocation m_5478_(T entity) {
        return textureLocation(entity);
    }

    @Override
    protected boolean m_6512_(T entity) {
        return false;
    }

    @Override
    public void m_7392_(T entity, float entityYaw, float partialTicks,
                        PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (!renderProfile(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight)) {
            super.m_7392_(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight);
        }
    }

    public boolean renderProfile(T entity, float entityYaw, float partialTicks,
                                 PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (entity instanceof BasicGeoProjectileEntity basic && entity.f_19797_ <= basic.getHiddenTicks()) {
            return true;
        }
        ResourceLocation renderModelLocation = modelLocation(entity);
        ResourceLocation renderTextureLocation = textureLocation(entity, renderModelLocation);
        PolyMeshModel loadedModel = getOrLoadModel(renderModelLocation);
        if (loadedModel == null) {
            return false;
        }

        Vec3 look = entity.m_20154_();
        if (look.m_82556_() < 1.0E-6D) {
            look = entity.m_20184_();
        }
        if (look.m_82556_() < 1.0E-6D) {
            look = new Vec3(0.0D, 0.0D, -1.0D);
        }

        poseStack.m_85836_();
        try {
            if (entity instanceof Projectile projectile) {
                ClientRenderHandler.markBulletRenderVisible(projectile, partialTicks);
                ClientRenderHandler.transformVirtualRenderPosition(poseStack, projectile, partialTicks);
            }
            poseStack.m_252880_(0.0F, entity.m_20206_() / 2.0F, 0.0F);
            poseStack.m_252781_(Axis.f_252436_.m_252977_((float) VehicleVecUtils.getYRotFromVector(look)));
            poseStack.m_252781_(Axis.f_252529_.m_252977_((float) -VehicleVecUtils.getXRotFromVector(look) + 180.0F));
            poseStack.m_252781_(Axis.f_252403_.m_252977_((entity.f_19797_ + partialTicks) * this.spinDegreesPerTick));
            if (this.modelForwardYawDegrees != 0.0F) {
                poseStack.m_252781_(Axis.f_252436_.m_252977_(this.modelForwardYawDegrees));
            }
            loadedModel.renderWithTranslucentSplit(poseStack, bufferSource, renderTextureLocation, packedLight);
        } finally {
            poseStack.m_85849_();
        }
        return true;
    }

    protected ResourceLocation modelLocation(T entity) {
        return this.modelLocation;
    }

    protected ResourceLocation textureLocation(T entity) {
        return this.textureLocation;
    }

    protected ResourceLocation textureLocation(T entity, ResourceLocation resolvedModelLocation) {
        return textureLocation(entity);
    }

    private PolyMeshModel getOrLoadModel(ResourceLocation location) {
        if (location == null) {
            return null;
        }
        PolyMeshModel cached = this.models.get(location);
        if (cached != null || this.failedModels.contains(location)) {
            return cached;
        }
        PolyMeshModel loaded = PolyMeshLoader.loadModel(location);
        if (loaded == null) {
            this.failedModels.add(location);
        } else {
            this.models.put(location, loaded);
        }
        return loaded;
    }
}
