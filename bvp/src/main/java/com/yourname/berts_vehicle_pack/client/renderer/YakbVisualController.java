package com.yourname.berts_vehicle_pack.client.renderer;

import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.yourname.berts_vehicle_pack.client.BvpFiredVisualClient;
import com.yourname.berts_vehicle_pack.entity.Mi24VEntity;

import java.util.Map;
import java.util.WeakHashMap;

final class YakbVisualController {
    private static final String[] SAFE_BARREL_BONES = new String[]{
            "yakbBarrels", "YakBBarrels", "yakb_barrels", "yakbSpin", "yakbSpinner"
    };
    private static final Map<Mi24VEntity, YakbVisualState> YAKB_VISUALS = new WeakHashMap<>();
    private static final float YAKB_BARREL_ACCEL_PER_TICK = 0.18F;
    private static final float YAKB_BARREL_DECEL_PER_TICK = 0.24F;
    private static final float YAKB_BARREL_DEGREES_PER_TICK = 840.0F;
    private static final float YAKB_FLASH_TICKS = 1.0F;

    private YakbVisualController() {
    }

    static void apply(Mi24VEntity entity, PolyMeshModel loadedModel, float partialTicks) {
        float renderTick = entity.m_9236_().m_46467_() + partialTicks;
        YakbVisualState state = YAKB_VISUALS.computeIfAbsent(entity,
                ignored -> new YakbVisualState(renderTick, BvpFiredVisualClient.autocannonSequence(entity)));
        float deltaTicks = state.advanceTime(renderTick);
        long flashCounter = BvpFiredVisualClient.autocannonSequence(entity);
        if (flashCounter != state.lastFlashCounter) {
            state.lastFlashCounter = flashCounter;
            state.flashTicks = YAKB_FLASH_TICKS;
        } else if (state.flashTicks > 0.0F) {
            state.flashTicks = Math.max(0.0F, state.flashTicks - deltaTicks);
        }

        BedrockBone barrelBone = firstAvailableBarrelBone(loadedModel);
        if (barrelBone == null) {
            return;
        }

        float targetSpeed = entity.getAutocannonBarrelSpinStrength();
        float maxStep = (targetSpeed > state.speed ? YAKB_BARREL_ACCEL_PER_TICK : YAKB_BARREL_DECEL_PER_TICK) * deltaTicks;
        state.speed = RendererBones.approach(state.speed, targetSpeed, maxStep);
        double heat = entity.getBvpAutocannonHeatFraction();

        if (targetSpeed <= 1.0E-4F && state.speed <= 1.0E-4F && heat <= 0.02D && state.flashTicks <= 0.0F) {
            RendererBones.setRotation(barrelBone, 0.0F, 0.0F, 0.0F);
            barrelBone.illuminated = false;
            YAKB_VISUALS.remove(entity);
            return;
        }

        state.angle = RendererBones.wrap(state.angle + YAKB_BARREL_DEGREES_PER_TICK * state.speed * deltaTicks, 360.0F);
        barrelBone.visible = true;
        barrelBone.illuminated = heat > 0.04D || state.flashTicks > 0.0F;
        RendererBones.setRotation(barrelBone, 0.0F, 0.0F, state.angle * RendererBones.DEG_TO_RAD);
    }

    private static BedrockBone firstAvailableBarrelBone(PolyMeshModel loadedModel) {
        for (String boneName : SAFE_BARREL_BONES) {
            BedrockBone bone = loadedModel.getBone(boneName);
            if (bone != null) {
                return bone;
            }
        }
        return null;
    }

    private static final class YakbVisualState {
        float speed;
        float angle;
        float lastRenderTick;
        float flashTicks;
        long lastFlashCounter;

        YakbVisualState(float renderTick, long flashCounter) {
            this.lastRenderTick = renderTick;
            this.lastFlashCounter = flashCounter;
        }

        float advanceTime(float renderTick) {
            float delta = renderTick - this.lastRenderTick;
            this.lastRenderTick = renderTick;
            if (!Float.isFinite(delta) || delta <= 0.0F) {
                return 0.0F;
            }
            return Math.min(delta, 2.0F);
        }
    }

}
