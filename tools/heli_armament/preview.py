#!/usr/bin/env python3
"""Orthographic, textured previews of a helicopter with a chosen loadout hung on its pylons, drawn the way the game
places suspended stores (BvpSuspendedStoreRenderer + AircraftStoreAttachment), for checking pylon positions, launcher
fit and munition placement without the game.

    python3 tools/heli_armament/preview.py VID OUT.png [--loadout NAME] [--views front,left,below,quarter]
                                            [--markers] [--scale PX_PER_BLOCK]

VID is the mod's vehicle id (ah_64d, mi24v...). --loadout picks a named loadout from loadouts.json (default: the
first one listed for VID); `empty` shows the bare airframe. --markers draws the mount positions as small cubes.
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
ASSETS = os.path.join(GEN, 'assets', 'berts_vehicle_pack')
DATA = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw')
LIGHT = np.array([0.35, 0.8, 0.5]); LIGHT /= np.linalg.norm(LIGHT)


def resource(loc, base):
    ns, path = loc.split(':', 1)
    root = {'berts_vehicle_pack': os.path.join(GEN, 'assets' if base == 'assets' else 'data', 'berts_vehicle_pack')}[ns]
    return os.path.join(root, path)


def mesh_triangles(geo, skip=(), only=None):
    """(tris N x 3 x 3 in vehicle-local blocks: x left, y up, z forward; uvs N x 3 x 2; bone index N; names)"""
    tris, uvs, ids, names = [], [], [], []
    for bone in geo['minecraft:geometry'][0]['bones']:
        pm = bone.get('poly_mesh')
        name = bone['name']
        if not pm or not pm.get('polys') or name.startswith(tuple(skip)):
            continue
        if only is not None and not name.startswith(tuple(only)):
            continue
        names.append(name)
        pos = np.array(pm['positions'], float) * np.array([1.0, 1.0, -1.0]) / 16.0
        uv = np.array(pm['uvs'], float)
        if not pm.get('normalized_uvs'):
            d = geo['minecraft:geometry'][0]['description']
            uv = uv / np.array([d['texture_width'], d['texture_height']])
        for poly in pm['polys']:
            idx = [v[0] for v in poly]; tid = [v[2] if len(v) > 2 else 0 for v in poly]
            for k in range(1, len(idx) - 1):
                tris.append(pos[[idx[0], idx[k], idx[k + 1]]]); uvs.append(uv[[tid[0], tid[k], tid[k + 1]]])
                ids.append(len(names) - 1)
    return (np.array(tris).reshape(-1, 3, 3), np.array(uvs).reshape(-1, 3, 2), np.array(ids, int), names)


def texture(path):
    return np.asarray(Image.open(path).convert('RGBA')).astype(np.float32) / 255.0


# ------------------------------------------------------------------------------------------------ placement

def store_local(tris, store):
    """store model triangles (vehicle-local frame of the model file) relative to its anchor, as the renderer hangs
    them: v' = R(scale * (v_loader - anchor)) in the mirrored loader frame, turned back to vehicle-local"""
    a = np.array(store.get('MountAnchor', [0, 0, 0]), float)
    return anchored(tris, store, a)


def anchored(tris, store, a):
    s = float(store.get('Scale', 1.0))
    F = tris * np.array([1.0, 1.0, -1.0])               # back to model-file blocks
    if store.get('ModelForward', '-Z') == '+Z':
        out = np.stack([-F[..., 0] - a[0], F[..., 1] - a[1], F[..., 2] - a[2]], -1)
    else:
        out = np.stack([F[..., 0] + a[0], F[..., 1] - a[1], -F[..., 2] + a[2]], -1)
    return out * s


FACES = {'bottom': 'top', 'left': 'right', 'right': 'left'}


def anchor_for(store, face):
    if face == 'bottom':
        return np.array(store.get('MountAnchor', [0, 0, 0]), float)
    side = (store.get('SideMountAnchors') or {}).get('Right' if face == 'left' else 'Left')
    return np.array(side if side is not None else store.get('MountAnchor', [0, 0, 0]), float)


def mount_positions(mount):
    return [mount['Position']] if 'Position' in mount else [mount['Left'], mount['Right']]


def stations(mount):
    if 'Position' in mount:
        return [mount.get('Stations') or []]
    return [mount.get('LeftStations') or [], mount.get('RightStations') or []]


