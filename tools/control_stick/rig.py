#!/usr/bin/env python3
"""Makes the pilot's control stick move with the controls.

The stick polygons found by find.py move out of their wreck piece into two new bones under the hull:
    control_stick_roll   (hull)                 tilts left/right with the aileron input
    control_stick_pitch  (control_stick_roll)   tilts fore/aft with the elevator input
    wreck_<piece>__control_stick_pitch          the stick polygons (still part of the same wreck piece)
both pivoting at the foot of the stick, and two AircraftRig Schema 2 surfaces drive them.

Frames: the geometry and the rig resource use model pixels (x left, y up, z aft). The renderer turns bones in the
PolyMesh frame (model x reversed) with right-handed rotations and maps resource axes [a, b, c] to [a, -b, -c]. In that
frame a pull (elevator up) swings the grip aft (+z) about +x, and right roll swings it right (+x) about -z, so the
resource axes are [1, 0, 0] (pitch) and [0, 0, 1] (roll).

Only aircraft with a Schema 2 rig are changed (adding a rig to one without would switch off its legacy animator).
Usage: python3 tools/control_stick/rig.py [--write] [id...]   (no ids: every aircraft with a stick)
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
import find as STICK  # noqa: E402
import thin as T  # noqa: E402

PITCH_DEGREES = 14.0
ROLL_DEGREES = 11.0
ROLL, PITCH = 'control_stick_roll', 'control_stick_pitch'


def r5(v):
    return [round(float(x), 5) for x in v]


def rig(vid):
    """(geo, vehicle json, summary) with the stick split out, or (None, None, reason)."""
    vpath = os.path.join(T.GEN, 'assets/berts_vehicle_pack/sbw/vehicles', vid + '.json')
    data = json.load(open(vpath))
    rig_data = data.get('AircraftRig')
    if not rig_data or rig_data.get('Schema') != 2:
        return None, None, 'no Schema 2 rig'
    if any(s.get('Bone') in (ROLL, PITCH) for s in rig_data.get('Surfaces', [])):
        return None, None, 'already rigged'
    found = STICK.find(vid)
    if not found:
        return None, None, 'no stick found'
    path, geo = T.load_geo(vid)
    bones = geo['minecraft:geometry'][0]['bones']
    by_name = {b['name']: b for b in bones}
    if ROLL in by_name or PITCH in by_name:
        return None, None, 'stick bones exist'
    # foot of the stick, model pixels
    pivot_local = np.asarray(found['pivot'], float)
    pivot_px = pivot_local * np.array([16.0, 16.0, -16.0])
    moves = {}
    for bone, pi in found['parts']:
        b = by_name[bone['name']]
        if b.get('parent') != 'hull' or not b['name'].startswith('wreck_'):
            continue
        moves.setdefault(b['name'], set()).add(pi)
    if not moves:
        return None, None, 'stick not in a hull wreck piece'
    new_bones = [
        {'name': ROLL, 'parent': 'hull', 'pivot': r5(pivot_px),
         'poly_mesh': {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}},
        {'name': PITCH, 'parent': ROLL, 'pivot': r5(pivot_px),
         'poly_mesh': {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}},
    ]
    moved = 0
    for name, pis in sorted(moves.items()):
        src = by_name[name]['poly_mesh']
        piece = name.split('__', 1)[0]
        dst = {'normalized_uvs': src.get('normalized_uvs', True), 'positions': [], 'normals': [], 'uvs': [],
               'polys': []}
        remap = [{}, {}, {}]
        for pi in sorted(pis):
            poly = []
            for vert in src['polys'][pi]:
                out = []
                for slot, key in enumerate(('positions', 'normals', 'uvs')):
                    idx = vert[slot]
                    if idx not in remap[slot]:
                        remap[slot][idx] = len(dst[key])
                        dst[key].append(src[key][idx])
                    out.append(remap[slot][idx])
                poly.append(out)
            dst['polys'].append(poly)
            moved += 1
        src['polys'] = [p for k, p in enumerate(src['polys']) if k not in pis]
        new_bones.append({'name': f'{piece}__{PITCH}', 'parent': PITCH, 'pivot': r5(pivot_px), 'poly_mesh': dst})
    # insert the new bones right after the hull's direct control bones, keeping parents before children
    insert_at = next((i for i, b in enumerate(bones) if b['name'].startswith('wreck_')), len(bones))
    bones[insert_at:insert_at] = new_bones
    surfaces = rig_data.setdefault('Surfaces', [])
    surfaces.append({'Bone': ROLL, 'Parent': 'hull', 'Pivot': r5(pivot_px), 'Axis': [0, 0, 1],
                     'ControlWeights': {'ElevatorUp': 0, 'RightRoll': 1, 'RudderRight': 0},
                     'MaxDeflectionDegrees': ROLL_DEGREES})
    surfaces.append({'Bone': PITCH, 'Parent': ROLL, 'Pivot': r5(pivot_px), 'Axis': [1, 0, 0],
                     'ControlWeights': {'ElevatorUp': 1, 'RightRoll': 0, 'RudderRight': 0},
                     'MaxDeflectionDegrees': PITCH_DEGREES})
    return (path, geo), (vpath, data), f'{moved} polys, pivot {r5(pivot_local)}'


def main(argv):
    write = '--write' in argv
    ids = [a for a in argv if not a.startswith('--')]
    if not ids:
        sys.path.insert(0, os.path.join(HERE, '..', 'canopy_glass'))
        import glass as G  # noqa: E402
        ids = G.aircraft()
    for vid in ids:
        geo_out, data_out, summary = rig(vid)
        print(f'{vid:34s} {summary}', flush=True)
        if write and geo_out:
            path, geo = geo_out
            raw = open(path).read()
            with open(path, 'w') as fh:
                json.dump(geo, fh, separators=(',', ':'))
                if raw.endswith('\n'):
                    fh.write('\n')
            vpath, data = data_out
            raw = open(vpath, encoding='utf-8').read()
            indent = 2 if raw.startswith('{\n') else None
            glass = data.get('CanopyGlass')       # kept on one line, as tools/canopy_glass writes it
            marker = '"__CANOPY_GLASS__"'
            if glass is not None:
                data['CanopyGlass'] = '__CANOPY_GLASS__'
            text = json.dumps(data, indent=indent, ensure_ascii=False)
            if glass is not None:
                text = text.replace(marker, json.dumps(glass, separators=(',', ':')))
            with open(vpath, 'w', encoding='utf-8') as fh:
                fh.write(text + ('\n' if raw.endswith('\n') else ''))


if __name__ == '__main__':
    main(sys.argv[1:])
