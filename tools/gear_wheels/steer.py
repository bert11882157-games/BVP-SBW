#!/usr/bin/env python3
"""Nose (and tail) wheel steering for fixed-wing aircraft (owner 2026-09-29).

For each aircraft whose server data names a NOSE or TAIL wheel contact inside a rigged gear leg:
  * the steerable part is split out of the leg: every connected polygon group of the leg that lies wholly inside a
    vertical cylinder around the wheel (strut piston, fork, mudguard, torque links, the wheel itself when it was not
    already its own bone). Doors, bay covers and anything reaching outside the cylinder stay on the leg;
  * it moves into a new bone  gear_steer_<n>  (parent: the gear leg, pivot: on the steering axis at the axle
    height), keeping the wreck-piece convention of tools/gear_wheels/rig.py (a child wreck_<piece>__gear_steer_<n>
    holds the polygons of each piece);
  * the leg's existing gear_wheel_<k> bones on that axis are re-parented under the steer bone (so the rolling
    wheel turns with it), and their groundRoll rotors follow;
  * an AircraftRig "NoseSteering" entry turns it about the vertical axis by the rudder / taxi command, at most
    MAX_NOSE (nose wheels) or MAX_TAIL (tail wheels) degrees; the renderer only steers it on the ground with the
    gear down. The axis is resource [0, -1, 0] for every aircraft: the flight model's positive rudder yaws the nose
    toward geo +x (the aircraft's left: Minecraft yaw falls), and a positive turn about [0, -1, 0] moves the front of
    the wheel toward geo +x, so the wheel always points where the aircraft is turning. (The same sense as the
    rudder surfaces of 60+ aircraft, whose trailing edge moves to geo +x on positive rudder.)

Idempotent (an aircraft that already has NoseSteering is skipped).
Usage: python3 tools/gear_wheels/steer.py [--write] [id...]
"""
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import find as F  # noqa: E402
import rig as R  # noqa: E402

DATA_VEH = os.path.join(F.GEN, 'data/berts_vehicle_pack/sbw/vehicles')
PREFIX = 'gear_steer_'
MAX_NOSE = 45.0
MAX_TAIL = 30.0
STEER_AXIS = [0, -1, 0]      # see the module docstring
CONTACT_REACH = 8.0          # px: a leg must come this close (horizontally) to the wheel contact
WHEEL_REACH = 16.0           # px: rolling wheels of that leg this close to the contact belong to the steered unit


def r5(v):
    return [round(float(x), 5) for x in v]


def contact_px(position):
    return np.array([position[0] * 16.0, position[1] * 16.0, -position[2] * 16.0])


def mesh_bones(bones, names):
    return [n for n in names if (bones[n].get('poly_mesh') or {}).get('polys')]


def horizontal(p, axis):
    return math.hypot(p[0] - axis[0], p[2] - axis[1])


