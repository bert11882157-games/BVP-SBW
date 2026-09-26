#!/usr/bin/env python3
"""Fits canopy glass to every aircraft.

From the crew eye the tool casts rays over the upper view sphere and keeps the points where a ray meets the canopy
shell: opaque geometry near the eye with nothing solid just beyond it along the ray (frames, bows, rails, the
windscreen arch). Canopies are convex bubbles, so the glass is the convex hull of those points; of the hull's
triangles only the ones spanning an open pane are kept (the ray from the eye through the triangle's centre passes
the triangle before reaching any geometry), so frames, the fuselage and the nose are never covered.

Output: "CanopyGlass" in the asset vehicle JSON:
  {"Schema":1,"Frame":"VEHICLE_LOCAL_BLOCKS","Triangles":[x,y,z, x,y,z, x,y,z, ...]}
Usage: python3 tools/canopy_glass/glass.py [--write] [--sheet DIR] [id...]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
import thin as T  # noqa: E402

SHELL_RANGE = 1.8      # blocks from the eye
BEHIND = (0.1, 0.9)    # solid in this range beyond a hit marks interior structure, not the canopy shell
MIN_EL = -30.0
INSET = 0.004          # glass sits this far inside the shell points


def aircraft():
    return sorted(f[:-5] for f in os.listdir(os.path.join(T.GEN, 'data/berts_vehicle_pack/flight_reference')))


def shell_points(geo, tex, eye):
    tris, uvs = T.triangles(geo)
    depth = T.render_depth(tris, uvs, tex, eye)
    caster = T.Caster(tris, uvs, tex, eye)
    pts = []
    for el in np.arange(MIN_EL, 90.1, 3.0):
        n_az = max(8, int(round(120 * math.cos(math.radians(el)))))
        for az in np.linspace(-180, 180, n_az, endpoint=False):
            a, h = math.radians(el), math.radians(az)
            d = np.array([math.cos(a) * math.sin(h), math.sin(a), math.cos(a) * math.cos(h)])
            t = float(T.lookup(depth, d[None])[0])
            if not t < SHELL_RANGE:
                continue
            if caster.hits(d, t + BEHIND[0], t + BEHIND[1]):
                continue
            pts.append(eye + d * (t - INSET))
    return np.array(pts), depth


def hull_triangles(points):
    from scipy.spatial import ConvexHull
    hull = ConvexHull(points)
    out = []
    for simplex, eq in zip(hull.simplices, hull.equations):
        tri = points[simplex]
        n = eq[:3]
        # orient outward (ConvexHull equations already point outward)
        a, b, c = tri
        if np.cross(b - a, c - a) @ n < 0:
            tri = tri[[0, 2, 1]]
        out.append(tri)
    return out


def glass(vid):
    _, geo = T.load_geo(vid)
    tex = T.load_texture(vid)
    eye = T.eyes(vid)[0]
    pts, depth = shell_points(geo, tex, eye)
    if len(pts) < 20:
        return eye, []
    tris = hull_triangles(pts)
    keep = []
    for tri in tris:
        c = tri.mean(axis=0)
        rel = c - eye
        dist = np.linalg.norm(rel)
        d = rel / dist
        if math.degrees(math.asin(np.clip(d[1], -1, 1))) < MIN_EL:
            continue
        # a pane: nothing solid before or right at the triangle's centre
        if T.lookup(depth, d[None])[0] > dist + 0.03:
            keep.append(tri)
    return eye, keep


def render_check(vid, eye, tris, out):
    """Pilot fisheye with the glass triangles outlined."""
    _, geo = T.load_geo(vid)
    T.view(vid, geo, eye, out)
    from PIL import Image, ImageDraw
    im = Image.open(out).convert('RGB')
    dr = ImageDraw.Draw(im, 'RGBA')
    size, radius = 420, 115.0
    c = np.array([0.0, math.sin(math.radians(40)), math.cos(math.radians(40))])
    right = -np.cross(np.array([0, 1.0, 0]), c); right /= np.linalg.norm(right)
    up = np.cross(c, -right); up /= np.linalg.norm(up)
    if up[1] < 0:
        up = -up

    def proj(p):
        d = (p - eye) / np.linalg.norm(p - eye)
        rho = math.acos(np.clip(d @ c, -1, 1))
        t = d - (d @ c) * c
        phi = math.atan2(t @ up, t @ right)
        x = rho / math.radians(radius) * math.cos(phi); y = rho / math.radians(radius) * math.sin(phi)
        return ((x + 1) / 2 * size, (1 - y) / 2 * size)
    for tri in tris:
        pts = [proj(p) for p in tri]
        dr.polygon(pts, fill=(120, 200, 255, 60), outline=(255, 255, 0, 200))
    im.save(out)


def main(argv):
    write = '--write' in argv
    sheet = argv[argv.index('--sheet') + 1] if '--sheet' in argv else None
    ids = [a for a in argv if not a.startswith('--') and a != sheet] or aircraft()
    for vid in ids:
        eye, tris = glass(vid)
        print(f'{vid:34s} {len(tris)} glass triangles')
        if sheet:
            os.makedirs(sheet, exist_ok=True)
            render_check(vid, eye, tris, os.path.join(sheet, vid + '.png'))
        if write:
            path = os.path.join(T.GEN, 'assets/berts_vehicle_pack/sbw/vehicles', vid + '.json')
            raw = open(path, encoding='utf-8').read()
            doc = json.loads(raw)
            if tris:
                doc['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS',
                                      'Triangles': [round(float(v), 4) for tri in tris for p in tri for v in p]}
            else:
                doc.pop('CanopyGlass', None)
            indent = 2 if raw.startswith('{\n') else None
            open(path, 'w', encoding='utf-8').write(json.dumps(doc, indent=indent, ensure_ascii=False)
                                                    + ('\n' if raw.endswith('\n') else ''))


if __name__ == '__main__':
    main(sys.argv[1:])
