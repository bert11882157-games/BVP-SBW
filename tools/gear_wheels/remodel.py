#!/usr/bin/env python3
"""Replaces the blocky landing gear wheels (stacked boxes, octagons in the side view) with lathed low-poly wheels.

Works on the wheels that rig.py moved into their own rolling bones (AircraftRig rotors on the "groundRoll"
channel), and on wheels still inside a gear leg's mesh (never rigged; rebuilt in place, they stay static). Each
new wheel keeps the old wheel's axle (the rotor pivot, so the spin stays centred), its outer
radius (so it still touches the ground at the same height) and its width, and is coloured from the aircraft's
own texture: the tyre takes the old tyre's darkest texel, the hub the main colour painted on the old hub. No
texture is edited.

Shape, as a section through the axle from the outer face inwards:
    tread band at the full radius, a chamfered shoulder down to a flat sidewall, a rim wall stepping into the
    recessed hub disc, and a raised hub boss (half the sides) with a dark axle cap.
The number of sides scales with the radius (see TIERS), so a nose wheel costs ~100 polygons and a big main wheel
~140. A gear bone whose wheel already has 16 or more sides (a hand-made round wheel) is left alone.

Model frame: pixels, axle along x; positions are written as rig.py found them (y up, z towards the tail).
Faces follow the convention of the converted models: the stored normal and the (v1 - v0) x (v2 - v0) winding both
point into the wheel in file coordinates (the loader turns the model about y).

Reruns change nothing: a wheel this tool built is recognised by its ring radii and skipped. After another tool
rewrites a model, rerun this one on it instead of merging the single-line geo JSON by hand.

Usage: python3 tools/gear_wheels/remodel.py [--write] [id...]   (no ids: every aircraft with an AircraftRig)
"""
import json, math, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import find as F  # noqa: E402

TEX = os.path.join(F.GEN, 'assets/berts_vehicle_pack/textures/entity')
PREFIX = 'gear_wheel_'
ROUND_ALREADY = 16          # sides; wheels at least this round are kept
# (largest radius in px, sides); the hub boss has half as many
TIERS = ((4.5, 10), (9.0, 12), (math.inf, 16))


def tier(radius, crowded):
    i = next(k for k, t in enumerate(TIERS) if radius < t[0])
    if crowded and i:           # bombers with a dozen wheels drop a tier
        i -= 1
    return TIERS[i][1]


# ------------------------------------------------------------------------------------------------ old wheel


def wheel_bones(bones, name):
    """bones that hold the rolling wheel's polygons: the rotor bone and its wreck_<piece>__ children"""
    return [b for b in bones if (b['name'] == name or b['name'].endswith('__' + name))
            and (b.get('poly_mesh') or {}).get('polys')]


def texel_of(uv, pm, tw, th):
    u, v = uv
    if not pm.get('normalized_uvs'):
        u, v = u / tw, v / th
    return int(u * tw), int((1 - v) * th)


def texel_uv(tx, ty, tw, th):
    return [round((tx + 0.5) / tw, 6), round(1 - (ty + 0.5) / th, 6)]


def face_texels(pm, poly, tw, th):
    """texels covered by a face's texture rectangle"""
    pts = [texel_of(pm['uvs'][v[2]], pm, tw, th) for v in poly]
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    if x1 == x0: x1 += 1
    if y1 == y0: y1 += 1
    return [(x, y) for x in range(x0, min(x1, x0 + 64)) for y in range(y0, min(y1, y0 + 64))]


