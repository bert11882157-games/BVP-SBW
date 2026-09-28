package com.yourname.berts_vehicle_pack.armor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Headless load check for generated mesh armor (tools/armor_mesh/auto_mesh.py): every file goes through the game's
 * own loader and profile merge, and the result is printed per vehicle.
 *
 * <pre>
 * java ... ArmorMeshAutoCheck &lt;armorDir&gt; &lt;meshDir&gt; &lt;id&gt;...
 * </pre>
 *
 * A line per vehicle: volumes, triangles, plates in the merged profile, the ERA/engine/ammo boxes it keeps, and every
 * loader warning. Exit code 1 when a file fails to load, defines no plate or produces a warning.
 */
public final class ArmorMeshAutoCheck {
    private ArmorMeshAutoCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path armorDir = Path.of(args[0]);
        Path meshDir = Path.of(args[1]);
        int bad = 0;
        for (int i = 2; i < args.length; i++) {
            String id = args[i];
            JsonObject root = JsonParser.parseString(Files.readString(armorDir.resolve(id + ".json"))).getAsJsonObject();
            JsonObject geo = JsonParser.parseString(Files.readString(meshDir.resolve(id + ".geo.json"))).getAsJsonObject();
            ArmorMeshLoader.Result result = ArmorMeshLoader.load(id, geo, false);
            ArmorProfile boxes = ArmorProfiles.parse(id, root);
            ArmorProfile merged = ArmorProfiles.withMesh(boxes, geo, "check");
            boolean ok = merged.usesArmorMesh() && !result.plates.isEmpty() && result.warnings.isEmpty();
            if (!ok) bad++;
            System.out.printf(Locale.ROOT,
                    "%s %s: %d volumes, %d triangles, %d mesh plates, %d tracks | profile plates %d, era %d, engines %d, "
                            + "ammo %d, modules %d, tracks %d, mesh=%s, warnings %d%n",
                    ok ? "OK  " : "FAIL", id, result.volumes, result.triangles, result.plates.size(),
                    result.tracks.size(), merged.plates.size(), merged.eraBoxes.size(), merged.engineBoxes.size(),
                    merged.ammoRacks.size(), merged.moduleBoxes.size(), merged.trackBoxes.size(), merged.usesArmorMesh(),
                    result.warnings.size());
            for (String w : result.warnings) System.out.println("     warning: " + w);
        }
        System.out.println(bad == 0 ? "ALL OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
