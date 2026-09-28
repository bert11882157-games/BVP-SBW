#!/usr/bin/env python3
"""Munition guidance audit fixes (owner's brief, 2026-09-28: "compatible munitions and real guidance types").

    python3 tools/munition_audit/guidance_fix.py [--check]

Television-seeker weapons become COMMAND_GUIDED stores with CommandGuidance Mode "TV" (SBW-side missiles with the
pilot's seeker view and crosshair steering, AircraftTvGuidance):
  * AGM-65 (the F/A-18E's plain "AGM-65 · laser-guided" store): the plain AGM-65A/B is a TV Maverick; the laser
    one is the AGM-65E, which exists separately.
  * Kh-25MT: TV seeker (was labelled fire-and-forget on the FFA infrared lock).
  * KD-88: TV/IR land-attack missile with a data link (was an anti-radiation missile).
TV bombs (GBU-15, KAB-500Kr-OD) keep their data; the code gives them the same seeker view.
Launch-gun and projectile profiles for the new SBW missiles are cloned from the matching laser missile
(Kh-25MT <- Kh-25ML, KD-88 <- Kh-29ML with its own flight model and slower cruise flight). A second run changes
nothing; --check exits 1 when a file would change.
"""
import copy
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
SBW = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack', 'sbw')
NS = 'berts_vehicle_pack'
STORE_KEYS_DROP = ('Guidance', 'Flight')

TV_STORES = {
    # store path: (name, profile id or None to keep, clone-from profile, gun/profile overrides)
    'fa18e/agm65.json': dict(name='AGM-65B Maverick · TV'),
    'kh25mt.json': dict(name='Kh-25MT · TV', profile='kh25mt', clone='kh25ml',
                        gun={'Name': 'Kh-25MT · TV'}),
    'munition/kd88.json': dict(name='KD-88 · TV land attack (data link)', profile='kd88', clone='kh29l',
                               gun={'Name': 'KD-88 · TV land attack', 'Damage': 2840, 'ExplosionDamage': 2840,
                                    'ExplosionRadius': 1.25, 'TntEquivalentKg': 165, 'ProjectileLife': 900},
                               flight={'InitialSpeed': 1.0, 'MaxSpeed': 5.5, 'AccelerationPerTick': 0.08,
                                       'ThrustDurationTicks': 800, 'MaxTurnRateDegreesPerSecond': 40,
                                       'IgnitionDelayTicks': 8, 'EjectionSpeed': 1.0},
                               mesh={'Model': f'{NS}:custom_geo/aircraft_stores/kd88_flight.geo.json',
                                     'Texture': f'{NS}:textures/aircraft_stores/kd88.png', 'ForwardYaw': 0,
                                     'SpinDegreesPerTick': 0, 'ForwardAxis': 'Y', 'Origin': 'TAIL'},
                               combat={'CaliberMm': 540, 'DiameterMm': 540, 'HullDamage': 2840,
                                       'ModuleDamage': 1700, 'AmmoRackDamage': 340}),
}


# stores to offer next to one a carrier already allows: (new store, next to, carriers)
OFFER = [
    # the TV Maverick went wherever the AGM-65D (IIR) does on aircraft of its era or later
    (f'{NS}:fa18e/agm65', f'{NS}:fa18e/agm65d', ['a_10', 'f_16b', 'f_16c', 'fa_18e', 'f_111f']),
]


# Generic guided bombs on aircraft that never carried that kind of guidance (the pack's fallback 500 kg set gave
# every bomb-capable aircraft a GPS JDAM and a laser bomb, including a P-51D). GPS bombs need a 1990s+ airframe;
# laser bombs a 1970s+ strike aircraft with a designator or buddy lasing. The unguided 500 kg bomb stays.
GENERIC_GPS = [f'{NS}:fa18e/bomb_500kg_gps_1', f'{NS}:fa18e/bomb_500kg_gps_2']
GENERIC_LASER = [f'{NS}:fa18e/bomb_500kg_laser_1', f'{NS}:fa18e/bomb_500kg_laser_2']
NO_GUIDED_BOMBS = ['p_51d', 'f8f_1', 'f9f_2', 'meteor_f_8', 'sabre_mk_6', 'f_86k', 'md_450_ouragan', 'saab_j_21a_1',
                   'saab_32_lansen', 'f_84f', 'b_47e', 'm_50a', 'super_mystere', 'f_100c', 'f_104g', 'f_8h', 'f_5a',
                   'mirage_5', 'mig_21bis', 'mig_23mld', 'an_12b', 'c_130h', 'il_76m', 'saab_37_viggen']
NO_GPS_BOMBS = ['a_7d']     # A-7D: Paveway laser bombs, retired before JDAM


def armament_plan(out):
    for ac in NO_GUIDED_BOMBS + NO_GPS_BOMBS:
        drop = set(GENERIC_GPS) | (set(GENERIC_LASER) if ac in NO_GUIDED_BOMBS else set())
        path = os.path.join(SBW, 'aircraft_armaments', ac + '.json')
        raw = out.get(path) or load(path)
        arm = json.loads(raw)
        for pair in arm.get('Pairs', []) + arm.get('Singles', []):
            allowed = pair.get('AllowedStores', [])
            kept = [x for x in allowed if x not in drop]
            if kept:          # never leave a station with nothing to carry
                pair['AllowedStores'] = kept
        for group in list(arm.get('StoreGroups', {})):
            if group in drop:
                del arm['StoreGroups'][group]
        indent = 1 if raw.startswith('{\n ') and not raw.startswith('{\n  ') else 2
        out[path] = json.dumps(arm, indent=indent, ensure_ascii=False) + ('\n' if raw.endswith('\n') else '')


