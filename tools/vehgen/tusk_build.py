#!/usr/bin/env python3
"""M1A1 Abrams TUSK II (m1a1_tusk): the supplied M1A1 Abrams TUSK II.mtb fitted as a tracked Abrams.

    python3 tools/vehgen/tusk_build.py [--write] [--sources DIR]     (then tusk_icon.py, then icon_flags/flags.py --write m1a1_tusk)

The source is the same Flan Abrams the pack's m1a1_abrams was fitted from (wheel centres, idler, sprocket and gun
muzzle land on the m1a1_abrams values at 0.928 px/unit), plus the TUSK parts. It is built as a new vehicle:

  1. geometry (this file): toolbox parts -> the pack's Abrams bone hierarchy (m1a1_abrams as the bone template, its
     89-link track bones kept; tools/track_path/belt.py re-fits the belt round the new wheels afterwards);
  2. systems (tools/vehgen/fit_existing.py, spec injected below): weapons, seats, station, armour layout and handling
     from the m1a2_abrams_sep_v2 template (WT M829A2 / M830A1 / M830, M256 coax, M2HB station), the model's own points.

Source parts: 0 hull (+ 15 ARAT side tiles and skirts), 3 turret, 4 gun mantlet and the M2HB with its shield and
cradle, 17 gun tube (authored at the origin: it belongs to the mantlet pivot, +35 / -27.5), 9 / 10 left / right
road wheels and idler, 12 sprockets, 14 a 6-box hub inside the hull (dropped).
"""
import copy
import json
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mtbgeo  # noqa: E402
import vehgen  # noqa: E402
import fit_existing  # noqa: E402
import fit_specs  # noqa: E402

mtb = mtbgeo.mtb
VID = 'm1a1_tusk'
TEMPLATE_GEO_VID = 'm1a1_abrams'
SCALE = 0.928            # px per toolbox unit: the pack Abrams' hull length (140.3 px) over the model's 151.2 units
WHEEL_BOTTOM = 1.5       # px: the pack Abrams' wheels sit 1.5 px over the ground plane (track plate under them)
SOURCE = 'M1A1 Abrams TUSK II.mtb'
TEX_H = 1152             # the model's 1024 rows plus the 128 the Abrams' track-link bones sample (x 0-127, y 1024-1151)
GUN_SHIFT = (35.0, -27.5, 0.0)       # part 17 is authored at the origin; the mantlet pivot is where it belongs


def load(path):
    return vehgen.load(path)


