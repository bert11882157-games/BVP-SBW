package com.yourname.berts_vehicle_pack.armor;

import com.google.gson.JsonObject;
import com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.FakeTarget;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;

import java.util.List;

import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.GSON;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.check;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.near;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.v;

/**
 * Per-face armor thickness ({@code bvp_face_mm}, written by tools/blockbench/bvp_armor_faces.js): the loader reads
 * it from poly_mesh bones and cubes, and a hit on a face with its own thickness is scored with that thickness.
 */
public final class ArmorFaceThicknessTest {
    public static void main(String[] args) {
        cubeFaces();
        polyMeshFaces();
        mismatchedArrayIsIgnored();
        volumesWithoutFaceArmorAreUnchanged();
        System.out.println("PASS per-face armor: cube faces, poly_mesh faces, ray hits, snaps, bad data ignored");
    }

    /** Blockbench cube from [0,0,0] to [16,16,16]: armor-local [0,1]^3 (Blockbench east = armor +X). */
    private static void cubeFaces() {
        String geo = "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"armor_hull\"},"
                + "{\"name\":\"plate__30mm__box\",\"parent\":\"armor_hull\",\"cubes\":[{\"origin\":[-16,0,0],"
                + "\"size\":[16,16,16],\"bvp_face_mm\":{\"north\":80,\"east\":5,\"up\":null,\"down\":\"x\"}}]}]}]}";
        ArmorMeshLoader.Result result = ArmorMeshLoader.load("cube", GSON.fromJson(geo, JsonObject.class), false);
        check(result.warnings.isEmpty(), "cube is clean: " + result.warnings);
        ArmorBox plate = result.plates.get(0);
        ArmorMeshVolume mesh = (ArmorMeshVolume) plate.volume;
        check(mesh.hasFaceArmor(), "cube has face armor");
        near(mesh.faceArmorMm(v(0.5, 0.5, 0.0), v(0, 0, -1)), 80.0D, 0.0D, "north face (-Z)");
        near(mesh.faceArmorMm(v(1.0, 0.5, 0.5), v(1, 0, 0)), 5.0D, 0.0D, "east face (+X)");
        check(Double.isNaN(mesh.faceArmorMm(v(0.0, 0.5, 0.5), v(-1, 0, 0))), "west face: volume default");
        check(Double.isNaN(mesh.faceArmorMm(v(0.5, 1.0, 0.5), v(0, 1, 0))), "null entry: volume default");
        check(Double.isNaN(mesh.faceArmorMm(v(0.5, 0.0, 0.5), v(0, -1, 0))), "non-number entry: volume default");

        FakeTarget target = FakeTarget.of("face_fixture", 0.0D, 0.0D);
        List<ArmorBox> plates = result.plates;
        ArmorHit front = ArmorHitResolver.findFirstBoxOnRay(target, plates, v(0.5, 0.5, -3), v(0, 0, 1), 10, 0.1);
        check(front != null && front.plate == plate, "ray hits the cube from the north");
        near(front.armorMm(), 80.0D, 0.0D, "north ray: face thickness");
        ArmorHit side = ArmorHitResolver.findFirstBoxOnRay(target, plates, v(4, 0.4, 0.6), v(-1, 0, 0), 10, 0.1);
        near(side.armorMm(), 5.0D, 0.0D, "east ray: face thickness");
        ArmorHit other = ArmorHitResolver.findFirstBoxOnRay(target, plates, v(-4, 0.4, 0.6), v(1, 0, 0), 10, 0.1);
        near(other.armorMm(), 30.0D, 0.0D, "west ray: bone thickness");
        // a hit found without a ray normal (snap / proximity) looks the face up from the contact point
        ArmorHit snapped = new ArmorHit(plate, v(0.5, 0.5, 0.0), v(0.5, 0.5, 0.0), 0.0D);
        near(snapped.armorMm(), 80.0D, 0.0D, "snap on the north face");
        ArmorHit proximity = ArmorHit.proximity(plate, v(0.0, 0.5, 0.5), v(0.0, 0.5, 0.5), 0.02D);
        near(proximity.armorMm(), 30.0D, 0.0D, "proximity on the west face: bone thickness");
    }

