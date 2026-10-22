"""Building blocks for the hand-made canopies (one file per aircraft, canopy_<vid>.py): the canopy's shape is given by
hand as cross-sections (the right half of each, from the sill up over the top, metres from the pilot's eye), and
its structure as frame members placed by hand: transverse bows (the windscreen arch, the canopy bows) at stations
along it, longitudinal members (windscreen posts, the sill rails, a spine) along a position across the sections.
From those this module makes the glass (CanopyGlass, as the game draws it) and the frames (a canopy bone), and
removes the model's old canopy frame faces (canopy_models/strip.py finds them against the old glass).

Nothing here measures or guesses a shape: glass_sections.py prints the old glass's sections for reference, and the
aircraft's file holds the shape and the frame layout it settles on.
"""
import json, math, os, subprocess, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, 'authored'))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_models'))
import cockpit_lib as L      # noqa: E402
import handbuilt as H        # noqa: E402
import free_atlas as FA      # noqa: E402
import lib as A              # noqa: E402

N = 18                        # points per resampled half-section, sill to top
_FRAMES = []                  # every bow and member placed this run: the glass is cut round them (cut_glass)
_GLASS = []                   # every glass patch asked for this run
_EXTRA = []                   # free-standing glass (extra_glass): not cut by the frames, not used to clear old faces


def extra_glass(points):
    """glass triangles (flat list of vehicle-local points) that stand apart from the canopy's skin, e.g. an armoured
    plate inside the windscreen; they go into CanopyGlass as they are"""
    _EXTRA.extend(points)
BASE = 'be357476'             # the commit holding the models' original canopy glass


def original_glass(vid):
    """the model's original CanopyGlass triangles (k x 3 x 3), from git"""
    path = f'bvp/src/generated/resources/assets/berts_vehicle_pack/sbw/vehicles/{vid}.json'
    git = os.environ.get('BVP_GIT', r'C:\Users\udstu\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\git\cmd\git.exe')
    repo = os.path.normpath(os.path.join(HERE, '..', '..'))
    txt = subprocess.run([git, '-C', repo, 'show', f'{BASE}:{path}'], capture_output=True, check=True).stdout
    cg = json.loads(txt.decode('utf-8')).get('CanopyGlass') or {}
    return np.array(cg.get('Triangles', []), float).reshape(-1, 3, 3)


class Shape:
    """the canopy as cross-sections: [(z, [(x, y), ...]), ...] (eye-relative, right half from the sill up to the top
    centre at x = 0), resampled by arc length to N points; point(z, s) on the surface, s = 0 at the sill, 1 at the top"""

    def __init__(self, sections):
        self.z = np.array([z for z, _ in sections], float)
        self.P = np.array([self.resample(np.array(p, float)) for _, p in sections])

    @staticmethod
    def resample(p):
        d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]
        t = np.linspace(0, d[-1], N)
        return np.c_[np.interp(t, d, p[:, 0]), np.interp(t, d, p[:, 1])]

    def section(self, z):
        z = float(np.clip(z, self.z[0], self.z[-1]))
        i = int(np.clip(np.searchsorted(self.z, z) - 1, 0, len(self.z) - 2))
        f = (z - self.z[i]) / max(1e-9, self.z[i + 1] - self.z[i])
        return self.P[i] * (1 - f) + self.P[i + 1] * f

    def point(self, z, s, side=1):
        sec = self.section(z)
        k = s * (N - 1)
        i = int(min(N - 2, math.floor(k)))
        f = k - i
        x, y = sec[i] * (1 - f) + sec[i + 1] * f
        return np.array([x * side, y, z])

    def arclen(self, z):
        """the half-section's length at z, sill to top (metres per unit of s)"""
        sec = self.section(z)
        return float(np.linalg.norm(np.diff(sec, axis=0), axis=1).sum())

    def s_at_x(self, z, x):
        """the position s across the section at z where the surface stands x out from the centre line (s from the
        sill up, the first point at or inside x): for posts and members set by their width, not by arc length"""
        sec = self.section(z)
        for i in range(N - 1):
            a, b = sec[i][0], sec[i + 1][0]
            if (a - x) * (b - x) <= 0 and a != b:
                return (i + (a - x) / (a - b)) / (N - 1)
        return 1.0 if sec[0][0] > x else 0.0

    def normal(self, z, s, side=1):
        """outward normal of the surface at (z, s): across the section, and along it"""
        e = 1e-3
        a = self.point(z, min(1, s + 0.02), side) - self.point(z, max(0, s - 0.02), side)
        b = self.point(min(self.z[-1], z + e), s, side) - self.point(max(self.z[0], z - e), s, side)
        n = np.cross(b, a) * side
        n = n / (np.linalg.norm(n) or 1)
        p = self.point(z, s, side)
        if n @ np.array([p[0], p[1] + 0.2, 0]) < 0:      # away from the inside (a point under the top, on the axis)
            n = -n
        return n


