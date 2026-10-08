"""WeaponPairs (a seat's primary/secondary weapon) may only name weapons that seat carries: retired names are
replaced by the seat's first weapons in order, and a pair whose seat carries nothing is dropped.
Usage: fix_pairs.py <tree> ids..."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from heli_stores import rel, load, dump

tree = sys.argv[1]
for vid in sys.argv[2:]:
    path = rel(tree, r'data\berts_vehicle_pack\sbw\vehicles', vid + '.json')
    data = load(path)
    pairs = data.get('WeaponPairs')
    if not pairs:
        continue
    out = []
    for p in pairs:
        seat = data['Seats'][p['Seat']].get('Weapons', [])
        if not seat:
            continue
        prim = p.get('Primary') if p.get('Primary') in seat else seat[0]
        sec = p.get('Secondary')
        if sec not in seat or sec == prim:
            sec = next((w for w in seat if w != prim), None)
        q = dict(p, Primary=prim)
        if sec:
            q['Secondary'] = sec
        else:
            q.pop('Secondary', None)
        if q != p:
            print(vid, p, '->', q)
        out.append(q)
    data['WeaponPairs'] = out
    dump(path, data)
