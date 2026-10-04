#!/usr/bin/env python3
"""Damage normalization pass (owner direction 2026-09-28). Idempotent; run it LAST, after the vehgen tools.

  python3 tools/damage/balance.py            # rewrite the generated data in place + tools/damage/REPORT.md
  python3 tools/damage/balance.py --check    # exit 1 when any generated value differs from the rules

What it writes (all numbers are the rules below, nothing hand-tuned per vehicle):
  * ground vehicles: MaxHealth by class and combat weight, DamageClass, DeathChargeKg, class DamageModifiers;
  * aircraft: MaxHealth by full-fuel mass, DamageClass, wing pools at 40 % (no elevator/rudder modules);
  * every BVP projectile profile: HullDamage / ModuleDamage / AmmoRackDamage (per-mille detonation chance) and,
    for HE, PenetrationMm = calibre / 2 (autocannon HE below 57 mm: 0.7 x calibre);
  * machine-gun belts (< 20 mm): no TNT charge and no legacy explosion;
  * placeholder box armor on light vehicles: one realistic thickness per face (public figures, RHA-equivalent);
  * launcher_weapon for the 9P149 Shturm's launcher-tube module (the only carrier with one for now);
  * mi24v / mi28n: no armor boxes, no rotor modules, no strict armor gate (originals kept in tools/replaced/).

MBT reference R = 300 hull HP. Weights are public combat weights (tonnes).
"""
import argparse
import copy
import glob
import json
import math
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
DATA = os.path.join(REPO, 'bvp/src/generated/resources/data/berts_vehicle_pack')
VEHICLES = os.path.join(DATA, 'sbw/vehicles')
PROFILES = os.path.join(DATA, 'sbw/projectile_profiles')
HANDHELD = os.path.join(REPO, 'bvp/src/main/resources/data/berts_vehicle_pack/sbw/projectile_profiles')
ARMOR = os.path.join(DATA, 'armor')
FLIGHT = os.path.join(DATA, 'flight_reference')
REPLACED = os.path.join(REPO, 'tools/replaced/damage-normalization-20260928')

R = 300.0

# ---------------------------------------------------------------- ground hull HP
# class: (base HP, reference tonnes, clamp lo, clamp hi); HP = base * (t / ref)^(1/3)
CLASSES = {
    'MBT':         (300, 50, 255, 345),
    'MBT_CHASSIS': (240, 50, 200, 260),
    'IFV':         (180, 22, 150, 215),
    'WHEELED':     (150, 18, 115, 180),
    'LIGHT':       (135, 12, 110, 150),
    'CAR':         (75, 2.3, 60, 85),
    'STATIC':      (45, 1, 40, 55),
}
GROUND = {  # combat weight (t), class
    'challenger_2': (62.5, 'MBT'), 'k2a1_black_panther': (55, 'MBT'), 'leclerc_s1': (56.5, 'MBT'),
    'leo2a6': (62.3, 'MBT'), 'leopard_2a4': (55.2, 'MBT'), 'm1_abrams_elite': (54.5, 'MBT'),
    'm1a1_abrams': (57, 'MBT'), 'm1a2_abrams_sep_v2': (64.6, 'MBT'), 'm48a3_elite': (47, 'MBT'),
    'm60a1': (52.6, 'MBT'), 't14_armata': (55, 'MBT'), 't55a_2_0': (36, 'MBT'), 't64b_obr1976': (42.4, 'MBT'),
    't72a': (41.5, 'MBT'), 't72b': (44.5, 'MBT'), 't72b3': (46, 'MBT'), 't72b3_ubh_cope': (48, 'MBT'),
    't80b_obr1976': (42.5, 'MBT'), 't80u_obr1985': (46, 'MBT'), 't90a': (46.5, 'MBT'), 't90m': (48, 'MBT'),
    't_62a': (37.5, 'MBT'), 'type_90': (50.2, 'MBT'), 'vt_4a1': (52, 'MBT'), 'ztz99a': (55, 'MBT'),
    'bmpt': (47.5, 'MBT_CHASSIS'), 'gepard': (47.5, 'MBT_CHASSIS'), 'pzh_2000': (55, 'MBT_CHASSIS'),
    'm109a7_paladin': (35, 'MBT_CHASSIS'), 'qn_506model': (36, 'MBT_CHASSIS'),
    'bmp2': (14.3, 'IFV'), 'bmp2m': (14.6, 'IFV'), 'bmp3m_elite': (18.7, 'IFV'), 'bmp_1am': (14, 'IFV'),
    'cv9040_no_net': (23, 'IFV'), 'm2_bradley': (30, 'IFV'), 'marder_1a1': (28, 'IFV'),
    'marder_1a2': (28.5, 'IFV'), 'marder_1a5': (37.4, 'IFV'), '9k22_tunguska': (34, 'IFV'), 'zsu23_4': (21, 'IFV'),
    'btr_90': (20.9, 'WHEELED'), 'vbci': (28, 'WHEELED'), 'lav25': (13, 'WHEELED'), 'btr80a': (14.6, 'WHEELED'),
    'btr_60pb': (10.3, 'WHEELED'), 'zbd_09': (21, 'WHEELED'), 'zsl_92': (15.8, 'WHEELED'), 'ztl_09': (23, 'WHEELED'),
    'm1128': (18.8, 'WHEELED'), '9p148': (7, 'WHEELED'), 'gaz_3937_vodnik_aa': (7.5, 'WHEELED'),
    'bmd_1': (7.5, 'LIGHT'), 'btr_zd': (8.5, 'LIGHT'), '9p149_shturm': (12, 'LIGHT'), 'm551a1': (15.8, 'LIGHT'),
    'toyota_jihad_bmp1': (2.5, 'CAR'), 'toyota_jihad_dshk': (2.3, 'CAR'), 'toyota_jihad_s5': (2.3, 'CAR'),
    'toyota_jihad_spg9': (2.3, 'CAR'), 'uaz_469_spg9': (1.6, 'CAR'),
    'ags_30': (0.1, 'STATIC'), 'browning_tripod': (0.06, 'STATIC'), 'kord_tripod': (0.05, 'STATIC'),
    'milan_tripod': (0.1, 'STATIC'), 'spg9_tripod': (0.07, 'STATIC'), 'tow_tripod': (0.1, 'STATIC'),
    'zu23_2': (0.95, 'STATIC'),
}


