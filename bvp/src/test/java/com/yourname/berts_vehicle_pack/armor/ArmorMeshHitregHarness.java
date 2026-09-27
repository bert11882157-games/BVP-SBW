package com.yourname.berts_vehicle_pack.armor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.BoxQuery;
import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.ShotTrace;
import com.yourname.berts_vehicle_pack.armor.ArmorModuleResolver.InternalModuleHits;
import com.yourname.berts_vehicle_pack.armor.ArmorModuleResolver.ModuleHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Headless hit registration harness for a mesh armor file: resolves ground-truth shot rays (written by
 * tools/armor_mesh/hitreg_rays.py from the vehicle's visual model) with the game's own armor code path
 * (ArmorProfiles.withMesh, ArmorHitResolver, ArmorModuleResolver, the penetration angle rule) and writes one
 * CSV row per ray for tools/armor_mesh/hitreg_report.py.
 *
 * <pre>
 * java ... ArmorMeshHitregHarness &lt;id&gt; &lt;armor.json&gt; &lt;mesh.geo.json&gt; &lt;rays.jsonl&gt; &lt;out.csv&gt;
 *      &lt;turretPivot x,y,z (vehicle-local)&gt; &lt;barrelPivot x,y,z (turret-relative)&gt; &lt;yaw,yaw,...&gt;
 * </pre>
 *
 * Rays are in the armor-profile frame with the turret at rest; for a non-zero yaw every ray is rotated about the
 * turret pivot with the turret, so a turret-frame result must match the yaw 0 result (frame consistency).
 */
public final class ArmorMeshHitregHarness {
    private ArmorMeshHitregHarness() {
    }

    public static void main(String[] args) throws Exception {
        String id = args[0];
        JsonObject root = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        JsonObject geo = JsonParser.parseString(Files.readString(Path.of(args[2]))).getAsJsonObject();
        ArmorProfile boxes = ArmorProfiles.parse(id, root);
        ArmorProfile profile = ArmorProfiles.withMesh(boxes, geo, "harness");
        System.out.printf(Locale.ROOT, "profile %s: %d plates, %d era, %d engines, %d ammo, %d modules, %d tracks, mesh=%s%n",
                id, profile.plates.size(), profile.eraBoxes.size(), profile.engineBoxes.size(),
                profile.ammoRacks.size(), profile.moduleBoxes.size(), profile.trackBoxes.size(), profile.usesArmorMesh());
        Vec3 turretPivot = vec3(args[5]);
        Vec3 barrelPivot = vec3(args[6]);
        Vec pivot = new Vec(-turretPivot.f_82479_, turretPivot.f_82480_, -turretPivot.f_82481_);
        String[] yaws = args[7].split(",");
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(args[4]), StandardCharsets.UTF_8)) {
            out.write("ray,yaw,cat,frame,outcome,volume,volume_frame,ray_hit,dist,gap,nx,ny,nz,cos,armor_mm,eff_mm,"
                    + "engine,ammo,era,model_t,core_t,exact\n");
            for (String yawText : yaws) {
                double yaw = Double.parseDouble(yawText);
                HarnessTarget target = new HarnessTarget(id, pivot, yaw, ArmorCoordinateFrame.renderedBarrelFrame(
                        turretPivot, barrelPivot, yaw, 0.0D, ArmorProfiles.mirrorsProfileX(id)));
                int index = 0;
                try (BufferedReader in = Files.newBufferedReader(Path.of(args[3]), StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        JsonObject ray = JsonParser.parseString(line).getAsJsonObject();
                        Vec direction = rotate(vec(ray.getAsJsonArray("d")), yaw);
                        Vec contact = pivot.add(rotate(vec(ray.getAsJsonArray("hit")).subtract(pivot), yaw));
                        ShotTrace trace = new ShotTrace(new Vec3(0, 0, 0), direction,
                                contact.subtract(direction.scale(ArmorHitResolver.ARMOR_RAY_BACKTRACE_BLOCKS)), contact);
                        BoxQuery query = ArmorHitResolver.findBestBoxWithNearestFallback(target, profile.plates, trace,
                                ArmorHitResolver.ARMOR_RAY_DISTANCE_BLOCKS, profile.impactTolerance);
                        ArmorHit armor = query.hit();
                        ModuleHit track = ArmorModuleResolver.findDirectTrackHit(target, profile, trace);
                        ModuleHit module = ArmorModuleResolver.findDirectModuleHit(target, profile, trace);
                        ModuleHit exposed = ArmorModuleResolver.nearestExposed(armor, track, module);
                        ArmorHit era = ArmorHitResolver.findNearestBoxAtImpact(target, profile.eraBoxes, contact,
                                profile.impactTolerance).hit();
                        String outcome;
                        StringBuilder row = new StringBuilder();
                        ArmorHit shown = null;
                        if (exposed != null) {
                            outcome = exposed.trackSide.isEmpty() ? "module" : "track";
                            shown = exposed.hit;
                        } else if (armor != null) {
                            outcome = armor.isRayHit() ? "plate" : "plate_proximity";
                            shown = armor;
                        } else {
                            outcome = "miss";
                        }
                        double cos = Double.NaN, eff = Double.NaN;
                        Vec n = null;
                        String engine = "", ammo = "";
                        if (shown != null && outcome.startsWith("plate")) {
                            Vec local = ArmorHitResolver.directionToBoxFrame(target, shown.plate, direction).normalize();
                            n = shown.frameNormal();
                            cos = Math.max(0.05D, Math.abs(local.dot(n)));
                            eff = shown.plate.armorMm / cos;
                            InternalModuleHits internal = ArmorModuleResolver.findInternalHits(target, profile, shown, trace);
                            engine = internal.engineHit == null ? "" : internal.engineHit.plate.name;
                            ammo = internal.ammoRackHit == null ? "" : internal.ammoRackHit.plate.name;
                        }
                        // exact: the shot meets the chosen volume's solid itself, not only its numerical skin
                        boolean exact = false;
                        if (shown != null && shown.isRayHit()) {
                            Vec frameStart = ArmorHitResolver.pointToBoxFrame(target, shown.plate, trace.rayStart);
                            Vec frameDir = ArmorHitResolver.directionToBoxFrame(target, shown.plate, direction).normalize();
                            exact = frameStart != null && Double.isFinite(shown.plate.rayHitDistance(frameStart, frameDir,
                                    ArmorHitResolver.ARMOR_RAY_DISTANCE_BLOCKS, 0.0D));
                        }
                        row.append(index++).append(',').append(yawText).append(',')
                                .append(ray.get("cat").getAsString()).append(',').append(ray.get("frame").getAsString())
                                .append(',').append(outcome).append(',')
                                .append(shown == null ? "" : shown.plate.name).append(',')
                                .append(shown == null ? "" : shown.plate.frame).append(',')
                                .append(shown != null && shown.isRayHit()).append(',')
                                .append(shown == null || !shown.isRayHit() ? "" : fmt(shown.distance - ArmorHitResolver.ARMOR_RAY_BACKTRACE_BLOCKS))
                                .append(',').append(shown == null || shown.isRayHit() ? "" : fmt(shown.proximityGap)).append(',')
                                .append(n == null ? ",," : fmt(n.x) + "," + fmt(n.y) + "," + fmt(n.z)).append(',')
                                .append(Double.isNaN(cos) ? "" : fmt(cos)).append(',')
                                .append(shown == null ? "" : fmt(shown.plate.armorMm)).append(',')
                                .append(Double.isNaN(eff) ? "" : fmt(eff)).append(',')
                                .append(engine).append(',').append(ammo).append(',')
                                .append(era == null ? "" : era.plate.name).append(',')
                                .append(ray.get("t").getAsString()).append(',').append(ray.get("core_t").getAsString())
                                .append(',').append(exact).append('\n');
                        out.write(row.toString());
                    }
                }
                System.out.printf(Locale.ROOT, "yaw %s: %d rays%n", yawText, index);
            }
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private static Vec rotate(Vec v, double yaw) {
        return yaw == 0.0D ? v : v.rotateY(yaw);
    }

    private static Vec vec(JsonArray a) {
        return new Vec(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    private static Vec3 vec3(String csv) {
        String[] p = csv.split(",");
        return new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
    }

    /** A target with a turret pivot/yaw and a barrel frame; no entity behind it. */
    static final class HarnessTarget implements ArmorTarget {
        final String id;
        final Vec pivot;
        final double yaw;
        final ArmorCoordinateFrame.BarrelFrame barrel;

        HarnessTarget(String id, Vec pivot, double yaw, ArmorCoordinateFrame.BarrelFrame barrel) {
            this.id = id;
            this.pivot = pivot;
            this.yaw = yaw;
            this.barrel = barrel;
        }

        @Override public ArmoredVehicleEntity vehicle() { return null; }
        @Override public Level level() { return null; }
        @Override public String armorProfileId() { return id; }
        @Override public Vec worldPointToArmorLocal(Vec3 point) { throw new UnsupportedOperationException(); }
        @Override public Vec3 armorLocalPointToWorld(Vec point) { throw new UnsupportedOperationException(); }
        /** Armor-profile frame is vehicle-local turned 180 degrees about Y (ArmorCoordinateFrame). */
        @Override public Vec3 armorLocalPointToVehicleLocal(Vec point) { return new Vec3(-point.x, point.y, -point.z); }
        @Override public Vec worldDirectionToArmorLocal(Vec3 direction) { throw new UnsupportedOperationException(); }
        @Override public Vec turretPivot() { return pivot; }
        @Override public double turretFrameYaw() { return yaw; }
        @Override public ArmorCoordinateFrame.BarrelFrame barrelFrame() { return barrel; }
    }
}
