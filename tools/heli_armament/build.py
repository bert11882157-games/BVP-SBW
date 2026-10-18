#!/usr/bin/env python3
"""Helicopter suspended armament, built from the War Thunder loadouts (wt_slots.json) and the procedural models of
catalog.py: every WT weapon slot becomes one Single mount at its pylon (helis.py), carrying exactly the stores WT
allows on it. Writes

  custom_geo/aircraft_stores/heli_<model>.geo.json   launchers, pods, rails and munitions (+ _flight models)
  aircraft_stores/heli/<store>.json                  store definitions
  guns|projectile_profiles/aircraft_stores/<p>.json  launch and projectile profiles of the guided rounds
  aircraft_armaments/<vid>.json                      the slots
  vehicles/<vid>.json                                one native channel per (slot, pod) firing from the pod's
                                                     tube mouths or muzzles; retired channels removed
  custom_geo/<vid>.geo.json                          the baked suspended_* store bones removed

and the shared palette texture to --texture (an LFS file the owner adds; see README.md).

    python3 tools/heli_armament/build.py [--texture PATH] [vid ...]
"""
import copy, json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import catalog as C
import meshlib
from meshlib import anchor

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
ASSETS = os.path.join(GEN, 'assets', 'berts_vehicle_pack')
DATA = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw')
NS = 'berts_vehicle_pack'
TEXTURE = f'{NS}:textures/aircraft_stores/heli_stores.png'
TEMPLATES = json.load(open(os.path.join(HERE, 'templates.json')))


def geo_loc(name):
    return f'{NS}:custom_geo/aircraft_stores/{name}.geo.json'


def write_json(path, doc):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(doc, indent=2, ensure_ascii=False) + '\n')


def load(path):
    return json.load(open(path, encoding='utf-8-sig'))


# ------------------------------------------------------------------------------------------------ models
MODELS = {   # file name -> builder
    'heli_m261': C.m261, 'heli_lau131': C.lau131, 'heli_aero_6a': C.aero_6a, 'heli_aero_7a': C.aero_7a,
    'heli_fz225': C.fz225, 'heli_b8v20': C.b8v20, 'heli_ub32': C.ub32, 'heli_b13l': C.b13l,
    'heli_xm18': C.xm18, 'heli_m134d': C.m134d_mount, 'heli_gau19': C.gau19, 'heli_xm35': C.xm35,
    'heli_hmp400': C.hmp400, 'heli_rmp_lc': C.rmp_lc, 'heli_upk23': C.upk23, 'heli_guv8700': C.guv8700,
    'heli_ofab250': C.ofab250, 'heli_fab500_m54': C.fab500_m54, 'heli_fab500_m62': C.fab500_m62,
    'heli_stinger': lambda: C.stinger(0.0387), 'heli_igla': lambda: C.igla(0.0387),
    'heli_tow': lambda: C.tow(bore=0.0946), 'heli_tow2': lambda: C.tow(True, 0.0946), 'heli_hot3': lambda: C.hot3(0.0774),
    'heli_shturm': lambda: C.shturm(0.067), 'heli_ataka': lambda: C.ataka(0.067), 'heli_vikhr': lambda: C.vikhr(0.065),
    'heli_apkws': lambda: C.apkws(0.037), 'heli_s24': None,
    'heli_m272': lambda: C.m299(4), 'heli_m279': lambda: C.m299(2), 'heli_tow_x2': lambda: C.tow_launcher(2),
    'heli_tow_x4': lambda: C.tow_launcher(4), 'heli_hot_x2': lambda: C.hot_launcher(2),
    'heli_hot_x4': lambda: C.hot_launcher(4), 'heli_atas': C.atas, 'heli_atas_tiger': lambda: C.atas(True),
    'heli_igla_x2': lambda: C.igla_launcher(2), 'heli_igla_x4': lambda: C.igla_launcher(4),
    'heli_shturm_x2': C.shturm_launcher, 'heli_ataka_x4': lambda: C.ataka_launcher(4),
    'heli_ataka_x8': lambda: C.ataka_launcher(8), 'heli_vikhr_x6': C.vikhr_launcher,
    'heli_falanga_x2': C.falanga_rails, 'heli_apu60_x2': lambda: C.apu60(2),
    'heli_trigat_x2': lambda: C.trigat_launcher(2), 'heli_trigat_x4': lambda: C.trigat_launcher(4),
    'heli_ltc': C.ltc_dispenser,
}
# tube-launched rounds are stored with their wings folded into the bore; their flight models have them deployed
FLIGHT = {'heli_stinger': C.stinger, 'heli_igla': C.igla, 'heli_tow': C.tow, 'heli_tow2': lambda: C.tow(True),
          'heli_hot3': C.hot3, 'heli_shturm': C.shturm, 'heli_ataka': C.ataka, 'heli_vikhr': C.vikhr,
          'heli_apkws': C.apkws}
