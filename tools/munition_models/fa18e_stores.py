#!/usr/bin/env python3
"""Store models and store definitions for the War Thunder F/A-18E Block II loadout (second batch after f16c_stores.py).

Reuses the builders of f16c_stores.py (Mesh, revolve, cruciform, lugs, JDAM/SDB bodies, rack adapters, anchor
derivation with tools/aircraft_attach/munitions.py) and generate.py's body-of-revolution missile builder.
Sources for every dimension are in the report that came with this change; guesses are marked GUESS below.

Usage: python tools/munition_models/fa18e_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py   (and --check)
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
from f16c_stores import Mesh, revolve, cruciform, lugs, centred, ogive, build_jdam  # noqa: E402

NS, STORES, SBW, GEO, TEX, PX = F.NS, F.STORES, F.SBW, F.GEO, F.TEX, F.PX
munitions = F.munitions
OD = OrderedDict


def load(rel):
    with open(os.path.join(STORES, rel + '.json'), encoding='utf-8') as fh:
        return json.load(fh, object_pairs_hook=OD)


# ------------------------------------------------------------------------------------------------ models
def build_jsow(deployed):
    """AGM-154A JSOW: box-section glide bomb 4.06 m long, 0.33 m square, wings stowed flat on top (span 2.69 m deployed)."""
    L, h = 4.06, 0.165
    m = Mesh()

    def frustum(z0, z1, h0, h1, colour):
        a = [[sx * h0, sy * h0, z0] for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
        b = [[sx * h1, sy * h1, z1] for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
        for i in range(4):
            j = (i + 1) % 4
            m.poly([a[i], a[j], b[j], b[i]], colour)
        m.poly(a[::-1], 'dgrey'); m.poly(b, 'dgrey')
    frustum(0.0, 0.55, 0.06, h, 'white')            # nose
    frustum(0.55, 3.55, h, h, 'grey')               # body
    frustum(3.55, L, h, 0.10, 'grey')               # tail
    if deployed:
        cruciform(m, (1.35, 2.65), (1.85, 2.50), h * 0.9, 1.345, 0.02, 'grey', 0, count=2)
    else:
        m.box(-0.12, 0.12, h, h + 0.022, 1.30, 2.70, 'dgrey')
    cruciform(m, (3.35, 3.95), (3.65, 3.95), h * 0.8, 0.26, 0.015, 'dgrey', 45)   # GUESS: tail fin size from drawings
    lugs(m, 2.0, 0.356, h)
    return centred(m, L), L


def build_mk83_air(deployed):
    """Mk 83 with the BSU-85/B ballute tail: 3.0 m body, 0.356 m, no fins; deployed adds the open ballute."""
    L, d = 3.0, 0.356; r = d / 2
    m = Mesh()
    prof = [(0.0, 0.06)] + ogive(0.62, 0.06, r)[1:] + [(2.35, r), (L, 0.20)]

    def col(s):
        if 0.95 <= s < 1.02:
            return 'yellow'
        return 'olive' if s < 2.35 else 'dgrey'
    revolve(m, prof, col, cuts=[0.95, 1.02, 2.35], caps=('dgrey', 'dgrey'))
    total = L
    if deployed:
        # ballute: open cone, 0.20 m at the tail growing to 0.52 m over 1.0 m (GUESS: shape and size)
        for k in range(F.SEG):
            a0, a1 = 2 * math.pi * k / F.SEG, 2 * math.pi * (k + 1) / F.SEG
            p = [np.array([0.20 * math.cos(a), 0.20 * math.sin(a), L]) for a in (a0, a1)]
            q = [np.array([0.52 * math.cos(a), 0.52 * math.sin(a), L + 1.0]) for a in (a0, a1)]
            m.poly([p[0], p[1], q[1], q[0]], 'orange')
            m.poly([q[0], q[1], p[1], p[0]], 'orange')
        total = L + 1.0
    lugs(m, 1.45, 0.356, r)
    return centred(m, total), total


def build_firebomb(L, d):
    """Mk 77 Mod 4 fire bomb: aluminium canister, rounded ends, red bands, small tail stabilisers (BLU-27 pattern)."""
    r = d / 2
    m = Mesh()
    prof = [(0.0, 0.09), (0.05, 0.16), (0.14, 0.205), (0.30, r), (L - 0.40, r), (L - 0.20, 0.205), (L - 0.04, 0.12), (L, 0.09)]

    def col(s):
        return 'red' if (0.55 <= s < 0.65 or L - 0.75 <= s < L - 0.65) else 'grey'
    revolve(m, prof, col, cuts=[0.55, 0.65, L - 0.75, L - 0.65], caps=('dgrey', 'dgrey'))
    cruciform(m, (L - 0.45, L - 0.02), (L - 0.35, L - 0.02), 0.12, 0.30, 0.012, 'dgrey', 45)
    lugs(m, L / 2, 0.356, r)
    return centred(m, L), L


def build_zuni_pod(L, d, pitch):
    """LAU-10/A: four 5 in tubes in a 2 x 2 bundle in a round skin, frangible nose and tail fairings."""
    r = d / 2
    m = Mesh()
    nose, tail = 0.22, 0.20
    prof = [(0.0, 0.62 * r), (nose, r), (L - tail, r), (L, 0.85 * r)]
    revolve(m, prof, lambda s: 'dgrey' if (s < nose or s > L - tail) else 'olive', cuts=[nose, L - tail], caps=('grey', 'grey'))
    for sx in (-1, 1):
        for sy in (-1, 1):
            F.disc(m, sx * pitch / 2, sy * pitch / 2, L + 0.001, 0.0635 * 1.05, 'black', +1)
    lugs(m, L * 0.5, 0.356, r)
    return centred(m, L), L


def build_atflir():
    """AN/ASQ-228 ATFLIR: 1.83 m x 0.33 m pod, forward turret with a large nose window, sensor section aft of it."""
    L, d = 1.83, 0.33; r = d / 2
    m = Mesh()
    prof = [(0.0, 0.10), (0.05, 0.135), (0.14, 0.158), (0.42, r), (L - 0.35, r), (L, 0.13)]

    def col(s):
        if s < 0.05:
            return 'glass'
        if s < 0.42:
            return 'dgrey'
        return 'grey' if s < L - 0.35 else 'dgrey'
    revolve(m, prof, col, cuts=[0.05, 0.42, L - 0.35], caps=('glass', 'dgrey'))
    m.box(-0.08, 0.08, -0.17, -0.145, 0.10, 0.40, 'glass')      # lower window facet of the turret
    lugs(m, L * 0.55, 0.356, r)
    return centred(m, L), L


# ------------------------------------------------------------------------------------------------ main
def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True); os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()
    prof_dir = os.path.join(SBW, 'projectile_profiles')
    F.palette_png().save(os.path.join(TEX, 'fa18e_rack.png'))

    # ============================================================== models
    m, L = build_jsow(False); F.save_model('agm154a', m)
    m, L = build_jsow(True); F.save_model('agm154a_deployed', m)
    m, L = build_mk83_air(False); F.save_model('mk83_air', m)
    m, L = build_mk83_air(True); F.save_model('mk83_air_deployed', m)
    m, L = build_firebomb(3.18, 0.47); F.save_model('mk77', m)                 # GUESS: Mk 77 Mod 4 length
    m, L = build_zuni_pod(2.90, 0.40, 0.15); F.save_model('lau10_zuni', m)     # GUESS: pod length and diameter
    m, L = build_atflir(); F.save_model('atflir', m)
    # GBU-32 JDAM (Mk 83): 3.0 m body, JDAM tail kit; GUESS: kit length and fin sizes from drawings
    m, L = build_jdam(3.05, 0.356, 0.65, 2.25, 0.47, (1.20, 2.15, 0.05), lug_gap=0.356, lug_z=1.45)
    F.save_model('gbu32', m)
    # AGM-65E Maverick: 2.49 m, 0.305 m, wing and tail span 0.72 m
    spec = dict(length=2.49, diameter=0.305, nose=0.50, boattail=0.10, tail_r=0.8, bands=[(0.30, 0.315, 'yellow')], radome=0.22,
                wings=[dict(root=(1.50, 2.05), tip=(1.78, 2.05), span=0.72 / 2, roll=45)],
                fins=[dict(root=(2.15, 2.47), tip=(2.30, 2.47), span=0.72 / 2, roll=45)])
    mesh, Lpx, rpx = generate.build(spec)
    F.write_json_compact(os.path.join(GEO, 'agm65e.geo.json'), generate.geo(mesh, 'geometry.aircraft_store.agm65e', False, Lpx))
    generate.texture().save(os.path.join(TEX, 'agm65e.png'))
    # AGM-88G AARGM-ER: 4.06 m, 0.29 m, strakes instead of wings (GUESS strake and fin sizes)
    spec = dict(length=4.06, diameter=0.29, nose=0.60, boattail=0.08, tail_r=0.85, bands=[(0.17, 0.18, 'yellow'), (0.19, 0.20, 'brown')],
                radome=0.48, wings=[dict(root=(1.40, 2.60), tip=(2.05, 2.60), span=0.20, roll=45)],
                fins=[dict(root=(3.55, 4.02), tip=(3.78, 4.02), span=0.64 / 2, roll=45)])
    mesh, Lpx, rpx = generate.build(spec)
    F.write_json_compact(os.path.join(GEO, 'agm88g.geo.json'), generate.geo(mesh, 'geometry.aircraft_store.agm88g', False, Lpx))
    generate.texture().save(os.path.join(TEX, 'agm88g.png'))

    # ============================================================== racks (BRU-33 / BRU-55 twin racks)
    def rack_two(name, model, fin_lat, write=True):
        # the store hangs beside the rack beam: its axis is the store's own top-to-axis drop below the pylon (as the F-16C racks),
        # and the beam is wide enough that the fins (lateral reach fin_lat) of two neighbours clear each other
        an = F.anchors_for(model)[0]
        drop = an['MountAnchor'][1] - an['SideMountAnchors']['Left'][1]
        ax = an['SideMountAnchors']['Left'][0]
        r = drop - 0.0245
        W = max(0.10, 2 * fin_lat + 0.02 - 2 * ax)
        D = r - F.THK / 2 + 0.03
        ymid = -D - F.THK / 2
        if write:
            F.rack_model(name, [(-0.03, 0.03, -D, 0.0, -0.35, 0.35), (-W / 2, W / 2, -D - F.THK, -D, -0.45, 0.45)])
        return [OD([('Point', F.vec([-W / 2, ymid, 0.0])), ('Face', 'left')]),
                OD([('Point', F.vec([W / 2, ymid, 0.0])), ('Face', 'right')])]
    def adapter(rack, stations):
        return OD([('Model', f'{NS}:custom_geo/aircraft_stores/{rack}.geo.json'),
                   ('Texture', f'{NS}:textures/aircraft_stores/fa18e_rack.png'),
                   ('MountAnchor', [0.0, 0.0, 0.0]), ('Stations', stations)])
    st_mk83 = rack_two('rack_fa18_x2_mk83', 'mk83', 0.258)
    st_gbu32 = rack_two('rack_fa18_x2_gbu32', 'gbu32', 0.235)
    st_mk83air = rack_two('rack_fa18_x2_mk83_air', 'mk83_air', 0.19)
    st_mk77 = rack_two('rack_blu27_x2', 'mk77', 0.24, write=False)             # the F-16C batch's rack: same radius and drop
    st_jsow = rack_two('rack_fa18_x2_jsow', 'agm154a', 0.19)
    st_sdb = rack_two('rack_fa18_x2_sdb', 'gbu53', 0.134)
    # ============================================================== air-to-air
    d = load('munition/aim7m'); d['Name'] = 'AIM-7P Sparrow · semi-active radar'
    cat.add('munition/aim7p', d)                                                # airframe and flight as the AIM-7M (GUESS: WT file unread)
    for tag, nm, mass, rng, speed, tnt in (('b', 'AIM-120B', 156.5, 2700, 12.7, 8.2), ('c5', 'AIM-120C-5', 161.5, 4000, 14, 9.1735),
                                           ('d', 'AIM-120D', 162.4, 4096, 14, 9.1735)):
        for suffix, srcid, extra in (('', 'fa18e/aim120', ''), ('_x2', 'fa18e/aim120_x2', ' · shoulder rails')):
            d = load(srcid)
            d['Name'] = f'{nm} AMRAAM · active radar{extra}'
            d['MassKg'] = mass
            d['Guidance']['Range'] = rng
            d['Flight']['MaxSpeed'] = speed
            d['Flight']['TntEquivalentKg'] = tnt
            cat.add(f'munition/aim120{tag}{suffix}', d)

    # ============================================================== air-to-ground
    # AGM-65E (laser): LASER_GUIDED store with its own projectile profile and launch gun, as the APKWS
    a, launch, g = F.anchors_for('agm65e', missile=True)
    prof = json.load(open(os.path.join(SBW, 'projectile_profiles/aircraft_stores/agm65e.json'), encoding='utf-8'), object_pairs_hook=OD)
    prof['Combat']['WeaponId'] = f'{NS}:aircraft_stores/agm65e_maverick'
    prof['Combat']['RoundId'] = f'{NS}:munition/agm65e'
    prof['Combat']['CaliberMm'] = 305; prof['Combat']['DiameterMm'] = 305
    prof['Collision'] = OD([('Width', 0.305), ('Height', 0.305)])
    prof['RenderScale'] = 1
    prof['Extensions'][f'{NS}:projectile_mesh_v1'] = OD([
        ('Model', f'{NS}:custom_geo/aircraft_stores/agm65e.geo.json'), ('Texture', f'{NS}:textures/aircraft_stores/agm65e.png'),
        ('ForwardYaw', 0), ('SpinDegreesPerTick', 0)])
    F.write_json(os.path.join(prof_dir, 'aircraft_stores/agm65e_maverick.json'), prof)
    gun = json.load(open(os.path.join(SBW, 'guns/aircraft_stores/agm65e.json'), encoding='utf-8'), object_pairs_hook=OD)
    gun['Name'] = 'AGM-65E Maverick · laser'
    gun['Projectile']['Profile'] = f'{NS}:aircraft_stores/agm65e_maverick'
    F.write_json(os.path.join(SBW, 'guns/aircraft_stores/agm65e_maverick.json'), gun)
    cat.add('munition/agm65e', F.store('AGM-65E Maverick · laser-guided', 'LASER_GUIDED', 'agm65e', a, Capacity=1, MassKg=291.2,
                                       Scale=1, ProjectileProfile=f'{NS}:aircraft_stores/agm65e_maverick',
                                       LaunchGunProfile=f'{NS}:aircraft_stores/agm65e_maverick',
                                       AmmoItem='superbwarfare:medium_air_to_ground_missile', LaunchOffset=launch))
    d = load('munition/agm65g'); d['Name'] = 'AGM-65F Maverick · imaging IR anti-ship'
    d['MassKg'] = 303.5; d['Flight']['TntEquivalentKg'] = 44.6335
    cat.add('munition/agm65f', d)
    d = load('fa18e/agm84e'); d['Name'] = 'AGM-84H SLAM-ER · cruise'
    d['MassKg'] = 668.1; d['Flight']['Range'] = 520
    cat.add('munition/agm84h', d)
    # AGM-88G AARGM-ER: the pack's AGM-88 flight model, WT mass/range/speed
    a, launch, g = F.anchors_for('agm88g', missile=True)
    d = load('fa18e/agm88')
    d['Name'] = 'AGM-88G AARGM-ER · anti-radiation'
    d['Model'] = f'{NS}:custom_geo/aircraft_stores/agm88g.geo.json'
    d['Texture'] = f'{NS}:textures/aircraft_stores/agm88g.png'
    for k in ('MountAnchor', 'SideMountAnchors', 'MountAxis', 'LaunchOffset'):
        d.pop(k, None)
    out = OD()
    for k, v in d.items():
        out[k] = v
        if k == 'ModelForward':
            out.update(a)
    out['LaunchOffset'] = launch
    out['MassKg'] = 467.0
    out['Guidance']['Range'] = 4000
    out['Flight']['MaxSpeed'] = 13; out['Flight']['TurnDegreesPerSecond'] = 40
    out['Flight']['TntEquivalentKg'] = 21.63
    cat.add('munition/agm88g', out)

    # ============================================================== bombs
    def bomb_profile(pid, model):
        F.write_json(os.path.join(prof_dir, 'aircraft_bombs', pid + '.json'), OD([
            ('VisualProfile', f'{NS}:authored_bomb'), ('MotionSync', 'INHERIT'), ('TrailMode', 'SUPPRESS'), ('RenderScale', 1),
            ('Extensions', OD([(f'{NS}:projectile_mesh_v1', OD([
                ('Model', f'{NS}:custom_geo/aircraft_stores/{model}.geo.json'),
                ('Texture', f'{NS}:textures/aircraft_stores/{model}.png'), ('ForwardYaw', 0), ('SpinDegreesPerTick', 0)]))]))]))

    def bomb(rel, name, model, mass, mode, blast, tnt, dmg, ammo, profile, *, turn=2, drag=1, glide=None, rack=None, spacing=None):
        a, launch, g = F.anchors_for(model)
        b = OD([('Mode', mode), ('Gravity', 0.08), ('DragMultiplier', drag), ('TurnDegreesPerTick', turn),
                ('BlastRadius', blast), ('TntEquivalentKg', tnt), ('BlastDamage', dmg)])
        if glide:
            b['LiftToDrag'], b['BestGlideSpeed'], b['MaxG'] = glide
        extra = OD()
        if rack:
            extra['MaxPerPylon'] = 2; extra['FixedRackCount'] = 2; extra['RackAdapter'] = rack; extra['RackSpacing'] = spacing
        cat.add(rel, F.store(name, 'BOMB', model, a, Capacity=1, MassKg=mass, Bomb=b, AmmoItem=ammo,
                             ProjectileProfile=f'{NS}:aircraft_bombs/{profile}', **extra, LaunchOffset=launch))

    MG, MF, SG = 'superbwarfare:medium_guided_bomb', 'superbwarfare:medium_freefall_bomb', 'superbwarfare:small_guided_bomb'
    bomb_profile('gbu32', 'gbu32'); bomb_profile('mk77', 'mk77'); bomb_profile('mk83_air', 'mk83_air_deployed')
    bomb_profile('agm154a', 'agm154a_deployed')
    K83 = (10, 272.43, 650)                      # 202 kg H6 x1.35, the pack's Mk 83 / GBU-16
    bomb('munition/gbu32', 'GBU-32 JDAM · Mk 83 GPS', 'gbu32', 480.0, 'GPS', *K83, MG, 'gbu32')
    bomb('munition/gbu32_x2', 'GBU-32 JDAM · BRU-33 twin rack', 'gbu32', 480.0, 'GPS', *K83, MG, 'gbu32',
         rack=adapter('rack_fa18_x2_gbu32', st_gbu32), spacing=[0.58, 0.4, 0])
    bomb('munition/mk83_air', 'Mk 83 AIR · 1,000 lb ballute retarded', 'mk83_air', 460.0, 'DUMB', *K83, MF, 'mk83_air', turn=0, drag=5)
    bomb('munition/mk83_air_x2', 'Mk 83 AIR · 1,000 lb retarded · BRU-33 twin', 'mk83_air', 460.0, 'DUMB', *K83, MF, 'mk83_air',
         turn=0, drag=5, rack=adapter('rack_fa18_x2_mk83_air', st_mk83air), spacing=[0.58, 0.4, 0])
    src = load('munition/mk83')
    a83, launch83, _ = F.anchors_for('mk83')                                   # the pack's Mk 83 anchors are fin-wide; the rack needs body-side ones
    d = OD()
    for k, v in src.items():
        if k in ('MountAnchor', 'SideMountAnchors', 'MountAxis', 'LaunchOffset'):
            continue
        d[k] = v
        if k == 'ModelForward':
            d.update(a83)
    d['Name'] = 'Mk 83 · 1,000 lb freefall · BRU-33 twin rack'
    la = launch83
    d['MaxPerPylon'] = 2; d['FixedRackCount'] = 2; d['RackAdapter'] = adapter('rack_fa18_x2_mk83', st_mk83)
    d['RackSpacing'] = [0.58, 0.4, 0]; d['LaunchOffset'] = la
    cat.add('munition/mk83_x2', d)
    bomb('munition/mk77', 'Mk 77 Mod 4 · 750 lb fire bomb', 'mk77', 340.0, 'DUMB', 9, 60, 280, MF, 'mk77', turn=0)
    bomb('munition/mk77_x2', 'Mk 77 Mod 4 · fire bomb · BRU-33 twin rack', 'mk77', 340.0, 'DUMB', 9, 60, 280, MF, 'mk77', turn=0,
         rack=adapter('rack_blu27_x2', st_mk77), spacing=[0.55, 0.4, 0])
    bomb('munition/agm154a', 'AGM-154A JSOW · GPS glide bomb', 'agm154a', 483.0, 'GPS', 9, 45, 500, MG, 'agm154a', glide=(9, 6, 3))
    bomb('munition/agm154a_x2', 'AGM-154A JSOW · BRU-55 twin rack', 'agm154a', 483.0, 'GPS', 9, 45, 500, MG, 'agm154a', glide=(9, 6, 3),
         rack=adapter('rack_fa18_x2_jsow', st_jsow), spacing=[0.52, 0.4, 0])
    src = load('munition/gbu53')
    d = OD()
    for k, v in src.items():
        d[k] = v
    d['Name'] = 'GBU-53/B StormBreaker · BRU-55 twin rack'
    la = d.pop('LaunchOffset')
    d['MaxPerPylon'] = 2; d['FixedRackCount'] = 2; d['RackAdapter'] = adapter('rack_fa18_x2_sdb', st_sdb)
    d['RackSpacing'] = [0.40, 0.4, 0]; d['LaunchOffset'] = la
    cat.add('munition/gbu53_x2', d)

    # ============================================================== rocket pod
    a, launch, g = F.anchors_for('lau10_zuni')
    cat.add('munition/lau10_zuni', F.store('LAU-10/A · 4 x Zuni 5 in', 'ROCKET_POD', 'lau10_zuni', a, Capacity=4, Scale=1, MassKg=61.8,
                                           AmmoItem='superbwarfare:small_unguided_rocket', LaunchOffset=launch))

    # ============================================================== targeting pod
    a, launch, g = F.anchors_for('atflir')
    eye = munitions.to_anchor_frame([g.x0, g.y0, g.zmin + 0.02])
    cat.add('sensor/atflir', F.store('AN/ASQ-228 ATFLIR · targeting pod', 'TARGETING_POD', 'atflir', a, Item='minecraft:spyglass',
                                     Capacity=1, MassKg=191.0,
                                     TargetingPod=OD([('Eye', F.vec(eye)), ('YawLimit', 150), ('PitchMin', -30), ('PitchMax', 90),
                                                      ('MaxZoom', 16), ('Range', 8192)])))
    print('written', len(cat.items), 'stores')


if __name__ == '__main__':
    main(sys.argv[1:])
