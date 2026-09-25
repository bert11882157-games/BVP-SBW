package com.yourname.berts_vehicle_pack.armor;

import com.google.gson.JsonObject;
import com.yourname.berts_vehicle_pack.armor.ArmorMeshLoader.Frame;
import com.yourname.berts_vehicle_pack.armor.ArmorMeshLoader.Kind;
import com.yourname.berts_vehicle_pack.armor.ArmorMeshLoader.VolumeName;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.GSON;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.check;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.near;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.nearVec;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.v;

/** Headless checks for the Blockbench armor file loader: naming, frames, transforms, mirroring, merging. */
public final class ArmorMeshLoaderTest {
    public static void main(String[] args) {
        volumeNames();
        frameNames();
        blockbenchBoneRotationConvention();
        blockbenchCubeConvention();
        frameBoneRotationIsIgnored();
        polyMeshLayouts();
        mirrorHandling();
        childBonesMergeAndFramesComeFromAncestors();
        unknownAndInvalidNames();
        legacyGeometryLayout();
        profileIntegration();
        meshResourceIds();
        System.out.println("PASS armor mesh loader: names, frames, Blockbench transforms, cubes, mirroring, merging,"
                + " profile replacement");
    }

    private static void volumeNames() {
        VolumeName plate = ArmorMeshLoader.parseName("plate__80mm__ufp");
        check(plate.kind() == Kind.PLATE && plate.error() == null && plate.name().equals("ufp"), "plate");
        near(plate.armorMm(), 80.0D, 0.0D, "plate thickness");
        near(ArmorMeshLoader.parseName("plate__12.7mm__skirt").armorMm(), 12.7D, 1.0E-12D, "decimal thickness");
        near(ArmorMeshLoader.parseName("plate__12p5__x").armorMm(), 12.5D, 1.0E-12D, "p decimal, no unit");
        near(ArmorMeshLoader.parseName("armor__200__x").armorMm(), 200.0D, 0.0D, "armor alias");
        near(ArmorMeshLoader.parseName("PLATE__90MM__Upper").armorMm(), 90.0D, 0.0D, "kind and unit ignore case");
        check(ArmorMeshLoader.parseName("PLATE__90MM__Upper").name().equals("Upper"), "name keeps case");
        check(ArmorMeshLoader.parseName("plate__abc__x").error() != null, "bad thickness");
        check(ArmorMeshLoader.parseName("plate__x").error() != null, "plate without thickness");
        check(ArmorMeshLoader.parseName("plate__80mm__").error() != null, "empty name");

        VolumeName era = ArmorMeshLoader.parseName("era__kontakt5__front_03");
        check(era.kind() == Kind.ERA && era.name().equals("front_03") && era.eraType().equals("kontakt5"), "era");
        near(era.kineticMm(), 120.0D, 0.0D, "kontakt5 default kinetic");
        near(era.chemicalMm(), 450.0D, 0.0D, "kontakt5 default chemical");
        VolumeName relict = ArmorMeshLoader.parseName("era__relict_ke210_ce650__cheek");
        check(relict.eraType().equals("relict"), "relict type");
        near(relict.kineticMm(), 210.0D, 0.0D, "explicit kinetic");
        near(relict.chemicalMm(), 650.0D, 0.0D, "explicit chemical");
        VolumeName unknownEra = ArmorMeshLoader.parseName("era__nova__x");
        near(unknownEra.kineticMm(), 25.0D, 0.0D, "unknown type kinetic default");
        near(unknownEra.chemicalMm(), 400.0D, 0.0D, "unknown type chemical default");
        check(ArmorMeshLoader.parseName("era____x").eraType().equals("kontakt1"), "default type");

        check(ArmorMeshLoader.parseName("engine____block").kind() == Kind.ENGINE, "engine");
        check(ArmorMeshLoader.parseName("engine____block").name().equals("block"), "engine name");
        check(ArmorMeshLoader.parseName("engine__block").name().equals("block"), "engine with one separator");
        VolumeName ammo = ArmorMeshLoader.parseName("ammo__10hp__rack_00");
        check(ammo.kind() == Kind.AMMO && ammo.name().equals("rack_00"), "ammo");
        VolumeName module = ArmorMeshLoader.parseName("module__weaponsystems__gun");
        check(module.kind() == Kind.MODULE && module.module().equals("weaponsystems") && module.unified(), "module");
        VolumeName split = ArmorMeshLoader.parseName("module__turret_drive-split__td");
        check(split.module().equals("turret_drive") && !split.unified(), "split module");
        check(ArmorMeshLoader.parseName("track__left__front").kind() == Kind.TRACK, "track");
        check(ArmorMeshLoader.parseName("internal____fuel").kind() == Kind.INTERNAL, "internal");
        check(ArmorMeshLoader.parseName("wheel__L") == null, "unknown kind is not armor");
        check(ArmorMeshLoader.parseName("wreck_wing_left__hull") == null, "visual model bone is not armor");
        check(ArmorMeshLoader.parseName("hull") == null && ArmorMeshLoader.parseName("__x") == null, "no kind");
    }

