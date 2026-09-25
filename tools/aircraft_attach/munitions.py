"""Munition attachment anchors derived from store model geometry.

Rules (documented in README.md):
  body     connected mesh islands centred on the body axis whose radial size is within the main body's;
           fins, canards, lugs and rings that sit off the axis are not body.
  axis     x0 = centre of the main body island's x range, y0 = centre of its y range; the axis runs along z.
  lugs     small off-body islands sitting on top of the body (bottom within 0.35 R of the body top, centred
           laterally, narrower than the body, protruding less than 0.6 R, and in the middle 70% of the length).
           Tall centred islands are fins, off-centre islands are fins/canards/strakes.
  radius   body radius at z = the smallest outermost-skin distance over 8 directions (fins, intakes and strakes
           occupy only some directions); the body radius R is its 80th percentile along the length.
  station  with lugs: the lengthwise centre of the lug tops (one lug: its centre).
           lugless missile: 14% of the munition length aft of its lengthwise centre (the established convention
           that lets a rail missile extend forward of its launcher), kept where the body radius is >= 80% of R.
           lugless bomb or pod: the middle of the cylindrical section (radius >= 95% of R), its centre of mass.
  top      with lugs: the lug top (one lug) or the midpoint of the lug tops (2+ lugs).
           without lugs: the top of the body surface straight above the axis at the station.
  left/right  the body side surfaces at the station, at the axis height (x extremes of the body cross-section),
           named for the munition's own port/starboard once hung nose forward.
  axis point  (x0, y0, station): where the launch is derived from.

All anchors are written in the MountAnchor frame of BvpSuspendedStoreRenderer: model-file blocks with x negated,
before the ModelForward turn and before Scale.
"""
import os

import numpy as np

import geo

LUG_MAX_PROTRUSION = 0.6       # of body radius
LUG_MAX_WIDTH = 0.9            # of body radius (full x width)
LUG_CENTRE_TOL = 0.35          # of body radius
LUG_LENGTH_BAND = 0.15         # lugs lie within the middle 70% of the munition length
CYLINDER_FRACTION = 0.95       # cylindrical section: radius >= 95% of the body radius
USABLE_FRACTION = 0.8          # a lugless missile never hangs from a nose cone or boat tail
MISSILE_AFT_FRACTION = 0.14


