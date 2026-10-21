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
    """the glass over [z0, z1] x [s0, s1] of the shape, vehicle-local, both halves; flat list of points"""
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


def atlas_for(vid):
    """an atlas in the texture's free area, the cockpit's tiles counted as used"""
    at = A.Atlas.__new__(A.Atlas)
    (at.TW, at.TH), rects = FA.free_rects(vid, 256, skip=())
    at.vid = vid
    at.areas = [tuple(int(v) for v in r) for r in rects]
    at.free = list(at.areas)
    at.tiles = []
    return at


def install(ac, mesh, source, glass_pts, old_glass):
    """the old canopy frame faces go (strip.find against the old glass), the frames into <piece>__canopy (parent
    hull), the new glass into CanopyGlass"""
    import strip as S
    from PIL import Image
    ac.glass = old_glass
    tub = L.Tub(ac, 0)
    tex = np.array(Image.open(L.texture_path(ac.vid)).convert('RGBA'))
    rm = S.find(ac, tub, canopy=old_glass, tex=tex)[0] if len(old_glass) else {}   # no old glass: clear_skin does it
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
    m.box(top + np.array([0, 0.012, 0]), (X, Y, Z), (0.028, 0.022, 0.06), out)
    m.box(top + np.array([0, 0.014, -0.0615]), (X, Y, Z), (0.02, 0.014, 0.002), dark)


def skin(m, shape, E, z0, z1, rect, nz=6, ns=10):
    """a solid (metal) part of the canopy over [z0, z1], both halves, facing out: e.g. a faired rear section"""
    zs, ss = np.linspace(z0, z1, nz + 1), np.linspace(0, 1, ns + 1)
    for side in (1, -1):
        for i in range(nz):
            for j in range(ns):
                q = [shape.point(zs[a], ss[b], side) + E for a, b in ((i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1))]
                c = sum(q) / 4
                m.flat(q, rect, toward=c + shape.normal((zs[i] + zs[i + 1]) / 2, (ss[j] + ss[j + 1]) / 2, side))


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
            if d < depth or (inside and d < depth * 1.6) or (not inside and d < outside):
                n += 1
            else:
                keep.append(poly)
        pm['polys'] = keep
    return n


def run(vid, source, build, argv, colour=None, clear=None, boxes=()):
    """the aircraft file's canopy put in: build(atlas, E, skin) -> (mesh, glass points); the frames in [colour], or
    the spine's own paint; [clear] = (shape, z0, z1[, outside]) also clears the model's leftover faces against that canopy"""
    ac = L.Aircraft(vid)
    E = ac.eye(0)
    old = original_glass(vid)
    atlas = atlas_for(vid)
    m, glass = build(atlas, E, colour or frame_colour(ac))
    n_old, n_new = install(ac, m, source, glass, old)
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
