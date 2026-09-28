"""Sets the rifle-calibre floor ("min_armor_mm") on every armored ground vehicle's armor profile.

    python3 tools/armor_mesh/armor_floor.py [--check]

The owner does not want a 7.62 mm coax to kill an IFV (2026-09-28). Rifle-calibre rounds in the pack
penetrate at most 13 mm (7.62 B-32 / M61 AP, 5.8 DBP10); 12.7 mm AP starts at 26 mm. ArmorProfiles reads
"min_armor_mm" as a floor under every plate (box or mesh) and under gaps in box armor ("unboxed" hits), so
a floor of 16 mm stops every rifle-calibre round at any angle while heavy machine guns and autocannons keep
their effect on the thin roofs and sides. Every SBW vehicle of Type Tank or APC gets it; cars (the technicals,
the UAZ), emplacements and aircraft do not.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack')
FLOOR_MM = 16
TYPES = {'Tank', 'APC'}


def vehicle_type(vid):
    path = os.path.join(DATA, 'sbw', 'vehicles', f'{vid}.json')
    if not os.path.exists(path):
        return None
    m = re.search(r'"Type": *"([A-Za-z_]+)"', open(path).read())
    return m.group(1) if m else None


def main(argv):
    check = '--check' in argv
    changed = []
    for name in sorted(os.listdir(os.path.join(DATA, 'armor'))):
        vid = name[:-5]
        if vehicle_type(vid) not in TYPES:
            continue
        path = os.path.join(DATA, 'armor', name)
        text = open(path).read()
        profile = json.loads(text)
        if profile.get('min_armor_mm') == FLOOR_MM:
            continue
        changed.append(vid)
        if check:
            continue
        out = {}
        for k, v in profile.items():
            if k == 'min_armor_mm':
                continue
            out[k] = v
            if k == 'unboxed_hits_penetrate':
                out['min_armor_mm'] = FLOOR_MM
        if 'min_armor_mm' not in out:
            out = {**{k: v for k, v in list(out.items())[:2]}, 'min_armor_mm': FLOOR_MM,
                   **{k: v for k, v in list(out.items())[2:]}}
        indent = 2 if text.startswith('{\n  ') else None
        with open(path, 'w') as f:
            f.write(json.dumps(out, indent=indent) + ('\n' if text.endswith('\n') else ''))
    print(('needs floor: ' if check else 'floor set: ') + (', '.join(changed) or 'nothing to change'))
    return 1 if check and changed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
