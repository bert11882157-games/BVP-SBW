#!/usr/bin/env python3
"""Store models and store definitions for the F-4E, F-4D and F-4B loadouts (after f4c_stores.py).

Built here, in the pack's aircraft-store format (nose -Z, one bone 'store', 1 block = 1 m):
  aim9j (+_x2)   the F-4D model's own AIM-9J, cut out at real size (3.10 m); WT AIM-9J numbers (F-4E rails)
  aim9d (+_x2)   built: AIM-9D (F-4B), 2.87 m x 0.127 m, pointed IR dome, canards, 0.56 m tail fins
  aim4d (+_x2)   built: AIM-4D Falcon (F-4D), 2.02 m x 0.163 m, four delta wings (0.508 m span), two on a LAU-42/A type rail per pylon
  aim7e2         the pack's AIM-7E airframe with the AIM-7E-2 mass (WT 193.7 kg)
  agm65b / agm65b_x3, agm65a / agm65a_x3   AGM-65A/B (TV): the pack's AGM-65 airframe and LAU-88 rack, TV presentation
  m118           built: M118 3,000 lb demolition bomb (3.35 m x 0.61 m, public figures; size and effects GUESS)
Not built: AIM-9P (not on WT's F-4E), AIM-7F (not on WT's F-4E), LAU-34 / AGM-45B-9/-10 (AGM-45A offered), M117 x5 rack.
Dimensions and numbers: work/elite_expansion_2/f4e/WT-f4e.md, f4d/WT-f4d.md, f4b/WT-f4b.md; guesses are marked GUESS.

Usage: python tools/munition_models/f4_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo files>   (and --check)
"""
import json
import os
import shutil
import sys
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import f16c_stores as F  # noqa: E402
import f4c_stores as C  # noqa: E402
import generate  # noqa: E402

NS, STORES, SBW, GEO, TEX = F.NS, F.STORES, F.SBW, F.GEO, F.TEX
OD = OrderedDict
DOT = '\u00b7'
WORK = r'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2'
NEW_GEO = []


def load(rel):
    with open(os.path.join(STORES, rel + '.json'), encoding='utf-8') as fh:
        return json.load(fh, object_pairs_hook=OD)


def import_cut(vid, src, name):
    C.CUT = os.path.join(WORK, vid, 'stores')
    C.import_cut(src, name)
    NEW_GEO.append(name)