class Model:
    def __init__(self, sources):
        self.src = mtbgeo.Source(os.path.join(sources, SOURCE))
        ground = self.src.corners([9, 10, 12])[:, 1].max()
        self.ground = ground
        self.frame = mtbgeo.Frame(SCALE, ground * SCALE + WHEEL_BOTTOM)

    def geo(self, p):
        return self.frame.geo(p)

    def els(self, part, pick=None):
        out = []
        for i, f in enumerate(self.src.parts[part]):
            if pick is None or pick(i, f):
                out.append(f)
        return out

    def mesh(self, elements, shift=(0.0, 0.0, 0.0)):
        """poly_mesh of elements; `shift` moves them in the toolbox frame first (part 17)."""
        if not elements:
            return {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        if any(shift):
            moved = []
            for f in elements:
                g = list(f)
                for k in range(3):
                    g[6 + k] = repr(mtb.num(f[6 + k]) + shift[k])
                moved.append(g)
            elements = moved
        return mtbgeo.mesh(elements, self.frame, self.src.tw, TEX_H)

    def box(self, elements):
        c = np.array([self.geo(p) for f in elements for p in mtb.box_corners(f)])
        return c.min(0), c.max(0)


def wheel_groups(model, part):
    """Wheel elements of a track side part, grouped by their rotation point x."""
    groups = {}
    for f in model.src.parts[part]:
        key = round(mtb.num(f[6]), 3)
        groups.setdefault(key, []).append(f)
    return dict(sorted(groups.items()))


def pivot_of(model, elements):
    rp = mtbgeo.Source.rotation_point(elements)
    return [round(float(v), 5) for v in model.geo(rp)]


def shifted(geo_bone, dx):
    pm = geo_bone.get('poly_mesh')
    if pm:
        for p in pm['positions']:
            p[0] = round(p[0] + dx, 5)
    for c in geo_bone.get('cubes', []):
        c['origin'][0] = round(c['origin'][0] + dx, 5)
    geo_bone['pivot'][0] = round(geo_bone['pivot'][0] + dx, 5)


def build_geo(model, tpl):
    """The fitted model in final px: template bones and hierarchy, the TUSK's meshes."""
    g = copy.deepcopy(tpl)
    geo = g['minecraft:geometry'][0]
    geo['description']['identifier'] = f'geometry.{VID}'
    geo['description']['texture_width'] = model.src.tw
    geo['description']['texture_height'] = TEX_H
    bones = {b['name']: b for b in geo['bones']}
    keep_mesh = ('trackRot', 'trackMov', 'brokenTrack', 'crudeTrack', 'TrackL', 'TrackR')
    for b in geo['bones']:
        if not b['name'].startswith(keep_mesh):
            b.pop('poly_mesh', None)

    # hull: body, ARAT tiles and skirts (the hub in part 14 is inside the hull and is dropped)
    bones['hull']['poly_mesh'] = model.mesh(model.els(0) + model.els(15))
    # turret: part 3
    tur = model.els(3)
    bones['turret']['poly_mesh'] = model.mesh(tur)
    bones['turret']['pivot'] = pivot_of(model, tur)
    # gun: the mantlet, the tube (part 17, moved onto the mantlet pivot) and the M2HB with its shield and cradle on top
    # of it (TUSK's Counter Sniper / Anti-Materiel Mount, CSAMM): one bone, so the M2 elevates and traverses with the gun
    p4 = model.src.parts[4]
    gun_pivot = mtbgeo.Source.rotation_point(p4)
    tube = model.mesh(model.els(17), GUN_SHIFT)
    ment = model.mesh(p4)
    off = len(ment['positions'])
    merged = {'normalized_uvs': True, 'positions': ment['positions'] + tube['positions'],
              'normals': ment['normals'] + tube['normals'], 'uvs': ment['uvs'] + tube['uvs'], 'polys': list(ment['polys'])}
    for poly in tube['polys']:
        merged['polys'].append([[v[0] + off, v[1] + len(ment['normals']), v[2] + len(ment['uvs'])] for v in poly])
    bones['barell']['poly_mesh'] = merged
    bones['barell']['pivot'] = [round(float(v), 5) for v in model.geo(gun_pivot)]
    # no passenger weapon station: the template's station bones go
    geo['bones'] = [b for b in geo['bones'] if not b['name'].startswith('passengerWeaponStation')]
    # running gear: road wheels (rear to front), idler and sprocket per side
    for side, part, sign in (('L', 9, 1), ('R', 10, -1)):
        groups = wheel_groups(model, part)
        xs = list(groups)
        idler = xs[-1]
        road = xs[:-1]
        assert len(road) == 7, (side, xs)
        for i, x in enumerate(road):
            b = bones[f'wheel{side}{i}']
            b['poly_mesh'] = model.mesh(groups[x])
            b['pivot'] = pivot_of(model, groups[x])
        b = bones[f'wheel{side}Front']
        b['poly_mesh'] = model.mesh(groups[idler])
        b['pivot'] = pivot_of(model, groups[idler])
        spr = [f for f in model.src.parts[12] if (mtb.num(f[8]) > 0) == (sign > 0)]
        b = bones[f'wheel{side}Rear']
        b['poly_mesh'] = model.mesh(spr)
        b['pivot'] = pivot_of(model, spr)

    # the track plane: the template's links sit on the wheel centre plane (x 25.05); move them to this model's
    for side, sign in (('L', 1), ('R', -1)):
        road0 = bones[f'wheel{side}0']
        P = np.array(road0['poly_mesh']['positions'])
        centre = (P[:, 0].min() + P[:, 0].max()) / 2
        tpl_x = sign * 25.0518
        dx = centre - tpl_x
        for name, b in bones.items():
            if name.startswith((f'trackRot{side}', f'trackMov{side}', f'brokenTrack{side}', f'crudeTrack{side}',
                                f'Track{side}')):
                shifted(b, dx)
        bones[f'Wheel{side}']['pivot'][0] = round(sign * abs(road0['pivot'][0]), 5)
        TRACK_X[side] = round(float(centre), 5)

    lo, hi = bounds_of(geo)
    size = hi - lo
    geo['description']['visible_bounds_width'] = round(float(max(size[0], size[2]) / 16 + 1), 2)
    geo['description']['visible_bounds_height'] = round(float(size[1] / 16 + 1), 2)
    geo['description']['visible_bounds_offset'] = [0, round(float((lo[1] + hi[1]) / 32), 2), 0]
    return g


TRACK_X = {}
M2_MUZZLE = (64.0, -35.0, -1.0)         # toolbox: front of the M2 barrel
EXHAUST = (-72.0, -17.0, 0.0)            # toolbox: over the rear grille, behind the turret


def bounds_of(geo):
    pts = [p for b in geo['bones'] if b.get('poly_mesh') for p in b['poly_mesh']['positions']]
    P = np.array(pts)
    return P.min(0), P.max(0)


def px(model, p):
    """Toolbox point -> final model px [x, y, z] (+X left, +Y up, nose -Z)."""
    return [round(float(v), 4) for v in model.geo(p)]


def make_spec(model):
    t = lambda *p: px(model, p)
    hull_lo, hull_hi = model.box(model.els(0))
    centre_z = lambda f: float(np.mean([mtb.box_corners(f)[k][2] for k in range(8)]))
    centre_x = lambda f: float(np.mean([mtb.box_corners(f)[k][0] for k in range(8)]))
    side = [f for f in model.els(15) if centre_x(f) > -60]          # the side arrays, not the tail piece
    left = [f for f in side if centre_z(f) > 20]
    right = [f for f in side if centre_z(f) < -20]
    sl_lo, sl_hi = model.box(left)
    sr_lo, sr_hi = model.box(right)
    # turret body without the two antennas and their bases
    body = [f for f in model.els(3) if min(c[1] for c in mtb.box_corners(f)) >= -50.0]
    tb_lo, tb_hi = model.box(body)
    return {
        # collision: the hull body, the two ARAT / skirt arrays (the source is 18 % wider with them), the engine
        # deck (same place and size as the Abrams template's MainEngine box) and the turret
        'obb': [
            {'box': [list(hull_lo), list(hull_hi)]},
            {'box': [[float(v) for v in sl_lo], [float(v) for v in sl_hi]]},
            {'box': [[float(v) for v in sr_lo], [float(v) for v in sr_hi]]},
            {'box': [[-14.8, 6.5, 41.8], [14.8, 18.7, 61.8]], 'part': 'MainEngine'},
            {'box': [[float(v) for v in tb_lo], [float(v) for v in tb_hi]], 'transform': 'Turret', 'part': 'Turret'},
        ],
        'id': VID,
        'template': 'm1a2_abrams_sep_v2',
        'hitbox': (4.07, 2.42),
        'geometry': {'scale': 1.0},
        'attachments': {
            # gunner's primary sight: just ahead of the GPS housing's open front (part 3 elements 3-6: x 1-14,
            # y -39.5..-33.5, z -20.5..-9.5), on its axis
            'gunner1_camera': {'parent': 'Turret', 'at': t(14.6, -36.5, -15.0)},
            'gunner1_zoom': {'parent': 'Turret', 'at': t(14.6, -36.5, -15.0)},
            # commander: head over his closed cupola ring (right, rear of the turret roof); no weapon of his own
            'gunner2_camera': {'parent': 'Turret', 'at': t(-15.5, -51.0, -10.5)},
            'main_muzzle': {'parent': 'Barrel', 'at': t(111.0, -27.5, 0.5)},
            # coax: the tube stub in the mantlet right of the gun (part 4 elements 1-3), muzzle at its front end
            'coax_muzzle': {'parent': 'Barrel', 'at': t(47.5, -30.75, -5.25)},
        },
        'seats': [
            {'frame': 'Turret', 'at': t(2.0, -23.0, -15.0)},       # gunner, right front under the GPS
            {'frame': 'Turret', 'at': t(-15.5, -23.0, -10.0)},     # commander, right rear under his cupola ring
            {'frame': 'Turret', 'at': t(-15.5, -23.0, 10.0)},      # loader, left rear
        ],
        'muzzleRename': {'roof_coax_muzzle': 'coax_muzzle'},
        'dropData': ['RoofCoaxPitch', 'PassengerWeaponStationControllerIndex', 'PassengerWeaponStationBinding',
                     'PassengerWeaponStationPos', 'PassengerWeaponStationBarrelPos', 'PassengerWeaponStationTurnSpeed',
                     'PassengerWeaponStationPitchRange', 'PassengerWeaponStationYawRange'],
    }


BELT_ROUNDS = {
    # WT 7.62 mm M240 belt: ap_ball 853 m/s, t_ball 835 m/s
    'western_762_ap': ('NONE', 853, None), 'western_762_t': ('RED', 835, None),
    # WT 12.7 mm M2HB: M20 API-T 929 m/s, M1 I 944 m/s, M2 AP 894 m/s
    'western_127_m20_api_t_1': ('RED', 929, None), 'western_127_m20_api_t_2': ('RED', 929, None),
    'western_127_m1_i': ('NONE', 944, None), 'western_127_m2_ap': ('NONE', 894, None),
}
# WT belts (datamine 7_62mm_m240 / 12_7mm_m2_hb blk): the M240 alternates ap_ball and t_ball; the M2HB has four belts,
# AP (M2 AP, M2 AP, M20 API-T), Universal (M20, M1 I, M2 AP, M20), API (M20, M8 API, M20: the pack has no M8 API
# round, so that belt is not built) and APIT. The best armour-piercing belt is the default (docs/WT_BELTS.md).
COAX_BELTS = [('AP Belt', [('western_762_ap', 1, 'NONE'), ('western_762_t', 1, 'T')])]
M2_BELTS = [('AP Belt', [('western_127_m2_ap', 2, 'NONE'), ('western_127_m20_api_t_1', 1, 'T')]),
            ('Universal Belt', [('western_127_m20_api_t_2', 1, 'T'), ('western_127_m1_i', 1, 'NONE'),
                                ('western_127_m2_ap', 1, 'NONE'), ('western_127_m20_api_t_1', 1, 'T')]),
            ('APIT Belt', [('western_127_m20_api_t_1', 1, 'T')])]
WT = {
    'Mass': 61.23,                     # WT us_m1a1_hc_abrams: empty 60,000 kg + 1,230 kg fuel
    'TurretTurnSpeed': [40.0, 24.0],   # WT speedYaw 40, speedPitch 24 deg/s
    'MaxBackwardSpeedRate': 0.1088,    # WT maxRevSpeed 10 against maxFwdSpeed 75 (the template's 0.816)
}


def finish(model, spec):
    """War Thunder and model-specific data on top of what fit_existing wrote."""
    sys.path.insert(0, os.path.join(HERE, '..', 'ballistics'))
    sys.path.insert(0, os.path.join(HERE, '..', 'damage'))
    import wt_belts
    import tracer_nations
    import balance
    path = fit_existing.files_of(VID)['data']
    d = load(path)
    d['VehicleIcon'] = f'berts_vehicle_pack:textures/vehicle_icon/{VID}.png'
    d['Mass'] = WT['Mass']
    d['TurretTurnSpeed'] = WT['TurretTurnSpeed']
    d['EngineInfo']['MaxBackwardSpeedRate'] = WT['MaxBackwardSpeedRate']
    # CSAMM: the M2HB is a gunner weapon fixed on the gun (key MainMachineGun, the pack's coax-class reticle); the
    # commander has no weapon (the model has none on the cupola) and looks over his cupola
    d['Weapons']['MainMachineGun'] = d['Weapons'].pop('PassengerMachineGun')
    d['Seats'][0]['Weapons'] = ['Cannon', 'MachineGun', 'MainMachineGun']
    cmd = d['Seats'][1]
    cmd.pop('Weapons', None)
    cam = cmd['CameraPos']
    cam.update({'Transform': 'Turret', 'Direction': 'Turret', 'EyeAttachment': 'gunner2_camera',
                'ZoomEyeAttachment': 'gunner2_camera', 'DirectionAttachment': 'main_muzzle',
                'ZoomDirectionAttachment': 'main_muzzle'})
    # M2 muzzle in the barrel frame, like the coax's ShootPos (z negated there)
    barrel_origin = np.array(d['TurretPos']) + np.array(d['BarrelPos'])
    muzzle = mtbgeo.geo_to_data(np.array(px(model, M2_MUZZLE))) - barrel_origin
    d['Attachments']['hmg_muzzle'] = {'Parent': 'Barrel', 'Position': [round(float(v), 5) for v in muzzle],
                                      'Direction': [0, 0, 1]}
    sp = d['Weapons']['MainMachineGun']['ShootPos']
    shoot = [round(float(muzzle[0]), 5), round(float(muzzle[1]), 5), round(float(-muzzle[2]), 5)]
    sp.update({'Transform': 'Barrel', 'Directions': ['Barrel'], 'Positions': [shoot], 'ViewPosition': shoot})
    t, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)
    # WT belts and belt change times (WT reloadTime 8 s for both)
    wt_belts.ROUNDS.update(BELT_ROUNDS)
    notes = []
    for wn, family, belts, mag in (('MachineGun', 'WESTERN_COAX_762', COAX_BELTS, 200),
                                   ('MainMachineGun', 'WESTERN_127', M2_BELTS, 100)):
        w = d['Weapons'][wn]
        wt_belts.rebuild(VID, wn, w, family, belts, {}, {}, notes)
        w['Magazine'] = mag
        w['NormalReloadTime'] = w['EmptyReloadTime'] = 160
    for wn in ('MachineGun', 'MainMachineGun'):         # round entries are named for the round, not a belt
        for e in d['Weapons'][wn].get('ProjectileBeltAmmoType', []):
            rid = os.path.basename(e['Override']['Projectile']['Profile'])
            e['Override']['Name'] = {'belt_ammo_00_western_127_m1_i': 'I', 'belt_ammo_00_western_762_t': 'T'}.get(rid, e['Override']['Name'])
    tracer_nations.fix(d, False, [])
    if notes:
        print('belts:', notes)
    # the driver's eye is the gunner's sight (owner's rule for this expansion): reference attachment, data frame
    sight = spec['attachments']['gunner1_camera']['at']
    eye = mtbgeo.geo_to_data(np.array(sight))
    d.setdefault('Attachments', {})['driver_camera'] = {'Parent': 'VehicleCustomPitch',
                                                        'Position': [round(float(v), 5) for v in eye],
                                                        'Direction': [0, 0, 1]}
    # the M2's profiles follow its new weapon key (WeaponId and file names)
    text = json.dumps(d, indent=2).replace(f'{VID}/passengermachinegun', f'{VID}/mainmachinegun')
    with open(path, 'w') as fh:
        fh.write(text + '\n')
    base = os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', VID)
    old = os.path.join(base, 'passengermachinegun')
    if os.path.isdir(old):
        new = os.path.join(base, 'mainmachinegun')
        os.makedirs(new, exist_ok=True)
        for name in os.listdir(old):
            body = open(os.path.join(old, name)).read().replace(f'{VID}/passengermachinegun', f'{VID}/mainmachinegun')
            with open(os.path.join(new, name), 'w') as fh:
                fh.write(body)
            os.remove(os.path.join(old, name))
        os.rmdir(old)
    # exhaust: the AGT-1500 grille on the rear deck (the Abrams template's origin is at the glacis)
    cpath = fit_existing.files_of(VID)['client']
    c = load(cpath)
    c['EngineExhaust']['Origins'][0]['Position'] = [round(float(v), 5) for v in
                                                    mtbgeo.geo_to_data(np.array(px(model, EXHAUST)))]
    with open(cpath, 'w') as fh:
        fh.write(json.dumps(c, indent=2) + '\n')
    return [round(float(v), 5) for v in eye]


