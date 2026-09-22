package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.client.camera.VehicleFreeCameraController;
import com.atsuishio.superbwarfare.client.camera.VehicleOpticalZoomController;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import com.atsuishio.superbwarfare.event.ClickEventHandler;
import com.atsuishio.superbwarfare.init.ModKeyMappings;
import com.google.gson.Gson;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded private observation of authored eyes against submitted Komodo parent transforms. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID, value = Dist.CLIENT)
public final class BvpGroundCameraClientProbe {
    private static VehicleEntity target;
    private static final List<Map<String, Object>> ROWS = new ArrayList<>();
    private static final Map<Integer, Map<String, Object>> FRAMEBUFFERS = new LinkedHashMap<>();
    private static long started;
    private static long lastSample;
    private static int firstTick;
    private static String startedUtc;
    private static boolean capped;
    private static boolean injectedZoom;
    private static String observedHold = "";
    private static int holdStartedTick;

    private BvpGroundCameraClientProbe() { }

    private static boolean context(Minecraft mc) {
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios") && EliteDiagnostics.isClientEnabled()
                && mc.player != null && mc.level != null && mc.getConnection() != null
                && "0".equals(mc.getUser().getAccessToken())
                && BvpFireTrafficControl.identity(mc.player.getGameProfile().getName(), mc.player.getUUID())
                && BvpFireTrafficControl.loopback(mc.getConnection().getConnection().getRemoteAddress(), true)
                && mc.screen == null && mc.isWindowActive();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        VehicleEntity vehicle = context(mc) && mc.player.getVehicle() instanceof VehicleEntity candidate
                && candidate.hasCustomName() && candidate.getName().getString().startsWith("camera_probe:")
                ? candidate : null;
        if (vehicle != target) {
            if (target != null) finish("CONTEXT_FINISHED");
            capped = false;
            if (vehicle != null) {
                target = vehicle; started = System.nanoTime(); firstTick = mc.player.tickCount;
                startedUtc = Instant.now().toString(); ROWS.clear(); FRAMEBUFFERS.clear(); lastSample = 0;
                observedHold = ""; holdStartedTick = firstTick;
            }
        }
        if (target == null || capped) return;
        if (System.nanoTime() - started > 180_000_000_000L || ROWS.size() >= 1_800) {
            finish("CAP_REACHED"); capped = true; return;
        }
        // The HUD fixture uses real external keys and view changes; no periodic zoom injection.
        if(target.getName().getString().contains(":hud:")) return;
        boolean held = (mc.player.tickCount - firstTick) / 100 % 2 == 1;
        if (held != injectedZoom) {
            var binding = ModKeyMappings.VEHICLE_HOLD_ZOOM.getKey();
            if (binding.getType() != com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
                finish("MOUSE_ZOOM_BINDING_REQUIRED"); return;
            }
            // Zoom is edge-driven. Exercise its normal press/release and optical-session owners.
            ModKeyMappings.VEHICLE_HOLD_ZOOM.setDown(held);
            var input = new InputEvent.MouseButton.Pre(binding.getValue(), held ? 1 : 0, 0);
            if (held) ClickEventHandler.INSTANCE.onButtonPressed(input);
            else ClickEventHandler.INSTANCE.onButtonReleased(input);
            injectedZoom = held;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(TickEvent.RenderTickEvent event) {
        if (target == null || event.phase != TickEvent.Phase.END) return;
        long now = System.nanoTime();
        if (now - lastSample < 100_000_000L) return;
        lastSample = now;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!context(mc) || mc.player.getVehicle() != target) { finish("CONTEXT_FINISHED"); return; }
            var camera = mc.gameRenderer.getMainCamera();
            boolean zoom = ClientEventHandler.zoomVehicle;
            var seat = target.resolveVehicleSeatPose(mc.player, event.renderTickTime, zoom);
            var chassis = target.resolveChassisPresentation(event.renderTickTime);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tick", mc.level.getGameTime()); row.put("partial", event.renderTickTime);
            row.put("phase", target.getName().getString()); row.put("seat", target.getSeatIndex(mc.player));
            row.put("zoom", zoom); row.put("zoomHeld", ModKeyMappings.VEHICLE_HOLD_ZOOM.isDown());
            var optical = VehicleOpticalZoomController.activeView(mc.player);
            row.put("opticalHoldActive", optical != null && optical.getHeldActive());
            row.put("magnification", optical == null ? 1.0 : optical.getMagnification());
            row.put("view", mc.options.getCameraType().name());
            row.put("framebufferWidth",mc.getWindow().getWidth());
            row.put("framebufferHeight",mc.getWindow().getHeight());
            row.put("guiWidth",mc.getWindow().getGuiScaledWidth());
            row.put("guiHeight",mc.getWindow().getGuiScaledHeight());
            row.put("guiScale",mc.getWindow().getGuiScale());
            var systems = com.atsuishio.superbwarfare.client.weapon.VehicleWeaponHudSnapshot.capture(target,mc.player);
            if(systems != null) {
                row.put("secondaryHoldProgress",systems.getSecondaryHoldProgress());
                row.put("ammoCycleKey",systems.getAmmoCycleKeyLabel().getString());
                List<Map<String,Object>> weapons = new ArrayList<>();
                for(var system : systems.getSystems()) {
                    Map<String,Object> weapon = new LinkedHashMap<>();
                    weapon.put("slot",system.getSlotIndex());weapon.put("id",system.getWeaponId());
                    weapon.put("kind",system.getKind().name());weapon.put("primary",system.getPrimary());
                    weapon.put("secondary",system.getSecondary());weapon.put("loaded",system.getLoadedAmmo());
                    weapon.put("reserve",system.getReserveAmmo());weapon.put("ammoCycle",system.getSupportsAmmoCycle());
                    weapons.add(weapon);
                }
                row.put("systems",weapons);
            }
            var gun = target.getGunData(mc.player);
            var round = gun == null ? null
                    : com.atsuishio.superbwarfare.client.weapon.VehicleSelectedRoundSnapshot.capture(gun);
            if(round != null) {
                row.put("ammoIndex",round.getAmmoIndex());
                row.put("roundType",round.getType() == null ? null : round.getType().name());
                row.put("roundDesignation",round.getDesignation() == null ? null : round.getDesignation().getString());
            }
            if(target instanceof com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity armored)
                row.put("modules",armored.vehicleModuleHudState());
            row.put("freecam", VehicleFreeCameraController.isActive(mc.player));
            row.put("rawAttitude", List.of(target.getPitch(event.renderTickTime), target.getRoll(event.renderTickTime)));
            row.put("baseAttitude", List.of(chassis.getPose().getBasePitchDegrees(), chassis.getPose().getBaseRollDegrees()));
            row.put("chassisSequence", chassis.getPose().getSequence());
            row.put("chassisMode", chassis.getMode().name()); row.put("anchor", vector(chassis.getAnchor()));
            row.put("speedBlocksPerTick", target.getDeltaMovement().length());
            row.put("camera", vector(camera.getPosition()));
            row.put("cameraAngles", List.of(camera.getYRot(), camera.getXRot()));
            row.put("playerAngles", List.of(mc.player.getYRot(), mc.player.getXRot()));
            row.put("riderPosition", vector(mc.player.position()));
            if (seat == null) {
                var definition = target.getSeat(mc.player);
                var optics = definition.getCameraPos();
                if (optics != null && (optics.getEyeAttachment() != null || optics.getZoomEyeAttachment() != null)) {
                    row.put("seatStatus", "UNRESOLVED_AUTHORED_EYE");
                } else {
                    String parent = definition.getBodyAttachment() != null
                            ? definition.getBodyAttachment() : definition.transform;
                    if (parent == null) parent = "Vehicle";
                    Vec3 local = definition.getPosition();
                    Vec3 body = target.getVehicleAttachmentSnapshot(event.renderTickTime).point(parent, local);
                    if (body == null) row.put("seatStatus", "UNRESOLVED_LEGACY_BODY");
                    else {
                        Vec3 eye = mc.player.getEyePosition(event.renderTickTime);
                        row.put("seatStatus", "LEGACY_BODY_FALLBACK");
                        row.put("resolvedBody", vector(body)); row.put("bodyAnchor", parent);
                        row.put("eye", vector(eye)); row.put("cameraEyeError", camera.getPosition().distanceTo(eye));
                        row.put("submissionAnchor", "BODY");
                        row.put("attachmentParent", parent); row.put("attachmentLocal", vector(local));
                        row.put("submission", submission(target, parent, local, camera.getPosition(), body,
                                ClientEventHandler.modelViewMatrix));
                    }
                }
            }
            else {
                row.put("resolvedBody", vector(seat.getBodyPosition()));
                row.put("bodyAnchor", seat.getBodyAnchor());
                row.put("eye", vector(seat.getEyePosition()));
                row.put("cameraEyeError", camera.getPosition().distanceTo(seat.getEyePosition()));
                row.put("eyeAnchor", seat.getEyeAnchor());
                var definition = target.getSeat(mc.player).getCameraPos();
                String eyeName = definition == null ? null : zoom && definition.getZoomEyeAttachment() != null
                        ? definition.getZoomEyeAttachment() : definition.getEyeAttachment();
                var attachment = eyeName == null ? null : target.computed().getAttachments().get(eyeName);
                if (attachment != null) {
                    row.put("attachmentParent", attachment.getParent());
                    row.put("attachmentLocal", vector(attachment.getPosition()));
                    row.put("submissionAnchor", "EYE");
                    row.put("submission", submission(target, attachment.getParent(), attachment.getPosition(),
                            camera.getPosition(), seat.getEyePosition(), ClientEventHandler.modelViewMatrix));
                } else if (eyeName != null && !eyeName.isBlank()) {
                    row.put("seatStatus", "UNRESOLVED_AUTHORED_EYE");
                } else {
                    // Legacy providers synthesize an eye; its independently submitted anchor is the rider body.
                    String parent = seat.getBodyAnchor();
                    Vec3 local = target.getSeat(mc.player).getPosition();
                    row.put("seatStatus", "PROVIDER_BODY_FALLBACK");
                    row.put("submissionAnchor", "BODY");
                    row.put("attachmentParent", parent); row.put("attachmentLocal", vector(local));
                    row.put("submission", submission(target, parent, local, camera.getPosition(),
                            seat.getBodyPosition(), ClientEventHandler.modelViewMatrix));
                }
            }
            ROWS.add(row);
            captureFrame(mc, zoom);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            ROWS.add(Map.of("error", failure.toString())); finish("OBSERVATION_ERROR");
        }
    }

