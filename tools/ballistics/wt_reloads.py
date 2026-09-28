#!/usr/bin/env python3
"""Reload times and fire rates of ground-vehicle and emplaced weapons.

  python3 tools/ballistics/wt_reloads.py [--check]

Main guns take the War Thunder reload (datamine `shotFreq`, unit-level override first; for a human loader this is
the aced-crew value, basic crew is 1.3x slower). Guns WT does not have get a stated estimate. Everything else follows
the pack's rules of thumb (the user's):

  coaxial / pintle LMG (<= 8 mm)   20 s
  HMG (12.7 / 14.5 mm)             10 s
  autocannon / automatic grenade   20 s
  externally mounted ATGM / SAM    12 s

Machine-gun and autocannon fire rates follow WT's rounds per minute for the same gun. A magazine-fed main gun gets
RPM 60 so that its reload, not the fire-rate gate, sets the interval (an RPM of 10 blocked the Type 90's 4 s
autoloader for 6 s). Idempotent: a second run reports nothing to change. Aircraft and helicopters are not touched.
Sources are in docs/RELOADS.md.
"""
import glob, json, os, re, sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
# SBW's own vehicles keep their tuning (the LAV-AD's FIM-92 reload was set by hand to the 12 s rule).
VEHICLES = [os.path.join(REPO, 'bvp/src/generated/resources/data/berts_vehicle_pack/sbw/vehicles')]
LANG = os.path.join(REPO, 'bvp/src/generated/resources/assets/berts_vehicle_pack/lang/en_us.json')
TPS = 20

# Main guns, seconds between shots. WT = datamine value (unit file / weapon blk shotFreq).
MAIN = {
    't72a': (7.0, 'WT T-72A 2A46M autoloader'), 't72b': (7.0, 'WT T-72B'), 't72b3': (7.0, 'WT T-72B3 (2011)'),
    't72b3_ubh_cope': (7.0, 'WT T-72B3 (2011)'), 't90a': (7.0, 'WT T-90A'), 't90m': (7.0, 'WT T-90M (2020)'),
    't80b_obr1976': (6.0, 'WT T-80B'), 't80u_obr1985': (6.0, 'WT T-80U'), 't64b_obr1976': (6.0, 'WT T-64B'),
    't14_armata': (5.5, 'estimate: not in WT; faster than the T-80 (6.0 s), and fits its 5.4 s autoloader clip'),
    't55a_2_0': (7.5, 'WT T-55A, aced'), 't_62a': (8.0, 'WT T-62, aced'),
    'm1_abrams_elite': (5.0, 'WT M1 Abrams, aced'), 'm1a1_abrams': (5.0, 'WT M1A1, aced'),
    'm1a2_abrams_sep_v2': (5.0, 'WT M1A2 SEP V2, aced'), 'm48a3_elite': (6.0, 'WT M48A1 90 mm M41, aced'),
    'm551a1': (12.0, 'WT M551 152 mm M81, aced'), 'm60a1': (6.67, 'WT M60A1, aced'),
    'challenger_2': (5.0, 'WT Challenger 2, aced'), 'leopard_2a4': (6.0, 'WT Leopard 2A4, aced'),
    'leo2a6': (6.0, 'WT Leopard 2A6, aced'), 'leclerc_s1': (5.0, 'WT Leclerc S1 autoloader'),
    'type_90': (4.0, 'WT Type 90 autoloader'), 'vt_4a1': (6.67, 'WT VT4 autoloader'),
    'ztz99a': (6.67, 'WT ZTZ99A autoloader'), 'ztl_09': (6.67, 'WT ZTL-11 105 mm (same gun family), aced'),
    'm1128': (7.5, 'WT M1128 MGS autoloader'), 'pzh_2000': (5.0, 'WT PzH 2000'),
    'm109a7_paladin': (10.0, 'estimate: WT M109A1 is 13.3 s aced; the A7 has a powered rammer'),
    'k2a1_black_panther': (4.0, 'estimate: not in WT; K2 autoloader rated 15 rounds/min'),
    'bmp3m_elite': (4.0, 'WT BMP-3 100 mm 2A70 autoloader'),
    'bmp_1am': (6.0, 'WT BMP-1 73 mm 2A28 autoloader'), 'bmd_1': (6.0, 'WT BMP-1 73 mm 2A28 autoloader'),
    'toyota_jihad_bmp1': (8.0, 'estimate: 2A28 on a technical, hand-loaded'),
}
MAIN_WEAPON = {'bmp3m_elite': 'Cannon', 'toyota_jihad_bmp1': 'BMP1Cannon'}