# Owner overrides of the class rule (hull HP).
HP_OVERRIDE = {
    'zu23_2': 90,  # owner 2026-10-04: "zu-23-2 has too little health" (the rule gave 44)
}


def ground_hp(name):
    if name in HP_OVERRIDE:
        return HP_OVERRIDE[name]
    tonnes, cls = GROUND[name]
    base, ref, lo, hi = CLASSES[cls]
    return int(round(max(lo, min(hi, base * (tonnes / ref) ** (1 / 3)))))


def death_charge_kg(name):
    """Fuel + stowed ammunition going up when the hull is destroyed (TNT-equivalent kg)."""
    tonnes, cls = GROUND[name]
    if cls == 'STATIC':
        return 0.5
    return round(max(2.0, min(25.0, 0.4 * tonnes)), 1)


# Native (non-armor) damage resistance by class: SBW's standard vehicle list with a class-scaled "All" factor.
# Projectile hits on BVP vehicles are resolved by the armor model and TNT blasts by DamageClass; these only
# scale what is left (melee, fire, lava, legacy explosions).
_STANDARD = ["minecraft:arrow 0", "minecraft:trident 0", "minecraft:mob_attack 0", "minecraft:mob_attack_no_aggro 0",
             "minecraft:mob_projectile 0", "minecraft:player_attack 0", "#superbwarfare:projectile 0", "All - 20",
             "minecraft:lava + 20", "minecraft:lava * 10", "@minecraft:tnt * 4", "@minecraft:tnt_minecart * 4",
             "@#superbwarfare:aerial_bomb * 12"]
RESIST = {'MBT': 0.23, 'MBT_CHASSIS': 0.3, 'IFV': 0.4, 'WHEELED': 0.5, 'LIGHT': 0.55, 'AIRPLANE': 0.23,
          'HELICOPTER': 0.35}


def modifiers(cls):
    if cls in ('CAR', 'STATIC'):
        return []
    return _STANDARD + [f"All * {RESIST[cls]}", "superbwarfare:vehicle_strike * 2.5", "minecraft:explosion * 2",
                        "superbwarfare:custom_explosion * 0.65", "superbwarfare:projectile_explosion * 0.65",
                        "superbwarfare:mine * 0.5", "superbwarfare:lunge_mine * 0.5",
                        "superbwarfare:projectile_hit * 1.3", "#superbwarfare:projectile_absolute * 0.15",
                        "@#superbwarfare:aa_missile * 0.3", "@superbwarfare:small_cannon_shell * 0.25",
                        "@superbwarfare:c4 * 4", "@#superbwarfare:at_rocket * 1.1",
                        "@superbwarfare:gun_grenade * 1.25", "@superbwarfare:mortar_shell * 1.25",
                        "@superbwarfare:tm_62 * 2.5"]


# ---------------------------------------------------------------- aircraft hull HP
HELI_MASS = {'mi24_hind_a': 11500, 'mi_24a': 11500, 'mi_24d': 11500, 'mi24v': 11500, 'mi28n': 11500, 'ka50': 10800,
             'ah_64d': 9500, 'eurocopter_tiger': 6000, 'ah1w_super_cobra': 6700, 'ah_1f': 4500, 'ah_1g_cobra': 4300,
             'ah_6j': 1600, 'uh1d_huey': 4300, 'ch_46e': 10400, 'mi_26': 56000}
