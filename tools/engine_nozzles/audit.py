#!/usr/bin/env python3
"""Exhaust outlets against the model's own nozzle exits, for every jet with an AfterburnerPresentation.

For each aircraft (the Elite Expansion ids are skipped) the jet-pipe exits are found in the geometry (nozzles.find:
pits, see-through pipes and painted exits seen from straight behind) and paired with the authored Outlets, nearest
first. Reported: a different count, an outlet more than OFF_XY (or half the pipe radius, if larger) across from its
pipe, or more than OFF_Z fore or aft of the exit rim, and an outlet with no pipe near it. A rear view with the found
pipes (green circles) and the outlets (small marks) is written per aircraft with --view=<dir>.

Usage: python3 tools/engine_nozzles/audit.py [--view=<dir>] [--json=<file>] [ids...]
"""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import nozzles as N  # noqa: E402

EXPANSION = {'a_10', 'f_16c', 'f_15c', 'f_15e', 'fa_18e', 'f_4c', 'a_10a', 'f_16a', 'f_15a', 'f_15ex', 'fa_18c', 'f_22a',
             'f_4e', 'f_4d', 'f_4b', 'f_14c'}
OFF_XY = 0.12      # blocks
OFF_Z = 0.5        # blocks


def jets():
    out = []
    for name in sorted(os.listdir(N.ASSETS)):
        vid = name[:-5]
        if not name.endswith('.json') or vid in EXPANSION:
            continue
        try:
            ab = json.load(open(os.path.join(N.ASSETS, name), encoding='utf-8')).get('AfterburnerPresentation')
        except (ValueError, OSError):
            continue
        if ab and ab.get('Outlets'):
            out.append(vid)
    return out


def lips(vid, authored):
    """The nozzle lip behind each outlet: the median first hit from behind on a ring just outside its nozzle radius
    (z, and the share of the ring that hit anything)"""
    depth, xs, ys = N.rear_depth(N.model_triangles(vid))
    out = []
    for p, r in authored:
        r = r or 0.3
        zs = []
        for a in np.linspace(0, 2 * np.pi, 48, endpoint=False):
            for k in (1.05, 1.15, 1.3):
                x, y = p[0] + k * r * np.cos(a), p[1] + k * r * np.sin(a)
                i, j = int(round((x - xs[0]) / N.STEP)), int(round((y - ys[0]) / N.STEP))
                if 0 <= j < depth.shape[0] and 0 <= i < depth.shape[1] and np.isfinite(depth[j, i]):
                    zs.append(depth[j, i])
                    break
        out.append((float(np.median(zs)) if zs else None, len(zs) / 48))
    return out


def audit(vid, view_dir=None):
    found = N.find(vid)
    authored = N.existing(vid)
    if view_dir:
        os.makedirs(view_dir, exist_ok=True)
        marks = [(o[0], o[1], o[3]) for o in found] + [(p[0], p[1], 0.04) for p, _ in authored]
        N.rear_view(vid, os.path.join(view_dir, vid + '.png'), marks)
    pairs, free = [], list(range(len(found)))
    for i, (p, r) in sorted(enumerate(authored), key=lambda a: a[1][0][0]):
        if not free:
            pairs.append((i, None, None))
            continue
        j = min(free, key=lambda j: np.hypot(found[j][0] - p[0], found[j][1] - p[1])) if free else None
        d = float(np.hypot(found[j][0] - p[0], found[j][1] - p[1])) if j is not None else None
        if j is None or d > max(OFF_XY, found[j][3]) * 2.5:
            # no pit found there: measure the exit around the outlet itself (open or dark pipe mouths the pit search
            # misses), so its depth is still checked
            m = N.probe(vid, p[0], p[1])
            if m is not None and m[3] <= N.MAX_RADIUS:
                found.append((m[0], m[1], m[2], m[3], 0.0))
                pairs.append((i, len(found) - 1, float(np.hypot(m[0] - p[0], m[1] - p[1]))))
            else:
                pairs.append((i, None, d))        # nothing near it
            continue
        free.remove(j)
        pairs.append((i, j, d))
    issues = []
    if len(found) != len(authored):
        issues.append(f'{len(authored)} outlets, {len(found)} pipes in the model')
    for i, j, d in pairs:
        p, r = authored[i]
        if j is None:
            issues.append(f'outlet {i} at ({p[0]:+.2f}, {p[1]:.2f}, {p[2]:.2f}) has no pipe near it'
                          + (f' (nearest {d:.2f} away)' if d is not None else ''))
            continue
        cx, cy, z, fr, _ = found[j]
        if d > max(OFF_XY, 0.5 * fr):
            issues.append(f'outlet {i} at ({p[0]:+.2f}, {p[1]:.2f}, {p[2]:.2f}): pipe at ({cx:+.2f}, {cy:.2f}) '
                          f'r {fr:.2f}, {d:.2f} across')
    for j in free:
        cx, cy, z, fr, _ = found[j]
        if fr >= 0.25:                        # smaller pits are tail stingers, gun ports, vents
            issues.append(f'pipe at ({cx:+.2f}, {cy:.2f}, {z:.2f}) r {fr:.2f} has no outlet')
    lip = lips(vid, authored)
    for i, ((p, r), (z, share)) in enumerate(zip(authored, lip)):
        if z is None or share < 0.6:
            issues.append(f'outlet {i}: no nozzle lip round it ({share:.0%} of the ring hit)')
        elif abs(p[2] - z) > OFF_Z:
            issues.append(f'outlet {i}: {p[2] - z:+.2f} blocks {"aft of" if p[2] < z else "inside"} its nozzle lip '
                          f'(z {z:.2f})')
    return found, authored, issues, lip


def main(argv):
    view_dir = next((a[7:] for a in argv if a.startswith('--view=')), None)
    out_json = next((a[7:] for a in argv if a.startswith('--json=')), None)
    ids = [a for a in argv if not a.startswith('--')] or jets()
    report = {}
    for vid in ids:
        try:
            found, authored, issues, lip = audit(vid, view_dir)
        except Exception as e:  # a model the finder cannot read is reported, not fatal
            print(f'{vid}: ERROR {e}', flush=True)
            report[vid] = {'error': str(e)}
            continue
        print(f'{vid}: {"OK" if not issues else "CHECK"} ({len(authored)} outlets, {len(found)} pipes)', flush=True)
        for s in issues:
            print('    ' + s, flush=True)
        report[vid] = {'found': [list(map(float, f[:4])) for f in found],
                       'authored': [[list(p), r] for p, r in authored], 'lips': lip, 'issues': issues}
    if out_json:
        json.dump(report, open(out_json, 'w'), indent=1)


if __name__ == '__main__':
    main(sys.argv[1:])
