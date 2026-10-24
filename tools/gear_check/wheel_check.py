"""
Wheel bones that carry more than the wheel: a spinning wheel bone takes every face under it round with it, so a fork,
mudguard or torque link parented to the wheel spins with the tyre.

The faces under each `gear_wheel_*` bone (and its `wreck_*__gear_wheel_*` mesh children) are split into connected
pieces. A wheel is round about its axle (the bone pivot, axis X): each piece of it either sits balanced on the axle or
has a matching piece on the far side of the axle. A piece with neither is reported, and with --fix moved to the wheel
bone's parent's mesh (the steering or gear leg bone), so only the tyre and hub rotate.

Usage: python wheel_check.py [--fix] [vehicle ids...]   (default: every custom_geo model, expansion ids skipped)
"""
import glob
import json
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GEO = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack', 'custom_geo')
EXPANSION = {'a_10', 'f_16c', 'f_15c', 'f_15e', 'fa_18e', 'f_4c', 'a_10a', 'f_16a', 'f_15a', 'f_15ex', 'fa_18c', 'f_22a',
             'f_4e'}
PIVOT_TOL = 0.25   # model units (1/16 block): a wheel pivot further off its hub than this wobbles visibly
TOL = 0.15        # a matching piece's centre and size may be this far off the mirrored one, as a fraction of the radius


def blocks(pm):
    """the mesh's modelled blocks, when it is a converted cube list (each six faces in turn close one solid: every edge
    shared by two of them), else None"""
    polys, pos = pm['polys'], pm['positions']
    if not polys or len(polys) % 6:
        return None
    key = lambda i: tuple(round(c, 3) for c in pos[i])
    out = []
    for s in range(0, len(polys), 6):
        group = list(range(s, s + 6))
        edges = {}
        for n in group:
            ks = [key(v[0]) for v in polys[n]]
            for a, b in zip(ks, ks[1:] + ks[:1]):
                if a != b:
                    e = (min(a, b), max(a, b))
                    edges[e] = edges.get(e, 0) + 1
        if not edges or any(c != 2 for c in edges.values()):
            return None
        out.append(group)
    return out


def pieces(pm):
    """polys grouped into the blocks they were modelled as, or else into connected pieces (shared positions)"""
    b = blocks(pm)
    if b is not None:
        return b
    pos = pm['positions']
    key = lambda i: tuple(round(c, 3) for c in pos[i])
    parent = list(range(len(pm['polys'])))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    owner = {}
    for n, poly in enumerate(pm['polys']):
        for v in poly:
            k = key(v[0])
            if k in owner:
                parent[find(n)] = find(owner[k])
            else:
                owner[k] = n
    groups = {}
    for n in range(len(pm['polys'])):
        groups.setdefault(find(n), []).append(n)
    return list(groups.values())


def centre(pm, polys):
    vs = {tuple(pm['positions'][v[0]]) for n in polys for v in pm['polys'][n]}
    lo = [min(v[i] for v in vs) for i in range(3)]
    hi = [max(v[i] for v in vs) for i in range(3)]
    return [(a + b) / 2 for a, b in zip(lo, hi)], lo, hi


def strays(bone):
    """the pieces of a wheel mesh bone that are not round about its axle"""
    pm = bone['poly_mesh']
    parts = pieces(pm)
    info = [centre(pm, p) for p in parts]
    # the axle: the centre of the hub, the biggest piece about the bone's pivot (the pivot itself can sit a little off
    # it; a thin post can be centred on it too)
    span = lambda i: max(i[2][1] - i[1][1], i[2][2] - i[1][2])
    dist = lambda i: math.hypot(i[0][1] - bone['pivot'][1], i[0][2] - bone['pivot'][2])
    near = min(dist(i) for i in info)
    hub = max((i for i in info if dist(i) <= near + 0.75), key=span)
    py, pz = hub[0][1], hub[0][2]
    radius = max(math.hypot(y - py, z - pz) for c, lo, hi in info for y in (lo[1], hi[1]) for z in (lo[2], hi[2]))
    tol = TOL * radius
    out = []
    hub_lo, hub_hi = hub[1][0], hub[2][0]
    for i, (c, lo, hi) in enumerate(info):
        # beside the tyre along the axle (a strut between twin wheels, an axle stub, a fork leg): not the wheel
        beside = min(hi[0], hub_hi) - max(lo[0], hub_lo) <= 0.1 * (hub_hi - hub_lo)
        off = math.hypot(c[1] - py, c[2] - pz)
        if beside and off <= tol and all(abs((hi[k] - lo[k]) - (hub[2][k] - hub[1][k])) <= tol for k in (1, 2)):
            beside = False                # a twin tyre on the same axle, the hub's size
        if not beside and off <= tol:
            continue                      # balanced on the axle
        my, mz = 2 * py - c[1], 2 * pz - c[2]
        size = [h - l for l, h in zip(lo, hi)]

        def matches(o, olo, ohi):
            osize = [h - l for l, h in zip(olo, ohi)]
            # the same block turned a half (y and z sizes the same) or a quarter turn (swapped) about the axle
            same = abs(osize[0] - size[0]) <= tol and (
                abs(osize[1] - size[1]) <= tol and abs(osize[2] - size[2]) <= tol or
                abs(osize[1] - size[2]) <= tol and abs(osize[2] - size[1]) <= tol)
            return same and math.hypot(o[1] - my, o[2] - mz) <= tol and abs(o[0] - c[0]) <= tol
        if not beside and any(j != i and matches(*info[j]) for j in range(len(info))):
            continue                      # matched across the axle
        out.append((parts[i], c, lo, hi))
    return out, radius, (py, pz)


