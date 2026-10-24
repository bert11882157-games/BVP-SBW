#!/usr/bin/env python3
"""Store models and store definitions for the Mirage IIIE (War Thunder mirage_3e loads), after f4c_stores.py / mig25_stores.py.

Built here, in the pack's aircraft-store format (nose -Z, one bone 'store', 1 block = 1 m). Dimensions and masses come from the
War Thunder datamine (work/elite_expansion_2/mirage3e/wt, summarised in mirage3e/NOTES.md) or public figures; guesses are marked GUESS.

  tank_1700l      the Mirage IIIE model's own 1,700 l drop tank, cut out at real size (4.24 m x 0.53 m; mirage3e/stores). Cut from a supplied
                  model, so it is NOT marked (AI). WT: 150 kg empty, 1,320 kg of fuel -> MassKg 1,470 like the pack's other tanks (full).
  as30            Nord AS.30 (WT fr_as30: 3.885 m x 0.342 m, 520 kg, 76 kg Comp B, manual radio command, operated range 10 km). Built.
  samp25 / samp21 SAMP Type 25 250 kg (WT: 2.12 m x 0.324 m, 247 kg, 123.5 kg TNT) and SAMP Type 21 400 kg (2.15 m x 0.449 m, 400 kg, 167 kg TNT)
                  on the PM3 twin rack (two bombs); the "200" variants are parachute retarded (WT 264 kg / 400 kg), flight model with the open chute.
  lau32_fz49      LAU-32 7-tube pod (WT lau_32_fz: pod 89 kg + 7 x FZ49 8.12 kg = 145.8 kg). Native rocket weapon on the outer pylons.
  jl100r          Matra Type 155 JL-100R, 18 x SNEB 68 mm in the nose and a 250 l tank behind (WT jl_100r: 140 kg + 18 x 5.05 kg = 230.9 kg).
  r530_radar      R.530 semi-active radar (the pack's r530 model; WT fr_r_530_matra_radar). The pack's r530 stays the infrared R.530 (WT
                  fr_r_530_matra_ir).

Usage: python tools/munition_models/mirage3e_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo files>   (and --check)
"""
import json
import math
import os
import shutil
import sys
from collections import OrderedDict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import f16c_stores as F  # noqa: E402
import generate  # noqa: E402
from f16c_stores import Mesh, revolve, cruciform, lugs, centred, ogive  # noqa: E402

NS, STORES, SBW, GEO, TEX, PX = F.NS, F.STORES, F.SBW, F.GEO, F.TEX, F.PX
OD = OrderedDict
DOT = '\u00b7'
CUT = os.environ.get('MIRAGE3E_STORE_SOURCE', r'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2\mirage3e\stores')
NEW_GEO = []


def load(rel):
    with open(os.path.join(STORES, rel + '.json'), encoding='utf-8') as fh:
        return json.load(fh, object_pairs_hook=OD)


def import_cut(src, name):
    """Copy a part cut from the Mirage IIIE model (geo + png); one bone 'store', nose -Z, body on the origin."""
    g = json.load(open(os.path.join(CUT, src, src + '.geo.json'), encoding='utf-8'))
    g['minecraft:geometry'][0]['description']['identifier'] = 'geometry.aircraft_store.' + name
    F.write_json_compact(os.path.join(GEO, name + '.geo.json'), g)
    shutil.copyfile(os.path.join(CUT, src, src + '.png'), os.path.join(TEX, name + '.png'))
    NEW_GEO.append(name)
    return name


def save(name, m):
    F.save_model(name, m)
    NEW_GEO.append(name)


