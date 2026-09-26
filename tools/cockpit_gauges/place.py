#!/usr/bin/env python3
"""Places round analogue ("steam") instruments on the instrument panel of aircraft without electronic displays.

The panel is found the way tools/flight_display/place.py finds a spot for a flight display (rays from the pilot's
eye into the rendered cockpit mesh; an aft-facing surface under the canopy with room and a clear view). Around that
spot the tool lays out a cluster:

        THROTTLE   ATTITUDE   ALTIMETER            (+ filler dials further out on both sides, where they fit)
        RPM        HEADING    FUEL

Every dial is re-seated on the panel surface behind it and kept only if the panel supports it and the pilot can see
it; the four working instruments must all fit (the cluster shrinks until they do), fillers are optional.

Output: "CockpitGauges" in the asset vehicle JSON (VEHICLE_LOCAL_BLOCKS: +X left, +Y up, +Z forward):
  {"Schema":1,"Frame":"VEHICLE_LOCAL_BLOCKS","Gauges":[{"Seat":0,"Kind":"ALTIMETER","Center":[...],"Normal":[...],
   "Up":[...],"Diameter":d,"Depth":e}, ...]}
Usage: python3 tools/cockpit_gauges/place.py [--write] [--sheet out.png] [id...]   (no ids: every steam cockpit)
"""
import json, math, os, sys
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'flight_display'))
import place as P  # noqa: E402

ANGULAR = 7.5            # degrees a dial subtends from the eye
MIN_D, MAX_D = 0.05, 0.12
RIM = 0.16               # bezel ring, fraction of the radius (matches CockpitGauges.RIM)
GAP = 0.006
DEPTH = 0.03

LIVE = [('THROTTLE', -1, 0.5), ('ATTITUDE', 0, 0.5), ('ALTIMETER', 1, 0.5), ('HEADING', 0, -0.5)]
# Narrow or short panels: a 2 x 2 block, or one row.
LAYOUTS = [
    LIVE,
    [('ATTITUDE', -0.5, 0.5), ('ALTIMETER', 0.5, 0.5), ('THROTTLE', -0.5, -0.5), ('HEADING', 0.5, -0.5)],
    [('THROTTLE', -1.5, 0), ('ATTITUDE', -0.5, 0), ('ALTIMETER', 0.5, 0), ('HEADING', 1.5, 0)],
]
FILLERS = [('FILLER_RPM', -1, -0.5), ('FILLER_FUEL', 1, -0.5), ('FILLER_OIL', -2, 0.5), ('FILLER_TEMP', 2, 0.5),
           ('FILLER_CLOCK', -2, -0.5), ('FILLER_VOLTS', 2, -0.5), ('FILLER_HYDRAULIC', -3, 0.5),
           ('FILLER_OXYGEN', 3, 0.5)]


def steam_cockpits():
    ids = sorted(f[:-5] for f in os.listdir(os.path.join(P.GEN, 'data/berts_vehicle_pack/flight_reference')))
    return [v for v in ids if v not in P.GLASS_COCKPITS]


def fit(scene, eye, centre, normal, up, d):
    """(support, visibility) of a dial of diameter d (with its bezel) at centre."""
    right = np.cross(up, normal)
    outer = d / 2 * (1 + RIM)
    sup = tot = 0
    for a in np.linspace(0, 2 * math.pi, 12, endpoint=False):
        for rr in (0.0, 0.5, 0.95):
            if rr == 0.0 and a > 0:
                continue
            s = centre + (right * math.cos(a) + up * math.sin(a)) * outer * rr
            tot += 1
            tb, _ = scene.cast(s + normal * 0.02, -normal, tmin=0.0, tmax=0.02 + 0.06)
            sup += tb is not None
    vis = n = 0
    for a in np.linspace(0, 2 * math.pi, 8, endpoint=False):
        for rr in (0.0, 0.8):
            if rr == 0.0 and a > 0:
                continue
            s = centre + (right * math.cos(a) + up * math.sin(a)) * d / 2 * rr
            v = s - eye
            dist = np.linalg.norm(v)
            tv, _ = scene.cast(eye, v / dist, tmin=0.05, tmax=dist - 0.02)
            vis += tv is None
            n += 1
    return sup / tot, vis / n


def reseat(scene, centre, normal):
    tb, _ = scene.cast(centre + normal * 0.05, -normal, tmin=0.0, tmax=0.15)
    return None if tb is None else centre + normal * 0.05 - normal * tb + normal * P.EPS


