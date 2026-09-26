#!/usr/bin/env python3
"""Scales ground vehicles (tanks, IFVs, APCs, trucks, cars) up by a factor, everything together.

Every length that belongs to a vehicle is multiplied about the vehicle origin:
  - models: custom_geo/<id>.geo.json, custom_geo/<id>_turret_wreck.geo.json, geo/native_fallback/<id>.geo.json
    (bone pivots, poly-mesh positions, cube origins/sizes/pivots/inflate, locators, visible bounds);
  - gameplay data (data/.../sbw/vehicles/<id>.json): OBBs, seats, cameras, dismount points, turret/barrel pivots,
    attachments, weapon muzzles and view points, terrain probes, rotate height, third-person camera;
  - presentation (assets/.../sbw/vehicles/<id>.json): track paths and bounds, exhaust origins, fitted rig pivots;
  - armor profiles (data/.../armor/<id>.json): plates, modules, engines, ammo racks, ERA, OBB mirrors;
  - the registered entity size in ModEntities.java.
Wheel and track visual spin rates are divided by the factor (bigger wheels turn slower at the same speed).
Directions, angles, speeds, masses, armour thicknesses and step height are left alone.

tools/vehicle_scale/applied.json records what was scaled, so running again never scales twice.
Usage: python3 tools/vehicle_scale/scale.py [--write] [--factor 1.1] [id...]   (no ids: every ground vehicle)
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..', '..')
GEN = os.path.join(ROOT, 'bvp', 'src', 'generated', 'resources')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles')
ASSETS = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles')
ARMOR = os.path.join(GEN, 'data/berts_vehicle_pack/armor')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
NATIVE = os.path.join(GEN, 'assets/berts_vehicle_pack/geo/native_fallback')
ENTITIES = os.path.join(ROOT, 'bvp/src/main/java/com/yourname/berts_vehicle_pack/init/ModEntities.java')
APPLIED = os.path.join(HERE, 'applied.json')
GROUND_TYPES = ('Tank', 'APC', 'Car')

# gameplay data: array keys holding one point/extent (or a list of points) in blocks
DATA_POINTS = {'Position', 'Size', 'Pivot', 'ZoomPosition', 'ViewPosition', 'ShootPositionForHud', 'BarrelPos',
               'TurretPos', 'PassengerWeaponStationPos', 'PassengerWeaponStationBarrelPos', 'ThirdPersonCameraPos',
               'Positions', 'TerrainCompat', 'Simulate3PPos'}
DATA_SCALARS = {'RotateOffsetHeight'}
SPIN_RATES = {'WheelRotSpeed', 'TrackRotSpeed'}
TRACK_SCALARS = {'Radius', 'YCenter', 'ZFront', 'ZRear', 'XCenter', 'YBottom', 'YTop', 'LinkHalfThickness',
                 'SourceYMin', 'SourceYMax', 'SourceZMin', 'SourceZMax'}


def r(v):
    return round(float(v), 5)


def sc(x, k):
    """x scaled; an integer zero stays an integer (keeps the files' own spelling)."""
    return x if isinstance(x, int) and x == 0 else r(x * k)


def is_vec(v):
    return isinstance(v, list) and v and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in v)


def scale_points(node, keys, scalars, k, spin=()):
    """Scales, in place, every numeric array under one of [keys] (nested lists of arrays too) and every scalar
    named in [scalars]; divides scalars named in [spin]."""
    count = 0
    if isinstance(node, dict):
        for name, v in node.items():
            if name in keys:
                count += scale_any(node, name, k)
            elif name in scalars and isinstance(v, (int, float)) and not isinstance(v, bool):
                node[name] = r(v * k); count += 1
            elif name in spin and isinstance(v, (int, float)) and not isinstance(v, bool):
                node[name] = r(v / k); count += 1
            else:
                count += scale_points(v, keys, scalars, k, spin)
    elif isinstance(node, list):
        for v in node:
            count += scale_points(v, keys, scalars, k, spin)
    return count


