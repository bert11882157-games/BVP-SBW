#!/usr/bin/env python3
"""Checks that the afterburner flame, the exhaust heat haze and the TaP flame emitters of a jet sit on the model's own
nozzle exits, and that the flame is as wide as the nozzle.

Usage: nozzle_check.py VID [VID...] [--tol 0.15] [--radius-tol 0.25] [--json OUT] [--sheet OUT.jpg]
       nozzle_check.py --fix VID [VID...]   moves the outlets onto the measured exits: writes the spec's clientSet
                                            (tools/vehgen/specs/VID.json, so a regeneration keeps it) and the
                                            generated client json (AfterburnerPresentation.Outlets only)
       --sheet OUT.jpg draws a rear three-quarter picture per jet (nozzle_sheet.py)

Frames and units. The generated custom_geo (geo px: +X vehicle left, +Y up, nose -Z, 16 px per block) maps to the
data / client json frame as [x/16, y/16, -z/16] (vehicle-local blocks, +X left, +Y up, nose +Z). The client asset json
(AfterburnerPresentation.Outlets[].Position, TapEmitters[].Offset) is in that same frame, VEHICLE_LOCAL_BLOCKS, and the
renderer (BvpAircraftAfterburnerRenderer.observe) feeds Position through vehicle.getVehicleTransform, the same
transform that places attachments and OBBs, with no scale or shift. The expansion jets are modelled at 16 px per
metre (spec "note_scale"), so one block is one metre; the tool also prints the model length to show that.

Emitters read per aircraft (client asset json):
  Outlet      AfterburnerPresentation.Outlets[i]: flame body, core, shock diamonds, nozzle glow AND the exhaust heat
              haze (HeatHaze is drawn from the same plume), radius NozzleRadiusBlocks
  TaP         AfterburnerPresentation.TapEmitters[i]: Outlet.Position + Offset, a box of Extents
  Sound       the engine loop plays at the entity origin (VehicleLoopSoundProvider, no per-nozzle position exists in
              code or json), so it is listed for the record and cannot fail
Nozzle exit = the rear-most ring of the nozzle geometry per engine: the rig bone nozzle_N when the model has one
(F-22: the vectoring nozzle piece), otherwise the rear-most ring of vertices around the engine's jet-pipe pit, which
engine_nozzles/nozzles.py finds from behind: the rear-most circle of vertices (RANSAC fit) around it. A round exit has
radius = the fitted circle's; the 2-D exit has radius (w+h)/4, w the piece's width, h its trailing-edge height.
Thrust vectoring: the rig bone nozzle_N pivot must lie on the nozzle axis and engineering.thrustVectoring.armM (CG to
nozzle pivot, along the fuselage) must equal the pivot's distance from the entity origin (the rotation centre).
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
GEN = os.path.join(ROOT, 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles')
FLIGHT = os.path.join(GEN, 'data/berts_vehicle_pack/flight_reference')

METRES_PER_BLOCK = 1.0      # 16 px per metre
TOL_M = 0.15                # an emitter may sit this far (metres) from its nozzle exit centre
RADIUS_TOL = 0.25           # and its flame radius may differ this much (fraction) from the nozzle radius
WINDOW = 1.1                # engine search window (blocks, in the rear view)
LAYER = 0.06                # a nozzle ring lies within this depth (blocks)

# Seeds (x, y, z-hint or None) for engines the automatic pit search cannot find or finds wrong. x is the +X side;
# the mirror engine is derived. Read off the rear view.
SEEDS = {}


def load_bones(vid):
    """{bone name: (vertices Nx3 in client/data blocks, used by triangles)}"""
    geo = json.load(open(os.path.join(GEO, vid + '.geo.json')))
    bones = {}
    for bone in geo['minecraft:geometry'][0]['bones']:
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys') or bone['name'].startswith('pilot_view_occluder'):
            continue
        pos = np.array(pm['positions'], float) * np.array([1.0, 1.0, -1.0]) / 16.0
        used = sorted({v[0] for poly in pm['polys'] for v in poly})
        bones[bone['name']] = pos[used]
    return bones


def circle_through(a, b, c):
    ax, ay = a; bx, by = b; cx, cy = c
    d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if abs(d) < 1e-9:
        return None
    ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
    uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
    return ux, uy, math.hypot(ax - ux, ay - uy)


def fit_circle(xy):
    """Algebraic (Kasa) least-squares circle through points: (cx, cy, r)."""
    A = np.c_[2 * xy[:, 0], 2 * xy[:, 1], np.ones(len(xy))]
    b = (xy ** 2).sum(1)
    s = np.linalg.lstsq(A, b, rcond=None)[0]
    return s[0], s[1], math.sqrt(max(s[2] + s[0] ** 2 + s[1] ** 2, 0.0))


def ring_at(points, seed, rhint=None, window=WINDOW):
    """The rear-most circular ring of vertices around seed (x, y): returns dict or None.
    Vertices within `window` of the seed on the seed's side of the centre line are cut into depth layers; a layer holds
    the nozzle rim when a circle (RANSAC over its vertices, so neighbouring geometry in the window is ignored) has at
    least 8 vertices on it spread round at least 300 degrees, a centre within 0.4 blocks of the seed and a radius of
    0.18 .. 1.2 blocks (and within 45% of rhint when given). Layers are tried from the tail forward."""
    sx, sy = seed[0], seed[1]
    d = np.hypot(points[:, 0] - sx, points[:, 1] - sy)
    m = d < window
    if abs(sx) > 0.15:
        m &= points[:, 0] * np.sign(sx) > 0.05
    p = points[m]
    if len(p) < 8:
        return None
    rng = np.random.RandomState(7)
    for z0 in np.unique(np.round(p[:, 2], 3)):
        q = p[(p[:, 2] >= z0 - 0.001) & (p[:, 2] <= z0 + LAYER)]
        xy = q[:, :2]
        if len(xy) < 8:
            continue
        best = None
        for _ in range(250):
            i = rng.choice(len(xy), 3, replace=False)
            c = circle_through(xy[i[0]], xy[i[1]], xy[i[2]])
            if not c or not (0.18 <= c[2] <= 1.2) or math.hypot(c[0] - sx, c[1] - sy) > 0.4:
                continue
            if rhint and abs(c[2] - rhint) > 0.45 * rhint:
                continue
            inl = np.abs(np.hypot(xy[:, 0] - c[0], xy[:, 1] - c[1]) - c[2]) < 0.03
            if best is None or inl.sum() > best.sum():
                best = inl
        if best is None or best.sum() < 8:
            continue
        cx, cy, r = fit_circle(xy[best])
        ang = np.sort(np.arctan2(xy[best, 1] - cy, xy[best, 0] - cx))
        gaps = np.diff(np.concatenate([ang, [ang[0] + 2 * math.pi]]))
        if gaps.max() > math.radians(60) or not (0.18 <= r <= 1.2):
            continue
        return {'x': float(cx), 'y': float(cy), 'z': float(q[best, 2].mean()), 'w': 2 * float(r), 'h': 2 * float(r),
                'r': float(r), 'n': int(best.sum())}
    return None

def pits(vid):
    """Automatic engine seeds: the jet-pipe pits seen from behind (tools/engine_nozzles/nozzles.py)."""
    sys.path.insert(0, os.path.join(ROOT, 'tools', 'engine_nozzles'))
    sys.path.insert(0, os.path.join(ROOT, 'tools', 'canopy_frames'))
    import warnings
    warnings.filterwarnings('ignore')
    import nozzles as N
    return [(cx, cy, z, r) for cx, cy, z, r, _ in N.find(vid)]


def measure(vid, bones):
    """List of measured nozzle exits: dicts with x, y, z, w, h, r, how."""
    out = []
    nb = sorted(n for n in bones if n.startswith('nozzle_') or '__nozzle_' in n)
    if nb:
        # a vectored 2-D nozzle: the piece swings about its hinge (front edge); its rear-most edge is the exit plane, as
        # wide as the piece and as tall as that trailing edge (the gap between the upper and lower flap)
        for n in nb:
            p = bones[n]
            lo, hi = p.min(0), p.max(0)
            aft = p[p[:, 2] <= lo[2] + 0.02]
            h = float(aft[:, 1].max() - aft[:, 1].min())
            w = float(hi[0] - lo[0])
            out.append({'x': float((lo[0] + hi[0]) / 2), 'y': float((aft[:, 1].max() + aft[:, 1].min()) / 2),
                        'z': float(aft[:, 2].mean()), 'w': w, 'h': h, 'r': (w + h) / 4, 'how': 'rig bone ' + n})
        return out
    allp = np.concatenate(list(bones.values()))
    seeds = [(x, y, None) for x, y, z in SEEDS.get(vid, [])]
    for x, y, _ in list(seeds):
        if abs(x) > 0.15:
            seeds.append((-x, y, None))
    how = 'seed'
    if not seeds:
        how = 'pit'
        seeds = [(cx, cy, None) for cx, cy, z, r in pits(vid)]
    for sx, sy, _ in seeds:
        ring = ring_at(allp, (sx, sy))
        if ring:
            ring['how'] = how
            out.append(ring)
    return out


def emitters(vid):
    """[(label, position, nozzle radius or None, kind)] from the client asset json."""
    asset = json.load(open(os.path.join(ASSET, vid + '.json')))
    ab = asset.get('AfterburnerPresentation') or {}
    rows = []
    byid = {}
    for o in ab.get('Outlets', []):
        byid[o['Id']] = np.array(o['Position'], float)
        rows.append((o['Id'] + ' flame+haze', np.array(o['Position'], float), o.get('NozzleRadiusBlocks', 0.42), 'outlet'))
    for t in ab.get('TapEmitters', []):
        base = byid.get(t['Outlet'])
        if base is None:
            continue
        ext = t.get('Extents', [0, 0, 0])
        rows.append((t['Outlet'] + ' tap ' + t['Style'], base + np.array(t.get('Offset', [0, 0, 0]), float),
                     max(ext) / 2 if ext else None, 'tap'))
    return rows, asset, ab


def check(vid, tol=TOL_M, rtol=RADIUS_TOL, quiet=False):
    bones = load_bones(vid)
    allp = np.concatenate(list(bones.values()))
    length = (allp[:, 2].max() - allp[:, 2].min()) * METRES_PER_BLOCK
    noz = measure(vid, bones)
    rows, asset, ab = emitters(vid)
    lines = [f'{vid}: model length {length:.2f} m ({METRES_PER_BLOCK:g} m per block), '
             f'{len(noz)} nozzle exit(s) measured, {len(rows)} emitter(s)']
    fails = []
    if not noz:
        fails.append('no nozzle exit found')
    if not rows and noz:
        fails.append('no AfterburnerPresentation outlets')
    result = []
    for n in noz:
        lines.append(f'  nozzle ({n["x"]:+.3f}, {n["y"]:.3f}, {n["z"]:.3f}) {n["w"]:.2f} x {n["h"]:.2f} m '
                     f'(diameter {2 * n["r"]:.2f} m) [{n["how"]}]')
    for label, pos, rad, kind in rows:
        if not noz:
            break
        best = min(noz, key=lambda n: np.linalg.norm(pos - [n['x'], n['y'], n['z']]) + (5 if n['x'] * pos[0] < -0.01 else 0))
        c = np.array([best['x'], best['y'], best['z']])
        off = float(np.linalg.norm(pos - c)) * METRES_PER_BLOCK
        status = 'ok'
        if off > tol:
            status = 'FAIL offset'
            fails.append(f'{label}: {off:.2f} m from the nozzle')
        flame = ''
        if kind == 'outlet' and rad is not None:
            ratio = rad / best['r']
            flame = f'flame r {rad:.3f} vs nozzle r {best["r"]:.3f} ({(ratio - 1) * 100:+.0f}%)'
            if abs(ratio - 1) > rtol:
                status = (status + ' ' if status != 'ok' else '') + 'FAIL radius'
                fails.append(f'{label}: flame radius {rad:.3f} vs nozzle {best["r"]:.3f}')
        elif kind == 'tap' and rad is not None:
            flame = f'box {2 * rad:.3f} m'
        lines.append(f'  {label:<26} now ({pos[0]:+.3f}, {pos[1]:.3f}, {pos[2]:.3f})  measured ({c[0]:+.3f}, {c[1]:.3f}, '
                     f'{c[2]:.3f})  offset {off:.2f} m  {flame}  {status}')
        result.append({'label': label, 'kind': kind, 'now': pos.round(4).tolist(), 'measured': c.round(4).tolist(),
                       'offset_m': round(off, 3), 'radius': rad, 'nozzle_r': round(best['r'], 3), 'status': status})
    lines.append('  engine sound (loop at the entity origin, no nozzle position exists): origin to nozzle centre '
                 + ', '.join(f'{math.hypot(n["y"], n["z"]):.1f} m' for n in noz[:1]) + '  n/a')
    # thrust vectoring (F-22): the rig nozzle pivot and the flight reference lever arm
    rig = (asset.get('AircraftRig') or {}).get('Surfaces', [])
    tv = None
    try:
        tv = json.load(open(os.path.join(FLIGHT, vid + '.json')))['engineering'].get('thrustVectoring')
    except (OSError, KeyError):
        pass
    for s in rig:
        if s['Bone'].startswith('nozzle_') and noz:
            px, py, pz = s['Pivot'][0] / 16, s['Pivot'][1] / 16, -s['Pivot'][2] / 16
            lines.append(f'  rig {s["Bone"]} pivot ({px:+.3f}, {py:.3f}, {pz:.3f}) (hinge, front of the vectoring piece)')
            near = min(noz, key=lambda n: abs(n['x'] - px))
            if math.hypot(px - near['x'], py - near['y']) > tol:
                fails.append(f'{s["Bone"]} pivot is {math.hypot(px - near["x"], py - near["y"]):.2f} m off the nozzle axis')
    if tv and noz:
        pivots = [-s['Pivot'][2] / 16 for s in rig if s['Bone'].startswith('nozzle_')]
        arm = (max(abs(z) for z in pivots) if pivots else max(abs(n['z']) for n in noz)) * METRES_PER_BLOCK
        lines.append(f'  thrustVectoring armM {tv["armM"]} vs distance origin to nozzle pivot {arm:.2f} m')
        if abs(tv['armM'] - arm) > tol:
            fails.append(f'thrustVectoring armM {tv["armM"]} vs pivot distance {arm:.2f} m')
    lines.append('  ' + ('PASS' if not fails else 'FAIL: ' + '; '.join(fails)))
    if not quiet:
        print('\n'.join(lines))
    return {'vid': vid, 'length_m': round(length, 2), 'nozzles': [{k: (round(v, 4) if isinstance(v, float) else v)
            for k, v in n.items()} for n in noz], 'emitters': result, 'fails': fails}


def fixed_outlets(vid):
    """The asset's Outlets list with each outlet moved onto its measured nozzle exit (id, direction and any other key
    kept). A mirrored pair takes the mean of both exits, so the jet stays symmetric."""
    noz = measure(vid, load_bones(vid))
    if not noz:
        raise SystemExit(f'{vid}: no nozzle exit measured')
    ab = json.load(open(os.path.join(ASSET, vid + '.json')))['AfterburnerPresentation']
    outlets = json.loads(json.dumps(ab['Outlets']))
    for o in outlets:
        near = min(noz, key=lambda n: abs(n['x'] - o['Position'][0]))
        mirror = [n for n in noz if n['x'] * near['x'] < 0 and abs(abs(n['x']) - abs(near['x'])) < 0.2]
        group = [near] + mirror
        x = float(np.mean([abs(n['x']) for n in group])) * (1 if near['x'] >= 0 else -1) if abs(near['x']) > 0.15 else near['x']
        o['Position'] = [round(x, 3), round(float(np.mean([n['y'] for n in group])), 3),
                         round(float(np.mean([n['z'] for n in group])), 3)]
        o['NozzleRadiusBlocks'] = round(float(np.mean([n['r'] for n in group])), 3)
    return outlets


def block_span(text, key):
    """(start, end, indent) of the json value `"key": [ ... ]` or `{ ... }` in text, found by bracket matching."""
    import re
    m = re.search(r'\n( *)"%s": ([\[{])' % re.escape(key), text)
    if not m:
        raise SystemExit(f'no "{key}" in the asset json')
    open_c = m.group(2)
    close_c = ']' if open_c == '[' else '}'
    depth = 0
    for i in range(m.end() - 1, len(text)):
        if text[i] == open_c:
            depth += 1
        elif text[i] == close_c:
            depth -= 1
            if depth == 0:
                return m.start() + 1, i + 1, m.group(1)
    raise SystemExit(f'unbalanced "{key}"')


def apply_fix(vid):
    outlets = fixed_outlets(vid)
    # 1. the spec: clientSet is applied by aircraft_variant.py after the template's client json is cloned, so a
    #    regeneration keeps the corrected outlets
    spec_path = os.path.join(ROOT, 'tools', 'vehgen', 'specs', vid + '.json')
    raw = open(spec_path, encoding='utf-8', newline='').read()
    spec = json.loads(raw)
    spec.setdefault('clientSet', {})['AfterburnerPresentation.Outlets'] = outlets
    text = json.dumps(spec, indent=2, ensure_ascii=False) + ('\n' if raw.endswith('\n') else '')
    open(spec_path, 'w', encoding='utf-8', newline='').write(text)
    # 2. the generated client json: replace the Outlets array in place (the file has hand-compacted blocks elsewhere)
    path = os.path.join(ASSET, vid + '.json')
    raw = open(path, encoding='utf-8', newline='').read()
    start, end, indent = block_span(raw, 'Outlets')
    body = json.dumps(outlets, indent=2).replace('\n', '\n' + indent)
    new = raw[:start] + f'{indent}"Outlets": {body}' + raw[end:]
    before, after = json.loads(raw), json.loads(new)
    before['AfterburnerPresentation']['Outlets'] = after['AfterburnerPresentation']['Outlets']
    assert before == after, 'only Outlets may change'
    open(path, 'w', encoding='utf-8', newline='').write(new)
    print(f'{vid}: wrote {len(outlets)} outlet(s) to the spec clientSet and the generated client json')


def main(argv):
    tol, rtol, jout, sheet = TOL_M, RADIUS_TOL, None, None
    ids = []
    if '--fix' in argv:
        for vid in [a for a in argv if a != '--fix']:
            apply_fix(vid)
        argv = [a for a in argv if a != '--fix']
    it = iter(argv)
    for a in it:
        if a == '--tol':
            tol = float(next(it))
        elif a == '--radius-tol':
            rtol = float(next(it))
        elif a == '--json':
            jout = next(it)
        elif a == '--sheet':
            sheet = next(it)
        else:
            ids.append(a)
    if not ids:
        raise SystemExit(__doc__)
    results = [check(v, tol, rtol) for v in ids]
    if jout:
        json.dump(results, open(jout, 'w'), indent=1)
    if sheet:
        import nozzle_sheet
        nozzle_sheet.make(ids, sheet, results)
    bad = [r['vid'] for r in results if r['fails']]
    print('nozzle_check: ' + ('all %d ok' % len(results) if not bad else 'FAIL %s' % ', '.join(bad)))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