    private static void captureFrame(Minecraft mc, boolean zoom) {
        String name = target.getName().getString();
        int elapsed = mc.player.tickCount - firstTick;
        int phase;
        if (name.contains(":station_")) {
            phase = elapsed / 100;
            if (phase < 0 || phase >= 3 || elapsed % 100 < 30) return;
        } else {
            if (!name.endsWith(":hold")) { observedHold = ""; return; }
            String[] fields = name.split(":");
            if (fields.length != 4) return;
            int stop = switch (fields[2]) {
                case "flat" -> 0;
                case "uphill" -> 1;
                case "crest" -> 2;
                case "downhill" -> 3;
                case "flat_end" -> 4;
                case "hud" -> 5;
                default -> -1;
            };
            if (stop < 0) return;
            if (!name.equals(observedHold)) {
                observedHold = name; holdStartedTick = mc.player.tickCount;
            }
            if (mc.player.tickCount - holdStartedTick < 30) return;
            phase = stop * 2 + (zoom ? 1 : 0);
        }
        if(name.contains(":hud:")) {
            if(FRAMEBUFFERS.size()>=64) return;
            var snapshot = com.atsuishio.superbwarfare.client.weapon.VehicleWeaponHudSnapshot.capture(target,mc.player);
            var modules = target instanceof com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity armored
                    ? armored.vehicleModuleHudState() : null;
            phase = java.util.Objects.hash(mc.getWindow().getWidth(),mc.getWindow().getHeight(),
                    mc.getWindow().getGuiScale(),mc.options.getCameraType(),zoom,
                    target.getPrimaryWeaponIndex(target.getSeatIndex(mc.player)),
                    target.getSecondaryWeaponIndex(target.getSeatIndex(mc.player)),
                    modules == null ? null : List.of(
                        com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudDamage.Companion.from(modules.getEngine()),
                        com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudDamage.Companion.from(modules.getLeftTrack()),
                        com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudDamage.Companion.from(modules.getRightTrack()),
                        com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudDamage.Companion.from(modules.getWeapon())),
                    snapshot == null || snapshot.getSecondaryHoldProgress() == null ? -1
                        : (int)(snapshot.getSecondaryHoldProgress()*3),
                    target.getGunData(mc.player) == null ? -1 : target.getGunData(mc.player).selectedAmmoType.get());
        }
        if (FRAMEBUFFERS.containsKey(phase)) return;
        String filename = "bvp-station-" + target.getUUID() + "-" + firstTick + "-p" + phase + ".png";
        FRAMEBUFFERS.put(phase, Map.of("filename", filename, "elapsedTicks", elapsed,
                "zoom", zoom, "view", mc.options.getCameraType().name(), "fixturePhase", name,
                "framebuffer",List.of(mc.getWindow().getWidth(),mc.getWindow().getHeight()),
                "gui",List.of(mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight()),
                "guiScale",mc.getWindow().getGuiScale(),
                "status", "REQUESTED_FILE_VERIFICATION_REQUIRED"));
        VehicleEntity observed = target;
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, filename, mc.getMainRenderTarget(),
                message -> EliteDiagnostics.record(observed, "ground_camera", "FRAMEBUFFER_RESULT",
                        "filename", filename, "result", message.getString()));
    }

    private static Map<String, Object> submission(VehicleEntity vehicle, String parent, Vec3 local,
                                                 Vec3 camera, Vec3 eye, Matrix4f view)
            throws ReflectiveOperationException {
        String bone = switch (parent) {
            case "Vehicle", "Default", "VehicleCustomPitch" -> "hull";
            case "Turret" -> "turret";
            case "Barrel" -> "barell";
            case "WeaponStation" -> "passengerWeaponStationYaw";
            case "WeaponStationBarrel" -> "passengerWeaponStationPitch";
            case "fitted_sight_pitch" -> "sight_pitch";
            default -> null;
        };
        if (bone == null) return Map.of("status", "UNSUPPORTED_PARENT", "parent", parent);
        if (parent.equals("VehicleCustomPitch") && targetCustomPitchNonzero(vehicle)) {
            return Map.of("status", "UNSUPPORTED_NONZERO_CUSTOM_PITCH", "parent", parent);
        }
        Class<?> bridge = Class.forName("com.yourname.berts_vehicle_pack.client.renderer.BvpKomodoBridge");
        Object receiver = ((Map<?, ?>) field(bridge, "RECEIVERS").get(null)).get(vehicle);
        if (receiver == null) return Map.of("status", "NO_RECEIVER");
        synchronized (receiver) {
            Class<?> type = receiver.getClass();
            long frame = field(bridge, "frame").getLong(null);
            long submitted = field(type, "submittedFrame").getLong(receiver);
            Object geometry = field(type, "pendingGeometry").get(receiver);
            if (geometry == null) return Map.of("status", "NO_GEOMETRY");
            List<?> parts = (List<?>) field(geometry.getClass(), "parts").get(geometry);
            if (parts.size() > 4_096) return Map.of("status", "GEOMETRY_PART_CAP_EXCEEDED");
            Matrix4f[] transforms = (Matrix4f[]) field(type, "transforms").get(receiver);
            boolean[] visible = (boolean[]) field(type, "visible").get(receiver);
            boolean[] drawn = (boolean[]) field(type, "instanceVisible").get(receiver);
            boolean sameGeometry = field(type, "instanceGeometry").get(receiver) == geometry;
            Vec3i origin = (Vec3i) method(type, "renderOrigin").invoke(receiver);
            Vector3f rootPivot = new Vector3f();
            if (bone.equals("hull")) {
                // Vehicle anchors use model coordinates; root mesh vertices are relative to its pivot.
                Map<?, ?> boneParts = (Map<?, ?>) field(geometry.getClass(), "boneParts").get(geometry);
                Object rootBone = null;
                for (var entry : boneParts.entrySet()) {
                    for (Object partIndex : (List<?>) entry.getValue()) {
                        int index = (Integer) partIndex;
                        if (bone.equals(field(parts.get(index).getClass(), "name").get(parts.get(index)))) {
                            if (rootBone != null && rootBone != entry.getKey())
                                return Map.of("status", "AMBIGUOUS_HULL_ROOT");
                            rootBone = entry.getKey();
                        }
                    }
                }
                if (rootBone == null || field(rootBone.getClass(), "parent").get(rootBone) != null)
                    return Map.of("status", "UNSUPPORTED_HULL_PARENT");
                var rotation = (org.joml.Quaternionf) field(rootBone.getClass(), "rotation").get(rootBone);
                if (!rotation.isFinite() || Math.abs(rotation.x) > 0.000001F || Math.abs(rotation.y) > 0.000001F
                        || Math.abs(rotation.z) > 0.000001F || Math.abs(rotation.w - 1) > 0.000001F)
                    return Map.of("status", "UNSUPPORTED_HULL_LOCAL_ROTATION");
                for (String axis : List.of("xScale", "yScale", "zScale")) {
                    if (field(rootBone.getClass(), axis).getFloat(rootBone) != 1.0F)
                        return Map.of("status", "UNSUPPORTED_HULL_LOCAL_SCALE");
                }
                rootPivot.set(field(rootBone.getClass(), "x").getFloat(rootBone) / 16.0F,
                        field(rootBone.getClass(), "y").getFloat(rootBone) / 16.0F,
                        field(rootBone.getClass(), "z").getFloat(rootBone) / 16.0F);
                if (!rootPivot.isFinite()) return Map.of("status", "NONFINITE_HULL_PIVOT");
            }
            for (int i = 0; i < Math.min(parts.size(), 4_096); i++) {
                if (!bone.equals(field(parts.get(i).getClass(), "name").get(parts.get(i)))) continue;
                Matrix4f matrix = new Matrix4f(transforms[i]);
                Vector3f meshLocal = new Vector3f((float) -local.x, (float) local.y,
                        (float) -local.z).sub(rootPivot);
                Vector3f rendered = matrix.transformPosition(new Vector3f(meshLocal));
                Vec3 world = new Vec3(rendered.x + origin.getX(), rendered.y + origin.getY(), rendered.z + origin.getZ());
                Vector3f viewPoint = new Vector3f((float) (world.x - camera.x),
                        (float) (world.y - camera.y), (float) (world.z - camera.z));
                if (view != null) new Matrix4f(view).transformPosition(viewPoint);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "SAMPLED_SUBMISSION_NOT_GPU_PROOF"); result.put("bone", bone);
                result.put("rootPivot", List.of(rootPivot.x, rootPivot.y, rootPivot.z));
                result.put("meshLocal", List.of(meshLocal.x, meshLocal.y, meshLocal.z));
                result.put("frame", frame); result.put("submittedFrame", submitted);
                result.put("matrix", matrix.get(new float[16])); result.put("renderOrigin", List.of(origin.getX(), origin.getY(), origin.getZ()));
                result.put("visible", visible[i] && sameGeometry && i < drawn.length && drawn[i]);
                result.put("renderedEyeWorld", vector(world)); result.put("renderedEyeError", world.distanceTo(eye));
                result.put("renderedEyeInView", List.of(viewPoint.x, viewPoint.y, viewPoint.z));
                result.put("finalView", view == null ? null : view.get(new float[16]));
                return result;
            }
            return Map.of("status", "NO_PARENT_PART", "bone", bone);
        }
    }

    private static boolean targetCustomPitchNonzero(VehicleEntity vehicle) {
        return !Float.isFinite(vehicle.getTurretCustomPitch()) || Math.abs(vehicle.getTurretCustomPitch()) > 0.000001F;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field result = owner.getDeclaredField(name); result.setAccessible(true); return result;
    }

    private static Method method(Class<?> owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Method result = type.getDeclaredMethod(name); result.setAccessible(true); return result; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }

    private static List<Double> vector(Vec3 point) { return List.of(point.x, point.y, point.z); }

    private static void finish(String reason) {
        if (target == null) return;
        if (injectedZoom) ClickEventHandler.INSTANCE.onButtonReleased(new InputEvent.MouseButton.Pre(
                ModKeyMappings.VEHICLE_HOLD_ZOOM.getKey().getValue(), 0, 0));
        injectedZoom = false;
        ModKeyMappings.VEHICLE_HOLD_ZOOM.setDown(false);
        try {
            Path directory = Path.of("logs", "ground-camera-tests"); Files.createDirectories(directory);
            String report = new Gson().toJson(Map.of("vehicle", target.getUUID().toString(),
                    "startedUtc", startedUtc, "reason", reason, "rows", ROWS,
                    "framebufferCaptures", FRAMEBUFFERS));
            if (report.length() > 16 * 1024 * 1024) throw new IllegalStateException("Camera report size cap");
            Files.writeString(directory.resolve(target.getUUID() + "-" + firstTick + ".json"), report);
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger(BvpGroundCameraClientProbe.class).error("Ground camera report failed", failure);
        } finally { target = null; ROWS.clear(); FRAMEBUFFERS.clear(); }
    }
}