def plan(vid):
    vpath = os.path.join(F.VEH, vid + '.json')
    data = json.load(open(vpath, encoding='utf-8'))
    rig_data = data.get('AircraftRig') or {}
    if rig_data.get('Schema') != 2 or not rig_data.get('Gear'):
        return None, 'no Schema 2 rig with gear'
    if rig_data.get('NoseSteering'):
        return None, 'already steered'
    server = json.load(open(os.path.join(DATA_VEH, vid + '.json'), encoding='utf-8'))
    contacts = (server.get('AircraftTerrainContact') or {}).get('WheelContacts') or []
    steered = [c for c in contacts if c.get('Group') in ('NOSE', 'TAIL')]
    if not steered:
        return None, 'no nose/tail wheel contact'
    group = steered[0]['Group']
    target = np.mean([contact_px(c['Position']) for c in steered], axis=0)
    gpath = os.path.join(F.GEO, vid + '.geo.json')
    geo = json.load(open(gpath))
    bones_list = geo['minecraft:geometry'][0]['bones']
    bones = {b['name']: b for b in bones_list}
    legs = [g['Bone'] for g in rig_data['Gear'] if g.get('VisibleWhen') != 'RETRACTED' and g['Bone'] in bones]
    best, best_d = None, None
    for leg in legs:
        pts = [p for n in mesh_bones(bones, R.subtree(bones, leg)) for p in bones[n]['poly_mesh']['positions']]
        if not pts:
            continue
        d = min(horizontal(p, (target[0], target[2])) for p in pts)
        if best_d is None or d < best_d:
            best, best_d = leg, d
    if best is None or best_d > CONTACT_REACH:
        return None, f'{group} wheel not inside a rigged gear leg ({best} {best_d})'
    leg = best
    rotors = rig_data.get('Rotors', [])
    wheels = []
    for rot in rotors:
        if rot.get('SpeedChannel') == 'groundRoll' and rot['Parent'] == leg:
            piv = rot['Pivot']
            if horizontal(piv, (target[0], target[2])) <= WHEEL_REACH:
                radius = 16.0 * 180.0 / (math.pi * rot['DegreesPerTickAtFullSpeed'])
                wheels.append((rot['Bone'], np.array(piv), radius))
    # The steering axis sits laterally on the wheel contact (the aircraft centreline for every nose gear here): a
    # wheel bone's pivot x is only somewhere on its axle, and one tyre of a twin pair is not the unit's centre.
    if wheels:
        axis = (float(target[0]), float(np.mean([w[1][2] for w in wheels])))
        axle_y = float(np.mean([w[1][1] for w in wheels]))
        reach = max(horizontal(w[1], axis) + w[2] for w in wheels)
    else:
        # the wheel is part of the leg's mesh: steer about the centre of the ground-touching group at the contact
        # (strut + tyre), which also covers raked struts whose wheel sits off the strut top
        bounds = [c['Bounds'] for c in steered if c.get('Bounds')]
        radius = 16.0 * max(max((b['Max'][1] - b['Min'][1]), (b['Max'][2] - b['Min'][2])) / 2 for b in bounds) \
            if bounds else 3.0
        ground = None
        for n in mesh_bones(bones, R.subtree(bones, leg)):
            pm = bones[n]['poly_mesh']
            for idx in F.components(pm):
                pts = F.points(pm, idx)
                near = min(horizontal(q, (target[0], target[2])) for q in pts)
                if pts[:, 1].min() <= target[1] + 1.0 and near <= 4.0 and (ground is None or near < ground[0]):
                    ground = (near, pts)
        if ground is None:
            return None, f'{group} leg {leg}: no ground-touching group at the contact'
        lo, hi = ground[1].min(0), ground[1].max(0)
        axis = (float(target[0]), float((lo[2] + hi[2]) / 2))
        axle_y = float(target[1] + radius)
        reach = min(12.0, max(horizontal(q, axis) for q in ground[1]))
    cylinder = 1.2 * max(reach, 2.0) + 0.5
    wheel_names = {w[0] for w in wheels}
    # components of the leg's own meshes (not inside already-split wheel bones) that lie within the cylinder
    skip = set()
    for w in wheel_names:
        skip.update(R.subtree(bones, w))
    moves = {}
    for n in mesh_bones(bones, R.subtree(bones, leg)):
        if n in skip:
            continue
        pm = bones[n]['poly_mesh']
        for idx in F.components(pm):
            pts = F.points(pm, idx)
            if max(horizontal(p, axis) for p in pts) <= cylinder:
                moves.setdefault(n, []).extend(idx)
    if not moves and not wheels:
        return None, f'{group} leg {leg}: nothing within {cylinder:.1f} px of the axis'
    return dict(vid=vid, vpath=vpath, data=data, gpath=gpath, geo=geo, leg=leg, group=group, axis=axis,
                axle_y=axle_y, cylinder=cylinder, wheels=sorted(wheel_names), moves=moves), None


def merge(into, extra):
    """Appends one poly_mesh to another (same normalized_uvs), re-indexing the extra's vertices."""
    base = [len(into['positions']), len(into['normals']), len(into['uvs'])]
    for key in ('positions', 'normals', 'uvs'):
        into[key].extend(extra[key])
    for poly in extra['polys']:
        into['polys'].append([[v[0] + base[0], v[1] + base[1], v[2] + base[2]] for v in poly])


