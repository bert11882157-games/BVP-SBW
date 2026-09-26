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

    private static final java.util.Map<String, MissileAsset> EXPANSION = java.util.Map.ofEntries(
        flight("aim9m"), flight("kh31"), flight("kh32"), flight("kh38"),
        flight("kh47m2"), flight("kh58"), flight("lmur"), flight("r13m1"),
        flight("r3r"), flight("r27"), flight("r27t"), flight("r73"), flight("r77"), flight("kh25ml"),
        flight("aim9b"), flight("aim9l"), flight("aim9x"), flight("aim7e"),
        flight("aim54"), flight("agm45a"), flight("pars3"));
    private static java.util.Map.Entry<String, MissileAsset> flight(String id) {
        return java.util.Map.entry(id, new MissileAsset(
            new ResourceLocation("berts_vehicle_pack", "custom_geo/aircraft_stores/" + id + "_flight.geo.json"),
            new ResourceLocation("berts_vehicle_pack", "textures/aircraft_stores/" + id + ".png"), id));
    }
    private BvpCoordinateMissileRenderer() { }

    /**
     * Render-thread only. The incoming pose has +Y along flight and its origin at the nozzle.
     * Returns false while the optional model is unavailable so FFA can draw its native fallback.
     */
    public static boolean render(String model, PoseStack pose, MultiBufferSource buffers, int light) {
        return render(model, pose, buffers, light, Float.POSITIVE_INFINITY);
    }

    /** As above, [ageTicks] after launch: pop-out wings swing out over their first few ticks. */
    public static boolean render(String model, PoseStack pose, MultiBufferSource buffers, int light, float ageTicks) {
        MissileAsset asset = switch (model) {
            case "kh55" -> KH55;
            case "agm84k_slam_er" -> SLAM_ER;
            default -> EXPANSION.get(model);
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
            BvpFoldingWings.Restore wings = BvpFoldingWings.pose(mesh, asset.model,
                    BvpFoldingWings.deployed(asset.model, ageTicks));
            float lighting = BvpVboLighting.setFlat(BvpSuspendedStoreRenderer.MUNITION_FLAT_LIGHTING);
            try {
                mesh.renderCutoutOnly(pose, buffers, asset.texture, light, 1.0F);
                mesh.renderTranslucentOnly(pose, buffers, asset.texture, light, 1.0F);
            } finally { wings.run(); BvpVboLighting.setFlat(lighting); }
        } finally {
            pose.m_85849_();
        }
        return true;
    }

    /** Reuse the bounded model queue and resource-reload lifetime used by suspended stores. */
    private static final class MissileAsset extends BaseVehicleRenderer<GeoVehicleEntity> {
        private final ResourceLocation texture;
        private final ResourceLocation model;
        private long textureGeneration = -1;
        private boolean texturePresent;

        private MissileAsset(ResourceLocation model, ResourceLocation texture, String debugName) {
            super(null, model, texture, debugName);
            this.texture = texture;
            this.model = model;
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
