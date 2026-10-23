#!/usr/bin/env python3
"""Removes CanopyGlass from vehicle assets (the canopy glass is being redone: until then no vehicle draws any).
Usage: python3 tools/canopy_models/no_glass.py [--except id,id] [id...]   (no ids: every asset that has glass)"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import canopy as C  # noqa: E402

ASSETS = C.L.ASSET_DIR


def main(argv):
    skip = set()
    if '--except' in argv:
        skip = set(argv[argv.index('--except') + 1].split(','))
    ids = [a for a in argv if not a.startswith('--') and ',' not in a and a not in skip]
    if not ids:
        ids = sorted(f[:-5] for f in os.listdir(ASSETS) if f.endswith('.json'))
    n = 0
    for vid in ids:
        if vid in skip:
            continue
        path = os.path.join(ASSETS, vid + '.json')
        with open(path, encoding='utf-8') as f:
            asset = json.load(f)
        if 'CanopyGlass' not in asset:
            continue
        asset.pop('CanopyGlass')
        C.write_asset_lf(path, asset)
        n += 1
        print('no glass:', vid)
    print(n, 'assets changed')


if __name__ == '__main__':
    main(sys.argv[1:])