    private static void frameNames() {
        check(ArmorMeshLoader.frameOf("hull") == Frame.HULL, "hull");
        check(ArmorMeshLoader.frameOf("HULL") == Frame.HULL, "case");
        check(ArmorMeshLoader.frameOf("armor_turret") == Frame.TURRET, "armor_ prefix");
        check(ArmorMeshLoader.frameOf("armor-turret") == Frame.TURRET, "armor- prefix");
        check(ArmorMeshLoader.frameOf("turret2") == Frame.TURRET, "Blockbench duplicate digits");
        check(ArmorMeshLoader.frameOf("barell") == Frame.BARREL, "visual model barrel spelling");
        check(ArmorMeshLoader.frameOf("armor_barrel3") == Frame.BARREL, "barrel");
        check(ArmorMeshLoader.frameOf("turret_ring") == null && ArmorMeshLoader.frameOf("hullx") == null,
                "other names are not frames");
    }

    /**
     * Blockbench shows a group with origin o and rotation r as p -> o + Rz(Ry(Rx(p - o))) in its own
     * space, and exports pivot (-o.x, o.y, o.z), rotation (-r.x, -r.y, r.z) and Meshy positions
     * (-p.x, p.y, p.z). The loader must reproduce what Blockbench shows: armor-local = shown / 16 on a
     * non-mirrored profile.
     */
    private static void blockbenchBoneRotationConvention() {
        Random random = new Random(41);
        for (int trial = 0; trial < 50; trial++) {
            double[] outerOrigin = randomVector(random, 20);
            double[] outerRotation = randomVector(random, 170);
            double[] innerOrigin = randomVector(random, 20);
            double[] innerRotation = randomVector(random, 170);
            double[][] points = {randomVector(random, 30), randomVector(random, 30), randomVector(random, 30),
                    randomVector(random, 30)};
            StringBuilder positions = new StringBuilder();
            for (int i = 0; i < points.length; i++) {
                if (i > 0) positions.append(',');
                positions.append(json(-points[i][0], points[i][1], points[i][2]));
            }
            String geo = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"bones\":["
                    + "{\"name\":\"armor_hull\",\"pivot\":[0,0,0]},"
                    + "{\"name\":\"group\",\"parent\":\"armor_hull\",\"pivot\":" + exportedPivot(outerOrigin)
                    + ",\"rotation\":" + exportedRotation(outerRotation) + "},"
                    + "{\"name\":\"plate__50mm__tet\",\"parent\":\"group\",\"pivot\":" + exportedPivot(innerOrigin)
                    + ",\"rotation\":" + exportedRotation(innerRotation) + ",\"poly_mesh\":{\"positions\":["
                    + positions + "],\"polys\":[[[0,0,0],[1,0,0],[2,0,0]],[[0,0,0],[3,0,0],[1,0,0]],"
                    + "[[1,0,0],[3,0,0],[2,0,0]],[[0,0,0],[2,0,0],[3,0,0]]]}}]}]}";
            // The volume bone is a volume, not a plain child, so give it a frame-free parent chain.
            ArmorMeshLoader.Result result = ArmorMeshLoader.load("bone", GSON.fromJson(geo, JsonObject.class), false);
            check(result.plates.size() == 1, "one plate");
            ArmorBox plate = result.plates.get(0);
            List<double[]> expected = new ArrayList<>();
            for (double[] point : points) {
                double[] inner = blockbenchShow(point, innerOrigin, innerRotation);
                double[] shown = blockbenchShow(inner, outerOrigin, outerRotation);
                expected.add(new double[] {shown[0] / 16.0D, shown[1] / 16.0D, shown[2] / 16.0D});
            }
            assertSameVertices(plate, expected, "bone transform " + trial);
        }
    }