def move(src, dst, polys):
    """move polys from src's mesh to dst's (both in model space), carrying their positions, normals and uvs"""
    a, b = src['poly_mesh'], dst.setdefault('poly_mesh', {'normalized_uvs': src['poly_mesh'].get('normalized_uvs', False),
                                                           'positions': [], 'normals': [], 'uvs': [], 'polys': []})
    for k in ('positions', 'normals', 'uvs', 'polys'):
        b.setdefault(k, [])
    for n in polys:
        new = []
        for p, nr, uv in a['polys'][n]:
            b['positions'].append(a['positions'][p]); b['normals'].append(a['normals'][nr]); b['uvs'].append(a['uvs'][uv])
            new.append([len(b['positions']) - 1, len(b['normals']) - 1, len(b['uvs']) - 1])
        b['polys'].append(new)
    keep = set(range(len(a['polys']))) - set(polys)
    a['polys'] = [a['polys'][n] for n in sorted(keep)]


def check(vid, fix):
    path = os.path.join(GEO, vid + '.geo.json')
    doc = json.load(open(path, encoding='utf-8'))
    bones = doc['minecraft:geometry'][0]['bones']
    by = {b['name']: b for b in bones}
    found = []
    for b in bones:
        if not b.get('poly_mesh') or not b.get('parent', '').startswith('gear_wheel_'):
            continue
        wheel = by[b['parent']]
        bad, radius, (ay, az) = strays(b)
        off = math.hypot(wheel['pivot'][1] - ay, wheel['pivot'][2] - az)
        if not bad and off <= PIVOT_TOL:
            continue
        leg = by.get(wheel.get('parent'))
        found.append((b['name'], wheel.get('parent'), len(b['poly_mesh']['polys']), bad, radius, off))
        if fix and off > PIVOT_TOL:
            # spin the wheel about its own hub, not a point beside it (it wobbled)
            for bone in (wheel, b):
                bone['pivot'] = [bone['pivot'][0], round(ay, 5), round(az, 5)]
        if fix and bad and leg is not None:
            # the leg's mesh child (wreck_<part>__<leg>), so the faces keep their wreck part
            prefix = b['name'][:-len(b['parent'])]
            target = by.get(prefix + leg['name'])
            if target is None:
                target = {'name': prefix + leg['name'], 'parent': leg['name'], 'pivot': list(leg['pivot'])}
                bones.insert(bones.index(b), target)
                by[target['name']] = target
            move(b, target, [n for p, *_ in bad for n in p])
    if fix and found:
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(doc, f, separators=(',', ':'))
        # the rig turns each wheel about its resource pivot: keep it on the bone's
        vpath = os.path.join(GEO, '..', 'sbw', 'vehicles', vid + '.json')
        if os.path.exists(vpath):
            # edited in place (the file mixes layouts: a compact CanopyGlass line among indented blocks)
            with open(vpath, encoding='utf-8', newline='') as f:
                text = f.read()
            start = text.find('"Rotors"')
            changed = False
            for rotor in (json.loads(text).get('AircraftRig') or {}).get('Rotors', []):
                bone = by.get(rotor.get('Bone'))
                if start < 0 or bone is None or rotor.get('Pivot') == bone['pivot']:
                    continue
                m = re.compile(r'"Bone"\s*:\s*"%s"' % re.escape(rotor['Bone'])).search(text, start)
                p = re.compile(r'"Pivot"\s*:\s*\[([^\]]*)\]').search(text, m.end())
                values = iter(bone['pivot'])
                body = re.sub(r'-?\d+(\.\d+)?([eE][-+]?\d+)?', lambda _: repr(next(values)), p.group(1))
                text = text[:p.start(1)] + body + text[p.end(1):]
                changed = True
            if changed:
                with open(vpath, 'w', encoding='utf-8', newline='') as f:
                    f.write(text)
    return found


def main(argv):
    fix = '--fix' in argv
    ids = [a for a in argv if not a.startswith('--')] or sorted(
        os.path.basename(p)[:-9] for p in glob.glob(os.path.join(GEO, '*.geo.json')) if not p.endswith('_wreck.geo.json'))
    total = 0
    for vid in ids:
        if vid in EXPANSION:
            continue
        for mesh, leg, n, bad, radius, off in check(vid, fix):
            total += 1
            moved = sum(len(p) for p, *_ in bad)
            print(f'{vid}: {mesh} (wheel radius {radius:.2f}, leg {leg}): {moved} of {n} faces not part of the wheel'
                  + (' - moved to the leg' if fix and moved else '')
                  + (f'; pivot {off:.2f} off the hub' + (' - recentred' if fix else '') if off > PIVOT_TOL else ''))
            for p, c, lo, hi in bad:
                print(f'    {len(p)} faces, centre {[round(x, 2) for x in c]}, '
                      f'box {[round(x, 2) for x in lo]}..{[round(x, 2) for x in hi]}')
    print('wheel meshes with stray faces:', total)


if __name__ == '__main__':
    main(sys.argv[1:])