def offer_plan(out):
    for store, beside, carriers in OFFER:
        for ac in carriers:
            path = os.path.join(SBW, 'aircraft_armaments', ac + '.json')
            raw = out.get(path) or load(path)
            arm = json.loads(raw)
            for pair in arm.get('Pairs', []) + arm.get('Singles', []):
                allowed = pair.get('AllowedStores', [])
                if beside in allowed and store not in allowed:
                    allowed.insert(allowed.index(beside) + 1, store)
            indent = 1 if raw.startswith('{\n ') and not raw.startswith('{\n  ') else 2
            out[path] = json.dumps(arm, indent=indent, ensure_ascii=False) + ('\n' if raw.endswith('\n') else '')


def pod_channels(pair, store_id):
    """The native weapons a pod on this station fires (AircraftArmamentManager.nativeWeapons)."""
    mapped = (pair.get('NativeWeaponIds') or {}).get(store_id)
    if isinstance(mapped, list):
        return mapped
    return [mapped] if mapped else ([pair['WeaponId']] if pair.get('WeaponId') else [])


def pod_plan(out):
    """Rocket and gun pods only where the station has a native weapon to fire them (war recheck 2026-09-28:
    an S-8/B-8 or GSh-23 pod offered on a station without one could be fitted but never fired)."""
    stores = {}
    for f in glob.glob(os.path.join(SBW, 'aircraft_stores', '**', '*.json'), recursive=True):
        rel = os.path.relpath(f, os.path.join(SBW, 'aircraft_stores'))[:-5].replace(os.sep, '/')
        stores[f'{NS}:{rel}'] = json.loads(load(f)).get('Category')
    for path in sorted(glob.glob(os.path.join(SBW, 'aircraft_armaments', '*.json'))):
        raw = out.get(path) or load(path)
        arm = json.loads(raw)
        touched = False
        for pair in arm.get('Pairs', []) + arm.get('Singles', []):
            allowed = pair.get('AllowedStores', [])
            kept = [x for x in allowed
                    if stores.get(x) not in ('ROCKET_POD', 'GUN_POD') or pod_channels(pair, x)]
            if kept != allowed and kept:          # never leave a station with nothing to carry
                pair['AllowedStores'] = kept
                touched = True
                # pylon station placement lists may only name stores the mount allows
                dropped = set(allowed) - set(kept)
                # (a station that only placed a dropped store goes with it; paired lists drop the same entries)
                for key in ('Stations', 'LeftStations', 'RightStations'):
                    if key not in pair:
                        continue
                    stations = []
                    for station in pair[key]:
                        if 'Stores' in station:
                            station['Stores'] = [x for x in station['Stores'] if x not in dropped]
                            if not station['Stores']:
                                continue
                        if 'ExceptStores' in station:
                            station['ExceptStores'] = [x for x in station['ExceptStores'] if x not in dropped]
                        stations.append(station)
                    pair[key] = stations
        if touched:
            indent = 1 if raw.startswith('{\n ') and not raw.startswith('{\n  ') else 2
            out[path] = json.dumps(arm, indent=indent, ensure_ascii=False) + ('\n' if raw.endswith('\n') else '')


def load(path):
    with open(path) as f:
        return f.read()


def dump(obj):
    return json.dumps(obj, indent=2, ensure_ascii=False) + '\n'


def plan():
    """{path: new text} for every file the fixes write."""
    out = {}
    for rel, spec in TV_STORES.items():
        path = os.path.join(SBW, 'aircraft_stores', rel)
        store = json.loads(load(path))
        store['Name'] = spec['name']
        store['Category'] = 'COMMAND_GUIDED'
        store['CommandGuidance'] = {'Mode': 'TV'}
        for k in STORE_KEYS_DROP:
            store.pop(k, None)
        pid = spec.get('profile')
        if pid:
            store['ProjectileProfile'] = f'{NS}:aircraft_stores/{pid}'
            store['LaunchGunProfile'] = f'{NS}:aircraft_stores/{pid}'
            src = spec['clone']
            gun = json.loads(load(os.path.join(SBW, 'guns', 'aircraft_stores', src + '.json')))
            gun.update(spec.get('gun', {}))
            gun['Projectile'] = dict(gun['Projectile'], Profile=f'{NS}:aircraft_stores/{pid}')
            prof = json.loads(load(os.path.join(SBW, 'projectile_profiles', 'aircraft_stores', src + '.json')))
            prof['Combat'] = dict(prof['Combat'], WeaponId=f'{NS}:aircraft_stores/{pid}', RoundId=f'{NS}:{pid}',
                                  **spec.get('combat', {}))
            prof['GuidedPropulsion'] = dict(prof['GuidedPropulsion'], **spec.get('flight', {}))
            if 'mesh' in spec:
                prof['Extensions'][f'{NS}:projectile_mesh_v1'] = spec['mesh']
            out[os.path.join(SBW, 'guns', 'aircraft_stores', pid + '.json')] = dump(gun)
            out[os.path.join(SBW, 'projectile_profiles', 'aircraft_stores', pid + '.json')] = dump(prof)
        else:
            gun_path = os.path.join(SBW, 'guns', 'aircraft_stores', store['LaunchGunProfile'].split('/')[-1] + '.json')
            gun = json.loads(load(gun_path))
            gun['Name'] = spec['name']
            out[gun_path] = dump(gun)
        out[path] = dump(store)
    armament_plan(out)
    offer_plan(out)
    pod_plan(out)
    return out


def main(argv):
    check = '--check' in argv
    changed = []
    for path, text in plan().items():
        if not os.path.exists(path) or load(path) != text:
            changed.append(os.path.relpath(path, REPO))
            if not check:
                with open(path, 'w') as f:
                    f.write(text)
    print(('would change: ' if check else 'changed: ') + (', '.join(changed) or 'nothing'))
    return 1 if check and changed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
