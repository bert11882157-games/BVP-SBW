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
from scipy.interpolate import UnivariateSpline

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

    def __init__(self, glass, spec, E):
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
            z_, v_ = zs[use], v[use]
            zf = np.concatenate([[self.rear], z_, [self.front]])
            vv = np.concatenate([[end_v], v_, [tip_v]])
            w = np.concatenate([[20.0], np.ones(len(z_)), [20.0]])
            return UnivariateSpline(zf, vv, w=w, k=3, s=s_mul * len(z_) * smooth)
        # where the ends close: on the spine / nose at the given height, else at the last station's sill
        end_y = lambda: spec.get('rear_y', ys[ok][0])
        tip_y = lambda: spec.get('front_y', ys[ok][-1])
        for _ in range(len(zs) // 3):
            f_y = fit(ys, end_y(), tip_y(), 0.0004, ok)
            f_c = fit(cs, end_y() + 0.01, tip_y() + 0.01, 0.0002, ok)
            f_w = fit(ws, 0.0, 0.0, 0.0003, ok)
            res = np.maximum.reduce([np.abs(f_y(zs) - ys) / 0.03, np.abs(f_c(zs) - cs) / 0.03, np.abs(f_w(zs) - ws) / 0.04])
            res[~ok] = 0.0
            if res.max() <= 1.0:
                break
            ok[int(np.argmax(res))] = False
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
    for side, phi in ((1, 0.0), (-1, math.pi)):
        pts = [shape.point(z, phi) for z in zs]
        rail.append(pts)
    path = rail[0] + list(reversed(rail[1]))[1:]   # rear (+x side) -> front tip -> rear (-x side)
    path = np.array(path)
    ring = shape.at(shape.rear)[0] < 1e-3            # closes on the spine too: one loop round the canopy
    if ring:
        path = path[:-1]
    lat, upv = [], []
    for j in range(len(path)):
        if ring:
            a, b = path[j - 1], path[(j + 1) % len(path)]
        else:
            a, b = path[max(0, j - 1)], path[min(len(path) - 1, j + 1)]
        tng = b - a; tng[1] = 0.0
        if np.linalg.norm(tng) < 1e-9:
            tng = np.array([0.0, 0.0, 1.0])
        tng /= np.linalg.norm(tng)
        l = np.cross(tng, [0.0, 1.0, 0.0])
        if l @ (path[j] - np.array([shape.x0, path[j][1], (shape.front + shape.rear) / 2])) < 0:
            l = -l
        lat.append(l); upv.append(np.array([0.0, 1.0, 0.0]))
    lat, upv = np.array(lat), np.array(upv)
    section = [(0.006, -sd), (0.006, sh - sd), (-sw_in, sh - sd), (-sw_in, -sd)]
    sweep(m, path, lat, upv, section, [outside, seal, inside, inside], closed=ring)
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
    if spec.get('rear_arch', True):              # a canopy that tapers to a point on the spine has none
        arches.append({'z': shape.rear + spec.get('rear_arch_inset', 0.05), 'width': spec.get('rear_arch_width', 0.06),
                       'rear': True})
    for a in arches:
        z, hw = a['z'], a['width'] / 2
        ph = np.linspace(0, math.pi, SEG + 1)
        mid = np.array([shape.point(z, p) for p in ph])
        lat = np.array([shape.normal(z, p) for p in ph])
        upz = np.tile([0.0, 0.0, 1.0], (len(ph), 1))
        proud = a.get('proud', 0.012)
        sec = [(proud, -hw), (proud, hw), (-t_glass - 0.018, hw), (-t_glass - 0.018, -hw)]
        sweep(m, mid, lat, upz, sec, [outside, inside if a.get('rear') else outside, inside, outside])
    # hinge blocks under the rear arch, and the actuator yoke on the centre bow (F-16 style: an inverted V inside)
    if spec.get('hinges', True):
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


def apply(vid, dry=False):
    spec = CONFIG['aircraft'][vid]
    ac = L.Aircraft(vid)
    tub = L.Tub(ac, spec.get('seat', 0))
    tex = np.asarray(Image.open(L.texture_path(vid)).convert('RGBA')).astype(np.int16)
    sw = L.Swatches(L.texture_path(vid))
    # drop what an earlier run added
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__canopy')]
    rm, keep = S.find(ac, tub)
    old = ac.glass[~keep]
    shape = Shape(old, spec, tub.E)
    paint = tuple(spec.get('paint_rgb') or paint_rgb(ac, tub, tex))
    count = {}
    for b in ac.bones:
        if b['name'] in rm:
            gone = rm[b['name']]
            b['poly_mesh']['polys'] = [p for i, p in enumerate(b['poly_mesh']['polys']) if i not in gone]
            count[b['name']] = len(gone)
    m, glass = build(ac, tub, shape, spec, sw, paint)
    source = max(count, key=count.get) if count else 'wreck_fuselage_1__hull'
    piece = source.split('__', 1)[0] if source.startswith('wreck_') else 'wreck_fuselage_1'
    pm = m.poly_mesh()
    hull = ac.bone('hull')
    bone = {'name': piece + '__canopy', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm}
    at = next((i for i, b in enumerate(ac.bones) if b['name'] == source), len(ac.bones) - 1) + 1
    ac.bones.insert(at, bone)
    kept = ac.glass[keep].reshape(-1, 3)
    tri = np.concatenate([kept, np.array(glass)]) if len(kept) else np.array(glass)
    ac.asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS',
                               'Triangles': [round(float(v), 4) + 0.0 for v in tri.reshape(-1)]}
    rep = (f'{vid}: removed {sum(count.values())} old frame faces {count}, {len(old)} old glass triangles; '
           f'canopy z {shape.rear:.2f}..{shape.front:.2f}, fullness {shape.n}, paint {paint}; '
           f'{len(pm["polys"])} frame faces, {len(glass) // 3} glass triangles, colour error {sw.worst:.0f}')
    if not dry:
        with open(ac.geo_path, 'w') as f:
            json.dump(ac.geo, f, separators=(',', ':'))
        L.write_asset(ac.asset_path, ac.asset)
    return ac, rep


def main(argv):
    dry = '--dry-run' in argv
    ids = [a for a in argv if not a.startswith('--')] or sorted(CONFIG['aircraft'])
    for vid in ids:
        print(apply(vid, dry)[1])


if __name__ == '__main__':
    main(sys.argv[1:])