_parts = {}


def s24_on_rail():
    """the S-24B hangs from its APU-68 rail and is drawn with it (one round per rail)"""
    rail = C.apu68(); rocket = C.s24()
    m = rail.mesh.copy().add(rocket.mesh.moved((0, -0.10, 0.0)))
    return C.Part(m, muzzles=[(0, -0.22, 1.165)], axis=(0, -0.22, 0), length=2.33)


MODELS['heli_s24'] = s24_on_rail


def part(name):
    if name not in _parts:
        _parts[name] = MODELS[name]()
    return _parts[name]


def write_models():
    out = os.path.join(ASSETS, 'custom_geo', 'aircraft_stores')
    for name in MODELS:
        p = part(name)
        meshlib.write_store(p.mesh, os.path.join(out, name + '.geo.json'), f'geometry.aircraft_store.{name}')
    for name, fn in FLIGHT.items():
        meshlib.write_flight(fn().mesh, os.path.join(out, name + '_flight.geo.json'),
                             f'geometry.aircraft_store.{name}_flight')


# ------------------------------------------------------------------------------------------------ stores
def modeled(name, model, extra):
    p = part(model)
    doc = {'Schema': 1, 'Name': name, 'Model': geo_loc(model), 'Texture': TEXTURE, 'ModelForward': '-Z',
           'MountAnchor': [0.0, 0.0, 0.0], 'MountAxis': anchor(p.axis), 'LaunchOffset': anchor(p.axis)}
    doc.update(extra)
    return doc


def pod(name, model, capacity, loaded_kg, ammo, category='ROCKET_POD'):
    """a pod firing its own native channel; a rocket pod's rounds are bought when it is fitted (AmmoItem), a gun
    pod's belt is loaded by its channel like the fixed-wing gun pods"""
    extra = {'Category': category, 'Capacity': capacity, 'MassKg': round(loaded_kg / capacity, 4)}
    if ammo:
        extra['AmmoItem'] = ammo
    return modeled(name, model, extra)


# reused fixed-wing munition models and their authored anchors (stores of the same name in aircraft_stores/)
REUSED = {
    'agm114': {'Model': geo_loc('agm114'), 'Texture': f'{NS}:textures/aircraft_stores/agm114.png',
               'ModelForward': '-Z', 'MountAnchor': [0.0, 0.1232, -0.2339], 'MountAxis': [0.0, -0.0213, -0.2339]},
    'falanga': {'Model': geo_loc('falanga'), 'Texture': f'{NS}:textures/aircraft_stores/falanga.png',
                'ModelForward': '+Z', 'MountAnchor': [0, 0.1055, -0.1624], 'MountAxis': [0.0, 0.0, -0.1624]},
    'r60': {'Model': geo_loc('r60'), 'Texture': f'{NS}:textures/aircraft_stores/r60.png', 'ModelForward': '-Z',
            'MountAnchor': [0, 0.0, 0.308], 'MountAxis': [0.0, -0.05, 0.308]},
    'pars3': {'Model': geo_loc('pars3'), 'Texture': f'{NS}:textures/aircraft_stores/pars3.png', 'ModelForward': '+Z',
              'MountAnchor': [0, 0.0849, -0.2282], 'MountAxis': [0.0, 0.0, -0.2282]},
}


def racked(name, round_model, launcher, missile_kg, launcher_kg, extra, reused=None):
    """a launcher (RackAdapter) carrying one store copy per station, released one at a time"""
    L = part(launcher)
    stations = L.stations
    if not stations:     # rocket rounds in a pod's tubes: the tip just ahead of each mouth
        half = part(round_model).length / 2
        stations = [(x, y, z - half + 0.08) for x, y, z in L.muzzles]
    n = len(stations)
    if reused:
        doc = {'Schema': 1, 'Name': name, **copy.deepcopy(REUSED[reused])}
        doc['LaunchOffset'] = [0.0, doc['MountAxis'][1] - doc['MountAnchor'][1], 0.0]
    else:
        p = part(round_model)
        doc = {'Schema': 1, 'Name': name, 'Model': geo_loc(round_model), 'Texture': TEXTURE, 'ModelForward': '-Z',
               'MountAnchor': [0.0, 0.0, 0.0], 'MountAxis': anchor(p.axis), 'LaunchOffset': anchor(p.axis)}
    doc.update({'Capacity': 1, 'MassKg': missile_kg, 'MaxPerPylon': n, 'FixedRackCount': n,
                'RackMassKg': launcher_kg,
                'RackAdapter': {'Model': geo_loc(launcher), 'Texture': TEXTURE, 'MountAnchor': [0.0, 0.0, 0.0],
                                'Stations': [{'Point': [round(c, 4) for c in s], 'Face': 'bottom'}
                                             for s in stations]}})
    doc.update(extra)
    return doc


