#!/usr/bin/env python3
"""Store models and store definitions for the War Thunder F-4C loadout (fourth batch after f16c_stores.py, fa18e_stores.py
and a10c_stores.py).

Reuses the builders of f16c_stores.py (Mesh, revolve, cruciform, lugs, rack adapters, anchor derivation with
tools/aircraft_attach/munitions.py) and generate.py's body-of-revolution missile builder. The AIM-9E and the SUU-16/SUU-23
gun pod are the F-4C model's own parts, cut out at real size (work/elite_expansion_2/f4c/stores); everything else is built
here. Dimensions and values come from the War Thunder brief (work/elite_expansion_2/f4c/WT-f4c.md, sections 2 to 4 and 7)
or public figures; guesses are marked GUESS below.

Not built (already in the pack): AIM-9B, AGM-12B, AIM-7E (the AIM-7D reuses its model), Mk 82 (x1, x3, x6), Mk 83 (x1, x2),
Mk 84, BLU-27 (x1, x2, x3), LAU-3/A, LAU-10, the 600 gal tank, gun_pod/f4c_gau4.
Not built (no WT data or no pack-side model work asked for): AGM-45B on LAU-34, M118, Snakeye on MER/TER beyond the pack.

Usage: python tools/munition_models/f4c_stores.py [--write]
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
munitions = F.munitions
OD = OrderedDict
THK = F.THK
CUT = os.environ.get('F4C_STORE_SOURCE', r'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2\f4c\stores')
DOT = '\u00b7'


def load(rel):
    with open(os.path.join(STORES, rel + '.json'), encoding='utf-8') as fh:
        return json.load(fh, object_pairs_hook=OD)


def import_cut(src, name):
    """Copy a part cut from the F-4C model (geo + png); one bone 'store', nose -Z, body on the origin."""
    g = json.load(open(os.path.join(CUT, src, src + '.geo.json'), encoding='utf-8'))
    g['minecraft:geometry'][0]['description']['identifier'] = 'geometry.aircraft_store.' + name
    F.write_json_compact(os.path.join(GEO, name + '.geo.json'), g)
    shutil.copyfile(os.path.join(CUT, src, src + '.png'), os.path.join(TEX, name + '.png'))
    return name


# ------------------------------------------------------------------------------------------------ models
def build_ldgp(L, d, nose, tail_at, tail_r, fin_z, fin_reach, band, lug_z, lug_gap=0.356):
    """Low-drag general purpose bomb (Mk 81 / M117): olive body with a yellow band, tapered tail, four tail fins, two lugs."""
    r = d / 2
    m = Mesh()
    prof = [(0.0, 0.05)] + ogive(nose, 0.05, r)[1:] + [(tail_at, r), (L, tail_r)]

    def col(s):
        if band[0] <= s < band[1]:
            return 'yellow'
        return 'olive' if s < tail_at else 'dgrey'
    revolve(m, prof, col, cuts=[band[0], band[1], tail_at], caps=('dgrey', 'dgrey'))
    cruciform(m, (fin_z[0], fin_z[1]), (fin_z[0] + 0.12, fin_z[1]), tail_r * 0.9, fin_reach, 0.012, 'dgrey', 45)
    lugs(m, lug_z, lug_gap, r)
    return centred(m, L), L


def build_blu1(L, d):
    """BLU-1/B fire bomb: aluminium canister, rounded ends, red bands, cruciform tail fins (GUESS: length, fin size)."""
    r = d / 2
    m = Mesh()
    prof = [(0.0, 0.09), (0.06, 0.17), (0.16, 0.22), (0.32, r), (L - 0.60, r), (L - 0.30, 0.20), (L, 0.13)]

    def col(s):
        return 'red' if (0.55 <= s < 0.65 or L - 0.95 <= s < L - 0.85) else 'alu'
    revolve(m, prof, col, cuts=[0.55, 0.65, L - 0.95, L - 0.85], caps=('dgrey', 'dgrey'))
    cruciform(m, (L - 0.70, L - 0.03), (L - 0.50, L - 0.03), 0.13, 0.34, 0.012, 'dgrey', 45)
    lugs(m, L / 2, 0.356, r)
    return centred(m, L), L


# ------------------------------------------------------------------------------------------------ racks
def adapter(rack, stations):
    return OD([('Model', f'{NS}:custom_geo/aircraft_stores/{rack}.geo.json'),
               ('Texture', f'{NS}:textures/aircraft_stores/f4c_rack.png'),
               ('MountAnchor', [0.0, 0.0, 0.0]), ('Stations', stations)])


def body_side(model):
    """Body-side radius and top-to-axis drop of a written model (from the derived anchors)."""
    a = F.anchors_for(model)[0]
    return abs(a['SideMountAnchors']['Left'][0]), a['MountAnchor'][1] - a['MountAxis'][1]


def rack_ter(name, model):
    """TER-9A pattern of the A-10C batch (one beam, a store under it and one on each side), beam width fitted to the store."""
    rs, drop = body_side(model)
    sep = 2 * rs + 0.02
    W = round(2 * max(math.sqrt(sep * sep - (drop + THK / 2) ** 2) - rs, 0.05), 3)
    F.rack_model(name, [(-W / 2, W / 2, -THK, 0.0, -0.5, 0.5)])
    return adapter(name, [OD([('Point', F.vec([0.0, -THK, 0.0])), ('Face', 'bottom')]),
                          OD([('Point', F.vec([-W / 2, -THK / 2, 0.0])), ('Face', 'left')]),
                          OD([('Point', F.vec([W / 2, -THK / 2, 0.0])), ('Face', 'right')])]), W


def rack_two(model, fin_lat, d_extra=0.0):
    """Stations of a twin rack hung like the F-16C BRU-57 / BLU-27 racks (the rack geometry itself is not rewritten)."""
    r, _ = body_side(model)
    W = max(0.10, 2 * fin_lat - 2 * r + 0.05)
    D = r - THK / 2 + 0.03 + d_extra
    ymid = -D - THK / 2
    return [OD([('Point', F.vec([-W / 2, ymid, 0.0])), ('Face', 'left')]), OD([('Point', F.vec([W / 2, ymid, 0.0])), ('Face', 'right')])]


def rack_mer(name, model, fin_r):
    """MER pattern of the F-16C batch: three crossbars one below the other, a store on each end."""
    r, _ = body_side(model)
    W = max(0.10, 2 * fin_r - 2 * r + 0.04)
    D = r - THK / 2 + 0.03
    vsep = 2 * fin_r + 0.02
    ys = [-D - THK / 2 - i * vsep for i in range(3)]
    boxes = [(-0.04, 0.04, -D, 0.0, -0.55, 0.55)]
    for i, y in enumerate(ys):
        boxes.append((-W / 2, W / 2, y - THK / 2, y + THK / 2, -0.60, 0.60))
        if i:
            boxes.append((-0.04, 0.04, y + THK / 2, ys[i - 1] - THK / 2, -0.45, 0.45))
    F.rack_model(name, boxes)
    st = []
    for y in ys:
        st += [OD([('Point', F.vec([-W / 2, y, 0.0])), ('Face', 'left')]), OD([('Point', F.vec([W / 2, y, 0.0])), ('Face', 'right')])]
    return adapter(name, st)


# ------------------------------------------------------------------------------------------------ main
def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True); os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()
    prof_dir = os.path.join(SBW, 'projectile_profiles')
    gun_dir = os.path.join(SBW, 'guns/aircraft_stores')
    F.palette_png().save(os.path.join(TEX, 'f4c_rack.png'))

    # ============================================================== models
    # the model's own AIM-9E (2.98 m) and SUU-16-type gun pod (4.96 m x 0.58 m, close to the real SUU-23/A 5.05 m x 0.56 m)
    import_cut('aim9e', 'aim9e')
    import_cut('suu16_gau4', 'suu23a')
    # AGM-12C Bullpup B: scaled-up Bullpup, 4.14 m x 0.46 m, cruciform wings (GUESS: wing span 1.20 m and station, as the B's 0.94 m
    # scaled; the tail surfaces are left out)
    spec = dict(length=4.14, diameter=0.46, nose=0.90, boattail=0.10, tail_r=0.8, bands=[(0.24, 0.25, 'yellow')], radome=0.12,
                wings=[dict(root=(2.55, 3.55), tip=(2.95, 3.55), span=1.20 / 2, roll=45)], fins=[])
    mesh, Lpx, rpx = generate.build(spec)
    F.write_json_compact(os.path.join(GEO, 'agm12c.geo.json'), generate.geo(mesh, 'geometry.aircraft_store.agm12c', False, Lpx))
    F.write_json_compact(os.path.join(GEO, 'agm12c_flight.geo.json'), generate.geo(mesh, 'geometry.aircraft_store.agm12c_flight', True, Lpx))
    generate.texture().save(os.path.join(TEX, 'agm12c.png'))
    # M117 750 lb: 2.18 m x 0.408 m (the brief's 3.25 m is longer than a Mk 83, so the public 86 in is used: DEVIATION from the task text);
    # fin reach and band positions GUESS
    m, L = build_ldgp(2.18, 0.408, 0.55, 1.55, 0.17, (1.72, 2.16), 0.27, (0.95, 1.03), 1.05); F.save_model('m117', m)
    # Mk 81 250 lb: 1.88 m x 0.229 m, fins GUESS
    m, L = build_ldgp(1.88, 0.229, 0.40, 1.45, 0.075, (1.55, 1.86), 0.165, (0.62, 0.67), 0.95); F.save_model('mk81', m)
    m, L = build_blu1(3.05, 0.47); F.save_model('blu1', m)

    # ============================================================== racks
    ter_m117, w1 = rack_ter('rack_ter_x3_m117', 'm117')
    ter_mk83, w2 = rack_ter('rack_ter_x3_mk83', 'mk83')
    mer_mk81 = rack_mer('rack_mer_x6_mk81', 'mk81', 0.135)
    st_blu1 = rack_two('blu1', 0.24, 0.02)                      # the F-16C batch's rack_blu27_x2 (same radius and drop as the BLU-27)

    # ============================================================== gun pod: SUU-23/A (fires through NativeWeaponIds like gun_pod/f4c_gau4)
    a, launch, g = F.anchors_for('suu23a')
    # 20 mm GAU-4 pod: 1,200 rounds (WT); MassKg 780 = loaded pod, about 1,720 lb: GUESS (WT wiki lists the gun at 613.7 kg, file 494 kg)
    cat.add('gun_pod/suu23a', OD([('Schema', 1), ('Name', f'SUU-23/A {DOT} 20 mm GAU-4 gun pod'), ('Category', 'GUN_POD'),
                                   ('Model', f'{NS}:custom_geo/aircraft_stores/suu23a.geo.json'), ('ModelForward', '-Z'),
                                   ('MountAnchor', a['MountAnchor']),
                                   ('Texture', f'{NS}:textures/aircraft_stores/suu23a.png'), ('MassKg', 780.0)]))

    # ============================================================== air-to-air
    # AIM-9E: WT 76.43 kg (file), 11 km seeker against the 9B's 8 km, lock cone 10 deg against 5, rateMax 12 against 11, machMax 2.5
    # against 1.7, same hbx_1 4.763 kg warhead as the 9B (TNT 7.6204 as the pack's 9B). Store numbers are the 9B's moved by those
    # WT ratios and kept below the AIM-9L: Range, LockTicks, ConeDegrees, CountermeasureVulnerability, MaxSpeed are GUESS.
    a, launch, g = F.anchors_for('aim9e', missile=True)
    guid = OD([('Mode', 'INFRARED'), ('LockTicks', 62), ('Range', 850), ('ConeDegrees', 24), ('CountermeasureVulnerability', 1.2)])
    flight = OD([('InitialSpeed', 0.5), ('MaxSpeed', 4.8), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 46), ('Damage', 25),
                 ('BlastRadius', 2.5), ('TntEquivalentKg', 7.6204)])
    cat.add('munition/aim9e', F.store(f'AIM-9E Sidewinder {DOT} improved infrared', 'AIR_TO_AIR', 'aim9e', a, Capacity=1, MassKg=76.4,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))
    # two on the LAU-7 rails either side of the inner pylon: fixed pair, spacing as the pack's aim9b_x2 (GUESS: the F-4's rail offset)
    cat.add('munition/aim9e_x2', F.store(f'AIM-9E Sidewinder {DOT} improved infrared {DOT} twin LAU-7 rails', 'AIR_TO_AIR', 'aim9e', a,
                                         Capacity=1, MassKg=76.4, Guidance=guid, Flight=flight,
                                         AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=2, FixedRackCount=2,
                                         RackSpacing=[0.66, 0.35, 0], LaunchOffset=launch))
    # AIM-7D: the pack's AIM-7E model and airframe; WT mass 182.3 kg, pbxn_4 9.072 kg (same TNT as the pack's 7E), machMax 4.0,
    # rateMax 12. WT's 7E was not read, so the step down from the 7E (Range 1,500, turn 38) is GUESS
    d = load('munition/aim7e')
    d['Name'] = f'AIM-7D Sparrow {DOT} semi-active radar'
    d['MassKg'] = 182.3
    d['Guidance']['Range'] = 1400
    d['Flight']['TurnDegreesPerSecond'] = 36
    cat.add('munition/aim7d', d)

    # ============================================================== AGM-12C Bullpup B
    prof = json.load(open(os.path.join(prof_dir, 'aircraft_stores/agm12b.json'), encoding='utf-8'), object_pairs_hook=OD)
    c = prof['Combat']
    c['WeaponId'] = f'{NS}:aircraft_stores/agm12c'; c['RoundId'] = f'{NS}:agm12c'
    c['CaliberMm'] = 460; c['DiameterMm'] = 460
    c['HullDamage'] = 1800; c['ModuleDamage'] = 1080; c['AmmoRackDamage'] = 200      # GUESS: 12B values x 136.5 / 58 kg TNT, damped
    c['PenetrationMm'] = 1100.0                                                      # GUESS
    prof['Collision'] = OD([('Width', 0.46), ('Height', 0.46)])
    gp = prof['GuidedPropulsion']
    gp['MaxSpeed'] = 7; gp['AccelerationPerTick'] = 0.11; gp['MaxTurnRateDegreesPerSecond'] = 40   # GUESS: WT force 147,000 on 810 kg vs 53,400 on 259 kg
    prof['Extensions'][f'{NS}:projectile_mesh_v1']['Model'] = f'{NS}:custom_geo/aircraft_stores/agm12c_flight.geo.json'
    prof['Extensions'][f'{NS}:projectile_mesh_v1']['Texture'] = f'{NS}:textures/aircraft_stores/agm12c.png'
    prof['Extensions']['superbwarfare:heavy_warhead_blast_v1']['InnerRadius'] = 4
    prof['Extensions']['superbwarfare:heavy_warhead_blast_v1']['OuterRadius'] = 8
    F.write_json(os.path.join(prof_dir, 'aircraft_stores/agm12c.json'), prof)
    gun = json.load(open(os.path.join(gun_dir, 'agm12b.json'), encoding='utf-8'), object_pairs_hook=OD)
    gun['Name'] = f'AGM-12C Bullpup B {DOT} manual radio command'
    gun['Projectile']['Profile'] = f'{NS}:aircraft_stores/agm12c'
    gun['ProjectileLife'] = 1000                                                     # GUESS: WT operatedDist 16,000 against the B's 8,000
    gun['Damage'] = 1800; gun['ExplosionDamage'] = 1800; gun['ExplosionRadius'] = 5; gun['TntEquivalentKg'] = 136.5   # WT tnt 136.5 kg; the rest GUESS
    F.write_json(os.path.join(gun_dir, 'agm12c.json'), gun)
    a, launch, g = F.anchors_for('agm12c', missile=True)
    # MassKg 810 is War Thunder's file mass (real Bullpup B: about 810 lb)
    cat.add('munition/agm12c', F.store(f'AGM-12C Bullpup B {DOT} manual command', 'COMMAND_GUIDED', 'agm12c', a, Capacity=1, MassKg=810.0,
                                       CommandGuidance=OD([('Mode', 'MCLOS')]), ProjectileProfile=f'{NS}:aircraft_stores/agm12c',
                                       LaunchGunProfile=f'{NS}:aircraft_stores/agm12c', LaunchOffset=launch,
                                       AmmoItem='superbwarfare:medium_air_to_ground_missile'))

    # ============================================================== bombs
    def bomb_profile(pid, model):
        F.write_json(os.path.join(prof_dir, 'aircraft_bombs', pid + '.json'), OD([
            ('VisualProfile', f'{NS}:authored_bomb'), ('MotionSync', 'INHERIT'), ('TrailMode', 'SUPPRESS'), ('RenderScale', 1),
            ('Extensions', OD([(f'{NS}:projectile_mesh_v1', OD([
                ('Model', f'{NS}:custom_geo/aircraft_stores/{model}.geo.json'),
                ('Texture', f'{NS}:textures/aircraft_stores/{model}.png'), ('ForwardYaw', 0), ('SpinDegreesPerTick', 0)]))]))]))

    def bomb(rel, name, model, mass, blast, tnt, dmg, ammo, *, copies=1, rack=None, rack_mass=None, spacing=None, rows=None):
        a, launch, g = F.anchors_for(model)
        b = OD([('Mode', 'DUMB'), ('Gravity', 0.08), ('DragMultiplier', 1), ('TurnDegreesPerTick', 0),
                ('BlastRadius', blast), ('TntEquivalentKg', tnt), ('BlastDamage', dmg)])
        extra = OD()
        if copies > 1:
            extra['MaxPerPylon'] = copies; extra['FixedRackCount'] = copies; extra['RackAdapter'] = rack; extra['RackSpacing'] = spacing
            if rows:
                extra['RackColumns'] = rows
            if rack_mass:
                extra['RackMassKg'] = rack_mass
        cat.add(rel, F.store(name, 'BOMB', model, a, Capacity=1, MassKg=mass, Bomb=b, AmmoItem=ammo,
                             ProjectileProfile=f'{NS}:aircraft_bombs/{model}', **extra, LaunchOffset=launch))

    bomb_profile('m117', 'm117'); bomb_profile('mk81', 'mk81'); bomb_profile('blu1', 'blu1')
    SF, MF = 'superbwarfare:small_freefall_bomb', 'superbwarfare:medium_freefall_bomb'
    # M117: WT wiki 362.4 kg each (5 x 1,812.1); filler 174 kg tritonal x1.35 as the pack's convention (87 kg x1.35 on the Mk 82);
    # radius and damage interpolated between the pack's Mk 82 (7 / 450) and Mk 83 (10 / 650): GUESS
    M117 = (9, 234.9, 600)
    bomb('munition/m117', f'M117 {DOT} 750 lb general purpose', 'm117', 362.4, *M117, MF)
    bomb('munition/m117_x3', f'M117 {DOT} 750 lb {DOT} TER triple rack', 'm117', 362.4, *M117, MF, copies=3, rack=ter_m117,
         spacing=[0.60, 0.45, 0], rack_mass=45.0)                                 # RackMassKg GUESS (TER-9A about 45 kg)
    # Mk 81: 250 lb, 118 kg; filler about 45 kg x1.35; radius and damage scaled down from the Mk 82: GUESS
    bomb('munition/mk81', f'Mk 81 {DOT} 250 lb freefall', 'mk81', 118.0, 5.5, 60.75, 300, SF)
    bomb('munition/mk81_x6', f'Mk 81 {DOT} 250 lb {DOT} MER six-bomb rack', 'mk81', 118.0, 5.5, 60.75, 300, SF, copies=6, rack=mer_mk81,
         spacing=[0.40, 0.38, 0], rows=2, rack_mass=83.4)
    # Mk 83 x3 on a TER: the pack's Mk 83 (model, profile, 454 kg) with body-side anchors, as the F/A-18E batch's mk83_x2
    src = load('munition/mk83')
    a83, launch83, _ = F.anchors_for('mk83')
    d = OD()
    for k, v in src.items():
        if k in ('MountAnchor', 'SideMountAnchors', 'MountAxis', 'LaunchOffset'):
            continue
        d[k] = v
        if k == 'ModelForward':
            d.update(a83)
    d['Name'] = f'Mk 83 {DOT} 1,000 lb freefall {DOT} TER triple rack'
    d['MaxPerPylon'] = 3; d['FixedRackCount'] = 3; d['RackAdapter'] = ter_mk83
    d['RackSpacing'] = [0.65, 0.5, 0]; d['RackMassKg'] = 45.0; d['LaunchOffset'] = launch83
    cat.add('munition/mk83_x3', d)
    # BLU-1: 320 kg, napalm like the pack's BLU-27 (70 kg TNT-eq at 401 kg) scaled by mass: GUESS
    bomb('munition/blu1', f'BLU-1 {DOT} 750 lb fire bomb', 'blu1', 320.0, 9, 56, 250, MF)
    bomb('munition/blu1_x2', f'BLU-1 {DOT} 750 lb fire bomb {DOT} twin rack', 'blu1', 320.0, 9, 56, 250, MF, copies=2,
         rack=adapter('rack_blu27_x2', st_blu1), spacing=[0.55, 0.4, 0])
    print('written', len(cat.items), 'stores; TER beam widths', w1, w2)


if __name__ == '__main__':
    main(sys.argv[1:])
