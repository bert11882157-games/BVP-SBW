package com.atsuishio.superbwarfare.client.camera;

import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Exact installed CameraOverhaul strafe kernel plus the production ownership gate; no world. */
public final class VehicleCameraEffectOwnershipTest {
    private static int checks;

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }

    public static void main(String[] args) throws Exception {
        Class<?> systemType = Class.forName("mirsario.cameraoverhaul.CameraSystem");
        Class<?> contextType = Class.forName("mirsario.cameraoverhaul.CameraContext");
        Class<?> transformType = Class.forName("mirsario.cameraoverhaul.utilities.Transform");
        Class<?> configType = Class.forName("mirsario.cameraoverhaul.configuration.ConfigData$Contextual");
        Method kernel = systemType.getDeclaredMethod("strafingRollOffset", contextType, transformType, double.class);
        kernel.setAccessible(true);
        Method apply = systemType.getMethod("modifyCameraTransform", transformType);
        double speed = 193.0 / 72.0;
        for (int yaw : new int[]{-90, 0, 90}) {
            Object system = systemType.getConstructor().newInstance();
            Object context = contextType.getConstructor().newInstance();
            Object cfg = configType.getConstructor().newInstance();
            field(configType, "strafingRollFactor").setDouble(cfg, 10.0);
            field(configType, "horizontalVelocitySmoothingFactor").setDouble(cfg, 1.0);
            field(systemType, "ctxCfg").set(system, cfg);
            field(contextType, "velocity").set(context, new Vector3d(0, 0, speed));
            Object cameraTransform = transformType.getConstructor().newInstance();
            field(contextType, "transform").set(context, cameraTransform);
            ((Vector3d) field(transformType, "eulerRot").get(cameraTransform)).set(0, yaw, 0);
            Object offset = field(systemType, "offsetTransform").get(system);
            Vector3d offsetEuler = (Vector3d) field(transformType, "eulerRot").get(offset);
            for (int frame = 0; frame < 240; frame++) {
                offsetEuler.zero();
                kernel.invoke(system, context, offset, 1.0 / 60.0);
            }
            double oldRoll = offsetEuler.z;
            expect(Math.abs(Math.abs(oldRoll) - (yaw == 0 ? 0 : speed * 10.0)) < 0.001,
                    "actual installed kernel produces about 26.8 degrees at side view, zero straight ahead");
            Object output = transformType.getConstructor().newInstance();
            Vector3d shown = (Vector3d) field(transformType, "eulerRot").get(output);
            for (int frame = 0; frame < 90; frame++) {
                shown.zero(); apply.invoke(system, output);
                expect(Math.abs(shown.z - oldRoll) < 1e-10, "effect application alone never retires a stale stored roll");
            }

            var owner = new VehicleCameraEffectOwnership.State();
            for (int frame = 0; frame < 180; frame++) {
                boolean vehicleCamera = true; // entry, held freelook, release, continuous chase all share this owner.
                expect(owner.suppressUpdate(vehicleCamera), "pedestrian accumulation is excluded while mounted");
                shown.zero();
                if (!owner.suppressTransform(vehicleCamera)) apply.invoke(system, output);
                expect(shown.z == 0, "foreign roll cannot stack onto the authoritative vehicle camera");
                Matrix4f bodyView = new Matrix4f().rotateZ((float) Math.toRadians(frame - 90));
                Matrix4f before = new Matrix4f(bodyView);
                bodyView.rotateZ((float) Math.toRadians(shown.z));
                expect(bodyView.equals(before), "legitimate vehicle bank remains exact; no counter-rotation or forced level");
            }
            expect(owner.suppressTransform(false), "exit projection cannot expose a vehicle-era stale transform");
            expect(!owner.suppressUpdate(false), "ordinary on-foot update remains enabled");
            expect(owner.resetBeforeOrdinaryUpdate(), "one exit update rebases the upstream inertia state");
            expect(!owner.resetBeforeOrdinaryUpdate(), "no per-frame restart after exit");
            expect(!owner.suppressTransform(false), "fresh on-foot camera effects resume normally");
            System.out.println("INSTALLED_STRAFE yaw=" + yaw + " oldRollDegrees=" + oldRoll + " mountedContribution=0.0");
        }
        var ordinary = new VehicleCameraEffectOwnership.State();
        expect(!ordinary.suppressTransform(false) && !ordinary.suppressUpdate(false)
                && !ordinary.resetBeforeOrdinaryUpdate(), "unmounted/non-SBW cameras are unchanged");
        System.out.println("PASS " + checks + " installed-kernel, ownership, release/exit and legitimate-bank checks");
    }
}
