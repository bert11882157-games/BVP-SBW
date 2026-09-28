#!/usr/bin/env python3
"""Split a vehicle's externally carried round out of the bone it is baked into, so it can vanish once fired.

  python3 tools/vehgen/split_ammo_bones.py [--check]

A bone named `ammo_<Weapon>_<n>` is drawn only while <Weapon> holds at least n loaded rounds (BVP
`BvpAmmoBoneVisibility`): firing the last round hides it, and it reappears when the reload completes.

Each spec names the source bone and a selection over the source bone's connected mesh pieces (pieces sharing a
vertex position), and moves the selected polygons into the new bone (same pivot, parented to the source bone, so it
follows the launcher). The original geo is kept once in tools/vehgen/replaced/<id>/. Idempotent.
"""
import collections, json, os, shutil, sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
GEO = os.path.join(REPO, 'bvp/src/generated/resources/assets/berts_vehicle_pack/custom_geo')
REPLACED = os.path.join(REPO, 'tools/vehgen/replaced')


def bmp1_malyutka(mn, mx):
    # The 9M14 lies on the launch rail above the 2A28 (rail top at y 41.16 px): body, nose and upper fins sit on or
    # above y 40.9; the two lower fins hang beside the rail (|x| >= 0.6) in the fin station z -25.6..-21.9.
    if mn[1] >= 40.9:
        return True
    return min(abs(mn[0]), abs(mx[0])) >= 0.6 and mn[2] >= -25.6 and mx[2] <= -21.9 and mn[1] >= 40.0


SPECS = {
    'bmp_1am': [dict(source='barell', bone='ammo_Missile_1', select=bmp1_malyutka, expect_polys=(300, 460),
                     # the same polygons are the 9M14's flight model (it had none and flew as the Mi-24's Ataka)
                     flight=dict(name='malyutka', texture='berts_vehicle_pack:textures/entity/bmp_1am.png',
                                 rounds=('berts_vehicle_pack:9m14',), spin=36.0))],
}
ATGM_GEO = os.path.join(GEO, 'atgm')
PROFILES = os.path.join(REPO, 'bvp/src/generated/resources/data/berts_vehicle_pack/sbw/projectile_profiles')
MESH_EXT = 'berts_vehicle_pack:projectile_mesh_v1'