def laser(profile):
    return {'Category': 'LASER_GUIDED', 'ProjectileProfile': f'{NS}:aircraft_stores/{profile}',
            'LaunchGunProfile': f'{NS}:aircraft_stores/{profile}', 'AmmoItem': 'superbwarfare:small_atgm'}


def saclos(profile, ammo='superbwarfare:small_atgm'):
    return {'Category': 'COMMAND_GUIDED', 'CommandGuidance': {'Mode': 'SACLOS'},
            'ProjectileProfile': f'{NS}:aircraft_stores/{profile}', 'LaunchGunProfile': f'{NS}:aircraft_stores/{profile}',
            'AmmoItem': ammo}


def infrared(range_blocks, cone, lock=40, cm=1.0, tnt=0.45):
    return {'Category': 'AIR_TO_AIR', 'AmmoItem': 'superbwarfare:small_air_to_air_missile', 'TntEquivalentKg': tnt,
            'Guidance': {'Mode': 'INFRARED', 'LockTicks': lock, 'Range': range_blocks, 'ConeDegrees': cone,
                         'CountermeasureVulnerability': cm}}


ROCKET = 'superbwarfare:small_unguided_rocket'
LARGE_ROCKET = 'superbwarfare:medium_unguided_rocket'
SHELL = RIFLE = HMG = None
BOMBS = {'heli_ofab250': ('OFAB-250-270', 'fab_250', 250, 97.0), 'heli_fab500_m54': ('FAB-500M-54', 'fab_500', 478, 201.0),
         'heli_fab500_m62': ('FAB-500M-62', 'fab_500', 508, 213.0)}


def bomb(model):
    title, base, mass, tnt = BOMBS[model]
    ref = load(os.path.join(DATA, 'aircraft_stores', base + '.json'))
    doc = modeled(title, model, {'Category': 'BOMB', 'Capacity': 1, 'MassKg': mass,
                                 'Bomb': copy.deepcopy(ref['Bomb']), 'ProjectileProfile': ref['ProjectileProfile'],
                                 'AmmoItem': ref['AmmoItem']})
    doc['Bomb']['TntEquivalentKg'] = tnt
    return doc