def scale_any(parent, name, k):
    v = parent[name]
    if is_vec(v):
        parent[name] = [sc(x, k) for x in v]
        return 1
    if isinstance(v, list):
        n = 0
        for i, item in enumerate(v):
            if is_vec(item):
                v[i] = [sc(x, k) for x in item]; n += 1
        return n
    return 0


def scale_geo(path, k):
    geo = json.load(open(path))
    n = 0
    for g in geo['minecraft:geometry']:
        d = g.get('description', {})
        for key in ('visible_bounds_width', 'visible_bounds_height'):
            if key in d:
                d[key] = r(d[key] * k)
        if 'visible_bounds_offset' in d:
            d['visible_bounds_offset'] = [sc(x, k) for x in d['visible_bounds_offset']]
        for b in g.get('bones', []):
            if 'pivot' in b:
                b['pivot'] = [sc(x, k) for x in b['pivot']]
            pm = b.get('poly_mesh')
            if pm and pm.get('positions'):
                pm['positions'] = [[sc(x, k) for x in p] for p in pm['positions']]
                n += len(pm['positions'])
            for c in b.get('cubes', []):
                for key in ('origin', 'size', 'pivot'):
                    if key in c:
                        c[key] = [sc(x, k) for x in c[key]]
                if 'inflate' in c:
                    c['inflate'] = r(c['inflate'] * k)
                n += 1
            for name, loc in (b.get('locators') or {}).items():
                if is_vec(loc):
                    b['locators'][name] = [sc(x, k) for x in loc]
                elif isinstance(loc, dict) and 'offset' in loc:
                    loc['offset'] = [sc(x, k) for x in loc['offset']]
    return geo, n


def scale_track(tr, k):
    n = 0
    def path(p):
        nonlocal n
        for key in ('MoveY', 'MoveZ'):
            p[key] = [[ph, r(v * k)] for ph, v in p[key]]
            n += len(p[key])
        for key in ('SourceYMin', 'SourceYMax', 'SourceZMin', 'SourceZMax'):
            if key in p:
                p[key] = r(p[key] * k)
    if 'Path' in tr:
        path(tr['Path'])
    for key in TRACK_SCALARS:
        if key in tr:
            tr[key] = r(tr[key] * k); n += 1
    ev = tr.get('EvaluationLayout') or {}
    for key in TRACK_SCALARS:
        if key in ev:
            ev[key] = r(ev[key] * k); n += 1
    for side in tr.get('Sides', []):
        for key in TRACK_SCALARS:
            if key in side:
                side[key] = r(side[key] * k); n += 1
        if 'RoadCenters' in side:
            side['RoadCenters'] = [sc(x, k) for x in side['RoadCenters']]
        if 'Path' in side:
            path(side['Path'])
    return n


def dump_like(path, doc, pairs=False):
    raw = open(path, encoding='utf-8').read()
    indent = 2 if raw.startswith('{\n') else None
    text = json.dumps(doc, indent=indent, ensure_ascii=False) if indent else json.dumps(doc, separators=(',', ':'), ensure_ascii=False)
    if indent and pairs:
        # keyframe pairs stay on one line, as tools/track_path writes them
        text = re.sub(r'\[\n\s*(-?[\d.eE+-]+),\n\s*(-?[\d.eE+-]+)\n\s*\]', r'[\1, \2]', text)
    return text + ('\n' if raw.endswith('\n') else '')


def ground_ids():
    out = []
    for f in sorted(os.listdir(DATA)):
        d = json.load(open(os.path.join(DATA, f), encoding='utf-8'))
        if d.get('Type') in GROUND_TYPES:
            out.append(f[:-5])
    return out


