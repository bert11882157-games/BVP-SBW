"""Adds shell-contact OBBs wherever a vehicle's authored armor sticks out of its SBW OBBs.

    python3 tools/vehgen/obb_armor_cover.py [--check] [ids...]

A shell only reaches BVP's armor resolver after SBW's OBB test reports a contact with the vehicle. The in-game
hit registration test on the T-90A (r43) found shells flying straight through the turret cheeks: the turret OBB was
39 px wide, the turret with its cheeks 67 px. Every armor volume must lie inside an OBB of its frame, or a shot at it
does not register at all.

Growing the existing boxes would also catch shells in the empty space around the armor (an invisible wall, since a
strict profile stops a shell that contacts an OBB but meets no plate), and the first hull body box also sets the
ground-movement footprint (BvpMovementCollisionBounds). So the existing OBBs are left alone. For the armor points
that stick out, extra OBBs are added in the same frame: one per face of the base box that armor crosses, spanning
from that face to the farthest armor point and only as wide and tall as the armor that sticks out there. They go
after every existing OBB, so the movement footprint (the first body box) is unchanged.

Frames: turret = turret- and barrel-frame (at rest) volumes against the Turret-transform OBBs; hull = hull volumes
(not tracks) against the Vehicle-transform OBBs, clamped to the model's hull bone. Armor-profile p -> vehicle data
(-p.x, p.y, -p.z) ((p.x, p.y, -p.z) for the X-mirrored t72a/t72b); turret boxes are relative to TurretPos.
A second run finds nothing to do.
"""
import glob
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
DATA = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack')
GEO = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack', 'custom_geo')
MESH = os.path.join(REPO, 'bvp', 'src', 'main', 'resources', 'data', 'berts_vehicle_pack', 'armor_mesh')
MIRRORED = {'t72a', 't72b'}
MARGIN = 0.02     # blocks around the armor
TOLERANCE = 0.01  # armor within this of an OBB counts as covered
LISTS = ('plates', 'explosive_reactive_armor', 'engines', 'ammo_racks', 'sensitive_internals', 'modules')
KIND = {'plates': 'plate', 'explosive_reactive_armor': 'era', 'engines': 'engine', 'ammo_racks': 'ammo',
        'sensitive_internals': 'internal', 'modules': 'module'}
MAX_EXTRA = 6


def rot(v, deg):
    x, y, z = v
    for axis, a in zip('xyz', deg):
        c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
        if axis == 'x':
            y, z = y * c - z * s, y * s + z * c
        elif axis == 'y':
            x, z = x * c + z * s, -x * s + z * c
        else:
            x, y = x * c - y * s, x * s + y * c
    return np.array([x, y, z])


def armor_points(vid, armor, frames):
    """Vehicle-data points (blocks) of every armor volume in the given frames."""
    pts = []
    mesh_kinds = set()
    mesh = os.path.join(MESH, f'{vid}.geo.json')
    sign = 1 if vid in MIRRORED else -1
    if os.path.exists(mesh) and armor.get('armor_mesh', True) is not False:
        bones = json.load(open(mesh))['minecraft:geometry'][0]['bones']
        mesh_kinds = {b['name'].split('__')[0] for b in bones if '__' in b['name']}
        for b in bones:
            if b.get('parent') in {f'armor_{f}' for f in frames} and b.get('poly_mesh') \
                    and not b['name'].startswith('track__'):
                pts += [[sign * p[0] / 16, p[1] / 16, p[2] / 16] for p in b['poly_mesh']['positions']]
    for key in LISTS:
        if KIND[key] in mesh_kinds:
            continue  # the mesh replaces this category
        for item in armor.get(key, []):
            if item.get('frame', 'hull') not in frames:
                continue
            c, h, r = np.array(item['center']), np.array(item['half_size']), item.get('rotation', [0, 0, 0])
            for sx in (-1, 1):
                for sy in (-1, 1):
                    for sz in (-1, 1):
                        pts.append(c + rot(h * [sx, sy, sz], r))
    if not pts:
        return np.zeros((0, 3))
    P = np.array(pts, float)
    return np.column_stack([P[:, 0] if vid in MIRRORED else -P[:, 0], P[:, 1], -P[:, 2]])


