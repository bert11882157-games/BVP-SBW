#!/usr/bin/env python3
"""Builds store and flight models for missiles that had none (AGM-88 HARM, AIM-120 AMRAAM).

Bodies of revolution with cruciform wings and tail fins, sized from published dimensions (1 block = 1 m,
16 px per block). Flat colour cells in a small texture (vertical stripes, so the loader's v flip does not matter).
Store models: nose at -Z, centred on the body. Flight models: +Y forward, origin at the nozzle (the FFA track pose).

Usage: python3 tools/munition_models/generate.py [--write]
"""
import json, math, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/aircraft_stores')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/aircraft_stores')
STORES = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/aircraft_stores')
PX = 16.0
SEG = 16

# colour cells (column index in the texture)
COLOURS = {'body': (201, 204, 206), 'radome': (228, 228, 222), 'dark': (70, 72, 74), 'yellow': (214, 176, 40),
           'brown': (120, 84, 50), 'fin': (190, 193, 196), 'nozzle': (40, 40, 42)}
CELLS = list(COLOURS)

MISSILES = {
    # AIM-120C AMRAAM: 3.65 m long, 0.178 m diameter, clipped-delta mid wings (span 0.447 m), tail fins 0.45 m.
    'aim120': dict(store='fa18e/aim120', length=3.65, diameter=0.178,
                   nose=0.55, boattail=0.08, tail_r=0.8,
                   bands=[(0.62, 0.66, 'yellow'), (0.70, 0.74, 'brown')], radome=0.42,
                   wings=[dict(root=(1.28, 1.70), tip=(1.48, 1.60), span=0.447 / 2, roll=45)],
                   fins=[dict(root=(3.28, 3.62), tip=(3.44, 3.62), span=0.45 / 2, roll=45)]),
    # AGM-88 HARM: 4.17 m long, 0.254 m diameter, double-delta mid wings (span 1.12 m), tail fins 0.64 m.
    'agm88': dict(store='fa18e/agm88', length=4.17, diameter=0.254,
                  nose=0.62, boattail=0.1, tail_r=0.85,
                  bands=[(0.70, 0.75, 'yellow'), (0.80, 0.85, 'brown')], radome=0.48,
                  wings=[dict(root=(1.20, 2.35), tip=(2.05, 2.35), span=1.12 / 2, roll=45)],
                  fins=[dict(root=(3.70, 4.12), tip=(3.92, 4.12), span=0.64 / 2, roll=45)]),
}


class Mesh:
    def __init__(self):
        self.positions, self.normals, self.uvs, self.polys = [], [], [], []

    def uv(self, colour):
        i = CELLS.index(colour)
        return [round((i + 0.5) / len(CELLS), 5), 0.5]

    def poly(self, pts, colour):
        pts = [np.asarray(p, float) for p in pts]
        n = np.cross(pts[1] - pts[0], pts[2] - pts[0])
        if np.linalg.norm(n) < 1e-9 and len(pts) > 3:
            n = np.cross(pts[2] - pts[0], pts[3] - pts[0])
        if np.linalg.norm(n) < 1e-9:
            return
        n = n / np.linalg.norm(n)
        self.normals.append([round(float(c), 4) for c in n]); ni = len(self.normals) - 1
        self.uvs.append(self.uv(colour)); ui = len(self.uvs) - 1
        idx = []
        for p in pts:
            self.positions.append([round(float(c), 4) for c in p])
            idx.append([len(self.positions) - 1, ni, ui])
        self.polys.append(idx)


def build(spec):
    """Store frame, pixels: nose at -Z, body centred on the origin."""
    L = spec['length'] * PX; r = spec['diameter'] / 2 * PX
    z0 = -L / 2
    m = Mesh()
    # profile: (distance from nose in m, radius factor)
    prof = []
    for i in range(9):                              # tangent ogive nose
        t = i / 8
        prof.append((spec['nose'] * t, math.sqrt(max(0.0, 1 - (1 - t) ** 2)) * 0.98 + 0.02 * (i > 0)))
    prof.append((spec['length'] - spec['boattail'], 1.0))
    prof.append((spec['length'], spec['tail_r']))
    # split the cylinder at the band edges so each band gets its own colour
    cuts = sorted({round(spec['length'] * a, 4) for b in spec['bands'] for a in b[:2]} | {spec['radome']})
    full = []
    for (s0, f0), (s1, f1) in zip(prof, prof[1:]):
        full.append((s0, f0))
        for c in cuts:
            if s0 < c < s1:
                full.append((c, f0 + (f1 - f0) * (c - s0) / (s1 - s0)))
    full.append(prof[-1])

    def colour_at(s):
        if s < spec['radome']:
            return 'radome' if s > 0.06 else 'dark'
        for a, b, c in spec['bands']:
            if spec['length'] * a <= s < spec['length'] * b:
                return c
        return 'body'

    ring = lambda s, f: [np.array([r * f * math.cos(2 * math.pi * k / SEG), r * f * math.sin(2 * math.pi * k / SEG),
                                   z0 + s * PX]) for k in range(SEG)]
    for (s0, f0), (s1, f1) in zip(full, full[1:]):
        a, b = ring(s0, f0), ring(s1, f1)
        col = colour_at((s0 + s1) / 2)
        for k in range(SEG):
            k2 = (k + 1) % SEG
            if f0 < 1e-3:
                m.poly([a[k], b[k2], b[k]], col)
            else:
                m.poly([a[k], a[k2], b[k2], b[k]], col)
    # nozzle cap
    last = ring(*full[-1])
    centre = np.array([0.0, 0.0, z0 + spec['length'] * PX])
    for k in range(SEG):
        m.poly([last[k], centre, last[(k + 1) % SEG]], 'nozzle')

    def plate(fin, roll):
        th = 0.35
        rr = r * 0.95; tip = fin['span'] * PX
        (r0, r1), (t0, t1) = fin['root'], fin['tip']
        ca, sa = math.cos(math.radians(roll)), math.sin(math.radians(roll))
        radial = np.array([ca, sa, 0.0]); side = np.array([-sa, ca, 0.0])
        pts = [radial * rr + np.array([0, 0, z0 + r0 * PX]), radial * rr + np.array([0, 0, z0 + r1 * PX]),
               radial * tip + np.array([0, 0, z0 + t1 * PX]), radial * tip + np.array([0, 0, z0 + t0 * PX])]
        top = [p + side * th / 2 for p in pts]; bot = [p - side * th / 2 for p in pts]
        m.poly(top, 'fin'); m.poly(bot[::-1], 'fin')
        for i in range(4):
            j = (i + 1) % 4
            m.poly([top[i], bot[i], bot[j], top[j]], 'fin')

    for group in spec['wings'] + spec['fins']:
        for q in range(4):
            plate(group, group['roll'] + 90 * q)
    return m, L, r