# store id (aircraft_stores/heli/<id>.json) -> (definition, native family or None)
# Native families: rocket and gun pods fire a channel copied from templates.json.
STORES = {
    # 70 mm pods; masses are loaded launcher weights (WT container mass + rounds)
    'm261_m247': (lambda: pod('M261 · 19 × Hydra 70 (M247)', 'heli_m261', 19, 38.6 + 19 * 10.16, ROCKET), 'hydra'),
    'lau131_m247': (lambda: pod('LAU-131 · 7 × Hydra 70 (M247)', 'heli_lau131', 7, 34.0 + 7 * 10.16, ROCKET), 'hydra'),
    'aero7a_ffar': (lambda: pod('Aero 7A · 19 × 2.75 in FFAR', 'heli_aero_7a', 19, 38.0 + 19 * 8.98, ROCKET), 'ffar'),
    'aero6a_ffar': (lambda: pod('Aero 6A · 7 × 2.75 in FFAR', 'heli_aero_6a', 7, 25.0 + 7 * 8.98, ROCKET), 'ffar'),
    'fz225_ffar': (lambda: pod('FZ225 · 19 × 68 mm rockets', 'heli_fz225', 19, 40.0 + 19 * 8.98, ROCKET), 'ffar'),
    'b8v20_s8ko': (lambda: pod('B-8V20A · 20 × S-8KO', 'heli_b8v20', 20, 123.0 + 20 * 11.3, ROCKET), 's8'),
    'ub32_s5k': (lambda: pod('UB-32A-24 · 32 × S-5K', 'heli_ub32', 32, 103.0 + 32 * 5.01, ROCKET), 's5'),
    'ub32_s5kp': (lambda: pod('UB-32A-24 · 32 × S-5KP', 'heli_ub32', 32, 103.0 + 32 * 5.01, ROCKET), 's5'),
    'b13l_s13of': (lambda: pod('B-13L · 5 × S-13OF', 'heli_b13l', 5, 160.0 + 5 * 69.0, LARGE_ROCKET), 's13'),
    's24b': (lambda: pod('S-24B · APU-68 rail', 'heli_s24', 1, 235.0 + 35.0, LARGE_ROCKET), 's24'),
    # gun pods (Capacity = rounds carried, WT belts)
    'xm18_m134': (lambda: pod('XM18 · 7.62 mm M134 (1500 rds)', 'heli_xm18', 1500, 148.0, RIFLE, 'GUN_POD'), 'm134'),
    'm134d': (lambda: pod('M134D · 7.62 mm (6000 rds)', 'heli_m134d', 6000, 160.0, RIFLE, 'GUN_POD'), 'm134'),
    'xm35_m195': (lambda: pod('XM35 · 20 mm M195 (950 rds)', 'heli_xm35', 950, 240.0, SHELL, 'GUN_POD'), 'm195'),
    'gau19': (lambda: pod('GAU-19/B · 12.7 mm (1200 rds)', 'heli_gau19', 1200, 210.0, HMG, 'GUN_POD'), 'gau19'),
    'hmp400': (lambda: pod('HMP400 · 12.7 mm M3P (400 rds)', 'heli_hmp400', 400, 140.0, HMG, 'GUN_POD'), 'm3p'),
    'rmp_lc': (lambda: pod('RMP-LC · 12.7 mm M3P + 3 × 70 mm', 'heli_rmp_lc', 250, 160.0, HMG, 'GUN_POD'), 'rmp'),
    'upk23': (lambda: pod('UPK-23-250 · GSh-23L (250 rds)', 'heli_upk23', 250, 218.0, SHELL, 'GUN_POD'), 'gsh23'),
    'guv8700': (lambda: pod('GUV-8700 · YaK-B 12.7 mm + 2 × GShG 7.62 mm', 'heli_guv8700', 750, 452.0, HMG,
                            'GUN_POD'), 'guv'),
    # bombs
    'ofab250': (lambda: bomb('heli_ofab250'), None),
    'fab500_m54': (lambda: bomb('heli_fab500_m54'), None),
    'fab500_m62': (lambda: bomb('heli_fab500_m62'), None),
    # laser
    'm272_agm114k': (lambda: racked('M272 · 4 × AGM-114K Hellfire II', None, 'heli_m272', 45.1, 64.0,
                                    laser('agm114k_ah64d'), 'agm114'), None),
    'm279_agm114k': (lambda: racked('M279 · 2 × AGM-114K Hellfire II', None, 'heli_m279', 45.1, 40.0,
                                    laser('agm114k_ah64d'), 'agm114'), None),
    'm279_agm114b': (lambda: racked('M279 · 2 × AGM-114B Hellfire', None, 'heli_m279', 45.1, 40.0,
                                    laser('agm114b'), 'agm114'), None),
    'lau131_apkws_m151': (lambda: racked('LAU-131 · 7 × APKWS II (M151)', 'heli_apkws', 'heli_lau131', 15.8, 34.0,
                                         laser('apkws_m151')), None),
    'lau131_apkws_m282': (lambda: racked('LAU-131 · 7 × APKWS II (M282)', 'heli_apkws', 'heli_lau131', 18.1, 34.0,
                                         laser('apkws_m282')), None),
    # command (SACLOS)
    'tow_x2': (lambda: racked('M65 · 2 × BGM-71 TOW', 'heli_tow', 'heli_tow_x2', 19.1, 30.0, saclos('bgm71_tow')), None),
    'tow_x4': (lambda: racked('M65 · 4 × BGM-71 TOW', 'heli_tow', 'heli_tow_x4', 19.1, 55.0, saclos('bgm71_tow')), None),
    'tow2_x2': (lambda: racked('M65 · 2 × BGM-71C TOW-2', 'heli_tow2', 'heli_tow_x2', 21.5, 30.0, saclos('bgm71_tow2')),
                None),
    'tow2_x4': (lambda: racked('M65 · 4 × BGM-71C TOW-2', 'heli_tow2', 'heli_tow_x4', 21.5, 55.0, saclos('bgm71_tow2')),
                None),
    'hot3_x2': (lambda: racked('HOT 3 · 2 tubes', 'heli_hot3', 'heli_hot_x2', 23.6, 30.0, saclos('hot3_uht')), None),
    'hot3_x4': (lambda: racked('HOT 3 · 4 tubes', 'heli_hot3', 'heli_hot_x4', 23.6, 55.0, saclos('hot3_uht')), None),
    'shturm_x2': (lambda: racked('9M114 Shturm · 2 tubes', 'heli_shturm', 'heli_shturm_x2', 31.4, 40.0,
                                 saclos('shturm_9m114')), None),
    'ataka_x4': (lambda: racked('9M120M Ataka · 4 tubes', 'heli_ataka', 'heli_ataka_x4', 33.5, 60.0,
                                saclos('ataka_9m120m')), None),
    'ataka_x8': (lambda: racked('9M120M Ataka · 8 tubes', 'heli_ataka', 'heli_ataka_x8', 33.5, 110.0,
                                saclos('ataka_9m120m')), None),
    'vikhr_x6': (lambda: racked('APU-6 · 6 × 9M127 Vikhr', 'heli_vikhr', 'heli_vikhr_x6', 47.0, 90.0,
                                saclos('vikhr_9m127')), None),
    'falanga_x2': (lambda: racked('9M17M Falanga · 2 rails', None, 'heli_falanga_x2', 31.5, 25.0,
                                  saclos('falanga_9m17m', 'superbwarfare:large_atgm'), 'falanga'), None),
    # infrared fire-and-forget (Trigat LR: WT 'optical' guidance, the existing PARS 3 LR behaviour)
    'trigat_x2': (lambda: racked('PARS 3 LR (Trigat) · 2 rails', None, 'heli_trigat_x2', 49.0, 40.0,
                                 trigat(), 'pars3'), None),
    'trigat_x4': (lambda: racked('PARS 3 LR (Trigat) · 4 rails', None, 'heli_trigat_x4', 49.0, 70.0,
                                 trigat(), 'pars3'), None),
    # air-to-air
    'atas_stinger': (lambda: racked('ATAS · 2 × FIM-92 Stinger', 'heli_stinger', 'heli_atas', 10.1, 18.0,
                                    infrared(1000, 25)), None),
    'atas_stinger_tiger': (lambda: racked('ATAS · 2 × FIM-92 Stinger', 'heli_stinger', 'heli_atas_tiger', 10.1, 18.0,
                                          infrared(1000, 25)), None),
    'igla_x2': (lambda: racked('9M39 Igla-V · 2 tubes', 'heli_igla', 'heli_igla_x2', 10.6, 20.0,
                               infrared(800, 25, tnt=0.4)), None),
    'igla_x4': (lambda: racked('9M39 Igla-V · 4 tubes', 'heli_igla', 'heli_igla_x4', 10.6, 38.0,
                               infrared(800, 25, tnt=0.4)), None),
    'r60_x2': (lambda: racked('APU-60-2 · 2 × R-60', None, 'heli_apu60_x2', 43.5, 30.0, r60(False), 'r60'), None),
    'r60m_x2': (lambda: racked('APU-60-2 · 2 × R-60M', None, 'heli_apu60_x2', 44.0, 30.0, r60(True), 'r60'), None),
    # countermeasures fairing (no weapon)
    'ltc': (lambda: modeled('Wingtip countermeasure dispenser', 'heli_ltc', {'Category': 'VISUAL_ONLY', 'MassKg': 20.0}),
            None),
}


