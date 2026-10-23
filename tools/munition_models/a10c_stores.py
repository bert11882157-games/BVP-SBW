#!/usr/bin/env python3
"""Store models and store definitions for the War Thunder A-10C loadout (third batch after f16c_stores.py and fa18e_stores.py).

Reuses the builders of f16c_stores.py (Mesh, revolve, lugs, pod and cluster builders, rack adapters, anchor derivation
with tools/aircraft_attach/munitions.py). The tank and the LAU-105 rail adapter are the A-10C model's own parts, cut out
at real size (work/elite_expansion_2/a10c/stores); everything else is built here. Dimensions come from the War Thunder
brief (work/elite_expansion_2/a10c/WT-a10c.md, sections 3 and 4) or public figures; guesses are marked GUESS below.

Not built (already in the pack): AIM-9L/M singles, GBU-12/24/31/38, Mk 82, Mk 84, AGM-65D x3, APKWS 3 x 7, LAU-68 single.
Not built (WT has none): the ECM pod. It stays set aside (a10c/stores/ecm_pod_a10).

Usage: python tools/munition_models/a10c_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py   (and --check)
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
from f16c_stores import Mesh, revolve, lugs, centred, ogive, build_pod, build_cluster  # noqa: E402

NS, STORES, SBW, GEO, TEX, PX = F.NS, F.STORES, F.SBW, F.GEO, F.TEX, F.PX
munitions = F.munitions
OD = OrderedDict
CUT = os.environ.get('A10C_STORE_SOURCE', r'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2\a10c\stores')


def load(rel):
    with open(os.path.join(STORES, rel + '.json'), encoding='utf-8') as fh:
        return json.load(fh, object_pairs_hook=OD)


# ------------------------------------------------------------------------------------------------ cut parts
def import_cut(src, name):
    """Copy a part cut from the A-10C model (geo + png); one bone 'store', nose -Z, body on the origin."""
    g = json.load(open(os.path.join(CUT, src, src + '.geo.json'), encoding='utf-8'))
    g['minecraft:geometry'][0]['description']['identifier'] = 'geometry.aircraft_store.' + name
    F.write_json_compact(os.path.join(GEO, name + '.geo.json'), g)
    shutil.copyfile(os.path.join(CUT, src, src + '.png'), os.path.join(TEX, name + '.png'))
    return name


def file_points(name):
    """Vertices of a written model in store blocks (file frame, geo / 16)."""
    g = json.load(open(os.path.join(GEO, name + '.geo.json'), encoding='utf-8'))
    return np.array(g['minecraft:geometry'][0]['bones'][0]['poly_mesh']['positions']) / PX


# ------------------------------------------------------------------------------------------------ models
def build_retarded(L, d, nose, tail_at, tail_r, band, lug_gap, lug_z, ballute=None):
    """Mk 82 / Mk 84 with a ballute retarder tail (BSU-49 / BSU-50): LDGP body, no fins, round tail container;
    `ballute` = (length, end radius) adds the opened ballute behind it (the in-flight model), as build_mk83_air does."""
    r = d / 2
    m = Mesh()
    prof = [(0.0, 0.06)] + ogive(nose, 0.06, r)[1:] + [(tail_at, r), (L, tail_r)]

    def col(s):
        if band[0] <= s < band[1]:
            return 'yellow'
        return 'olive' if s < tail_at else 'dgrey'
    revolve(m, prof, col, cuts=[band[0], band[1], tail_at], caps=('dgrey', 'dgrey'))
    total = L
    if ballute:
        bl, br = ballute
        for k in range(F.SEG):
            a0, a1 = 2 * math.pi * k / F.SEG, 2 * math.pi * (k + 1) / F.SEG
            p = [np.array([tail_r * math.cos(a), tail_r * math.sin(a), L]) for a in (a0, a1)]
            q = [np.array([br * math.cos(a), br * math.sin(a), L + bl]) for a in (a0, a1)]
            m.poly([p[0], p[1], q[1], q[0]], 'orange')
            m.poly([q[0], q[1], p[1], p[0]], 'orange')
        total = L + bl
    lugs(m, lug_z, lug_gap, r)
    return centred(m, total), total


# ------------------------------------------------------------------------------------------------ main
def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True); os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()
    prof_dir = os.path.join(SBW, 'projectile_profiles')
    gun_dir = os.path.join(SBW, 'guns/aircraft_stores')
    F.palette_png().save(os.path.join(TEX, 'a10c_rack.png'))

    # ============================================================== models
    # the model's own 600 gal tank (6.14 m x 0.70 m, longer and fatter than WT's 4.7 m tank file; kept as the model has it)
    # and its LAU-105 dual rail adapter (flat plate 0.55 m wide, two rails 1.65 m long)
    import_cut('tank_600gal', 'tank_600gal')
    import_cut('rack_lau105_x2', 'rack_lau105_x2')
    # Mk 82 AIR (BSU-49) and Mk 84 AIR (BSU-50): lengths GUESS (Mk 82 2.21 m + kit, Mk 84 3.84 m + kit); tail radius GUESS;
    # opened ballute size GUESS (the Mk 83 AIR's 0.52 m scaled by diameter)
    m, L = build_retarded(2.30, 0.273, 0.45, 1.72, 0.145, (0.80, 0.86), 0.356, 1.10); F.save_model('mk82_air', m)
    m, L = build_retarded(2.30, 0.273, 0.45, 1.72, 0.145, (0.80, 0.86), 0.356, 1.10, ballute=(0.85, 0.38)); F.save_model('mk82_air_deployed', m)
    m, L = build_retarded(3.90, 0.458, 0.85, 3.00, 0.25, (1.30, 1.40), 0.762, 1.80); F.save_model('mk84_air', m)
    m, L = build_retarded(3.90, 0.458, 0.85, 3.00, 0.25, (1.30, 1.40), 0.762, 1.80, ballute=(1.40, 0.68)); F.save_model('mk84_air_deployed', m)
    # TER-9A with three LAU-131A pods (3 x 7 Hydra 70) as one cluster, and a single 7-round LAU-131 pod (APKWS)
    m, L, c = build_cluster(1.806, 0.257, 7, 0.0825, 0.27); F.save_model('lau68_hydra_x3', m)
    m, L = build_pod(1.806, 0.257, 7, 0.0825); m = centred(m, L); F.save_model('lau131_apkws_x1', m)

    # ============================================================== racks
    # LAU-105: the AIM-9s hang on the outer faces of the two rails; the pylon touches the plate top (all read from the cut)
    P = file_points('rack_lau105_x2')
    x_out = float(np.abs(P[:, 0]).max()); y_top = float(P[:, 1].max()); y_mid = float(0.5 * (P[:, 1].min() + y_top))
    z_mid = float(0.5 * (P[:, 2].min() + P[:, 2].max()))
    top = munitions.to_anchor_frame([0.0, y_top, z_mid])
    lau105 = OD([('Model', f'{NS}:custom_geo/aircraft_stores/rack_lau105_x2.geo.json'),
                 ('Texture', f'{NS}:textures/aircraft_stores/rack_lau105_x2.png'),
                 ('MountAnchor', F.vec(top)),
                 ('Stations', [OD([('Point', F.vec([-x_out, y_mid - y_top, 0.0])), ('Face', 'left')]),
                               OD([('Point', F.vec([x_out, y_mid - y_top, 0.0])), ('Face', 'right')])])])

    # TER-9A: one beam, a store under it and one on each side; the beam is wide enough that the neighbours clear each other
    THK = F.THK
    stores_ter = [F.anchors_for('gbu12')[0], F.anchors_for('mk82_air')[0]]
    half = 0.0
    for a in stores_ter:
        rs = abs(a['SideMountAnchors']['Left'][0]); drop = a['MountAnchor'][1] - a['MountAxis'][1]
        sep = 2 * rs + 0.02
        half = max(half, math.sqrt(sep * sep - (drop + THK / 2) ** 2) - rs)
    W = round(2 * half, 3)
    F.rack_model('rack_ter9a_x3', [(-W / 2, W / 2, -THK, 0.0, -0.5, 0.5)])
    ter9a = OD([('Model', f'{NS}:custom_geo/aircraft_stores/rack_ter9a_x3.geo.json'),
                ('Texture', f'{NS}:textures/aircraft_stores/a10c_rack.png'),
                ('MountAnchor', [0.0, 0.0, 0.0]),
                ('Stations', [OD([('Point', F.vec([0.0, -THK, 0.0])), ('Face', 'bottom')]),
                              OD([('Point', F.vec([-W / 2, -THK / 2, 0.0])), ('Face', 'left')]),
                              OD([('Point', F.vec([W / 2, -THK / 2, 0.0])), ('Face', 'right')])])])

    # ============================================================== fuel tank
    # 175 kg empty (WT) + 600 US gal of JP-8 (2,271.2 l at 0.8 kg/l = 1,817 kg): an ESTIMATE, WT lists the empty tank only
    a, launch, g = F.anchors_for('tank_600gal')
    cat.add('munition/tank_600gal', F.store('600 US gal A-10C fuel tank', 'VISUAL_ONLY', 'tank_600gal', a, Capacity=1,
                                            MassKg=round(175 + 600 * 3.785411784 * 0.8, 1), LaunchOffset=launch))

    # ============================================================== air-to-air: AIM-9 on the LAU-105
    def aim9_x2(rel, name, src, mass):
        d = load(src)
        out = OD()
        for k, v in d.items():
            if k == 'Name':
                v = name
            elif k == 'MassKg':
                v = mass
            elif k in ('RackSpacing', 'FixedRackCount', 'LaunchOffset'):
                continue
            out[k] = v
            if k == 'MaxPerPylon':
                out[k] = 2
                out['FixedRackCount'] = 2
                out['RackAdapter'] = lau105
                out['RackSpacing'] = [0.7, 0.35, 0]            # legacy fallback only: twice the rail offset
                out['RackMassKg'] = 25.0                       # GUESS: LAU-105 dual rail adapter
        out['LaunchOffset'] = d['LaunchOffset']
        cat.add(rel, out)
    # WT A-10C file: AIM-9M 84.46 kg (the F/A-18E store carries 44); flight data as the F/A-18E AIM-9M
    aim9_x2('munition/aim9m_x2_lau105', 'AIM-9M Sidewinder · infrared · LAU-105 dual rail', 'fa18e/aim9m', 84.5)
    aim9_x2('munition/aim9l_x2_lau105', 'AIM-9L Sidewinder · all-aspect infrared · LAU-105 dual rail', 'munition/aim9l', 84.5)

    # ============================================================== rocket pods and APKWS
    # MassKg is per round (the pylon mass is MassKg x Capacity): Hydra 70 M247 10.16 kg (WT) + pod share as the LAU-68 (4.61 kg)
    # + TER-9A 45 kg / 21 rounds (GUESS)
    c3 = [(-0.135, 0.0), (0.135, 0.0), (0.0, -0.2338)]                 # pod axes of build_cluster(spacing 0.27)
    r = 0.257 / 2
    bar_top = r - 0.01 + 0.024                                         # lug top on the carriage bar
    cy = sum(p[1] for p in c3) / 3
    manual = dict(top=[0.0, bar_top, 0.0], left=[0.27 / 2 + r, 0.0, 0.0], right=[-(0.27 / 2 + r), 0.0, 0.0], axis=[0.0, cy, 0.0])
    a, launch, g = F.anchors_for('lau68_hydra_x3', manual=manual)      # hung by the bar's lugs, centred (lau131_apkws hangs 0.135 off)
    cat.add('munition/lau68_hydra_x3', F.store('LAU-131A · 3 x 7 Hydra 70 · TER-9A', 'ROCKET_POD', 'lau68_hydra_x3', a, Capacity=21,
                                               Scale=1, MassKg=16.9, AmmoItem='superbwarfare:small_unguided_rocket', LaunchOffset=launch))
    # single LAU-131 with 7 APKWS II: M151 15.8 kg (WT) + pod share 4.61 kg
    a, launch, g = F.anchors_for('lau131_apkws_x1')
    cat.add('munition/lau131_apkws_x1', F.store('LAU-131 · 7 x APKWS II laser rockets', 'LASER_GUIDED', 'lau131_apkws_x1', a, Capacity=7,
                                                Scale=1, ProjectileProfile=f'{NS}:aircraft_stores/apkws',
                                                LaunchGunProfile=f'{NS}:aircraft_stores/apkws', MassKg=20.4,
                                                AmmoItem='superbwarfare:small_atgm', LaunchOffset=launch))

    # ============================================================== AGM-65L (laser Maverick: the AGM-65E's model, route and warhead)
    prof = json.load(open(os.path.join(prof_dir, 'aircraft_stores/agm65e_maverick.json'), encoding='utf-8'), object_pairs_hook=OD)
    prof['Combat']['WeaponId'] = f'{NS}:aircraft_stores/agm65l_maverick'
    prof['Combat']['RoundId'] = f'{NS}:munition/agm65l'
    F.write_json(os.path.join(prof_dir, 'aircraft_stores/agm65l_maverick.json'), prof)
    gun = json.load(open(os.path.join(gun_dir, 'agm65e_maverick.json'), encoding='utf-8'), object_pairs_hook=OD)
    gun['Name'] = F.ai_name('AGM-65L Maverick · laser')
    gun['Projectile']['Profile'] = f'{NS}:aircraft_stores/agm65l_maverick'
    F.write_json(os.path.join(gun_dir, 'agm65l_maverick.json'), gun)
    a, launch, g = F.anchors_for('agm65e', missile=True)
    cat.add('munition/agm65l', F.store('AGM-65L Maverick · laser-guided', 'LASER_GUIDED', 'agm65e', a, Capacity=1, MassKg=291.2, Scale=1,
                                       ProjectileProfile=f'{NS}:aircraft_stores/agm65l_maverick',
                                       LaunchGunProfile=f'{NS}:aircraft_stores/agm65l_maverick',
                                       AmmoItem='superbwarfare:medium_air_to_ground_missile', LaunchOffset=launch))

    # ============================================================== bombs
    def bomb_profile(pid, model):
        F.write_json(os.path.join(prof_dir, 'aircraft_bombs', pid + '.json'), OD([
            ('VisualProfile', f'{NS}:authored_bomb'), ('MotionSync', 'INHERIT'), ('TrailMode', 'SUPPRESS'), ('RenderScale', 1),
            ('Extensions', OD([(f'{NS}:projectile_mesh_v1', OD([
                ('Model', f'{NS}:custom_geo/aircraft_stores/{model}.geo.json'),
                ('Texture', f'{NS}:textures/aircraft_stores/{model}.png'), ('ForwardYaw', 0), ('SpinDegreesPerTick', 0)]))]))]))

    def bomb(rel, name, model, mass, blast, tnt, dmg, ammo, profile, *, drag=1, rack=None, copies=1, spacing=None):
        a, launch, g = F.anchors_for(model)
        b = OD([('Mode', 'DUMB'), ('Gravity', 0.08), ('DragMultiplier', drag), ('TurnDegreesPerTick', 0),
                ('BlastRadius', blast), ('TntEquivalentKg', tnt), ('BlastDamage', dmg)])
        extra = OD()
        if copies > 1:
            extra['MaxPerPylon'] = copies; extra['FixedRackCount'] = copies; extra['RackAdapter'] = rack; extra['RackSpacing'] = spacing
        cat.add(rel, F.store(name, 'BOMB', model, a, Capacity=1, MassKg=mass, Bomb=b, AmmoItem=ammo,
                             ProjectileProfile=f'{NS}:aircraft_bombs/{profile}', **extra, LaunchOffset=launch))

    SF, LF = 'superbwarfare:small_freefall_bomb', 'superbwarfare:large_freefall_bomb'
    H6 = (7, 117.585, 450)                       # 87.1 kg H6 x1.35: the pack's Mk 82 / GBU-12
    K84 = (14, 578.61, 900)                      # 428.6 kg H6 x1.35: the pack's Mk 84
    bomb_profile('mk82_air', 'mk82_air_deployed'); bomb_profile('mk84_air', 'mk84_air_deployed')
    # masses: the pack's Mk 82 227 kg / Mk 84 907 kg plus the ballute kit (the Mk 83 AIR is +6 kg over the Mk 83); GUESS 233 / 917 kg
    # (WT's own Mk 82 and Mk 84 files read 240.9 / 893.6 kg; the _air files were not read)
    bomb('munition/mk82_air', 'Mk 82 AIR · 500 lb ballute retarded', 'mk82_air', 233.0, *H6, SF, 'mk82_air', drag=5)
    bomb('munition/mk82_air_x3', 'Mk 82 AIR · 500 lb retarded · TER-9A triple rack', 'mk82_air', 233.0, *H6, SF, 'mk82_air', drag=5,
         rack=ter9a, copies=3, spacing=[0.5, 0.45, 0])
    bomb('munition/mk84_air', 'Mk 84 AIR · 2,000 lb ballute retarded', 'mk84_air', 917.0, *K84, LF, 'mk84_air', drag=5)
    # GBU-12 x3: the pack's GBU-12 (model, anchors, mass 227 kg, laser bomb, profile) on the TER-9A
    d = load('munition/gbu12')
    out = OD()
    for k, v in d.items():
        if k == 'LaunchOffset':
            continue
        out[k] = v
        if k == 'Name':
            out[k] = 'GBU-12 Paveway II · 500 lb laser · TER-9A triple rack'
        if k == 'ProjectileProfile':
            out['MaxPerPylon'] = 3; out['FixedRackCount'] = 3; out['RackAdapter'] = ter9a; out['RackSpacing'] = [0.5, 0.45, 0]
    out['LaunchOffset'] = d['LaunchOffset']
    cat.add('munition/gbu12_x3', out)
    print('written', len(cat.items), 'stores; TER-9A beam width', W)


if __name__ == '__main__':
    main(sys.argv[1:])
