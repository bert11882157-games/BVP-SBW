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
    at = {}
    tip = spec['geometry'].get('stretch', {}).get('muzzleZ')
    for name, a in spec['attachments'].items():
        # 'tip': the muzzle z the gun stretch ends at (final model px)
        p = pt([a['at'][0], a['at'][1], 0.0]) + [0, 0, tip] if a['at'][2] == 'tip' else pt(a['at'])
        at[name] = {'Parent': a['parent'], 'Position': r5(geo_to_data(p - origin[a['parent']])),
                    'Direction': [0, 0, 1]}
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
            # the template stores ShootPos with the attachment's z negated (nose -z)
            sp['Transform'] = at[names[0]]['Parent']
            sp['Positions'] = [[at[n]['Position'][0], at[n]['Position'][1], -at[n]['Position'][2]] for n in names]
            v = at[sp.get('ViewAttachment', names[0])]['Position']
            sp['ViewPosition'] = [v[0], v[1], -v[2]]
            sp['Directions'] = [at[names[0]]['Parent']] * len(names)
    for wname, fields in spec.get('weaponSet', {}).items():
        d['Weapons'][wname].update(fields)
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
        elif tr in ('Vehicle', None):
            g, k = map_point(c, *hull_map)
            o['Position'] = r5(geo_to_data(g))
        else:
            raise SystemExit(f'OBB transform {tr} has no mapping')
        o['Size'] = r5(half * np.abs(k) / 16)
        obbs.append(o)
    d['OBB'] = obbs
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
    a.update(spec.get('armorSet', {}))
    return a


def build_client(spec, old_client, s):
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
    for o in c.get('EngineExhaust', {}).get('Origins', []):
        o['Position'] = r5(np.array(o['Position']) * s)
    return c


def copy_profiles(spec, data, out):
    """Every projectile profile the data names under this vehicle is copied from the template's; renamed rounds
    get their new round id."""
    vid, tid = spec['id'], spec['template']
    base = os.path.join(DATA, 'sbw', 'projectile_profiles')
    ids = set(re.findall(rf'"{NS}:({vid}/[^"]+)"', json.dumps(data)))
    back = {new['id']: old for old, new in spec.get('rounds', {}).items()}
    for pid in sorted(ids):
        tpid = pid.replace(f'{vid}/', f'{tid}/', 1)
        for new_id, old_id in back.items():
            tpid = re.sub(rf'_{new_id}$', f'_{old_id}', tpid)
        src = os.path.join(base, tpid + '.json')
        dst = os.path.join(base, pid + '.json')
        if not os.path.exists(src):
            raise SystemExit(f'profile {pid}: template file {rel(src)} missing')
        p = vehgen.load(src)
        p = json.loads(json.dumps(p).replace(f'{NS}:{tid}/', f'{NS}:{vid}/'))
        rid = p.get('Combat', {}).get('RoundId', '').split(':')[-1]
        if rid in spec.get('rounds', {}):
            new = spec['rounds'][rid]
            p['Combat']['RoundId'] = f'{NS}:{new["id"]}'
            p['Combat'].update(new.get('combat', {}))
        out.json(dst, p)


def rename_rounds(spec, data):
    """Profile paths and ammo names of renamed rounds."""
    text = json.dumps(data)
    for old, new in spec.get('rounds', {}).items():
        text = text.replace(f'_{old}"', f'_{new["id"]}"').replace(f'.{old}"', f'.{new["id"]}"')
    return json.loads(text)


def register(spec, out):
    vid, tid = spec['id'], spec['template']
    const = vid.upper()
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
    if G.get('mirrorRunningGear'):
        report.append(f'mirrored running gear: {mirror_running_gear(geo, G["mirrorRunningGear"])}')
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
    out.json(files_of(vid)['client'], build_client(spec, orig['client'], s))

    tpl_data = vehgen.load(files_of(tid)['data'])
    data = build_data(spec, tpl_data, geo, s, orig['data'], tpl_geo)
    data = rename_rounds(spec, data)
    out.json(files_of(vid)['data'], data)
    copy_profiles(spec, data, out)
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