def trigat():
    ref = load(os.path.join(DATA, 'aircraft_stores', 'munition', 'pars3_lr.json'))
    return {k: copy.deepcopy(ref[k]) for k in ('Category', 'Guidance', 'Flight', 'AmmoItem') if k in ref}


def r60(m):
    ref = load(os.path.join(DATA, 'aircraft_stores', 'r60.json'))
    out = {k: copy.deepcopy(ref[k]) for k in ('Category', 'Guidance', 'AmmoItem', 'TntEquivalentKg')}
    if m:
        out['TntEquivalentKg'] = 1.35
    return out


# reused single-copy stores: R-60 / R-60M on APU-60-1 (the existing fixed-wing R-60 store)
EXISTING = {'r60_x1': f'{NS}:r60'}


def store_id(key):
    return EXISTING.get(key) or f'{NS}:heli/{key}'


def write_stores():
    for key, (fn, _) in STORES.items():
        write_json(os.path.join(DATA, 'aircraft_stores', 'heli', key + '.json'), fn())


# ------------------------------------------------------------------------------------------------ profiles
def scaled_speed(mps):
    """the store profiles' speed scale: falanga 230 m/s -> 4, Hellfire 475 m/s -> 8 blocks/tick"""
    return round(mps / 58.0, 2)


