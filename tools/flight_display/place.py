#!/usr/bin/env python3
"""Places the primary flight display (PFD) panel on the instrument panel of glass-cockpit aircraft.

For every equipped crew seat the tool looks from that seat's eye attachment into the cockpit mesh (the same
poly_mesh geometry the game renders, canopy-glass texels and pilot_view_occluder bones ignored) and searches the
directions a pilot looks down at a head-down display (8..40 degrees below the horizon, +-20 degrees sideways). A
direction qualifies when the ray meets a surface facing the eye, a display of the size chosen for that distance lies
flush on that surface (at least 80% of a 5x5 sample grid has panel geometry directly behind it), and nothing in the
cockpit hides any part of it from the eye. The best direction (closest to 18 degrees down, straight ahead of the
seat) wins; cockpits without a modelled panel get a display floating where the panel would be, reported as such.

Output: "FlightDisplays" in the asset vehicle JSON (VEHICLE_LOCAL_BLOCKS: +X left, +Y up, +Z forward):
  {"Schema":1,"Frame":"VEHICLE_LOCAL_BLOCKS","Displays":[{"Seat":0,"Center":[...],"Normal":[...],"Up":[...],
   "Width":w,"Depth":d}]}
Usage: python3 tools/flight_display/place.py [--write] [--sheet out.png] <id>...
"""
import json, math, os, sys
import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
GEN = os.path.join(ROOT, 'bvp', 'src', 'generated', 'resources')

# Aircraft whose modelled variant flies with electronic (glass) flight displays, and the seats with a flight panel.
GLASS_COCKPITS = {
    'b_1b': [0, 1],                  # pilot and co-pilot; the offensive/defensive stations have no flight panel
    'eurofighter_typhoon': [0],
    'f_14d': [0],                    # the RIO station has no flight instruments of its own
    'f_16c': [0],
    'f_22a': [0],
    'fa_18e': [0],
    'j_11a': [0],
    'rafale': [0],
    'saab_jas_39_gripen': [0],
    'su_35': [0],
    'su_57': [0],
    'ah_64d': [0],                   # the CPG flies from the TADS sight camera; his MPDs are not modelled
    'eurocopter_tiger': [0, 1],
    'mi28n': [0],                    # the operator's station is only seen through its nose sight camera
}

PREFERRED_DOWN = 18.0
ANGULAR_WIDTH = 15.0          # degrees the screen subtends from the eye, like a real 8 inch PFD at arm's length
MIN_WIDTH, MAX_WIDTH = 0.14, 0.34
DEPTH = 0.035                 # housing depth behind the screen, blocks
EPS = 0.004


