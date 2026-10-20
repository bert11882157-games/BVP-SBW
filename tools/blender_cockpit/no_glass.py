#!/usr/bin/env python3
"""Removes the canopy glass (CanopyGlass) from every vehicle asset, aircraft and helicopters alike (the owner's call,
2026-10-20: no glass for now). The rest of each file is left byte for byte: the glass block is cut out of the text,
and the result is checked to parse to the same asset less its glass.

Usage: python3 tools/blender_cockpit/no_glass.py [--dry-run]
"""
import glob, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack', 'sbw',
                      'vehicles')
dry = '--dry-run' in sys.argv
done = []
for path in sorted(glob.glob(os.path.join(ASSETS, '*.json'))):
    text = open(path, encoding='utf-8').read()
    if '"CanopyGlass"' not in text:
        continue
    asset = json.loads(text)
    asset.pop('CanopyGlass')
    m = re.search(r',\s*"CanopyGlass"\s*:\s*\{', text)
    depth, i = 0, m.end() - 1
    while True:                               # the glass block's closing brace
        depth += {'{': 1, '}': -1}.get(text[i], 0)
        if depth == 0:
            break
        i += 1
    new = text[:m.start()] + text[i + 1:]
    if json.loads(new) != asset:
        sys.exit(f'{os.path.basename(path)}: the cut does not give the asset less its glass')
    if not dry:
        with open(path, 'w', encoding='utf-8', newline='') as f:
            f.write(new)
    done.append(os.path.basename(path)[:-5])
print(f'{"would remove" if dry else "removed"} CanopyGlass from {len(done)} assets: {" ".join(done)}')