RULE = {'LMG': 20.0, 'HMG': 10.0, 'AUTOCANNON': 20.0, 'ATGM': 12.0}

# WT rounds per minute by gun.
RPM = {
    'PKT': 700, 'M240': 940, 'MG3': 1200, 'M73': 500, 'SGMT': 600, 'TYPE74': 700, 'TYPE86': 700, 'L94A1': 600,
    'L37A2': 650, 'QJT': 800, 'AANF1': 900, 'KSP': 650,
    'M2HB': 575, 'NSVT': 700, 'KORD': 700, 'DSHK': 600, 'M85': 625, 'QJC88A': 600, 'KPVT': 600,
    '2A42': 550, '2A72': 330, '2A14x2': 1600,
}
# Nation of the generic "7.62 mm coax" per vehicle.
COAX = {
    'bmp2': 'PKT', 'bmp2m': 'PKT', 'bmp3m_elite': 'PKT', 'bmp_1am': 'PKT', 'btr80a': 'PKT', 'btr_60pb': 'PKT',
    't14_armata': 'PKT', 't64b_obr1976': 'PKT', 't72a': 'PKT', 't72b': 'PKT', 't72b3': 'PKT', 't72b3_ubh_cope': 'PKT',
    't80b_obr1976': 'PKT', 't80u_obr1985': 'PKT', 't90a': 'PKT', 't90m': 'PKT', 't_62a': 'PKT', 't55a_2_0': 'SGMT',
    'ztz99a': 'TYPE86', 'cv9040_no_net': 'KSP', 'leo2a6': 'MG3',
    'm1_abrams_elite': 'M240', 'm1a1_abrams': 'M240', 'm1a2_abrams_sep_v2': 'M240', 'm2_bradley': 'M240',
    'm48a3_elite': 'M73', 'm551a1': 'M73', 'm60a1': 'M73',
}


def gun_of(vid, name):
    """The gun a machine gun is (for its WT fire rate), from the display name or the vehicle's nation."""
    n = name.upper().replace(' ', '').replace('.', '')
    for key, pat in (('M2HB', 'M2HB'), ('NSVT', 'NSVT'), ('KORD', 'KORD'), ('DSHK', 'DSHK'), ('M85', 'M85'),
                     ('QJC88A', 'QJC88A'), ('KPVT', 'KPVT'), ('PKT', 'PKT'), ('MG3', 'MG3'), ('M240', 'M240'),
                     ('L94A1', 'L94A1'), ('L37A2', 'L37A2'), ('TYPE74', 'TYPE74'), ('TYPE86', 'TYPE86'),
                     ('QJT', 'QJT'), ('AANF1', 'AANF1')):
        if pat in n:
            return key
    if 'COAX' in n:
        return COAX.get(vid)
    return None


def classify(vid, weapon, v, name):
    proj = v.get('Projectile')
    proj = proj.get('Type') if isinstance(proj, dict) else proj
    if MAIN_WEAPON.get(vid, 'Cannon') == weapon and vid in MAIN:
        return 'MAIN'
    if weapon in ('Missile', 'MicroMissile', 'FimMissile') or proj in (
            'superbwarfare:wire_guide_missile', 'superbwarfare:ru_9m336_missile') or re.search(r'MILAN|TOW', name):
        return 'ATGM'
    if re.search(r'12\.7|14\.5|M2HB|NSVT|DShK|Kord|KPVT|\bK6\b|QJC88A|M85', name):
        return 'HMG'
    if re.search(r'7\.62|7\.92|5\.8|PKT|MG3|coax|Type 74|Type 86|L94|L37|AA NF1|QJT', name, re.I):
        return 'LMG'
    if re.search(r'\b(20|23|25|30|35|40) mm|2A42|2A72|2A38|Rh202|RH202|KDA|ZU-23|M242|M811|ZPT|ZPZ|Akan|AG-30|AGS-30',
                 name):
        return 'AUTOCANNON'
    return None


# Weapons left alone: internal autoloading launchers, recoilless rifles, rocket pods (see docs/RELOADS.md).
SKIP = {('9p149_shturm', 'Missile'), ('toyota_jihad_s5', 'S5RocketPod'), ('spg9_tripod', 'Cannon'),
        ('toyota_jihad_spg9', 'RecoillessGun'), ('uaz_469_spg9', 'RecoillessGun')}