def layout(mount, store, store_id, copies):
    """[(point, face)], [(point, adapter, mirrored)] like AircraftStoreAttachment.layout"""
    positions = mount_positions(mount)
    places, adapters = [], []
    adapter = store.get('RackAdapter')
    for i, pos in enumerate(positions):
        own = [s for s in stations(mount)[i] if (s.get('Copies') is None or copies in s['Copies'])
               and (s.get('Stores') is None or store_id in s['Stores']) and store_id not in s.get('ExceptStores', [])]
        base = next((s['Point'] for s in stations(mount)[i] if s.get('Face', 'bottom') == 'bottom'
                     and s.get('Stores') is None and (s.get('Copies') is None or 1 in s['Copies'])), pos)
        if len(own) >= copies:
            places += [(np.array(s['Point'], float), s.get('Face', 'bottom')) for s in own[:copies]]
        elif adapter and len(adapter['Stations']) == copies:
            mirror = 'Left' in mount and i == 1
            adapters.append((np.array(base, float), adapter, mirror))
            for s in adapter['Stations']:
                p = np.array(s['Point'], float); f = s.get('Face', 'bottom')
                if mirror:
                    p = p * np.array([-1, 1, 1]); f = {'left': 'right', 'right': 'left'}.get(f, f)
                places.append((np.array(base, float) + p, f))
        else:
            sp = np.array(store.get('RackSpacing', [0.6, 0.4, 0.0]), float)
            cols = min(copies, store.get('RackColumns') or 3)
            for c in range(copies):
                row = c // cols; rs = min(cols, copies - row * cols)
                places.append((np.array(base, float) + np.array([(c % cols - (rs - 1) * 0.5) * sp[0], -row * sp[1],
                                                                 -row * sp[2]]), 'bottom'))
    return places, adapters


class Scene:
    def __init__(self):
        self.parts = []                  # (tris, uvs, tex)

    def add(self, tris, uvs, tex):
        if len(tris):
            self.parts.append((tris, uvs, tex))

    def bounds(self):
        P = np.concatenate([t.reshape(-1, 3) for t, _, _ in self.parts])
        return P.min(0), P.max(0)


_geo_cache = {}


def load_model(model_loc, tex_loc):
    key = (model_loc, tex_loc)
    if key not in _geo_cache:
        geo = json.load(open(resource(model_loc, 'assets')))
        tris, uvs, _, _ = mesh_triangles(geo)
        _geo_cache[key] = (tris, uvs, texture(resource(tex_loc, 'assets')))
    return _geo_cache[key]


def hang(scene, armament, selections, counts=None):
    counts = counts or {}
    mounts = {m['Id']: m for m in armament.get('Pairs', []) + armament.get('Singles', [])}
    for mid, sid in selections.items():
        mount = mounts[mid]
        store = json.load(open(resource(sid.replace(':', ':sbw/aircraft_stores/', 1) + '.json', 'data')))
        if store.get('Model') is None:
            continue
        copies = counts.get(mid) or store.get('FixedRackCount') or 1
        places, adapters = layout(mount, store, sid, copies)
        tris, uvs, tex = load_model(store['Model'], store['Texture'])
        for p, face in places:
            scene.add(p + anchored(tris, store, anchor_for(store, face)), uvs, tex)
        for base, adapter, mirror in adapters:
            at, au, ax = load_model(adapter['Model'], adapter['Texture'])
            a = np.array(adapter.get('MountAnchor', [0, 0, 0]), float)
            local = anchored(at, {'ModelForward': '-Z'}, a)
            scene.add(base + local, au, ax)


def cube(scene, p, size=0.08, colour=(1.0, 0.1, 0.8)):
    tex = np.ones((1, 1, 4), np.float32); tex[0, 0, :3] = colour
    c = np.array(p, float); h = size / 2
    corners = np.array([[x, y, z] for x in (-h, h) for y in (-h, h) for z in (-h, h)]) + c
    faces = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    tris = []
    for a, b, cc, d in faces:
        tris += [corners[[a, b, cc]], corners[[a, cc, d]]]
    scene.add(np.array(tris), np.zeros((len(tris), 3, 2)), tex)


# ------------------------------------------------------------------------------------------------ raster

VIEWS = {   # (forward direction the camera looks along, up)
    'front': ((0, 0, -1), (0, 1, 0)), 'rear': ((0, 0, 1), (0, 1, 0)),
    'left': ((-1, 0, 0), (0, 1, 0)), 'right': ((1, 0, 0), (0, 1, 0)),
    'below': ((0, 1, 0), (0, 0, 1)), 'top': ((0, -1, 0), (0, 0, 1)),
    'quarter': ((-0.62, -0.38, -0.68), (0, 1, 0)), 'quarter_low': ((-0.6, 0.3, -0.74), (0, 1, 0)),
}


