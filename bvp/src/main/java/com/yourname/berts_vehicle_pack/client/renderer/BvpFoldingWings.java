package com.yourname.berts_vehicle_pack.client.renderer;

import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pop-out wings of cruise missiles (SLAM-ER, Kh-55): folded along the body while the missile hangs on its pylon,
 * swinging out within a few ticks of launch. The wing panels are separate bones (tools/munition_wings/fold.py);
 * {@code assets/berts_vehicle_pack/folding_wings.json} gives each bone its fold axis and angle per model.
 * Models are shared, so every pose is restored right after the draw.
 */
public final class BvpFoldingWings {
    private static final ResourceLocation TABLE = new ResourceLocation("berts_vehicle_pack", "folding_wings.json");
    private record Wing(String bone, Vector3f axis, float degrees) { }
    private record Entry(float deployTicks, List<Wing> wings) { }

    private static Map<String, Entry> table;
    private static long tableGeneration = -1;

    private BvpFoldingWings() { }

    /** Restores the bones a {@link #pose} call turned. */
    public interface Restore { void run(); }
    private static final Restore NOTHING = () -> { };

    /**
     * Poses [model]'s wings: {@code deployed} 0 = folded (on the pylon), 1 = fully out. Returns the restore action,
     * to be run once the model has been drawn.
     */
    public static Restore pose(PolyMeshModel model, Object modelLocation, float deployed) {
        if (model == null || modelLocation == null) return NOTHING;
        Entry entry = entries().get(modelLocation.toString());
        if (entry == null) return NOTHING;
        float fold = 1.0F - Math.max(0.0F, Math.min(1.0F, deployed));
        if (fold <= 0.0F) return NOTHING;
        List<BedrockBone> bones = new ArrayList<>();
        List<Quaternionf> saved = new ArrayList<>();
        for (Wing wing : entry.wings) {
            BedrockBone bone = model.getBone(wing.bone);
            if (bone == null) continue;
            bones.add(bone);
            saved.add(new Quaternionf(bone.rotation));
            bone.rotation.rotationAxis((float) Math.toRadians(wing.degrees * fold), wing.axis.x, wing.axis.y, wing.axis.z)
                    .mul(saved.get(saved.size() - 1));
        }
        if (bones.isEmpty()) return NOTHING;
        return () -> { for (int i = 0; i < bones.size(); i++) bones.get(i).rotation.set(saved.get(i)); };
    }

    /** Deployment fraction [age] ticks after launch for [modelLocation]. */
    public static float deployed(Object modelLocation, float ageTicks) {
        Entry entry = modelLocation == null ? null : entries().get(modelLocation.toString());
        if (entry == null || !(ageTicks >= 0)) return 1.0F;
        float t = Math.min(1.0F, ageTicks / Math.max(1.0F, entry.deployTicks));
        return t * t * (3.0F - 2.0F * t);
    }

    private static Map<String, Entry> entries() {
        long generation = BaseVehicleRenderer.currentResourceGeneration();
        if (table != null && tableGeneration == generation) return table;
        tableGeneration = generation;
        Map<String, Entry> loaded = new HashMap<>();
        try {
            var resource = Minecraft.m_91087_().m_91098_().m_213713_(TABLE);
            if (resource.isPresent()) {
                try (var reader = new InputStreamReader(resource.get().m_215507_(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                        JsonObject o = e.getValue().getAsJsonObject();
                        List<Wing> wings = new ArrayList<>();
                        for (Map.Entry<String, JsonElement> b : o.getAsJsonObject("Bones").entrySet()) {
                            JsonObject w = b.getValue().getAsJsonObject();
                            var a = w.getAsJsonArray("Axis");
                            Vector3f axis = new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
                            if (axis.lengthSquared() < 1e-6f) continue;
                            wings.add(new Wing(b.getKey(), axis.normalize(), w.get("Degrees").getAsFloat()));
                        }
                        loaded.put(e.getKey(), new Entry(o.has("DeployTicks") ? o.get("DeployTicks").getAsFloat() : 8F, wings));
                    }
                }
            }
        } catch (Exception failure) {
            LogUtils.getLogger().warn("Folding wing table unreadable: {}", failure.toString());
        }
        table = loaded;
        return table;
    }
}
