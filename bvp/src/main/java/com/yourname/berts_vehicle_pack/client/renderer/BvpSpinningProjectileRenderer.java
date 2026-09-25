package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.entity.projectile.BasicGeoProjectileEntity;
import com.atsuishio.superbwarfare.client.ClientRenderHandler;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
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
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class BvpSpinningProjectileRenderer<T extends Entity> extends EntityRenderer<T> {
    private final ResourceLocation modelLocation;
    private final ResourceLocation textureLocation;
    private final float spinDegreesPerTick;
    private final float modelForwardYawDegrees;
    private final Map<ResourceLocation, PolyMeshModel> models = new HashMap<>();
    private final Set<ResourceLocation> failedModels = new HashSet<>();
    private final Map<Entity, Double> diagnosticSamples = new WeakHashMap<>();
    private static final ResourceLocation MESH_EXTENSION = new ResourceLocation("berts_vehicle_pack", "projectile_mesh_v1");
    private final Map<com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile, MeshPresentation> presentations = new WeakHashMap<>();
    private record MeshPresentation(ResourceLocation model, ResourceLocation texture, float yaw, float spin,
                                    boolean forwardY, boolean tailOrigin) { }

    private MeshPresentation presentation(T entity) {
        var profile = com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles.resolve(entity);
        if (profile == null) return null;
        if (presentations.containsKey(profile)) return presentations.get(profile);
        MeshPresentation result = null;
        try {
            var extension = profile.extension(MESH_EXTENSION);
            if (extension != null && extension.isJsonObject()) {
                var json = extension.getAsJsonObject();
                var model = ResourceLocation.m_135820_(json.get("Model").getAsString());
                var texture = ResourceLocation.m_135820_(json.get("Texture").getAsString());
                float yaw = json.has("ForwardYaw") ? json.get("ForwardYaw").getAsFloat() : 0F;
                float spin = json.has("SpinDegreesPerTick") ? json.get("SpinDegreesPerTick").getAsFloat() : 0F;
                String forward = json.has("ForwardAxis") ? json.get("ForwardAxis").getAsString() : "-Z";
                String origin = json.has("Origin") ? json.get("Origin").getAsString() : "CENTER";
                if (model != null && texture != null && Float.isFinite(yaw) && Math.abs(yaw) <= 360F
                        && Float.isFinite(spin) && Math.abs(spin) <= 720F
                        && (forward.equals("-Z") || forward.equals("Y"))
                        && (origin.equals("CENTER") || origin.equals("TAIL")))
                    result = new MeshPresentation(model, texture, yaw, spin, forward.equals("Y"), origin.equals("TAIL"));
            }
        } catch (RuntimeException invalid) { /* Invalid optional art retains the native renderer. */ }
        presentations.put(profile, result);
        return result;
    }

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

        // Heading blended between ticks (wrap-aware yaw), so guided rounds turn smoothly instead of once per tick.
        Vec3 look = Vec3.m_82498_(Mth.m_14179_(partialTicks, entity.f_19860_, entity.m_146909_()),
                Mth.m_14189_(partialTicks, entity.f_19859_, entity.m_146908_()));
        if (look.m_82556_() < 1.0E-6D) {
            look = entity.m_20154_();
        }
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
            MeshPresentation authored = presentation(entity);
            if (authored == null || !authored.tailOrigin)
                poseStack.m_252880_(0.0F, entity.m_20206_() / 2.0F, 0.0F);
            poseStack.m_252781_(Axis.f_252436_.m_252977_((float) VehicleVecUtils.getYRotFromVector(look)));
            poseStack.m_252781_(Axis.f_252529_.m_252977_((float) -VehicleVecUtils.getXRotFromVector(look) + 180.0F));
            float spin = authored == null ? spinDegreesPerTick : authored.spin;
            float forwardYaw = authored == null ? modelForwardYawDegrees : authored.yaw;
            poseStack.m_252781_(Axis.f_252403_.m_252977_((entity.f_19797_ + partialTicks) * spin));
            if (forwardYaw != 0.0F) {
                poseStack.m_252781_(Axis.f_252436_.m_252977_(forwardYaw));
            }
            if (authored != null && authored.forwardY)
                poseStack.m_252781_(Axis.f_252529_.m_252977_(-90.0F));
            var profile = com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles.resolve(entity);
            float scale = profile == null ? 1.0F : profile.getRenderScale();
            if (Float.isFinite(scale) && scale > 0.0F) poseStack.m_85841_(scale, scale, scale);
            loadedModel.renderWithTranslucentSplit(poseStack, bufferSource, renderTextureLocation, packedLight);
            recordRendered(entity, partialTicks);
        } finally {
            poseStack.m_85849_();
        }
        return true;
    }

    private void recordRendered(T entity, float partialTick) {
        if (!Boolean.getBoolean("bvp.diagnostics.scenarios") || !EliteDiagnostics.isClientEnabled()) return;
        double tick = entity.m_9236_().m_46467_() + (double) partialTick;
        if (tick - diagnosticSamples.getOrDefault(entity, Double.NEGATIVE_INFINITY) < 0.25) return;
        diagnosticSamples.put(entity, tick);
        EliteDiagnostics.record(entity, "elite_flight", "PROJECTILE_RENDERED",
                "render_tick", tick, "partial_tick", partialTick, "position", entity.m_20318_(partialTick));
    }

    protected ResourceLocation modelLocation(T entity) {
        MeshPresentation authored = presentation(entity);
        return authored == null ? modelLocation : authored.model;
    }

    protected ResourceLocation textureLocation(T entity) {
        MeshPresentation authored = presentation(entity);
        return authored == null ? textureLocation : authored.texture;
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
        if (models.size() + failedModels.size() >= 64) return null;
        PolyMeshModel loaded = PolyMeshLoader.loadModel(location);
        if (loaded == null) {
            this.failedModels.add(location);
        } else {
            this.models.put(location, loaded);
        }
        return loaded;
    }
}