def glass_triangles(shape, E, z0, z1, s0=0.0, s1=1.0, nz=12, ns=10, sides=(1, -1)):
    """the glass over [z0, z1] x [s0, s1] of the shape, vehicle-local, both halves; flat list of points (run() then
    remakes it with the frames cut out of it: cut_glass)"""
    _GLASS.append((shape, z0, z1, s0, s1, nz, ns, tuple(sides)))
    out = []
    zs = np.linspace(z0, z1, nz + 1)
    ss = np.linspace(s0, s1, ns + 1)
    for side in sides:
        for i in range(nz):
            for j in range(ns):
                a, b = shape.point(zs[i], ss[j], side), shape.point(zs[i + 1], ss[j], side)
                c, d = shape.point(zs[i + 1], ss[j + 1], side), shape.point(zs[i], ss[j + 1], side)
                out += [a + E, b + E, c + E, a + E, c + E, d + E]
    return out


def bow(m, shape, E, z, width, depth, rect_out, rect_in, s0=0.0, s1=1.0, proud=0.004):
    """a transverse frame at station z over the shape from s0 to s1 on both sides (one arch over the top), [width]
    along the aircraft, [depth] deep inward, standing [proud] outside the glass"""
    _FRAMES.append((shape, 'bow', z, width))
    ss = np.linspace(s0, s1, 12)
    for side in (1, -1):
        for zz in ((z - width / 2, z + width / 2),):
            for j in range(len(ss) - 1):
                q = []
                for s in (ss[j], ss[j + 1]):
                    for zc in zz:
                        p = shape.point(zc, s, side); n = shape.normal(zc, s, side)
                        q.append((p + n * proud + E, p - n * depth + E))
                (a0, a1), (b0, b1), (c0, c1), (d0, d1) = q          # (s_j, z-), (s_j, z+), (s_j+1, z-), (s_j+1, z+)
                m.flat([a0, b0, d0, c0], rect_out, toward=(a0 + d0) / 2 + (a0 - a1))
                m.flat([a1, b1, d1, c1], rect_in, toward=(a1 + d1) / 2 + (a1 - a0))
                m.flat([a0, c0, c1, a1], rect_in, toward=(a0 + c1) / 2 + (a0 - b0))
                m.flat([b0, d0, d1, b1], rect_in, toward=(b0 + d1) / 2 + (b0 - a0))