ARMOURED = {'a_10': 1.35, 'su_25': 1.35, 'su_39': 1.35, 'il_10': 1.35, 'mi24_hind_a': 1.25, 'mi_24a': 1.25,
            'mi_24d': 1.25, 'mi24v': 1.25, 'mi28n': 1.25, 'ka50': 1.25, 'ah_64d': 1.25, 'eurocopter_tiger': 1.25,
            'ah1w_super_cobra': 1.25}
MASS_ALIAS = {'mig19': 'mig_19s'}
WING_IDS = {'superbwarfare:wing_left', 'superbwarfare:wing_right'}
WING_FRACTION = 0.4


def aircraft_mass(name):
    ref = os.path.join(FLIGHT, MASS_ALIAS.get(name, name) + '.json')
    if os.path.isfile(ref):
        return json.load(open(ref))['reference']['fullFuelMassKg']
    return HELI_MASS.get(name)


def aircraft_charge_kg(name):
    """Fuel and stores going up when the airframe is destroyed (TNT-equivalent kg)."""
    return round(max(3.0, min(150.0, 1.5 * aircraft_mass(name) / 1000.0)), 1)


def aircraft_hp(name):
    mass = aircraft_mass(name)
    if not mass:
        raise SystemExit(f'no mass for aircraft {name}')
    return int(round(400 * (mass / 12000.0) ** (2 / 3) * ARMOURED.get(name, 1.0)))


# ---------------------------------------------------------------- rounds
ATGM_DIAMETER = {'9m14': 125, 'bgm71a_tow': 152, 'bgm71e_tow': 152, '9m114_shturm': 130, 'milan_atgm': 115,
                 'milan_3_atgm': 115, '9m120_ataka': 130, '9k127_vikhr': 130}
TANDEM = {'bgm71e_tow', 'milan_3_atgm', 'agm114k_ah64d', 'hot3_uht'}
AIR_ATGM = {'agm114k_ah64d', 'hot3_uht', 'falanga_9m17m'}   # aircraft stores that are anti-tank guided missiles
GUN_MM = 75.0
HE_PEN_FROM_MM = 57.0
# autocannon HE (20-56 mm) penetration per mm of calibre (owner 2026-09-29: hit or miss vs IFVs)
AUTOCANNON_HE_PEN = 0.7


def GRENADE_LAUNCHER(path, rid):
    """Automatic grenade launchers (AGS-30 / BMP-2M / BTR-90 VOG-30) fire HE-frag grenades, not autocannon HE."""
    return 'grenadelauncher' in path or 'ags_30' in path or 'vog' in rid


def k(cal):
    return (cal / 120.0) ** 0.75


def autocannon(cal):
    return 0.30 * R * (cal / 120.0) ** 1.5


def atgm_fraction(diameter, pen, tandem):
    f = 0.40 * (diameter / 130.0) ** 0.5 * (max(pen, 1.0) / 700.0) ** 0.2 * (1.08 if tandem else 1.0)
    return max(0.30, min(0.55, f))


def rack_permille(kind, cal, atgm_f=None):
    if kind == 'ATGM':
        return int(round(450 + 300 * max(0.0, min(1.0, (atgm_f - 0.30) / 0.25))))
    if kind == 'ROCKET_HEAT':
        return 400
    if kind == 'MISSILE_HE':
        return 300
    if cal >= GUN_MM:
        return {'APFSDS': 350, 'APDS': 300, 'APCR': 300, 'APHE': 300, 'DEFAULT': 300, 'HEAT_FS': 500,
                'HEAT': 500, 'HE': 700}[kind]
    if kind == 'HE':
        return int(round(min(120, 60 * cal / 30)))
    if kind == 'HEAT_FS':
        return 500
    if cal >= 20:
        return int(round(min(80, 30 * cal / 30)))
    return 5


def round_kind(c, rid, path):
    """The rule family of a Combat block, or None to leave it as authored."""
    stem = os.path.splitext(os.path.basename(path))[0]
    if stem in AIR_ATGM:
        rid = stem
    cls = c['HullDamageClass']
    mun = c['MunitionType'].split(':')[1]
    cal = c.get('CaliberMm') or c.get('DiameterMm') or 0
    if mun == 'cluster_bomblet':
        return None
    if path.startswith('aircraft_stores/') and rid not in AIR_ATGM:
        return None     # heavy air-to-ground missiles (Maverick, Kh-25/29, KD-88, Bullpup) stay lethal
    if rid in AIR_ATGM or cls == 'ATGM' and mun != 'rocket':
        return 'ATGM'
    if mun == 'rocket' and c['DamageType'].endswith('chemical') and cls in ('ATGM', 'HEAT'):
        return 'ROCKET_HEAT'   # handheld RPG and unguided HEAT rockets
    if cls == 'HEAT' and mun == 'rocket':
        return 'ROCKET_HEAT'
    if cls == 'HE' and mun in ('atgm', 'sam'):
        return 'MISSILE_HE'
    if cls == 'HEAT' and mun == 'atgm':
        return 'ATGM'
    if cal < 20:
        return 'MG'
    return cls