def cluster(scene, eye, spot, d, live=LIVE):
    normal, up = spot['normal'], spot['up']
    right = np.cross(up, normal)
    pitch = d * (1 + RIM) + GAP
    out = []
    fillers = FILLERS if live is LIVE else []
    for kind, col, row in live + fillers:
        c = spot['centre'] + right * col * pitch + up * row * pitch
        c = reseat(scene, c, normal)
        ok = c is not None
        if ok:
            support, vis = fit(scene, eye, c, normal, up, d)
            ok = support >= 0.75 and vis >= 0.9
        if not ok:
            if kind.startswith('FILLER'):
                continue
            return None
        out.append(dict(kind=kind, centre=c, normal=normal, up=up, diameter=d))
    return out


def place(vid):
    scene = P.Scene(vid)
    eye = P.eyes(vid)[0]
    spot = P.place_seat(scene, eye)
    if spot['mode'] != 'panel':
        return scene, eye, spot, None
    base = float(np.clip(2 * spot['distance'] * math.tan(math.radians(ANGULAR / 2)), MIN_D, MAX_D))
    right = np.cross(spot['up'], spot['normal'])
    # The flight-display spot sits where a screen fits; the cluster may sit a little higher or lower. Panels
    # where nothing fits near it get a wider search (further down, and off to either side).
    near = [(0.0, 0.0), (0.5, 0.0), (-0.5, 0.0)]
    wide = [(lift, shift) for lift in (-1.0, -1.5, -2.0, -2.5, 0.0, 1.0) for shift in (0.0, -0.75, 0.75, -1.5, 1.5)
            if (lift, shift) not in near]
    for offsets in (near, wide):
        for scale in (1.0, 0.85, 0.72, 0.6, 0.5):
            for live in LAYOUTS:
                for lift, shift in offsets:
                    s = dict(spot)
                    d = base * scale
                    c = reseat(scene, spot['centre'] + spot['up'] * lift * d + right * shift * d, spot['normal'])
                    if c is None:
                        continue
                    s['centre'] = c
                    gauges = cluster(scene, eye, s, d, live)
                    if gauges:
                        return scene, eye, spot, gauges
    return scene, eye, spot, None


def resource_block(gauges):
    return {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': [
        {'Seat': 0, 'Kind': g['kind'], 'Center': P.r5(g['centre']), 'Normal': P.r5(g['normal']), 'Up': P.r5(g['up']),
         'Diameter': round(g['diameter'], 4), 'Depth': DEPTH} for g in gauges]}


def sheet(entries, out):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    rows = len(entries)
    fig, axes = plt.subplots(rows, 1, figsize=(8, 4.6 * rows))
    axes = np.atleast_1d(axes)
    for ax, (vid, scene, eye, spot, gauges) in zip(axes, entries):
        results = []
        for g in gauges or []:
            # draw each dial as a small square "display" in the verification render
            results.append(dict(centre=g['centre'], normal=g['normal'], up=g['up'], width=g['diameter'] / P.HOUSING,
                                mode='panel', kind='RADAR' if g['kind'].startswith('FILLER') else 'PFD'))
        P.render_view(scene, results, eye, ax, down=spot['down'] if spot else 14.0,
                      title=f"{vid} ({len(gauges or [])} gauges, d {gauges[0]['diameter']:.3f})" if gauges else f'{vid} (none)')
    fig.tight_layout(); fig.savefig(out, dpi=80); plt.close(fig)


def main(argv):
    write = '--write' in argv
    out_sheet = argv[argv.index('--sheet') + 1] if '--sheet' in argv else None
    ids = [a for a in argv if not a.startswith('--') and a != out_sheet] or steam_cockpits()
    entries = []
    for vid in ids:
        scene, eye, spot, gauges = place(vid)
        entries.append((vid, scene, eye, spot, gauges))
        if not gauges:
            print(f'{vid:34s} no gauge cluster ({spot["mode"]})')
        else:
            kinds = [g['kind'].replace('FILLER_', '').lower() for g in gauges]
            print(f'{vid:34s} {len(gauges)} gauges d {gauges[0]["diameter"]:.3f} dist {spot["distance"]:.2f} {kinds}')
        if write:
            path = os.path.join(P.GEN, 'assets/berts_vehicle_pack/sbw/vehicles', vid + '.json')
            raw = open(path, encoding='utf-8').read()
            doc = json.loads(raw)
            if gauges:
                doc['CockpitGauges'] = resource_block(gauges)
            else:
                doc.pop('CockpitGauges', None)
            indent = 2 if raw.startswith('{\n') else None
            text = json.dumps(doc, indent=indent, ensure_ascii=False)
            open(path, 'w', encoding='utf-8').write(text + ('\n' if raw.endswith('\n') else ''))
    if out_sheet:
        sheet(entries, out_sheet)


if __name__ == '__main__':
    main(sys.argv[1:])
