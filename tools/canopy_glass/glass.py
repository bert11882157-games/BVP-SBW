#!/usr/bin/env python3
"""Fits canopy glass to every aircraft: one simple, connected sheet shaped like the canopy.

1. Canopy volume. The canopy is the convex hull of
     * the canopy frames and rails the model has (surfaces near the pilot, above the rails, with nothing solid just
       beyond them), so framed canopies get flat panes between their frames; and
     * an oblong dome standing on the rails: as wide as the cockpit at its rails, as high as the tallest frame (or
       APEX above the pilot's eye for frameless bubbles), and as long as the frames reach or, without frames, a
       canopy of typical proportions (LENGTH_PER_WIDTH) centred a little ahead of the eye.
   canopies.json can set per-aircraft proportions taken from reference photographs.
2. An even latitude/longitude grid is laid over that volume from its middle.
3. Parts that collide with the model are removed: inside the airframe (no way out along or near the surface
   normal) or lying on the skin (the nose or spine directly under the glass). Enclosed holes (a mirror or handle under
   the canopy) are filled.
4. Grid triangles cut by the removal are clipped exactly where the glass meets the model (binary search along each
   cut edge), so the edge follows the rails, windscreen base and canopy end instead of the grid.

Output: "CanopyGlass" in the asset vehicle JSON (vehicle-local blocks, +X left, +Y up, +Z forward):
  {"Schema":1,"Frame":"VEHICLE_LOCAL_BLOCKS","Triangles":[x,y,z, x,y,z, x,y,z, ...]}   (written on one line)
Usage: python3 tools/canopy_glass/glass.py [--write] [id...]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
import thin as T  # noqa: E402

SHELL_RANGE = 2.2        # blocks from the eye: surfaces considered for frames and rails
BEHIND = (0.1, 0.9)      # solid in this range beyond a hit marks interior structure, not the canopy shell
APEX = 0.3               # blocks above the eye for a frameless bubble
LENGTH_PER_WIDTH = 2.6   # canopy half-length per rail half-width when no frames show the length
AHEAD = 0.2              # a frameless canopy's middle sits this fraction of its half-length ahead of the eye
AZ_STEP, EL_STEP = 5.0, 4.0
EL_MIN = -40.0
ESCAPE_RANGE = 2.5       # blocks: a surface point with a clear line this long outward is outside the airframe
ESCAPE_TILT = 25.0       # degrees: the fan of escape rays around the outward normal
SKIN_GAP = 0.07          # blocks: glass with the airframe this close under it (toward the canopy middle) is on the skin
INSET = 0.006
NOSE_REACH = 1.2         # a frameless windscreen runs at most this many rail half-widths past the cockpit opening
REFINE = 10              # binary search steps along cut edges
SKIP = {'ho_229', 'me_163', 'me_262_50mm', 'me_262_elite'}
OVERRIDES = os.path.join(HERE, 'canopies.json')


def aircraft():
    return sorted(f[:-5] for f in os.listdir(os.path.join(T.GEN, 'data/berts_vehicle_pack/flight_reference')))


def direction(az, el):
    a, e = np.radians(az), np.radians(el)
    return np.stack([np.cos(e) * np.sin(a), np.sin(e), np.cos(e) * np.cos(a)], -1)


class Rays:
    """Alpha-tested ray casts from any origin against the model triangles near the cockpit."""

    def __init__(self, tris, uvs, tex, centre, reach=4.5):
        near = np.linalg.norm(tris - centre, axis=2).min(axis=1) < reach
        self.v0 = tris[near, 0]; self.e1 = tris[near, 1] - self.v0; self.e2 = tris[near, 2] - self.v0
        self.uvs = uvs[near]; self.tex = tex

    def first(self, o, d, tmax):
        """Distance to the first opaque surface along o + t d (t in (1e-4, tmax)), or inf."""
        p = np.cross(d, self.e2)
        det = np.einsum('ij,ij->i', self.e1, p)
        ok = np.abs(det) > 1e-12
        inv = np.where(ok, 1.0 / np.where(ok, det, 1.0), 0.0)
        s = o - self.v0
        a = np.einsum('ij,ij->i', s, p) * inv
        q = np.cross(s, self.e1)
        b = (q @ d) * inv
        t = np.einsum('ij,ij->i', self.e2, q) * inv
        idx = np.where(ok & (a >= 0) & (b >= 0) & (a + b <= 1) & (t > 1e-4) & (t < tmax))[0]
        if not len(idx):
            return np.inf
        H, W = self.tex.shape[:2]
        for i in idx[np.argsort(t[idx])]:
            uv = self.uvs[i, 0] * (1 - a[i] - b[i]) + self.uvs[i, 1] * a[i] + self.uvs[i, 2] * b[i]
            if self.tex[int(np.clip((1 - uv[1]) * H, 0, H - 1)), int(np.clip(uv[0] * W, 0, W - 1)), 3] > 0.5:
                return t[i]
        return np.inf


def shell_points(depth, caster, eye):
    """Near surfaces with nothing solid just beyond them: canopy frames, rails and the skin round the cockpit."""
    pts = []
    for el in np.arange(-60.0, 90.1, 2.0):
        n_az = max(8, int(round(180 * math.cos(math.radians(el)))))
        for az in np.linspace(-180, 180, n_az, endpoint=False):
            d = direction(az, el)
            t = float(T.lookup(depth, d[None])[0])
            if t < SHELL_RANGE and not caster.hits(d, t + BEHIND[0], t + BEHIND[1]):
                pts.append(eye + d * t)
    return np.array(pts).reshape(-1, 3)


def rails(depth, eye):
    """(half-width, height) of the cockpit rails: the outermost cockpit wall the pilot sees to either side."""
    best, top = 0.0, []
    for az in (90.0, -90.0):
        prev = None
        last = None
        for el in np.arange(-70.0, 1.0, 2.0):          # up the cockpit wall until the view jumps past the rail
            d = direction(az, el)
            t = float(T.lookup(depth, d[None])[0])
            if t >= 1.5 or (prev is not None and t > prev * 1.25 + 0.03):
                break
            p = eye + d * t
            best = max(best, abs(p[0])); last = p[1]
            prev = t
        if last is not None:
            top.append(last)
    return max(best, 0.25) + 0.02, (float(np.mean(top)) if top else eye[1] - 0.35)


def canopy_volume(vid, eye, shell, width, rail_y, overrides, extent=None):
    """Points whose convex hull is the canopy: frames above the rails plus an oblong dome on the rails."""
    o = overrides.get(vid, {})
    above = shell[(shell[:, 1] > rail_y + 0.04) & (np.abs(shell[:, 0]) <= width + 0.05)]
    frame_top = above[:, 1].max() if len(above) else -np.inf
    top = max(frame_top, eye[1] + o.get('apex', APEX))
    if 'front' in o and 'rear' in o:
        zf, zr = eye[2] + o['front'], eye[2] - o['rear']
    elif extent is not None:
        zf, zr = extent[:2]
    elif len(above) and above[:, 2].max() - above[:, 2].min() > 1.2 * width:
        zf, zr = above[:, 2].max(), above[:, 2].min()
    else:
        c0 = LENGTH_PER_WIDTH * width
        zf, zr = eye[2] + (1 + AHEAD) * c0, eye[2] - (1 - AHEAD) * c0
    c = (zf - zr) / 2
    zc = (zf + zr) / 2
    a = width * o.get('width', 1.0)
    b = top - rail_y
    pts = []
    for el in np.linspace(0, 90, 10):
        for az in np.linspace(0, 360, 36, endpoint=False):
            d = direction(az, el)
            pts.append([a * d[0], b * d[1], c * d[2]])
    dome = np.array(pts) + np.array([0.0, rail_y, zc])
    use_frames = o.get('shape', 'blend') != 'bubble'
    parts = [dome] + ([above] if use_frames and len(above) else [])
    if extent is not None and len(extent[2]):
        parts.append(extent[2])
    P = np.vstack(parts)
    P = np.vstack([P, P * np.array([-1.0, 1, 1])])
    centre = np.array([0.0, rail_y + 0.35 * b, zc])
    return P, centre, (a, b, c)


def canopy_extent(rays, eye, width, rail_y):
    """(front z, rear z) of the canopy from the airframe's centre-line height seen from above: the cockpit opening
    is the run round the eye where the view drops deep inside (frame bars and the headrest bridged); the canopy
    runs on to the windscreen frame, or over the nose until the nose has fallen to the rails, and back to the end of
    the opening."""
    step = 0.05
    zs = np.arange(-3.0, 3.0 + 1e-6, step)
    h = np.full(len(zs), -np.inf)
    for k, dz in enumerate(zs):
        best = -np.inf
        for x in (0.0, 0.3 * width, -0.3 * width):
            o = np.array([x, eye[1] + 3.0, eye[2] + dz])
            t = rays.first(o, np.array([0.0, -1.0, 0.0]), 6.0)
            if np.isfinite(t):
                best = max(best, o[1] - t)
        h[k] = best - eye[1]
    # The view drops clearly below the rails: inside the cockpit (nose skin just under high rails does not count).
    deep = h < min((rail_y - eye[1]) + 0.02, -0.15)
    i0 = int(np.argmin(np.abs(zs)))
    bridge = int(round(0.35 / step))                    # frame bars, headrest and sight narrower than this
    def run(direction):
        k, last, gap = i0, i0, 0
        while 0 <= k + direction < len(zs):
            k += direction
            if direction > 0 and h[k] > 0.08:           # the windscreen arch closes the opening in front
                break
            if deep[k]:
                last, gap = k, 0
            else:
                gap += 1
                if gap > bridge:
                    break
        return last
    rear_i, front_i = run(-1), run(+1)
    if front_i - rear_i < 4:
        return None
    zr = zs[rear_i] - step
    # Forward: a windscreen frame above eye level ends the canopy; otherwise it runs on over the nose.
    zf = zs[front_i] + step
    framed = False
    for k in range(front_i + 1, min(len(zs), front_i + int(round(1.0 / step)))):
        if h[k] > 0.08:                                  # an arch above eye level, not the sight
            zf, framed = zs[k] + step, True
    if not framed:
        k = front_i + 1
        while k < len(zs) and h[k] > (rail_y - eye[1]) and zs[k] - zs[front_i] < NOSE_REACH * width:
            k += 1
        zf = max(zf, zs[min(k, len(zs) - 1)])
    # Everything standing up inside the canopy (headrest, sight, frames) must stay under the glass.
    inside = [np.array([x, eye[1] + h[k] + 0.05, eye[2] + zs[k]]) for k in range(len(zs))
              if zr <= zs[k] <= zf and np.isfinite(h[k]) and h[k] > rail_y - eye[1]
              for x in (0.0, 0.3 * width, -0.3 * width)]
    return eye[2] + zf, eye[2] + zr, np.array(inside).reshape(-1, 3)


def hull_exit(hull, origin, dirs):
    n, off = hull.equations[:, :3], hull.equations[:, 3]
    nd = dirs @ n.T
    dist = -(n @ origin + off)
    with np.errstate(divide='ignore', invalid='ignore'):
        t = np.where(nd > 1e-9, dist / nd, np.inf)
    return t.min(-1)


def tilted(n, deg):
    """n and four rays tilted deg degrees around it."""
    helper = np.array([0.0, 1.0, 0.0]) if abs(n[1]) < 0.9 else np.array([1.0, 0.0, 0.0])
    u = np.cross(n, helper); u /= np.linalg.norm(u)
    v = np.cross(n, u)
    s, c = math.sin(math.radians(deg)), math.cos(math.radians(deg))
    return [n] + [c * n + s * w for w in (u, -u, v, -v)]


def glass(vid):
    _, geo = T.load_geo(vid)
    tex = T.load_texture(vid)
    eye = T.eyes(vid)[0]
    if vid in SKIP:
        return eye, None
    overrides = json.load(open(OVERRIDES)) if os.path.exists(OVERRIDES) else {}
    if overrides.get(vid, {}).get('skip'):
        return eye, None
    tris, uvs = T.triangles(geo)
    depth = T.render_depth(tris, uvs, tex, eye)
    caster = T.Caster(tris, uvs, tex, eye)
    width, rail_y = rails(depth, eye)
    shell = shell_points(depth, caster, eye)
    rays = Rays(tris, uvs, tex, eye, reach=6.0)
    extent = canopy_extent(rays, eye, width, rail_y)
    P, centre, _ = canopy_volume(vid, eye, shell, width, rail_y, overrides, extent)
    from scipy.spatial import ConvexHull
    hull = ConvexHull(np.vstack([P, [[0.0, rail_y - 0.8, centre[2]]]]))
    if (hull.equations[:, :3] @ centre + hull.equations[:, 3]).max() > -0.02:
        return eye, None

    def surface(az, el):
        d = direction(az, el)
        return centre + d * (hull_exit(hull, centre, d[None])[0] - INSET)

    def keep(p):
        if p[1] < rail_y - 0.12:
            return False
        n = p - centre; n /= np.linalg.norm(n)
        if all(rays.first(p, d / np.linalg.norm(d), ESCAPE_RANGE) < np.inf for d in tilted(n, ESCAPE_TILT)):
            return False                               # inside the airframe
        if rays.first(p, -n, SKIN_GAP) < np.inf:
            return False                               # lying on the nose, spine or rails
        return True

    azs = np.arange(-180.0, 180.0, AZ_STEP)
    els = np.arange(EL_MIN, 90.0 + 1e-6, EL_STEP)
    if els[-1] < 90.0:
        els = np.append(els, 90.0)
    pts = np.array([[surface(az, el) for el in els] for az in azs])
    mask = np.array([[keep(pts[i, j]) for j in range(len(els))] for i in range(len(azs))])
    # The top row is one point: settle it by majority.
    mask[:, -1] = mask[:, -1].mean() >= 0.5
    mask = tidy(mask)
    if mask.sum() < 12:
        return eye, None

    def edge_point(a, b):
        """Where the glass meets the model between grid points a (kept) and b (removed), in (az, el)."""
        lo, hi = 0.0, 1.0
        for _ in range(REFINE):
            m = (lo + hi) / 2
            if keep(surface(a[0] + (b[0] - a[0]) * m, a[1] + (b[1] - a[1]) * m)):
                lo = m
            else:
                hi = m
        return surface(a[0] + (b[0] - a[0]) * lo, a[1] + (b[1] - a[1]) * lo)

    out = []
    n_az, n_el = len(azs), len(els)
    for i in range(n_az):
        i2 = (i + 1) % n_az
        az0, az1 = azs[i], azs[i] + AZ_STEP
        for j in range(n_el - 1):
            quad = [((az0, els[j]), pts[i, j], mask[i, j]), ((az1, els[j]), pts[i2, j], mask[i2, j]),
                    ((az1, els[j + 1]), pts[i2, j + 1], mask[i2, j + 1]),
                    ((az0, els[j + 1]), pts[i, j + 1], mask[i, j + 1])]
            for tri in ((quad[0], quad[1], quad[2]), (quad[0], quad[2], quad[3])):
                flags = [t[2] for t in tri]
                if all(flags):
                    out.append([t[1] for t in tri])
                elif any(flags):
                    out += clip(tri, edge_point)
    tris_out = [t for t in out if np.linalg.norm(np.cross(t[1] - t[0], t[2] - t[0])) > 1e-7]
    if len(tris_out) < 8:
        return eye, None
    flat = [round(float(v), 4) for t in tris_out for p in t for v in p]
    return eye, {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': flat}


def clip(tri, edge_point):
    """The kept part of a triangle with one or two corners removed, as triangles."""
    poly = []
    for k in range(3):
        a, b = tri[k], tri[(k + 1) % 3]
        if a[2]:
            poly.append(a[1])
        if a[2] != b[2]:
            kept, gone = (a, b) if a[2] else (b, a)
            poly.append(edge_point(kept[0], gone[0]))
    return [[poly[0], poly[k], poly[k + 1]] for k in range(1, len(poly) - 1)]


def tidy(mask):
    """Fill removed holes enclosed by glass (a mirror or handle under the canopy) and keep the largest glass piece.
    Azimuth wraps; the bottom row borders the outside."""
    from scipy.ndimage import label
    wrap = np.concatenate([mask, mask[:1]], 0)
    holes, n = label(~wrap)
    outside = set(np.unique(holes[:, 0])) - {0}
    fill = ~np.isin(holes, list(outside)) & (holes > 0)
    wrap = wrap | fill
    parts, n = label(wrap)
    if n > 1:
        sizes = np.bincount(parts.ravel())[1:]
        wrap = parts == (1 + int(sizes.argmax()))
    out = wrap[:-1].copy()
    out[0] |= wrap[-1]
    return out


def triangulate(block):
    return list(np.array(block['Triangles'], float).reshape(-1, 3, 3))


def write_block(vid, block):
    path = os.path.join(T.GEN, 'assets/berts_vehicle_pack/sbw/vehicles', vid + '.json')
    raw = open(path, encoding='utf-8').read()
    doc = json.loads(raw)
    doc.pop('CanopyGlass', None)
    indent = 2 if raw.startswith('{\n') else None
    marker = '"__CANOPY_GLASS__"'
    if block:
        doc['CanopyGlass'] = '__CANOPY_GLASS__'
    text = json.dumps(doc, indent=indent, ensure_ascii=False)
    if block:
        text = text.replace(marker, json.dumps(block, separators=(',', ':')))
    open(path, 'w', encoding='utf-8').write(text + ('\n' if raw.endswith('\n') else ''))


def main(argv):
    write = '--write' in argv
    ids = [a for a in argv if not a.startswith('--')] or aircraft()
    for vid in ids:
        eye, block = glass(vid)
        print(f'{vid:34s} ' + (f"{len(block['Triangles']) // 9} triangles" if block else 'no glass'), flush=True)
        if write:
            write_block(vid, block)


if __name__ == '__main__':
    main(sys.argv[1:])
