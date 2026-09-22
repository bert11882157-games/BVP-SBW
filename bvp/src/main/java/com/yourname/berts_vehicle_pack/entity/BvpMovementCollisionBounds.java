package com.yourname.berts_vehicle_pack.entity;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Fitted physical envelope of current authored collision, anchored to the native ground plane. */
public final class BvpMovementCollisionBounds {
    static final int MAX_BOXES = 256;

    private BvpMovementCollisionBounds() { }

    public static AABB resolve(VehicleEntity vehicle, AABB fallback) {
        try {
            if (vehicle.getObb().isEmpty() || vehicle.getObb().size() > MAX_BOXES) return fallback;
            // Movement can follow setPos in the same tick. Never reuse the prior world centers.
            vehicle.updateOBB();
            // Ground vehicles use a stable central footprint. Rotating the full hull envelope
            // makes the white Minecraft box balloon at diagonal headings and includes gun parts.
            boolean aircraft = vehicle.isFixedWingFlightVehicle();
            var body = vehicle.getObb().stream()
                    .filter(info -> info.getPart() == OBB.Part.BODY && !info.getLandingGear())
                    // Aircraft retain their oriented body/wheel collision separately. Choose the
                    // central fuselage section here, excluding wings, fin, nose and tail appendages.
                    .filter(info -> !aircraft || info.getSize().z > info.getSize().x
                            && Math.abs(info.getPosition().x) <= info.getSize().x * 0.5
                            && Math.abs(info.getPosition().z) <= info.getSize().z * 0.5)
                    .findFirst().orElse(null);
            if (body != null) return core(body.getOBB(), fallback);
            return fitToGroundPlane(union(vehicle.getOBBs(), fallback), fallback);
        } catch (RuntimeException unavailablePose) {
            // Data/pose initialization failure retains the native broad movement envelope.
            return fallback;
        }
    }

    static AABB core(OBB body, AABB fallback) {
        AABB envelope = union(List.of(body), fallback);
        if (envelope == fallback) return fallback;
        // The local minor horizontal dimension determines the core, independent of yaw.
        double radius = Math.max(0.25, Math.min(1.5,
                0.8 * Math.min(body.extents().x, body.extents().z)));
        AABB core = new AABB(body.center.x - radius, envelope.minY, body.center.z - radius,
                body.center.x + radius, envelope.maxY, body.center.z + radius);
        return fitToGroundPlane(core, fallback);
    }

    static AABB fitToGroundPlane(AABB authored, AABB fallback) {
        if (authored == fallback) return fallback;
        // The native lower face is the spawn/contact plane. Authored armor may cross it;
        // swept block collision cannot recover a box that already starts inside the floor.
        double insetX = Math.min(0.05, (authored.maxX - authored.minX) * 0.05);
        double insetZ = Math.min(0.05, (authored.maxZ - authored.minZ) * 0.05);
        double minX = Math.max(fallback.minX, authored.minX + insetX);
        double maxX = Math.min(fallback.maxX, authored.maxX - insetX);
        double minZ = Math.max(fallback.minZ, authored.minZ + insetZ);
        double maxZ = Math.min(fallback.maxZ, authored.maxZ - insetZ);
        double maxY = Math.min(fallback.maxY, Math.max(fallback.minY + 0.25, authored.maxY - 0.05));
        if (minX >= maxX || minZ >= maxZ || fallback.minY >= maxY) return fallback;
        return new AABB(minX, fallback.minY, minZ, maxX, maxY, maxZ);
    }

    static AABB union(List<OBB> boxes, AABB fallback) {
        if (boxes == null || boxes.isEmpty() || boxes.size() > MAX_BOXES) return fallback;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (OBB box : boxes) {
            if (box == null) return fallback;
            var center = box.center;
            var extent = box.extents();
            var rotation = box.rotation();
            if (center == null || extent == null || rotation == null
                    || !Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z)
                    || !Double.isFinite(extent.x) || !Double.isFinite(extent.y) || !Double.isFinite(extent.z)
                    || extent.x < 0 || extent.y < 0 || extent.z < 0) return fallback;
            double x = rotation.x, y = rotation.y, z = rotation.z, w = rotation.w;
            double norm = x * x + y * y + z * z + w * w;
            if (!Double.isFinite(norm) || Math.abs(norm - 1.0) > 1.0E-6) return fallback;
            double factor = 2.0 / norm;
            // Projection of each rotated half-axis gives the exact enclosing AABB, without padding.
            double radiusX = Math.abs(1 - factor * (y * y + z * z)) * extent.x
                    + Math.abs(factor * (x * y - z * w)) * extent.y
                    + Math.abs(factor * (x * z + y * w)) * extent.z;
            double radiusY = Math.abs(factor * (x * y + z * w)) * extent.x
                    + Math.abs(1 - factor * (x * x + z * z)) * extent.y
                    + Math.abs(factor * (y * z - x * w)) * extent.z;
            double radiusZ = Math.abs(factor * (x * z - y * w)) * extent.x
                    + Math.abs(factor * (y * z + x * w)) * extent.y
                    + Math.abs(1 - factor * (x * x + y * y)) * extent.z;
            minX = Math.min(minX, center.x - radiusX); maxX = Math.max(maxX, center.x + radiusX);
            minY = Math.min(minY, center.y - radiusY); maxY = Math.max(maxY, center.y + radiusY);
            minZ = Math.min(minZ, center.z - radiusZ); maxZ = Math.max(maxZ, center.z + radiusZ);
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX >= maxX || minY >= maxY || minZ >= maxZ) return fallback;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
