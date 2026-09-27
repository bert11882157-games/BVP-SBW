#!/usr/bin/env python3
"""Completes a data-only ground vehicle (model and data files present, but no weapons, seats, armour or entity) from
an existing vehicle of the same family, on the vehicle's own model.

    python3 tools/vehgen/fit_existing.py <id> [--write]

The five vehicles added before 2026-09-27 (M1A1, T-14, Type 90, 9K22, 9P149) had a model, a client resource and a
placeholder data file, but no weapons, no armour and no registered entity, so they could not be summoned. Each spec in
tools/vehgen/fit_specs.py says:
  template       the vehicle whose systems it gets (weapons, seats, station, armour layout, sounds, handling)
  geometry       model fixes, applied to the original model kept in tools/vehgen/replaced/<id>/ (so a rerun gives
                 the same result): components moved onto station bones, running gear mirrored, gun stretched,
                 uniform scale to the template's hull length
  points         seats, eyes and muzzles in model px of the original model (after the moves and the gun stretch,
                 before the scale)
  rounds         template round -> this vehicle's round (profile copied with the new round id and WT values)

Derived: geometry, turret wreck and native fallback skeleton; client resource (running gear, exhaust scaled);
vehicle data (template systems, this model's pivots, seats, attachments, OBBs mapped box to box, terrain contacts);
armour (template plates, engines and racks mapped box to box per frame); projectile profiles; registration
(ModEntities / ModEntityRenderers as a FittedGroundVehicle, creative tab, lang). Run obb_armor_cover.py and
wt_tank_rounds.py afterwards.
"""
import copy
import json
import os
import re
import shutil
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)
import vehgen  # noqa: E402
from fit_specs import SPECS  # noqa: E402

NS = vehgen.NS
ASSETS, DATA, JAVA = vehgen.ASSETS, vehgen.DATA, vehgen.JAVA
REPLACED = os.path.join(HERE, 'replaced')
r5 = vehgen.r5


def rel(path):
    return os.path.relpath(path, REPO)


def files_of(vid):
    return {
        'geo': os.path.join(ASSETS, 'custom_geo', f'{vid}.geo.json'),
        'wreck': os.path.join(ASSETS, 'custom_geo', f'{vid}_turret_wreck.geo.json'),
        'fallback': os.path.join(ASSETS, 'geo', 'native_fallback', f'{vid}.geo.json'),
        'client': os.path.join(ASSETS, 'sbw', 'vehicles', f'{vid}.json'),
        'data': os.path.join(DATA, 'sbw', 'vehicles', f'{vid}.json'),
        'armor': os.path.join(DATA, 'armor', f'{vid}.json'),
    }


def originals(vid, write):
    """The data-only files as they were before the first fit (kept under tools/vehgen/replaced/<id>/)."""
    out = {}
    for key, path in files_of(vid).items():
        kept = os.path.join(REPLACED, vid, rel(path))
        if not os.path.exists(kept):
            if not write:
                out[key] = vehgen.load(path)
                continue
            os.makedirs(os.path.dirname(kept), exist_ok=True)
            shutil.copy2(path, kept)
        out[key] = vehgen.load(kept)
    return out


# ------------------------------------------------------------------ geometry

def bones_of(geo):
    return {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}


