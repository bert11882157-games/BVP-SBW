#!/usr/bin/env python3
"""Helicopter suspended armament: moves each helicopter's built-in pylon stores out of the always-drawn model into
per-pylon bones that the loadout shows only while that store is fitted, and writes the matching armament definition,
stores and weapon channels.

For every helicopter in heli_stores.json:
  * geometry: the connected parts of the source bones whose centres fall inside a mount's box (|x|, y, z in
    vehicle-local blocks, +X left, +Z forward) move to bones suspended_<mount>_l / _r (left = +X); parts of a
    suspended_legacy_hull bone that no mount claims (pylon adapters, racks) go back to the hull, and the empty legacy
    bone is removed;
  * weapons: a rocket or gun mount fires its own channel, copied from a template weapon, from the front of the pod on
    each side (left/right alternating: AircraftRocketPodOrder reads the side from the muzzle attachment name); a
    missile mount fires its existing channel from the front of each launch tube, left and right interleaved;
  * armament: a pair per mount, its store allowed, the store's bones as the mount's StoreGroups and the channel as its
    NativeWeaponIds; every channel is a SuspendedWeapon, so nothing fires until the store is fitted.
Usage: heli_stores.py <tree> [--preview=<dir>] [--write] ids...
"""
import copy, json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from comps import bone_parts, to_local

CONFIG = os.path.join(HERE, 'heli_stores.json')


def rel(tree, *parts):
    return os.path.join(tree, r'bvp\src\generated\resources', *parts)


def load(path):
    return json.load(open(path, encoding='utf-8-sig'))


def dump(path, doc):
    raw = open(path, encoding='utf-8').read() if os.path.exists(path) else '{\n'
    indent = 2 if raw.startswith('{\n') else None
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(doc, indent=indent, ensure_ascii=False) + ('\n' if raw.endswith('\n') or indent else ''))


def in_box(c, box, sign):
    x = c[0] * sign
    if not (box['x'][0] <= x <= box['x'][1]):
        return False
    for k, i in (('y', 1), ('z', 2)):
        if k in box and not (box[k][0] <= c[i] <= box[k][1]):
            return False
    return True


def new_bone(src, name, polys):
    """A bone holding the given polys of src, with its own compact position/normal/uv arrays."""
    pm = src['poly_mesh']
    keep = {}, {}, {}
    out = []
    for pi in polys:
        poly = []
        for v in pm['polys'][pi]:
            idx = []
            for slot, key in enumerate(('positions', 'normals', 'uvs')):
                if slot >= len(v):
                    break
                m = keep[slot]
                if v[slot] not in m:
                    m[v[slot]] = len(m)
                idx.append(m[v[slot]])
            poly.append(idx)
        out.append(poly)
    mesh = {k: v for k, v in pm.items() if k not in ('positions', 'normals', 'uvs', 'polys')}
    for slot, key in enumerate(('positions', 'normals', 'uvs')):
        if key in pm:
            arr = [None] * len(keep[slot])
            for old, new in keep[slot].items():
                arr[new] = pm[key][old]
            mesh[key] = arr
    mesh['polys'] = out
    bone = {k: v for k, v in src.items() if k not in ('poly_mesh', 'name', 'parent', 'cubes')}
    bone['name'] = name
    bone['parent'] = src.get('parent') if src['name'] != 'hull' and src.get('parent') else 'hull'
    bone['poly_mesh'] = mesh
    return bone


def remove_polys(bone, polys):
    drop = set(polys)
    pm = bone['poly_mesh']
    pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in drop]


def add_polys(dst, src, polys):
    """Appends src's polys to dst (both poly_mesh bones), copying the referenced vertex data."""
    a, b = dst['poly_mesh'], src['poly_mesh']
    maps = [{}, {}, {}]
    for pi in polys:
        poly = []
        for v in b['polys'][pi]:
            idx = []
            for slot, key in enumerate(('positions', 'normals', 'uvs')):
                if slot >= len(v):
                    break
                if v[slot] not in maps[slot]:
                    maps[slot][v[slot]] = len(a[key])
                    a[key].append(b[key][v[slot]])
                idx.append(maps[slot][v[slot]])
            poly.append(idx)
        a['polys'].append(poly)


def tube_fronts(parts, tube):
    """Front centre of each launch tube part (vehicle-local)."""
    out = []
    for q in parts:
        s = q['hi'] - q['lo']
        if s[2] >= tube['min_len'] and max(s[0], s[1]) <= tube['max_dia']:
            c = (q['lo'] + q['hi']) / 2
            out.append([round(float(c[0]), 4), round(float(c[1]), 4), round(float(q['hi'][2]), 4)])
    return out


def pod_front(parts):
    body = max(parts, key=lambda q: len(q['polys']) * float(np.prod(q['hi'] - q['lo'] + 1e-3)))
    c = (body['lo'] + body['hi']) / 2
    return [round(float(c[0]), 4), round(float(c[1]), 4), round(float(body['hi'][2]), 4)]