def render(scene, view, px_per_block=None, size=None, centre=None, extent=None, bg=(196, 204, 212)):
    f, up = (np.array(v, float) for v in VIEWS[view] if True)
    f /= np.linalg.norm(f)
    right = np.cross(f, up); right /= np.linalg.norm(right)          # x is left, so f x up points screen-right
    up = np.cross(right, f)
    R = np.stack([right, up, f])
    lo, hi = scene.bounds()
    if centre is None:
        centre = (lo + hi) / 2
    pts = np.concatenate([t.reshape(-1, 3) for t, _, _ in scene.parts]) - centre
    v = pts @ R.T
    ext = extent if extent is not None else np.abs(v[:, :2]).max(0) * 1.04 + 0.05
    if size is None:
        s = px_per_block or 120
        size = (int(2 * ext[0] * s) + 8, int(2 * ext[1] * s) + 8)
    W, H = size
    scale = min((W - 8) / (2 * ext[0]), (H - 8) / (2 * ext[1]))
    depth = np.full((H, W), np.inf); colour = np.zeros((H, W, 3)); colour[:] = np.array(bg) / 255.0
    for tris, uvs, tex in scene.parts:
        TH, TW = tex.shape[:2]
        V = (tris - centre) @ R.T
        S = np.stack([W / 2 + V[..., 0] * scale, H / 2 - V[..., 1] * scale], -1)
        N = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
        L = np.linalg.norm(N, axis=1); ok = L > 1e-12
        N[ok] /= L[ok, None]
        lit = 0.5 + 0.5 * np.abs(N @ LIGHT)
        for k in np.nonzero(ok)[0]:
            s = S[k]; z = V[k, :, 2]
            x0 = max(0, int(math.floor(s[:, 0].min()))); x1 = min(W - 1, int(math.ceil(s[:, 0].max())))
            y0 = max(0, int(math.floor(s[:, 1].min()))); y1 = min(H - 1, int(math.ceil(s[:, 1].max())))
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
            m = (w0 >= -1e-3) & (w1 >= -1e-3) & (w2 >= -1e-3)
            if not m.any():
                continue
            zz = w0 * z[0] + w1 * z[1] + w2 * z[2]
            uv = uvs[k]
            u = w0 * uv[0, 0] + w1 * uv[1, 0] + w2 * uv[2, 0]
            vv = w0 * uv[0, 1] + w1 * uv[1, 1] + w2 * uv[2, 1]
            tx = np.clip((u * TW).astype(int), 0, TW - 1); ty = np.clip(((1 - vv) * TH).astype(int), 0, TH - 1)
            texel = tex[ty, tx]
            m &= texel[..., 3] > 0.5
            blk = depth[y0:y1 + 1, x0:x1 + 1]
            m &= zz < blk
            if m.any():
                blk[m] = zz[m]
                colour[y0:y1 + 1, x0:x1 + 1][m] = texel[..., :3][m] * lit[k]
    return Image.fromarray((np.clip(colour, 0, 1) * 255).astype(np.uint8))


ROTORS = ('main_rotor', 'tail_rotor', 'propeller', 'tailPropeller', 'contraPropeller', 'rotor')


def vehicle_scene(vid, skip=('suspended_', 'pilot_view_occluder') + ROTORS, region=None):
    geo = json.load(open(os.path.join(ASSETS, 'custom_geo', vid + '.geo.json')))
    tris, uvs, _, _ = mesh_triangles(geo, skip)
    if region is not None:
        c = tris.mean(1); lo, hi = np.array(region[0::2]), np.array(region[1::2])
        keep = np.all((c >= lo) & (c <= hi), axis=1); tris, uvs = tris[keep], uvs[keep]
    sc = Scene(); sc.add(tris, uvs, texture(os.path.join(ASSETS, 'textures', 'entity', vid + '.png')))
    return sc


def sheet(images, labels, cols=2, pad=6):
    w = max(i.width for i in images); h = max(i.height for i in images)
    rows = (len(images) + cols - 1) // cols
    out = Image.new('RGB', (cols * (w + pad) + pad, rows * (h + pad + 16) + pad), (255, 255, 255))
    d = ImageDraw.Draw(out)
    for k, (im, lab) in enumerate(zip(images, labels)):
        x = pad + (k % cols) * (w + pad); y = pad + (k // cols) * (h + pad + 16)
        d.text((x, y), lab, fill=(0, 0, 0)); out.paste(im, (x, y + 14))
    return out


def main(argv):
    vid, out = argv[0], argv[1]
    opts = {argv[i]: argv[i + 1] for i in range(2, len(argv) - 1) if argv[i].startswith('--')}
    views = opts.get('--views', 'front,left,below,quarter').split(',')
    region = [float(x) for x in opts['--region'].split(',')] if '--region' in opts else None
    sc = vehicle_scene(vid, region=region)
    arm_path = os.path.join(DATA, 'aircraft_armaments', vid + '.json')
    arm = json.load(open(arm_path)) if os.path.exists(arm_path) else {}
    name = opts.get('--loadout')
    loadouts = json.load(open(os.path.join(HERE, 'loadouts.json'))).get(vid, {}) if os.path.exists(
        os.path.join(HERE, 'loadouts.json')) else {}
    if name != 'empty' and loadouts:
        name = name or next(iter(loadouts))
        hang(sc, arm, loadouts[name])
    if '--markers' in argv:
        for m in arm.get('Pairs', []) + arm.get('Singles', []):
            for p in mount_positions(m):
                cube(sc, p)
    ppb = float(opts.get('--scale', 70))
    ims = [render(sc, v, px_per_block=ppb) for v in views]
    sheet(ims, [f'{vid} {name or ""} {v}' for v in views]).save(out)


if __name__ == '__main__':
    main(sys.argv[1:])
