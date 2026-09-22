package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.entity.TurretWreckVisualContext;
import com.atsuishio.superbwarfare.client.renderer.entity.TurretWreckVisualProvider;
import com.atsuishio.superbwarfare.entity.vehicle.TurretWreckEntity;
import com.example.sbwmeshloader.core.PolyMeshLoader;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** BVP PolyMesh implementation of custom SBW's per-instance turret-wreck visual seam. */
public final class BvpTurretWreckVisualProvider implements TurretWreckVisualProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BVP_NAMESPACE = "berts_vehicle_pack";
    private static final float DEG_TO_RAD = 0.017453292F;

    private final Map<String, WreckVisual> visuals = new HashMap<>();
    private final Set<String> failedModels = new HashSet<>();

    @Override
    public boolean render(TurretWreckVisualContext context) {
        ResourceLocation visualId = context.getVisualId();
        if (!BVP_NAMESPACE.equals(visualId.m_135827_())) {
            return false;
        }

        WreckVisual visual = visualFor(visualId.m_135815_());
        if (visual == null) {
            return false;
        }

        TurretWreckEntity entity = context.getWreckEntity();
        applyStoredBonePose(entity, visual);
        visual.model.renderWithTranslucentSplit(
                context.getPoseStack(),
                context.getBufferSource(),
                visual.deadTexture,
                context.getPackedLight()
        );
        return true;
    }

    private WreckVisual visualFor(String vehiclePath) {
        WreckVisual cached = visuals.get(vehiclePath);
        if (cached != null || failedModels.contains(vehiclePath)) {
            return cached;
        }
        ResourceLocation modelLocation = new ResourceLocation(BVP_NAMESPACE,
                "custom_geo/" + vehiclePath + "_turret_wreck.geo.json");
        try {
            PolyMeshModel loaded = PolyMeshLoader.loadModel(modelLocation);
            if (loaded == null) {
                failedModels.add(vehiclePath);
                LOGGER.warn("[BERTS_VEHICLE_PACK_DEBUG] Missing turret wreck model {}", modelLocation);
                return null;
            }
            WreckVisual visual = new WreckVisual(
                    loaded,
                    new ResourceLocation(BVP_NAMESPACE, "textures/entity_dead/" + vehiclePath + ".png"),
                    loaded.getBone("barell"),
                    loaded.getBone("barrelRecoil"),
                    loaded.getBone("passengerWeaponStationYaw"),
                    loaded.getBone("passengerWeaponStationPitch"));
            visuals.put(vehiclePath, visual);
            return visual;
        } catch (Exception error) {
            failedModels.add(vehiclePath);
            LOGGER.warn("[BERTS_VEHICLE_PACK_DEBUG] Failed to load turret wreck model {}", modelLocation, error);
            return null;
        }
    }

    private static void applyStoredBonePose(TurretWreckEntity entity, WreckVisual visual) {
        if (visual.barrel != null) {
            setBoneRotation(visual.barrel, -entity.m_146909_() * DEG_TO_RAD, 0.0F, 0.0F);
        }
        if (visual.barrelRecoil != null) {
            setBoneRotation(visual.barrelRecoil, 0.0F, 0.0F, 0.0F);
        }
        if (visual.passengerWeaponYaw != null) {
            setBoneRotation(visual.passengerWeaponYaw, 0.0F, 0.0F, 0.0F);
        }
        if (visual.passengerWeaponPitch != null) {
            setBoneRotation(visual.passengerWeaponPitch, 0.0F, 0.0F, 0.0F);
        }
    }

    private static void setBoneRotation(BedrockBone bone, float xRad, float yRad, float zRad) {
        bone.rotation.identity().rotateZYX(zRad, yRad, xRad);
        bone.rotationInEuler.set(xRad, yRad, zRad);
    }

    private record WreckVisual(PolyMeshModel model, ResourceLocation deadTexture,
                               BedrockBone barrel, BedrockBone barrelRecoil,
                               BedrockBone passengerWeaponYaw, BedrockBone passengerWeaponPitch) {
    }
}
