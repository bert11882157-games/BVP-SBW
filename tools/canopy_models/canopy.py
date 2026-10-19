#!/usr/bin/env python3
"""Modelled canopies: removes an aircraft's old canopy (frame geometry and glass) and builds a new one.

1. Strip (strip.py): the old canopy's frame faces go from the hull bones, and its glass from CanopyGlass (the HUD
   combiner glass of the modelled cockpit stays).
2. Shape: the canopy's footprint (sill line, width and crown along its length) is read from the old glass, which was
   fitted to the model's canopy opening, and smoothed into a fair loft: every station is a superellipse from sill rail
   to crown to sill rail, its fullness fitted once for the whole canopy; the ends close on the fuselage. canopies.json
   can override the profile per aircraft from reference drawings.
3. Glass: the transparency has its real thickness (outer and inner skin, CanopyGlass), so it reads as a solid piece
   at grazing angles.
4. Frame (new bone wreck_<piece>__canopy, so it breaks away with its fuselage piece): a sill member along both lower
   edges (and round the front tip on frameless-bow canopies), bows across the glass where the aircraft has them, the
   rear arch and hinge blocks, and the dark sealant band where the transparency meets the frame. The outside is in the
   aircraft's own paint (sampled from the fuselage skin beside the cockpit), the inside black, as on the real frames.
   Colours come from uniform texel blocks of the aircraft's texture (cockpit_lib.Swatches); textures are not written.

Usage: python3 tools/canopy_models/canopy.py [--dry-run] id...   (canopies.json lists the aircraft)
"""
import json, math, os, sys
import numpy as np
from PIL import Image
from scipy.interpolate import PchipInterpolator, UnivariateSpline

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
import cockpit_lib as L  # noqa: E402
import strip as S  # noqa: E402

CONFIG = json.load(open(os.path.join(HERE, 'canopies.json'), encoding='utf-8-sig'))
DZ = 0.04                    # station spacing (blocks)
SEG = 28                     # points across each station, sill to sill
INTERIOR = (22, 22, 24)
SEALANT = (46, 48, 50)


# ---------------------------------------------------------------- shape

