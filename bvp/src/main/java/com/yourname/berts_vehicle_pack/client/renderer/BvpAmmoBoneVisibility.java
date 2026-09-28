package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Rounds carried outside the vehicle: a bone named {@code ammo_<Weapon>_<n>} is drawn only while that weapon holds at
 * least n loaded rounds, so a fired rail missile disappears and comes back when the reload completes (the client's
 * synced GunData ammo). Bones are split out of the model by tools/vehgen/split_ammo_bones.py.
 */
final class BvpAmmoBoneVisibility {
    static final String PREFIX = "ammo_";
    private static final Map<PolyMeshModel, List<Entry>> ENTRIES = new WeakHashMap<>();

    private record Entry(BedrockBone bone, String weapon, int rounds) { }

    record Hidden(List<BedrockBone> bones) {
        void restore() { for (BedrockBone bone : bones) bone.visible = true; }
    }

    private static List<Entry> entries(PolyMeshModel model) {
        return ENTRIES.computeIfAbsent(model, m -> {
            List<Entry> list = new ArrayList<>();
            for (var e : m.getBoneMap().entrySet()) {
                String name = e.getKey();
                if (!name.startsWith(PREFIX)) continue;
                int split = name.lastIndexOf('_');
                if (split <= PREFIX.length()) continue;
                try {
                    int rounds = Integer.parseInt(name.substring(split + 1));
                    list.add(new Entry(e.getValue(), name.substring(PREFIX.length(), split), Math.max(1, rounds)));
                } catch (NumberFormatException ignored) {
                    // not an ammo bone
                }
            }
            return list.isEmpty() ? List.of() : List.copyOf(list);
        });
    }

    static Hidden apply(GeoVehicleEntity vehicle, PolyMeshModel model) {
        List<Entry> entries = entries(model);
        if (entries.isEmpty()) return null;
        List<BedrockBone> hidden = null;
        for (Entry entry : entries) {
            if (!entry.bone.visible) continue;
            GunData data = vehicle.getGunData(entry.weapon);
            if (data == null || data.ammo.get() >= entry.rounds) continue;
            entry.bone.visible = false;
            if (hidden == null) hidden = new ArrayList<>(2);
            hidden.add(entry.bone);
        }
        return hidden == null ? null : new Hidden(hidden);
    }

    private BvpAmmoBoneVisibility() { }
}
