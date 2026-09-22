package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.client.FixedWingPilotIntentClient;
import com.atsuishio.superbwarfare.client.camera.FixedWingDynamicCamera;
import com.atsuishio.superbwarfare.client.camera.VehicleFreeCameraController;
import com.atsuishio.superbwarfare.event.ClientMouseHandler;
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource.AircraftRigResource;
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/** Private, bounded observer of the normal camera and submitted rig; never renders or moves a part. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID, value = Dist.CLIENT)
public final class BvpAircraftPresentationDiagnostic {
    private static BvpFlightClientVisualProbe probe;
    private static long lastNanos;

    private BvpAircraftPresentationDiagnostic() { }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !BvpFlightClientControl.enabled()
                || !EliteDiagnostics.isClientEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null
                || !BvpFireTrafficControl.identity(mc.player.getGameProfile().getName(), mc.player.getUUID())
                || !"0".equals(mc.getUser().getAccessToken())
                || !BvpFireTrafficControl.loopback(mc.getConnection().getConnection().getRemoteAddress(), true)
                || !(mc.player.getVehicle() instanceof VehicleEntity vehicle)) return;
        long now = System.nanoTime();
        if (now - lastNanos < 250_000_000L) return;
        lastNanos = now;
        try {
            var camera = mc.gameRenderer.getMainCamera();
            var seat = vehicle.resolveVehicleSeatPose(mc.player, event.renderTickTime, false);
            var flight = vehicle.getVehicleFlightPresentationSnapshot(event.renderTickTime);
            var controls = flight.getControlSurfaces();
            var intent = FixedWingPilotIntentClient.activeView(mc.player);
            var cameraOffset = FixedWingDynamicCamera.INSTANCE.offset(vehicle, event.renderTickTime);
            EliteDiagnostics.record(vehicle, "aircraft_presentation", "CAMERA",
                    "view", mc.options.getCameraType().name(), "partial", event.renderTickTime,
                    "camera_angles", Arrays.toString(new float[]{camera.getYRot(), camera.getXRot()}),
                    "camera_offset", Arrays.toString(new float[]{cameraOffset.x, cameraOffset.y}),
                    "freelook_held", VehicleFreeCameraController.isActive(mc.player),
                    "freelook_release", VehicleFreeCameraController.getReleaseSequence(),
                    "return_phase", FixedWingDynamicCamera.getReturnPhase(),
                    "return_blocked_gestures", FixedWingDynamicCamera.getSuppressedReturnGestures(),
                    "return_rearms", FixedWingDynamicCamera.getReturnRearms(),
                    "legacy_free_offsets", Arrays.toString(new double[]{ClientMouseHandler.freeCameraYaw,
                            ClientMouseHandler.freeCameraPitch}),
                    "screen_roll", intent == null ? null : intent.getScreenRollInput(),
                    "actual_eye", camera.getPosition(), "resolved_eye", seat == null ? null : seat.getEyePosition(),
                    "eye_anchor", seat == null ? null : seat.getEyeAnchor(),
                    "eye_error", seat == null ? -1.0 : camera.getPosition().distanceTo(seat.getEyePosition()),
                    "body_angles", Arrays.toString(new double[]{flight.getBodyYaw(), flight.getBodyPitch(), flight.getBodyRoll()}),
                    "render_body_angles", Arrays.toString(new float[]{vehicle.getResolvedChassisYaw(event.renderTickTime),
                            vehicle.getPitch(event.renderTickTime), vehicle.getRoll(event.renderTickTime)}),
                    "controls", controls == null ? null : Arrays.toString(new double[]{controls.getElevator(), controls.getAileron(),
                            controls.getRudder(), controls.getThrottle()}), "gear", vehicle.getSynchedGearRot());
            var resource = VehicleResource.getDefault(vehicle.getType());
            var rig = resource == null ? null : resource.getAircraftRig();
            if (rig == null) return;
            Set<String> names = new LinkedHashSet<>();
            names.add("hull");
            add(names, rig.surfaces); add(names, rig.rotors); add(names, rig.sweeps); add(names, rig.flaps);
            if (rig.gear != null) for (var gear : rig.gear) { names.add(gear.bone); names.add(gear.parent); }
            names.remove(null);
            if (names.size() > 192) throw new IllegalStateException("Rig observation bound exceeded");
            if (probe == null) probe = new BvpFlightClientVisualProbe();
            Map<String, Object> sample = probe.sample(vehicle, names);
            if (!(sample.get("bones") instanceof Map<?, ?> bones)
                    || !(bones.get("hull") instanceof Map<?, ?> hull)
                    || !(hull.get("renderOriginMatrix") instanceof float[] hullMatrix)) {
                EliteDiagnostics.record(vehicle, "aircraft_presentation", "RIG_UNAVAILABLE",
                        "status", sample.get("status"));
                return;
            }
            Matrix4f inverseHull = new Matrix4f().set(hullMatrix).invert();
            for (String name : names) {
                if (!(bones.get(name) instanceof Map<?, ?> bone)
                        || !(bone.get("renderOriginMatrix") instanceof float[] matrix)) {
                    EliteDiagnostics.record(vehicle, "aircraft_presentation", "RIG_MISSING", "bone", name);
                    continue;
                }
                float[] relative = new Matrix4f(inverseHull).mul(new Matrix4f().set(matrix)).get(new float[16]);
                for (float value : relative) if (!Float.isFinite(value))
                    throw new IllegalStateException("Nonfinite relative rig matrix");
                EliteDiagnostics.record(vehicle, "aircraft_presentation", "RIG_PART", "bone", name,
                        "relative_to_hull", Arrays.toString(relative), "submitted", bone.get("submittedVisible"),
                        "visible_instance", bone.get("instanceVisible"),
                        "submitted_this_frame", sample.get("submittedThisFrame"));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            EliteDiagnostics.record(vehicle, "aircraft_presentation", "PROBE_FAILED",
                    "error", failure.getClass().getSimpleName());
        }
    }

    private static void add(Set<String> names, AircraftRigResource.Part[] parts) {
        if (parts == null) return;
        for (var part : parts) { names.add(part.bone); names.add(part.parent); }
    }
}
