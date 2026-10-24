#!/usr/bin/env python3
"""Exhaust outlets moved onto the model's own nozzle exits (found with audit.py and side_section.py, 2026-10-23).

Each fix gives an outlet's Position (vehicle-local blocks) and optionally NozzleRadiusBlocks, measured on the model:
the exit plane is the nozzle lip (or the flat painted exit disc) seen from straight behind. The asset JSON is edited in
place, so its layout is kept; run again it changes nothing. tools/../work/handbuilt/run.ps1 applies it after a rebuild.

Usage: python3 tools/engine_nozzles/fix_outlets.py [ids...]   (default: every aircraft listed here)
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack', 'sbw',
                      'vehicles')

FIXES = {
    # the jet pipe ends at a flat exit disc at z -5.20 under the tail boom (radius about half a block); the outlet sat
    # 2.6 blocks further aft, in the open air under the boom
    'f3h': {'afterburner_0': {'Position': [0, 2.05, -5.2], 'NozzleRadiusBlocks': 0.5}},
    # the pipe's lip is at z -4.3; the outlet sat 1.3 blocks up the pipe, at the turbine face
    'f9f_2': {'engine_0': {'Position': [0.0, 1.407, -4.3]}},
    # the nozzles end at z -9.26 (a flat exit); the outlets sat 0.45 inside them
    'su_30': {'afterburner_left': {'Position': [1.140895625, 2.0075375, -9.26]},
              'afterburner_right': {'Position': [-1.140895625, 2.0075375, -9.26]}},
    # the nozzle discs are centred at x +-1.30, y 2.10 with their lips at z -9.04; the outlets sat on the discs' upper
    # outer edges, 0.3 inside
    'su_57': {'afterburner_0': {'Position': [1.3, 2.1, -9.04]},
              'afterburner_1': {'Position': [-1.3, 2.1, -9.04]}},
}

NUMBER = re.compile(r'-?\d+(\.\d+)?([eE][-+]?\d+)?')


def fix(vid):
    path = os.path.join(ASSETS, vid + '.json')
    with open(path, encoding='utf-8', newline='') as f:
        text = f.read()
    outlets = {o['Id']: o for o in json.loads(text)['AfterburnerPresentation']['Outlets']}
    start = text.find('"Outlets"')
    changed = []
    for oid, want in FIXES[vid].items():
        o = outlets[oid]
        if all(o.get(k) == v for k, v in want.items()):
            continue
        m = re.compile(r'"Id"\s*:\s*"%s"' % re.escape(oid)).search(text, start)
        end = text.find('}', m.end())                       # the outlet's own object (no nested objects in it)
        body = text[m.end():end]
        for key, value in want.items():
            if key == 'Position':
                p = re.compile(r'"Position"\s*:\s*\[([^\]]*)\]').search(body)
                values = iter(value)
                inner = NUMBER.sub(lambda _: repr(next(values)), p.group(1))
                body = body[:p.start(1)] + inner + body[p.end(1):]
            else:
                p = re.compile(r'"%s"\s*:\s*(%s)' % (key, NUMBER.pattern)).search(body)
                body = body[:p.start(1)] + repr(value) + body[p.end(1):]
        text = text[:m.end()] + body + text[end:]
        changed.append(oid)
    if changed:
        with open(path, 'w', encoding='utf-8', newline='') as f:
            f.write(text)
    return changed


def main(argv):
    for vid in [a for a in argv if a in FIXES] if argv else sorted(FIXES):
        changed = fix(vid)
        print(f'{vid}: ' + (', '.join(changed) + ' moved onto the nozzle' if changed else 'outlets already on the nozzles'))


if __name__ == '__main__':
    main(sys.argv[1:])
