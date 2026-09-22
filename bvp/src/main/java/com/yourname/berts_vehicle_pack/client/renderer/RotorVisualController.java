package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.helicopter.BvpHelicopterEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

public final class RotorVisualController {
    private static final Map<GeoVehicleEntity, RotorVisualState> ROTOR_VISUALS = new WeakHashMap<>();
    private static final float ROTOR_START_SPEED = 0.02F;
    private static final float ROTOR_ACCEL_PER_TICK = 0.00225F;
    private static final float ROTOR_DECEL_PER_TICK = 0.04F;
    private static final float MAIN_ROTOR_DEGREES_PER_TICK = 98.0F;
    private static final float TAIL_ROTOR_DEGREES_PER_TICK = 392.0F;

    private RotorVisualController() {
    }

    public static void apply(GeoVehicleEntity entity, PolyMeshModel loadedModel, float partialTicks) {
        BedrockBone mainRotor = loadedModel.getBone("propeller");
        BedrockBone counterRotor = loadedModel.getBone("contraPropeller");
        BedrockBone tailRotor = loadedModel.getBone("tailPropeller");
        if (mainRotor == null && counterRotor == null && tailRotor == null) {
            return;
        }

        float renderTick = entity.m_9236_().m_46467_() + partialTicks;
        RotorVisualState state = ROTOR_VISUALS.computeIfAbsent(entity, ignored -> new RotorVisualState(renderTick));
        boolean active = shouldAnimateRotor(entity);
        float deltaTicks = state.advanceTime(renderTick);
        float targetSpeed = active ? 1.0F : 0.0F;
        if (active && state.speed < ROTOR_START_SPEED) {
            state.speed = ROTOR_START_SPEED;
        }
        float maxStep = (targetSpeed > state.speed ? ROTOR_ACCEL_PER_TICK : ROTOR_DECEL_PER_TICK) * deltaTicks;
        state.speed = RendererBones.approach(state.speed, targetSpeed, maxStep);

        if (!active && state.speed <= 1.0E-4F) {
            if (mainRotor != null) {
                RendererBones.setRotation(mainRotor, 0.0F, 0.0F, 0.0F);
            }
            if (counterRotor != null) {
                RendererBones.setRotation(counterRotor, 0.0F, 0.0F, 0.0F);
            }
            if (tailRotor != null) {
                RendererBones.setRotation(tailRotor, 0.0F, 0.0F, 0.0F);
            }
            ROTOR_VISUALS.remove(entity);
            return;
        }

        state.mainAngle = RendererBones.wrap(state.mainAngle + MAIN_ROTOR_DEGREES_PER_TICK * state.speed * deltaTicks, 360.0F);
        state.tailAngle = RendererBones.wrap(state.tailAngle + TAIL_ROTOR_DEGREES_PER_TICK * state.speed * deltaTicks, 360.0F);
        float mainAngle = state.mainAngle * RendererBones.DEG_TO_RAD;
        float tailAngle = state.tailAngle * RendererBones.DEG_TO_RAD;
        if (mainRotor != null) {
            RendererBones.setRotation(mainRotor, 0.0F, mainAngle, 0.0F);
        }
        if (counterRotor != null) {
            RendererBones.setRotation(counterRotor, 0.0F, -mainAngle, 0.0F);
        }
        if (tailRotor != null) {
            RendererBones.setRotation(tailRotor, -tailAngle, 0.0F, 0.0F);
        }
    }

    private static boolean shouldAnimateRotor(GeoVehicleEntity entity) {
        if (entity instanceof ArmoredVehicleEntity armored && armored.isWreck()) {
            return false;
        }
        if (entity.m_20197_().isEmpty()) {
            return false;
        }
        if (entity instanceof BvpHelicopterEntity helicopter
                && (helicopter.getBvpThrottleTarget() > 0.01D
                || helicopter.getBvpRotorLiftPower() > 0.01D
                || helicopter.getBvpRotorThrustMps2() > 0.01D)) {
            return true;
        }
        if (Math.abs(entity.getPower()) > 0.01F || Math.abs(entity.getTargetSpeed()) > 0.01D) {
            return true;
        }
        Vec3 motion = entity.m_20184_();
        return motion.m_82556_() > 1.0E-4D;
    }

    private static final class RotorVisualState {
        float speed;
        float mainAngle;
        float tailAngle;
        float lastRenderTick;

        RotorVisualState(float renderTick) {
            this.lastRenderTick = renderTick;
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
