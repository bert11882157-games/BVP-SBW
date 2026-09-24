package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/** Explicit exterior-only pieces in models whose opaque shell intersects the cockpit view. */
final class BvpCockpitVisibility {
    static final String BONE = "pilot_view_occluder";
    private static java.util.UUID lastObservedVehicle;
    private static long lastObservedTick = Long.MIN_VALUE;

    record Hidden(BedrockBone bone, boolean previous) {
        void restore() { bone.visible = previous; }
    }

    static Hidden apply(GeoVehicleEntity vehicle, PolyMeshModel model) {
        Minecraft minecraft = Minecraft.m_91087_();
        var player = minecraft.f_91074_;
        if (player == null || player.m_20202_() != vehicle) return null;
        int seat = vehicle.getSeatIndex(player);
        boolean pod = AircraftArmamentClient.isPodActive(vehicle);
        boolean eligible = minecraft.f_91066_.m_92176_() == CameraType.FIRST_PERSON
                && seat == 0 && !vehicle.isWreck() && !pod;
        BedrockBone bone = model.getBone(BONE);
        Hidden hidden = eligible && bone != null ? new Hidden(bone, bone.visible) : null;
        if (hidden != null) bone.visible = false;
        if (EliteDiagnostics.isClientEnabled()) {
            long tick = vehicle.m_9236_().m_46467_();
            if (!vehicle.getUUID().equals(lastObservedVehicle) || tick - lastObservedTick >= 20) {
                lastObservedVehicle = vehicle.getUUID();
                lastObservedTick = tick;
                EliteDiagnostics.recordClient(tick, "cockpit_visibility", "MODEL_SUBMISSION",
                        "vehicle", vehicle.getUUID(), "seat", seat,
                        "view", minecraft.f_91066_.m_92176_().name(), "pod", pod,
                        "eligible", eligible, "bone_found", bone != null,
                        "bone_visible", bone == null ? null : bone.visible,
                        "bones", new java.util.TreeSet<>(model.getBoneMap().keySet()).toString());
            }
        }
        return hidden;
    }

    private BvpCockpitVisibility() { }
}
