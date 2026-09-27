"""Weapon fire loudness by calibre: sets SoundInfo.Volume (the SpatialAudio gain of the fire cue) on every vehicle weapon.

    python3 tools/audio/weapon_loudness.py [--check]

User rule (2026-09-27): a battle must not be insufferable; bomb impacts and tank cannon fire are decently loud,
autocannons quieter, lower calibre quieter still. Before this every weapon played at the same gain (1.0): SoundRadius
only set how far a shot carried, not how loud it was.

SpatialAudio plays a shot at gain min(1, sqrt(6 / d)) x Volume per clip: Volume 2 keeps a tank gun at full level
out to 24 blocks, Volume 0.4 puts a 7.62 mm MG at 40 % even point blank. The ladder (by the calibre of the weapon's
first round, from its projectile profile's Combat.CaliberMm):

    >= 150 mm  artillery / 152 mm guns   2.2
    100-149    tank and assault guns     2.0
    75-99      medium guns               1.7
    40-74      heavy autocannons (40, 57 mm Bofors, 2A70 excluded: see 100 mm)  1.2
    30-39      30-35 mm autocannons      1.0
    20-29      20-25 mm autocannons      0.85
    12.7-19    HMGs (12.7, 14.5 mm)      0.6
    < 12.7     rifle-calibre MGs         0.4
    missiles (ATGM/SAM launch)           0.9
    automatic grenade launchers          0.55
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
SBW = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack', 'sbw')
LADDER = [(150, 2.2), (100, 2.0), (75, 1.7), (40, 1.2), (30, 1.0), (20, 0.85), (12.7, 0.6), (0, 0.4)]
GRENADE = ('mk19', 'ags_17', 'ags17', 'ags_30', 'ags30', 'qlz87', 'grenade')


def profile(pid):
    if not pid or ':' not in pid:
        return {}
    path = os.path.join(SBW, 'projectile_profiles', pid.split(':', 1)[1] + '.json')
    return json.load(open(path)) if os.path.exists(path) else {}


def weapon_profile(w):
    for a in w.get('AmmoType') or []:
        p = profile(((a.get('Override') or {}).get('Projectile') or {}).get('Profile'))
        if p:
            return p
    return profile((w.get('Projectile') or {}).get('Profile'))


def loudness(name, w):
    p = weapon_profile(w)
    c = p.get('Combat', {})
    ptype = ((w.get('Projectile') or {}).get('Type') or '').lower()
    fire = json.dumps(w.get('SoundInfo') or {}).lower()
    if any(g in fire or g in name.lower() for g in GRENADE):
        return 0.55
    if c.get('HullDamageClass') == 'ATGM' or 'missile' in ptype or 'missile' in name.lower():
        return 0.9
    cal = c.get('CaliberMm')
    if not cal:
        return None
    for lo, v in LADDER:
        if cal >= lo:
            return v
    return None


def main(argv):
    check = '--check' in argv
    changes, unknown = [], []
    for path in sorted(glob.glob(os.path.join(SBW, 'vehicles', '*.json'))):
        vid = os.path.basename(path)[:-5]
        text = open(path).read()
        d = json.loads(text)
        touched = False
        for name, w in (d.get('Weapons') or {}).items():
            info = w.get('SoundInfo')
            if not isinstance(info, dict) or not info.get('Fire3P'):
                continue
            v = loudness(name, w)
            if v is None:
                unknown.append(f'{vid}.{name}')
                continue
            if info.get('Volume') != v:
                changes.append(f'{vid}.{name}: Volume {info.get("Volume")} -> {v}')
                info['Volume'] = v
                touched = True
        if touched and not check:
            open(path, 'w').write(json.dumps(d, indent=2) + ('\n' if text.endswith('\n') else ''))
    print(f'{len(changes)} weapons changed' + (f'; no calibre for {len(unknown)}: {", ".join(unknown[:30])}' if unknown else ''))
    if check:
        print('\n'.join(changes[:40]))
    return 1 if (check and changes) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