def apply(p):
    geo, data = p['geo'], p['data']
    bones_list = geo['minecraft:geometry'][0]['bones']
    bones = {b['name']: b for b in bones_list}
    rig_data = data['AircraftRig']
    n = sum(1 for b in bones_list if b['name'].startswith(PREFIX))
    name = f'{PREFIX}{n}'
    pivot = r5([p['axis'][0], p['axle_y'], p['axis'][1]])
    new_bones = [{'name': name, 'parent': p['leg'], 'pivot': pivot}]
    moved = 0
    for src_name, idx in p['moves'].items():
        src = bones[src_name]['poly_mesh']
        dst = {'normalized_uvs': src.get('normalized_uvs', True), 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        remap = [{}, {}, {}]
        chosen = set(idx)
        for pi in sorted(chosen):
            poly = []
            for vert in src['polys'][pi]:
                out = []
                for slot, key in enumerate(('positions', 'normals', 'uvs')):
                    k = vert[slot]
                    if k not in remap[slot]:
                        remap[slot][k] = len(dst[key])
                        dst[key].append(src[key][k])
                    out.append(remap[slot][k])
                poly.append(out)
            dst['polys'].append(poly)
            moved += 1
        src['polys'] = [poly for k, poly in enumerate(src['polys']) if k not in chosen]
        if src_name.startswith('wreck_'):
            piece = src_name.split('__', 1)[0]
            child = f'{piece}__{name}'
            target = next((b for b in new_bones if b['name'] == child), None)
            if target is None:
                target = {'name': child, 'parent': name, 'pivot': pivot}
                new_bones.append(target)
        else:
            target = new_bones[0]
        if 'poly_mesh' in target:
            merge(target['poly_mesh'], dst)
        else:
            target['poly_mesh'] = dst
    for w in p['wheels']:
        bones[w]['parent'] = name
    for rot in rig_data.get('Rotors', []):
        if rot['Bone'] in p['wheels']:
            rot['Parent'] = name
    # the steer bone and its pieces go right after the leg's bone, ahead of the wheels it now parents
    leg_index = next(i for i, b in enumerate(bones_list) if b['name'] == p['leg'])
    first_wheel = min([i for i, b in enumerate(bones_list) if b['name'] in p['wheels']], default=len(bones_list))
    at = min(leg_index + 1, first_wheel)
    bones_list[at:at] = new_bones
    rig_data['NoseSteering'] = [{
        'Bone': name, 'Parent': p['leg'], 'Pivot': pivot, 'Axis': STEER_AXIS,
        'MaxDeflectionDegrees': MAX_TAIL if p['group'] == 'TAIL' else MAX_NOSE,
    }]
    return f"{p['group']} {p['leg']} -> {name} axis ({pivot[0]:.1f},{pivot[2]:.1f}) r{p['cylinder']:.1f}: " \
           f"{moved} polys from {len(p['moves'])} meshes, wheels {p['wheels'] or '-'}"


def main(argv):
    write = '--write' in argv
    ids = [a for a in argv if not a.startswith('--')]
    if not ids:
        ids = sorted(f[:-5] for f in os.listdir(F.VEH)
                     if '"AircraftRig"' in open(os.path.join(F.VEH, f), encoding='utf-8').read())
    done = 0
    for vid in ids:
        p, reason = plan(vid)
        if p is None:
            print(f'{vid:34s} skip: {reason}')
            continue
        summary = apply(p)
        done += 1
        print(f'{vid:34s} {summary}', flush=True)
        if write:
            raw = open(p['gpath']).read()
            with open(p['gpath'], 'w') as fh:
                json.dump(p['geo'], fh, separators=(',', ':'))
                if raw.endswith('\n'):
                    fh.write('\n')
            R.write_vehicle(p['vpath'], p['data'])
    print(f'{done} aircraft steered' + ('' if write else ' (dry run)'))


if __name__ == '__main__':
    main(sys.argv[1:])
