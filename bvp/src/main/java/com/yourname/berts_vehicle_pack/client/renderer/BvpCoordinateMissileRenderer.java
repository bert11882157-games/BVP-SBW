package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.aircraft.AircraftStoreItemRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;

/** Optional client presentation entry point for FFA's vehicle-launched missile tracks. */
public final class BvpCoordinateMissileRenderer {
    private static final ResourceLocation KH55_MODEL = new ResourceLocation(
            "berts_vehicle_pack", "custom_geo/projectiles/kh55.geo.json");
    private static final ResourceLocation KH55_TEXTURE = new ResourceLocation(
            "berts_vehicle_pack", "textures/aircraft_stores/kh55.png");
    private static final ResourceLocation SLAM_ER_MODEL = new ResourceLocation(
            "berts_vehicle_pack", "custom_geo/aircraft_stores/agm84k_slam_er.geo.json");
    private static final ResourceLocation SLAM_ER_TEXTURE = new ResourceLocation(
            "berts_vehicle_pack", "textures/aircraft_stores/agm84k_slam_er.png");
    private static final MissileAsset KH55 = new MissileAsset(KH55_MODEL, KH55_TEXTURE, "KH-55");
    private static final MissileAsset SLAM_ER = new MissileAsset(SLAM_ER_MODEL, SLAM_ER_TEXTURE, "AGM-84K SLAM-ER");

    private BvpCoordinateMissileRenderer() { }

    /**
     * Render-thread only. The incoming pose has +Y along flight and its origin at the nozzle.
     * Returns false while the optional model is unavailable so FFA can draw its native fallback.
     */
    public static boolean render(String model, PoseStack pose, MultiBufferSource buffers, int light) {
        MissileAsset asset = switch (model) {
            case "kh55" -> KH55;
            case "agm84k_slam_er" -> SLAM_ER;
            default -> null;
        };
        if (asset == null) return false;
        PolyMeshModel mesh = asset.ready();
        if (mesh == null) return false;
        pose.m_85836_();
        try {
            if (asset == SLAM_ER) {
                // The supplied Blockbench body is +Z forward and centered at the store mount.
                // FFA's incoming pose is +Y forward with its origin at the missile nozzle.
                pose.m_252880_(0.0F, 2.2F, 0.0F);
                pose.m_252781_(Axis.f_252529_.m_252977_(-90.0F));
            }
            mesh.renderCutoutOnly(pose, buffers, asset.texture, light, 1.0F);
            mesh.renderTranslucentOnly(pose, buffers, asset.texture, light, 1.0F);
        } finally {
            pose.m_85849_();
        }
        return true;
    }

    /** Reuse the bounded model queue and resource-reload lifetime used by suspended stores. */
    private static final class MissileAsset extends BaseVehicleRenderer<GeoVehicleEntity> {
        private final ResourceLocation texture;
        private long textureGeneration = -1;
        private boolean texturePresent;

        private MissileAsset(ResourceLocation model, ResourceLocation texture, String debugName) {
            super(null, model, texture, debugName);
            this.texture = texture;
        }

        private PolyMeshModel ready() {
            long generation = currentResourceGeneration();
            if (textureGeneration != generation) {
                textureGeneration = generation;
                texturePresent = AircraftStoreItemRenderer.resourceExists(texture);
            }
            return texturePresent ? getOrLoadModel() : null;
        }
    }
}