def profile(name, title, weapon_id, round_id, calibre_mm, pen, tandem, speed_mps, turn, hull, model, wire,
            sound=None, tnt=None, damage=None, base='falanga_9m17m', propulsion=None):
    g = load(os.path.join(DATA, 'guns', 'aircraft_stores', base + '.json'))
    p = load(os.path.join(DATA, 'projectile_profiles', 'aircraft_stores', base + '.json'))
    g['Name'] = title
    g['Projectile']['Profile'] = f'{NS}:aircraft_stores/{name}'
    if damage:
        g['Damage'] = g['ExplosionDamage'] = damage
    if tnt:
        g['TntEquivalentKg'] = tnt
    if sound:
        g['SoundInfo'] = sound
    c = p['Combat']
    c.update({'WeaponId': f'{NS}:aircraft_stores/{name}', 'RoundId': round_id, 'CaliberMm': calibre_mm,
              'DiameterMm': calibre_mm, 'PenetrationMm': pen, 'Tandem': tandem, 'HullDamage': hull,
              'ModuleDamage': round(hull * 0.6), 'AmmoRackDamage': round(hull * 4.6)})
    p['Collision'] = {'Width': round(calibre_mm / 1000, 3), 'Height': round(calibre_mm / 1000, 3)}
    gp = p['GuidedPropulsion']
    gp['MaxSpeed'] = scaled_speed(speed_mps)
    gp['MaxTurnRateDegreesPerSecond'] = turn
    if propulsion:
        gp.update(propulsion)
    mesh = p['Extensions']['berts_vehicle_pack:projectile_mesh_v1']
    mesh.update({'Model': geo_loc(model + '_flight') if model.startswith('heli_') else mesh['Model'],
                 'Texture': TEXTURE if model.startswith('heli_') else mesh['Texture'], 'ForwardYaw': 0,
                 'ForwardAxis': 'Y', 'Origin': 'TAIL'})
    if wire:
        hot = load(os.path.join(DATA, 'projectile_profiles', 'aircraft_stores', 'hot3_uht.json'))
        p['Extensions']['berts_vehicle_pack:physical_wire_v1'] = hot['Extensions']['berts_vehicle_pack:physical_wire_v1']
    else:
        p['Extensions'].pop('berts_vehicle_pack:physical_wire_v1', None)
    write_json(os.path.join(DATA, 'guns', 'aircraft_stores', name + '.json'), g)
    write_json(os.path.join(DATA, 'projectile_profiles', 'aircraft_stores', name + '.json'), p)


RU_SOUND = {'Fire1P': 'bvp_audio:weapon/atgm_ru_1p', 'Fire3P': 'bvp_audio:weapon/atgm_ru_3p',
            'Fire3PFar': 'bvp_audio:weapon/atgm_ru_far', 'Fire3PVeryFar': 'bvp_audio:weapon/atgm_ru_veryfar',
            'Volume': 0.9}


def native_propulsion(path):
    gp = load(os.path.join(DATA, 'projectile_profiles', path + '.json'))['GuidedPropulsion']
    return {k: gp[k] for k in ('InitialSpeed', 'MaxSpeed', 'AccelerationPerTick', 'ThrustDurationTicks')}


def write_profiles():
    # WT datamine: TOW 127 mm 296 m/s 630 mm; TOW-2 152 mm 329 m/s 800 mm; HOT 3 150 mm 250 m/s 1250 mm
    profile('bgm71_tow', 'BGM-71 TOW', f'{NS}:bgm71_tow', f'{NS}:bgm71_tow', 127, 630, False, 296, 45, 90,
            'heli_tow', True, tnt=2.04 * 1.2, damage=560)
    profile('bgm71_tow2', 'BGM-71C TOW-2', f'{NS}:bgm71_tow2', f'{NS}:bgm71_tow2', 152, 800, False, 329, 45, 100,
            'heli_tow2', True, tnt=3.13 * 1.2, damage=640)
    profile('hot3_uht', 'HOT 3', f'{NS}:hot3', f'{NS}:hot3', 150, 1250, True, 250, 45, 110, 'heli_hot3', True,
            tnt=4.06 * 1.2, damage=640)
    # Shturm, Ataka and Vikhr keep their native helicopter flight figures and warheads (the retired native channels)
    profile('shturm_9m114', '9M114 Shturm', f'{NS}:9m114_shturm', f'{NS}:9m114_shturm', 130, 560, False, 550, 40, 85,
            'heli_shturm', False, RU_SOUND, 2.47 * 1.2, 600, propulsion=native_propulsion('mi24v/passengermissile/default'))
    profile('ataka_9m120m', '9M120M Ataka', f'{NS}:9m120_ataka', f'{NS}:9m120_ataka', 130, 800, True, 550, 40, 100,
            'heli_ataka', False, RU_SOUND, 2.82 * 1.2, 640, propulsion=native_propulsion('mi28n/passengermissile/default'))
    profile('vikhr_9m127', '9M127 Vikhr', f'{NS}:9k127_vikhr', f'{NS}:9k127_vikhr', 130, 800, True, 600, 40, 100,
            'heli_vikhr', False, RU_SOUND, 4.3 * 1.2, 680, propulsion=native_propulsion('ka50/pilotmissile/default'))
    # laser rounds: Hellfire B (1100 mm), APKWS (HE, WT 455 / 395 m/s, 11 km)
    profile('agm114b', 'AGM-114B Hellfire', f'{NS}:agm114b', f'{NS}:agm114b', 178, 1100, False, 475, 90, 120,
            'agm114', False, base='agm114k_ah64d')
    profile('apkws_m151', 'APKWS II (M151 HE)', f'{NS}:apkws_m151', f'{NS}:apkws_m151', 70, 50, False, 455, 90, 60,
            'heli_apkws', False, tnt=1.04 * 1.2, damage=200, base='agm114k_ah64d')
    profile('apkws_m282', 'APKWS II (M282 multi-purpose)', f'{NS}:apkws_m282', f'{NS}:apkws_m282', 70, 150, False, 395,
            90, 70, 'heli_apkws', False, tnt=0.44 * 1.2, damage=260, base='agm114k_ah64d')


