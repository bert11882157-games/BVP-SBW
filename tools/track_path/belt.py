#!/usr/bin/env python3
"""Rebuilds tracked vehicles' track paths so the links wrap the running gear.

The authored paths were coarse polygons (about a dozen keyframes) scaled into a bounding box: links cut the corners
at the idler and sprocket, passed through the tops of the idler and sprocket, and ran through the bottoms of the road
wheels. The new path is the belt around the actual wheels: the convex hull of every wheel circle grown by the link
half-thickness (so the inner face of each link touches the wheel), sampled finely along its length.

Each link's rest geometry is moved onto the new path (the renderer poses links relative to their rest position on
the path), and the side bounds/path source ranges are set to the path itself (no rescaling).

Usage: python3 tools/track_path/belt.py [--write] [--sheet DIR] [id...]   (no ids: every tracked profile)
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
VEH = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
KEYFRAMES = 120
ARC_STEP_DEG = 3.0


def bones_by_name(geo):
    return {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}


def bone_points(bone):
    pm = bone.get('poly_mesh') or {}
    if not pm.get('polys'):
        return None
    return np.array([pm['positions'][v[0]] for p in pm['polys'] for v in p], float)


def wheel_circle(bone):
    P = bone_points(bone)
    if P is None:
        return None
    y0, y1 = P[:, 1].min(), P[:, 1].max()
    z0, z1 = P[:, 2].min(), P[:, 2].max()
    r = min(y1 - y0, z1 - z0) / 2
    return np.array([(z0 + z1) / 2, (y0 + y1) / 2]), r


def hull(points):
    pts = sorted(map(tuple, points))
    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return np.array(lower[:-1] + upper[:-1])


def belt(circles, h):
    pts = []
    for c, r in circles:
        R = r + h
        for a in np.arange(0, 360, ARC_STEP_DEG):
            pts.append(c + R * np.array([math.cos(math.radians(a)), math.sin(math.radians(a))]))
    return hull(np.array(pts))                     # counter-clockwise in (z, y)


def resample(poly, start, forward, n):
    """n+1 points (last = first) evenly by arc length, starting nearest [start], heading along [forward]."""
    k = int(np.argmin(np.linalg.norm(poly - start, axis=1)))
    poly = np.roll(poly, -k, axis=0)
    if np.dot(poly[1] - poly[0], forward) < 0:     # wrong way round: reverse, keeping the start point first
        poly = np.vstack([poly[:1], poly[1:][::-1]])
    closed = np.vstack([poly, poly[:1]])
    seg = np.linalg.norm(np.diff(closed, axis=0), axis=1)
    s = np.concatenate([[0], np.cumsum(seg)])
    L = s[-1]
    t = np.linspace(0, L, n + 1)
    z = np.interp(t, s, closed[:, 0]); y = np.interp(t, s, closed[:, 1])
    return np.stack([z, y], -1), L


def hull_triangles(bones, skip_prefixes=('trackMov', 'trackRot', 'wheel', 'Track', 'brokenTrack', 'crudeTrack')):
    tris = []
    for name, b in bones.items():
        if name.startswith(skip_prefixes) or name.lower().startswith('wreck') is False and False:
            continue
        pm = b.get('poly_mesh') or {}
        for poly in pm.get('polys', []):
            q = [pm['positions'][v[0]] for v in poly]
            for k in range(1, len(q) - 1):
                tris.append((q[0], q[k], q[k + 1]))
    return np.array(tris, float).reshape(-1, 3, 3)


def ray_up(tris, o, maxd):
    """Distance along +y from o to the nearest triangle (<= maxd), or inf."""
    if len(tris) == 0:
        return np.inf
    d = np.array([0.0, 1.0, 0.0])
    v0, v1, v2 = tris[:, 0], tris[:, 1], tris[:, 2]
    e1, e2 = v1 - v0, v2 - v0
    pv = np.cross(np.broadcast_to(d, e2.shape), e2)
    det = np.einsum('ij,ij->i', e1, pv)
    ok = np.abs(det) > 1e-9
    inv = np.where(ok, 1.0 / np.where(ok, det, 1), 0)
    tv = o - v0
    u = np.einsum('ij,ij->i', tv, pv) * inv
    qv = np.cross(tv, e1)
    v = (qv @ d) * inv
    t = np.einsum('ij,ij->i', e2, qv) * inv
    hit = ok & (u >= 0) & (v >= 0) & (u + v <= 1) & (t >= 0) & (t <= maxd)
    return t[hit].min() if hit.any() else np.inf


def clear_hull(pts, bones, side, circles, h):
    """Lets the upper run sag under the hull (fenders, sponsons) where the straight run would pass through it:
    one smooth plateau-shaped sag between the idler and sprocket, never below the road wheels."""
    tris = hull_triangles(bones)
    rot = bones.get(side['LinkRotationPrefix'] + '0')
    P = bone_points(rot) if rot else None
    if P is None:
        return pts, 0
    xs = [P[:, 0].min() + 0.3, (P[:, 0].min() + P[:, 0].max()) / 2, P[:, 0].max() - 0.3]
    median_y = np.median([c[1] for c, _ in circles])
    road = [(c, r) for c, r in circles if c[1] <= median_y + 1.0]
    road_top = max(c[1] + r for c, r in road) + h
    zs = [c[0] for c, _ in circles]
    z_lo, z_hi = min(zs), max(zs)
    upper = [i for i, (z, y) in enumerate(pts) if y > road_top + 0.5 and z_lo < z < z_hi]
    if not upper:
        return pts, 0
    need = 0.0
    for i in upper:
        z, y = pts[i]
        clear = min(ray_up(tris, np.array([x, y - 3 * h, z]), 6 * h) for x in xs) - 2 * h
        if clear < h:
            need = max(need, h - clear + 0.05)
    if need <= 0:
        return pts, 0
    out = pts.copy()
    run_z = pts[upper, 0]
    a, b = run_z.min(), run_z.max()
    for i in upper:
        z, y = pts[i]
        t = (z - a) / max(b - a, 1e-6)
        ramp = min(1.0, min(t, 1 - t) / 0.18)
        ramp = ramp * ramp * (3 - 2 * ramp)
        out[i, 1] = max(road_top, y - need * ramp)
    return out, len(upper)


def link_centres(bones, side):
    out = []
    i = 0
    while True:
        b = bones.get(side['LinkRotationPrefix'] + str(i))
        if b is None:
            break
        out.append(np.array(b['pivot'], float))
        i += 1
    return np.array(out) if out else np.zeros((0, 3))


def unlisted_wheels(bones, side, listed):
    """Sprockets, idlers and return rollers that exist as their own bones but are missing from WheelBones:
    round bones named like wheels that sit in this side's track plane."""
    C = link_centres(bones, side)
    if len(C) == 0:
        return []
    lx = C[:, 0].mean()
    found = []
    for n, b in bones.items():
        if n in listed or 'wheel' not in n.lower() or n.startswith((side['LinkRotationPrefix'], side['LinkMovePrefix'])):
            continue
        P = bone_points(b)
        if P is None:
            continue
        dy, dz, dx = np.ptp(P[:, 1]), np.ptp(P[:, 2]), np.ptp(P[:, 0])
        if dy < 2 or abs(dy - dz) > 0.2 * max(dy, dz) or dx > 12 or abs(P[:, 0].mean() - lx) > 7:
            continue
        found.append((n, wheel_circle(b)))
    return found


