"""Tank and assault-gun cannon rounds fly at their War Thunder muzzle velocities (user rule, 2026-09-27: War Thunder
is the source for weaponry values). About twenty guns still carried the 20 blocks/tick (400 m/s) placeholder on every
round, so a 3BM60 left the barrel at a quarter of its real speed.

    python3 tools/ballistics/tank_gun_velocity.py [--check]

Velocities are War Thunder muzzle velocities in m/s; the data stores blocks/tick = m/s / 20. Only rounds named in
ROUNDS are touched, and only when they still sit on a placeholder (<= 25 blocks/tick); gun-launched ATGMs keep their
own missile speed. The weapon-level Velocity follows the gun's first (default) round.
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SBW = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack', 'sbw')
PLACEHOLDER = 25.0

# War Thunder muzzle velocity (m/s) by round, keyed by the projectile profile's round id (ammo_NN_<id>)
ROUNDS = {
    # 125 mm 2A46 family / ZPT-98
    '3bm22_apfsds': 1760, '3bm42': 1700, '3bm60': 1660, '3bk18m_heat_fs': 905, '3of26_he': 850,
    'dtc10_125_apfsds': 1770, 'dtp_125_heat_fs': 905, 'dtb_125_he': 850,
    # 120 mm M256 / Rh120 L55
    'm829a2_apfsds': 1680, 'm830a1_heat_mp': 1400, 'm830_heat_fs': 1140, 'dm53_apfsds': 1750,
    # 115 mm U-5TS
    '3bm4_apfsds': 1615,
    # 105 mm M68 / L7
    'm900_apfsds': 1500, 'm774_apfsds': 1508, 'm456a2_heat_fs': 1174, 'm456_heat_fs': 1174, 'm728_apds': 1426,
    # 100 mm D-10T2S
    '3bm25_apfsds': 1415, 'br_412d_apcbc': 887, '3bk17m_heat_fs': 1085, 'of_412_he': 900,
    # 90 mm M41
    'm332_apcr': 1021, 'm82_apcbc': 853, 'm431_heat_fs': 1219, 'm71_he': 823,
    # 152 mm M81 (M551)
    'apfsds': 1478,
}
# vehicles whose single unnamed round is ambiguous by id alone
BY_VEHICLE = {('m551a1', 'apfsds'): 1478}


def round_id(profile):
    base = profile.split('/')[-1]
    parts = base.split('_', 2)
    return parts[2] if len(parts) == 3 and parts[0] == 'ammo' and parts[1].isdigit() else base


def fix(vid, d):
    changes = []
    for wn, w in (d.get('Weapons') or {}).items():
        first = None
        matched = False
        for i, a in enumerate(w.get('AmmoType') or []):
            o = a.get('Override') or {}
            prof = (o.get('Projectile') or {}).get('Profile') or ''
            rid = round_id(prof)
            ms = BY_VEHICLE.get((vid, rid), ROUNDS.get(rid) if rid != 'apfsds' else None)
            cur = o.get('Velocity', w.get('Velocity'))
            if ms is None:
                if i == 0:
                    first = cur
                continue
            v = round(ms / 20.0, 3)
            matched = True
            if i == 0:
                first = v if (cur is None or cur <= PLACEHOLDER) else cur
            if cur is not None and cur > PLACEHOLDER:
                continue
            changes.append(f'{vid} {wn} {rid}: {cur} -> {v}')
            a.setdefault('Override', o)['Velocity'] = v
        wv = w.get('Velocity')
        if matched and first is not None and wv is not None and wv <= PLACEHOLDER and first > PLACEHOLDER:
            changes.append(f'{vid} {wn} weapon: {wv} -> {first}')
            w['Velocity'] = first
    return changes


def main(argv):
    check = '--check' in argv
    total = []
    for path in sorted(glob.glob(os.path.join(SBW, 'vehicles', '*.json'))):
        text = open(path).read()
        d = json.loads(text)
        ch = fix(os.path.basename(path)[:-5], d)
        if ch:
            total += ch
            if not check:
                open(path, 'w').write(json.dumps(d, indent=2) + ('\n' if text.endswith('\n') else ''))
    print('\n'.join(total) or 'nothing to change')
    return 1 if (check and total) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
