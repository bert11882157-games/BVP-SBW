#!/usr/bin/env python3
"""Contact-sheet renders of an aircraft for checking gear wells, gear doors and the retraction pose.

Every view is drawn over a magenta sky, so any see-through hole in the airframe shows up as magenta, and faces
seen from their back side (by their stored normals) can be tinted green with --backs (the pack's models are
drawn without back-face culling and many stock faces carry inward normals, so this only means something for
faces this tool generated). The gear pose comes from the vehicle's AircraftRig: Gear legs
with a "Retract" entry and GearDoors are posed at the given gear fraction (0 extended, 1 retracted), exactly the
way AircraftRigAnimator poses them.

Usage: render.py VID OUT.png [--gear F] [--geo PATH] [--asset PATH] [--views a,b,...] [--size W] [--zoom Z]
       [--focus x,y,z]   (focus point in geo px; the views look at it)
Views: below, below_front, below_rear, left, right, front, rear, below_left, below_right, top, inside_left, ...
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
import eyeview as EV  # noqa: E402

GEO, ASSET, TEX = EV.GEO, EV.ASSET, EV.TEX
SKY = (1.0, 0.0, 1.0)
BACK_TINT = np.array([0.35, 1.0, 0.35])


def rot(axis, deg):
    a = np.array(axis, float); a /= np.linalg.norm(a)
    t = math.radians(deg); c, s = math.cos(t), math.sin(t)
    x, y, z = a
    return np.array([[c + x * x * (1 - c), x * y * (1 - c) - z * s, x * z * (1 - c) + y * s],
                     [y * x * (1 - c) + z * s, c + y * y * (1 - c), y * z * (1 - c) - x * s],
                     [z * x * (1 - c) - y * s, z * y * (1 - c) + x * s, c + z * z * (1 - c)]])


def smooth(t):
    t = min(1.0, max(0.0, t))
    return t * t * (3 - 2 * t)


# the travel split AircraftRigAnimator uses: legs swing over the first LEG_SPAN of the gear travel, doors that
# are modelled closed shut over the rest (and, lowering, open before the legs move)
LEG_SPAN = 0.8


def leg_travel(f):
    return smooth(f / LEG_SPAN)


def door_open(f):
    return 1 - smooth((f - LEG_SPAN) / (1 - LEG_SPAN))


def rig_pose(asset, gear):
    """{bone: (pivot, axis, degrees)} in geo px for the gear legs and doors at gear fraction `gear`."""
    rig = asset.get('AircraftRig') or {}
    pose = {}
    for g in rig.get('Gear') or []:
        r = g.get('Retract')
        if r:
            pose[g['Bone']] = (r['Pivot'], r['Axis'], leg_travel(gear) * r['AngleDegrees'])
            sl = r.get('Slide')
            if sl:
                pose[sl['Bone']] = (sl.get('Pivot', r['Pivot']), [0, 1, 0], sl.get('TwistDegrees', 0.0) * leg_travel(gear),
                                    np.array(sl['Direction'], float) * sl['DistancePx'] * leg_travel(gear))
    for d in rig.get('GearDoors') or []:
        if 'OpenAngleDegrees' in d:
            pose[d['Bone']] = (d['Pivot'], d['Axis'], d['OpenAngleDegrees'] * door_open(gear))
        else:
            pose[d['Bone']] = (d['Pivot'], d['Axis'], d['ClosedAngleDegrees'] * gear)
    return pose


def posed_triangles(geo, pose, skip=()):
    """(tris Nx3x3 vehicle blocks, uvs, back-normal Nx3 vehicle frame, bone names per tri)"""
    bones = {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}
    cache = {}

    def world(name):
        if name in cache:
            return cache[name]
        b = bones.get(name)
        M, t = np.eye(3), np.zeros(3)
        if b is not None and b.get('parent'):
            M, t = world(b['parent'])
        if name in pose:
            p, ax, deg = pose[name][:3]
            T = pose[name][3] if len(pose[name]) > 3 else np.zeros(3)
            R = rot(ax, deg); p = np.array(p, float)
            # x' = M (R (x - p) + p + T) + t
            t = M @ (p - R @ p + T) + t
            M = M @ R
        cache[name] = (M, t)
        return M, t

    tris, uvs, nrm, who = [], [], [], []
    for name, b in bones.items():
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or name.startswith(tuple(skip)):
            continue
        M, t = world(name)
        P = np.array(pm['positions'], float) @ M.T + t
        N = np.array(pm.get('normals') or [[0, 1, 0]], float) @ M.T
        P = P * np.array([1.0, 1.0, -1.0]) / 16.0
        N = N * np.array([1.0, 1.0, -1.0])
        uv = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            idx = [v[0] for v in poly]; nid = [v[1] for v in poly]; tid = [v[2] for v in poly]
            n = N[nid].sum(0) if len(N) > max(nid) else np.zeros(3)
            for k in range(1, len(idx) - 1):
                tris.append(P[[idx[0], idx[k], idx[k + 1]]]); uvs.append(uv[[tid[0], tid[k], tid[k + 1]]])
                nrm.append(n); who.append(name)
    return np.array(tris), np.array(uvs), np.array(nrm), who


def draw(r, tris, uvs, nrm, tex, backs=False):
    cam = r.cam; H, W = tex.shape[:2]
    for t, uv, sn in zip(tris, uvs, nrm):
        vs = cam.view(t)
        n = np.cross(t[1] - t[0], t[2] - t[0]); ln = np.linalg.norm(n)
        if ln < 1e-12:
            continue
        n /= ln
        lit = 0.55 + 0.45 * abs(float(n @ EV.LIGHT))
        back = backs and float(sn @ (t.mean(0) - cam.eye)) > 0 if np.linalg.norm(sn) > 1e-9 else False
        for cv, cuv in EV.clip(vs, uv):
            s = cam.screen(cv)
            x0 = max(0, int(math.floor(s[:, 0].min()))); x1 = min(cam.w - 1, int(math.ceil(s[:, 0].max())))
            y0 = max(0, int(math.floor(s[:, 1].min()))); y1 = min(cam.h - 1, int(math.ceil(s[:, 1].max())))
            if x1 < x0 or y1 < y0:
                continue
            X, Y = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            (ax, ay), (bx, by), (cx, cy) = s
            den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if abs(den) < 1e-9:
                continue
            w0 = ((by - cy) * (X - cx) + (cx - bx) * (Y - cy)) / den
            w1 = ((cy - ay) * (X - cx) + (ax - cx) * (Y - cy)) / den
            w2 = 1 - w0 - w1
            m = (w0 >= -1e-4) & (w1 >= -1e-4) & (w2 >= -1e-4)
            if not m.any():
                continue
            iz = w0 / cv[0, 2] + w1 / cv[1, 2] + w2 / cv[2, 2]
            z = 1 / iz
            u = (w0 * cuv[0, 0] / cv[0, 2] + w1 * cuv[1, 0] / cv[1, 2] + w2 * cuv[2, 0] / cv[2, 2]) * z
            v = (w0 * cuv[0, 1] / cv[0, 2] + w1 * cuv[1, 1] / cv[1, 2] + w2 * cuv[2, 1] / cv[2, 2]) * z
            tx = np.clip((u * W).astype(int), 0, W - 1); ty = np.clip(((1 - v) * H).astype(int), 0, H - 1)
            texel = tex[ty, tx]
            m &= texel[..., 3] > 0.5
            blk = r.depth[y0:y1 + 1, x0:x1 + 1]
            m &= z < blk
            if not m.any():
                continue
            col = texel[..., :3] * lit
            if back:
                col = col * 0.4 + BACK_TINT * 0.6
            blk[m] = z[m]
            r.color[y0:y1 + 1, x0:x1 + 1][m] = col[m]


VIEWS = {   # (yaw from the aircraft's nose toward its left, pitch of the line of sight up from level)
    'below': (0, 89.0), 'below_front': (180, 35), 'below_rear': (0, 35), 'left': (-90, 0), 'right': (90, 0),
    'front': (180, 0), 'rear': (0, 0), 'below_left': (-90, 30), 'below_right': (90, 30), 'top': (0, -89.0),
    'below_fl': (-135, 30), 'below_fr': (135, 30), 'below_rl': (-45, 30), 'below_rr': (45, 30),
    'low_left': (-90, 8), 'low_right': (90, 8), 'low_front': (180, 8),
}


def view(tris, uvs, nrm, tex, focus, name, dist, size, fov=40, backs=False):
    yaw, pitch = VIEWS[name]
    # camera looks along (yaw, pitch) at the focus, from `dist` away
    y, p = math.radians(yaw), math.radians(pitch)
    f = np.array([math.sin(y) * math.cos(p), math.sin(p), math.cos(y) * math.cos(p)])
    eye = np.array(focus) - f * dist
    cam = EV.Camera(eye, yaw, pitch, fov, size, size)
    r = EV.Raster(cam, bg=tuple(int(c * 255) for c in SKY))
    draw(r, tris, uvs, nrm, tex, backs)
    im = r.image()
    ImageDraw.Draw(im).text((6, 4), name, fill=(255, 255, 255))
    return im


def render(vid, out, gear=0.0, geo_path=None, asset_path=None, views=None, size=420, zoom=1.0, focus=None, backs=False, only=None,
           skip=('pilot_view_occluder',)):
    geo = json.load(open(geo_path or os.path.join(GEO, vid + '.geo.json')))
    asset = json.load(open(asset_path or os.path.join(ASSET, vid + '.json')))
    tex = np.asarray(Image.open(os.path.join(TEX, vid + '.png')).convert('RGBA')).astype(np.float32) / 255.0
    pose = rig_pose(asset, gear)
    tris, uvs, nrm, who = posed_triangles(geo, pose, skip)
    keep = np.ones(len(tris), bool)
    if gear >= 1.0:   # legs without a Retract entry still pop out when fully retracted
        legs = {g['Bone'] for g in (asset.get('AircraftRig') or {}).get('Gear') or [] if not g.get('Retract')}
        bones = {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}
        def under(n):
            while n:
                if n in legs: return True
                n = bones[n].get('parent') if n in bones else None
            return False
        keep = np.array([not under(w) for w in who])
    if only:
        keep &= np.array([any(o in w for o in only) for w in who])
    tris, uvs, nrm = tris[keep], uvs[keep], nrm[keep]
    lo, hi = tris.reshape(-1, 3).min(0), tris.reshape(-1, 3).max(0)
    if focus is None:
        c = (lo + hi) / 2
    else:
        c = np.array(focus, float) * np.array([1, 1, -1]) / 16.0
    span = float(np.linalg.norm(hi - lo))
    dist = span * 1.45 / zoom
    names = views or ['below', 'below_fl', 'below_fr', 'below_rl', 'below_rr', 'low_left', 'low_front', 'top']
    ims = [view(tris, uvs, nrm, tex, c, n, dist, size, backs=backs) for n in names]
    cols = 4
    rows = (len(ims) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * size, rows * size), (0, 0, 0))
    for i, im in enumerate(ims):
        sheet.paste(im, ((i % cols) * size, (i // cols) * size))
    sheet.save(out)
    return sheet


def main(argv):
    vid, out = argv[0], argv[1]
    kw = {}
    a = argv[2:]
    for i, k in enumerate(a):
        if k == '--gear': kw['gear'] = float(a[i + 1])
        if k == '--geo': kw['geo_path'] = a[i + 1]
        if k == '--asset': kw['asset_path'] = a[i + 1]
        if k == '--views': kw['views'] = a[i + 1].split(',')
        if k == '--size': kw['size'] = int(a[i + 1])
        if k == '--zoom': kw['zoom'] = float(a[i + 1])
        if k == '--only': kw['only'] = a[i + 1].split(',')
        if k == '--backs': kw['backs'] = True
        if k == '--focus': kw['focus'] = [float(x) for x in a[i + 1].split(',')]
    render(vid, out, **kw)


if __name__ == '__main__':
    main(sys.argv[1:])
