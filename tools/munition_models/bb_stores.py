#!/usr/bin/env python3
"""Store and flight models for aircraft munitions from the provided Blockbench sources.

  python3 tools/munition_models/bb_stores.py <folder with the .bbmodel files> [--write] [ids...]

Each source is re-oriented (its nose axis in MUNITIONS), centred on its body axis and scaled to the published
length (16 px per metre):
  store model  custom_geo/aircraft_stores/<id>.geo.json         nose -Z, body axis on the origin
  flight model custom_geo/aircraft_stores/<id>_flight.geo.json  +Y forward, nozzle (tail) at y = 0 (FFA track pose)
  texture      textures/aircraft_stores/<id>.png                the embedded texture (a flat body colour if none)
Stores that use the model (STORES) are pointed at it with Scale removed; run tools/aircraft_attach/attach.py apply
afterwards to re-derive their anchors from the new geometry. Faces are wound counter-clockwise seen from outside
their element. Idempotent.
"""
import copy, io, json, os, sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)
import bbmodel  # noqa: E402

GEN = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets', 'berts_vehicle_pack', 'custom_geo', 'aircraft_stores')
TEX = os.path.join(GEN, 'assets', 'berts_vehicle_pack', 'textures', 'aircraft_stores')
STORE_DATA = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw', 'aircraft_stores')
NS = 'berts_vehicle_pack'
PX = 16.0

# nose: the source axis the nose points along. length: published length in metres. roll180: lugs were below.
MUNITIONS = {
    'agm88': dict(source='AGM-88 HARM.bbmodel', nose='+Y', length=4.17),
    'aim120': dict(source='AIM-120.bbmodel', nose='-Z', length=3.65, colour=(201, 204, 206)),
    'agm114': dict(source='AGM-114.bbmodel', nose='+Y', length=1.63, roll180=True),   # launch lug on top
}
MUNITIONS.update({
    # new munitions (stores in aircraft_stores/munition/<id>.json, see NEW)
    'agm122': dict(source='AGM-122 Sidearm.bbmodel', nose='+Y', length=2.87),
    'agm158': dict(source='AGM-158 JASSM.bbmodel', nose='+Y', length=4.27, roll=90),     # wings / fin were on +X
    'agm86': dict(source='AGM-86 ALCM.bbmodel', nose='+Y', length=6.32, roll180=True),   # wings under, fin up
    'cm102': dict(source='CM-102.bbmodel', nose='+Y', length=4.4),
    'gbu28': dict(source='GBU-28.bbmodel', nose='+Y', length=5.84, roll=-90),           # lugs were on +X
    'kab1500l': dict(source='KAB-1500-L.bbmodel', nose='+Y', length=4.6),
    'kab250': dict(source='KAB-250-LG-E.bbmodel', nose='+Y', length=3.2),
    'kab500od': dict(source='KAB-500-OD.bbmodel', nose='+Y', length=3.05),
    'odab250': dict(source='ODAB-250.bbmodel', nose='+X', length=1.9),
    'odab500pm': dict(source='ODAB-500-PM.bbmodel', nose='+X', length=2.28),
    'yj91': dict(source='YJ-91.mtb', nose='+Z', length=4.7),
    'kd88': dict(source='KD-88.mtb', nose='+Z', length=4.8),
    'c201': dict(source='C-201.mtb', nose='+Z', length=7.36),
    'pl2': dict(source='PL-2.mtb', nose='+Z', length=2.99),
    'pl5': dict(source='PL-5.mtb', nose='+Z', length=2.89),
    'pl7': dict(source='PL-7.mtb', nose='+Z', length=2.75),
    'pl8': dict(source='PL-8.mtb', nose='+Z', length=2.99),
    'pl10': dict(source='PL-10.mtb', nose='+Z', length=3.0),
    'pl12': dict(source='PL-12.mtb', nose='+Z', length=3.93),
})