# The 9P149's launcher is reloaded from inside by its autoloader: WT 8.33 s between missiles.
EXTRA_RPM = {('9p149_shturm', 'Missile'): 7.2, ('zu23_2', 'Cannon'): 1600}
# Marder 1A1/1A2 Rh202 had no magazine (fed straight from the hold, never reloading): a 200-round box like the 1A5.
MAGAZINE = {('marder_1a1', 'Cannon'): 200, ('marder_1a2', 'Cannon'): 200}


def plan(vid, weapon, v, name, typ):
    if typ in ('Airplane', 'Helicopter') or (vid, weapon) in SKIP:
        changes = {}
        if (vid, weapon) in EXTRA_RPM:
            changes['RPM'] = EXTRA_RPM[(vid, weapon)]
        return None, changes
    cls = classify(vid, weapon, v, name)
    if cls is None:
        return None, {}
    secs = MAIN[vid][0] if cls == 'MAIN' else RULE[cls]
    ticks = int(round(secs * TPS))
    changes = {'NormalReloadTime': ticks, 'EmptyReloadTime': ticks}
    if (vid, weapon) in MAGAZINE:
        changes['Magazine'] = MAGAZINE[(vid, weapon)]
    if cls == 'MAIN' and v.get('Magazine', 1) == 1:
        changes['RPM'] = 60
    if cls in ('LMG', 'HMG'):
        g = gun_of(vid, name)
        if g in RPM:
            # a twin mount fires both guns through one weapon
            changes['RPM'] = RPM[g] * (2 if re.search(r'\btwin\b', name, re.I) else 1)
    if (vid, weapon) in EXTRA_RPM:
        changes['RPM'] = EXTRA_RPM[(vid, weapon)]
    return cls, changes


# Reload clips: sound event -> clip length in ticks (measured from the .ogg, rounded up), so the clip is started that
# many ticks before the reload completes and ends with it (VehicleReloadClipDurationTicks). A clip longer than the
# reload would be cut off at completion, so each gun gets a clip no longer than its reload.
CLIP = {
    'berts_vehicle_pack:soviet_125mm_autoloader': 134,       # 6.661 s carousel autoloader
    'berts_vehicle_pack:m1_abrams_elite_m68a1_reload': 87,   # 4.314 s hand-loaded round
    'superbwarfare:t_90a_reload': 109,                        # 5.408 s
    'superbwarfare:ztz_99a_reload': 109,                      # 5.448 s
    'superbwarfare:m_1a_2_reload': 104,                       # 5.200 s
    'superbwarfare:plz_05_reload': 121,                       # 6.024 s self-propelled howitzer loader
    'superbwarfare:cannon_reload': 37,                        # 1.49-1.82 s breech close (3 variants)
    'superbwarfare:m_60_reload_empty': 127,                   # 6.304 s belt change
    'superbwarfare:m_2_hb_reload_empty': 127,                 # 6.336 s belt change and charge
    'superbwarfare:medium_missile_reload': 32,                # 1.578 s
    'superbwarfare:missile_reload': 25,                       # 1.227 s
}
MAIN_CLIP = {
    't72a': 'berts_vehicle_pack:soviet_125mm_autoloader', 't72b': 'berts_vehicle_pack:soviet_125mm_autoloader',
    't72b3': 'berts_vehicle_pack:soviet_125mm_autoloader', 't72b3_ubh_cope': 'berts_vehicle_pack:soviet_125mm_autoloader',
    't90a': 'berts_vehicle_pack:soviet_125mm_autoloader', 't90m': 'berts_vehicle_pack:soviet_125mm_autoloader',
    'm1128': 'berts_vehicle_pack:soviet_125mm_autoloader',
    't80b_obr1976': 'superbwarfare:t_90a_reload', 't80u_obr1985': 'superbwarfare:t_90a_reload',
    't64b_obr1976': 'superbwarfare:t_90a_reload', 't14_armata': 'superbwarfare:t_90a_reload',
    'bmp_1am': 'superbwarfare:t_90a_reload', 'bmd_1': 'superbwarfare:t_90a_reload',
    'vt_4a1': 'superbwarfare:ztz_99a_reload', 'ztz99a': 'superbwarfare:ztz_99a_reload',
    'leopard_2a4': 'superbwarfare:m_1a_2_reload', 'leo2a6': 'superbwarfare:m_1a_2_reload',
    'm109a7_paladin': 'superbwarfare:plz_05_reload',
    'type_90': 'superbwarfare:cannon_reload', 'k2a1_black_panther': 'superbwarfare:cannon_reload',
    'bmp3m_elite': 'superbwarfare:cannon_reload', 'toyota_jihad_bmp1': 'superbwarfare:cannon_reload',
}
DEFAULT_MAIN_CLIP = 'berts_vehicle_pack:m1_abrams_elite_m68a1_reload'
CLASS_CLIP = {'LMG': 'superbwarfare:m_60_reload_empty', 'HMG': 'superbwarfare:m_2_hb_reload_empty',
              'AUTOCANNON': 'superbwarfare:m_2_hb_reload_empty', 'ATGM': 'superbwarfare:medium_missile_reload'}
