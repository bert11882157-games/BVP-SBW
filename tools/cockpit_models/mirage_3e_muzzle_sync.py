#!/usr/bin/env python3
"""Mirage IIIE: makes every gun's Vehicle-frame ShootPos Positions[0] and ViewPosition EQUAL (exactly) to the Position of its muzzle
attachment (the fixed gun bank refuses a muzzle that differs from its attachment frame, even by rounding: 1.58827 vs 1.58828).
The guns stay at the real ports: the attachment's value is the master. Run after the pipeline's restore of the data json.
Usage: python tools/cockpit_models/mirage_3e_muzzle_sync.py"""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack', 'sbw', 'vehicles', 'mirage_3e.json')


def main():
    raw = open(DATA, encoding='utf-8', newline='').read()
    d = json.loads(raw)
    att = d.get('Attachments', {})
    fixed = 0

    def walk(o):
        nonlocal fixed
        if isinstance(o, dict):
            sp = o.get('ShootPos')
            if isinstance(sp, dict) and sp.get('Transform') == 'Vehicle' and sp.get('MuzzleAttachments'):
                pos = att[sp['MuzzleAttachments'][0]]['Position']
                if sp['Positions'][0] != pos or sp.get('ViewPosition') != pos:
                    sp['Positions'][0] = list(pos)
                    sp['ViewPosition'] = list(pos)
                    fixed += 1
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(d)
    with open(DATA, 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(d, indent=2, ensure_ascii=False) + ('\n' if raw.endswith('\n') else ''))
    print(f'mirage_3e muzzle sync: {fixed} gun(s) moved onto their muzzle attachment')


if __name__ == '__main__':
    main()