# ------------------------------------------------------------------------------------------------ helicopters
FAMILY_NAMES = {'hydra': 'Hydra', 'ffar': 'Ffar', 's8': 'S8', 's5': 'S5', 's13': 'S13', 's24': 'S24', 'm134': 'M134',
                'm195': 'M195', 'gau19': 'Gau19', 'm3p': 'M3P', 'rmp': 'Rmp', 'gsh23': 'Gsh23', 'guv': 'Guv'}
GUN_TEMPLATES = {'m134': ('m134', 6000, None), 'm195': ('m195', 950, None), 'gau19': ('yakb', 1200, '12.7 mm GAU-19/B'),
                 'm3p': ('yakb', 400, '12.7 mm FN M3P'), 'rmp': ('yakb', 250, '12.7 mm FN M3P'),
                 'gsh23': ('gsh23', 250, '23 mm GSh-23L (UPK-23-250)'), 'guv': ('yakb', 750, '12.7 mm YaK-B (GUV)')}
ROCKET_TEMPLATES = {'hydra': 'hydra', 'ffar': 'ffar', 's8': 's8', 's5': 's5', 's13': 's13', 's24': 's13'}


def side(x):
    return 'left' if x >= 0 else 'right'


def channel(vid, mount, key, family):
    """(name, weapon, attachments) of the native channel of pod `key` on `mount`"""
    p = part(STORES[key][0]()['Model'].rsplit('/', 1)[1].replace('.geo.json', ''))
    name = f"{FAMILY_NAMES[family]}{mount['Id'].replace('slot_', 'S')}{'' if key in ('m261_m247', 'b8v20_s8ko') else key.split('_')[0].capitalize()}"
    pos = np.array(mount['Position'], float)
    muzzles = [pos + np.array(m) for m in p.muzzles]
    if family in ROCKET_TEMPLATES:
        w = copy.deepcopy(TEMPLATES[ROCKET_TEMPLATES[family]]['weapon'])
        w['Magazine'] = len(muzzles)
        if family == 's24':
            w['Name'] = 'S-24B rocket'
            w['Damage'] = 500; w['ExplosionRadius'] = 10; w['TntEquivalentKg'] = 25.5 * 1.2
    else:
        t, rounds, title = GUN_TEMPLATES[family]
        w = copy.deepcopy(TEMPLATES[t]['weapon'])
        w['Magazine'] = rounds
        if title:
            w['Name'] = title
        if family == 'rmp':
            muzzles = muzzles[:1]
    s = side(pos[0])
    att = {f'{name}_{s}_{k}': {'Parent': 'Vehicle', 'Position': [round(float(c), 4) for c in m],
                               'Direction': [0, 0, 1]} for k, m in enumerate(muzzles)}
    w['ShootPos'] = {'Transform': 'Vehicle', 'ShootDirectionForHud': [0, 0, 1],
                     'Positions': [[round(float(c), 4) for c in m] for m in muzzles],
                     'Directions': [[0, 0, 1] for _ in muzzles], 'MuzzleAttachments': list(att),
                     'MuzzleDirectionAttachments': ['Default'], 'EffectAttachments': list(att),
                     'EffectDirectionAttachments': ['Default']}
    return name, w, att


