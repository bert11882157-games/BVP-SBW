package com.yourname.berts_vehicle_pack.entity;

import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleCollisionBoxes;
import com.atsuishio.superbwarfare.tools.OBB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** No-world checks of exact world envelopes and the existing active-gear selector. */
public final class BvpMovementCollisionBoundsTest {
    private static int checks;
    private static final AABB FALLBACK = new AABB(-100, -100, -100, 100, 100, 100);

    private static void expect(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("movement bounds check " + checks);
    }

    public static void main(String[] args) {
        checkGroundContact();
        // Leopard-sized hull: diagonal yaw must not inflate the Minecraft core footprint.
        for (double yaw : new double[]{0, 45, 90, 135}) {
            OBB hull = new OBB(new Vector3d(10, 65.3, -10), new Vector3d(1.8, .65, 4.15),
                    new Quaterniond().rotateY(Math.toRadians(yaw)), OBB.Part.BODY);
            AABB core = BvpMovementCollisionBounds.core(hull, new AABB(0,64,-20,20,70,0));
            expect(Math.abs((core.maxX-core.minX)-2.78) < 1E-8);
            expect(Math.abs((core.maxZ-core.minZ)-2.78) < 1E-8);
            expect(core.minY == 64 && core.maxY > 65);
        }
        for (double yaw : new double[]{-180, -90, -35, 0, 35, 90, 180}) {
            for (double bank : new double[]{-90, -30, 0, 30, 90}) {
                for (Vector3d translation : List.of(new Vector3d(), new Vector3d(160, 64, -240))) {
                    var rotation = new Quaterniond().rotateY(Math.toRadians(yaw))
                            .rotateX(0.2).rotateZ(Math.toRadians(bank));
                    double roundoff = 1.0000001;
                    rotation.set(rotation.x * roundoff, rotation.y * roundoff,
                            rotation.z * roundoff, rotation.w * roundoff);
                    List<OBB> boxes = new ArrayList<>();
                    for (int index = 0; index < 3; index++) {
                        var center = rotation.transform(new Vector3d(index - 1, 1, index * 2)).add(translation);
                        boxes.add(new OBB(center, new Vector3d(0.5 + index, 0.3, 1.2),
                                new Quaterniond(rotation), OBB.Part.BODY));
                    }
                    AABB bounds = BvpMovementCollisionBounds.union(boxes, FALLBACK);
                    expect(bounds != FALLBACK);
                    double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
                    double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
                    for (OBB box : boxes) for (Vector3d corner : box.getVertices()) {
                        expect(corner.x >= bounds.minX - 1E-9 && corner.x <= bounds.maxX + 1E-9
                                && corner.y >= bounds.minY - 1E-9 && corner.y <= bounds.maxY + 1E-9
                                && corner.z >= bounds.minZ - 1E-9 && corner.z <= bounds.maxZ + 1E-9);
                        minX = Math.min(minX, corner.x); maxX = Math.max(maxX, corner.x);
                        minY = Math.min(minY, corner.y); maxY = Math.max(maxY, corner.y);
                        minZ = Math.min(minZ, corner.z); maxZ = Math.max(maxZ, corner.z);
                    }
                    expect(Math.abs(bounds.minX - minX) < 1E-9 && Math.abs(bounds.maxX - maxX) < 1E-9
                            && Math.abs(bounds.minY - minY) < 1E-9 && Math.abs(bounds.maxY - maxY) < 1E-9
                            && Math.abs(bounds.minZ - minZ) < 1E-9 && Math.abs(bounds.maxZ - maxZ) < 1E-9);
                    for (OBB box : boxes) box.center.add(100, -20, 300);
                    AABB moved = BvpMovementCollisionBounds.union(boxes, FALLBACK);
                    expect(Math.abs(moved.minX - bounds.minX - 100) < 1E-9
                            && Math.abs(moved.minY - bounds.minY + 20) < 1E-9
                            && Math.abs(moved.minZ - bounds.minZ - 300) < 1E-9);
                }
            }
        }
        OBB body = new OBB(new Vector3d(), new Vector3d(1, 2, 3), new Quaterniond(), OBB.Part.BODY);
        AABB exact = BvpMovementCollisionBounds.union(List.of(body), FALLBACK);
        expect(exact.minX == -1 && exact.maxY == 2 && exact.maxZ == 3);
        expect(body.center.equals(new Vector3d()) && body.extents().equals(new Vector3d(1, 2, 3))
                && body.rotation().equals(new Quaterniond()));
        expect(BvpMovementCollisionBounds.union(List.of(), FALLBACK) == FALLBACK);
        expect(BvpMovementCollisionBounds.union(Collections.nCopies(257, body), FALLBACK) == FALLBACK);
        expect(BvpMovementCollisionBounds.union(Collections.nCopies(256, body), FALLBACK) != FALLBACK);
        body.center.x = Double.NaN;
        expect(BvpMovementCollisionBounds.union(List.of(body), FALLBACK) == FALLBACK);
        body.center.x = 0; body.extents().x = -1;
        expect(BvpMovementCollisionBounds.union(List.of(body), FALLBACK) == FALLBACK);
        body.extents().x = 1; body.rotation().w = 2;
        expect(BvpMovementCollisionBounds.union(List.of(body), FALLBACK) == FALLBACK);
        body.rotation().w = 1; body.extents().zero();
        expect(BvpMovementCollisionBounds.union(List.of(body), FALLBACK) == FALLBACK);

        var bodyInfo = new OBBInfo(); bodyInfo.setSize(new Vec3(1, 1, 1));
        var gearInfo = new OBBInfo(); gearInfo.setSize(new Vec3(1, 1, 1)); gearInfo.setLandingGear(true);
        gearInfo.getOBB().center.set(0, -5, 0);
        var selection = new VehicleCollisionBoxes();
        for (float fraction : new float[]{0, .5F, .99F, Float.NaN, 1, 0}) {
            var active = selection.select(List.of(bodyInfo, gearInfo), true, fraction);
            var bounds = BvpMovementCollisionBounds.union(active, FALLBACK);
            expect(active.size() == (fraction == 1 ? 1 : 2));
            expect(bounds.minY == (fraction == 1 ? -1 : -6));
        }
        expect(selection.select(List.of(bodyInfo, gearInfo), false, 1).size() == 2);
        System.out.println("PASS movement bounds: " + checks + " corner/translation/yaw/bank/gear/fallback checks");
    }

    private static void checkGroundContact() {
        for (double surface : new double[]{0.5, 1.0}) {
            var floor = Shapes.box(-20, 0, -20, 20, surface, 20);
            double y = surface + 0.5;
            for (int tick = 0; tick < 40; tick++) {
                var nativeBox = new AABB(-6, y, -6, 6, y + 4, 6);
                var authored = new AABB(-1.8, y - 0.014, -3.5, 1.8, y + 2.4, 3.5);
                var movement = BvpMovementCollisionBounds.fitToGroundPlane(authored, nativeBox);
                expect(movement.minY == y && movement.minX > authored.minX
                        && movement.maxX < authored.maxX && movement.maxY < authored.maxY);
                y += Shapes.collide(Direction.Axis.Y, movement, List.of(floor), -0.08);
                expect(y >= surface);
            }
            expect(Math.abs(y - surface) < 1E-9);
        }
        expect(BvpMovementCollisionBounds.fitToGroundPlane(FALLBACK, FALLBACK) == FALLBACK);
    }
}