    /** Meshy-padded tetrahedron, one bvp_face_mm entry per polygon. */
    private static void polyMeshFaces() {
        String positions = "[[0,0,0],[-16,0,0],[0,16,0],[0,0,16]]";
        String polys = "[[[0,0,0],[2,0,0],[1,0,0],[0,0,0]],[[0,0,0],[1,0,0],[3,0,0],[0,0,0]],"
                + "[[0,0,0],[3,0,0],[2,0,0],[0,0,0]],[[1,0,0],[2,0,0],[3,0,0],[1,0,0]]]";
        String geo = "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"hull\"},"
                + "{\"name\":\"plate__10mm__tet\",\"parent\":\"hull\",\"poly_mesh\":{\"positions\":" + positions
                + ",\"polys\":" + polys + "},\"bvp_face_mm\":[100,null,20,-4]}]}]}";
        ArmorMeshLoader.Result result = ArmorMeshLoader.load("tet", GSON.fromJson(geo, JsonObject.class), false);
        check(result.warnings.isEmpty(), "tetrahedron is clean: " + result.warnings);
        ArmorBox plate = result.plates.get(0);
        ArmorMeshVolume mesh = (ArmorMeshVolume) plate.volume;
        near(mesh.faceArmorMm(v(0.2, 0.2, 0.0), v(0, 0, -1)), 100.0D, 0.0D, "polygon 0 (z = 0)");
        check(Double.isNaN(mesh.faceArmorMm(v(0.2, 0.0, 0.2), v(0, -1, 0))), "polygon 1 null: default");
        near(mesh.faceArmorMm(v(0.0, 0.2, 0.2), v(-1, 0, 0)), 20.0D, 0.0D, "polygon 2 (x = 0)");
        double s = 1.0D / Math.sqrt(3.0D);
        check(Double.isNaN(mesh.faceArmorMm(v(0.3, 0.3, 0.3), v(s, s, s))), "negative entry: default");

        FakeTarget target = FakeTarget.of("face_fixture", 0.0D, 0.0D);
        ArmorHit bottom = ArmorHitResolver.findFirstBoxOnRay(target, result.plates, v(0.2, 0.2, -2), v(0, 0, 1),
                10, 0.1);
        near(bottom.armorMm(), 100.0D, 0.0D, "ray into polygon 0");
        ArmorHit slanted = ArmorHitResolver.findFirstBoxOnRay(target, result.plates, v(2, 2, 2), v(-1, -1, -1),
                10, 0.1);
        near(slanted.armorMm(), 10.0D, 0.0D, "ray into the slanted face: bone thickness");
        // mirrored profiles flip X for the whole volume; the per-face values follow their triangles
        ArmorMeshLoader.Result mirrored = ArmorMeshLoader.load("tet", GSON.fromJson(geo, JsonObject.class), true);
        ArmorMeshVolume flipped = (ArmorMeshVolume) mirrored.plates.get(0).volume;
        near(flipped.faceArmorMm(v(-0.2, 0.2, 0.0), v(0, 0, -1)), 100.0D, 0.0D, "mirrored polygon 0");
        near(flipped.faceArmorMm(v(0.0, 0.2, 0.2), v(1, 0, 0)), 20.0D, 0.0D, "mirrored polygon 2");
    }

    private static void mismatchedArrayIsIgnored() {
        String geo = "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"hull\"},"
                + "{\"name\":\"plate__10mm__tet\",\"parent\":\"hull\",\"poly_mesh\":{\"positions\":"
                + "[[0,0,0],[-16,0,0],[0,16,0],[0,0,16]],\"polys\":[[[0,0,0],[2,0,0],[1,0,0],[0,0,0]],"
                + "[[0,0,0],[1,0,0],[3,0,0],[0,0,0]],[[0,0,0],[3,0,0],[2,0,0],[0,0,0]],"
                + "[[1,0,0],[2,0,0],[3,0,0],[1,0,0]]]},\"bvp_face_mm\":[100,20]}]}]}";
        ArmorMeshLoader.Result result = ArmorMeshLoader.load("bad", GSON.fromJson(geo, JsonObject.class), false);
        check(result.warnings.stream().anyMatch(w -> w.contains("bvp_face_mm") && w.contains("ignored")),
                "length mismatch warned: " + result.warnings);
        check(!((ArmorMeshVolume) result.plates.get(0).volume).hasFaceArmor(), "mismatched array ignored");
    }

    private static void volumesWithoutFaceArmorAreUnchanged() {
        String geo = "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"armor_hull\"},"
                + "{\"name\":\"plate__45mm__plain\",\"parent\":\"armor_hull\",\"cubes\":[{\"origin\":[-16,0,0],"
                + "\"size\":[16,16,16],\"bvp_face_mm\":{\"north\":null}}]}]}]}";
        ArmorMeshLoader.Result result = ArmorMeshLoader.load("plain", GSON.fromJson(geo, JsonObject.class), false);
        ArmorBox plate = result.plates.get(0);
        check(!((ArmorMeshVolume) plate.volume).hasFaceArmor(), "all-default map: no face armor");
        ArmorHit hit = ArmorHitResolver.findFirstBoxOnRay(FakeTarget.of("face_fixture", 0.0D, 0.0D), result.plates,
                v(0.5, 0.5, -3), v(0, 0, 1), 10, 0.1);
        near(hit.armorMm(), 45.0D, 0.0D, "plate thickness");
    }
}