# New stores: template store (aircraft_stores/ path), what differs, and the aircraft that get them (every pylon of
# a carrier that already allows the template store also allows the new one). Figures are published values; TNT
# equivalents follow the pack's convention (explosive mass x relative effectiveness; fuel-air x2).
NEW = {
    'gbu28': dict(template='munition/gbu27.json', name='GBU-28 · 2,100 kg deep penetrator (laser)', mass=2130,
                  bomb={'BlastRadius': 13, 'TntEquivalentKg': 333.5, 'BlastDamage': 1100,
                        'Penetrator': {'MaxDepthBlocks': 8, 'MaxBlockHardness': 12, 'FuzeDelayTicks': 8}},
                  carriers=['f_111f']),
    'kab1500l': dict(template='fab_1500.json', name='KAB-1500L · 1,500 kg laser-guided', mass=1525,
                     bomb={'Mode': 'LASER', 'TurnDegreesPerTick': 1.5}, carriers=['su_35', 'tu_22m']),
    'kab500od': dict(template='fab_500.json', name='KAB-500Kr-OD · 500 kg fuel-air (TV)', mass=525,
                     bomb={'Mode': 'TV', 'TurnDegreesPerTick': 2, 'TntEquivalentKg': 500, 'BlastRadius': 30},
                     set={'Guidance': {'Mode': 'GROUND_INFRARED', 'Presentation': 'TV', 'LockTicks': 60, 'Range': 800,
                                       'ConeDegrees': 25, 'CountermeasureVulnerability': 0}},   # TV bombs need it
                     carriers=['su_35', 'su_27', 'su_39']),
    'kab250': dict(template='fab_250.json', name='KAB-250LG-E · 250 kg laser-guided', mass=256,
                   bomb={'Mode': 'LASER', 'TurnDegreesPerTick': 2.5},
                   carriers=['su_35', 'su_27', 'su_39', 'su_25', 'mig_29']),
    'odab250': dict(template='fab_250.json', name='ODAB-250 · 250 kg fuel-air', mass=250,
                    bomb={'TntEquivalentKg': 200, 'BlastRadius': 22},
                    carriers=['su_25', 'su_39', 'mig_21bis', 'mig_23mld', 'mig_29', 'su_27']),
    'odab500pm': dict(template='fab_500.json', name='ODAB-500PM · 500 kg fuel-air', mass=520,
                      bomb={'TntEquivalentKg': 386, 'BlastRadius': 32},
                      carriers=['su_25', 'su_39', 'mig_23mld', 'mig_29', 'su_27', 'su_35', 'tu_22m']),
    'agm122': dict(template='fa18e/agm88.json', name='AGM-122 Sidearm · anti-radiation', mass=88,
                   guidance={'Range': 900, 'ConeDegrees': 40}, flight={'Damage': 35, 'BlastRadius': 4,
                                                                          'TntEquivalentKg': 5.1, 'MaxSpeed': 8},
                   carriers=[]),                     # the AH-1W / AH-1F here have no store stations
    'agm158': dict(template='fa18e/agm84e.json', name='AGM-158 JASSM · stealth cruise', mass=1020,
                   flight={'Damage': 140, 'BlastRadius': 8, 'TntEquivalentKg': 150, 'Range': 1400},
                   match=['fa18e/agm84e.json', 'fa18e/agm88.json', 'munition/mk84.json'],
                   carriers=['fa_18e', 'f_16c', 'b_1b']),
    'agm86': dict(template='fa18e/agm84e.json', name='AGM-86C CALCM · cruise', mass=1430,
                  flight={'Damage': 200, 'BlastRadius': 12, 'TntEquivalentKg': 600, 'Range': 2000},
                  match=['munition/mk84.json', 'kh55.json'], carriers=['b_1b', 'tu_95ms']),
    'cm102': dict(template='munition/kh31p.json', name='CM-102 · anti-radiation', mass=300,
                  flight={'Damage': 90, 'BlastRadius': 6, 'TntEquivalentKg': 60},
                  match=['munition/kh31p.json', 'r27r.json'], carriers=['j_11a', 'su_35']),
    'yj91': dict(template='munition/kh31p.json', name='YJ-91 · anti-radiation', mass=600,
                 match=['r27r.json'], carriers=['j_11a']),
    'kd88': dict(template='munition/kh31p.json', name='KD-88 · TV/IR land attack', mass=600,
                 guidance={'Mode': 'ANTI_RADIATION'}, flight={'Damage': 150, 'BlastRadius': 9, 'TntEquivalentKg': 165},
                 match=['r27r.json'], carriers=['j_11a']),
    'pl8': dict(template='r73.json', name='PL-8 · short range IR', mass=115, carriers=['j_11a']),
    'pl12': dict(template='r77.json', name='PL-12 · medium range radar', mass=180, match=['r27r.json'],
                 carriers=['j_11a']),
}
# anchors the aircraft_attach rules cannot derive (non-circular bodies); MountAnchor frame, blocks
ANCHORS = {
    'agm86': {'MountAnchor': [0.0, 0.2575, 0.8848], 'SideMountAnchors': {'Left': [0.2502, 0.0, 0.8848],
              'Right': [-0.2502, 0.0, 0.8848]}, 'MountAxis': [0.0, 0.0, 0.8848], 'LaunchOffset': [0.0, -0.2575, 0.0]},
}
AIRCRAFT_STORES = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw', 'aircraft_stores')
ARMAMENTS = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw', 'aircraft_armaments')
BOMB_PROFILES = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw', 'projectile_profiles', 'aircraft_bombs')