def attach_point(parts):
    lo = np.min([q['lo'] for q in parts], 0); hi = np.max([q['hi'] for q in parts], 0)
    return [round(float((lo[0] + hi[0]) / 2), 4), round(float(hi[1]), 4), round(float((lo[2] + hi[2]) / 2), 4)]


def convert(tree, vid, cfg, preview=None, write=False):
    geo_path = rel(tree, r'assets\berts_vehicle_pack\custom_geo', vid + '.geo.json')
    data_path = rel(tree, r'data\berts_vehicle_pack\sbw\vehicles', vid + '.json')
    arm_path = rel(tree, r'data\berts_vehicle_pack\sbw\aircraft_armaments', vid + '.json')
    geo = load(geo_path)
    data = load(data_path)
    arm = load(arm_path) if os.path.exists(arm_path) else {'Schema': 1, 'Name': data.get('Name', vid)}
    bones = geo['minecraft:geometry'][0]['bones']
    by_name = {b['name']: b for b in bones}
    hull = by_name['hull']
    report = []
    claimed = {}           # (bone, part index) -> mount
    side_parts = {}        # (mount id, side) -> [(bone name, part)]
    for src_name in cfg['sources']:
        _, parts = bone_parts(geo, src_name)
        for i, q in enumerate(parts):
            c = (q['lo'] + q['hi']) / 2
            for m in cfg['mounts']:
                hit = None
                for side, sign in (('l', 1), ('r', -1)):
                    if in_box(c, m['box'], sign):
                        hit = side
                if hit:
                    side_parts.setdefault((m['id'], hit), []).append((src_name, q))
                    claimed[(src_name, i)] = m['id']
                    break
        # unclaimed parts of a legacy bone go back to the hull
        if src_name.startswith('suspended_'):
            rest = [pi for i, q in enumerate(parts) if (src_name, i) not in claimed for pi in q['polys']]
            report.append(f'{src_name}: {len(parts) - len([k for k in claimed if k[0] == src_name])} parts back to hull')
            if rest:
                add_polys(hull, by_name[src_name], rest)
    # new bones
    new_bones = []
    for m in cfg['mounts']:
        for side in ('l', 'r'):
            items = side_parts.get((m['id'], side), [])
            if not items:
                raise SystemExit(f'{vid}: mount {m["id"]} side {side} matched nothing')
            name = f'suspended_{m["id"]}_{side}'
            polys_by_bone = {}
            for b, q in items:
                polys_by_bone.setdefault(b, []).extend(q['polys'])
            first = True
            bone = None
            for b, polys in polys_by_bone.items():
                if first:
                    bone = new_bone(by_name[b], name, polys); first = False
                else:
                    add_polys(bone, by_name[b], polys)
            new_bones.append(bone)
            report.append(f'{name}: {sum(len(q["polys"]) for _, q in items)} polys from {sorted(polys_by_bone)}')
    # remove moved polys from the sources (recompute part lists before mutation)
    for src_name in cfg['sources']:
        _, parts = bone_parts(geo, src_name)
        moved = [pi for i, q in enumerate(parts) if (src_name, i) in claimed for pi in q['polys']]
        if src_name.startswith('suspended_'):
            bones.remove(by_name[src_name])
        else:
            remove_polys(by_name[src_name], moved)
    hull_index = bones.index(hull)
    for k, b in enumerate(new_bones):
        bones.insert(hull_index + 1 + k, b)

    # weapons
    weapons = data['Weapons']
    attachments = data.setdefault('Attachments', {})
    channels = []
    pairs = []
    for m in cfg['mounts']:
        L = [q for _, q in side_parts[(m['id'], 'l')]]
        R = [q for _, q in side_parts[(m['id'], 'r')]]
        allowed = [m['store']] + m.get('extra_allowed', [])
        pair = {'Id': m['id'], 'Name': m['name'], 'Left': attach_point(L), 'Right': attach_point(R),
                'AllowedStores': allowed,
                'StoreGroups': {m['store']: [f'suspended_{m["id"]}_l', f'suspended_{m["id"]}_r']},
                'MaxWeaponsPerPylon': 1, 'MaxPylonMassKg': m.get('max_pylon_kg', 1000)}
        pairs.append(pair)
        if m['kind'] == 'store':
            continue          # a released guided store: the store system fires it, no native channel
        ch = m['channel']
        pair['NativeWeaponIds'] = {m['store']: ch}
        template = m.get('template', ch)
        if ':' in template:
            other, name = template.split(':')
            source = load(rel(tree, r'data\berts_vehicle_pack\sbw\vehicles', other + '.json'))['Weapons'][name]
        else:
            source = weapons[template]
        if m['kind'] in ('rocket', 'gun'):
            w = copy.deepcopy(source)
            fl, fr = pod_front(L), pod_front(R)
            positions = [fl, fr]
            names = [f'{ch}_left_0', f'{ch}_right_0']
            if m['kind'] == 'rocket':
                w['Magazine'] = m['capacity'] * 2
                w['RPM'] = 600; w['DefaultFireMode'] = 'Auto'; w['AvailableFireModes'] = 'Auto'
        else:
            w = copy.deepcopy(source)
            tl = sorted(tube_fronts(L, m['tube']), key=lambda p: (-p[1], p[0]))
            tr = sorted(tube_fronts(R, m['tube']), key=lambda p: (-p[1], -p[0]))
            if len(tl) != len(tr) or not tl:
                raise SystemExit(f'{vid}: {m["id"]} tubes {len(tl)}/{len(tr)}')
            positions, names = [], []
            for i, (a, b) in enumerate(zip(tl, tr)):
                positions += [a, b]; names += [f'{ch}_left_{i}', f'{ch}_right_{i}']
            w['Magazine'] = len(positions)
            report.append(f'{ch}: {len(positions)} tubes')
        sp = w.setdefault('ShootPos', {})
        sp['Transform'] = 'Vehicle'
        sp['Positions'] = positions
        d = (sp.get('Directions') or [[0, 0, 1]])[0]
        sp['Directions'] = [d] * len(positions)
        if m['kind'] == 'missile':
            sp['BoundUpWithAmmoAmount'] = True
        for key in ('MuzzleAttachments', 'EffectAttachments'):
            sp[key] = list(names)
        for key in ('MuzzleDirectionAttachments', 'EffectDirectionAttachments'):
            sp[key] = ['Default']
        for key in ('HudOriginAttachment', 'ShootPositionForHud'):
            sp.pop(key, None)
        if 'ViewDirectionAttachment' in sp and sp['ViewDirectionAttachment'] != 'Barrel':
            sp.pop('ViewDirectionAttachment')
        for old in list(attachments):
            if old.startswith(ch + '_left_') or old.startswith(ch + '_right_'):
                del attachments[old]
        for n, p in zip(names, positions):
            attachments[n] = {'Parent': 'Vehicle', 'Position': p, 'Direction': [0, 0, 1]}
        if 'name' in m.get('weapon', {}):
            w['Name'] = m['weapon']['name']
        weapons[ch] = w
        channels.append(ch)
    # seats: retired channels out, new channels in at the authored seat
    retire = set(cfg.get('retire', []))
    for i, seat in enumerate(data['Seats']):
        lst = [w for w in seat.get('Weapons', []) if w not in retire and w not in channels]
        for m in cfg['mounts']:
            if m['kind'] != 'store' and m.get('seat', 0) == i and m['channel'] not in lst:
                lst.append(m['channel'])
        seat['Weapons'] = lst
    for name in retire:
        if name not in channels:
            weapons.pop(name, None)
    # armament definition
    arm['SuspendedWeapons'] = channels
    arm['BuiltInWeapons'] = cfg.get('built_in', arm.get('BuiltInWeapons', []))
    arm['Pairs'] = pairs
    arm['Singles'] = arm.get('Singles', []) if cfg.get('keep_singles') else []
    arm['StoreGroups'] = {}
    arm['MaxPayloadKg'] = cfg['payload_kg']
    arm['MaxPylonMassKg'] = cfg.get('max_pylon_kg', 1000)
    arm['MaxWeaponsPerPylon'] = 1
    if 'weapon_seat' in cfg:
        arm['WeaponSeat'] = cfg['weapon_seat']
    else:
        arm.pop('WeaponSeat', None)
    # the cube placeholder model used without the mesh loader names the same bones
    fb_path = rel(tree, r'assets\berts_vehicle_pack\geo\native_fallback', vid + '.geo.json')
    fallback = load(fb_path) if os.path.exists(fb_path) else None
    if fallback:
        fb = fallback['minecraft:geometry'][0]['bones']
        names = {b['name'] for b in fb}
        proto = next((b for b in fb if b['name'] == 'suspended_legacy_hull'), None) or \
            next(b for b in fb if b['name'] == 'hull')
        fb[:] = [b for b in fb if not b['name'].startswith('suspended_')]
        at = next(i for i, b in enumerate(fb) if b['name'] == 'hull') + 1
        for k, b in enumerate(new_bones):
            clone = copy.deepcopy(proto); clone['name'] = b['name']; clone['parent'] = 'hull'
            fb.insert(at + k, clone)
    if preview:
        os.makedirs(preview, exist_ok=True)
        json.dump(geo, open(os.path.join(preview, vid + '.geo.json'), 'w'))
    if write:
        dump(geo_path, geo); dump(data_path, data); dump(arm_path, arm)
        if fallback:
            dump(fb_path, fallback)
    return report, arm


def main(argv):
    tree = argv[0]
    preview = next((a[10:] for a in argv if a.startswith('--preview=')), None)
    write = '--write' in argv
    cfgs = json.load(open(CONFIG))
    ids = [a for a in argv[1:] if not a.startswith('--')] or [k for k in cfgs if not k.startswith('_')]
    for vid in ids:
        report, arm = convert(tree, vid, cfgs[vid], preview, write)
        if write:
            from fix_attachments import fix
            report += fix(tree, vid)
        print(vid); [print('  ' + r) for r in report]


if __name__ == '__main__':
    main(sys.argv[1:])
