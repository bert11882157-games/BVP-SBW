#!/usr/bin/env python3
"""Store models and store definitions for the Xi'an H-6K (h_6k), after vulcan_stores.py.

War Thunder has no H-6 / H-6K (checked 2026-10-23: no h_6 / h_6k flightmodels file in the gszabi99 datamine, only a forum suggestion thread), so the loads
are the publicly reported ones (Wikipedia: Xi'an H-6, CJ-10, YJ-12; Sinodefence). Guesses are marked GUESS.
Built here, in the pack's aircraft-store format (nose -Z, one bone 'store', 1 block = 1 m). Generated geometry, so each display name ends in " (AI)"
(owner rule 2026-10-23):
  cj10a    CJ-10A / CJ-10K (the air-launched KD-20 class) land-attack cruise missile: INS + satellite navigation + TERCOM / DSMAC, 500 kg warhead,
           range 1,500 to 2,000 km (Wikipedia, CJ-10). Body 6.0 m x 0.52 m, ventral turbofan scoop, folding mid wings (shown deployed), X tail:
           dimensions and the 1,300 kg mass are GUESSES. Pack numbers: the AGM-86C CALCM (the same GPS land-attack cruise class).
  yj12     YJ-12 supersonic anti-ship missile: integral ramjet / booster, BeiDou with mid-course updates and active radar terminal homing,
           Mach 2.5 to 4, range 460 to 500 km, warhead 205 to 500 kg (Wikipedia, YJ-12). Body 6.5 m x 0.70 m, two ramjet intakes, four short wings,
           X tail: dimensions, 2,500 kg and the 300 kg warhead are GUESSES. Pack numbers: scaled from the Kh-32 (Mach 4.6 = speed 14).
Cut from the supplied model (not generated, unmarked):
  c201     the H-6K model's own inner baked store (toolbox group "Import c201", four of them), cut by work/elite_expansion_2/tools/cut_stores.py:
           a turbojet anti-ship missile (ventral intake, cranked mid wings, X tail). Identified by the modeller's tag as the C-201 (HY-2 family,
           C-201W turbojet version); the figures (2,400 kg, 500 kg warhead, 400 km, subsonic, active radar) are GUESSES.
The outer baked store (toolbox group "Import KD-88") is the pack's own munition/kd88 (and kd88_ir): nothing is cut for it.
Nuclear weapons are left out (owner brief 2026-10-23).

Usage: python tools/munition_models/h6k_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo files>   (and --check)
"""
import os
import shutil
import sys
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import f16c_stores as F  # noqa: E402
from f16c_stores import Mesh, revolve, cruciform, lugs, centred, ogive  # noqa: E402

NS, STORES, SBW, GEO, TEX = F.NS, F.STORES, F.SBW, F.GEO, F.TEX
OD = OrderedDict
DOT = '·'
NEW_GEO_FILES = ['cj10a', 'cj10a_flight', 'yj12', 'yj12_flight']
CUT_DIR = os.environ.get('H6K_CUT_SOURCE', r'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2\h6k\stores_work\out')


def build_cj10a(L=6.0, d=0.52):
    """Cruise missile: ogive radome nose, cylinder, boat-tail, ventral turbofan scoop, two folding mid wings (deployed), four X tail fins."""
    r = d / 2
    m = Mesh()
    nose, tail = 0.95, 0.40
    prof = [(0.0, 0.05)] + ogive(nose, 0.05, r)[1:] + [(L - tail, r), (L, 0.55 * r)]

    def col(s):
        if s < 0.45:
            return 'dgrey'
        return 'grey' if s < L - tail else 'dgrey'
    revolve(m, prof, col, cuts=[0.45, L - tail], caps=('dgrey', 'black'))
    cruciform(m, (2.55, 3.45), (3.05, 3.45), r * 0.95, 0.80, 0.014, 'grey', roll=0, count=2)          # mid wings, span 1.6 m (GUESS)
    cruciform(m, (L - 0.80, L - 0.05), (L - 0.50, L - 0.05), r * 0.6, 0.40, 0.014, 'dgrey', roll=45)      # X tail, span 0.8 m (GUESS)
    m.box(-0.10, 0.10, -r - 0.17, -r * 0.9, 3.55, 4.55, 'black')                                          # ventral intake scoop
    m.box(-0.025, 0.025, -r * 0.95, -r * 0.5, 3.60, 4.50, 'dgrey')
    lugs(m, 2.9, 0.356, r)
    return centred(m, L), L