def member(m, shape, E, s, z0, z1, width, depth, rect_out, rect_in, sides=(1, -1), proud=0.004, steps=10):
    """a longitudinal frame along the shape at position s from z0 to z1, [width] across the surface (s may be a
    function of z, e.g. lambda z: shape.s_at_x(z, 0.12) for a post standing a set width out)"""
    _FRAMES.append((shape, 'member', s, z0, z1, width))
    zs = np.linspace(z0, z1, steps + 1)
    for side in sides:
        for i in range(steps):
            q = []
            for zc in (zs[i], zs[i + 1]):
                sc = s(zc) if callable(s) else s
                p = shape.point(zc, sc, side); n = shape.normal(zc, sc, side)
                t = shape.point(zc, min(1, sc + 0.02), side) - shape.point(zc, max(0, sc - 0.02), side)
                t = t / (np.linalg.norm(t) or 1)
                for o in (-1, 1):
                    q.append((p + t * o * width / 2 + n * proud + E, p + t * o * width / 2 - n * depth + E))
            (a0, a1), (b0, b1), (c0, c1), (d0, d1) = q      # (z_i, -), (z_i, +), (z_i+1, -), (z_i+1, +)
            m.flat([a0, b0, d0, c0], rect_out, toward=(a0 + d0) / 2 + (a0 - a1))
            m.flat([a1, b1, d1, c1], rect_in, toward=(a1 + d1) / 2 + (a1 - a0))
            m.flat([a0, c0, c1, a1], rect_in, toward=(a0 + c1) / 2 + (a0 - b0))
            m.flat([b0, d0, d1, b1], rect_in, toward=(b0 + d1) / 2 + (b0 - a0))


def cut_glass(E, pad=0.006):
    """the glass patches asked for, remade with every frame cut out of them: the glass stops at each bow's and
    member's edge (less [pad], as the frames' faces lean out over their footprint where the glass curves) instead of
    running on under it, so no frame face lies a few millimetres over the glass (they flicker through each other)"""
    out = []
    for sh, z0, z1, s0, s1, nz, ns, sides in _GLASS:
        frames = [f for f in _FRAMES if f[0] is sh]
        bows = [(f[2] - f[3] / 2 - pad, f[2] + f[3] / 2 + pad) for f in frames if f[1] == 'bow']
        mems = [f for f in frames if f[1] == 'member']
        zb = set(np.linspace(z0, z1, nz + 1).tolist())
        for a, b in bows:
            zb |= {a, b}
        for f in mems:
            zb |= {f[3] - pad, f[4] + pad}
        zb = sorted(z for z in zb if z0 - 1e-9 <= z <= z1 + 1e-9)
        for i in range(len(zb) - 1):
            za, zc = zb[i], zb[i + 1]
            if zc - za < 1e-6:
                continue
            zm = (za + zc) / 2
            if any(a - 1e-6 <= zm <= b + 1e-6 for a, b in bows):
                continue                                   # under a bow: no glass
            live = [f for f in mems if f[3] - pad - 1e-6 <= zm <= f[4] + pad + 1e-6]
            def mid(f, z):
                return f[2](z) if callable(f[2]) else f[2]
            live.sort(key=lambda f: mid(f, zm))
            def bounds(z):
                L = sh.arclen(z) or 1.0
                b, prev = [], s0
                for f in live:
                    h = (f[5] / 2 + pad) / L
                    lo, hi = mid(f, z) - h, mid(f, z) + h
                    b.append((prev, max(prev, min(lo, s1))))
                    prev = min(s1, max(prev, hi))
                b.append((prev, s1))
                return b
            ba, bc = bounds(za), bounds(zc)
            for (la, ha), (lc, hc) in zip(ba, bc):
                w = max(ha - la, hc - lc)
                if w <= 1e-4:
                    continue
                k = max(1, int(round(ns * w / max(1e-6, s1 - s0))))
                for j in range(k):
                    sa0, sa1 = la + (ha - la) * j / k, la + (ha - la) * (j + 1) / k
                    sc0, sc1 = lc + (hc - lc) * j / k, lc + (hc - lc) * (j + 1) / k
                    for side in sides:
                        a, b = sh.point(za, sa0, side), sh.point(zc, sc0, side)
                        c, d = sh.point(zc, sc1, side), sh.point(za, sa1, side)
                        out += [a + E, b + E, c + E, a + E, c + E, d + E]
    return out