def store_id(rel):
    return f'{NS}:' + rel[:-len('.json')]


def create_store(mid, write):
    spec = NEW[mid]
    tpl_rel = spec['template']
    tpl = json.load(open(os.path.join(AIRCRAFT_STORES, tpl_rel)))
    d = {}
    for k, v in tpl.items():
        d[k] = copy.deepcopy(v)
    d['Name'] = spec['name']
    d['Model'] = f'{NS}:custom_geo/aircraft_stores/{mid}.geo.json'
    d['Texture'] = f'{NS}:textures/aircraft_stores/{mid}.png'
    d['ModelForward'] = '-Z'
    d.pop('Scale', None)
    d['MassKg'] = spec['mass']
    for k, v in spec.get('set', {}).items():      # whole top-level blocks the template lacks
        d[k] = copy.deepcopy(v)
    for key, block in (('Bomb', 'bomb'), ('Flight', 'flight'), ('Guidance', 'guidance')):
        if block in spec:
            if key not in d:
                raise SystemExit(f'{mid}: template {tpl_rel} has no {key}')
            d[key].update(spec[block])
    notes = []
    if str(d.get('ProjectileProfile', '')).startswith(f'{NS}:aircraft_bombs/'):
        src = d['ProjectileProfile'].split('/', 1)[1]
        prof = json.load(open(os.path.join(BOMB_PROFILES, src + '.json')))
        ext = prof.setdefault('Extensions', {}).setdefault(f'{NS}:projectile_mesh_v1', {})
        ext.update({'Model': d['Model'], 'Texture': d['Texture'], 'ForwardYaw': 0, 'SpinDegreesPerTick': 0})
        prof['RenderScale'] = 1
        d['ProjectileProfile'] = f'{NS}:aircraft_bombs/{mid}'
        if write:
            with open(os.path.join(BOMB_PROFILES, mid + '.json'), 'w') as f:
                json.dump(prof, f, indent=2)
                f.write('\n')
        notes.append('bomb profile aircraft_bombs/' + mid)
    elif 'ProjectileProfile' in d or 'LaunchGunProfile' in d:
        raise SystemExit(f'{mid}: template {tpl_rel} uses a projectile profile this tool does not copy')
    rel = f'munition/{mid}.json'
    # anchors are derived later by tools/aircraft_attach (or set in ANCHORS); keep what is already written
    existing = os.path.join(AIRCRAFT_STORES, rel)
    if os.path.exists(existing):
        old = json.loads(open(existing).read().replace('NaN', 'null'))
        for k in ('MountAnchor', 'SideMountAnchors', 'MountAxis', 'LaunchOffset'):
            if k in old and 'null' not in json.dumps(old[k]):
                d[k] = old[k]
    for k, v in ANCHORS.get(mid, {}).items():
        d[k] = v
    if write:
        with open(os.path.join(AIRCRAFT_STORES, rel), 'w') as f:
            json.dump(d, f, indent=2, ensure_ascii=False)
            f.write('\n')
    # carriers: every pylon that allows the template store
    nid = store_id(rel)
    match = [store_id(m) for m in spec.get('match', [tpl_rel])]
    for ac in spec['carriers']:
        path = os.path.join(ARMAMENTS, ac + '.json')
        raw = open(path).read()
        arm = json.loads(raw)
        n = 0
        for pair in arm.get('Pairs', []) + arm.get('Singles', []):
            allowed = pair.get('AllowedStores', [])
            hit = [m for m in match if m in allowed]
            if hit and nid not in allowed:
                allowed.insert(allowed.index(hit[0]) + 1, nid)
                n += 1
        if n:
            notes.append(f'{ac} x{n}')
            if write:
                with open(path, 'w') as f:
                    f.write(json.dumps(arm, indent=1 if raw.startswith('{\n ') and not raw.startswith('{\n  ')
                                       else 2, ensure_ascii=False) + ('\n' if raw.endswith('\n') else ''))
        else:
            notes.append(f'{ac}: no station allows {match}' if not any(
                nid in p.get('AllowedStores', []) for p in arm.get('Pairs', []) + arm.get('Singles', [])) else f'{ac} ok')
    return notes


