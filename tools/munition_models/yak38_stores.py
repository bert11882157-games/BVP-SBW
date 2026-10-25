#!/usr/bin/env python3
"""Store models and store definitions for the Yak-38M (War Thunder yak-38m weapons), after su15_stores.py / mirage3e_stores.py.

Built here, in the pack's aircraft-store format (nose -Z, one bone 'store', 1 block = 1 m). Generated geometry, so each display name
ends in " (AI)" (owner rule 2026-10-23). War Thunder gamedata/weapons (gszabi99 datamine, checked 2026-10-23):
  kh23m       Kh-23M (AS-7 Kerry): su_kh_23m 289 kg, 3.49 m x 0.275 m, explosive tg_40 75 kg (x 1.35 = 101 kg TNT, taken as the AS.30's), operated
              range 10 km, flight 450 m/s; radio command (MCLOS, like the pack's AS.30 / AGM-12C). Layout read off the Yak-38M model's baked
              Kh-23M: four small fins at about 30 % of the length and four clipped-delta wings in the X layout at 62-82 % (wing span 0.78 m, Wikipedia).
  zb500       ZB-500 incendiary tank: su_zb_500 374 kg, 2.86 m x 0.5 m, 250 kg napalm; rounded nose, parallel body, short tail cone and four small fins.
              Fire effect scaled from the pack's BLU-27 (401 kg, 70 kg TNT equivalent).
  s24b        S-24B 240 mm rocket on its APU-68 rail: su_s_24b_rocket 235 kg, 2.33 m x 0.24 m, 25.5 kg TNT, 410 m/s. Fired by a native rocket weapon.
  ub16_57     UB-16-57U rocket pod, 16 x S-5KP (WT ub_16_57_su_s5kp, 132.2 kg loaded): about 2.14 m x 0.29 m (GUESS: no figure found).
  ub32a_24    UB-32A-24 rocket pod, 32 x S-5KP (WT ub_32a_24_su_s5kp, 269.3 kg loaded): about 2.0 m x 0.40 m (GUESS).
  sensor/delta_ng  Delta-NG radio-command station pod (WT su_delta_ng_station, 20 kg): 1.8 m x 0.22 m is an ESTIMATE; VISUAL_ONLY (the pack's
              MCLOS guidance needs no pod).
Stats are the pack's scale (AS.30 / Hydra anchors) and a GUESS where marked.

Usage: python tools/munition_models/yak38_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo files>
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

NS, GEO, TEX, SBW = F.NS, F.GEO, F.TEX, F.SBW
OD = OrderedDict
DOT = '·'
NEW_GEO = []
NEW_GEO_FILES = ['kh23m', 'kh23m_flight', 'zb500', 's24b', 'ub16_57', 'ub32a_24', 'delta_ng']


def tube_lattice(n, pitch):
    """The n hexagonal-lattice points nearest the centre."""
    pts = []
    for j in range(-6, 7):
        for i in range(-6, 7):
            x, y = (i + 0.5 * (j % 2)) * pitch, j * pitch * math.sqrt(3) / 2
            pts.append((math.hypot(x, y), x, y))
    pts.sort()
    return [(p[1], p[2]) for p in pts[:n]]


def build_pod(L, d, n, pitch, body='olive'):
    """Rocket pod: tube barrel with a frangible nose cone and a tail fairing, tube mouths drawn aft, two lugs on top."""
    r = d / 2
    m = Mesh()
    nose, tail = 0.20, 0.22
    prof = [(0.0, 0.55 * r), (nose, r), (L - tail, r), (L, 0.82 * r)]

    def col(s):
        return 'dgrey' if (s < nose or s > L - tail) else body
    revolve(m, prof, col, cuts=[nose, L - tail], caps=('grey', 'grey'))
    for (px, py) in tube_lattice(n, pitch):
        disc(m, px, py, L + 0.001, pitch * 0.40, 'black', +1)
    lugs(m, L * 0.5, 0.356, r)
    return centred(m, L), L


def build_zb500():
    L, d = 2.86, 0.50
    r = d / 2
    m = Mesh()
    nose = F.ogive(0.55, 0.30 * r, r, 8)
    prof = nose + [(L - 0.65, r), (L, 0.45 * r)]

    def col(s):
        return 'red' if s < 0.10 else ('alu' if s < L - 0.65 else 'grey')
    revolve(m, prof, col, cuts=[0.10, L - 0.65], caps=('red', 'dgrey'))
    F.cruciform(m, (L - 0.75, L - 0.10), (L - 0.58, L - 0.10), 0.80 * r, r + 0.20, 0.012, 'grey', roll=45)
    lugs(m, L * 0.46, 0.356, r)
    return centred(m, L), L


def build_s24b():
    L, d = 2.33, 0.24
    r = d / 2
    m = Mesh()
    nose = F.ogive(0.50, 0.12 * r, r, 8)
    prof = nose + [(L - 0.22, r), (L - 0.05, 0.80 * r), (L, 0.70 * r)]

    def col(s):
        return 'dgrey' if s < 0.50 else ('olive' if s < L - 0.22 else 'black')
    revolve(m, prof, col, cuts=[0.50, L - 0.22], caps=('dgrey', 'black'))
    F.cruciform(m, (L - 0.62, L - 0.04), (L - 0.50, L - 0.04), r, 0.27, 0.012, 'grey', roll=45)
    lugs(m, L * 0.45, 0.356, r)
    return centred(m, L), L


def build_delta():
    L, d = 1.8, 0.22
    r = d / 2
    m = Mesh()
    prof = [(0.0, 0.55 * r), (0.12, 0.90 * r), (0.30, r), (L - 0.35, r), (L, 0.45 * r)]

    def col(s):
        return 'white' if s < 0.12 else ('grey' if s < L - 0.35 else 'dgrey')
    revolve(m, prof, col, cuts=[0.12, L - 0.35], caps=('white', 'dgrey'))
    m.box(-0.012, 0.012, -r - 0.07, -r + 0.01, L * 0.55, L * 0.55 + 0.05, 'grey')          # antenna blade
    lugs(m, L * 0.5, 0.356, r)
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

    def load(p):
        return json.load(open(p, encoding='utf-8'), object_pairs_hook=OD)

    # ============================================================== Kh-23M (radio command, as the AS.30)
    spec = dict(length=3.49, diameter=0.275, nose=0.62, boattail=0.15, tail_r=0.80, bands=[], radome=0.30,
                wings=[dict(root=(2.15, 2.85), tip=(2.45, 2.85), span=0.78 / 2, roll=45)],
                fins=[dict(root=(1.00, 1.22), tip=(1.10, 1.22), span=0.42 / 2, roll=45)])
    mesh, Lpx, rpx = generate.build(spec)
    F.write_json_compact(os.path.join(GEO, 'kh23m.geo.json'), generate.geo(mesh, 'geometry.aircraft_store.kh23m', False, Lpx))
    F.write_json_compact(os.path.join(GEO, 'kh23m_flight.geo.json'), generate.geo(mesh, 'geometry.aircraft_store.kh23m_flight', True, Lpx))
    generate.texture().save(os.path.join(TEX, 'kh23m.png'))
    F.mark_generated('kh23m', 'kh23m_flight')
    prof = load(os.path.join(prof_dir, 'aircraft_stores/agm12c.json'))
    c = prof['Combat']
    c['WeaponId'] = f'{NS}:aircraft_stores/kh23m'
    c['RoundId'] = f'{NS}:kh23m'
    c['CaliberMm'] = 275
    c['DiameterMm'] = 275
    c['HullDamage'] = 1330
    c['ModuleDamage'] = 800
    c['AmmoRackDamage'] = 150                 # GUESS: the AGM-12C values x 101 / 136.5 kg TNT, as the AS.30
    c['PenetrationMm'] = 600.0                # GUESS
    prof['Collision'] = OD([('Width', 0.275), ('Height', 0.275)])
    gp = prof['GuidedPropulsion']
    gp['MaxSpeed'] = 9.7
    gp['AccelerationPerTick'] = 0.14
    gp['ThrustDurationTicks'] = 98            # WT timeFire 4.9 s
    gp['MaxTurnRateDegreesPerSecond'] = 30    # GUESS
    mesh_ext = prof['Extensions'][f'{NS}:projectile_mesh_v1']
    mesh_ext['Model'] = f'{NS}:custom_geo/aircraft_stores/kh23m_flight.geo.json'
    mesh_ext['Texture'] = f'{NS}:textures/aircraft_stores/kh23m.png'
    prof['Extensions']['superbwarfare:heavy_warhead_blast_v1']['InnerRadius'] = 3.5
    prof['Extensions']['superbwarfare:heavy_warhead_blast_v1']['OuterRadius'] = 7
    F.write_json(os.path.join(prof_dir, 'aircraft_stores/kh23m.json'), prof)
    gun = load(os.path.join(gun_dir, 'agm12c.json'))
    gun['Name'] = F.ai_name(f'Kh-23M {DOT} radio command')
    gun['Projectile']['Profile'] = f'{NS}:aircraft_stores/kh23m'
    gun['ProjectileLife'] = 700               # WT operatedDist 10,000 m (the AS.30's setting)
    gun['Damage'] = 1330
    gun['ExplosionDamage'] = 1330
    gun['ExplosionRadius'] = 4
    gun['TntEquivalentKg'] = 101.0            # WT tg_40 75 kg x 1.35
    F.write_json(os.path.join(gun_dir, 'kh23m.json'), gun)
    a, launch, g = F.anchors_for('kh23m', missile=True)
    cat.add('munition/kh23m', F.store(f'Kh-23M {DOT} radio command', 'COMMAND_GUIDED', 'kh23m', a, Capacity=1, MassKg=289.0,
                                      CommandGuidance=OD([('Mode', 'MCLOS')]), ProjectileProfile=f'{NS}:aircraft_stores/kh23m',
                                      LaunchGunProfile=f'{NS}:aircraft_stores/kh23m', LaunchOffset=launch,
                                      AmmoItem='superbwarfare:medium_air_to_ground_missile'))
    print('kh23m anchors', a['MountAnchor'])

    # ============================================================== ZB-500 incendiary tank
    save('zb500', build_zb500())
    F.write_json(os.path.join(prof_dir, 'aircraft_bombs', 'zb500.json'), OD([
        ('VisualProfile', f'{NS}:authored_bomb'), ('MotionSync', 'INHERIT'), ('TrailMode', 'SUPPRESS'), ('RenderScale', 1),
        ('Extensions', OD([(f'{NS}:projectile_mesh_v1', OD([
            ('Model', f'{NS}:custom_geo/aircraft_stores/zb500.geo.json'),
            ('Texture', f'{NS}:textures/aircraft_stores/zb500.png'), ('ForwardYaw', 0), ('SpinDegreesPerTick', 0)]))]))]))
    a, launch, g = F.anchors_for('zb500')
    bomb = OD([('Mode', 'DUMB'), ('Gravity', 0.08), ('DragMultiplier', 1), ('TurnDegreesPerTick', 0), ('BlastRadius', 9),
               ('TntEquivalentKg', 65.0), ('BlastDamage', 300)])
    cat.add('munition/zb500', F.store(f'ZB-500 {DOT} 374 kg incendiary tank', 'BOMB', 'zb500', a, Capacity=1, MassKg=374.0, Bomb=bomb,
                                      AmmoItem='superbwarfare:medium_freefall_bomb', ProjectileProfile=f'{NS}:aircraft_bombs/zb500',
                                      MaxPerPylon=1, LaunchOffset=launch))
    print('zb500 anchors', a['MountAnchor'])

    # ============================================================== rockets
    save('s24b', build_s24b())
    a, launch, g = F.anchors_for('s24b', missile=True)
    cat.add('munition/s24b', F.store(f'S-24B {DOT} 240 mm rocket on APU-68', 'ROCKET_POD', 's24b', a, Capacity=1, Scale=1, MassKg=235.0,
                                     AmmoItem='superbwarfare:small_unguided_rocket', LaunchOffset=launch))
    for name, L, d, n, pitch, kg, label in (('ub16_57', 2.14, 0.29, 16, 0.065, 132.2 / 16, 'UB-16-57U'),
                                            ('ub32a_24', 2.0, 0.40, 32, 0.065, 269.3 / 32, 'UB-32A-24')):
        save(name, build_pod(L, d, n, pitch))
        a, launch, g = F.anchors_for(name)
        cat.add(f'munition/{name}', F.store(f'{label} {DOT} {n} x S-5KP rockets', 'ROCKET_POD', name, a, Capacity=n, Scale=1,
                                            MassKg=round(kg, 2), AmmoItem='superbwarfare:small_unguided_rocket', LaunchOffset=launch))

    # ============================================================== Delta-NG pod
    save('delta_ng', build_delta())
    a, launch, g = F.anchors_for('delta_ng')
    cat.add('sensor/delta_ng', F.store(f'Delta-NG {DOT} radio command station pod', 'VISUAL_ONLY', 'delta_ng', a, Capacity=1, MassKg=20.0,
                                       LaunchOffset=launch))
    print('written; new geo files:', ' '.join(NEW_GEO))


if __name__ == '__main__':
    main(sys.argv[1:])