def load_mesh(vid):
    geo = json.load(open(os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo', vid + '.geo.json')))
    tris, uvs = [], []
    for bone in geo['minecraft:geometry'][0]['bones']:
        if bone['name'].startswith('pilot_view_occluder'):
            continue
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        pos = np.array(pm['positions'], float)
        uv = np.array(pm['uvs'], float) if pm.get('uvs') else np.zeros((1, 2))
        for poly in pm['polys']:
            idx = [v[0] for v in poly]
            tidx = [v[2] if len(v) > 2 else 0 for v in poly]
            for k in range(1, len(idx) - 1):
                tris.append(pos[[idx[0], idx[k], idx[k + 1]]])
                uvs.append(uv[[tidx[0], tidx[k], tidx[k + 1]]])
    tris = np.array(tris)
    # model pixels (x left, y up, z aft) -> vehicle-local blocks (x left, y up, z forward)
    tris = tris * np.array([1.0, 1.0, -1.0]) / 16.0
    return tris, np.array(uvs)


def load_texture(vid):
    path = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity', vid + '.png')
    try:
        return np.asarray(Image.open(path).convert('RGBA')).astype(float) / 255.0
    except Exception:
        return None


class Scene:
    def __init__(self, vid):
        self.tris, self.uvs = load_mesh(vid)
        self.tex = load_texture(vid)
        self.v0 = self.tris[:, 0]
        self.e1 = self.tris[:, 1] - self.v0
        self.e2 = self.tris[:, 2] - self.v0
        n = np.cross(self.e1, self.e2)
        ln = np.linalg.norm(n, axis=1)
        ok = ln > 1e-12
        self.normals = np.where(ok[:, None], n / np.maximum(ln, 1e-12)[:, None], 0)
        self.alpha = self.texel_alpha()

    def texel_alpha(self):
        if self.tex is None:
            return np.ones(len(self.tris))
        h, w = self.tex.shape[:2]
        c = self.uvs.mean(axis=1)
        x = np.clip((c[:, 0] * w).astype(int), 0, w - 1)
        y = np.clip(((1 - c[:, 1]) * h).astype(int), 0, h - 1)  # the mesh loader flips v
        return self.tex[y, x, 3]

    def colors(self):
        if self.tex is None:
            return np.full((len(self.tris), 3), 0.6)
        h, w = self.tex.shape[:2]
        c = self.uvs.mean(axis=1)
        x = np.clip((c[:, 0] * w).astype(int), 0, w - 1)
        y = np.clip(((1 - c[:, 1]) * h).astype(int), 0, h - 1)  # the mesh loader flips v
        return self.tex[y, x, :3]

    def cast(self, origin, direction, tmin=1e-4, tmax=1e9, glass=False):
        """First opaque hit along the ray: (t, triangle index) or (None, None)."""
        d = np.asarray(direction, float)
        p = np.cross(d, self.e2)
        det = np.einsum('ij,ij->i', self.e1, p)
        valid = np.abs(det) > 1e-12
        inv = np.where(valid, 1.0 / np.where(valid, det, 1), 0)
        s = origin - self.v0
        u = np.einsum('ij,ij->i', s, p) * inv
        q = np.cross(s, self.e1)
        v = (q @ d) * inv
        t = np.einsum('ij,ij->i', self.e2, q) * inv
        hit = valid & (u >= 0) & (v >= 0) & (u + v <= 1) & (t > tmin) & (t < tmax)
        if not hit.any():
            return None, None
        idx = np.where(hit)[0]
        idx = idx[np.argsort(t[idx])]
        for i in idx:
            if glass or self.alpha_at(i, u[i], v[i]) > 0.5:
                return t[i], i
        return None, None

    def alpha_at(self, i, u, v):
        """Texture alpha at the hit point (canopy glass and cut-out holes are see-through)."""
        if self.tex is None:
            return 1.0
        uv = self.uvs[i, 0] * (1 - u - v) + self.uvs[i, 1] * u + self.uvs[i, 2] * v
        h, w = self.tex.shape[:2]
        return self.tex[int(np.clip((1 - uv[1]) * h, 0, h - 1)), int(np.clip(uv[0] * w, 0, w - 1)), 3]


def rot_dir(down_deg, left_deg):
    a, h = math.radians(down_deg), math.radians(left_deg)
    return np.array([math.cos(a) * math.sin(h), -math.sin(a), math.cos(a) * math.cos(h)])


def frame_for(normal):
    up = np.array([0.0, 1.0, 0.0]) - normal * normal[1]
    if np.linalg.norm(up) < 1e-6:
        up = np.array([0.0, 0.0, 1.0])
    up /= np.linalg.norm(up)
    right = np.cross(up, normal)  # screen right as seen from the eye (normal points at the eye)
    return up, right


stats = {}


def evaluate(scene, eye, down, left):
    d = rot_dir(down, left)
    t, i = scene.cast(eye, d, tmin=0.2, tmax=2.2)
    if t is None:
        return None
    point = eye + d * t
    n = scene.normals[i]
    if n @ d > 0:
        n = -n
    facing = -(n @ d)
    stats['hit'] += 1
    # An instrument panel faces aft toward the crew; the nose skin seen through the windscreen faces up/forward.
    if facing < 0.45 or n[2] > -0.3:
        return None
    stats['facing'] += 1
    # Under the canopy or glareshield (glass counts): exterior surfaces have open sky above them. Some canopies
    # are only frames, so an open top is a penalty rather than a rejection; the aft-facing test already keeps the
    # nose skin out.
    covered = any(scene.cast(point + n * 0.02, np.array(u) / np.linalg.norm(u), tmin=0.0, tmax=3.0, glass=True)[0]
                  is not None for u in ((0, 1, 0), (0, 1, -0.6), (0, 1, 0.6), (0.6, 1, 0), (-0.6, 1, 0)))
    if covered:
        stats['covered'] += 1
    normal = n
    width = float(np.clip(2 * t * math.tan(math.radians(ANGULAR_WIDTH / 2)), MIN_WIDTH, MAX_WIDTH))
    up, right = frame_for(normal)
    centre = point + normal * EPS
    supported, visible, total = 0, 0, 0
    for sy in np.linspace(-0.45, 0.45, 5):
        for sx in np.linspace(-0.45, 0.45, 5):
            s = centre + right * sx * width + up * sy * width
            total += 1
            # panel geometry close behind the screen sample
            tb, _ = scene.cast(s + normal * 0.02, -normal, tmin=0.0, tmax=0.02 + 0.09)
            if tb is not None:
                supported += 1
            # nothing between the eye and the sample
            v = s - eye
            dist = np.linalg.norm(v)
            tv, _ = scene.cast(eye, v / dist, tmin=0.05, tmax=dist - 0.03)
            if tv is None:
                visible += 1
    support = supported / total
    vis = visible / total
    if support < 0.8:
        return None
    stats['support'] += 1
    if vis < 0.96:
        return None
    stats['visible'] += 1
    score = abs(down - PREFERRED_DOWN) + 0.8 * abs(left) + 25 * (1 - support) + (0 if covered else 8)
    return dict(score=score, centre=centre, normal=normal, up=up, width=width, down=down, left=left,
                distance=float(t), support=support)


def place_seat(scene, eye):
    stats.clear(); stats.update(hit=0, facing=0, covered=0, support=0, visible=0)
    best = None
    for down in np.arange(8, 41, 2.0):
        for left in np.arange(-20, 21, 2.5):
            r = evaluate(scene, eye, float(down), float(left))
            if r and (best is None or r['score'] < best['score']):
                best = r
    if best:
        best['mode'] = 'panel'
        return best
    # No modelled panel: float the screen where one would be.
    d = rot_dir(PREFERRED_DOWN, 0)
    t = 0.75
    t_hit, _ = scene.cast(eye, d, tmin=0.2, tmax=2.2)
    if t_hit is not None:
        t = min(t, t_hit - 0.02)
    centre = eye + d * t
    normal = -d
    up, _ = frame_for(normal)
    return dict(score=None, centre=centre, normal=normal, up=up,
                width=float(np.clip(2 * t * math.tan(math.radians(ANGULAR_WIDTH / 2)), MIN_WIDTH, MAX_WIDTH)),
                down=PREFERRED_DOWN, left=0.0, distance=t, support=0.0, mode='floating')


def open_space(scene, p, r=0.22):
    dirs = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))
    return all(scene.cast(p, np.array(d, float), tmin=0.0, tmax=r, glass=True)[0] is None for d in dirs)