# store definitions (relative to aircraft_stores/) shown with each model
STORES = {
    'agm88': ['fa18e/agm88.json'],
    'aim120': ['fa18e/aim120.json'],
    'agm114': ['ah_64d/agm114_proxy.json'],
}
# projectile profiles whose flying mesh (projectile_mesh_v1) is the store model (nose -Z, centred, full size)
PROFILES = {
    'agm114': ['aircraft_stores/agm114k_ah64d.json'],
}
PROFILE_DATA = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw', 'projectile_profiles')

# source axis -> rotation taking it to store -Z (proper rotations)
TO_STORE = {
    '-Z': np.eye(3),
    '+Z': np.diag([-1.0, 1.0, -1.0]),                                  # yaw 180
    '+Y': np.array([[1.0, 0, 0], [0, 0, 1.0], [0, -1.0, 0]]),         # (x, z, -y)
    '-Y': np.array([[1.0, 0, 0], [0, 0, -1.0], [0, 1.0, 0]]),         # (x, -z, y)
    '+X': np.array([[0, 0, 1.0], [0, 1.0, 0], [-1.0, 0, 0]]),         # (z, y, -x)
    '-X': np.array([[0, 0, -1.0], [0, 1.0, 0], [1.0, 0, 0]]),         # (-z, y, x)
}


def source_polys(path):
    """(polys [(P, uv texels, texture index, element index)], [(image, uv width, uv height)]) of a .bbmodel, or of
    an SMP Toolbox .mtb mapped to (z, -y, -x) (+Y up, toolbox forward = -Z)."""
    if path.endswith('.mtb'):
        sys.path.insert(0, os.path.join(REPO, 'tools', 'mtb_wheels'))
        import mtb
        (tw, th), elements, png = mtb.read(path)
        polys = []
        for ei, f in enumerate(elements):
            for pos, uv in mtb.element_polys(f, tw, th):
                P = np.array([[p[2], -p[1], -p[0]] for p in pos], float)
                polys.append((P, np.array(uv, float), 0, ei))
        img = Image.open(io.BytesIO(png)).convert('RGBA') if png else None
        return polys, [(img, tw, th)]
    model = bbmodel.load(path)
    return bbmodel.polys(model), bbmodel.textures(model)


def convert(path, spec):
    polys, tex = source_polys(path)
    R = TO_STORE[spec['nose']]
    if spec.get('roll180'):
        R = np.diag([-1.0, -1.0, 1.0]) @ R
    if spec.get('roll'):                       # degrees about the store axis, +X toward +Y (lug to the top)
        a = np.radians(spec['roll'])
        R = np.array([[np.cos(a), -np.sin(a), 0], [np.sin(a), np.cos(a), 0], [0, 0, 1.0]]) @ R
    P_all = np.vstack([p for p, _, _, _ in polys]) @ R.T
    z0, z1 = P_all[:, 2].min(), P_all[:, 2].max()
    s = spec['length'] * PX / (z1 - z0)
    # body axis: the cross-section centre of the longest element (the body shell), not of fins or lugs
    by_el = {}
    for P, _, _, e in polys:
        by_el.setdefault(e, []).append(P @ R.T)
    body = max(by_el.values(), key=lambda v: (np.ptp(np.vstack(v)[:, 2]), len(v)))
    body = np.vstack(body)
    if path.endswith('.mtb'):
        # toolbox bodies are built from many strips: the whole cross-section (symmetric fins, no lugs) instead
        body = P_all
    cx = (body[:, 0].min() + body[:, 0].max()) / 2
    cy = (body[:, 1].min() + body[:, 1].max()) / 2
    centre = np.array([cx, cy, (z0 + z1) / 2])

    img, tw, th = (tex[0] if tex else (None, 16, 16))
    if img is None:
        img = Image.new('RGBA', (16, 16), tuple(spec.get('colour', (190, 190, 190))) + (255,))
        tw = th = 16
    positions, normals, uvs, out_polys = [], [], [], []
    element_centre = {}
    for P, _, _, e in polys:
        element_centre.setdefault(e, []).append(P)
    element_centre = {e: (np.vstack(v) @ R.T - centre).mean(0) * s for e, v in element_centre.items()}
    for P, U, t, e in polys:
        q = (P @ R.T - centre) * s
        n = np.cross(q[1] - q[0], q[2] - q[1])
        if len(q) == 4 and np.linalg.norm(n) < 1e-9:
            n = np.cross(q[2] - q[0], q[3] - q[2])
        ln = np.linalg.norm(n)
        if ln < 1e-9:
            continue
        n = n / ln
        # outward: away from the centre of the face's own element
        ref = q.mean(0) - element_centre[e]
        order = list(range(len(q)))
        if np.dot(n, ref) < 0:
            order, n = order[::-1], -n
        if not tex or t is None or isinstance(t, bool):
            uv_face = [(0.5, 0.5)] * len(q)
        else:
            uv_face = [(U[i][0] / tw, 1 - U[i][1] / th) for i in range(len(q))]
        normals.append([round(float(v), 5) for v in n])
        poly = []
        for i in order:
            positions.append([round(float(v), 5) for v in q[i]])
            uvs.append([round(float(uv_face[i][0]), 6), round(float(uv_face[i][1]), 6)])
            poly.append([len(positions) - 1, len(normals) - 1, len(uvs) - 1])
        out_polys.append(poly)
    mesh = {'normalized_uvs': True, 'positions': positions, 'normals': normals, 'uvs': uvs, 'polys': out_polys}
    return mesh, img, s, (z1 - z0) * s


