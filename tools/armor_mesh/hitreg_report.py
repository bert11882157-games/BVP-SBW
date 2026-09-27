#!/usr/bin/env python3
"""Summarises ArmorMeshHitregHarness output against the ground-truth rays.

    python3 tools/armor_mesh/hitreg_report.py t90a rays.jsonl harness.csv [--examples N]

Checks, per ray (turret yaw 0 rows against the visual model; other yaws against yaw 0):
* core-first rays (the shot's first model contact is the armored structure) must resolve to a plate by ray, or to a
  track module where the tracks stand in front; a miss or a proximity-only match is a hit registration gap;
* the armor entry must lie within 2 px of the visual surface the shot met (plate placement);
* the entered face normal must be the plate's own outer face normal (a slab rim or a skin corner is an error);
* fitting/ERA/track-first rays with armored structure behind them must still find a plate;
* rays that meet no armored structure (fittings only) are reported by outcome, as information;
* at every other turret yaw, turret rays must resolve exactly like at yaw 0 (frame consistency).
"""
import argparse
import collections
import csv
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_mesh as B  # noqa: E402

PX = 1 / 16


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument('vid')
    ap.add_argument('rays')
    ap.add_argument('csv')
    ap.add_argument('--examples', type=int, default=6)
    ap.add_argument('--vehicle-data', help='sbw/vehicles/<id>.json: also require an SBW OBB contact for core rays '
                                           '(a shell only reaches the armor resolver after one)')
    a = ap.parse_args(argv)
    spec, volumes = B.build(a.vid)
    # expected outer normal per plate, in the armor-profile frame ((-x, y, z) of geo)
    expect = {}
    for v in volumes:
        if v['kind'] == 'plate' and 'area' in v:
            n = v['n']
            expect[B.bone_name(v).split('__', 2)[2]] = np.array([-n[0], n[1], n[2]])
    rays = [json.loads(l) for l in open(a.rays)]
    # rows are written yaw by yaw, yaw 0 first; keep yaw 0 and stream the rest (a full sweep is ~1.5M rows)
    reader = csv.DictReader(open(a.csv))
    base = []
    first_other = None
    for r in reader:
        if r['yaw'] not in ('0', '0.0'):
            first_other = r
            break
        base.append(r)

    def other_yaws():
        if first_other is None:
            return
        cur, rows = first_other['yaw'], [first_other]
        for r in reader:
            if r['yaw'] != cur:
                yield cur, rows
                cur, rows = r['yaw'], []
            rows.append(r)
        yield cur, rows
    problems = collections.defaultdict(list)
    stats = collections.Counter()
    offsets = []
    for i, (ray, r) in enumerate(zip(rays, base)):
        cat, out = ray['cat'], r['outcome']
        core_behind = ray['core_t'] >= 0
        stats[(cat, out)] += 1
        if cat == 'core':
            if out in ('miss', 'plate_proximity'):
                problems['core ray without a plate on the ray'].append((i, ray, r))
            elif out == 'plate':
                # placement error measured across the plate (along its normal): along the shot it grows as
                # 1/cos at grazing angles, where a 0.3 px height difference reads as several px of travel
                d = float(r['dist'])
                if r['volume'] in expect:
                    d *= abs(float(np.dot(ray['d'], expect[r['volume']])))
                offsets.append(d)
                solid = ray.get('solid_t', -1)
                if abs(d) > 2 * PX:
                    # entry before the model face, but on a core component's hull that the shot entered earlier
                    # without meeting a face: the model component is open there, the armor solid is right
                    if solid >= 0 and d < 0 and abs((ray['t'] + float(r['dist'])) - solid) <= 2 * PX:
                        stats[('info', 'open model component: armor on the component hull')] += 1
                    else:
                        problems['plate entry more than 2 px from the model surface'].append((i, ray, r))
        elif core_behind and out in ('miss', 'plate_proximity'):
            problems[f'{cat} ray with armored structure behind it but no plate'].append((i, ray, r))
        if out == 'plate' and r['volume'] in expect and r['nx'] != '':
            n = np.array([float(r['nx']), float(r['ny']), float(r['nz'])])
            e = expect[r['volume']]
            if r['volume_frame'] in ('turret', 'barrel', 'hull') and n @ e < 0.999:
                problems['entered face is not the plate face (rim or skin corner)'].append((i, ray, r))
    if a.vehicle_data:
        d = json.load(open(a.vehicle_data))
        tp = np.array(d['TurretPos'], float)
        boxes = []
        for o in d['OBB']:
            c, h = np.array(o['Position'], float), np.array(o['Size'], float)
            if o.get('Transform') == 'Turret':
                boxes.append((c + tp, h))
            elif o.get('Transform') in (None, 'Vehicle', 'Default'):
                boxes.append((c, h))
        for i, ray in enumerate(rays):
            if ray['cat'] != 'core':
                continue
            dd = np.array(ray['d'], float) * [-1, 1, -1]
            o = (np.array(ray['hit'], float) - 4 * np.array(ray['d'], float)) * [-1, 1, -1]
            hit = False
            for c, h in boxes:
                with np.errstate(divide='ignore', invalid='ignore'):
                    t1, t2 = (c - h - o) / dd, (c + h - o) / dd
                tn, tf = np.nanmax(np.minimum(t1, t2)), np.nanmin(np.maximum(t1, t2))
                if tn <= tf and tf >= 0 and tn <= 8:
                    hit = True
                    break
            if not hit:
                problems['core ray with no SBW OBB contact (the shell never reaches the armor)'].append(
                    (i, ray, base[i]))
    print(f'{a.vid}: {len(base)} rays at yaw 0')
    grazes = collections.Counter(r['cat'] for r in base if r.get('exact') == 'false' and r['outcome'] == 'plate')
    if grazes:
        print(f'  plate hits decided by the skin alone (shot passes within the skin of a plate): {dict(grazes)}')
    for cat in ('core', 'track', 'era', 'barrel', 'fitting'):
        line = {o: c for (k, o), c in stats.items() if k == cat}
        print(f'  {cat:8s} {dict(sorted(line.items()))}')
    if offsets:
        o = np.array(offsets) / PX
        print(f'  plate entry vs model surface (px): median {np.median(o):.2f}, p5 {np.percentile(o, 5):.2f}, '
              f'p95 {np.percentile(o, 95):.2f}, max |.| {np.abs(o).max():.2f}')
    for (k, o), c in stats.items():
        if k == 'info':
            print(f'  info: {o}: {c}')
    occluded = collections.Counter()
    graze_superseded = collections.Counter()
    for yaw, rs in other_yaws():
        bad = 0
        for i, (r0, r1) in enumerate(zip(base, rs)):
            if r0['volume_frame'] in ('turret', 'barrel') and r0['outcome'] == 'plate':
                if r0.get('exact') == 'false' and (r1['volume'] != r0['volume'] or r1.get('exact') == 'true'):
                    # yaw 0 only grazed a turret plate's skin; turned, the shot reaches a solid (hull armor, a
                    # track) further on, and a solid hit outranks a skin graze
                    graze_superseded[yaw] += 1
                    continue
                if r1['volume_frame'] not in ('turret', 'barrel') and r1['dist'] != '' \
                        and float(r1['dist']) < float(r0['dist']) + 1e-6:
                    # the turret turned, the hull did not: the rotated shot now meets hull armor or a track first
                    occluded[yaw] += 1
                    continue
                if r1['volume'] != r0['volume'] or abs(float(r1['dist'] or 99) - float(r0['dist'])) > 1e-4:
                    bad += 1
                    if len(problems[f'turret frame result changes at yaw {yaw}']) < 50:
                        problems[f'turret frame result changes at yaw {yaw}'].append((i, rays[i], r1))
        print(f'  yaw {yaw}: {bad} turret-frame plate results differ from yaw 0 '
              f'({occluded[yaw]} now meet hull armor/tracks first, as the hull does not turn; '
              f'{graze_superseded[yaw]} were skin grazes now resolved to a solid behind)')
    print('problems:')
    if not problems:
        print('  none')
    for key, items in problems.items():
        print(f'  {key}: {len(items)}')
        where = collections.Counter((it[1]['az'], it[1]['el']) for it in items)
        print('    by direction (az, el):', dict(where.most_common(8)))
        vols = collections.Counter(it[2]['volume'] for it in items)
        print('    by volume:', dict(vols.most_common(8)))
        for i, ray, r in items[:a.examples]:
            print(f"    ray {i} az {ray['az']} el {ray['el']} hit {ray['hit']} -> {r['outcome']} {r['volume']} "
                  f"dist {r['dist']} n ({r['nx']},{r['ny']},{r['nz']}) gap {r['gap']}")
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