def rules(c, rid, path):
    """(HullDamage, ModuleDamage, AmmoRackDamage per mille, PenetrationMm or None to keep, Tandem) or None."""
    kind = round_kind(c, rid, path)
    if kind is None:
        return None
    stem = os.path.splitext(os.path.basename(path))[0]
    if stem in AIR_ATGM:
        rid = stem   # aircraft-store ATGMs carry a proxy RoundId; the store file names the missile
    cal = float(c.get('CaliberMm') or c.get('DiameterMm') or 0)
    pen = c['PenetrationMm']
    # full-bore gun rounds (tank / recoilless / low-pressure guns) use the gun rules at any calibre >= 57 mm;
    # autocannon shells and bullets below 75 mm use the autocannon rule
    munition = c['MunitionType'].split(':')[1]
    gun = cal >= GUN_MM or (cal >= HE_PEN_FROM_MM and munition not in ('autocannon_shell', 'bullet'))
    tandem = bool(c['Tandem'] or rid in TANDEM)
    new_pen = None
    f = None
    if kind == 'ATGM':
        d = ATGM_DIAMETER.get(rid) or c.get('DiameterMm') or cal or 130
        f = atgm_fraction(d, pen, tandem)
        hull = f * R
    elif kind == 'ROCKET_HEAT':
        hull = 0.30 * R * (max(cal, 57.0) / 85.0) ** 0.75
    elif kind == 'MISSILE_HE':
        hull = 0.35 * R * (min(cal, 152.0) / 152.0) ** 0.75
        new_pen = round(0.2 * cal, 1)
    elif kind == 'MG':
        hull = autocannon(cal)
    elif kind == 'HE':
        hull = 0.60 * R * k(cal) if gun else 2 * autocannon(cal)
        if cal >= HE_PEN_FROM_MM:
            new_pen = round(0.5 * cal, 1)
        elif not GRENADE_LAUNCHER(path, rid):
            # owner 2026-09-29: autocannon HE is hit or miss - it defeats an IFV's weak spots (thin sides, rear,
            # roof: 30 mm HE 21 mm) and does next to nothing to its heavier plates
            new_pen = round(max(float(pen), AUTOCANNON_HE_PEN * cal), 1)
    elif kind in ('HEAT_FS', 'HEAT'):
        hull = 0.40 * R * k(cal)
    elif kind == 'APFSDS':
        hull = 0.30 * R * k(cal) if gun else autocannon(cal)
    else:  # APDS, APCR, APHE, DEFAULT (full-bore AP / autocannon AP)
        hull = 0.27 * R * k(cal) if gun else autocannon(cal)
    hull_i = max(1, int(round(hull)))
    module = max(1, int(round(min(100.0, 0.6 * hull))))
    rack_kind = 'ATGM' if kind == 'ATGM' else ('ROCKET_HEAT' if kind == 'ROCKET_HEAT' else
                                               'MISSILE_HE' if kind == 'MISSILE_HE' else
                                               'HE' if kind == 'HE' else ('DEFAULT' if kind == 'MG' else kind))
    rack = 5 if kind == 'MG' else rack_permille(rack_kind, max(cal, GUN_MM) if gun else cal, f)
    return hull_i, module, rack, new_pen, tandem, kind


def apply_round(data, path):
    c = data.get('Combat')
    if not c:
        return None
    rid = c['RoundId'].split(':')[1]
    r = rules(c, rid, path)
    if r is None:
        return None
    hull, module, rack, pen, tandem, kind = r
    before = (c['HullDamage'], c['ModuleDamage'], c['AmmoRackDamage'], c['PenetrationMm'])
    c['HullDamage'], c['ModuleDamage'], c['AmmoRackDamage'] = hull, module, rack
    if tandem:
        c['Tandem'] = True
    if kind == 'ATGM' and c['HullDamageClass'] != 'ATGM':
        c['HullDamageClass'] = 'ATGM'
    if pen is not None:
        c['PenetrationMm'] = pen
        curve = c.get('PenetrationCurve')
        if curve:
            curve['PenetrationMm'] = [pen] * len(curve['PenetrationMm'])
    return {'round': rid, 'kind': kind, 'cal': c.get('CaliberMm') or c.get('DiameterMm'), 'before': before,
            'after': (hull, module, rack, c['PenetrationMm'])}


