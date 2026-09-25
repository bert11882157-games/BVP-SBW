#!/usr/bin/env python3
"""Export a Blockbench armor file for EVERY registered BVP vehicle.

  python3 tools/armor_mesh/export_templates.py                 # armor-only templates -> tools/armor_mesh/templates/
  python3 tools/armor_mesh/export_templates.py --edit DIR      # also write <id>.armor_edit.geo.json into DIR:
                                                                #   armor + the vehicle's visual model as reference
  python3 tools/armor_mesh/export_templates.py --check         # round-trip check against the box profiles

Vehicles that have box armor today get every box exported exactly (a closed 6-quad mesh per box, original
names kept, so xray highlights, ERA spent masks and ricochet seeds keep working). Vehicles without box armor
get STARTER volumes made from their SBW collision boxes (OBB list), named plate__10mm__starter_* - they are
placeholders to reshape and re-thickness, not real armor values.

Frames: volumes live under root bones armor_hull / armor_turret / armor_barrel (rest pose). Coordinates are geo
units in the frame of the visual model custom_geo/<id>.geo.json, so the two line up in one Blockbench project.
armor-profile local -> geo: (-16x, 16y, 16z), or (16x, 16y, 16z) for the X-mirrored profiles t72a/t72b.
Templates are NOT loaded by the game; copy an edited file to
bvp/src/main/resources/data/berts_vehicle_pack/armor_mesh/<id>.geo.json to enable it (see docs/ARMOR_MESH.md).
"""
import argparse
import glob
import json
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
GEN = os.path.join(REPO, 'bvp/src/generated/resources')
ARMOR = os.path.join(GEN, 'data/berts_vehicle_pack/armor')
VEHICLES = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles')
VISUAL = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
ENTITIES = os.path.join(REPO, 'bvp/src/main/java/com/yourname/berts_vehicle_pack/init/ModEntities.java')
MIRRORED = {'t72a', 't72b'}
ERA_DEFAULTS = {'kontakt1': (25.0, 400.0), 'kontakt5': (120.0, 450.0), 'relict': (200.0, 600.0)}
ENGINE_NAME = re.compile(r'^(engine_|motor_|powerpack_)|(_engine_|_motor_|_powerpack_)')
FACES = [(0, 2, 3, 1), (4, 5, 7, 6), (0, 1, 5, 4), (2, 6, 7, 3), (0, 4, 6, 2), (1, 3, 7, 5)]  # outward for +det


def registered_ids():
    text = open(ENTITIES, encoding='utf8').read()
    ids = set(re.findall(r'register\("([a-z0-9_]+)"', text))
    have = {os.path.basename(p)[:-5] for p in glob.glob(os.path.join(ARMOR, '*.json'))}
    return sorted(ids & have)


def rot_x(v, d):
    r = math.radians(d); c, s = math.cos(r), math.sin(r)
    return (v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c)


def rot_y(v, d):
    r = math.radians(d); c, s = math.cos(r), math.sin(r)
    return (v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c)


def rot_z(v, d):
    r = math.radians(d); c, s = math.cos(r), math.sin(r)
    return (v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2])


def box_corners(center, half, rot):
    """Armor-local corners, same rotation order as ArmorProfiles.ArmorBox.rotate (X, then Y, then Z)."""
    out = []
    for i in range(8):
        v = (half[0] * (1 if i & 1 else -1), half[1] * (1 if i & 2 else -1), half[2] * (1 if i & 4 else -1))
        v = rot_z(rot_y(rot_x(v, rot[0]), rot[1]), rot[2])
        out.append((center[0] + v[0], center[1] + v[1], center[2] + v[2]))
    return out


def to_geo(p, mirrored):
    return ((16.0 if mirrored else -16.0) * p[0], 16.0 * p[1], 16.0 * p[2])


def fmt_num(x):
    s = ('%.4f' % x).rstrip('0').rstrip('.')
    return s.replace('.', 'p')


