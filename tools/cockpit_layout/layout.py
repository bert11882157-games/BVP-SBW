#!/usr/bin/env python3
"""Lays out every aircraft's cockpit instruments from its era preset (presets.json).

  * Gauge presets: the steam-gauge cluster of tools/cockpit_gauges/place.py; COLD_WAR_RADAR adds a CRT radar
    scope beside the cluster (right of it, else left, else above).
  * Display presets: a row of multi-function displays on the pilot's panel (left to right as listed: stores, PFD,
    radar), shrinking until the whole row fits the panel; other crew stations of glass cockpits get a PFD. A radar
    page is only fitted to aircraft that carry a radar.
  * Aircraft with a stores page get a planform outline (top view of the airframe without stores and landing gear,
    vehicle-local blocks) for the stores diagram.

Writes "FlightDisplays" and "CockpitGauges" into the asset vehicle JSON (and removes them when the preset has
none). Usage: python3 tools/cockpit_layout/layout.py [--write] [--sheet out.png] [id...]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'flight_display'))
import place as P  # noqa: E402  (flight displays)
import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location('gauge_place', os.path.join(HERE, '..', 'cockpit_gauges', 'place.py'))
G = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(G)

PRESETS = json.load(open(os.path.join(HERE, 'presets.json')))
GAP = 0.012


def fits(scene, eye, centre, normal, up, width):
    support, vis = P.panel_fit(scene, eye, centre, normal, up, width)
    return support >= 0.7 and vis >= 0.9


def row(scene, eye, spot, kinds):
    """Displays side by side around the panel spot; None if no size fits."""
    normal, up = spot['normal'], spot['up']
    right = np.cross(up, normal)
    n = len(kinds)
    for scale in (1.0, 0.9, 0.8, 0.7, 0.62, 0.55, 0.48):
        w = spot['width'] * scale
        pitch = w * P.HOUSING + GAP
        for lift in (0.0, 0.25, -0.25, 0.5):
            out = []
            for k, kind in enumerate(kinds):
                c = spot['centre'] + right * (k - (n - 1) / 2) * pitch + up * lift * w
                c = P.reseat(scene, c, normal)
                if c is None or not fits(scene, eye, c, normal, up, w):
                    break
                out.append(dict(kind=kind, centre=c, normal=normal, up=up, width=w))
            if len(out) == n:
                return out
    return None


def displays_for(vid, scene, preset):
    seat_eyes = P.eyes(vid)
    data = json.load(open(os.path.join(P.GEN, 'data/berts_vehicle_pack/sbw/vehicles', vid + '.json')))
    specs = [d for d in preset['displays'] if d['kind'] != 'RADAR' or P.has_radar(vid)]
    style = specs[0]['style'] if specs else 'LCD'
    seats = P.GLASS_COCKPITS.get(vid, [0])
    results = []
    for si, seat in enumerate(seats):
        eye = seat_eyes[seat]
        spot = P.place_seat(scene, eye)
        if spot['mode'] != 'panel':
            found = P.search_head(scene, np.array(data['Seats'][seat]['Position'], float), eye, seat_eyes[0])
            if found:
                spot = found
                eye = found['eye']
        kinds = [d['kind'] for d in specs] if si == 0 else ['PFD']
        placed = None
        # Drop pages from the edges (stores first, then radar) until the row fits.
        while kinds and placed is None:
            placed = row(scene, eye, spot, kinds) if spot['mode'] != 'floating' else None
            if placed is None:
                if 'STORES' in kinds and len(kinds) > 1:
                    kinds = [k for k in kinds if k != 'STORES']
                elif 'RADAR' in kinds and len(kinds) > 1:
                    kinds = [k for k in kinds if k != 'RADAR']
                else:
                    break
        dropped = [k for k in (['STORES', 'RADAR'] if si == 0 else []) if k in [d['kind'] for d in specs] and k not in kinds]
        if placed:
            # Pages that did not fit in the row: try them above, below or beside it at the row's size.
            for kind in dropped:
                extra = beside_row(scene, eye, placed, kind)
                if extra:
                    placed.append(extra)
                    print(f'  {vid}: {kind} placed off the row')
        if placed is None:
            spot.update(kind='PFD', seat=seat)
            placed = [dict(kind='PFD', centre=spot['centre'], normal=spot['normal'], up=spot['up'], width=spot['width'])]
            print(f'  {vid} seat {seat}: no panel fit, PFD at the search spot ({spot["mode"]})')
        for d in placed:
            d['seat'] = seat
            d['style'] = style
            d['eye'] = eye
        results += placed
    return results


def beside_row(scene, eye, placed, kind):
    """One more display next to a placed row: below its middle, above it, then off either end."""
    normal, up = placed[0]['normal'], placed[0]['up']
    right = np.cross(up, normal)
    cs = np.array([d['centre'] for d in placed])
    centre = cs.mean(axis=0)
    xs = (cs - centre) @ right
    w0 = placed[0]['width']
    for w in (w0, w0 * 0.85, w0 * 0.72, w0 * 0.6, w0 * 0.5):
        step = (w0 + w) / 2 * P.HOUSING + GAP
        for c in (centre - up * step, centre + up * step,
                  centre + right * (xs.max() + step), centre + right * (xs.min() - step),
                  centre - up * step + right * step / 2, centre - up * step - right * step / 2):
            c = P.reseat(scene, c, normal)
            clear = c is not None and all(
                abs((c - d['centre']) @ right) >= (d['width'] + w) / 2 * P.HOUSING
                or abs((c - d['centre']) @ up) >= (d['width'] + w) / 2 * P.HOUSING for d in placed)
            if clear and fits(scene, eye, c, normal, up, w):
                return dict(kind=kind, centre=c, normal=normal, up=up, width=w)
    return None


def radar_beside(scene, eye, gauges, spot, style):
    """CRT radar scope next to the four working gauges (right, left, then above); filler dials in its way go.
    Returns (radar display, remaining gauges) or (None, gauges)."""
    normal, up = gauges[0]['normal'], gauges[0]['up']
    right = np.cross(up, normal)
    d = gauges[0]['diameter']
    live = [g for g in gauges if not g['kind'].startswith('FILLER')]
    cs = np.array([g['centre'] for g in live])
    centre = cs.mean(axis=0)
    xs = (cs - centre) @ right
    ys = (cs - centre) @ up
    half = d / 2 * (1 + G.RIM)
    fillers = np.array([g['centre'] for g in gauges if g['kind'].startswith('FILLER')]).reshape(-1, 3)
    # Last resort: in place of filler dials (on one, or between two or four neighbours).
    inside = [f for f in fillers]
    for i in range(len(fillers)):
        for j in range(i + 1, len(fillers)):
            if np.linalg.norm(fillers[i] - fillers[j]) < d * 1.6:
                inside.append((fillers[i] + fillers[j]) / 2)
    if len(fillers) >= 4:
        for i in range(len(fillers)):
            near = sorted(range(len(fillers)), key=lambda k: np.linalg.norm(fillers[k] - fillers[i]))[:4]
            if max(np.linalg.norm(fillers[k] - fillers[i]) for k in near) < d * 1.8:
                inside.append(fillers[near].mean(axis=0))
    for w in (d * 2.4, d * 2.0, d * 1.7, d * 1.45, d * 1.25, d * 1.1):
        hw = w * P.HOUSING / 2
        for c in [centre + right * (xs.max() + half + GAP + hw), centre + right * (xs.min() - half - GAP - hw),
                  centre + up * (ys.max() + half + GAP + hw), centre - up * (-ys.min() + half + GAP + hw)] + inside:
            c = P.reseat(scene, c, normal)
            if c is None or not fits(scene, eye, c, normal, up, w):
                continue
            keep = []
            for g in gauges:
                rel = g['centre'] - c
                if abs(rel @ right) < hw + half and abs(rel @ up) < hw + half:
                    if not g['kind'].startswith('FILLER'):
                        break
                    continue
                keep.append(g)
            else:
                return dict(kind='RADAR', style=style, centre=c, normal=normal, up=up, width=w, seat=0, eye=eye), keep
    return None, gauges


def radar_shifted(scene, eye, spot, gauges, style):
    """Full panels: the four working dials as a 2 x 2 block pushed to one side, the scope beside them, and the
    original filler dials wherever they still fit."""
    d = gauges[0]['diameter']
    normal, up = gauges[0]['normal'], gauges[0]['up']
    right = np.cross(up, normal)
    pitch = d * (1 + G.RIM) + G.GAP
    centre = np.mean([g['centre'] for g in gauges if not g['kind'].startswith('FILLER')], axis=0)
    for scale in (1.0, 0.85, 0.72):
        dd = d * scale
        for shift in (-1.0, 1.0, -1.5, 1.5, -2.0, 2.0, -0.5, 0.5):
            for lift in (0.0, 0.5, -0.5):
                c = G.reseat(scene, centre + right * shift * pitch + up * lift * pitch, normal)
                if c is None:
                    continue
                s = dict(spot, centre=c, normal=normal, up=up)
                live = G.cluster(scene, eye, s, dd, G.LAYOUTS[1])
                if not live:
                    continue
                r, _ = radar_beside(scene, eye, live, s, style)
                if not r:
                    continue
                hw = r['width'] * P.HOUSING / 2
                keep = list(live)
                for g in gauges:
                    if not g['kind'].startswith('FILLER'):
                        continue
                    half = g['diameter'] / 2 * (1 + G.RIM)
                    rel = g['centre'] - r['centre']
                    if abs(rel @ right) < hw + half and abs(rel @ up) < hw + half:
                        continue
                    if any(np.linalg.norm(g['centre'] - k['centre']) < (g['diameter'] + k['diameter']) / 2 * (1 + G.RIM)
                           for k in keep):
                        continue
                    keep.append(dict(g, diameter=g['diameter']))
                return r, keep
    return None, gauges


# ------------------------------------------------------------------ planform

def planform(vid, cell=0.04):
    """Top-view outline polylines [[x, z, x, z, ...], ...] (vehicle-local blocks) of the airframe."""
    import matplotlib
    matplotlib.use('Agg')
    from matplotlib import path as mpath
    import matplotlib.pyplot as plt
    geo = json.load(open(os.path.join(P.GEN, 'assets/berts_vehicle_pack/custom_geo', vid + '.geo.json')))
    tris = []
    for bone in geo['minecraft:geometry'][0]['bones']:
        name = bone['name']
        if 'suspended' in name or 'landing_gear' in name or name.startswith('pilot_view_occluder'):
            continue
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        pos = np.array(pm['positions'], float)
        for poly in pm['polys']:
            idx = [v[0] for v in poly]
            for k in range(1, len(idx) - 1):
                tris.append(pos[[idx[0], idx[k], idx[k + 1]]])
    if not tris:
        return []
    t = np.array(tris) * np.array([1.0, 1.0, -1.0]) / 16.0     # vehicle-local blocks
    xz = t[:, :, [0, 2]]
    lo = xz.reshape(-1, 2).min(axis=0) - cell * 2
    hi = xz.reshape(-1, 2).max(axis=0) + cell * 2
    nx, nz = (np.ceil((hi - lo) / cell)).astype(int) + 1
    grid = np.zeros((nz, nx), bool)
    gx = lo[0] + np.arange(nx) * cell
    gz = lo[1] + np.arange(nz) * cell
    for tri in xz:
        a, b, c = tri
        mn = np.floor((tri.min(axis=0) - lo) / cell).astype(int)
        mx = np.ceil((tri.max(axis=0) - lo) / cell).astype(int)
        if (mx - mn).min() < 0:
            continue
        X, Z = np.meshgrid(gx[mn[0]:mx[0] + 1], gz[mn[1]:mx[1] + 1])
        pts = np.stack([X, Z], -1)
        d = (b - a)[0] * (c - a)[1] - (b - a)[1] * (c - a)[0]
        if abs(d) < 1e-12:
            continue
        v0 = pts - a
        l1 = (v0[..., 0] * (c - a)[1] - v0[..., 1] * (c - a)[0]) / d
        l2 = ((b - a)[0] * v0[..., 1] - (b - a)[1] * v0[..., 0]) / d
        inside = (l1 >= -0.3) & (l2 >= -0.3) & (l1 + l2 <= 1.3)
        grid[mn[1]:mx[1] + 1, mn[0]:mx[0] + 1] |= inside
    fig = plt.figure()
    cs = plt.contour(gx, gz, grid.astype(float), levels=[0.5])
    lines = []
    for seg in cs.allsegs[0]:
        if len(seg) < 8:
            continue
        simple = simplify(seg, cell * 0.8)
        lines.append([round(float(v), 3) for p in simple for v in p])
    plt.close(fig)
    return lines


def simplify(pts, tol):
    """Douglas-Peucker."""
    pts = np.asarray(pts)
    if len(pts) < 3:
        return pts
    a, b = pts[0], pts[-1]
    ab = b - a
    L = np.linalg.norm(ab)
    if L < 1e-9:
        d = np.linalg.norm(pts - a, axis=1)
    else:
        d = np.abs(ab[0] * (pts[:, 1] - a[1]) - ab[1] * (pts[:, 0] - a[0])) / L
    i = int(np.argmax(d))
    if d[i] > tol:
        left = simplify(pts[:i + 1], tol)
        right = simplify(pts[i:], tol)
        return np.vstack([left[:-1], right])
    return np.array([a, b])


# ------------------------------------------------------------------ main

def layout(vid):
    preset_name = PRESETS['aircraft'][vid]
    preset = PRESETS['presets'][preset_name]
    displays, gauges = [], None
    if preset['gauges']:
        scene, eye, spot, gauges = G.place(vid)
        radar = [d for d in preset['displays'] if d['kind'] == 'RADAR']
        if gauges and radar and P.has_radar(vid):
            r, gauges = radar_beside(scene, eye, gauges, spot, radar[0]['style'])
            if not r:
                r, gauges = radar_shifted(scene, eye, spot, gauges, radar[0]['style'])
            if r:
                displays.append(r)
            else:
                print(f'  {vid}: no room for the radar scope beside the gauges')
    else:
        scene = P.Scene(vid)
        displays = displays_for(vid, scene, preset)
    outline = planform(vid) if any(d['kind'] == 'STORES' for d in displays) else None
    return preset_name, scene, displays, gauges, outline


def display_block(displays, outline):
    block = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': [
        {'Seat': d['seat'], 'Center': P.r5(d['centre']), 'Normal': P.r5(d['normal']), 'Up': P.r5(d['up']),
         'Width': round(d['width'], 4), 'Depth': P.DEPTH, 'Kind': d['kind'], 'Style': d['style']} for d in displays]}
    if outline:
        block['Planform'] = outline
    return block


def main(argv):
    write = '--write' in argv
    out_sheet = argv[argv.index('--sheet') + 1] if '--sheet' in argv else None
    ids = [a for a in argv if not a.startswith('--') and a != out_sheet] or sorted(PRESETS['aircraft'])
    entries = []
    for vid in ids:
        preset, scene, displays, gauges, outline = layout(vid)
        kinds = [f"{d['kind']}/{d['style']}@{d['seat']}" for d in displays]
        print(f"{vid:34s} {preset:15s} displays {kinds} gauges {len(gauges or [])}"
              + (f" planform {len(outline)} lines" if outline else ''))
        entries.append((vid, scene, displays, gauges))
        if write:
            path = os.path.join(P.GEN, 'assets/berts_vehicle_pack/sbw/vehicles', vid + '.json')
            raw = open(path, encoding='utf-8').read()
            doc = json.loads(raw)
            if displays:
                doc['FlightDisplays'] = display_block(displays, outline)
            else:
                doc.pop('FlightDisplays', None)
            if gauges:
                doc['CockpitGauges'] = G.resource_block(gauges)
            else:
                doc.pop('CockpitGauges', None)
            indent = 2 if raw.startswith('{\n') else None
            text = json.dumps(doc, indent=indent, ensure_ascii=False)
            open(path, 'w', encoding='utf-8').write(text + ('\n' if raw.endswith('\n') else ''))
    if out_sheet:
        sheet(entries, out_sheet)


def sheet(entries, out):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(len(entries), 1, figsize=(8, 4.6 * len(entries)))
    axes = np.atleast_1d(axes)
    for ax, (vid, scene, displays, gauges) in zip(axes, entries):
        res = []
        for d in displays:
            res.append(dict(centre=d['centre'], normal=d['normal'], up=d['up'], width=d['width'], mode='panel',
                            kind='RADAR' if d['kind'] != 'PFD' else 'PFD'))
        for g in gauges or []:
            res.append(dict(centre=g['centre'], normal=g['normal'], up=g['up'], width=g['diameter'] / P.HOUSING,
                            mode='panel', kind='PFD'))
        eye = displays[0]['eye'] if displays and 'eye' in displays[0] else P.eyes(vid)[0]
        P.render_view(scene, res, eye, ax, down=18.0, title=f'{vid}')
    fig.tight_layout(); fig.savefig(out, dpi=80); plt.close(fig)


if __name__ == '__main__':
    main(sys.argv[1:])