def clear_under_glass(ac, glass_pts, E, gap=0.015):
    """the model's own faces lying within [gap] of the new glass, parallel to it and under it (old canopy panes and
    plates): seen through the glass they flicker against it, so they go"""
    G = np.array(glass_pts, float).reshape(-1, 3, 3)
    if not len(G):
        return 0
    n = np.cross(G[:, 1] - G[:, 0], G[:, 2] - G[:, 0])
    ln = np.linalg.norm(n, axis=1)
    G, n = G[ln > 1e-10], n[ln > 1e-10] / ln[ln > 1e-10, None]
    glo, ghi = G.min(1) - gap, G.max(1) + gap
    def overlap2d(a, b):
        def shrink(t):
            c = t.mean(0)
            return c + (t - c) * 0.96
        a, b = shrink(a), shrink(b)
        for tt in (a, b):
            for i in range(len(tt)):
                e = tt[(i + 1) % len(tt)] - tt[i]
                nn = np.array([-e[1], e[0]])
                pa, pb = a @ nn, b @ nn
                if pa.max() <= pb.min() or pb.max() <= pa.min():
                    return False
        return True
    total = 0
    for bone in ac.bones:
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys') or bone['name'].endswith('__cockpit') or bone['name'].endswith('__canopy'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        keep = []
        for poly in pm['polys']:
            V = P[[v[0] for v in poly]] if poly else None
            hit = False
            if V is not None and len(V) >= 3:
                fn = np.cross(V[1] - V[0], V[2] - V[0])
                if np.linalg.norm(fn) > 1e-10:
                    fn = fn / np.linalg.norm(fn)
                    lo, hi = V.min(0), V.max(0)
                    for i in np.where(np.all(glo <= hi, axis=1) & np.all(ghi >= lo, axis=1))[0]:
                        if abs(n[i] @ fn) < math.cos(math.radians(8)) or np.abs((V - G[i][0]) @ n[i]).max() > gap:
                            continue
                        ax = np.argsort(np.abs(n[i]))[:2]
                        if overlap2d(G[i][:, ax], V[:, ax]):
                            hit = True
                            break
            if hit:
                total += 1
            else:
                keep.append(poly)
        pm['polys'] = keep
    return total


def atlas_for(vid):
    """an atlas in the texture's free area, the cockpit's tiles counted as used"""
    at = A.Atlas.__new__(A.Atlas)
    (at.TW, at.TH), rects = FA.free_rects(vid, 256, skip=())
    at.vid = vid
    at.areas = [tuple(int(v) for v in r) for r in rects]
    at.free = list(at.areas)
    at.tiles = []
    return at


def at_canopy(ac, glass_pts, E, near=0.07, below=0.05, old_glass=None):
    """keep(bone, poly) -> True for a face the canopy step may take: within [near] of the new glass, or inside the
    new canopy's volume (under its surface, no more than [below] under the sill), or a piece of the old canopy: near
    the old glass, standing over the new canopy's sill line within its length. Anything else is the model's exterior
    skin (the nose deck ahead of the windscreen, the spine behind the canopy, the fuselage sides) and stays."""
    from matplotlib.path import Path
    G = np.array(glass_pts, float).reshape(-1, 3, 3) - E if len(glass_pts) else np.zeros((0, 3, 3))
    pts = np.concatenate([G.reshape(-1, 3), G.mean(1)]) if len(G) else np.zeros((0, 3))
    shapes = [(sh, z0, z1) for sh, z0, z1, *_ in _GLASS]
    O = (np.asarray(old_glass, float).reshape(-1, 3) - E) if old_glass is not None and len(old_glass) else np.zeros((0, 3))
    def ok(c):
        if len(pts) and np.min(np.linalg.norm(pts - c, axis=1)) < near:
            return True
        if len(O) and np.min(np.linalg.norm(O - c, axis=1)) < 0.10:
            for sh, z0, z1 in shapes:
                if z0 - 0.05 <= c[2] <= z1 + 0.05 and c[1] > sh.section(c[2])[0][1] + 0.03:
                    return True
        for sh, z0, z1 in shapes:
            if z0 <= c[2] <= z1:
                sec = sh.section(c[2])
                if c[1] >= sec[0][1] - below and Path(np.r_[sec, sec[::-1] * np.array([-1, 1])]).contains_point((c[0], c[1])):
                    return True
        return False
    return ok


