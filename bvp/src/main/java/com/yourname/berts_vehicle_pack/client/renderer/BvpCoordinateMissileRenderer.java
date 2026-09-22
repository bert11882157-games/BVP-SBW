package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.aircraft.AircraftStoreItemRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;

/** Optional client presentation entry point for FFA's vehicle-launched missile tracks. */
public final class BvpCoordinateMissileRenderer {
    private static final ResourceLocation MODEL = new ResourceLocation(
            "berts_vehicle_pack", "custom_geo/projectiles/kh29l.geo.json");
    private static final ResourceLocation TEXTURE = new ResourceLocation(
            "berts_vehicle_pack", "textures/aircraft_stores/kh29l.png");
    private static final MissileAsset KH55 = new MissileAsset();

    private BvpCoordinateMissileRenderer() { }

    /**
     * Render-thread only. The incoming pose has +Y along flight and its origin at the nozzle.
     * Returns false while the optional model is unavailable so FFA can draw its native fallback.
     */
    public static boolean render(String model, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!"kh55".equals(model)) return false;
        PolyMeshModel mesh = KH55.ready();
        if (mesh == null) return false;
        pose.m_85836_();
        try {
            // The reused KH-29 mesh has its nose at +Z and nozzle 1.95 blocks behind its centre.
            pose.m_85837_(0.0, 1.95, 0.0);
            pose.m_252781_(new Quaternionf().rotationX((float) (-Math.PI / 2.0)));
            mesh.renderCutoutOnly(pose, buffers, TEXTURE, light, 1.0F);
            mesh.renderTranslucentOnly(pose, buffers, TEXTURE, light, 1.0F);
        } finally {
            pose.m_85849_();
        }
        return true;
    }

    /** Reuse the bounded model queue and resource-reload lifetime used by suspended stores. */
    private static final class MissileAsset extends BaseVehicleRenderer<GeoVehicleEntity> {
        private long textureGeneration = -1;
        private boolean texturePresent;

        private MissileAsset() {
            super(null, MODEL, TEXTURE, "KH-55 temporary KH-29 model");
        }

        private PolyMeshModel ready() {
            long generation = currentResourceGeneration();
            if (textureGeneration != generation) {
                textureGeneration = generation;
                texturePresent = AircraftStoreItemRenderer.resourceExists(TEXTURE);
            }
            return texturePresent ? getOrLoadModel() : null;
        }
    }
}