def build_yj12(L=6.5, d=0.70):
    """Ramjet anti-ship missile: long ogive nose, cylinder, two ramjet intake blisters, four short mid wings, X tail."""
    r = d / 2
    m = Mesh()
    nose, tail = 1.60, 0.50
    prof = [(0.0, 0.06)] + ogive(nose, 0.06, r)[1:] + [(L - tail, r), (L, 0.70 * r)]

    def col(s):
        if s < 0.30:
            return 'black'
        return 'white' if s < L - tail else 'dgrey'
    revolve(m, prof, col, cuts=[0.30, L - tail], caps=('black', 'dgrey'))
    cruciform(m, (3.0, 3.8), (3.35, 3.8), r * 0.97, 0.65, 0.016, 'white', roll=45)                      # four mid wings, span 1.3 m (GUESS)
    cruciform(m, (L - 1.05, L - 0.10), (L - 0.65, L - 0.10), r * 0.7, 0.60, 0.016, 'dgrey', roll=45)    # X tail, span 1.2 m (GUESS)
    for sx in (-1, 1):                                                                                  # two ramjet intakes on the flanks
        x0, x1 = (r - 0.02, r + 0.12) if sx > 0 else (-r - 0.12, -r + 0.02)
        m.box(x0, x1, -0.14, 0.14, 2.20, 3.00, 'black')
    lugs(m, 3.2, 0.356, r)
    return centred(m, L), L


def recentre_cut(name, z_probe=-2.0):
    """Put the body axis of the cut c201 on x = y = 0 (F.recentre's body-axis fit is thrown off by the ventral engine and the wings): the axis is the
    middle of the body's section at z_probe (a plain cylinder stretch, x +-0.38, y -0.78..0.13 before the shift), then write the flight model."""
    import json
    import numpy as np
    path = os.path.join(GEO, name + '.geo.json')
    g = json.load(open(path, encoding='utf-8'))
    pm = g['minecraft:geometry'][0]['bones'][0]['poly_mesh']
    P = np.array(pm['positions'], float)
    pts = []
    for poly in pm['polys']:
        idx = [v[0] for v in poly]
        for a, b in zip(idx, idx[1:] + idx[:1]):
            pa, pb = P[a], P[b]
            if (pa[2] - z_probe * 16) * (pb[2] - z_probe * 16) < 0:
                t = (z_probe * 16 - pa[2]) / (pb[2] - pa[2])
                pts.append(pa + t * (pb - pa))
    pts = np.array(pts)
    cx, cy = (pts[:, 0].min() + pts[:, 0].max()) / 2, (pts[:, 1].min() + pts[:, 1].max()) / 2
    pm['positions'] = [[round(p[0] - cx, 4), round(p[1] - cy, 4), p[2]] for p in pm['positions']]
    F.write_json_compact(path, g)
    F.write_json_compact(os.path.join(GEO, name + '_flight.geo.json'), F.flight_geo(g, name + '_flight'))
    print(name, 'body axis moved by', round(cx / 16, 4), round(cy / 16, 4), 'm')