def search_head(scene, seat_pos, eye, pilot_eye):
    """Crew seats whose camera is a sight (or sits inside the model) still have a cockpit: find the head position
    above the seat from which a flight panel is visible, trying heights between the seat and the pilot's eye."""
    best = None
    lo = min(seat_pos[1], eye[1]) + 0.3
    hi = max(pilot_eye[1], eye[1]) + 0.2
    for z in sorted({round(seat_pos[2], 3), round(eye[2], 3)} | {round(seat_pos[2] + dz, 3) for dz in (-0.3, 0.3)}):
        if abs(z - seat_pos[2]) > 1.0:
            continue
        for y in np.arange(lo, hi + 1e-6, 0.12):
            head = np.array([seat_pos[0], y, z])
            if not open_space(scene, head):
                continue
            r = place_seat(scene, head)
            if r['mode'] == 'panel' and (best is None or r['score'] < best['score']):
                r['eye'] = head
                r['stats'] = dict(stats)
                best = r
    return best


def eyes(vid):
    data = json.load(open(os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles', vid + '.json')))
    att = data.get('Attachments', {})
    out = {}
    for k, seat in enumerate(data['Seats']):
        cam = seat.get('CameraPos', {})
        name = cam.get('EyeAttachment')
        if name and name in att:
            out[k] = np.array(att[name]['Position'], float)
        else:
            out[k] = np.array(seat['Position'], float) + np.array([0, 1.5, 0])
    return out


def r5(v):
    return [round(float(x), 5) + 0.0 for x in v]


def place(vid):
    scene = Scene(vid)
    seat_eyes = eyes(vid)
    data = json.load(open(os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles', vid + '.json')))
    results = []
    for seat in GLASS_COCKPITS[vid]:
        eye = seat_eyes[seat]
        r = place_seat(scene, eye)
        r['stats'] = dict(stats)
        r['eye'] = eye
        if r['mode'] != 'panel':
            found = search_head(scene, np.array(data['Seats'][seat]['Position'], float), eye, seat_eyes[0])
            if found:
                found['mode'] = 'panel-head'
                r = found
        r['seat'] = seat
        results.append(r)
    return scene, results


def resource_block(results):
    return {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': [
        {'Seat': r['seat'], 'Center': r5(r['centre']), 'Normal': r5(r['normal']), 'Up': r5(r['up']),
         'Width': round(r['width'], 4), 'Depth': DEPTH} for r in results]}


# ---------------------------------------------------------------- verification render

def render_view(scene, results, eye, ax, fov=70.0, down=14.0, title=''):
    from matplotlib.collections import PolyCollection
    fwd = rot_dir(down, 0)
    right = np.cross(fwd, [0, 1, 0]); right /= np.linalg.norm(right)   # screen right = vehicle right (-X)
    up = np.cross(right, fwd)
    cols = scene.colors()
    f = 1 / math.tan(math.radians(fov / 2))
    near = 0.03
    polys, depth, fc = [], [], []

    def project(pts):
        rel = pts - eye
        z = rel @ fwd
        return np.c_[(rel @ right) * f / z, (rel @ up) * f / z], z

    def clip(tri):
        z = (tri - eye) @ fwd
        out = []
        for k in range(len(tri)):
            a, b = tri[k], tri[(k + 1) % len(tri)]
            za, zb = z[k], z[(k + 1) % len(tri)]
            if za >= near:
                out.append(a)
            if (za >= near) != (zb >= near):
                out.append(a + (b - a) * (near - za) / (zb - za))
        return np.array(out)

    L = np.array([0.3, 0.8, 0.4]); L /= np.linalg.norm(L)
    for tri, n, c in zip(scene.tris, scene.normals, cols):
        rel = tri.mean(0) - eye
        if np.linalg.norm(rel) > 12:
            continue
        pts = clip(tri)
        if len(pts) < 3:
            continue
        xy, z = project(pts)
        if np.all(np.abs(xy) > 3):
            continue
        shade = 0.55 + 0.45 * abs(n @ L)
        polys.append(xy); depth.append(np.linalg.norm(rel)); fc.append(np.clip((0.12 + 0.88 * c ** 0.6) * shade, 0, 1))
    for r in results:
        upv, rightv = r['up'], np.cross(r['up'], r['normal'])
        w = r['width'] / 2
        quad = np.array([r['centre'] + rightv * sx * w + upv * sy * w for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1))])
        pts = clip(quad)
        if len(pts) >= 3:
            xy, _ = project(pts)
            polys.append(xy); depth.append(-1); fc.append((0.1, 1.0, 0.3) if r['mode'] == 'panel' else (1, 0.2, 0.9))
    order = np.argsort(depth)[::-1]
    ax.add_collection(PolyCollection([polys[i] for i in order], facecolors=[fc[i] for i in order],
                                     edgecolors=(0, 0, 0, 0.25), linewidths=0.2))
    aspect = 16 / 9
    ax.set_xlim(-aspect, aspect); ax.set_ylim(-1, 1); ax.set_aspect('equal')
    ax.set_facecolor((0.55, 0.7, 0.9)); ax.set_xticks([]); ax.set_yticks([])
    ax.set_title(title, fontsize=8)


def sheet(entries, out):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    rows = sum(len(res) for _, _, res in entries)
    fig, axes = plt.subplots(rows, 1, figsize=(8, 4.6 * rows))
    axes = np.atleast_1d(axes)
    k = 0
    for vid, scene, res in entries:
        for r in res:
            render_view(scene, res, r['eye'], axes[k], title=f"{vid} seat {r['seat']} ({r['mode']}, {r['down']:.0f} deg down, "
                        f"{r['left']:+.1f} deg left, {r['distance']:.2f} blocks, width {r['width']:.2f})")
            k += 1
    fig.tight_layout(); fig.savefig(out, dpi=80); plt.close(fig)


def main(argv):
    write = '--write' in argv
    out_sheet = None
    if '--sheet' in argv:
        out_sheet = argv[argv.index('--sheet') + 1]
    ids = [a for a in argv if not a.startswith('--') and a != out_sheet] or sorted(GLASS_COCKPITS)
    entries = []
    for vid in ids:
        scene, res = place(vid)
        entries.append((vid, scene, res))
        for r in res:
            print(f"{vid:22s} seat {r['seat']} {r['mode']:8s} down {r['down']:4.0f} left {r['left']:+5.1f} "
                  f"dist {r['distance']:.2f} width {r['width']:.3f} support {r['support']:.2f} centre {r5(r['centre'])} {r['stats']}")
        if write:
            path = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles', vid + '.json')
            raw = open(path, encoding='utf-8').read()
            doc = json.loads(raw)
            doc['FlightDisplays'] = resource_block(res)
            indent = 2 if raw.startswith('{\n') else None
            text = json.dumps(doc, indent=indent, ensure_ascii=False)
            open(path, 'w', encoding='utf-8').write(text + ('\n' if raw.endswith('\n') else ''))
    if out_sheet:
        sheet(entries, out_sheet)


if __name__ == '__main__':
    main(sys.argv[1:])