def mesh_bone(name, parent, corners_geo):
    positions, normals, polys = [], [], []
    # choose winding so faces point outward in geo space (the loader re-orients anyway)
    a, b, c, d = (corners_geo[i] for i in (0, 1, 2, 4))
    ux = [b[k] - a[k] for k in range(3)]; uy = [c[k] - a[k] for k in range(3)]; uz = [d[k] - a[k] for k in range(3)]
    det = (ux[0] * (uy[1] * uz[2] - uy[2] * uz[1]) - ux[1] * (uy[0] * uz[2] - uy[2] * uz[0])
           + ux[2] * (uy[0] * uz[1] - uy[1] * uz[0]))
    for fi, face in enumerate(FACES):
        idx = list(face) if det > 0 else list(reversed(face))
        p = [corners_geo[i] for i in idx]
        e1 = [p[1][k] - p[0][k] for k in range(3)]; e2 = [p[2][k] - p[0][k] for k in range(3)]
        n = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]]
        ln = math.sqrt(sum(x * x for x in n)) or 1.0
        normals.append([round(x / ln, 5) for x in n])
        poly = []
        for k, q in enumerate(p):
            positions.append([round(q[0], 5), round(q[1], 5), round(q[2], 5)])
            poly.append([len(positions) - 1, fi, len(positions) - 1])
        polys.append(poly)
    uvs = [[0, 0], [1, 0], [1, 1], [0, 1]] * 6
    return {'name': name, 'parent': parent, 'pivot': [0, 0, 0],
            'poly_mesh': {'normalized_uvs': True, 'positions': positions, 'normals': normals,
                          'uvs': uvs, 'polys': polys}}


def frame_root(frame):
    return {'hull': 'armor_hull', 'turret': 'armor_turret', 'barrel': 'armor_barrel'}.get(frame or 'hull', 'armor_hull')


def volume_name(kind, box):
    name = box.get('name') or kind
    name = re.sub(r'[^A-Za-z0-9_.-]', '_', name)
    if kind == 'plate':
        return f"plate__{fmt_num(float(box.get('armor_mm', 0)))}mm__{name}"
    if kind == 'era':
        t = (box.get('type') or 'kontakt1')
        dk, dc = ERA_DEFAULTS.get(t.replace('-', '').replace('_', '').lower(), (25.0, 400.0))
        ke = float(box.get('kinetic_protection_mm', dk)); ce = float(box.get('chemical_protection_mm', dc))
        param = t.replace('_', '')
        if ke != dk: param += '_ke' + fmt_num(ke)
        if ce != dc: param += '_ce' + fmt_num(ce)
        return f"era__{param}__{name}"
    if kind == 'module':
        module = box.get('module', '') + ('' if box.get('unified', True) else '-split')
        return f"module__{module}__{name}"
    return f"{kind}____{name}"


def load_json(path):
    with open(path, encoding='utf8') as f:
        return json.load(f)


def build(vid):
    prof = load_json(os.path.join(ARMOR, vid + '.json'))
    mirrored = vid in MIRRORED
    bones = [{'name': r, 'pivot': [0, 0, 0]} for r in ('armor_hull', 'armor_turret', 'armor_barrel')]
    lists = [('plate', prof.get('plates')), ('era', prof.get('explosive_reactive_armor') or prof.get('era')),
             ('module', prof.get('modules')), ('ammo', prof.get('ammo_racks')), ('track', prof.get('tracks')),
             ('engine', prof.get('engines'))]
    for box in prof.get('sensitive_internals') or []:
        lists.append(('engine' if ENGINE_NAME.search((box.get('name') or '').lower()) else 'internal', [box]))
    used = set(); count = 0; kinds = {}
    for kind, boxes in lists:
        for box in boxes or []:
            half = box.get('half_size') or [0, 0, 0]
            if min(half) <= 0:
                continue
            name = volume_name(kind, box)
            base, n = name, 2
            while name.lower() in used:
                name = f'{base}_{n}'; n += 1
            used.add(name.lower())
            corners = box_corners(box['center'], half, box.get('rotation') or [0, 0, 0])
            bones.append(mesh_bone(name, frame_root(box.get('frame')), [to_geo(c, mirrored) for c in corners]))
            count += 1; kinds[kind] = kinds.get(kind, 0) + 1
    starter = False
    if count == 0:
        starter = True
        veh = load_json(os.path.join(VEHICLES, vid + '.json'))
        tpos = veh.get('TurretPos') or [0, 0, 0]
        for i, obb in enumerate(veh.get('OBB') or []):
            pos = obb.get('Position') or [0, 0, 0]; half = obb.get('Size') or [0, 0, 0]
            if min(half) <= 0:
                continue
            turret = obb.get('Transform') == 'Turret'
            sbw = [pos[k] + (tpos[k] if turret else 0) for k in range(3)]
            part = re.sub(r'[^A-Za-z0-9]', '', obb.get('Part') or ('Turret' if turret else 'Body')).lower() or 'body'
            corners_sbw = [(sbw[0] + half[0] * (1 if j & 1 else -1), sbw[1] + half[1] * (1 if j & 2 else -1),
                            sbw[2] + half[2] * (1 if j & 4 else -1)) for j in range(8)]
            geo = [(16.0 * c[0], 16.0 * c[1], -16.0 * c[2]) for c in corners_sbw]  # SBW local -> geo
            name = f'plate__10mm__starter_{part}_{i:02d}'
            bones.append(mesh_bone(name, 'armor_turret' if turret else 'armor_hull', geo))
            count += 1; kinds['starter'] = kinds.get('starter', 0) + 1
    doc = {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': f'geometry.{vid}_armor', 'texture_width': 16, 'texture_height': 16,
                        'visible_bounds_width': 16, 'visible_bounds_height': 8, 'visible_bounds_offset': [0, 2, 0]},
        'bones': bones}]}
    return doc, count, kinds, starter