# ---------------------------------------------------------------- machine-gun belts
def calibre_of_profile(ref):
    if not ref or ':' not in ref:
        return None
    ns, path = ref.split(':', 1)
    for root in (PROFILES, HANDHELD):
        f = os.path.join(root, path + '.json')
        if os.path.isfile(f):
            c = json.load(open(f)).get('Combat') or {}
            return c.get('CaliberMm') or c.get('DiameterMm')
    return None


def strip_mg_charges(node, changes, where=''):
    """A gun data block whose projectile profile is < 20 mm carries no TNT and no legacy explosion."""
    if isinstance(node, dict):
        profile = (node.get('NominalBallistics') or {}).get('ProjectileProfile')
        cal = calibre_of_profile(profile)
        if cal is not None and cal < 20:
            had = [k for k in ('TntEquivalentKg', 'ExplosionDamage', 'ExplosionRadius')
                   if k in node and (k == 'TntEquivalentKg' or node[k] not in (0, 0.0))]
            node.pop('TntEquivalentKg', None)
            for key in ('ExplosionDamage', 'ExplosionRadius'):
                if key in node:
                    node[key] = 0
            if had or any(k in node for k in ('ExplosionDamage', 'ExplosionRadius')):
                changes.append(f'{where.lstrip("/")} ({cal} mm): no charge, no explosion'
                               + (f' (removed {", ".join(had)})' if had else ''))
        for key, value in node.items():
            strip_mg_charges(value, changes, where + '/' + key)
    elif isinstance(node, list):
        for i, value in enumerate(node):
            strip_mg_charges(value, changes, f'{where}[{i}]')


# ---------------------------------------------------------------- placeholder box armor
# Nominal RHA-equivalent mm per face, public figures (manufacturer protection levels / open references):
#   BTR-80A 10 front / 7-8 side, BTR-60PB 9 / 7, LAV-25 14.5 mm-proof front, 7.62-proof sides;
#   M2 Bradley aluminium + spaced steel (30 mm-proof front arc), M1128 Stryker 14.5 mm-proof all round with
#   ceramic appliqué; Marder 1A2 upper glacis 32 mm steel; CV9040C 30 mm APFSDS-proof front, 14.5 sides;
#   VBCI 14.5 mm all round, frontal arc against 25-30 mm.
PLATES = {
    'btr80a':        {'front': 10, 'side': 8, 'rear': 7, 'roof': 7, 'belly': 5, 'turret_front': 10, 'turret': 7},
    'btr_60pb':      {'front': 9, 'side': 7, 'rear': 7, 'roof': 7, 'belly': 5, 'turret_front': 10, 'turret': 7},
    'lav25':         {'front': 14, 'side': 10, 'rear': 8, 'roof': 8, 'belly': 6, 'turret_front': 14, 'turret': 10},
    'm2_bradley':    {'front': 40, 'side': 25, 'rear': 20, 'roof': 16, 'belly': 12, 'turret_front': 40, 'turret': 25},
    'm1128':         {'front': 30, 'side': 20, 'rear': 15, 'roof': 10, 'belly': 10, 'turret_front': 20, 'turret': 15},
    'marder_1a2':    {'front': 32, 'side': 20, 'rear': 15, 'roof': 15, 'belly': 12, 'turret_front': 25, 'turret': 15},
    'cv9040_no_net': {'front': 60, 'side': 25, 'rear': 15, 'roof': 12, 'belly': 10, 'turret_front': 50, 'turret': 20},
    'vbci':          {'front': 40, 'side': 20, 'rear': 15, 'roof': 12, 'belly': 10, 'turret_front': 20, 'turret': 15},
}


def _rot(v, rot):
    def rx(v, d):
        r = math.radians(d); c, s = math.cos(r), math.sin(r); return (v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c)
    def ry(v, d):
        r = math.radians(d); c, s = math.cos(r), math.sin(r); return (v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c)
    def rz(v, d):
        r = math.radians(d); c, s = math.cos(r), math.sin(r); return (v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2])
    return rz(ry(rx(v, rot[0]), rot[1]), rot[2])


def aspect(n, frame):
    """tools/armor_mesh/auto_mesh.py rule; armor-profile frame, front = -Z, up = +Y."""
    if frame == 'hull' and n[2] < -0.12 and abs(n[1]) < 0.995:
        return 'front'
    if frame == 'hull' and n[2] > 0.25 and abs(n[1]) < 0.97:
        return 'rear'
    if n[1] > 0.7:
        return 'roof'
    if n[1] < -0.7:
        return 'belly' if frame == 'hull' else 'floor'
    if n[2] < -0.5:
        return 'front'
    if n[2] > 0.5:
        return 'rear'
    return 'side'


