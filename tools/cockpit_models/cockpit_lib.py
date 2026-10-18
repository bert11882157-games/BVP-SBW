#!/usr/bin/env python3
"""Shared cockpit modelling library: fits a modelled cockpit into an aircraft's existing canopy and replaces the old
interior with it (tools/cockpit_models/fleet.py drives it for the fleet; mig_29.py is the hand-built original it was
generalised from).

What it builds, per crew station, in vehicle-local blocks (x left, y up, z forward; model pixels are (x, y, -z) * 16):
  * a three-facet instrument panel (flat centre, wings turned toward the pilot, top leaning away) sized to the tub;
  * the glareshield coaming lofted from the panel lip forward to the windscreen base;
  * a head-up display (body, combiner frame and combiner glass, plus the HeadUpDisplay resource the game projects
    onto it) on HUD-equipped jets, or a reflector gunsight (body and glass plate) on the others;
  * side consoles with switch blocks, side walls, sill caps, floor, rear bulkhead, footwell, rudder pedals, throttle,
    the seat (ejection seat on jets, armoured bucket seat on piston fighters) and a control stick (centre or side
    stick) in the existing animated stick bone, pivoting where the stick rig pivots;
  * instruments laid out by era: game-drawn dials (CockpitGauges, live where the game knows the value) and
    multi-function / radar displays (FlightDisplays).

Textures are never written: every face takes its colour from a uniform texel block already in the aircraft's own
texture (Swatches), so the model works with the textures in the repository as they are.

Winding: the mesh loader (sbwmeshloader) mirrors X and takes (p1 - p0) x (p2 - p0) of the mirrored points as the face
normal, so in file pixels a face winds clockwise seen from its front, as the hull's own faces do (Mesh.poly_mesh).

Old interior: faces of the cockpit tub below the canopy sill (except the outer skin), and small loose parts inside
the canopy (old seat, panel, gunsight, consoles) are removed from the visual bones. Moving canopy bones and wreck
pieces of other bones than the hull are never touched.
"""
import json, math, os
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO_DIR = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
ASSET_DIR = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles')
DATA_DIR = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles')
TEX_DIR = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity')


def texture_path(vid):
    """the aircraft's texture; BVP_TEX_DIR overrides the repository folder (where Git LFS files are pointers)"""
    d = os.environ.get('BVP_TEX_DIR')
    return os.path.join(d, vid + '.png') if d else os.path.join(TEX_DIR, vid + '.png')


def r5(v):
    return [round(float(x), 5) + 0.0 for x in v]


def unit(v):
    v = np.array(v, float); return v / np.linalg.norm(v)


def frame(centre, normal, up):
    """(c, n, u, r): r = the right-hand direction seen from in front of the face (looking along -n)"""
    n = unit(normal)
    u = np.array(up, float); u = u - n * (u @ n); u /= np.linalg.norm(u)
    return np.array(centre, float), n, u, np.cross(u, n)


def half_along(A, half, d):
    return sum(abs(float(a @ d)) * h for a, h in zip(A, half))


# ---------------------------------------------------------------- colours from the existing texture

