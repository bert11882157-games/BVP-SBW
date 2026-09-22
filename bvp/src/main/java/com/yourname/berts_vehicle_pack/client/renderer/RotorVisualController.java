package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.yourname.berts_vehicle_pack.entity.BvpFarVehicleVisuals;
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.client.FarVehicleClient;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.WeakHashMap;

public final class RotorVisualController {
    private static final RotorVisualTimeline TIMELINE = new RotorVisualTimeline();
    private static final Map<GeoVehicleEntity, Double> LAST_DIAGNOSTIC = new WeakHashMap<>();

    private RotorVisualController() {
    }

    public static void apply(GeoVehicleEntity entity, PolyMeshModel loadedModel, float partialTicks) {
        if (AircraftRigAnimator.owns(loadedModel)) {
            return;
        }
        BedrockBone mainRotor = loadedModel.getBone("propeller");
        BedrockBone counterRotor = loadedModel.getBone("contraPropeller");
        BedrockBone tailRotor = loadedModel.getBone("tailPropeller");
        if (mainRotor == null && counterRotor == null && tailRotor == null) {
            return;
        }

        if (FarVehicleClient.INSTANCE.currentLevel() != entity.m_9236_()) return;
        // The client tick clock survives server world-time corrections and tracking handoffs.
        double renderTick = FarVehicleClient.INSTANCE.time(partialTicks);
        boolean active = BvpFarVehicleVisuals.rotorActive(entity);
        boolean paused = Minecraft.m_91087_().m_91104_();
        RotorVisualTimeline.Sample sample = TIMELINE.sample(entity.m_9236_(), entity.getUUID(), renderTick, active, paused);
        float mainAngle = sample.mainDegrees() * RendererBones.DEG_TO_RAD;
        float tailAngle = sample.tailDegrees() * RendererBones.DEG_TO_RAD;
        if (mainRotor != null) {
            RendererBones.setRotation(mainRotor, 0.0F, mainAngle, 0.0F);
        }
        if (counterRotor != null) {
            RendererBones.setRotation(counterRotor, 0.0F, -mainAngle, 0.0F);
        }
        if (tailRotor != null) {
            RendererBones.setRotation(tailRotor, -tailAngle, 0.0F, 0.0F);
        }
        if (Boolean.getBoolean("bvp.diagnostics.scenarios") && EliteDiagnostics.isClientEnabled()) {
            Double previous = LAST_DIAGNOSTIC.get(entity);
            if (previous == null || renderTick < previous || renderTick - previous >= 0.25D) {
                LAST_DIAGNOSTIC.put(entity, renderTick);
                EliteDiagnostics.record(entity, "far_render", "ROTOR_RENDERED",
                        "copy", FarVehicleCopies.isCopy(entity),
                        "render_tick", entity.m_9236_().m_46467_() + (double) partialTicks,
                        "presentation_tick", renderTick, "paused", paused,
                        "active", active, "speed", sample.speed(),
                        "main_degrees", sample.mainDegrees(), "tail_degrees", sample.tailDegrees());
            }
        }
    }
}