class MunitionGeometry:
    def __init__(self, model_name):
        self.name = model_name
        path = os.path.join(geo.CUSTOM_GEO, "aircraft_stores", model_name + ".geo.json")
        self.mesh = geo.model_mesh(path).transformed(lambda p: p / 16.0)   # store blocks (geo / 16)
        self._analyse()

    # ----------------------------------------------------------------- analysis
    def _island_boxes(self):
        boxes = {}
        for i in self.mesh.islands():
            sel = self.mesh.island == i
            pts = self.mesh.tris[sel].reshape(-1, 3)
            boxes[i] = (pts.min(0), pts.max(0), int(sel.sum()))
        return boxes

    def _analyse(self):
        m = self.mesh
        boxes = self._island_boxes()
        self.boxes = boxes
        lo, hi = m.bounds()
        self.zmin, self.zmax = lo[2], hi[2]
        self.length = hi[2] - lo[2]

        # Main body: the longest roughly round island (fins attached to it do not move its box centre).
        def score(item):
            i, (a, b, n) = item
            ext = b - a
            round_ok = min(ext[0], ext[1]) >= 0.55 * max(ext[0], ext[1])
            return (1 if round_ok else 0, ext[2] * ext[0] * ext[1])

        main = max(boxes.items(), key=score)[0]
        a, b, _ = boxes[main]
        self.x0 = 0.5 * (a[0] + b[0])
        self.y0 = 0.5 * (a[1] + b[1])
        self.main = main
        # Body islands: every island whose box contains the axis (nose/tail cones, body sections).
        eps = 1e-4
        self.body_ids = [i for i, (a, b, n) in boxes.items()
                         if a[0] - eps <= self.x0 <= b[0] + eps and a[1] - eps <= self.y0 <= b[1] + eps]
        self.body = m.island_mesh(self.body_ids)
        blo, bhi = self.body.bounds()
        zs = np.linspace(blo[2] + 1e-4, bhi[2] - 1e-4, 300)
        self.profile_z = zs
        self.profile_r = np.array([self._section_radius(z) for z in zs])
        valid = self.profile_r[np.isfinite(self.profile_r)]
        # Body radius: exceeded over at least 20% of the length (short rings and intakes do not count).
        self.radius = float(np.percentile(valid, 80))
        self.cylinder = self._longest_run(CYLINDER_FRACTION)
        self.usable = self._longest_run(USABLE_FRACTION)
        self.lugs = self._find_lugs()

    def _longest_run(self, fraction):
        zs = self.profile_z
        ok = np.nan_to_num(self.profile_r) >= fraction * self.radius
        runs, start = [], None
        for k, v in enumerate(ok):
            if v and start is None:
                start = k
            if (not v or k == len(ok) - 1) and start is not None:
                runs.append((start, k if v else k - 1))
                start = None
        best = max(runs, key=lambda r: zs[r[1]] - zs[r[0]])
        return float(zs[best[0]]), float(zs[best[1]])

    def _section(self, mesh, z):
        """Segments of mesh ∩ plane z; returns an (n, 2, 2) array of xy endpoints."""
        t = mesh.tris
        d = t[:, :, 2] - z
        segs = []
        for tri, dd in zip(t, d):
            pts = []
            for i in range(3):
                j = (i + 1) % 3
                if (dd[i] <= 0 < dd[j]) or (dd[j] <= 0 < dd[i]):
                    s = dd[i] / (dd[i] - dd[j])
                    pts.append(tri[i] + s * (tri[j] - tri[i]))
            if len(pts) == 2:
                segs.append([pts[0][:2], pts[1][:2]])
        return np.array(segs).reshape(-1, 2, 2)

    def _section_radius(self, z):
        """Body radius at z: the smallest outermost-skin distance over 8 directions (every 45 degrees).

        Cruciform fins, strakes and intakes occupy some directions only, so they never set the radius."""
        best = np.inf
        for k in range(8):
            ang = k * np.pi / 4
            d = np.array([np.cos(ang), np.sin(ang), 0.0])
            o = np.array([self.x0, self.y0, z]) + d * 5.0
            t, idx = geo.first_hit(self.body, o, -d)
            if t is None:
                return np.nan
            best = min(best, 5.0 - t)
        return float(best)

    def surface_from_axis(self, z, direction):
        """First surface hit from the axis point at z along direction (body skin, fins attached or not)."""
        o = np.array([self.x0, self.y0, z], float)
        t, idx = geo.first_hit(self.mesh, o, np.asarray(direction, float))
        return None if t is None else o + t * np.asarray(direction, float)

    def _outer_skin(self, z, axis_dir, mesh=None):
        """Outermost surface crossing along axis_dir from the body axis at z, within 1.25 body radii.

        Internal faces (rails, bulkheads, inner tubes) and fin tips beyond the body are ignored."""
        d = np.asarray(axis_dir, float)
        o = np.array([self.x0, self.y0, z], float) - d * 5.0
        t, idx = geo.ray_hits(self.mesh if mesh is None else mesh, o, d)
        s = t - 5.0                                    # signed distance from the axis
        s = s[(s > 1e-6) & (s <= 1.25 * self.radius)]
        return float(s.max()) if len(s) else None

    def body_top(self, z, body_only=False):
        s = self._outer_skin(z, [0, 1, 0], self.body if body_only else None)
        return None if s is None else self.y0 + s

    def body_side(self, z, sign):
        s = self._outer_skin(z, [sign, 0, 0])
        return None if s is None else self.x0 + sign * s

    def _find_lugs(self):
        """Clusters of small islands on top of the body; symmetric fin/canard sets are rejected."""
        R = self.radius
        cands = []
        for i, (a, b, n) in self.boxes.items():
            if i in self.body_ids:
                continue
            c = 0.5 * (a + b)
            if c[2] < self.zmin + LUG_LENGTH_BAND * self.length or c[2] > self.zmax - LUG_LENGTH_BAND * self.length:
                continue
            if abs(c[0] - self.x0) > 0.8 * R:
                continue
            top = self.body_top(c[2], body_only=True)
            if top is None or a[1] < top - 0.5 * R or b[1] <= top + 1e-4:
                continue
            if b[1] - top > LUG_MAX_PROTRUSION * R:
                continue
            # A fin/canard set has a partner below the axis at the same stations.
            partner = any(j != i and abs(bb[2] - b[2]) < 0.01 and abs(aa[2] - a[2]) < 0.01 and
                          0.5 * (aa[1] + bb[1]) < self.y0 - 0.3 * R for j, (aa, bb, nn) in self.boxes.items())
            if partner:
                continue
            cands.append(dict(island=i, lo=a, hi=b))
        # Stacked or side-by-side islands at the same station form one lug.
        cands.sort(key=lambda l: l["lo"][2])
        groups = []
        for c in cands:
            if groups and c["lo"][2] <= max(g["hi"][2] for g in groups[-1]) + 1e-4:
                groups[-1].append(c)
            else:
                groups.append([c])
        lugs = []
        for g in groups:
            lo = np.min([c["lo"] for c in g], 0)
            hi = np.max([c["hi"] for c in g], 0)
            cx = 0.5 * (lo[0] + hi[0])
            if abs(cx - self.x0) > LUG_CENTRE_TOL * R or hi[0] - lo[0] > 1.5 * R:
                continue
            lugs.append(dict(islands=[c["island"] for c in g], lo=lo, hi=hi,
                             top=np.array([self.x0, hi[1], 0.5 * (lo[2] + hi[2])])))
        return lugs

    # ------------------------------------------------------------------ anchors
    def anchors(self, model_forward, missile=True):
        """Anchors in store blocks (geo/16, no x negation). Keys: top, left, right, axis, station, lugs.

        missile: lugless missiles hang 14% of their length aft of centre; lugless bombs and pods hang at the
        middle of their cylindrical section (their centre of mass)."""
        nose_sign = -1.0 if model_forward == "-Z" else 1.0   # nose direction along model z
        if self.lugs:
            tops = np.array([l["top"] for l in self.lugs])
            top = tops.mean(0)
            station = float(top[2])
            self.rule = "lug" if len(self.lugs) == 1 else "lug midpoint"
        else:
            c0, c1 = self.cylinder
            if missile:
                centre = 0.5 * (self.zmin + self.zmax)
                station = centre - nose_sign * MISSILE_AFT_FRACTION * self.length
                u0, u1 = self.usable
                margin = min(0.05, 0.25 * (u1 - u0))
                station = float(np.clip(station, u0 + margin, u1 - margin))
                self.rule = "missile 14% aft"
            else:
                station = 0.5 * (c0 + c1)
                self.rule = "cylinder middle"
            y = self.body_top(station)
            top = np.array([self.x0, y, station])
        xp = self.body_side(station, +1)
        xn = self.body_side(station, -1)
        side_pos = np.array([xp, self.y0, station])
        side_neg = np.array([xn, self.y0, station])
        # Model +x is hull +x for a -Z store (no turn) and hull -x for a +Z store (turned 180 degrees).
        if model_forward == "-Z":
            right, left = side_pos, side_neg
        else:
            right, left = side_neg, side_pos
        return dict(top=top, left=left, right=right, axis=np.array([self.x0, self.y0, station]),
                    station=station, lugs=len(self.lugs))