    /** Blockbench cube export: origin.x = -(from.x + size.x), pivot and rotation as for bones. */
    private static void blockbenchCubeConvention() {
        Random random = new Random(43);
        for (int trial = 0; trial < 30; trial++) {
            double[] from = randomVector(random, 10);
            double[] size = {1 + random.nextDouble() * 5, 1 + random.nextDouble() * 5, 1 + random.nextDouble() * 5};
            double inflate = random.nextDouble() * 0.5;
            double[] origin = randomVector(random, 10);
            double[] rotation = {random.nextDouble() * 90 - 45, 0, 0};
            rotation[random.nextInt(3)] = random.nextDouble() * 90 - 45; // Blockbench: one axis per cube
            double[] exportedOrigin = {-(from[0] + size[0]), from[1], from[2]};
            String geo = "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"armor_hull\"},"
                    + "{\"name\":\"plate__30mm__cube\",\"parent\":\"armor_hull\",\"cubes\":[{\"origin\":"
                    + json(exportedOrigin[0], exportedOrigin[1], exportedOrigin[2]) + ",\"size\":"
                    + json(size[0], size[1], size[2]) + ",\"inflate\":" + inflate + ",\"pivot\":"
                    + exportedPivot(origin) + ",\"rotation\":" + exportedRotation(rotation) + "}]}]}]}";
            ArmorMeshLoader.Result result = ArmorMeshLoader.load("cube", GSON.fromJson(geo, JsonObject.class), false);
            check(result.warnings.isEmpty(), "cube is clean: " + result.warnings);
            ArmorBox plate = result.plates.get(0);
            check(plate.volume instanceof ArmorMeshVolume mesh && mesh.triangleCount() == 12, "cube = 12 triangles");
            List<double[]> expected = new ArrayList<>();
            for (int corner = 0; corner < 8; corner++) {
                double[] point = {
                        (corner & 1) == 0 ? from[0] - inflate : from[0] + size[0] + inflate,
                        (corner & 2) == 0 ? from[1] - inflate : from[1] + size[1] + inflate,
                        (corner & 4) == 0 ? from[2] - inflate : from[2] + size[2] + inflate};
                double[] shown = blockbenchShow(point, origin, rotation);
                expected.add(new double[] {shown[0] / 16.0D, shown[1] / 16.0D, shown[2] / 16.0D});
            }
            assertSameVertices(plate, expected, "cube transform " + trial);
            near(plate.volume.volume(), (size[0] + 2 * inflate) * (size[1] + 2 * inflate) * (size[2] + 2 * inflate)
                    / 4096.0D, 1.0E-9D, "cube volume " + trial);
        }
    }

    private static void frameBoneRotationIsIgnored() {
        String geo = "{\"minecraft:geometry\":[{\"bones\":["
                + "{\"name\":\"armor_turret\",\"pivot\":[3,4,5],\"rotation\":[0,35,0]},"
                + "{\"name\":\"plate__40mm__side\",\"parent\":\"armor_turret\",\"cubes\":[{\"origin\":[-16,0,0],"
                + "\"size\":[16,16,16]}]}]}]}";
        ArmorMeshLoader.Result result = ArmorMeshLoader.load("frame", GSON.fromJson(geo, JsonObject.class), false);
        ArmorBox plate = result.plates.get(0);
        check(plate.isTurretFrame(), "turret frame");
        double[] b = plate.volume.bounds();
        nearVec(v(b[0], b[1], b[2]), v(0, 0, 0), 1.0E-12D, "rest pose min");
        nearVec(v(b[3], b[4], b[5]), v(1, 1, 1), 1.0E-12D, "rest pose max");
        check(result.warnings.stream().anyMatch(w -> w.contains("rotation on frame bone")), "rotation warned");
    }

