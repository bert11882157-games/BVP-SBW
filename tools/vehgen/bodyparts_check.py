"""Pre-checkpoint check: AircraftTerrainContact.valid() on every aircraft and helicopter data file (read-only).

python tools/vehgen/bodyparts_check.py [ID ...]        (no ids = the whole fleet; BVP generated data and the SBW built-in data)

Mirrors sbw/.../data/vehicle/subdata/AircraftTerrainContact.kt `valid()` exactly. DefaultVehicleData.kt throws
"AircraftTerrainContact requires two finite nonempty boxes" at spawn when it is false (the 0.11.185beta Vulcan / H-6K / Vautour spawn failure).
A contact block is rejected when any of these holds:
  - Fuselage or LandingGear is not a valid box (a missing key is the default box Min = Max = 0, which is invalid);
  - BodyParts has more than 16 entries (an empty list is fine: the fuselage stands in);
  - any BodyParts entry is not a valid box;
  - WreckSections is non-empty and does not hold exactly 4 valid boxes;
  - WheelContacts is non-empty and is not valid: 1..32 entries, each with a non-blank Id of at most 96 chars and a finite Position,
    unique ids, optional Bounds valid (all or none of the contacts have Bounds), and at least one MAIN contact.
A valid box has a non-blank Bone of at most 96 chars (default "hull"), finite Min and Max, and Min < Max strictly on every axis.
Exit code 1 on any failure. The matching JUnit test is sbw AircraftTerrainContactDataTest.
"""
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, '..', '..'))
SOURCES = [
    os.path.join(REPO, 'bvp/src/generated/resources/data/berts_vehicle_pack/sbw/vehicles'),
    os.path.join(REPO, 'sbw/src/main/resources/assets/superbwarfare/sbw/vehicles'),
]
MAX_BODY_PARTS = 16
MAX_WHEELS = 32


def vec_ok(v):
    return isinstance(v, list) and len(v) == 3 and all(isinstance(x, (int, float)) and math.isfinite(x) for x in v)


def box_problem(b):
    if not isinstance(b, dict):
        return 'not an object'
    bone = b.get('Bone', 'hull')
    if not isinstance(bone, str) or not bone.strip() or len(bone) > 96:
        return 'bad Bone'
    lo, hi = b.get('Min', [0, 0, 0]), b.get('Max', [0, 0, 0])
    if not vec_ok(lo) or not vec_ok(hi):
        return 'non-finite or malformed Min/Max'
    if not all(lo[i] < hi[i] for i in range(3)):
        return 'empty or degenerate box (Min >= Max on an axis): %s %s' % (lo, hi)
    return None


def problems(tc):
    out = []
    for key in ('Fuselage', 'LandingGear'):
        p = box_problem(tc.get(key, {}))
        if p:
            out.append('%s: %s' % (key, p))
    parts = tc.get('BodyParts', [])
    if len(parts) > MAX_BODY_PARTS:
        out.append('BodyParts has %d entries (limit %d)' % (len(parts), MAX_BODY_PARTS))
    for i, b in enumerate(parts):
        p = box_problem(b)
        if p:
            out.append('BodyParts[%d]: %s' % (i, p))
    wreck = tc.get('WreckSections', [])
    if wreck:
        if len(wreck) != 4:
            out.append('WreckSections has %d entries (must be 0 or 4)' % len(wreck))
        for i, b in enumerate(wreck):
            p = box_problem(b)
            if p:
                out.append('WreckSections[%d]: %s' % (i, p))
    wc = tc.get('WheelContacts', [])
    if wc:
        bad = []
        if len(wc) > MAX_WHEELS:
            bad.append('more than %d contacts' % MAX_WHEELS)
        ids = [w.get('Id', '') for w in wc]
        for w in wc:
            i = w.get('Id', '')
            if not isinstance(i, str) or not i.strip() or len(i) > 96:
                bad.append('bad Id %r' % (i,))
            if not vec_ok(w.get('Position', [0, 0, 0])):
                bad.append('%s: non-finite Position' % i)
            if w.get('Bounds') is not None:
                p = box_problem(w['Bounds'])
                if p:
                    bad.append('%s Bounds: %s' % (i, p))
        if len(set(ids)) != len(ids):
            bad.append('duplicate ids')
        withb = sum(1 for w in wc if w.get('Bounds') is not None)
        if 0 < withb < len(wc):
            bad.append('some contacts have Bounds and some do not')
        if not any(w.get('Group', 'MAIN') == 'MAIN' for w in wc):
            bad.append('no MAIN contact')
        for b in bad:
            out.append('WheelContacts: ' + b)
    return out


def main(argv):
    want = set(argv)
    seen, fails, checked = set(), 0, 0
    counts = {}
    for src in SOURCES:
        if not os.path.isdir(src):
            continue
        for name in sorted(os.listdir(src)):
            if not name.endswith('.json'):
                continue
            vid = name[:-5]
            if want and vid not in want:
                continue
            with open(os.path.join(src, name), encoding='utf-8') as f:
                d = json.load(f)
            tc = d.get('AircraftTerrainContact')
            kind = d.get('Type')
            if tc is None:
                if kind in ('Airplane', 'Helicopter'):
                    print('NOTE  %s (%s): no AircraftTerrainContact block (skipped by valid())' % (vid, kind))
                continue
            checked += 1
            counts[vid] = len(tc.get('BodyParts', []))
            seen.add(vid)
            for p in problems(tc):
                fails += 1
                print('FAIL  %s [%s]: %s' % (vid, os.path.relpath(src, REPO).split(os.sep)[0], p))
    for vid in sorted(want - seen):
        fails += 1
        print('FAIL  %s: no data file with an AircraftTerrainContact block' % vid)
    top = sorted(counts.items(), key=lambda kv: -kv[1])[:5]
    print('checked %d aircraft/helicopter files; most BodyParts: %s' % (checked, ', '.join('%s %d' % kv for kv in top)))
    print('%d failures' % fails)
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
