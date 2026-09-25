package com.yourname.berts_vehicle_pack.armor;

/**
 * Allocation-free triangle geometry used by {@link ArmorMeshVolume}.
 *
 * <p>Triangles live in flat arrays: triangle {@code i} occupies {@code tri[9i .. 9i+8]} as
 * {@code ax ay az bx by bz cx cy cz}. Ray directions are unit length. Methods that return a ray
 * parameter return NaN on a miss.</p>
 */
final class ArmorMeshMath {
    /** Barycentric slack so a ray through a shared edge is never lost between two triangles. */
    static final double EDGE_SLACK = 1.0E-12D;
    /** Directions closer to parallel than this count as parallel to a plane (matches the box slab test). */
    static final double PARALLEL_EPSILON = 1.0E-7D;

    private ArmorMeshMath() {
    }

    /**
     * Two-sided Moller-Trumbore test against triangle {@code o} (an array offset) translated by
     * {@code offset * n}. Returns the ray parameter of the intersection (any sign) or NaN.
     */
    static double rayTriangle(double[] tri, int o, double offset, double nx, double ny, double nz,
                              double sx, double sy, double sz, double dx, double dy, double dz) {
        double ax = tri[o] + offset * nx;
        double ay = tri[o + 1] + offset * ny;
        double az = tri[o + 2] + offset * nz;
        double e1x = tri[o + 3] - tri[o];
        double e1y = tri[o + 4] - tri[o + 1];
        double e1z = tri[o + 5] - tri[o + 2];
        double e2x = tri[o + 6] - tri[o];
        double e2y = tri[o + 7] - tri[o + 1];
        double e2z = tri[o + 8] - tri[o + 2];
        double px = dy * e2z - dz * e2y;
        double py = dz * e2x - dx * e2z;
        double pz = dx * e2y - dy * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        double e1sq = e1x * e1x + e1y * e1y + e1z * e1z;
        double e2sq = e2x * e2x + e2y * e2y + e2z * e2z;
        if (det * det <= 1.0E-28D * e1sq * e2sq || det == 0.0D) {
            return Double.NaN;
        }
        double inv = 1.0D / det;
        double tx = sx - ax;
        double ty = sy - ay;
        double tz = sz - az;
        double u = (tx * px + ty * py + tz * pz) * inv;
        if (u < -EDGE_SLACK || u > 1.0D + EDGE_SLACK) {
            return Double.NaN;
        }
        double qx = ty * e1z - tz * e1y;
        double qy = tz * e1x - tx * e1z;
        double qz = tx * e1y - ty * e1x;
        double v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < -EDGE_SLACK || u + v > 1.0D + EDGE_SLACK) {
            return Double.NaN;
        }
        return (e2x * qx + e2y * qy + e2z * qz) * inv;
    }

    /** Entering parameter (>= 0) of the ray into a sphere, or NaN (also when the start is inside). */
    static double raySphere(double sx, double sy, double sz, double dx, double dy, double dz,
                            double cx, double cy, double cz, double radius) {
        double ox = sx - cx;
        double oy = sy - cy;
        double oz = sz - cz;
        double b = ox * dx + oy * dy + oz * dz;
        double c = ox * ox + oy * oy + oz * oz - radius * radius;
        double h = b * b - c;
        if (h < 0.0D) {
            return Double.NaN;
        }
        double t = -b - Math.sqrt(h);
        return t >= 0.0D ? t : Double.NaN;
    }

    /**
     * Entering parameter (>= 0) of the ray into the capsule of radius {@code radius} around the
     * segment AB, or NaN. A start inside the capsule is not an entry and returns NaN.
     */
    static double rayCapsule(double sx, double sy, double sz, double dx, double dy, double dz,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double radius) {
        double best = Double.NaN;
        double bax = bx - ax;
        double bay = by - ay;
        double baz = bz - az;
        double oax = sx - ax;
        double oay = sy - ay;
        double oaz = sz - az;
        double baba = bax * bax + bay * bay + baz * baz;
        if (baba > 1.0E-24D) {
            double bard = bax * dx + bay * dy + baz * dz;
            double baoa = bax * oax + bay * oay + baz * oaz;
            double rdoa = dx * oax + dy * oay + dz * oaz;
            double oaoa = oax * oax + oay * oay + oaz * oaz;
            double a = baba - bard * bard;
            if (a > 1.0E-18D * baba) {
                double b = baba * rdoa - baoa * bard;
                double c = baba * oaoa - baoa * baoa - radius * radius * baba;
                double h = b * b - a * c;
                if (h >= 0.0D) {
                    double t = (-b - Math.sqrt(h)) / a;
                    double y = baoa + t * bard;
                    if (t >= 0.0D && y > 0.0D && y < baba) {
                        best = t;
                    }
                }
            }
        }
        double t = raySphere(sx, sy, sz, dx, dy, dz, ax, ay, az, radius);
        if (t == t && !(best <= t)) best = t;
        t = raySphere(sx, sy, sz, dx, dy, dz, bx, by, bz, radius);
        if (t == t && !(best <= t)) best = t;
        return best;
    }

    /**
     * Closest point on triangle {@code o} to P (Ericson, Real-Time Collision Detection 5.1.5).
     * Writes the point to {@code out[0..2]} and returns the squared distance.
     */
    static double closestPointOnTriangle(double[] tri, int o, double px, double py, double pz, double[] out) {
        double ax = tri[o], ay = tri[o + 1], az = tri[o + 2];
        double bx = tri[o + 3], by = tri[o + 4], bz = tri[o + 5];
        double cx = tri[o + 6], cy = tri[o + 7], cz = tri[o + 8];
        double abx = bx - ax, aby = by - ay, abz = bz - az;
        double acx = cx - ax, acy = cy - ay, acz = cz - az;
        double apx = px - ax, apy = py - ay, apz = pz - az;
        double d1 = abx * apx + aby * apy + abz * apz;
        double d2 = acx * apx + acy * apy + acz * apz;
        double rx, ry, rz;
        if (d1 <= 0.0D && d2 <= 0.0D) {
            rx = ax; ry = ay; rz = az;
        } else {
            double bpx = px - bx, bpy = py - by, bpz = pz - bz;
            double d3 = abx * bpx + aby * bpy + abz * bpz;
            double d4 = acx * bpx + acy * bpy + acz * bpz;
            if (d3 >= 0.0D && d4 <= d3) {
                rx = bx; ry = by; rz = bz;
            } else {
                double vc = d1 * d4 - d3 * d2;
                if (vc <= 0.0D && d1 >= 0.0D && d3 <= 0.0D) {
                    double v = d1 / (d1 - d3);
                    rx = ax + v * abx; ry = ay + v * aby; rz = az + v * abz;
                } else {
                    double cpx = px - cx, cpy = py - cy, cpz = pz - cz;
                    double d5 = abx * cpx + aby * cpy + abz * cpz;
                    double d6 = acx * cpx + acy * cpy + acz * cpz;
                    if (d6 >= 0.0D && d5 <= d6) {
                        rx = cx; ry = cy; rz = cz;
                    } else {
                        double vb = d5 * d2 - d1 * d6;
                        if (vb <= 0.0D && d2 >= 0.0D && d6 <= 0.0D) {
                            double w = d2 / (d2 - d6);
                            rx = ax + w * acx; ry = ay + w * acy; rz = az + w * acz;
                        } else {
                            double va = d3 * d6 - d5 * d4;
                            if (va <= 0.0D && (d4 - d3) >= 0.0D && (d5 - d6) >= 0.0D) {
                                double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
                                rx = bx + w * (cx - bx); ry = by + w * (cy - by); rz = bz + w * (cz - bz);
                            } else {
                                double denom = 1.0D / (va + vb + vc);
                                double v = vb * denom;
                                double w = vc * denom;
                                rx = ax + abx * v + acx * w;
                                ry = ay + aby * v + acy * w;
                                rz = az + abz * v + acz * w;
                            }
                        }
                    }
                }
            }
        }
        out[0] = rx;
        out[1] = ry;
        out[2] = rz;
        double ex = px - rx, ey = py - ry, ez = pz - rz;
        return ex * ex + ey * ey + ez * ez;
    }

    /**
     * Closest points of segments P1Q1 and P2Q2 (Ericson 5.1.9). Writes {@code s} (on the first
     * segment, 0..1) to {@code out[0]} and the closest point on the second segment to
     * {@code out[1..3]}; returns the squared distance.
     */
    static double segmentSegment(double p1x, double p1y, double p1z, double q1x, double q1y, double q1z,
                                 double p2x, double p2y, double p2z, double q2x, double q2y, double q2z,
                                 double[] out) {
        double d1x = q1x - p1x, d1y = q1y - p1y, d1z = q1z - p1z;
        double d2x = q2x - p2x, d2y = q2y - p2y, d2z = q2z - p2z;
        double rx = p1x - p2x, ry = p1y - p2y, rz = p1z - p2z;
        double a = d1x * d1x + d1y * d1y + d1z * d1z;
        double e = d2x * d2x + d2y * d2y + d2z * d2z;
        double f = d2x * rx + d2y * ry + d2z * rz;
        double s;
        double t;
        final double eps = 1.0E-20D;
        if (a <= eps && e <= eps) {
            s = 0.0D;
            t = 0.0D;
        } else if (a <= eps) {
            s = 0.0D;
            t = clamp01(f / e);
        } else {
            double c = d1x * rx + d1y * ry + d1z * rz;
            if (e <= eps) {
                t = 0.0D;
                s = clamp01(-c / a);
            } else {
                double b = d1x * d2x + d1y * d2y + d1z * d2z;
                double denom = a * e - b * b;
                s = denom > eps * a * e ? clamp01((b * f - c * e) / denom) : 0.0D;
                t = (b * s + f) / e;
                if (t < 0.0D) {
                    t = 0.0D;
                    s = clamp01(-c / a);
                } else if (t > 1.0D) {
                    t = 1.0D;
                    s = clamp01((b - c) / a);
                }
            }
        }
        double c1x = p1x + d1x * s, c1y = p1y + d1y * s, c1z = p1z + d1z * s;
        double c2x = p2x + d2x * t, c2y = p2y + d2y * t, c2z = p2z + d2z * t;
        out[0] = s;
        out[1] = c2x;
        out[2] = c2y;
        out[3] = c2z;
        double ex = c1x - c2x, ey = c1y - c2y, ez = c1z - c2z;
        return ex * ex + ey * ey + ez * ez;
    }

    /**
     * Exact squared distance between the segment {@code S + D * [0, length]} and triangle
     * {@code o}. Writes the segment parameter (0..length) to {@code out[0]} and the closest
     * triangle point to {@code out[1..3]}. {@code scratch} needs 4 entries.
     */
    static double segmentTriangle(double[] tri, int o, double sx, double sy, double sz,
                                  double dx, double dy, double dz, double length,
                                  double[] out, double[] scratch) {
        double t = rayTriangle(tri, o, 0.0D, 0.0D, 0.0D, 0.0D, sx, sy, sz, dx, dy, dz);
        if (t >= 0.0D && t <= length) {
            out[0] = t;
            out[1] = sx + dx * t;
            out[2] = sy + dy * t;
            out[3] = sz + dz * t;
            return 0.0D;
        }
        double ex = sx + dx * length, ey = sy + dy * length, ez = sz + dz * length;
        double best = closestPointOnTriangle(tri, o, sx, sy, sz, scratch);
        out[0] = 0.0D;
        out[1] = scratch[0];
        out[2] = scratch[1];
        out[3] = scratch[2];
        double candidate = closestPointOnTriangle(tri, o, ex, ey, ez, scratch);
        if (candidate < best) {
            best = candidate;
            out[0] = length;
            out[1] = scratch[0];
            out[2] = scratch[1];
            out[3] = scratch[2];
        }
        for (int edge = 0; edge < 3; edge++) {
            int a = o + edge * 3;
            int b = o + ((edge + 1) % 3) * 3;
            candidate = segmentSegment(sx, sy, sz, ex, ey, ez,
                    tri[a], tri[a + 1], tri[a + 2], tri[b], tri[b + 1], tri[b + 2], scratch);
            if (candidate < best) {
                best = candidate;
                out[0] = scratch[0] * length;
                out[1] = scratch[1];
                out[2] = scratch[2];
                out[3] = scratch[3];
            }
        }
        return best;
    }

    /** Signed solid angle of triangle {@code o} seen from P (Van Oosterom and Strackee). */
    static double solidAngle(double[] tri, int o, double px, double py, double pz) {
        double ax = tri[o] - px, ay = tri[o + 1] - py, az = tri[o + 2] - pz;
        double bx = tri[o + 3] - px, by = tri[o + 4] - py, bz = tri[o + 5] - pz;
        double cx = tri[o + 6] - px, cy = tri[o + 7] - py, cz = tri[o + 8] - pz;
        double la = Math.sqrt(ax * ax + ay * ay + az * az);
        double lb = Math.sqrt(bx * bx + by * by + bz * bz);
        double lc = Math.sqrt(cx * cx + cy * cy + cz * cz);
        double numerator = ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx);
        double denominator = la * lb * lc + (ax * bx + ay * by + az * bz) * lc
                + (ax * cx + ay * cy + az * cz) * lb + (bx * cx + by * cy + bz * cz) * la;
        return 2.0D * Math.atan2(numerator, denominator);
    }

    /**
     * Slab entry of the ray into the box {@code b[o..o+5]} (min xyz, max xyz) grown by {@code pad},
     * restricted to {@code [0, maxT]}. Returns the entry parameter or NaN.
     */
    static double rayAabb(double[] b, int o, double pad, double sx, double sy, double sz,
                          double dx, double dy, double dz, double maxT) {
        double tMin = 0.0D;
        double tMax = maxT;
        double min = b[o] - pad;
        double max = b[o + 3] + pad;
        if (Math.abs(dx) < 1.0E-15D) {
            if (sx < min || sx > max) return Double.NaN;
        } else {
            double t1 = (min - sx) / dx;
            double t2 = (max - sx) / dx;
            if (t1 > t2) { double swap = t1; t1 = t2; t2 = swap; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return Double.NaN;
        }
        min = b[o + 1] - pad;
        max = b[o + 4] + pad;
        if (Math.abs(dy) < 1.0E-15D) {
            if (sy < min || sy > max) return Double.NaN;
        } else {
            double t1 = (min - sy) / dy;
            double t2 = (max - sy) / dy;
            if (t1 > t2) { double swap = t1; t1 = t2; t2 = swap; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return Double.NaN;
        }
        min = b[o + 2] - pad;
        max = b[o + 5] + pad;
        if (Math.abs(dz) < 1.0E-15D) {
            if (sz < min || sz > max) return Double.NaN;
        } else {
            double t1 = (min - sz) / dz;
            double t2 = (max - sz) / dz;
            if (t1 > t2) { double swap = t1; t1 = t2; t2 = swap; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return Double.NaN;
        }
        return tMin;
    }

    /** Squared distance from P to the box {@code b[o..o+5]}; 0 inside. */
    static double pointAabbDistanceSquared(double[] b, int o, double px, double py, double pz) {
        double dx = Math.max(0.0D, Math.max(b[o] - px, px - b[o + 3]));
        double dy = Math.max(0.0D, Math.max(b[o + 1] - py, py - b[o + 4]));
        double dz = Math.max(0.0D, Math.max(b[o + 2] - pz, pz - b[o + 5]));
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : Math.min(value, 1.0D);
    }
}