def era_tiles(model):
    """The ARAT tiles the model carries as geometry: the 14 x 2 tiles on each hull side (part 15, outer face z 37.8 and
    out) and the 6 x 2 on each turret side (part 3, 5-6 units wide, 6 high, z beyond 29). Tiles are the touching
    clusters of those elements. Returns [(frame, name, lo, hi)] with boxes in geo px."""
    def clusters(els):
        boxes = [model.box([f]) for f in els]
        par = list(range(len(els)))

        def find(a):
            while par[a] != a:
                par[a] = par[par[a]]
                a = par[a]
            return a
        for a in range(len(els)):
            for b in range(a + 1, len(els)):
                if np.all(boxes[a][0] <= boxes[b][1] + 0.02) and np.all(boxes[b][0] <= boxes[a][1] + 0.02):
                    par[find(a)] = find(b)
        groups = {}
        for i in range(len(els)):
            groups.setdefault(find(i), []).append(boxes[i])
        return [(np.min([b[0] for b in g], 0), np.max([b[1] for b in g], 0)) for g in groups.values()]

    def zmin_max(f):
        z = [c[2] for c in mtb.box_corners(f)]
        return min(z), max(z)

    hull = [f for f in model.els(15) if abs(np.mean([c[2] for c in mtb.box_corners(f)])) > 37.0 and
            np.mean([c[0] for c in mtb.box_corners(f)]) > -60]
    tur = []
    for f in model.els(3):
        c = np.array(mtb.box_corners(f))
        dx, dy = np.ptp(c[:, 0]), np.ptp(c[:, 1])
        if 4.5 <= dx <= 6.2 and 5.5 <= dy <= 6.2 and abs(c[:, 2]).min() >= 28.0 and c[:, 0].max() < 0:
            tur.append(f)
    out = []
    for frame, els in (('hull', hull), ('turret', tur)):
        for lo, hi in clusters(els):
            out.append((frame, lo, hi))
    return out