def scale_vehicle(vid, k):
    """{path: new text} for every file of [vid], and a summary."""
    out, notes = {}, []
    for path in (os.path.join(GEO, vid + '.geo.json'), os.path.join(GEO, vid + '_turret_wreck.geo.json'),
                 os.path.join(NATIVE, vid + '.geo.json')):
        if os.path.exists(path):
            geo, n = scale_geo(path, k)
            raw = open(path).read()
            text = json.dumps(geo, separators=(',', ':')) if not raw.startswith('{\n') else json.dumps(geo, indent=2)
            out[path] = text + ('\n' if raw.endswith('\n') else '')
            notes.append(f'{os.path.basename(path)} {n}')
    dpath = os.path.join(DATA, vid + '.json')
    d = json.load(open(dpath, encoding='utf-8'))
    n = scale_points(d, DATA_POINTS, DATA_SCALARS, k, SPIN_RATES)
    out[dpath] = dump_like(dpath, d)
    notes.append(f'data {n}')
    apath = os.path.join(ASSETS, vid + '.json')
    if os.path.exists(apath):
        a = json.load(open(apath, encoding='utf-8'))
        n = 0
        tr = (a.get('RunningGear') or {}).get('TrackRender')
        if tr:
            n += scale_track(tr, k)
        n += scale_points(a.get('EngineExhaust') or {}, {'Position'}, set(), k)
        n += scale_points(a.get('FittedGroundRig') or {}, {'Pivot'}, set(), k)
        out[apath] = dump_like(apath, a, pairs=True)
        notes.append(f'assets {n}')
    rpath = os.path.join(ARMOR, vid + '.json')
    if os.path.exists(rpath):
        arm = json.load(open(rpath, encoding='utf-8'))
        n = 0
        for key, v in arm.items():
            if isinstance(v, list):
                for item in v:
                    if isinstance(item, dict):
                        for pk in ('center', 'half_size', 'Position', 'Size'):
                            if is_vec(item.get(pk)):
                                item[pk] = [sc(x, k) for x in item[pk]]; n += 1
        out[rpath] = dump_like(rpath, arm)
        notes.append(f'armor {n}')
    return out, notes


def scale_entities(ids, k, text):
    """Resizes the vehicle(..., width, height, "id") registrations of [ids] (plain or lambda factories)."""
    count = 0
    def sub(m):
        nonlocal count
        if m.group(3) not in ids:
            return m.group(0)
        count += 1
        w, h = float(m.group(1)) * k, float(m.group(2)) * k
        return f'{w:.2f}f, {h:.2f}f, "{m.group(3)}"))'
    text = re.sub(r'(?<=[\s,])([\d.]+)f,\s*([\d.]+)f,\s*"(\w+)"\)\)', sub, text)
    return text, count


def main(argv):
    write = '--write' in argv
    k = float(argv[argv.index('--factor') + 1]) if '--factor' in argv else 1.1
    ids = [a for a in argv if not a.startswith('--') and not re.fullmatch(r'[\d.]+', a)] or ground_ids()
    applied = json.load(open(APPLIED)) if os.path.exists(APPLIED) else {}
    todo = [v for v in ids if v not in applied]
    for vid in ids:
        if vid in applied:
            print(f'{vid:24s} already scaled x{applied[vid]}')
    writes = {}
    for vid in todo:
        out, notes = scale_vehicle(vid, k)
        writes.update(out)
        print(f'{vid:24s} ' + ', '.join(notes))
    ent = open(ENTITIES).read()
    ent2, count = scale_entities(set(todo), k, ent)
    print(f'ModEntities: {count} of {len(todo)} registrations resized')
    if write and todo:
        for path, text in writes.items():
            with open(path, 'w', encoding='utf-8') as fh:
                fh.write(text)
        with open(ENTITIES, 'w') as fh:
            fh.write(ent2)
        for vid in todo:
            applied[vid] = k
        with open(APPLIED, 'w') as fh:
            json.dump(dict(sorted(applied.items())), fh, indent=1)
            fh.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