# ------------------------------------------------------------------------------------------------ models
def build_samp(L, d, nose, tail_at, tail_r, fin_z, reach, band, lug_z, retarded=False, deployed=False):
    """SAMP general-purpose bomb: olive body, yellow band, tapered tail with four fins; retarded: grey parachute container
    instead of fins, deployed adds the open canopy (GUESS: container and canopy sizes)."""
    r = d / 2
    m = Mesh()
    prof = [(0.0, 0.05)] + ogive(nose, 0.05, r)[1:] + [(tail_at, r), (L, tail_r)]
    if retarded:
        prof = [(0.0, 0.05)] + ogive(nose, 0.05, r)[1:] + [(tail_at, r), (L - 0.40, 0.62 * r), (L, 0.62 * r)]

    def col(s):
        if band[0] <= s < band[1]:
            return 'yellow'
        if retarded and s >= L - 0.40:
            return 'grey'
        return 'olive' if s < tail_at else 'dgrey'
    cuts = [band[0], band[1], tail_at] + ([L - 0.40] if retarded else [])
    revolve(m, prof, col, cuts=cuts, caps=('dgrey', 'dgrey'))
    total = L
    if retarded:
        if deployed:
            # open canopy behind the container: a cone from the container radius out to 0.85 m over 1.3 m (GUESS shape and size)
            r0, r1, dz = 0.62 * r, 0.85, 1.3
            for k in range(F.SEG):
                a0, a1 = 2 * math.pi * k / F.SEG, 2 * math.pi * (k + 1) / F.SEG
                p = [np.array([r0 * math.cos(a), r0 * math.sin(a), L]) for a in (a0, a1)]
                q = [np.array([r1 * math.cos(a), r1 * math.sin(a), L + dz]) for a in (a0, a1)]
                m.poly([p[0], p[1], q[1], q[0]], 'white')
                m.poly([q[0], q[1], p[1], p[0]], 'white')
            total = L + dz
    else:
        cruciform(m, (fin_z[0], fin_z[1]), (fin_z[0] + 0.12, fin_z[1]), tail_r * 0.9, reach, 0.012, 'dgrey', 45)
    lugs(m, lug_z, 0.356, r)
    return centred(m, total), total


def build_lau32(L=1.93, d=0.235):
    """LAU-32 seven-tube 2.75 in rocket pod (GUESS: 1.93 m x 0.235 m from the 7-tube Hydra pods of the pack, the datamine gives no size)."""
    m, L = F.build_pod(L, d, 7, 0.075, body='olive')
    return centred(m, L), L


def build_jl100r(L=3.30, d=0.50, tube_pitch=0.085):
    """Matra Type 155 JL-100R: 250 l tank body with an ogive tail cone and, in the nose, 18 rocket tubes (hexagon ring pattern of 19 minus
    the centre). GUESS: length 3.30 m and diameter 0.50 m (public figures differ), the tube pattern drawn on the nose face."""
    r = d / 2
    m = Mesh()
    nose, tail = 0.22, 0.60
    prof = [(0.0, 0.88 * r), (nose, r), (L - tail, r), (L, 0.35 * r)]

    def col(s):
        return 'dgrey' if s < nose else ('olive' if s < L - tail else 'grey')
    revolve(m, prof, col, cuts=[nose, L - tail], caps=('grey', 'dgrey'))
    for (px, py) in F.tubes(19)[1:]:
        F.disc(m, px * tube_pitch, py * tube_pitch, -0.001, tube_pitch * 0.40, 'black', -1)
    lugs(m, L * 0.5, 0.356, r)
    return centred(m, L), L