def model_hull_bounds(vid):
    path = os.path.join(GEO, f'{vid}.geo.json')
    if not os.path.exists(path):
        return None
    for b in json.load(open(path))['minecraft:geometry'][0]['bones']:
        if b['name'] == 'hull' and b.get('poly_mesh'):
            P = np.array(b['poly_mesh']['positions'], float) / 16
            P[:, 2] *= -1
            return P.min(0), P.max(0)
    return None


def outside(points, boxes):
    """Distance of each point outside the union of the boxes (0 inside any)."""
    d = np.full(len(points), np.inf)
    for c, h in boxes:
        d = np.minimum(d, np.max(np.maximum(np.abs(points - c) - h, 0), axis=1))
    return d


def extras(points, base, boxes):
    """New boxes (centre, half size) for the points outside every box, one per face of the base box."""
    c, h = base
    added = []
    for _ in range(MAX_EXTRA):
        out = points[outside(points, boxes + added) > TOLERANCE]
        if not len(out):
            break
        excess = np.abs(out - c) - h
        axis = np.argmax(excess, axis=1)
        side = np.sign(out[np.arange(len(out)), axis] - c[axis])
        faces = {}
        for k, s in zip(axis, side):
            faces[(int(k), float(s))] = faces.get((int(k), float(s)), 0) + 1
        k, s = max(faces, key=faces.get)
        grp = out[(axis == k) & (side == s)]
        lo, hi = grp.min(0) - MARGIN, grp.max(0) + MARGIN
        face = c[k] + s * h[k]
        if s > 0:
            lo[k] = min(lo[k], face)
        else:
            hi[k] = max(hi[k], face)
        added.append(((lo + hi) / 2, (hi - lo) / 2))
    return added


def box_of(o):
    return np.array(o['Position'], float), np.array(o['Size'], float)


def fit(vid, d, armor):
    changes = []
    obbs = d.get('OBB', [])
    new = []
    tur = [o for o in obbs if o.get('Transform') == 'Turret']
    base_t = next((o for o in tur if o.get('Part') == 'Turret'), tur[0] if tur else None)
    if base_t is not None and 'TurretPos' in d:
        pts = armor_points(vid, armor, ('turret', 'barrel'))
        if len(pts):
            pts = pts - np.array(d['TurretPos'], float)
            for c, h in extras(pts, box_of(base_t), [box_of(o) for o in tur]):
                o = {k: v for k, v in base_t.items() if k not in ('Position', 'Size')}
                o['Size'] = [round(float(x), 5) for x in h]
                o['Position'] = [round(float(x), 5) for x in c]
                new.append(o)
                changes.append(f'turret +OBB at {o["Position"]} half {o["Size"]}')
    hull = [o for o in obbs if o.get('Transform') in (None, 'Vehicle', 'Default') and not o.get('LandingGear')]
    body = [o for o in hull if o.get('Part', 'Body') in ('Body', 'BODY')]
    bounds = model_hull_bounds(vid)
    if body and bounds is not None:
        pts = armor_points(vid, armor, ('hull',))
        if len(pts):
            pts = np.clip(pts, bounds[0], bounds[1])
            for c, h in extras(pts, box_of(body[0]), [box_of(o) for o in hull]):
                o = {k: v for k, v in body[0].items() if k not in ('Position', 'Size', 'Part')}
                o['Size'] = [round(float(x), 5) for x in h]
                o['Position'] = [round(float(x), 5) for x in c]
                new.append(o)
                changes.append(f'hull +OBB at {o["Position"]} half {o["Size"]}')
    obbs.extend(new)
    return changes


def main(argv):
    check = '--check' in argv
    ids = [a for a in argv if not a.startswith('--')]
    report = []
    for path in sorted(glob.glob(os.path.join(DATA, 'sbw', 'vehicles', '*.json'))):
        vid = os.path.basename(path)[:-5]
        if ids and vid not in ids:
            continue
        apath = os.path.join(DATA, 'armor', f'{vid}.json')
        if not os.path.exists(apath):
            continue
        text = open(path).read()
        d = json.loads(text)
        ch = fit(vid, d, json.load(open(apath)))
        if ch:
            report += [f'{vid}: {c}' for c in ch]
            if not check:
                open(path, 'w').write(json.dumps(d, indent=2) + ('\n' if text.endswith('\n') else ''))
    print('\n'.join(report) or 'nothing to change')
    return 1 if (check and report) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
