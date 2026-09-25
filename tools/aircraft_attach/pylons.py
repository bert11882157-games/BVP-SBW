"""Pylon attachment stations derived from aircraft geometry (hull blocks).

A station is a point on the structure's surface plus the face it belongs to:
  bottom  a store hangs underneath (its top anchor touches the point),
  left    the point is on a -X facing side (the store sits on the -X side, its right anchor touches),
  right   the point is on a +X facing side (the store sits on the +X side, its left anchor touches).

Finding the support
  Rays are cast upward from just below the mount position; the first structure hit is the support. Hidden baked
  stores (`suspended_*`) and landing gear (retracted in flight) are not structure.
  A small island (pylon blade, rail, crossbar, arm) is a pylon; a large one (wing or fuselage skin) is skin.
  The pylon assembly is the support island plus the small islands touching it.

Bottom station
  pylon: the support's downward-facing surface near the mount, at its lengthwise centre and lateral centre.
  skin : straight above the mount position.

Side stations (lateral members only)
  The extreme -X and +X vertical faces of the assembly below the wing. A face counts as a rail/crossbar end
  when it stands out laterally from the part of the pylon directly above the bottom station (the blade) by
  more than SIDE_PROTRUSION; the station is the face's centre (vertical middle, lengthwise centre).
"""
import os

import numpy as np

import geo

STRUCTURE_EXCLUDE = ("suspended_", "landing_gear", "gear_")
PYLON_MAX_EXTENT = np.array([1.2, 1.4, 7.0])
PYLON_MAX_TRIS = 600
TOUCH_TOL = 0.012
SIDE_PROTRUSION = 0.05
DOWN_NORMAL = -0.5
BOTTOM_BAND = 0.03
SIDE_BAND = (-0.01, 0.3)


def structure(aircraft):
    path = os.path.join(geo.CUSTOM_GEO, aircraft + ".geo.json")
    keep = lambda bone: not any(k in bone for k in STRUCTURE_EXCLUDE)
    return geo.model_mesh(path, keep).transformed(geo.geo_to_hull)