def pieces(pm):
    P, polys = pm['positions'], pm['polys']
    parent = list(range(len(polys)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    owner = {}
    for pi, poly in enumerate(polys):
        for v in poly:
            k = tuple(round(c, 3) for c in P[v[0]])
            if k in owner:
                parent[find(pi)] = find(owner[k])
            else:
                owner[k] = pi
    groups = collections.defaultdict(list)
    for pi in range(len(polys)):
        groups[find(pi)].append(pi)
    for ps in groups.values():
        pts = [P[v[0]] for pi in ps for v in polys[pi]]
        yield ps, [min(p[i] for p in pts) for i in range(3)], [max(p[i] for p in pts) for i in range(3)]


def subset(pm, keep):
    """A poly_mesh holding only the polygons in `keep`, with positions/normals/uvs re-indexed."""
    out = {'normalized_uvs': pm.get('normalized_uvs', False), 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
    maps = [{}, {}, {}]
    for pi in keep:
        poly = []
        for v in pm['polys'][pi]:
            idx = []
            for slot, (arr, name) in enumerate(((pm['positions'], 'positions'), (pm['normals'], 'normals'),
                                                (pm['uvs'], 'uvs'))):
                src = v[slot]
                if src not in maps[slot]:
                    maps[slot][src] = len(out[name])
                    out[name].append(arr[src])
                idx.append(maps[slot][src])
            poly.append(idx)
        out['polys'].append(poly)
    return out


def export_flight(vid, spec, bone, description, check):
    """The split round as a projectile_mesh_v1 model: nose -Z, body centre at the origin, same texture."""
    f = spec['flight']
    pm = json.loads(json.dumps(bone['poly_mesh']))
    P = pm['positions']
    c = [(min(p[i] for p in P) + max(p[i] for p in P)) / 2 for i in range(3)]
    c[0] = 0.0  # the rail lies on the vehicle centreline; keep the body axis exactly on it
    pm['positions'] = [[round(p[0] - c[0], 5), round(p[1] - c[1], 5), round(p[2] - c[2], 5)] for p in P]
    length = (max(p[2] for p in P) - min(p[2] for p in P)) / 16
    model = {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': f'geometry.atgm.{f["name"]}', 'texture_width': description['texture_width'],
                        'texture_height': description['texture_height'], 'visible_bounds_width': 3,
                        'visible_bounds_height': 3, 'visible_bounds_offset': [0, 0, 0]},
        'bones': [{'name': 'missile', 'pivot': [0, 0, 0], 'poly_mesh': pm}]}]}
    out = os.path.join(ATGM_GEO, f['name'] + '.geo.json')
    text = json.dumps(model, separators=(',', ':')) + '\n'
    if not os.path.exists(out) or open(out).read() != text:
        print(f'{vid}: flight model {os.path.relpath(out, REPO)} ({length:.2f} m)')
        if not check:
            open(out, 'w').write(text)
    ext = {'Model': f'berts_vehicle_pack:custom_geo/atgm/{f["name"]}.geo.json', 'Texture': f['texture'],
           'ForwardYaw': 0, 'SpinDegreesPerTick': f['spin']}
    for root, _, files in os.walk(PROFILES):
        for name in files:
            path = os.path.join(root, name)
            t = open(path).read()
            d = json.loads(t)
            if (d.get('RoundId') or d.get('Combat', {}).get('RoundId')) not in f['rounds'] or d.get('Extensions', {}).get(MESH_EXT) == ext:
                continue
            d.setdefault('Extensions', {})[MESH_EXT] = ext
            print(f'{vid}: {os.path.relpath(path, PROFILES)} flies as {f["name"]}')
            if not check:
                open(path, 'w').write(json.dumps(d, indent=2) + ('\n' if t.endswith('\n') else ''))


def apply(vid, specs, check):
    path = os.path.join(GEO, vid + '.geo.json')
    text = open(path).read()
    g = json.loads(text)
    bones = g['minecraft:geometry'][0]['bones']
    names = {b['name'] for b in bones}
    changed = False
    for spec in specs:
        if spec['bone'] in names:
            if spec.get('flight'):
                export_flight(vid, spec, next(b for b in bones if b['name'] == spec['bone']),
                              g['minecraft:geometry'][0]['description'], check)
            continue
        src = next(b for b in bones if b['name'] == spec['source'])
        pm = src['poly_mesh']
        take = sorted(pi for ps, mn, mx in pieces(pm) if spec['select'](mn, mx) for pi in ps)
        lo, hi = spec['expect_polys']
        if not lo <= len(take) <= hi:
            raise SystemExit(f'{vid}: selection has {len(take)} polygons, expected {lo}..{hi}')
        rest = [pi for pi in range(len(pm['polys'])) if pi not in set(take)]
        src['poly_mesh'] = subset(pm, rest)
        new = {'name': spec['bone'], 'parent': spec['source'], 'pivot': list(src['pivot']), 'poly_mesh': subset(pm, take)}
        bones.insert(bones.index(src) + 1, new)
        print(f'{vid}: moved {len(take)} of {len(pm["polys"])} polygons from {spec["source"]} to {spec["bone"]}')
        changed = True
    if changed and not check:
        keep = os.path.join(REPLACED, vid, os.path.basename(path))
        if not os.path.exists(keep):
            os.makedirs(os.path.dirname(keep), exist_ok=True)
            shutil.copyfile(path, keep)
        indent = 2 if text.lstrip().startswith('{\n') else None
        open(path, 'w').write(json.dumps(g, indent=indent, separators=None if indent else (',', ':')) +
                              ('\n' if text.endswith('\n') else ''))


if __name__ == '__main__':
    for vid, specs in SPECS.items():
        apply(vid, specs, '--check' in sys.argv)
