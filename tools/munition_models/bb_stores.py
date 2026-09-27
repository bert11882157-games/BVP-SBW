#!/usr/bin/env python3
"""Store and flight models for aircraft munitions from the provided Blockbench sources.

  python3 tools/munition_models/bb_stores.py <folder with the .bbmodel files> [--write] [ids...]

Each source is re-oriented (its nose axis in MUNITIONS), centred on its body axis and scaled to the published
length (16 px per metre):
  store model  custom_geo/aircraft_stores/<id>.geo.json         nose -Z, body axis on the origin
  flight model custom_geo/aircraft_stores/<id>_flight.geo.json  +Y forward, nozzle (tail) at y = 0 (FFA track pose)
  texture      textures/aircraft_stores/<id>.png                the embedded texture (a flat body colour if none)
Stores that use the model (STORES) are pointed at it with Scale removed; run tools/aircraft_attach/attach.py apply
afterwards to re-derive their anchors from the new geometry. Faces are wound counter-clockwise seen from outside
their element. Idempotent.
"""
import io, json, os, sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)
import bbmodel  # noqa: E402

GEN = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets', 'berts_vehicle_pack', 'custom_geo', 'aircraft_stores')
TEX = os.path.join(GEN, 'assets', 'berts_vehicle_pack', 'textures', 'aircraft_stores')
STORE_DATA = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw', 'aircraft_stores')
NS = 'berts_vehicle_pack'
PX = 16.0

# nose: the source axis the nose points along. length: published length in metres. roll180: lugs were below.
MUNITIONS = {
    'agm88': dict(source='AGM-88 HARM.bbmodel', nose='+Y', length=4.17),
    'aim120': dict(source='AIM-120.bbmodel', nose='-Z', length=3.65, colour=(201, 204, 206)),
    'agm114': dict(source='AGM-114.bbmodel', nose='+Y', length=1.63, roll180=True),   # launch lug on top
}
# store definitions (relative to aircraft_stores/) shown with each model
STORES = {
    'agm88': ['fa18e/agm88.json'],
    'aim120': ['fa18e/aim120.json'],
    'agm114': ['ah_64d/agm114_proxy.json'],
}
# projectile profiles whose flying mesh (projectile_mesh_v1) is the store model (nose -Z, centred, full size)
PROFILES = {
    'agm114': ['aircraft_stores/agm114k_ah64d.json'],
}
PROFILE_DATA = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw', 'projectile_profiles')

# source axis -> rotation taking it to store -Z (proper rotations)
TO_STORE = {
    '-Z': np.eye(3),
    '+Z': np.diag([-1.0, 1.0, -1.0]),                                  # yaw 180
    '+Y': np.array([[1.0, 0, 0], [0, 0, 1.0], [0, -1.0, 0]]),         # (x, z, -y)
    '-Y': np.array([[1.0, 0, 0], [0, 0, -1.0], [0, 1.0, 0]]),         # (x, -z, y)
    '+X': np.array([[0, 0, 1.0], [0, 1.0, 0], [-1.0, 0, 0]]),         # (z, y, -x)
    '-X': np.array([[0, 0, -1.0], [0, 1.0, 0], [1.0, 0, 0]]),         # (-z, y, x)
}


def convert(path, spec):
    model = bbmodel.load(path)
    tex = bbmodel.textures(model)
    polys = bbmodel.polys(model)
    R = TO_STORE[spec['nose']]
    if spec.get('roll180'):
        R = np.diag([-1.0, -1.0, 1.0]) @ R
    P_all = np.vstack([p for p, _, _, _ in polys]) @ R.T
    z0, z1 = P_all[:, 2].min(), P_all[:, 2].max()
    s = spec['length'] * PX / (z1 - z0)
    # body axis: centre of the cross-section of the points in the middle 60% of the length, trimmed of fins by
    # taking the 20th/80th percentile extent
    mid = P_all[(P_all[:, 2] > z0 + 0.2 * (z1 - z0)) & (P_all[:, 2] < z1 - 0.2 * (z1 - z0))]
    cx = (np.percentile(mid[:, 0], 20) + np.percentile(mid[:, 0], 80)) / 2
    cy = (np.percentile(mid[:, 1], 20) + np.percentile(mid[:, 1], 80)) / 2
    centre = np.array([cx, cy, (z0 + z1) / 2])

    img, tw, th = (tex[0] if tex else (None, 16, 16))
    if img is None:
        img = Image.new('RGBA', (16, 16), tuple(spec.get('colour', (190, 190, 190))) + (255,))
        tw = th = 16
    positions, normals, uvs, out_polys = [], [], [], []
    element_centre = {}
    for P, _, _, e in polys:
        element_centre.setdefault(e, []).append(P)
    element_centre = {e: (np.vstack(v) @ R.T - centre).mean(0) * s for e, v in element_centre.items()}
    for P, U, t, e in polys:
        q = (P @ R.T - centre) * s
        n = np.cross(q[1] - q[0], q[2] - q[1])
        if len(q) == 4 and np.linalg.norm(n) < 1e-9:
            n = np.cross(q[2] - q[0], q[3] - q[2])
        ln = np.linalg.norm(n)
        if ln < 1e-9:
            continue
        n = n / ln
        # outward: away from the centre of the face's own element
        ref = q.mean(0) - element_centre[e]
        order = list(range(len(q)))
        if np.dot(n, ref) < 0:
            order, n = order[::-1], -n
        if not tex or t is None or isinstance(t, bool):
            uv_face = [(0.5, 0.5)] * len(q)
        else:
            uv_face = [(U[i][0] / tw, 1 - U[i][1] / th) for i in range(len(q))]
        normals.append([round(float(v), 5) for v in n])
        poly = []
        for i in order:
            positions.append([round(float(v), 5) for v in q[i]])
            uvs.append([round(float(uv_face[i][0]), 6), round(float(uv_face[i][1]), 6)])
            poly.append([len(positions) - 1, len(normals) - 1, len(uvs) - 1])
        out_polys.append(poly)
    mesh = {'normalized_uvs': True, 'positions': positions, 'normals': normals, 'uvs': uvs, 'polys': out_polys}
    return mesh, img, s, (z1 - z0) * s