    private static void polyMeshLayouts() {
        // A unit tetrahedron written three ways: Meshy-padded quads, a tri_list and plain triangles.
        String positions = "[[0,0,0],[-16,0,0],[0,16,0],[0,0,16]]";
        String padded = "[[[0,0,0],[2,0,0],[1,0,0],[0,0,0]],[[0,0,0],[1,0,0],[3,0,0],[0,0,0]],"
                + "[[0,0,0],[3,0,0],[2,0,0],[0,0,0]],[[1,0,0],[2,0,0],[3,0,0],[1,0,0]]]";
        String geo = "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"hull\"},"
                + "{\"name\":\"plate__10mm__padded\",\"parent\":\"hull\",\"poly_mesh\":{\"positions\":" + positions
                + ",\"polys\":" + padded + "}},"
                + "{\"name\":\"plate__10mm__trilist\",\"parent\":\"hull\",\"poly_mesh\":{\"positions\":"
                + "[[0,0,0],[0,16,0],[-16,0,0],[0,0,0],[-16,0,0],[0,0,16],[0,0,0],[0,0,16],[0,16,0],"
                + "[-16,0,0],[0,16,0],[0,0,16]],\"polys\":\"tri_list\"}}]}]}";
        ArmorMeshLoader.Result result = ArmorMeshLoader.load("layouts", GSON.fromJson(geo, JsonObject.class), false);
        check(result.warnings.isEmpty(), "layouts are clean: " + result.warnings);
        check(result.plates.size() == 2, "two plates");
        for (ArmorBox plate : result.plates) {
            ArmorMeshVolume mesh = (ArmorMeshVolume) plate.volume;
            check(mesh.triangleCount() == 4 && mesh.isClosed(), plate.name + " is a closed tetrahedron");
            near(mesh.volume(), 1.0D / 6.0D, 1.0E-12D, plate.name + " volume");
            check(mesh.contains(v(0.1, 0.1, 0.1)), plate.name + " inside (x mapped to +x)");
        }
    }

    private static void mirrorHandling() {
        String geo = "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"hull\"},"
                + "{\"name\":\"plate__10mm__p\",\"parent\":\"hull\",\"cubes\":[{\"origin\":[8,0,0],\"size\":[8,8,8]}]}"
                + "]}]}";
        JsonObject root = GSON.fromJson(geo, JsonObject.class);
        double[] normal = ArmorMeshLoader.load("normal", root, false).plates.get(0).volume.bounds();
        double[] mirrored = ArmorMeshLoader.load("mirrored", root, true).plates.get(0).volume.bounds();
        near(normal[0], -1.0D, 1.0E-12D, "normal profile: armor x = -geo x / 16 (min)");
        near(normal[3], -0.5D, 1.0E-12D, "normal profile: armor x = -geo x / 16 (max)");
        near(mirrored[0], 0.5D, 1.0E-12D, "mirrored profile: armor x = geo x / 16 (min)");
        near(mirrored[3], 1.0D, 1.0E-12D, "mirrored profile: armor x = geo x / 16 (max)");
        check(ArmorProfiles.mirrorsProfileX("t72a") && ArmorProfiles.mirrorsProfileX("t72b")
                && !ArmorProfiles.mirrorsProfileX("t90a"), "mirrored profile ids");
    }

    private static void childBonesMergeAndFramesComeFromAncestors() {
        String geo = "{\"minecraft:geometry\":[{\"bones\":["
                + "{\"name\":\"hull\"},{\"name\":\"turret\",\"parent\":\"hull\"},"
                + "{\"name\":\"barell\",\"parent\":\"turret\"},"
                + "{\"name\":\"plate__20mm__mantlet\",\"parent\":\"barell\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[4,4,4]}]},"
                + "{\"name\":\"extra_piece\",\"parent\":\"plate__20mm__mantlet\",\"cubes\":[{\"origin\":[0,0,32],\"size\":[4,4,4]}]},"
                + "{\"name\":\"loose\",\"parent\":\"turret\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[4,4,4]}]},"
                + "{\"name\":\"engine____pack\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[4,4,4]}]},"
                + "{\"name\":\"internal____engine_aux\",\"parent\":\"hull\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[4,4,4]}]},"
                + "{\"name\":\"internal____fuel\",\"parent\":\"hull\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[4,4,4]}]}"
                + "]}]}";
        ArmorMeshLoader.Result result = ArmorMeshLoader.load("merge", GSON.fromJson(geo, JsonObject.class), false);
        check(result.plates.size() == 1, "loose geometry under a frame bone is not a volume");
        ArmorBox mantlet = result.plates.get(0);
        check(mantlet.isBarrelFrame(), "barrel frame from the visual 'barell' ancestor");
        check(((ArmorMeshVolume) mantlet.volume).partCount() == 2, "child bone merged as a second part");
        check(result.engines.size() == 2 && result.internals.size() == 1, "engine_* internal counts as engine");
        check(result.engines.get(0).frame.equals("hull"), "no frame ancestor -> hull");
        check(result.warnings.stream().anyMatch(w -> w.contains("engine____pack") && w.contains("hull frame")),
                "missing frame ancestor warned");
    }

