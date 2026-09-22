package com.yourname.berts_vehicle_pack.armor;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Shared SBW vehicle-local to BVP visual-local armor-frame conversion. */
public final class ArmorCoordinateFrame {
    private ArmorCoordinateFrame() {
    }

    /** BVP armor/model geometry is authored in the fixed 180-degree visual frame. */
    public static ArmorProfiles.Vec sbwVehicleLocalToVisualLocal(Vec3 local) {
        return new ArmorProfiles.Vec(local.f_82479_, local.f_82480_, local.f_82481_).rotateY(180.0D);
    }

    /** Rest-authored armor coordinates to the current barrel frame, with no copied pivot. */
    public static BarrelFrame barrelFrame(Matrix4d vehicleTransform, Matrix4d barrelTransform,
                                           Vec3 turretPivot, Vec3 barrelPivot, boolean mirrorProfileX) {
        if (!finite(turretPivot) || !finite(barrelPivot)
                || !invertible(vehicleTransform) || !invertible(barrelTransform)) {
            return null;
        }
        Matrix4d profileToVehicle = new Matrix4d().rotationY(Math.PI)
                .scale(mirrorProfileX ? -1.0D : 1.0D, 1.0D, 1.0D);
        Matrix4d forward = new Matrix4d(profileToVehicle).invert()
                .mul(new Matrix4d(vehicleTransform).invert())
                .mul(barrelTransform)
                .translate(-turretPivot.f_82479_ - barrelPivot.f_82479_,
                        -turretPivot.f_82480_ - barrelPivot.f_82480_,
                        -turretPivot.f_82481_ - barrelPivot.f_82481_)
                .mul(profileToVehicle);
        return invertible(forward) ? new BarrelFrame(forward) : null;
    }

    /** Uses the visible model's immutable part sample, never a second mutable aim read. */
    public static BarrelFrame renderedBarrelFrame(Vec3 turretPivot, Vec3 barrelPivot,
                                                   double turretYaw, double barrelPitch,
                                                   boolean mirrorProfileX) {
        if (!finite(turretPivot) || !finite(barrelPivot)
                || !Double.isFinite(turretYaw) || !Double.isFinite(barrelPitch)) return null;
        Matrix4d barrel = new Matrix4d()
                .translate(turretPivot.f_82479_, turretPivot.f_82480_, turretPivot.f_82481_)
                .rotateY(Math.toRadians(turretYaw))
                .translate(barrelPivot.f_82479_, barrelPivot.f_82480_, barrelPivot.f_82481_)
                .rotateX(Math.toRadians(-barrelPitch));
        return barrelFrame(new Matrix4d(), barrel, turretPivot, barrelPivot, mirrorProfileX);
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.f_82479_)
                && Double.isFinite(point.f_82480_) && Double.isFinite(point.f_82481_);
    }

    private static boolean invertible(Matrix4d matrix) {
        if (matrix == null) return false;
        for (double value : matrix.get(new double[16])) if (!Double.isFinite(value)) return false;
        double determinant = matrix.determinant();
        return Double.isFinite(determinant) && Math.abs(determinant) > 1.0E-12D;
    }

    /** The matrices are private copies; one query/render pass reuses this coherent sample. */
    public static final class BarrelFrame {
        private final Matrix4d forward;
        private final Matrix4d inverse;

        private BarrelFrame(Matrix4d forward) {
            this.forward = new Matrix4d(forward);
            this.inverse = new Matrix4d(forward).invert();
        }

        public ArmorProfiles.Vec toHullPoint(ArmorProfiles.Vec point) {
            return point(forward, point);
        }

        public ArmorProfiles.Vec toHullPoint(double x, double y, double z) {
            return toHullPoint(new ArmorProfiles.Vec(x, y, z));
        }

        public ArmorProfiles.Vec toBarrelPoint(ArmorProfiles.Vec point) {
            return point(inverse, point);
        }

        public ArmorProfiles.Vec toHullDirection(ArmorProfiles.Vec direction) {
            return direction(forward, direction);
        }

        public ArmorProfiles.Vec toBarrelDirection(ArmorProfiles.Vec direction) {
            return direction(inverse, direction);
        }

        private static ArmorProfiles.Vec point(Matrix4d matrix, ArmorProfiles.Vec point) {
            Vector3d result = matrix.transformPosition(new Vector3d(point.x, point.y, point.z));
            return new ArmorProfiles.Vec(result.x, result.y, result.z);
        }

        private static ArmorProfiles.Vec direction(Matrix4d matrix, ArmorProfiles.Vec direction) {
            Vector3d result = matrix.transformDirection(new Vector3d(direction.x, direction.y, direction.z));
            return new ArmorProfiles.Vec(result.x, result.y, result.z);
        }
    }
}