def add_era(model, spec):
    path = fit_existing.files_of(VID)['armor']
    a = load(path)
    # armour-profile frame of a geo point: (-x / 16, y / 16, z / 16), the turret's boxes in the same rest-pose coordinates
    tiles = []
    counts = {'hull': 0, 'turret': 0}
    for frame, lo, hi in sorted(era_tiles(model), key=lambda t: (t[0], t[1][2] < 0, t[1][0], t[1][1])):
        c = (lo + hi) / 2
        half = (hi - lo) / 32
        tiles.append({'name': f'arat_{frame}_{counts[frame]:02d}',
                      'center': [round(float(-c[0] / 16), 5), round(float(c[1] / 16), 5), round(float(c[2] / 16), 5)],
                      'half_size': [round(float(v), 5) for v in half], 'rotation': [0, 0, 0], 'frame': frame,
                      'type': 'arat_i', 'kinetic_protection_mm': 25, 'chemical_protection_mm': 400})
        counts[frame] += 1
    a['explosive_reactive_armor'] = tiles
    with open(path, 'w') as fh:
        fh.write(json.dumps(a, indent=2) + '\n')
    return counts


def write_textures(model, geo):
    """Entity texture (the model's own PNG plus the track-link rows), wreck texture (x0.178). The icons are tusk_icon.py."""
    assets = vehgen.ASSETS
    own = Image.open(__import__('io').BytesIO(model.src.png)).convert('RGBA')
    tpl = Image.open(os.path.join(assets, 'textures', 'entity', f'{TEMPLATE_GEO_VID}.png')).convert('RGBA')
    img = Image.new('RGBA', (own.width, TEX_H), (0, 0, 0, 0))
    img.paste(own, (0, 0))
    img.paste(tpl.crop((0, own.height, own.width, TEX_H)), (0, own.height))      # the track-link texture
    img.save(os.path.join(assets, 'textures', 'entity', f'{VID}.png'))
    a = np.asarray(img).astype(float)
    dead = a.copy()
    dead[..., :3] = np.clip(np.round(a[..., :3] * vehgen.DEAD), 0, 255)
    Image.fromarray(dead.astype(np.uint8)).save(os.path.join(assets, 'textures', 'entity_dead', f'{VID}.png'))


