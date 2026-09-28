#!/usr/bin/env python3
"""Tracer colour by nation, the owner's rule of thumb (2026-09-28): Russian / Chinese / Soviet-bloc tracers are green,
NATO (and every other Western) tracers are red.

    python3 tools/ballistics/tracer_nations.py [--check]

Two places carry a tracer colour and both follow the rule:

* the belt entries' ``"Tracer"`` in sbw/vehicles/<id>.json (and sbw/guns): Eastern vehicles -> ``GREEN`` for every
  traced round; Western vehicles -> ``RED`` for a traced round in any non-red colour (the red shades from War
  Thunder, LIGHT_RED / DARK_RED / BRIGHT_RED, stay);
* the BVP trail tracer (``projectile_effect_v1`` Trail.Tracer.ColorRgb) of every projectile profile: Eastern ->
  GREEN_RGB, Western -> RED_RGB unless it is already a red.

Nation: the vehicle id of the file (sbw/vehicles/<id>.json, projectile_profiles/<id>/...), or the gun family for the
shared aircraft rounds and guns (projectile_profiles/aircraft_rounds/<family>/, guns/aircraft_guns/<gun>.json).
Untraced rounds (NONE) and INHERIT are left alone. Idempotent; files keep json.dumps(indent=2) formatting.
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
SBW = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack', 'sbw')

EAST_VEHICLES = {
    # tanks, IFVs, APCs, AA, launchers, technicals with Soviet weapons
    '9k22_tunguska', '9p148', '9p149_shturm', 'bmd_1', 'bmp2', 'bmp2m', 'bmp3m_elite', 'bmp_1am', 'bmpt', 'btr80a',
    'btr_60pb', 'btr_90', 'btr_zd', 'gaz_3937_vodnik_aa', 'kord_tripod', 'qn_506model', 't14_armata', 't55a_2_0',
    't64b_obr1976', 't72a', 't72b', 't72b3', 't72b3_ubh_cope', 't80b_obr1976', 't80u_obr1985', 't90a', 't90m',
    't_62a', 'toyota_jihad_bmp1', 'toyota_jihad_dshk', 'toyota_jihad_s5', 'toyota_jihad_spg9', 'tunguska', 'vt_4a1',
    'zbd_09', 'zsl_92', 'zsu23_4', 'ztl_09', 'ztz99a', 'zu23_2', 'uaz_469_spg9', 'ags_30',
    # helicopters
    'ka50', 'mi24v', 'mi28n', 'mi_24a', 'mi_24d',
    # aircraft
    'an_12b', 'il_10', 'il_76m', 'j_10a', 'j_11a', 'j_15d', 'j_2', 'j_26', 'j_5', 'mig19', 'mig_15bis', 'mig_19s',
    'mig_21bis', 'mig_23mld', 'mig_29', 'mig_9', 'q_5', 'su_17', 'su_24', 'su_25', 'su_27', 'su_30', 'su_35', 'su_39',
    'su_57', 'tu_22m', 'tu_95ms', 'yak_15p', 'yak_3', 'yak_9u',
}
EAST_GUN_FAMILIES = {'a127', 'am23', 'gsh23l', 'gsh23m', 'gsh301', 'gsh302', 'n37d', 'nr23', 'nr30', 'ns23', 'ns23_1946',
                     'shvak', 'ubs', 'yakb'}
REDS = {'RED', 'LIGHT_RED', 'DARK_RED', 'BRIGHT_RED'}
GREEN_RGB = [72, 255, 96]
RED_RGB = [255, 32, 32]


def east_of(rel):
    parts = rel.replace(os.sep, '/').split('/')
    if parts[0] == 'vehicles':
        return parts[1][:-5] in EAST_VEHICLES
    if parts[0] == 'projectile_profiles':
        if parts[1] == 'aircraft_rounds':
            return parts[2] in EAST_GUN_FAMILIES
        return parts[1] in EAST_VEHICLES
    if parts[0] == 'guns':
        name = parts[-1][:-5]
        return any(name.startswith(f) for f in EAST_GUN_FAMILIES) or name.split('_')[0] in EAST_VEHICLES
    return None


def is_red(rgb):
    r, g, b = rgb[:3]
    return r >= 180 and g <= 90 and b <= 90


def fix(o, east, changes):
    if isinstance(o, dict):
        t = o.get('Tracer')
        if isinstance(t, str) and t not in ('NONE', 'INHERIT'):
            want = 'GREEN' if east else (t if t in REDS else 'RED')
            if t != want:
                o['Tracer'] = want
                changes.append(f'{t}->{want}')
        elif isinstance(t, dict) and t.get('Enabled') and isinstance(t.get('ColorRgb'), list):
            rgb = t['ColorRgb']
            want = GREEN_RGB if east else (rgb if is_red(rgb) else RED_RGB)
            if list(rgb) != want:
                t['ColorRgb'] = list(want)
                changes.append(f'rgb{rgb}->{want}')
        for v in o.values():
            fix(v, east, changes)
    elif isinstance(o, list):
        for v in o:
            fix(v, east, changes)


def main(argv):
    check = '--check' in argv
    total = 0
    for path in sorted(glob.glob(os.path.join(SBW, '**', '*.json'), recursive=True)):
        text = open(path).read()
        if '"Tracer"' not in text:
            continue
        rel = os.path.relpath(path, SBW)
        east = east_of(rel)
        if east is None:
            continue
        d = json.loads(text)
        changes = []
        fix(d, east, changes)
        if changes:
            total += 1
            print(f"{rel}: {'east' if east else 'west'} {', '.join(sorted(set(changes)))}")
            if not check:
                open(path, 'w').write(json.dumps(d, indent=2) + ('\n' if text.endswith('\n') else ''))
    print(f'{total} files ' + ('would change' if check else 'changed'))
    return 1 if check and total else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