    private static void unknownAndInvalidNames() {
        String geo = "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"armor_hull\"},"
                + "{\"name\":\"wreck_wing_left__hull\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[4,4,4]}]},"
                + "{\"name\":\"plate__thick__bad\",\"parent\":\"armor_hull\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[4,4,4]}]},"
                + "{\"name\":\"plate__10mm__dup\",\"parent\":\"armor_hull\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[4,4,4]}]},"
                + "{\"name\":\"plate__20mm__dup\",\"parent\":\"armor_hull\",\"cubes\":[{\"origin\":[8,0,0],\"size\":[4,4,4]}]},"
                + "{\"name\":\"plate__30mm__empty\",\"parent\":\"armor_hull\"},"
                + "{\"name\":\"armor_turret\",\"parent\":\"armor_hull\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[4,4,4]}]}"
                + "]}]}";
        ArmorMeshLoader.Result result = ArmorMeshLoader.load("names", GSON.fromJson(geo, JsonObject.class), false);
        String warnings = String.join("\n", result.warnings).toLowerCase(Locale.ROOT);
        check(result.plates.size() == 2, "two valid plates");
        check(!warnings.contains("wreck_wing"), "unknown kinds are ignored silently");
        check(warnings.contains("plate__thick__bad") && warnings.contains("thickness"), "bad param warned");
        check(warnings.contains("used twice"), "duplicate volume name warned");
        check(warnings.contains("plate__30mm__empty") && warnings.contains("no geometry"), "empty volume warned");
        check(warnings.contains("geometry directly in frame bone"), "loose frame geometry warned");
    }

    private static void legacyGeometryLayout() {
        String geo = "{\"format_version\":\"1.10.0\",\"geometry.legacy\":{\"bones\":[{\"name\":\"hull\"},"
                + "{\"name\":\"plate__10mm__p\",\"parent\":\"hull\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[16,16,16]}]}]}}";
        check(ArmorMeshLoader.load("legacy", GSON.fromJson(geo, JsonObject.class), false).plates.size() == 1,
                "legacy geometry.<name> layout");
    }

    private static void profileIntegration() {
        JsonObject settings = GSON.fromJson("{\"impact_tolerance\":0.4,\"unboxed_hits_penetrate\":false,"
                + "\"plates\":[{\"name\":\"box\",\"center\":[0,1,0],\"half_size\":[1,1,1],\"rotation\":[0,0,0],"
                + "\"armor_mm\":50}],\"engines\":[{\"name\":\"engine_block_00\",\"center\":[0,0,0],"
                + "\"half_size\":[1,1,1],\"rotation\":[0,0,0]}]}", JsonObject.class);
        ArmorProfile boxes = ArmorProfiles.parse("fixture", settings);
        check(!boxes.usesArmorMesh() && boxes.plates.size() == 1 && boxes.engineBoxes.size() == 1, "box profile");
        String geo = "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"armor_hull\"},"
                + "{\"name\":\"plate__80mm__a\",\"parent\":\"armor_hull\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[16,16,1]}]},"
                + "{\"name\":\"plate__60mm__b\",\"parent\":\"armor_hull\",\"cubes\":[{\"origin\":[0,0,4],\"size\":[16,16,1]}]}"
                + "]}]}";
        ArmorProfile mesh = ArmorProfiles.withMesh(boxes, GSON.fromJson(geo, JsonObject.class), "armor_mesh/fixture.geo.json");
        check(mesh.usesArmorMesh() && mesh.meshSource.equals("armor_mesh/fixture.geo.json"), "mesh source");
        check(mesh.plates.size() == 2 && mesh.plates.get(0).isMesh(), "mesh plates replace the box plates");
        check(mesh.engineBoxes.size() == 1 && !mesh.engineBoxes.get(0).isMesh(),
                "a category the mesh leaves out keeps its boxes (e.g. ERA kept as JSON boxes)");
        String withEra = "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"armor_hull\"},"
                + "{\"name\":\"plate__80mm__a\",\"parent\":\"armor_hull\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[16,16,1]}]},"
                + "{\"name\":\"era__kontakt5__brick_00\",\"parent\":\"armor_hull\",\"cubes\":[{\"origin\":[0,0,-2],\"size\":[4,4,1]}]}"
                + "]}]}";
        ArmorProfile eraMesh = ArmorProfiles.withMesh(boxes, GSON.fromJson(withEra, JsonObject.class), "era");
        check(eraMesh.eraBoxes.size() == 1 && eraMesh.eraBoxes.get(0).isMesh()
                && "kontakt5".equals(eraMesh.eraBoxes.get(0).eraType)
                && eraMesh.eraBoxes.get(0).kineticProtectionMm == 120.0D, "ERA authored as a Blockbench cube");
        check(mesh.plates.get(0).isMesh() && mesh.plates.get(0).armorMm == 80.0D, "mesh plate thickness");
        near(mesh.impactTolerance, 0.4D, 0.0D, "settings kept");
        check(!mesh.unboxedHitsPenetrate && mesh.hasImpactVolumes(), "strictness kept, volumes present");
        ArmorProfile empty = ArmorProfiles.withMesh(boxes, GSON.fromJson(
                "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"hull\"}]}]}", JsonObject.class), "x");
        check(empty == boxes, "a mesh without volumes keeps the boxes");
    }