def plate_faces(plates):
    """[(plate, aspect)]: a plate faces along its thinnest axis, turned away from its frame's centre."""
    centres = {}
    for frame in {p.get('frame', 'hull') for p in plates}:
        own = [p['center'] for p in plates if p.get('frame', 'hull') == frame]
        centres[frame] = [sum(c[i] for c in own) / len(own) for i in range(3)]
    out = []
    for p in plates:
        frame = p.get('frame', 'hull')
        axis = min(range(3), key=lambda i: p['half_size'][i])
        unit = [0.0, 0.0, 0.0]
        unit[axis] = 1.0
        n = _rot(unit, p.get('rotation') or [0, 0, 0])
        off = [p['center'][i] - centres[frame][i] for i in range(3)]
        if sum(n[i] * off[i] for i in range(3)) < 0:
            n = tuple(-x for x in n)
        out.append((p, aspect(n, frame), frame))
    return out


def plate_mm(values, frame, asp):
    if frame == 'hull':
        return values.get(asp, values['side'])
    if asp == 'front':
        return values['turret_front']
    return values['turret']


# ---------------------------------------------------------------- launcher tubes
MIRRORED = {'t72a', 't72b'}
LAUNCHER_TUBE_VEHICLES = {'9p149_shturm'}   # launcher-tube module (owner 2026-09-28: Shturm only for now)
TUBE_HALF_WIDTH = 0.14
TUBE_LENGTH = 1.2


def profile_combat(ref):
    if not ref or ':' not in ref:
        return None
    f = os.path.join(PROFILES, ref.split(':', 1)[1] + '.json')
    return (load(f).get('Combat') if os.path.isfile(f) else None)


def launcher_weapon(vehicle):
    """(weapon key, weapon) whose every round is a guided anti-tank missile, or None."""
    for key, w in (vehicle.get('Weapons') or {}).items():
        refs = [(w.get('NominalBallistics') or {}).get('ProjectileProfile')]
        for ammo in w.get('AmmoType') or []:
            refs.append(((ammo.get('Override') or {}).get('NominalBallistics') or {}).get('ProjectileProfile'))
        combats = [profile_combat(r) for r in refs if r]
        if combats and all(c and c['HullDamageClass'] == 'ATGM' and c['MunitionType'].endswith('atgm')
                           for c in combats):
            return key, w
    return None


def launcher_boxes(name, vehicle):
    """Launcher-tube module boxes behind every muzzle of the vehicle's missile launcher (profile frame)."""
    found = launcher_weapon(vehicle)
    if not found:
        return None, []
    key, weapon = found
    attachments = vehicle.get('Attachments') or {}
    turret = vehicle.get('TurretPos') or [0, 0, 0]
    barrel = vehicle.get('BarrelPos') or [0, 0, 0]
    boxes = []
    shoot = weapon.get('ShootPos') or {}
    muzzles = []
    for att in shoot.get('MuzzleAttachments') or []:
        if att in attachments:
            a = attachments[att]
            pos, parent, hops = list(a['Position']), a.get('Parent', 'Vehicle'), 0
            while parent in attachments and hops < 8:   # fitted mounts: sum the chain at rest pose
                up = attachments[parent]
                pos = [pos[j] + up['Position'][j] for j in range(3)]
                parent, hops = up.get('Parent', 'Vehicle'), hops + 1
            muzzles.append((parent, pos, a.get('Direction') or [0, 0, 1]))
    if not muzzles:   # fitted launchers name their muzzles in the model only; the positions are authored here
        muzzles = [(shoot.get('Transform', 'Vehicle'), pos, [0, 0, 1]) for pos in shoot.get('Positions') or []]
    for i, (parent, pos, direction) in enumerate(muzzles):
        p = list(pos)
        frame = {'Vehicle': 'hull', 'Turret': 'turret', 'Barrel': 'barrel'}.get(parent)
        if frame is None:
            continue
        if frame == 'hull' and vehicle.get('TurretPos'):
            frame = 'turret'   # launchers ride the turret; rest-pose coordinates are the same in both frames
        if parent in ('Turret', 'Barrel'):
            p = [p[j] + turret[j] for j in range(3)]
        if parent == 'Barrel':
            p = [p[j] + barrel[j] for j in range(3)]
        d = direction
        sx = 1.0 if name in MIRRORED else -1.0          # SBW vehicle-local -> armor profile: rotate 180 about Y
        pc = (sx * p[0], p[1], -p[2])
        dc = (sx * d[0], d[1], -d[2])
        n = math.sqrt(sum(x * x for x in dc)) or 1.0
        dc = tuple(x / n for x in dc)
        pitch = math.degrees(math.asin(max(-1.0, min(1.0, -dc[1]))))
        yaw = math.degrees(math.atan2(dc[0], dc[2]))
        centre = [round(pc[j] - dc[j] * TUBE_LENGTH / 2, 5) for j in range(3)]
        boxes.append({'name': f'launcherreload_tube_{i:02d}', 'center': centre,
                      'half_size': [TUBE_HALF_WIDTH, TUBE_HALF_WIDTH, TUBE_LENGTH / 2],
                      'rotation': [round(pitch, 3), round(yaw, 3), 0], 'frame': frame, 'module': 'launcherreload'})
    return key, boxes


