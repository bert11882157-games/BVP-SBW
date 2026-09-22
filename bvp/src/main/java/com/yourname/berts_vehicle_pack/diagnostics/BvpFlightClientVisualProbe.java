package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Opt-in read-only observer of the target receiver's submitted transforms, not a second render. */
final class BvpFlightClientVisualProbe {
    private static final Set<String> BONES = Set.of("hull", "aileron_left", "aileron_right",
            "elevator_left", "elevator_right", "rudder");
    private final Field receivers;
    private final Field frame;

    BvpFlightClientVisualProbe() throws ReflectiveOperationException {
        Class<?> bridge = Class.forName("com.yourname.berts_vehicle_pack.client.renderer.BvpKomodoBridge");
        receivers = field(bridge, "RECEIVERS"); frame = field(bridge, "frame");
    }

    Map<String, Object> sample(VehicleEntity vehicle) {
        return sample(vehicle, BONES);
    }

    Map<String, Object> sample(VehicleEntity vehicle, Set<String> selectedBones) {
        Map<String, Object> row = new LinkedHashMap<>();
        try {
            Object receiver = ((Map<?, ?>) receivers.get(null)).get(vehicle);
            if (receiver == null) return Map.of("status", "NO_KOMODO_RECEIVER");
            if (!receiver.getClass().getName().equals(
                    "com.yourname.berts_vehicle_pack.client.renderer.BvpKomodoVehicleVisual"))
                return Map.of("status", "UNEXPECTED_RECEIVER");
            synchronized (receiver) {
                Class<?> type = receiver.getClass();
                long submitted = field(type, "submittedFrame").getLong(receiver);
                long currentFrame = frame.getLong(null);
                row.put("bridgeFrame", currentFrame); row.put("submittedFrame", submitted);
                row.put("submittedThisFrame", submitted == currentFrame);
                row.put("failed", field(type, "failed").getBoolean(receiver));
                Object geometry = field(type, "pendingGeometry").get(receiver);
                if (geometry == null) { row.put("status", "NO_SUBMITTED_GEOMETRY"); return row; }
                List<?> parts = (List<?>) field(geometry.getClass(), "parts").get(geometry);
                Matrix4f[] transforms = (Matrix4f[]) field(type, "transforms").get(receiver);
                boolean[] visible = (boolean[]) field(type, "visible").get(receiver);
                boolean[] drawn = (boolean[]) field(type, "instanceVisible").get(receiver);
                boolean sameGeometry = field(type, "instanceGeometry").get(receiver) == geometry;
                Map<String, Object> bones = new LinkedHashMap<>();
                Field partName = parts.isEmpty() ? null : field(parts.get(0).getClass(), "name");
                for (int index = 0; index < Math.min(parts.size(), 4_096); index++) {
                    Object part = parts.get(index);
                    String name = (String) partName.get(part);
                    if (!selectedBones.contains(name) || bones.containsKey(name) || index >= transforms.length) continue;
                    float[] matrix = transforms[index].get(new float[16]);
                    for (float value : matrix) if (!Float.isFinite(value))
                        return Map.of("status", "NONFINITE_SUBMITTED_MATRIX");
                    bones.put(name, Map.of("partIndex", index, "renderOriginMatrix", matrix,
                            "submittedVisible", index < visible.length && visible[index],
                            "instanceVisible", sameGeometry && index < drawn.length && drawn[index]));
                }
                row.put("status", "SAMPLED_SUBMISSION_NOT_GPU_PROOF"); row.put("bones", bones);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            row.clear(); row.put("status", "PROBE_UNAVAILABLE");
            row.put("error", failure.getClass().getSimpleName());
        }
        return row;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