EXTRA_CLIP = {('9p149_shturm', 'Missile'): 'superbwarfare:medium_missile_reload',
              ('spg9_tripod', 'Cannon'): 'superbwarfare:cannon_reload',
              ('toyota_jihad_spg9', 'RecoillessGun'): 'superbwarfare:cannon_reload',
              ('uaz_469_spg9', 'RecoillessGun'): 'superbwarfare:cannon_reload'}


def sound_plan(vid, weapon, cls, v):
    """SoundInfo changes (None deletes a key): one crew reload clip, timed to end with the reload."""
    si = v.get('SoundInfo') or {}
    current = si.get('VehicleReload') or si.get('VehicleReload3p')
    if cls == 'MAIN':
        want = MAIN_CLIP.get(vid, DEFAULT_MAIN_CLIP)
    elif (vid, weapon) in EXTRA_CLIP:
        want = current if current in CLIP else EXTRA_CLIP[(vid, weapon)]
    elif cls in CLASS_CLIP:
        want = current if current in CLIP else CLASS_CLIP[cls]
    elif current in CLIP:
        want = current
    else:
        return {}
    reload = v.get('EmptyReloadTime') or v.get('NormalReloadTime') or 0
    if CLIP[want] > reload:
        raise SystemExit(f'{vid} {weapon}: clip {want} ({CLIP[want]} ticks) is longer than the reload ({reload})')
    key = 'VehicleReload3p' if si.get('VehicleReload3p') and not si.get('VehicleReload') else 'VehicleReload'
    changes = {key: want, 'VehicleReloadClipDurationTicks': CLIP[want]}
    if 'VehicleReloadSoundTime' in si:
        changes['VehicleReloadSoundTime'] = None
    return changes


def main():
    check = '--check' in sys.argv
    lang = json.load(open(LANG)) if os.path.exists(LANG) else {}
    report, touched = [], 0
    for folder in VEHICLES:
        for path in sorted(glob.glob(os.path.join(folder, '*.json'))):
            text = open(path).read()
            d = json.loads(text)
            vid = os.path.basename(path)[:-5]
            typ = d.get('Type')
            dirty = False
            for weapon, v in (d.get('Weapons') or {}).items():
                if not isinstance(v, dict):
                    continue
                name = lang.get(v.get('Name', ''), v.get('Name', ''))
                cls, changes = plan(vid, weapon, v, name, typ)
                for k, new in changes.items():
                    old = v.get(k)
                    if old != new:
                        report.append(f'{vid:20s} {weapon:20s} {cls or "-":10s} {k:16s} {old} -> {new}')
                        v[k] = new
                        dirty = True
                if typ in ('Airplane', 'Helicopter'):
                    continue
                si = v.setdefault('SoundInfo', {})
                for k, new in sound_plan(vid, weapon, cls, v).items():
                    old = si.get(k)
                    if old != new:
                        report.append(f'{vid:20s} {weapon:20s} {cls or "-":10s} {k:16s} {old} -> {new}')
                        if new is None:
                            si.pop(k, None)
                        else:
                            si[k] = new
                        dirty = True
            if dirty:
                touched += 1
                if not check:
                    open(path, 'w').write(json.dumps(d, indent=2, ensure_ascii=False) + ('\n' if text.endswith('\n') else ''))
    print('\n'.join(report) if report else 'nothing to change')
    print(f'{len(report)} changes in {touched} files' + (' (check only)' if check else ''))


if __name__ == '__main__':
    main()