def geo(mesh, ident, flight, L):
    pos = mesh.positions
    normals = mesh.normals
    if flight:
        # store (x, y, z) with nose at -Z -> flight (x, -z, y) shifted so the nozzle sits at y = 0
        pos = [[p[0], round(L / 2 - p[2], 4), p[1]] for p in pos]
        normals = [[n[0], -n[2], n[1]] for n in normals]
    P = np.array(pos)
    return {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': ident, 'texture_width': 16 * len(CELLS), 'texture_height': 16,
                        'visible_bounds_width': 8, 'visible_bounds_height': 8, 'visible_bounds_offset': [0, 0, 0]},
        'bones': [{'name': 'store', 'pivot': [0, 0, 0], 'poly_mesh': {
            'normalized_uvs': True, 'positions': pos, 'normals': normals, 'uvs': mesh.uvs, 'polys': mesh.polys}}]}]}


def texture():
    im = Image.new('RGBA', (16 * len(CELLS), 16))
    for i, name in enumerate(CELLS):
        im.paste(COLOURS[name] + (255,), (16 * i, 0, 16 * (i + 1), 16))
    return im


def main(argv):
    write = '--write' in argv
    for mid, spec in MISSILES.items():
        mesh, L, r = build(spec)
        print(f'{mid}: {len(mesh.polys)} polys, length {L:.1f} px, radius {r:.2f} px')
        if not write:
            continue
        for flight in (False, True):
            name = mid + ('_flight' if flight else '')
            with open(os.path.join(GEO, name + '.geo.json'), 'w') as fh:
                json.dump(geo(mesh, 'geometry.aircraft_store.' + name, flight, L), fh, separators=(',', ':'))
        texture().save(os.path.join(TEX, mid + '.png'))
        sp = os.path.join(STORES, spec['store'] + '.json')
        raw = open(sp).read(); d = json.loads(raw)
        rb = round(r / PX, 4)
        new = {}
        for k, v in d.items():
            new[k] = v
            if k == 'Category':
                new['Model'] = f'berts_vehicle_pack:custom_geo/aircraft_stores/{mid}.geo.json'
                new['ModelForward'] = '-Z'
                new['MountAnchor'] = [0, rb, 0]
                new['SideMountAnchors'] = {'Left': [rb, 0.0, 0], 'Right': [-rb, 0.0, 0]}
                new['MountAxis'] = [0.0, 0.0, 0]
                new['Texture'] = f'berts_vehicle_pack:textures/aircraft_stores/{mid}.png'
        new.setdefault('LaunchOffset', [0.0, -rb, 0.0])
        for f in [sp] + ([sp.replace('.json', '_x2.json')] if os.path.exists(sp.replace('.json', '_x2.json')) else []):
            raw = open(f).read(); d = json.loads(raw)
            for k in ('Model', 'ModelForward', 'MountAnchor', 'SideMountAnchors', 'MountAxis', 'Texture', 'LaunchOffset'):
                d.pop(k, None)
            out = {}
            for k, v in d.items():
                out[k] = v
                if k == 'Category':
                    for kk in ('Model', 'ModelForward', 'MountAnchor', 'SideMountAnchors', 'MountAxis', 'Texture'):
                        out[kk] = new[kk]
            out['LaunchOffset'] = new['LaunchOffset']
            indent = 2 if raw.startswith('{\n') else None
            open(f, 'w').write(json.dumps(out, indent=indent, ensure_ascii=False) + ('\n' if raw.endswith('\n') else ''))
            print('  store', os.path.relpath(f, STORES))


if __name__ == '__main__':
    main(sys.argv[1:])
