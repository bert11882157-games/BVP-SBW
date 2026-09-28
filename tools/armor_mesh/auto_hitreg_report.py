#!/usr/bin/env python3
"""Summary of ArmorMeshHitregHarness output for a generated mesh (rays from auto_hitreg.py).

    python3 tools/armor_mesh/auto_hitreg_report.py <id> <harness.csv> [--json]

Per yaw-0 ray category: how the game resolved it. The gates:
* core rays (the shot's first model contact is armored structure) resolve to a plate by ray: >= 99.5%;
* track rays with structure behind them resolve to a plate (tracks have no hitbox): >= 97%;
* plate placement: the armor entry lies within 8 px (half a block) of the model surface the shot met for >= 80% of
  core rays (the within-2 px share is reported too; convex solids bridge concave outlines such as the gap under a
  turret bustle, so a generated mesh sits a few px proud of the model in places);
* turret-frame rays resolve to the same volume at every other yaw (frame consistency): >= 95%.
"""
import argparse
import collections
import csv
import json
import sys

PX = 1 / 16


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument('vid')
    ap.add_argument('csv')
    ap.add_argument('--json', action='store_true')
    a = ap.parse_args(argv)
    base, others = {}, collections.defaultdict(dict)
    for r in csv.DictReader(open(a.csv)):
        if float(r['yaw']) == 0:
            base[r['ray']] = r
        else:
            others[r['yaw']][r['ray']] = r
    out = collections.defaultdict(collections.Counter)
    dists = []
    for r in base.values():
        out[r['cat']][r['outcome']] += 1
        if r['cat'] == 'core' and r['outcome'] == 'plate' and r['dist']:
            dists.append(float(r['dist']) / PX)
    core = out['core']
    core_ok = core['plate'] / max(1, sum(core.values()))
    track_behind = collections.Counter(r['outcome'] for r in base.values()
                                       if r['cat'] == 'track' and float(r['core_t']) > 0)
    track_ok = track_behind['plate'] / max(1, sum(track_behind.values())) if track_behind else 1.0
    dists.sort()
    within = sum(1 for d in dists if abs(d) <= 2.0) / max(1, len(dists))
    within8 = sum(1 for d in dists if abs(d) <= 8.0) / max(1, len(dists))
    pct = lambda p: round(dists[min(len(dists) - 1, int(p * len(dists)))], 2) if dists else None
    # turret rays rotate with the turret; at another yaw the hull may stand in front of a rotated ray (occluded),
    # which is geometry, not a frame error; an inconsistency is a different turret/barrel volume or outcome
    consistent, total, occluded = 0, 0, 0
    for yaw, rows in others.items():
        for k, r in rows.items():
            b = base.get(k)
            if b and b['frame'] in ('turret', 'barrel') and b['cat'] == 'core':
                if r['volume_frame'].lower() == 'hull' and b['volume_frame'].lower() != 'hull':
                    occluded += 1
                    continue
                total += 1
                consistent += (r['outcome'] == b['outcome'] and r['volume'] == b['volume'])
    cons = consistent / total if total else 1.0
    gates = {'core_plate': core_ok >= 0.995, 'track_through': track_ok >= 0.97, 'placement_8px': within8 >= 0.80,
             'turret_consistent': cons >= 0.95}
    res = {'vid': a.vid, 'core_plate': round(core_ok, 4), 'core_outcomes': dict(core),
           'track_behind_plate': round(track_ok, 4), 'track_behind': dict(track_behind),
           'placement_within_2px': round(within, 4), 'placement_within_8px': round(within8, 4), 'dist_px_p5_p50_p95': [pct(0.05), pct(0.5), pct(0.95)],
           'turret_consistency': round(cons, 4), 'turret_rows': total, 'turret_rows_occluded_by_hull': occluded,
           'by_category': {k: dict(v) for k, v in out.items()}, 'pass': all(gates.values()), 'gates': gates}
    print(json.dumps(res) if a.json else json.dumps(res, indent=1))
    return 0 if res['pass'] else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