def fit_circle(P):
    A = np.column_stack([2 * P[:, 0], 2 * P[:, 1], np.ones(len(P))])
    b = (P ** 2).sum(1)
    (cx, cy, k), *_ = np.linalg.lstsq(A, b, rcond=None)
    return np.array([cx, cy]), math.sqrt(max(k + cx * cx + cy * cy, 0.0))


def link_end_circles(bones, side, circles, h):
    """Sprocket or idler modelled into the hull (no bone of its own): where the authored links reach well past
    the wheels, the end of the loop is fitted as a wheel from the links' own rest positions."""
    C = link_centres(bones, side)
    if len(C) < 8:
        return []
    zy = C[:, [2, 1]]
    out = []
    zs = [c[0] for c, _ in circles]
    reach = [(c[0] - r - h, c[0] + r + h) for c, r in circles]
    lo, hi = min(r[0] for r in reach), max(r[1] for r in reach)
    rmed = float(np.median([r for _, r in circles]))
    for name, beyond, sel in (('rear', zy[:, 0].min() < lo - 3, zy[:, 0] < min(zs)),
                              ('front', zy[:, 0].max() > hi + 3, zy[:, 0] > max(zs))):
        if not beyond or sel.sum() < 4:
            continue
        c, R = fit_circle(zy[sel])
        r = float(np.clip(R - h, 0.5 * rmed, 1.1 * rmed))
        tip = zy[sel][np.argmin(zy[sel][:, 0])] if name == 'rear' else zy[sel][np.argmax(zy[sel][:, 0])]
        # keep the fitted wheel's outer edge where the authored loop ends
        c = np.array([tip[0] + (r + h) * (1 if name == 'rear' else -1), c[1]])
        out.append((name, (c, r)))
    return out


def interp(keys, phase):
    ph = phase % 100.0
    xs = [k[0] for k in keys]; vs = [k[1] for k in keys]
    return float(np.interp(ph, xs, vs))