# ------------------------------------------------------------------------------------------------ main
def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()
    prof_dir = os.path.join(SBW, 'projectile_profiles')
    gun_dir = os.path.join(SBW, 'guns/aircraft_stores')

    # ============================================================== models
    import_cut('tank_1700l', 'tank_1700l')
    # AS.30: 3.885 m x 0.342 m (WT length / caliber); four trapezoidal wings in the rear third, span 1.00 m (GUESS: wing station)
    spec = dict(length=3.885, diameter=0.342, nose=0.85, boattail=0.10, tail_r=0.85, bands=[(0.30, 0.32, 'yellow')], radome=0.20,
                wings=[dict(root=(2.55, 3.45), tip=(2.95, 3.45), span=1.00 / 2, roll=45)], fins=[])
    mesh, Lpx, rpx = generate.build(spec)
    F.write_json_compact(os.path.join(GEO, 'as30.geo.json'), generate.geo(mesh, 'geometry.aircraft_store.as30', False, Lpx))
    F.write_json_compact(os.path.join(GEO, 'as30_flight.geo.json'), generate.geo(mesh, 'geometry.aircraft_store.as30_flight', True, Lpx))
    generate.texture().save(os.path.join(TEX, 'as30.png'))
    F.mark_generated('as30', 'as30_flight')
    NEW_GEO.extend(['as30', 'as30_flight'])
    # SAMP bombs; fin reach, band and lug positions GUESS
    m, L = build_samp(2.12, 0.324, 0.55, 1.45, 0.12, (1.70, 2.10), 0.24, (0.85, 0.93), 1.00); save('samp25', m)
    m, L = build_samp(2.15, 0.449, 0.60, 1.50, 0.17, (1.72, 2.12), 0.32, (0.88, 0.96), 1.02); save('samp21', m)
    m, L = build_samp(2.12, 0.324, 0.55, 1.45, 0.12, None, 0.0, (0.85, 0.93), 1.00, retarded=True); save('samp25_200', m)
    m, L = build_samp(2.12, 0.324, 0.55, 1.45, 0.12, None, 0.0, (0.85, 0.93), 1.00, retarded=True, deployed=True); save('samp25_200_deployed', m)
    m, L = build_samp(2.15, 0.449, 0.60, 1.50, 0.17, None, 0.0, (0.88, 0.96), 1.02, retarded=True); save('samp21_200', m)
    m, L = build_samp(2.15, 0.449, 0.60, 1.50, 0.17, None, 0.0, (0.88, 0.96), 1.02, retarded=True, deployed=True); save('samp21_200_deployed', m)
    m, L = build_lau32(); save('lau32_fz49', m)
    m, L = build_jl100r(); save('jl100r', m)

    # ============================================================== tank
    a, launch, g = F.anchors_for('tank_1700l')
    cat.add('munition/tank_1700l', F.store(f'1,700 l drop tank', 'VISUAL_ONLY', 'tank_1700l', a, Capacity=1, MassKg=1470.0,
                                           LaunchOffset=launch))

    # ============================================================== R.530 semi-active radar (the pack's R.530 airframe, WT radar seeker)
    d = load('munition/r530')
    d['Name'] = f'R.530 {DOT} semi-active radar'
    d['MassKg'] = 193.5
    d['Guidance'] = OD([('Mode', 'SEMI_ACTIVE_RADAR'), ('LockTicks', 0), ('Range', 3000), ('ConeDegrees', 25),
                        ('CountermeasureVulnerability', 0.9)])
    cat.add('munition/r530_radar', d)

    # ============================================================== AS.30 (manual radio command, like the pack's AGM-12C)
    prof = json.load(open(os.path.join(prof_dir, 'aircraft_stores/agm12c.json'), encoding='utf-8'), object_pairs_hook=OD)
    c = prof['Combat']
    c['WeaponId'] = f'{NS}:aircraft_stores/as30'; c['RoundId'] = f'{NS}:as30'
    c['CaliberMm'] = 342; c['DiameterMm'] = 342
    c['HullDamage'] = 1330; c['ModuleDamage'] = 800; c['AmmoRackDamage'] = 150      # GUESS: the AGM-12C values x 101 / 136.5 kg TNT
    c['PenetrationMm'] = 800.0                                                      # GUESS
    prof['Collision'] = OD([('Width', 0.34), ('Height', 0.34)])
    gp = prof['GuidedPropulsion']
    gp['MaxSpeed'] = 8; gp['AccelerationPerTick'] = 0.12; gp['ThrustDurationTicks'] = 200; gp['MaxTurnRateDegreesPerSecond'] = 35   # GUESS (WT 370 m/s)
    mesh_ext = prof['Extensions'][f'{NS}:projectile_mesh_v1']
    mesh_ext['Model'] = f'{NS}:custom_geo/aircraft_stores/as30_flight.geo.json'
    mesh_ext['Texture'] = f'{NS}:textures/aircraft_stores/as30.png'
    prof['Extensions']['superbwarfare:heavy_warhead_blast_v1']['InnerRadius'] = 3.5
    prof['Extensions']['superbwarfare:heavy_warhead_blast_v1']['OuterRadius'] = 7
    F.write_json(os.path.join(prof_dir, 'aircraft_stores/as30.json'), prof)
    gun = json.load(open(os.path.join(gun_dir, 'agm12c.json'), encoding='utf-8'), object_pairs_hook=OD)
    gun['Name'] = F.ai_name(f'Nord AS.30 {DOT} manual radio command')
    gun['Projectile']['Profile'] = f'{NS}:aircraft_stores/as30'
    gun['ProjectileLife'] = 700                                                      # WT operatedDist 10,000 m against the Bullpup B's 16,000
    gun['Damage'] = 1330; gun['ExplosionDamage'] = 1330; gun['ExplosionRadius'] = 4; gun['TntEquivalentKg'] = 101.0   # WT Comp B 76 kg x 1.33
    F.write_json(os.path.join(gun_dir, 'as30.json'), gun)
    a, launch, g = F.anchors_for('as30', missile=True)
    cat.add('munition/as30', F.store(f'Nord AS.30 {DOT} manual command', 'COMMAND_GUIDED', 'as30', a, Capacity=1, MassKg=520.0,
                                     CommandGuidance=OD([('Mode', 'MCLOS')]), ProjectileProfile=f'{NS}:aircraft_stores/as30',
                                     LaunchGunProfile=f'{NS}:aircraft_stores/as30', LaunchOffset=launch,
                                     AmmoItem='superbwarfare:medium_air_to_ground_missile'))

    # ============================================================== SAMP bombs on the PM3 twin rack
    def bomb_profile(pid, model):
        F.write_json(os.path.join(prof_dir, 'aircraft_bombs', pid + '.json'), OD([
            ('VisualProfile', f'{NS}:authored_bomb'), ('MotionSync', 'INHERIT'), ('TrailMode', 'SUPPRESS'), ('RenderScale', 1),
            ('Extensions', OD([(f'{NS}:projectile_mesh_v1', OD([
                ('Model', f'{NS}:custom_geo/aircraft_stores/{model}.geo.json'),
                ('Texture', f'{NS}:textures/aircraft_stores/{model}.png'), ('ForwardYaw', 0), ('SpinDegreesPerTick', 0)]))]))]))

    bomb_profile('samp25', 'samp25'); bomb_profile('samp21', 'samp21')
    bomb_profile('samp25_200', 'samp25_200_deployed'); bomb_profile('samp21_200', 'samp21_200_deployed')
    # PM3 twin rack: one beam under the centreline pylon, a bomb on each side (the BRU-57 / BLU-27 pattern of the F-16C batch)

    def rack(name, model, fin_lat):
        a_, _, _ = F.anchors_for(model)
        r = abs(a_['SideMountAnchors']['Left'][0])
        W = max(0.10, 2 * fin_lat - 2 * r + 0.05)
        D = r - F.THK / 2 + 0.03
        ymid = -D - F.THK / 2
        boxes = [(-0.03, 0.03, -D, 0.0, -0.35, 0.35), (-W / 2, W / 2, -D - F.THK, -D, -0.45, 0.45)]
        F.rack_model(name, boxes)
        NEW_GEO.append(name)
        return OD([('Model', f'{NS}:custom_geo/aircraft_stores/{name}.geo.json'),
                   ('Texture', f'{NS}:textures/aircraft_stores/mirage3e_rack.png'), ('MountAnchor', [0.0, 0.0, 0.0]),
                   ('Stations', [OD([('Point', F.vec([-W / 2, ymid, 0.0])), ('Face', 'left')]),
                                 OD([('Point', F.vec([W / 2, ymid, 0.0])), ('Face', 'right')])])])
    F.palette_png().save(os.path.join(TEX, 'mirage3e_rack.png'))
    pm3_250 = rack('rack_pm3_samp25', 'samp25', 0.24)
    pm3_400 = rack('rack_pm3_samp21', 'samp21', 0.30)
    pm3_250r = rack('rack_pm3_samp25_200', 'samp25_200', 0.24)
    pm3_400r = rack('rack_pm3_samp21_200', 'samp21_200', 0.30)

    def bomb(rel, name, model, mass, drag, blast, tnt, dmg, ammo, rk, spacing, profile):
        a, launch, g = F.anchors_for(model)
        b = OD([('Mode', 'DUMB'), ('Gravity', 0.08), ('DragMultiplier', drag), ('TurnDegreesPerTick', 0),
                ('BlastRadius', blast), ('TntEquivalentKg', tnt), ('BlastDamage', dmg)])
        cat.add(rel, F.store(name, 'BOMB', model, a, Capacity=1, MassKg=mass, Bomb=b, AmmoItem=ammo,
                             ProjectileProfile=f'{NS}:aircraft_bombs/{profile}', MaxPerPylon=2, FixedRackCount=2, RackAdapter=rk,
                             RackSpacing=spacing, LaunchOffset=launch))
    SF, MF = 'superbwarfare:small_freefall_bomb', 'superbwarfare:medium_freefall_bomb'
    # blast numbers: the pack's Mk 82 (7 m, 450, 117.6 kg TNT eq) and Mk 83 (10 m, 650, 272.4 kg) interpolated by the cube root of the TNT
    # (SAMP 25: 123.5 kg TNT, SAMP 21: 167 kg TNT, both WT, TNT filler = factor 1); radius and damage GUESS
    S25 = (7, 123.5, 470)
    S21 = (8, 167.0, 540)
    bomb('munition/samp25_x2', f'SAMP Type 25 250 kg {DOT} PM3 twin rack', 'samp25', 247.0, 1, *S25, SF, pm3_250, [0.50, 0.4, 0], 'samp25')
    bomb('munition/samp21_x2', f'SAMP Type 21 400 kg {DOT} PM3 twin rack', 'samp21', 400.0, 1, *S21, MF, pm3_400, [0.60, 0.4, 0], 'samp21')
    bomb('munition/samp25_200_x2', f'SAMP Type 25 250 kg {DOT} parachute {DOT} PM3 twin rack', 'samp25_200', 264.0, 5, *S25, SF, pm3_250r,
         [0.50, 0.4, 0], 'samp25_200')
    bomb('munition/samp21_200_x2', f'SAMP Type 21 400 kg {DOT} parachute {DOT} PM3 twin rack', 'samp21_200', 400.0, 5, *S21, MF, pm3_400r,
         [0.60, 0.4, 0], 'samp21_200')

    # ============================================================== rocket pods (unguided: the aircraft's native rocket channel fires them)
    def pod(rel, name, model, rounds, kg_per_round):
        a, launch, g = F.anchors_for(model)
        cat.add(rel, F.store(name, 'ROCKET_POD', model, a, Capacity=rounds, Scale=1, MassKg=kg_per_round,
                             AmmoItem='superbwarfare:small_unguided_rocket', LaunchOffset=launch))
    pod('munition/lau32_fz49', f'LAU-32 {DOT} 7 x FZ49 2.75 in', 'lau32_fz49', 7, round((89.0 + 7 * 8.12) / 7, 2))
    pod('munition/jl100r', f'Matra JL-100R {DOT} 18 x SNEB 68 mm + tank', 'jl100r', 18, round((140.0 + 18 * 5.05) / 18, 2))
    print('written', len(cat.items), 'stores; new geo files:', ' '.join(NEW_GEO))


if __name__ == '__main__':
    main(sys.argv[1:])
