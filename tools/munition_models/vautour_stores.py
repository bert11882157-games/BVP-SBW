#!/usr/bin/env python3
"""Store models and store definitions for the Vautour IIN (War Thunder so_4050_vautour_2n / 2n_late loads), after mirage3e_stores.py / yak38_stores.py.

Built here, in the pack's aircraft-store format (nose -Z, one bone 'store', 1 block = 1 m). Generated geometry, so each display name ends in
" (AI)" (owner rule 2026-10-23). War Thunder gamedata/weapons (gszabi99 datamine, checked 2026-10-23, files in work/elite_expansion_2/vautour/wt):
  aa20        Nord AA.20 (5103) air-to-air: fr_aa20 133 kg, 3.12 m x 0.25 m, 8 kg Comp B, radio command (operated range 8 km), 402 m/s, motor 1.8 s.
              Manual radio command (MCLOS, like the pack's AS.30 / AGM-12C), the only way the pack steers a missile by hand. Wing layout GUESS.
  as20        Nord AS.20 air-to-ground: fr_as20 143.8 kg, 3.12 m x 0.25 m, 12.1 kg Comp B, radio command, 375 m/s.
  r511        Matra R.511 semi-active radar: fr_r_511_matra 172.5 kg, 3.28 m x 0.263 m, 8 kg PBXN-3, radar seeker, range 8 / 10 km (the R.530's is
              18 / 20 km and 3,000 in the pack). Wing layout GUESS.
  matra116    Matra Type 116 pod, 19 x SNEB 68 mm (matra_116: pod 34.05 kg + 19 x 5.05 kg = 130 kg). Pod size 1.50 m x 0.41 m GUESS (no figure in WT).
  t10_140 / t10_151  six T10 rockets directly on the pylon (fr_matra_t_10_140a/b HE, 28 kg, 1.69 m x 0.12 m, 2.52 kg TNT; 151 HEAT, 1.615 kg RDX/TNT
              with a shaped charge), shown as a six-tube pod (GUESS: WT models no pod mass; 168 kg are the rockets).
  matra104    ventral rocket pack in the bomb bay: 112 x SNEB 68 mm (WT 104_inter, 565.6 kg). Tray 2.6 m x 0.70 m x 0.30 m GUESS; internal, never seen
              hanging, shown in the loadout list only.
  samp25 / samp21  single SAMP Type 25 250 kg and Type 21 400 kg (the pack's models and numbers from mirage3e_stores.py, without the PM3 rack).
Stats are the pack's scale (AS.30, R.530, FZ49 / SNEB as anchors) and a GUESS where marked.

Usage: python tools/munition_models/vautour_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo files>   (and --check)
"""
import json
import math
import os
import sys
from collections import OrderedDict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import f16c_stores as F  # noqa: E402
import generate  # noqa: E402
from f16c_stores import Mesh, revolve, disc, lugs, centred  # noqa: E402

NS, GEO, TEX, SBW, STORES = F.NS, F.GEO, F.TEX, F.SBW, F.STORES
OD = OrderedDict
DOT = '·'
NEW_GEO = []
NEW_GEO_FILES = ['aa20', 'aa20_flight', 'as20', 'as20_flight', 'r511', 'r511_flight', 'matra116', 't10_pod', 'matra104']


def load(p):
    return json.load(open(p, encoding='utf-8'), object_pairs_hook=OD)


def build_missile(name, spec):
    mesh, Lpx, rpx = generate.build(spec)
    F.write_json_compact(os.path.join(GEO, name + '.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}', False, Lpx))
    F.write_json_compact(os.path.join(GEO, name + '_flight.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}_flight', True, Lpx))
    generate.texture().save(os.path.join(TEX, name + '.png'))
    F.mark_generated(name, name + '_flight')
    NEW_GEO.extend([name, name + '_flight'])


def build_pod_at(L, d, points, tube_r, body='olive'):
    """Rocket pod: tube barrel with a frangible nose cone and a tail fairing, tube mouths drawn aft at the given (x, y) points, two lugs."""
    r = d / 2
    m = Mesh()
    nose, tail = 0.20, 0.22
    prof = [(0.0, 0.55 * r), (nose, r), (L - tail, r), (L, 0.82 * r)]

    def col(s):
        return 'dgrey' if (s < nose or s > L - tail) else body
    revolve(m, prof, col, cuts=[nose, L - tail], caps=('grey', 'grey'))
    for (px, py) in points:
        disc(m, px, py, L + 0.001, tube_r, 'black', +1)
    lugs(m, L * 0.5, 0.356, r)
    return centred(m, L), L