def chord_rotation(dy, dz):
    return math.degrees(math.atan2(-dy, dz))


def rebuild(vid, write=False):
    vpath = os.path.join(VEH, vid + '.json')
    raw = open(vpath, encoding='utf-8').read(); doc = json.loads(raw)
    rg = doc.get('RunningGear') or {}
    tr = rg.get('TrackRender')
    if not tr or tr.get('Mode') != 'LINKS':
        return None, 'no LINKS track profile'
    gpath = os.path.join(GEO, vid + '.geo.json')
    geo = json.load(open(gpath)); bones = bones_by_name(geo)
    h = float(tr.get('LinkHalfThickness') or 0.0)
    old = tr['Path']
    my, mz = old['MoveY'], old['MoveZ']
    start = np.array([interp(mz, 0), interp(my, 0)])
    ahead = np.array([interp(mz, 2), interp(my, 2)]) - start
    report = []
    new_paths = {}
    for side in tr['Sides']:
        s = side['Side']
        names = rg.get('WheelBones', {}).get('Left' if s == 'L' else 'Right', [])
        circles = [wc for wc in (wheel_circle(bones[n]) for n in names if n in bones) if wc]
        if len(circles) < 3:
            return None, f'side {s}: {len(circles)} wheels'
        listed = set(rg.get('WheelBones', {}).get('Left', [])) | set(rg.get('WheelBones', {}).get('Right', []))
        extra = unlisted_wheels(bones, side, listed)
        if extra:
            circles += [c for _, c in extra]
            report.append(f'{s}: added unlisted wheels ' + ', '.join(n for n, _ in extra))
        ends = link_end_circles(bones, side, circles, h)
        if ends:
            circles += [c for _, c in ends]
            report.append(f'{s}: fitted {"/".join(n for n, _ in ends)} wheel from the authored links')
        poly = belt(circles, h)
        pts, L = resample(poly, start, ahead, KEYFRAMES)
        pts, sunk = clear_hull(pts, bones, side, circles, h)
        if sunk:
            report.append(f'{s}: top run lowered at {sunk} samples to clear the hull')
        new_paths[s] = (pts, L)
        # how far the old path sank into the wheels (negative = inside a wheel by that much)
        worst = 0.0
        for ph in np.linspace(0, 100, 400, endpoint=False):
            p = np.array([interp(mz, ph), interp(my, ph)])
            for c, r in circles:
                worst = min(worst, np.linalg.norm(p - c) - (r + h))
        report.append(f'{s}: {len(circles)} wheels, belt {L:.1f} px, old path up to {-worst:.2f} px inside wheels')
    # Both sides share one path unless they differ (mirror-symmetric running gear is the norm).
    (pl, Ll), (pr, Lr) = new_paths['L'], new_paths['R']
    shared = np.abs(pl - pr).max() < 0.05
    def keyframes(pts):
        phases = np.linspace(0, 100, KEYFRAMES + 1)
        mz_new = [[round(float(p), 5), round(float(v), 5)] for p, v in zip(phases, pts[:, 0])]
        my_new = [[round(float(p), 5), round(float(v), 5)] for p, v in zip(phases, pts[:, 1])]
        # rotation keyframes (used only by RIGID_PATH fits): unwrapped chord angle
        rot = []
        prev = None
        for i in range(len(pts)):
            a, b = pts[i - 1] if i > 0 else pts[-2], pts[(i + 1) % len(pts)] if i + 1 < len(pts) else pts[1]
            ang = chord_rotation(b[1] - a[1], b[0] - a[0])
            if prev is not None:
                while ang - prev > 180: ang -= 360
                while ang - prev < -180: ang += 360
            rot.append(ang); prev = ang
        rot_new = [[round(float(p), 5), round(float(v), 5)] for p, v in zip(phases, rot)]
        return {'SourceYMin': round(float(pts[:, 1].min()), 5), 'SourceYMax': round(float(pts[:, 1].max()), 5),
                'SourceZMin': round(float(pts[:, 0].min()), 5), 'SourceZMax': round(float(pts[:, 0].max()), 5),
                'MoveY': my_new, 'MoveZ': mz_new, 'RotationX': rot_new}
    path_l = keyframes(pl)
    tr['Path'] = path_l
    for side in tr['Sides']:
        pts = new_paths[side['Side']][0]
        pth = path_l if shared else keyframes(pts)
        if not shared:
            side['Path'] = pth
        else:
            side.pop('Path', None)
        side['YBottom'] = pth['SourceYMin']; side['YTop'] = pth['SourceYMax']
        side['ZRear'] = pth['SourceZMin']; side['ZFront'] = pth['SourceZMax']
        side['YCenter'] = round((pth['SourceYMin'] + pth['SourceYMax']) / 2, 5)
        side['Radius'] = round((pth['SourceYMax'] - pth['SourceYMin']) / 2, 5)
    ev = tr['EvaluationLayout']
    ev.update({'YCenter': tr['Sides'][0]['YCenter'], 'Radius': tr['Sides'][0]['Radius'],
               'ZRear': path_l['SourceZMin'], 'ZFront': path_l['SourceZMax']})
    # Move each link's rest geometry onto the new path at its base phase (chord midpoint of its interval).
    count = int(tr['LinkCount']); pd = float(tr['PhaseDistance'])
    moved = 0
    stretched = 0
    for side in tr['Sides']:
        pth = side.get('Path') or path_l
        for i in range(count):
            base = pd * i
            a = np.array([interp(pth['MoveZ'], base - pd / 2), interp(pth['MoveY'], base - pd / 2)])
            b = np.array([interp(pth['MoveZ'], base + pd / 2), interp(pth['MoveY'], base + pd / 2)])
            mid = (a + b) / 2
            rot_bone = bones.get(side['LinkRotationPrefix'] + str(i))
            mov_bone = bones.get(side['LinkMovePrefix'] + str(i))
            if rot_bone is None or mov_bone is None:
                continue
            piv = np.array(rot_bone['pivot'], float)
            dz, dy = mid[0] - piv[2], mid[1] - piv[1]
            if abs(dz) < 1e-6 and abs(dy) < 1e-6:
                continue
            for bone in (mov_bone, rot_bone):
                bone['pivot'] = [bone['pivot'][0], round(bone['pivot'][1] + dy, 5), round(bone['pivot'][2] + dz, 5)]
            pm = rot_bone.get('poly_mesh')
            if pm and pm.get('positions'):
                used = sorted({v[0] for p in pm['polys'] for v in p})
                # Links are authored flat; where the belt is longer than the links cover, lengthen each link
                # (about its pivot, along the track) so the loop stays closed instead of showing gaps.
                zs = [pm['positions'][k][2] for k in used]
                length = max(zs) - min(zs)
                chord = float(np.linalg.norm(b - a))
                stretch = chord / (1.02 * length) if length > 1e-6 and chord / length > 1.05 else 1.0
                if stretch > 1.0:
                    stretched += 1
                pz = piv[2]
                for k in used:
                    q = pm['positions'][k]
                    pm['positions'][k] = [q[0], round(q[1] + dy, 5), round(pz + (q[2] - pz) * stretch + dz, 5)]
            moved += 1
    report.append(f'shared path {shared}, {moved} links moved onto the belt' + (f', {stretched} lengthened to close gaps' if stretched else ''))
    if write:
        with open(gpath, 'w') as fh:
            json.dump(geo, fh, separators=(',', ':'))
        indent = 2 if raw.startswith('{\n') else None
        with open(vpath, 'w', encoding='utf-8') as fh:
            fh.write(json.dumps(doc, indent=indent, ensure_ascii=False) + ('\n' if raw.endswith('\n') else ''))
    return (circles, new_paths, old, h), '; '.join(report)


