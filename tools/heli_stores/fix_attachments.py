"""A weapon may only name attachments its vehicle defines: in every weapon of the given vehicles, a ShootPos (or
weapon) key ending in Attachment/Attachments that names a missing attachment is removed (the special names Default,
Barrel and Vehicle are kept). Usage: fix_attachments.py <tree> ids..."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from heli_stores import rel, load, dump

SPECIAL = {'Default', 'Barrel', 'Vehicle'}


def clean(obj, names, where, report):
    if not isinstance(obj, dict):
        return
    for key in list(obj):
        v = obj[key]
        if key.endswith('Attachment') or key.endswith('Attachments'):
            refs = v if isinstance(v, list) else [v]
            missing = [r for r in refs if isinstance(r, str) and r not in SPECIAL and r not in names]
            if missing:
                report.append(f'{where}.{key}: {missing[:3]}')
                del obj[key]
        elif isinstance(v, dict):
            clean(v, names, f'{where}.{key}', report)


def fix(tree, vid):
    path = rel(tree, r'data\berts_vehicle_pack\sbw\vehicles', vid + '.json')
    data = load(path)
    names = set(data.get('Attachments', {}))
    report = []
    for wname, w in data.get('Weapons', {}).items():
        clean(w, names, wname, report)
    if report:
        dump(path, data)
    return report


if __name__ == '__main__':
    for vid in sys.argv[2:]:
        for r in fix(sys.argv[1], vid):
            print(vid, r)
