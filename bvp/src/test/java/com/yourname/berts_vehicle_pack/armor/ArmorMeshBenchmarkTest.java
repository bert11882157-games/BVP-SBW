package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.BoxQuery;
import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.ShotTrace;
import com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.FakeTarget;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.v;

/**
 * Micro-benchmark: microseconds per shot on the largest profile (vt_4a1, 447 plates), box volumes
 * against the same volumes loaded as a mesh template. One "shot" is the resolver work of a real
 * impact: plate ray with nearest fallback, direct track and module rays, the ERA contact query and
 * the internal module rays from the contact.
 */
public final class ArmorMeshBenchmarkTest {
    private static final String PROFILE = "vt_4a1";
    private static final int SHOTS = 4000;
    private static final int ROUNDS = 7;

    public static void main(String[] args) {
        ArmorProfile boxes = ArmorMeshTestSupport.boxProfile(PROFILE);
        ArmorProfile mesh = ArmorProfiles.withMesh(boxes, ArmorMeshTestSupport.exportTemplate(boxes), "benchmark");
        ArmorMeshTestSupport.check(boxes.plates.size() == 447 && mesh.plates.size() == 447, "vt_4a1 has 447 plates");
        FakeTarget target = FakeTarget.of(PROFILE, 23.0D, 3.0D);
        Random random = new Random(99);
        Vec[] starts = new Vec[SHOTS];
        Vec[] directions = new Vec[SHOTS];
        Vec[] impacts = new Vec[SHOTS];
        for (int i = 0; i < SHOTS; i++) {
            ArmorBox plate = boxes.plates.get(random.nextInt(boxes.plates.size()));
            Vec aim = ArmorHitResolver.pointToHullFrame(target, plate, plate.centroid());
            Vec direction = v(random.nextGaussian(), random.nextGaussian() * 0.3, random.nextGaussian()).normalize();
            impacts[i] = aim;
            directions[i] = direction;
            starts[i] = aim.subtract(direction.scale(ArmorHitResolver.ARMOR_RAY_BACKTRACE_BLOCKS));
        }
        long boxChecksum = 0;
        long meshChecksum = 0;
        for (int warm = 0; warm < 3; warm++) {
            boxChecksum = run(boxes, target, starts, directions, impacts);
            meshChecksum = run(mesh, target, starts, directions, impacts);
        }
        // Exact agreement is ArmorMeshEquivalenceTest's job; coincident faces may tie differently here.
        System.out.println("Benchmark result checksums " + (boxChecksum == meshChecksum ? "match" : "differ (ties)"));
        double[] boxTimes = new double[ROUNDS];
        double[] meshTimes = new double[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            long begin = System.nanoTime();
            run(boxes, target, starts, directions, impacts);
            boxTimes[round] = (System.nanoTime() - begin) / 1000.0D / SHOTS;
            begin = System.nanoTime();
            run(mesh, target, starts, directions, impacts);
            meshTimes[round] = (System.nanoTime() - begin) / 1000.0D / SHOTS;
        }
        Arrays.sort(boxTimes);
        Arrays.sort(meshTimes);
        double box = boxTimes[ROUNDS / 2];
        double meshTime = meshTimes[ROUNDS / 2];
        System.out.printf("Armor resolver on %s (%d plates, %d mesh triangles): box %.1f us/shot, mesh template"
                        + " %.1f us/shot (x%.2f), median of %d rounds x %d shots%n", PROFILE, boxes.plates.size(),
                triangles(mesh), box, meshTime, meshTime / box, ROUNDS, SHOTS);
        ArmorMeshTestSupport.check(meshTime < 1000.0D, "mesh resolution stays well under a millisecond per shot");
        System.out.println("PASS armor mesh benchmark");
    }

    private static long run(ArmorProfile profile, FakeTarget target, Vec[] starts, Vec[] directions, Vec[] impacts) {
        long checksum = 0;
        for (int i = 0; i < starts.length; i++) {
            ShotTrace trace = new ShotTrace(null, directions[i], starts[i], impacts[i]);
            BoxQuery armor = ArmorHitResolver.findBestBoxWithNearestFallback(target, profile.plates, trace,
                    ArmorHitResolver.ARMOR_RAY_DISTANCE_BLOCKS, profile.impactTolerance);
            ArmorHit track = ArmorHitResolver.findFirstBoxOnRay(target, profile.trackBoxes, trace.rayStart,
                    trace.hullShotDirection, ArmorHitResolver.ARMOR_RAY_DISTANCE_BLOCKS, profile.impactTolerance);
            ArmorHit module = ArmorHitResolver.findFirstBoxOnRay(target, profile.moduleBoxes, trace.rayStart,
                    trace.hullShotDirection, ArmorHitResolver.ARMOR_RAY_DISTANCE_BLOCKS, profile.impactTolerance);
            BoxQuery era = ArmorHitResolver.findNearestBoxAtImpact(target, profile.eraBoxes,
                    trace.hullImpactFallback, 0.45D);
            Vec origin = armor.hit() == null ? trace.hullImpactFallback : armor.hit().hullImpact;
            for (List<ArmorBox> internals : List.of(profile.sensitiveInternals, profile.engineBoxes,
                    profile.ammoRacks, profile.moduleBoxes)) {
                ArmorHit internal = ArmorHitResolver.findFirstBoxOnRay(target, internals, origin,
                        trace.hullShotDirection, profile.internalRayLength, profile.impactTolerance);
                checksum = checksum * 31 + (internal == null ? 0 : internal.plate.name.hashCode());
            }
            checksum = checksum * 31 + (armor.hit() == null ? 0 : armor.hit().plate.name.hashCode());
            checksum = checksum * 31 + (track == null ? 0 : 1) + (module == null ? 0 : 2)
                    + (era.hit() == null ? 0 : 4);
        }
        return checksum;
    }

    private static int triangles(ArmorProfile profile) {
        int count = 0;
        for (ArmorBox plate : profile.plates) count += ((ArmorMeshVolume) plate.volume).triangleCount();
        return count;
    }
}