def colours(polys, img, neutral=False):
    """(tyre texel, hub texel) from the old wheel faces: the darkest common colour and the commonest colour
    on the side faces clearly lighter or differently tinted than the tyre (with neutral, only greys)"""
    tw, th = img.size
    px = img.load()
    count, side = {}, {}
    for pm, poly, n in polys:
        for t in face_texels(pm, poly, tw, th):
            c = px[t[0] % tw, t[1] % th]
            if c[3] < 128:
                continue
            count.setdefault(c[:3], []).append(t)
            if abs(n[0]) > 0.9:
                side.setdefault(c[:3], []).append(t)
    if not count:
        return None, None
    lum = lambda c: 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]
    common = [c for c in count if len(count[c]) >= 0.05 * sum(map(len, count.values()))] or list(count)
    tyre = min(common, key=lum)
    hub = [c for c in side if sum(abs(a - b) for a, b in zip(c, tyre)) > 60
           and (not neutral or max(c) - min(c) < 24)]
    hub = max(hub, key=lambda c: len(side[c])) if hub else None
    pick = lambda c: count[c][len(count[c]) // 2]
    return pick(tyre), (pick(hub) if hub else None)


def dark(pm, poly, img, tyre):
    """whether a face is painted in the tyre colour (the middle texel of its texture rectangle)"""
    tw, th = img.size
    t = face_texels(pm, poly, tw, th)
    c = img.getpixel((t[len(t) // 2][0] % tw, t[len(t) // 2][1] % th))
    return sum(abs(a - b) for a, b in zip(c[:3], tyre)) <= 60


def tyres(polys, centre, radius, is_tyre):
    """(x ranges of the tyres side by side on the axle, polygons that are not wheel: axles, forks, mudguards)

    A tyre is a run of tyre-coloured boxes reaching the full radius (a painted hub drum between twin wheels
    is not one); touching runs with different x spans are separate tyres
    (twin wheels built against each other), and caps (zero width) belong to the run they close."""
    def span(pm, poly):
        xs = [pm['positions'][v[0]][0] for v in poly]
        return min(xs), max(xs)

    def reach(pm, poly):
        return max(math.hypot(pm['positions'][v[0]][1] - centre[1], pm['positions'][v[0]][2] - centre[2])
                   for v in poly)

    runs = sorted({tuple(round(x, 2) for x in span(pm, poly)) for pm, poly, _ in polys
                   if span(pm, poly)[1] - span(pm, poly)[0] > 0.2 and reach(pm, poly) > 0.8 * radius
                   and is_tyre(pm, poly)})
    merged = []
    for a, b in runs:
        if merged and a < merged[-1][1] - 0.05:
            merged[-1][1] = max(merged[-1][1], b)
        else:
            merged.append([a, b])
    # whole connected pieces go one way or the other: a piece mostly in a tyre's width and within its rim is old
    # wheel (box-built octagons reach up to the corners of their square, 1.41 radii); anything else (axle stub,
    # fork, mudguard) is not
    key = lambda pm, v: tuple(round(c, 3) for c in pm['positions'][v[0]])
    parent = list(range(len(polys)))

    def root(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]; i = parent[i]
        return i
    owner = {}
    for i, (pm, poly, _) in enumerate(polys):
        for v in poly:
            k = key(pm, v)
            if k in owner:
                parent[root(i)] = root(owner[k])
            else:
                owner[k] = i
    pieces = {}
    for i in range(len(polys)):
        pieces.setdefault(root(i), []).append(i)
    rest = []
    for members in pieces.values():
        lo = min(span(*polys[i][:2])[0] for i in members); hi = max(span(*polys[i][:2])[1] for i in members)
        within = sum(max(0.0, min(hi, b) - max(lo, a)) for a, b in merged)
        flat_in = hi - lo < 1e-3 and any(a - 0.06 <= lo <= b + 0.06 for a, b in merged)
        in_width = flat_in or hi - lo >= 1e-3 and within >= 0.5 * (hi - lo)
        if not (in_width and max(reach(*polys[i][:2]) for i in members) <= 1.45 * radius):
            rest += [polys[i][:2] for i in sorted(members)]
    return merged, rest


def sides_of(points, centre):
    """distinct rim directions of the old wheel in the side view (8 for the octagon wheels)"""
    d = points[:, 1:] - np.array(centre[1:])
    r = np.hypot(d[:, 0], d[:, 1])
    far = r > 0.8 * r.max()
    return len(set(np.round(np.degrees(np.arctan2(d[far, 0], d[far, 1]))).astype(int) % 360))


# ------------------------------------------------------------------------------------------------ new wheel

SHOULDER, RIM, BOSS = 0.86, 0.60, 0.24       # ring radii as fractions of the tyre's vertex radius


def remodelled(points, centre):
    """whether this tool already built the wheel: its shoulder, rim and boss rings, so reruns change nothing"""
    r = np.hypot(points[:, 1] - centre[1], points[:, 2] - centre[2])
    # kept axle stubs can reach past the tyre, so any vertex radius may be the tyre's
    return any(all(np.any(np.abs(r - f * big) < 2e-3 * big) for f in (SHOULDER, RIM, BOSS))
               for big in np.unique(r.round(4)) if big > 0)



class Lathe:
    """faces around the x axis through (cy, cz); profile rings are (x, r, sides, phase)"""

    def __init__(self, cy, cz):
        self.cy, self.cz = cy, cz
        self.pos, self.faces = [], []      # faces: (vertex indices outward CCW, colour key)

    def ring(self, x, r, n, phase):
        start = len(self.pos)
        for k in range(n):
            a = phase + 2 * math.pi * k / n
            self.pos.append([x, self.cy + r * math.sin(a), self.cz + r * math.cos(a)])
        return list(range(start, start + n))

    def band(self, a, b, key, recess=False):
        """quads between two rings of equal side count; a recess wall is seen from the axle side"""
        n = len(a)
        for k in range(n):
            self.faces.append(([a[k], b[k], b[(k + 1) % n], a[(k + 1) % n]], key, recess))

    def cap(self, ring, key):
        """N-gon cap split into quads (and a closing triangle for an odd count)"""
        n = len(ring)
        for k in range(1, n - 1, 2):
            quad = [ring[0], ring[k], ring[k + 1], ring[k + 2]] if k + 2 < n else [ring[0], ring[k], ring[k + 1]]
            self.faces.append((quad, key, False))

    def orient(self, xc):
        """wind every face to point out of the solid: away from the axle on bands, away from the tyre's middle
        plane on caps, towards the axle on recess walls"""
        P = np.array(self.pos)
        out = []
        for idx, key, recess in self.faces:
            q = P[idx]
            n = np.cross(q[1] - q[0], q[2] - q[0])
            c = q.mean(0)
            radial = np.array([0.0, c[1] - self.cy, c[2] - self.cz])
            ref = -radial if recess else radial if abs(n[0]) < 0.7 * np.linalg.norm(n) else np.array([c[0] - xc, 0, 0])
            out.append((idx[::-1] if n @ ref < 0 else idx, key))
        self.faces = out


def build(x0, x1, cy, cz, radius, sides):
    """one tyre from x0 to x1 (px) with its outer radius (apothem) on the axle (cy, cz)"""
    m = Lathe(cy, cz)
    xc, h = (x0 + x1) / 2, (x1 - x0) / 2
    R = radius / math.cos(math.pi / sides)      # vertex radius whose flats sit at the old radius
    ph = math.pi / sides                         # a flat at the bottom: rests on the ground like the old wheel
    tread, shoulder, rim, hub = 0.62, SHOULDER, RIM, 0.55
    rings = {}
    for s in (1, -1):
        rings[s] = dict(
            tread=m.ring(xc + s * tread * h, R, sides, ph),
            shoulder=m.ring(xc + s * h, shoulder * R, sides, ph),
            rim_out=m.ring(xc + s * h, rim * R, sides, ph),
            rim_in=m.ring(xc + s * hub * h, rim * R, sides, ph))
    m.band(rings[1]['tread'], rings[-1]['tread'], 'tyre')
    for s in (1, -1):
        r = rings[s]
        m.band(r['shoulder'], r['tread'], 'tyre')              # shoulder chamfer
        m.band(r['rim_out'], r['shoulder'], 'tyre')            # flat sidewall
        m.band(r['rim_in'], r['rim_out'], 'hub', recess=True)  # rim wall down into the hub recess
        # hub disc: a ring from the rim to the boss, whose corners line up with every other rim corner, so the
        # boss is joined to the wheel (one connected piece) and nothing is hidden under it
        base = m.ring(xc + s * hub * h, BOSS * R, sides // 2, ph)
        top = m.ring(xc + s * 0.85 * h, BOSS * R, sides // 2, ph)
        o = r['rim_in']
        for k in range(sides // 2):
            m.faces.append(([base[k], o[2 * k], o[2 * k + 1]], 'hub', False))
            quad = [base[k], o[2 * k + 1], o[(2 * k + 2) % sides], base[(k + 1) % (sides // 2)]]
            m.faces.append((quad, 'hub', False))
        m.band(top, base, 'hub')                               # boss
        m.cap(top, 'tyre')                                     # dark axle cap
    m.orient(xc)
    return m


def keep(pm, rest):
    """copies old polygons (axles, forks, hub stubs) into a mesh unchanged"""
    for src, poly in rest:
        out = []
        for vert in poly:
            out.append([len(pm['positions']), len(pm['normals']), len(pm['uvs'])])
            pm['positions'].append(src['positions'][vert[0]])
            pm['normals'].append(src['normals'][vert[1]])
            uv = src['uvs'][vert[2]]
            pm['uvs'].append(uv)
        pm['polys'].append(out)


def to_poly_mesh(parts, uv_of, normalized):
    pm = {'normalized_uvs': normalized, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
    uv_index = {}
    for m in parts:
        base = len(pm['positions'])
        pm['positions'] += [[round(float(v), 5) for v in p] for p in m.pos]
        P = np.array(m.pos)
        for idx, key in m.faces:
            # file convention: winding and normal point into the wheel (see module doc)
            idx = idx[::-1]
            q = P[idx]
            n = np.zeros(3)
            for k in range(1, len(idx) - 1):
                n += np.cross(q[k] - q[0], q[k + 1] - q[0])
            n /= np.linalg.norm(n)
            if key not in uv_index:
                uv_index[key] = len(pm['uvs']); pm['uvs'].append(uv_of[key])
            ni = len(pm['normals'])
            for _ in idx:
                pm['normals'].append([round(float(v), 5) for v in n])
            pm['polys'].append([[base + i, ni + j, uv_index[key]] for j, i in enumerate(idx)])
    return pm


# ------------------------------------------------------------------------------------------------ aircraft


def rebuild(polys, centre, img, leg, crowded, name, notes, dark_only=False):
    """(new wheel poly_mesh, old polygons that are not wheel) for one old wheel (polys: (poly_mesh, poly, normal))
    on the axle centre, or None"""
    tw, th = img.size
    P = np.array([pm['positions'][v[0]] for pm, poly, _ in polys for v in poly], float)
    lo, hi = P.min(0), P.max(0)
    mid = (lo + hi) / 2
    if abs(mid[1] - centre[1]) > 0.35 or abs(mid[2] - centre[2]) > 0.35:
        notes.append(f'{name}: off its axle by {np.abs(mid - centre)[1:].round(2).tolist()}, kept'); return None
    if remodelled(P, centre):
        return None
    if sides_of(P, centre) >= ROUND_ALREADY:
        notes.append(f'{name}: already round, kept'); return None
    if not all(pm.get('normalized_uvs') for pm, _, _ in polys):
        notes.append(f'{name}: texel-unit uvs, kept'); return None
    tyre_t, hub_t = colours(polys, img)
    if tyre_t is None:
        notes.append(f'{name}: no texels, kept'); return None
    if dark_only and sum(img.getpixel(tuple(tyre_t))[:3]) > 3 * 90:
        return None                # a round light piece (brake drum, wheel cover) is not a tyre
    if hub_t is None:          # a plain black wheel: the hub takes the gear leg's paint
        hub_t = colours(polys + leg, img, neutral=True)[1] if leg else None
        notes.append(f'{name}: hub in the gear leg colour' if hub_t else f'{name}: hub in tyre colour')
    uv_of = {'tyre': texel_uv(*tyre_t, tw, th), 'hub': texel_uv(*(hub_t or tyre_t), tw, th)}
    radius = float(max(hi[1] - centre[1], centre[1] - lo[1], hi[2] - centre[2], centre[2] - lo[2]))
    sides = tier(radius, crowded)
    tyre_rgb = img.getpixel(tuple(tyre_t))[:3]
    runs, rest = tyres(polys, centre, radius, lambda pm, poly: dark(pm, poly, img, tyre_rgb))
    if not runs:
        notes.append(f'{name}: no tyre found, kept'); return None
    parts = [build(x0, x1, centre[1], centre[2], radius, sides) for x0, x1 in runs]
    new = to_poly_mesh(parts, uv_of, True)
    return new, rest


def subtree(bones, roots):
    kids = {}
    for b in bones:
        kids.setdefault(b.get('parent'), []).append(b['name'])
    out, stack = set(), list(roots)
    while stack:
        n = stack.pop(); out.add(n); stack += kids.get(n, [])
    return out


def static_wheels(pm):
    """wheels left inside a gear leg's mesh (never rigged to roll): (polygon indices, axle centre). Like
    find.py's test, but a wheel stays a wheel when a bogie beam or axle runs through it; coaxial pieces inside
    the wheel (hub stubs) go with it."""
    comps = []
    for idx in F.components(pm):
        P = F.points(pm, idx)
        comps.append((idx, P.min(0), P.max(0)))
    out = []
    for idx, lo, hi in comps:
        sx, sy, sz = hi - lo
        c = (lo + hi) / 2
        if len(idx) < 12 or min(sy, sz) < 5 or abs(sy - sz) > 0.1 * max(sy, sz) or sx > 0.9 * max(sy, sz):
            continue
        P = F.points(pm, idx)
        rr = np.hypot(P[:, 1] - c[1], P[:, 2] - c[2])
        if np.percentile(rr, 90) < 0.3 * sy / 2 or rr.max() > 1.12 * sy / 2:
            continue
        inner = [i for jdx, l2, h2 in comps if jdx is not idx and np.all(l2 >= lo - 0.05) and np.all(h2 <= hi + 0.05)
                 for i in jdx]
        out.append((sorted(idx + inner), c))
    return out


def remodel(vid):
    vpath = os.path.join(F.VEH, vid + '.json')
    rig = json.load(open(vpath, encoding='utf-8')).get('AircraftRig') or {}
    rotors = [r for r in rig.get('Rotors') or [] if r.get('SpeedChannel') == 'groundRoll']
    gears = [g['Bone'] for g in rig.get('Gear') or [] if g.get('VisibleWhen') != 'RETRACTED']
    if not rotors and not gears:
        return None, 'no landing gear'
    gpath = os.path.join(F.GEO, vid + '.geo.json')
    geo = json.load(open(gpath))
    bones = geo['minecraft:geometry'][0]['bones']
    img = Image.open(os.path.join(TEX, vid + '.png')).convert('RGBA')
    notes, before, after, done, fixed = [], 0, 0, 0, 0
    rolling = set()
    for rotor in rotors:
        name = rotor['Bone']
        holders = wheel_bones(bones, name)
        rolling.update(b['name'] for b in holders)
        if not holders:
            notes.append(f'{name}: empty'); continue
        polys = [(b['poly_mesh'], p, b['poly_mesh']['normals'][p[0][1]]) for b in holders
                 for p in b['poly_mesh']['polys']]
        leg = [(b['poly_mesh'], p, [1, 0, 0]) for b in wheel_bones(bones, rotor['Parent'])
               for p in b['poly_mesh']['polys']]
        built = rebuild(polys, rotor['Pivot'], img, leg, len(rotors) >= 8, name, notes)
        if built is None:
            continue
        new, rest = built
        target = max(holders, key=lambda b: len(b['poly_mesh']['polys']))
        if rest:
            # what is not wheel would spin with it: it moves to the leg's mesh in the same wreck piece
            # (tools/gear_check/wheel_check.py on the cockpit line does the same)
            piece = target['name'][:-len(name)]
            leg_name = piece + rotor['Parent'] if piece else rotor['Parent']
            leg_bone = next((b for b in bones if b['name'] == leg_name), None)
            if leg_bone is None:
                pivot = next(b['pivot'] for b in bones if b['name'] == rotor['Parent'])
                leg_bone = {'name': leg_name, 'parent': rotor['Parent'], 'pivot': list(pivot)}
                bones.insert(bones.index(target), leg_bone)
            if not leg_bone.get('poly_mesh'):
                leg_bone['poly_mesh'] = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
            keep(leg_bone['poly_mesh'], rest)
        for b in holders:
            if b is not target:
                b['poly_mesh'] = {k: ([] if isinstance(v, list) else v) for k, v in b['poly_mesh'].items()}
        target['poly_mesh'] = new
        before += len(polys); after += len(new['polys']); done += 1
    # wheels that never got their own rolling bone stay where they are, rebuilt inside the leg's mesh
    for b in bones:
        pm = b.get('poly_mesh') or {}
        if b['name'] not in subtree(bones, gears) or b['name'] in rolling or not pm.get('polys'):
            continue
        found = static_wheels(pm)
        drop = set()
        for idx, centre in found:
            polys = [(pm, pm['polys'][i], pm['normals'][pm['polys'][i][0][1]]) for i in idx]
            leg = [(pm, p, [1, 0, 0]) for k, p in enumerate(pm['polys']) if k not in idx]
            built = rebuild(polys, centre.tolist(), img, leg, len(found) >= 8, b['name'] + ' (static)', notes,
                            dark_only=True)
            if built is None:
                continue
            new, rest = built
            keep(new, rest)
            drop.update(idx)
            base = [len(pm['positions']), len(pm['normals']), len(pm['uvs'])]
            for key in ('positions', 'normals', 'uvs'):
                pm[key] += new[key]
            pm['polys'] += [[[v[0] + base[0], v[1] + base[1], v[2] + base[2]] for v in poly] for poly in new['polys']]
            before += len(idx); after += len(new['polys']); fixed += 1
        if drop:
            pm['polys'] = [p for k, p in enumerate(pm['polys']) if k not in drop]
    summary = (f'{done}/{len(rotors)} rolling + {fixed} static wheels, {before} -> {after} polys'
               + (' | ' + '; '.join(notes) if notes else ''))
    return ((gpath, geo) if done or fixed else None), summary


def main(argv):
    write = '--write' in argv
    ids = [a for a in argv if not a.startswith('--')]
    if not ids:
        ids = sorted(f[:-5] for f in os.listdir(F.VEH)
                     if '"AircraftRig"' in open(os.path.join(F.VEH, f), encoding='utf-8').read())
    for vid in ids:
        out, summary = remodel(vid)
        print(f'{vid:34s} {summary}', flush=True)
        if write and out:
            path, geo = out
            raw = open(path).read()
            with open(path, 'w') as fh:
                json.dump(geo, fh, separators=(',', ':'))
                if raw.endswith('\n'):
                    fh.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