    private static void meshResourceIds() {
        check("t72b".equals(ArmorProfiles.meshResourceId("t72b", new JsonObject())), "default: own id");
        check("t72b".equals(ArmorProfiles.meshResourceId("t72b", null)), "no JSON: own id");
        check(ArmorProfiles.meshResourceId("t72b", GSON.fromJson("{\"armor_mesh\":false}", JsonObject.class)) == null,
                "disabled");
        check("t72b".equals(ArmorProfiles.meshResourceId("t72b", GSON.fromJson("{\"armor_mesh\":true}",
                JsonObject.class))), "enabled");
        check("t72b3".equals(ArmorProfiles.meshResourceId("t72b3_ubh_cope",
                GSON.fromJson("{\"armor_mesh\":\"t72b3\"}", JsonObject.class))), "shared mesh");
        check("t72b".equals(ArmorProfiles.meshResourceId("t72b",
                GSON.fromJson("{\"armor_mesh\":\"../evil\"}", JsonObject.class))), "unsafe id rejected");
    }

    // ---------------------------------------------------------------- helpers

    private static double[] blockbenchShow(double[] point, double[] origin, double[] rotationDeg) {
        Vec relative = v(point[0] - origin[0], point[1] - origin[1], point[2] - origin[2]);
        Vec rotated = relative.rotateX(rotationDeg[0]).rotateY(rotationDeg[1]).rotateZ(rotationDeg[2]);
        return new double[] {rotated.x + origin[0], rotated.y + origin[1], rotated.z + origin[2]};
    }

    private static String exportedPivot(double[] origin) {
        return json(-origin[0], origin[1], origin[2]);
    }

    private static String exportedRotation(double[] rotation) {
        return json(-rotation[0], -rotation[1], rotation[2]);
    }

    private static String json(double x, double y, double z) {
        return "[" + x + "," + y + "," + z + "]";
    }

    private static double[] randomVector(Random random, double scale) {
        return new double[] {(random.nextDouble() * 2 - 1) * scale, (random.nextDouble() * 2 - 1) * scale,
                (random.nextDouble() * 2 - 1) * scale};
    }

    private static void assertSameVertices(ArmorBox box, List<double[]> expected, String label) {
        check(box.volume.vertexCount() == expected.size(), label + ": vertex count " + box.volume.vertexCount());
        double[] vertex = new double[3];
        for (double[] want : expected) {
            boolean found = false;
            for (int i = 0; i < box.volume.vertexCount() && !found; i++) {
                box.volume.vertex(i, vertex);
                found = Math.abs(vertex[0] - want[0]) < 1.0E-9D && Math.abs(vertex[1] - want[1]) < 1.0E-9D
                        && Math.abs(vertex[2] - want[2]) < 1.0E-9D;
            }
            check(found, label + ": missing vertex " + want[0] + ", " + want[1] + ", " + want[2]);
        }
    }
}