class Structure:
    def __init__(self, aircraft):
        self.name = aircraft
        self.mesh = structure(aircraft)
        self.box = {}
        self.count = {}
        self._island_meshes = {}
        for i in self.mesh.islands():
            sel = self.mesh.island == i
            p = self.mesh.tris[sel].reshape(-1, 3)
            self.box[i] = (p.min(0), p.max(0))
            self.count[i] = int(sel.sum())

    def is_small(self, i):
        lo, hi = self.box[i]
        return bool(np.all(hi - lo <= PYLON_MAX_EXTENT) and self.count[i] <= PYLON_MAX_TRIS)

    def up_hit(self, p, islands=None, tmax=1.5):
        mesh = self.mesh if islands is None else self.mesh.island_mesh(islands)
        t, idx = geo.first_hit(mesh, p, [0, 1, 0], tmax)
        if t is None:
            return None, None
        return np.asarray(p, float) + [0, t, 0], int(mesh.island[idx])

    def section_low(self, island, z):
        """Lowest point of an island's cross-section at z: (y_low, [x values within BOTTOM_BAND of it])."""
        m = self.island_mesh(island)
        pts = []
        for tri in m.tris:
            seg = _plane_segment(tri, 2, z)
            if seg is not None:
                pts.extend(seg)
        if not pts:
            return None, None
        pts = np.array(pts)
        y = pts[:, 1].min()
        return float(y), pts[pts[:, 1] <= y + BOTTOM_BAND, 0]

    def island_mesh(self, island):
        if island not in self._island_meshes:
            self._island_meshes[island] = self.mesh.island_mesh(island)
        return self._island_meshes[island]

    def find_support(self, P, reach=0.8):
        """The pylon island at the mount (open-bottomed blades included), else the skin straight above it."""
        P = np.asarray(P, float)
        best = None
        for i, (lo, hi) in self.box.items():
            if not self.is_small(i):
                continue
            if not (lo[0] - 0.03 <= P[0] <= hi[0] + 0.03 and lo[2] - 0.05 <= P[2] <= hi[2] + 0.05):
                continue
            if not (P[1] - 0.15 <= lo[1] <= P[1] + reach):
                continue
            y, xs = self.section_low(i, float(np.clip(P[2], lo[2] + 1e-3, hi[2] - 1e-3)))
            if y is None or not (P[1] - 0.15 <= y <= P[1] + reach):
                continue
            if not np.any(np.abs(xs - P[0]) < 0.3):
                continue
            cost = abs(y - P[1]) + 0.5 * float(np.min(np.abs(xs - P[0])))
            if best is None or cost < best[0]:
                best = (cost, i, y)
        if best is not None:
            return dict(hit=np.array([P[0], best[2], P[2]]), island=best[1], offset=(0.0, 0.0))
        for dx in (0.0, -0.02, 0.02, -0.05, 0.05):
            hit, isl = self.up_hit(P + [dx, -0.05, 0.0], tmax=reach)
            if hit is not None:
                return dict(hit=hit, island=isl, offset=(dx, 0.0))
        return None

    def assembly(self, island):
        """The support island plus small islands touching it (transitively), never the wing/fuselage skin."""
        if not self.is_small(island):
            return [island]
        out = {island}
        frontier = [island]
        while frontier:
            i = frontier.pop()
            lo, hi = self.box[i]
            for j, (a, b) in self.box.items():
                if j in out or not self.is_small(j):
                    continue
                if np.all(a <= hi + TOUCH_TOL) and np.all(b >= lo - TOUCH_TOL):
                    if self._touching(i, j):
                        out.add(j)
                        frontier.append(j)
        return sorted(out)

    def _touching(self, i, j):
        pi = self.mesh.tris[self.mesh.island == i].reshape(-1, 3)
        tj = self.mesh.tris[self.mesh.island == j]
        # any vertex of one within TOUCH_TOL of the other's surface
        for p in pi[:: max(1, len(pi) // 400)]:
            if np.min(geo.point_triangle_distances(tj, p)) <= TOUCH_TOL:
                return True
        pj = tj.reshape(-1, 3)
        ti = self.mesh.tris[self.mesh.island == i]
        for p in pj[:: max(1, len(pj) // 400)]:
            if np.min(geo.point_triangle_distances(ti, p)) <= TOUCH_TOL:
                return True
        return False

    # ------------------------------------------------------------------ stations
    def bottom_station(self, P, support):
        """Lowest edge of the support at its lengthwise centre (pylon), or the skin straight above (skin)."""
        P = np.asarray(P, float)
        isl = support["island"]
        if not self.is_small(isl):
            hit, _ = self.up_hit(P + [0, -0.05, 0], [isl])
            if hit is None:
                hit = support["hit"]
            return dict(point=hit, face="bottom", kind="skin", island=isl)
        lo, hi = self.box[isl]
        zs = np.linspace(lo[2] + 1e-3, hi[2] - 1e-3, 81)
        lows = [self.section_low(isl, z) for z in zs]
        ys = np.array([np.inf if y is None else y for y, _ in lows])
        ymin = ys.min()
        ok = ys <= ymin + BOTTOM_BAND
        # contiguous bottom-edge run nearest the mount position
        runs, start = [], None
        for k, v in enumerate(ok):
            if v and start is None:
                start = k
            if (not v or k == len(ok) - 1) and start is not None:
                runs.append((start, k if v else k - 1))
                start = None
        run = min(runs, key=lambda r: 0 if zs[r[0]] <= P[2] <= zs[r[1]] else min(abs(zs[r[0]] - P[2]), abs(zs[r[1]] - P[2])))
        z0, z1 = zs[run[0]], zs[run[1]]
        # extend to the true ends of the bottom edge (between samples)
        step = zs[1] - zs[0]
        z0 = max(lo[2], z0 - 0.5 * step) if run[0] > 0 else lo[2]
        z1 = min(hi[2], z1 + 0.5 * step) if run[1] < len(zs) - 1 else hi[2]
        zc = 0.5 * (z0 + z1)
        y, xs = self.section_low(isl, zc)
        xc = 0.5 * (xs.min() + xs.max())
        return dict(point=np.array([xc, y, zc]), face="bottom", kind="pylon", island=isl,
                    z_range=(float(z0), float(z1)), x_range=(float(xs.min()), float(xs.max())))

    def side_faces(self, islands, bottom, below_y=None):
        """Extreme -X / +X vertical faces of the assembly (with their protrusion beyond the blade)."""
        m = self.mesh.island_mesh(islands)
        n = m.normal
        c = m.tris.mean(1)
        res = {}
        by = bottom["point"][1]
        band = (by + SIDE_BAND[0], by + SIDE_BAND[1]) if below_y is None else below_y
        blade = _blade_extent(m, bottom)
        for face, sign in (("left", -1), ("right", 1)):
            sel = (n[:, 0] * sign > 0.7) & (c[:, 1] >= band[0]) & (c[:, 1] <= band[1])
            if not np.any(sel):
                continue
            xs = c[sel, 0]
            extreme = xs.max() if sign > 0 else xs.min()
            on = sel & (np.abs(c[:, 0] - extreme) < 0.006)
            pts = m.tris[on].reshape(-1, 3)
            y0, y1 = pts[:, 1].min(), pts[:, 1].max()
            z0, z1 = pts[:, 2].min(), pts[:, 2].max()
            x = pts[:, 0].max() if sign > 0 else pts[:, 0].min()
            protrusion = (x - blade[1]) if sign > 0 else (blade[0] - x)
            # side by side with the bottom station when the face reaches it (twin rails hang stores abreast)
            bz = bottom["point"][2]
            zs = bz if z0 <= bz <= z1 else 0.5 * (z0 + z1)
            res[face] = dict(point=np.array([x, 0.5 * (y0 + y1), zs]), face=face,
                             y_range=(y0, y1), z_range=(z0, z1), protrusion=float(protrusion))
        return res


def _blade_extent(m, bottom):
    """x range of the pylon directly above the bottom station, a little above its bottom face."""
    p = bottom["point"]
    xs = []
    for dy in (0.06, 0.1, 0.15):
        for tri in m.tris:
            seg = _plane_segment(tri, 1, p[1] + dy)
            if seg is None:
                continue
            a, b = seg
            if min(a[2], b[2]) - 0.05 <= p[2] <= max(a[2], b[2]) + 0.05:
                xs.extend([a[0], b[0]])
        if xs:
            break
    if not xs:
        return (p[0] - 0.01, p[0] + 0.01)
    xs = np.array(xs)
    near = xs[np.abs(xs - p[0]) < 0.25]
    if not len(near):
        near = xs
    return (float(near.min()), float(near.max()))


def _plane_segment(tri, axis, value):
    d = tri[:, axis] - value
    pts = []
    for i in range(3):
        j = (i + 1) % 3
        if (d[i] <= 0 < d[j]) or (d[j] <= 0 < d[i]):
            s = d[i] / (d[i] - d[j])
            pts.append(tri[i] + s * (tri[j] - tri[i]))
    if len(pts) == 2:
        return pts
    return None


def mirror_station(st):
    p = np.array(st["point"], float) * [-1, 1, 1]
    face = {"left": "right", "right": "left"}.get(st["face"], st["face"])
    out = dict(st)
    out["point"] = p
    out["face"] = face
    return out