def main(argv):
    write = '--write' in argv
    sources = os.path.join(HERE, '..', '..', '..', 'source', 'mtb', 'elite_expansion')
    if '--sources' in argv:
        sources = argv[argv.index('--sources') + 1]
    sources = os.path.abspath(sources)
    model = Model(sources)
    tpl = load(fit_existing.files_of(TEMPLATE_GEO_VID)['geo'])
    geo = build_geo(model, tpl)
    if '--dump' in argv:                       # the fitted geometry only, for a look in Blender
        with open(argv[argv.index('--dump') + 1], 'w') as fh:
            json.dump(geo, fh, separators=(',', ':'))
        return 0
    spec = make_spec(model)
    fit_specs.SPECS[VID] = spec
    # the data-only "original" fit_existing starts from: this fitted geo and the m1a1 client/wreck skeletons
    files = fit_existing.files_of(TEMPLATE_GEO_VID)
    client = json.loads(json.dumps(load(files['client'])).replace(TEMPLATE_GEO_VID, VID))
    for side in client['RunningGear']['TrackRender']['Sides']:
        side['XCenter'] = TRACK_X[side['Side']]
    orig = {'geo': geo, 'wreck': load(files['wreck']), 'client': client, 'data': {}, 'armor': {}}
    fit_existing.originals = lambda vid, w: orig
    # the armour layout maps the template's hull box onto this hull: leave the ARAT / skirt arrays (18 % wider) out
    own_box = fit_existing.own_box

    def hull_body_box(g, bone):
        if bone != 'hull':
            return own_box(g, bone)
        P = np.array(fit_existing.bones_of(g)['hull']['poly_mesh']['positions'], float)
        P = P[np.abs(P[:, 0]) <= 33.0]
        return P.min(0), P.max(0)
    fit_existing.own_box = hull_body_box
    rc = fit_existing.main([VID] + (['--write'] if write else []))
    if write and rc == 0:
        sys.path.insert(0, os.path.join(HERE, '..', 'track_path'))
        import belt                                   # the link belt round this model's wheels (tools/track_path)
        _, msg = belt.rebuild(VID, write=True)
        print('belt:', msg)
        eye = finish(model, spec)
        print('ARAT tiles:', add_era(model, spec))
        write_textures(model, geo)
        print('driver_camera (data frame):', eye)
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
