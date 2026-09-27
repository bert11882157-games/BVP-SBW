"""30 mm autocannon HE belts fly at the gun's APDS/APFSDS velocity (user rule, 2026-09-27: "30mm HE is too slow,
needs to be same velocity as the apds"). Also repairs guns whose weapon-level Velocity was a placeholder (18/30) and
the BTR-80A 2A72, whose APDS belt sat at 18 (the 3UBR8 is the same round as the BMP-2's: 1120 m/s = 56 blocks/tick).

    python3 tools/ballistics/autocannon_he_velocity.py [--check]

Only ground/helicopter guns that carry an APDS or APFSDS belt are touched; guns without one (Tunguska 2A38, aircraft
air/ground belts) keep their War Thunder muzzle velocities.
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SBW = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack', 'sbw')
KINETIC = {'APDS', 'APFSDS'}
# gun -> the War Thunder velocity of its APDS round when the data has none worth trusting (blocks/tick = m/s / 20)
FIXED_APDS = {('btr80a', 'Cannon'): 56.0}


def combat(pid):
    if not pid:
        return {}
    path = os.path.join(SBW, 'projectile_profiles', pid.split(':', 1)[1] + '.json')
    return json.load(open(path)).get('Combat', {}) if os.path.exists(path) else {}


def fix(vid, d):
    changes = []
    for wn, w in (d.get('Weapons') or {}).items():
        ammo = w.get('AmmoType') or []
        rounds = []
        for a in ammo:
            o = a.get('Override') or {}
            c = combat((o.get('Projectile') or {}).get('Profile'))
            rounds.append((o, c))
        if not rounds or not all(c.get('CaliberMm') == 30 for _, c in rounds):
            continue
        kin = [o.get('Velocity', w.get('Velocity')) for o, c in rounds if c.get('HullDamageClass') in KINETIC]
        if not kin:
            continue
        v = FIXED_APDS.get((vid, wn), max(kin))
        for o, c in rounds:
            if o.get('Velocity', w.get('Velocity')) != v:
                changes.append(f"{vid} {wn} {o.get('Name', '?').split('.')[-1]}: {o.get('Velocity', w.get('Velocity'))} -> {v}")
                o['Velocity'] = v
        if w.get('Velocity') != v:
            changes.append(f'{vid} {wn} weapon: {w.get("Velocity")} -> {v}')
            w['Velocity'] = v
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