# ---------------------------------------------------------------- driver
def load(path):
    with open(path, encoding='utf8') as f:
        return json.load(f)


def dump(path, data, check, changed):
    text = json.dumps(data, indent=2, ensure_ascii=False) + '\n'
    old = open(path, encoding='utf8').read()
    if old != text:
        changed.append(os.path.relpath(path, REPO))
        if not check:
            with open(path, 'w', encoding='utf8') as f:
                f.write(text)


def keep_original(path, check):
    if check:
        return
    dest = os.path.join(REPLACED, os.path.relpath(path, REPO))
    if not os.path.exists(dest):
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        shutil.copy2(path, dest)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true')
    args = ap.parse_args()
    changed, report = [], {'ground': [], 'air': [], 'rounds': [], 'belts': [], 'plates': [], 'launchers': []}

    for path in sorted(glob.glob(os.path.join(VEHICLES, '*.json'))):
        name = os.path.basename(path)[:-5]
        d = load(path)
        before = d.get('MaxHealth')
        if d['Type'] in ('Airplane', 'Helicopter'):
            cls = 'AIRPLANE' if d['Type'] == 'Airplane' else 'HELICOPTER'
            d['MaxHealth'] = aircraft_hp(name)
            d['DamageModifiers'] = modifiers(cls)
            d['DeathChargeKg'] = aircraft_charge_kg(name)
            surfaces = d.get('AircraftSurfaceModules')
            if surfaces:
                # wings hold 40 % of the hull; elevators and rudder are hull (owner 2026-09-28)
                d['AircraftSurfaceModules'] = [dict(m, MaxHealthFraction=WING_FRACTION) for m in surfaces
                                               if m['Id'] in WING_IDS]
            report['air'].append((name, d['Type'], round(aircraft_mass(name) / 1000, 1), before, d['MaxHealth']))
        else:
            if name not in GROUND:
                raise SystemExit(f'ground vehicle {name} has no weight/class entry')
            cls = GROUND[name][1]
            d['MaxHealth'] = ground_hp(name)
            d['DeathChargeKg'] = death_charge_kg(name)
            d['DamageModifiers'] = modifiers(cls)
            report['ground'].append((name, cls, GROUND[name][0], before, d['MaxHealth'], d['DeathChargeKg']))
        d['DamageClass'] = cls
        belts = []
        strip_mg_charges(d.get('Weapons'), belts, name)
        report['belts'] += belts
        dump(path, d, args.check, changed)

    for root in (PROFILES, HANDHELD):
        for path in sorted(glob.glob(os.path.join(root, '**/*.json'), recursive=True)):
            d = load(path)
            rel = os.path.relpath(path, root)
            r = apply_round(d, rel)
            if r:
                r['path'] = rel
                report['rounds'].append(r)
                dump(path, d, args.check, changed)

    for name, values in PLATES.items():
        path = os.path.join(ARMOR, name + '.json')
        d = load(path)
        keep_original(path, args.check)
        for p, asp, frame in plate_faces(d['plates']):
            p['armor_mm'] = plate_mm(values, frame, asp)
            report['plates'].append((name, p['name'], frame, asp, p['armor_mm']))
        dump(path, d, args.check, changed)

    for path in sorted(glob.glob(os.path.join(VEHICLES, '*.json'))):
        name = os.path.basename(path)[:-5]
        vehicle = load(path)
        armor = os.path.join(ARMOR, name + '.json')
        if vehicle['Type'] in ('Airplane', 'Helicopter') or not os.path.isfile(armor):
            continue
        key, boxes = launcher_boxes(name, vehicle)
        if not key:
            continue
        d = load(armor)
        if name not in LAUNCHER_TUBE_VEHICLES:
            # owner 2026-09-28: only the Shturm has a launcher-tube module for now
            modules = [m for m in d.get('modules') or [] if m.get('module') != 'launcherreload']
            if modules != (d.get('modules') or []) or 'launcher_weapon' in d:
                d['modules'] = modules
                d.pop('launcher_weapon', None)
                dump(armor, d, args.check, changed)
            continue
        modules = d.get('modules') or []
        authored = [m for m in modules if m.get('module') == 'launcherreload'
                    and not m['name'].startswith('launcherreload_tube_')]
        if not authored:   # the 9P149 keeps its hand-fitted arm and tube
            modules = [m for m in modules if not m['name'].startswith('launcherreload_tube_')] + boxes
        d['modules'] = modules
        d['launcher_weapon'] = key
        report['launchers'].append((name, key, len(authored) or len(boxes), 'authored' if authored else 'muzzles'))
        dump(armor, d, args.check, changed)

    for name in ('mi24v', 'mi28n'):
        path = os.path.join(ARMOR, name + '.json')
        d = load(path)
        if d.get('plates') or d.get('modules') or d.get('engines') or d.get('strict_armor_gate'):
            keep_original(path, args.check)
        d['plates'], d['modules'], d['engines'] = [], [], []
        d['strict_armor_gate'] = False
        d['unboxed_hits_penetrate'] = True
        dump(path, d, args.check, changed)

    if args.check:
        if changed:
            print('\n'.join(changed))
            print(f'{len(changed)} file(s) differ from the damage rules')
            return 1
        print('damage data matches the rules')
        return 0
    write_report(report)
    print(f'{len(changed)} file(s) rewritten; report: tools/damage/REPORT.md')
    return 0


