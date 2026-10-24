#!/usr/bin/env python3
"""Store models and store definitions for the FC-31 Gyrfalcon (fc_31), after mirage4_stores.py.

The FC-31 is not in War Thunder (no fc_31 / j_35 flightmodels file, wiki.warthunder.com/unit/fc_31 is a 404), so the loads are the
publicly reported ones (Wikipedia: Shenyang FC-31; Chinese stand-off weapons: LS-6 family). Guesses are marked GUESS.

  pl15       PL-15 beyond-visual-range missile (active radar). Numbers on the pack's PL-12 model (no new geometry, so it is NOT marked
             (AI); the real PL-15 is 4.0 m long with rectangular tail fins, the PL-12 model is 3.93 m): 200 kg, 4.0 m.
  ls6_500    LS-6-500 GPS/INS glide bomb (Leishi-6, 500 kg class, folding wing kit). Built here (AI): 3.0 m x 0.37 m and the wing span are
             GUESSES (no published drawings found); numbers as the pack's 500 kg-class bombs (Mk 83: 272 kg TNT equivalent).
Existing stores offered unchanged: pl10, pl12, tank_300gal (the F-16C 300 US gal tank, about 1,130 l: the FC-31's reported 1,100 l tank).

Usage: python tools/munition_models/fc31_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo file>
"""
import json
import os
import sys
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import f16c_stores as F  # noqa: E402
from f16c_stores import build_jdam  # noqa: E402

NS, STORES, GEO = F.NS, F.STORES, F.GEO


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(F.TEX, exist_ok=True)
    cat = F.Catalogue()

    # ---- PL-15: the PL-12 store with the PL-15's figures
    src = json.load(open(os.path.join(STORES, 'munition/pl12.json'), encoding='utf-8'), object_pairs_hook=OrderedDict)
    d = OrderedDict(src)
    d['Name'] = 'PL-15 · long range active radar'
    d['MassKg'] = 200
    d['Guidance'] = OrderedDict([('Mode', 'ACTIVE_RADAR'), ('LockTicks', 0), ('Range', 4096), ('ConeDegrees', 25),
                                 ('CountermeasureVulnerability', 1.1)])
    d['Flight'] = OrderedDict([('InitialSpeed', 0.5), ('MaxSpeed', 15), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 70),
                               ('Damage', 55), ('BlastRadius', 4), ('TntEquivalentKg', 12.0)])
    cat.add('munition/pl15', d)

    # ---- LS-6-500 guided glide bomb (GUESS size)
    m, L = build_jdam(3.0, 0.37, 0.70, 2.05, 0.50, (1.10, 1.95, 0.05), wing_kit=(1.20, 2.00, 0.60),
                      er_fins=((2.25, 2.85), (2.45, 2.85), 0.22), lug_z=1.35)
    F.save_model('ls6_500', m)
    a, launch, g = F.anchors_for('ls6_500')
    F.write_json(os.path.join(F.SBW, 'projectile_profiles', 'aircraft_bombs', 'ls6_500.json'), OrderedDict([
        ('VisualProfile', f'{NS}:authored_bomb'), ('MotionSync', 'INHERIT'), ('TrailMode', 'SUPPRESS'), ('RenderScale', 1),
        ('Extensions', OrderedDict([(f'{NS}:projectile_mesh_v1', OrderedDict([
            ('Model', f'{NS}:custom_geo/aircraft_stores/ls6_500.geo.json'),
            ('Texture', f'{NS}:textures/aircraft_stores/ls6_500.png'), ('ForwardYaw', 0), ('SpinDegreesPerTick', 0)]))]))]))
    b = OrderedDict([('Mode', 'GPS'), ('Gravity', 0.08), ('DragMultiplier', 1), ('TurnDegreesPerTick', 2), ('BlastRadius', 10),
                     ('TntEquivalentKg', 272.43), ('BlastDamage', 650), ('LiftToDrag', 8), ('BestGlideSpeed', 6), ('MaxG', 3)])
    cat.add('munition/ls6_500', F.store('LS-6-500 · 500 kg GPS/INS glide bomb', 'BOMB', 'ls6_500', a, Capacity=1, MassKg=520.0,
                                        Bomb=b, AmmoItem='superbwarfare:small_guided_bomb',
                                        ProjectileProfile=f'{NS}:aircraft_bombs/ls6_500', LaunchOffset=launch))
    print('written', len(cat.items), 'stores: pl15, ls6_500 (new geo: ls6_500)')


if __name__ == '__main__':
    main(sys.argv[1:])