def build_tray():
    """Matra 104 bay pack: a flat tray with the tube mouths drawn on the front face."""
    L, w, h = 2.6, 0.70, 0.30
    m = Mesh()
    m.box(-w / 2, w / 2, -h, 0.0, 0.0, L, 'dgrey')
    m.box(-w / 2 + 0.03, w / 2 - 0.03, -h - 0.02, -h + 0.02, 0.05, L - 0.05, 'olive')
    lugs(m, L * 0.5, 0.356, 0.0)
    return centred(m, L), L


def save(name, mc):
    m, L = mc
    F.save_model(name, m)
    NEW_GEO.append(name)
    return L


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()
    prof_dir = os.path.join(SBW, 'projectile_profiles')
    gun_dir = os.path.join(SBW, 'guns/aircraft_stores')

    # ============================================================== AA.20 / AS.20 (manual radio command, like the AS.30)
    def command_missile(name, label, mass, tnt, speed, life_ticks, spec):
        build_missile(name, spec)
        prof = load(os.path.join(prof_dir, 'aircraft_stores/agm12c.json'))
        c = prof['Combat']
        c['WeaponId'] = f'{NS}:aircraft_stores/{name}'
        c['RoundId'] = f'{NS}:{name}'
        c['CaliberMm'] = 250
        c['DiameterMm'] = 250
        c['HullDamage'] = round(1330 * tnt / 101.0)
        c['ModuleDamage'] = round(800 * tnt / 101.0)
        c['AmmoRackDamage'] = max(20, round(150 * tnt / 101.0))      # GUESS: the AS.30 values (101 kg TNT) scaled by the TNT equivalent
        c['PenetrationMm'] = 250.0                                   # GUESS
        prof['Collision'] = OD([('Width', 0.25), ('Height', 0.25)])
        gp = prof['GuidedPropulsion']
        gp['MaxSpeed'] = speed
        gp['AccelerationPerTick'] = 0.15
        gp['ThrustDurationTicks'] = 200                              # GUESS: sustained like the AS.30 (WT motor time 1.8 s is the boost only)
        gp['MaxTurnRateDegreesPerSecond'] = 35                       # GUESS
        mesh_ext = prof['Extensions'][f'{NS}:projectile_mesh_v1']
        mesh_ext['Model'] = f'{NS}:custom_geo/aircraft_stores/{name}_flight.geo.json'
        mesh_ext['Texture'] = f'{NS}:textures/aircraft_stores/{name}.png'
        blast = prof['Extensions']['superbwarfare:heavy_warhead_blast_v1']
        blast['InnerRadius'] = 1.5
        blast['OuterRadius'] = 4
        F.write_json(os.path.join(prof_dir, f'aircraft_stores/{name}.json'), prof)
        gun = load(os.path.join(gun_dir, 'agm12c.json'))
        gun['Name'] = F.ai_name(f'{label} {DOT} manual radio command')
        gun['Projectile']['Profile'] = f'{NS}:aircraft_stores/{name}'
        gun['ProjectileLife'] = life_ticks                           # WT operatedDist 8,000 m against the AS.30's 10,000 m = 700
        gun['Damage'] = c['HullDamage']
        gun['ExplosionDamage'] = c['HullDamage']
        gun['ExplosionRadius'] = 3
        gun['TntEquivalentKg'] = tnt
        F.write_json(os.path.join(gun_dir, f'{name}.json'), gun)
        a, launch, g = F.anchors_for(name, missile=True)
        cat.add(f'munition/{name}', F.store(f'{label} {DOT} manual command', 'COMMAND_GUIDED', name, a, Capacity=1, MassKg=mass,
                                           CommandGuidance=OD([('Mode', 'MCLOS')]), ProjectileProfile=f'{NS}:aircraft_stores/{name}',
                                           LaunchGunProfile=f'{NS}:aircraft_stores/{name}', LaunchOffset=launch,
                                           AmmoItem='superbwarfare:medium_air_to_ground_missile'))
        print(name, 'anchors', a['MountAnchor'])

    # layout read off nothing (no baked copy in the model): four cruciform wings behind the middle, four small tail fins, 0.80 m span (GUESS)
    common = dict(length=3.12, diameter=0.25, nose=0.60, boattail=0.12, tail_r=0.80,
                  wings=[dict(root=(1.75, 2.35), tip=(1.98, 2.35), span=0.80 / 2, roll=45)],
                  fins=[dict(root=(2.62, 2.96), tip=(2.72, 2.96), span=0.46 / 2, roll=45)])
    command_missile('aa20', 'Nord AA.20', 133.0, 10.6, 8.7, 560, dict(common, bands=[(0.35, 0.37, 'yellow')], radome=0.30))      # 8.0 kg Comp B x 1.33
    command_missile('as20', 'Nord AS.20', 143.8, 16.1, 8.1, 560, dict(common, bands=[(0.55, 0.57, 'yellow'), (0.62, 0.64, 'yellow')], radome=0.0))  # 12.1 kg x 1.33

    # ============================================================== R.511 semi-active radar
    L = 3.28
    build_missile('r511', dict(length=L, diameter=0.263, nose=0.80, boattail=0.10, tail_r=0.85, bands=[(0.30, 0.31, 'yellow'), (0.62, 0.63, 'yellow')],
                               radome=0.55,
                               wings=[dict(root=(1.95, 2.75), tip=(2.30, 2.75), span=0.70 / 2, roll=45)],
                               fins=[dict(root=(0.95, 1.20), tip=(1.08, 1.20), span=0.46 / 2, roll=45)]))
    a, launch, g = F.anchors_for('r511', missile=True)
    guid = OD([('Mode', 'SEMI_ACTIVE_RADAR'), ('LockTicks', 0), ('Range', 1350), ('ConeDegrees', 20), ('CountermeasureVulnerability', 0.9)])
    flight = OD([('InitialSpeed', 0.5), ('MaxSpeed', 5), ('AccelerationPerTick', 0.08), ('TurnDegreesPerSecond', 45), ('Damage', 40),
                 ('BlastRadius', 4.1), ('TntEquivalentKg', 13.0)])
    cat.add('munition/r511', F.store(f'Matra R.511 {DOT} semi-active radar', 'AIR_TO_AIR', 'r511', a, Capacity=1, MassKg=172.5,
                                     Guidance=guid, Flight=flight, AmmoItem='superbwarfare:medium_air_to_air_missile', MaxPerPylon=1,
                                     LaunchOffset=launch))
    print('r511 anchors', a['MountAnchor'])

    # ============================================================== rocket pods (unguided: the aircraft's native rocket channel fires them)
    def pod(rel, name, model, n, kg_per_round):
        a, launch, g = F.anchors_for(model)
        cat.add(rel, F.store(name, 'ROCKET_POD', model, a, Capacity=n, Scale=1, MassKg=round(kg_per_round, 2),
                             AmmoItem='superbwarfare:small_unguided_rocket', LaunchOffset=launch))

    pts19 = [(px * 0.085, py * 0.085) for px, py in F.tubes(19)]
    save('matra116', build_pod_at(1.50, 0.41, pts19, 0.034))
    pod('munition/matra116', f'Matra Type 116 {DOT} 19 x SNEB 68 mm', 'matra116', 19, 130.0 / 19)
    pts6 = [(x * 0.115, y * 0.115) for x in (-1, 0, 1) for y in (-0.5, 0.5)]
    save('t10_pod', build_pod_at(1.75, 0.40, pts6, 0.050))
    pod('munition/t10_140', f'Matra T10 140 {DOT} 6 x 120 mm HE', 't10_pod', 6, 28.0)
    pod('munition/t10_151', f'Matra T10 151 {DOT} 6 x 120 mm HEAT', 't10_pod', 6, 28.0)
    save('matra104', build_tray())
    pod('munition/matra104', f'Matra 104 bay pack {DOT} 112 x SNEB 68 mm', 'matra104', 112, 565.6 / 112)

    # ============================================================== single SAMP bombs (the pack's models, no rack)
    for rel, base, name in (('munition/samp25', 'munition/samp25_x2', 'SAMP Type 25 250 kg'),
                            ('munition/samp21', 'munition/samp21_x2', 'SAMP Type 21 400 kg')):
        d = load(os.path.join(STORES, base + '.json'))
        d['Name'] = name
        for k in ('RackAdapter', 'FixedRackCount', 'RackSpacing'):
            d.pop(k, None)
        d['MaxPerPylon'] = 1
        cat.add(rel, d)
    print('written; new geo files:', ' '.join(NEW_GEO))


if __name__ == '__main__':
    main(sys.argv[1:])