class Shape:
    """Canopy loft: per station z, the sill height ys, the half width w, the centre x0 and the crown c; the section
    is a superellipse of exponent n."""

    def __init__(self, glass, spec, E, sill_ref=None):
        """sill_ref: (z from, z to, rail height) of the cockpit opening, or None"""
        G = glass.reshape(-1, 3)
        z0, z1 = G[:, 2].min(), G[:, 2].max()
        zs, ys, ws, cs, xs = [], [], [], [], []
        for z in np.arange(z0 + DZ / 2, z1 - DZ / 4, DZ / 2):
            s = G[np.abs(G[:, 2] - z) < DZ]
            if len(s) < 6:
                continue
            zs.append(z); ys.append(s[:, 1].min()); ws.append(np.ptp(s[:, 0]) / 2)
            cs.append(s[:, 1].max()); xs.append((s[:, 0].max() + s[:, 0].min()) / 2)
        zs, ys, ws, cs = map(np.array, (zs, ys, ws, cs))
        ok = np.ones(len(zs), bool)
        for lo_z, hi_z in spec.get('exclude', []):       # stations the old glass got wrong (rel. to the eye)
            ok &= ~((zs > E[2] + lo_z) & (zs < E[2] + hi_z))
        self.x0 = float(np.median(xs))
        self.front = float(spec.get('front', z1))
        self.rear = float(spec.get('rear', z0))
        smooth = spec.get('smooth', 1.0)
        # the ends close: half width to nothing and the crown down to the sill, at the canopy's ends. Robust: fit,
        # drop the stations far off the fit (a hole cut for a HUD, a stray pane), fit again.
        def fit(v, end_v, tip_v, s_mul, use):
            """end_v / tip_v: the value the rear / front end is held to, or None (free)"""
            z_, v_, w_ = list(zs[use]), list(v[use]), [1.0] * int(use.sum())
            if end_v is not None:
                z_.insert(0, self.rear); v_.insert(0, end_v); w_.insert(0, 20.0)
            if tip_v is not None:
                z_.append(self.front); v_.append(tip_v); w_.append(20.0)
            return UnivariateSpline(z_, v_, w=w_, k=3, s=s_mul * len(z_) * smooth)
        # the ends: "point" closes to nothing on the spine / nose (a bubble's tip), "flat" ends at full width on the
        # fuselage (a windscreen base, a hood meeting a fairing); either way the crown comes down to the sill there
        self.ends = (spec.get('rear_end', 'flat'), spec.get('front_end', 'flat'))
        # a flat rear end (a hood meeting the fairing behind it) keeps its height there, closed by the rear arch
        point_rear = self.ends[0] == 'point'
        end_y = lambda: spec.get('rear_y', ys[ok][0]) if point_rear else None
        tip_y = lambda: spec.get('front_y', ys[ok][-1])
        tol = spec.get('fit_tolerance', 1.0)
        for _ in range(len(zs) // 4):            # at most a quarter of the stations go
            f_y = fit(ys, end_y(), tip_y(), 0.0004, ok)
            f_c = fit(cs, end_y() + 0.01 if point_rear else None, tip_y() + 0.01, 0.0002, ok)
            f_w = fit(ws, 0.0 if self.ends[0] == 'point' else None, 0.0 if self.ends[1] == 'point' else None,
                      0.0003, ok)
            res = np.maximum.reduce([np.abs(f_y(zs) - ys) / 0.06, np.abs(f_c(zs) - cs) / 0.06,
                                     np.abs(f_w(zs) - ws) / 0.07]) / tol
            res[~ok] = 0.0
            if res.max() <= 1.0:
                break
            ok[int(np.argmax(res))] = False
        # the sill is the cockpit rail: a fair line, so it is fitted far more stiffly than the crown (side panes and
        # holes in the old glass make the lowest glass point jump from station to station), and along the cockpit it
        # is held within reach of the measured rail
        f_y = fit(ys, end_y(), tip_y(), 0.004 * spec.get('sill_stiffness', 1.0), ok)
        f_w = fit(ws, 0.0 if self.ends[0] == 'point' else None, 0.0 if self.ends[1] == 'point' else None,
                  0.002 * spec.get('sill_stiffness', 1.0), ok)
        if sill_ref is not None:
            lo_z, hi_z, rail = sill_ref
            raw = f_y
            def f_y(z, raw=raw):
                v = raw(z)
                inside = (z > lo_z) & (z < hi_z)
                return np.where(inside, np.maximum(v, rail - 0.04), v)
        f_c = fit(cs, end_y() + 0.01 if point_rear else None, tip_y() + 0.01, 0.002 * spec.get('crown_stiffness', 1.0), ok)
        # a canopy's crown and width rise to one peak and fall away from it: the fitted curves are made single-peaked
        # (each side of the peak non-increasing outward), and the crown clears the pilot's head
        zz = np.linspace(self.rear, self.front, 400)
        head = E[1] + spec.get('head_clearance', 0.12)
        def one_peak(v):
            k = int(np.argmax(v))
            v = v.copy()
            v[k:] = np.minimum.accumulate(v[k:])
            v[:k + 1] = np.minimum.accumulate(v[:k + 1][::-1])[::-1]
            return v
        cv = f_c(zz)
        near = np.abs(zz - E[2]) < 0.25
        cv[near] = np.maximum(cv[near], head)
        cv = one_peak(cv)
        wv = one_peak(np.maximum(f_w(zz), 0.0))
        f_c = PchipInterpolator(zz, cv, extrapolate=True)
        f_w = PchipInterpolator(zz, wv, extrapolate=True)
        self.ys, self.cs, self.ws = f_y, f_c, f_w
        self.dropped = [round(float(z), 2) for z in zs[~ok]]
        for k, v in spec.get('profile', {}).items():     # reference overrides: {"crown": [[dz, dy], ...]} rel. to eye
            pts = np.array(v, float) + [E[2], E[1] if k != 'width' else 0.0]
            sp = UnivariateSpline(pts[:, 0], pts[:, 1], k=min(3, len(pts) - 1), s=0)
            setattr(self, {'crown': 'cs', 'sill': 'ys', 'width': 'ws'}[k], sp)
        # fullness: the superellipse exponent that fits the old glass best
        self.n = float(spec.get('fullness') or self._fit_n(G))

    def _fit_n(self, G):
        best = None
        sel = G[(G[:, 2] > self.rear + 0.1) & (G[:, 2] < self.front - 0.1)]
        for n in np.arange(1.6, 4.01, 0.1):
            err = 0.0
            for p in sel[::3]:
                w, ys, c = self.at(p[2])
                if w < 0.05 or c - ys < 0.05:
                    continue
                u = abs(p[0] - self.x0) / w; v = max(0.0, (p[1] - ys) / (c - ys))
                err += (min(u, 1.5) ** n + min(v, 1.5) ** n - 1.0) ** 2
            if best is None or err < best[0]:
                best = (err, n)
        return round(best[1], 2)

    def at(self, z):
        w = max(0.0, float(self.ws(z))); ys = float(self.ys(z)); c = max(float(self.cs(z)), ys + 0.005)
        return w, ys, c

    def point(self, z, phi, off=0.0):
        """the glass's outer surface at station z, angle phi (0 = the +x sill, pi = the -x sill), [off] along the
        section's outward normal"""
        w, ys, c = self.at(z)
        e = 2.0 / self.n
        cp, sp = math.cos(phi), math.sin(phi)
        x = self.x0 + w * math.copysign(abs(cp) ** e, cp)
        y = ys + (c - ys) * abs(sp) ** e
        p = np.array([x, y, z])
        if off:
            p = p + self.normal(z, phi) * off
        return p

    def normal(self, z, phi, h=1e-3):
        a = self.point(z, min(math.pi, phi + h)) - self.point(z, max(0.0, phi - h))
        b = self.point(min(self.front, z + h), phi) - self.point(max(self.rear, z - h), phi)
        n = np.cross(b, a)
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            return np.array([0.0, 1.0, 0.0])
        n /= ln
        mid = np.array([self.x0, self.at(z)[1] - 0.2, z])
        return n if n @ (self.point(z, phi) - mid) > 0 else -n

    def stations(self):
        k = max(8, int(round((self.front - self.rear) / DZ)))
        # denser at the ends where the canopy closes
        t = (1 - np.cos(np.linspace(0, math.pi, k + 1))) / 2
        return self.rear + (self.front - self.rear) * t


# ---------------------------------------------------------------- colours

def _face_colours(ac, pick, tex):
    """[(rgb, area)] of the faces pick(bone, index, corners, normal) selects, sampled at their uv centres"""
    H, W = tex.shape[:2]
    out = []
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        N = np.array(pm['normals'], float) * np.array([1, 1, -1]) if pm.get('normals') else None
        U = np.array(pm['uvs'], float)
        for i, poly in enumerate(pm['polys']):
            V = P[[v[0] for v in poly]]
            n = N[poly[0][1]] if N is not None and len(poly[0]) > 1 else np.zeros(3)
            if not pick(b['name'], i, V, n):
                continue
            uv = U[[v[2] for v in poly]].mean(0)
            px = tex[int(np.clip((1 - uv[1]) * H, 0, H - 1)), int(np.clip(uv[0] * W, 0, W - 1))]
            area = np.linalg.norm(np.cross(V[1] - V[0], V[-1] - V[0])) / (2 if len(V) == 3 else 1)
            if px[3] > 200 and area > 0:
                out.append((px[:3].astype(float), area))
    return out


def _clusters(cols, step=24):
    """colour clusters (quantized) by total area, largest first: [(mean rgb, area)]"""
    acc = {}
    for c, a in cols:
        k = tuple((c // step).astype(int))
        s = acc.setdefault(k, [np.zeros(3), 0.0])
        s[0] += c * a; s[1] += a
    return sorted(((s[0] / s[1], s[1]) for s in acc.values()), key=lambda t: -t[1])


def frame_rgb(ac, rm, tub, tex):
    """the canopy frame's paint: of the fuselage skin's main colours round the cockpit (by area, so a stripe or a
    marking loses to the base paint), the one the old canopy frame faces carry most; None if there is no skin"""
    E = tub.E
    def outer_skin(n, i, V, nrm):
        c = V.mean(0); side = np.sign(c[0] - E[0])
        # the outer skin beside and just below the rail: outside the canopy's width, facing out (as old_interior's
        # skin test), so the cockpit's own walls (black, or Soviet turquoise) do not count
        if not (L.is_interior_bone(n) and not n.endswith('__cockpit') and E[2] - 1.0 < c[2] < E[2] + 0.3 and
                tub.sill - 0.6 < c[1] < tub.sill + 0.02 and abs(c[0] - E[0]) > tub.Wg - 0.02 and
                i not in rm.get(n, ())):
            return False
        d = np.array([side, 0.0, 0.0])
        return ac.escapes(c + d * 0.01, d)        # nothing beyond it sideways: the outside of the airframe
    skin = _clusters(_face_colours(ac, outer_skin, tex))
    if not skin:
        return None
    top = [c for c, a in skin[:3] if a > 0.15 * skin[0][1]]
    frame = _face_colours(ac, lambda n, i, V, nrm: i in rm.get(n, ()), tex)
    if not frame:
        return tuple(int(v) for v in top[0])
    score = [sum(a for c, a in frame if np.abs(c - t).max() < 30) for t in top]
    best = top[int(np.argmax(score))] if max(score) > 0 else top[0]
    return tuple(int(v) for v in best)


def paint_rgb(ac, tub, tex):
    """the fuselage paint beside the cockpit: the texel colour of the outer skin faces just under the sill"""
    H, W = tex.shape[:2]
    cols = []
    for b in ac.bones:
        if not L.is_interior_bone(b['name']):
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            V = P[[v[0] for v in poly]]; c = V.mean(0)
            if abs(c[2] - tub.E[2]) < 0.6 and tub.sill - 0.35 < c[1] < tub.sill and abs(c[0] - tub.E[0]) > tub.Wg - 0.05:
                uv = U[[v[2] for v in poly]].mean(0)
                px = tex[int(np.clip((1 - uv[1]) * H, 0, H - 1)), int(np.clip(uv[0] * W, 0, W - 1))]
                if px[3] > 200:
                    cols.append(px[:3])
    if not cols:
        return (128, 132, 136)
    return tuple(int(v) for v in np.median(np.array(cols), 0))


# ---------------------------------------------------------------- frame

def sweep(m, path, lateral, up, section, uvs, closed=False, caps=True):
    """extrudes a closed section polygon [(l, u), ...] along path (k x 3) with per-point lateral and up directions;
    uvs[i] colours the side between section points i and i + 1"""
    k = len(path); ns = len(section)
    rings = [np.array([path[j] + lateral[j] * l + up[j] * u for l, u in section]) for j in range(k)]
    centre = lambda j: rings[j].mean(0)
    for j in range(k - 1 + (1 if closed else 0)):
        a, b = rings[j], rings[(j + 1) % k]
        for i in range(ns):
            i2 = (i + 1) % ns
            q = [a[i], a[i2], b[i2], b[i]]
            mid = (centre(j) + centre((j + 1) % k)) / 2
            m.flat(q, uvs[i], normal=np.mean(q, 0) - mid)
    if caps and not closed:
        m.flat(list(rings[0]), uvs[0], normal=path[0] - path[1])
        m.flat(list(rings[-1]), uvs[0], normal=path[-1] - path[-2])


def sweep_var(m, path, lateral, up, sections, uvs, closed=False):
    """sweep() with a section per path point (same corner count), for members that taper"""
    k = len(path); ns = len(sections[0])
    rings = [np.array([path[j] + lateral[j] * l + up[j] * u for l, u in sections[j]]) for j in range(k)]
    for j in range(k - 1 + (1 if closed else 0)):
        a, b = rings[j], rings[(j + 1) % k]
        mid = (a.mean(0) + b.mean(0)) / 2
        for i in range(ns):
            i2 = (i + 1) % ns
            q = [a[i], a[i2], b[i2], b[i]]
            m.flat(q, uvs[i], normal=np.mean(q, 0) - mid)
    if not closed:
        m.flat(list(rings[0]), uvs[0], normal=path[0] - path[1])
        m.flat(list(rings[-1]), uvs[0], normal=path[-1] - path[-2])


def build(ac, tub, shape, spec, sw, paint):
    m = L.Mesh()
    E = tub.E
    t_glass = spec.get('glass_mm', 20) / 1000.0
    outside = sw.uv(paint); inside = sw.uv(INTERIOR); seal = sw.uv(SEALANT)
    zs = shape.stations()

    # glass: outer and inner skins over the whole loft
    glass = []
    phis = np.linspace(0, math.pi, SEG + 1)
    for off in (0.0, -t_glass):
        grid = [[shape.point(z, p, off) for p in phis] for z in zs]
        for j in range(len(zs) - 1):
            for i in range(SEG):
                a, b, c, d = grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i]
                if np.linalg.norm(a - b) < 1e-5 and np.linalg.norm(d - c) < 1e-5:
                    continue
                glass += [a, b, c, a, c, d]

    # sill member: along both lower edges, and round the front tip unless the canopy has a windscreen bow there
    sh, sd, sw_in = spec.get('sill_height', 0.075), spec.get('sill_drop', 0.03), spec.get('sill_width', 0.05)
    rail = []
    # at a pointed end the rails stop where the canopy is still a few centimetres wide and a short bar closes them:
    # run on to the point, the member's section would fold over itself into a hook
    zr = [z for z in zs if shape.at(z)[0] > 0.035 or
          not ((z > shape.front - 0.3 and shape.ends[1] == 'point') or (z < shape.rear + 0.3 and shape.ends[0] == 'point'))]
    for side, phi in ((1, 0.0), (-1, math.pi)):
        pts = [shape.point(z, phi) for z in zr]
        rail.append(pts)
    # one loop round the canopy: rear (+x side) -> front -> rear (-x side) -> back across the rear; ends that
    # close to a point give coincident corners, which are dropped
    path = rail[0] + list(reversed(rail[1]))
    path = [p for k, p in enumerate(path) if k == 0 or np.linalg.norm(p - path[k - 1]) > 1e-4]
    if np.linalg.norm(path[-1] - path[0]) < 1e-4:
        path = path[:-1]
    path = np.array(path)
    ring = True
    lat, upv = [], []
    for j in range(len(path)):
        a, b = path[j - 1], path[(j + 1) % len(path)]
        tng = b - a; tng[1] = 0.0
        if np.linalg.norm(tng) < 1e-9:
            tng = np.array([0.0, 0.0, 1.0])
        tng /= np.linalg.norm(tng)
        l = np.cross(tng, [0.0, 1.0, 0.0])
        if l @ (path[j] - np.array([shape.x0, path[j][1], (shape.front + shape.rear) / 2])) < 0:
            l = -l
        lat.append(l); upv.append(np.array([0.0, 1.0, 0.0]))
    lat, upv = np.array(lat), np.array(upv)
    # a canopy that closes to a point at the front (a frameless forward transparency) is held there by a thin edge
    # frame: the member tapers from the cockpit forward to a third of its size at the tip
    taper = np.ones(len(path))
    if shape.ends[1] == 'point':
        z_start = E[2] + 0.25
        for j, p in enumerate(path):
            if p[2] > z_start:
                taper[j] = 1.0 - 0.67 * min(1.0, (p[2] - z_start) / max(shape.front - z_start, 1e-6))
    rings_sec = [[(0.006, -sd * k), (0.006, (sh - sd) * k), (-sw_in * k, (sh - sd) * k), (-sw_in * k, -sd * k)]
                 for k in taper]
    sweep_var(m, path, lat, upv, rings_sec, [outside, seal, inside, inside], closed=ring)
    # the transparency's sealed edge: a dark band on the glass just above the sill member
    band = spec.get('edge_band', 0.022)
    for phi0 in (0.0, math.pi):
        for j in range(len(zs) - 1):
            za, zb = zs[j], zs[j + 1]
            def lift(z, phi, h):
                # the point on the glass at height h above the sill, on this side
                w, ys, c = shape.at(z)
                if c - ys < 1e-4:
                    return shape.point(z, phi, 0.002)
                v = min(1.0, h / (c - ys))
                ph = math.asin(v ** (shape.n / 2.0))
                return shape.point(z, ph if phi < 1 else math.pi - ph, 0.002)
            h0, h1 = sh - sd, sh - sd + band
            q = [lift(za, phi0, h0), lift(zb, phi0, h0), lift(zb, phi0, h1), lift(za, phi0, h1)]
            if np.linalg.norm(q[0] - q[3]) > 1e-4:
                m.flat(q, seal, normal=shape.normal((za + zb) / 2, 0.15 if phi0 < 1 else math.pi - 0.15))

    # bows and the rear arch: bands across the glass, bracketing it (inside the inner skin to outside the outer)
    arches = [dict(b, z=E[2] + b['dz']) for b in spec.get('bows', [])]
    if spec.get('rear_arch', shape.ends[0] != 'point'):   # a canopy that tapers to a point on the spine has none
        arches.append({'z': shape.rear + spec.get('rear_arch_inset', 0.03), 'width': spec.get('rear_arch_width', 0.06),
                       'rear': True})
    for a in arches:
        z, hw = a['z'], a['width'] / 2
        w, ys, c = shape.at(z)
        if w < 0.05 or c - ys < 0.04 or not shape.rear <= z <= shape.front:
            continue                              # no glass standing there to frame
        ph = np.linspace(0, math.pi, SEG + 1)
        mid = np.array([shape.point(z, p) for p in ph])
        lat = np.array([shape.normal(z, p) for p in ph])
        upz = np.tile([0.0, 0.0, 1.0], (len(ph), 1))
        proud = a.get('proud', 0.012)
        sec = [(proud, -hw), (proud, hw), (-t_glass - 0.018, hw), (-t_glass - 0.018, -hw)]
        sweep(m, mid, lat, upz, sec, [outside, inside if a.get('rear') else outside, inside, outside])
    # windscreen posts: from the windscreen bow forward to the windscreen base, where the flat panels meet
    for ws in [b for b in spec.get('bows', []) if b.get('posts')]:
        z0 = E[2] + ws['dz']
        zz = [z for z in zs if z > z0] or [z0]
        zz = [z0] + zz
        # the posts stand at a fixed distance either side of the centreline (where the flat front panel meets the side
        # panels), running straight down to the windscreen base; they never converge across the view
        dx = ws.get('post_frac', 0.42) * shape.at(z0)[0]
        def phi_at(z, side):
            w, ys, c = shape.at(z)
            if w < 1e-4:
                return None
            u = min(dx / w, 0.97) ** (shape.n / 2.0)          # |cos phi| for that x on the superellipse
            a = math.acos(u)
            return a if side > 0 else math.pi - a
        for side in (1, -1):
            zz_s = [z for z in zz if phi_at(z, side) is not None]
            pts = np.array([shape.point(z, phi_at(z, side)) for z in zz_s])
            if len(pts) < 2:
                continue
            nrm = np.array([shape.normal(z, phi_at(z, side)) for z in zz_s])
            tng = np.gradient(pts, axis=0)
            side = np.cross(tng, nrm); side /= np.maximum(np.linalg.norm(side, axis=1, keepdims=True), 1e-9)
            hw = ws.get('post_width', 0.035) / 2
            sec = [(0.008, -hw), (0.008, hw), (-t_glass - 0.012, hw), (-t_glass - 0.012, -hw)]
            sweep(m, pts, nrm, side, sec, [outside, outside, inside, outside])
    # hinge blocks under the rear arch, and the actuator yoke on the centre bow (F-16 style: an inverted V inside)
    if spec.get('hinges', False):
        z = shape.rear + spec.get('hinge_inset', 0.05)
        for phi in (0.25, math.pi - 0.25):
            p = shape.point(z, phi, -t_glass - 0.03)
            m.box(p + [0, -0.02, 0], ([1, 0, 0], [0, 1, 0], [0, 0, 1]), (0.025, 0.035, 0.05), inside)
    for b in spec.get('bows', []):
        if b.get('yoke'):
            z = E[2] + b['dz']
            top = shape.point(z, math.pi / 2, -t_glass - 0.03)
            for side in (1, -1):
                foot = np.array([shape.x0 + side * 0.10, top[1] - 0.16, z - 0.10])
                d = top - foot; ln = np.linalg.norm(d); d /= ln
                ax2 = np.cross(d, [0, 0, 1.0]); ax2 /= np.linalg.norm(ax2); ax3 = np.cross(d, ax2)
                m.box((top + foot) / 2, (d, ax2, ax3), (ln / 2, 0.012, 0.012), inside)
    return m, glass


# Era defaults (cockpit_layout presets): transparency thickness, and whether the windscreen is a framed set of flat
# panels (posts where the panels meet) or one curved piece. Per-aircraft entries in canopies.json override them.
ERA_DEFAULTS = {
    'WWII_STEAM': {'glass_mm': 8, 'posts': 0.62, 'sill_height': 0.06, 'sill_width': 0.04},
    'COLD_WAR_STEAM': {'glass_mm': 15, 'posts': 0.62},
    'COLD_WAR_RADAR': {'glass_mm': 20, 'posts': 0.62},
    'GEN4_CRT': {'glass_mm': 25, 'posts': 0},
    'MODERN_GLASS': {'glass_mm': 25, 'posts': 0},
}
FLEET = json.load(open(os.path.join(HERE, '..', 'cockpit_models', 'fleet.json')))['aircraft']
PRESETS = json.load(open(os.path.join(HERE, '..', 'cockpit_layout', 'presets.json')))['aircraft']


def era_of(vid):
    spec = FLEET.get(vid, {})
    e = spec.get('era') or PRESETS.get(vid)
    return e if isinstance(e, str) else (e or {}).get('preset', 'COLD_WAR_RADAR')


def see_through_triangles(ac, tub, eyes, tex):
    """triangles (k x 3 x 3) of hull faces over the cockpit whose texture is see-through: the model's own glass"""
    out = []
    G = ac.glass.reshape(-1, 3)
    glo, ghi = (G.min(0), G.max(0)) if len(G) else (np.full(3, -1e9), np.full(3, 1e9))
    for b in ac.bones:
        if not L.is_interior_bone(b['name']):
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            V = P[[v[0] for v in poly]]
            c = V.mean(0)
            if c[1] < tub.sill - 0.05 or not any(abs(c[0] - E[0]) < 0.6 and abs(c[2] - E[2]) < 1.6 for E in eyes):
                continue
            if not (glo[0] - 0.1 < c[0] < ghi[0] + 0.1 and glo[2] - 0.6 < c[2] < ghi[2] + 0.6):
                continue                          # far from the fitted canopy: some other translucent part
            if S._see_through(tex, U[[v[2] for v in poly]]):
                for k in range(1, len(V) - 1):
                    out.append(V[[0, k, k + 1]])
    return np.array(out).reshape(-1, 3, 3)


def glass_groups(tris, eyes, sill, gap=0.08):
    """the old glass split into canopies: connected sheets (corners shared), merged when they touch (a windscreen
    pane against the hood); a group is a canopy when it stands over a crew eye. -> [(triangle mask, seat), ...]"""
    n = len(tris)
    parent = list(range(n))
    def root(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]; i = parent[i]
        return i
    owner = {}
    for i, t in enumerate(tris):
        for p in t:
            k = tuple(np.round(p, 3))
            if k in owner:
                parent[root(i)] = root(owner[k])
            else:
                owner[k] = i
    comps = {}
    for i in range(n):
        comps.setdefault(root(i), []).append(i)
    boxes = {r: (tris[ix].reshape(-1, 3).min(0), tris[ix].reshape(-1, 3).max(0)) for r, ix in comps.items()}
    # a gunsight's reflector plate (the modelled cockpit's): a small sheet below eye level ahead of a pilot. Kept.
    def sight(r):
        lo, hi = boxes[r]
        return len(comps[r]) <= 40 and any(hi[1] < E[1] + 0.05 and abs((lo[0] + hi[0]) / 2 - E[0]) < 0.2 and
                                           E[2] < lo[2] < E[2] + 1.2 for E in eyes)
    keys = [r for r in comps if not sight(r)]
    # merge sheets whose boxes touch and that stand above the sill (not nose glazing below it)
    merged = True
    while merged:
        merged = False
        for a in keys:
            for b in keys:
                if a >= b or root(a) == root(b):
                    continue
                (la, ha), (lb, hb) = boxes[root(a)], boxes[root(b)]
                if np.all(la - gap <= hb) and np.all(lb - gap <= ha) and ha[1] > sill and hb[1] > sill:
                    ra, rb = root(a), root(b)
                    parent[rb] = ra
                    boxes[ra] = (np.minimum(la, lb), np.maximum(ha, hb))
                    merged = True
    groups = {}
    for r in keys:
        groups.setdefault(root(r), []).extend(comps[r])
    out = []
    for ix in groups.values():
        P = tris[ix].reshape(-1, 3); lo, hi = P.min(0), P.max(0)
        for seat, E in enumerate(eyes):
            if lo[0] - 0.05 < E[0] < hi[0] + 0.05 and lo[2] - 0.05 < E[2] < hi[2] + 0.05 and hi[1] > E[1]:
                mask = np.zeros(n, bool); mask[ix] = True
                out.append((mask, seat))
                break
    return out


def spec_for(vid, ac, shape_eye, seat_eyes, hud):
    """canopies.json entry over the era defaults, with the windscreen bow placed at the HUD or gunsight"""
    base = dict(ERA_DEFAULTS.get(era_of(vid), ERA_DEFAULTS['COLD_WAR_RADAR']))
    spec = dict(base, **CONFIG['aircraft'].get(vid, {}))
    if 'bows' not in spec:
        bows = []
        if spec.get('windscreen', True):
            # the windscreen frame stands just behind the HUD combiner / gunsight glass, else over the panel lip
            if hud is not None:
                dz = float(hud['Center'][2]) - shape_eye[2] - 0.04
            else:
                dz = spec.get('windscreen_dz', 0.50)
            bows.append({'dz': round(dz, 3), 'width': spec.get('windscreen_width', 0.05),
                         'posts': spec.get('posts', 0)})
        for E in seat_eyes[1:]:                    # tandem: a bow between the seats
            if abs(E[2] - shape_eye[2]) > 0.5:
                bows.append({'dz': round(float((E[2] - shape_eye[2]) / 2), 3), 'width': 0.05})
        spec['bows'] = bows
    return spec


def apply(vid, dry=False):
    ac = L.Aircraft(vid)
    data_seats = ac.data['Seats']
    eyes = []
    for k in range(len(data_seats)):
        try:
            eyes.append(ac.eye(k))
        except (KeyError, TypeError):
            pass
    tex = np.asarray(Image.open(L.texture_path(vid)).convert('RGBA')).astype(np.int16)
    sw = L.Swatches(L.texture_path(vid))
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__canopy')]     # drop an earlier run's
    tub0 = L.Tub(ac, 0)
    # the model's own glass (faces with a see-through texture over the cockpit, such as a windscreen the fitted glass
    # never covered) is evidence of the canopy's shape too: it joins the old glass for the grouping and the fit, and is
    # never kept (strip.find removes those faces)
    n_fitted = len(ac.glass)
    model_glass = see_through_triangles(ac, tub0, eyes, tex)
    if len(model_glass):
        ac.glass = np.concatenate([ac.glass, model_glass])
    hud_mask = S.hud_glass_mask(ac.asset, ac.glass)
    groups = glass_groups(ac.glass, eyes, tub0.sill - 0.15)
    groups = [(mask & ~hud_mask, seat) for mask, seat in groups]
    if not groups:
        return ac, f'{vid}: no canopy glass over a crew eye; unchanged'
    replaced = np.zeros(len(ac.glass), bool)
    new_glass, reports, count = [], [], {}
    mesh = L.Mesh()
    hud = ac.asset.get('HeadUpDisplay')
    for mask, seat in groups:
        tub = L.Tub(ac, seat)
        old = ac.glass[mask]
        rm, _ = S.find(ac, tub, canopy=old, tex=tex)
        under = [E for E in eyes if old.reshape(-1, 3)[:, 2].min() - 0.05 < E[2] < old.reshape(-1, 3)[:, 2].max() + 0.05]
        spec = spec_for(vid, ac, tub.E, under, hud if seat == (hud or {}).get('Seat', 0) else None)
        shape = Shape(old, spec, tub.E, sill_ref=(tub.back_z, tub.Z + 0.1, tub.sill))
        paint = tuple(spec.get('paint_rgb') or frame_rgb(ac, rm, tub, tex) or paint_rgb(ac, tub, tex))
        for b in ac.bones:
            if b['name'] in rm:
                gone = rm[b['name']]
                b['poly_mesh']['polys'] = [p for i, p in enumerate(b['poly_mesh']['polys']) if i not in gone]
                count[b['name']] = count.get(b['name'], 0) + len(gone)
        m, glass = build(ac, tub, shape, spec, sw, paint)
        mesh.extend(m)
        new_glass += glass
        replaced |= mask
        reports.append(f'seat {seat}: z {shape.rear:.2f}..{shape.front:.2f}, fullness {shape.n}, '
                       f'{len(old)} old glass, bows {[b["dz"] for b in spec["bows"]]}, paint {paint}'
                       + (f', dropped stations {shape.dropped}' if shape.dropped else ''))
    source = max(count, key=count.get) if count else 'wreck_fuselage_1__hull'
    piece = source.split('__', 1)[0] if source.startswith('wreck_') else 'wreck_fuselage_1'
    pm = mesh.poly_mesh()
    hull = ac.bone('hull')
    bone = {'name': piece + '__canopy', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm}
    at = next((i for i, b in enumerate(ac.bones) if b['name'] == source), len(ac.bones) - 1) + 1
    ac.bones.insert(at, bone)
    replaced[n_fitted:] = True                     # model glass faces are never written to CanopyGlass
    kept = ac.glass[~replaced].reshape(-1, 3)
    tri = np.concatenate([kept, np.array(new_glass)]) if len(kept) else np.array(new_glass)
    ac.asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS',
                               'Triangles': [round(float(v), 4) + 0.0 for v in tri.reshape(-1)]}
    rep = (f'{vid} ({era_of(vid)}): removed {sum(count.values())} old frame faces; {"; ".join(reports)}; '
           f'kept {int((~replaced).sum())} other glass; {len(pm["polys"])} frame faces, colour error {sw.worst:.0f}')
    if not dry:
        with open(ac.geo_path, 'w', newline='\n') as f:
            json.dump(ac.geo, f, separators=(',', ':'))
        write_asset_lf(ac.asset_path, ac.asset)
    return ac, rep


def write_asset_lf(path, asset):
    """cockpit_lib.write_asset, with LF line ends on every platform (the repository's)"""
    L.write_asset(path, asset)
    with open(path, 'rb') as f:
        b = f.read()
    with open(path, 'wb') as f:
        f.write(b.replace(b'\r\n', b'\n'))


def main(argv):
    dry = '--dry-run' in argv
    ids = [a for a in argv if not a.startswith('--')]
    if '--fleet' in argv:
        ids = sorted((set(FLEET) | {'mig_29'}) - set(CONFIG.get('pending', [])))
    for vid in ids or sorted(CONFIG['aircraft']):
        try:
            print(apply(vid, dry)[1], flush=True)
        except Exception as e:                    # one aircraft's failure is reported, the rest carry on
            print(f'{vid}: FAILED {type(e).__name__}: {e}', flush=True)


if __name__ == '__main__':
    main(sys.argv[1:])
