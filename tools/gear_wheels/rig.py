#!/usr/bin/env python3
"""Makes landing gear wheels roll.

Each wheel found by find.py moves out of its gear leg's polygons into a bone of its own:
    gear_wheel_<n>            (the gear leg bone)     pivot on the axle, turned by the rig
    wreck_<piece>__gear_wheel_<n>                     the wheel polygons (still part of the same wreck piece)
and an AircraftRig rotor on the "groundRoll" channel spins it by the distance rolled (16 / radius radians per
block, capped at one block per tick of visible spin). The axle runs along model x; rolling forward (nose at -z)
turns the top of the wheel forward, which is a negative turn about the resource +x axis.

Only extended-gear wheels that touch the ground (their bottom within a quarter radius of the leg's lowest point)
are rigged, so gear doors and hub caps that merely look round are left alone.
Usage: python3 tools/gear_wheels/rig.py [--write] [id...]   (no ids: every aircraft with rig gear)
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import find as F  # noqa: E402

MAX_ROTORS = 16
PREFIX = 'gear_wheel_'


def r5(v):
    return [round(float(x), 5) for x in v]


def subtree(bones, root):
    kids = {}
    for b in bones.values():
        kids.setdefault(b.get('parent'), []).append(b['name'])
    out, stack = [], [root]
    while stack:
        n = stack.pop(); out.append(n); stack += kids.get(n, [])
    return out


def rig(vid):
    vpath = os.path.join(F.VEH, vid + '.json')
    data = json.load(open(vpath, encoding='utf-8'))
    rig_data = data.get('AircraftRig') or {}
    if rig_data.get('Schema') != 2 or not rig_data.get('Gear'):
        return None, None, 'no Schema 2 rig with gear'
    rotors = rig_data.setdefault('Rotors', [])
    if any(r.get('SpeedChannel') == 'groundRoll' for r in rotors):
        return None, None, 'already rigged'
    gpath = os.path.join(F.GEO, vid + '.geo.json')
    geo = json.load(open(gpath))
    bones_list = geo['minecraft:geometry'][0]['bones']
    bones = {b['name']: b for b in bones_list}
    found = F.find(vid)
    plan = []
    for gear, wheels in found.items():
        names = subtree(bones, gear)
        ys = [p[1] for n in names for p in ((bones[n].get('poly_mesh') or {}).get('positions') or [])
              if (bones[n].get('poly_mesh') or {}).get('polys')]
        if not ys:
            continue
        floor = min(ys)
        for w in wheels:
            if w['centre'][1] - w['radius'] > floor + 0.25 * w['radius']:
                continue
            plan.append((gear, w))
    room = MAX_ROTORS - len(rotors)
    if not plan:
        return None, None, 'no ground wheels found'
    if len(plan) > room:
        plan = sorted(plan, key=lambda gw: -gw[1]['radius'])[:room]
    new_bones = []
    removals = {}
    moved = 0
    for n, (gear, w) in enumerate(plan):
        name = f'{PREFIX}{n}'
        pivot = r5(w['centre'])
        new_bones.append({'name': name, 'parent': gear, 'pivot': pivot,
                          'poly_mesh': {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}})
        src_bone = bones[w['bone']]
        src = src_bone['poly_mesh']
        pis = set(w['idx'])
        dst = {'normalized_uvs': src.get('normalized_uvs', True), 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
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
        removals.setdefault(w['bone'], set()).update(pis)
        if w['bone'].startswith('wreck_'):
            piece = w['bone'].split('__', 1)[0]
            new_bones.append({'name': f'{piece}__{name}', 'parent': name, 'pivot': pivot, 'poly_mesh': dst})
        else:
            new_bones[-1]['poly_mesh'] = dst
        rotors.append({'Bone': name, 'Parent': gear, 'Pivot': pivot, 'Axis': [1, 0, 0],
                       'SpeedChannel': 'groundRoll', 'Direction': -1,
                       'DegreesPerTickAtFullSpeed': round(16.0 / w['radius'] * 180.0 / math.pi, 3)})
    for bname, pis in removals.items():
        pm = bones[bname]['poly_mesh']
        pm['polys'] = [p for k, p in enumerate(pm['polys']) if k not in pis]
    # new bones go right after the last gear bone so parents precede children
    last_gear = max(i for i, b in enumerate(bones_list) if b['name'] in found)
    bones_list[last_gear + 1:last_gear + 1] = new_bones
    summary = f'{len(plan)} wheels, {moved} polys: ' + ', '.join(
        f'{g}(r{w["radius"]:.1f})' for g, w in plan)
    return (gpath, geo), (vpath, data), summary


def write_vehicle(vpath, data):
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


def main(argv):
    write = '--write' in argv
    ids = [a for a in argv if not a.startswith('--')]
    if not ids:
        ids = sorted(f[:-5] for f in os.listdir(F.VEH)
                     if '"AircraftRig"' in open(os.path.join(F.VEH, f), encoding='utf-8').read())
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
            write_vehicle(*data_out)


if __name__ == '__main__':
    main(sys.argv[1:])
