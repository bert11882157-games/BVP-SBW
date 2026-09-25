package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.SegmentApproach;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A triangle-mesh armor volume: the union of one or more mesh parts (connected components).
 *
 * <p>Build-time work (welding, winding repair, closed/manifold checks, convexity, one BVH per
 * part) happens once in {@link #build}. Every query afterwards is read-only and thread-safe.</p>
 *
 * <p>Query semantics:</p>
 * <ul>
 *   <li>A closed convex part answers ray queries with its face planes (the same slab arithmetic a
 *   box uses), so the numerical skin {@code inflation} pushes every face out by exactly that
 *   distance, like the box's grown half extents.</li>
 *   <li>Any other part is a two-sided triangle surface searched through its BVH; the skin is the
 *   set of points within {@code inflation} of the surface (face caps plus edge capsules).</li>
 *   <li>Only closed parts have an inside. An open part is treated as a surface of zero thickness.</li>
 * </ul>
 */
public final class ArmorMeshVolume implements ArmorVolume {
    /** Positions closer than this (blocks) weld into one vertex. */
    static final double WELD_BLOCKS = 1.0E-7D;
    private static final double CONVEX_TOLERANCE = 1.0E-6D;
    private static final double PLANE_MERGE_DOT = 1.0D - 1.0E-9D;
    private static final double PLANE_MERGE_OFFSET = 1.0E-7D;
    private static final double FEATURE_EDGE_DOT = 1.0D - 1.0E-6D;
    private static final int LEAF_SIZE = 4;

    private final double[] tri;
    private final double[] nrm;
    private final double[] vertices;
    private final Part[] parts;
    private final double[] bounds;
    private final Vec centroid;
    private final double volume;
    private final double minY;
    private final double[] featureEdges;
    private final Report report;

    private ArmorMeshVolume(double[] tri, double[] nrm, double[] vertices, Part[] parts, double[] featureEdges,
                            Report report) {
        this.tri = tri;
        this.nrm = nrm;
        this.vertices = vertices;
        this.parts = parts;
        this.featureEdges = featureEdges;
        this.report = report;
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < vertices.length; i += 3) {
            for (int axis = 0; axis < 3; axis++) {
                b[axis] = Math.min(b[axis], vertices[i + axis]);
                b[axis + 3] = Math.max(b[axis + 3], vertices[i + axis]);
            }
        }
        this.bounds = b;
        this.minY = b[1];
        double solid = 0.0D;
        double cx = 0.0D, cy = 0.0D, cz = 0.0D;
        double area = 0.0D;
        double ax = 0.0D, ay = 0.0D, az = 0.0D;
        for (Part part : parts) {
            if (part.closed) {
                solid += part.volume;
                cx += part.centroid[0] * part.volume;
                cy += part.centroid[1] * part.volume;
                cz += part.centroid[2] * part.volume;
            }
            area += part.area;
            ax += part.areaCentroid[0] * part.area;
            ay += part.areaCentroid[1] * part.area;
            az += part.areaCentroid[2] * part.area;
        }
        this.volume = solid;
        if (solid > 1.0E-15D) {
            this.centroid = new Vec(cx / solid, cy / solid, cz / solid);
        } else if (area > 1.0E-15D) {
            this.centroid = new Vec(ax / area, ay / area, az / area);
        } else {
            this.centroid = new Vec((b[0] + b[3]) * 0.5D, (b[1] + b[4]) * 0.5D, (b[2] + b[5]) * 0.5D);
        }
    }

    /**
     * Builds a volume from a triangle soup ({@code 9 * triangleCount} coordinates, armor-profile
     * blocks). Returns null when no non-degenerate triangle remains. Problems found while
     * validating are appended to {@code warnings}, prefixed with {@code label}.
     */
    static ArmorMeshVolume build(double[] soup, int triangleCount, String label, List<String> warnings) {
        Report report = new Report();
        // 1. Weld identical positions so shared edges become shared vertex pairs.
        Map<WeldKey, Integer> weld = new HashMap<>();
        double[] welded = new double[Math.max(3, triangleCount * 9)];
        int vertexCount = 0;
        int[] indices = new int[triangleCount * 3];
        int kept = 0;
        for (int t = 0; t < triangleCount; t++) {
            int[] local = new int[3];
            boolean finite = true;
            for (int corner = 0; corner < 3; corner++) {
                int o = t * 9 + corner * 3;
                double x = soup[o], y = soup[o + 1], z = soup[o + 2];
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                    finite = false;
                    break;
                }
                WeldKey key = new WeldKey(Math.round(x / WELD_BLOCKS), Math.round(y / WELD_BLOCKS),
                        Math.round(z / WELD_BLOCKS));
                Integer existing = weld.get(key);
                if (existing == null) {
                    existing = vertexCount++;
                    weld.put(key, existing);
                    welded[existing * 3] = x;
                    welded[existing * 3 + 1] = y;
                    welded[existing * 3 + 2] = z;
                }
                local[corner] = existing;
            }
            if (!finite || local[0] == local[1] || local[1] == local[2] || local[0] == local[2]
                    || areaTwice(welded, local[0], local[1], local[2]) <= 1.0E-12D) {
                report.degenerate++;
                continue;
            }
            indices[kept * 3] = local[0];
            indices[kept * 3 + 1] = local[1];
            indices[kept * 3 + 2] = local[2];
            kept++;
        }
        if (kept == 0) {
            warnings.add(label + ": no usable triangles");
            return null;
        }
        indices = Arrays.copyOf(indices, kept * 3);
        double[] vertexArray = Arrays.copyOf(welded, vertexCount * 3);

        // 2. Undirected edge -> incident triangles.
        Map<Long, int[]> edges = new HashMap<>();
        for (int t = 0; t < kept; t++) {
            for (int e = 0; e < 3; e++) {
                long key = edgeKey(indices[t * 3 + e], indices[t * 3 + (e + 1) % 3]);
                int[] list = edges.get(key);
                if (list == null) {
                    edges.put(key, new int[] {1, t});
                } else {
                    int[] grown = Arrays.copyOf(list, list.length + 1);
                    grown[0] = list[0] + 1;
                    grown[grown.length - 1] = t;
                    edges.put(key, grown);
                }
            }
        }

        // 3. Connected components over shared edges; 4. consistent winding by breadth-first flips.
        int[] component = new int[kept];
        Arrays.fill(component, -1);
        boolean[] flip = new boolean[kept];
        List<int[]> components = new ArrayList<>();
        List<Boolean> conflicted = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < kept; seed++) {
            if (component[seed] >= 0) continue;
            int id = components.size();
            List<Integer> members = new ArrayList<>();
            component[seed] = id;
            queue.add(seed);
            boolean conflict = false;
            while (!queue.isEmpty()) {
                int t = queue.poll();
                members.add(t);
                for (int e = 0; e < 3; e++) {
                    int a = oriented(indices, flip, t, e);
                    int b = oriented(indices, flip, t, (e + 1) % 3);
                    int[] incident = edges.get(edgeKey(a, b));
                    if (incident[0] != 2) continue; // boundary or non-manifold: do not propagate
                    int u = incident[1] == t ? incident[2] : incident[1];
                    // u must traverse the shared edge as b -> a.
                    boolean needsFlip = hasDirectedEdge(indices, u, a, b);
                    if (component[u] < 0) {
                        component[u] = id;
                        flip[u] = needsFlip;
                        queue.add(u);
                    } else if (flip[u] != needsFlip) {
                        conflict = true;
                    }
                }
            }
            int[] list = new int[members.size()];
            for (int i = 0; i < list.length; i++) list[i] = members.get(i);
            components.add(list);
            conflicted.add(conflict);
            if (conflict) report.nonOrientable++;
        }
        for (Map.Entry<Long, int[]> entry : edges.entrySet()) {
            int count = entry.getValue()[0];
            if (count == 1) report.boundaryEdges++;
            else if (count > 2) report.nonManifoldEdges++;
        }

        // 5-8. Per component: closed check, outward orientation, convexity and planes.
        List<Part> partList = new ArrayList<>();
        double[] triOut = new double[kept * 9];
        double[] nrmOut = new double[kept * 3];
        int cursor = 0;
        for (int c = 0; c < components.size(); c++) {
            int[] members = components.get(c);
            boolean closed = !conflicted.get(c);
            for (int t : members) {
                for (int e = 0; e < 3 && closed; e++) {
                    int[] incident = edges.get(edgeKey(indices[t * 3 + e], indices[t * 3 + (e + 1) % 3]));
                    if (incident[0] != 2) closed = false;
                }
            }
            int flipped = 0;
            for (int t : members) if (flip[t]) flipped++;
            double signed = 0.0D;
            for (int t : members) {
                signed += signedTetra(vertexArray, oriented(indices, flip, t, 0),
                        oriented(indices, flip, t, 1), oriented(indices, flip, t, 2));
            }
            boolean invertAll;
            if (closed) {
                invertAll = signed < 0.0D;
                if (invertAll) report.invertedParts++;
            } else {
                // Open surfaces have no inside; keep the majority of the authored winding.
                invertAll = flipped * 2 > members.length;
                report.openParts++;
            }
            int first = cursor;
            for (int t : members) {
                boolean f = flip[t] ^ invertAll;
                if (f) report.flippedTriangles++;
                int i0 = indices[t * 3];
                int i1 = f ? indices[t * 3 + 2] : indices[t * 3 + 1];
                int i2 = f ? indices[t * 3 + 1] : indices[t * 3 + 2];
                int o = cursor * 9;
                System.arraycopy(vertexArray, i0 * 3, triOut, o, 3);
                System.arraycopy(vertexArray, i1 * 3, triOut, o + 3, 3);
                System.arraycopy(vertexArray, i2 * 3, triOut, o + 6, 3);
                unitNormal(triOut, o, nrmOut, cursor * 3);
                cursor++;
            }
            partList.add(Part.create(triOut, nrmOut, first, cursor - first, closed));
        }

        Part[] parts = partList.toArray(new Part[0]);
        double[] feature = featureEdges(triOut, nrmOut, indices, flip, edges, kept, vertexArray);
        ArmorMeshVolume volume = new ArmorMeshVolume(triOut, nrmOut, vertexArray, parts, feature, report);
        report.appendWarnings(label, warnings);
        return volume;
    }

    Report report() {
        return report;
    }

    int triangleCount() {
        return tri.length / 9;
    }

    int partCount() {
        return parts.length;
    }

    boolean isClosed() {
        for (Part part : parts) if (!part.closed) return false;
        return true;
    }

    boolean isConvexPart(int index) {
        return parts[index].convex;
    }

    @Override
    public boolean isMesh() {
        return true;
    }

    // ------------------------------------------------------------------ ray queries

    @Override
    public double rayHitDistance(Vec start, Vec direction, double maxDistance, double inflation) {
        return ray(start.x, start.y, start.z, direction.x, direction.y, direction.z, maxDistance,
                Math.max(0.0D, inflation), null);
    }

    @Override
    public Vec rayEntryNormal(Vec start, Vec direction, double maxDistance, double inflation) {
        double[] normal = new double[] {Double.NaN, 0.0D, 0.0D};
        double t = ray(start.x, start.y, start.z, direction.x, direction.y, direction.z, maxDistance,
                Math.max(0.0D, inflation), normal);
        if (!Double.isFinite(t)) {
            return null;
        }
        if (Double.isNaN(normal[0])) {
            return normalAt(start);
        }
        return new Vec(normal[0], normal[1], normal[2]);
    }

    private double ray(double sx, double sy, double sz, double dx, double dy, double dz, double maxDistance,
                       double inflation, double[] normalOut) {
        if (!(maxDistance >= 0.0D)) {
            return Double.NaN;
        }
        double best = Double.NaN;
        double[] bestNormal = normalOut == null ? null : new double[] {Double.NaN, 0.0D, 0.0D};
        double[] partNormal = normalOut == null ? null : new double[3];
        for (Part part : parts) {
            double limit = best == best ? best : maxDistance;
            double pad = inflation * part.skinGrowth + 1.0E-9D;
            if (!Double.isFinite(ArmorMeshMath.rayAabb(part.bounds, 0, pad, sx, sy, sz, dx, dy, dz, limit))) {
                continue;
            }
            if (partNormal != null) partNormal[0] = Double.NaN;
            double t = part.convex
                    ? convexRay(part, sx, sy, sz, dx, dy, dz, maxDistance, inflation, partNormal)
                    : surfaceRay(part, sx, sy, sz, dx, dy, dz, maxDistance, inflation, partNormal);
            if (t == t && !(best <= t)) {
                best = t;
                if (bestNormal != null) System.arraycopy(partNormal, 0, bestNormal, 0, 3);
            }
        }
        if (normalOut != null && best == best) {
            System.arraycopy(bestNormal, 0, normalOut, 0, 3);
        }
        return best;
    }

    /** Plane clipping of a closed convex part grown by {@code inflation}; exact box semantics. */
    private static double convexRay(Part part, double sx, double sy, double sz, double dx, double dy, double dz,
                                    double maxDistance, double inflation, double[] normalOut) {
        double tNear = 0.0D;
        double tFar = maxDistance;
        double enterT = Double.NEGATIVE_INFINITY;
        int enterPlane = -1;
        double[] planes = part.planes;
        for (int k = 0; k < planes.length; k += 4) {
            double nx = planes[k], ny = planes[k + 1], nz = planes[k + 2];
            double outside = nx * sx + ny * sy + nz * sz - (planes[k + 3] + inflation);
            double denom = nx * dx + ny * dy + nz * dz;
            if (Math.abs(denom) < ArmorMeshMath.PARALLEL_EPSILON) {
                if (outside > 0.0D) return Double.NaN;
                continue;
            }
            double t = -outside / denom;
            if (denom < 0.0D) {
                if (t > enterT) {
                    enterT = t;
                    enterPlane = k;
                }
                if (t > tNear) tNear = t;
            } else if (t < tFar) {
                tFar = t;
            }
            if (tNear > tFar) return Double.NaN;
        }
        if (normalOut != null && enterPlane >= 0) {
            normalOut[0] = planes[enterPlane];
            normalOut[1] = planes[enterPlane + 1];
            normalOut[2] = planes[enterPlane + 2];
        }
        return tNear;
    }

    /** First entry into a general part (two-sided surface; closed parts also have an inside). */
    private double surfaceRay(Part part, double sx, double sy, double sz, double dx, double dy, double dz,
                              double maxDistance, double inflation, double[] normalOut) {
        boolean startInside = part.closed && part.containsPoint(tri, sx, sy, sz);
        if (!startInside && inflation > 0.0D
                && ArmorMeshMath.pointAabbDistanceSquared(part.bounds, 0, sx, sy, sz) <= inflation * inflation) {
            startInside = nearestSquared(part, sx, sy, sz, null) <= inflation * inflation;
        }
        if (startInside) {
            return 0.0D; // normal left unset: the caller falls back to the nearest face
        }
        RayScratch scratch = new RayScratch(sx, sy, sz, dx, dy, dz, maxDistance, inflation);
        rayNode(part, 0, scratch);
        if (!scratch.found) {
            return Double.NaN;
        }
        if (normalOut != null) {
            int o = scratch.tri * 3;
            double nx = nrm[o], ny = nrm[o + 1], nz = nrm[o + 2];
            if (!part.closed && nx * dx + ny * dy + nz * dz > 0.0D) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            normalOut[0] = nx;
            normalOut[1] = ny;
            normalOut[2] = nz;
        }
        return scratch.best;
    }

    private void rayNode(Part part, int node, RayScratch r) {
        Bvh bvh = part.bvh;
        double limit = r.found ? r.best + 1.0E-9D : r.maxDistance;
        if (!Double.isFinite(ArmorMeshMath.rayAabb(bvh.box, node * 6, r.inflation + 1.0E-9D,
                r.sx, r.sy, r.sz, r.dx, r.dy, r.dz, limit))) {
            return;
        }
        int count = bvh.count[node];
        if (count > 0) {
            int start = bvh.start[node];
            for (int i = start; i < start + count; i++) {
                testTriangle(bvh.order[i], r);
            }
            return;
        }
        rayNode(part, bvh.left[node], r);
        rayNode(part, bvh.right[node], r);
    }

    private void testTriangle(int t, RayScratch r) {
        int o = t * 9;
        int n = t * 3;
        if (r.inflation <= 0.0D) {
            consider(t, ArmorMeshMath.rayTriangle(tri, o, 0.0D, 0.0D, 0.0D, 0.0D,
                    r.sx, r.sy, r.sz, r.dx, r.dy, r.dz), r);
            return;
        }
        double nx = nrm[n], ny = nrm[n + 1], nz = nrm[n + 2];
        consider(t, ArmorMeshMath.rayTriangle(tri, o, r.inflation, nx, ny, nz,
                r.sx, r.sy, r.sz, r.dx, r.dy, r.dz), r);
        consider(t, ArmorMeshMath.rayTriangle(tri, o, -r.inflation, nx, ny, nz,
                r.sx, r.sy, r.sz, r.dx, r.dy, r.dz), r);
        for (int e = 0; e < 3; e++) {
            int a = o + e * 3;
            int b = o + ((e + 1) % 3) * 3;
            consider(t, ArmorMeshMath.rayCapsule(r.sx, r.sy, r.sz, r.dx, r.dy, r.dz,
                    tri[a], tri[a + 1], tri[a + 2], tri[b], tri[b + 1], tri[b + 2], r.inflation), r);
        }
    }

    private void consider(int t, double distance, RayScratch r) {
        if (!(distance >= 0.0D) || distance > r.maxDistance) {
            return;
        }
        if (!r.found || distance < r.best - 1.0E-12D) {
            r.found = true;
            r.best = distance;
            r.tri = t;
        } else if (distance <= r.best + 1.0E-12D) {
            // Same entry point on a shared edge: keep the face that meets the shot most squarely.
            double current = facing(r.tri, r);
            double candidate = facing(t, r);
            if (candidate < current) {
                r.best = Math.min(r.best, distance);
                r.tri = t;
            }
        }
    }

    private double facing(int t, RayScratch r) {
        int n = t * 3;
        return nrm[n] * r.dx + nrm[n + 1] * r.dy + nrm[n + 2] * r.dz;
    }

    // ------------------------------------------------------------------ point queries

    @Override
    public boolean contains(Vec point) {
        for (Part part : parts) {
            if (part.closed && part.containsPoint(tri, point.x, point.y, point.z)) return true;
        }
        return false;
    }

    @Override
    public double distanceOutside(Vec point) {
        double best = Double.POSITIVE_INFINITY;
        for (Part part : parts) {
            if (part.closed && part.containsPoint(tri, point.x, point.y, point.z)) return 0.0D;
            if (ArmorMeshMath.pointAabbDistanceSquared(part.bounds, 0, point.x, point.y, point.z) >= best) continue;
            best = Math.min(best, nearestSquared(part, point.x, point.y, point.z, null));
        }
        return Math.sqrt(best);
    }

    @Override
    public Vec normalAt(Vec point) {
        NearestScratch scratch = new NearestScratch(point.x, point.y, point.z);
        for (Part part : parts) {
            if (ArmorMeshMath.pointAabbDistanceSquared(part.bounds, 0, point.x, point.y, point.z)
                    > scratch.bestSquared + 1.0E-12D) continue;
            scratch.part = part;
            nearestNode(part, 0, scratch);
        }
        if (scratch.tri < 0) {
            return new Vec(0.0D, 1.0D, 0.0D);
        }
        int n = scratch.tri * 3;
        double nx = nrm[n], ny = nrm[n + 1], nz = nrm[n + 2];
        if (!scratch.triClosed) {
            int o = scratch.tri * 9;
            double side = nx * (point.x - tri[o]) + ny * (point.y - tri[o + 1]) + nz * (point.z - tri[o + 2]);
            if (side < 0.0D) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
        }
        return new Vec(nx, ny, nz);
    }

    /** Squared distance from P to the surface of one part (BVH nearest search). */
    private double nearestSquared(Part part, double px, double py, double pz, NearestScratch reuse) {
        NearestScratch scratch = reuse == null ? new NearestScratch(px, py, pz) : reuse;
        scratch.part = part;
        nearestNode(part, 0, scratch);
        return scratch.bestSquared;
    }

    private void nearestNode(Part part, int node, NearestScratch s) {
        Bvh bvh = part.bvh;
        if (ArmorMeshMath.pointAabbDistanceSquared(bvh.box, node * 6, s.px, s.py, s.pz) > s.bestSquared + 1.0E-12D) {
            return;
        }
        int count = bvh.count[node];
        if (count > 0) {
            int start = bvh.start[node];
            for (int i = start; i < start + count; i++) {
                int t = bvh.order[i];
                int o = t * 9;
                double squared = ArmorMeshMath.closestPointOnTriangle(tri, o, s.px, s.py, s.pz, s.point);
                int n = t * 3;
                double plane = nrm[n] * (s.px - tri[o]) + nrm[n + 1] * (s.py - tri[o + 1])
                        + nrm[n + 2] * (s.pz - tri[o + 2]);
                if (!part.closed) plane = Math.abs(plane);
                if (s.tri < 0 || squared < s.bestSquared - 1.0E-12D
                        || (squared <= s.bestSquared + 1.0E-12D && plane > s.bestPlane)) {
                    s.bestSquared = Math.min(s.bestSquared, squared);
                    s.tri = t;
                    s.bestPlane = plane;
                    s.triClosed = part.closed;
                }
            }
            return;
        }
        int left = bvh.left[node];
        int right = bvh.right[node];
        double dl = ArmorMeshMath.pointAabbDistanceSquared(bvh.box, left * 6, s.px, s.py, s.pz);
        double dr = ArmorMeshMath.pointAabbDistanceSquared(bvh.box, right * 6, s.px, s.py, s.pz);
        if (dl <= dr) {
            nearestNode(part, left, s);
            nearestNode(part, right, s);
        } else {
            nearestNode(part, right, s);
            nearestNode(part, left, s);
        }
    }

    @Override
    public SegmentApproach closestApproach(Vec start, Vec direction, double length) {
        double dl = direction.length();
        if (dl < 1.0E-6D || !(length >= 0.0D) || !Double.isFinite(length)) {
            return null;
        }
        double dx = direction.x / dl, dy = direction.y / dl, dz = direction.z / dl;
        double t = ray(start.x, start.y, start.z, dx, dy, dz, length, 0.0D, null);
        if (t == t) {
            return new SegmentApproach(t, 0.0D, new Vec(start.x + dx * t, start.y + dy * t, start.z + dz * t));
        }
        double[] out = new double[4];
        double[] scratch = new double[4];
        double bestSquared = Double.POSITIVE_INFINITY;
        double bestT = 0.0D;
        double bx = 0.0D, by = 0.0D, bz = 0.0D;
        int triangles = tri.length / 9;
        for (int i = 0; i < triangles; i++) {
            double squared = ArmorMeshMath.segmentTriangle(tri, i * 9, start.x, start.y, start.z,
                    dx, dy, dz, length, out, scratch);
            if (squared < bestSquared - 1.0E-12D
                    || (squared <= bestSquared + 1.0E-12D && out[0] < bestT)) {
                bestSquared = Math.min(bestSquared, squared);
                bestT = out[0];
                bx = out[1];
                by = out[2];
                bz = out[3];
            }
        }
        if (!Double.isFinite(bestSquared)) {
            return null;
        }
        return new SegmentApproach(bestT, Math.sqrt(bestSquared), new Vec(bx, by, bz));
    }

    // ------------------------------------------------------------------ descriptive queries

    @Override
    public double minY() {
        return minY;
    }

    @Override
    public Vec centroid() {
        return centroid;
    }

    @Override
    public double volume() {
        return volume;
    }

    @Override
    public double[] bounds() {
        return bounds.clone();
    }

    @Override
    public double skinPad(double inflation) {
        double growth = 1.0D;
        for (Part part : parts) growth = Math.max(growth, part.skinGrowth);
        return Math.max(0.0D, inflation) * growth + 1.0E-9D;
    }

    @Override
    public int vertexCount() {
        return vertices.length / 3;
    }

    @Override
    public void vertex(int index, double[] out) {
        out[0] = vertices[index * 3];
        out[1] = vertices[index * 3 + 1];
        out[2] = vertices[index * 3 + 2];
    }

    @Override
    public void forEachTriangle(TriangleSink sink) {
        for (int o = 0; o < tri.length; o += 9) {
            sink.accept(tri[o], tri[o + 1], tri[o + 2], tri[o + 3], tri[o + 4], tri[o + 5],
                    tri[o + 6], tri[o + 7], tri[o + 8]);
        }
    }

    @Override
    public void forEachFeatureEdge(EdgeSink sink) {
        for (int o = 0; o < featureEdges.length; o += 6) {
            sink.accept(featureEdges[o], featureEdges[o + 1], featureEdges[o + 2],
                    featureEdges[o + 3], featureEdges[o + 4], featureEdges[o + 5]);
        }
    }

    @Override
    public Vec dominantFaceNormal(Vec hint) {
        List<double[]> planes = new ArrayList<>(); // nx, ny, nz, d, area
        for (int t = 0; t < tri.length / 9; t++) {
            int o = t * 9;
            int n = t * 3;
            double nx = nrm[n], ny = nrm[n + 1], nz = nrm[n + 2];
            double d = nx * tri[o] + ny * tri[o + 1] + nz * tri[o + 2];
            double area = areaOf(tri, o);
            double[] match = null;
            for (double[] plane : planes) {
                if (plane[0] * nx + plane[1] * ny + plane[2] * nz > 1.0D - 1.0E-6D
                        && Math.abs(plane[3] - d) < 1.0E-5D) {
                    match = plane;
                    break;
                }
            }
            if (match == null) {
                planes.add(new double[] {nx, ny, nz, d, area});
            } else {
                match[4] += area;
            }
        }
        double[] best = null;
        double bestDot = 0.0D;
        for (double[] plane : planes) {
            double dot = plane[0] * hint.x + plane[1] * hint.y + plane[2] * hint.z;
            if (dot <= 1.0E-9D) continue;
            if (best == null || plane[4] > best[4] * (1.0D + 1.0E-9D)
                    || (Math.abs(plane[4] - best[4]) <= best[4] * 1.0E-9D && dot > bestDot)) {
                best = plane;
                bestDot = dot;
            }
        }
        return best == null ? null : new Vec(best[0], best[1], best[2]);
    }

    // ------------------------------------------------------------------ build helpers

    private static int oriented(int[] indices, boolean[] flip, int t, int corner) {
        if (!flip[t] || corner == 0) return indices[t * 3 + corner];
        return indices[t * 3 + (corner == 1 ? 2 : 1)];
    }

    private static boolean hasDirectedEdge(int[] indices, int t, int a, int b) {
        for (int e = 0; e < 3; e++) {
            if (indices[t * 3 + e] == a && indices[t * 3 + (e + 1) % 3] == b) return true;
        }
        return false;
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static double areaTwice(double[] v, int a, int b, int c) {
        double ux = v[b * 3] - v[a * 3], uy = v[b * 3 + 1] - v[a * 3 + 1], uz = v[b * 3 + 2] - v[a * 3 + 2];
        double wx = v[c * 3] - v[a * 3], wy = v[c * 3 + 1] - v[a * 3 + 1], wz = v[c * 3 + 2] - v[a * 3 + 2];
        double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        return Math.sqrt(nx * nx + ny * ny + nz * nz);
    }

    static double areaOf(double[] tri, int o) {
        double ux = tri[o + 3] - tri[o], uy = tri[o + 4] - tri[o + 1], uz = tri[o + 5] - tri[o + 2];
        double wx = tri[o + 6] - tri[o], wy = tri[o + 7] - tri[o + 1], wz = tri[o + 8] - tri[o + 2];
        double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        return 0.5D * Math.sqrt(nx * nx + ny * ny + nz * nz);
    }

    private static double signedTetra(double[] v, int a, int b, int c) {
        double ax = v[a * 3], ay = v[a * 3 + 1], az = v[a * 3 + 2];
        double bx = v[b * 3], by = v[b * 3 + 1], bz = v[b * 3 + 2];
        double cx = v[c * 3], cy = v[c * 3 + 1], cz = v[c * 3 + 2];
        return (ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)) / 6.0D;
    }

    private static void unitNormal(double[] tri, int o, double[] out, int n) {
        double ux = tri[o + 3] - tri[o], uy = tri[o + 4] - tri[o + 1], uz = tri[o + 5] - tri[o + 2];
        double wx = tri[o + 6] - tri[o], wy = tri[o + 7] - tri[o + 1], wz = tri[o + 8] - tri[o + 2];
        double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        out[n] = nx / length;
        out[n + 1] = ny / length;
        out[n + 2] = nz / length;
    }

    private static double[] featureEdges(double[] triOut, double[] nrmOut, int[] indices, boolean[] flip,
                                         Map<Long, int[]> edges, int kept, double[] vertexArray) {
        // Map original triangle index -> output index is not needed: normals of incident triangles
        // are recomputed from the welded vertices, which is orientation independent via |dot|.
        double[] out = new double[edges.size() * 6];
        int count = 0;
        for (Map.Entry<Long, int[]> entry : edges.entrySet()) {
            int[] incident = entry.getValue();
            boolean feature = incident[0] != 2;
            if (!feature) {
                double[] n1 = rawNormal(vertexArray, indices, incident[1]);
                double[] n2 = rawNormal(vertexArray, indices, incident[2]);
                feature = Math.abs(n1[0] * n2[0] + n1[1] * n2[1] + n1[2] * n2[2]) < FEATURE_EDGE_DOT;
            }
            if (!feature) continue;
            long key = entry.getKey();
            int a = (int) (key >>> 32);
            int b = (int) key;
            System.arraycopy(vertexArray, a * 3, out, count * 6, 3);
            System.arraycopy(vertexArray, b * 3, out, count * 6 + 3, 3);
            count++;
        }
        return Arrays.copyOf(out, count * 6);
    }

    private static double[] rawNormal(double[] v, int[] indices, int t) {
        int a = indices[t * 3], b = indices[t * 3 + 1], c = indices[t * 3 + 2];
        double ux = v[b * 3] - v[a * 3], uy = v[b * 3 + 1] - v[a * 3 + 1], uz = v[b * 3 + 2] - v[a * 3 + 2];
        double wx = v[c * 3] - v[a * 3], wy = v[c * 3 + 1] - v[a * 3 + 1], wz = v[c * 3 + 2] - v[a * 3 + 2];
        double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return new double[] {nx / length, ny / length, nz / length};
    }

    // ------------------------------------------------------------------ nested types

    private record WeldKey(long x, long y, long z) {
    }

    /** Validation findings for one volume. */
    static final class Report {
        int degenerate;
        int boundaryEdges;
        int nonManifoldEdges;
        int nonOrientable;
        int openParts;
        int invertedParts;
        int flippedTriangles;

        boolean clean() {
            return degenerate == 0 && boundaryEdges == 0 && nonManifoldEdges == 0 && nonOrientable == 0;
        }

        void appendWarnings(String label, List<String> warnings) {
            if (boundaryEdges > 0 || openParts > 0) {
                warnings.add(String.format(Locale.ROOT,
                        "%s: not closed (%d open part(s), %d boundary edge(s)); treated as a two-sided surface"
                                + " with no inside", label, openParts, boundaryEdges));
            }
            if (nonManifoldEdges > 0) {
                warnings.add(String.format(Locale.ROOT,
                        "%s: %d non-manifold edge(s) (shared by more than two faces)", label, nonManifoldEdges));
            }
            if (nonOrientable > 0) {
                warnings.add(String.format(Locale.ROOT,
                        "%s: %d part(s) cannot be wound consistently", label, nonOrientable));
            }
            if (degenerate > 0) {
                warnings.add(String.format(Locale.ROOT,
                        "%s: dropped %d degenerate triangle(s)", label, degenerate));
            }
        }
    }

    private static final class Part {
        final int first;
        final int count;
        final boolean closed;
        final boolean convex;
        final double[] planes;
        final double[] bounds;
        final Bvh bvh;
        final double volume;
        final double[] centroid;
        final double area;
        final double[] areaCentroid;
        /**
         * How far (per unit of skin) the grown part can reach past its bounds: 1 for a surface
         * skin (points within the skin of a triangle), and the largest vertex displacement of the
         * plane-offset polytope for a convex part (sqrt(3) for a box, more for sharp wedges).
         */
        final double skinGrowth;

        private Part(int first, int count, boolean closed, boolean convex, double[] planes, double[] bounds,
                     Bvh bvh, double volume, double[] centroid, double area, double[] areaCentroid,
                     double skinGrowth) {
            this.first = first;
            this.count = count;
            this.closed = closed;
            this.convex = convex;
            this.planes = planes;
            this.bounds = bounds;
            this.bvh = bvh;
            this.volume = volume;
            this.centroid = centroid;
            this.area = area;
            this.areaCentroid = areaCentroid;
            this.skinGrowth = skinGrowth;
        }

        static Part create(double[] tri, double[] nrm, int first, int count, boolean closed) {
            double[] bounds = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            double six = 0.0D, cx = 0.0D, cy = 0.0D, cz = 0.0D;
            double area = 0.0D, ax = 0.0D, ay = 0.0D, az = 0.0D;
            for (int t = first; t < first + count; t++) {
                int o = t * 9;
                for (int corner = 0; corner < 3; corner++) {
                    for (int axis = 0; axis < 3; axis++) {
                        double value = tri[o + corner * 3 + axis];
                        bounds[axis] = Math.min(bounds[axis], value);
                        bounds[axis + 3] = Math.max(bounds[axis + 3], value);
                    }
                }
                double x0 = tri[o], y0 = tri[o + 1], z0 = tri[o + 2];
                double x1 = tri[o + 3], y1 = tri[o + 4], z1 = tri[o + 5];
                double x2 = tri[o + 6], y2 = tri[o + 7], z2 = tri[o + 8];
                double det = x0 * (y1 * z2 - z1 * y2) + y0 * (z1 * x2 - x1 * z2) + z0 * (x1 * y2 - y1 * x2);
                six += det;
                cx += det * (x0 + x1 + x2);
                cy += det * (y0 + y1 + y2);
                cz += det * (z0 + z1 + z2);
                double a = areaOf(tri, o);
                area += a;
                ax += a * (x0 + x1 + x2) / 3.0D;
                ay += a * (y0 + y1 + y2) / 3.0D;
                az += a * (z0 + z1 + z2) / 3.0D;
            }
            double volume = closed ? Math.abs(six) / 6.0D : 0.0D;
            double[] centroid = Math.abs(six) > 1.0E-15D
                    ? new double[] {cx / (4.0D * six), cy / (4.0D * six), cz / (4.0D * six)}
                    : new double[] {(bounds[0] + bounds[3]) * 0.5D, (bounds[1] + bounds[4]) * 0.5D,
                    (bounds[2] + bounds[5]) * 0.5D};
            double[] areaCentroid = area > 1.0E-15D
                    ? new double[] {ax / area, ay / area, az / area}
                    : centroid.clone();
            double[] planes = closed ? convexPlanes(tri, nrm, first, count) : null;
            Bvh bvh = Bvh.build(tri, first, count);
            double growth = planes == null ? 1.0D : offsetGrowth(tri, first, count, planes);
            return new Part(first, count, closed, planes != null, planes, bounds, bvh, volume, centroid,
                    area, areaCentroid, growth);
        }

        /**
         * Largest displacement, per unit offset, of a vertex of the polytope whose face planes are
         * all pushed out by the same distance. Every vertex of the grown polytope comes from three
         * planes meeting at an original vertex; the support function is concave in the offset, so
         * the initial rate bounds every offset.
         */
        private static double offsetGrowth(double[] tri, int first, int count, double[] planes) {
            double extent = 0.0D;
            for (int i = first * 9; i < (first + count) * 9; i++) extent = Math.max(extent, Math.abs(tri[i]));
            double tolerance = 1.0E-6D * Math.max(1.0D, extent);
            double growth = 1.0D;
            int planeCount = planes.length / 4;
            int[] incident = new int[planeCount];
            for (int i = first * 9; i < (first + count) * 9; i += 3) {
                double vx = tri[i], vy = tri[i + 1], vz = tri[i + 2];
                int n = 0;
                for (int k = 0; k < planeCount; k++) {
                    int p = k * 4;
                    if (Math.abs(planes[p] * vx + planes[p + 1] * vy + planes[p + 2] * vz - planes[p + 3]) <= tolerance) {
                        incident[n++] = p;
                    }
                }
                for (int a = 0; a < n; a++) {
                    for (int b = a + 1; b < n; b++) {
                        for (int c = b + 1; c < n; c++) {
                            double[] delta = solveUnitOffset(planes, incident[a], incident[b], incident[c]);
                            if (delta == null) continue;
                            boolean feasible = true;
                            for (int m = 0; m < n && feasible; m++) {
                                int p = incident[m];
                                feasible = planes[p] * delta[0] + planes[p + 1] * delta[1]
                                        + planes[p + 2] * delta[2] <= 1.0D + 1.0E-6D;
                            }
                            if (feasible) {
                                growth = Math.max(growth, Math.sqrt(delta[0] * delta[0] + delta[1] * delta[1]
                                        + delta[2] * delta[2]));
                            }
                        }
                    }
                }
            }
            return growth * (1.0D + 1.0E-6D);
        }

        /** Solves n_a . d = n_b . d = n_c . d = 1 (Cramer); null when the planes are dependent. */
        private static double[] solveUnitOffset(double[] planes, int a, int b, int c) {
            double a0 = planes[a], a1 = planes[a + 1], a2 = planes[a + 2];
            double b0 = planes[b], b1 = planes[b + 1], b2 = planes[b + 2];
            double c0 = planes[c], c1 = planes[c + 1], c2 = planes[c + 2];
            double det = a0 * (b1 * c2 - b2 * c1) - a1 * (b0 * c2 - b2 * c0) + a2 * (b0 * c1 - b1 * c0);
            if (Math.abs(det) < 1.0E-9D) return null;
            double x = ((b1 * c2 - b2 * c1) - a1 * (c2 - b2) + a2 * (c1 - b1)) / det;
            double y = (a0 * (c2 - b2) - (b0 * c2 - b2 * c0) + a2 * (b0 - c0)) / det;
            double z = (a0 * (b1 - c1) - a1 * (b0 - c0) + (b0 * c1 - b1 * c0)) / det;
            return new double[] {x, y, z};
        }

        /** Unique face planes when every vertex lies behind every face; null when not convex. */
        private static double[] convexPlanes(double[] tri, double[] nrm, int first, int count) {
            double extent = 0.0D;
            for (int i = first * 9; i < (first + count) * 9; i++) extent = Math.max(extent, Math.abs(tri[i]));
            double tolerance = CONVEX_TOLERANCE * Math.max(1.0D, extent);
            List<double[]> planes = new ArrayList<>();
            for (int t = first; t < first + count; t++) {
                int n = t * 3;
                int o = t * 9;
                double nx = nrm[n], ny = nrm[n + 1], nz = nrm[n + 2];
                double d = nx * tri[o] + ny * tri[o + 1] + nz * tri[o + 2];
                for (int i = first * 9; i < (first + count) * 9; i += 3) {
                    if (nx * tri[i] + ny * tri[i + 1] + nz * tri[i + 2] - d > tolerance) {
                        return null;
                    }
                }
                boolean duplicate = false;
                for (double[] plane : planes) {
                    if (plane[0] * nx + plane[1] * ny + plane[2] * nz > PLANE_MERGE_DOT
                            && Math.abs(plane[3] - d) < PLANE_MERGE_OFFSET) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) planes.add(new double[] {nx, ny, nz, d});
            }
            if (planes.size() < 4) {
                return null;
            }
            double[] flat = new double[planes.size() * 4];
            for (int i = 0; i < planes.size(); i++) System.arraycopy(planes.get(i), 0, flat, i * 4, 4);
            return flat;
        }

        boolean containsPoint(double[] tri, double px, double py, double pz) {
            if (px < bounds[0] || py < bounds[1] || pz < bounds[2]
                    || px > bounds[3] || py > bounds[4] || pz > bounds[5]) {
                return false;
            }
            if (convex) {
                for (int k = 0; k < planes.length; k += 4) {
                    if (planes[k] * px + planes[k + 1] * py + planes[k + 2] * pz - planes[k + 3] > 0.0D) {
                        return false;
                    }
                }
                return true;
            }
            double total = 0.0D;
            for (int t = first; t < first + count; t++) {
                total += ArmorMeshMath.solidAngle(tri, t * 9, px, py, pz);
            }
            // Generalized winding number: +-1 inside, 0 outside, robust to small cracks.
            return Math.abs(total) >= 2.0D * Math.PI;
        }
    }

    /** Median-split bounding volume hierarchy over one part's triangles. */
    static final class Bvh {
        final double[] box;
        final int[] left;
        final int[] right;
        final int[] start;
        final int[] count;
        final int[] order;
        private int nodes;

        private Bvh(int triangles) {
            int capacity = Math.max(1, 2 * triangles);
            box = new double[capacity * 6];
            left = new int[capacity];
            right = new int[capacity];
            start = new int[capacity];
            count = new int[capacity];
            order = new int[triangles];
        }

        static Bvh build(double[] tri, int first, int triangles) {
            Bvh bvh = new Bvh(triangles);
            double[] centroids = new double[triangles * 3];
            for (int i = 0; i < triangles; i++) {
                bvh.order[i] = first + i;
                int o = (first + i) * 9;
                centroids[i * 3] = (tri[o] + tri[o + 3] + tri[o + 6]) / 3.0D;
                centroids[i * 3 + 1] = (tri[o + 1] + tri[o + 4] + tri[o + 7]) / 3.0D;
                centroids[i * 3 + 2] = (tri[o + 2] + tri[o + 5] + tri[o + 8]) / 3.0D;
            }
            bvh.buildNode(tri, centroids, first, 0, triangles);
            return bvh;
        }

        private int buildNode(double[] tri, double[] centroids, int first, int lo, int hi) {
            int node = nodes++;
            double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            double[] c = b.clone();
            for (int i = lo; i < hi; i++) {
                int t = order[i];
                int o = t * 9;
                for (int corner = 0; corner < 3; corner++) {
                    for (int axis = 0; axis < 3; axis++) {
                        double value = tri[o + corner * 3 + axis];
                        b[axis] = Math.min(b[axis], value);
                        b[axis + 3] = Math.max(b[axis + 3], value);
                    }
                }
                int ci = (t - first) * 3;
                for (int axis = 0; axis < 3; axis++) {
                    c[axis] = Math.min(c[axis], centroids[ci + axis]);
                    c[axis + 3] = Math.max(c[axis + 3], centroids[ci + axis]);
                }
            }
            System.arraycopy(b, 0, box, node * 6, 6);
            if (hi - lo <= LEAF_SIZE) {
                start[node] = lo;
                count[node] = hi - lo;
                return node;
            }
            int axis = 0;
            double spread = c[3] - c[0];
            if (c[4] - c[1] > spread) { axis = 1; spread = c[4] - c[1]; }
            if (c[5] - c[2] > spread) axis = 2;
            final int sortAxis = axis;
            Integer[] range = new Integer[hi - lo];
            for (int i = lo; i < hi; i++) range[i - lo] = order[i];
            Arrays.sort(range, (p, q) -> Double.compare(centroids[(p - first) * 3 + sortAxis],
                    centroids[(q - first) * 3 + sortAxis]));
            for (int i = lo; i < hi; i++) order[i] = range[i - lo];
            int mid = (lo + hi) >>> 1;
            count[node] = 0;
            left[node] = buildNode(tri, centroids, first, lo, mid);
            right[node] = buildNode(tri, centroids, first, mid, hi);
            return node;
        }
    }

    private static final class RayScratch {
        final double sx, sy, sz, dx, dy, dz, maxDistance, inflation;
        boolean found;
        double best;
        int tri = -1;

        RayScratch(double sx, double sy, double sz, double dx, double dy, double dz, double maxDistance,
                   double inflation) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.maxDistance = maxDistance;
            this.inflation = inflation;
        }
    }

    private static final class NearestScratch {
        final double px, py, pz;
        final double[] point = new double[3];
        double bestSquared = Double.POSITIVE_INFINITY;
        double bestPlane = Double.NEGATIVE_INFINITY;
        int tri = -1;
        boolean triClosed;
        Part part;

        NearestScratch(double px, double py, double pz) {
            this.px = px;
            this.py = py;
            this.pz = pz;
        }
    }
}
