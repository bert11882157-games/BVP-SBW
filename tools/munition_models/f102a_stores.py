#!/usr/bin/env python3
"""Store models and store definitions for the F-102A Delta Dagger (f_102a), after f4_stores.py and mirage4_stores.py.

War Thunder's F-102A (datamine f_102a_late.blkx, work/elite_expansion_2/f102a/WT-f102a.md) carries AIM-4G (infrared) and AIM-4F (semi-active
radar) Super Falcons, 24 Mighty Mouse FFAR in the bay doors and two 215 US gal drop tanks. Built here, in the pack's aircraft-store format
(nose -Z, one bone 'store', 1 block = 1 m); guesses are marked GUESS.

  tank_215gal   the F-102A model's own wing tank, cut out at real size (4.70 m x 0.65 m, WT us_215gal_drop_tank 4.7 m x 0.65 m; work/elite_expansion_2/
                f102a/stores). Cut from a supplied model, so NOT marked (AI). 120 kg tank (WT) + 215 US gal of JP-4 = 755 kg.
  aim4g, aim4f  built (AI): WT us_aim4g_falcon (2.0955 m, 0.1687 m, 66.2 kg, HBX-3 2.268 kg, seeker 11 km, 27 G, lockAngleMax 15) and
                us_aim4f_falcon (2.19 m, 68.9 kg, radar seeker, same warhead). Wing span 0.61 m GUESS (the Super Falcon's wings are larger than the
                AIM-4D's 0.508 m). Store numbers (Range, Cone, LockTicks, Turn, MaxSpeed) are the AIM-4D's moved by the WT ratios: GUESS.
  aim4a, aim4c  built (AI), real-world early loads beyond WT (the initial F-102A load was three pairs of GAR-1/2/3/4, Wikipedia F-102):
                AIM-4A / GAR-1 (SARH, 1.98 m, 54.4 kg) and AIM-4C / GAR-2A (infrared, 2.02 m, 61.6 kg), on the AIM-4D's airframe model
                (length 2.02 m, so the AIM-4A is 4 cm too long). Numbers GUESS (no WT unit).
  ffar_doors    the 24 x 2.75 in Mk 4 / M151 Mighty Mouse FFAR tubes in the two bay doors (12 a door; WT us_2_75_in_ffar_mighty_mouse_m151: 8.936 kg,
                comp_b 1.043 kg). A rocket store for the internal bay (internal mounts draw nothing), on the pack's LAU-3/A model; native weapon FfarDoors.
Not built: the nuclear AIM-26A (GAR-11) Falcon (not in WT, no conventional stand-in).
Existing stores offered unchanged: aim4d (AIM-4D, from the F-4D work).

Usage: python tools/munition_models/f102a_stores.py [--write]
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
import generate  # noqa: E402

NS, STORES, GEO, TEX = F.NS, F.STORES, F.GEO, F.TEX
OD = OrderedDict
CUT = os.environ.get('F102A_STORE_SOURCE', r'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2\f102a\stores')
NEW_GEO = []
DOT = '\u00b7'


def import_cut(src, name):
    """Copy a part cut from the F-102A model (geo + png); one bone 'store', nose -Z, body on the origin."""
    g = json.load(open(os.path.join(CUT, src, src + '.geo.json'), encoding='utf-8'))
    g['minecraft:geometry'][0]['description']['identifier'] = 'geometry.aircraft_store.' + name
    F.write_json_compact(os.path.join(GEO, name + '.geo.json'), g)
    shutil.copyfile(os.path.join(CUT, src, src + '.png'), os.path.join(TEX, name + '.png'))
    NEW_GEO.append(name)
    return name


def build_missile(name, spec):
    mesh, Lpx, rpx = generate.build(spec)
    F.write_json_compact(os.path.join(GEO, name + '.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}', False, Lpx))
    F.write_json_compact(os.path.join(GEO, name + '_flight.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}_flight', True, Lpx))
    generate.texture().save(os.path.join(TEX, name + '.png'))
    NEW_GEO.extend([name, name + '_flight'])
    F.mark_generated(name, name + '_flight')


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()

    # ============================================================== 215 US gal tank (cut from the model)
    import_cut('tank_215gal', 'tank_215gal')
    a, launch, g = F.anchors_for('tank_215gal')
    # the lug search lands 0.46 m ahead of the tank's middle (the cut tank has no separate lug); hang it from its middle, as modelled
    a['MountAnchor'][2] = 0.0
    a['MountAxis'][2] = 0.0
    for side in a['SideMountAnchors'].values():
        side[2] = 0.0
    cat.add('munition/tank_215gal', F.store('215 US gal drop tank', 'VISUAL_ONLY', 'tank_215gal', a, Capacity=1, MassKg=755.0, LaunchOffset=launch))

    # ============================================================== AIM-4G / AIM-4F Super Falcons (WT f_102a_late)
    # Wing span 0.61 m GUESS. WT: both mass 66.2 / 68.9 kg, HBX-3 2.268 kg (x1.6 as the pack's AIM-9 warheads: 3.63 kg TNT), 27 G.
    # AIM-4G seeker: lockAngleMax 15, angleMax 30, rateMax 12, 11 km (the AIM-4D store is Range 750, Cone 20, LockTicks 70, Turn 40, MaxSpeed 4.6):
    # Range 800, Cone 20, LockTicks 62, Turn 52 (27 G against the D's), MaxSpeed 5.0 (WT machMax 3.0): GUESS.
    # AIM-4F radar seeker: receiver 9 km / 15 km max, lock 0.85 s, angleMax 25: Range 1100, Cone 25, no lock ticks (the radar holds it), vulnerability 0.8.
    build_missile('aim4g', dict(length=2.0955, diameter=0.1687, nose=0.52, boattail=0.04, tail_r=0.9, bands=[(0.30, 0.31, 'yellow')],
                                radome=0.23, wings=[dict(root=(1.34, 2.05), tip=(1.66, 2.00), span=0.61 / 2, roll=45)], fins=[]))
    build_missile('aim4f', dict(length=2.19, diameter=0.1687, nose=0.60, boattail=0.04, tail_r=0.9,
                                bands=[(0.28, 0.29, 'yellow'), (0.31, 0.32, 'brown')],
                                radome=0.40, wings=[dict(root=(1.42, 2.14), tip=(1.74, 2.09), span=0.61 / 2, roll=45)], fins=[]))
    warhead = OD([('Damage', 20), ('BlastRadius', 2.0), ('TntEquivalentKg', 3.63)])
    a, launch, g = F.anchors_for('aim4g', missile=True)
    guid = OD([('Mode', 'INFRARED'), ('LockTicks', 62), ('Range', 800), ('ConeDegrees', 20), ('CountermeasureVulnerability', 1.2)])
    flight = OD([('InitialSpeed', 0.5), ('MaxSpeed', 5.0), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 52)] + list(warhead.items()))
    cat.add('munition/aim4g', F.store(f'AIM-4G Super Falcon {DOT} rear-aspect infrared', 'AIR_TO_AIR', 'aim4g', a, Capacity=1, MassKg=66.2,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))
    a, launch, g = F.anchors_for('aim4f', missile=True)
    guid = OD([('Mode', 'SEMI_ACTIVE_RADAR'), ('LockTicks', 0), ('Range', 1100), ('ConeDegrees', 25), ('CountermeasureVulnerability', 0.8)])
    flight = OD([('InitialSpeed', 0.5), ('MaxSpeed', 5.0), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 52)] + list(warhead.items()))
    cat.add('munition/aim4f', F.store(f'AIM-4F Super Falcon {DOT} semi-active radar', 'AIR_TO_AIR', 'aim4f', a, Capacity=1, MassKg=68.9,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))

    # ============================================================== AIM-4A (SARH) and AIM-4C (IR): real-world early loads, the AIM-4D's airframe
    a, launch, g = F.anchors_for('aim4d', missile=True)
    guid = OD([('Mode', 'SEMI_ACTIVE_RADAR'), ('LockTicks', 0), ('Range', 700), ('ConeDegrees', 25), ('CountermeasureVulnerability', 1.0)])
    flight = OD([('InitialSpeed', 0.5), ('MaxSpeed', 4.4), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 32), ('Damage', 20),
                 ('BlastRadius', 2.0), ('TntEquivalentKg', 4.4)])
    cat.add('munition/aim4a', F.store(f'AIM-4A Falcon {DOT} semi-active radar', 'AIR_TO_AIR', 'aim4d', a, Capacity=1, MassKg=54.4,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))
    guid = OD([('Mode', 'INFRARED'), ('LockTicks', 76), ('Range', 650), ('ConeDegrees', 18), ('CountermeasureVulnerability', 1.4)])
    flight = OD([('InitialSpeed', 0.5), ('MaxSpeed', 4.5), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 36), ('Damage', 20),
                 ('BlastRadius', 2.0), ('TntEquivalentKg', 4.4)])
    cat.add('munition/aim4c', F.store(f'AIM-4C Falcon {DOT} infrared', 'AIR_TO_AIR', 'aim4d', a, Capacity=1, MassKg=61.6,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:small_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))

    # ============================================================== 24 FFAR in the bay doors (WT 2 x 12 M151)
    with open(os.path.join(STORES, 'munition', 'lau3a_ffar.json'), encoding='utf-8') as fh:
        base = json.load(fh, object_pairs_hook=OD)
    a = OD((k, base[k]) for k in ('MountAnchor', 'SideMountAnchors', 'MountAxis'))
    launch = base['LaunchOffset']
    cat.add('munition/ffar_doors', F.store(f'Mk 4 FFAR bay door tubes {DOT} 2 x 12 FFAR 2.75 in', 'ROCKET_POD', 'lau3a_ffar', a, Capacity=24, Scale=1,
                                           MassKg=8.936, AmmoItem='superbwarfare:small_unguided_rocket', LaunchOffset=launch))
    print('written', len(cat.items), 'stores; new geo files:', ' '.join(NEW_GEO))


if __name__ == '__main__':
    main(sys.argv[1:])