def geo(mesh, ident, flight, length, tw, th):
    pos, normals = mesh['positions'], mesh['normals']
    if flight:
        pos = [[p[0], round(length / 2 - p[2], 5), p[1]] for p in pos]
        normals = [[n[0], -n[2], n[1]] for n in normals]
    return {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': ident, 'texture_width': tw, 'texture_height': th,
                        'visible_bounds_width': 8, 'visible_bounds_height': 8, 'visible_bounds_offset': [0, 0, 0]},
        'bones': [{'name': 'store', 'pivot': [0, 0, 0], 'poly_mesh': dict(mesh, positions=pos, normals=normals)}]}]}


def main(argv):
    write = '--write' in argv
    args = [a for a in argv if not a.startswith('--')]
    folder, ids = args[0], args[1:] or list(MUNITIONS)
    for mid in ids:
        spec = MUNITIONS[mid]
        mesh, img, s, length = convert(os.path.join(folder, spec['source']), spec)
        P = np.array(mesh['positions'])
        print(f'{mid}: {len(mesh["polys"])} faces, {s:.3f} px/unit, length {length / PX:.2f} m, '
              f'span x {(P[:, 0].max() - P[:, 0].min()) / PX:.2f} y {(P[:, 1].max() - P[:, 1].min()) / PX:.2f} m')
        if not write:
            continue
        for flight in (False, True):
            name = mid + ('_flight' if flight else '')
            with open(os.path.join(GEO, name + '.geo.json'), 'w') as fh:
                json.dump(geo(mesh, 'geometry.aircraft_store.' + name, flight, length, img.width, img.height), fh,
                          separators=(',', ':'))
        img.save(os.path.join(TEX, mid + '.png'))
        for rel in STORES.get(mid, []):
            path = os.path.join(STORE_DATA, rel)
            raw = open(path).read()
            d = json.loads(raw)
            d['Model'] = f'{NS}:custom_geo/aircraft_stores/{mid}.geo.json'
            d['Texture'] = f'{NS}:textures/aircraft_stores/{mid}.png'
            d['ModelForward'] = '-Z'
            d.pop('Scale', None)
            indent = 2 if raw.startswith('{\n') else None
            new = json.dumps(d, indent=indent, ensure_ascii=False) + ('\n' if raw.endswith('\n') else '')
            if new != raw:
                open(path, 'w').write(new)
                print('  store', rel)
        if mid in NEW:
            print('  ' + ', '.join(create_store(mid, write)))
        for rel in PROFILES.get(mid, []):
            path = os.path.join(PROFILE_DATA, rel)
            raw = open(path).read()
            d = json.loads(raw)
            ext = d.setdefault('Extensions', {}).setdefault(f'{NS}:projectile_mesh_v1', {})
            ext.update({'Model': f'{NS}:custom_geo/aircraft_stores/{mid}.geo.json',
                        'Texture': f'{NS}:textures/aircraft_stores/{mid}.png', 'ForwardYaw': 0})
            ext.setdefault('SpinDegreesPerTick', 0)
            d['RenderScale'] = 1
            new = json.dumps(d, indent=2 if raw.startswith('{\n') else None, ensure_ascii=False) + \
                ('\n' if raw.endswith('\n') else '')
            if new != raw:
                open(path, 'w').write(new)
                print('  profile', rel)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