def to_anchor_frame(p):
    """store blocks (geo/16) -> MountAnchor frame (x negated)."""
    p = np.asarray(p, float)
    return np.array([-p[0], p[1], p[2]])


def hull_offset(model_delta_anchor_frame, model_forward, scale):
    """Hull-local delta of a model delta expressed in the MountAnchor frame (see AircraftStoreAttachment)."""
    d = np.asarray(model_delta_anchor_frame, float) * scale
    if model_forward == "-Z":
        return np.array([-d[0], d[1], -d[2]])
    return d


def placed_mesh(munition, anchor_frame_point, model_forward, scale, hull_point):
    """The munition mesh in hull blocks when `anchor_frame_point` is placed on `hull_point`."""
    a = np.asarray(anchor_frame_point, float)

    def fn(p):
        m = p * np.array([-1.0, 1.0, 1.0])          # store blocks -> anchor frame
        d = (m - a) * scale
        if model_forward == "-Z":
            d = d * np.array([-1.0, 1.0, -1.0])
        return d + np.asarray(hull_point, float)

    return munition.mesh.transformed(fn)


if __name__ == "__main__":
    import sys
    import glob
    names = sys.argv[1:] or sorted(os.path.basename(f)[:-9] for f in glob.glob(
        os.path.join(geo.CUSTOM_GEO, "aircraft_stores", "*.geo.json")) if "_flight" not in f)
    for n in names:
        g = MunitionGeometry(n)
        a = g.anchors("-Z")
        print("%-24s L=%.3f R=%.3f axis=(%.3f,%.3f) cyl=[%.3f,%.3f] lugs=%d top=%s L=%s R=%s" % (
            n, g.length, g.radius, g.x0, g.y0, g.cylinder[0], g.cylinder[1], len(g.lugs),
            np.round(a["top"], 4), np.round(a["left"], 4), np.round(a["right"], 4)))
        for l in g.lugs:
            print("      lug x[%.3f,%.3f] y[%.3f,%.3f] z[%.3f,%.3f]" % (l["lo"][0], l["hi"][0], l["lo"][1], l["hi"][1], l["lo"][2], l["hi"][2]))
