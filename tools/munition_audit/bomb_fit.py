#!/usr/bin/env python3
"""Guided and heavy bomb fit per aircraft (owner 2026-09-29: every GBU version on the aircraft that really carry it,
and bombs of the size each pylon really takes). Idempotent; edits the generated aircraft_armaments in place.

  python3 tools/munition_audit/bomb_fit.py          # apply
  python3 tools/munition_audit/bomb_fit.py --check  # exit 1 if anything differs

Carriers (public loadout references):
  GBU-10 (2,000 lb LGB)   F-111F, F-15E, F-16C/D, F/A-18E, A-10
  GBU-12 (500 lb LGB)     F-111F, F-15E, F-16C/D, F/A-18E, A-10
  GBU-16 (1,000 lb LGB)   F-111F, F-16C/D, F/A-18E
  GBU-27 (2,000 lb BLU-109 LGB)  F-111F, F-15E, F-16C/D
  GBU-28 (4,700 lb LGB)   F-111F, F-15E (wing station)
  GBU-15 (TV glide)       F-111F, F-15E -- not the F-15C
  GBU-31(V)4 (BLU-109 JDAM)  F-15E, F-16C/D, F/A-18E, A-10, B-1B -- not the F-15C
  KAB-1500L / FAB-1500    Su-24M (belly), Su-30 (intakes), Su-35, Tu-22M3
Pylon limits are raised only where the real station takes the heavier store (F-15E wing station 2,300 kg for the
GBU-28, Su-24 belly and Su-30 intake stations 1,600 kg for the KAB-1500).
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
DATA = os.path.join(REPO, 'bvp/src/generated/resources/data/berts_vehicle_pack/sbw')
ARMAMENTS = os.path.join(DATA, 'aircraft_armaments')
STORES = os.path.join(DATA, 'aircraft_stores')
NS = 'berts_vehicle_pack:'

HEAVY_US = ['gbu10', 'gbu27', 'blu109', 'blu109_jdam', 'mk84']
CLUSTER = ['cbu87', 'cbu97']

# aircraft -> pylon id -> {'add': [...], 'remove': [...], 'max_kg': n}; store ids without the munition/ prefix
# unless they live at the store root (Russian stores).
RULES = {
    'f_15e': {
        'wing_centre': {'max_kg': 2300, 'add': ['gbu10', 'gbu12', 'gbu27', 'gbu28', 'gbu15_v2b', 'blu109',
                                                'blu109_jdam', 'mk83', 'mk84'] + CLUSTER},
        'centerline': {'add': ['gbu10', 'gbu12', 'gbu27', 'blu109', 'mk83']},
    },
    'f_15c': {
        'wing_centre': {'remove': ['gbu15_v2b', 'blu109_jdam']},
        'centerline': {'remove': ['blu109_jdam']},
    },
    'f_16c': {p: {'add': ['gbu10', 'gbu12', 'gbu16', 'gbu27', 'blu109', 'blu109_jdam', 'mk83'] + CLUSTER}
              for p in ('inner', 'middle')},
    'f_16b': {p: {'add': ['gbu10', 'gbu12', 'gbu16', 'gbu27', 'blu109', 'blu109_jdam', 'mk83'] + CLUSTER}
              for p in ('inner', 'middle')},
    'fa_18e': {p: {'add': ['gbu10', 'gbu12', 'gbu16']} for p in ('mid', 'inner', 'root', 'centerline')},
    'f_111f': {
        **{p: {'add': ['gbu10', 'gbu12', 'gbu16', 'gbu27', 'gbu28', 'mk83']} for p in ('pylon_1', 'pylon_2')},
        'pylon_3': {'add': ['gbu10', 'gbu12', 'gbu16', 'gbu27', 'mk83']},
        'pylon_4': {'add': ['gbu12', 'gbu16']},
    },
    'a_10': {
        'outer': {'add': ['gbu12']},
        'marked_mid': {'add': ['gbu12'] + CLUSTER},
        'inner': {'add': ['gbu10', 'gbu12', 'blu109_jdam', 'mk84'] + CLUSTER},
        'root': {'add': ['gbu10', 'gbu12', 'blu109_jdam', 'mk84'] + CLUSTER},
        'centerline': {'add': ['gbu12']},
    },
    'f_4c': {p: {'add': ['mk83', 'mk84']} for p in ('inner', 'outer')},
    'su_24': {p: {'max_kg': 1600, 'add': ['@kab1500l', '@fab_1500']} for p in ('belly_fwd', 'belly_aft')},
    'su_30': {'intake': {'max_kg': 1600, 'add': ['@kab1500l', '@fab_1500']}},
}


def store_id(short):
    """'gbu12' -> munition/gbu12, '@fab_1500' -> fab_1500 (root store), anything with a slash kept."""
    if short.startswith('@'):
        return NS + ('munition/' + short[1:] if os.path.exists(os.path.join(STORES, 'munition', short[1:] + '.json'))
                     else short[1:])
    return NS + (short if '/' in short else 'munition/' + short)


def store_mass(sid):
    path = os.path.join(STORES, sid.split(':', 1)[1] + '.json')
    if not os.path.exists(path):
        raise SystemExit(f'unknown store {sid}')
    return json.load(open(path)).get('MassKg') or 0


def apply(check):
    changed = []
    for aircraft, pylons in sorted(RULES.items()):
        path = os.path.join(ARMAMENTS, aircraft + '.json')
        data = json.load(open(path))
        mounts = {m['Id']: m for m in data.get('Pairs', []) + data.get('Singles', [])}
        before = json.dumps(data, sort_keys=True)
        for pid, rule in pylons.items():
            mount = mounts.get(pid)
            if mount is None:
                raise SystemExit(f'{aircraft}: no pylon {pid}')
            if 'max_kg' in rule and (mount.get('MaxPylonMassKg') or 0) < rule['max_kg']:
                mount['MaxPylonMassKg'] = rule['max_kg']
            allowed = list(mount.get('AllowedStores', []))
            for short in rule.get('remove', []):
                sid = store_id(short)
                if sid in allowed:
                    allowed.remove(sid)
            for short in rule.get('add', []):
                sid = store_id(short)
                if sid in allowed:
                    continue
                if store_mass(sid) > (mount.get('MaxPylonMassKg') or 0):
                    raise SystemExit(f'{aircraft}/{pid}: {sid} ({store_mass(sid)} kg) over the pylon limit')
                allowed.append(sid)
            mount['AllowedStores'] = allowed
        if json.dumps(data, sort_keys=True) != before:
            changed.append(aircraft)
            if not check:
                with open(path, 'w') as out:
                    json.dump(data, out, indent=2)
                    out.write('\n')
    return changed


if __name__ == '__main__':
    check = '--check' in sys.argv
    changed = apply(check)
    if check:
        if changed:
            print('bomb fit differs for: ' + ', '.join(changed))
            sys.exit(1)
        print('bomb fit matches the rules')
    else:
        print(f'{len(changed)} aircraft updated: ' + ', '.join(changed))