def geo(mesh, ident, flight, length, tw, th):
    pos, normals = mesh['positions'], mesh['normals']
    if flight:
        pos = [[p[0], round(length / 2 - p[2], 5), p[1]] for p in pos]
        normals = [[n[0], -n[2], n[1]] for n in normals]
    return {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': ident, 'texture_width': tw, 'texture_height': th,
                        'visible_bounds_width': 8, 'visible_bounds_height': 8, 'visible_bounds_offset': [0, 0, 0]},
        'bones': [{'name': 'store', 'pivot': [0, 0, 0], 'poly_mesh': dict(mesh, positions=pos, normals=normals)}]}]}


def main(argv):
    write = '--write' in argv
    args = [a for a in argv if not a.startswith('--')]
    folder, ids = args[0], args[1:] or list(MUNITIONS)
    for mid in ids:
        spec = MUNITIONS[mid]
        mesh, img, s, length = convert(os.path.join(folder, spec['source']), spec)
        P = np.array(mesh['positions'])
        print(f'{mid}: {len(mesh["polys"])} faces, {s:.3f} px/unit, length {length / PX:.2f} m, '
              f'span x {(P[:, 0].max() - P[:, 0].min()) / PX:.2f} y {(P[:, 1].max() - P[:, 1].min()) / PX:.2f} m')
        if not write:
            continue
        for flight in (False, True):
            name = mid + ('_flight' if flight else '')
            with open(os.path.join(GEO, name + '.geo.json'), 'w') as fh:
                json.dump(geo(mesh, 'geometry.aircraft_store.' + name, flight, length, img.width, img.height), fh,
                          separators=(',', ':'))
        img.save(os.path.join(TEX, mid + '.png'))
        for rel in STORES.get(mid, []):
            path = os.path.join(STORE_DATA, rel)
            raw = open(path).read()
            d = json.loads(raw)
            d['Model'] = f'{NS}:custom_geo/aircraft_stores/{mid}.geo.json'
            d['Texture'] = f'{NS}:textures/aircraft_stores/{mid}.png'
            d['ModelForward'] = '-Z'
            d.pop('Scale', None)
            indent = 2 if raw.startswith('{\n') else None
            new = json.dumps(d, indent=indent, ensure_ascii=False) + ('\n' if raw.endswith('\n') else '')
            if new != raw:
                open(path, 'w').write(new)
                print('  store', rel)
        for rel in PROFILES.get(mid, []):
            path = os.path.join(PROFILE_DATA, rel)
            raw = open(path).read()
            d = json.loads(raw)
            ext = d.setdefault('Extensions', {}).setdefault(f'{NS}:projectile_mesh_v1', {})
            ext.update({'Model': f'{NS}:custom_geo/aircraft_stores/{mid}.geo.json',
                        'Texture': f'{NS}:textures/aircraft_stores/{mid}.png', 'ForwardYaw': 0})
            ext.setdefault('SpinDegreesPerTick', 0)
            d['RenderScale'] = 1
            new = json.dumps(d, indent=2 if raw.startswith('{\n') else None, ensure_ascii=False) + \
                ('\n' if raw.endswith('\n') else '')
            if new != raw:
                open(path, 'w').write(new)
                print('  profile', rel)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
