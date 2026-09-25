#!/usr/bin/env python3
"""Undo the X mirror of every MTB-converted aircraft in the generated resources.

Background (see the session doc "Root cause of the MTB inversions"): the runtime model frame is left-handed as
stored (the mesh loader negates X and the renderer turns the model 180 degrees), and the aircraft pipeline fixed
the backwards facing of its MTB sources with `visualMirrorZ` (a reflection) instead of a 180 degree yaw, so every
converted aircraft is the mirror image of its source (AH-64D / Mi-24V / UH-1D tail rotors on the right, A-10 gun
on the right and nose wheel on the left). Everything authored on top of the mirrored models (seats, weapons,
stations, cameras, rig pivots and axes, armor, hitboxes, terrain contact) is mirrored consistently, so reflecting
all of it once more in the vehicle-local X axis yields the true-handed aircraft.

Reflection rules (vehicle frame and model-pixel frame differ only in the sign of Z, so both use X):
  * points, offsets and polar directions: x -> -x
  * Min/Max boxes: x range negated and swapped
  * sizes, extents, half sizes, colours, shake/sensitivity triples: unchanged
  * hinge/sweep/door/flap/station-pitch axes (axial vectors): (x, y, z) -> (x, -y, -z)
  * rotor axes and station yaw axes: polar (x -> -x), so the rotation seen in the world keeps its sense
  * Euler rotations (armor boxes, geo bones/cubes): (rx, ry, rz) -> (rx, -ry, -rz)
  * antisymmetric commands: roll and yaw control weights negated (pitch unchanged), so each control surface still
    deflects the same way in the world for the same stick input
  * yaw ranges [a, b] -> [-b, -a]; MinYaw/MaxYaw swapped and negated; base yaw b -> -b
  * geometry: poly_mesh positions and normals x -> -x with each polygon's winding reversed; cubes mirrored with
    their box UV mirrored

Usage: python3 tools/aircraft_unmirror/unmirror.py [--check] <aircraft id>...   (from the repository root)
Idempotence is enforced by tools/aircraft_unmirror/applied.json.
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
GEN = os.path.join(ROOT, 'bvp', 'src', 'generated', 'resources')
APPLIED = os.path.join(os.path.dirname(__file__), 'applied.json')

UNCHANGED_TRIPLES = {'Size', 'Extents', 'half_size', 'size', 'ColorRgb', 'ShootShake', 'Sensitivity', 'Scale', 'uv'}
AXIAL_PARENTS_POLAR = {'Rotors[]', 'Yaw'}  # axes that keep their world rotation sense
YAW_RANGES = {'PassengerWeaponStationYawRange', 'TurretYawRange', 'Yaw'}


def neg0(v):
    r = -v
    return 0 if r == 0 else r


def polar(v):
    return [neg0(v[0]), v[1], v[2]]


def axial(v):
    return [v[0], neg0(v[1]), neg0(v[2])]


def euler(v):
    return [v[0], neg0(v[1]), neg0(v[2])]


def is_vec(o, n=None):
    return isinstance(o, list) and o and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in o) \
        and (n is None or len(o) == n)


def transform(o, key='', parent=''):
    """Returns the reflected copy of a JSON value, dispatching on its key and parent key."""
    if isinstance(o, dict):
        out = {}
        for k, v in o.items():
            out[k] = transform(v, k, key)
        if is_vec(out.get('Min'), 3) and is_vec(out.get('Max'), 3):
            lo, hi = o['Min'][0], o['Max'][0]
            out['Min'] = [neg0(hi), o['Min'][1], o['Min'][2]]
            out['Max'] = [neg0(lo), o['Max'][1], o['Max'][2]]
        if 'MinYaw' in o and 'MaxYaw' in o:
            out['MinYaw'], out['MaxYaw'] = neg0(o['MaxYaw']), neg0(o['MinYaw'])
        if 'BaseYawDegrees' in o and isinstance(o['BaseYawDegrees'], (int, float)):
            b = o['BaseYawDegrees']
            out['BaseYawDegrees'] = 180 if b in (180, -180) else neg0(b)
        cw = o.get('ControlWeights')
        if isinstance(cw, dict):
            cw = dict(cw)
            for name in ('RightRoll', 'RudderRight'):
                if isinstance(cw.get(name), (int, float)):
                    cw[name] = neg0(cw[name])
            out['ControlWeights'] = cw
        elif is_vec(cw, 3):
            out['ControlWeights'] = [cw[0], neg0(cw[1]), neg0(cw[2])]
        return out
    if isinstance(o, list):
        if is_vec(o, 3):
            if key in ('Min', 'Max', 'ControlWeights') or key in UNCHANGED_TRIPLES:
                return o  # handled by the parent dict or unchanged
            if key == 'Axis':
                return polar(o) if parent in AXIAL_PARENTS_POLAR else axial(o)
            if key == 'rotation':
                return euler(o)
            return polar(o)
        if is_vec(o, 2):
            if key in YAW_RANGES:
                return [neg0(o[1]), neg0(o[0])]
            return o
        if is_vec(o):
            return o
        return [transform(v, key + '[]' if not key.endswith('[]') else key, parent) for v in o]
    return o


# ---------------------------------------------------------------- geometry

def mirror_poly_mesh(pm):
    """x flipped on positions and normals; each explicit polygon's winding reversed. Intact bones of split
    aircraft keep positions with an empty polygon list (their wreck pieces draw them); nothing to rewind there."""
    out = dict(pm)
    out['positions'] = [[neg0(p[0]), p[1], p[2]] for p in pm.get('positions', [])]
    out['normals'] = [[neg0(n[0]), n[1], n[2]] for n in pm.get('normals', [])]
    polys = pm.get('polys')
    if isinstance(polys, list) and polys:
        out['polys'] = [poly_reversed(p) for p in polys]
    return out


def poly_reversed(poly):
    # [v0, v1, v2, v3] -> [v0, v3, v2, v1]: same first vertex, opposite winding.
    return [poly[0]] + list(reversed(poly[1:]))


def mirror_cube(c):
    c = dict(c)
    o, s = c['origin'], c['size']
    c['origin'] = [neg0(o[0] + s[0]), o[1], o[2]]
    if 'pivot' in c:
        c['pivot'] = polar(c['pivot'])
    if 'rotation' in c:
        c['rotation'] = euler(c['rotation'])
    if isinstance(c.get('uv'), dict):
        uv = dict(c['uv'])
        uv['east'], uv['west'] = c['uv'].get('west'), c['uv'].get('east')
        for face, spec in list(uv.items()):
            if spec and 'uv' in spec and 'uv_size' in spec:
                spec = dict(spec)
                spec['uv'] = [spec['uv'][0] + spec['uv_size'][0], spec['uv'][1]]
                spec['uv_size'] = [-spec['uv_size'][0], spec['uv_size'][1]]
                uv[face] = spec
        c['uv'] = {k: v for k, v in uv.items() if v is not None}
    else:
        c['mirror'] = not c.get('mirror', False)
    return c


def mirror_geo(doc):
    doc = json.loads(json.dumps(doc))
    for geo in doc.get('minecraft:geometry', []):
        for bone in geo.get('bones', []):
            if 'pivot' in bone:
                bone['pivot'] = polar(bone['pivot'])
            if 'rotation' in bone:
                bone['rotation'] = euler(bone['rotation'])
            if 'poly_mesh' in bone:
                bone['poly_mesh'] = mirror_poly_mesh(bone['poly_mesh'])
            if 'cubes' in bone:
                bone['cubes'] = [mirror_cube(c) for c in bone['cubes']]
            for loc_key in ('locators',):
                if isinstance(bone.get(loc_key), dict):
                    bone[loc_key] = {k: (polar(v) if is_vec(v, 3) else v) for k, v in bone[loc_key].items()}
    return doc


# ---------------------------------------------------------------- files

def files_for(vid):
    return {
        'data_vehicle': os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles', vid + '.json'),
        'asset_vehicle': os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles', vid + '.json'),
        'armaments': os.path.join(GEN, 'data/berts_vehicle_pack/sbw/aircraft_armaments', vid + '.json'),
        'armor': os.path.join(GEN, 'data/berts_vehicle_pack/armor', vid + '.json'),
        'geo': os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo', vid + '.geo.json'),
        'wreck_geo': os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo', vid + '_turret_wreck.geo.json'),
        'fallback_geo': os.path.join(GEN, 'assets/berts_vehicle_pack/geo/native_fallback', vid + '.geo.json'),
        'armor_mesh': os.path.join(ROOT, 'tools/armor_mesh/templates', vid + '.armor.geo.json'),
    }


def dump_like(path, doc):
    raw = open(path, encoding='utf-8').read()
    indent = None
    if raw.startswith('{\n'):
        second = raw.split('\n', 2)[1]
        indent = len(second) - len(second.lstrip(' '))
        indent = indent or 2
    text = json.dumps(doc, indent=indent, ensure_ascii=False, separators=None if indent else (',', ':'))
    if raw.endswith('\n'):
        text += '\n'
    open(path, 'w', encoding='utf-8').write(text)


def unmirror(vid, check=False):
    touched = []
    for kind, path in files_for(vid).items():
        if not os.path.exists(path):
            continue
        doc = json.load(open(path, encoding='utf-8'))
        new = mirror_geo(doc) if kind.endswith('geo') or kind == 'armor_mesh' else transform(doc)
        touched.append(os.path.relpath(path, ROOT))
        if not check:
            dump_like(path, new)
    return touched


def main(argv):
    check = '--check' in argv
    ids = [a for a in argv if not a.startswith('--')]
    applied = json.load(open(APPLIED)) if os.path.exists(APPLIED) else {'schema': 1, 'aircraft': []}
    done = set(applied['aircraft'])
    for vid in ids:
        if vid in done:
            print('skip (already un-mirrored)', vid)
            continue
        files = unmirror(vid, check)
        print(('would touch' if check else 'un-mirrored'), vid, len(files), 'files')
        if not check:
            done.add(vid)
    if not check:
        applied['aircraft'] = sorted(done)
        json.dump(applied, open(APPLIED, 'w'), indent=2)
        open(APPLIED, 'a').write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
