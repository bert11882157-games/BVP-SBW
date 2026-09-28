#!/usr/bin/env python3
"""Flight models for wire / beam-riding ATGMs from SMP Toolbox .mtb sources (TOW, Kornet), and the projectile
profiles that show them.

  python3 tools/munition_models/atgm_mtb.py <folder with the .mtb files> [--write]

Each source is a 'Generic Java Model' with the missile's long axis on toolbox Y, nose at +Y. Output (the
projectile_mesh_v1 convention of BvpSpinningProjectileRenderer, ForwardAxis -Z, Origin CENTER): nose at -Z, body
axis through the origin, scaled so the model's length is the missile's published length (16 px per metre).
Faces keep the embedded texture; each face is wound counter-clockwise seen from outside its element.

Every projectile profile whose RoundId names the missile (ROUNDS) gets the extension
berts_vehicle_pack:projectile_mesh_v1 -> this model. Idempotent; other profile fields are left alone.
"""
import glob, json, os, sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, os.path.join(REPO, 'tools', 'mtb_wheels'))
import mtb  # noqa: E402

GEN = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets', 'berts_vehicle_pack', 'custom_geo', 'atgm')
TEX = os.path.join(GEN, 'assets', 'berts_vehicle_pack', 'textures', 'atgm')
PROFILES = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw', 'projectile_profiles')
EXTENSION = 'berts_vehicle_pack:projectile_mesh_v1'
PX = 16.0

MISSILES = {
    # BGM-71 TOW: 1.17 m (probe retracted), 152 mm. Roll-stabilised: no spin.
    'tow': dict(source='TOW.mtb', length=1.17, spin=0.0, rounds=('bgm71',)),
    # 9M133 Kornet: 1.20 m, 152 mm. Rolls in flight at the pack's 2 Hz ATGM rate (36 degrees per tick), the same as
    # the exhaust swirl.
    'kornet': dict(source='Kornet Elite.mtb', length=1.20, spin=36.0, rounds=('9m133', 'kornet')),
}


def build(path, length):
    (tw, th), elements, png = mtb.read(path)
    corners = np.array([p for f in elements for p in mtb.box_corners(f)])
    y0, y1 = corners[:, 1].min(), corners[:, 1].max()
    s = length * PX / (y1 - y0)
    mid = (y0 + y1) / 2

    def to_model(p):
        # toolbox (x, y, z), nose +Y -> model (x, z, -(y - mid)): a proper rotation (no mirror), nose at -Z
        return [p[0] * s, p[2] * s, -(p[1] - mid) * s]

    positions, normals, uvs, polys = [], [], [], []
    for f in elements:
        faces = mtb.element_polys(f, tw, th)
        centre = np.mean([to_model(p) for pos, _ in faces for p in pos], axis=0)
        for pos, uv in faces:
            P = [to_model(p) for p in pos]
            q = np.array(P)
            n = np.cross(q[1] - q[0], q[2] - q[1])
            if len(P) == 4 and np.linalg.norm(n) < 1e-9:
                n = np.cross(q[2] - q[0], q[3] - q[2])
            ln = np.linalg.norm(n)
            if ln < 1e-9:
                continue  # zero-area face of a flat (fin) element
            n = n / ln
            if np.dot(n, q.mean(0) - centre) < 0:
                P, uv, n = P[::-1], uv[::-1], -n
            normals.append([round(float(c), 5) for c in n])
            poly = []
            for p, t in zip(P, uv):
                positions.append([round(float(c), 5) for c in p])
                uvs.append([round(t[0] / tw, 6), round(1 - t[1] / th, 6)])
                poly.append([len(positions) - 1, len(normals) - 1, len(uvs) - 1])
            polys.append(poly)
    return (tw, th), png, {'normalized_uvs': True, 'positions': positions, 'normals': normals, 'uvs': uvs,
                           'polys': polys}, s


def main(argv):
    write = '--write' in argv
    args = [a for a in argv if not a.startswith('--')]
    if not args:
        print(__doc__)
        return 2
    folder = args[0]
    for name, spec in MISSILES.items():
        (tw, th), png, mesh, s = build(os.path.join(folder, spec['source']), spec['length'])
        p = np.array(mesh['positions'])
        span = p.max(0) - p.min(0)
        geo = {'format_version': '1.12.0', 'minecraft:geometry': [{
            'description': {'identifier': f'geometry.atgm.{name}', 'texture_width': tw, 'texture_height': th,
                            'visible_bounds_width': 3, 'visible_bounds_height': 3, 'visible_bounds_offset': [0, 0, 0]},
            'bones': [{'name': 'missile', 'pivot': [0, 0, 0], 'poly_mesh': mesh}]}]}
        model = f'berts_vehicle_pack:custom_geo/atgm/{name}.geo.json'
        texture = f'berts_vehicle_pack:textures/atgm/{name}.png'
        print(f'{name}: {len(mesh["polys"])} faces, {s:.3f} px per toolbox unit, '
              f'size x {span[0] / PX:.3f} y {span[1] / PX:.3f} z {span[2] / PX:.3f} m, texture {tw}x{th}')
        patched = []
        for path in sorted(glob.glob(os.path.join(PROFILES, '**', '*.json'), recursive=True)):
            data = json.load(open(path))
            round_id = ((data.get('Combat') or {}).get('RoundId') or '').split(':')[-1]
            if not any(k in round_id for k in spec['rounds']):
                continue
            ext = data.setdefault('Extensions', {})
            want = {'Model': model, 'Texture': texture, 'ForwardYaw': 0, 'SpinDegreesPerTick': spec['spin']}
            if ext.get(EXTENSION) != want:
                ext[EXTENSION] = want
                patched.append(path)
                if write:
                    with open(path, 'w') as f:
                        json.dump(data, f, indent=2)
                        f.write('\n')
        print(f'  profiles {"patched" if write else "to patch"}: ' +
              (', '.join(os.path.relpath(x, PROFILES) for x in patched) or 'none'))
        if write:
            os.makedirs(GEO, exist_ok=True)
            os.makedirs(TEX, exist_ok=True)
            with open(os.path.join(GEO, f'{name}.geo.json'), 'w') as f:
                json.dump(geo, f, separators=(',', ':'))
            with open(os.path.join(TEX, f'{name}.png'), 'wb') as f:
                f.write(png)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
