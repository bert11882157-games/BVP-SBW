package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.SegmentApproach;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;

/**
 * Geometry of one authored armor volume in its own frame: hull, turret or barrel armor-profile
 * coordinates, in blocks, at the rest pose. Implemented by oriented boxes ({@link ArmorBoxVolume})
 * and by triangle meshes ({@link ArmorMeshVolume}).
 *
 * <p>Every query is pure and thread-safe: volumes are immutable after loading, and the client and
 * the integrated server share them. Directions passed in must be unit length.</p>
 */
public interface ArmorVolume {
    /** True for a triangle mesh volume, false for an oriented box. */
    boolean isMesh();

    /**
     * Distance along {@code start + direction * t}, {@code t in [0, maxDistance]}, at which the
     * ray first enters this volume grown by the numerical skin {@code inflation}. Returns 0 when
     * the start already lies inside the grown volume and NaN when the ray misses.
     */
    double rayHitDistance(Vec start, Vec direction, double maxDistance, double inflation);

    /**
     * Outward unit normal of the face the same ray query enters, in this volume's frame, or null
     * on a miss. A mesh returns the true triangle normal of the entered face.
     */
    Vec rayEntryNormal(Vec start, Vec direction, double maxDistance, double inflation);

    /** True when the point lies inside the solid (a box, or a closed mesh part). */
    boolean contains(Vec point);

    /** Euclidean distance from the point to the solid; 0 inside. Open mesh parts count as surfaces. */
    double distanceOutside(Vec point);

    /** Outward unit normal of the surface nearest to the point (boxes keep their legacy face-ratio rule). */
    Vec normalAt(Vec point);

    /**
     * Closest approach of the segment {@code start + direction * [0, length]} to this volume:
     * the gap (0 when the segment touches it), the segment parameter and a surface point.
     * Null for degenerate input.
     */
    SegmentApproach closestApproach(Vec start, Vec direction, double length);

    /** Lowest point of the volume in its frame. */
    double minY();

    /** Solid centroid (area centroid for open meshes). */
    Vec centroid();

    /** Solid volume in cubic blocks (0 for open meshes). */
    double volume();

    /** Axis-aligned bounds in the frame: {minX, minY, minZ, maxX, maxY, maxZ}; a fresh copy. */
    double[] bounds();

    /**
     * Conservative distance by which the volume grown by the skin {@code inflation} can reach
     * past {@link #bounds()} on any axis. Used for bounding-box culling before exact tests.
     */
    double skinPad(double inflation);

    /** Number of distinct vertices (8 for a box). */
    int vertexCount();

    /** Writes vertex {@code index} into {@code out[0..2]}. */
    void vertex(int index, double[] out);

    /** Visits every surface triangle with outward winding. */
    void forEachTriangle(TriangleSink sink);

    /** Visits every feature edge: boundary edges and edges between non-coplanar faces. */
    void forEachFeatureEdge(EdgeSink sink);

    /**
     * Outward normal of the largest planar face whose normal points along {@code hint}
     * (positive dot product). Useful to aim at "the armor face" of a slab. Null when no face
     * points along the hint.
     */
    Vec dominantFaceNormal(Vec hint);

    @FunctionalInterface
    interface TriangleSink {
        void accept(double ax, double ay, double az, double bx, double by, double bz,
                    double cx, double cy, double cz);
    }

    @FunctionalInterface
    interface EdgeSink {
        void accept(double ax, double ay, double az, double bx, double by, double bz);
    }
}