def sheet(vid, data, out):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    circles, new_paths, old, h = data
    fig, ax = plt.subplots(figsize=(14, 4))
    for c, r in circles:
        ax.add_patch(plt.Circle(c, r, fill=False, color='k'))
    ph = np.linspace(0, 100, 500)
    ax.plot([interp(old['MoveZ'], p) for p in ph], [interp(old['MoveY'], p) for p in ph], 'r-', lw=1, label='old path')
    pts = new_paths['L'][0]
    ax.plot(pts[:, 0], pts[:, 1], 'b-', lw=1, label='new path (link centre)')
    ax.set_aspect('equal'); ax.legend(); ax.set_title(vid)
    fig.savefig(out, dpi=80, bbox_inches='tight'); plt.close(fig)


def main(argv):
    write = '--write' in argv
    sheet_dir = argv[argv.index('--sheet') + 1] if '--sheet' in argv else None
    ids = [a for a in argv if not a.startswith('--') and a != sheet_dir]
    if not ids:
        ids = sorted(f[:-5] for f in os.listdir(VEH)
                     if '"TrackRender"' in open(os.path.join(VEH, f), encoding='utf-8').read())
    for vid in ids:
        try:
            data, msg = rebuild(vid, write)
        except Exception as e:  # keep going; report
            data, msg = None, f'ERROR {e!r}'
        print(f'{vid:24s} {msg}', flush=True)
        if sheet_dir and data:
            os.makedirs(sheet_dir, exist_ok=True)
            sheet(vid, data, os.path.join(sheet_dir, vid + '.png'))


if __name__ == '__main__':
    main(sys.argv[1:])