def with_reference(vid, doc):
    """Armor + the visual model renamed ref_* under a reference_model bone (ignored by the armor loader)."""
    vis = load_json(os.path.join(VISUAL, vid + '.geo.json'))
    geo = vis['minecraft:geometry'][0]
    out = json.loads(json.dumps(doc))
    g = out['minecraft:geometry'][0]
    d = geo.get('description', {})
    for k in ('texture_width', 'texture_height'):
        if k in d: g['description'][k] = d[k]
    g['bones'].append({'name': 'reference_model', 'pivot': [0, 0, 0]})
    for b in geo.get('bones', []):
        nb = dict(b)
        nb['name'] = 'ref_' + b['name']
        nb['parent'] = ('ref_' + b['parent']) if b.get('parent') else 'reference_model'
        g['bones'].append(nb)
    return out


def check_roundtrip(vid, doc):
    """Re-read the template exactly as the loader will (geo -> armor local) and compare with the boxes."""
    prof = load_json(os.path.join(ARMOR, vid + '.json'))
    mirrored = vid in MIRRORED
    bones = {b['name']: b for b in doc['minecraft:geometry'][0]['bones'] if 'poly_mesh' in b}
    worst = 0.0
    lists = [('plate', prof.get('plates')), ('era', prof.get('explosive_reactive_armor') or prof.get('era'))]
    for kind, boxes in lists:
        for box in boxes or []:
            if min(box.get('half_size') or [0]) <= 0: continue
            name = volume_name(kind, box)
            bone = bones.get(name)
            if bone is None: continue
            pts = bone['poly_mesh']['positions']
            local = [((p[0] / 16.0) if mirrored else (-p[0] / 16.0), p[1] / 16.0, p[2] / 16.0) for p in pts]
            corners = box_corners(box['center'], box['half_size'], box.get('rotation') or [0, 0, 0])
            for q in local:
                dmin = min(math.dist(q, c) for c in corners)
                worst = max(worst, dmin)
    return worst


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=os.path.join(HERE, 'templates'))
    ap.add_argument('--edit', help='also write armor+reference edit files into this directory')
    ap.add_argument('--check', action='store_true')
    ap.add_argument('ids', nargs='*')
    a = ap.parse_args()
    ids = a.ids or registered_ids()
    os.makedirs(a.out, exist_ok=True)
    if a.edit: os.makedirs(a.edit, exist_ok=True)
    total = 0; starters = []; worst = 0.0
    for vid in ids:
        doc, count, kinds, starter = build(vid)
        with open(os.path.join(a.out, f'{vid}.armor.geo.json'), 'w', encoding='utf8') as f:
            json.dump(doc, f, separators=(',', ':'))
        if a.edit:
            with open(os.path.join(a.edit, f'{vid}.armor_edit.geo.json'), 'w', encoding='utf8') as f:
                json.dump(with_reference(vid, doc), f, separators=(',', ':'))
        if a.check and not starter:
            worst = max(worst, check_roundtrip(vid, doc))
        total += count
        if starter: starters.append(vid)
        print(f'{vid:32s} {count:4d} volumes {kinds}' + ('  (STARTER from collision boxes)' if starter else ''))
    print(f'{len(ids)} vehicles, {total} volumes, {len(starters)} starter files')
    if a.check:
        print(f'round-trip worst corner error: {worst:.2e} blocks')
        if worst > 1e-4: sys.exit(1)


if __name__ == '__main__':
    main()