def write_report(rep):
    out = ['# Damage normalization report', '', 'Generated by `tools/damage/balance.py`. MBT reference R = 300.', '']
    out += ['## Ground hull HP', '', '| vehicle | class | t | HP before | HP | death charge kg |', '|---|---|---|---|---|---|']
    out += [f'| {n} | {c} | {t} | {b} | {h} | {kg} |' for n, c, t, b, h, kg in rep['ground']]
    cols = [('125 APFSDS', 0.30 * R * k(125)), ('120 APFSDS', 0.30 * R * k(120)), ('105 APFSDS', 0.30 * R * k(105)),
            ('125 HEAT-FS', 0.40 * R * k(125)), ('125 HE', 0.60 * R * k(125)), ('30 AP', autocannon(30)),
            ('30 HE', 2 * autocannon(30)), ('12.7', autocannon(12.7)), ('Konkurs', atgm_fraction(135, 575, 0) * R),
            ('Kornet', atgm_fraction(152, 1200, 1) * R), ('RPG 110', 0.30 * R * (110 / 85) ** 0.75)]
    out += ['', '## Penetrating hits to kill', '', '| vehicle | HP | ' + ' | '.join(c for c, _ in cols) + ' |',
            '|---|---|' + '---|' * len(cols)]
    for n, c, t, b, h, kg in rep['ground']:
        out.append(f'| {n} | {h} | ' + ' | '.join(str(math.ceil(h / d)) for _, d in cols) + ' |')
    out += ['', '## Aircraft hull HP', '', '| aircraft | type | full-fuel t | HP before | HP | wing pool |',
            '|---|---|---|---|---|---|']
    out += [f'| {n} | {ty} | {m} | {b} | {h} | {round(0.4 * h) if ty == "Airplane" else "-"} |'
            for n, ty, m, b, h in rep['air']]
    out += ['', '## Rounds', '', '| profile | kind | cal | hull | module | rack ‰ | pen |', '|---|---|---|---|---|---|---|']
    for r in rep['rounds']:
        b, a = r['before'], r['after']
        out.append(f"| {r['path'][:-5]} | {r['kind']} | {r['cal']} | {b[0]} → {a[0]} | {b[1]} → {a[1]} | "
                   f"{b[2]} → {a[2]} | {b[3]} → {a[3]} |")
    out += ['', '## Launcher tubes (module 30 HP; destroyed = loaded missile lost, back after the reload)', '',
            '| vehicle | weapon | boxes | source |', '|---|---|---|---|']
    out += [f'| {n} | {w} | {c} | {src} |' for n, w, c, src in rep['launchers']]
    out += ['', '## Machine-gun rounds (< 20 mm) without charges', ''] + [f'- {x}' for x in rep['belts']]
    agg = {}
    for n, pl, fr, asp, mm in rep['plates']:
        agg.setdefault((n, fr, asp, mm), 0)
        agg[(n, fr, asp, mm)] += 1
    out += ['', '## Light-vehicle box armor (plates per face)', '', '| vehicle | frame | face | mm | plates |',
            '|---|---|---|---|---|']
    out += [f'| {n} | {fr} | {asp} | {mm} | {c} |' for (n, fr, asp, mm), c in sorted(agg.items())]
    with open(os.path.join(HERE, 'REPORT.md'), 'w', encoding='utf8') as f:
        f.write('\n'.join(out) + '\n')


if __name__ == '__main__':
    sys.exit(main())