def poly_components(pm):
    """Poly index lists of the bone's connected components (shared or coincident positions)."""
    P = np.array(pm['positions'], float)
    parent = list(range(len(P)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    for poly in pm['polys']:
        for v in poly[1:]:
            parent[find(v[0])] = find(poly[0][0])
    same = {}
    for i, p in enumerate(P):
        key = tuple(np.round(p, 2))
        if key in same:
            parent[find(i)] = find(same[key])
        else:
            same[key] = i
    groups = {}
    for k, poly in enumerate(pm['polys']):
        groups.setdefault(find(poly[0][0]), []).append(k)
    return list(groups.values())


def move_components(geo, move):
    """Moves every component of move['from'] whose bounding box lies inside move['box'] to move['to']."""
    bones = bones_of(geo)
    src = bones[move['from']]['poly_mesh']
    P = np.array(src['positions'], float)
    lo, hi = np.array(move['box'][0], float), np.array(move['box'][1], float)
    picked = []
    for comp in poly_components(src):
        pts = P[sorted({v[0] for k in comp for v in src['polys'][k]})]
        cx = (pts[:, 0].min() + pts[:, 0].max()) / 2
        if 'centreX' in move and not (move['centreX'][0] <= cx <= move['centreX'][1]):
            continue
        if np.all(pts.min(0) >= lo) and np.all(pts.max(0) <= hi):
            picked += comp
    if len(picked) == 0:
        raise SystemExit(f'move {move["from"]} -> {move["to"]}: nothing inside {move["box"]}')
    dst = bones[move['to']].setdefault('poly_mesh', {'normalized_uvs': src.get('normalized_uvs', True),
                                                     'positions': [], 'normals': [], 'uvs': [], 'polys': []})
    polys = [src['polys'][k] for k in sorted(picked)]
    for poly in polys:
        out = []
        for p, n, u in poly:
            dst['positions'].append(list(src['positions'][p]))
            dst['normals'].append(src['normals'][n])
            dst['uvs'].append(src['uvs'][u])
            out.append([len(dst['positions']) - 1, len(dst['normals']) - 1, len(dst['uvs']) - 1])
        dst['polys'].append(out)
    keep = set(range(len(src['polys']))) - set(picked)
    src['polys'] = [p for k, p in enumerate(src['polys']) if k in keep]
    compact(src)
    return len(picked)


def compact(pm):
    for slot, key in enumerate(('positions', 'normals', 'uvs')):
        used = sorted({v[slot] for poly in pm['polys'] for v in poly})
        remap = {old: new for new, old in enumerate(used)}
        pm[key] = [pm[key][i] for i in used]
        for poly in pm['polys']:
            for v in poly:
                v[slot] = remap[v[slot]]


def add_bones(geo, new):
    """New bones inserted right after their parent, in spec order."""
    blist = geo['minecraft:geometry'][0]['bones']
    for name, b in new.items():
        if any(x['name'] == name for x in blist):
            continue
        i = max(k for k, x in enumerate(blist) if x['name'] == b['parent'] or x.get('parent') == b['parent'])
        blist.insert(i + 1, {'name': name, 'parent': b['parent'], 'pivot': list(b['pivot'])})


def side_bones(bones, side):
    other = 'R' if side == 'L' else 'L'
    pat = re.compile(rf'^(wheel{side}\d+|wheel{side}Front|wheel{side}Rear|Wheel{side}|Track{side}|crudeTrack{side}|'
                     rf'brokenTrack{side}|trackMov{side}\d+|trackRot{side}\d+)$')
    return {n: n.replace(side, other, 1) if not n.startswith(('trackMov', 'trackRot'))
            else n[:8] + other + n[9:] for n in bones if pat.match(n)}


def mirror_running_gear(geo, source_side):
    """The other side's running gear placed as the mirror image of `source_side` in x (same meshes, x offsets
    only): the source model had one side's wheels and track several px off."""
    bones = bones_of(geo)
    pairs = side_bones(bones, source_side)
    moved = []
    for src, dst in pairs.items():
        if dst not in bones:
            continue
        s, d = bones[src], bones[dst]
        dx_pivot = -s['pivot'][0] - d['pivot'][0]
        pm_s, pm_d = s.get('poly_mesh'), d.get('poly_mesh')
        if pm_s and pm_d and pm_d['positions']:
            Ps, Pd = np.array(pm_s['positions']), np.array(pm_d['positions'])
            dx = -(Ps[:, 0].min() + Ps[:, 0].max()) / 2 - (Pd[:, 0].min() + Pd[:, 0].max()) / 2
            for p in pm_d['positions']:
                p[0] = round(p[0] + dx, 5)
        elif d.get('cubes') and s.get('cubes'):
            dx = -(s['cubes'][0]['origin'][0] + s['cubes'][0]['size'][0] / 2) - \
                (d['cubes'][0]['origin'][0] + d['cubes'][0]['size'][0] / 2)
        else:
            dx = dx_pivot
        for c in d.get('cubes', []):
            c['origin'][0] = round(c['origin'][0] + dx, 5)
            if 'pivot' in c:
                c['pivot'][0] = round(c['pivot'][0] + dx, 5)
        d['pivot'][0] = round(-s['pivot'][0], 5)
        if abs(dx) > 1e-3:
            moved.append((dst, round(dx, 2)))
    return moved


def mirror_meshes(geo, source_side):
    """The other side's running gear rebuilt as the mirror image of `source_side` (meshes, cubes and pivots): the
    source had one side's road wheels or track links malformed (the 9K22's left links 2.9 px wide, its right 8.2)."""
    bones = bones_of(geo)
    pairs = side_bones(bones, source_side)
    n = 0
    for src, dst in pairs.items():
        if dst not in bones:
            continue
        s, d = bones[src], bones[dst]
        d['pivot'] = [round(-s['pivot'][0], 5), s['pivot'][1], s['pivot'][2]]
        if 'rotation' in s:
            d['rotation'] = [s['rotation'][0], -s['rotation'][1], -s['rotation'][2]]
        else:
            d.pop('rotation', None)
        if s.get('poly_mesh'):
            pm = copy.deepcopy(s['poly_mesh'])
            pm['positions'] = [[round(-p[0], 5), p[1], p[2]] for p in pm['positions']]
            pm['normals'] = [[-v[0], v[1], v[2]] for v in pm['normals']]
            pm['polys'] = [list(reversed(poly)) for poly in pm['polys']]
            d['poly_mesh'] = pm
        else:
            d.pop('poly_mesh', None)
        if s.get('cubes'):
            cubes = copy.deepcopy(s['cubes'])
            for c in cubes:
                c['origin'][0] = round(-(c['origin'][0] + c['size'][0]), 5)
                if 'pivot' in c:
                    c['pivot'][0] = round(-c['pivot'][0], 5)
                if 'rotation' in c:
                    c['rotation'] = [c['rotation'][0], -c['rotation'][1], -c['rotation'][2]]
            d['cubes'] = cubes
        else:
            d.pop('cubes', None)
        n += 1
    return n


def symmetrize_running_gear(geo, outer=None):
    """Both sides placed mirror-symmetric about x = 0, flush on one outer plane: every wheel bone (road wheels,
    sprocket, idler) moved so its outer face is at +-`outer`, and the track bones (crude, broken, link and path
    bones) so the track's outer face is 0.4 px beyond it, as on the healthy fitted vehicles (Leopard 2A4, 2K22M,
    Marder). `outer` defaults to the road wheels' outer face when both sides agree. The source fits of these
    vehicles had wheels, sprockets and tracks of one or both sides several px (the 9P149 up to 36 px) off."""
    bones = bones_of(geo)

    def extent(b):
        pm = b.get('poly_mesh')
        if pm and pm['positions']:
            P = np.array(pm['positions'])
            return P[:, 0].min(), P[:, 0].max()
        if b.get('cubes'):
            xs = [c['origin'][0] for c in b['cubes']] + [c['origin'][0] + c['size'][0] for c in b['cubes']]
            return min(xs), max(xs)
        return None

    def shift(b, dx):
        pm = b.get('poly_mesh')
        if pm:
            for p in pm['positions']:
                p[0] = round(p[0] + dx, 5)
        for c in b.get('cubes', []):
            c['origin'][0] = round(c['origin'][0] + dx, 5)
            if 'pivot' in c:
                c['pivot'][0] = round(c['pivot'][0] + dx, 5)
        b['pivot'][0] = round(b['pivot'][0] + dx, 5)

    road = sorted(n for n in bones if re.fullmatch(r'wheelL\d+', n))
    if outer is None:
        lo = [extent(bones[n])[1] for n in road]
        ro = [-extent(bones[n.replace('L', 'R', 1)])[0] for n in road]
        if max(abs(a - b) for a, b in zip(lo, ro)) > 1.0:
            raise SystemExit(f'road wheel outer faces disagree (L {lo[:2]} R {ro[:2]}): give "wheelOuter"')
        outer = float(np.mean(lo + ro))
    for n, b in bones.items():
        if re.fullmatch(r'wheel[LR](\d+|Front|Rear)', n):
            e = extent(b)
            shift(b, (outer - e[1]) if n[5] == 'L' else (-outer - e[0]))
    for side, sign in (('L', 1), ('R', -1)):
        e = extent(bones[f'crudeTrack{side}'])
        dx = (outer + 0.4 - e[1]) if side == 'L' else (-outer - 0.4 - e[0])
        for n, b in bones.items():
            if re.fullmatch(rf'(Track{side}|crudeTrack{side}|brokenTrack{side}|trackMov{side}\d+|trackRot{side}\d+)', n):
                shift(b, dx)
        bones[f'Wheel{side}']['pivot'][0] = round(sign * abs(bones[f'wheel{side}0']['pivot'][0]), 5)
    e = extent(bones['crudeTrackL'])
    return round(float((e[0] + e[1]) / 2), 5), round(outer, 3)


def stretch(geo, spec, scale):
    """Moves the vertices of `bone` in front of `zBelow` (px) forward so the muzzle ends at `muzzleZ` after the
    scale: the source gun stopped at the hull front."""
    pm = bones_of(geo)[spec['bone']]['poly_mesh']
    z = min(p[2] for p in pm['positions'])
    dz = spec['muzzleZ'] / scale - z
    n = 0
    for p in pm['positions']:
        if p[2] < spec['zBelow']:
            p[2] = round(p[2] + dz, 5)
            n += 1
    return dz, n


def scale_geo(geo, s):
    for b in geo['minecraft:geometry'][0]['bones']:
        b['pivot'] = r5(np.array(b['pivot']) * s)
        pm = b.get('poly_mesh')
        if pm:
            pm['positions'] = [r5(np.array(p) * s) for p in pm['positions']]
        for c in b.get('cubes', []):
            c['origin'] = r5(np.array(c['origin']) * s)
            c['size'] = r5(np.array(c['size']) * s)
            if 'pivot' in c:
                c['pivot'] = r5(np.array(c['pivot']) * s)
    d = geo['minecraft:geometry'][0]['description']
    for k in ('visible_bounds_width', 'visible_bounds_height'):
        d[k] = round(d[k] * s, 2)
    d['visible_bounds_offset'] = r5(np.array(d['visible_bounds_offset']) * s)


def own_box(geo, bone):
    P = np.array(bones_of(geo)[bone]['poly_mesh']['positions'], float)
    return P.min(0), P.max(0)


def family(bones, name, root):
    while name:
        if name == root:
            return True
        name = bones[name].get('parent') if name in bones else None
    return False


def build_wreck(geo, wreck_tpl):
    """The detached turret: every turret-family bone of the fitted model, moved so the turret pivot is the origin."""
    w = copy.deepcopy(wreck_tpl)
    g = w['minecraft:geometry'][0]
    bones = bones_of(geo)
    tp = np.array(bones['turret']['pivot'], float)
    g['bones'] = []
    for b in geo['minecraft:geometry'][0]['bones']:
        if not family(bones, b['name'], 'turret'):
            continue
        nb = {'name': b['name'], 'pivot': [0, 0, 0] if b['name'] == 'turret' else r5(np.array(b['pivot']) - tp)}
        if b['name'] != 'turret':
            nb['parent'] = b['parent']
        if b.get('poly_mesh'):
            m = copy.deepcopy(b['poly_mesh'])
            m['positions'] = [r5(np.array(q) - tp) for q in m['positions']]
            nb['poly_mesh'] = m
        g['bones'].append(nb)
    return w


# ------------------------------------------------------------------ data

def geo_to_data(g):
    return np.array([g[0] / 16, g[1] / 16, -g[2] / 16])


def map_point(p, src_box, dst_box):
    lo, hi = src_box
    dlo, dhi = dst_box
    k = (dhi - dlo) / (hi - lo)
    return (np.asarray(p, float) - lo) * k + dlo, k


def frame_origin(d, frame):
    """Rest-pose origin (vehicle data blocks) of an attachment parent frame."""
    z = np.zeros(3)
    tur = np.array(d.get('TurretPos', z), float)
    if frame in ('Vehicle', 'VehicleCustomPitch'):
        return z
    if frame == 'Turret':
        return tur
    if frame == 'Barrel':
        return tur + np.array(d.get('BarrelPos', z), float)
    ws = tur + np.array(d.get('PassengerWeaponStationPos', z), float)
    if frame == 'WeaponStation':
        return ws
    if frame == 'WeaponStationBarrel':
        return ws + np.array(d.get('PassengerWeaponStationBarrelPos', z), float)
    a = (d.get('Attachments') or {}).get(frame)
    if a:
        return frame_origin(d, a['Parent']) + np.array(a['Position'], float)
    raise SystemExit(f'no origin for frame {frame}')


def import_weapons(spec, d):
    """Weapons taken from another vehicle (e.g. an M2HB station): their profile ids renamed to this vehicle; the
    profile sources are remembered for copy_profiles."""
    vid = spec['id']
    sources = {}
    for wname, (src_vid, src_w) in spec.get('weaponFrom', {}).items():
        w = copy.deepcopy(vehgen.load(files_of(src_vid)['data'])['Weapons'][src_w])
        text = json.dumps(w)
        for pid in set(re.findall(rf'"{NS}:({src_vid}/[^"]+)"', text)):
            new = f'{vid}/{wname.lower()}/' + pid.split('/', 2)[2]
            sources[new] = pid
            text = text.replace(f'"{NS}:{pid}"', f'"{NS}:{new}"')
        keep_pos = (d['Weapons'].get(wname) or {}).get('ShootPos')
        w = json.loads(text)
        if keep_pos:
            w['ShootPos'] = keep_pos
        w.update(spec.get('weaponSet', {}).get(wname, {}))
        d['Weapons'][wname] = w
    return sources


def drop_rounds(spec, d):
    """AmmoType entries of the listed template rounds are removed (the vehicle never carried them)."""
    drop = set(spec.get('dropRounds', []))
    if not drop:
        return
    for w in d['Weapons'].values():
        if w.get('AmmoType'):
            w['AmmoType'] = [a for a in w['AmmoType']
                             if not any(((a.get('Override') or {}).get('Projectile') or {}).get('Profile', '')
                                        .endswith('_' + r) for r in drop)]


def build_data(spec, tpl, geo, s, old_data, tpl_geo):
    d = copy.deepcopy(tpl)
    vid, tid = spec['id'], spec['template']
    d['ID'] = f'{NS}:{vid}'
    d = json.loads(json.dumps(d).replace(f'{NS}:{tid}/', f'{NS}:{vid}/'))
    bones = bones_of(geo)
    piv = lambda n: np.array(bones[n]['pivot'], float)
    origin = {'Vehicle': np.zeros(3), 'VehicleCustomPitch': np.zeros(3), 'Turret': piv('turret'),
              'Barrel': piv('barell')}
    if 'passengerWeaponStationYaw' in bones:
        origin['WeaponStation'] = piv('passengerWeaponStationYaw')
        origin['WeaponStationBarrel'] = piv('passengerWeaponStationPitch')
        d['PassengerWeaponStationPos'] = r5(geo_to_data(origin['WeaponStation'] - origin['Turret']))
        d['PassengerWeaponStationBarrelPos'] = r5(geo_to_data(origin['WeaponStationBarrel'] - origin['WeaponStation']))
    d['TurretPos'] = r5(geo_to_data(origin['Turret']))
    d['BarrelPos'] = r5(geo_to_data(origin['Barrel'] - origin['Turret']))
    pt = lambda v: np.array(v, float) * s          # spec points are in original model px

    for key in spec.get('dropData', []):
        d.pop(key, None)
    for wname in spec.get('dropWeapons', []):
        d['Weapons'].pop(wname, None)
    at = {}
    tip = spec['geometry'].get('stretch', {}).get('muzzleZ')
    for name, a in spec['attachments'].items():
        # 'tip': the muzzle z the gun stretch ends at (final model px)
        if isinstance(a['at'], str) and a['at'].startswith('pivot:'):
            p = piv(a['at'][6:])
        elif a['at'][2] == 'tip':
            p = pt([a['at'][0], a['at'][1], 0.0]) + [0, 0, tip]
        else:
            p = pt(a['at'])
        at[name] = {'Parent': a['parent'], 'Position': r5(geo_to_data(p - origin[a['parent']])),
                    'Direction': [0, 0, 1]}
        for key in ('RotationChannel',):
            if key in a:
                at[name][key] = a[key]
        origin[name] = p          # later attachments may use this one as their frame
    # the driver's eye (tools/vehgen/driver_cameras.json, data blocks of the original model) scales with the model
    if 'driver_camera' in old_data.get('Attachments', {}):
        eye = old_data['Attachments']['driver_camera']
        at['driver_camera'] = {'Parent': eye['Parent'], 'Position': r5(np.array(eye['Position']) * s),
                               'Direction': eye.get('Direction', [0, 0, 1])}
    d['Attachments'] = at

    seats = []
    for i, seat_spec in enumerate(spec['seats']):
        seat = copy.deepcopy(d['Seats'][seat_spec.get('fromTemplate', i)])
        frame = seat_spec.get('frame', seat.get('Transform', 'Vehicle'))
        seat['Transform'] = frame
        seat['Position'] = r5(geo_to_data(pt(seat_spec['at']) - origin[frame]))
        seat.update(seat_spec.get('set', {}))
        seats.append(seat)
    d['Seats'] = seats

    # the template's ShootPos z convention (some store the muzzle with z negated), from its first weapon whose
    # ShootPos frame is its muzzle attachment's parent or the vehicle
    tpl_sign = None
    for w in tpl['Weapons'].values():
        sp = w.get('ShootPos') or {}
        a = (tpl.get('Attachments') or {}).get(((sp.get('MuzzleAttachments') or [None])[0]))
        tp = sp.get('Transform', 'Vehicle')
        if not a or not sp.get('Positions') or tp not in (a['Parent'], 'Vehicle'):
            continue
        ref = np.array(a['Position'], float) + (frame_origin(tpl, a['Parent']) if tp == 'Vehicle' else 0)
        z0 = sp['Positions'][0][2]
        tpl_sign = -1.0 if abs(z0 + ref[2]) < abs(z0 - ref[2]) else 1.0
        break
    if tpl_sign is None:
        raise SystemExit('template ShootPos convention not found')
    for wname, fields in spec.get('weaponSet', {}).items():
        d['Weapons'][wname].update(fields)
    for wname, names in spec.get('muzzles', {}).items():
        sp = d['Weapons'][wname]['ShootPos']
        for key in ('MuzzleAttachments', 'MuzzleDirectionAttachments', 'EffectAttachments',
                    'EffectDirectionAttachments'):
            if key in sp:
                sp[key] = list(names)
        if 'Directions' in sp:
            sp['Directions'] = [sp['Directions'][0]] * len(names)
    for wname, w in d['Weapons'].items():
        sp = w.get('ShootPos') or {}
        for old, new in spec.get('muzzleRename', {}).items():
            for key in list(sp):
                if sp[key] == old:
                    sp[key] = new
                elif isinstance(sp[key], list):
                    sp[key] = [new if x == old else x for x in sp[key]]
        names = sp.get('MuzzleAttachments') or []
        if names and all(n in at for n in names):
            tp = sp.get('Transform', 'Vehicle')
            sign = tpl_sign

            def shoot(n):
                v = np.array(at[n]['Position'], float)
                if tp == 'Vehicle':
                    v = v + frame_origin(d, at[n]['Parent'])
                elif tp != at[n]['Parent']:
                    raise SystemExit(f'{wname}: ShootPos frame {tp} vs attachment {n} parent {at[n]["Parent"]}')
                return r5([v[0], v[1], sign * v[2]])
            sp['Positions'] = [shoot(n) for n in names]
            sp['ViewPosition'] = shoot(sp.get('ViewAttachment', names[0]))
    for key, value in spec.get('dataSet', {}).items():
        d[key] = value

    # OBBs: template boxes mapped box to box (hull boxes on the hull bone, turret boxes on the turret bone)
    t_b, n_b = bones_of(tpl_geo), bones
    hull_map = (own_box(tpl_geo, 'hull'), own_box(geo, 'hull'))
    tur_map = (own_box(tpl_geo, 'turret'), own_box(geo, 'turret'))
    t_turret, n_turret = np.array(t_b['turret']['pivot']), piv('turret')
    obbs = []
    for o in tpl['OBB']:
        o = copy.deepcopy(o)
        tr = o.get('Transform', 'Vehicle')
        c = vehgen.mtbgeo.data_to_geo(o['Position'])
        half = np.array(o['Size']) * 16
        if tr == 'Turret':
            (slo, shi), (dlo, dhi) = tur_map
            g, k = map_point(c + t_turret, (slo, shi), (dlo, dhi))
            o['Position'] = r5(geo_to_data(g - n_turret))
        elif tr == 'Barrel':
            t_bar = np.array(t_b['barell']['pivot'])
            g, k = map_point(c + t_bar, own_box(tpl_geo, 'barell'), own_box(geo, 'barell'))
            o['Position'] = r5(geo_to_data(g - piv('barell')))
        elif tr in ('Vehicle', None):
            g, k = map_point(c, *hull_map)
            o['Position'] = r5(geo_to_data(g))
        else:
            raise SystemExit(f'OBB transform {tr} has no mapping')
        o['Size'] = r5(half * np.abs(k) / 16)
        obbs.append(o)
    if spec.get('obb'):
        # OBBs from this model's own boxes (model px, original model; 'bone' = that bone's own polygons)
        obbs = []
        for o in spec['obb']:
            if 'bone' in o:
                lo, hi = own_box(geo, o['bone'])
            else:
                lo, hi = np.array(o['box'][0], float) * s, np.array(o['box'][1], float) * s
            tr = o.get('transform', 'Vehicle')
            c = (lo + hi) / 2 - (origin[tr] if tr in origin else 0)
            box = {'Size': r5((hi - lo) / 32), 'Position': r5(geo_to_data(c))}
            if tr != 'Vehicle':
                box['Transform'] = box['Rotation'] = tr
            if o.get('part'):
                box['Part'] = o['part']
            obbs.append(box)
    d['OBB'] = obbs
    for key in spec.get('keepData', []):
        if key in old_data:
            d[key] = copy.deepcopy(old_data[key])
        else:
            d.pop(key, None)
    contacts = []
    for name in vehgen.wheel_bones(bones):
        P = np.array(bones[name]['poly_mesh']['positions'])
        c = geo_to_data((P.min(0) + P.max(0)) / 2)
        contacts.append(r5([c[0], 0, c[2]]))
    d['TerrainCompat'] = contacts
    return d


def build_armor(spec, tpl, geo, tpl_geo):
    """Template armour mapped box to box per frame. Armour profile p <-> geo px (-16 p.x, 16 p.y, 16 p.z)."""
    a = copy.deepcopy(tpl)
    a['id'] = spec['id']
    maps = {'hull': (own_box(tpl_geo, 'hull'), own_box(geo, 'hull')),
            'turret': (own_box(tpl_geo, 'turret'), own_box(geo, 'turret')),
            'barrel': (own_box(tpl_geo, 'barell'), own_box(geo, 'barell'))}
    for key in ('plates', 'engines', 'ammo_racks', 'sensitive_internals', 'modules', 'explosive_reactive_armor',
                'tracks'):
        for item in a.get(key, []):
            frame = item.get('frame', 'hull')
            if frame not in maps:
                raise SystemExit(f'armor {key}: frame {frame} has no mapping')
            c = np.array(item['center'], float)
            g = np.array([-c[0] * 16, c[1] * 16, c[2] * 16])
            m, k = map_point(g, *maps[frame])
            item['center'] = r5([-m[0] / 16, m[1] / 16, m[2] / 16])
            item['half_size'] = r5(np.array(item['half_size']) * np.abs(k))
    for key, frame in spec.get('armorDrop', {}).items():
        a[key] = [item for item in a.get(key, []) if item.get('frame', 'hull') != frame]
    a.update(spec.get('armorSet', {}))
    return a


def build_client(spec, old_client, s, track_x=None, pivots=None):
    c = copy.deepcopy(old_client)
    rg = c.get('RunningGear', {}).get('TrackRender', {})
    lay = rg.get('EvaluationLayout', {})
    for k in ('YCenter', 'Radius', 'ZRear', 'ZFront'):
        if k in lay:
            lay[k] = round(lay[k] * s, 5)
    if 'LinkHalfThickness' in rg:
        rg['LinkHalfThickness'] = round(rg['LinkHalfThickness'] * s, 5)
    sides = {x['Side']: x for x in rg.get('Sides', [])}
    for side in sides.values():
        for k in ('XCenter', 'YCenter', 'Radius', 'YBottom', 'YTop', 'ZRear', 'ZFront'):
            side[k] = round(side[k] * s, 5)
        side['RoadCenters'] = [round(v * s, 5) for v in side['RoadCenters']]
    src = spec['geometry'].get('mirrorRunningGear')
    if src and src in sides:
        other = sides['R' if src == 'L' else 'L']
        other['XCenter'] = -sides[src]['XCenter']
    if spec.get('rig'):
        c['FittedGroundRig'] = {'Schema': 1, 'Frame': 'RUNTIME_MODEL_PIXELS',
                                'PitchBones': [{'Bone': n, 'Parent': 'turret', 'Pivot': pivots[n]}
                                               for n in spec['rig']]}
    if track_x is not None:
        for name, side in sides.items():
            side['XCenter'] = round((track_x if name == 'L' else -track_x) * s, 5)
    for o in c.get('EngineExhaust', {}).get('Origins', []):
        o['Position'] = r5(np.array(o['Position']) * s)
    return c


def copy_profiles(spec, data, out, sources=None):
    """Every projectile profile the data names under this vehicle is copied from the template's; renamed rounds
    get their new round id."""
    vid, tid = spec['id'], spec['template']
    base = os.path.join(DATA, 'sbw', 'projectile_profiles')
    ids = set(re.findall(rf'"{NS}:({vid}/[^"]+)"', json.dumps(data)))
    written = {}
    back = {new['id']: old for old, new in spec.get('rounds', {}).items()}
    for pid in sorted(ids):
        tpid = pid.replace(f'{vid}/', f'{tid}/', 1)
        for new_id, old_id in back.items():
            tpid = re.sub(rf'_{new_id}$', f'_{old_id}', tpid)
        if sources and pid in sources:
            tpid = sources[pid]
        src = os.path.join(base, tpid + '.json')
        dst = os.path.join(base, pid + '.json')
        if not os.path.exists(src):
            raise SystemExit(f'profile {pid}: template file {rel(src)} missing')
        p = vehgen.load(src)
        p = json.loads(json.dumps(p).replace(f'{NS}:{tid}/', f'{NS}:{vid}/'))
        if sources and pid in sources:
            p = json.loads(json.dumps(p).replace(f'{NS}:{sources[pid].split("/")[0]}/', f'{NS}:{vid}/'))
        rid = p.get('Combat', {}).get('RoundId', '').split(':')[-1]
        if rid in spec.get('rounds', {}):
            new = spec['rounds'][rid]
            p['Combat']['RoundId'] = f'{NS}:{new["id"]}'
            p['Combat'].update(new.get('combat', {}))
        out.json(dst, p)
        written[pid] = p
    # a weapon's default profile follows its first remaining round (after dropped/renamed rounds)
    for w in data['Weapons'].values():
        default = (w.get('Projectile') or {}).get('Profile', '').split(':', 1)[-1]
        first = next(iter(w.get('AmmoType') or []), None)
        fpid = ((first or {}).get('Override') or {}).get('Projectile', {}).get('Profile', '').split(':', 1)[-1]
        if spec.get('defaultFromFirst') and default in written and fpid in written and default != fpid:
            out.json(os.path.join(base, default + '.json'), written[fpid])


def rename_rounds(spec, data):
    """Profile paths and ammo names of renamed rounds."""
    text = json.dumps(data)
    for old, new in spec.get('rounds', {}).items():
        text = text.replace(f'_{old}"', f'_{new["id"]}"').replace(f'.{old}"', f'.{new["id"]}"')
    return json.loads(text)


def register(spec, out):
    vid, tid = spec['id'], spec['template']
    const = vid.upper()
    if const[0].isdigit():      # Java names: 9p149_shturm -> NINE_P_149_SHTURM, like NINE_P_148
        const = re.sub(r'^9([A-Z])(\d+)', r'NINE_\1_\2', const)
    path = os.path.join(JAVA, 'init', 'ModEntities.java')
    src = open(path).read()
    if f'"{vid}"' not in src:
        m = re.search(r'( *)public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> LEOPARD_2A4 =\n'
                      r'.*?;\n', src, re.S)
        w, h = spec['hitbox']
        ind = m.group(1)
        line = (f'{ind}public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> {const} =\n'
                f'{ind}        ENTITIES.register("{vid}", () -> vehicle(\n'
                f'{ind}                (type, level) -> new FittedGroundVehicleEntity(type, level, "{vid}"),\n'
                f'{ind}                {w}f, {h}f, "{vid}"));\n')
        out.text(path, src, src[:m.end()] + line + src[m.end():])
    path = os.path.join(JAVA, 'init', 'ModEntityRenderers.java')
    src = open(path).read()
    if f'"{vid}"' not in src:
        m = re.search(r'( *)registerVehicle\(event, ModEntities\.LEOPARD_2A4, "leopard_2a4", .*?;\n', src, re.S)
        ind = m.group(1)
        line = (f'{ind}registerVehicle(event, ModEntities.{const}, "{vid}", context ->\n'
                f'{ind}        new FittedGroundVehicleRenderer(context, bvp("custom_geo/{vid}.geo.json"),\n'
                f'{ind}                bvp("textures/entity/{vid}.png"), "{vid}"));\n')
        out.text(path, src, src[:m.end()] + line + src[m.end():])
    path = os.path.join(DATA, 'creative_tabs.json')
    src = open(path).read()
    if f'"{vid}"' not in src:
        i = src.index(f'"{tid}",\n')
        indent = src[src.rfind('\n', 0, i) + 1:i]
        j = i + len(f'"{tid}",\n')
        out.text(path, src, src[:j] + indent + f'"{vid}",\n' + src[j:])
    path = os.path.join(ASSETS, 'lang', 'en_us.json')
    src = open(path).read()
    new = src
    for key, value in spec.get('lang', {}).items():
        if f'"{key}"' not in new:
            i = new.index(f'  "entity.{NS}.{vid}": ')
            j = new.index('\n', i) + 1
            new = new[:j] + f'  "{key}": {json.dumps(value)},\n' + new[j:]
    out.text(path, src, new)


def main(argv):
    write = '--write' in argv
    vid = [a for a in argv if not a.startswith('--')][0]
    spec = SPECS[vid]
    tid = spec['template']
    out = vehgen.Output(write)
    orig = originals(vid, write)
    tpl_geo = vehgen.load(files_of(tid)['geo'])
    geo = copy.deepcopy(orig['geo'])
    G = spec['geometry']
    report = []

    add_bones(geo, G.get('newBones', {}))
    for mv in G.get('moves', []):
        report.append(f'{move_components(geo, mv)} polys {mv["from"]} -> {mv["to"]}')
    for name, pivot in G.get('setPivots', {}).items():
        bones_of(geo)[name]['pivot'] = list(pivot)
    if G.get('mirrorRunningGear'):
        moved = mirror_running_gear(geo, G['mirrorRunningGear'])
        report.append(f'mirrored running gear: {len(moved)} bones, e.g. {moved[:3]}')
    track_x = None
    if G.get('mirrorMeshes'):
        report.append(f'running gear rebuilt from the {G["mirrorMeshes"]} side: {mirror_meshes(geo, G["mirrorMeshes"])} bones')
    if G.get('symmetrizeRunningGear'):
        track_x, outer = symmetrize_running_gear(geo, G.get('wheelOuter'))
        report.append(f'running gear symmetric: outer face +-{outer} px, track centre +-{track_x} px')
    t_lo, t_hi = own_box(tpl_geo, 'hull')
    lo, hi = own_box(geo, 'hull')
    s = G['scale'] if isinstance(G.get('scale'), (int, float)) else float((t_hi[2] - t_lo[2]) / (hi[2] - lo[2]))
    if G.get('stretch'):
        dz, n = stretch(geo, G['stretch'], s)
        report.append(f'gun stretched {dz:.2f} px ({n} vertices)')
    scale_geo(geo, s)
    report.append(f'scale {s:.5f}')

    out.json(files_of(vid)['geo'], geo, compact=True)
    out.json(files_of(vid)['wreck'], build_wreck(geo, orig['wreck']), compact=True)

    class B:
        pass
    b = B()
    b.id = vid
    out.json(files_of(vid)['fallback'], vehgen.native_fallback(b, geo), compact=True)
    pivots = {b['name']: b['pivot'] for b in geo['minecraft:geometry'][0]['bones']}
    out.json(files_of(vid)['client'], build_client(spec, orig['client'], s, track_x, pivots))

    tpl_data = vehgen.load(files_of(tid)['data'])
    data = build_data(spec, tpl_data, geo, s, orig['data'], tpl_geo)
    sources = import_weapons(spec, data)
    drop_rounds(spec, data)
    data = rename_rounds(spec, data)
    out.json(files_of(vid)['data'], data)
    copy_profiles(spec, data, out, sources)
    out.json(files_of(vid)['armor'], build_armor(spec, vehgen.load(files_of(tid)['armor']), geo, tpl_geo))
    register(spec, out)

    print(f'{vid} from {tid}:')
    for line in report:
        print('  ' + line)
    print(f'  TurretPos {data["TurretPos"]} BarrelPos {data["BarrelPos"]} '
          f'station {data.get("PassengerWeaponStationPos")} / {data.get("PassengerWeaponStationBarrelPos")}')
    for i, seat in enumerate(data['Seats']):
        print(f'  seat {i} {seat["Transform"]:<10} {seat["Position"]} {seat.get("Weapons", "")}')
    for k, v in data['Attachments'].items():
        print(f'  attachment {k:<16} {v["Parent"]:<20} {v["Position"]}')
    print(('wrote ' if write else 'would write ') + f'{len(out.files)} files')
    for f in out.files:
        print('  ' + rel(f))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