def load(rel):
    import json
    with open(os.path.join(STORES, rel + '.json'), encoding='utf-8') as fh:
        return json.load(fh, object_pairs_hook=OD)


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()

    # ---- CJ-10A: GPS / INS land-attack cruise missile (the pack's CRUISE class without a seeker, like the AGM-86C CALCM)
    m, L = build_cj10a()
    F.save_model('cj10a', m, flight=True)
    a, launch, g = F.anchors_for('cj10a', missile=True)
    flight = OD([('InitialSpeed', 0.3), ('MaxSpeed', 2.8), ('AccelerationPerTick', 0.025), ('TurnDegreesPerSecond', 36), ('Damage', 150),
                 ('BlastRadius', 12), ('TntEquivalentKg', 600), ('Range', 2000)])
    cat.add('munition/cj10a', F.store(f'CJ-10A {DOT} GPS/INS land-attack cruise missile', 'CRUISE', 'cj10a', a, Capacity=1, MassKg=1300,
                                      Flight=flight, AmmoItem='superbwarfare:cruise_missile', LaunchOffset=launch))
    print('cj10a anchors', a['MountAnchor'], a['MountAxis'], 'launch', launch)

    # ---- YJ-12: supersonic anti-ship missile, active radar terminal homing (the pack's ACTIVE_SURFACE_RADAR class, like the Kh-32)
    m, L = build_yj12()
    F.save_model('yj12', m, flight=True)
    a, launch, g = F.anchors_for('yj12', missile=True)
    guid = OD([('Mode', 'ACTIVE_SURFACE_RADAR'), ('LockTicks', 80), ('Range', 1200), ('ConeDegrees', 30), ('CountermeasureVulnerability', 0.25)])
    flight = OD([('InitialSpeed', 1), ('MaxSpeed', 9.5), ('AccelerationPerTick', 0.2), ('TurnDegreesPerSecond', 20), ('Damage', 900),
                 ('BlastRadius', 14), ('TntEquivalentKg', 300.0), ('Range', 1200)])
    cat.add('munition/yj12', F.store(f'YJ-12 {DOT} supersonic active-radar anti-ship', 'CRUISE', 'yj12', a, Capacity=1, MassKg=2500,
                                     Guidance=guid, Flight=flight, AmmoItem='superbwarfare:large_cruise_missile', LaunchOffset=launch))
    print('yj12 anchors', a['MountAnchor'], a['MountAxis'], 'launch', launch)

    # ---- C-201: the model's own inner baked store, cut from H-6K.mtb (human-made: not generated, not marked)
    shutil.copyfile(os.path.join(CUT_DIR, 'h6k_inner', 'h6k_inner.geo.json'), os.path.join(GEO, 'c201.geo.json'))
    shutil.copyfile(os.path.join(CUT_DIR, 'h6k_inner', 'h6k_inner.png'), os.path.join(TEX, 'c201.png'))
    recentre_cut('c201')
    # the anchors are set by hand: the body radius is 0.4539 m (the automatic fit reads the cranked wing root at the lug station as the body)
    R_C201, Z_C201 = 0.4539, 0.0       # the baked store hung with its centre under the pylon (pylon z -106..-26, store z -146..17), so the anchor is the centre
    a, launch, g = F.anchors_for('c201', missile=True, manual={'top': [0, R_C201, Z_C201], 'left': [R_C201, 0, Z_C201],
                                                                'right': [-R_C201, 0, Z_C201], 'axis': [0, 0, Z_C201]})
    guid = OD([('Mode', 'ACTIVE_SURFACE_RADAR'), ('LockTicks', 60), ('Range', 820), ('ConeDegrees', 30), ('CountermeasureVulnerability', 0.6)])
    flight = OD([('InitialSpeed', 0.3), ('MaxSpeed', 3.0), ('AccelerationPerTick', 0.03), ('TurnDegreesPerSecond', 24), ('Damage', 150),
                 ('BlastRadius', 12), ('TntEquivalentKg', 500.0), ('Range', 820)])
    cat.add('munition/c201', F.store(f'C-201 {DOT} active-radar anti-ship cruise missile', 'CRUISE', 'c201', a, Capacity=1, MassKg=2400,
                                     Guidance=guid, Flight=flight, AmmoItem='superbwarfare:large_cruise_missile', LaunchOffset=launch))
    print('c201 anchors', a['MountAnchor'], a['MountAxis'], 'launch', launch)
    print('written; new generated geo files:', ' '.join(NEW_GEO_FILES), '; cut: c201 (+ c201_flight)')


if __name__ == '__main__':
    main(sys.argv[1:])
