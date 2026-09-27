"""Tank-gun rounds and gun/vehicle missiles at War Thunder values: penetration, HE filler (TNT equivalent) and
muzzle velocity.

    python3 tools/ballistics/wt_tank_rounds.py [--check]

User rule (2026-09-27): War Thunder is the source for weaponry values. Source: the WT datamine
(gamedata/weapons/groundmodels_weapons/*.blkx: speed, explosiveMass x explosive.blkx strengthEquivalent) and the
wiki.warthunder.com unit pages (penetration at 10 m). See docs/WT_BELTS.md for the method.

Per round (matched by the projectile profile's Combat.RoundId):
  profile  Combat.PenetrationMm (and a PenetrationCurve scaled by the same factor, keeping its fall-off)
  entry    Override.ApDurability (the pack keeps it equal to the penetration), Override.TntEquivalentKg,
           Override.Velocity (blocks/tick = m/s / 20; missiles keep their own flight speed)
A second run changes nothing.
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SBW = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack', 'sbw')

# round id -> (WT muzzle velocity m/s or None to keep, penetration mm at 10 m or None, TNT equivalent kg or None)
ROUNDS = {
    '3bm60': (1660, 580, None), '3bm42': (1700, 457, None), '3bm22_apfsds': (1760, 425, None),
    '3bk18m_heat_fs': (905, 550, 2.841), '3of26_he': (850, 42, 5.239), 'dtc10_125_apfsds': (1770, 577, None),
    'm829a1_apfsds': (1575, 600, None), 'm829a2_apfsds': (1680, 629, None),
    'm830_heat_fs': (1140, 480, 2.362), 'm830a1_heat_mp': (1410, 350, 1.391),
    'dm53_apfsds': (1750, 653, None), 'jm33_apfsds': (1640, 481, None), 'jm12a1_heat_fs': (1140, 480, 2.148),
    'm900_apfsds': (1505, 522, None), 'm774_apfsds': (1509, 372, None), 'm728_apds': (1426, 265, None),
    'm456_heat_fs': (1173.5, 400, 1.271), 'm456a2_heat_fs': (1173.5, 400, 1.271),
    '3bm25_apfsds': (1430, 335, None), 'br_412d_apcbc': (887, 239, 0.100), '3bk17m_heat_fs': (1085, 390, 1.169),
    'of_412_he': (900, 27, 1.462), 'm332_apcr': (1249, 321, None), 'm82_apcbc': (853.4, 185, 0.137),
    'm431_heat_fs': (1216, 320, 0.713), 'm71_he': (823, 20, 1.212),
    # missiles: flight speed stays the pack's; penetration and warhead from WT
    '9m114_shturm': (None, 560, 4.001), '9m311_sam': (None, None, 4.620),
}


def load(path):
    return json.load(open(path))


def dump(path, data, text):
    open(path, 'w').write(json.dumps(data, indent=2) + ('\n' if text.endswith('\n') else ''))


def profile_path(pid):
    return os.path.join(SBW, 'projectile_profiles', pid.split(':', 1)[1] + '.json')


def fix_profile(pid, pen, changes, done):
    if pen is None or pid in done:
        return
    done.add(pid)
    path = profile_path(pid)
    if not os.path.exists(path):
        return
    text = open(path).read()
    d = json.loads(text)
    c = d.get('Combat', {})
    old = c.get('PenetrationMm')
    if old == pen:
        return
    c['PenetrationMm'] = pen
    curve = c.get('PenetrationCurve')
    if isinstance(curve, dict) and curve.get('PenetrationMm') and old:
        f = pen / old
        curve['PenetrationMm'] = [round(v * f, 1) for v in curve['PenetrationMm']]
    changes.append(f'{pid}: PenetrationMm {old} -> {pen}')
    if not CHECK:
        dump(path, d, text)


def close(key, have, want):
    """Existing values within WT's own rounding are kept (TNT 0.5 %, velocity 1 m/s)."""
    if not isinstance(have, (int, float)):
        return False
    if key == 'TntEquivalentKg':
        return abs(have - want) <= 0.005 * want
    if key == 'Velocity':
        return abs(have - want) * 20.0 <= 1.0
    return have == want


def round_of(pid):
    if not pid or not os.path.exists(profile_path(pid)):
        return None
    return load(profile_path(pid)).get('Combat', {}).get('RoundId', '').split(':')[-1]


def main():
    changes, done = [], set()
    for path in sorted(glob.glob(os.path.join(SBW, 'vehicles', '*.json'))):
        vid = os.path.basename(path)[:-5]
        text = open(path).read()
        d = json.loads(text)
        touched = False
        for wn, w in (d.get('Weapons') or {}).items():
            base = (w.get('Projectile') or {}).get('Profile')
            if round_of(base) in ROUNDS:
                pen = ROUNDS[round_of(base)][1]
                fix_profile(base, pen, changes, done)
                if pen is not None and not (w.get('AmmoType')) and 'ApDurability' in w and w['ApDurability'] != pen:
                    changes.append(f'{vid}.{wn} {round_of(base)}: ApDurability {w["ApDurability"]} -> {pen}')
                    w['ApDurability'] = pen
                    touched = True
            for entry in w.get('AmmoType') or []:
                o = entry.get('Override')
                if not isinstance(o, dict):
                    continue
                pid = (o.get('Projectile') or {}).get('Profile')
                if not pid or not os.path.exists(profile_path(pid)):
                    continue
                rid = load(profile_path(pid)).get('Combat', {}).get('RoundId', '').split(':')[-1]
                if rid not in ROUNDS:
                    continue
                vel, pen, tnt = ROUNDS[rid]
                fix_profile(pid, pen, changes, done)
                want = {}
                if pen is not None and ('ApDurability' in o or 'ApDurability' in w):
                    want['ApDurability'] = pen
                if tnt is not None:
                    want['TntEquivalentKg'] = tnt
                if vel is not None:
                    want['Velocity'] = round(vel / 20.0, 2)
                for k, v in want.items():
                    if close(k, o.get(k), v):
                        continue
                    if True:
                        changes.append(f'{vid}.{wn} {rid}: {k} {o.get(k)} -> {v}')
                        o[k] = v
                        touched = True
        if touched and not CHECK:
            dump(path, d, text)
    print('\n'.join(changes) if changes else 'nothing to change')
    return 1 if CHECK and changes else 0


CHECK = '--check' in sys.argv
if __name__ == '__main__':
    sys.exit(main())
