"""Checks a model with gear wells for holes. Over every point where, with the gear down and the doors open, a
ray from below meets a well face (wall or ceiling, drawn with the one well texel), the same ray with the gear up
must meet a shut door or the skin before any well face. A failure is a gap you could see the well through.
python3 holes.py <id> [--geo FILE --asset FILE]"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import render as R  # noqa: E402
import wells as W  # noqa: E402


def first_hits(tris, xs, zs):
    """For each (x, z), the index of the first triangle a ray straight up meets, or -1."""
    out = np.full(len(xs), -1)
    v0, v1, v2 = tris[:, 0], tris[:, 1], tris[:, 2]
    e1, e2 = v1 - v0, v2 - v0
    d = np.array([0, 1.0, 0])
    p = np.cross(d, e2)
    det = (e1 * p).sum(1)
    ok = np.abs(det) > 1e-12
    inv = np.where(ok, 1.0 / np.where(ok, det, 1), 0)
    q0 = np.cross(-v0, e1)
    for k, (x, z) in enumerate(zip(xs, zs)):
        o = np.array([x, -200.0, z])
        s = o - v0
        u = (s * p).sum(1) * inv
        q = np.cross(s, e1)
        v = (q * d).sum(1) * inv
        t = (e2 * q).sum(1) * inv
        m = ok & (u >= -1e-9) & (v >= -1e-9) & (u + v <= 1 + 1e-9) & (t > 0)
        idx = np.where(m)[0]
        if len(idx):
            out[k] = idx[np.argmin(t[idx])]
    return out


def posed(geo, asset, gear, legs):
    tris, uvs, _, who = R.posed_triangles(geo, R.rig_pose(asset, gear))
    bones = {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}

    def under(n):
        while n:
            if n in legs:
                return True
            n = bones[n].get('parent') if n in bones else None
        return False
    keep = np.array([not under(w) for w in who])
    tris = tris[keep] * np.array([16.0, 16.0, -16.0])
    uvs = uvs[keep]
    who = [w for w, k in zip(who, keep) if k]
    flat_uv = np.all(np.abs(uvs - uvs[:, :1]) < 1e-7, axis=(1, 2))
    return tris, flat_uv, who


def check(vid, geo_path=None, asset_path=None, step=0.5, geo=None, asset=None):
    geo = geo or json.load(open(geo_path or os.path.join(W.GEO, vid + '.geo.json')))
    asset = asset or json.load(open(asset_path or os.path.join(W.VEH, vid + '.json')))
    rig = asset['AircraftRig']
    legs = {g['Bone'] for g in rig.get('Gear') or []}
    doors = {d['Bone'] for d in rig.get('GearDoors') or []}
    t0, well0, who0 = posed(geo, asset, 0.0, legs)
    t1, well1, who1 = posed(geo, asset, 1.0, legs)
    door1 = np.array([any(w.endswith('__' + d) for d in doors) for w in who1])
    pts = t0[well0].reshape(-1, 3)
    if not len(pts):
        return ['no well faces found']
    problems = []
    # the wells' extent, padded, on a fine grid
    lo, hi = pts.min(0) - 1, pts.max(0) + 1
    xs, zs = np.meshgrid(np.arange(lo[0], hi[0], step) + step / 2, np.arange(lo[2], hi[2], step) + step / 2)
    xs, zs = xs.ravel(), zs.ravel()
    f0 = first_hits(t0, xs, zs)
    sel = np.where((f0 >= 0) & np.where(f0 >= 0, well0[np.maximum(f0, 0)], False))[0]
    # with the gear up nothing of it may show from below
    ta, _, _, wa = R.posed_triangles(geo, R.rig_pose(asset, 1.0))
    bones = {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}

    moving = {g['Bone'] for g in rig.get('Gear') or [] if 'Retract' in g}   # pop-in legs are hidden in game

    def leg_of(n):
        while n:
            if n in moving:
                return True
            n = bones[n].get('parent') if n in bones else None
        return False
    fa = first_hits(ta * np.array([16.0, 16.0, -16.0]), xs, zs)
    for k, f in enumerate(fa):
        if f >= 0 and leg_of(wa[f]):
            problems.append(f'the stowed gear ({wa[f]}) shows from below at ({xs[k]:.1f},{zs[k]:.1f})')
    f1 = first_hits(t1, xs[sel], zs[sel])
    for k, f in zip(sel, f1):
        if f < 0:
            problems.append(f'open sky through the well at ({xs[k]:.1f},{zs[k]:.1f})')
        elif well1[f]:
            problems.append(f'the well shows at ({xs[k]:.1f},{zs[k]:.1f}) past {who1[f]}')
    return problems


if __name__ == '__main__':
    a = sys.argv[1:]
    kw = {}
    if '--geo' in a: kw['geo_path'] = a[a.index('--geo') + 1]
    if '--asset' in a: kw['asset_path'] = a[a.index('--asset') + 1]
    probs = check(a[0], **kw)
    print(a[0], 'CLEAN' if not probs else f'{len(probs)} problem point(s)')
    for p in probs[:15]:
        print('   ', p)