def install(ac, mesh, source, glass_pts, old_glass):
    """the old canopy frame faces go (strip.find against the old glass), the frames into <piece>__canopy (parent
    hull), the new glass into CanopyGlass"""
    import strip as S
    from PIL import Image
    ac.glass = old_glass
    tub = L.Tub(ac, 0)
    tex = np.array(Image.open(L.texture_path(ac.vid)).convert('RGBA'))
    rm = S.find(ac, tub, canopy=old_glass, tex=tex)[0] if len(old_glass) else {}   # no old glass: clear_skin does it
    E = ac.eye(0)
    ok = at_canopy(ac, glass_pts, E, old_glass=old_glass)
    for b in ac.bones:                                  # only what lies at the new canopy goes; the skin stays
        if b['name'] in rm:
            P = np.array(b['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0 - E
            polys = b['poly_mesh']['polys']
            rm[b['name']] = {i for i in rm[b['name']] if polys[i] and ok(P[[v[0] for v in polys[i]]].mean(0))}
    n = 0
    for b in ac.bones:
        if b['name'] in rm:
            pm = b['poly_mesh']
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in rm[b['name']]]
            n += len(rm[b['name']])
    pm = mesh.poly_mesh()
    piece = source.split('__', 1)[0]
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__canopy')]
    hull = ac.bone('hull')
    at = next(i for i, b in enumerate(ac.bones) if b['name'] == source) + 1
    ac.bones.insert(at, {'name': piece + '__canopy', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    ac.asset.pop('CanopyGlass', None)
    if glass_pts:
        L.add_glass(ac.asset, [list(map(float, p)) for p in glass_pts])
    return n, len(pm['polys'])


def periscope(m, shape, E, z, out, dark):
    """the rear-view periscope housing on the canopy's top at station z, sitting on the glass"""
    top = shape.point(z, 1.0) + E
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    m.box(top + np.array([0, 0.0, 0]), (X, Y, Z), (0.028, 0.022, 0.06), out)          # its foot sunk 22 mm through the glass
    m.box(top + np.array([0, 0.006, -0.0615]), (X, Y, Z), (0.02, 0.014, 0.002), dark)


def skin(m, shape, E, z0, z1, rect, nz=6, ns=10):
    """a solid (metal) part of the canopy over [z0, z1], both halves, facing out: e.g. a faired rear section"""
    zs, ss = np.linspace(z0, z1, nz + 1), np.linspace(0, 1, ns + 1)
    for side in (1, -1):
        for i in range(nz):
            for j in range(ns):
                q = [shape.point(zs[a], ss[b], side) + E for a, b in ((i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1))]
                c = sum(q) / 4
                m.flat(q, rect, toward=c + shape.normal((zs[i] + zs[i + 1]) / 2, (ss[j] + ss[j + 1]) / 2, side))


def deck(m, shape, E, z0, z1, rect, drop=0.008, reach=0.015, skirt=0.15, front=0.06, x_in=0.0, nz=8):
    """a closed tub under the windscreen over [z0, z1]: a floor just under the sill line reaching [reach] past the sill
    (under the fuselage skin), skirts down from its outer edges and a front wall [front] past z1, all facing in and up:
    what shows through the glass ahead of the panel's coaming is an enclosed deck, not the open nose bay under it.
    With x_in > 0 only the outer bands (|x| > x_in) are floored, beside a panel that stands in the middle."""
    zs = np.linspace(z0, z1 + front, nz + 1)
    edge = []
    for z in zs:
        sx, sy = shape.section(min(z, z1))[0]
        edge.append((z, sx + reach, sy - drop))
    for i in range(nz):
        (za, xa, ya), (zb, xb, yb) = edge[i], edge[i + 1]
        if x_in > 0:
            for s in (1, -1):
                m.flat([E + [s * x_in, ya, za], E + [s * xa, ya, za], E + [s * xb, yb, zb], E + [s * x_in, yb, zb]], rect,
                       normal=[0, 1, 0])
        else:
            m.flat([E + [-xa, ya, za], E + [xa, ya, za], E + [xb, yb, zb], E + [-xb, yb, zb]], rect, normal=[0, 1, 0])
        for s in (1, -1):                                   # the skirts, down from the floor's outer edges
            m.flat([E + [s * xa, ya, za], E + [s * xb, yb, zb], E + [s * xb, yb - skirt, zb], E + [s * xa, ya - skirt, za]],
                   rect, normal=[-s, 0, 0])
    if front <= 0:
        return
    (z, x, y) = edge[-1]                                    # the front wall
    m.flat([E + [-x, y, z], E + [x, y, z], E + [x, y - skirt, z], E + [-x, y - skirt, z]], rect, normal=[0, 0, -1])


def shell(m, shape, E, z0, z1, rect, sight, inset=0.03, nz=10):
    """one smooth surface under a windscreen over [z0, z1]: the glass's own sections set [inset] inside it, from sill to
    sill, its top held under the pilot's sight line over the coaming (y = sight(z)), facing up and out: under the glass
    the nose reads as one homogeneous deck instead of the parts and old skin below it"""
    zs = np.linspace(z0, z1, nz + 1)
    rows = []
    for z in zs:
        row = []
        for x, y in shape.section(z):
            row.append((max(x - inset, 0.0), min(y - inset * 0.5, sight(z))))
        row[0] = (row[0][0], shape.section(z)[0][1] - 0.004)     # (its edge just under the sill line, on the skin)
        rows.append((z, row))
    for (za, ra), (zb, rb) in zip(rows[:-1], rows[1:]):
        for j in range(len(ra) - 1):
            for s in (1, -1):
                q = [E + [s * ra[j][0], ra[j][1], za], E + [s * ra[j + 1][0], ra[j + 1][1], za],
                     E + [s * rb[j + 1][0], rb[j + 1][1], zb], E + [s * rb[j][0], rb[j][1], zb]]
                c = sum(q) / 4
                m.flat(q, rect, toward=c + np.array([s * 0.3, 1.0, 0.0]))


def clear_box(ac, E, lo, hi):
    """the model's own faces (not the new cockpit or canopy) whose centres lie in the eye-relative box lo..hi"""
    n = 0
    lo, hi = np.array(lo, float), np.array(hi, float)
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'].endswith('__cockpit') or b['name'].endswith('__canopy'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0 - E
        keep = [p for p in pm['polys'] if not p or not np.all((lo <= P[[v[0] for v in p]].mean(0)) & (P[[v[0] for v in p]].mean(0) <= hi))]
        n += len(pm['polys']) - len(keep)
        pm['polys'] = keep
    return n


def clear_skin(ac, shape, E, z0, z1, depth=0.07, above=0.03, outside=0.0):
    """the model's own faces left against the new canopy (old frame slabs and side plates strip.find missed): every
    face of a model bone whose centre lies within [depth] of the canopy's surface, inside or out, between z0 and z1 and
    more than [above] over the sill (and, with [outside], anything up to that far outside it: an old canopy standing clear of the new one); returns how many went"""
    from matplotlib.path import Path
    n = 0
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'].endswith('__cockpit') or b['name'].endswith('__canopy'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0 - E
        keep = []
        for poly in pm['polys']:
            c = P[[v[0] for v in poly]].mean(0) if poly else None
            if c is None or not z0 <= c[2] <= z1:
                keep.append(poly)
                continue
            sec = shape.section(c[2])
            if c[1] < sec[0][1] + above:
                keep.append(poly)
                continue
            d = np.min(np.hypot(sec[:, 0] - abs(c[0]), sec[:, 1] - c[1]))
            outline = np.r_[sec, sec[::-1] * np.array([-1, 1])]
            inside = Path(outline).contains_point((c[0], c[1]))
            if (inside and d < depth * 1.6) or (not inside and d < max(0.025, outside)):   # outside: only what hugs it
                n += 1
            else:
                keep.append(poly)
        pm['polys'] = keep
    return n


def mirrors(m, shape, E, z, depth, rect_back, rect_glass, at=(1.0, 0.62), size=(0.11, 0.035)):
    """rear-view mirrors on the inside of a bow at station z: one at the top (s = 1) and a pair at s = at[1] on the
    sides, standing a little inside the bow, their glass toward the pilot"""
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    spots = [(at[0], 1)] + [(at[1], s) for s in (1, -1)]
    for s_, side in spots:
        p = shape.point(z, s_, side); n = shape.normal(z, s_, side)
        c = p - n * (depth + 0.016) + E
        t = shape.point(z, min(1, s_ + 0.02), side) - shape.point(z, max(0, s_ - 0.02), side)
        t = t / (np.linalg.norm(t) or 1)
        w = np.cross(n, Z); w = w / (np.linalg.norm(w) or 1)
        m.box(c, (w, np.cross(Z, w), Z), (size[0] / 2, size[1] / 2, 0.006), rect_back)
        m.box(c - Z * 0.0065, (w, np.cross(Z, w), Z), (size[0] / 2 - 0.006, size[1] / 2 - 0.005, 0.0008), rect_glass)


def run(vid, source, build, argv, colour=None, clear=None, boxes=()):
    """the aircraft file's canopy put in: build(atlas, E, skin) -> (mesh, glass points); the frames in [colour], or
    the spine's own paint; [clear] = (shape, z0, z1[, outside]) also clears the model's leftover faces against that canopy"""
    ac = L.Aircraft(vid)
    E = ac.eye(0)
    old = original_glass(vid)
    atlas = atlas_for(vid)
    _FRAMES.clear(); _GLASS.clear(); _EXTRA.clear()
    m, glass = build(atlas, E, colour or frame_colour(ac))
    if _GLASS:
        glass = cut_glass(E)                             # the frames cut out of the glass
    n_old, n_new = install(ac, m, source, glass + _EXTRA, old)
    n_old += clear_under_glass(ac, glass, E)
    if clear:
        n_old += clear_skin(ac, clear[0], E, clear[1], clear[2], outside=clear[3] if len(clear) > 3 else 0.0)
    for lo, hi in boxes:                                 # old frame pieces standing clear of the glass
        n_old += clear_box(ac, E, lo, hi)
    print(f'{vid} canopy: {n_old} old frame faces removed; {n_new} frame faces, {len(glass) // 3} glass triangles')
    if '--dry-run' not in argv:
        H.write(ac, atlas)


def frame_colour(ac, rm_faces=None):
    """the skin colour round the canopy: the commonest texel colour of the spine behind it (outside paint, not the
    cockpit interior)"""
    from PIL import Image
    from collections import Counter
    tex = np.array(Image.open(L.texture_path(ac.vid)).convert('RGB'))
    E = ac.eye(0)
    c = Counter()
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or not L.is_interior_bone(b['name']):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            if not poly:
                continue
            V = P[[v[0] for v in poly]] - E
            cc = V.mean(0)
            if abs(cc[0]) < 0.25 and -0.1 < cc[1] < 0.5 and -1.8 < cc[2] < -0.6:
                u = U[[v[2] for v in poly]].mean(0)
                px = tex[int(np.clip((1 - u[1]) * tex.shape[0], 0, tex.shape[0] - 1)), int(np.clip(u[0] * tex.shape[1], 0, tex.shape[1] - 1))]
                c[tuple(int(v) // 8 * 8 for v in px)] += 1
    return c.most_common(1)[0][0] if c else (110, 116, 118)