class Swatches:
    """Uniform texel blocks of the aircraft's texture, and the one nearest a wanted colour. uv(rgb) -> normalized uv
    of the centre of a block whose 5x5 texels all have that colour (within a few levels), alpha opaque."""

    def __init__(self, path):
        im = np.asarray(Image.open(path).convert('RGBA')).astype(np.int16)
        self.H, self.W = im.shape[:2]
        k = 5
        cand = []
        step = max(2, self.W // 512)
        for y in range(0, self.H - k, step):
            row = im[y:y + k]
            for x in range(0, self.W - k, step):
                b = row[:, x:x + k]
                if b[..., 3].min() < 250:
                    continue
                lo = b[..., :3].reshape(-1, 3).min(0); hi = b[..., :3].reshape(-1, 3).max(0)
                if (hi - lo).max() <= 3:
                    cand.append((x + k // 2, y + k // 2, *((lo + hi) / 2)))
        self.cand = np.array(cand, float) if cand else np.zeros((0, 5))
        self.cache = {}
        self.worst = 0.0

    def uv(self, rgb):
        key = tuple(int(c) for c in rgb)
        if key not in self.cache:
            if not len(self.cand):
                raise SystemExit('texture has no uniform texel blocks')
            c = self.cand[:, 2:5]
            want = np.array(key, float)
            # perceptual-ish: weight green most, and keep the brightness close
            d = ((c - want) ** 2 * [0.3, 0.59, 0.11]).sum(1) + 0.5 * (c.mean(1) - want.mean()) ** 2
            i = int(np.argmin(d))
            x, y = self.cand[i, :2]
            self.worst = max(self.worst, float(np.abs(c[i] - want).max()))
            self.cache[key] = ([(x + 0.5) / self.W, 1.0 - (y + 0.5) / self.H], tuple(int(v) for v in c[i]))
        return self.cache[key][0]

    def got(self, rgb):
        self.uv(rgb)
        return self.cache[tuple(int(c) for c in rgb)][1]


# ---------------------------------------------------------------- mesh

class Mesh:
    """Faces in vehicle-local blocks, each with a uv per vertex. flat(): one colour (a Swatches uv)."""

    def __init__(self):
        self.faces = []                      # (points k x 3, uvs k x 2, normal 3)

    def face(self, pts, uvs, toward=None, normal=None):
        P = np.array(pts, float)
        n = np.zeros(3)
        for i in range(len(P)):              # Newell normal
            a, b = P[i], P[(i + 1) % len(P)]
            n += np.array([(a[1] - b[1]) * (a[2] + b[2]), (a[2] - b[2]) * (a[0] + b[0]), (a[0] - b[0]) * (a[1] + b[1])])
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            return
        n /= ln
        want = None
        if normal is not None:
            want = np.array(normal, float)
        elif toward is not None:
            want = np.array(toward, float) - P.mean(0)
        uvs = [list(u) for u in uvs]
        if want is not None and n @ want < 0:
            P = P[::-1]; uvs = uvs[::-1]; n = -n
        if len(P) > 4:                       # fan into triangles
            for k in range(1, len(P) - 1):
                self.faces.append((P[[0, k, k + 1]], [uvs[0], uvs[k], uvs[k + 1]], n))
        else:
            self.faces.append((P, uvs, n))

    def flat(self, pts, uv, toward=None, normal=None):
        self.face(pts, [uv] * len(pts), toward=toward, normal=normal)

    def box(self, centre, axes, half, uv, faces=None):
        """oriented box: axes = 3 unit vectors, half sizes; faces = optional {(axis, sign): uv} overrides"""
        c = np.array(centre, float); A = [np.array(a, float) for a in axes]
        for k in range(3):
            for s in (-1, 1):
                i, j = [m for m in range(3) if m != k]
                fc = c + A[k] * half[k] * s
                pts = [fc - A[i] * half[i] - A[j] * half[j], fc + A[i] * half[i] - A[j] * half[j],
                       fc + A[i] * half[i] + A[j] * half[j], fc - A[i] * half[i] + A[j] * half[j]]
                self.flat(pts, (faces or {}).get((k, s), uv), normal=A[k] * s)

    def ring(self, centre, normal, up, r0, r1, proud, uv, seg=20, back=0.0):
        """flat annulus (bezel) standing [proud] in front of the plane, with its walls back to the plane"""
        c, n, u, rr = frame(centre, normal, up)
        for k in range(seg):
            a0 = 2 * math.pi * k / seg; a1 = 2 * math.pi * (k + 1) / seg
            d0 = rr * math.cos(a0) + u * math.sin(a0); d1 = rr * math.cos(a1) + u * math.sin(a1)
            f = c + n * proud
            self.flat([f + d0 * r0, f + d0 * r1, f + d1 * r1, f + d1 * r0], uv, normal=n)
            self.flat([f + d0 * r1, c + d0 * r1 - n * back, c + d1 * r1 - n * back, f + d1 * r1], uv,
                      normal=(d0 + d1) / 2)
            self.flat([f + d0 * r0, c + d0 * r0, c + d1 * r0, f + d1 * r0], uv, normal=-(d0 + d1) / 2)

    def disc(self, centre, normal, up, radius, uv, seg=16):
        c, n, u, rr = frame(centre, normal, up)
        pts = [c + (rr * math.cos(2 * math.pi * k / seg) + u * math.sin(2 * math.pi * k / seg)) * radius
               for k in range(seg)]
        self.flat(pts, uv, normal=n)

    def frame_rect(self, centre, normal, up, w, h, t, proud, uv):
        """rectangular bezel (four bars) around a w x h opening"""
        c, n, u, rr = frame(centre, normal, up)
        for (cx, cy, hw, hh) in ((0, h / 2 + t / 2, w / 2 + t, t / 2), (0, -h / 2 - t / 2, w / 2 + t, t / 2),
                                 (w / 2 + t / 2, 0, t / 2, h / 2), (-w / 2 - t / 2, 0, t / 2, h / 2)):
            self.box(c + rr * cx + u * cy + n * proud / 2, (rr, u, n), (hw, hh, proud / 2), uv)

    def extend(self, other):
        self.faces += other.faces

    def poly_mesh(self):
        P, N, U, polys = [], [], [], []
        for pts, uvs, n in self.faces:
            px = [[float(p[0]) * 16, float(p[1]) * 16, -float(p[2]) * 16] for p in pts]
            npx = np.array([n[0], n[1], -n[2]])
            a, b, c = (np.array(q) for q in px[:3])
            # The mesh loader mirrors X and takes (p1 - p0) x (p2 - p0) of the mirrored points as the face normal, so
            # in file pixels a face winds clockwise seen from its front (the hull's own faces do the same); the stored
            # normal follows the file-pixel winding, as the hull's does.
            if np.cross(b - a, c - a) @ npx > 0:
                px = px[::-1]; uvs = uvs[::-1]
            ni = len(N); N.append([round(float(x), 5) + 0.0 for x in -npx])
            poly = []
            for p, uv in zip(px, uvs):
                poly.append([len(P), ni, len(U)])
                P.append([round(x, 4) + 0.0 for x in p]); U.append([round(uv[0], 6), round(uv[1], 6)])
            polys.append(poly)
        return {'normalized_uvs': True, 'positions': P, 'normals': N, 'uvs': U, 'polys': polys}


def loader_volume(pm):
    """six times the volume enclosed with the loader's face normals: positive when faces point outward"""
    pts = [(-p[0], p[1], p[2]) for p in pm['positions']]
    total = 0.0
    for poly in pm['polys']:
        idx = [v[0] for v in poly]
        a = pts[idx[0]]
        for k in range(1, len(idx) - 1):
            b, c = pts[idx[k]], pts[idx[k + 1]]
            total += (a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0])
                      + a[2] * (b[0] * c[1] - b[1] * c[0]))
    return total


def point_triangle_distance(p, t):
    """distance from point p to triangle t (3 x 3)"""
    a, b, c = t
    ab, ac, ap = b - a, c - a, p - a
    d1, d2 = ab @ ap, ac @ ap
    if d1 <= 0 and d2 <= 0:
        return float(np.linalg.norm(ap))
    bp = p - b; d3, d4 = ab @ bp, ac @ bp
    if d3 >= 0 and d4 <= d3:
        return float(np.linalg.norm(bp))
    vc = d1 * d4 - d3 * d2
    if vc <= 0 and d1 >= 0 and d3 <= 0:
        return float(np.linalg.norm(p - (a + ab * d1 / (d1 - d3))))
    cp = p - c; d5, d6 = ab @ cp, ac @ cp
    if d6 >= 0 and d5 <= d6:
        return float(np.linalg.norm(cp))
    vb = d5 * d2 - d1 * d6
    if vb <= 0 and d2 >= 0 and d6 <= 0:
        return float(np.linalg.norm(p - (a + ac * d2 / (d2 - d6))))
    va = d3 * d6 - d5 * d4
    if va <= 0 and d4 - d3 >= 0 and d5 - d6 >= 0:
        return float(np.linalg.norm(p - (b + (c - b) * (d4 - d3) / ((d4 - d3) + (d5 - d6)))))
    den = 1.0 / (va + vb + vc)
    return float(np.linalg.norm(p - (a + ab * vb * den + ac * vc * den)))


# ---------------------------------------------------------------- reading the aircraft

class Aircraft:
    def __init__(self, vid):
        self.vid = vid
        self.geo_path = os.path.join(GEO_DIR, vid + '.geo.json')
        self.asset_path = os.path.join(ASSET_DIR, vid + '.json')
        self.geo = json.load(open(self.geo_path))
        self.asset = json.load(open(self.asset_path))
        self.data = json.load(open(os.path.join(DATA_DIR, vid + '.json')))
        self.bones = self.geo['minecraft:geometry'][0]['bones']
        cg = self.asset.get('CanopyGlass')
        self.glass = np.array(cg['Triangles'], float).reshape(-1, 3, 3) if cg else np.zeros((0, 3, 3))

    def drop_sight_glass(self, E, s=1.0):
        """removes the glass of an old head-up display or gunsight in front of the eye (plates facing the pilot,
        near the centre line, below eye level) from what the fitting sees; returns the triangles removed"""
        if not len(self.glass):
            return 0
        c = self.glass.mean(1)
        n = np.cross(self.glass[:, 1] - self.glass[:, 0], self.glass[:, 2] - self.glass[:, 0])
        n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
        span = np.abs(self.glass[:, :, 0] - E[0]).max(1)
        sight = ((span < 0.13 * s) & (np.abs(n[:, 2]) > 0.75) & (c[:, 1] < E[1] + 0.06) & (c[:, 1] > E[1] - 0.4 * s) &
                 (c[:, 2] > E[2] + 0.2 * s) & (c[:, 2] < E[2] + 1.1 * s))
        self.glass = self.glass[~sight]
        return int(sight.sum())

    def drop_glass_inside(self, t):
        """removes glass lying wholly inside the cockpit below eye level, up to just ahead of the panel (old display
        covers and gunsight glass)"""
        if not len(self.glass):
            return 0
        g = self.glass
        inside = (np.all(np.abs(g[:, :, 0] - t.E[0]) < t.Wg, axis=1) & np.all(g[:, :, 1] < t.E[1] - 0.02, axis=1) &
                  np.all(g[:, :, 2] > t.back_z, axis=1) & np.all(g[:, :, 2] < t.Z + 0.25 * t.s, axis=1))
        self.glass = g[~inside]
        return int(inside.sum())

    def skin_x(self, y, z, x0):
        """distance from (x0, y, z) sideways to the outside of the aircraft, the outermost face along that line,
        either way: (to +x, to -x), inf where there is none (the new cockpit bones are not counted)"""
        if not hasattr(self, '_skin'):
            T = []
            for b in self.bones:
                pm = b.get('poly_mesh')
                if not pm or not pm.get('polys') or b['name'].endswith('__cockpit') or not is_interior_bone(b['name']):
                    continue
                P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
                for poly in pm['polys']:
                    V = P[[v[0] for v in poly]]
                    for k in range(1, len(V) - 1):
                        T.append(V[[0, k, k + 1]])
            self._skin = np.array(T).reshape(-1, 3, 3)
        T = self._skin
        a, b, c = T[:, 0, 1:], T[:, 1, 1:], T[:, 2, 1:]
        q = np.array([y, z])
        cr = lambda u, v: u[..., 0] * v[..., 1] - u[..., 1] * v[..., 0]
        d1, d2, d3 = cr(b - a, q - a), cr(c - b, q - b), cr(a - c, q - c)
        area = cr(b - a, c - a)
        m = (((d1 >= 0) & (d2 >= 0) & (d3 >= 0)) | ((d1 <= 0) & (d2 <= 0) & (d3 <= 0))) & (np.abs(area) > 1e-9)
        w0, w2 = d2[m] / area[m], d3[m] / area[m]
        x = w0 * T[m, 0, 0] + (1 - w0 - w2) * T[m, 1, 0] + w2 * T[m, 2, 0] - x0
        return [float(x.max()) if (x > 0.05).any() else np.inf, float(-x.min()) if (x < -0.05).any() else np.inf]

    def escapes(self, p, d):
        """True when the ray from p along d meets nothing of the aircraft (canopy glass included, the new cockpit
        bones not)"""
        if not hasattr(self, '_all'):
            self.skin_x(0.0, 0.0, 0.0)
            self._all = np.concatenate([self._skin, self.glass]) if len(self.glass) else self._skin
        T = self._all
        e1, e2 = T[:, 1] - T[:, 0], T[:, 2] - T[:, 0]
        h = np.cross(d, e2); a = (e1 * h).sum(1)
        ok = np.abs(a) > 1e-12
        f = np.where(ok, 1.0 / np.where(ok, a, 1.0), 0.0)
        sv = p - T[:, 0]; u = f * (sv * h).sum(1)
        q = np.cross(sv, e1); v = f * (q @ d); t = f * (q * e2).sum(1)
        return not (ok & (u >= 0) & (u <= 1) & (v >= 0) & (u + v <= 1) & (t > 0.004)).any()

    def hugs_glass(self, V, gap=0.07):
        """True when every point lies within [gap] of the canopy glass (a frame member along the glass edge)"""
        if not len(self.glass):
            return False
        g = self.glass
        lo, hi = g.min(1) - gap, g.max(1) + gap
        for p in V:
            m = np.all((lo <= p) & (p <= hi), axis=1)
            if not m.any() or min(point_triangle_distance(p, t) for t in g[m]) > gap:
                return False
        return True

    def touches_glass(self, V, gap=0.04):
        """True when some point lies within [gap] of the canopy glass"""
        if not len(self.glass):
            return False
        g = self.glass
        lo, hi = g.min(1) - gap, g.max(1) + gap
        for p in V:
            m = np.all((lo <= p) & (p <= hi), axis=1)
            if m.any() and min(point_triangle_distance(p, t) for t in g[m]) <= gap:
                return True
        return False

    def eye(self, seat):
        s = self.data['Seats'][seat]
        return np.array(self.data['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)

    def bone(self, name):
        return next((b for b in self.bones if b['name'] == name), None)

    def instruments(self, seat):
        """centres of the instruments placed for this seat before (on the old panel)"""
        out = [g['Center'] for g in (self.asset.get('CockpitGauges') or {}).get('Gauges', []) if g.get('Seat', 0) == seat]
        out += [d['Center'] for d in (self.asset.get('FlightDisplays') or {}).get('Displays', [])
                if d.get('Seat', 0) == seat]
        return np.array(out, float) if out else None

    def glass_above(self, x, z, y_from, dz=0.06, dx=0.06):
        """lowest canopy glass point above y_from near (x, z), or None"""
        G = self.glass.reshape(-1, 3)
        if not len(G):
            return None
        lo, hi = self.glass.min(1), self.glass.max(1)
        sel = self.glass[(lo[:, 0] <= x + 1e-6) & (hi[:, 0] >= x - 1e-6) & (lo[:, 2] <= z + 1e-6) &
                         (hi[:, 2] >= z - 1e-6)]
        best = None
        for t in sel:                        # vertical ray through (x, z) against each triangle
            a, b, c = t
            d = (b[0] - a[0]) * (c[2] - a[2]) - (c[0] - a[0]) * (b[2] - a[2])
            if abs(d) < 1e-12:
                continue
            u = ((x - a[0]) * (c[2] - a[2]) - (c[0] - a[0]) * (z - a[2])) / d
            v = ((b[0] - a[0]) * (z - a[2]) - (x - a[0]) * (b[2] - a[2])) / d
            if u < -1e-6 or v < -1e-6 or u + v > 1 + 1e-6:
                continue
            y = a[1] + u * (b[1] - a[1]) + v * (c[1] - a[1])
            if y > y_from and (best is None or y < best):
                best = y
        return best


# ---------------------------------------------------------------- the fitted tub

class Tub:
    """Cockpit dimensions for one crew station, measured from the eye and the canopy glass."""

    def __init__(self, ac, seat=0, **over):
        E = ac.eye(seat)
        self.seat, self.E = seat, E
        G = ac.glass.reshape(-1, 3)
        side = G[(np.abs(G[:, 2] - E[2]) < 0.25) & (np.abs(G[:, 0] - E[0]) > 0.12)]
        sill = side[:, 1].min() if len(side) else E[1] - 0.3
        W = float(np.median(np.abs(side[side[:, 1] < sill + 0.08][:, 0] - E[0]))) if len(side) else 0.42
        self.W = over.get('W', float(np.clip(W, 0.30, 0.56)))
        self.s = s = over.get('s', float(np.clip(self.W / 0.43, 0.75, 1.25)))
        self.sill = over.get('sill', float(np.clip(sill, E[1] - 0.50 * s, E[1] - 0.18 * s)))
        ahead = G[(G[:, 2] > E[2] + 0.35) & (np.abs(G[:, 0] - E[0]) < 0.6 * self.W)]
        wy = ahead[:, 1].min() if len(ahead) else E[1] - 0.3
        # the windscreen base: where the lowest glass ahead meets the deck, at its furthest forward
        wz = ahead[ahead[:, 1] < wy + 0.03][:, 2].max() if len(ahead) else E[2] + 1.2
        # the panel stands about 0.64 m ahead of the eye (scaled), and well behind the windscreen base
        self.panel_dz = over.get('panel_dz', float(min(0.64 * s, max(0.5 * s, wz - E[2] - 0.35 * s))))
        self.Z = E[2] + self.panel_dz                       # panel reference plane (centre facet at mid height)
        self.top_c = over.get('top_c', E[1] - 0.165 * s)
        # keep the panel top under the windscreen: glass low over the panel pushes it down
        g = ac.glass_above(E[0], self.Z + 0.05, self.top_c - 0.2)
        if g is not None and g < self.top_c + 0.10 * s:
            self.top_c = g - 0.10 * s
        self.top_w = over.get('top_w', min(self.sill + 0.01, self.top_c - 0.06 * s))
        self.Wg = self.W                              # the width at the canopy: the old interior is cleared to it
        if 'W' not in over:                           # and inside the fuselage beside the pilot, which may be
            sk = [min(ac.skin_x(y, z, E[0]))            # narrower than the canopy (a sloping upper fuselage)
                  for y in np.linspace(E[1] - 0.60 * s, self.sill - 0.05 * s, 4)
                  for z in np.linspace(E[2] - 0.40 * s, self.Z - 0.05, 5)]
            self.W = float(min(self.W, max(0.26, np.percentile(np.minimum(sk, 9.0), 20) - 0.015)))
        self.bot_c = E[1] - 0.70 * s
        self.bot_w = E[1] - 0.60 * s
        self.console_y = self.bot_w
        self.floor_y = E[1] - 1.10 * s
        if 'W' not in over:                           # the panel wings' outer top corners inside the skin too
            W0, wing = self.W, math.radians(over.get('wing', 28))
            while self.W > 0.8 * W0 and not all(
                    self.fits(ac, E[0] + sg * self.W, self.top_w - 0.03, self.Z - 0.5 * self.W * math.tan(wing))
                    for sg in (1, -1)):
                self.W -= 0.01
        self.lean = math.radians(over.get('lean', 12))
        self.hinge = over.get('hinge', 0.50 * self.W)
        self.wing = math.radians(over.get('wing', 28))
        self.wing_len = (self.W - self.hinge) / math.cos(self.wing)
        self.ws_y = over.get('ws_y', float(np.clip(wy, self.top_c - 0.35 * s, self.top_c + 0.03)))
        self.ws_z = over.get('ws_z', float(np.clip(wz, self.Z + 0.30 * s, self.Z + 1.10 * s)))
        self.back_z = E[2] - 0.58 * s                       # rear bulkhead
        self.seat_z = E[2]
        self.front_z = self.Z + 0.17 * s                    # footwell front

    def fits(self, ac, x, y, z):
        """True when the point lies inside the fuselage skin (sideways from the centreline)"""
        d = ac.skin_x(y, z, self.E[0])[0 if x > self.E[0] else 1]
        return abs(x - self.E[0]) <= d + 0.005

    def describe(self):
        E = self.E
        return (f'W {self.W:.2f} s {self.s:.2f} sill {self.sill - E[1]:+.2f} panel dz {self.panel_dz:.2f} '
                f'top {self.top_c - E[1]:+.2f} ws ({self.ws_y - E[1]:+.2f}, {self.ws_z - E[2]:+.2f})')


class Facet:
    def __init__(self, tub, side):
        t = tub
        self.t, self.side = t, side             # 0 centre, +1 right wing, -1 left wing
        self.Rc = np.array([-1.0, 0, 0])        # pilot's right
        self.Uc = np.array([0, math.cos(t.lean), math.sin(t.lean)])
        self.Nc = np.array([0, math.sin(t.lean), -math.cos(t.lean)])
        self.P0 = np.array([t.E[0], (t.top_c + t.bot_c) / 2, t.Z])
        if side == 0:
            self.R, self.N = self.Rc, self.Nc
        else:
            self.R = self.Rc * side * math.cos(t.wing) + self.Nc * math.sin(t.wing)
            self.N = self.Nc * math.cos(t.wing) - self.Rc * side * math.sin(t.wing)
        self.U = self.Uc

    def at(self, a, y, lift=0.0):
        """panel point: a = metres right of the centre line (centre) or along the wing from the hinge (wings)"""
        t = self.t
        v = (y - self.P0[1]) / math.cos(t.lean)
        if self.side == 0:
            p = self.P0 + self.Rc * a + self.Uc * v
        else:
            p = self.P0 + self.Rc * (self.side * t.hinge) + self.Uc * v + self.R * a
        return p + self.N * lift

    def top(self, a):
        t = self.t
        return t.top_c if self.side == 0 else t.top_c + (t.top_w - t.top_c) * a / t.wing_len

    def bottom(self):
        return self.t.bot_c if self.side == 0 else self.t.bot_w

    def extent(self):
        return (-self.t.hinge, self.t.hinge) if self.side == 0 else (0.0, self.t.wing_len)


# ---------------------------------------------------------------- palettes and eras

PALETTES = {
    # panel, panel shade, walls/consoles, coaming, floor, seat frame, cushion
    'nato':    dict(panel=(30, 32, 34), shade=(22, 23, 24), wall=(78, 82, 84), console=(40, 42, 44),
                    coaming=(20, 20, 21), floor=(52, 54, 55), frame=(60, 64, 60), cushion=(64, 60, 46)),
    'soviet':  dict(panel=(74, 150, 154), shade=(52, 112, 116), wall=(74, 150, 154), console=(60, 128, 132),
                    coaming=(24, 25, 26), floor=(46, 50, 50), frame=(62, 68, 64), cushion=(58, 62, 52)),
    'soviet_grey': dict(panel=(40, 42, 44), shade=(30, 31, 32), wall=(118, 124, 126), console=(90, 96, 98),
                        coaming=(24, 25, 26), floor=(56, 60, 60), frame=(62, 68, 64), cushion=(58, 62, 52)),
    'modern':  dict(panel=(46, 48, 50), shade=(34, 35, 36), wall=(86, 90, 92), console=(52, 54, 56),
                    coaming=(20, 20, 21), floor=(52, 54, 55), frame=(58, 60, 58), cushion=(46, 48, 44)),
    'us_ww2':  dict(panel=(24, 25, 26), shade=(18, 18, 19), wall=(98, 108, 66), console=(86, 96, 60),
                    coaming=(30, 30, 28), floor=(78, 70, 54), frame=(98, 108, 66), cushion=(92, 74, 52)),
    'rlm66':   dict(panel=(44, 46, 48), shade=(34, 35, 36), wall=(54, 57, 59), console=(48, 50, 52),
                    coaming=(30, 31, 32), floor=(60, 58, 52), frame=(54, 57, 59), cushion=(92, 74, 52)),
    'ussr_ww2': dict(panel=(30, 31, 32), shade=(22, 23, 24), wall=(112, 120, 124), console=(96, 104, 108),
                     coaming=(30, 31, 32), floor=(78, 70, 54), frame=(112, 120, 124), cushion=(92, 74, 52)),
    'raf':     dict(panel=(26, 26, 26), shade=(18, 18, 19), wall=(126, 134, 108), console=(110, 118, 94),
                    coaming=(26, 26, 26), floor=(78, 70, 54), frame=(126, 134, 108), cushion=(92, 74, 52)),
}
ACCENT = dict(black=(24, 25, 26), dgrey=(44, 46, 48), mgrey=(88, 92, 94), lgrey=(150, 154, 156),
              metal=(128, 132, 134), yellow=(226, 186, 30), red=(196, 36, 30), bezel=(30, 31, 32),
              white=(220, 222, 214), green=(60, 150, 70), amber=(214, 140, 30))

# Instrument sets, as rows from the top of each facet. Items: (kind, diameter) for a dial, ('D:<PAGE>', width) for
# a display of that page (square screen; its housing is 1.4 times wider), ('UFCP', width) for the up-front control
# block and ('A:<set>', width, height) for an annunciator panel of LAMP_SETS[set]. Sizes are metres at scale 1 (real
# instruments: 3 inch dials about .080, 2 inch .057; displays are drawn larger than life so they read in game). 'fill' rows are added, in order, while the facet has room.
# Dials the game knows the value of are live (GaugePainter.Kind.live): the basic six, Mach, AoA, G, RWR, fuel, radar
# altitude, clock, turn and slip, engine RPM and EGT, and the rounds counter.
ERAS = {
    'WWII_STEAM': dict(
        centre=[[('A:ww2', .125, .050)],
                [('AIRSPEED', .076), ('ATTITUDE', .082), ('ALTIMETER', .076)],
                [('TURN_SLIP', .076), ('HEADING', .082), ('VVI', .076)],
                [('ENGINE_RPM', .070), ('THROTTLE', .070), ('FILLER_TEMP', .057), ('FILLER_OIL', .057)]],
        centre_fill=[[('FUEL_QTY', .057), ('FILLER_VOLTS', .050), ('CLOCK', .057), ('FILLER_OXYGEN', .050)]],
        left=[[('G_METER', .057), ('AMMO', .057)]],
        left_fill=[[('FILLER_HYDRAULIC', .050), ('FILLER_FUEL', .050)]],
        right=[[('EGT', .064), ('FILLER_VOLTS', .050)], [('CLOCK', .057), ('FILLER_OXYGEN', .050)]],
        right_fill=[[('FILLER_HYDRAULIC', .050)]],
        hud=False, displays='CRT', seat='bucket'),
    'COLD_WAR_STEAM': dict(
        centre=[[('A:cw', .215, .044)],
                [('AIRSPEED', .076), ('ATTITUDE', .082), ('ALTIMETER', .076)],
                [('MACH', .064), ('HEADING', .082), ('VVI', .064)],
                [('TURN_SLIP', .057), ('AOA', .057), ('G_METER', .057), ('CLOCK', .050)]],
        centre_fill=[[('FILLER_OXYGEN', .044), ('FILLER_VOLTS', .044), ('FILLER_HYDRAULIC', .044)]],
        left=[[('ENGINE_RPM', .064), ('EGT', .064)], [('FUEL_QTY', .064), ('FILLER_OIL', .050)],
              [('AMMO', .057), ('FILLER_HYDRAULIC', .050)]],
        left_fill=[[('THROTTLE', .050), ('FILLER_TEMP', .050)]],
        right=[[('RADAR_ALT', .064), ('FILLER_OXYGEN', .050)], [('FILLER_VOLTS', .050), ('FILLER_TEMP', .050)]],
        right_fill=[[('FILLER_FUEL', .050), ('FILLER_CLOCK', .044)]],
        hud=False, displays='CRT', seat='ejection'),
    'COLD_WAR_RADAR': dict(
        centre=[[('A:cw', .215, .044)],
                [('AIRSPEED', .076), ('ATTITUDE', .082), ('ALTIMETER', .076)],
                [('MACH', .064), ('HEADING', .082), ('VVI', .064)],
                [('AOA', .057), ('G_METER', .057), ('RADAR_ALT', .057), ('CLOCK', .050)]],
        centre_fill=[[('TURN_SLIP', .044), ('FILLER_OXYGEN', .044), ('FILLER_VOLTS', .044)]],
        left=[[('ENGINE_RPM', .064), ('EGT', .064)], [('FUEL_QTY', .064), ('FILLER_OIL', .050)],
              [('AMMO', .057), ('FILLER_HYDRAULIC', .050)]],
        left_fill=[[('THROTTLE', .050), ('FILLER_TEMP', .050)]],
        right=[[('D:RADAR', .135)], [('RWR', .064), ('TURN_SLIP', .050)], [('FILLER_OXYGEN', .044), ('FILLER_VOLTS', .044)]],
        right_fill=[[('FILLER_TEMP', .044), ('FILLER_CLOCK', .044)]],
        hud=False, displays='CRT', seat='ejection'),
    'GEN4_CRT': dict(
        centre=[[('UFCP', .170)], [('D:RADAR', .140), ('D:PFD', .140)],
                [('AIRSPEED', .057), ('ATTITUDE', .064), ('ALTIMETER', .057), ('VVI', .050)],
                [('ENGINE_RPM', .050), ('EGT', .050), ('FUEL_QTY', .050), ('CLOCK', .044)]],
        centre_fill=[[('FILLER_HYDRAULIC', .044), ('FILLER_OIL', .044), ('FILLER_OXYGEN', .044)]],
        left=[[('D:STORES', .110)]],
        left_fill=[[('G_METER', .050), ('AOA', .050)], [('AMMO', .044), ('FILLER_VOLTS', .044)]],
        right=[[('RWR', .064), ('RADAR_ALT', .050)]],
        right_fill=[[('AMMO', .044), ('FILLER_HYDRAULIC', .044)], [('TURN_SLIP', .044), ('FILLER_TEMP', .044)]],
        eyebrows=(('eyebrow_l', .120, .050), ('eyebrow_r', .120, .050)),
        hud=True, displays='CRT', seat='ejection'),
    'MODERN_GLASS': dict(
        centre=[[('UFCP', .170)], [('D:STORES', .150), ('D:PFD', .150)],
                [('AIRSPEED', .050), ('ATTITUDE', .057), ('ALTIMETER', .050), ('FUEL_QTY', .050)]],
        centre_fill=[[('ENGINE_RPM', .044), ('EGT', .044), ('FILLER_HYDRAULIC', .044), ('CLOCK', .044)]],
        left=[[('D:RADAR', .115)]],
        left_fill=[[('ENGINE_RPM', .044), ('EGT', .044)], [('G_METER', .044), ('AOA', .044)]],
        right=[[('RWR', .064), ('RADAR_ALT', .050)]],
        right_fill=[[('AMMO', .044), ('CLOCK', .044)], [('FILLER_OXYGEN', .040), ('FILLER_VOLTS', .040)]],
        eyebrows=(('eyebrow_l', .120, .050), ('eyebrow_r', .120, .050)),
        hud=True, displays='LCD', seat='ejection'),
}

# Annunciator lamp sets: (caption, condition, colour) left to right, then down; "|" breaks a caption into two lines.
# Conditions are Annunciator.Condition in the game; NONE legends never light. Soviet palettes get the Russian captions.
LAMP_SETS = {
    'ww2': dict(columns=3, lamps=[
        ('GEAR|DOWN', 'GEAR_DOWN', 'green'), ('GEAR|UNSAFE', 'GEAR_TRANSIT', 'red'), ('FUEL|LOW', 'LOW_FUEL', 'amber'),
        ('OIL|PRESS', 'NONE', 'amber'), ('ENGINE|FIRE', 'FIRE', 'red'), ('GEN', 'NONE', 'amber')]),
    'cw': dict(columns=6, lamps=[
        ('MASTER|CAUTION', 'MASTER_CAUTION', 'amber'), ('GEAR|DOWN', 'GEAR_DOWN', 'green'),
        ('GEAR|UNSAFE', 'GEAR_TRANSIT', 'red'), ('FUEL|LOW', 'LOW_FUEL', 'amber'), ('FIRE', 'FIRE', 'red'),
        ('ENG|OUT', 'ENGINE_OUT', 'red'),
        ('HYD|PRESS', 'NONE', 'amber'), ('GEN', 'NONE', 'amber'), ('STALL', 'STALL', 'red'), ('OVER G', 'OVER_G', 'amber'),
        ('RWR|LOCK', 'RWR_LOCK', 'amber'), ('LAUNCH', 'MISSILE_LAUNCH', 'red')]),
    'eyebrow_l': dict(columns=4, lamps=[
        ('MASTER|CAUTION', 'MASTER_CAUTION', 'amber'), ('FIRE', 'FIRE', 'red'), ('STALL', 'STALL', 'red'),
        ('LOW|ALT', 'LOW_ALT', 'red'),
        ('ENG|OUT', 'ENGINE_OUT', 'red'), ('FUEL|LOW', 'LOW_FUEL', 'amber'), ('HYD|PRESS', 'NONE', 'amber'),
        ('DAMAGE', 'DAMAGE', 'amber')]),
    'eyebrow_r': dict(columns=4, lamps=[
        ('LAUNCH', 'MISSILE_LAUNCH', 'red'), ('RWR|LOCK', 'RWR_LOCK', 'amber'), ('GEAR|DOWN', 'GEAR_DOWN', 'green'),
        ('A/B', 'AFTERBURNER', 'green'),
        ('OVER G', 'OVER_G', 'amber'), ('OVER|SPEED', 'OVERSPEED', 'amber'), ('SPD|BRK', 'AIRBRAKE', 'green'),
        ('AMMO|LOW', 'AMMO_LOW', 'amber')]),
}
RUSSIAN = {
    'MASTER|CAUTION': 'ВНИМАНИЕ', 'GEAR|DOWN': 'ШАССИ|ВЫП', 'GEAR|UNSAFE': 'ШАССИ|ПРОМЕЖ', 'FUEL|LOW': 'ОСТАТОК|ТОПЛ',
    'OIL|PRESS': 'ДАВЛ|МАСЛА', 'ENGINE|FIRE': 'ПОЖАР|ДВИГ', 'GEN': 'ГЕНЕР', 'FIRE': 'ПОЖАР', 'ENG|OUT': 'ОТКАЗ|ДВИГ',
    'HYD|PRESS': 'ГИДРО', 'STALL': 'УГОЛ|ОПАСН', 'OVER G': 'ПЕРЕГР', 'RWR|LOCK': 'ОБЛУЧ', 'LAUNCH': 'ПУСК',
    'LOW|ALT': 'ОПАСН|ВЫСОТА', 'DAMAGE': 'ОТКАЗ', 'A/B': 'ФОРСАЖ', 'OVER|SPEED': 'ПРЕДЕЛ|СКОР', 'SPD|BRK': 'ТОРМ|ЩИТКИ',
    'AMMO|LOW': 'МАЛО|БОЕЗАП',
}


def lamps_of(name, pal_name):
    spec = LAMP_SETS[name]
    cyr = pal_name.startswith('soviet') or pal_name == 'ussr_ww2'
    return spec['columns'], [{'Text': RUSSIAN.get(t, t) if cyr else t, 'Condition': c, 'Colour': k}
                             for t, c, k in spec['lamps']]


RIM = 0.16                     # game gauge bezel width (CockpitGauges.RIM)
DISPLAY_BEZEL = 0.2            # game display housing border, fraction of the screen width (FlightDisplays.BEZEL)
GAUGE_LIFT = 0.004             # game dials stand this far proud of the panel face
DISPLAY_LIFT = 0.003
GAP = 0.010


def footprint(item):
    """(w, h) a row item occupies on the panel, bezels included, at scale 1"""
    kind = item[0]
    if kind.startswith('A:'):
        return item[1], item[2]
    if kind.startswith('D:'):
        return item[1] * (1 + 2 * DISPLAY_BEZEL), item[1] * (1 + 2 * DISPLAY_BEZEL)
    if kind == 'UFCP':
        return item[1], item[1] * 0.42
    return item[1] * (1 + RIM), item[1] * (1 + RIM)


def layout(tub, era, facets, hud_body=None):
    """[(facet, a, y, kind, size)] instruments packed in rows on each facet from the top; rows are shrunk a little to
    fit, dropped from the bottom when they still do not, and 'fill' rows added while room remains. The spare height
    under the rows is returned per facet as [(facet, y_from, y_to)] for switch panels."""
    out, spare = [], []
    s = tub.s
    for key, f in (('centre', facets[0]), ('left', facets[-1]), ('right', facets[1])):
        base = [list(r) for r in era[key]]
        fill = [list(r) for r in era.get(key + '_fill', [])]
        a0, a1 = f.extent()
        width = (a1 - a0) - 0.016 * s
        top = f.top(0) if f.side == 0 else min(f.top(a0), f.top(a1))
        y_top = top - 0.012 * s
        if f.side == 0 and hud_body is not None:
            y_top = min(y_top, hud_body)            # the HUD's control strip takes the top of the centre facet
        height = (y_top - f.bottom()) / math.cos(tub.lean) - 0.012 * s

        def measure(rows, scale):
            fp = [[footprint(it) for it in row] for row in rows]
            hs = [max(h for _, h in r) * s * scale for r in fp]
            ws = [sum(w for w, _ in r) * s * scale + GAP * s * (len(r) - 1) for r in fp]
            return hs, ws

        def fits(rows, scale):
            hs, ws = measure(rows, scale)
            return sum(hs) + GAP * s * (len(rows) - 1) <= height and max(ws) <= width

        rows, scale = base, 1.0
        for scale in np.linspace(1.0, 0.72, 15):
            if fits(rows, scale):
                break
        else:
            while len(rows) > 1 and not fits(rows, scale):
                rows = rows[:-1]
        for extra in fill:
            if fits(rows + [extra], scale):
                rows = rows + [extra]
        # too-wide rows (a long annunciator strip on a narrow panel) are squeezed to the width
        hs, ws = measure(rows, scale)
        v = 0.0
        for row, h, w in zip(rows, hs, ws):
            sq = min(1.0, width / w) if w > width else 1.0
            yc = y_top - (v + h / 2) * math.cos(tub.lean)
            v += h + GAP * s
            fp = [footprint(it) for it in row]
            span = sum(fw for fw, _ in fp) * s * scale * sq + GAP * s * (len(row) - 1)
            a = (a0 + a1) / 2 - span / 2
            for it, (fw, fh) in zip(row, fp):
                size = it[1] * s * scale * sq
                if it[0].startswith('A:'):
                    size = (it[1] * s * scale * sq, it[2] * s * scale)
                out.append((f, a + fw * s * scale * sq / 2, yc, it[0], size))
                a += fw * s * scale * sq + GAP * s
        y_used = y_top - v * math.cos(tub.lean)
        spare.append((f, f.bottom() + 0.006 * s, y_used - 0.004 * s))
    return out, spare


# ---------------------------------------------------------------- building the cockpit

def build(ac, tub, era_name, pal_name, sw, hud=None, gunsight=True, stick_foot=None, side_stick=False,
          extra=None):
    """-> (cockpit Mesh, stick Mesh, gauges, displays, glass triangles, HeadUpDisplay dict or None)"""
    era = ERAS[era_name]
    pal = PALETTES[pal_name]
    c = {k: sw.uv(v) for k, v in pal.items()}
    c.update({k: sw.uv(v) for k, v in ACCENT.items()})
    t, s, E = tub, tub.s, tub.E
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    m = Mesh()
    gauges, displays, glass = [], [], []
    facets = [Facet(t, 0), Facet(t, 1), Facet(t, -1)]           # centre, right, left
    want_hud = era['hud'] if hud is None else hud

    # -- panel facets: face, back and edges (2 cm slab)
    for f in facets:
        a0, a1 = f.extent()
        corners = [f.at(a0, f.bottom()), f.at(a1, f.bottom()), f.at(a1, f.top(a1)), f.at(a0, f.top(a0))]
        m.flat(corners, c['panel'], normal=f.N)
        back = -f.N * 0.02
        m.flat([q + back for q in corners], c['shade'], normal=-f.N)
        mid0 = np.mean(corners, 0)
        for i in range(4):
            p, q = corners[i], corners[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], c['shade'], toward=mid + (mid - mid0))

    # -- HUD (or gunsight) on the coaming just ahead of the panel lip
    hud_out, strip_y = None, None
    cen = facets[0]
    lip_c = cen.at(0, t.top_c)
    if want_hud or gunsight:
        hw = (0.08 if want_hud else 0.045) * s
        body_h = (0.10 if want_hud else 0.06) * s
        z0 = lip_c[2] - 0.03 * s
        z1 = z0 + (0.29 if want_hud else 0.16) * s
        y0 = t.top_c - 0.035 * s
        y1 = t.top_c + body_h - 0.035 * s
        # the combiner's height: as tall as the glass above lets it be
        lean = math.radians(22 if want_hud else 30)
        g_up = np.array([0, math.cos(lean), -math.sin(lean)])
        g_n = unit(np.cross(X, g_up))
        g_bot = np.array([E[0], y1 + 0.012 * s, z0 + 0.02 * s])
        g_h = (0.165 if want_hud else 0.09) * s
        above = ac.glass_above(E[0], g_bot[2] - g_up[2] * g_h, g_bot[1])
        if above is not None:
            g_h = min(g_h, (above - 0.025 - g_bot[1]) / g_up[1])
        if g_h > 0.05 * s:
            m.box([E[0], (y0 + y1) / 2, (z0 + z1) / 2], (X, Y, Z), (hw, (y1 - y0) / 2, (z1 - z0) / 2), c['dgrey'],
                  faces={(2, -1): c['black']})
            if want_hud:
                # up-front control strip on the aft face: a row of buttons
                for k in range(5):
                    bx = E[0] + hw * (-0.7 + 0.35 * k)
                    m.box([bx, (y0 + y1) / 2, z0 - 0.004], (X, Y, Z), (0.012 * s, 0.01 * s, 0.004), c['mgrey'])
                strip_y = y0 - 0.01 * s
            g_hw = (0.074 if want_hud else 0.035) * s
            g_top = g_bot + g_up * g_h
            m.box(g_bot - g_up * 0.006, (X, g_up, g_n), (g_hw + 0.008, 0.006, 0.012), c['dgrey'])
            for sg in (-1, 1):
                m.box((g_bot + g_top) / 2 + X * sg * (g_hw + 0.004), (X, g_up, g_n), (0.004, g_h / 2, 0.005),
                      c['dgrey'])
            gc = [g_bot - X * g_hw, g_bot + X * g_hw, g_top + X * g_hw, g_top - X * g_hw]
            glass += [gc[0], gc[1], gc[2], gc[0], gc[2], gc[3]]
            if want_hud:
                hud_out = dict(centre=(g_bot + g_top) / 2, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)
        else:
            want_hud = False

    # -- eyebrow lights: annunciators standing on the glareshield either side of the HUD, facing the pilot
    if want_hud and era.get('eyebrows'):
        for sg, (name, ew, eh) in zip((1, -1), era['eyebrows']):      # +1 = pilot's left (x > 0)
            ew, eh = ew * s, eh * s
            ax = sg * (0.08 * s + 0.012 * s + ew / 2)                    # clear of the HUD body (half width 0.08 s)
            foot = cen.at(-ax, t.top_c) + cen.N * 0.045 * s + np.array([0, 0.03 * s, 0])    # on the lip edge
            top_glass = ac.glass_above(foot[0], foot[2], foot[1])
            if top_glass is not None:
                eh = min(eh, (top_glass - 0.02 - foot[1]) / cen.U[1])
            if eh < 0.028 * s:
                continue
            centre = foot + cen.U * eh / 2
            columns, lamps = lamps_of(name, pal_name)
            gauges.append({'Seat': t.seat, 'Kind': 'ANNUNCIATOR', 'Center': r5(centre + cen.N * 0.002), 'Normal': r5(cen.N),
                           'Up': r5(cen.U), 'Diameter': round(ew, 4), 'Width': round(ew, 4), 'Height': round(eh, 4),
                           'Depth': 0.03, 'Columns': columns, 'Lamps': lamps})

    # -- instruments (the centre rows start under the HUD or sight body hanging over the panel lip)
    rows, spare = layout(t, era, facets, hud_body=(t.top_c - 0.035 * s - 0.004) if (want_hud or gunsight) else None)
    for f, a, y, kind, d in rows:
        p = f.at(a, y)
        if kind.startswith('A:'):
            # annunciator panel: the game draws the housing and the lamps (Annunciator.kt)
            w, h = d
            columns, lamps = lamps_of(kind[2:], pal_name)
            gauges.append({'Seat': t.seat, 'Kind': 'ANNUNCIATOR', 'Center': r5(p + f.N * 0.002), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': round(w, 4), 'Width': round(w, 4), 'Height': round(h, 4),
                           'Depth': 0.02, 'Columns': columns, 'Lamps': lamps})
        elif kind == 'UFCP':
            # up-front control panel: keypad block under the HUD
            w, h = d, d * 0.42
            m.box(p + f.N * 0.012, (f.R, f.U, f.N), (w / 2, h / 2, 0.012), c['dgrey'], faces={(2, 1): c['black']})
            for i in range(4):
                for j in range(3):
                    q = p + f.R * (-w * 0.30 + j * w * 0.12) + f.U * (h * 0.30 - i * h * 0.2) + f.N * 0.026
                    m.box(q, (f.R, f.U, f.N), (w * 0.045, h * 0.07, 0.002), c['mgrey'])
            m.box(p + f.R * w * 0.25 + f.N * 0.0245, (f.R, f.U, f.N), (w * 0.18, h * 0.3, 0.001), c['green'])
        elif kind.startswith('D:'):
            style = era['displays']
            displays.append({'Seat': t.seat, 'Center': r5(p + f.N * DISPLAY_LIFT), 'Normal': r5(f.N), 'Up': r5(f.U),
                             'Width': round(d, 4), 'Depth': 0.035, 'Kind': kind[2:], 'Style': style})
            if style == 'CRT' and kind == 'D:RADAR':
                # radar scope shading hood: top plate deep, sides tapering
                _, n_, u_, r_ = frame(p, f.N, f.U)
                hw_ = d / 2 + 0.012
                for sg in (-1, 1):
                    p0 = p + r_ * sg * hw_ - u_ * hw_; p1 = p + r_ * sg * hw_ + u_ * hw_
                    q1 = p1 + n_ * 0.05; q0 = p0 + n_ * 0.015
                    m.flat([p0, p1, q1, q0], c['black'], normal=r_ * sg)
                    m.flat([p0, p1, q1, q0], c['black'], normal=-r_ * sg)
                top0 = p - r_ * hw_ + u_ * hw_; top1 = p + r_ * hw_ + u_ * hw_
                m.box((top0 + top1) / 2 + n_ * 0.025 + u_ * 0.003, (r_, u_, n_), (hw_ + 0.006, 0.003, 0.025),
                      c['black'])
        else:
            gauges.append({'Seat': t.seat, 'Kind': kind, 'Center': r5(p + f.N * GAUGE_LIFT), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': round(d, 4), 'Depth': 0.03})
    # the band under the instruments: switch panels (rows of toggles over a dark strip), a gear lever on the left
    for f, y0, y1 in spare:
        a0, a1 = f.extent()
        a0 += 0.012 * s; a1 -= 0.012 * s
        band = (y1 - y0) / math.cos(t.lean)
        n_rows = int(band / (0.045 * s))
        if n_rows < 1 or a1 - a0 < 0.06 * s:
            continue
        gear_lever = f.side == -1 and era['seat'] == 'ejection'
        if gear_lever:
            # gear handle: a short lever with a red wheel knob, at the inboard end of the lowest row
            ga, gy = a0 + 0.025 * s, y0 + 0.018 * s
            gp = f.at(ga, gy)
            m.box(gp + f.N * 0.006, (f.R, f.U, f.N), (0.016 * s, 0.022 * s, 0.006), c['black'])
            m.box(gp + f.N * 0.022 + f.U * 0.012 * s, (f.R, f.U, f.N), (0.004 * s, 0.018 * s, 0.016), c['metal'])
            m.box(gp + f.N * 0.040 + f.U * 0.028 * s, (f.R, f.U, f.N), (0.010 * s, 0.010 * s, 0.006 * s), c['red'])
        for k in range(min(n_rows, 2)):
            yc = y1 - (k + 0.5) * (band / min(n_rows, 2)) * math.cos(t.lean)
            ra0 = a0 + (0.06 * s if gear_lever and k == min(n_rows, 2) - 1 else 0.0)
            if a1 - ra0 < 0.05 * s:
                continue
            pc = f.at((ra0 + a1) / 2, yc)
            hw = (a1 - ra0) / 2
            m.box(pc + f.N * 0.003, (f.R, f.U, f.N), (hw, 0.016 * s, 0.003), c['black'])
            n_sw = max(2, int((a1 - ra0) / (0.024 * s)))
            for j in range(n_sw):
                sa = ra0 + (j + 0.5) * (a1 - ra0) / n_sw
                sp = f.at(sa, yc) + f.N * 0.006
                m.box(sp, (f.R, f.U, f.N), (0.005 * s, 0.005 * s, 0.004), c['mgrey'])          # the switch base
                tilt = 1 if (j + k) % 3 else -1
                m.box(sp + f.N * 0.010 + f.U * tilt * 0.006 * s, (f.R, unit(f.U * tilt + f.N * 0.9), unit(f.N - f.U * tilt * 0.9)),
                      (0.0025 * s, 0.009 * s, 0.0025 * s), c['lgrey'] if (j % 4) else c['red'])     # the toggle

    # -- coaming: lofted from the panel lip forward to the windscreen base
    lip = []
    for f, aa in ((facets[2], np.linspace(t.wing_len, 0, 4)), (facets[0], np.linspace(-t.hinge, t.hinge, 7)),
                  (facets[1], np.linspace(0, t.wing_len, 4))):
        for a in aa:
            lip.append((f.at(a, f.top(a)), f))
    lip.sort(key=lambda q: -q[0][0])
    pts = []
    for p, f in lip:
        if pts and np.linalg.norm(pts[-1][0] - p) < 1e-6:
            continue
        pts.append((p, f))
    lip_edge = [p + f.N * 0.045 * s + np.array([0, 0.03 * s, 0]) for p, f in pts]
    panel_top = [p for p, f in pts]
    front_x = t.W * 0.9
    rows_, pinched = [], set()
    for k, tt in enumerate((0.0, 0.35, 0.7, 1.0)):
        row = []
        for le in lip_edge:
            sx = (le[0] - E[0]) / max(abs(lip_edge[0][0] - E[0]), 1e-6)
            xf = E[0] + sx * front_x
            yf = t.ws_y - 0.05 * s * sx * sx
            z = le[2] + (t.ws_z - le[2]) * tt
            x = le[0] + (xf - le[0]) * tt
            crown = 0.04 * s * math.sin(math.pi * tt) * (1 - sx * sx)
            y = le[1] + (yf - le[1]) * tt + crown
            # under the glass: a corner reaching out beside a narrow windscreen comes in
            if k:
                while abs(x - E[0]) > 0.05 and ac.glass_above(x, z, y - 0.3) is None:
                    x -= np.sign(x - E[0]) * 0.02
                    pinched.add((k, len(row)))
            # never through the glass
            g = ac.glass_above(x, z, y - 0.3) if k else None
            if g is not None and g < y + 0.02:
                y = g - 0.02
            if k and abs(x - E[0]) > 0.05:                # nor out through a narrowing nose
                d = ac.skin_x(y, z, E[0])[0 if x > E[0] else 1]
                if abs(x - E[0]) > d - 0.01:
                    x = E[0] + np.sign(x - E[0]) * max(0.05, d - 0.01)
                    pinched.add((k, len(row)))
            row.append(np.array([x, y, z]))
        rows_.append(row)
    for i in range(len(rows_) - 1):
        for j in range(len(rows_[i]) - 1):
            m.flat([rows_[i][j], rows_[i][j + 1], rows_[i + 1][j + 1], rows_[i + 1][j]], c['coaming'],
                   normal=[0, 1, 0])
    for j in range(len(lip_edge) - 1):
        a, b = lip_edge[j], lip_edge[j + 1]
        da, db = a - [0, 0.012 * s, 0], b - [0, 0.012 * s, 0]
        m.flat([a, b, db, da], c['coaming'], toward=E)
        m.flat([da, db, panel_top[j + 1], panel_top[j]], c['coaming'], normal=[0, -1, 0])
    for j in (0, len(lip_edge) - 1):
        col = [rows_[i][j] for i in range(len(rows_))]
        for i in range(len(col) - 1):
            if (i, j) in pinched or (i + 1, j) in pinched:
                continue                                  # pulled in from the canopy's width: no side below it
            p, q = col[i], col[i + 1]
            lo = min(t.sill, p[1]) - 0.15 * s
            while lo < p[1] - 0.01 and not (t.fits(ac, p[0], lo, p[2]) and t.fits(ac, q[0], lo, q[2])):
                lo += 0.02                                    # the nose narrows: keep the side inside the skin
            m.flat([p, q, [q[0], lo, q[2]], [p[0], lo, p[2]]], c['coaming'], toward=[E[0], lo, (p[2] + q[2]) / 2])

    # -- side walls, consoles, floor, bulkheads, footwell
    xin = 0.58 * t.W                                  # console inner edge
    xlow = {}
    for side in (1, -1):                              # +1 = left (x > 0)
        xw = E[0] + t.W * side
        xi = E[0] + xin * side
        wing = facets[-1] if side > 0 else facets[1]
        def wing_z(x):
            a = (abs(x - E[0]) - t.hinge) / math.cos(t.wing)
            return wing.at(max(0.0, min(t.wing_len, a)), t.bot_w)[2]
        zf_i, zf_o = wing_z(xi), wing_z(xw)
        zb = t.back_z + 0.1 * s
        zw = zf_o + 0.1                               # on past the wing's outer edge, closing the side behind it,
        while zw < t.Z + 0.3 * s and t.fits(ac, xw, t.sill - 0.02, zw + 0.03) and \
                t.fits(ac, xw, t.console_y, zw + 0.03):   # as far as the fuselage is wide enough
            zw += 0.03
        m.flat([[xw, t.console_y - 0.02, t.back_z], [xw, t.console_y - 0.02, zw],
                [xw, t.sill, zw], [xw, t.sill, t.back_z]], c['wall'], normal=[-side, 0, 0])
        xl = xw                                       # the lower wall behind the console, inside a narrowing belly
        for y in (t.floor_y + 0.02, (t.floor_y + t.console_y) / 2):
            d = ac.skin_x(y, (t.back_z + zb) / 2, E[0])[0 if side > 0 else 1]
            xl = E[0] + side * min(abs(xl - E[0]), d - 0.01)
        xlow[side] = xl
        m.flat([[xl, t.floor_y, t.back_z], [xl, t.floor_y, zb], [xw, t.console_y, zb], [xw, t.console_y, t.back_z]],
               c['wall'], normal=[-side, 0, 0])
        top = [[xi, t.console_y, zb], [xw, t.console_y, zb], [xw, t.console_y, zf_o], [xi, t.console_y, zf_i]]
        m.flat(top, c['console'], normal=[0, 1, 0])
        m.flat([[xi, t.floor_y, zb], [xi, t.floor_y, zf_i], [xi, t.console_y, zf_i], [xi, t.console_y, zb]],
               c['shade'], normal=[-side, 0, 0])
        m.flat([[xi, t.floor_y, zb], [xw, t.floor_y, zb], [xw, t.console_y, zb], [xi, t.console_y, zb]],
               c['shade'], normal=[0, 0, -1])
        # switch blocks along the console: small dark panels with a few knobs and toggles
        L = zf_i - zb
        n_blocks = max(3, int(L / (0.11 * s)))
        for k in range(n_blocks):
            zc = zb + (k + 0.5) * L / n_blocks
            wx = abs(xw - xi) - 0.02 * s
            m.box([(xi + xw) / 2, t.console_y + 0.004, zc], (X, Y, Z), (wx / 2, 0.004, L / n_blocks / 2 - 0.006 * s),
                  c['black'])
            for j in range(3):
                kx = xi + side * (0.02 * s + (j + 0.5) * (wx - 0.02 * s) / 3)
                col = c['mgrey'] if (k + j) % 3 else c['lgrey']
                m.box([kx, t.console_y + 0.016 * s, zc], (X, Y, Z), (0.006 * s, 0.008 * s, 0.006 * s), col)
        # sill cap (canopy rail ledge) along the wall top
        m.box([xw - side * 0.015, t.sill + 0.01, (t.back_z + zf_o) / 2], (X, Y, Z),
              (0.03, 0.012, (zf_o - t.back_z) / 2), c['dgrey'])
    m.flat([[E[0] - xin, t.floor_y, t.back_z], [E[0] + xin, t.floor_y, t.back_z],
            [E[0] + xin, t.floor_y, t.front_z], [E[0] - xin, t.floor_y, t.front_z]], c['floor'], normal=[0, 1, 0])
    m.flat([[xlow[-1], t.floor_y, t.back_z], [xlow[1], t.floor_y, t.back_z], [E[0] + t.W, t.console_y, t.back_z],
            [E[0] - t.W, t.console_y, t.back_z]], c['wall'], normal=[0, 0, 1])
    m.flat([[E[0] - t.W, t.console_y, t.back_z], [E[0] + t.W, t.console_y, t.back_z],
            [E[0] + t.W, t.sill + 0.02, t.back_z], [E[0] - t.W, t.sill + 0.02, t.back_z]], c['wall'], normal=[0, 0, 1])
    m.flat([[E[0] - xin, t.floor_y, t.front_z], [E[0] + xin, t.floor_y, t.front_z],
            [E[0] + xin, t.bot_c, t.front_z], [E[0] - xin, t.bot_c, t.front_z]], c['floor'], normal=[0, 0, -1])
    bl, br = cen.at(t.hinge, t.bot_c), cen.at(-t.hinge, t.bot_c)
    m.flat([bl, br, [br[0], t.bot_c, t.front_z], [bl[0], t.bot_c, t.front_z]], c['shade'], normal=[0, -1, 0])
    for sg in (1, -1):
        e = cen.at(-t.hinge * sg, t.bot_c)
        m.flat([[E[0] + sg * xin, t.floor_y, e[2] - 0.2 * s], [E[0] + sg * xin, t.floor_y, t.front_z],
                [E[0] + sg * xin, t.bot_c, t.front_z], [E[0] + sg * xin, t.bot_c, e[2] - 0.2 * s]], c['shade'],
               normal=[-sg, 0, 0])
        m.flat([e, cen.at(-t.hinge * sg, t.bot_w), [E[0] + sg * xin, t.bot_w, e[2]], [E[0] + sg * xin, t.bot_c, e[2]]],
               c['shade'], normal=[0, 0, -1])
    # rudder pedals
    pz = t.front_z - 0.08 * s
    for sg in (1, -1):
        m.box([E[0] + sg * 0.12 * s, t.floor_y + 0.12 * s, pz], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])),
              (0.045 * s, 0.07 * s, 0.01), c['mgrey'])
        m.box([E[0] + sg * 0.12 * s, t.floor_y + 0.06 * s, pz + 0.06 * s], (X, Y, Z), (0.012, 0.03 * s, 0.06 * s),
              c['dgrey'])

    # -- throttle on the left console
    tx, tz = E[0] + 0.78 * t.W, E[2] + 0.24 * s
    m.box([tx, t.console_y + 0.055 * s, tz], (X, unit([0, 1, 0.25]), unit([0, -0.25, 1])),
          (0.009 * s, 0.06 * s, 0.009 * s), c['metal'])
    grip_ax = (X, unit([0, 1, 0.3]), unit([0, -0.3, 1]))
    m.box([tx, t.console_y + 0.135 * s, tz + 0.02 * s], grip_ax, (0.03 * s, 0.045 * s, 0.04 * s), c['black'])
    m.box([tx - 0.023 * s, t.console_y + 0.15 * s, tz + 0.045 * s], grip_ax, (0.004, 0.008 * s, 0.008 * s), c['red'])
    m.box([tx, t.console_y + 0.02 * s, tz], (X, Y, Z), (0.035 * s, 0.02 * s, 0.09 * s), c['black'])   # quadrant

    # -- the seat
    if era['seat'] == 'ejection':
        ejection_seat(m, ac, t, c)
    else:
        bucket_seat(m, t, c)

    # -- control stick: the boot stays with the cockpit, the rest moves in the stick bone
    stick = Mesh()
    if stick_foot is None:
        stick_foot = np.array([E[0], t.floor_y + 0.1 * s, E[2] + 0.44 * s])
    foot = np.array(stick_foot, float)
    if side_stick:
        sd = 1 if foot[0] > E[0] else -1
        m.box(foot + [0, -0.01, 0], (X, Y, Z), (0.03 * s, 0.012 * s, 0.04 * s), c['black'])
        ax = (X, unit([sd * 0.15, 1, 0.25]), unit([0, -0.25, 1]))
        gc = foot + ax[1] * 0.075 * s
        stick.box(gc, ax, (0.02 * s, 0.06 * s, 0.026 * s), c['black'])
        stick.box(gc + ax[1] * 0.064 * s, ax, (0.016 * s, 0.006 * s, 0.02 * s), c['dgrey'])
        stick.box(gc + ax[2] * 0.028 * s + ax[1] * 0.02 * s, ax, (0.006 * s, 0.01 * s, 0.004 * s), c['metal'])
        stick.box(gc + ax[1] * 0.07 * s + ax[2] * 0.01 * s, ax, (0.005 * s, 0.004 * s, 0.005 * s), c['red'])
    else:
        top_y = E[1] - 0.445 * s
        top_y = max(top_y, foot[1] + 0.25 * s)
        m.box(foot + [0, 0.03 * s, 0], (X, Y, Z), (0.045 * s, 0.04 * s, 0.045 * s), c['dgrey'])
        L = top_y - 0.05 * s - foot[1]
        col_ax = (X, unit([0, 1, -0.07]), unit([0, 0.07, 1]))
        stick.box(foot + col_ax[1] * L / 2, col_ax, (0.011 * s, L / 2, 0.011 * s), c['metal'])
        g_ax = (X, unit([0, 1, 0.21]), unit([0, -0.21, 1]))
        gc = foot + col_ax[1] * L + g_ax[1] * 0.055 * s
        stick.box(gc, g_ax, (0.021 * s, 0.062 * s, 0.027 * s), c['black'])
        stick.box(gc + g_ax[1] * 0.066 * s, g_ax, (0.017 * s, 0.006 * s, 0.022 * s), c['dgrey'])
        stick.box(gc + g_ax[1] * 0.07 * s + g_ax[2] * 0.010 * s, g_ax, (0.006 * s, 0.004 * s, 0.006 * s), c['red'])
        stick.box(gc + g_ax[2] * 0.031 * s + g_ax[1] * 0.015 * s, g_ax, (0.008 * s, 0.012 * s, 0.005 * s),
                  c['metal'])
    if extra:
        extra(m, t, c)
    return m, stick, gauges, displays, glass, hud_out


def ejection_seat(m, ac, t, c):
    s, E = t.s, t.E
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    ax = (X, unit([0, 1, -0.14]), unit([0, 0.14, 1]))           # 8 degrees reclined
    pan_y = E[1] - 0.85 * s
    m.box([E[0], pan_y - 0.125 * s, E[2]], (X, Y, Z), (0.20 * s, 0.09 * s, 0.22 * s), c['frame'])
    m.box([E[0], pan_y, E[2] + 0.03 * s], (X, Y, Z), (0.19 * s, 0.035 * s, 0.21 * s), c['cushion'])
    for sg in (1, -1):
        m.box([E[0] + sg * 0.212 * s, pan_y + 0.005 * s, E[2] - 0.01 * s], (X, Y, Z), (0.012 * s, 0.15 * s, 0.24 * s),
              c['frame'])
        m.box([E[0] + sg * 0.17 * s, E[1] - 0.40 * s, E[2] - 0.40 * s], ax, (0.02 * s, 0.62 * s, 0.025 * s),
              c['frame'])
    m.box([E[0], E[1] - 0.50 * s, E[2] - 0.23 * s], ax, (0.19 * s, 0.33 * s, 0.03 * s), c['cushion'])
    m.box([E[0], E[1] - 0.50 * s, E[2] - 0.29 * s], ax, (0.205 * s, 0.36 * s, 0.035 * s), c['frame'])
    hb_top = E[1] + 0.21 * s
    g = ac.glass_above(E[0], E[2] - 0.31 * s, E[1])
    if g is not None:
        hb_top = min(hb_top, g - 0.03)
    hb_bot = E[1] - 0.18 * s
    m.box([E[0], (hb_top + hb_bot) / 2, E[2] - 0.31 * s], (X, Y, Z), (0.16 * s, (hb_top - hb_bot) / 2, 0.10 * s),
          c['frame'])
    m.box([E[0], E[1] + 0.02 * s, E[2] - 0.195 * s], (X, Y, Z), (0.11 * s, 0.11 * s, 0.015 * s), c['cushion'])
    # ejection handle loop between the knees (yellow)
    for sg in (1, -1):
        m.box([E[0] + sg * 0.05 * s, pan_y + 0.07 * s, E[2] + 0.26 * s], (X, Y, Z),
              (0.008 * s, 0.035 * s, 0.008 * s), c['yellow'])
    m.box([E[0], pan_y + 0.105 * s, E[2] + 0.26 * s], (X, Y, Z), (0.058 * s, 0.008 * s, 0.008 * s), c['yellow'])


def bucket_seat(m, t, c):
    """piston-fighter seat: a bucket on a frame with an armour plate behind the pilot"""
    s, E = t.s, t.E
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    pan_y = E[1] - 0.80 * s
    m.box([E[0], pan_y - 0.04 * s, E[2] + 0.02 * s], (X, Y, Z), (0.19 * s, 0.03 * s, 0.20 * s), c['frame'])
    m.box([E[0], pan_y, E[2] + 0.02 * s], (X, Y, Z), (0.17 * s, 0.02 * s, 0.18 * s), c['cushion'])
    for sg in (1, -1):
        m.box([E[0] + sg * 0.195 * s, pan_y + 0.10 * s, E[2] - 0.02 * s], (X, Y, Z), (0.008, 0.13 * s, 0.20 * s),
              c['frame'])
    ax = (X, unit([0, 1, -0.2]), unit([0, 0.2, 1]))
    m.box([E[0], E[1] - 0.48 * s, E[2] - 0.22 * s], ax, (0.19 * s, 0.33 * s, 0.012 * s), c['frame'])
    m.box([E[0], E[1] - 0.42 * s, E[2] - 0.20 * s], ax, (0.15 * s, 0.22 * s, 0.02 * s), c['cushion'])
    m.box([E[0], E[1] - 0.06 * s, E[2] - 0.30 * s], ax, (0.17 * s, 0.17 * s, 0.012 * s), c['dgrey'])   # head armour
    m.box([E[0], E[1] + 0.0 * s, E[2] - 0.285 * s], ax, (0.08 * s, 0.07 * s, 0.015 * s), c['cushion'])


# ---------------------------------------------------------------- removing the old interior

SKIP_BONES = ('canopy', 'pilot_view_occluder', 'gear', 'landing', 'suspended', 'propeller', 'wheel', 'door',
              'aileron', 'elevator', 'rudder', 'flap', 'airbrake', 'control_stick', 'turret', 'barell')


def is_interior_bone(name):
    if not name.startswith('wreck_'):
        return False
    tail = name.split('__', 1)[1] if '__' in name else name
    return tail == 'hull'


def old_interior(ac, tubs, keep=()):
    """{bone name: set(poly index)} of each tub's old interior:
      A. faces of the tub below the canopy sill (the outer skin along the sill excepted);
      B. small loose parts in the canopy space (old seat, panel, consoles), windscreen frames excepted;
      C. faces in front of the pilot below eye level, up to the windscreen base: old panels, glareshields, gunsight
         and HUD parts (the windscreen frame, which lies along the glass, and thin members such as the legs of an
         armour-glass frame are kept)."""
    out, clips = {}, {}
    for b in ac.bones:
        if not is_interior_bone(b['name']) or b['name'] in keep:
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        polys = pm['polys']
        verts = [P[[v[0] for v in p]] for p in polys]
        cent = np.array([V.mean(0) for V in verts])
        N = np.array(pm['normals'], float) * np.array([1, 1, -1])
        # connected parts of the whole bone: top and face count of each face's part
        parent = {}
        def root(x):
            while parent.setdefault(x, x) != x:
                parent[x] = parent[parent[x]]; x = parent[x]
            return x
        vkey = lambda i: tuple(np.round(P[i], 4))
        for p in polys:
            ks = [vkey(v[0]) for v in p]
            for k in ks[1:]:
                parent[root(ks[0])] = root(k)
        part = [root(vkey(p[0][0])) for p in polys]
        part_top, part_n = {}, {}
        for V, r in zip(verts, part):
            part_top[r] = max(part_top.get(r, -1e9), V[:, 1].max())
            part_n[r] = part_n.get(r, 0) + 1
        sel = set()
        for t in tubs:
            E, s = t.E, t.s
            for i, (cc, p, V) in enumerate(zip(cent, polys, verts)):
                dxv = np.abs(V[:, 0] - E[0])
                dx = abs(cc[0] - E[0])
                # A
                nx = N[p[0][1]][0] * np.sign(cc[0] - E[0]) if dx > 1e-6 else 0
                # the outer skin: outward-facing at the canopy's width, or the outermost face along its sideways line
                skin = dx > t.Wg - 0.05 and nx > 0.3
                def outer():
                    if dx > 0.1 and dx >= ac.skin_x(cc[1], cc[2], E[0])[0 if cc[0] > E[0] else 1] - 0.02:
                        return True
                    n = np.cross(V[1] - V[0], V[-1] - V[0]); ln = np.linalg.norm(n)
                    if ln < 1e-9:
                        return False
                    n = n / ln
                    if n @ (cc - [E[0], E[1] - 0.3, cc[2]]) < 0:
                        n = -n                                # away from the cockpit's middle
                    return ac.escapes(cc + n * 0.002, n)
                if not skin and dx < t.Wg - 0.008 and t.floor_y - 0.25 * s < cc[1] < t.sill - 0.004 and \
                        t.back_z + 0.02 < cc[2] < t.Z + 0.12 * s and not outer():
                    sel.add(i)
                    continue
                # C
                crosses = V[:, 0].min() < E[0] - 0.05 and V[:, 0].max() > E[0] + 0.05
                inside = crosses or dxv.min() < t.Wg - 0.008
                if not inside or dxv.max() > t.Wg + 0.10 or skin:
                    continue
                dz = V[:, 2] - E[2]
                far = V[:, 2] >= t.ws_z + 0.05
                ax = np.linalg.svd(V - V.mean(0))[2]          # principal axes, longest first
                ext = np.ptp((V - V.mean(0)) @ ax.T, axis=0)
                if ext[1] < 0.07 and ext[0] > 0.15 and abs(ax[0][1]) > 0.5 and V[:, 1].max() > t.top_c + 0.03 * s:
                    if ac.hugs_glass(V):
                        # a thin upright member reaching above the coaming along the glass (a frame post or an
                        # armour-glass leg): kept, but not in front of the panel face
                        if np.all(dz > 0.12) and V[:, 1].min() < t.top_c - 0.02 and \
                                V[V[:, 1] < t.top_c, 2].min() < t.Z + 0.15 * s:
                            clips.setdefault(b['name'], {})[i] = (1, t.top_c - 0.01)
                        continue
                    if dxv.max() < t.Wg - 0.08 and np.all(dz > 0.12) and np.all(V[:, 1] < E[1]):
                        # one standing well inside the glass, over the glareshield: an old HUD or sight support
                        # (its glass is gone), and the new HUD brings its own
                        sel.add(i)
                        continue
                if not (np.all(dz > 0.12) and np.all(V[:, 1] < E[1]) and np.all(V[:, 1] > t.floor_y - 0.3)):
                    continue
                if far.any() and (far.all() or V[:, 2].min() > t.ws_z - 0.1):
                    continue
                if ac.hugs_glass(V) and not (crosses and V[:, 2].max() < t.ws_z - 0.15 * s):
                    continue                                  # lies along the glass: canopy / windscreen frame
                if not far.any() and outer():
                    continue                                  # (a deck cut at the windscreen keeps its outer part)
                if far.any():
                    clips.setdefault(b['name'], {})[i] = (2, t.ws_z)   # a deck running on under the windscreen: cut
                else:
                    sel.add(i)
            # B: small connected parts in the canopy space
            pr = {}
            def find(x):
                while pr.setdefault(x, x) != x:
                    pr[x] = pr[pr[x]]; x = pr[x]
                return x
            box = [i for i, cc in enumerate(cent) if abs(cc[0] - E[0]) < t.Wg + 0.1
                   and t.floor_y - 0.4 < cc[1] < E[1] + 0.8 and t.back_z - 0.6 < cc[2] < t.ws_z + 0.3]
            for i in box:
                ks = [vkey(v[0]) for v in polys[i]]
                for k in ks[1:]:
                    pr[find(ks[0])] = find(k)
            comps = {}
            for i in box:
                comps.setdefault(find(vkey(polys[i][0][0])), []).append(i)
            for ids in comps.values():
                if len(ids) > 400:
                    continue
                V = np.concatenate([verts[i] for i in ids])
                lo, hi = V.min(0), V.max(0)
                inside = (np.all(np.abs(V[:, 0] - E[0]) < t.Wg - 0.01) and lo[1] > t.floor_y - 0.3 and
                          hi[1] < E[1] + 0.7 and lo[2] > t.back_z - 0.35 and hi[2] < t.ws_z + 0.05)
                frame_like = hi[2] > t.Z + 0.25 * s and hi[1] > t.top_c + 0.15 * s and \
                    ac.touches_glass(np.unique(np.round(V, 4), axis=0))     # a gunsight stands clear of the glass
                if inside and not frame_like:
                    sel.update(ids)
        if sel:
            out[b['name']] = sel
    for name, cl in clips.items():
        for i in out.get(name, ()):
            cl.pop(i, None)
    return out, clips


def clip_forward(pm, index, zc):
    """cuts poly [index] of a poly mesh at vehicle-local z = zc, keeping the part ahead (z >= zc)"""
    clip_keep(pm, index, 2, zc)


def clip_keep(pm, index, axis, c):
    """cuts poly [index] of a poly mesh at the vehicle-local plane [axis] = c, keeping the part where it is >= c;
    new corner vertices interpolate position and uv"""
    poly = pm['polys'][index]
    P = pm['positions']; U = pm['uvs']
    sg = -1.0 if axis == 2 else 1.0                       # file pixels: z is reversed
    val = lambda v: sg * P[v[0]][axis] / 16.0
    keep = lambda v: val(v) >= c - 1e-9
    out = []
    for k in range(len(poly)):
        a, b = poly[k], poly[(k + 1) % len(poly)]
        if keep(a):
            out.append(a)
        if keep(a) != keep(b):
            pa, pb = np.array(P[a[0]]), np.array(P[b[0]])
            t = (c - val(a)) / (val(b) - val(a))
            q = pa + (pb - pa) * t
            ua, ub = np.array(U[a[2]]), np.array(U[b[2]])
            P.append([round(float(x), 4) + 0.0 for x in q])
            U.append([round(float(x), 6) for x in ua + (ub - ua) * t])
            out.append([len(P) - 1, a[1], len(U) - 1])
    pm['polys'][index] = out if len(out) >= 3 else []


# ---------------------------------------------------------------- writing

def write_asset(path, asset):
    """the asset keeps its layout: indented, with the canopy glass triangle list on one line at the end"""
    glass_json = asset.pop('CanopyGlass', None)
    text = json.dumps(asset, indent=2, ensure_ascii=False)
    if glass_json is not None:
        text = text[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
        asset['CanopyGlass'] = glass_json
    with open(path, 'w') as f:
        f.write(text + '\n')


def add_glass(asset, tris, drop=None):
    """adds triangles (flat list of points, 3 per triangle) to CanopyGlass in both windings; drop(centroid) -> bool
    removes earlier ones"""
    cg = asset.get('CanopyGlass')
    if not cg:
        cg = asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': []}
    t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
    if drop is not None and len(t):
        t = t[[not drop(c) for c in t.mean(1)]]
    G = [list(map(float, p)) for p in tris]
    both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
    cg['Triangles'] = [round(v, 4) + 0.0 for p in [list(map(float, q)) for q in t.reshape(-1, 3)] + both for v in p]