def build(vid, spec):
    arm_path = os.path.join(DATA, 'aircraft_armaments', vid + '.json')
    veh_path = os.path.join(DATA, 'vehicles', vid + '.json')
    arm = load(arm_path); veh = load(veh_path)
    # the earlier suspended channels (the baked pods' and a previous build's) are replaced by the slot channels
    retire = set(spec.get('retire', [])) | set(arm.get('SuspendedWeapons', []))
    # retired channels: weapons, their attachments and every seat list
    for w in retire:
        veh['Weapons'].pop(w, None)
        for a in [a for a in veh.get('Attachments', {}) if a.startswith(w + '_')]:
            veh['Attachments'].pop(a)
    singles, channels = [], []
    for slot in spec['slots']:
        mount = {'Id': slot['id'], 'Name': slot['name'], 'Position': [round(c, 4) for c in slot['pos']],
                 'AllowedStores': [store_id(k) for k in slot['stores']],
                 'MaxPylonMassKg': slot.get('max_kg', spec.get('pylon_kg', 1000)), 'MaxWeaponsPerPylon': 12}
        natives = {}
        for key in slot['stores']:
            family = STORES[key][1] if key in STORES else None
            if family is None:
                continue
            name, w, att = channel(vid, mount, key, family)
            veh['Weapons'][name] = w
            veh.setdefault('Attachments', {}).update(att)
            ids = [name]
            if family == 'rmp':          # the pod's three rockets on their own channel
                rname, rw, ratt = channel(vid, mount, key, 'ffar')
                rname = name + 'Rockets'
                rw['ShootPos']['MuzzleAttachments'] = rw['ShootPos']['EffectAttachments'] = [
                    a.replace(rw['ShootPos']['MuzzleAttachments'][0].rsplit('_', 2)[0], rname) for a in ratt][1:]
                pos = rw['ShootPos']['Positions'][1:]
                rw['ShootPos']['Positions'] = pos; rw['ShootPos']['Directions'] = [[0, 0, 1]] * len(pos)
                rw['Magazine'] = len(pos)
                veh['Weapons'][rname] = rw
                veh['Attachments'].update({rname + '_' + a.split('_', 1)[1]: v for a, v in list(ratt.items())[1:]})
                ids.append(rname)
            natives[store_id(key)] = ids if len(ids) > 1 else ids[0]
            channels += ids
        if natives:
            mount['NativeWeaponIds'] = natives
        singles.append(mount)
    pilot = veh['Seats'][0]
    for i, seat in enumerate(veh['Seats']):
        seat['Weapons'] = [w for w in seat.get('Weapons', []) if w not in retire and w in veh['Weapons']
                           or w.startswith('@')]
        if 'FixedGunBank' in seat:
            seat['FixedGunBank'] = [w for w in seat['FixedGunBank'] if w in veh['Weapons']]
    pilot['Weapons'] += [c for c in channels if c not in pilot['Weapons']]
    builtin = [w for w in arm.get('BuiltInWeapons', []) if w not in retire and w in veh['Weapons']]
    doc = {'Schema': 1, 'Name': arm['Name'], 'BuiltInWeapons': builtin, 'SuspendedWeapons': channels,
           'Pairs': [], 'Singles': singles, 'MaxPayloadKg': spec['payload'], 'MaxPylonMassKg': spec.get('pylon_kg', 1000),
           'MaxWeaponsPerPylon': 12, 'WeaponSeat': spec['weapon_seat']}
    if 'Radar' in arm:
        doc['Radar'] = arm['Radar']
    write_json(arm_path, doc)
    write_json(veh_path, veh)
    strip_baked(vid, spec.get('hull_remove', []))


def strip_baked(vid, boxes=()):
    """removes the baked store bones (suspended_*) and the connected hull parts whose centres fall in one of
    `boxes` ([x0, x1, y0, y1, z0, z1] vehicle-local; |x| is matched so a box covers both sides)"""
    sys.path.insert(0, os.path.join(HERE, '..', 'heli_stores'))
    from comps import bone_parts
    path = os.path.join(ASSETS, 'custom_geo', vid + '.geo.json')
    geo = load(path)
    bones = geo['minecraft:geometry'][0]['bones']
    keep = [b for b in bones if not b['name'].startswith('suspended_')]
    changed = len(keep) != len(bones)
    if boxes:
        hull, parts = bone_parts(geo, 'hull')
        drop = set()
        for q in parts:
            c = (q['lo'] + q['hi']) / 2
            if any(b[0] <= abs(c[0]) <= b[1] and b[2] <= c[1] <= b[3] and b[4] <= c[2] <= b[5] for b in boxes):
                drop.update(q['polys'])
        if drop:
            hull['poly_mesh']['polys'] = [p for i, p in enumerate(hull['poly_mesh']['polys']) if i not in drop]
            changed = True
    if changed:
        geo['minecraft:geometry'][0]['bones'] = keep
        raw = open(path, encoding='utf-8').read()
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(geo, f, indent=None if '\n' not in raw.strip() else 2, separators=(',', ':') if '\n' not in raw.strip() else None)
            if raw.endswith('\n'):
                f.write('\n')


def main(argv):
    from helis import HELIS
    tex = None
    if '--texture' in argv:
        tex = argv[argv.index('--texture') + 1]
        argv = [a for a in argv if a not in ('--texture', tex)]
    write_models(); write_stores(); write_profiles()
    if tex:
        os.makedirs(os.path.dirname(tex), exist_ok=True)
        meshlib.palette_image().save(tex)
    for vid in argv or HELIS:
        build(vid, HELIS[vid])
        print('built', vid)


if __name__ == '__main__':
    main(sys.argv[1:])