def build_missile(name, spec):
    mesh, Lpx, rpx = generate.build(spec)
    F.write_json_compact(os.path.join(GEO, name + '.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}', False, Lpx))
    F.write_json_compact(os.path.join(GEO, name + '_flight.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}_flight', True, Lpx))
    generate.texture().save(os.path.join(TEX, name + '.png'))
    NEW_GEO.extend([name, name + '_flight'])


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True); os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()
    prof_dir = os.path.join(SBW, 'projectile_profiles')

    # ============================================================== AIM-9J (F-4E rails; F-4D model's own missile)
    import_cut('f4d', 'aim9j', 'aim9j')
    # WT us_aim9j_sidewinder: lockAngleMax 40 (the E's 10), rateMax 16.5 (12), loadFactorMax 20 (10), same 11 km seeker,
    # mass 76.93 kg, hbx_1 4.763 kg (the pack's AIM-9B/E TNT 7.6204). Store numbers are the AIM-9E's moved by those ratios and kept
    # under the AIM-9L (Range 1000, cone 32, turn 88): LockTicks, Cone, Turn, MaxSpeed GUESS.
    a, launch, g = F.anchors_for('aim9j', missile=True)
    guid = OD([('Mode', 'INFRARED'), ('LockTicks', 56), ('Range', 850), ('ConeDegrees', 28), ('CountermeasureVulnerability', 1.1)])
    flight = OD([('InitialSpeed', 0.5), ('MaxSpeed', 4.8), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 63), ('Damage', 25),
                 ('BlastRadius', 2.5), ('TntEquivalentKg', 7.6204)])
    cat.add('munition/aim9j', F.store(f'AIM-9J Sidewinder {DOT} improved infrared', 'AIR_TO_AIR', 'aim9j', a, Capacity=1, MassKg=76.9,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))
    cat.add('munition/aim9j_x2', F.store(f'AIM-9J Sidewinder {DOT} improved infrared {DOT} twin LAU-7 rails', 'AIR_TO_AIR', 'aim9j', a,
                                         Capacity=1, MassKg=76.9, Guidance=guid, Flight=flight,
                                         AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=2, FixedRackCount=2,
                                         RackSpacing=[0.66, 0.35, 0], LaunchOffset=launch))

    # ============================================================== AIM-9D (F-4B)
    # Navy AIM-9D: 2.87 m x 0.127 m, 88.5 kg, cooled PbS seeker under a pointed ogive, four canards, four 0.62 m tail fins with rollerons
    # (rollerons left out). Stats between the pack's AIM-9B and AIM-9E: GUESS (no WT unit).
    build_missile('aim9d', dict(length=2.87, diameter=0.127, nose=0.42, boattail=0.06, tail_r=0.8, bands=[(0.30, 0.31, 'yellow')],
                                radome=0.20, wings=[dict(root=(0.55, 0.70), tip=(0.60, 0.68), span=0.28 / 2, roll=45)],
                                fins=[dict(root=(2.36, 2.84), tip=(2.58, 2.84), span=0.62 / 2, roll=45)]))
    a, launch, g = F.anchors_for('aim9d', missile=True)
    guid = OD([('Mode', 'INFRARED'), ('LockTicks', 68), ('Range', 800), ('ConeDegrees', 20), ('CountermeasureVulnerability', 1.3)])
    flight = OD([('InitialSpeed', 0.5), ('MaxSpeed', 4.7), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 44), ('Damage', 25),
                 ('BlastRadius', 2.5), ('TntEquivalentKg', 7.6204)])
    cat.add('munition/aim9d', F.store(f'AIM-9D Sidewinder {DOT} cooled infrared', 'AIR_TO_AIR', 'aim9d', a, Capacity=1, MassKg=88.5,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))
    cat.add('munition/aim9d_x2', F.store(f'AIM-9D Sidewinder {DOT} cooled infrared {DOT} twin LAU-7 rails', 'AIR_TO_AIR', 'aim9d', a,
                                         Capacity=1, MassKg=88.5, Guidance=guid, Flight=flight,
                                         AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=2, FixedRackCount=2,
                                         RackSpacing=[0.66, 0.35, 0], LaunchOffset=launch))

    # ============================================================== AIM-4D Falcon (F-4D)
    # GAR-2B / AIM-4D: 2.02 m x 0.163 m, 0.508 m span (WT-f4d.md B3) (four delta wings at the tail), 61 kg, 3.4 kg warhead, IR. Stats GUESS (no WT unit).
    build_missile('aim4d', dict(length=2.02, diameter=0.163, nose=0.50, boattail=0.04, tail_r=0.9, bands=[(0.30, 0.31, 'yellow')],
                                radome=0.22, wings=[dict(root=(1.30, 1.98), tip=(1.62, 1.92), span=0.508 / 2, roll=45)], fins=[]))
    a, launch, g = F.anchors_for('aim4d', missile=True)
    guid = OD([('Mode', 'INFRARED'), ('LockTicks', 70), ('Range', 750), ('ConeDegrees', 20), ('CountermeasureVulnerability', 1.3)])
    flight = OD([('InitialSpeed', 0.5), ('MaxSpeed', 4.6), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 40), ('Damage', 20),
                 ('BlastRadius', 2.0), ('TntEquivalentKg', 4.4)])
    cat.add('munition/aim4d', F.store(f'AIM-4D Falcon {DOT} infrared', 'AIR_TO_AIR', 'aim4d', a, Capacity=1, MassKg=61.0,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))
    cat.add('munition/aim4d_x2', F.store(f'AIM-4D Falcon {DOT} infrared {DOT} twin LAU-42/A rails', 'AIR_TO_AIR', 'aim4d', a,
                                         Capacity=1, MassKg=61.0, Guidance=guid, Flight=flight,
                                         AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=2, FixedRackCount=2,
                                         RackSpacing=[0.60, 0.40, 0], LaunchOffset=launch))

    # ============================================================== AIM-7E-2: AIM-7E airframe, WT mass 193.7 kg
    d = load('munition/aim7e')
    d['Name'] = f'AIM-7E-2 Sparrow {DOT} dogfight, semi-active radar'
    d['MassKg'] = 193.7
    d['Guidance']['Range'] = 1400          # GUESS: the dogfight round trades range for a shorter minimum range
    d['Flight']['TurnDegreesPerSecond'] = 40
    cat.add('munition/aim7e2', d)

    # ============================================================== AGM-65A / AGM-65B (TV): the pack's AGM-65 airframe and LAU-88 rack
    # WT us_agm_65b: mass 210.467 kg, comp_b 39.08 kg (x1.33 = 51.97 kg TNT), seeker fov 0.1, machMax 0.9. The AGM-65A has the same
    # airframe and mass class (about 210 kg). Presentation 'TV' like the pack's Walleye / GBU-8; Range, Cone, MaxSpeed GUESS.
    for mid, label in (('a', 'AGM-65A'), ('b', 'AGM-65B')):
        s = load('munition/agm65g')
        s['Name'] = f'{label} Maverick {DOT} TV'
        s['MassKg'] = 210.5
        s['Guidance'] = OD([('Mode', 'GROUND_INFRARED'), ('Presentation', 'TV'), ('LockTicks', 60), ('Range', 700), ('ConeDegrees', 20),
                            ('CountermeasureVulnerability', 0)])
        s['Flight']['MaxSpeed'] = 9
        s['Flight']['TntEquivalentKg'] = 51.97
        s['Flight']['Damage'] = 1300
        cat.add(f'munition/agm65{mid}', s)
        r = load('munition/agm65d_x3')
        r['Name'] = f'{label} Maverick {DOT} TV {DOT} LAU-88 triple rack'
        r['MassKg'] = 210.5
        r['Guidance'] = s['Guidance']
        r['Flight']['MaxSpeed'] = 9
        r['Flight']['TntEquivalentKg'] = 51.97
        r['Flight']['Damage'] = 1300
        cat.add(f'munition/agm65{mid}_x3', r)

    # ============================================================== M118 3,000 lb
    # WT f-4e us_3000lb_m118: 1,369.8 kg loaded (the wiki mass). Real M118: about 3.35 m x 0.61 m (public figures, UNVERIFIED in the
    # briefs); fins, band and lug positions GUESS. Filler about 770 kg tritonal x1.35 = 1,040 kg TNT-eq, radius and damage scaled up from
    # the Mk 84 (14 / 900): GUESS.
    m, L = C.build_ldgp(3.35, 0.61, 0.85, 2.30, 0.26, (2.60, 3.33), 0.40, (1.45, 1.55), 1.60, lug_gap=0.76)
    F.save_model('m118', m)
    NEW_GEO.append('m118')
    F.write_json(os.path.join(prof_dir, 'aircraft_bombs', 'm118.json'), OD([
        ('VisualProfile', f'{NS}:authored_bomb'), ('MotionSync', 'INHERIT'), ('TrailMode', 'SUPPRESS'), ('RenderScale', 1),
        ('Extensions', OD([(f'{NS}:projectile_mesh_v1', OD([
            ('Model', f'{NS}:custom_geo/aircraft_stores/m118.geo.json'),
            ('Texture', f'{NS}:textures/aircraft_stores/m118.png'), ('ForwardYaw', 0), ('SpinDegreesPerTick', 0)]))]))]))
    a, launch, g = F.anchors_for('m118')
    b = OD([('Mode', 'DUMB'), ('Gravity', 0.08), ('DragMultiplier', 1), ('TurnDegreesPerTick', 0), ('BlastRadius', 17),
            ('TntEquivalentKg', 1040.0), ('BlastDamage', 1080)])
    cat.add('munition/m118', F.store(f'M118 {DOT} 3,000 lb demolition', 'BOMB', 'm118', a, Capacity=1, MassKg=1369.8, Bomb=b,
                                     AmmoItem='superbwarfare:large_freefall_bomb', ProjectileProfile=f'{NS}:aircraft_bombs/m118',
                                     LaunchOffset=launch))
    print('written', len(cat.items), 'stores; new geo files:', ' '.join(NEW_GEO))


if __name__ == '__main__':
    main(sys.argv[1:])
